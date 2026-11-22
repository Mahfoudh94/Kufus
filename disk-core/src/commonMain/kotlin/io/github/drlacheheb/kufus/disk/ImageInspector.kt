package io.github.drlacheheb.kufus.disk

private const val SECTOR_512 = 512
private const val MBR_SIGNATURE_OFFSET = 510
private const val MBR_SIG_BYTE_0 = 0x55
private const val MBR_SIG_BYTE_1 = 0xAA
private const val ISO_SECTOR_SIZE = 2048
private const val ISO_PVD_OFFSET = 16L * ISO_SECTOR_SIZE
private const val ISO_SECONDARY_VD_OFFSET = 17L * ISO_SECTOR_SIZE
private const val ISO_VD_MAX_SEARCH_SECTORS = 6
private const val ISO_ROOT_RECORD_OFFSET = 156
private const val ISO_EXTENT_LBA_OFFSET = 2
private const val ISO_DATA_LENGTH_OFFSET = 10
private const val ISO_RECORD_FLAGS_OFFSET = 25
private const val ISO_NAME_LEN_OFFSET = 32
private const val ISO_NAME_OFFSET = 33
private const val ISO_DIR_FLAG = 0x02
private const val MAX_ISO_DIR_READ_BYTES = 65_536
private const val HEADER_READ_BYTES = 1024
private const val GZIP_MIN_BYTES = 18L
private const val MAX_32BIT_BYTES = 4_294_967_296L
private const val ZSTD_MAGIC_BYTES = 4
private const val GZIP_MAGIC_0 = 0x1F
private const val GZIP_MAGIC_1 = 0x8B
private const val GZIP_DEFLATE_METHOD = 0x08
private const val BYTE_MASK = 0xFF
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val SHIFT_32 = 32
private const val SHIFT_40 = 40
private const val SHIFT_48 = 48
private const val SHIFT_56 = 56

private val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
private val gptSignature = "EFI PART".encodeToByteArray()
private val isoSignature = "CD001".encodeToByteArray()
private val udfBeaSignature = "BEA01".encodeToByteArray()
private val udfNsr2Signature = "NSR02".encodeToByteArray()
private val udfNsr3Signature = "NSR03".encodeToByteArray()
private val elToritoSignature = "EL TORITO SPECIFICATION".encodeToByteArray()
private val windowsInstallWimNames = setOf("INSTALL.WIM", "INSTALL.ESD", "INSTALL.SWM")

interface SeekableByteReader {
    fun sizeBytes(): Long

    fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray
}

fun inspectImage(
    path: String,
    reader: SeekableByteReader,
): ImageInfo {
    val totalBytes = reader.sizeBytes()
    if (totalBytes <= 0L) {
        return ImageInfo(path = path, sizeBytes = 0L, format = ImageFormat.UNKNOWN)
    }
    val initialHeader = reader.readAt(offset = 0L, length = minOf(HEADER_READ_BYTES.toLong(), totalBytes).toInt())
    val compression = detectCompressionFormat(initialHeader)
    if (compression != CompressionFormat.NONE) {
        val hintBytes = readUncompressedSizeHint(compression, initialHeader, totalBytes, reader)
        return ImageInfo(
            path = path,
            sizeBytes = totalBytes,
            format = ImageFormat.COMPRESSED_IMAGE,
            compression = compression,
            uncompressedSizeHintBytes = hintBytes,
        )
    }
    val format = classifyUncompressedImage(initialHeader, totalBytes, reader)
    return ImageInfo(path = path, sizeBytes = totalBytes, format = format)
}

private fun classifyUncompressedImage(
    initialHeader: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): ImageFormat {
    val isoPvd = readIsoPrimaryVolumeDescriptor(totalBytes, reader)
    if (isoPvd != null) {
        return classifyIso9660Image(initialHeader, isoPvd, totalBytes, reader)
    }
    if (hasUdfDescriptors(totalBytes, reader)) {
        return ImageFormat.WINDOWS_ISO
    }
    if (hasMbrSignature(initialHeader) || hasGptSignature(initialHeader)) {
        return ImageFormat.RAW_IMAGE
    }
    return ImageFormat.UNKNOWN
}

private fun classifyIso9660Image(
    initialHeader: ByteArray,
    pvd: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): ImageFormat {
    if (hasWindowsInstallImage(pvd, totalBytes, reader)) {
        return ImageFormat.WINDOWS_ISO
    }
    val bootableIso =
        hasMbrSignature(initialHeader) ||
            hasGptSignature(initialHeader) ||
            hasElToritoBootRecord(totalBytes, reader)
    return if (bootableIso) ImageFormat.HYBRID_ISO else ImageFormat.UNKNOWN
}

private fun detectCompressionFormat(header: ByteArray): CompressionFormat =
    when {
        isGzipHeader(header) -> CompressionFormat.GZIP
        startsWithMagic(header, xzMagic) -> CompressionFormat.XZ
        startsWithMagic(header, zstdMagic) -> CompressionFormat.ZSTD
        else -> CompressionFormat.NONE
    }

