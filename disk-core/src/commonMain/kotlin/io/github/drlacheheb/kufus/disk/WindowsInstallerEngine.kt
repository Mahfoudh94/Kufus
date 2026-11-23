package io.github.drlacheheb.kufus.disk

const val UEFI_NTFS_EXPECTED_SHA256 = "72683fa1250eeea772d3399277b434d4e55ba8dd0dc926e52d817e701fc2eb9e"
private const val UEFI_NTFS_EXPECTED_SIZE_BYTES = 1_048_576L
private const val WINDOWS_COPY_CHUNK_BYTES = 1_048_576
private const val WINDOWS_PROGRESS_INTERVAL_MS = 150L
private const val MILLIS_PER_SECOND = 1_000L

interface WritableMountedFile {
    fun write(
        bytes: ByteArray,
        length: Int,
    )

    fun close()
}

interface MountedPartitionHandle {
    fun ensureDirectory(relativeDirPath: String)

    fun openFileForWrite(relativeFilePath: String): WritableMountedFile

    fun sync()

    fun unmountAndClose()
}

private val windowsInstallerImagePaths =
    setOf(
        "SOURCES/INSTALL.WIM",
        "SOURCES/INSTALL.ESD",
        "SOURCES/INSTALL.SWM",
        "X64/SOURCES/INSTALL.WIM",
        "X64/SOURCES/INSTALL.ESD",
        "X64/SOURCES/INSTALL.SWM",
        "X86/SOURCES/INSTALL.WIM",
        "X86/SOURCES/INSTALL.ESD",
        "X86/SOURCES/INSTALL.SWM",
    )

/**
 * Coordinates UEFI Windows installer USB creation: single FAT32 (with pure-Kotlin WIM splitting)
 * or dual-partition NTFS + verified `UEFI:NTFS` FAT ESP, ISO extraction, and `autounattend.xml`.
 */
object WindowsInstallerEngine {
    @Suppress("LongParameterList")
    fun execute(
        request: WindowsFlashRequest,
        isoReader: SeekableByteReader,
        target: BlockFlashTarget,
        uefiNtfsBytes: ByteArray?,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
        externalPartitionFormatter: (
            partitionIndex: Int,
            formatRequest: FormatRequest,
            onProgress: (Long, Long) -> Unit,
        ) -> Unit,
        mountPartition: (
            partitionIndex: Int,
            filesystem: FilesystemType,
        ) -> MountedPartitionHandle,
        uefiNtfsVerifier: (ByteArray?) -> Unit = ::verifyUefiNtfsBytes,
    ): WorkerEvent.Completed {
        val startMs = clockMs()
        val archive = openValidatedWindowsArchive(isoReader, request, uefiNtfsBytes, uefiNtfsVerifier)
        val meta = extractArchiveMetadataOrThrow(archive, isoReader, request)
        val alignedChunk = ((WINDOWS_COPY_CHUNK_BYTES + target.sectorSizeBytes - 1) / target.sectorSizeBytes) * target.sectorSizeBytes
        val zeroBuffer = ByteArray(alignedChunk)
        var targetClosed = false

        try {
            onEvent(WorkerEvent.Progress(FlashPhase.UNMOUNTING, 0L, meta.totalPayloadBytes, 0L))
            ensureNotCancelled(shouldCancel)
            target.unmountAndLock()

            targetClosed =
                partitionAndFormatWindowsDrive(
                    request = request,
                    target = target,
                    zeroBuffer = zeroBuffer,
                    uefiNtfsBytes = uefiNtfsBytes,
                    startMs = startMs,
                    clockMs = clockMs,
                    shouldCancel = shouldCancel,
                    onEvent = onEvent,
                    externalPartitionFormatter = externalPartitionFormatter,
                )
        } finally {
            if (!targetClosed) {
                runCatching { target.close() }
            }
        }

        extractWindowsPayloadToMountedVolume(
            request = request,
            archive = archive,
            isoReader = isoReader,
            meta = meta,
            clockMs = clockMs,
            shouldCancel = shouldCancel,
            onEvent = onEvent,
            mountPartition = mountPartition,
        )

        return WorkerEvent.Completed(
            verified = false,
            elapsedMs = (clockMs() - startMs).coerceAtLeast(0L),
        )
    }

