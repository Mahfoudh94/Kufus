package io.github.drlacheheb.kufus.disk.io

/**
 * Sequential readable stream supplying uncompressed image bytes for flashing.
 */
interface FlashImageSource {
    val knownUncompressedSizeBytes: Long?

    fun readChunk(buffer: ByteArray): Int

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
