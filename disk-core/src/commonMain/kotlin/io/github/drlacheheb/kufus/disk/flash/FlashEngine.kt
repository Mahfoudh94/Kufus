package io.github.drlacheheb.kufus.disk.flash

import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.io.FlashImageSource
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.io.Sha256
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.FlashPhase
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.WorkerEvent

private const val DEFAULT_FLASH_CHUNK_BYTES = 4_194_304
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
 * Returns `null` if [info] can be written by the raw/compressed block flasher, or a user-facing reason if blocked.
 */
fun unsupportedFlashReason(info: ImageInfo): String? =
    when (info.format) {
        ImageFormat.RAW_IMAGE,
        ImageFormat.HYBRID_ISO,
        -> null
        ImageFormat.WINDOWS_ISO ->
            "Windows ISO requires UEFI/NTFS installer preparation rather than raw byte writing."
        ImageFormat.UNKNOWN ->
            "Unrecognized image format — choose a valid bootable ISO, raw IMG, or compressed (.gz/.xz/.zst) image."
        ImageFormat.COMPRESSED_IMAGE ->
            if (info.compression == CompressionFormat.NONE) {
                "Unsupported compressed image stream format."
            } else {
                null
            }
    }

/**
 * Executes unmounting, double-buffered aligned block streaming, flushing, and optional SHA-256 verification.
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
        val buffers = arrayOf(ByteArray(alignedChunkBytes), ByteArray(alignedChunkBytes))
        val executor = createBackgroundSlotExecutor("kufus-flash-io")

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
                    buffers = buffers,
                    executor = executor,
                    computeDigest = verifyAfterWrite,
                    expectedTotal = expectedTotal,
                    progressIntervalMs = progressIntervalMs,
                    clockMs = clockMs,
                    shouldCancel = shouldCancel,
                    onEvent = onEvent,
                )

            syncAndVerifyWrite(
                target = target,
                buffers = buffers,
                executor = executor,
                writeStats = writeStats,
                verifyAfterWrite = verifyAfterWrite,
                progressIntervalMs = progressIntervalMs,
                clockMs = clockMs,
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )

            return WorkerEvent.Completed(
                verified = verifyAfterWrite,
                elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
            )
        } finally {
            runCatching { executor.close() }
            runCatching { source.close() }
            runCatching { target.close() }
        }
    }

    @Suppress("LongParameterList")
    private fun syncAndVerifyWrite(
        target: BlockFlashTarget,
        buffers: Array<ByteArray>,
        executor: BackgroundSlotExecutor,
        writeStats: WritePhaseResult,
        verifyAfterWrite: Boolean,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ) {
        target.syncAndFlush()
        onEvent(
            WorkerEvent.Progress(
                phase = FlashPhase.WRITING,
                bytesProcessed = writeStats.bytesWritten,
                totalBytes = writeStats.bytesWritten,
                speedBytesPerSec = computeSpeedBytesPerSec(writeStats.bytesWritten, clockMs() - writeStats.phaseStartMs),
            ),
        )
        if (!verifyAfterWrite) return

        onEvent(
            WorkerEvent.Progress(
                phase = FlashPhase.VERIFYING,
                bytesProcessed = 0L,
                totalBytes = writeStats.bytesWritten,
                speedBytesPerSec = 0L,
            ),
        )
        verifyWrittenData(
            target = target,
            buffers = buffers,
            executor = executor,
            writtenBytes = writeStats.bytesWritten,
            expectedDigest = writeStats.digest,
            progressIntervalMs = progressIntervalMs,
            clockMs = clockMs,
            shouldCancel = shouldCancel,
            onEvent = onEvent,
        )
    }

    private data class WritePhaseResult(
        val bytesWritten: Long,
        val digest: ByteArray,
        val phaseStartMs: Long,
    )

    private class ReadSlotResult(
        val bytesRead: Int,
        val error: Throwable?,
    )

    @Suppress("LongParameterList")
    private fun streamWritePhase(
        source: FlashImageSource,
        target: BlockFlashTarget,
        buffers: Array<ByteArray>,
        executor: BackgroundSlotExecutor,
        computeDigest: Boolean,
        expectedTotal: Long,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ): WritePhaseResult {
        val digest = if (computeDigest) Sha256() else null
        val phaseStartMs = clockMs()
        var lastEmitMs = phaseStartMs
        var bytesWritten = 0L
        var activeSlot = 0
        var pendingRead: BackgroundSlotTask<ReadSlotResult>? =
            submitReadAndHash(executor, source, digest, buffers[activeSlot])

        try {
            while (pendingRead != null) {
                checkCancelled(shouldCancel)
                val bytesRead = awaitReadSlot(pendingRead)
                pendingRead = null
                if (bytesRead <= 0) break
                val nextSlot = 1 - activeSlot
                pendingRead =
                    if (bytesRead == buffers[activeSlot].size) {
                        submitReadAndHash(executor, source, digest, buffers[nextSlot])
                    } else {
                        null
                    }
                writeValidatedChunk(target, buffers[activeSlot], bytesWritten, bytesRead)
                bytesWritten += bytesRead
                lastEmitMs =
                    emitThrottledWriteProgress(
                        bytesWritten = bytesWritten,
                        expectedTotal = expectedTotal,
                        phaseStartMs = phaseStartMs,
                        lastEmitMs = lastEmitMs,
                        progressIntervalMs = progressIntervalMs,
                        clockMs = clockMs,
                        onEvent = onEvent,
                    )
                activeSlot = nextSlot
            }
        } finally {
            pendingRead?.let { task -> runCatching { task.await() } }
        }

        if (bytesWritten == 0L) {
            throw FlashStepException(code = "EMPTY_IMAGE", message = "Image stream contained zero bytes")
        }

        return WritePhaseResult(
            bytesWritten = bytesWritten,
            digest = digest?.digest() ?: ByteArray(0),
            phaseStartMs = phaseStartMs,
        )
    }

    private fun awaitReadSlot(task: BackgroundSlotTask<ReadSlotResult>): Int {
        val result = task.await()
        result.error?.let { throw it }
        return result.bytesRead
    }

    private fun writeValidatedChunk(
        target: BlockFlashTarget,
        buffer: ByteArray,
        bytesWritten: Long,
        bytesRead: Int,
    ) {
        if (bytesWritten + bytesRead > target.capacityBytes) {
            throw FlashStepException(
                code = "IMAGE_TOO_LARGE",
                message = "Image exceeds target capacity (${target.capacityBytes} bytes)",
            )
        }
        val alignedLength = alignUp(bytesRead, target.sectorSizeBytes)
        if (alignedLength > bytesRead) {
            buffer.fill(0, fromIndex = bytesRead, toIndex = alignedLength)
        }
        target.writeAt(bytesWritten, buffer, alignedLength)
    }

    @Suppress("LongParameterList")
    private fun emitThrottledWriteProgress(
        bytesWritten: Long,
        expectedTotal: Long,
        phaseStartMs: Long,
        lastEmitMs: Long,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        onEvent: (WorkerEvent) -> Unit,
    ): Long {
        val now = clockMs()
        if (now - lastEmitMs < progressIntervalMs) return lastEmitMs
        val reportedTotal = if (expectedTotal > 0L) maxOf(expectedTotal, bytesWritten) else 0L
        val reportedBytes =
            if (reportedTotal > 1L) bytesWritten.coerceAtMost(reportedTotal - 1L) else bytesWritten
        onEvent(
            WorkerEvent.Progress(
                phase = FlashPhase.WRITING,
                bytesProcessed = reportedBytes,
                totalBytes = reportedTotal,
                speedBytesPerSec = computeSpeedBytesPerSec(bytesWritten, now - phaseStartMs),
            ),
        )
        return now
    }

    private fun submitReadAndHash(
        executor: BackgroundSlotExecutor,
        source: FlashImageSource,
        digest: Sha256?,
        buffer: ByteArray,
    ): BackgroundSlotTask<ReadSlotResult> =
        executor.submit {
            try {
                val read = fillChunkBuffer(source, buffer)
                if (read > 0 && digest != null) {
                    digest.update(buffer, 0, read)
                }
                ReadSlotResult(bytesRead = read, error = null)
            } catch (
                @Suppress("TooGenericExceptionCaught") error: Throwable,
            ) {
                ReadSlotResult(bytesRead = 0, error = error)
            }
        }

    @Suppress("LongParameterList")
    private fun verifyWrittenData(
        target: BlockFlashTarget,
        buffers: Array<ByteArray>,
        executor: BackgroundSlotExecutor,
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
        var activeSlot = 0
        var pendingHash: BackgroundSlotTask<Unit>? = null

        try {
            while (offset < writtenBytes) {
                checkCancelled(shouldCancel)
                val remaining = writtenBytes - offset
                val buf = buffers[activeSlot]
                val exactNeed = minOf(buf.size.toLong(), remaining).toInt()
                val alignedReadLength = alignUp(exactNeed, target.sectorSizeBytes)
                readFullyFromTarget(target, offset, buf, alignedReadLength, exactNeed)
                pendingHash?.await()
                pendingHash = executor.submit { verifyDigest.update(buf, 0, exactNeed) }
                offset += exactNeed
                activeSlot = 1 - activeSlot

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
            pendingHash?.await()
            pendingHash = null
        } finally {
            pendingHash?.let { task -> runCatching { task.await() } }
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
    while (total < buffer.size) {
        val read = source.readChunk(buffer, offset = total, maxLength = buffer.size - total)
        if (read <= 0) break
        total += read
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
