package io.github.drlacheheb.kufus.disk

private const val DEFAULT_WIPE_CHUNK_BYTES = 1_048_576
private const val DEFAULT_PROGRESS_INTERVAL_MS = 150L
private const val QUICK_ZERO_HEAD_BYTES = 4_194_304L
private const val QUICK_ZERO_TAIL_BYTES = 1_048_576L
private const val MILLIS_PER_SECOND = 1_000L

/**
 * Coordinates USB unmounting, optional full sector wipe, GPT/MBR partitioning, and filesystem creation.
 */
object FormatEngine {
    fun execute(
        request: FormatRequest,
        target: BlockFlashTarget,
        chunkSizeBytes: Int = DEFAULT_WIPE_CHUNK_BYTES,
        progressIntervalMs: Long = DEFAULT_PROGRESS_INTERVAL_MS,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
        externalPartitionFormatter: (
            partitionIndex: Int,
            request: FormatRequest,
            onProgress: (Long, Long) -> Unit,
        ) -> Unit = { _, req, _ ->
            throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "External formatter unavailable for ${req.filesystem}",
            )
        },
    ): WorkerEvent.Completed {
        val startMs = clockMs()
        val span =
            PartitionTableBuilder.computeSinglePartitionSpan(
                diskSizeBytes = target.capacityBytes,
                sectorSizeBytes = target.sectorSizeBytes,
                scheme = request.partitionScheme,
            )
        val alignedChunk = alignUpFormatChunk(maxOf(chunkSizeBytes, target.sectorSizeBytes), target.sectorSizeBytes)
        val zeroBuffer = ByteArray(alignedChunk)
        var targetClosed = false

        try {
            onEvent(WorkerEvent.Progress(FlashPhase.UNMOUNTING, 0L, 0L, 0L))
            ensureNotCancelled(shouldCancel)
            target.unmountAndLock()

            if (!request.quickFormat) {
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
                clearStaleMetadataRegions(target, span, zeroBuffer, shouldCancel)
            }

            onEvent(WorkerEvent.Progress(FlashPhase.PARTITIONING, 0L, 0L, 0L))
            ensureNotCancelled(shouldCancel)
            writePartitionTable(target, span, request)

            targetClosed =
                runFilesystemPhase(
                    request = request,
                    span = span,
                    target = target,
                    shouldCancel = shouldCancel,
                    onEvent = onEvent,
                    externalPartitionFormatter = externalPartitionFormatter,
                )

            return WorkerEvent.Completed(
                verified = false,
                elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
            )
        } finally {
            if (!targetClosed) {
                runCatching { target.close() }
            }
        }
    }

    private fun runFilesystemPhase(
        request: FormatRequest,
        span: PartitionSpan,
        target: BlockFlashTarget,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
        externalPartitionFormatter: (Int, FormatRequest, (Long, Long) -> Unit) -> Unit,
    ): Boolean {
        if (request.filesystem == FilesystemType.FAT32) {
            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 0L, 100L, 0L))
            Fat32Formatter.format(
                target = target,
                span = span,
                volumeLabel = request.volumeLabel,
                requestedClusterBytes = request.clusterSizeBytes,
                shouldCancel = shouldCancel,
            )
            target.syncAndFlush()
            target.close()
            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 100L, 100L, 0L))
            return true
        }
        target.syncAndFlush()
        target.close()
        onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 0L, 100L, 0L))
        ensureNotCancelled(shouldCancel)
        externalPartitionFormatter(1, request) { processed, total ->
            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, processed, total, 0L))
        }
        return true
    }

    internal fun runFullZeroWipe(
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

    internal fun clearStaleMetadataRegions(
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
            val alignedStep = alignUpFormatChunk(step, target.sectorSizeBytes)
            val maxSectorAligned = ((target.capacityBytes - (startOffset + written)) / target.sectorSizeBytes) * target.sectorSizeBytes
            val bounded = alignedStep.toLong().coerceAtMost(maxSectorAligned).toInt()
            if (bounded <= 0) break
            target.writeAt(startOffset + written, zeroBuffer, bounded)
            written += bounded.toLong()
        }
    }

    private fun writePartitionTable(
        target: BlockFlashTarget,
        span: PartitionSpan,
        request: FormatRequest,
    ) {
        val cleanLabel = sanitizeVolumeLabel(request.volumeLabel, request.filesystem).ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        when (request.partitionScheme) {
            PartitionScheme.MBR -> {
                val mbr = PartitionTableBuilder.buildMbrSector(span, request.filesystem)
                target.writeAt(0L, mbr, mbr.size)
            }
            PartitionScheme.GPT -> {
                val gpt = PartitionTableBuilder.buildGptLayout(span, request.filesystem, partitionName = cleanLabel)
                target.writeAt(0L, gpt.protectiveMbrSector, gpt.protectiveMbrSector.size)
                target.writeAt(span.sectorSizeBytes.toLong(), gpt.primaryHeaderSector, gpt.primaryHeaderSector.size)
                target.writeAt(2L * span.sectorSizeBytes.toLong(), gpt.primaryEntriesBytes, gpt.primaryEntriesBytes.size)
                target.writeAt(gpt.backupEntriesOffsetBytes, gpt.backupEntriesBytes, gpt.backupEntriesBytes.size)
                target.writeAt(gpt.backupHeaderOffsetBytes, gpt.backupHeaderSector, gpt.backupHeaderSector.size)
            }
        }
        target.syncAndFlush()
    }

    private fun alignUpFormatChunk(
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