    private fun openValidatedWindowsArchive(
        isoReader: SeekableByteReader,
        request: WindowsFlashRequest,
        uefiNtfsBytes: ByteArray?,
        uefiNtfsVerifier: (ByteArray?) -> Unit,
    ): OpenedIsoUdfArchive {
        if (request.filesystem == FilesystemType.NTFS) {
            uefiNtfsVerifier(uefiNtfsBytes)
        } else if (request.filesystem != FilesystemType.FAT32) {
            throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "Windows installer requires FAT32 or NTFS filesystem.",
            )
        }
        return IsoUdfReader.open(isoReader)
            ?: throw FlashStepException("INVALID_ISO", "Unable to read ISO/UDF filesystem from '${request.imagePath}'.")
    }

    private fun extractArchiveMetadataOrThrow(
        archive: OpenedIsoUdfArchive,
        isoReader: SeekableByteReader,
        request: WindowsFlashRequest,
    ): WindowsIsoMetadata {
        val fileEntries = archive.entries.filterNot { it.isDirectory }
        val installEntry =
            fileEntries.firstOrNull { entry ->
                entry.relativePath.uppercase() in windowsInstallerImagePaths
            } ?: throw FlashStepException(
                code = "NOT_WINDOWS_ISO",
                message = "ISO '${request.imagePath}' does not contain sources/install.wim or install.esd.",
            )

        val totalPayload = fileEntries.sumOf { it.sizeBytes }
        val largestFile = fileEntries.maxOfOrNull { it.sizeBytes } ?: 0L
        val needsSplit = largestFile > FAT32_MAX_FILE_BYTES
        if (request.filesystem == FilesystemType.FAT32 && needsSplit) {
            val isWim = installEntry.relativePath.uppercase().endsWith(".WIM")
            val splittable = isWim && !WimSplitter.inspectSplitCapability(IsoUdfReader.openEntryReader(isoReader, installEntry)).isSolidOrUnsupported
            if (!splittable) {
                throw FlashStepException(
                    code = "UNSUPPORTED_SOLID_WIM",
                    message = "Image contains a solid >4 GiB install archive (${installEntry.relativePath}) that cannot be split onto FAT32.",
                )
            }
        }
        return WindowsIsoMetadata(
            volumeLabel = archive.volumeLabel,
            installImagePath = installEntry.relativePath,
            installImageSizeBytes = installEntry.sizeBytes,
            largestFileBytes = largestFile,
            totalPayloadBytes = totalPayload,
            isSplitRequiredForFat32 = needsSplit,
            isWimSplittable = true,
            hasExistingUnattendXml = fileEntries.any { it.relativePath.equals("autounattend.xml", ignoreCase = true) },
        )
    }

    private data class ComputedWindowsSpans(
        val dataSpan: PartitionSpan,
        val dualSpans: DualWindowsPartitionSpans?,
    )

    fun verifyUefiNtfsBytes(uefiNtfsBytes: ByteArray?) {
        val error =
            when {
                uefiNtfsBytes == null ->
                    "MISSING_ASSET" to "Required UEFI:NTFS bootloader image was not found."
                uefiNtfsBytes.size.toLong() != UEFI_NTFS_EXPECTED_SIZE_BYTES ->
                    "CORRUPT_ASSET" to "UEFI:NTFS bootloader image size mismatch (expected $UEFI_NTFS_EXPECTED_SIZE_BYTES bytes)."
                Sha256().apply { update(uefiNtfsBytes) }.digest().toLowerHex() != UEFI_NTFS_EXPECTED_SHA256 ->
                    "CORRUPT_ASSET" to "UEFI:NTFS bootloader image SHA-256 verification failed."
                else -> null
            }
        if (error != null) {
            throw FlashStepException(code = error.first, message = error.second)
        }
    }

    @Suppress("LongParameterList")
    private fun partitionAndFormatWindowsDrive(
        request: WindowsFlashRequest,
        target: BlockFlashTarget,
        zeroBuffer: ByteArray,
        uefiNtfsBytes: ByteArray?,
        startMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
        externalPartitionFormatter: (Int, FormatRequest, (Long, Long) -> Unit) -> Unit,
    ): Boolean {
        val rawLabel = request.volumeLabel.ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        val cleanLabel = sanitizeVolumeLabel(rawLabel, request.filesystem).ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        val isDualNtfs = request.filesystem == FilesystemType.NTFS
        val spans = computeWindowsSpans(target, request.partitionScheme, isDualNtfs)

        prepareWindowsDiskSurface(request.quickFormat, target, spans.dataSpan, zeroBuffer, startMs, clockMs, shouldCancel, onEvent)

        onEvent(WorkerEvent.Progress(FlashPhase.PARTITIONING, 0L, 0L, 0L))
        ensureNotCancelled(shouldCancel)

        val specs = buildWindowsPartitionSpecs(spans, cleanLabel, isDualNtfs)
        writeWindowsPartitionTable(target, specs, request.partitionScheme)
        if (isDualNtfs) {
            val espBytes = checkNotNull(uefiNtfsBytes)
            target.writeAt(checkNotNull(spans.dualSpans).uefiNtfsSpan.startByteOffset, espBytes, espBytes.size)
        }

        val fmtReq =
            FormatRequest(
                deviceId = request.deviceId,
                partitionScheme = request.partitionScheme,
                filesystem = request.filesystem,
                volumeLabel = cleanLabel,
                clusterSizeBytes = request.clusterSizeBytes,
                quickFormat = request.quickFormat,
            )
        return runWindowsFilesystemFormat(fmtReq, spans.dataSpan, target, shouldCancel, onEvent, externalPartitionFormatter)
    }

    private fun computeWindowsSpans(
        target: BlockFlashTarget,
        scheme: PartitionScheme,
        isDualNtfs: Boolean,
    ): ComputedWindowsSpans =
        if (isDualNtfs) {
            val dual =
                PartitionTableBuilder.computeDualWindowsPartitionSpans(
                    diskSizeBytes = target.capacityBytes,
                    sectorSizeBytes = target.sectorSizeBytes,
                    scheme = scheme,
                )
            ComputedWindowsSpans(dataSpan = dual.dataSpan, dualSpans = dual)
        } else {
            ComputedWindowsSpans(
                dataSpan = PartitionTableBuilder.computeSinglePartitionSpan(target.capacityBytes, target.sectorSizeBytes, scheme),
                dualSpans = null,
            )
        }

    @Suppress("LongParameterList")
    private fun prepareWindowsDiskSurface(
        quickFormat: Boolean,
        target: BlockFlashTarget,
        dataSpan: PartitionSpan,
        zeroBuffer: ByteArray,
        startMs: Long,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ) {
        if (!quickFormat) {
            FormatEngine.runFullZeroWipe(
                target = target,
                zeroBuffer = zeroBuffer,
                startMs = startMs,
                progressIntervalMs = WINDOWS_PROGRESS_INTERVAL_MS,
                clockMs = clockMs,
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )
        } else {
            FormatEngine.clearStaleMetadataRegions(target, dataSpan, zeroBuffer, shouldCancel)
        }
    }

    private fun buildWindowsPartitionSpecs(
        spans: ComputedWindowsSpans,
        cleanLabel: String,
        isDualNtfs: Boolean,
    ): List<PartitionSpec> =
        if (isDualNtfs) {
            val dual = checkNotNull(spans.dualSpans)
            listOf(
                PartitionSpec(dual.dataSpan, FilesystemType.NTFS, cleanLabel, PartitionRole.DATA),
                PartitionSpec(dual.uefiNtfsSpan, FilesystemType.FAT32, "UEFI_NTFS", PartitionRole.EFI_SYSTEM),
            )
        } else {
            listOf(PartitionSpec(spans.dataSpan, FilesystemType.FAT32, cleanLabel, PartitionRole.DATA))
        }

    private fun writeWindowsPartitionTable(
        target: BlockFlashTarget,
        specs: List<PartitionSpec>,
        scheme: PartitionScheme,
    ) {
        when (scheme) {
            PartitionScheme.MBR -> {
                val mbr = PartitionTableBuilder.buildMultiPartitionMbrSector(specs)
                target.writeAt(0L, mbr, mbr.size)
            }
            PartitionScheme.GPT -> {
                val gpt = PartitionTableBuilder.buildMultiPartitionGptLayout(specs)
                val secSize = target.sectorSizeBytes.toLong()
                target.writeAt(0L, gpt.protectiveMbrSector, gpt.protectiveMbrSector.size)
                target.writeAt(secSize, gpt.primaryHeaderSector, gpt.primaryHeaderSector.size)
                target.writeAt(2L * secSize, gpt.primaryEntriesBytes, gpt.primaryEntriesBytes.size)
                target.writeAt(gpt.backupEntriesOffsetBytes, gpt.backupEntriesBytes, gpt.backupEntriesBytes.size)
                target.writeAt(gpt.backupHeaderOffsetBytes, gpt.backupHeaderSector, gpt.backupHeaderSector.size)
            }
        }
        target.syncAndFlush()
    }

    private fun runWindowsFilesystemFormat(
        formatRequest: FormatRequest,
        dataSpan: PartitionSpan,
        target: BlockFlashTarget,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
        externalPartitionFormatter: (Int, FormatRequest, (Long, Long) -> Unit) -> Unit,
    ): Boolean {
        if (formatRequest.filesystem == FilesystemType.FAT32) {
            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 0L, 100L, 0L))
            Fat32Formatter.format(
                target = target,
                span = dataSpan,
                volumeLabel = formatRequest.volumeLabel,
                requestedClusterBytes = formatRequest.clusterSizeBytes,
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
        externalPartitionFormatter(1, formatRequest) { processed, total ->
            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, processed, total, 0L))
        }
        return true
    }

    @Suppress("LongParameterList")
    private fun extractWindowsPayloadToMountedVolume(
        request: WindowsFlashRequest,
        archive: OpenedIsoUdfArchive,
        isoReader: SeekableByteReader,
        meta: WindowsIsoMetadata,
        clockMs: () -> Long,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
        mountPartition: (Int, FilesystemType) -> MountedPartitionHandle,
    ) {
        val generateXml = UnattendXmlBuilder.shouldGenerate(request.userExperience)
        val xmlBytes = if (generateXml) UnattendXmlBuilder.buildXml(request.userExperience).encodeToByteArray() else null
        val totalBytes = (meta.totalPayloadBytes + (xmlBytes?.size?.toLong() ?: 0L)).coerceAtLeast(1L)

        onEvent(WorkerEvent.Started(totalBytes = totalBytes))
        onEvent(WorkerEvent.Progress(FlashPhase.WRITING, 0L, totalBytes, 0L))

        val mounted = mountPartition(1, request.filesystem)
        val writeStartMs = clockMs()
        var lastReportMs = writeStartMs
        var writtenBytes = 0L
        val copyBuffer = ByteArray(WINDOWS_COPY_CHUNK_BYTES)

        val reportBytes: (Long) -> Unit = { delta ->
            writtenBytes += delta
            val now = clockMs()
            if (now - lastReportMs >= WINDOWS_PROGRESS_INTERVAL_MS) {
                val elapsed = (now - writeStartMs).coerceAtLeast(1L)
                val speed = (writtenBytes * MILLIS_PER_SECOND) / elapsed
                val cappedProcessed = if (totalBytes > 1L) writtenBytes.coerceAtMost(totalBytes - 1L) else writtenBytes
                onEvent(WorkerEvent.Progress(FlashPhase.WRITING, cappedProcessed, totalBytes, speed))
                lastReportMs = now
            }
        }

        try {
            createMountedDirectories(archive.entries, mounted, shouldCancel)
            copyArchiveFiles(
                request = request,
                entries = archive.entries,
                isoReader = isoReader,
                mounted = mounted,
                copyBuffer = copyBuffer,
                skipExistingAutounattend = generateXml,
                shouldCancel = shouldCancel,
                reportBytes = reportBytes,
            )
            if (xmlBytes != null) {
                val out = mounted.openFileForWrite("autounattend.xml")
                try {
                    out.write(xmlBytes, xmlBytes.size)
                    reportBytes(xmlBytes.size.toLong())
                } finally {
                    out.close()
                }
            }
            mounted.sync()
            mounted.unmountAndClose()
            val finalElapsed = (clockMs() - writeStartMs).coerceAtLeast(1L)
            val finalSpeed = (totalBytes * MILLIS_PER_SECOND) / finalElapsed
            onEvent(WorkerEvent.Progress(FlashPhase.WRITING, totalBytes, totalBytes, finalSpeed))
        } finally {
            runCatching { mounted.unmountAndClose() }
        }
    }

    private fun createMountedDirectories(
        entries: List<ArchiveEntry>,
        mounted: MountedPartitionHandle,
        shouldCancel: () -> Boolean,
    ) {
        entries
            .filter { it.isDirectory }
            .sortedBy { it.relativePath.length }
            .forEach { dirEntry ->
                ensureNotCancelled(shouldCancel)
                val safePath = sanitizeSafeRelativePath(dirEntry.relativePath)
                mounted.ensureDirectory(safePath)
            }
    }

    @Suppress("LongParameterList")
    private fun copyArchiveFiles(
        request: WindowsFlashRequest,
        entries: List<ArchiveEntry>,
        isoReader: SeekableByteReader,
        mounted: MountedPartitionHandle,
        copyBuffer: ByteArray,
        skipExistingAutounattend: Boolean,
        shouldCancel: () -> Boolean,
        reportBytes: (Long) -> Unit,
    ) {
        val splitWimOnFat32 = request.filesystem == FilesystemType.FAT32
        val noPromptBootOverride = resolveNoPromptBootx64Override(entries, isoReader)
        entries
            .filterNot { it.isDirectory }
            .forEach { fileEntry ->
                ensureNotCancelled(shouldCancel)
                val safeRelPath = sanitizeSafeRelativePath(fileEntry.relativePath)
                val upperPath = safeRelPath.uppercase()
                val isAutounattend = upperPath == "AUTOUNATTEND.XML"
                val isOversizedWim =
                    splitWimOnFat32 &&
                        (upperPath == "SOURCES/INSTALL.WIM" || upperPath.endsWith("/SOURCES/INSTALL.WIM")) &&
                        fileEntry.sizeBytes > FAT32_MAX_FILE_BYTES

                when {
                    isAutounattend && skipExistingAutounattend -> Unit
                    isOversizedWim -> {
                        val parentDir = safeRelPath.substringBeforeLast('/', "")
                        if (parentDir.isNotEmpty()) mounted.ensureDirectory(parentDir)
                        WimSplitter.split(
                            reader = IsoUdfReader.openEntryReader(isoReader, fileEntry),
                            shouldCancel = shouldCancel,
                            onBytesWritten = reportBytes,
                        ) { _, splitFileName ->
                            val targetPath = if (parentDir.isEmpty()) splitFileName else "$parentDir/$splitFileName"
                            val outFile = mounted.openFileForWrite(targetPath)
                            MountedWimPartSink(outFile)
                        }
                    }
                    upperPath == "EFI/BOOT/BOOTX64.EFI" && noPromptBootOverride != null ->
                        copyRegularArchiveFile(isoReader, noPromptBootOverride, safeRelPath, mounted, copyBuffer, shouldCancel, reportBytes)
                    else -> copyRegularArchiveFile(isoReader, fileEntry, safeRelPath, mounted, copyBuffer, shouldCancel, reportBytes)
                }
            }
    }

    private fun resolveNoPromptBootx64Override(
        entries: List<ArchiveEntry>,
        isoReader: SeekableByteReader,
    ): ArchiveEntry? {
        val bootx64 = entries.firstOrNull { !it.isDirectory && it.relativePath.equals("efi/boot/bootx64.efi", ignoreCase = true) }
        val cdboot =
            entries.firstOrNull { !it.isDirectory && it.relativePath.equals("efi/microsoft/boot/cdboot.efi", ignoreCase = true) }
        val cdbootNoPrompt =
            entries.firstOrNull { !it.isDirectory && it.relativePath.equals("efi/microsoft/boot/cdboot_noprompt.efi", ignoreCase = true) }
        if (bootx64 == null || cdboot == null || cdbootNoPrompt == null) return null
        return if (areArchiveEntriesByteEqual(isoReader, bootx64, cdboot)) cdbootNoPrompt else null
    }

    private fun areArchiveEntriesByteEqual(
        isoReader: SeekableByteReader,
        first: ArchiveEntry,
        second: ArchiveEntry,
    ): Boolean {
        if (first.sizeBytes != second.sizeBytes || first.sizeBytes <= 0L || first.sizeBytes > 8_388_608L) return false
        val len = first.sizeBytes.toInt()
        val bytesA = IsoUdfReader.openEntryReader(isoReader, first).readAt(0L, len)
        val bytesB = IsoUdfReader.openEntryReader(isoReader, second).readAt(0L, len)
        return bytesA.contentEquals(bytesB)
    }

    private fun copyRegularArchiveFile(
        isoReader: SeekableByteReader,
        entry: ArchiveEntry,
        safeRelPath: String,
        mounted: MountedPartitionHandle,
        copyBuffer: ByteArray,
        shouldCancel: () -> Boolean,
        reportBytes: (Long) -> Unit,
    ) {
        val parentDir = safeRelPath.substringBeforeLast('/', "")
        if (parentDir.isNotEmpty()) mounted.ensureDirectory(parentDir)
        val outFile = mounted.openFileForWrite(safeRelPath)
        try {
            IsoUdfReader.streamFile(isoReader, entry, copyBuffer, shouldCancel) { chunk, len ->
                outFile.write(chunk, len)
                reportBytes(len.toLong())
            }
        } finally {
            outFile.close()
        }
    }

    private fun sanitizeSafeRelativePath(raw: String): String {
        val parts = raw.split('/').filter { it.isNotEmpty() }
        val invalid = parts.isEmpty() || parts.any { it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') }
        if (invalid) {
            throw FlashStepException("UNSAFE_ARCHIVE_PATH", "Rejected unsafe path inside ISO: '$raw'")
        }
        return parts.joinToString("/")
    }

    private fun ensureNotCancelled(shouldCancel: () -> Boolean) {
        if (shouldCancel()) {
            throw FlashStepException(code = "CANCELLED", message = "Windows ISO installation cancelled by user.")
        }
    }
}

private class MountedWimPartSink(
    private val file: WritableMountedFile,
) : WimPartSink {
    override fun write(
        bytes: ByteArray,
        length: Int,
    ) = file.write(bytes, length)

    override fun close() = file.close()
}

private fun ByteArray.toLowerHex(): String =
    buildString(size * 2) {
        for (b in this@toLowerHex) {
            val v = b.toInt() and 0xFF
            append(HEX_CHARS[v ushr 4])
            append(HEX_CHARS[v and 0x0F])
        }
    }

private const val HEX_CHARS = "0123456789abcdef"
