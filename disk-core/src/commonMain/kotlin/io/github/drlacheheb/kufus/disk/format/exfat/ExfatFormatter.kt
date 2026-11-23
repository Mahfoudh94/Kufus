package io.github.drlacheheb.kufus.disk.format.exfat

import io.github.drlacheheb.kufus.disk.format.core.PartitionFormatOptions
import io.github.drlacheheb.kufus.disk.format.core.PartitionFormatter
import io.github.drlacheheb.kufus.disk.format.core.UnicodeUpcaseTable
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.io.FormatBlockIo
import io.github.drlacheheb.kufus.disk.io.writeLeInt
import io.github.drlacheheb.kufus.disk.io.writeLeLong
import io.github.drlacheheb.kufus.disk.io.writeLeShort
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.partition.PartitionSpan

private const val EXFAT_ENTRY_BITMAP = 0x81
private const val EXFAT_ENTRY_UPCASE = 0x82
private const val EXFAT_ENTRY_LABEL = 0x83
private const val EXFAT_ENTRY_LABEL_EMPTY = 0x03
private const val EXFAT_BOOT_REGION_SECTORS = 12
private const val EXFAT_EXTENDED_BOOT_SECTORS = 8
private const val EXFAT_EOC = -1 // 0xFFFF_FFFF
private const val EXFAT_MEDIA_FAT0 = -8 // 0xFFFF_FFF8
private const val EXFAT_MAX_CLUSTER_COUNT = 0x0FFF_FFF0L
private const val EXFAT_MIN_CLUSTERS = 8L
private const val EXFAT_ALIGNMENT_THRESHOLD_SECTORS = 32_768L
private const val EXFAT_BOUNDARY_ALIGNMENT_SECTORS = 2048L
private const val EXFAT_FAT_STREAM_CHUNK_BYTES = 262_144
private const val EXFAT_BITMAP_STREAM_CHUNK_BYTES = 262_144
private const val EXFAT_FIRST_DATA_CLUSTER = 2

internal data class ExfatGeometry(
    val sectorSizeBytes: Int,
    val sectorsPerCluster: Int,
    val bytesPerSectorShift: Int,
    val sectorsPerClusterShift: Int,
    val fatOffsetSectors: Long,
    val fatLengthSectors: Long,
    val clusterHeapOffsetSectors: Long,
    val clusterCount: Long,
    val rootCluster: Int,
    val bitmapFirstCluster: Int,
    val bitmapByteLength: Long,
    val bitmapClusterCount: Int,
    val upcaseFirstCluster: Int,
    val upcaseByteLength: Long,
    val upcaseClusterCount: Int,
)

/**
 * Pure-Kotlin exFAT partition formatter writing Main/Backup VBRs (with rolling boot checksums),
 * single FAT allocation table, Allocation Bitmap, compressed Up-case Table, and Root Directory.
 */
internal object ExfatFormatter : PartitionFormatter {
    override val filesystem: FilesystemType = FilesystemType.EXFAT

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
        val upcaseBytes = UnicodeUpcaseTable.buildExfatCompressedTable()
        val geometry = computeGeometry(span, upcaseBytes.size.toLong(), requestedClusterBytes)
        val cleanLabel = sanitizeVolumeLabel(volumeLabel, FilesystemType.EXFAT)
        val serial = FormatBlockIo.deterministicSerial32(span.sectorCount, cleanLabel)

        val bootRegion = buildBootRegion(span, geometry, serial)
        target.writeAt(span.startByteOffset, bootRegion, bootRegion.size)
        val backupOffset = span.startByteOffset + EXFAT_BOOT_REGION_SECTORS.toLong() * geometry.sectorSizeBytes
        target.writeAt(backupOffset, bootRegion, bootRegion.size)

