package io.github.drlacheheb.kufus.disk.archive

import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.io.SeekableByteReader
import io.github.drlacheheb.kufus.disk.io.readUInt16LeInt
import io.github.drlacheheb.kufus.disk.io.readUInt32LeInt
import io.github.drlacheheb.kufus.disk.io.readUInt32LeLong
import io.github.drlacheheb.kufus.disk.io.readUInt64LeLong
import io.github.drlacheheb.kufus.disk.io.startsWithBytes
import io.github.drlacheheb.kufus.disk.io.writeLeInt
import io.github.drlacheheb.kufus.disk.io.writeLeLong
import io.github.drlacheheb.kufus.disk.io.writeLeShort

private const val WIM_HEADER_SIZE = 208
private const val WIM_LOOKUP_ENTRY_SIZE = 50
private const val WIM_DEFAULT_PART_MAX_BYTES = 3_800_000_000L
private const val WIM_HEADER_FLAG_SPANNED = 0x00000008L
private const val WIM_HEADER_FLAG_SOLID = 0x20000000L
private const val WIM_RES_FLAG_FREE = 0x01
private const val WIM_RES_FLAG_METADATA = 0x02
private const val WIM_RES_FLAG_COMPRESSED = 0x04
private const val WIM_RES_FLAG_SPANNED = 0x08
private const val WIM_RES_FLAG_SOLID = 0x10
private const val WIM_COPY_BUFFER_BYTES = 2_097_152
private const val WIM_HDR_OFFSET_FLAGS = 0x10
private const val WIM_HDR_OFFSET_PART_NUM = 0x28
private const val WIM_HDR_OFFSET_TOTAL_PARTS = 0x2A
private const val WIM_HDR_OFFSET_LOOKUP = 0x30
private const val WIM_HDR_OFFSET_XML = 0x48
private const val WIM_HDR_OFFSET_BOOT_META = 0x60
private const val WIM_HDR_OFFSET_BOOT_INDEX = 0x78
private const val WIM_HDR_OFFSET_INTEGRITY = 0x7C

private val wimMagic = byteArrayOf('M'.code.toByte(), 'S'.code.toByte(), 'W'.code.toByte(), 'I'.code.toByte(), 'M'.code.toByte(), 0, 0, 0)

internal data class WimResHdr(
    val sizeBytes: Long,
    val flags: Int,
    val offsetBytes: Long,
    val originalSizeBytes: Long,
) {
    val isFree: Boolean
        get() = (flags and WIM_RES_FLAG_FREE) != 0

    val isMetadata: Boolean
        get() = (flags and WIM_RES_FLAG_METADATA) != 0

    val isSolid: Boolean
        get() = (flags and WIM_RES_FLAG_SOLID) != 0
}

internal data class WimLookupEntry(
    val resource: WimResHdr,
    val partNumber: Int,
    val refCount: Long,
    val sha1Hash: ByteArray,
)

data class WimPlanSummary(
    val totalParts: Int,
    val isSolidOrUnsupported: Boolean,
    val largestResourceBytes: Long,
)

interface WimPartSink {
    fun write(
        bytes: ByteArray,
        length: Int,
    )

    fun close()
}

/**
 * Pure-Kotlin MS-WIM splitter creating spanned `install.swm`, `install2.swm`, ... volumes
 * so Windows Installer images larger than 4 GiB fit onto FAT32 without external tools.
 */
object WimSplitter {
    fun formatSplitFileName(partIndexOneBased: Int): String = if (partIndexOneBased <= 1) "install.swm" else "install$partIndexOneBased.swm"

