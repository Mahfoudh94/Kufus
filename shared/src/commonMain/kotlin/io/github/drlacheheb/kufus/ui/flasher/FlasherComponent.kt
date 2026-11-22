package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.disk.UsbDrive

data class FlasherUiState(
    val drives: List<UsbDrive> = emptyList(),
    val selectedDriveId: String? = null,
    val selectedImagePath: String? = null,
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
    val platformName: String = "",
)

interface FlasherComponent {
    val state: Value<FlasherUiState>

    fun onRefreshDrives()

    fun onSelectDrive(driveId: String)

    fun interface Factory {
        fun create(componentContext: ComponentContext): FlasherComponent
    }
}
