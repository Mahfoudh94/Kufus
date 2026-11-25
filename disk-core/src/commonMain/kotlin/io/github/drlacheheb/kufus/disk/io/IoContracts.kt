package io.github.drlacheheb.kufus.disk.io

/**
 * Sequential readable stream supplying uncompressed image bytes for flashing.
 */
interface FlashImageSource {
    val knownUncompressedSizeBytes: Long?

    fun readChunk(buffer: ByteArray): Int

    fun readChunk(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (maxLength <= 0) return 0
        if (offset == 0 && maxLength == buffer.size) return readChunk(buffer)
        val scratch = ByteArray(maxLength)
        val read = readChunk(scratch)
        if (read > 0) scratch.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = read)
        return read
    }

    fun close()
}

/**
 * Random-access read contract used by image and archive inspectors.
 */
interface SeekableByteReader {
    fun sizeBytes(): Long

    fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray

    fun readInto(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int = 0,
        length: Int = destination.size - destinationOffset,
    ): Int {
        if (offset < 0L || length <= 0) return 0
        val bytes = readAt(offset, length)
        if (bytes.isEmpty()) return 0
        bytes.copyInto(destination, destinationOffset = destinationOffset, startIndex = 0, endIndex = bytes.size)
        return bytes.size
    }
}

/**
 * Minimal sector-aligned write contract used by partition formatters.
 */
interface BlockWriter {
    val sectorSizeBytes: Int

    fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    )
}

/**
 * Sector-aligned block write/read abstraction implemented per operating system.
 */
interface BlockFlashTarget : BlockWriter {
    val capacityBytes: Long

    fun unmountAndLock()

    fun syncAndFlush()

    fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int

    fun close()
}

/**
 * Signals a deterministic, machine-readable worker failure during flash or format execution.
 */
class FlashStepException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)
