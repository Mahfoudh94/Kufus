package io.github.drlacheheb.kufus.disk.compression

private const val BCJ_STAGE_CAPACITY = 65_536
private const val BCJ_TRAILING_RESERVE = 4

/**
 * Post-decompression branch-call-jump filter stage supported in XZ blocks (x86 and ARM64).
 */
internal interface XzByteTransform {
    val minimumLookaheadBytes: Int

    fun processChunk(
        buffer: ByteArray,
        length: Int,
    ): Int
}

/**
 * Buffers [decoder] output so [transforms] always see contiguous instruction lookahead across reads.
 */
internal class XzFilteredDecoderStage(
    private val decoder: XzLzma2Decoder,
    private val transforms: List<XzByteTransform>,
) {
    private val stageBuffer = ByteArray(BCJ_STAGE_CAPACITY)
    private var readyStart = 0
    private var readyEnd = 0
    private var tailStart = 0
    private var tailEnd = 0
    private var decoderEof = false

    val finished: Boolean
        get() = decoderEof && readyStart >= readyEnd && tailStart >= tailEnd

    fun readFiltered(
        dest: ByteArray,
        destOffset: Int,
        maxLength: Int,
    ): Int {
        if (maxLength <= 0 || finished) return 0
        if (transforms.isEmpty()) {
            val count = decoder.readInto(dest, destOffset, maxLength)
            if (decoder.streamFinished) decoderEof = true
            return count
        }
        if (readyStart >= readyEnd) {
            fillAndTransformStage()
        }
        val available = readyEnd - readyStart
        if (available <= 0) return 0
        val count = minOf(available, maxLength)
        stageBuffer.copyInto(dest, destinationOffset = destOffset, startIndex = readyStart, endIndex = readyStart + count)
        readyStart += count
        return count
    }

    private fun fillAndTransformStage() {
        val minBytesNeeded = transforms.maxOf { it.minimumLookaheadBytes } + 1
        var totalLoaded = moveTailToFront()

        while (!decoderEof && totalLoaded < stageBuffer.size) {
            val read = decoder.readInto(stageBuffer, totalLoaded, stageBuffer.size - totalLoaded)
            if (read > 0) totalLoaded += read
            if (decoder.streamFinished) decoderEof = true
            if (decoderEof || totalLoaded >= minBytesNeeded) break
        }

        if (totalLoaded == 0) {
            readyStart = 0
            readyEnd = 0
            return
        }

        val converted = applyTransformChain(stageBuffer, totalLoaded)
        readyStart = 0
        if (decoderEof) {
            readyEnd = totalLoaded
            tailStart = 0
            tailEnd = 0
        } else {
            readyEnd = converted
            tailStart = converted
            tailEnd = totalLoaded
        }
    }

    private fun moveTailToFront(): Int {
        val tailLen = tailEnd - tailStart
        if (tailLen > 0) {
            stageBuffer.copyInto(stageBuffer, destinationOffset = 0, startIndex = tailStart, endIndex = tailEnd)
        }
        tailStart = 0
        tailEnd = 0
        return tailLen
    }

    private fun applyTransformChain(
        buffer: ByteArray,
        totalLength: Int,
    ): Int {
        var processed = totalLength
        for (index in transforms.lastIndex downTo 0) {
            processed = transforms[index].processChunk(buffer, processed)
        }
        return processed
    }
}

/**
 * XZ x86 CALL/JMP (E8/E9) BCJ inverse filter.
 */
