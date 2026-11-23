package io.github.drlacheheb.kufus.disk

const val DEFAULT_FORMAT_VOLUME_LABEL = "KUFUS"
private const val DEFAULT_SECTOR_SIZE_BYTES = 512
private const val MBR_MAX_SECTOR_COUNT = 0xFFFFFFFFL
private const val FAT32_MAX_LABEL_LENGTH = 11
private const val EXFAT_MAX_LABEL_LENGTH = 15
private const val NTFS_MAX_LABEL_LENGTH = 32
private const val EXT4_MAX_LABEL_BYTES = 16

private val disallowedFatLabelChars = setOf('*', '?', '/', '\\', '|', '<', '>', ':', '"', '.', ',', ';', '=', '[', ']', '+')
private val disallowedExfatLabelChars = setOf('*', '?', '/', '\\', '|', '<', '>', ':', '"')

/**
 * Resolves a host OS kind from a JVM `os.name`, platform name, or Konan target identifier.
 */
fun resolveHostOsKind(identifier: String): HostOsKind {
    val normalized = identifier.lowercase()
    return when {
        normalized.contains("win") || normalized.contains("mingw") -> HostOsKind.WINDOWS
        normalized.contains("mac") || normalized.contains("darwin") -> HostOsKind.MACOS
        else -> HostOsKind.LINUX
    }
}

/**
 * Returns filesystems offered on [hostOs] (`ext4` is Linux-only).
 */
fun supportedFilesystemsForHost(hostOs: HostOsKind): List<FilesystemType> =
    when (hostOs) {
        HostOsKind.LINUX -> listOf(FilesystemType.FAT32, FilesystemType.EXFAT, FilesystemType.NTFS, FilesystemType.EXT4)
        HostOsKind.WINDOWS -> listOf(FilesystemType.FAT32, FilesystemType.EXFAT, FilesystemType.NTFS)
        HostOsKind.MACOS -> listOf(FilesystemType.FAT32, FilesystemType.EXFAT, FilesystemType.NTFS)
    }

fun isFilesystemSupportedOnHost(
    filesystem: FilesystemType,
    hostOs: HostOsKind,
): Boolean = filesystem != FilesystemType.EXT4 || hostOs == HostOsKind.LINUX

fun maxMbrDiskBytes(sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE_BYTES): Long {
    val safeSector = sectorSizeBytes.coerceAtLeast(DEFAULT_SECTOR_SIZE_BYTES).toLong()
    return MBR_MAX_SECTOR_COUNT * safeSector
}

fun targetSystemLabel(scheme: PartitionScheme): String =
    when (scheme) {
        PartitionScheme.GPT -> "UEFI (non-CSM)"
        PartitionScheme.MBR -> "BIOS or UEFI"
    }

fun filesystemDisplayName(filesystem: FilesystemType): String =
    when (filesystem) {
        FilesystemType.FAT32 -> "FAT32"
        FilesystemType.EXFAT -> "exFAT"
        FilesystemType.NTFS -> "NTFS"
        FilesystemType.EXT4 -> "ext4"
    }

fun supportedClusterSizes(filesystem: FilesystemType): List<Int?> =
    when (filesystem) {
        FilesystemType.FAT32 -> listOf(null, 4096, 8192, 16_384, 32_768, 65_536)
        FilesystemType.EXFAT -> listOf(null, 4096, 8192, 16_384, 32_768, 65_536, 131_072)
        FilesystemType.NTFS -> listOf(null, 4096, 8192, 16_384, 32_768, 65_536)
        FilesystemType.EXT4 -> listOf(null, 4096)
    }

/**
 * Sanitizes [rawLabel] to the character set and byte/char bounds of [filesystem].
 */
fun sanitizeVolumeLabel(
    rawLabel: String,
    filesystem: FilesystemType,
): String {
    val printable = rawLabel.filter { ch -> ch.code >= 0x20 && ch.code != 0x7F }.trim()
    return when (filesystem) {
        FilesystemType.FAT32 -> sanitizeFat32Label(printable)
        FilesystemType.EXFAT ->
            printable
                .filterNot { it in disallowedExfatLabelChars }
                .take(EXFAT_MAX_LABEL_LENGTH)
                .trim()
        FilesystemType.NTFS -> printable.take(NTFS_MAX_LABEL_LENGTH)
        FilesystemType.EXT4 -> truncateUtf8ToBytes(printable, EXT4_MAX_LABEL_BYTES)
    }
}

fun partitionSchemeBlockReason(
    scheme: PartitionScheme,
    capacityBytes: Long,
    sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE_BYTES,
): String? {
    if (capacityBytes <= 0L) return "Target drive reports zero capacity."
    return if (scheme == PartitionScheme.MBR && capacityBytes > maxMbrDiskBytes(sectorSizeBytes)) {
        "MBR supports up to 2 TiB drives — select GPT partition scheme."
    } else {
        null
    }
}

