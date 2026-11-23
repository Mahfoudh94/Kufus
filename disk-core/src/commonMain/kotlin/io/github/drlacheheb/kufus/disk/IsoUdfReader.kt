package io.github.drlacheheb.kufus.disk

private const val UDF_SECTOR_SIZE = 2048
private const val UDF_AVDP_SECTOR = 256L
private const val UDF_MAX_VDS_SECTORS = 64
private const val UDF_MAX_AD_EXTENTS = 256
private const val UDF_MAX_INLINE_BYTES = 65_536L
private const val UDF_TAG_PVD = 1
private const val UDF_TAG_AVDP = 2
private const val UDF_TAG_PD = 5
private const val UDF_TAG_LVD = 6
private const val UDF_TAG_TD = 8
private const val UDF_TAG_FSD = 256
private const val UDF_TAG_FID = 257
private const val UDF_TAG_AED = 258
private const val UDF_TAG_FE = 261
private const val UDF_TAG_EFE = 266
private const val UDF_AD_SHORT = 0
private const val UDF_AD_LONG = 1
private const val UDF_AD_INLINE = 3
private const val UDF_EXTENT_RECORDED = 0
private const val UDF_EXTENT_NEXT_AD = 3
private const val UDF_FID_DIR = 0x02
private const val UDF_FID_DELETED = 0x04
private const val UDF_FID_PARENT = 0x08
private const val UDF_FID_FIXED_BYTES = 38

private const val ISO_PVD_SECTOR = 16L
private const val ISO_MAX_VD_SECTORS = 22L
private const val ISO_LABEL_OFFSET = 40
private const val ISO_LABEL_LEN = 32
private const val ISO_ROOT_RECORD_OFFSET = 156
private const val ISO_RECORD_FLAGS_OFFSET = 25
private const val ISO_NAME_LEN_OFFSET = 32
private const val ISO_NAME_OFFSET = 33
private const val ISO_DIR_FLAG = 0x02
private const val ISO_MULTI_EXTENT_FLAG = 0x80
private const val ISO_DIR_MAX_BYTES = 16L * 1024L * 1024L

private val cd001Signature = "CD001".encodeToByteArray()

data class ArchiveExtent(
    val offsetBytes: Long,
    val lengthBytes: Long,
    val isSparseZero: Boolean = false,
    val inlineData: ByteArray? = null,
)

data class ArchiveEntry(
    val relativePath: String,
    val isDirectory: Boolean,
    val sizeBytes: Long,
    val extents: List<ArchiveExtent>,
)

data class OpenedIsoUdfArchive(
    val isUdf: Boolean,
    val volumeLabel: String,
    val entries: List<ArchiveEntry>,
)

/**
 * Pure-Kotlin ECMA-167 UDF 1.02+ and ISO-9660 / Joliet image reader over [SeekableByteReader].
 */
object IsoUdfReader {
    fun open(reader: SeekableByteReader): OpenedIsoUdfArchive? {
        val totalBytes = reader.sizeBytes()
        if (totalBytes <= 0L) return null
        parseUdfArchive(totalBytes, reader)?.let { return it }
        return parseIso9660Archive(totalBytes, reader)
    }

    fun openEntryReader(
        reader: SeekableByteReader,
        entry: ArchiveEntry,
    ): SeekableByteReader = ArchiveExtentSeekableReader(reader, entry.extents, entry.sizeBytes)

    fun streamFile(
        reader: SeekableByteReader,
        entry: ArchiveEntry,
        buffer: ByteArray,
        shouldCancel: () -> Boolean = { false },
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        if (entry.isDirectory || entry.sizeBytes <= 0L) return
        var remaining = entry.sizeBytes
        var stopped = false
        for (extent in entry.extents) {
            if (remaining <= 0L || stopped) {
                stopped = true
            } else {
                if (shouldCancel()) {
                    throw FlashStepException("CANCELLED", "Operation cancelled while reading '${entry.relativePath}'")
                }
                remaining = emitSingleExtent(reader, extent, remaining, buffer, shouldCancel, onChunk)
            }
        }
    }

