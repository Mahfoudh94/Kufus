package io.github.drlacheheb.kufus.disk

private const val DEFAULT_WIPE_CHUNK_BYTES = 1_048_576
private const val DEFAULT_PROGRESS_INTERVAL_MS = 150L

/**
 * Coordinates USB unmounting, disk surface preparation, GPT/MBR partitioning, and pure-Kotlin
 * filesystem initialization via a [PartitionFormatterRegistry].
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
        formatterRegistry: PartitionFormatterRegistry = PartitionFormatterRegistry.Default,
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

            DiskSurfacePreparer.prepare(
                quickFormat = request.quickFormat,
                target = target,
                dataSpan = span,
                zeroBuffer = zeroBuffer,
                startMs = startMs,
                progressIntervalMs = progressIntervalMs,
                clockMs = clockMs,
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )

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
                    formatterRegistry = formatterRegistry,
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
        formatterRegistry: PartitionFormatterRegistry,
    ): Boolean {
        onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 0L, 100L, 0L))
        formatterRegistry.format(
            filesystem = request.filesystem,
            writer = target,
            span = span,
            options =
                PartitionFormatOptions(
                    volumeLabel = request.volumeLabel,
                    requestedClusterBytes = request.clusterSizeBytes,
                ),
            shouldCancel = shouldCancel,
        )
        target.syncAndFlush()
        target.close()
        onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 100L, 100L, 0L))
        return true
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

    private fun ensureNotCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw FlashStepException(code = "CANCELLED", message = "Format cancelled by user")
        }
    }
}
