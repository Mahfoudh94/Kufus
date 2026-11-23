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
private const val WIM_COPY_BUFFER_BYTES = 1_048_576
private const val WIM_HDR_OFFSET_FLAGS = 0x10
private const val WIM_HDR_OFFSET_PART_NUM = 0x28
private const val WIM_HDR_OFFSET_TOTAL_PARTS = 0x2A
private const val WIM_HDR_OFFSET_LOOKUP = 0x30
private const val WIM_HDR_OFFSET_XML = 0x48
private const val WIM_HDR_OFFSET_BOOT_META = 0x60
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
        if (lookupEntries.any { it.resource.isSolid } || largestEntry > maxPartBytes) {
            return WimPlanSummary(totalParts = 1, isSolidOrUnsupported = true, largestResourceBytes = largestEntry)
        }
        val partCount = partitionEntriesAcrossParts(lookupEntries, header, maxPartBytes).size
        return WimPlanSummary(totalParts = partCount, isSolidOrUnsupported = false, largestResourceBytes = largestEntry)
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
        val updatedLookupBytes = buildSpannedLookupTableBytes(assignments, WIM_HEADER_SIZE.toLong())

        writeAllParts(
            reader = reader,
            wimBaseOffset = wimBaseOffset,
            baseHeaderBytes = headerBytes,
            baseXmlBytes = xmlBytes,
            assignments = assignments,
            updatedLookupBytes = updatedLookupBytes,
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
        if (lookupHdr.sizeBytes <= 0L || lookupHdr.sizeBytes % WIM_LOOKUP_ENTRY_SIZE != 0L) return null
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
        if (xmlHdr.sizeBytes <= 0L || xmlHdr.offsetBytes + xmlHdr.sizeBytes > wimSize) return ByteArray(0)
        return reader.readAt(baseOffset + xmlHdr.offsetBytes, xmlHdr.sizeBytes.toInt())
    }

    private data class PartAssignment(
        val partIndexOneBased: Int,
        val entries: List<WimLookupEntry>,
        val dataBytes: Long,
    )

    private fun partitionEntriesAcrossParts(
        entries: List<WimLookupEntry>,
        headerBytes: ByteArray,
        maxPartBytes: Long,
    ): List<PartAssignment> {
        val hdr = parseHeaderInfo(headerBytes)
        val xmlBytesLen = hdr.xmlHeader.sizeBytes.coerceAtLeast(0L)
        val lookupBytesLen = entries.size.toLong() * WIM_LOOKUP_ENTRY_SIZE
        val overheadPerPart = WIM_HEADER_SIZE.toLong() + lookupBytesLen + xmlBytesLen + 4096L
        val maxPayloadPerPart = (maxPartBytes - overheadPerPart).coerceAtLeast(1_048_576L)

        val metaEntries = entries.filter { it.resource.isMetadata }
        val dataEntries = entries.filterNot { it.resource.isMetadata }

        val parts = mutableListOf<PartAssignment>()
        var currentEntries = mutableListOf<WimLookupEntry>()
        var currentBytes = 0L

        for (meta in metaEntries) {
            currentEntries.add(meta)
            currentBytes += meta.resource.sizeBytes
        }

        for (entry in dataEntries) {
            val size = entry.resource.sizeBytes
            if (size > maxPayloadPerPart) {
                throw FlashStepException(
                    code = "WIM_ENTRY_TOO_LARGE",
                    message = "Single WIM stream ($size bytes) exceeds FAT32 split limit ($maxPayloadPerPart bytes).",
                )
            }
            if (currentEntries.isNotEmpty() && currentBytes + size > maxPayloadPerPart) {
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

    private fun buildSpannedLookupTableBytes(
        assignments: List<PartAssignment>,
        payloadStartOffset: Long,
    ): ByteArray {
        val totalEntries = assignments.sumOf { it.entries.size }
        val out = ByteArray(totalEntries * WIM_LOOKUP_ENTRY_SIZE)
        var outOffset = 0

        for (part in assignments) {
            var cursor = payloadStartOffset
            for (entry in part.entries) {
                val res = entry.resource
                val flags = res.flags or WIM_RES_FLAG_SPANNED
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
        updatedLookupBytes: ByteArray,
        totalParts: Int,
        shouldCancel: () -> Boolean,
        onBytesWritten: (Long) -> Unit,
        openPartSink: (Int, String) -> WimPartSink,
    ) {
        val bufferSize = minOf(WIM_COPY_BUFFER_BYTES, WIM_COPY_BUFFER_BYTES)
        val origHdr = parseHeaderInfo(baseHeaderBytes)

        for (part in assignments) {
            val fileName = formatSplitFileName(part.partIndexOneBased)
            val sink = openPartSink(part.partIndexOneBased, fileName)
            try {
                val lookupOffset = WIM_HEADER_SIZE.toLong() + part.dataBytes
                val xmlOffset = lookupOffset + updatedLookupBytes.size.toLong()
                val partHeader =
                    buildPartHeader(
                        baseHeaderBytes = baseHeaderBytes,
                        partNumber = part.partIndexOneBased,
                        totalParts = totalParts,
                        lookupOffset = lookupOffset,
                        lookupSize = updatedLookupBytes.size.toLong(),
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
                        chunkMax = bufferSize,
                        shouldCancel = shouldCancel,
                        sink = sink,
                        onBytesWritten = onBytesWritten,
                    )
                }

                if (updatedLookupBytes.isNotEmpty()) {
                    sink.write(updatedLookupBytes, updatedLookupBytes.size)
                    onBytesWritten(updatedLookupBytes.size.toLong())
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
            flags = 0,
            resOffsetBytes = lookupOffset,
            originalSizeBytes = lookupSize,
        )
        writeResHdr(
            target = hdr,
            offset = WIM_HDR_OFFSET_XML,
            sizeBytes = xmlSize,
            flags = 0,
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
        }
        writeResHdr(hdr, WIM_HDR_OFFSET_INTEGRITY, 0L, 0, 0L, 0L)
        return hdr
    }

    private fun streamEntryPayload(
        reader: SeekableByteReader,
        sourceOffset: Long,
        lengthBytes: Long,
        chunkMax: Int,
        shouldCancel: () -> Boolean,
        sink: WimPartSink,
        onBytesWritten: (Long) -> Unit,
    ) {
        var cursor = 0L
        while (cursor < lengthBytes) {
            if (shouldCancel()) throw FlashStepException("CANCELLED", "Cancelled while splitting WIM")
            val step = minOf(chunkMax.toLong(), lengthBytes - cursor).toInt()
            val bytes = reader.readAt(sourceOffset + cursor, step)
            if (bytes.isEmpty()) {
                throw FlashStepException("INVALID_WIM", "Unexpected EOF reading WIM resource at $sourceOffset")
            }
            sink.write(bytes, bytes.size)
            cursor += bytes.size.toLong()
            onBytesWritten(bytes.size.toLong())
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
