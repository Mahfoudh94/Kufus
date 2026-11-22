package io.github.drlacheheb.kufus.disk

private const val BLOCK_BYTES = 64
private const val LENGTH_FIELD_START = 56
private const val DIGEST_WORDS = 8
private const val SCHEDULE_WORDS = 64
private const val BYTE_MASK = 0xFF
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val SHIFT_32 = 32
private const val SHIFT_40 = 40
private const val SHIFT_48 = 48
private const val SHIFT_56 = 56
private const val BITS_PER_BYTE = 8L
private const val APPEND_ONE_BYTE = 0x80

private val initialState =
    intArrayOf(
        0x6a09e667,
        0xbb67ae85.toInt(),
        0x3c6ef372,
        0xa54ff53a.toInt(),
        0x510e527f,
        0x9b05688c.toInt(),
        0x1f83d9ab,
        0x5be0cd19,
    )

private val roundConstants =
    intArrayOf(
        0x428a2f98,
        0x71374491,
        0xb5c0fbcf.toInt(),
        0xe9b5dba5.toInt(),
        0x3956c25b,
        0x59f111f1,
        0x923f82a4.toInt(),
        0xab1c5ed5.toInt(),
        0xd807aa98.toInt(),
        0x12835b01,
        0x243185be,
        0x550c7dc3,
        0x72be5d74,
        0x80deb1fe.toInt(),
        0x9bdc06a7.toInt(),
        0xc19bf174.toInt(),
        0xe49b69c1.toInt(),
        0xefbe4786.toInt(),
        0x0fc19dc6,
        0x240ca1cc,
        0x2de92c6f,
        0x4a7484aa,
        0x5cb0a9dc,
        0x76f988da,
        0x983e5152.toInt(),
        0xa831c66d.toInt(),
        0xb00327c8.toInt(),
        0xbf597fc7.toInt(),
        0xc6e00bf3.toInt(),
        0xd5a79147.toInt(),
        0x06ca6351,
        0x14292967,
        0x27b70a85,
        0x2e1b2138,
        0x4d2c6dfc,
        0x53380d13,
        0x650a7354,
        0x766a0abb,
        0x81c2c92e.toInt(),
        0x92722c85.toInt(),
        0xa2bfe8a1.toInt(),
        0xa81a664b.toInt(),
        0xc24b8b70.toInt(),
        0xc76c51a3.toInt(),
        0xd192e819.toInt(),
        0xd6990624.toInt(),
        0xf40e3585.toInt(),
        0x106aa070,
        0x19a4c116,
        0x1e376c08,
        0x2748774c,
        0x34b0bcb5,
        0x391c0cb3,
        0x4ed8aa4a,
        0x5b9cca4f,
        0x682e6ff3,
        0x748f82ee,
        0x78a5636f,
        0x84c87814.toInt(),
        0x8cc70208.toInt(),
        0x90befffa.toInt(),
        0xa4506ceb.toInt(),
        0xbef9a3f7.toInt(),
        0xc67178f2.toInt(),
    )

/**
 * Streaming FIPS 180-4 SHA-256 digest used to verify written bytes against read-back disk blocks.
 */
class Sha256 {
    private val state = initialState.copyOf()
    private val schedule = IntArray(SCHEDULE_WORDS)
    private val block = ByteArray(BLOCK_BYTES)
    private var blockOffset = 0
    private var totalBytes = 0L

    fun update(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ) {
        if (length <= 0) return
        var cursor = offset
        var remaining = length
        totalBytes += length.toLong()
        while (remaining > 0) {
            val space = BLOCK_BYTES - blockOffset
            val toCopy = minOf(space, remaining)
            input.copyInto(block, destinationOffset = blockOffset, startIndex = cursor, endIndex = cursor + toCopy)
            blockOffset += toCopy
            cursor += toCopy
            remaining -= toCopy
            if (blockOffset == BLOCK_BYTES) {
                compressBlock(block, 0)
                blockOffset = 0
            }
        }
    }

