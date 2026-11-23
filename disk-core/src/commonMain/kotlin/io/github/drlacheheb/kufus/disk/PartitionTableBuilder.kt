package io.github.drlacheheb.kufus.disk

private const val SECTOR_512 = 512
private const val SECTOR_4096 = 4096
private const val ONE_MIB_BYTES = 1_048_576L
private const val MIN_PARTITION_SECTORS = 2048L
private const val GPT_ENTRY_COUNT = 128
private const val GPT_ENTRY_BYTES = 128
private const val GPT_ENTRIES_TOTAL_BYTES = GPT_ENTRY_COUNT * GPT_ENTRY_BYTES
private const val GPT_HEADER_BYTES = 92
private const val MBR_ENTRY_OFFSET = 446
private const val MBR_DISK_SIGNATURE_OFFSET = 440
private const val MBR_SIGNATURE_OFFSET = 510
private const val MBR_MAX_32BIT_LBA = 0xFFFFFFFFL
private const val CHS_SECTORS_PER_TRACK = 63L
private const val CHS_HEADS = 255L
private const val CHS_MAX_LBA = 1023L * CHS_HEADS * CHS_SECTORS_PER_TRACK

private val msBasicDataGuidBytes =
    byteArrayOf(
        0xA2.toByte(),
        0xA0.toByte(),
        0xD0.toByte(),
        0xEB.toByte(),
        0xE5.toByte(),
        0xB9.toByte(),
        0x33,
        0x44,
        0x87.toByte(),
        0xC0.toByte(),
        0x68,
        0xB6.toByte(),
        0xB7.toByte(),
        0x26,
        0x99.toByte(),
        0xC7.toByte(),
    )

private val linuxFilesystemGuidBytes =
    byteArrayOf(
        0xAF.toByte(),
        0x3D,
        0xC6.toByte(),
        0x0F,
        0x83.toByte(),
        0x84.toByte(),
        0x72,
        0x47,
        0x8E.toByte(),
        0x79,
        0x3D,
        0x69,
        0xD8.toByte(),
        0x47,
        0x7D,
        0xE4.toByte(),
    )

data class PartitionSpan(
    val sectorSizeBytes: Int,
    val totalSectors: Long,
    val startLba: Long,
    val lastLba: Long,
) {
    val sectorCount: Long
        get() = lastLba - startLba + 1L

    val startByteOffset: Long
        get() = startLba * sectorSizeBytes.toLong()

    val sizeBytes: Long
        get() = sectorCount * sectorSizeBytes.toLong()
}

data class GptBinaryLayout(
    val protectiveMbrSector: ByteArray,
    val primaryHeaderSector: ByteArray,
    val primaryEntriesBytes: ByteArray,
    val backupEntriesBytes: ByteArray,
    val backupHeaderSector: ByteArray,
    val backupEntriesOffsetBytes: Long,
    val backupHeaderOffsetBytes: Long,
)

/**
 * Generates deterministic MBR and GPT partition table structures for single-partition USB drives.
 */
object PartitionTableBuilder {
    fun computeSinglePartitionSpan(
        diskSizeBytes: Long,
        sectorSizeBytes: Int,
        scheme: PartitionScheme,
    ): PartitionSpan {
        validatePartitionSectorSize(sectorSizeBytes)
        val totalSectors = diskSizeBytes / sectorSizeBytes.toLong()
        if (scheme == PartitionScheme.MBR && totalSectors > MBR_MAX_32BIT_LBA) {
            throw FlashStepException(
                code = "DISK_TOO_LARGE_FOR_MBR",
                message = "Drive capacity ($diskSizeBytes bytes) exceeds MBR 2 TiB limit — use GPT.",
            )
        }
        val alignmentSectors = (ONE_MIB_BYTES / sectorSizeBytes.toLong()).coerceAtLeast(1L)
        val entriesSectors = (GPT_ENTRIES_TOTAL_BYTES / sectorSizeBytes).toLong()
        val firstUsableLba = 2L + entriesSectors
        val startLba = alignmentSectors.coerceAtLeast(firstUsableLba)
        val lastLba =
            when (scheme) {
                PartitionScheme.MBR -> totalSectors - 1L
                PartitionScheme.GPT -> totalSectors - 2L - entriesSectors
            }
        if (lastLba - startLba + 1L < MIN_PARTITION_SECTORS) {
            throw FlashStepException(
                code = "DISK_TOO_SMALL",
                message = "Target drive capacity ($diskSizeBytes bytes) is too small to partition cleanly.",
            )
        }
        return PartitionSpan(
            sectorSizeBytes = sectorSizeBytes,
            totalSectors = totalSectors,
            startLba = startLba,
            lastLba = lastLba,
        )
    }