    fun inspectSplitCapability(
        reader: SeekableByteReader,
        wimBaseOffset: Long = 0L,
        wimTotalSize: Long = reader.sizeBytes(),
        maxPartBytes: Long = WIM_DEFAULT_PART_MAX_BYTES,
    ): WimPlanSummary {
        val header = readHeader(reader, wimBaseOffset, wimTotalSize) ?: return WimPlanSummary(1, isSolidOrUnsupported = true, 0L)
        if (!canLosslesslySplitHeader(header)) {
            return WimPlanSummary(totalParts = 1, isSolidOrUnsupported = true, largestResourceBytes = wimTotalSize)
        }
        val lookupEntries = parseLookupTable(reader, wimBaseOffset, wimTotalSize, header) ?: return WimPlanSummary(1, true, wimTotalSize)
        val largestEntry = lookupEntries.maxOfOrNull { it.resource.sizeBytes } ?: 0L
        if (lookupEntries.any { it.resource.isSolid }) {
            return WimPlanSummary(totalParts = 1, isSolidOrUnsupported = true, largestResourceBytes = largestEntry)
        }
        val assignments =
            partitionEntriesOrNull(lookupEntries, header, maxPartBytes)
                ?: return WimPlanSummary(totalParts = 1, isSolidOrUnsupported = true, largestResourceBytes = largestEntry)
        return WimPlanSummary(totalParts = assignments.size, isSolidOrUnsupported = false, largestResourceBytes = largestEntry)
    }

    fun split(
        reader: SeekableByteReader,
        wimBaseOffset: Long = 0L,
        wimTotalSize: Long = reader.sizeBytes(),
        maxPartBytes: Long = WIM_DEFAULT_PART_MAX_BYTES,
        shouldCancel: () -> Boolean = { false },
        onBytesWritten: (Long) -> Unit = {},
        openPartSink: (partIndexOneBased: Int, fileName: String) -> WimPartSink,
    ): Int {
        val headerBytes =
            readHeader(reader, wimBaseOffset, wimTotalSize)
                ?: throw FlashStepException("INVALID_WIM", "Invalid MS-WIM header")
        val hdr = parseHeaderInfo(headerBytes)
        val entries = loadValidatedLookupEntries(reader, wimBaseOffset, wimTotalSize, headerBytes)
        val xmlBytes = readXmlRegion(reader, wimBaseOffset, wimTotalSize, hdr.xmlHeader)
        val assignments = partitionEntriesAcrossParts(entries, headerBytes, maxPartBytes)
        val totalParts = assignments.size

        writeAllParts(
            reader = reader,
            wimBaseOffset = wimBaseOffset,
            baseHeaderBytes = headerBytes,
            baseXmlBytes = xmlBytes,
            assignments = assignments,
            totalParts = totalParts,
            shouldCancel = shouldCancel,
            onBytesWritten = onBytesWritten,
            openPartSink = openPartSink,
        )
        return totalParts
    }

    private fun readHeader(
        reader: SeekableByteReader,
        baseOffset: Long,
        wimSize: Long,
    ): ByteArray? {
        if (wimSize < WIM_HEADER_SIZE) return null
        val header = reader.readAt(baseOffset, WIM_HEADER_SIZE)
        if (header.size < WIM_HEADER_SIZE) return null
        if (!startsWithBytes(header, wimMagic)) return null
        if (readUInt32LeInt(header, 8) != WIM_HEADER_SIZE) return null
        return header
    }

    private fun canLosslesslySplitHeader(header: ByteArray): Boolean {
        val flags = readUInt32LeLong(header, WIM_HDR_OFFSET_FLAGS)
        if ((flags and WIM_HEADER_FLAG_SOLID) != 0L) return false
        if (readUInt16LeInt(header, WIM_HDR_OFFSET_TOTAL_PARTS) > 1) return false
        val lookupHdr = readResHdr(header, WIM_HDR_OFFSET_LOOKUP)
        val xmlHdr = readResHdr(header, WIM_HDR_OFFSET_XML)
        val blockingResFlags = WIM_RES_FLAG_COMPRESSED or WIM_RES_FLAG_SOLID
        if ((lookupHdr.flags and blockingResFlags) != 0) return false
        if ((xmlHdr.flags and blockingResFlags) != 0) return false
        return true
    }

    private fun ensureLosslessHeader(headerBytes: ByteArray) {
        if (!canLosslesslySplitHeader(headerBytes)) {
            throw FlashStepException(
                code = "UNSUPPORTED_SOLID_WIM",
                message = "Solid or compressed-header WIM/ESD image cannot be split without recompression.",
            )
        }
    }

