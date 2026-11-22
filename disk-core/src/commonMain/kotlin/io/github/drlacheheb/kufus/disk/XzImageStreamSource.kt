package io.github.drlacheheb.kufus.disk

private const val XZ_HEADER_BYTES = 12
private const val XZ_FOOTER_BYTES = 12
private const val XZ_CHECK_NONE = 0x00
private const val XZ_CHECK_CRC32 = 0x01
private const val XZ_CHECK_CRC64 = 0x04
private const val XZ_CHECK_SHA256 = 0x0A
private const val XZ_FILTER_X86 = 0x04L
private const val XZ_FILTER_ARM64 = 0x0AL
private const val XZ_FILTER_LZMA2 = 0x21L
private const val XZ_MAX_DICTIONARY_BYTES = 128 * 1024 * 1024
private const val XZ_MIN_DICTIONARY_BYTES = 4096

private val xzStreamMagicBytes = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00)

/**
 * Pure-Kotlin streaming XZ (`.xz`) decompressor implementing [FlashImageSource].
 */
internal class XzImageStreamSource(
    source: StreamingByteSource,
    override val knownUncompressedSizeBytes: Long?,
) : FlashImageSource {
    private val input = BufferedByteInput(source)
    private var streamEnded = false
    private var closed = false

    private var activeCheckType = XZ_CHECK_NONE
    private var activeStreamFlags0 = 0
    private var activeStreamFlags1 = 0
    private var streamInitialized = false

    private val currentStreamRecords = mutableListOf<XzDecodedBlockRecord>()
    private var activeBlock: ActiveXzBlock? = null

    override fun readChunk(buffer: ByteArray): Int {
        if (closed || streamEnded || buffer.isEmpty()) return 0
        while (!streamEnded) {
            if (!streamInitialized && !openNextStream()) {
                streamEnded = true
                return 0
            }
            val block = activeBlock ?: openNextBlockOrFinishStream() ?: continue
            val produced = block.stage.readFiltered(buffer, destOffset = 0, maxLength = buffer.size)
            if (produced > 0) {
                block.updateOutput(buffer, 0, produced)
                return produced
            }
            finishActiveBlock(block)
        }
        return 0
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private fun openNextStream(): Boolean {
        val nextByte = input.readByteOrEof()
        if (nextByte < 0) return false

        val header = ByteArray(XZ_HEADER_BYTES)
        header[0] = nextByte.toByte()
        input.readFully(header, destOffset = 1, length = XZ_HEADER_BYTES - 1, errorCode = "CORRUPT_XZ", message = "Truncated XZ stream header")
        validateStreamHeader(header)
        streamInitialized = true
        currentStreamRecords.clear()
        return true
    }

    private fun validateStreamHeader(header: ByteArray) {
        val magicMatch = xzStreamMagicBytes.indices.all { idx -> header[idx] == xzStreamMagicBytes[idx] }
        requireStream(magicMatch, "INVALID_XZ") { "Invalid XZ stream magic header" }
        val flags0 = header[6].toInt() and 0xFF
        val flags1 = header[7].toInt() and 0xFF
        requireStream(flags0 == 0 && (flags1 and 0xF0) == 0, "INVALID_XZ") { "Unsupported XZ stream flags" }
        val expectedCrc = Crc32.compute(header, offset = 6, length = 2)
        requireStream(expectedCrc == readLittleEndianU32(header, 8), "CORRUPT_XZ") { "XZ stream header CRC32 mismatch" }
        activeCheckType = flags1 and 0x0F
        requireStream(
            activeCheckType in listOf(XZ_CHECK_NONE, XZ_CHECK_CRC32, XZ_CHECK_CRC64, XZ_CHECK_SHA256),
            "UNSUPPORTED_XZ_CHECK",
        ) { "Unsupported XZ check type: $activeCheckType" }
        activeStreamFlags0 = flags0
        activeStreamFlags1 = flags1
    }

    private fun openNextBlockOrFinishStream(): ActiveXzBlock? {
        val headerSizeByte = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF waiting for XZ block or index")
        if (headerSizeByte == 0x00) {
            verifyStreamIndexAndFooter()
            streamInitialized = false
            consumeInterStreamPadding()
            return null
        }

        val blockHeaderSize = (headerSizeByte + 1) * 4
        val blockHeader = ByteArray(blockHeaderSize)
        blockHeader[0] = headerSizeByte.toByte()
        input.readFully(
            blockHeader,
            destOffset = 1,
            length = blockHeaderSize - 1,
            errorCode = "CORRUPT_XZ",
            message = "Unexpected EOF inside XZ block header",
        )
        val spec = parseBlockHeader(blockHeader)
        val payloadStartOffset = input.totalBytesConsumed
        val lzma2 = XzLzma2Decoder(input, spec.dictionarySize)
        val stage = XzFilteredDecoderStage(lzma2, spec.bcjTransforms)
        val block =
            ActiveXzBlock(
                headerSize = blockHeaderSize,
                expectedCompressedSize = spec.expectedCompressedSize,
                expectedUncompressedSize = spec.expectedUncompressedSize,
                payloadStartOffset = payloadStartOffset,
                stage = stage,
                check = XzBlockCheckAccumulator.forType(activeCheckType),
            )
        activeBlock = block
        return block
    }

    private fun finishActiveBlock(block: ActiveXzBlock) {
        val compressedSize = input.totalBytesConsumed - block.payloadStartOffset
        requireStream(
            block.expectedCompressedSize == null || compressedSize == block.expectedCompressedSize,
            "CORRUPT_XZ",
        ) { "XZ block compressed size mismatch ($compressedSize != ${block.expectedCompressedSize})" }
        requireStream(
            block.expectedUncompressedSize == null || block.uncompressedWritten == block.expectedUncompressedSize,
            "CORRUPT_XZ",
        ) { "XZ block uncompressed size mismatch (${block.uncompressedWritten} != ${block.expectedUncompressedSize})" }
        val padLen = ((4L - (compressedSize % 4L)) % 4L).toInt()
        repeat(padLen) {
            val padByte = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF in XZ block padding")
            requireStream(padByte == 0, "CORRUPT_XZ") { "Non-zero byte in XZ block padding" }
        }
        verifyBlockChecksum(block)
        val unpaddedSize = block.headerSize.toLong() + compressedSize + block.check.byteLength.toLong()
        currentStreamRecords += XzDecodedBlockRecord(unpaddedSize = unpaddedSize, uncompressedSize = block.uncompressedWritten)
        activeBlock = null
    }

    private fun verifyBlockChecksum(block: ActiveXzBlock) {
        val checkLen = block.check.byteLength
        if (checkLen == 0) return
        val expectedBytes = ByteArray(checkLen)
        input.readFully(expectedBytes, errorCode = "CORRUPT_XZ", message = "Unexpected EOF reading XZ block check")
        requireStream(expectedBytes.contentEquals(block.check.digestBytes()), "CORRUPT_XZ") {
            "XZ block checksum mismatch"
        }
    }

    private fun verifyStreamIndexAndFooter() {
        val crc = Crc32().apply { update(byteArrayOf(0x00)) }
        var indexBodyBytes = 1L
        val recordCount = readCheckedVli(crc).also { indexBodyBytes += it.bytesConsumed }.value
        requireStream(recordCount == currentStreamRecords.size.toLong(), "CORRUPT_XZ") {
            "XZ index record count mismatch ($recordCount != ${currentStreamRecords.size})"
        }
        for (record in currentStreamRecords) {
            val unpaddedVli = readCheckedVli(crc).also { indexBodyBytes += it.bytesConsumed }.value
            val uncompressedVli = readCheckedVli(crc).also { indexBodyBytes += it.bytesConsumed }.value
            requireStream(unpaddedVli == record.unpaddedSize && uncompressedVli == record.uncompressedSize, "CORRUPT_XZ") {
                "XZ index record mismatch"
            }
        }
        val padLen = ((4L - (indexBodyBytes % 4L)) % 4L).toInt()
        repeat(padLen) {
            val padByte = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF in XZ index padding")
            requireStream(padByte == 0, "CORRUPT_XZ") { "Non-zero byte in XZ index padding" }
            crc.update(byteArrayOf(0x00))
            indexBodyBytes++
        }
        val expectedIndexCrc = input.readUInt32LeOrThrow("CORRUPT_XZ", "Unexpected EOF reading XZ index CRC32").toLong()
        requireStream(crc.value() == expectedIndexCrc, "CORRUPT_XZ") { "XZ index CRC32 mismatch" }
        verifyStreamFooter(indexSizeBytes = indexBodyBytes + 4L)
    }

    private fun verifyStreamFooter(indexSizeBytes: Long) {
        val footer = ByteArray(XZ_FOOTER_BYTES)
        input.readFully(footer, errorCode = "CORRUPT_XZ", message = "Truncated XZ stream footer")
        val magicValid = (footer[10].toInt() and 0xFF) == 0x59 && (footer[11].toInt() and 0xFF) == 0x5A
        requireStream(magicValid, "CORRUPT_XZ") { "Invalid XZ stream footer magic" }
        requireStream(Crc32.compute(footer, offset = 4, length = 6) == readLittleEndianU32(footer, 0), "CORRUPT_XZ") {
            "XZ stream footer CRC32 mismatch"
        }
        val backwardSize = (readLittleEndianU32(footer, 4) + 1L) * 4L
        requireStream(backwardSize == indexSizeBytes, "CORRUPT_XZ") { "XZ stream footer backward size mismatch" }
        val flags0 = footer[8].toInt() and 0xFF
        val flags1 = footer[9].toInt() and 0xFF
        requireStream(flags0 == activeStreamFlags0 && flags1 == activeStreamFlags1, "CORRUPT_XZ") {
            "XZ stream footer flags do not match header"
        }
    }

    private fun consumeInterStreamPadding() {
        var zeroCount = 0
        while (true) {
            val next = input.readByteOrEof()
            if (next < 0) {
                if (zeroCount % 4 != 0) throw FlashStepException("CORRUPT_XZ", "Invalid trailing XZ stream padding")
                streamEnded = true
                return
            }
            if (next == 0) {
                zeroCount++
            } else {
                if (zeroCount % 4 != 0) throw FlashStepException("CORRUPT_XZ", "Unaligned XZ inter-stream padding")
                val header = ByteArray(XZ_HEADER_BYTES)
                header[0] = next.toByte()
                input.readFully(
                    header,
                    destOffset = 1,
                    length = XZ_HEADER_BYTES - 1,
                    errorCode = "CORRUPT_XZ",
                    message = "Truncated concatenated XZ stream header",
                )
                validateStreamHeader(header)
                streamInitialized = true
                currentStreamRecords.clear()
                return
            }
        }
    }

    private fun readCheckedVli(crc: Crc32): VliReadResult {
        val b0 = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF reading XZ VLI")
        crc.update(byteArrayOf(b0.toByte()))
        if ((b0 and 0x80) == 0) return VliReadResult(b0.toLong(), 1)
        var value = (b0 and 0x7F).toLong()
        var shift = 7
        for (idx in 1 until 9) {
            val b = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF reading multi-byte XZ VLI")
            if (b == 0) throw FlashStepException("CORRUPT_XZ", "Non-minimal XZ VLI encoding")
            crc.update(byteArrayOf(b.toByte()))
            value = value or ((b and 0x7F).toLong() shl shift)
            if ((b and 0x80) == 0) return VliReadResult(value, idx + 1)
            shift += 7
        }
        throw FlashStepException("CORRUPT_XZ", "XZ VLI exceeded maximum 9-byte length")
    }
}

private data class VliReadResult(
    val value: Long,
    val bytesConsumed: Int,
)

private data class XzDecodedBlockRecord(
    val unpaddedSize: Long,
    val uncompressedSize: Long,
)

private data class XzBlockSpec(
    val expectedCompressedSize: Long?,
    val expectedUncompressedSize: Long?,
    val dictionarySize: Int,
    val bcjTransforms: List<XzByteTransform>,
)

private class ActiveXzBlock(
    val headerSize: Int,
    val expectedCompressedSize: Long?,
    val expectedUncompressedSize: Long?,
    val payloadStartOffset: Long,
    val stage: XzFilteredDecoderStage,
    val check: XzBlockCheckAccumulator,
) {
    var uncompressedWritten: Long = 0L

    fun updateOutput(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ) {
        uncompressedWritten += length.toLong()
        check.update(buffer, offset, length)
    }
}

private sealed interface XzBlockCheckAccumulator {
    val byteLength: Int

    fun update(
        data: ByteArray,
        offset: Int,
        length: Int,
    )

    fun digestBytes(): ByteArray

    data object None : XzBlockCheckAccumulator {
        override val byteLength: Int = 0

        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) = Unit

        override fun digestBytes(): ByteArray = ByteArray(0)
    }

    class Crc32Check : XzBlockCheckAccumulator {
        override val byteLength: Int = 4
        private val crc = Crc32()

        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) = crc.update(data, offset, length)

        override fun digestBytes(): ByteArray {
            val value = crc.value()
            return ByteArray(4) { idx -> ((value ushr (idx * 8)) and 0xFFL).toByte() }
        }
    }

    class Crc64Check : XzBlockCheckAccumulator {
        override val byteLength: Int = 8
        private val crc = Crc64Ecma()

        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) = crc.update(data, offset, length)

        override fun digestBytes(): ByteArray = crc.digestBytesLittleEndian()
    }

    class Sha256Check : XzBlockCheckAccumulator {
        override val byteLength: Int = 32
        private val sha = Sha256()

        override fun update(
            data: ByteArray,
            offset: Int,
            length: Int,
        ) = sha.update(data, offset, length)

        override fun digestBytes(): ByteArray = sha.digest()
    }

    companion object {
        fun forType(checkType: Int): XzBlockCheckAccumulator =
            when (checkType) {
                XZ_CHECK_NONE -> None
                XZ_CHECK_CRC32 -> Crc32Check()
                XZ_CHECK_CRC64 -> Crc64Check()
                XZ_CHECK_SHA256 -> Sha256Check()
                else -> throw FlashStepException("UNSUPPORTED_XZ_CHECK", "Unsupported XZ check type: $checkType")
            }
    }
}

