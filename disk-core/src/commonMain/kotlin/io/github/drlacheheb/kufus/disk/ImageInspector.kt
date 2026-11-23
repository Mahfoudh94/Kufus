package io.github.drlacheheb.kufus.disk

private const val SECTOR_512 = 512
private const val MBR_SIGNATURE_OFFSET = 510
private const val MBR_SIG_BYTE_0 = 0x55
private const val MBR_SIG_BYTE_1 = 0xAA
private const val ISO_SECTOR_SIZE = 2048
private const val ISO_PVD_OFFSET = 16L * ISO_SECTOR_SIZE
private const val ISO_SECONDARY_VD_OFFSET = 17L * ISO_SECTOR_SIZE
private const val ISO_VD_MAX_SEARCH_SECTORS = 6
private const val HEADER_READ_BYTES = 1024
private const val GZIP_MIN_BYTES = 18L
private const val MAX_32BIT_BYTES = 4_294_967_296L
private const val ZSTD_MAGIC_BYTES = 4
private const val GZIP_MAGIC_0 = 0x1F
private const val GZIP_MAGIC_1 = 0x8B
private const val GZIP_DEFLATE_METHOD = 0x08
private const val BYTE_MASK = 0xFF

private const val XZ_HEADER_SIZE = 12
private const val XZ_FOOTER_SIZE = 12
private const val XZ_MIN_STREAM_SIZE = 32L
private const val XZ_MAX_INDEX_BYTES = 16L * 1024L * 1024L
private const val XZ_MAX_INDEX_RECORDS = 1_000_000L
private const val XZ_MAX_VLI_BYTES = 9
private const val XZ_STREAM_PADDING_UNIT = 4L
private const val XZ_FOOTER_MAGIC_0 = 0x59
private const val XZ_FOOTER_MAGIC_1 = 0x5A

private val xzMagic = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)
private val zstdMagic = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())
private val gptSignature = "EFI PART".encodeToByteArray()
private val isoSignature = "CD001".encodeToByteArray()
private val udfBeaSignature = "BEA01".encodeToByteArray()
private val udfNsr2Signature = "NSR02".encodeToByteArray()
private val udfNsr3Signature = "NSR03".encodeToByteArray()
private val elToritoSignature = "EL TORITO SPECIFICATION".encodeToByteArray()
private val windowsInstallPaths =
    setOf(
        "SOURCES/INSTALL.WIM",
        "SOURCES/INSTALL.ESD",
        "SOURCES/INSTALL.SWM",
        "X64/SOURCES/INSTALL.WIM",
        "X64/SOURCES/INSTALL.ESD",
        "X64/SOURCES/INSTALL.SWM",
        "X86/SOURCES/INSTALL.WIM",
        "X86/SOURCES/INSTALL.ESD",
        "X86/SOURCES/INSTALL.SWM",
    )

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
    return classifyUncompressedImage(path, initialHeader, totalBytes, reader)
}

private fun classifyUncompressedImage(
    path: String,
    initialHeader: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): ImageInfo {
    val archive = IsoUdfReader.open(reader)
    if (archive != null) {
        val windowsMeta = extractWindowsMetadataIfInstaller(archive, reader)
        if (windowsMeta != null) {
            return ImageInfo(
                path = path,
                sizeBytes = totalBytes,
                format = ImageFormat.WINDOWS_ISO,
                windowsMetadata = windowsMeta,
            )
        }
        val bootableIso =
            hasMbrSignature(initialHeader) ||
                hasGptSignature(initialHeader) ||
                hasElToritoBootRecord(totalBytes, reader)
        val format = if (bootableIso) ImageFormat.HYBRID_ISO else ImageFormat.UNKNOWN
        return ImageInfo(path = path, sizeBytes = totalBytes, format = format)
    }
    if (hasUdfDescriptors(totalBytes, reader)) {
        return ImageInfo(path = path, sizeBytes = totalBytes, format = ImageFormat.WINDOWS_ISO)
    }
    val rawFormat = if (hasMbrSignature(initialHeader) || hasGptSignature(initialHeader)) ImageFormat.RAW_IMAGE else ImageFormat.UNKNOWN
    return ImageInfo(path = path, sizeBytes = totalBytes, format = rawFormat)
}