    private fun loadValidatedLookupEntries(
        reader: SeekableByteReader,
        wimBaseOffset: Long,
        wimTotalSize: Long,
        headerBytes: ByteArray,
    ): List<WimLookupEntry> {
        ensureLosslessHeader(headerBytes)
        val entries =
            parseLookupTable(reader, wimBaseOffset, wimTotalSize, headerBytes)
                ?: throw FlashStepException("INVALID_WIM", "Corrupt or unreadable WIM lookup table.")
        if (entries.any { it.resource.isSolid }) {
            throw FlashStepException(
                code = "UNSUPPORTED_SOLID_WIM",
                message = "Install image contains solid LZMS chunks exceeding non-solid WIM splitting.",
            )
        }
        return entries
    }

    private data class ParsedHeader(
        val lookupHeader: WimResHdr,
        val xmlHeader: WimResHdr,
        val bootMetadataHeader: WimResHdr,
        val flags: Long,
    )

    private fun parseHeaderInfo(header: ByteArray): ParsedHeader =
        ParsedHeader(
            lookupHeader = readResHdr(header, WIM_HDR_OFFSET_LOOKUP),
            xmlHeader = readResHdr(header, WIM_HDR_OFFSET_XML),
            bootMetadataHeader = readResHdr(header, WIM_HDR_OFFSET_BOOT_META),
            flags = readUInt32LeLong(header, WIM_HDR_OFFSET_FLAGS),
        )

    private fun parseLookupTable(
        reader: SeekableByteReader,
        baseOffset: Long,
        wimSize: Long,
        header: ByteArray,
    ): List<WimLookupEntry>? {
        val lookupHdr = readResHdr(header, WIM_HDR_OFFSET_LOOKUP)
        val invalidLookupSize =
            lookupHdr.sizeBytes <= 0L ||
                lookupHdr.sizeBytes > Int.MAX_VALUE.toLong() ||
                lookupHdr.sizeBytes % WIM_LOOKUP_ENTRY_SIZE != 0L
        if (invalidLookupSize) return null
        if (lookupHdr.offsetBytes < WIM_HEADER_SIZE || lookupHdr.offsetBytes + lookupHdr.sizeBytes > wimSize) return null
        val raw = reader.readAt(baseOffset + lookupHdr.offsetBytes, lookupHdr.sizeBytes.toInt())
        if (raw.size.toLong() != lookupHdr.sizeBytes) return null
        val count = raw.size / WIM_LOOKUP_ENTRY_SIZE
        return buildList(count) {
            for (index in 0 until count) {
                val off = index * WIM_LOOKUP_ENTRY_SIZE
                val res = readResHdr(raw, off)
                val partNum = readUInt16LeInt(raw, off + 24)
                val refCount = readUInt32LeLong(raw, off + 26)
                val hash = raw.copyOfRange(off + 30, off + 50)
                if (!res.isFree) {
                    add(WimLookupEntry(resource = res, partNumber = partNum, refCount = refCount, sha1Hash = hash))
                }
            }
        }
    }

    private fun readXmlRegion(
        reader: SeekableByteReader,
        baseOffset: Long,
        wimSize: Long,
        xmlHdr: WimResHdr,
    ): ByteArray {
        val invalidXmlSize = xmlHdr.sizeBytes <= 0L || xmlHdr.sizeBytes > Int.MAX_VALUE.toLong()
        if (invalidXmlSize || xmlHdr.offsetBytes + xmlHdr.sizeBytes > wimSize) return ByteArray(0)
        return reader.readAt(baseOffset + xmlHdr.offsetBytes, xmlHdr.sizeBytes.toInt())
    }

    private data class PartAssignment(
        val partIndexOneBased: Int,
        val entries: List<WimLookupEntry>,
        val dataBytes: Long,
    )

    private fun partTotalBytes(
        baseOverhead: Long,
        dataBytes: Long,
        entryCount: Int,
    ): Long = baseOverhead + dataBytes + entryCount.toLong() * WIM_LOOKUP_ENTRY_SIZE

