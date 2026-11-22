package io.github.drlacheheb.kufus.disk

private const val MAX_LZMA2_COMPRESSED_CHUNK_BYTES = 65_536
private const val PROB_INIT = 1024
private const val NUM_BIT_MODEL_BITS = 11
private const val PROB_TOTAL = 1 shl NUM_BIT_MODEL_BITS
private const val NUM_MOVE_BITS = 5
private const val RANGE_TOP_MASK = 0x0100_0000u

private const val NUM_STATES = 12
private const val NUM_POS_STATES_MAX = 16
private const val LITERAL_CODER_SIZE = 0x300
private const val LEN_LOW_SYMBOLS = 8
private const val LEN_MID_SYMBOLS = 8
private const val LEN_HIGH_SYMBOLS = 256
private const val POS_SLOTS = 64
private const val END_POS_MODEL_INDEX = 14
private const val ALIGN_BITS = 4
private const val ALIGN_TABLE_SIZE = 1 shl ALIGN_BITS
private const val FULL_DISTANCES = 128

/**
 * Streaming LZMA2 + LZMA range decoder backed by a fixed dictionary ring buffer.
 */
internal class XzLzma2Decoder(
    private val input: BufferedByteInput,
    dictionaryCapacity: Int,
) {
    private val dictionary = ByteArray(dictionaryCapacity)
    private var dictWritePos = 0
    private var dictFull = false

    private val chunkBuffer = ByteArray(MAX_LZMA2_COMPRESSED_CHUNK_BYTES)
    private var chunkOffset = 0
    private var chunkLimit = 0

    private var range = 0xFFFF_FFFFu
    private var code = 0u

    private var lzmaReady = false
    private var propsSet = false
    private var lc = 0
    private var lp = 0
    private var pbMask = 0
    private var lpMask = 0

    private var state = 0
    private var rep0 = 0
    private var rep1 = 0
    private var rep2 = 0
    private var rep3 = 0
    private var totalDecodedPos = 0L

    private val isMatch = IntArray(NUM_STATES shl 4)
    private val isRep = IntArray(NUM_STATES)
    private val isRepG0 = IntArray(NUM_STATES)
    private val isRepG1 = IntArray(NUM_STATES)
    private val isRepG2 = IntArray(NUM_STATES)
    private val isRep0Long = IntArray(NUM_STATES shl 4)
    private val posSlotDecoder = Array(4) { IntArray(POS_SLOTS) }
    private val posDecoders = IntArray(FULL_DISTANCES - END_POS_MODEL_INDEX)
    private val alignDecoder = IntArray(ALIGN_TABLE_SIZE)
    private val matchLenDecoder = LzmaLenDecoder()
    private val repLenDecoder = LzmaLenDecoder()
    private var literalProbs = IntArray(0)

    private var activeChunkMode = ChunkMode.NONE
    private var chunkRemainingOut = 0
    private var pendingMatchLen = 0
    private var pendingMatchDist = 0
    var streamFinished: Boolean = false
        private set

    fun readInto(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        if (streamFinished || maxLength <= 0) return 0
        var written = 0
        while (written < maxLength && !streamFinished) {
            written += readStep(dest, destOffset + written, maxLength - written)
        }
        return written
    }

    private fun readStep(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        if (pendingMatchLen > 0) {
            return flushPendingMatch(dest, destOffset, maxLength)
        }
        if (chunkRemainingOut == 0) {
            loadNextLzma2Chunk()
            return 0
        }
        return when (activeChunkMode) {
            ChunkMode.UNCOMPRESSED -> readUncompressedStep(dest, destOffset, maxLength)
            ChunkMode.COMPRESSED -> decodeLzmaStep(dest, destOffset, maxLength)
            ChunkMode.NONE -> 0
        }
    }

    private fun loadNextLzma2Chunk() {
        val control = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF inside LZMA2 block")
        when {
            control == 0x00 -> streamFinished = true
            control == 0x01 || control == 0x02 -> startUncompressedChunk(control)
            control >= 0x80 -> startCompressedChunk(control)
            else -> throw FlashStepException("CORRUPT_XZ", "Invalid LZMA2 chunk control byte: $control")
        }
    }

    private fun startUncompressedChunk(control: Int) {
        if (control == 0x01) {
            resetDictionary()
        } else if (!lzmaReady) {
            throw FlashStepException("CORRUPT_XZ", "LZMA2 chunk references uninitialized dictionary")
        }
        val sizeMinusOne = input.readUInt16BeOrThrow("CORRUPT_XZ", "Unexpected EOF in LZMA2 uncompressed chunk header")
        chunkRemainingOut = sizeMinusOne + 1
        activeChunkMode = ChunkMode.UNCOMPRESSED
    }

    private fun startCompressedChunk(control: Int) {
        val resetMode = (control ushr 5) and 0x03
        val uncompressedSize =
            ((control and 0x1F) shl 16) +
                input.readUInt16BeOrThrow("CORRUPT_XZ", "Unexpected EOF in LZMA2 compressed chunk header") +
                1
        val compressedSize =
            input.readUInt16BeOrThrow("CORRUPT_XZ", "Unexpected EOF in LZMA2 compressed size header") + 1
        applyLzma2ResetMode(resetMode)
        input.readFully(
            dest = chunkBuffer,
            destOffset = 0,
            length = compressedSize,
            errorCode = "CORRUPT_XZ",
            message = "Unexpected EOF reading LZMA2 compressed chunk payload",
        )
        initChunkRangeDecoder(compressedSize)
        chunkRemainingOut = uncompressedSize
        activeChunkMode = ChunkMode.COMPRESSED
        lzmaReady = true
    }

    private fun applyLzma2ResetMode(resetMode: Int) {
        if (resetMode == 3) {
            resetDictionary()
        } else if (!lzmaReady && resetMode < 2) {
            throw FlashStepException("CORRUPT_XZ", "LZMA2 compressed chunk missing initial reset")
        }
        if (resetMode >= 1) {
            resetCoderState()
        }
        if (resetMode >= 2) {
            val props = input.readByteOrThrow("CORRUPT_XZ", "Unexpected EOF reading LZMA2 properties")
            decodeProperties(props)
        } else if (!propsSet) {
            throw FlashStepException("CORRUPT_XZ", "LZMA2 chunk missing literal properties")
        }
    }

    private fun decodeProperties(props: Int) {
        if (props > 224) {
            throw FlashStepException("CORRUPT_XZ", "Invalid LZMA2 property byte: $props")
        }
        val pb = props / 45
        val rem = props % 45
        lp = rem / 9
        lc = rem % 9
        if (lc + lp > 4) {
            throw FlashStepException("CORRUPT_XZ", "Unsupported LZMA2 lc=$lc lp=$lp combination")
        }
        pbMask = (1 shl pb) - 1
        lpMask = (1 shl lp) - 1
        val neededLiteralSize = LITERAL_CODER_SIZE shl (lc + lp)
        if (literalProbs.size != neededLiteralSize) {
            literalProbs = IntArray(neededLiteralSize)
        }
        literalProbs.fill(PROB_INIT)
        propsSet = true
    }

    private fun resetDictionary() {
        dictWritePos = 0
        dictFull = false
        totalDecodedPos = 0L
        resetCoderState()
    }

    private fun resetCoderState() {
        state = 0
        rep0 = 0
        rep1 = 0
        rep2 = 0
        rep3 = 0
        isMatch.fill(PROB_INIT)
        isRep.fill(PROB_INIT)
        isRepG0.fill(PROB_INIT)
        isRepG1.fill(PROB_INIT)
        isRepG2.fill(PROB_INIT)
        isRep0Long.fill(PROB_INIT)
        posSlotDecoder.forEach { it.fill(PROB_INIT) }
        posDecoders.fill(PROB_INIT)
        alignDecoder.fill(PROB_INIT)
        matchLenDecoder.reset()
        repLenDecoder.reset()
        if (literalProbs.isNotEmpty()) {
            literalProbs.fill(PROB_INIT)
        }
    }

    private fun initChunkRangeDecoder(compressedSize: Int) {
        if (compressedSize < 5 || chunkBuffer[0] != 0.toByte()) {
            throw FlashStepException("CORRUPT_XZ", "Invalid LZMA2 range decoder header")
        }
        code =
            ((chunkBuffer[1].toUInt() and 0xFFu) shl 24) or
            ((chunkBuffer[2].toUInt() and 0xFFu) shl 16) or
            ((chunkBuffer[3].toUInt() and 0xFFu) shl 8) or
            (chunkBuffer[4].toUInt() and 0xFFu)
        range = 0xFFFF_FFFFu
        chunkOffset = 5
        chunkLimit = compressedSize
    }

    private fun readUncompressedStep(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        val count = minOf(chunkRemainingOut, maxLength)
        input.readFully(
            dest = dest,
            destOffset = destOffset,
            length = count,
            errorCode = "CORRUPT_XZ",
            message = "Unexpected EOF reading uncompressed LZMA2 chunk",
        )
        for (i in 0 until count) {
            putDictionaryByte(dest[destOffset + i])
        }
        totalDecodedPos += count.toLong()
        chunkRemainingOut -= count
        return count
    }

    private fun decodeLzmaStep(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        var written = 0
        while (written < maxLength && chunkRemainingOut > 0) {
            val posState = (totalDecodedPos.toInt()) and pbMask
            val matchIndex = (state shl 4) + posState
            if (decodeBit(isMatch, matchIndex) == 0) {
                val decoded = decodeNextLiteral()
                putDictionaryByte(decoded)
                dest[destOffset + written++] = decoded
                totalDecodedPos++
                chunkRemainingOut--
            } else {
                val matchLen = decodeMatchAndRep(posState)
                val emitCount = minOf(matchLen, chunkRemainingOut)
                if (matchLen > chunkRemainingOut) {
                    throw FlashStepException("CORRUPT_XZ", "LZMA match exceeds chunk uncompressed boundary")
                }
                pendingMatchLen = emitCount
                pendingMatchDist = rep0
                chunkRemainingOut -= emitCount
                written += flushPendingMatch(dest, destOffset + written, maxLength - written)
            }
        }
        if (chunkRemainingOut == 0 && pendingMatchLen == 0) {
            verifyChunkRangeTerminated()
        }
        return written
    }

    private fun verifyChunkRangeTerminated() {
        normalizeRange()
        if (code != 0u || chunkOffset != chunkLimit) {
            throw FlashStepException("CORRUPT_XZ", "LZMA2 chunk ended with trailing range state ($chunkOffset != $chunkLimit)")
        }
    }

    private fun flushPendingMatch(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        val count = minOf(pendingMatchLen, maxLength)
        val distance = pendingMatchDist
        val available = if (dictFull) dictionary.size else dictWritePos
        if (distance < 0 || distance >= available) {
            throw FlashStepException("CORRUPT_XZ", "LZMA match distance out of range ($distance >= $available)")
        }
        for (i in 0 until count) {
            val byteVal = getDictionaryByte(distance)
            putDictionaryByte(byteVal)
            dest[destOffset + i] = byteVal
        }
        totalDecodedPos += count.toLong()
        pendingMatchLen -= count
        if (chunkRemainingOut == 0 && pendingMatchLen == 0 && activeChunkMode == ChunkMode.COMPRESSED) {
            verifyChunkRangeTerminated()
        }
        return count
    }

    private fun decodeNextLiteral(): Byte {
        val prevByte = if (dictWritePos == 0 && !dictFull) 0 else getDictionaryByte(0).toInt() and 0xFF
        val litState = ((totalDecodedPos.toInt() and lpMask) shl lc) + (prevByte ushr (8 - lc))
        val probOffset = litState * LITERAL_CODER_SIZE

        var symbol = 1
        if (state >= 7) {
            var matchByte = getDictionaryByte(rep0).toInt() and 0xFF
            do {
                val matchBit = (matchByte ushr 7) and 1
                matchByte = matchByte shl 1
                val bit = decodeBit(literalProbs, probOffset + ((1 + matchBit) shl 8) + symbol)
                symbol = (symbol shl 1) or bit
                if (matchBit != bit) break
            } while (symbol < 0x100)
        }
        while (symbol < 0x100) {
            symbol = (symbol shl 1) or decodeBit(literalProbs, probOffset + symbol)
        }
        state = updateStateAfterLiteral(state)
        return (symbol and 0xFF).toByte()
    }

    private fun decodeMatchAndRep(posState: Int): Int {
        if (decodeBit(isRep, state) == 0) {
            rep3 = rep2
            rep2 = rep1
            rep1 = rep0
            val len = matchLenDecoder.decode(posState) + 2
            state = if (state < 7) 7 else 10
            rep0 = decodeMatchDistance(len)
            return len
        }
        val len = decodeRepDistance(posState)
        return len
    }

    private fun decodeRepDistance(posState: Int): Int {
        if (decodeBit(isRepG0, state) == 0) {
            if (decodeBit(isRep0Long, (state shl 4) + posState) == 0) {
                state = if (state < 7) 9 else 11
                return 1
            }
        } else {
            val distance =
                if (decodeBit(isRepG1, state) == 0) {
                    rep1
                } else {
                    if (decodeBit(isRepG2, state) == 0) {
                        rep2
                    } else {
                        val tmp = rep3
                        rep3 = rep2
                        tmp
                    }.also { rep2 = rep1 }
                }
            rep1 = rep0
            rep0 = distance
        }
        state = if (state < 7) 8 else 11
        return repLenDecoder.decode(posState) + 2
    }

    private fun decodeMatchDistance(len: Int): Int {
        val lenToPosState = minOf(len - 2, 3)
        val posSlot = decodeBitTree(posSlotDecoder[lenToPosState], 0, 6)
        if (posSlot < 4) return posSlot

        val numDirectBits = (posSlot ushr 1) - 1
        var dist = (2 or (posSlot and 1)) shl numDirectBits
        if (posSlot < END_POS_MODEL_INDEX) {
            dist += reverseDecodeBitTree(posDecoders, dist - posSlot - 1, numDirectBits)
        } else {
            dist += decodeDirectBits(numDirectBits - ALIGN_BITS) shl ALIGN_BITS
            dist += reverseDecodeBitTree(alignDecoder, 0, ALIGN_BITS)
        }
        return dist
    }

    private fun decodeBit(
        probs: IntArray,
        index: Int,
    ): Int {
        normalizeRange()
        val prob = probs[index]
        val bound = (range shr NUM_BIT_MODEL_BITS) * prob.toUInt()
        return if (code < bound) {
            range = bound
            probs[index] = prob + ((PROB_TOTAL - prob) ushr NUM_MOVE_BITS)
            0
        } else {
            range -= bound
            code -= bound
            probs[index] = prob - (prob ushr NUM_MOVE_BITS)
            1
        }
    }

    private fun decodeBitTree(
        probs: IntArray,
        offset: Int,
        numBits: Int,
    ): Int {
        var m = 1
        repeat(numBits) {
            m = (m shl 1) or decodeBit(probs, offset + m)
        }
        return m - (1 shl numBits)
    }

    private fun reverseDecodeBitTree(
        probs: IntArray,
        offset: Int,
        numBits: Int,
    ): Int {
        var m = 1
        var symbol = 0
        for (bitIndex in 0 until numBits) {
            val bit = decodeBit(probs, offset + m)
            m = (m shl 1) or bit
            symbol = symbol or (bit shl bitIndex)
        }
        return symbol
    }

    private fun decodeDirectBits(numBits: Int): Int {
        var result = 0
        repeat(numBits) {
            normalizeRange()
            range = range shr 1
            val t = ((code - range) shr 31).toInt()
            code -= range and (t - 1).toUInt()
            result = (result shl 1) or (1 - t)
        }
        return result
    }

    private fun normalizeRange() {
        if (range < RANGE_TOP_MASK) {
            if (chunkOffset >= chunkLimit) {
                throw FlashStepException("CORRUPT_XZ", "Unexpected end of LZMA2 range-coded chunk")
            }
            range = range shl 8
            code = (code shl 8) or (chunkBuffer[chunkOffset++].toUInt() and 0xFFu)
        }
    }

    private fun putDictionaryByte(value: Byte) {
        dictionary[dictWritePos++] = value
        if (dictWritePos == dictionary.size) {
            dictWritePos = 0
            dictFull = true
        }
    }

    private fun getDictionaryByte(distance: Int): Byte {
        var index = dictWritePos - distance - 1
        if (index < 0) index += dictionary.size
        return dictionary[index]
    }

    private inner class LzmaLenDecoder {
        private val choice = IntArray(2)
        private val low = Array(NUM_POS_STATES_MAX) { IntArray(LEN_LOW_SYMBOLS) }
        private val mid = Array(NUM_POS_STATES_MAX) { IntArray(LEN_MID_SYMBOLS) }
        private val high = IntArray(LEN_HIGH_SYMBOLS)

        fun reset() {
            choice.fill(PROB_INIT)
            low.forEach { it.fill(PROB_INIT) }
            mid.forEach { it.fill(PROB_INIT) }
            high.fill(PROB_INIT)
        }

        fun decode(posState: Int): Int {
            if (decodeBit(choice, 0) == 0) {
                return decodeBitTree(low[posState], 0, 3)
            }
            return if (decodeBit(choice, 1) == 0) {
                LEN_LOW_SYMBOLS + decodeBitTree(mid[posState], 0, 3)
            } else {
                LEN_LOW_SYMBOLS + LEN_MID_SYMBOLS + decodeBitTree(high, 0, 8)
            }
        }
    }

    private enum class ChunkMode {
        NONE,
        UNCOMPRESSED,
        COMPRESSED,
    }
}

private fun updateStateAfterLiteral(state: Int): Int =
    when {
        state < 4 -> 0
        state < 10 -> state - 3
        else -> state - 6
    }