    fun buildMbrSector(
        span: PartitionSpan,
        filesystem: FilesystemType,
        diskSignature: Int = 0x4B554655,
    ): ByteArray {
        if (span.totalSectors > MBR_MAX_32BIT_LBA || span.sectorCount > MBR_MAX_32BIT_LBA) {
            throw FlashStepException(
                code = "DISK_TOO_LARGE_FOR_MBR",
                message = "Partition spans exceed 32-bit MBR limits.",
            )
        }
        val sector = ByteArray(span.sectorSizeBytes)
        writeLeInt(sector, MBR_DISK_SIGNATURE_OFFSET, diskSignature)
        val typeCode = mbrPartitionTypeByte(filesystem)
        writeMbrEntry(
            target = sector,
            entryOffset = MBR_ENTRY_OFFSET,
            typeCode = typeCode,
            startLba = span.startLba,
            sectorCount = span.sectorCount,
        )
        sector[MBR_SIGNATURE_OFFSET] = 0x55
        sector[MBR_SIGNATURE_OFFSET + 1] = 0xAA.toByte()
        return sector
    }

    fun buildGptLayout(
        span: PartitionSpan,
        filesystem: FilesystemType,
        partitionName: String = DEFAULT_FORMAT_VOLUME_LABEL,
        diskGuid: ByteArray = deterministicGuid(seed = span.totalSectors xor 0x4B554655L),
        partitionGuid: ByteArray = deterministicGuid(seed = span.totalSectors xor 0x50415254L),
    ): GptBinaryLayout {
        val entriesSectors = (GPT_ENTRIES_TOTAL_BYTES / span.sectorSizeBytes).toLong()
        val firstUsableLba = 2L + entriesSectors
        val lastUsableLba = span.totalSectors - 2L - entriesSectors
        val alternateLba = span.totalSectors - 1L
        val backupEntriesStartLba = span.totalSectors - 1L - entriesSectors

        val entries = ByteArray(GPT_ENTRIES_TOTAL_BYTES)
        val typeGuid = if (filesystem == FilesystemType.EXT4) linuxFilesystemGuidBytes else msBasicDataGuidBytes
        typeGuid.copyInto(entries, destinationOffset = 0)
        partitionGuid.copyInto(entries, destinationOffset = 16, startIndex = 0, endIndex = 16)
        writeLeLong(entries, 32, span.startLba)
        writeLeLong(entries, 40, span.lastLba)
        writeLeLong(entries, 48, 0L)
        writeUtf16PartitionName(entries, offset = 56, name = partitionName.ifBlank { "Kufus Data" })

        val entriesCrc = Crc32.compute(entries, 0, entries.size).toInt()
        val protectiveMbr = ByteArray(span.sectorSizeBytes)
        val protectiveCount = (span.totalSectors - 1L).coerceAtMost(MBR_MAX_32BIT_LBA)
        writeMbrEntry(protectiveMbr, MBR_ENTRY_OFFSET, typeCode = 0xEE, startLba = 1L, sectorCount = protectiveCount)
        protectiveMbr[MBR_SIGNATURE_OFFSET] = 0x55
        protectiveMbr[MBR_SIGNATURE_OFFSET + 1] = 0xAA.toByte()

        val primaryHeader =
            buildGptHeaderSector(
                sectorSizeBytes = span.sectorSizeBytes,
                myLba = 1L,
                alternateLba = alternateLba,
                firstUsableLba = firstUsableLba,
                lastUsableLba = lastUsableLba,
                diskGuid = diskGuid,
                partitionEntryLba = 2L,
                entriesCrc = entriesCrc,
            )
        val backupHeader =
            buildGptHeaderSector(
                sectorSizeBytes = span.sectorSizeBytes,
                myLba = alternateLba,
                alternateLba = 1L,
                firstUsableLba = firstUsableLba,
                lastUsableLba = lastUsableLba,
                diskGuid = diskGuid,
                partitionEntryLba = backupEntriesStartLba,
                entriesCrc = entriesCrc,
            )

        return GptBinaryLayout(
            protectiveMbrSector = protectiveMbr,
            primaryHeaderSector = primaryHeader,
            primaryEntriesBytes = entries,
            backupEntriesBytes = entries.copyOf(),
            backupHeaderSector = backupHeader,
            backupEntriesOffsetBytes = backupEntriesStartLba * span.sectorSizeBytes.toLong(),
            backupHeaderOffsetBytes = alternateLba * span.sectorSizeBytes.toLong(),
        )
    }

    private fun mbrPartitionTypeByte(filesystem: FilesystemType): Int =
        when (filesystem) {
            FilesystemType.FAT32 -> 0x0C
            FilesystemType.EXFAT, FilesystemType.NTFS -> 0x07
            FilesystemType.EXT4 -> 0x83
        }