    private fun emitSingleExtent(
        reader: SeekableByteReader,
        extent: ArchiveExtent,
        remaining: Long,
        buffer: ByteArray,
        shouldCancel: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit,
    ): Long {
        val copyLen = minOf(remaining, extent.lengthBytes)
        return when {
            extent.inlineData != null -> {
                val count = minOf(copyLen.toInt(), extent.inlineData.size)
                if (count > 0) onChunk(extent.inlineData, count)
                remaining - count.toLong()
            }
            extent.isSparseZero -> {
                streamZeroExtent(copyLen, buffer, shouldCancel, onChunk)
                remaining - copyLen
            }
            else -> {
                streamDiskExtent(reader, extent.offsetBytes, copyLen, buffer, shouldCancel, onChunk)
                remaining - copyLen
            }
        }
    }

    private fun streamZeroExtent(
        lengthBytes: Long,
        buffer: ByteArray,
        shouldCancel: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        buffer.fill(0)
        var left = lengthBytes
        while (left > 0L) {
            if (shouldCancel()) throw FlashStepException("CANCELLED", "Operation cancelled")
            val step = minOf(buffer.size.toLong(), left).toInt()
            onChunk(buffer, step)
            left -= step.toLong()
        }
    }

    private fun streamDiskExtent(
        reader: SeekableByteReader,
        offsetBytes: Long,
        lengthBytes: Long,
        buffer: ByteArray,
        shouldCancel: () -> Boolean,
        onChunk: (ByteArray, Int) -> Unit,
    ) {
        var cursor = 0L
        while (cursor < lengthBytes) {
            if (shouldCancel()) throw FlashStepException("CANCELLED", "Operation cancelled")
            val want = minOf(buffer.size.toLong(), lengthBytes - cursor).toInt()
            val chunk = reader.readAt(offsetBytes + cursor, want)
            if (chunk.isEmpty()) {
                throw FlashStepException("CORRUPT_IMAGE", "Unexpected EOF reading ISO/UDF extent at ${offsetBytes + cursor}")
            }
            onChunk(chunk, chunk.size)
            cursor += chunk.size.toLong()
        }
    }
}

private data class UdfLongAd(
    val lengthBytes: Long,
    val block: Long,
    val partitionRef: Int,
)

private data class UdfPartitionInfo(
    val partitionNumber: Int,
    val startSector: Long,
    val lengthSectors: Long,
)

private data class UdfVdsResult(
    val volumeLabel: String,
    val blockSize: Long,
    val fsdAd: UdfLongAd,
    val partitions: Map<Int, UdfPartitionInfo>,
    val partitionRefMap: Map<Int, Int>,
)

private data class UdfVolumeContext(
    val totalBytes: Long,
    val reader: SeekableByteReader,
    val blockSize: Long,
    val partitions: Map<Int, UdfPartitionInfo>,
    val partitionRefMap: Map<Int, Int>,
) {
    fun resolveByteOffset(
        partitionRef: Int,
        block: Long,
    ): Long? {
        val mappedNum = partitionRefMap[partitionRef] ?: partitionRef
        val part = partitions[mappedNum] ?: partitions[partitionRef] ?: return null
        if (block < 0L || block >= part.lengthSectors) return null
        val byteOffset = part.startSector * UDF_SECTOR_SIZE + block * blockSize
        return byteOffset.takeIf { it in 0L until totalBytes }
    }
}

private fun parseUdfArchive(
    totalBytes: Long,
    reader: SeekableByteReader,
): OpenedIsoUdfArchive? {
    val avdp = findUdfAvdp(totalBytes, reader) ?: return null
    val vdsLenBytes = readUInt32LeLong(avdp, 16)
    val vdsStartSector = readUInt32LeLong(avdp, 20)
    val vds = readUdfVds(totalBytes, reader, vdsStartSector, vdsLenBytes) ?: return null

    val ctx = UdfVolumeContext(totalBytes, reader, vds.blockSize, vds.partitions, vds.partitionRefMap)
    val fsdOffset = ctx.resolveByteOffset(vds.fsdAd.partitionRef, vds.fsdAd.block) ?: return null
    val fsd = reader.readAt(fsdOffset, UDF_SECTOR_SIZE)
    if (udfTagId(fsd) != UDF_TAG_FSD) return null

    val rootIcb = readUdfLongAd(fsd, 400)
    val entries = mutableListOf<ArchiveEntry>()
    val visitedIcbs = HashSet<Long>()
    traverseUdfDirectory(ctx, rootIcb, parentPath = "", visitedIcbs = visitedIcbs, outEntries = entries)
    return OpenedIsoUdfArchive(isUdf = true, volumeLabel = vds.volumeLabel, entries = entries)
}