private fun extractWindowsMetadataIfInstaller(
    archive: OpenedIsoUdfArchive,
    reader: SeekableByteReader,
): WindowsIsoMetadata? {
    val fileEntries = archive.entries.filterNot { it.isDirectory }
    val installEntry =
        fileEntries.firstOrNull { entry ->
            entry.relativePath.uppercase() in windowsInstallPaths
        } ?: return null

    val isWim = installEntry.relativePath.uppercase().endsWith(".WIM")
    val largestFile = fileEntries.maxOfOrNull { it.sizeBytes } ?: 0L
    val totalPayload = fileEntries.sumOf { it.sizeBytes }
    val needsSplit = largestFile > FAT32_MAX_FILE_BYTES
    val splittable =
        if (isWim) {
            val wimReader = IsoUdfReader.openEntryReader(reader, installEntry)
            !WimSplitter.inspectSplitCapability(wimReader).isSolidOrUnsupported
        } else {
            false
        }
    val hasUnattend = fileEntries.any { it.relativePath.equals("autounattend.xml", ignoreCase = true) }

    return WindowsIsoMetadata(
        volumeLabel = archive.volumeLabel,
        installImagePath = installEntry.relativePath,
        installImageSizeBytes = installEntry.sizeBytes,
        largestFileBytes = largestFile,
        totalPayloadBytes = totalPayload,
        isSplitRequiredForFat32 = needsSplit,
        isWimSplittable = splittable,
        hasExistingUnattendXml = hasUnattend,
    )
}

private fun detectCompressionFormat(header: ByteArray): CompressionFormat =
    when {
        isGzipHeader(header) -> CompressionFormat.GZIP
        startsWithBytes(header, xzMagic) -> CompressionFormat.XZ
        startsWithBytes(header, zstdMagic) -> CompressionFormat.ZSTD
        else -> CompressionFormat.NONE
    }

private fun isGzipHeader(header: ByteArray): Boolean =
    header.size >= 3 &&
        (header[0].toInt() and BYTE_MASK) == GZIP_MAGIC_0 &&
        (header[1].toInt() and BYTE_MASK) == GZIP_MAGIC_1 &&
        (header[2].toInt() and BYTE_MASK) == GZIP_DEFLATE_METHOD

private fun readUncompressedSizeHint(
    compression: CompressionFormat,
    header: ByteArray,
    totalBytes: Long,
    reader: SeekableByteReader,
): Long? =
    when (compression) {
        CompressionFormat.GZIP -> readGzipSizeHint(totalBytes, reader)
        CompressionFormat.ZSTD -> readZstdContentSize(header)
        CompressionFormat.XZ -> readXzSizeHint(totalBytes, reader)
        CompressionFormat.NONE -> null
    }

private fun readXzSizeHint(
    totalBytes: Long,
    reader: SeekableByteReader,
): Long? {
    if (totalBytes < XZ_MIN_STREAM_SIZE) return null
    var cursor = totalBytes
    var totalUncompressed = 0L
    var parsedStreams = 0

    while (cursor > 0L) {
        cursor = skipTrailingXzPadding(cursor, reader) ?: return null
        if (cursor == 0L) break
        val parsed = parseXzStreamIndexSummary(cursor, reader) ?: return null
        if (parsed.uncompressedBytes > Long.MAX_VALUE - totalUncompressed) return null
        totalUncompressed += parsed.uncompressedBytes
        cursor = parsed.streamStartOffset
        parsedStreams++
    }
    return totalUncompressed.takeIf { parsedStreams > 0 && it > 0L }
}

private data class XzStreamIndexSummary(
    val streamStartOffset: Long,
    val uncompressedBytes: Long,
)

