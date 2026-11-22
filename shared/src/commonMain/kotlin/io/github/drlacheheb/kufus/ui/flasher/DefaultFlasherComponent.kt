package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.decompose.value.update
import com.arkivanov.essenty.lifecycle.doOnDestroy
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
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
    platformName: String = "",
    mainContext: CoroutineContext = Dispatchers.Main,
) : FlasherComponent,
    ComponentContext by componentContext {
    private val scope = CoroutineScope(SupervisorJob() + mainContext)
    private val _state = MutableValue(FlasherUiState(platformName = platformName))
    override val state: Value<FlasherUiState> = _state
    private var refreshJob: Job? = null

    init {
        lifecycle.doOnDestroy { scope.cancel() }
        onRefreshDrives()
    }

    override fun onRefreshDrives() {
        refreshJob?.cancel()
        refreshJob =
            scope.launch {
                _state.update { it.copy(isScanning = true, errorMessage = null) }
                diskRepository
                    .listUsbDrives()
                    .onSuccess { drives ->
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
                    }.onFailure { error ->
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
}