private fun findUdfAvdp(
    totalBytes: Long,
    reader: SeekableByteReader,
): ByteArray? {
    val totalSectors = totalBytes / UDF_SECTOR_SIZE
    val candidates =
        buildList {
            add(UDF_AVDP_SECTOR)
            if (totalSectors > UDF_AVDP_SECTOR) add(totalSectors - UDF_AVDP_SECTOR)
            if (totalSectors > 1L) add(totalSectors - 1L)
        }
    return candidates
        .asSequence()
        .mapNotNull { sector ->
            val offset = sector * UDF_SECTOR_SIZE
            if (offset + UDF_SECTOR_SIZE <= totalBytes) {
                reader.readAt(offset, UDF_SECTOR_SIZE).takeIf { udfTagId(it) == UDF_TAG_AVDP }
            } else {
                null
            }
        }.firstOrNull()
}

private fun readUdfVds(
    totalBytes: Long,
    reader: SeekableByteReader,
    vdsStartSector: Long,
    vdsLenBytes: Long,
): UdfVdsResult? {
    if (vdsStartSector <= 0L || vdsLenBytes < UDF_SECTOR_SIZE) return null
    val sectorCount = minOf((vdsLenBytes / UDF_SECTOR_SIZE).toInt(), UDF_MAX_VDS_SECTORS)
    var primaryLabel = ""
    var logicalLabel = ""
    var logicalBlockSize = UDF_SECTOR_SIZE.toLong()
    var fsdAd: UdfLongAd? = null
    val partitions = mutableMapOf<Int, UdfPartitionInfo>()
    val partitionRefMap = mutableMapOf<Int, Int>()

    var index = 0
    var terminated = false
    while (index < sectorCount && !terminated) {
        val off = (vdsStartSector + index.toLong()) * UDF_SECTOR_SIZE
        if (off + UDF_SECTOR_SIZE > totalBytes) {
            terminated = true
        } else {
            val sector = reader.readAt(off, UDF_SECTOR_SIZE)
            when (udfTagId(sector)) {
                UDF_TAG_TD -> terminated = true
                UDF_TAG_PVD -> primaryLabel = decodeFixedUdfDString(sector, 24, 32).ifBlank { primaryLabel }
                UDF_TAG_PD -> {
                    val partNum = readUInt16LeInt(sector, 22)
                    partitions[partNum] = UdfPartitionInfo(partNum, readUInt32LeLong(sector, 188), readUInt32LeLong(sector, 192))
                }
                UDF_TAG_LVD -> {
                    logicalLabel = decodeFixedUdfDString(sector, 84, 128).ifBlank { logicalLabel }
                    logicalBlockSize = parseValidBlockSize(readUInt32LeLong(sector, 212), logicalBlockSize)
                    fsdAd = readUdfLongAd(sector, 248)
                    parseLvdType1PartitionMaps(sector, partitionRefMap)
                }
            }
        }
        index++
    }
    val validFsd = fsdAd ?: return null
    return UdfVdsResult(logicalLabel.ifBlank { primaryLabel }.trim(), logicalBlockSize, validFsd, partitions, partitionRefMap)
}

private fun parseLvdType1PartitionMaps(
    lvdSector: ByteArray,
    outMap: MutableMap<Int, Int>,
) {
    if (lvdSector.size < 440) return
    val mapTableLen = readUInt32LeInt(lvdSector, 264)
    val mapCount = readUInt32LeInt(lvdSector, 268)
    if (mapTableLen <= 0 || mapCount <= 0 || 440 + mapTableLen > lvdSector.size) return
    var pos = 440
    val end = 440 + mapTableLen
    var refIndex = 0
    while (refIndex < mapCount && pos + 2 <= end) {
        val mapType = lvdSector[pos].toInt() and 0xFF
        val mapLen = lvdSector[pos + 1].toInt() and 0xFF
        if (mapLen <= 0 || pos + mapLen > end) {
            pos = end
        } else {
            if (mapType == 1 && mapLen >= 6) {
                outMap[refIndex] = readUInt16LeInt(lvdSector, pos + 4)
            }
            pos += mapLen
            refIndex++
        }
    }
}

