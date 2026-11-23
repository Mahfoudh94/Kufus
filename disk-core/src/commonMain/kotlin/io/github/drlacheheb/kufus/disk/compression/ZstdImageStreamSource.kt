package io.github.drlacheheb.kufus.disk.compression

import io.github.drlacheheb.kufus.disk.io.BufferedByteInput
import io.github.drlacheheb.kufus.disk.io.FlashImageSource
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.io.StreamingByteSource
import io.github.drlacheheb.kufus.disk.io.XxHash64
import io.github.drlacheheb.kufus.disk.io.requireStream

private const val ZSTD_FRAME_MAGIC = 0xFD2FB528u
private const val ZSTD_SKIPPABLE_MAGIC_MASK = 0xFFFFFFF0u
private const val ZSTD_SKIPPABLE_MAGIC_BASE = 0x184D2A50u
private const val ZSTD_MAX_BLOCK_BYTES = 128 * 1024
private const val ZSTD_MAX_WINDOW_BYTES = 128 * 1024 * 1024

private const val ZSTD_BLOCK_RAW = 0
private const val ZSTD_BLOCK_RLE = 1
private const val ZSTD_BLOCK_COMPRESSED = 2

private const val LIT_RAW = 0
private const val LIT_RLE = 1
private const val LIT_COMPRESSED = 2
private const val LIT_TREELESS = 3

private const val FSE_MODE_PREDEFINED = 0
private const val FSE_MODE_RLE = 1
private const val FSE_MODE_COMPRESSED = 2
private const val FSE_MODE_REPEAT = 3

/**
 * Pure-Kotlin RFC 8878 streaming Zstandard (`.zst`) decompressor implementing [FlashImageSource].
 */
