package io.github.drlacheheb.kufus.disk

private const val DEFAULT_ZERO_CHUNK_BYTES = 1_048_576
private const val MINIMUM_SECTOR_SIZE_BYTES = 512

/**
 * Shared sector-aligned block writing and integer/string encoding helpers for pure-Kotlin formatters.
 */
internal object FormatBlockIo {
    fun alignUpBytes(
        value: Long,
        alignment: Long,
    ): Long {
        if (alignment <= 0L) return value
        val rem = value % alignment
        return if (rem == 0L) value else value + (alignment - rem)
    }

    fun ceilDiv(
        numerator: Long,
        denominator: Long,
    ): Long = (numerator + denominator - 1L) / denominator

    fun ensureNotCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw FlashStepException(code = "CANCELLED", message = "Operation cancelled by user")
        }
    }

    /**
     * Writes [byteCount] zero bytes starting at [startByteOffset] in sector-aligned chunks.
     */
    fun writeZeroSectors(
        writer: BlockWriter,
        startByteOffset: Long,
        byteCount: Long,
        shouldCancel: () -> Boolean,
        chunkBytes: Int = DEFAULT_ZERO_CHUNK_BYTES,
    ) {
        if (byteCount <= 0L) return
        val sectorSize = writer.sectorSizeBytes.coerceAtLeast(MINIMUM_SECTOR_SIZE_BYTES)
        val alignedChunk = (chunkBytes / sectorSize).coerceAtLeast(1) * sectorSize
        val zeros = ByteArray(minOf(byteCount, alignedChunk.toLong()).toInt())
        var written = 0L
        while (written < byteCount) {
            ensureNotCancelled(shouldCancel)
            val remaining = byteCount - written
            val len = minOf(remaining, zeros.size.toLong()).toInt()
            writer.writeAt(startByteOffset + written, zeros, len)
            written += len.toLong()
        }
    }

    /**
     * Writes [data] starting at [startByteOffset], padding any trailing partial sector with zeroes so
     * every [BlockWriter.writeAt] call stays sector-aligned.
     */
    fun writeSectorAlignedStream(
        writer: BlockWriter,
        startByteOffset: Long,
        data: ByteArray,
        shouldCancel: () -> Boolean,
        chunkBytes: Int = DEFAULT_ZERO_CHUNK_BYTES,
    ) {
        if (data.isEmpty()) return
        val sectorSize = writer.sectorSizeBytes.coerceAtLeast(MINIMUM_SECTOR_SIZE_BYTES)
        val alignedWholeBytes = (data.size / sectorSize) * sectorSize
        val alignedChunk = (chunkBytes / sectorSize).coerceAtLeast(1) * sectorSize

        var cursor = 0
        while (cursor < alignedWholeBytes) {
            ensureNotCancelled(shouldCancel)
            val len = minOf(alignedChunk, alignedWholeBytes - cursor)
            val slice = if (cursor == 0 && len == data.size) data else data.copyOfRange(cursor, cursor + len)
            writer.writeAt(startByteOffset + cursor.toLong(), slice, len)
            cursor += len
        }

        val tailLen = data.size - alignedWholeBytes
        if (tailLen > 0) {
            ensureNotCancelled(shouldCancel)
            val paddedSector = ByteArray(sectorSize)
            data.copyInto(paddedSector, destinationOffset = 0, startIndex = alignedWholeBytes, endIndex = data.size)
            writer.writeAt(startByteOffset + alignedWholeBytes.toLong(), paddedSector, sectorSize)
        }
    }

    fun writeUtf16Le(
        dest: ByteArray,
        offset: Int,
        text: String,
        maxChars: Int = text.length,
    ) {
        val count = minOf(text.length, maxChars)
        for (i in 0 until count) {
            writeLeShort(dest, offset + i * 2, text[i].code)
        }
    }

    fun encodeUtf16Le(text: String): ByteArray =
        ByteArray(text.length * 2).also { out ->
            writeUtf16Le(out, 0, text, text.length)
        }

    fun deterministicSerial32(
        sectorCount: Long,
        volumeLabel: String,
    ): Int {
        var acc = (sectorCount xor (sectorCount ushr 32)).toInt() xor 0x4B554655
        for (ch in volumeLabel) {
            acc = acc * 31 + ch.code
        }
        return if (acc == 0) 0x2026_1123 else acc
    }
}