    fun digest(): ByteArray {
        val savedState = state.copyOf()
        val savedBlock = block.copyOf()
        val savedOffset = blockOffset
        val bitLength = totalBytes * BITS_PER_BYTE

        block[blockOffset++] = APPEND_ONE_BYTE.toByte()
        if (blockOffset > LENGTH_FIELD_START) {
            block.fill(0, fromIndex = blockOffset, toIndex = BLOCK_BYTES)
            compressBlock(block, 0)
            blockOffset = 0
        }
        block.fill(0, fromIndex = blockOffset, toIndex = LENGTH_FIELD_START)
        writeBigEndianLong(block, LENGTH_FIELD_START, bitLength)
        compressBlock(block, 0)

        val out = ByteArray(DIGEST_WORDS * 4)
        for (i in 0 until DIGEST_WORDS) {
            writeBigEndianInt(out, i * 4, state[i])
        }
        savedState.copyInto(state)
        savedBlock.copyInto(block)
        blockOffset = savedOffset
        return out
    }

    private fun compressBlock(
        src: ByteArray,
        offset: Int,
    ) {
        for (i in 0 until 16) {
            schedule[i] = readBigEndianInt(src, offset + i * 4)
        }
        for (i in 16 until SCHEDULE_WORDS) {
            val s0 = schedule[i - 15].rotateRight(7) xor schedule[i - 15].rotateRight(18) xor (schedule[i - 15] ushr 3)
            val s1 = schedule[i - 2].rotateRight(17) xor schedule[i - 2].rotateRight(19) xor (schedule[i - 2] ushr 10)
            schedule[i] = schedule[i - 16] + s0 + schedule[i - 7] + s1
        }
        var a = state[0]
        var b = state[1]
        var c = state[2]
        var d = state[3]
        var e = state[4]
        var f = state[5]
        var g = state[6]
        var h = state[7]

        for (i in 0 until SCHEDULE_WORDS) {
            val sum1 = e.rotateRight(6) xor e.rotateRight(11) xor e.rotateRight(25)
            val ch = (e and f) xor (e.inv() and g)
            val temp1 = h + sum1 + ch + roundConstants[i] + schedule[i]
            val sum0 = a.rotateRight(2) xor a.rotateRight(13) xor a.rotateRight(22)
            val maj = (a and b) xor (a and c) xor (b and c)
            val temp2 = sum0 + maj
            h = g
            g = f
            f = e
            e = d + temp1
            d = c
            c = b
            b = a
            a = temp1 + temp2
        }

        state[0] += a
        state[1] += b
        state[2] += c
        state[3] += d
        state[4] += e
        state[5] += f
        state[6] += g
        state[7] += h
    }
}

private fun readBigEndianInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and BYTE_MASK) shl SHIFT_24) or
        ((bytes[offset + 1].toInt() and BYTE_MASK) shl SHIFT_16) or
        ((bytes[offset + 2].toInt() and BYTE_MASK) shl SHIFT_8) or
        (bytes[offset + 3].toInt() and BYTE_MASK)

private fun writeBigEndianInt(
    dest: ByteArray,
    offset: Int,
    value: Int,
) {
    dest[offset] = ((value ushr SHIFT_24) and BYTE_MASK).toByte()
    dest[offset + 1] = ((value ushr SHIFT_16) and BYTE_MASK).toByte()
    dest[offset + 2] = ((value ushr SHIFT_8) and BYTE_MASK).toByte()
    dest[offset + 3] = (value and BYTE_MASK).toByte()
}

private fun writeBigEndianLong(
    dest: ByteArray,
    offset: Int,
    value: Long,
) {
    dest[offset] = ((value ushr SHIFT_56) and BYTE_MASK.toLong()).toByte()
    dest[offset + 1] = ((value ushr SHIFT_48) and BYTE_MASK.toLong()).toByte()
    dest[offset + 2] = ((value ushr SHIFT_40) and BYTE_MASK.toLong()).toByte()
    dest[offset + 3] = ((value ushr SHIFT_32) and BYTE_MASK.toLong()).toByte()
    dest[offset + 4] = ((value ushr SHIFT_24) and BYTE_MASK.toLong()).toByte()
    dest[offset + 5] = ((value ushr SHIFT_16) and BYTE_MASK.toLong()).toByte()
    dest[offset + 6] = ((value ushr SHIFT_8) and BYTE_MASK.toLong()).toByte()
    dest[offset + 7] = (value and BYTE_MASK.toLong()).toByte()
}