private fun isGzipHeader(header: ByteArray): Boolean =
    header.size >= 3 &&
        (header[0].toInt() and BYTE_MASK) == GZIP_MAGIC_0 &&
        (header[1].toInt() and BYTE_MASK) == GZIP_MAGIC_1 &&
        (header[2].toInt() and BYTE_MASK) == GZIP_DEFLATE_METHOD

private fun startsWithMagic(
    bytes: ByteArray,
    magic: ByteArray,
    offset: Int = 0,
): Boolean {
    if (bytes.size < offset + magic.size) return false
    return magic.indices.all { index -> bytes[offset + index] == magic[index] }
}

private fun readUncompressedSizeHint(
    compression: CompressionFormat,
    header: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): Long? =
    when (compression) {
        CompressionFormat.GZIP -> readGzipSizeHint(totalBytes, reader)
        CompressionFormat.ZSTD -> readZstdContentSize(header)
        CompressionFormat.XZ, CompressionFormat.NONE -> null
    }

private fun readGzipSizeHint(
    totalBytes: Long,
    reader: SeekableByteReader,
): Long? {
    if (totalBytes < GZIP_MIN_BYTES || totalBytes >= MAX_32BIT_BYTES) return null
    val trailer = reader.readAt(offset = totalBytes - 4L, length = 4)
    if (trailer.size < 4) return null
    val isize = readUInt32Le(trailer, 0)
    return isize.takeIf { it > 0L && it >= totalBytes }
}

private fun readZstdContentSize(header: ByteArray): Long? {
    if (header.size <= ZSTD_MAGIC_BYTES) return null
    val descriptor = header[ZSTD_MAGIC_BYTES].toInt() and BYTE_MASK
    val fcsFlag = (descriptor ushr 6) and 0x03
    val singleSegment = ((descriptor ushr 5) and 0x01) == 1
    val dictIdFlag = descriptor and 0x03
    if (dictIdFlag != 0) return null
    val fcsOffset = ZSTD_MAGIC_BYTES + 1 + if (singleSegment) 0 else 1
    return parseZstdFcsValue(header, fcsOffset, fcsFlag, singleSegment)
}

private fun parseZstdFcsValue(
    header: ByteArray,
    offset: Int,
    fcsFlag: Int,
    singleSegment: Boolean,
): Long? =
    when (fcsFlag) {
        0 -> if (singleSegment && header.size >= offset + 1) (header[offset].toInt() and BYTE_MASK).toLong() else null
        1 -> if (header.size >= offset + 2) readUInt16Le(header, offset) + 256L else null
        2 -> if (header.size >= offset + 4) readUInt32Le(header, offset) else null
        else -> if (header.size >= offset + 8) readUInt64Le(header, offset) else null
    }

private fun readIsoPrimaryVolumeDescriptor(
    totalBytes: Long,
    reader: SeekableByteReader,
): ByteArray? {
    if (totalBytes < ISO_PVD_OFFSET + ISO_SECTOR_SIZE) return null
    val sector = reader.readAt(offset = ISO_PVD_OFFSET, length = ISO_SECTOR_SIZE)
    if (sector.size < ISO_SECTOR_SIZE) return null
    val isPvd = (sector[0].toInt() and BYTE_MASK) == 1 && startsWithMagic(sector, isoSignature, offset = 1)
    return if (isPvd) sector else null
}

private fun hasElToritoBootRecord(
    totalBytes: Long,
    reader: SeekableByteReader,
): Boolean {
    for (sectorIndex in 0 until ISO_VD_MAX_SEARCH_SECTORS) {
        val offset = ISO_SECONDARY_VD_OFFSET + sectorIndex.toLong() * ISO_SECTOR_SIZE
        if (totalBytes < offset + ISO_SECTOR_SIZE) return false
        val sector = reader.readAt(offset = offset, length = ISO_SECTOR_SIZE)
        if (sector.size < ISO_SECTOR_SIZE) return false
        val typeCode = sector[0].toInt() and BYTE_MASK
        if (typeCode == 0 && startsWithMagic(sector, isoSignature, offset = 1)) {
            if (startsWithMagic(sector, elToritoSignature, offset = 7)) return true
        }
        if (typeCode == BYTE_MASK) return false
    }
    return false
}

private fun hasUdfDescriptors(
    totalBytes: Long,
    reader: SeekableByteReader,
): Boolean {
    if (totalBytes < ISO_SECONDARY_VD_OFFSET + ISO_SECTOR_SIZE) return false
    val first = reader.readAt(offset = ISO_PVD_OFFSET, length = 8)
    val second = reader.readAt(offset = ISO_SECONDARY_VD_OFFSET, length = 8)
    val hasBea = startsWithMagic(first, udfBeaSignature, offset = 1)
    val hasNsr =
        startsWithMagic(second, udfNsr2Signature, offset = 1) ||
            startsWithMagic(second, udfNsr3Signature, offset = 1)
    return hasBea && hasNsr
}