private fun skipTrailingXzPadding(
    streamEnd: Long,
    reader: SeekableByteReader,
): Long? {
    var cursor = streamEnd
    while (cursor >= XZ_STREAM_PADDING_UNIT) {
        val word = reader.readAt(cursor - XZ_STREAM_PADDING_UNIT, XZ_STREAM_PADDING_UNIT.toInt())
        if (word.size < XZ_STREAM_PADDING_UNIT.toInt()) return null
        if (word.any { it != 0.toByte() }) return cursor
        cursor -= XZ_STREAM_PADDING_UNIT
    }
    return if (cursor == 0L) 0L else null
}

private fun parseXzStreamIndexSummary(
    streamEnd: Long,
    reader: SeekableByteReader,
): XzStreamIndexSummary? {
    if (streamEnd < XZ_MIN_STREAM_SIZE) return null
    val footerOffset = streamEnd - XZ_FOOTER_SIZE
    val footer = reader.readAt(footerOffset, XZ_FOOTER_SIZE)
    if (!isValidXzFooter(footer)) return null

    val backwardSizeBytes = (readUInt32LeLong(footer, 4) + 1L) * XZ_STREAM_PADDING_UNIT
    if (backwardSizeBytes <= 0L || backwardSizeBytes > XZ_MAX_INDEX_BYTES) return null
    val indexOffset = footerOffset - backwardSizeBytes
    if (indexOffset < XZ_HEADER_SIZE) return null

    val indexBytes = reader.readAt(indexOffset, backwardSizeBytes.toInt())
    if (indexBytes.size != backwardSizeBytes.toInt()) return null
    val parsedIndex = parseXzIndexRecords(indexBytes) ?: return null
    if (parsedIndex.paddedBlocksBytes > indexOffset - XZ_HEADER_SIZE) return null

    val streamStart = indexOffset - parsedIndex.paddedBlocksBytes - XZ_HEADER_SIZE
    val streamHeader = reader.readAt(streamStart, XZ_HEADER_SIZE)
    if (!isValidXzHeaderMatchingFooter(streamHeader, footer)) return null

    return XzStreamIndexSummary(
        streamStartOffset = streamStart,
        uncompressedBytes = parsedIndex.uncompressedBytes,
    )
}

private fun isValidXzFooter(footer: ByteArray): Boolean {
    if (footer.size < XZ_FOOTER_SIZE) return false
    if ((footer[10].toInt() and BYTE_MASK) != XZ_FOOTER_MAGIC_0 ||
        (footer[11].toInt() and BYTE_MASK) != XZ_FOOTER_MAGIC_1
    ) {
        return false
    }
    if ((footer[8].toInt() and BYTE_MASK) != 0 || (footer[9].toInt() and 0xF0) != 0) return false
    return Crc32.compute(footer, offset = 4, length = 6) == readUInt32LeLong(footer, 0)
}

private fun isValidXzHeaderMatchingFooter(
    header: ByteArray,
    footer: ByteArray,
): Boolean {
    if (header.size < XZ_HEADER_SIZE || !startsWithBytes(header, xzMagic)) return false
    if (header[6] != footer[8] || header[7] != footer[9]) return false
    return Crc32.compute(header, offset = 6, length = 2) == readUInt32LeLong(header, 8)
}

private data class ParsedXzIndex(
    val paddedBlocksBytes: Long,
    val uncompressedBytes: Long,
)

private data class XzIndexRecord(
    val paddedBlockBytes: Long,
    val uncompressedBytes: Long,
)

private fun parseXzIndexRecords(indexBytes: ByteArray): ParsedXzIndex? {
    if (indexBytes.size < 8 || indexBytes[0] != 0.toByte()) return null
    val crcOffset = indexBytes.size - 4
    if (Crc32.compute(indexBytes, offset = 0, length = crcOffset) != readUInt32LeLong(indexBytes, crcOffset)) {
        return null
    }
    val cursor = XzVliCursor(indexBytes, crcOffset).apply { position = 1 }
    val recordCount = cursor.nextVli() ?: return null
    if (recordCount !in 0L..XZ_MAX_INDEX_RECORDS) return null

    var paddedBlocksBytes = 0L
    var uncompressedBytes = 0L
    var remainingRecords = recordCount
    while (remainingRecords > 0L) {
        remainingRecords--
        val record = readSingleXzIndexRecord(cursor) ?: return null
        if (record.paddedBlockBytes > Long.MAX_VALUE - paddedBlocksBytes ||
            record.uncompressedBytes > Long.MAX_VALUE - uncompressedBytes
        ) {
            return null
        }
        paddedBlocksBytes += record.paddedBlockBytes
        uncompressedBytes += record.uncompressedBytes
    }
    if (!hasValidXzIndexPadding(indexBytes, cursor.position, crcOffset)) return null
    return ParsedXzIndex(paddedBlocksBytes = paddedBlocksBytes, uncompressedBytes = uncompressedBytes)
}