private fun parseBlockHeader(header: ByteArray): XzBlockSpec {
    val crcOffset = header.size - 4
    requireStream(Crc32.compute(header, offset = 0, length = crcOffset) == readLittleEndianU32(header, crcOffset), "CORRUPT_XZ") {
        "XZ block header CRC32 mismatch"
    }
    val flags = header[1].toInt() and 0xFF
    requireStream((flags and 0x3C) == 0, "CORRUPT_XZ") { "Unsupported reserved bits in XZ block header flags" }
    val filterCount = (flags and 0x03) + 1
    var cursor = 2
    val compressedSize =
        if ((flags and 0x40) != 0) {
            readBlockHeaderVli(header, cursor, crcOffset).also { cursor = it.nextCursor }.value
        } else {
            null
        }
    val uncompressedSize =
        if ((flags and 0x80) != 0) {
            readBlockHeaderVli(header, cursor, crcOffset).also { cursor = it.nextCursor }.value
        } else {
            null
        }
    val parsedFilters = parseBlockFilters(header, cursor, crcOffset, filterCount)
    for (padIdx in parsedFilters.nextCursor until crcOffset) {
        requireStream(header[padIdx] == 0.toByte(), "CORRUPT_XZ") { "Non-zero padding inside XZ block header" }
    }
    return XzBlockSpec(compressedSize, uncompressedSize, parsedFilters.dictionarySize, parsedFilters.bcjTransforms)
}

