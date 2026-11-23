package io.github.drlacheheb.kufus.disk

private const val FAT32_RESERVED_SECTORS = 32
private const val FAT32_NUM_FATS = 2
private const val FAT32_BACKUP_BOOT_SECTOR = 6
private const val FAT32_FSINFO_SECTOR = 1
private const val FAT32_ROOT_CLUSTER = 2
private const val FAT32_MIN_CLUSTERS = 65_525L
private const val FAT32_MAX_CLUSTERS = 0x0FFFFFF4L
private const val FAT32_MAX_SECTORS = 0xFFFFFFFFL
private const val FAT32_MAX_SEC_PER_CLUS = 128
private const val FAT32_MEDIA_FIXED = 0xF8
private const val FAT32_ZERO_CHUNK_BYTES = 262_144
private const val FAT32_DEFAULT_DOS_DATE = 0x5421

private const val GIB_8 = 8_589_934_592L
private const val GIB_16 = 17_179_869_184L
private const val GIB_32 = 34_359_738_368L
private const val MIB_260 = 272_629_760L

data class Fat32Geometry(
    val sectorSizeBytes: Int,
    val sectorsPerCluster: Int,
    val partitionSectors: Long,
    val fatSectors32: Long,
    val dataClusterCount: Long,
)

/**
 * Pure-Kotlin FAT32 formatter supporting volumes up to 2 TiB (512B sectors) across all platforms.
 */
object Fat32Formatter {
    fun computeGeometry(
        span: PartitionSpan,
        requestedClusterBytes: Int? = null,
    ): Fat32Geometry {
        val sectorSize = span.sectorSizeBytes
        val partitionSectors = span.sectorCount
        if (partitionSectors > FAT32_MAX_SECTORS) {
            throw FlashStepException(
                code = "VOLUME_TOO_LARGE",
                message = "FAT32 volume exceeds 32-bit sector limit (${partitionSectors * sectorSize} bytes).",
            )
        }
        val sectorsPerCluster = resolveSectorsPerCluster(span, requestedClusterBytes)
        val fatSectors = solveFatSectors(partitionSectors, sectorSize, sectorsPerCluster)
        val dataSectors = partitionSectors - FAT32_RESERVED_SECTORS - FAT32_NUM_FATS.toLong() * fatSectors
        val clusterCount = dataSectors / sectorsPerCluster.toLong()
        validateFat32ClusterCount(clusterCount)
        return Fat32Geometry(
            sectorSizeBytes = sectorSize,
            sectorsPerCluster = sectorsPerCluster,
            partitionSectors = partitionSectors,
            fatSectors32 = fatSectors,
            dataClusterCount = clusterCount,
        )
    }

    private fun validateFat32ClusterCount(clusterCount: Long) {
        if (clusterCount < FAT32_MIN_CLUSTERS) {
            throw FlashStepException(
                code = "INVALID_CLUSTER_SIZE",
                message = "Partition too small for FAT32 with $clusterCount clusters (minimum $FAT32_MIN_CLUSTERS).",
            )
        }
        if (clusterCount > FAT32_MAX_CLUSTERS) {
            throw FlashStepException(
                code = "INVALID_CLUSTER_SIZE",
                message = "Cluster count ($clusterCount) exceeds FAT32 maximum — select a larger cluster size.",
            )
        }
    }

    fun format(
        target: BlockFlashTarget,
        span: PartitionSpan,
        volumeLabel: String,
        requestedClusterBytes: Int? = null,
        volumeId: Int = 0x4B554655,
        shouldCancel: () -> Boolean = { false },
    ) {
        val geometry = computeGeometry(span, requestedClusterBytes)
        val cleanLabel = sanitizeVolumeLabel(volumeLabel, FilesystemType.FAT32)
        val partitionOffset = span.startByteOffset
        val sectorBytes = geometry.sectorSizeBytes

        writeReservedArea(target, span, geometry, cleanLabel, volumeId)
        checkCancelled(shouldCancel)

        val fatByteLength = geometry.fatSectors32 * sectorBytes.toLong()
        val fat1Offset = partitionOffset + FAT32_RESERVED_SECTORS.toLong() * sectorBytes.toLong()
        val fat2Offset = fat1Offset + fatByteLength
        writeFatTable(target, fat1Offset, fatByteLength, sectorBytes, shouldCancel)
        writeFatTable(target, fat2Offset, fatByteLength, sectorBytes, shouldCancel)

        val rootOffset = fat2Offset + fatByteLength
        writeRootDirectory(target, rootOffset, geometry, cleanLabel)
    }