    private fun validatePartitionSectorSize(sectorSizeBytes: Int) {
        if (sectorSizeBytes < SECTOR_512 || sectorSizeBytes > SECTOR_4096 || (sectorSizeBytes and (sectorSizeBytes - 1)) != 0) {
            throw FlashStepException(
                code = "INVALID_SECTOR_SIZE",
                message = "Unsupported sector size $sectorSizeBytes bytes",
            )
        }
    }

    private fun buildGptHeaderSector(
        sectorSizeBytes: Int,
        myLba: Long,
        alternateLba: Long,
        firstUsableLba: Long,
        lastUsableLba: Long,
        diskGuid: ByteArray,
        partitionEntryLba: Long,
        entriesCrc: Int,
    ): ByteArray {
        val header = ByteArray(sectorSizeBytes)
        "EFI PART".encodeToByteArray().copyInto(header, destinationOffset = 0)
        writeLeInt(header, 8, 0x00010000)
        writeLeInt(header, 12, GPT_HEADER_BYTES)
        writeLeInt(header, 16, 0)
        writeLeLong(header, 24, myLba)
        writeLeLong(header, 32, alternateLba)
        writeLeLong(header, 40, firstUsableLba)
        writeLeLong(header, 48, lastUsableLba)
        diskGuid.copyInto(header, destinationOffset = 56, startIndex = 0, endIndex = 16)
        writeLeLong(header, 72, partitionEntryLba)
        writeLeInt(header, 80, GPT_ENTRY_COUNT)
        writeLeInt(header, 84, GPT_ENTRY_BYTES)
        writeLeInt(header, 88, entriesCrc)
        val headerCrc = Crc32.compute(header, 0, GPT_HEADER_BYTES).toInt()
        writeLeInt(header, 16, headerCrc)
        return header
    }

    private fun writeMbrEntry(
        target: ByteArray,
        entryOffset: Int,
        typeCode: Int,
        startLba: Long,
        sectorCount: Long,
    ) {
        target[entryOffset] = 0x00
        writeChsAddress(target, entryOffset + 1, startLba)
        target[entryOffset + 4] = typeCode.toByte()
        writeChsAddress(target, entryOffset + 5, startLba + sectorCount - 1L)
        writeLeInt(target, entryOffset + 8, startLba.toInt())
        writeLeInt(target, entryOffset + 12, sectorCount.toInt())
    }

    private fun writeChsAddress(
        target: ByteArray,
        offset: Int,
        lba: Long,
    ) {
        if (lba >= CHS_MAX_LBA) {
            target[offset] = 0xFE.toByte()
            target[offset + 1] = 0xFF.toByte()
            target[offset + 2] = 0xFF.toByte()
            return
        }
        val cylinder = lba / (CHS_HEADS * CHS_SECTORS_PER_TRACK)
        val remainder = lba % (CHS_HEADS * CHS_SECTORS_PER_TRACK)
        val head = remainder / CHS_SECTORS_PER_TRACK
        val sector = (remainder % CHS_SECTORS_PER_TRACK) + 1L
        target[offset] = head.toByte()
        target[offset + 1] = (((cylinder shr 2) and 0xC0) or (sector and 0x3F)).toByte()
        target[offset + 2] = (cylinder and 0xFF).toByte()
    }

    private fun writeUtf16PartitionName(
        target: ByteArray,
        offset: Int,
        name: String,
    ) {
        val clipped = name.take(35)
        for (i in clipped.indices) {
            val code = clipped[i].code
            target[offset + i * 2] = (code and 0xFF).toByte()
            target[offset + i * 2 + 1] = ((code ushr 8) and 0xFF).toByte()
        }
    }

    private fun deterministicGuid(seed: Long): ByteArray {
        val guid = ByteArray(16)
        var state = seed xor 0x9E3779B97F4A7C15uL.toLong()
        for (i in 0 until 16) {
            state = state * 6364136223846793005L + 1442695040888963407L
            guid[i] = ((state ushr 24) and 0xFFL).toByte()
        }
        guid[7] = ((guid[7].toInt() and 0x0F) or 0x40).toByte()
        guid[8] = ((guid[8].toInt() and 0x3F) or 0x80).toByte()
        return guid
    }
}

internal fun writeLeShort(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
}

internal fun writeLeInt(
    target: ByteArray,
    offset: Int,
    value: Int,
) {
    target[offset] = (value and 0xFF).toByte()
    target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
    target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
}

internal fun writeLeLong(
    target: ByteArray,
    offset: Int,
    value: Long,
) {
    writeLeInt(target, offset, (value and 0xFFFFFFFFL).toInt())
    writeLeInt(target, offset + 4, ((value ushr 32) and 0xFFFFFFFFL).toInt())
}