    private fun partitionEntriesOrNull(
        entries: List<WimLookupEntry>,
        headerBytes: ByteArray,
        maxPartBytes: Long,
    ): List<PartAssignment>? {
        val hdr = parseHeaderInfo(headerBytes)
        val baseOverhead = WIM_HEADER_SIZE.toLong() + hdr.xmlHeader.sizeBytes.coerceAtLeast(0L)

        val metaEntries = entries.filter { it.resource.isMetadata }
        val dataEntries = entries.filterNot { it.resource.isMetadata }

        val parts = mutableListOf<PartAssignment>()
        var currentEntries = mutableListOf<WimLookupEntry>()
        var currentBytes = 0L

        for (meta in metaEntries) {
            currentEntries.add(meta)
            currentBytes += meta.resource.sizeBytes
        }
        if (partTotalBytes(baseOverhead, currentBytes, currentEntries.size) > maxPartBytes) {
            return null
        }

        for (entry in dataEntries) {
            val size = entry.resource.sizeBytes
            if (partTotalBytes(baseOverhead, size, 1) > maxPartBytes) {
                return null
            }
            val projectedBytes = partTotalBytes(baseOverhead, currentBytes + size, currentEntries.size + 1)
            if (currentEntries.isNotEmpty() && projectedBytes > maxPartBytes) {
                parts.add(PartAssignment(parts.size + 1, currentEntries, currentBytes))
                currentEntries = mutableListOf()
                currentBytes = 0L
            }
            currentEntries.add(entry)
            currentBytes += size
        }

        if (currentEntries.isNotEmpty() || parts.isEmpty()) {
            parts.add(PartAssignment(parts.size + 1, currentEntries, currentBytes))
        }
        return parts
    }

    private fun partitionEntriesAcrossParts(
        entries: List<WimLookupEntry>,
        headerBytes: ByteArray,
        maxPartBytes: Long,
    ): List<PartAssignment> =
        partitionEntriesOrNull(entries, headerBytes, maxPartBytes)
            ?: throw FlashStepException(
                code = "WIM_ENTRY_TOO_LARGE",
                message = "WIM metadata or single resource stream exceeds FAT32 split limit ($maxPartBytes bytes).",
            )

    private fun buildPartLookupTableBytes(
        part: PartAssignment,
        payloadStartOffset: Long,
    ): ByteArray {
        val out = ByteArray(part.entries.size * WIM_LOOKUP_ENTRY_SIZE)
        var outOffset = 0
        var cursor = payloadStartOffset

        for (entry in part.entries) {
            val res = entry.resource
            val flags = res.flags and WIM_RES_FLAG_SPANNED.inv()
            writeResHdr(
                target = out,
                offset = outOffset,
                sizeBytes = res.sizeBytes,
                flags = flags,
                resOffsetBytes = cursor,
                originalSizeBytes = res.originalSizeBytes,
            )
            writeLeShort(out, outOffset + 24, part.partIndexOneBased)
            writeLeInt(out, outOffset + 26, entry.refCount.toInt())
            entry.sha1Hash.copyInto(out, destinationOffset = outOffset + 30)
            cursor += res.sizeBytes
            outOffset += WIM_LOOKUP_ENTRY_SIZE
        }
        return out
    }