    private fun resolveSectorsPerCluster(
        span: PartitionSpan,
        requestedClusterBytes: Int?,
    ): Int {
        val sectorSize = span.sectorSizeBytes
        if (requestedClusterBytes != null) {
            val requestedSectors = requestedClusterBytes / sectorSize
            val isPowerOfTwo = requestedClusterBytes > 0 && (requestedClusterBytes and (requestedClusterBytes - 1)) == 0
            if (!isPowerOfTwo || requestedClusterBytes % sectorSize != 0 || requestedSectors !in 1..FAT32_MAX_SEC_PER_CLUS) {
                throw FlashStepException(
                    code = "INVALID_CLUSTER_SIZE",
                    message = "Unsupported FAT32 cluster size: $requestedClusterBytes bytes.",
                )
            }
            return requestedSectors
        }
        val sizeBytes = span.sizeBytes
        val desiredClusterBytes =
            when {
                sizeBytes <= MIB_260 -> return chooseSmallestValidFat32SectorsPerCluster(span)
                sizeBytes <= GIB_8 -> 4096
                sizeBytes <= GIB_16 -> 8192
                sizeBytes <= GIB_32 -> 16_384
                else -> 32_768
            }
        return (desiredClusterBytes / sectorSize).coerceIn(1, FAT32_MAX_SEC_PER_CLUS)
    }

    private fun chooseSmallestValidFat32SectorsPerCluster(span: PartitionSpan): Int {
        val maxCandidate = (4096 / span.sectorSizeBytes).coerceIn(1, FAT32_MAX_SEC_PER_CLUS)
        var candidate = maxCandidate
        while (candidate >= 1) {
            val fatSectors = solveFatSectors(span.sectorCount, span.sectorSizeBytes, candidate)
            val clusters = (span.sectorCount - FAT32_RESERVED_SECTORS - FAT32_NUM_FATS.toLong() * fatSectors) / candidate.toLong()
            if (clusters in FAT32_MIN_CLUSTERS..FAT32_MAX_CLUSTERS) {
                return candidate
            }
            candidate /= 2
        }
        return 1
    }

    private fun solveFatSectors(
        partitionSectors: Long,
        sectorSizeBytes: Int,
        sectorsPerCluster: Int,
    ): Long {
        val entriesPerSector = (sectorSizeBytes / 4).toLong()
        var fatSectors = 1L
        repeat(8) {
            val dataSectors = (partitionSectors - FAT32_RESERVED_SECTORS - FAT32_NUM_FATS.toLong() * fatSectors).coerceAtLeast(0L)
            val clusters = dataSectors / sectorsPerCluster.toLong()
            val neededSectors = ((clusters + 2L) + entriesPerSector - 1L) / entriesPerSector
            if (neededSectors == fatSectors) return fatSectors
            fatSectors = neededSectors.coerceAtLeast(1L)
        }
        val finalData = (partitionSectors - FAT32_RESERVED_SECTORS - FAT32_NUM_FATS.toLong() * fatSectors).coerceAtLeast(0L)
        val finalClusters = finalData / sectorsPerCluster.toLong()
        val requiredFat = ((finalClusters + 2L) + entriesPerSector - 1L) / entriesPerSector
        return maxOf(fatSectors, requiredFat, 1L)
    }

    private fun writeReservedArea(
        target: BlockFlashTarget,
        span: PartitionSpan,
        geometry: Fat32Geometry,
        cleanLabel: String,
        volumeId: Int,
    ) {
        val sectorBytes = geometry.sectorSizeBytes
        val reservedBuffer = ByteArray(FAT32_RESERVED_SECTORS * sectorBytes)
        val boot = buildBootSector(span, geometry, cleanLabel, volumeId)
        val fsInfo = buildFsInfoSector(geometry)
        boot.copyInto(reservedBuffer, destinationOffset = 0)
        fsInfo.copyInto(reservedBuffer, destinationOffset = FAT32_FSINFO_SECTOR * sectorBytes)
        boot.copyInto(reservedBuffer, destinationOffset = FAT32_BACKUP_BOOT_SECTOR * sectorBytes)
        fsInfo.copyInto(reservedBuffer, destinationOffset = (FAT32_BACKUP_BOOT_SECTOR + 1) * sectorBytes)
        target.writeAt(span.startByteOffset, reservedBuffer, reservedBuffer.size)
    }

