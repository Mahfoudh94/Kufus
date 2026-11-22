package io.github.drlacheheb.kufus.disk

private const val DEFAULT_FLASH_CHUNK_BYTES = 1_048_576
private const val DEFAULT_PROGRESS_INTERVAL_MS = 150L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Describes a flash job targeting a verified USB drive.
 */
data class FlashRequest(
    val imagePath: String,
    val deviceId: String,
    val verifyAfterWrite: Boolean = true,
    val ejectAfterWrite: Boolean = true,
    val eventLogPath: String? = null,
)

/**
 * Sequential readable stream supplying uncompressed image bytes for flashing.
 */
interface FlashImageSource {
    val knownUncompressedSizeBytes: Long?

    fun readChunk(buffer: ByteArray): Int

    fun close()
}

/**
 * Sector-aligned block write/read abstraction implemented per operating system.
 */
interface BlockFlashTarget {
    val sectorSizeBytes: Int
    val capacityBytes: Long

    fun unmountAndLock()

    fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    )

    fun syncAndFlush()

    fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int

    fun close()
}

/**
 * Signals a deterministic, machine-readable worker failure during flash execution.
 */
class FlashStepException(
    val code: String,
    override val message: String,
) : IllegalStateException(message)

/**
 * Returns `null` if [info] can be written by the raw/GZIP block flasher, or a user-facing reason if blocked.
 */
fun unsupportedFlashReason(info: ImageInfo): String? =
    when (info.format) {
        ImageFormat.RAW_IMAGE,
        ImageFormat.HYBRID_ISO,
        -> null
        ImageFormat.WINDOWS_ISO ->
            "Windows ISO requires UEFI/NTFS installer preparation rather than raw byte writing."
        ImageFormat.UNKNOWN ->
            "Unrecognized image format — choose a valid bootable ISO, raw IMG, or GZIP image."
        ImageFormat.COMPRESSED_IMAGE ->
            if (info.compression == CompressionFormat.GZIP) {
                null
            } else {
                "Compressed ${info.compression.name} streaming is not yet enabled in the native flasher."
            }
    }

/**
 * Executes unmounting, aligned block streaming, flushing, and optional SHA-256 verification.
 */
object FlashEngine {
    fun execute(
        source: FlashImageSource,
        target: BlockFlashTarget,
        verifyAfterWrite: Boolean = true,
        chunkSizeBytes: Int = DEFAULT_FLASH_CHUNK_BYTES,
        progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
    ): WorkerEvent.Completed {
        val startMs = clockMs()
        val expectedTotal = source.knownUncompressedSizeBytes ?: 0L
        validateSectorSize(target.sectorSizeBytes)
        if (expectedTotal > target.capacityBytes) {
            throw FlashStepException(
                code = "IMAGE_TOO_LARGE",
                message = "Image size ($expectedTotal bytes) exceeds target drive capacity (${target.capacityBytes} bytes)",
            )
        }

        val alignedChunkBytes = alignUp(maxOf(chunkSizeBytes, target.sectorSizeBytes), target.sectorSizeBytes)
        val buffer = ByteArray(alignedChunkBytes)

        try {
            onEvent(WorkerEvent.Progress(FlashPhase.UNMOUNTING, 0L, expectedTotal, 0L))
            checkCancelled(shouldCancel)
            target.unmountAndLock()

            if (expectedTotal > target.capacityBytes) {
                throw FlashStepException(
                    code = "IMAGE_TOO_LARGE",
                    message = "Image size ($expectedTotal bytes) exceeds target capacity (${target.capacityBytes} bytes)",
                )
            }

            onEvent(WorkerEvent.Started(expectedTotal))

            val writeStats =
                streamWritePhase(
                    source = source,
                    target = target,
                    buffer = buffer,
                    expectedTotal = expectedTotal,
                    progressIntervalMs = progressIntervalMs,
                    clockMs = clockMs,
                    shouldCancel = shouldCancel,
                    onEvent = onEvent,
                )

            target.syncAndFlush()

            if (verifyAfterWrite) {
                verifyWrittenData(
                    target = target,
                    buffer = buffer,
                    writtenBytes = writeStats.bytesWritten,
                    expectedDigest = writeStats.digest,
                    progressIntervalMs = progressIntervalMs,
                    clockMs = clockMs,
                    shouldCancel = shouldCancel,
                    onEvent = onEvent,
                )
            }

            return WorkerEvent.Completed(
                verified = verifyAfterWrite,
                elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
            )
        } finally {
            runCatching { source.close() }
            runCatching { target.close() }
        }
    }

    private data class WritePhaseResult(
        val bytesWritten: Long,
        val digest: ByteArray,
    )