internal class ZstdImageStreamSource(
    source: StreamingByteSource,
    override val knownUncompressedSizeBytes: Long?,
) : FlashImageSource {
    private val input = BufferedByteInput(source)
    private var streamEnded = false
    private var closed = false

    private val blockCompressedBuffer = ByteArray(ZSTD_MAX_BLOCK_BYTES)
    private val literalsBuffer = ByteArray(ZSTD_MAX_BLOCK_BYTES)
    private val blockOutputBuffer = ByteArray(ZSTD_MAX_BLOCK_BYTES)
    private var readyOffset = 0
    private var readyLength = 0

    private var activeFrame: ZstdFrameState? = null

    override fun readChunk(buffer: ByteArray): Int {
        if (closed || streamEnded || buffer.isEmpty()) return 0
        while (!streamEnded) {
            if (readyOffset < readyLength) {
                val copyCount = minOf(buffer.size, readyLength - readyOffset)
                blockOutputBuffer.copyInto(buffer, destinationOffset = 0, startIndex = readyOffset, endIndex = readyOffset + copyCount)
                readyOffset += copyCount
                return copyCount
            }
            val frame = activeFrame ?: openNextFrame()
            if (frame == null) {
                streamEnded = true
                return 0
            }
            decodeNextBlockIntoOutput(frame)
        }
        return 0
    }

    override fun close() {
        if (closed) return
        closed = true
        input.close()
    }

    private fun openNextFrame(): ZstdFrameState? {
        while (true) {
            val b0 = input.readByteOrEof()
            if (b0 < 0) return null
            val b1 = input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd frame magic")
            val b2 = input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd frame magic")
            val b3 = input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd frame magic")
            val magic = b0.toUInt() or (b1.toUInt() shl 8) or (b2.toUInt() shl 16) or (b3.toUInt() shl 24)
            if (magic == ZSTD_FRAME_MAGIC) {
                return parseStandardFrameHeader().also { activeFrame = it }
            }
            requireStream((magic and ZSTD_SKIPPABLE_MAGIC_MASK) == ZSTD_SKIPPABLE_MAGIC_BASE, "INVALID_ZSTD") {
                "Invalid Zstd frame magic: 0x${magic.toString(16)}"
            }
            val skipSize = input.readUInt32LeOrThrow("CORRUPT_ZSTD", "Truncated skippable Zstd frame size").toLong()
            input.skipFully(skipSize, "CORRUPT_ZSTD", "Unexpected EOF inside skippable Zstd frame")
        }
    }

    private fun parseStandardFrameHeader(): ZstdFrameState {
        val fhd = input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd frame descriptor")
        val fcsFlag = (fhd ushr 6) and 0x03
        val singleSegment = ((fhd ushr 5) and 0x01) == 1
        requireStream((fhd and 0x18) == 0, "INVALID_ZSTD") { "Reserved frame header bits must be zero" }
        val hasChecksum = ((fhd ushr 2) and 0x01) == 1
        val dictIdFlag = fhd and 0x03
        val windowByte = if (singleSegment) -1 else input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd window descriptor")

        if (dictIdFlag > 0) {
            val dictIdBytes = 1 shl (dictIdFlag - 1)
            val dictBytes = ByteArray(dictIdBytes)
            input.readFully(dictBytes, errorCode = "CORRUPT_ZSTD", message = "Truncated Zstd dictionary ID")
            requireStream(dictBytes.all { it == 0.toByte() }, "UNSUPPORTED_ZSTD_DICTIONARY") {
                "External Zstd dictionaries are not supported"
            }
        }

        val frameContentSize = readFrameContentSize(fcsFlag, singleSegment)
        val windowSize = computeFrameWindowSize(singleSegment, windowByte, frameContentSize)
        return ZstdFrameState(
            windowSize = windowSize,
            maxBlockSize = minOf(ZSTD_MAX_BLOCK_BYTES, windowSize),
            expectedContentSize = frameContentSize,
            checksumEnabled = hasChecksum,
        )
    }

    private fun readFrameContentSize(
        fcsFlag: Int,
        singleSegment: Boolean,
    ): Long? =
        when (fcsFlag) {
            0 -> if (singleSegment) input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd 1-byte FCS").toLong() else null
            1 -> input.readUInt16LeOrThrow("CORRUPT_ZSTD", "Truncated Zstd 2-byte FCS").toLong() + 256L
            2 -> input.readUInt32LeOrThrow("CORRUPT_ZSTD", "Truncated Zstd 4-byte FCS").toLong()
            else -> readUInt64LeOrThrow()
        }

    private fun readUInt64LeOrThrow(): Long {
        val low = input.readUInt32LeOrThrow("CORRUPT_ZSTD", "Truncated Zstd 8-byte FCS").toULong()
        val high = input.readUInt32LeOrThrow("CORRUPT_ZSTD", "Truncated Zstd 8-byte FCS").toULong()
        val value = low or (high shl 32)
        requireStream(value <= Long.MAX_VALUE.toULong(), "CORRUPT_ZSTD") { "Zstd frame content size overflows signed 64-bit integer" }
        return value.toLong()
    }

    private fun computeFrameWindowSize(
        singleSegment: Boolean,
        windowByte: Int,
        frameContentSize: Long?,
    ): Int {
        val rawWindowSize =
            if (singleSegment) {
                (frameContentSize ?: 0L).coerceAtLeast(1L)
            } else {
                val exponent = windowByte ushr 3
                val mantissa = windowByte and 0x07
                val base = 1L shl (10 + exponent)
                base + ((base ushr 3) * mantissa.toLong())
            }
        requireStream(rawWindowSize in 1L..ZSTD_MAX_WINDOW_BYTES.toLong(), "ZSTD_WINDOW_TOO_LARGE") {
            "Zstd window size ($rawWindowSize bytes) exceeds 128 MiB limit"
        }
        val effectiveSize =
            if (frameContentSize != null && frameContentSize > 0L) {
                minOf(rawWindowSize, maxOf(frameContentSize, ZSTD_MAX_BLOCK_BYTES.toLong()))
            } else {
                rawWindowSize
            }
        return effectiveSize.toInt()
    }

    private fun decodeNextBlockIntoOutput(frame: ZstdFrameState) {
        val h0 = input.readByteOrThrow("CORRUPT_ZSTD", "Unexpected EOF waiting for Zstd block header")
        val h1 = input.readByteOrThrow("CORRUPT_ZSTD", "Unexpected EOF in Zstd block header")
        val h2 = input.readByteOrThrow("CORRUPT_ZSTD", "Unexpected EOF in Zstd block header")
        val header = h0 or (h1 shl 8) or (h2 shl 16)
        val lastBlock = (header and 1) == 1
        val blockType = (header ushr 1) and 0x03
        val blockSize = header ushr 3

        val produced =
            when (blockType) {
                ZSTD_BLOCK_RAW -> decodeRawBlock(frame, blockSize)
                ZSTD_BLOCK_RLE -> decodeRleBlock(frame, blockSize)
                ZSTD_BLOCK_COMPRESSED -> decodeCompressedBlock(frame, blockSize)
                else -> throw FlashStepException("CORRUPT_ZSTD", "Reserved Zstd block type 3 encountered")
            }

        frame.onBlockDecoded(blockOutputBuffer, produced)
        readyOffset = 0
        readyLength = produced

        if (lastBlock) {
            finalizeFrame(frame)
        }
    }

    private fun decodeRawBlock(
        frame: ZstdFrameState,
        blockSize: Int,
    ): Int {
        requireStream(blockSize <= frame.maxBlockSize, "CORRUPT_ZSTD") {
            "Zstd raw block size ($blockSize) exceeds block limit (${frame.maxBlockSize})"
        }
        input.readFully(blockOutputBuffer, destOffset = 0, length = blockSize, errorCode = "CORRUPT_ZSTD", message = "Truncated Zstd raw block")
        return blockSize
    }

    private fun decodeRleBlock(
        frame: ZstdFrameState,
        regeneratedSize: Int,
    ): Int {
        requireStream(regeneratedSize <= frame.maxBlockSize, "CORRUPT_ZSTD") {
            "Zstd RLE block size ($regeneratedSize) exceeds block limit (${frame.maxBlockSize})"
        }
        val fillByte = input.readByteOrThrow("CORRUPT_ZSTD", "Truncated Zstd RLE block").toByte()
        blockOutputBuffer.fill(fillByte, fromIndex = 0, toIndex = regeneratedSize)
        return regeneratedSize
    }

    private fun decodeCompressedBlock(
        frame: ZstdFrameState,
        compressedSize: Int,
    ): Int {
        requireStream(compressedSize <= ZSTD_MAX_BLOCK_BYTES, "CORRUPT_ZSTD") {
            "Compressed Zstd block exceeds 128 KiB limit"
        }
        input.readFully(
            blockCompressedBuffer,
            destOffset = 0,
            length = compressedSize,
            errorCode = "CORRUPT_ZSTD",
            message = "Truncated Zstd compressed block",
        )
        val (literalsLen, seqOffset) = decodeLiteralsSection(frame, blockCompressedBuffer, compressedSize)
        return executeSequencesSection(frame, blockCompressedBuffer, seqOffset, compressedSize, literalsLen)
    }

    private fun decodeLiteralsSection(
        frame: ZstdFrameState,
        src: ByteArray,
        blockLimit: Int,
    ): Pair<Int, Int> {
        requireStream(blockLimit >= 1, "CORRUPT_ZSTD") { "Missing Zstd literals section" }
        val hdr0 = src[0].toInt() and 0xFF
        val litType = hdr0 and 0x03
        val sizeFormat = (hdr0 ushr 2) and 0x03
        return when (litType) {
            LIT_RAW -> decodeRawLiterals(src, blockLimit, hdr0, sizeFormat)
            LIT_RLE -> decodeRleLiterals(src, blockLimit, hdr0, sizeFormat)
            LIT_COMPRESSED, LIT_TREELESS -> decodeHuffmanLiterals(frame, src, blockLimit, litType == LIT_TREELESS, sizeFormat)
            else -> throw FlashStepException("CORRUPT_ZSTD", "Invalid Zstd literals type: $litType")
        }
    }

    private fun decodeRawLiterals(
        src: ByteArray,
        blockLimit: Int,
        hdr0: Int,
        sizeFormat: Int,
    ): Pair<Int, Int> {
        val (regenSize, headerLen) = parseUncompressedLiteralsSize(src, blockLimit, hdr0, sizeFormat)
        requireStream(regenSize <= ZSTD_MAX_BLOCK_BYTES && headerLen + regenSize <= blockLimit, "CORRUPT_ZSTD") {
            "Raw Zstd literals exceed block bounds"
        }
        src.copyInto(literalsBuffer, destinationOffset = 0, startIndex = headerLen, endIndex = headerLen + regenSize)
        return regenSize to (headerLen + regenSize)
    }

    private fun decodeRleLiterals(
        src: ByteArray,
        blockLimit: Int,
        hdr0: Int,
        sizeFormat: Int,
    ): Pair<Int, Int> {
        val (regenSize, headerLen) = parseUncompressedLiteralsSize(src, blockLimit, hdr0, sizeFormat)
        requireStream(regenSize <= ZSTD_MAX_BLOCK_BYTES && headerLen < blockLimit, "CORRUPT_ZSTD") {
            "RLE Zstd literals exceed block bounds"
        }
        literalsBuffer.fill(src[headerLen], fromIndex = 0, toIndex = regenSize)
        return regenSize to (headerLen + 1)
    }

    private fun parseUncompressedLiteralsSize(
        src: ByteArray,
        blockLimit: Int,
        hdr0: Int,
        sizeFormat: Int,
    ): Pair<Int, Int> =
        when (sizeFormat) {
            0, 2 -> (hdr0 ushr 3) to 1
            1 -> {
                requireStream(blockLimit >= 2, "CORRUPT_ZSTD") { "Truncated 2-byte literal header" }
                (((src[1].toInt() and 0xFF) shl 4) or (hdr0 ushr 4)) to 2
            }
            else -> {
                requireStream(blockLimit >= 3, "CORRUPT_ZSTD") { "Truncated 3-byte literal header" }
                (((src[2].toInt() and 0xFF) shl 12) or ((src[1].toInt() and 0xFF) shl 4) or (hdr0 ushr 4)) to 3
            }
        }

    private fun decodeHuffmanLiterals(
        frame: ZstdFrameState,
        src: ByteArray,
        blockLimit: Int,
        treeless: Boolean,
        sizeFormat: Int,
    ): Pair<Int, Int> {
        val spec = parseCompressedLiteralsHeader(src, blockLimit, sizeFormat)
        requireStream(spec.regenSize <= ZSTD_MAX_BLOCK_BYTES, "CORRUPT_ZSTD") { "Huffman literals size overflow" }
        var cursor = spec.headerBytes
        val payloadEnd = spec.headerBytes + spec.compressedSize
        requireStream(payloadEnd <= blockLimit, "CORRUPT_ZSTD") { "Compressed Huffman literals exceed block limit" }

        val hufTable =
            if (treeless) {
                frame.previousHuffmanTable
                    ?: throw FlashStepException("CORRUPT_ZSTD", "Treeless Zstd block has no previous Huffman table")
            } else {
                val (table, treeLen) = ZstdHuffmanTable.readTable(src, cursor, payloadEnd)
                cursor += treeLen
                frame.previousHuffmanTable = table
                table
            }

        if (spec.fourStreams) {
            decodeFourHuffmanStreams(hufTable, src, cursor, payloadEnd, spec.regenSize)
        } else {
            decodeSingleHuffmanStream(hufTable, src, cursor, payloadEnd, literalsBuffer, 0, spec.regenSize)
        }
        return spec.regenSize to payloadEnd
    }

    private fun parseCompressedLiteralsHeader(
        src: ByteArray,
        blockLimit: Int,
        sizeFormat: Int,
    ): CompressedLiteralsHeader {
        val needBytes =
            when (sizeFormat) {
                0, 1 -> 3
                2 -> 4
                else -> 5
            }
        requireStream(blockLimit >= needBytes, "CORRUPT_ZSTD") { "Truncated compressed literals header" }
        val b0 = src[0].toInt() and 0xFF
        val b1 = src[1].toInt() and 0xFF
        val b2 = src[2].toInt() and 0xFF
        return when (sizeFormat) {
            0 ->
                CompressedLiteralsHeader(
                    fourStreams = false,
                    regenSize = ((b0 ushr 4) or ((b1 and 0x3F) shl 4)),
                    compressedSize = ((b1 ushr 6) or (b2 shl 2)),
                    headerBytes = 3,
                )
            1 ->
                CompressedLiteralsHeader(
                    fourStreams = true,
                    regenSize = ((b0 ushr 4) or ((b1 and 0x3F) shl 4)),
                    compressedSize = ((b1 ushr 6) or (b2 shl 2)),
                    headerBytes = 3,
                )
            2 -> {
                val b3 = src[3].toInt() and 0xFF
                CompressedLiteralsHeader(
                    fourStreams = true,
                    regenSize = ((b0 ushr 4) or (b1 shl 4) or ((b2 and 0x03) shl 12)),
                    compressedSize = ((b2 ushr 2) or (b3 shl 6)),
                    headerBytes = 4,
                )
            }
            else -> {
                val b3 = src[3].toInt() and 0xFF
                val b4 = src[4].toInt() and 0xFF
                CompressedLiteralsHeader(
                    fourStreams = true,
                    regenSize = ((b0 ushr 4) or (b1 shl 4) or ((b2 and 0x3F) shl 12)),
                    compressedSize = ((b2 ushr 6) or (b3 shl 2) or (b4 shl 10)),
                    headerBytes = 5,
                )
            }
        }
    }

    private fun decodeSingleHuffmanStream(
        table: ZstdHuffmanTable,
        src: ByteArray,
        start: Int,
        end: Int,
        dest: ByteArray,
        destOffset: Int,
        outputLength: Int,
    ) {
        val reader = ZstdBackwardBitReader(src, start, end)
        for (i in 0 until outputLength) {
            dest[destOffset + i] = table.decodeSymbol(reader)
        }
        reader.verifyFullyConsumed()
    }

    private fun decodeFourHuffmanStreams(
        table: ZstdHuffmanTable,
        src: ByteArray,
        start: Int,
        end: Int,
        totalOutputLength: Int,
    ) {
        requireStream(start + 6 <= end, "CORRUPT_ZSTD") { "Truncated 4-stream Huffman jump table" }
        val s1Len = readU16Le(src, start)
        val s2Len = readU16Le(src, start + 2)
        val s3Len = readU16Le(src, start + 4)
        val s1Start = start + 6
        val s2Start = s1Start + s1Len
        val s3Start = s2Start + s2Len
        val s4Start = s3Start + s3Len
        requireStream(s4Start <= end, "CORRUPT_ZSTD") { "Invalid 4-stream Huffman stream sizes" }

        val chunkSize = (totalOutputLength + 3) / 4
        val s4OutputLen = totalOutputLength - 3 * chunkSize
        decodeSingleHuffmanStream(table, src, s1Start, s2Start, literalsBuffer, 0, chunkSize)
        decodeSingleHuffmanStream(table, src, s2Start, s3Start, literalsBuffer, chunkSize, chunkSize)
        decodeSingleHuffmanStream(table, src, s3Start, s4Start, literalsBuffer, chunkSize * 2, chunkSize)
        decodeSingleHuffmanStream(table, src, s4Start, end, literalsBuffer, chunkSize * 3, s4OutputLen)
    }

    private fun executeSequencesSection(
        frame: ZstdFrameState,
        src: ByteArray,
        seqStart: Int,
        blockEnd: Int,
        literalsLen: Int,
    ): Int {
        requireStream(seqStart < blockEnd, "CORRUPT_ZSTD") { "Missing Zstd sequences count byte" }
        var cursor = seqStart
        val firstByte = src[cursor++].toInt() and 0xFF
        val numSequences =
            when {
                firstByte == 0 -> {
                    requireStream(cursor == blockEnd, "CORRUPT_ZSTD") { "Unexpected data after zero-sequence Zstd block" }
                    return copyTrailingLiterals(0, literalsLen, 0, frame.maxBlockSize)
                }
                firstByte < 128 -> firstByte
                firstByte < 255 -> {
                    requireStream(cursor < blockEnd, "CORRUPT_ZSTD") { "Truncated 2-byte sequence count" }
                    ((firstByte - 128) shl 8) + (src[cursor++].toInt() and 0xFF)
                }
                else -> {
                    requireStream(cursor + 2 <= blockEnd, "CORRUPT_ZSTD") { "Truncated 3-byte sequence count" }
                    val extra = readU16Le(src, cursor)
                    cursor += 2
                    extra + 0x7F00
                }
            }

        requireStream(cursor < blockEnd, "CORRUPT_ZSTD") { "Missing Zstd sequence symbol compression modes byte" }
        val modesByte = src[cursor++].toInt() and 0xFF
        requireStream((modesByte and 0x03) == 0, "CORRUPT_ZSTD") { "Reserved bits set in sequence compression modes" }

        val llTable = resolveSequenceFseTable(src, cursor, blockEnd, (modesByte ushr 6) and 0x03, 35, 9, ZstdPredefinedTables.literalLengthFse, frame.prevLlFse)
        cursor += llTable.bytesConsumed
        frame.prevLlFse = llTable.table

        val ofTable = resolveSequenceFseTable(src, cursor, blockEnd, (modesByte ushr 4) and 0x03, 31, 8, ZstdPredefinedTables.offsetFse, frame.prevOfFse)
        cursor += ofTable.bytesConsumed
        frame.prevOfFse = ofTable.table

        val mlTable = resolveSequenceFseTable(src, cursor, blockEnd, (modesByte ushr 2) and 0x03, 52, 9, ZstdPredefinedTables.matchLengthFse, frame.prevMlFse)
        cursor += mlTable.bytesConsumed
        frame.prevMlFse = mlTable.table

        return decodeSequencesLoop(frame, src, cursor, blockEnd, numSequences, literalsLen, llTable.table, ofTable.table, mlTable.table)
    }

    private fun resolveSequenceFseTable(
        src: ByteArray,
        cursor: Int,
        end: Int,
        mode: Int,
        maxSymbol: Int,
        maxLog: Int,
        predefined: ZstdFseTable,
        previous: ZstdFseTable?,
    ): ResolvedFseTable =
        when (mode) {
            FSE_MODE_PREDEFINED -> ResolvedFseTable(predefined, 0)
            FSE_MODE_RLE -> {
                requireStream(cursor < end, "CORRUPT_ZSTD") { "Truncated RLE FSE symbol byte" }
                val sym = src[cursor].toInt() and 0xFF
                requireStream(sym <= maxSymbol, "CORRUPT_ZSTD") { "RLE FSE symbol $sym > $maxSymbol" }
                ResolvedFseTable(ZstdFseTable.singleSymbolRle(sym), 1)
            }
            FSE_MODE_COMPRESSED -> {
                val (table, used) = ZstdFseTable.readTable(src, cursor, end, maxSymbol, maxLog)
                ResolvedFseTable(table, used)
            }
            FSE_MODE_REPEAT -> {
                val prev = previous ?: throw FlashStepException("CORRUPT_ZSTD", "Repeat FSE mode without previous table")
                ResolvedFseTable(prev, 0)
            }
            else -> throw FlashStepException("CORRUPT_ZSTD", "Unsupported FSE mode: $mode")
        }

    private fun decodeSequencesLoop(
        frame: ZstdFrameState,
        src: ByteArray,
        streamStart: Int,
        streamEnd: Int,
        numSequences: Int,
        literalsLen: Int,
        llTable: ZstdFseTable,
        ofTable: ZstdFseTable,
        mlTable: ZstdFseTable,
    ): Int {
        val reader = ZstdBackwardBitReader(src, streamStart, streamEnd)
        var llState = reader.readBitsStrict(llTable.accuracyLog)
        var ofState = reader.readBitsStrict(ofTable.accuracyLog)
        var mlState = reader.readBitsStrict(mlTable.accuracyLog)
        var litCursor = 0
        var outCursor = 0

        for (seqIndex in 0 until numSequences) {
            val llCode = llTable.symbols[llState]
            val ofCode = ofTable.symbols[ofState]
            val mlCode = mlTable.symbols[mlState]
            requireStream(llCode <= 35 && mlCode <= 52 && ofCode <= 31, "CORRUPT_ZSTD") { "Sequence code out of range" }

            // RFC 8878 §3.1.1.3.2.1 extra bit order: Offset -> Match_Length -> Literal_Length
            val offsetValue = (1L shl ofCode) + reader.readBitsStrict(ofCode).toLong()
            val matchLength = ZstdPredefinedTables.matchLengthBase[mlCode] + reader.readBitsStrict(ZstdPredefinedTables.matchLengthExtraBits[mlCode])
            val literalLength =
                ZstdPredefinedTables.literalLengthBase[llCode] + reader.readBitsStrict(ZstdPredefinedTables.literalLengthExtraBits[llCode])

            if (seqIndex < numSequences - 1) {
                llState = llTable.newStates[llState] + reader.readBitsStrict(llTable.nbBits[llState])
                mlState = mlTable.newStates[mlState] + reader.readBitsStrict(mlTable.nbBits[mlState])
                ofState = ofTable.newStates[ofState] + reader.readBitsStrict(ofTable.nbBits[ofState])
            }

            outCursor = emitSingleSequence(frame, litCursor, outCursor, literalLength, matchLength, offsetValue, literalsLen)
            litCursor += literalLength
        }

        reader.verifyFullyConsumed()
        return copyTrailingLiterals(litCursor, literalsLen - litCursor, outCursor, frame.maxBlockSize)
    }

    private fun emitSingleSequence(
        frame: ZstdFrameState,
        litCursor: Int,
        startOutCursor: Int,
        literalLength: Int,
        matchLength: Int,
        offsetValue: Long,
        literalsLen: Int,
    ): Int {
        requireStream(litCursor + literalLength <= literalsLen, "CORRUPT_ZSTD") { "Sequence literals overflow" }
        var outCursor = copyTrailingLiterals(litCursor, literalLength, startOutCursor, frame.maxBlockSize)
        val actualOffset = resolveSequenceOffset(frame, literalLength, offsetValue, outCursor)
        requireStream(outCursor + matchLength <= frame.maxBlockSize, "CORRUPT_ZSTD") { "Sequence match overflows block output" }

        for (i in 0 until matchLength) {
            val byteVal =
                if (actualOffset <= outCursor) {
                    blockOutputBuffer[outCursor - actualOffset]
                } else {
                    frame.readHistoryByte(actualOffset - outCursor)
                }
            blockOutputBuffer[outCursor++] = byteVal
        }
        return outCursor
    }

    private fun resolveSequenceOffset(
        frame: ZstdFrameState,
        literalLength: Int,
        offsetValue: Long,
        currentBlockOffset: Int,
    ): Int {
        val resolvedLong =
            if (offsetValue > 3L) {
                val dist = offsetValue - 3L
                frame.pushOffset(dist)
                dist
            } else {
                var repIndex = (offsetValue - 1L).toInt()
                if (literalLength == 0) repIndex++
                val dist =
                    if (repIndex == 3) {
                        (frame.rep0 - 1L).also {
                            requireStream(it > 0L, "CORRUPT_ZSTD") { "Invalid repeated offset minus one ($it)" }
                        }
                    } else {
                        frame.getRep(repIndex)
                    }
                frame.promoteRep(repIndex, dist)
                dist
            }
        requireStream(resolvedLong in 1L..frame.maxAvailableHistoryBytes(currentBlockOffset), "CORRUPT_ZSTD") {
            "Zstd match offset out of history range ($resolvedLong)"
        }
        return resolvedLong.toInt()
    }

    private fun copyTrailingLiterals(
        litStart: Int,
        count: Int,
        outOffset: Int,
        blockMax: Int,
    ): Int {
        requireStream(outOffset + count <= blockMax, "CORRUPT_ZSTD") { "Literals overflow block output size" }
        if (count > 0) {
            literalsBuffer.copyInto(blockOutputBuffer, destinationOffset = outOffset, startIndex = litStart, endIndex = litStart + count)
        }
        return outOffset + count
    }

    private fun finalizeFrame(frame: ZstdFrameState) {
        if (frame.expectedContentSize != null) {
            requireStream(frame.totalBytesEmitted == frame.expectedContentSize, "CORRUPT_ZSTD") {
                "Zstd uncompressed content size mismatch (${frame.totalBytesEmitted} != ${frame.expectedContentSize})"
            }
        }
        if (frame.checksumEnabled) {
            val expectedChecksum = input.readUInt32LeOrThrow("CORRUPT_ZSTD", "Truncated Zstd content checksum")
            val actualChecksum = frame.checksum.digestLow32()
            requireStream(expectedChecksum == actualChecksum, "CORRUPT_ZSTD") {
                "Zstd xxHash64 content checksum mismatch"
            }
        }
        activeFrame = null
    }

    private class ZstdFrameState(
        val windowSize: Int,
        val maxBlockSize: Int,
        val expectedContentSize: Long?,
        val checksumEnabled: Boolean,
    ) {
        private val window = ByteArray(windowSize)
        private var windowWritePos = 0
        private var windowFull = false
        var totalBytesEmitted: Long = 0L
            private set

        val checksum = XxHash64()
        var previousHuffmanTable: ZstdHuffmanTable? = null
        var prevLlFse: ZstdFseTable? = null
        var prevOfFse: ZstdFseTable? = null
        var prevMlFse: ZstdFseTable? = null

        var rep0 = 1L
        var rep1 = 4L
        var rep2 = 8L

        fun onBlockDecoded(
            blockData: ByteArray,
            length: Int,
        ) {
            if (length <= 0) return
            totalBytesEmitted += length.toLong()
            if (checksumEnabled) checksum.update(blockData, 0, length)
            for (i in 0 until length) {
                window[windowWritePos++] = blockData[i]
                if (windowWritePos == windowSize) {
                    windowWritePos = 0
                    windowFull = true
                }
            }
        }

        fun maxAvailableHistoryBytes(currentBlockOffset: Int): Long =
            if (windowFull) {
                windowSize.toLong()
            } else {
                minOf(windowSize.toLong(), totalBytesEmitted + currentBlockOffset.toLong())
            }

        fun readHistoryByte(backDistanceFromWindowEnd: Int): Byte {
            var idx = windowWritePos - backDistanceFromWindowEnd
            if (idx < 0) idx += windowSize
            return window[idx]
        }

        fun pushOffset(dist: Long) {
            rep2 = rep1
            rep1 = rep0
            rep0 = dist
        }

        fun getRep(index: Int): Long =
            when (index) {
                0 -> rep0
                1 -> rep1
                else -> rep2
            }

        fun promoteRep(
            index: Int,
            resolvedDist: Long,
        ) {
            when (index) {
                0 -> Unit
                1 -> {
                    rep1 = rep0
                    rep0 = resolvedDist
                }
                else -> {
                    rep2 = rep1
                    rep1 = rep0
                    rep0 = resolvedDist
                }
            }
        }
    }

    private data class CompressedLiteralsHeader(
        val fourStreams: Boolean,
        val regenSize: Int,
        val compressedSize: Int,
        val headerBytes: Int,
    )

    private data class ResolvedFseTable(
        val table: ZstdFseTable,
        val bytesConsumed: Int,
    )
}

private fun readU16Le(
    src: ByteArray,
    offset: Int,
): Int = (src[offset].toInt() and 0xFF) or ((src[offset + 1].toInt() and 0xFF) shl 8)