private fun parseValidBlockSize(
    candidate: Long,
    fallback: Long,
): Long = if (candidate >= 512L && (candidate and (candidate - 1L)) == 0L) candidate else fallback

private fun traverseUdfDirectory(
    ctx: UdfVolumeContext,
    dirIcb: UdfLongAd,
    parentPath: String,
    visitedIcbs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
) {
    val icbKey = (dirIcb.partitionRef.toLong() shl 48) or dirIcb.block
    if (!visitedIcbs.add(icbKey)) return
    val fileEntry = readUdfFileEntry(ctx, dirIcb) ?: return
    if (fileEntry.infoLength > ISO_DIR_MAX_BYTES) return

    val blockSize = ctx.blockSize.toInt().coerceAtLeast(UDF_SECTOR_SIZE)
    val dirBytes = readUdfExtentsBytes(ctx, fileEntry.extents, fileEntry.infoLength.toInt())
    var offset = 0
    while (offset + UDF_FID_FIXED_BYTES <= dirBytes.size) {
        val stepNext =
            when (udfTagId(dirBytes, offset)) {
                0 -> {
                    val remInBlock = blockSize - (offset % blockSize)
                    offset + remInBlock
                }
                UDF_TAG_FID ->
                    processUdfFidRecord(ctx, dirBytes, offset, parentPath, visitedIcbs, outEntries)
                else -> -1
            }
        if (stepNext <= offset) return
        offset = stepNext
    }
}

private fun processUdfFidRecord(
    ctx: UdfVolumeContext,
    dirBytes: ByteArray,
    offset: Int,
    parentPath: String,
    visitedIcbs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
): Int {
    val characteristics = dirBytes[offset + 18].toInt() and 0xFF
    val fileIdLen = dirBytes[offset + 19].toInt() and 0xFF
    val childIcb = readUdfLongAd(dirBytes, offset + 20)
    val implUseLen = readUInt16LeInt(dirBytes, offset + 36)
    val recordBytes = ((38 + implUseLen + fileIdLen + 3) / 4) * 4
    if (recordBytes <= 0 || offset + recordBytes > dirBytes.size) return -1

    val skipped = (characteristics and (UDF_FID_PARENT or UDF_FID_DELETED)) != 0 || fileIdLen <= 0
    if (!skipped) {
        val rawName = decodeUdfDString(dirBytes, offset + 38 + implUseLen, fileIdLen)
        val safeName = sanitizeArchiveSegment(rawName)
        if (safeName != null) {
            emitUdfFidChild(ctx, safeName, parentPath, (characteristics and UDF_FID_DIR) != 0, childIcb, visitedIcbs, outEntries)
        }
    }
    return offset + recordBytes
}

private fun emitUdfFidChild(
    ctx: UdfVolumeContext,
    safeName: String,
    parentPath: String,
    isDir: Boolean,
    childIcb: UdfLongAd,
    visitedIcbs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
) {
    val relPath = if (parentPath.isEmpty()) safeName else "$parentPath/$safeName"
    if (isDir) {
        outEntries.add(ArchiveEntry(relPath, isDirectory = true, sizeBytes = 0L, extents = emptyList()))
        traverseUdfDirectory(ctx, childIcb, relPath, visitedIcbs, outEntries)
    } else {
        readUdfFileEntry(ctx, childIcb)?.let { childFe ->
            outEntries.add(ArchiveEntry(relPath, isDirectory = false, sizeBytes = childFe.infoLength, extents = childFe.extents))
        }
    }
}

private data class ParsedUdfFileEntry(
    val infoLength: Long,
    val extents: List<ArchiveExtent>,
)

