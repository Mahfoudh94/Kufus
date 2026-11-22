package io.github.drlacheheb.kufus.disk

private const val CRC32_POLYNOMIAL = 0xEDB88320.toInt()
private const val CRC64_ECMA_POLYNOMIAL = -0x3693a86a2878f0beL // 0xC96C5795D7870F42UL
private const val TABLE_SIZE = 256
private const val BITS_PER_BYTE = 8
private const val DEFAULT_READER_BUFFER_BYTES = 65_536

private const val XXH64_PRIME1 = 0x9E3779B185EBCA87uL
private const val XXH64_PRIME2 = 0xC2B2AE3D27D4EB4FuL
private const val XXH64_PRIME3 = 0x165667B19E3779F9uL
private const val XXH64_PRIME4 = 0x85EBCA77C2B2AE63uL
private const val XXH64_PRIME5 = 0x27D4EB2F165667C5uL

/**
 * Throws [FlashStepException] with [code] when [condition] is false.
 */
internal inline fun requireStream(
    condition: Boolean,
    code: String,
    message: () -> String,
) {
    if (!condition) {
        throw FlashStepException(code = code, message = message())
    }
}

/**
 * Sequential byte stream abstraction shared across common tests and native file handles.
 */
interface StreamingByteSource {
    fun readBytes(
        buffer: ByteArray,
        offset: Int = 0,
        maxLength: Int = buffer.size - offset,
    ): Int

    fun close()
}

/**
 * In-memory [StreamingByteSource] useful for unit tests and embedded compressed streams.
 */
internal class ByteArrayStreamingSource(
    private val data: ByteArray,
    private val maxChunkBytes: Int = data.size.coerceAtLeast(1),
) : StreamingByteSource {
    private var offset = 0

    override fun readBytes(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (this.offset >= data.size || maxLength <= 0) return 0
        val count = minOf(maxLength, maxChunkBytes, data.size - this.offset)
        data.copyInto(buffer, destinationOffset = offset, startIndex = this.offset, endIndex = this.offset + count)
        this.offset += count
        return count
    }

    override fun close() = Unit
}

/**
 * Buffered sequential reader tracking exact consumed byte counts from a [StreamingByteSource].
 */
internal class BufferedByteInput(
    private val source: StreamingByteSource,
    bufferSize: Int = DEFAULT_READER_BUFFER_BYTES,
) {
    private val buffer = ByteArray(bufferSize)
    private var offset = 0
    private var available = 0
    private var reachedEof = false

    var totalBytesConsumed: Long = 0L
        private set

    fun readByteOrEof(): Int {
        if (!ensureBuffered()) return -1
        totalBytesConsumed++
        available--
        return buffer[offset++].toInt() and 0xFF
    }

    fun readByteOrThrow(
        errorCode: String,
        message: String,
    ): Int {
        val value = readByteOrEof()
        if (value < 0) throw FlashStepException(code = errorCode, message = message)
        return value
    }

    fun readUInt16BeOrThrow(
        errorCode: String,
        message: String,
    ): Int {
        val b0 = readByteOrThrow(errorCode, message)
        val b1 = readByteOrThrow(errorCode, message)
        return (b0 shl 8) or b1
    }

    fun readUInt16LeOrThrow(
        errorCode: String,
        message: String,
    ): Int {
        val b0 = readByteOrThrow(errorCode, message)
        val b1 = readByteOrThrow(errorCode, message)
        return b0 or (b1 shl 8)
    }

    fun readUInt32LeOrThrow(
        errorCode: String,
        message: String,
    ): UInt {
        val b0 = readByteOrThrow(errorCode, message).toUInt()
        val b1 = readByteOrThrow(errorCode, message).toUInt()
        val b2 = readByteOrThrow(errorCode, message).toUInt()
        val b3 = readByteOrThrow(errorCode, message).toUInt()
        return b0 or (b1 shl 8) or (b2 shl 16) or (b3 shl 24)
    }

    fun readFully(
        dest: ByteArray,
        destOffset: Int = 0,
        length: Int = dest.size - destOffset,
        errorCode: String,
        message: String,
    ) {
        var cursor = destOffset
        var remaining = length
        while (remaining > 0) {
            if (!ensureBuffered()) {
                throw FlashStepException(code = errorCode, message = message)
            }
            val take = minOf(available, remaining)
            buffer.copyInto(dest, destinationOffset = cursor, startIndex = offset, endIndex = offset + take)
            offset += take
            available -= take
            cursor += take
            remaining -= take
            totalBytesConsumed += take.toLong()
        }
    }

    fun skipFully(
        count: Long,
        errorCode: String,
        message: String,
    ) {
        var remaining = count
        while (remaining > 0L) {
            if (!ensureBuffered()) {
                throw FlashStepException(code = errorCode, message = message)
            }
            val take = minOf(available.toLong(), remaining).toInt()
            offset += take
            available -= take
            remaining -= take.toLong()
            totalBytesConsumed += take.toLong()
        }
    }

    fun close() {
        source.close()
    }

    private fun ensureBuffered(): Boolean {
        if (available > 0) return true
        if (reachedEof) return false
        val read = source.readBytes(buffer, offset = 0, maxLength = buffer.size)
        if (read <= 0) {
            reachedEof = true
            return false
        }
        offset = 0
        available = read
        return true
    }
}