private fun hasWindowsInstallImage(
    pvd: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): Boolean {
    val rootExtentLba = readUInt32Le(pvd, ISO_ROOT_RECORD_OFFSET + ISO_EXTENT_LBA_OFFSET)
    val rootDataLen = readUInt32Le(pvd, ISO_ROOT_RECORD_OFFSET + ISO_DATA_LENGTH_OFFSET)
    val sourcesEntry = findIsoDirectoryEntry(rootExtentLba, rootDataLen, totalBytes, reader, "SOURCES") ?: return false
    if (!sourcesEntry.isDirectory) return false
    val sourcesListing = readIsoDirectoryEntries(sourcesEntry.extentLba, sourcesEntry.dataLength, totalBytes, reader)
    return sourcesListing.any { entry -> !entry.isDirectory && entry.name in windowsInstallWimNames }
}

private data class IsoDirEntry(
    val name: String,
    val extentLba: Long,
    val dataLength: Long,
    val isDirectory: Boolean,
)

private fun findIsoDirectoryEntry(
    extentLba: Long,
    dataLength: Long,
    totalBytes: Long,
    reader: SeekableByteReader,
    targetName: String,
): IsoDirEntry? =
    readIsoDirectoryEntries(extentLba, dataLength, totalBytes, reader)
        .firstOrNull { entry -> entry.name == targetName }

private fun readIsoDirectoryEntries(
    extentLba: Long,
    dataLength: Long,
    totalBytes: Long,
    reader: SeekableByteReader,
): List<IsoDirEntry> {
    if (extentLba <= 0L || dataLength <= 0L) return emptyList()
    val startOffset = extentLba * ISO_SECTOR_SIZE
    if (startOffset >= totalBytes) return emptyList()
    val maxToRead = minOf(dataLength, MAX_ISO_DIR_READ_BYTES.toLong(), totalBytes - startOffset).toInt()
    val buffer = reader.readAt(offset = startOffset, length = maxToRead)
    return parseIsoDirectoryBuffer(buffer)
}

private fun parseIsoDirectoryBuffer(buffer: ByteArray): List<IsoDirEntry> =
    buildList {
        var offset = 0
        while (offset < buffer.size) {
            val recordLen = buffer[offset].toInt() and BYTE_MASK
            if (recordLen == 0) {
                offset = ((offset / ISO_SECTOR_SIZE) + 1) * ISO_SECTOR_SIZE
            } else {
                parseSingleIsoRecord(buffer, offset, recordLen)?.let(::add)
                offset += recordLen
            }
        }
    }

private fun parseSingleIsoRecord(
    buffer: ByteArray,
    offset: Int,
    recordLen: Int,
): IsoDirEntry? {
    if (recordLen < ISO_NAME_OFFSET + 1 || offset + recordLen > buffer.size) return null
    val nameLen = buffer[offset + ISO_NAME_LEN_OFFSET].toInt() and BYTE_MASK
    if (nameLen <= 0 || offset + ISO_NAME_OFFSET + nameLen > offset + recordLen) return null
    val rawName = buffer.decodeToString(startIndex = offset + ISO_NAME_OFFSET, endIndex = offset + ISO_NAME_OFFSET + nameLen)
    if (nameLen == 1 && (rawName[0] == '\u0000' || rawName[0] == '\u0001')) return null
    val normalizedName = rawName.substringBefore(';').uppercase()
    val flags = buffer[offset + ISO_RECORD_FLAGS_OFFSET].toInt() and BYTE_MASK
    return IsoDirEntry(
        name = normalizedName,
        extentLba = readUInt32Le(buffer, offset + ISO_EXTENT_LBA_OFFSET),
        dataLength = readUInt32Le(buffer, offset + ISO_DATA_LENGTH_OFFSET),
        isDirectory = (flags and ISO_DIR_FLAG) != 0,
    )
}

private fun hasMbrSignature(header: ByteArray): Boolean =
    header.size >= SECTOR_512 &&
        (header[MBR_SIGNATURE_OFFSET].toInt() and BYTE_MASK) == MBR_SIG_BYTE_0 &&
        (header[MBR_SIGNATURE_OFFSET + 1].toInt() and BYTE_MASK) == MBR_SIG_BYTE_1

private fun hasGptSignature(header: ByteArray): Boolean = startsWithMagic(header, gptSignature, offset = SECTOR_512)

private fun readUInt16Le(
    bytes: ByteArray,
    offset: Int,
): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl SHIFT_8)

private fun readUInt32Le(
    bytes: ByteArray,
    offset: Int,
): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl SHIFT_8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl SHIFT_16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl SHIFT_24)

private fun readUInt64Le(
    bytes: ByteArray,
    offset: Int,
): Long =
    readUInt32Le(bytes, offset) or
        ((bytes[offset + 4].toLong() and 0xFFL) shl SHIFT_32) or
        ((bytes[offset + 5].toLong() and 0xFFL) shl SHIFT_40) or
        ((bytes[offset + 6].toLong() and 0xFFL) shl SHIFT_48) or
        ((bytes[offset + 7].toLong() and 0xFFL) shl SHIFT_56)
