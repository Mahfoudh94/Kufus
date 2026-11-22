package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
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
    platformName: String = "",
    mainContext: CoroutineContext = Dispatchers.Main,
) : FlasherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private val _state = MutableValue(FlasherUiState(platformName = platformName))
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

    override fun onSelectImagePath(path: String) {
        if (_state.value.isFlashing) return
        if (path.isBlank()) {
            _state.update {
                it.copy(
                    isInspectingImage = false,
                    imageErrorMessage = "Image path cannot be blank.",
                )
            }
            return
        }
        inspectJob?.cancel()
        _state.update {
            it.copy(
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
                                current.copy(
                                    inspectedImage = info,
                                    isInspectingImage = false,
                                    imageErrorMessage = null,
                                )
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

    override fun onRequestFlash() {
        if (!_state.value.canFlash) return
        _state.update {
            it.copy(flashState = FlashOperationState.Confirming(verifyAfterWrite = true, ejectAfterWrite = true))
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
            val confirming = current.flashState as? FlashOperationState.Confirming ?: return@update current
            current.copy(flashState = confirming.copy(ejectAfterWrite = enabled))
        }
    }

    override fun onDismissConfirmation() {
        _state.update { current ->
            if (current.flashState is FlashOperationState.Confirming) {
                current.copy(flashState = FlashOperationState.Idle)
            } else {
                current
            }
        }
    }

    override fun onConfirmFlash() {
        val current = _state.value
        val image = current.inspectedImage ?: return
        val drive = current.selectedDrive ?: return
        if (!current.canFlash) return
        val confirming = current.flashState as? FlashOperationState.Confirming
        val verifyAfterWrite = confirming?.verifyAfterWrite ?: true
        val ejectAfterWrite = confirming?.ejectAfterWrite ?: true

        flashJob?.cancel()
        _state.update {
            it.copy(
                flashState =
                    FlashOperationState.Flashing(
                        phase = FlashPhase.UNMOUNTING,
                        totalBytes = current.requiredSizeBytes ?: image.sizeBytes,
                    ),
            )
        }

        val request =
            FlashRequest(
                imagePath = image.path,
                deviceId = drive.id,
                verifyAfterWrite = verifyAfterWrite,
                ejectAfterWrite = ejectAfterWrite,
            )
        flashJob =
            scope.launch {
                runFlashCollection(request)
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

    private suspend fun runFlashCollection(request: FlashRequest) {
        try {
            var completed = false
            flashRepository.flashImage(request).collect { event ->
                if (event is WorkerEvent.Completed || event is WorkerEvent.Error) {
                    completed = true
                }
                applyFlashEvent(event)
            }
            if (!completed && _state.value.isFlashing) {
                _state.update {
                    it.copy(flashState = FlashOperationState.Failed("Flash worker exited before reporting completion."))
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            _state.update {
                it.copy(flashState = FlashOperationState.Failed(e.message ?: "Unexpected flash failure."))
            }
        }
    }

    private fun applyFlashEvent(event: WorkerEvent) {
        when (event) {
            is WorkerEvent.Started ->
                _state.update { current ->
                    current.copy(
                        flashState =
                            FlashOperationState.Flashing(
                                phase = FlashPhase.WRITING,
                                bytesProcessed = 0L,
                                totalBytes = event.totalBytes,
                            ),
                    )
                }
            is WorkerEvent.Progress ->
                _state.update { current ->
                    current.copy(
                        flashState =
                            FlashOperationState.Flashing(
                                phase = event.phase,
                                bytesProcessed = event.bytesProcessed,
                                totalBytes = event.totalBytes,
                                speedBytesPerSec = event.speedBytesPerSec,
                            ),
                    )
                }
            is WorkerEvent.Completed -> {
                _state.update {
                    it.copy(flashState = FlashOperationState.Completed(event.verified, event.elapsedMs, event.ejected))
                }
                onRefreshDrives()
            }
            is WorkerEvent.Error ->
                _state.update { it.copy(flashState = FlashOperationState.Failed(event.message)) }
            is WorkerEvent.DrivesListed,
            is WorkerEvent.ImageInspected,
            -> Unit
        }
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
