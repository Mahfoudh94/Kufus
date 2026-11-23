package io.github.drlacheheb.kufus.disk

internal data class NtfsGeometry(
    val sectorSizeBytes: Int,
    val sectorsPerCluster: Int,
    val clusterBytes: Int,
    val totalSectors: Long,
    val totalClusters: Long,
    val mftLcn: Long,
    val mftClusters: Long,
    val mftBitmapLcn: Long,
    val mftMirrLcn: Long,
    val mftMirrClusters: Long,
    val logFileLcn: Long,
    val logFileClusters: Long,
    val rootSdLcn: Long,
    val rootSdClusters: Long,
    val rootIndexLcn: Long,
    val rootIndexClusters: Long,
    val attrDefLcn: Long,
    val attrDefClusters: Long,
    val volumeBitmapLcn: Long,
    val volumeBitmapRealBytes: Long,
    val volumeBitmapClusters: Long,
    val secureSdsLcn: Long,
    val secureSdsClusters: Long,
    val upcaseLcn: Long,
    val upcaseClusters: Long,
) {
    val mftMirrRecordCount: Int
        get() = maxOf(4, clusterBytes / NTFS_MFT_RECORD_BYTES)

    val mftRecordCount: Int
        get() = maxOf(NTFS_MFT_RECORD_COUNT, mftMirrRecordCount)
}

/**
 * Pure-Kotlin NTFS 3.1 formatter computing volume geometry and writing `$Boot` (plus backup),
 * `$MFT`, `$MFTMirr`, `$LogFile`, `$Bitmap`, and system metadata streams to a [BlockWriter].
 */
internal object NtfsFormatter : PartitionFormatter {
    override val filesystem: FilesystemType = FilesystemType.NTFS

    override fun format(
        writer: BlockWriter,
        span: PartitionSpan,
        options: PartitionFormatOptions,
        shouldCancel: () -> Boolean,
    ) {
        format(
            target = writer,
            span = span,
            volumeLabel = options.volumeLabel,
            requestedClusterBytes = options.requestedClusterBytes,
            shouldCancel = shouldCancel,
        )
    }

    fun format(
        target: BlockWriter,
        span: PartitionSpan,
        volumeLabel: String,
        requestedClusterBytes: Int? = null,
        shouldCancel: () -> Boolean = { false },
    ) {
        val geom = computeGeometry(span, requestedClusterBytes)
        val cleanLabel = sanitizeVolumeLabel(volumeLabel, FilesystemType.NTFS)
        val serial = FormatBlockIo.deterministicSerial32(span.sectorCount, cleanLabel).toLong() and 0xFFFF_FFFFL
        val boot = buildBootBlock(span, geom, serial or (serial shl 32))

        // 1. Primary $Boot (8192 bytes) and Backup Boot Sector (very last physical sector)
        val bootClusters = clustersForBytes(NTFS_BOOT_BYTES.toLong(), geom.clusterBytes)
        writeClusterData(target, span, geom, 0L, boot, bootClusters, shouldCancel)
        val backupSectorOffset = span.startByteOffset + geom.totalSectors * geom.sectorSizeBytes
        target.writeAt(backupSectorOffset, boot.copyOfRange(0, geom.sectorSizeBytes), geom.sectorSizeBytes)

        // 2. Build $MFT and write both $MFT and $MFTMirr (at least 4 records or 1 full cluster)
        val mftBytes = NtfsSystemRecords.buildMftBytes(geom, cleanLabel)
        writeClusterData(target, span, geom, geom.mftLcn, mftBytes, geom.mftClusters, shouldCancel)
        val mftMirrBytes = mftBytes.copyOfRange(0, geom.mftMirrRecordCount * NTFS_MFT_RECORD_BYTES)
        writeClusterData(target, span, geom, geom.mftMirrLcn, mftMirrBytes, geom.mftMirrClusters, shouldCancel)

        // 3. Write $MFT $B0 bitmap
        val mftBitmap =
            ByteArray(8).apply {
                this[0] = 0xFF.toByte()
                this[1] = 0xFF.toByte()
                this[3] = 0x07
            }
        writeClusterData(target, span, geom, geom.mftBitmapLcn, mftBitmap, 1L, shouldCancel)

        // 4. Write $LogFile filled with 0xFF
        writeFillClusterRegion(target, span, geom, geom.logFileLcn, geom.logFileClusters, 0xFF.toByte(), shouldCancel)

        // 5. Write Root '.', '$AttrDef', '$VolumeBitmap', '$Secure.$SDS', '$UpCase'
        writeClusterData(target, span, geom, geom.rootSdLcn, NtfsSystemData.buildRootSecurityDescriptor(), geom.rootSdClusters, shouldCancel)
        writeClusterData(target, span, geom, geom.rootIndexLcn, NtfsSystemRecords.buildRootIndxBlock(geom), geom.rootIndexClusters, shouldCancel)
        writeClusterData(target, span, geom, geom.attrDefLcn, NtfsSystemData.buildAttrDefBytes(), geom.attrDefClusters, shouldCancel)
        writeVolumeBitmap(target, span, geom, shouldCancel)
        writeClusterData(target, span, geom, geom.secureSdsLcn, NtfsSystemData.buildSecureSdsBytes(), geom.secureSdsClusters, shouldCancel)
        writeClusterData(target, span, geom, geom.upcaseLcn, UnicodeUpcaseTable.buildNtfsUpcaseBytes(), geom.upcaseClusters, shouldCancel)
    }

