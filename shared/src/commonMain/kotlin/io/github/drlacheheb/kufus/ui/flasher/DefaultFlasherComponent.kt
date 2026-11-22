package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlin.coroutines.CoroutineContext

class DefaultFlasherComponent(
    componentContext: ComponentContext,
    private val diskRepository: DiskRepository,
    private val imageRepository: ImageRepository =
        ImageRepository { Result.failure(IllegalStateException("Image inspection unavailable.")) },
    private val imageFilePicker: ImageFilePicker = ImageFilePicker { null },
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

    init {
        lifecycle.doOnDestroy { scope.cancel() }
        onRefreshDrives()
        observeHotplugDrives()
    }

    override fun onRefreshDrives() {
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
        _state.update { it.copy(selectedDriveId = driveId) }
    }

    override fun onSelectImagePath(path: String) {
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
        inspectJob?.cancel()
        _state.update {
            it.copy(
                selectedImagePath = null,
                inspectedImage = null,
                isInspectingImage = false,
                imageErrorMessage = null,
            )
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