private data class ParsedBlockFilters(
    val dictionarySize: Int,
    val bcjTransforms: List<XzByteTransform>,
    val nextCursor: Int,
)

private fun parseBlockFilters(
    header: ByteArray,
    startCursor: Int,
    crcOffset: Int,
    filterCount: Int,
): ParsedBlockFilters {
    var cursor = startCursor
    val bcjTransforms = mutableListOf<XzByteTransform>()
    var dictionarySize = -1
    for (filterIdx in 0 until filterCount) {
        val filterId = readBlockHeaderVli(header, cursor, crcOffset).also { cursor = it.nextCursor }.value
        val propsLen = readBlockHeaderVli(header, cursor, crcOffset).also { cursor = it.nextCursor }.value.toInt()
        if (propsLen < 0 || cursor + propsLen > crcOffset) {
            throw FlashStepException("CORRUPT_XZ", "Invalid XZ filter property length")
        }
        val propsStart = cursor
        cursor += propsLen
        if (filterIdx == filterCount - 1) {
            if (filterId != XZ_FILTER_LZMA2 || propsLen != 1) {
                throw FlashStepException("UNSUPPORTED_XZ_FILTER", "Unsupported terminal XZ filter ID: $filterId")
            }
            dictionarySize = decodeLzma2DictionarySize(header[propsStart].toInt() and 0xFF)
        } else {
            bcjTransforms += createBcjTransform(filterId, header, propsStart, propsLen)
        }
    }
    return ParsedBlockFilters(dictionarySize = dictionarySize, bcjTransforms = bcjTransforms, nextCursor = cursor)
}