    private fun buildBootSector(
        span: PartitionSpan,
        geometry: Fat32Geometry,
        cleanLabel: String,
        volumeId: Int,
    ): ByteArray {
        val boot = ByteArray(geometry.sectorSizeBytes)
        boot[0] = 0xEB.toByte()
        boot[1] = 0x58
        boot[2] = 0x90.toByte()
        "MSWIN4.1".encodeToByteArray().copyInto(boot, destinationOffset = 3)
        writeLeShort(boot, 11, geometry.sectorSizeBytes)
        boot[13] = geometry.sectorsPerCluster.toByte()
        writeLeShort(boot, 14, FAT32_RESERVED_SECTORS)
        boot[16] = FAT32_NUM_FATS.toByte()
        writeLeShort(boot, 17, 0)
        writeLeShort(boot, 19, 0)
        boot[21] = FAT32_MEDIA_FIXED.toByte()
        writeLeShort(boot, 22, 0)
        writeLeShort(boot, 24, 63)
        writeLeShort(boot, 26, 255)
        writeLeInt(boot, 28, (span.startLba and 0xFFFFFFFFL).toInt())
        writeLeInt(boot, 32, geometry.partitionSectors.toInt())
        writeLeInt(boot, 36, geometry.fatSectors32.toInt())
        writeLeShort(boot, 40, 0)
        writeLeShort(boot, 42, 0)
        writeLeInt(boot, 44, FAT32_ROOT_CLUSTER)
        writeLeShort(boot, 48, FAT32_FSINFO_SECTOR)
        writeLeShort(boot, 50, FAT32_BACKUP_BOOT_SECTOR)
        boot[64] = 0x80.toByte()
        boot[66] = 0x29
        writeLeInt(boot, 67, volumeId)
        val paddedLabel = formatFat11Label(cleanLabel.ifEmpty { "NO NAME" })
        paddedLabel.copyInto(boot, destinationOffset = 71)
        "FAT32   ".encodeToByteArray().copyInto(boot, destinationOffset = 82)
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()
        return boot
    }

    private fun buildFsInfoSector(geometry: Fat32Geometry): ByteArray {
        val fsInfo = ByteArray(geometry.sectorSizeBytes)
        writeLeInt(fsInfo, 0, 0x41615252)
        writeLeInt(fsInfo, 484, 0x61417272)
        val freeClusters = (geometry.dataClusterCount - 1L).coerceIn(0L, 0xFFFFFFFFL).toInt()
        writeLeInt(fsInfo, 488, freeClusters)
        writeLeInt(fsInfo, 492, 3)
        writeLeInt(fsInfo, 508, 0xAA550000.toInt())
        return fsInfo
    }

    private fun writeFatTable(
        target: BlockFlashTarget,
        fatOffsetBytes: Long,
        fatByteLength: Long,
        sectorSizeBytes: Int,
        shouldCancel: () -> Boolean,
    ) {
        val alignedChunk = alignUpLocal(FAT32_ZERO_CHUNK_BYTES, sectorSizeBytes)
        val chunk = ByteArray(alignedChunk)
        var written = 0L
        while (written < fatByteLength) {
            checkCancelled(shouldCancel)
            val slice = minOf(alignedChunk.toLong(), fatByteLength - written).toInt()
            if (written == 0L) {
                writeLeInt(chunk, 0, 0x0FFFFFF8)
                writeLeInt(chunk, 4, 0x0FFFFFFF)
                writeLeInt(chunk, 8, 0x0FFFFFFF)
            }
            target.writeAt(fatOffsetBytes + written, chunk, slice)
            if (written == 0L) {
                chunk.fill(0, fromIndex = 0, toIndex = 12)
            }
            written += slice.toLong()
        }
    }

    private fun writeRootDirectory(
        target: BlockFlashTarget,
        rootOffsetBytes: Long,
        geometry: Fat32Geometry,
        cleanLabel: String,
    ) {
        val clusterBytes = geometry.sectorsPerCluster * geometry.sectorSizeBytes
        val rootCluster = ByteArray(clusterBytes)
        if (cleanLabel.isNotEmpty()) {
            val entryName = formatFat11Label(cleanLabel)
            entryName.copyInto(rootCluster, destinationOffset = 0)
            rootCluster[11] = 0x08
            writeLeShort(rootCluster, 16, FAT32_DEFAULT_DOS_DATE)
            writeLeShort(rootCluster, 24, FAT32_DEFAULT_DOS_DATE)
        }
        target.writeAt(rootOffsetBytes, rootCluster, rootCluster.size)
    }

    private fun formatFat11Label(label: String): ByteArray {
        val result = ByteArray(11) { ' '.code.toByte() }
        val ascii = label.encodeToByteArray()
        val copyLen = minOf(ascii.size, 11)
        ascii.copyInto(result, destinationOffset = 0, startIndex = 0, endIndex = copyLen)
        return result
    }

    private fun alignUpLocal(
        value: Int,
        alignment: Int,
    ): Int = ((value + alignment - 1) / alignment) * alignment

    private fun checkCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw FlashStepException(code = "CANCELLED", message = "Format cancelled by user")
        }
    }
}
