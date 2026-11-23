package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.github.drlacheheb.kufus.disk.DEFAULT_FORMAT_VOLUME_LABEL
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.HostOsKind
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.defaultWindowsFilesystemForHost
import io.github.drlacheheb.kufus.disk.filesystemDisplayName
import io.github.drlacheheb.kufus.disk.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.supportedClusterSizes
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.domain.repository.HostUserEnvironment
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

@Suppress("TooManyFunctions")
class DefaultFlasherComponent(
    componentContext: ComponentContext,
    private val diskRepository: DiskRepository,
    private val imageRepository: ImageRepository =
        ImageRepository { Result.failure(IllegalStateException("Image inspection unavailable.")) },
    private val imageFilePicker: ImageFilePicker = ImageFilePicker { null },
    private val flashRepository: FlashRepository = FlashRepository { emptyFlow() },
    private val formatRepository: FormatRepository = FormatRepository { emptyFlow() },
    private val hostUserEnvironment: HostUserEnvironment = HostUserEnvironment.Empty,
    platformName: String = "",
    hostOs: HostOsKind = HostOsKind.LINUX,
    mainContext: CoroutineContext = Dispatchers.Main,
) : FlasherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private val _state = MutableValue(FlasherUiState(platformName = platformName, hostOs = hostOs))
    override val state: Value<FlasherUiState> = _state
    private var refreshJob: Job? = null
    private var watchJob: Job? = null
    private var browseJob: Job? = null
    private var inspectJob: Job? = null
    private var flashJob: Job? = null

    init {
        lifecycle.doOnDestroy { scope.cancel() }
        onRefreshDrives()
        observeHotplugDrives()
    }

    override fun onRefreshDrives() {
        if (_state.value.isFlashing) return
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                _state.update { it.copy(isScanning = true, errorMessage = null) }
                diskRepository
                    .listUsbDrives()
                    .onSuccess(::applyDriveList)
                    .onFailure { error ->
                        _state.update { current ->
                            current.copy(
                                isScanning = false,
                                errorMessage = error.message ?: "Failed to scan USB drives.",
                            )
                        }
                    }
            }
    }

    override fun onSelectDrive(driveId: String) {
        if (_state.value.isFlashing) return
        _state.update { it.copy(selectedDriveId = driveId) }
    }

    override fun onSelectBootSelectionMode(mode: BootSelectionMode) {
        if (_state.value.isFlashing) return
        _state.update { current ->
            val resetState =
                when (current.flashState) {
                    is FlashOperationState.Confirming,
                    is FlashOperationState.ConfirmingFormat,
                    is FlashOperationState.Completed,
                    is FlashOperationState.Failed,
                    -> FlashOperationState.Idle
                    else -> current.flashState
                }
            current.copy(bootSelectionMode = mode, flashState = resetState)
        }
    }

    override fun onSelectImagePath(path: String) {
        if (_state.value.isFlashing) return
        if (path.isBlank()) {
            _state.update {
                it.copy(
                    bootSelectionMode = BootSelectionMode.DISK_OR_ISO,
                    isInspectingImage = false,
                    imageErrorMessage = "Image path cannot be blank.",
                )
            }
            return
        }
        inspectJob?.cancel()
        _state.update {
            it.copy(
                bootSelectionMode = BootSelectionMode.DISK_OR_ISO,
                selectedImagePath = path,
                inspectedImage = null,
                isInspectingImage = true,
                imageErrorMessage = null,
                flashState = FlashOperationState.Idle,
            )
        }
        inspectJob =
            scope.launch {
                imageRepository
                    .inspectImage(path)
                    .onSuccess { info ->
                        _state.update { current ->
                            if (current.selectedImagePath != path) {
                                current
                            } else {
                                applyInspectedImageState(current, info)
                            }
                        }
                    }.onFailure { error ->
                        _state.update { current ->
                            if (current.selectedImagePath != path) {
                                current
                            } else {
                                current.copy(
                                    inspectedImage = null,
                                    isInspectingImage = false,
                                    imageErrorMessage = error.message ?: "Failed to inspect image.",
                                )
                            }
                        }
                    }
            }
    }

    override fun onBrowseImage() {
        if (_state.value.isFlashing) return
        browseJob?.cancel()
        browseJob =
            scope.launch {
                val pickedPath =
                    try {
                        imageFilePicker.pickImagePath()
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        _state.update { it.copy(imageErrorMessage = e.message ?: "Failed to open file picker.") }
                        null
                    }
                if (!pickedPath.isNullOrBlank()) {
                    onSelectImagePath(pickedPath)
                }
            }
    }

    override fun onClearImage() {
        if (_state.value.isFlashing) return
        inspectJob?.cancel()
        _state.update {
            it.copy(
                selectedImagePath = null,
                inspectedImage = null,
                isInspectingImage = false,
                imageErrorMessage = null,
                flashState = FlashOperationState.Idle,
            )
        }
    }

    override fun onSelectPartitionScheme(scheme: PartitionScheme) {
        if (_state.value.isFlashing) return
        _state.update { it.copy(formatOptions = it.formatOptions.copy(partitionScheme = scheme)) }
    }

    override fun onSelectFilesystem(filesystem: FilesystemType) {
        if (_state.value.isFlashing) return
        _state.update { current ->
            if (filesystem !in current.availableFilesystems) return@update current
            val validClusters = supportedClusterSizes(filesystem)
            val nextCluster = current.formatOptions.clusterSizeBytes?.takeIf { it in validClusters }
            current.copy(
                formatOptions =
                    current.formatOptions.copy(
                        filesystem = filesystem,
                        clusterSizeBytes = nextCluster,
                    ),
            )
        }
    }

    override fun onUpdateVolumeLabel(label: String) {
        if (_state.value.isFlashing) return
        _state.update { current ->
            current.copy(formatOptions = current.formatOptions.copy(volumeLabel = label))
        }
    }

    override fun onSelectClusterSize(clusterSizeBytes: Int?) {
        if (_state.value.isFlashing) return
        _state.update { current ->
            val validClusters = supportedClusterSizes(current.formatOptions.filesystem)
            if (clusterSizeBytes !in validClusters) return@update current
            current.copy(formatOptions = current.formatOptions.copy(clusterSizeBytes = clusterSizeBytes))
        }
    }

    override fun onToggleQuickFormat(enabled: Boolean) {
        if (_state.value.isFlashing) return
        _state.update { current ->
            current.copy(formatOptions = current.formatOptions.copy(quickFormat = enabled))
        }
    }

    override fun onRequestFlash() {
        val current = _state.value
        if (!current.canFlash) return
        val nextConfirmState: FlashOperationState =
            if (current.isWindowsIsoSelected) {
                FlashOperationState.ConfirmingWindows(
                    options = WindowsUserExperienceOptions(),
                    ejectAfterWrite = true,
                    defaultUsername = hostUserEnvironment.defaultUsername,
                    defaultLocaleTag = hostUserEnvironment.defaultLocaleTag,
                )
            } else {
                FlashOperationState.Confirming(verifyAfterWrite = true, ejectAfterWrite = true)
            }
        _state.update { it.copy(flashState = nextConfirmState) }
    }

    override fun onUpdateWindowsUserExperience(options: WindowsUserExperienceOptions) {
        _state.update { current ->
            val confirming = current.flashState as? FlashOperationState.ConfirmingWindows ?: return@update current
            current.copy(flashState = confirming.copy(options = options))
        }
    }

    override fun onConfirmWindowsUserExperience() {
        _state.update { current ->
            val confirming = current.flashState as? FlashOperationState.ConfirmingWindows ?: return@update current
            current.copy(
                flashState =
                    FlashOperationState.Confirming(
                        verifyAfterWrite = false,
                        ejectAfterWrite = confirming.ejectAfterWrite,
                        windowsOptions = confirming.options,
                    ),
            )
        }
    }

    override fun onToggleVerifyAfterWrite(enabled: Boolean) {
        _state.update { current ->
            val confirming = current.flashState as? FlashOperationState.Confirming ?: return@update current
            current.copy(flashState = confirming.copy(verifyAfterWrite = enabled))
        }
    }

    override fun onToggleEjectAfterWrite(enabled: Boolean) {
        _state.update { current ->
            when (val flashState = current.flashState) {
                is FlashOperationState.Confirming -> current.copy(flashState = flashState.copy(ejectAfterWrite = enabled))
                is FlashOperationState.ConfirmingWindows -> current.copy(flashState = flashState.copy(ejectAfterWrite = enabled))
                else -> current
            }
        }
    }

    override fun onRequestFormat() {
        if (!_state.value.canFormat) return
        _state.update {
            it.copy(flashState = FlashOperationState.ConfirmingFormat(ejectAfterFormat = false))
        }
    }

    override fun onToggleEjectAfterFormat(enabled: Boolean) {
        _state.update { current ->
            val confirming = current.flashState as? FlashOperationState.ConfirmingFormat ?: return@update current
            current.copy(flashState = confirming.copy(ejectAfterFormat = enabled))
        }
    }

    override fun onDismissConfirmation() {
        _state.update { current ->
            when (current.flashState) {
                is FlashOperationState.Confirming,
                is FlashOperationState.ConfirmingWindows,
                is FlashOperationState.ConfirmingFormat,
                -> current.copy(flashState = FlashOperationState.Idle)
                else -> current
            }
        }
    }

    override fun onConfirmFlash() {
        val current = _state.value
        val image = current.inspectedImage ?: return
        val drive = current.selectedDrive ?: return
        if (!current.canFlash) return
        val confirming = current.flashState as? FlashOperationState.Confirming
        val windowsOptions = confirming?.windowsOptions
        if (image.format == ImageFormat.WINDOWS_ISO || windowsOptions != null) {
            launchWindowsInstallFlash(current, image, drive, confirming, windowsOptions)
        } else {
            launchRawImageFlash(current, image, drive, confirming)
        }
    }

    private fun launchRawImageFlash(
        current: FlasherUiState,
        image: ImageInfo,
        drive: UsbDrive,
        confirming: FlashOperationState.Confirming?,
    ) {
        val verifyAfterWrite = confirming?.verifyAfterWrite ?: true
        val ejectAfterWrite = confirming?.ejectAfterWrite ?: true
        flashJob?.cancel()
        _state.update {
            it.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = FlashPhase.UNMOUNTING,
                        totalBytes = current.requiredSizeBytes ?: image.sizeBytes,
                        operationKind = ActiveOperationKind.FLASH,
                    ),
            )
        }
        val request = FlashRequest(image.path, drive.id, verifyAfterWrite, ejectAfterWrite)
        flashJob =
            scope.launch {
                runWorkerCollection(
                    stream = flashRepository.flashImage(request),
                    operationKind = ActiveOperationKind.FLASH,
                    summaryDetail = null,
                    unexpectedExitMessage = "Flash worker exited before reporting completion.",
                    unexpectedFailureMessage = "Unexpected flash failure.",
                )
            }
    }

    private fun launchWindowsInstallFlash(
        current: FlasherUiState,
        image: ImageInfo,
        drive: UsbDrive,
        confirming: FlashOperationState.Confirming?,
        windowsOptions: WindowsUserExperienceOptions?,
    ) {
        val ejectAfterWrite = confirming?.ejectAfterWrite ?: true
        val options = windowsOptions ?: WindowsUserExperienceOptions()
        val rawLabel = current.formatOptions.volumeLabel.ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        val cleanLabel = sanitizeVolumeLabel(rawLabel, current.formatOptions.filesystem).ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        val payloadBytes = image.windowsMetadata?.totalPayloadBytes ?: image.sizeBytes
        flashJob?.cancel()
        _state.update {
            it.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = FlashPhase.UNMOUNTING,
                        totalBytes = payloadBytes,
                        operationKind = ActiveOperationKind.FLASH,
                    ),
            )
        }
        val request =
            WindowsFlashRequest(
                imagePath = image.path,
                deviceId = drive.id,
                partitionScheme = current.formatOptions.partitionScheme,
                filesystem = current.formatOptions.filesystem,
                volumeLabel = cleanLabel,
                clusterSizeBytes = current.formatOptions.clusterSizeBytes,
                quickFormat = current.formatOptions.quickFormat,
                ejectAfterWrite = ejectAfterWrite,
                userExperience = options,
            )
        val summary = buildFormatSummaryDetail(current.formatOptions.partitionScheme, current.formatOptions.filesystem, cleanLabel)
        flashJob =
            scope.launch {
                runWorkerCollection(
                    stream = flashRepository.flashWindowsIso(request),
                    operationKind = ActiveOperationKind.FLASH,
                    summaryDetail = summary,
                    unexpectedExitMessage = "Windows installer worker exited before reporting completion.",
                    unexpectedFailureMessage = "Unexpected Windows installer failure.",
                )
            }
    }

    private fun applyInspectedImageState(
        current: FlasherUiState,
        info: ImageInfo,
    ): FlasherUiState {
        if (info.format != ImageFormat.WINDOWS_ISO) {
            return current.copy(inspectedImage = info, isInspectingImage = false, imageErrorMessage = null)
        }
        val meta = info.windowsMetadata
        val nextFs = defaultWindowsFilesystemForHost(current.hostOs, meta)
        val rawLabel = meta?.volumeLabel?.takeIf { it.isNotBlank() } ?: DEFAULT_FORMAT_VOLUME_LABEL
        val cleanLabel = sanitizeVolumeLabel(rawLabel, nextFs).ifBlank { DEFAULT_FORMAT_VOLUME_LABEL }
        val validClusters = supportedClusterSizes(nextFs)
        val nextCluster = current.formatOptions.clusterSizeBytes?.takeIf { it in validClusters }
        return current.copy(
            inspectedImage = info,
            isInspectingImage = false,
            imageErrorMessage = null,
            formatOptions =
                current.formatOptions.copy(
                    filesystem = nextFs,
                    volumeLabel = cleanLabel,
                    clusterSizeBytes = nextCluster,
                ),
        )
    }

    override fun onConfirmFormat() {
        val current = _state.value
        val drive = current.selectedDrive ?: return
        if (!current.canFormat) return
        val confirming = current.flashState as? FlashOperationState.ConfirmingFormat
        val ejectAfterFormat = confirming?.ejectAfterFormat ?: false
        val opts = current.formatOptions
        val sanitizedLabel = sanitizeVolumeLabel(opts.volumeLabel, opts.filesystem)

        flashJob?.cancel()
        _state.update {
            it.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = FlashPhase.UNMOUNTING,
                        totalBytes = if (opts.quickFormat) 0L else drive.sizeBytes,
                        operationKind = ActiveOperationKind.FORMAT,
                    ),
            )
        }

        val request =
            FormatRequest(
                deviceId = drive.id,
                partitionScheme = opts.partitionScheme,
                filesystem = opts.filesystem,
                volumeLabel = sanitizedLabel,
                clusterSizeBytes = opts.clusterSizeBytes,
                quickFormat = opts.quickFormat,
                ejectAfterFormat = ejectAfterFormat,
            )
        val summary = buildFormatSummaryDetail(opts.partitionScheme, opts.filesystem, sanitizedLabel)
        flashJob =
            scope.launch {
                runWorkerCollection(
                    stream = formatRepository.formatDrive(request),
                    operationKind = ActiveOperationKind.FORMAT,
                    summaryDetail = summary,
                    unexpectedExitMessage = "Format worker exited before reporting completion.",
                    unexpectedFailureMessage = "Unexpected format failure.",
                )
            }
    }

    override fun onCancelFlash() {
        flashJob?.cancel()
        flashJob = null
        _state.update { current ->
            if (current.isFlashing) {
                current.copy(flashState = FlashOperationState.Idle)
            } else {
                current
            }
        }
    }

    override fun onDismissFlashResult() {
        _state.update { current ->
            if (current.flashState is FlashOperationState.Completed || current.flashState is FlashOperationState.Failed) {
                current.copy(flashState = FlashOperationState.Idle)
            } else {
                current
            }
        }
    }

    private suspend fun runWorkerCollection(
        stream: Flow<WorkerEvent>,
        operationKind: ActiveOperationKind,
        summaryDetail: String?,
        unexpectedExitMessage: String,
        unexpectedFailureMessage: String,
    ) {
        try {
            var completed = false
            stream.collect { event ->
                if (event is WorkerEvent.Completed || event is WorkerEvent.Error) {
                    completed = true
                }
                applyWorkerEvent(event, operationKind, summaryDetail)
            }
            if (!completed && _state.value.isFlashing) {
                _state.update {
                    it.copy(flashState = FlashOperationState.Failed(unexpectedExitMessage))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            _state.update {
                it.copy(flashState = FlashOperationState.Failed(e.message ?: unexpectedFailureMessage))
            }
        }
    }

    private fun applyWorkerEvent(
        event: WorkerEvent,
        operationKind: ActiveOperationKind,
        summaryDetail: String?,
    ) {
        when (event) {
            is WorkerEvent.Started -> applyStartedEvent(event, operationKind)
            is WorkerEvent.Progress -> applyProgressEvent(event, operationKind)
            is WorkerEvent.Completed -> applyCompletedEvent(event, operationKind, summaryDetail)
            is WorkerEvent.Error ->
                _state.update { current ->
                    current.copy(
                        flashState = FlashOperationState.Failed(event.message),
                        operationLog = appendLogLine(current.operationLog, "Error [${event.code}]: ${event.message}"),
                    )
                }
            is WorkerEvent.DrivesListed,
            is WorkerEvent.ImageInspected,
            -> Unit
        }
    }

    private fun applyStartedEvent(
        event: WorkerEvent.Started,
        operationKind: ActiveOperationKind,
    ) {
        val initialPhase = if (operationKind == ActiveOperationKind.FORMAT) FlashPhase.WIPING else FlashPhase.WRITING
        _state.update { current ->
            current.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = initialPhase,
                        bytesProcessed = 0L,
                        totalBytes = event.totalBytes,
                        operationKind = operationKind,
                    ),
                operationLog =
                    appendLogLine(current.operationLog, "Started ${operationKind.name.lowercase()} (${event.totalBytes} bytes)"),
            )
        }
    }

    private fun applyProgressEvent(
        event: WorkerEvent.Progress,
        operationKind: ActiveOperationKind,
    ) {
        _state.update { current ->
            val previousPhase = (current.flashState as? FlashOperationState.Flashing)?.phase
            val updatedLog =
                if (previousPhase != event.phase) {
                    appendLogLine(current.operationLog, "Phase: ${event.phase.name.lowercase()}")
                } else {
                    current.operationLog
                }
            current.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = event.phase,
                        bytesProcessed = event.bytesProcessed,
                        totalBytes = event.totalBytes,
                        speedBytesPerSec = event.speedBytesPerSec,
                        operationKind = operationKind,
                    ),
                operationLog = updatedLog,
            )
        }
    }

    private fun applyCompletedEvent(
        event: WorkerEvent.Completed,
        operationKind: ActiveOperationKind,
        summaryDetail: String?,
    ) {
        _state.update { current ->
            current.copy(
                flashState =
                    FlashOperationState.Completed(
                        verified = event.verified,
                        elapsedMs = event.elapsedMs,
                        ejected = event.ejected,
                        operationKind = operationKind,
                        summaryDetail = summaryDetail,
                    ),
                lastElapsedMs = event.elapsedMs,
                operationLog =
                    appendLogLine(
                        current.operationLog,
                        "Completed ${operationKind.name.lowercase()} in ${event.elapsedMs} ms (verified=${event.verified})",
                    ),
            )
        }
        onRefreshDrives()
    }

    private fun appendLogLine(
        existing: List<String>,
        entry: String,
    ): List<String> = (existing + entry).takeLast(100)

    private fun buildFormatSummaryDetail(
        scheme: PartitionScheme,
        filesystem: FilesystemType,
        sanitizedLabel: String,
    ): String {
        val labelSuffix = if (sanitizedLabel.isNotEmpty()) " • $sanitizedLabel" else ""
        return "${scheme.name} • ${filesystemDisplayName(filesystem)}$labelSuffix"
    }

    private fun observeHotplugDrives() {
        watchJob?.cancel()
        watchJob =
            scope.launch {
                diskRepository.observeUsbDrives().collect { result ->
                    result.onSuccess(::applyDriveList)
                }
            }
    }

    private fun applyDriveList(drives: List<UsbDrive>) {
        _state.update { current ->
            if (current.isFlashing) {
                current.copy(drives = drives, isScanning = false)
            } else {
                current.copy(
                    drives = drives,
                    selectedDriveId =
                        current.selectedDriveId?.takeIf { id -> drives.any { it.id == id } }
                            ?: drives.firstOrNull()?.id,
                    isScanning = false,
                    errorMessage = null,
                )
            }
        }
    }
}