private fun createBcjTransform(
    filterId: Long,
    header: ByteArray,
    propsStart: Int,
    propsLen: Int,
): XzByteTransform {
    val startOffset =
        when (propsLen) {
            0 -> 0
            4 ->
                (header[propsStart].toInt() and 0xFF) or
                    ((header[propsStart + 1].toInt() and 0xFF) shl 8) or
                    ((header[propsStart + 2].toInt() and 0xFF) shl 16) or
                    ((header[propsStart + 3].toInt() and 0xFF) shl 24)
            else -> throw FlashStepException("CORRUPT_XZ", "Invalid BCJ filter properties size: $propsLen")
        }
    return when (filterId) {
        XZ_FILTER_X86 -> X86BcjDecoder(startOffset)
        XZ_FILTER_ARM64 -> Arm64BcjDecoder(startOffset)
        else -> throw FlashStepException("UNSUPPORTED_XZ_FILTER", "Unsupported XZ filter ID: $filterId")
    }
}

private fun decodeLzma2DictionarySize(dictProp: Int): Int {
    if (dictProp > 40) {
        throw FlashStepException("CORRUPT_XZ", "Invalid LZMA2 dictionary size property: $dictProp")
    }
    val rawSize =
        if (dictProp == 40) {
            0xFFFF_FFFFL
        } else {
            (2L or (dictProp and 1).toLong()) shl (dictProp / 2 + 11)
        }
    if (rawSize > XZ_MAX_DICTIONARY_BYTES) {
        throw FlashStepException("XZ_DICT_TOO_LARGE", "LZMA2 dictionary size ($rawSize bytes) exceeds 128 MiB limit")
    }
    return rawSize.toInt().coerceAtLeast(XZ_MIN_DICTIONARY_BYTES)
}