private fun readUdfFileEntry(
    ctx: UdfVolumeContext,
    icb: UdfLongAd,
): ParsedUdfFileEntry? {
    val feOffset = ctx.resolveByteOffset(icb.partitionRef, icb.block) ?: return null
    val blockLen = minOf(ctx.blockSize, ctx.totalBytes - feOffset).toInt()
    val fe = ctx.reader.readAt(feOffset, blockLen)
    val headerBase =
        when (udfTagId(fe)) {
            UDF_TAG_FE -> 176
            UDF_TAG_EFE -> 216
            else -> return null
        }
    if (fe.size < headerBase) return null

    val adType = readUInt16LeInt(fe, 34) and 0x07
    val infoLength = readUInt64LeLong(fe, 56)
    val eaLen = readUInt32LeInt(fe, headerBase - 8)
    val adLen = readUInt32LeInt(fe, headerBase - 4)
    val adStart = headerBase + eaLen
    if (eaLen < 0 || adLen < 0 || adStart + adLen > fe.size) return null

    if (adType == UDF_AD_INLINE) {
        val inlineSize = minOf(infoLength, adLen.toLong(), UDF_MAX_INLINE_BYTES).toInt()
        val inlineBytes = fe.copyOfRange(adStart, adStart + inlineSize)
        val extent = ArchiveExtent(offsetBytes = 0L, lengthBytes = inlineBytes.size.toLong(), inlineData = inlineBytes)
        return ParsedUdfFileEntry(infoLength = inlineBytes.size.toLong(), extents = listOf(extent))
    }

    val extents = collectUdfAllocationDescriptors(ctx, icb.partitionRef, fe, adStart, adLen, adType)
    return ParsedUdfFileEntry(infoLength = infoLength, extents = extents)
}

private fun collectUdfAllocationDescriptors(
    ctx: UdfVolumeContext,
    defaultPartition: Int,
    initialBuffer: ByteArray,
    initialStart: Int,
    initialLength: Int,
    adType: Int,
): List<ArchiveExtent> {
    if (adType != UDF_AD_SHORT && adType != UDF_AD_LONG) return emptyList()
    val stepBytes = if (adType == UDF_AD_SHORT) 8 else 16
    val extents = mutableListOf<ArchiveExtent>()
    var buffer = initialBuffer
    var pos = initialStart
    var end = initialStart + initialLength
    var extentDepth = 0
    var stop = false

    while (!stop && pos + stepBytes <= end && extentDepth < UDF_MAX_AD_EXTENTS) {
        val rawLen = readUInt32LeLong(buffer, pos)
        val flags = ((rawLen ushr 30) and 0x03L).toInt()
        val lenBytes = rawLen and 0x3FFF_FFFFL
        val block = readUInt32LeLong(buffer, pos + 4)
        val partRef = if (adType == UDF_AD_LONG) readUInt16LeInt(buffer, pos + 8) else defaultPartition

        if (lenBytes == 0L) {
            stop = true
        } else if (flags == UDF_EXTENT_NEXT_AD) {
            val aed = loadNextAllocationExtent(ctx, partRef, block, lenBytes)
            if (aed == null) {
                stop = true
            } else {
                buffer = aed
                pos = 24
                end = minOf(24 + readUInt32LeInt(aed, 20), aed.size)
                extentDepth++
            }
        } else {
            val byteOffset = ctx.resolveByteOffset(partRef, block)
            val sparse = flags != UDF_EXTENT_RECORDED || byteOffset == null
            extents.add(ArchiveExtent(offsetBytes = byteOffset ?: 0L, lengthBytes = lenBytes, isSparseZero = sparse))
            pos += stepBytes
        }
    }
    return extents
}

private fun loadNextAllocationExtent(
    ctx: UdfVolumeContext,
    partRef: Int,
    block: Long,
    lenBytes: Long,
): ByteArray? {
    val nextOffset = ctx.resolveByteOffset(partRef, block) ?: return null
    val aedLen = minOf(lenBytes, 65_536L, ctx.totalBytes - nextOffset).toInt()
    val aed = ctx.reader.readAt(nextOffset, aedLen)
    return aed.takeIf { udfTagId(it) == UDF_TAG_AED && it.size >= 24 }
}

private fun readUdfExtentsBytes(
    ctx: UdfVolumeContext,
    extents: List<ArchiveExtent>,
    expectedSize: Int,
): ByteArray {
    if (expectedSize <= 0) return ByteArray(0)
    val result = ByteArray(expectedSize)
    var written = 0
    var idx = 0
    while (idx < extents.size && written < expectedSize) {
        val extent = extents[idx]
        val takeLen = minOf((expectedSize - written).toLong(), extent.lengthBytes).toInt()
        written += copyDirectoryExtent(ctx, extent, takeLen, result, written)
        idx++
    }
    return result
}