fun formatBlockReason(
    scheme: PartitionScheme,
    filesystem: FilesystemType,
    hostOs: HostOsKind,
    drive: UsbDrive?,
    sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE_BYTES,
): String? {
    if (drive == null) return "Plug in and select a target USB drive."
    if (!isFilesystemSupportedOnHost(filesystem, hostOs)) {
        return "${filesystemDisplayName(filesystem)} formatting is only supported on Linux hosts."
    }
    partitionSchemeBlockReason(scheme, drive.sizeBytes, sectorSizeBytes)?.let { return it }
    if (filesystem == FilesystemType.FAT32 && drive.sizeBytes > maxMbrDiskBytes(sectorSizeBytes)) {
        return "FAT32 partitions cannot exceed 2 TiB — select exFAT or NTFS."
    }
    return null
}

/**
 * Returns filesystems suitable for UEFI Windows installer preparation on [hostOs].
 */
fun supportedWindowsFilesystemsForHost(hostOs: HostOsKind): List<FilesystemType> =
    when (hostOs) {
        HostOsKind.LINUX, HostOsKind.WINDOWS -> listOf(FilesystemType.NTFS, FilesystemType.FAT32)
        HostOsKind.MACOS -> listOf(FilesystemType.FAT32)
    }

/**
 * Picks the default target filesystem for a Windows ISO on [hostOs].
 */
fun defaultWindowsFilesystemForHost(
    hostOs: HostOsKind,
    metadata: WindowsIsoMetadata?,
): FilesystemType =
    when (hostOs) {
        HostOsKind.MACOS -> FilesystemType.FAT32
        HostOsKind.LINUX, HostOsKind.WINDOWS ->
            if (metadata?.isSplitRequiredForFat32 == false) FilesystemType.FAT32 else FilesystemType.NTFS
    }

/**
 * Validates whether a Windows installer ISO can be prepared onto [drive] using [scheme] and [filesystem].
 */
fun windowsInstallBlockReason(
    scheme: PartitionScheme,
    filesystem: FilesystemType,
    hostOs: HostOsKind,
    drive: UsbDrive?,
    image: ImageInfo?,
    sectorSizeBytes: Int = DEFAULT_SECTOR_SIZE_BYTES,
): String? {
    if (drive == null) return "Plug in and select a target USB drive."
    if (filesystem !in supportedWindowsFilesystemsForHost(hostOs)) {
        return "${filesystemDisplayName(filesystem)} is not supported for Windows installer media on ${hostOs.name.lowercase()}."
    }
    partitionSchemeBlockReason(scheme, drive.sizeBytes, sectorSizeBytes)?.let { return it }
    if (filesystem == FilesystemType.FAT32 && drive.sizeBytes > maxMbrDiskBytes(sectorSizeBytes)) {
        return "FAT32 partitions cannot exceed 2 TiB — select NTFS."
    }
    val meta = image?.windowsMetadata
    if (meta != null && meta.totalPayloadBytes > drive.sizeBytes) {
        return "Windows ISO payload (${formatByteSize(meta.totalPayloadBytes)}) exceeds selected drive capacity."
    }
    if (cannotSplitWindowsInstallOnFat32(filesystem, meta)) {
        return "Image contains a solid >4 GiB install archive (${meta?.installImagePath}) that cannot be split onto FAT32."
    }
    return null
}

private fun cannotSplitWindowsInstallOnFat32(
    filesystem: FilesystemType,
    meta: WindowsIsoMetadata?,
): Boolean {
    if (filesystem != FilesystemType.FAT32 || meta == null) return false
    return meta.isSplitRequiredForFat32 && !meta.isWimSplittable
}

private fun sanitizeFat32Label(printable: String): String {
    val clean =
        buildString(FAT32_MAX_LABEL_LENGTH) {
            for (ch in printable) {
                val upper = if (ch in 'a'..'z') ch.uppercaseChar() else ch
                if (upper.code in 0x20..0x7E && upper !in disallowedFatLabelChars) {
                    append(upper)
                    if (length >= FAT32_MAX_LABEL_LENGTH) break
                }
            }
        }
    return clean.trim()
}

private fun truncateUtf8ToBytes(
    text: String,
    maxBytes: Int,
): String {
    var usedBytes = 0
    return buildString(text.length.coerceAtMost(maxBytes)) {
        for (ch in text) {
            val charBytes = ch.toString().encodeToByteArray().size
            if (usedBytes + charBytes > maxBytes) break
            append(ch)
            usedBytes += charBytes
        }
    }
}

/**
 * Resolves Partition 1 device node for a whole Linux disk path (e.g. `/dev/sdb` -> `/dev/sdb1`, `/dev/loop0` -> `/dev/loop0p1`).
 */
fun resolveLinuxPartitionNode(
    canonicalDiskPath: String,
    partitionIndex: Int = 1,
): String {
    val needsP = canonicalDiskPath.lastOrNull()?.isDigit() == true
    return if (needsP) "${canonicalDiskPath}p$partitionIndex" else "$canonicalDiskPath$partitionIndex"
}
