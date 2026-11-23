package io.github.drlacheheb.kufus.disk

private const val QUICK_ZERO_HEAD_BYTES = 4_194_304L
private const val QUICK_ZERO_TAIL_BYTES = 1_048_576L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Prepares a raw block target prior to partitioning by either wiping every sector or clearing stale
 * partition-table and filesystem metadata regions at the head, partition start, and disk tail.
 */
internal object DiskSurfacePreparer {
    @Suppress("LongParameterList")
    fun prepare(
        quickFormat: Boolean,
        target: BlockFlashTarget,
        dataSpan: PartitionSpan,
        zeroBuffer: ByteArray,
        startMs: Long,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ) {
        if (!quickFormat) {
            runFullZeroWipe(
                target = target,
                zeroBuffer = zeroBuffer,
                startMs = startMs,
                progressIntervalMs = progressIntervalMs,
                clockMs = clockMs,
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )
        } else {
            clearStaleMetadataRegions(target, dataSpan, zeroBuffer, shouldCancel)
        }
    }

    fun runFullZeroWipe(
        target: BlockFlashTarget,
        zeroBuffer: ByteArray,
        startMs: Long,
        progressIntervalMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ) {
        val total = alignDownSector(target.capacityBytes, target.sectorSizeBytes)
        onEvent(WorkerEvent.Started(totalBytes = total))
        var offset = 0L
        var lastReportMs = startMs
        while (offset < total) {
            ensureNotCancelled(shouldCancel)
            val remaining = total - offset
            val writeLen = minOf(zeroBuffer.size.toLong(), remaining).toInt()
            val alignedLen = alignDownSector(writeLen.toLong(), target.sectorSizeBytes).toInt()
            if (alignedLen <= 0) break
            target.writeAt(offset, zeroBuffer, alignedLen)
            offset += alignedLen.toLong()
            val now = clockMs()
            if (offset == total || now - lastReportMs >= progressIntervalMs) {
                val elapsed = (now - startMs).coerceAtLeast(1L)
                val speed = (offset * MILLIS_PER_SECOND) / elapsed
                onEvent(WorkerEvent.Progress(FlashPhase.WIPING, offset, total, speed))
                lastReportMs = now
            }
        }
        target.syncAndFlush()
    }

    fun clearStaleMetadataRegions(
        target: BlockFlashTarget,
        span: PartitionSpan,
        zeroBuffer: ByteArray,
        shouldCancel: () -> Boolean,
    ) {
        val headZeroLimit = minOf(QUICK_ZERO_HEAD_BYTES, target.capacityBytes)
        zeroDiskRange(target, startOffset = 0L, lengthBytes = headZeroLimit, zeroBuffer = zeroBuffer, shouldCancel = shouldCancel)

        val partitionZeroBytes = minOf(QUICK_ZERO_TAIL_BYTES, span.sizeBytes)
        zeroDiskRange(
            target = target,
            startOffset = span.startByteOffset,
            lengthBytes = partitionZeroBytes,
            zeroBuffer = zeroBuffer,
            shouldCancel = shouldCancel,
        )

        val tailStart = alignDownSector(target.capacityBytes - minOf(QUICK_ZERO_TAIL_BYTES, target.capacityBytes), target.sectorSizeBytes)
        zeroDiskRange(
            target = target,
            startOffset = tailStart,
            lengthBytes = target.capacityBytes - tailStart,
            zeroBuffer = zeroBuffer,
            shouldCancel = shouldCancel,
        )
    }

    private fun zeroDiskRange(
        target: BlockFlashTarget,
        startOffset: Long,
        lengthBytes: Long,
        zeroBuffer: ByteArray,
        shouldCancel: () -> Boolean,
    ) {
        var written = 0L
        while (written < lengthBytes) {
            ensureNotCancelled(shouldCancel)
            val remaining = lengthBytes - written
            val step = minOf(zeroBuffer.size.toLong(), remaining).toInt()
            val alignedStep = alignUpSector(step, target.sectorSizeBytes)
            val maxSectorAligned = ((target.capacityBytes - (startOffset + written)) / target.sectorSizeBytes) * target.sectorSizeBytes
            val bounded = alignedStep.toLong().coerceAtMost(maxSectorAligned).toInt()
            if (bounded <= 0) break
            target.writeAt(startOffset + written, zeroBuffer, bounded)
            written += bounded.toLong()
        }
    }

    private fun alignUpSector(
        value: Int,
        sectorSizeBytes: Int,
    ): Int = ((value + sectorSizeBytes - 1) / sectorSizeBytes) * sectorSizeBytes

    private fun alignDownSector(
        value: Long,
        sectorSizeBytes: Int,
    ): Long = (value / sectorSizeBytes.toLong()) * sectorSizeBytes.toLong()

    private fun ensureNotCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw FlashStepException(code = "CANCELLED", message = "Format cancelled by user")
        }
    }
}