/**
 * Standard IEEE 802.3 CRC-32 checksum used across XZ headers, indexes, footers, and block checks.
 */
internal class Crc32 {
    private var state = -1

    fun update(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ) {
        for (i in offset until (offset + length)) {
            val tableIndex = (state xor (input[i].toInt() and 0xFF)) and 0xFF
            state = (state ushr BITS_PER_BYTE) xor crc32Table[tableIndex]
        }
    }

    fun value(): Long = (state.inv().toLong()) and 0xFFFF_FFFFL

    companion object {
        private val crc32Table =
            IntArray(TABLE_SIZE) { entry ->
                var crc = entry
                repeat(BITS_PER_BYTE) {
                    crc = if ((crc and 1) != 0) (crc ushr 1) xor CRC32_POLYNOMIAL else (crc ushr 1)
                }
                crc
            }

        fun compute(
            input: ByteArray,
            offset: Int = 0,
            length: Int = input.size - offset,
        ): Long = Crc32().apply { update(input, offset, length) }.value()
    }
}

/**
 * ECMA-182 CRC-64 checksum used as the default block check in XZ streams.
 */
internal class Crc64Ecma {
    private var state = -1L

    fun update(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ) {
        for (i in offset until (offset + length)) {
            val tableIndex = ((state.toInt() xor (input[i].toInt() and 0xFF)) and 0xFF)
            state = (state ushr BITS_PER_BYTE) xor crc64Table[tableIndex]
        }
    }

    fun digestBytesLittleEndian(): ByteArray {
        val finalValue = state.inv()
        return ByteArray(8) { index ->
            ((finalValue ushr (index * BITS_PER_BYTE)) and 0xFFL).toByte()
        }
    }

    fun value(): ULong = state.inv().toULong()

    companion object {
        private val crc64Table =
            LongArray(TABLE_SIZE) { entry ->
                var crc = entry.toLong()
                repeat(BITS_PER_BYTE) {
                    crc = if ((crc and 1L) != 0L) (crc ushr 1) xor CRC64_ECMA_POLYNOMIAL else (crc ushr 1)
                }
                crc
            }
    }
}

/**
 * Streaming xxHash-64 implementation used to validate RFC 8878 Zstandard content checksums.
 */