    @Suppress("LongParameterList")
    private fun writeAllParts(
        reader: SeekableByteReader,
        wimBaseOffset: Long,
        baseHeaderBytes: ByteArray,
        baseXmlBytes: ByteArray,
        assignments: List<PartAssignment>,
        totalParts: Int,
        shouldCancel: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        openPartSink: (Int, String) -> WimPartSink,
    ) {
        val copyBuffer = ByteArray(WIM_COPY_BUFFER_BYTES)
        val outBuffer = ByteArray(WIM_COPY_BUFFER_BYTES)
        val origHdr = parseHeaderInfo(baseHeaderBytes)

        for (part in assignments) {
            val fileName = formatSplitFileName(part.partIndexOneBased)
            val rawSink = openPartSink(part.partIndexOneBased, fileName)
            val sink = BufferedWimPartSink(rawSink, outBuffer)
            try {
                val partLookupBytes = buildPartLookupTableBytes(part, WIM_HEADER_SIZE.toLong())
                val lookupOffset = WIM_HEADER_SIZE.toLong() + part.dataBytes
                val xmlOffset = lookupOffset + partLookupBytes.size.toLong()
                val partHeader =
                    buildPartHeader(
                        baseHeaderBytes = baseHeaderBytes,
                        partNumber = part.partIndexOneBased,
                        totalParts = totalParts,
                        lookupOffset = lookupOffset,
                        lookupSize = partLookupBytes.size.toLong(),
                        xmlOffset = xmlOffset,
                        xmlSize = baseXmlBytes.size.toLong(),
                        bootMetadataOffset =
                            if (part.partIndexOneBased == 1) remapBootMetadataOffset(part.entries, origHdr.bootMetadataHeader) else 0L,
                        bootMetadataHdr = origHdr.bootMetadataHeader,
                    )
                sink.write(partHeader, partHeader.size)
                onBytesWritten(partHeader.size.toLong())

                for (entry in part.entries) {
                    streamEntryPayload(
                        reader = reader,
                        sourceOffset = wimBaseOffset + entry.resource.offsetBytes,
                        lengthBytes = entry.resource.sizeBytes,
                        copyBuffer = copyBuffer,
                        shouldCancel = shouldCancel,
                        sink = sink,
                        onBytesWritten = onBytesWritten,
                    )
                }

                if (partLookupBytes.isNotEmpty()) {
                    sink.write(partLookupBytes, partLookupBytes.size)
                    onBytesWritten(partLookupBytes.size.toLong())
                }
                if (baseXmlBytes.isNotEmpty()) {
                    sink.write(baseXmlBytes, baseXmlBytes.size)
                    onBytesWritten(baseXmlBytes.size.toLong())
                }
            } finally {
                sink.close()
            }
        }
    }

    private fun remapBootMetadataOffset(
        partOneEntries: List<WimLookupEntry>,
        origBootHdr: WimResHdr,
    ): Long {
        if (origBootHdr.sizeBytes <= 0L) return 0L
        var cursor = WIM_HEADER_SIZE.toLong()
        for (entry in partOneEntries) {
            if (entry.resource.offsetBytes == origBootHdr.offsetBytes && entry.resource.sizeBytes == origBootHdr.sizeBytes) {
                return cursor
            }
            cursor += entry.resource.sizeBytes
        }
        return 0L
    }

    @Suppress("LongParameterList")
    private fun buildPartHeader(
        baseHeaderBytes: ByteArray,
        partNumber: Int,
        totalParts: Int,
        lookupOffset: Long,
        lookupSize: Long,
        xmlOffset: Long,
        xmlSize: Long,
        bootMetadataOffset: Long,
        bootMetadataHdr: WimResHdr,
    ): ByteArray {
        val hdr = baseHeaderBytes.copyOf()
        val flags = readUInt32LeLong(hdr, WIM_HDR_OFFSET_FLAGS) or WIM_HEADER_FLAG_SPANNED
        writeLeInt(hdr, WIM_HDR_OFFSET_FLAGS, flags.toInt())
        writeLeShort(hdr, WIM_HDR_OFFSET_PART_NUM, partNumber)
        writeLeShort(hdr, WIM_HDR_OFFSET_TOTAL_PARTS, totalParts)

        writeResHdr(
            target = hdr,
            offset = WIM_HDR_OFFSET_LOOKUP,
            sizeBytes = lookupSize,
            flags = if (lookupSize > 0L) WIM_RES_FLAG_METADATA else 0,
            resOffsetBytes = if (lookupSize > 0L) lookupOffset else 0L,
            originalSizeBytes = lookupSize,
        )
        writeResHdr(
            target = hdr,
            offset = WIM_HDR_OFFSET_XML,
            sizeBytes = xmlSize,
            flags = if (xmlSize > 0L) WIM_RES_FLAG_METADATA else 0,
            resOffsetBytes = if (xmlSize > 0L) xmlOffset else 0L,
            originalSizeBytes = xmlSize,
        )
        if (partNumber == 1 && bootMetadataOffset > 0L) {
            writeResHdr(
                target = hdr,
                offset = WIM_HDR_OFFSET_BOOT_META,
                sizeBytes = bootMetadataHdr.sizeBytes,
                flags = bootMetadataHdr.flags,
                resOffsetBytes = bootMetadataOffset,
                originalSizeBytes = bootMetadataHdr.originalSizeBytes,
            )
        } else {
            writeResHdr(hdr, WIM_HDR_OFFSET_BOOT_META, 0L, 0, 0L, 0L)
            writeLeInt(hdr, WIM_HDR_OFFSET_BOOT_INDEX, 0)
        }
        writeResHdr(hdr, WIM_HDR_OFFSET_INTEGRITY, 0L, 0, 0L, 0L)
        return hdr
    }