    fun computeGeometry(
        span: PartitionSpan,
        requestedClusterBytes: Int? = null,
    ): NtfsGeometry {
        val sectorSize = span.sectorSizeBytes
        val clusterBytes = chooseClusterBytes(sectorSize, requestedClusterBytes)
        val sectorsPerCluster = clusterBytes / sectorSize
        val totalSectors = span.sectorCount - 1L
        val totalClusters = totalSectors / sectorsPerCluster

        val bootClusters = clustersForBytes(NTFS_BOOT_BYTES.toLong(), clusterBytes)
        val mftBitmapLcn = bootClusters
        val mftLcn = maxOf(4L, mftBitmapLcn + 1L)
        val mftClusters = clustersForBytes(maxOf(NTFS_MFT_RECORD_COUNT * NTFS_MFT_RECORD_BYTES, clusterBytes).toLong(), clusterBytes)
        val mftMirrClusters = clustersForBytes(maxOf(4 * NTFS_MFT_RECORD_BYTES, clusterBytes).toLong(), clusterBytes)
        val mftMirrLcn = totalClusters / 2L

        val volumeBitmapRealBytes = FormatBlockIo.alignUpBytes(FormatBlockIo.ceilDiv(totalClusters, 8L), 8L)
        val rootSdClusters = clustersForBytes(NtfsSystemData.ROOT_SD_REAL_SIZE.toLong(), clusterBytes)
        val rootIndexClusters = clustersForBytes(NTFS_INDEX_RECORD_BYTES.toLong(), clusterBytes)
        val attrDefClusters = clustersForBytes(NtfsSystemData.ATTRDEF_BYTE_SIZE.toLong(), clusterBytes)
        val volumeBitmapClusters = clustersForBytes(volumeBitmapRealBytes, clusterBytes)
        val secureSdsClusters = clustersForBytes(NtfsSystemData.SECURE_SDS_REAL_SIZE.toLong(), clusterBytes)
        val upcaseClusters = clustersForBytes(131_072L, clusterBytes)
        val neededMeta =
            rootSdClusters + rootIndexClusters + attrDefClusters + volumeBitmapClusters + secureSdsClusters + upcaseClusters
        val desiredStart = maxOf(mftLcn + mftClusters, totalClusters / 8L)
        val metaStart = if (desiredStart + neededMeta < mftMirrLcn) desiredStart else mftLcn + mftClusters

        val logFileLcn = mftMirrLcn + mftMirrClusters
        val logFileClusters = minOf(totalClusters - logFileLcn, (totalClusters / 24L).coerceIn(64L, 4096L))

        return NtfsGeometry(
            sectorSizeBytes = sectorSize,
            sectorsPerCluster = sectorsPerCluster,
            clusterBytes = clusterBytes,
            totalSectors = totalSectors,
            totalClusters = totalClusters,
            mftLcn = mftLcn,
            mftClusters = mftClusters,
            mftBitmapLcn = mftBitmapLcn,
            mftMirrLcn = mftMirrLcn,
            mftMirrClusters = mftMirrClusters,
            logFileLcn = logFileLcn,
            logFileClusters = logFileClusters,
            rootSdLcn = metaStart,
            rootSdClusters = rootSdClusters,
            rootIndexLcn = metaStart + rootSdClusters,
            rootIndexClusters = rootIndexClusters,
            attrDefLcn = metaStart + rootSdClusters + rootIndexClusters,
            attrDefClusters = attrDefClusters,
            volumeBitmapLcn = metaStart + rootSdClusters + rootIndexClusters + attrDefClusters,
            volumeBitmapRealBytes = volumeBitmapRealBytes,
            volumeBitmapClusters = volumeBitmapClusters,
            secureSdsLcn = metaStart + rootSdClusters + rootIndexClusters + attrDefClusters + volumeBitmapClusters,
            secureSdsClusters = secureSdsClusters,
            upcaseLcn = metaStart + neededMeta - upcaseClusters,
            upcaseClusters = upcaseClusters,
        )
    }

    private fun clustersForBytes(
        byteCount: Long,
        clusterBytes: Int,
    ): Long = FormatBlockIo.ceilDiv(byteCount, clusterBytes.toLong())