private fun copyDirectoryExtent(
    ctx: UdfVolumeContext,
    extent: ArchiveExtent,
    takeLen: Int,
    dest: ByteArray,
    destOffset: Int,
): Int =
    when {
        extent.inlineData != null -> {
            val count = minOf(takeLen, extent.inlineData.size)
            extent.inlineData.copyInto(dest, destinationOffset = destOffset, endIndex = count)
            count
        }
        extent.isSparseZero -> takeLen
        else -> {
            val chunk = ctx.reader.readAt(extent.offsetBytes, takeLen)
            chunk.copyInto(dest, destinationOffset = destOffset)
            chunk.size
        }
    }

private fun udfTagId(
    sector: ByteArray,
    offset: Int = 0,
): Int {
    if (sector.size < offset + 16) return -1
    return readUInt16LeInt(sector, offset)
}

private fun readUdfLongAd(
    bytes: ByteArray,
    offset: Int,
): UdfLongAd {
    val len = readUInt32LeLong(bytes, offset) and 0x3FFF_FFFFL
    val block = readUInt32LeLong(bytes, offset + 4)
    val part = readUInt16LeInt(bytes, offset + 8)
    return UdfLongAd(lengthBytes = len, block = block, partitionRef = part)
}

private fun decodeFixedUdfDString(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String {
    if (length <= 1 || offset < 0 || offset + length > bytes.size) return ""
    val declaredByteCount = bytes[offset + length - 1].toInt() and 0xFF
    val usableLength =
        if (declaredByteCount in 1 until length) {
            declaredByteCount
        } else {
            findNullOrEnd(bytes, offset + 1, offset + length - 1) - offset
        }
    return decodeUdfDString(bytes, offset, usableLength.coerceAtLeast(0))
}

private fun decodeUdfDString(
    bytes: ByteArray,
    offset: Int,
    length: Int,
): String {
    if (length <= 0 || offset < 0 || offset + length > bytes.size) return ""
    val compressionId = bytes[offset].toInt() and 0xFF
    val contentStart = offset + 1
    val contentEnd = findNullOrEnd(bytes, contentStart, offset + length)
    return when (compressionId) {
        8 -> decodeIso88591(bytes, contentStart, contentEnd)
        16 -> decodeUtf16Be(bytes, contentStart, contentEnd)
        else -> decodeIso88591(bytes, offset, offset + length).trim('\u0000', ' ')
    }
}

private fun findNullOrEnd(
    bytes: ByteArray,
    start: Int,
    limit: Int,
): Int {
    var cursor = limit
    while (cursor > start && bytes[cursor - 1] == 0.toByte()) {
        cursor--
    }
    return cursor
}

private class ArchiveExtentSeekableReader(
    private val backing: SeekableByteReader,
    private val extents: List<ArchiveExtent>,
    private val totalSize: Long,
) : SeekableByteReader {
    override fun sizeBytes(): Long = totalSize

    override fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0L || offset >= totalSize || length <= 0) return ByteArray(0)
        val want = minOf(length.toLong(), totalSize - offset).toInt()
        val out = ByteArray(want)
        var outPos = 0
        var logicalStart = 0L

        for (extent in extents) {
            if (outPos >= want) break
            val extentEnd = logicalStart + extent.lengthBytes
            if (offset + outPos < extentEnd && offset + want > logicalStart) {
                val relOffset = (offset + outPos - logicalStart).coerceAtLeast(0L)
                val take = minOf((want - outPos).toLong(), extent.lengthBytes - relOffset).toInt()
                copyFromExtent(extent, relOffset, take, out, outPos)
                outPos += take
            }
            logicalStart = extentEnd
        }
        return if (outPos == out.size) out else out.copyOf(outPos)
    }

    private fun copyFromExtent(
        extent: ArchiveExtent,
        relOffset: Long,
        take: Int,
        dest: ByteArray,
        destOffset: Int,
    ) {
        when {
            extent.inlineData != null -> {
                val start = relOffset.toInt()
                val end = minOf(start + take, extent.inlineData.size)
                if (start < end) {
                    extent.inlineData.copyInto(dest, destinationOffset = destOffset, startIndex = start, endIndex = end)
                }
            }
            extent.isSparseZero -> Unit
            else -> {
                val chunk = backing.readAt(extent.offsetBytes + relOffset, take)
                chunk.copyInto(dest, destinationOffset = destOffset, endIndex = minOf(chunk.size, take))
            }
        }
    }
}