    private fun streamEntryPayload(
        reader: SeekableByteReader,
        sourceOffset: Long,
        lengthBytes: Long,
        copyBuffer: ByteArray,
        shouldCancel: () -> Boolean,
        sink: WimPartSink,
        onBytesWritten: (Long) -> Unit,
    ) {
        var cursor = 0L
        while (cursor < lengthBytes) {
            if (shouldCancel()) throw FlashStepException("CANCELLED", "Cancelled while splitting WIM")
            val step = minOf(copyBuffer.size.toLong(), lengthBytes - cursor).toInt()
            val read = reader.readInto(sourceOffset + cursor, copyBuffer, destinationOffset = 0, length = step)
            if (read <= 0) {
                throw FlashStepException("INVALID_WIM", "Unexpected EOF reading WIM resource at $sourceOffset")
            }
            sink.write(copyBuffer, read)
            cursor += read.toLong()
            onBytesWritten(read.toLong())
        }
    }

    private fun readResHdr(
        bytes: ByteArray,
        offset: Int,
    ): WimResHdr {
        val rawSizeAndFlags = readUInt64LeLong(bytes, offset)
        val size = rawSizeAndFlags and 0x00FF_FFFF_FFFF_FFFFL
        val flags = ((rawSizeAndFlags ushr 56) and 0xFFL).toInt()
        val resOffset = readUInt64LeLong(bytes, offset + 8)
        val origSize = readUInt64LeLong(bytes, offset + 16)
        return WimResHdr(sizeBytes = size, flags = flags, offsetBytes = resOffset, originalSizeBytes = origSize)
    }

    private fun writeResHdr(
        target: ByteArray,
        offset: Int,
        sizeBytes: Long,
        flags: Int,
        resOffsetBytes: Long,
        originalSizeBytes: Long,
    ) {
        val packed = (sizeBytes and 0x00FF_FFFF_FFFF_FFFFL) or ((flags.toLong() and 0xFFL) shl 56)
        writeLeLong(target, offset, packed)
        writeLeLong(target, offset + 8, resOffsetBytes)
        writeLeLong(target, offset + 16, originalSizeBytes)
    }
}

private class BufferedWimPartSink(
    private val delegate: WimPartSink,
    private val buffer: ByteArray,
) : WimPartSink {
    private var buffered = 0

    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        if (length <= 0) return
        var srcOffset = 0
        var remaining = length
        while (remaining > 0) {
            if (buffered == 0 && remaining >= buffer.size) {
                val slice = if (srcOffset == 0) bytes else bytes.copyOfRange(srcOffset, srcOffset + remaining)
                delegate.write(slice, remaining)
                return
            }
            val space = buffer.size - buffered
            val toCopy = minOf(space, remaining)
            bytes.copyInto(buffer, destinationOffset = buffered, startIndex = srcOffset, endIndex = srcOffset + toCopy)
            buffered += toCopy
            srcOffset += toCopy
            remaining -= toCopy
            if (buffered == buffer.size) {
                flush()
            }
        }
    }

    private fun flush() {
        if (buffered > 0) {
            delegate.write(buffer, buffered)
            buffered = 0
        }
    }

    override fun close() {
        try {
            flush()
        } finally {
            delegate.close()
        }
    }
}