internal class XxHash64(
    private val seed: ULong = 0uL,
) {
    private var v1 = seed + XXH64_PRIME1 + XXH64_PRIME2
    private var v2 = seed + XXH64_PRIME2
    private var v3 = seed
    private var v4 = seed - XXH64_PRIME1
    private var totalLength = 0uL
    private val pending = ByteArray(32)
    private var pendingLen = 0

    fun update(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ) {
        if (length <= 0) return
        totalLength += length.toULong()
        var cursor = offset
        var remaining = length

        if (pendingLen > 0) {
            val fill = minOf(32 - pendingLen, remaining)
            input.copyInto(pending, destinationOffset = pendingLen, startIndex = cursor, endIndex = cursor + fill)
            pendingLen += fill
            cursor += fill
            remaining -= fill
            if (pendingLen == 32) {
                processStripe(pending, 0)
                pendingLen = 0
            }
        }

        while (remaining >= 32) {
            processStripe(input, cursor)
            cursor += 32
            remaining -= 32
        }

        if (remaining > 0) {
            input.copyInto(pending, destinationOffset = 0, startIndex = cursor, endIndex = cursor + remaining)
            pendingLen = remaining
        }
    }

    fun digestLow32(): UInt = (digest() and 0xFFFF_FFFFuL).toUInt()

    fun digest(): ULong {
        var hash =
            if (totalLength >= 32uL) {
                var acc = v1.rotateLeft(1) + v2.rotateLeft(7) + v3.rotateLeft(12) + v4.rotateLeft(18)
                acc = mergeAccumulator(acc, v1)
                acc = mergeAccumulator(acc, v2)
                acc = mergeAccumulator(acc, v3)
                mergeAccumulator(acc, v4)
            } else {
                seed + XXH64_PRIME5
            }
        hash += totalLength

        var index = 0
        while (index + 8 <= pendingLen) {
            val lane = roundLane(0uL, readU64Le(pending, index))
            hash = (hash xor lane).rotateLeft(27) * XXH64_PRIME1 + XXH64_PRIME4
            index += 8
        }
        while (index + 4 <= pendingLen) {
            hash = (hash xor (readU32Le(pending, index) * XXH64_PRIME1)).rotateLeft(23) * XXH64_PRIME2 + XXH64_PRIME3
            index += 4
        }
        while (index < pendingLen) {
            val byteVal = (pending[index++].toInt() and 0xFF).toULong()
            hash = (hash xor (byteVal * XXH64_PRIME5)).rotateLeft(11) * XXH64_PRIME1
        }

        hash = hash xor (hash shr 33)
        hash *= XXH64_PRIME2
        hash = hash xor (hash shr 29)
        hash *= XXH64_PRIME3
        return hash xor (hash shr 32)
    }

    private fun processStripe(
        src: ByteArray,
        offset: Int,
    ) {
        v1 = roundLane(v1, readU64Le(src, offset))
        v2 = roundLane(v2, readU64Le(src, offset + 8))
        v3 = roundLane(v3, readU64Le(src, offset + 16))
        v4 = roundLane(v4, readU64Le(src, offset + 24))
    }

    private fun roundLane(
        acc: ULong,
        lane: ULong,
    ): ULong = (acc + lane * XXH64_PRIME2).rotateLeft(31) * XXH64_PRIME1

    private fun mergeAccumulator(
        acc: ULong,
        lane: ULong,
    ): ULong {
        val mixed = roundLane(0uL, lane)
        return (acc xor mixed) * XXH64_PRIME1 + XXH64_PRIME4
    }

    private fun readU32Le(
        src: ByteArray,
        offset: Int,
    ): ULong =
        (src[offset].toULong() and 0xFFuL) or
            ((src[offset + 1].toULong() and 0xFFuL) shl 8) or
            ((src[offset + 2].toULong() and 0xFFuL) shl 16) or
            ((src[offset + 3].toULong() and 0xFFuL) shl 24)

    private fun readU64Le(
        src: ByteArray,
        offset: Int,
    ): ULong =
        readU32Le(src, offset) or
            (readU32Le(src, offset + 4) shl 32)
}