private fun parseIso9660Archive(
    totalBytes: Long,
    reader: SeekableByteReader,
): OpenedIsoUdfArchive? {
    val (pvd, jolietSvd) = findIsoVolumeDescriptors(totalBytes, reader)
    val chosenVd = jolietSvd ?: pvd ?: return null
    val isJoliet = chosenVd === jolietSvd
    val label =
        if (isJoliet) {
            decodeUtf16Be(chosenVd, ISO_LABEL_OFFSET, ISO_LABEL_OFFSET + ISO_LABEL_LEN).trim()
        } else {
            decodeIso88591(chosenVd, ISO_LABEL_OFFSET, ISO_LABEL_OFFSET + ISO_LABEL_LEN).trim()
        }

    val rootLba = readUInt32LeLong(chosenVd, ISO_ROOT_RECORD_OFFSET + 2)
    val rootLen = readUInt32LeLong(chosenVd, ISO_ROOT_RECORD_OFFSET + 10)
    val entries = mutableListOf<ArchiveEntry>()
    val visitedDirs = HashSet<Long>()
    traverseIsoDirectory(
        totalBytes = totalBytes,
        reader = reader,
        extentLba = rootLba,
        dataLength = rootLen,
        isJoliet = isJoliet,
        parentPath = "",
        visitedDirs = visitedDirs,
        outEntries = entries,
    )
    return OpenedIsoUdfArchive(isUdf = false, volumeLabel = label, entries = entries)
}

private fun findIsoVolumeDescriptors(
    totalBytes: Long,
    reader: SeekableByteReader,
): Pair<ByteArray?, ByteArray?> {
    var pvd: ByteArray? = null
    var jolietSvd: ByteArray? = null
    var sectorIdx = ISO_PVD_SECTOR
    var finished = false

    while (sectorIdx <= ISO_MAX_VD_SECTORS && !finished) {
        val offset = sectorIdx * UDF_SECTOR_SIZE
        val sector = if (offset + UDF_SECTOR_SIZE <= totalBytes) reader.readAt(offset, UDF_SECTOR_SIZE) else null
        if (sector == null || !startsWithBytes(sector, cd001Signature, offset = 1)) {
            finished = true
        } else {
            val typeCode = sector[0].toInt() and 0xFF
            if (typeCode == 1 && pvd == null) pvd = sector
            if (typeCode == 2 && jolietSvd == null && isJolietEscape(sector)) jolietSvd = sector
            if (typeCode == 0xFF) finished = true
        }
        sectorIdx++
    }
    return pvd to jolietSvd
}

private fun isJolietEscape(sector: ByteArray): Boolean {
    if (sector.size < 91) return false
    return sector[88] == 0x25.toByte() &&
        sector[89] == 0x2F.toByte() &&
        (sector[90] == 0x40.toByte() || sector[90] == 0x43.toByte() || sector[90] == 0x45.toByte())
}

@Suppress("LongParameterList")
private fun traverseIsoDirectory(
    totalBytes: Long,
    reader: SeekableByteReader,
    extentLba: Long,
    dataLength: Long,
    isJoliet: Boolean,
    parentPath: String,
    visitedDirs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
) {
    if (extentLba <= 0L || dataLength <= 0L || dataLength > ISO_DIR_MAX_BYTES) return
    if (!visitedDirs.add(extentLba)) return
    val startOffset = extentLba * UDF_SECTOR_SIZE
    if (startOffset >= totalBytes) return
    val buffer = reader.readAt(startOffset, minOf(dataLength, totalBytes - startOffset).toInt())
    var offset = 0

    while (offset < buffer.size) {
        val recLen = buffer[offset].toInt() and 0xFF
        val nextOffset =
            if (recLen == 0) {
                ((offset / UDF_SECTOR_SIZE) + 1) * UDF_SECTOR_SIZE
            } else {
                parseIsoDirRecord(totalBytes, reader, buffer, offset, recLen, isJoliet, parentPath, visitedDirs, outEntries)
            }
        if (nextOffset <= offset) return
        offset = nextOffset
    }
}