    private fun buildBootBlock(
        span: PartitionSpan,
        geom: NtfsGeometry,
        volumeSerial: Long,
    ): ByteArray {
        val boot = ByteArray(NTFS_BOOT_BYTES)
        boot[0] = 0xEB.toByte()
        boot[1] = 0x52
        boot[2] = 0x90.toByte()
        "NTFS    ".encodeToByteArray().copyInto(boot, 3)
        writeLeShort(boot, 11, geom.sectorSizeBytes)
        boot[13] = geom.sectorsPerCluster.toByte()
        boot[21] = 0xF8.toByte()
        writeLeInt(boot, 28, span.startLba.toInt())
        writeLeInt(boot, 36, 0x0080_0080)
        writeLeLong(boot, 40, geom.totalSectors)
        writeLeLong(boot, 48, geom.mftLcn)
        writeLeLong(boot, 56, geom.mftMirrLcn)
        boot[64] = NtfsRecordCodec.encodeRecordSizeField(geom.clusterBytes, NTFS_MFT_RECORD_BYTES).toByte()
        boot[68] = NtfsRecordCodec.encodeRecordSizeField(geom.clusterBytes, NTFS_INDEX_RECORD_BYTES).toByte()
        writeLeLong(boot, 72, volumeSerial)
        boot[510] = 0x55
        boot[511] = 0xAA.toByte()
        return boot
    }

    private fun writeVolumeBitmap(
        target: BlockWriter,
        span: PartitionSpan,
        geom: NtfsGeometry,
        shouldCancel: () -> Boolean,
    ) {
        val totalBytes = geom.volumeBitmapClusters.toInt() * geom.clusterBytes
        val bitmap = ByteArray(totalBytes)
        val allocatedRanges =
            listOf(
                0L to clustersForBytes(NTFS_BOOT_BYTES.toLong(), geom.clusterBytes),
                geom.mftBitmapLcn to 1L,
                geom.mftLcn to geom.mftClusters,
                geom.rootSdLcn to geom.rootSdClusters,
                geom.rootIndexLcn to geom.rootIndexClusters,
                geom.attrDefLcn to geom.attrDefClusters,
                geom.volumeBitmapLcn to geom.volumeBitmapClusters,
                geom.secureSdsLcn to geom.secureSdsClusters,
                geom.upcaseLcn to geom.upcaseClusters,
                geom.mftMirrLcn to geom.mftMirrClusters,
                geom.logFileLcn to geom.logFileClusters,
            )
        for ((start, count) in allocatedRanges) {
            markBitmapRange(bitmap, start, count)
        }
        // Pad trailing bits beyond totalClusters with 1s matching NTFS specification
        val maxPaddingBits = geom.volumeBitmapRealBytes * 8L
        if (maxPaddingBits > geom.totalClusters) {
            markBitmapRange(bitmap, geom.totalClusters, maxPaddingBits - geom.totalClusters)
        }
        writeClusterData(target, span, geom, geom.volumeBitmapLcn, bitmap, geom.volumeBitmapClusters, shouldCancel)
    }

    private fun markBitmapRange(
        bitmap: ByteArray,
        startCluster: Long,
        count: Long,
    ) {
        for (i in 0L until count) {
            val cluster = startCluster + i
            val byteIdx = (cluster ushr 3).toInt()
            if (byteIdx < bitmap.size) {
                bitmap[byteIdx] = (bitmap[byteIdx].toInt() or (1 shl (cluster and 7L).toInt())).toByte()
            }
        }
    }

    private fun writeClusterData(
        target: BlockWriter,
        span: PartitionSpan,
        geom: NtfsGeometry,
        startLcn: Long,
        data: ByteArray,
        clusterCount: Long,
        shouldCancel: () -> Boolean,
    ) {
        val startOffset = span.startByteOffset + startLcn * geom.clusterBytes
        FormatBlockIo.writeSectorAlignedStream(target, startOffset, data, shouldCancel)
        val written = FormatBlockIo.alignUpBytes(data.size.toLong(), geom.sectorSizeBytes.toLong())
        val expected = clusterCount * geom.clusterBytes
        if (expected > written) {
            FormatBlockIo.writeZeroSectors(target, startOffset + written, expected - written, shouldCancel)
        }
    }

    private fun writeFillClusterRegion(
        target: BlockWriter,
        span: PartitionSpan,
        geom: NtfsGeometry,
        startLcn: Long,
        clusterCount: Long,
        fillByte: Byte,
        shouldCancel: () -> Boolean,
    ) {
        val totalBytes = clusterCount * geom.clusterBytes
        val chunk = ByteArray(minOf(totalBytes, 262_144L).toInt()) { fillByte }
        val startOffset = span.startByteOffset + startLcn * geom.clusterBytes
        var written = 0L
        while (written < totalBytes) {
            FormatBlockIo.ensureNotCancelled(shouldCancel)
            val len = minOf(totalBytes - written, chunk.size.toLong()).toInt()
            target.writeAt(startOffset + written, chunk, len)
            written += len.toLong()
        }
    }

    private fun chooseClusterBytes(
        sectorSize: Int,
        requestedClusterBytes: Int?,
    ): Int {
        val minCluster = maxOf(sectorSize, 512)
        if (requestedClusterBytes != null && requestedClusterBytes in minCluster..65_536) {
            if ((requestedClusterBytes and (requestedClusterBytes - 1)) == 0) {
                return requestedClusterBytes
            }
        }
        return maxOf(4096, sectorSize)
    }
}