    private fun streamWritePhase(
        source: FlashImageSource,
        target: BlockFlashTarget,
        buffer: ByteArray,
        expectedTotal: Long,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ): WritePhaseResult {
        val digest = Sha256()
        val phaseStartMs = clockMs()
        var lastEmitMs = phaseStartMs
        var bytesWritten = 0L

        while (true) {
            checkCancelled(shouldCancel)
            val bytesRead = fillChunkBuffer(source, buffer)
            if (bytesRead == 0) break
            if (bytesWritten + bytesRead > target.capacityBytes) {
                throw FlashStepException(
                    code = "IMAGE_TOO_LARGE",
                    message = "Image exceeds target capacity (${target.capacityBytes} bytes)",
                )
            }
            digest.update(buffer, 0, bytesRead)
            val alignedLength = alignUp(bytesRead, target.sectorSizeBytes)
            if (alignedLength > bytesRead) {
                buffer.fill(0, fromIndex = bytesRead, toIndex = alignedLength)
            }
            target.writeAt(bytesWritten, buffer, alignedLength)
            bytesWritten += bytesRead

            val now = clockMs()
            if (now - lastEmitMs >= progressIntervalMs) {
                onEvent(
                    WorkerEvent.Progress(
                        phase = FlashPhase.WRITING,
                        bytesProcessed = bytesWritten,
                        totalBytes = maxOf(expectedTotal, bytesWritten),
                        speedBytesPerSec = computeSpeedBytesPerSec(bytesWritten, now - phaseStartMs),
                    ),
                )
                lastEmitMs = now
            }
        }

        if (bytesWritten == 0L) {
            throw FlashStepException(code = "EMPTY_IMAGE", message = "Image stream contained zero bytes")
        }

        onEvent(
            WorkerEvent.Progress(
                phase = FlashPhase.WRITING,
                bytesProcessed = bytesWritten,
                totalBytes = bytesWritten,
                speedBytesPerSec = computeSpeedBytesPerSec(bytesWritten, clockMs() - phaseStartMs),
            ),
        )
        return WritePhaseResult(bytesWritten = bytesWritten, digest = digest.digest())
    }

    private fun verifyWrittenData(
        target: BlockFlashTarget,
        buffer: ByteArray,
        writtenBytes: Long,
        expectedDigest: ByteArray,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ) {
        val verifyDigest = Sha256()
        val phaseStartMs = clockMs()
        var lastEmitMs = phaseStartMs
        var offset = 0L

        while (offset < writtenBytes) {
            checkCancelled(shouldCancel)
            val remaining = writtenBytes - offset
            val exactNeed = minOf(buffer.size.toLong(), remaining).toInt()
            val alignedReadLength = alignUp(exactNeed, target.sectorSizeBytes)
            readFullyFromTarget(target, offset, buffer, alignedReadLength, exactNeed)
            verifyDigest.update(buffer, 0, exactNeed)
            offset += exactNeed

            val now = clockMs()
            if (now - lastEmitMs >= progressIntervalMs || offset == writtenBytes) {
                onEvent(
                    WorkerEvent.Progress(
                        phase = FlashPhase.VERIFYING,
                        bytesProcessed = offset,
                        totalBytes = writtenBytes,
                        speedBytesPerSec = computeSpeedBytesPerSec(offset, now - phaseStartMs),
                    ),
                )
                lastEmitMs = now
            }
        }

        if (!verifyDigest.digest().contentEquals(expectedDigest)) {
            throw FlashStepException(
                code = "VERIFY_FAILED",
                message = "Verification mismatch after writing $writtenBytes bytes to target drive",
            )
        }
    }
}

private fun fillChunkBuffer(
    source: FlashImageSource,
    buffer: ByteArray,
): Int {
    var total = 0
    val scratch by lazy(LazyThreadSafetyMode.NONE) { ByteArray(buffer.size) }
    while (total < buffer.size) {
        if (total == 0) {
            val read = source.readChunk(buffer)
            if (read <= 0) return 0
            total += read
        } else {
            val remaining = buffer.size - total
            val targetSlice = if (scratch.size == remaining) scratch else ByteArray(remaining)
            val read = source.readChunk(targetSlice)
            if (read <= 0) break
            targetSlice.copyInto(buffer, destinationOffset = total, startIndex = 0, endIndex = read)
            total += read
        }
    }
    return total
}

private fun readFullyFromTarget(
    target: BlockFlashTarget,
    offset: Long,
    buffer: ByteArray,
    alignedReadLength: Int,
    minimumBytesNeeded: Int,
) {
    val bytesRead = target.readAt(offset, buffer, alignedReadLength)
    if (bytesRead < minimumBytesNeeded) {
        throw FlashStepException(
            code = "VERIFY_READ_FAILED",
            message = "Short read during verification at offset $offset (expected $minimumBytesNeeded, got $bytesRead)",
        )
    }
}

private fun validateSectorSize(sectorSizeBytes: Int) {
    if (sectorSizeBytes <= 0 || (sectorSizeBytes and (sectorSizeBytes - 1)) != 0) {
        throw FlashStepException(
            code = "INVALID_SECTOR_SIZE",
            message = "Sector size must be a positive power of two (got $sectorSizeBytes)",
        )
    }
}

private fun checkCancelled(shouldCancel: () -> Boolean) {
    if (shouldCancel()) {
        throw FlashStepException(code = "CANCELLED", message = "Flash cancelled by user")
    }
}

private fun alignUp(
    value: Int,
    alignment: Int,
): Int = ((value + alignment - 1) / alignment) * alignment

private fun computeSpeedBytesPerSec(
    bytes: Long,
    elapsedMs: Long,
): Long {
    if (bytes <= 0L || elapsedMs <= 0L) return 0L
    return (bytes * MILLIS_PER_SECOND) / elapsedMs
}