private data class HeaderVliResult(
    val value: Long,
    val nextCursor: Int,
)

private fun readBlockHeaderVli(
    bytes: ByteArray,
    startOffset: Int,
    limit: Int,
): HeaderVliResult {
    var cursor = startOffset
    requireStream(cursor < limit, "CORRUPT_XZ") { "Truncated VLI in XZ block header" }
    val first = bytes[cursor++].toInt() and 0xFF
    if ((first and 0x80) == 0) return HeaderVliResult(first.toLong(), cursor)
    var value = (first and 0x7F).toLong()
    var shift = 7
    repeat(8) {
        requireStream(cursor < limit, "CORRUPT_XZ") { "Truncated multi-byte VLI in XZ block header" }
        val current = bytes[cursor++].toInt() and 0xFF
        requireStream(current != 0, "CORRUPT_XZ") { "Non-minimal VLI in XZ block header" }
        value = value or ((current and 0x7F).toLong() shl shift)
        if ((current and 0x80) == 0) return HeaderVliResult(value, cursor)
        shift += 7
    }
    throw FlashStepException("CORRUPT_XZ", "Oversized VLI in XZ block header")
}

private fun readLittleEndianU32(
    bytes: ByteArray,
    offset: Int,
): Long =
    (bytes[offset].toLong() and 0xFFL) or
        ((bytes[offset + 1].toLong() and 0xFFL) shl 8) or
        ((bytes[offset + 2].toLong() and 0xFFL) shl 16) or
        ((bytes[offset + 3].toLong() and 0xFFL) shl 24)