@Suppress("LongParameterList")
private fun parseIsoDirRecord(
    totalBytes: Long,
    reader: SeekableByteReader,
    buffer: ByteArray,
    offset: Int,
    recLen: Int,
    isJoliet: Boolean,
    parentPath: String,
    visitedDirs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
): Int {
    if (recLen < ISO_NAME_OFFSET + 1 || offset + recLen > buffer.size) return -1
    val nameLen = buffer[offset + ISO_NAME_LEN_OFFSET].toInt() and 0xFF
    if (nameLen > 0 && offset + ISO_NAME_OFFSET + nameLen <= offset + recLen) {
        val firstByte = buffer[offset + ISO_NAME_OFFSET]
        val isSelfOrParent = nameLen == 1 && (firstByte == 0.toByte() || firstByte == 1.toByte())
        if (!isSelfOrParent) {
            val rawName =
                if (isJoliet) {
                    decodeUtf16Be(buffer, offset + ISO_NAME_OFFSET, offset + ISO_NAME_OFFSET + nameLen)
                } else {
                    decodeIso88591(buffer, offset + ISO_NAME_OFFSET, offset + ISO_NAME_OFFSET + nameLen)
                }.substringBefore(';')
            val safeName = sanitizeArchiveSegment(rawName)
            if (safeName != null) {
                emitIsoDirChild(totalBytes, reader, buffer, offset, isJoliet, parentPath, safeName, visitedDirs, outEntries)
            }
        }
    }
    return offset + recLen
}

@Suppress("LongParameterList")
private fun emitIsoDirChild(
    totalBytes: Long,
    reader: SeekableByteReader,
    buffer: ByteArray,
    offset: Int,
    isJoliet: Boolean,
    parentPath: String,
    safeName: String,
    visitedDirs: MutableSet<Long>,
    outEntries: MutableList<ArchiveEntry>,
) {
    val childLba = readUInt32LeLong(buffer, offset + 2)
    val childLen = readUInt32LeLong(buffer, offset + 10)
    val isDir = (buffer[offset + ISO_RECORD_FLAGS_OFFSET].toInt() and ISO_DIR_FLAG) != 0
    val relPath = if (parentPath.isEmpty()) safeName else "$parentPath/$safeName"
    if (isDir) {
        outEntries.add(ArchiveEntry(relPath, isDirectory = true, sizeBytes = 0L, extents = emptyList()))
        traverseIsoDirectory(totalBytes, reader, childLba, childLen, isJoliet, relPath, visitedDirs, outEntries)
    } else {
        val extent = ArchiveExtent(offsetBytes = childLba * UDF_SECTOR_SIZE, lengthBytes = childLen)
        val flags = buffer[offset + ISO_RECORD_FLAGS_OFFSET].toInt() and 0xFF
        val last = outEntries.lastOrNull()
        val shouldCoalesce = last != null && !last.isDirectory && last.relativePath == relPath
        if (shouldCoalesce) {
            outEntries[outEntries.lastIndex] =
                last.copy(
                    sizeBytes = last.sizeBytes + childLen,
                    extents = last.extents + extent,
                )
        } else if ((flags and ISO_MULTI_EXTENT_FLAG) != 0 || childLen >= 0L) {
            outEntries.add(ArchiveEntry(relPath, isDirectory = false, sizeBytes = childLen, extents = listOf(extent)))
        }
    }
}

private fun sanitizeArchiveSegment(raw: String): String? {
    val clean = raw.trim('\u0000').trim()
    if (clean.isEmpty() || clean == "." || clean == "..") return null
    return if (hasInvalidPathSeparator(clean)) null else clean
}

private fun hasInvalidPathSeparator(name: String): Boolean = name.contains('/') || name.contains('\\')

private fun decodeIso88591(
    bytes: ByteArray,
    start: Int,
    end: Int,
): String {
    if (start >= end) return ""
    return buildString(end - start) {
        for (i in start until end) {
            append((bytes[i].toInt() and 0xFF).toChar())
        }
    }
}

private fun decodeUtf16Be(
    bytes: ByteArray,
    start: Int,
    end: Int,
): String {
    val evenEnd = start + ((end - start) / 2) * 2
    if (start >= evenEnd) return ""
    return buildString((evenEnd - start) / 2) {
        var i = start
        while (i + 1 < evenEnd) {
            val code = readUInt16BeInt(bytes, i)
            if (code == 0) break
            append(code.toChar())
            i += 2
        }
    }
}