internal class X86BcjDecoder(
    startOffset: Int = 0,
) : XzByteTransform {
    override val minimumLookaheadBytes: Int = BCJ_TRAILING_RESERVE
    private var currentPos = startOffset + 5
    private var prevMask = 0

    override fun processChunk(
        buffer: ByteArray,
        length: Int,
    ): Int {
        if (length < 5) return 0
        val end = length - 5
        var cursor = 0
        var prevPos = -1

        while (cursor <= end) {
            if ((buffer[cursor].toInt() and 0xFE) != 0xE8) {
                cursor++
            } else {
                val distance = cursor - prevPos
                prevPos = cursor
                val skipMasked = updateMaskAndShouldSkip(buffer, cursor, distance)
                cursor += if (skipMasked) 1 else processX86Instruction(buffer, cursor)
            }
        }

        val tailDistance = cursor - prevPos
        prevMask = if ((tailDistance and -4) != 0) 0 else prevMask shl (tailDistance - 1)
        currentPos += cursor
        return cursor
    }

    private fun updateMaskAndShouldSkip(
        buffer: ByteArray,
        cursor: Int,
        distance: Int,
    ): Boolean {
        if ((distance and -4) != 0) {
            prevMask = 0
            return false
        }
        prevMask = (prevMask shl (distance - 1)) and 0x07
        if (prevMask != 0 && shouldSkipMaskedByte(buffer, cursor)) {
            prevMask = (prevMask shl 1) or 1
            return true
        }
        return false
    }

    private fun shouldSkipMaskedByte(
        buffer: ByteArray,
        cursor: Int,
    ): Boolean = !MASK_ALLOWED_STATUS[prevMask] || isSignExtensionByte(buffer[cursor + 4 - MASK_BIT_NUMBER[prevMask]])

    private fun processX86Instruction(
        buffer: ByteArray,
        cursor: Int,
    ): Int {
        if (!isSignExtensionByte(buffer[cursor + 4])) {
            prevMask = (prevMask shl 1) or 1
            return 1
        }
        val src = readInt32Le(buffer, cursor + 1)
        val dest = convertX86Offset(src, cursor)
        buffer[cursor + 1] = dest.toByte()
        buffer[cursor + 2] = (dest ushr 8).toByte()
        buffer[cursor + 3] = (dest ushr 16).toByte()
        buffer[cursor + 4] = (((dest ushr 24) and 1) - 1).inv().toByte()
        return 5
    }

    private fun convertX86Offset(
        initialSrc: Int,
        cursor: Int,
    ): Int {
        var src = initialSrc
        while (true) {
            val dest = src - (currentPos + cursor)
            if (prevMask == 0) return dest
            val shiftBits = 24 - MASK_BIT_NUMBER[prevMask] * 8
            if (!isSignExtensionByte((dest ushr shiftBits).toByte())) return dest
            src = dest xor ((1 shl (shiftBits + 8)) - 1)
        }
    }

    private fun isSignExtensionByte(byteVal: Byte): Boolean = byteVal == 0.toByte() || byteVal == (-1).toByte()

    companion object {
        private val MASK_ALLOWED_STATUS = booleanArrayOf(true, true, true, false, true, false, false, false)
        private val MASK_BIT_NUMBER = intArrayOf(0, 1, 2, 2, 3, 3, 3, 3)
    }
}

/**
 * XZ ARM64 BL/ADRP BCJ inverse filter.
 */
internal class Arm64BcjDecoder(
    startOffset: Int = 0,
) : XzByteTransform {
    override val minimumLookaheadBytes: Int = 3
    private var currentPos = startOffset

    override fun processChunk(
        buffer: ByteArray,
        length: Int,
    ): Int {
        val alignedEnd = length and -4
        var cursor = 0
        while (cursor < alignedEnd) {
            val instr = readInt32Le(buffer, cursor)
            val updated = decodeArm64Instruction(instr, currentPos + cursor)
            if (updated != instr) {
                writeInt32Le(buffer, cursor, updated)
            }
            cursor += 4
        }
        currentPos += cursor
        return cursor
    }

    private fun decodeArm64Instruction(
        instr: Int,
        pos: Int,
    ): Int {
        if ((instr ushr 26) == 0x25) {
            val dest = (instr and 0x03FF_FFFF) - (pos ushr 2)
            return 0x9400_0000.toInt() or (dest and 0x03FF_FFFF)
        }
        if ((instr and 0x9F00_0000.toInt()) == 0x9000_0000.toInt()) {
            val src = ((instr ushr 29) and 0x03) or ((instr ushr 3) and 0x001F_FFFC)
            if (((src + 0x0002_0000) and 0x001C_0000) == 0) {
                val dest = src - (pos ushr 12)
                return (instr and 0x9000_001F.toInt()) or
                    ((dest and 0x03) shl 29) or
                    ((dest and 0x0003_FFFC) shl 3) or
                    ((0 - (dest and 0x0002_0000)) and 0x00E0_0000)
            }
        }
        return instr
    }
}

private fun readInt32Le(
    src: ByteArray,
    offset: Int,
): Int =
    (src[offset].toInt() and 0xFF) or
        ((src[offset + 1].toInt() and 0xFF) shl 8) or
        ((src[offset + 2].toInt() and 0xFF) shl 16) or
        ((src[offset + 3].toInt() and 0xFF) shl 24)

private fun writeInt32Le(
    dest: ByteArray,
    offset: Int,
    value: Int,
) {
    dest[offset] = value.toByte()
    dest[offset + 1] = (value ushr 8).toByte()
    dest[offset + 2] = (value ushr 16).toByte()
    dest[offset + 3] = (value ushr 24).toByte()
}