        writeFat(target, span, geometry, shouldCancel)
        writeAllocationBitmap(target, span, geometry, shouldCancel)
        writeUpcaseTable(target, span, geometry, upcaseBytes, shouldCancel)
        writeRootDirectory(target, span, geometry, cleanLabel, upcaseBytes, shouldCancel)
    }

    fun computeGeometry(
        span: PartitionSpan,
        upcaseByteLength: Long = 2850L,
        requestedClusterBytes: Int? = null,
    ): ExfatGeometry {
        val sectorSize = span.sectorSizeBytes
        val volumeBytes = span.sectorCount * sectorSize
        val initialSpc = chooseSectorsPerCluster(volumeBytes, sectorSize, requestedClusterBytes)
        val layout = resolveClusterLayout(span, sectorSize, initialSpc)

        val clusterBytes = sectorSize.toLong() * layout.sectorsPerCluster
        val bitmapBytes = FormatBlockIo.ceilDiv(layout.clusterCount, 8L)
        val bitmapClusters = FormatBlockIo.ceilDiv(bitmapBytes, clusterBytes).toInt()
        val upcaseClusters = FormatBlockIo.ceilDiv(upcaseByteLength, clusterBytes).toInt()
        val upcaseFirstCluster = EXFAT_FIRST_DATA_CLUSTER + bitmapClusters

        return ExfatGeometry(
            sectorSizeBytes = sectorSize,
            sectorsPerCluster = layout.sectorsPerCluster,
            bytesPerSectorShift = log2Exact(sectorSize),
            sectorsPerClusterShift = log2Exact(layout.sectorsPerCluster),
            fatOffsetSectors = layout.fatOffset,
            fatLengthSectors = layout.fatLength,
            clusterHeapOffsetSectors = layout.clusterHeapOffset,
            clusterCount = layout.clusterCount,
            rootCluster = upcaseFirstCluster + upcaseClusters,
            bitmapFirstCluster = EXFAT_FIRST_DATA_CLUSTER,
            bitmapByteLength = bitmapBytes,
            bitmapClusterCount = bitmapClusters,
            upcaseFirstCluster = upcaseFirstCluster,
            upcaseByteLength = upcaseByteLength,
            upcaseClusterCount = upcaseClusters,
        )
    }

    private data class ResolvedExfatLayout(
        val sectorsPerCluster: Int,
        val fatOffset: Long,
        val fatLength: Long,
        val clusterHeapOffset: Long,
        val clusterCount: Long,
    )

    private fun resolveClusterLayout(
        span: PartitionSpan,
        sectorSize: Int,
        initialSectorsPerCluster: Int,
    ): ResolvedExfatLayout {
        val useLargeAlignment = span.sectorCount >= EXFAT_ALIGNMENT_THRESHOLD_SECTORS
        val fatOffset = if (useLargeAlignment) EXFAT_BOUNDARY_ALIGNMENT_SECTORS else 24L
        var sectorsPerCluster = initialSectorsPerCluster
        var clusterHeapOffset = fatOffset + 8L
        var fatLength = 8L
        var clusterCount = 0L

        var converged = false
        while (!converged) {
            val alignmentSectors =
                if (useLargeAlignment) {
                    maxOf(EXFAT_BOUNDARY_ALIGNMENT_SECTORS, sectorsPerCluster.toLong())
                } else {
                    sectorsPerCluster.toLong()
                }
            val rawClusters = (span.sectorCount - clusterHeapOffset) / sectorsPerCluster
            val adjustedSpc = adjustSectorsPerCluster(rawClusters, sectorsPerCluster)
            if (adjustedSpc != sectorsPerCluster) {
                sectorsPerCluster = adjustedSpc
            } else {
                val nextClusterCount = rawClusters.coerceIn(EXFAT_MIN_CLUSTERS, EXFAT_MAX_CLUSTER_COUNT)
                val nextFatLength = FormatBlockIo.ceilDiv((nextClusterCount + 2L) * 4L, sectorSize.toLong())
                val nextHeapOffset = FormatBlockIo.alignUpBytes(fatOffset + nextFatLength, alignmentSectors)
                converged =
                    nextClusterCount == clusterCount &&
                    nextFatLength == fatLength &&
                    nextHeapOffset == clusterHeapOffset
                clusterCount = nextClusterCount
                fatLength = nextFatLength
                clusterHeapOffset = nextHeapOffset
            }
        }
        return ResolvedExfatLayout(sectorsPerCluster, fatOffset, fatLength, clusterHeapOffset, clusterCount)
    }

    private fun adjustSectorsPerCluster(
        rawClusters: Long,
        currentSpc: Int,
    ): Int =
        when {
            rawClusters > EXFAT_MAX_CLUSTER_COUNT && currentSpc < 65_536 -> currentSpc * 2
            rawClusters < EXFAT_MIN_CLUSTERS && currentSpc > 1 -> currentSpc / 2
            else -> currentSpc
        }

    private fun buildBootRegion(
        span: PartitionSpan,
        geometry: ExfatGeometry,
        serial: Int,
    ): ByteArray {
        val sec = geometry.sectorSizeBytes
        val region = ByteArray(EXFAT_BOOT_REGION_SECTORS * sec)
        val usedClusters = (geometry.bitmapClusterCount + geometry.upcaseClusterCount + 1).toLong()
        val percentInUse = ((usedClusters * 100L) / geometry.clusterCount).toInt().coerceIn(0, 100)

        // Sector 0: exFAT Main Boot Sector
        region[0] = 0xEB.toByte()
        region[1] = 0x76
        region[2] = 0x90.toByte()
        "EXFAT   ".encodeToByteArray().copyInto(region, 3)
        writeLeLong(region, 64, span.startLba)
        writeLeLong(region, 72, span.sectorCount)
        writeLeInt(region, 80, geometry.fatOffsetSectors.toInt())
        writeLeInt(region, 84, geometry.fatLengthSectors.toInt())
        writeLeInt(region, 88, geometry.clusterHeapOffsetSectors.toInt())
        writeLeInt(region, 92, geometry.clusterCount.toInt())
        writeLeInt(region, 96, geometry.rootCluster)
        writeLeInt(region, 100, serial)
        writeLeShort(region, 104, 0x0100) // FileSystemRevision 1.0
        writeLeShort(region, 106, 0x0000) // VolumeFlags
        region[108] = geometry.bytesPerSectorShift.toByte()
        region[109] = geometry.sectorsPerClusterShift.toByte()
        region[110] = 1
        region[111] = 0x80.toByte()
        region[112] = percentInUse.toByte()
        region[510] = 0x55
        region[511] = 0xAA.toByte()

        // Sectors 1..8: Extended Boot Sectors with 0x55 0xAA at +510..511
        for (extSec in 1..EXFAT_EXTENDED_BOOT_SECTORS) {
            val base = extSec * sec
            region[base + 510] = 0x55
            region[base + 511] = 0xAA.toByte()
        }

        // Sector 11: Boot Checksum across Sectors 0..10 (skip VolumeFlags 106..107 and PercentInUse 112)
        val checksum = computeBootChecksum(region, 11 * sec)
        val csumBase = 11 * sec
        for (offset in 0 until sec step 4) {
            writeLeInt(region, csumBase + offset, checksum)
        }
        return region
    }

    private fun writeFat(
        target: BlockWriter,
        span: PartitionSpan,
        geometry: ExfatGeometry,
        shouldCancel: () -> Boolean,
    ) {
        val sec = geometry.sectorSizeBytes
        val fatByteStart = span.startByteOffset + geometry.fatOffsetSectors * sec
        val fatTotalBytes = geometry.fatLengthSectors * sec
        val chunkSize = ((EXFAT_FAT_STREAM_CHUNK_BYTES / sec).coerceAtLeast(1)) * sec
        val buffer = ByteArray(minOf(fatTotalBytes, chunkSize.toLong()).toInt())

        var byteCursor = 0L
        var fatIndex = 0L
        val upcaseEnd = geometry.upcaseFirstCluster + geometry.upcaseClusterCount - 1

        while (byteCursor < fatTotalBytes) {
            FormatBlockIo.ensureNotCancelled(shouldCancel)
            val len = minOf(fatTotalBytes - byteCursor, buffer.size.toLong()).toInt()
            buffer.fill(0)
            var pos = 0
            while (pos < len) {
                val entryValue =
                    fatEntryValue(
                        index = fatIndex.toInt(),
                        bitmapStart = geometry.bitmapFirstCluster,
                        bitmapEnd = geometry.bitmapFirstCluster + geometry.bitmapClusterCount - 1,
                        upcaseStart = geometry.upcaseFirstCluster,
                        upcaseEnd = upcaseEnd,
                        rootCluster = geometry.rootCluster,
                    )
                if (entryValue != 0) {
                    writeLeInt(buffer, pos, entryValue)
                }
                fatIndex++
                pos += 4
            }
            target.writeAt(fatByteStart + byteCursor, buffer, len)
            byteCursor += len.toLong()
        }
    }

    private fun fatEntryValue(
        index: Int,
        bitmapStart: Int,
        bitmapEnd: Int,
        upcaseStart: Int,
        upcaseEnd: Int,
        rootCluster: Int,
    ): Int =
        when {
            index == 0 -> EXFAT_MEDIA_FAT0
            index == 1 -> EXFAT_EOC
            index in bitmapStart until bitmapEnd -> index + 1
            index == bitmapEnd -> EXFAT_EOC
            index in upcaseStart until upcaseEnd -> index + 1
            index == upcaseEnd -> EXFAT_EOC
            index == rootCluster -> EXFAT_EOC
            else -> 0
        }

    private fun writeAllocationBitmap(
        target: BlockWriter,
        span: PartitionSpan,
        geometry: ExfatGeometry,
        shouldCancel: () -> Boolean,
    ) {
        val clusterBytes = geometry.sectorSizeBytes * geometry.sectorsPerCluster
        val allocatedBytes = geometry.bitmapClusterCount.toLong() * clusterBytes
        val bitmapStartOffset = clusterByteOffset(span, geometry, geometry.bitmapFirstCluster)
        val totalUsedClusters = geometry.bitmapClusterCount + geometry.upcaseClusterCount + 1
        val sec = geometry.sectorSizeBytes
        val chunkSize = ((EXFAT_BITMAP_STREAM_CHUNK_BYTES / sec).coerceAtLeast(1)) * sec
        val buffer = ByteArray(minOf(allocatedBytes, chunkSize.toLong()).toInt())

        var cursor = 0L
        while (cursor < allocatedBytes) {
            FormatBlockIo.ensureNotCancelled(shouldCancel)
            val len = minOf(allocatedBytes - cursor, buffer.size.toLong()).toInt()
            buffer.fill(0)
            for (b in 0 until len) {
                val byteMask = allocationBitmapByte((cursor + b.toLong()) * 8L, totalUsedClusters.toLong())
                if (byteMask == 0) break
                buffer[b] = byteMask.toByte()
            }
            target.writeAt(bitmapStartOffset + cursor, buffer, len)
            cursor += len.toLong()
        }
    }

    private fun allocationBitmapByte(
        bitStart: Long,
        totalUsedClusters: Long,
    ): Int {
        if (bitStart >= totalUsedClusters) return 0
        var mask = 0
        for (bit in 0 until 8) {
            if (bitStart + bit < totalUsedClusters) {
                mask = mask or (1 shl bit)
            }
        }
        return mask
    }

    private fun writeUpcaseTable(
        target: BlockWriter,
        span: PartitionSpan,
        geometry: ExfatGeometry,
        upcaseBytes: ByteArray,
        shouldCancel: () -> Boolean,
    ) {
        val clusterBytes = geometry.sectorSizeBytes * geometry.sectorsPerCluster
        val upcaseStartOffset = clusterByteOffset(span, geometry, geometry.upcaseFirstCluster)
        FormatBlockIo.writeSectorAlignedStream(target, upcaseStartOffset, upcaseBytes, shouldCancel)
        val writtenBytes = FormatBlockIo.alignUpBytes(upcaseBytes.size.toLong(), geometry.sectorSizeBytes.toLong())
        val totalClusterBytes = geometry.upcaseClusterCount.toLong() * clusterBytes
        if (totalClusterBytes > writtenBytes) {
            FormatBlockIo.writeZeroSectors(
                writer = target,
                startByteOffset = upcaseStartOffset + writtenBytes,
                byteCount = totalClusterBytes - writtenBytes,
                shouldCancel = shouldCancel,
            )
        }
    }

    private fun writeRootDirectory(
        target: BlockWriter,
        span: PartitionSpan,
        geometry: ExfatGeometry,
        cleanLabel: String,
        upcaseBytes: ByteArray,
        shouldCancel: () -> Boolean,
    ) {
        FormatBlockIo.ensureNotCancelled(shouldCancel)
        val clusterBytes = geometry.sectorSizeBytes * geometry.sectorsPerCluster
        val rootBuffer = ByteArray(clusterBytes)

        // Entry 0: Volume Label (0x83 when set, 0x03 when empty; exFAT spec limits label to 11 UTF-16 code units)
        val clampedLabel = cleanLabel.take(11)
        if (clampedLabel.isNotEmpty()) {
            rootBuffer[0] = EXFAT_ENTRY_LABEL.toByte()
            rootBuffer[1] = clampedLabel.length.toByte()
            FormatBlockIo.writeUtf16Le(rootBuffer, 2, clampedLabel, maxChars = 11)
        } else {
            rootBuffer[0] = EXFAT_ENTRY_LABEL_EMPTY.toByte()
        }

        // Entry 1: Allocation Bitmap (0x81)
        val bitmapEntry = 32
        rootBuffer[bitmapEntry] = EXFAT_ENTRY_BITMAP.toByte()
        writeLeInt(rootBuffer, bitmapEntry + 20, geometry.bitmapFirstCluster)
        writeLeLong(rootBuffer, bitmapEntry + 24, geometry.bitmapByteLength)

        // Entry 2: Up-case Table (0x82)
        val upcaseEntry = 64
        rootBuffer[upcaseEntry] = EXFAT_ENTRY_UPCASE.toByte()
        writeLeInt(rootBuffer, upcaseEntry + 4, computeTableChecksum(upcaseBytes))
        writeLeInt(rootBuffer, upcaseEntry + 20, geometry.upcaseFirstCluster)
        writeLeLong(rootBuffer, upcaseEntry + 24, geometry.upcaseByteLength)

        val rootOffset = clusterByteOffset(span, geometry, geometry.rootCluster)
        target.writeAt(rootOffset, rootBuffer, rootBuffer.size)
    }

    private fun clusterByteOffset(
        span: PartitionSpan,
        geometry: ExfatGeometry,
        cluster: Int,
    ): Long =
        span.startByteOffset +
            (geometry.clusterHeapOffsetSectors + (cluster - EXFAT_FIRST_DATA_CLUSTER).toLong() * geometry.sectorsPerCluster) *
            geometry.sectorSizeBytes

    internal fun computeBootChecksum(
        bootRegion11Sectors: ByteArray,
        length: Int,
    ): Int {
        var checksum = 0
        for (i in 0 until length) {
            if (i == 106 || i == 107 || i == 112) continue
            checksum = ((checksum ushr 1) or (checksum shl 31)) + (bootRegion11Sectors[i].toInt() and 0xFF)
        }
        return checksum
    }

    internal fun computeTableChecksum(bytes: ByteArray): Int {
        var checksum = 0
        for (b in bytes) {
            checksum = ((checksum ushr 1) or (checksum shl 31)) + (b.toInt() and 0xFF)
        }
        return checksum
    }

    private fun chooseSectorsPerCluster(
        volumeBytes: Long,
        sectorSize: Int,
        requestedClusterBytes: Int?,
    ): Int {
        if (requestedClusterBytes != null && requestedClusterBytes >= sectorSize) {
            val spc = requestedClusterBytes / sectorSize
            if (spc > 0 && (spc and (spc - 1)) == 0) return spc
        }
        val targetBytes =
            when {
                volumeBytes <= 268_435_456L -> 4096
                volumeBytes <= 34_359_738_368L -> 32_768
                volumeBytes <= 2_199_023_255_552L -> 131_072
                else -> 262_144
            }
        return (targetBytes / sectorSize).coerceAtLeast(1)
    }

    private fun log2Exact(value: Int): Int {
        var shift = 0
        var cur = value
        while (cur > 1) {
            cur = cur ushr 1
            shift++
        }
        return shift
    }
}