private fun readSingleXzIndexRecord(cursor: XzVliCursor): XzIndexRecord? {
    val unpaddedSize = cursor.nextVli() ?: return null
    val uncompressedSize = cursor.nextVli() ?: return null
    if (unpaddedSize <= 0L || uncompressedSize < 0L) return null
    val padded = ((unpaddedSize + 3L) / XZ_STREAM_PADDING_UNIT) * XZ_STREAM_PADDING_UNIT
    return XzIndexRecord(paddedBlockBytes = padded, uncompressedBytes = uncompressedSize)
}

private fun hasValidXzIndexPadding(
    indexBytes: ByteArray,
    position: Int,
    crcOffset: Int,
): Boolean {
    val expectedPadding = (XZ_STREAM_PADDING_UNIT.toInt() - (position % XZ_STREAM_PADDING_UNIT.toInt())) % XZ_STREAM_PADDING_UNIT.toInt()
    if (position + expectedPadding != crcOffset) return false
    return (position until crcOffset).all { idx -> indexBytes[idx] == 0.toByte() }
}

private class XzVliCursor(
    private val bytes: ByteArray,
    private val limit: Int,
) {
    var position: Int = 0

    fun nextVli(): Long? {
        if (position >= limit) return null
        val first = bytes[position++].toInt() and BYTE_MASK
        if ((first and 0x80) == 0) return first.toLong()
        var value = (first and 0x7F).toLong()
        var shift = 7
        repeat(XZ_MAX_VLI_BYTES - 1) {
            if (position >= limit) return null
            val current = bytes[position++].toInt() and BYTE_MASK
            if (current == 0) return null
            value = value or ((current and 0x7F).toLong() shl shift)
            if ((current and 0x80) == 0) return value
            shift += 7
        }
        return null
    }
}

private fun readGzipSizeHint(
    totalBytes: Long,
    reader: SeekableByteReader,
): Long? {
    if (totalBytes < GZIP_MIN_BYTES || totalBytes >= MAX_32BIT_BYTES) return null
    val trailer = reader.readAt(offset = totalBytes - 4L, length = 4)
    if (trailer.size < 4) return null
    val isize = readUInt32LeLong(trailer, 0)
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
        1 -> if (header.size >= offset + 2) readUInt16LeLong(header, offset) + 256L else null
        2 -> if (header.size >= offset + 4) readUInt32LeLong(header, offset) else null
        else -> if (header.size >= offset + 8) readUInt64LeLong(header, offset) else null
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
        if (typeCode == 0 && startsWithBytes(sector, isoSignature, offset = 1)) {
            if (startsWithBytes(sector, elToritoSignature, offset = 7)) return true
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
    val hasBea = startsWithBytes(first, udfBeaSignature, offset = 1)
    val hasNsr =
        startsWithBytes(second, udfNsr2Signature, offset = 1) ||
            startsWithBytes(second, udfNsr3Signature, offset = 1)
    return hasBea && hasNsr
}

private fun hasMbrSignature(header: ByteArray): Boolean =
    header.size >= SECTOR_512 &&
        (header[MBR_SIGNATURE_OFFSET].toInt() and BYTE_MASK) == MBR_SIG_BYTE_0 &&
        (header[MBR_SIGNATURE_OFFSET + 1].toInt() and BYTE_MASK) == MBR_SIG_BYTE_1

private fun hasGptSignature(header: ByteArray): Boolean = startsWithBytes(header, gptSignature, offset = SECTOR_512)
