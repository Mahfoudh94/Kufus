package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.runtime.Immutable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive

@Immutable
data class FlasherUiState(
    val drives: List<UsbDrive> = emptyList(),
    val selectedDriveId: String? = null,
    val selectedImagePath: String? = null,
    val inspectedImage: ImageInfo? = null,
    val isInspectingImage: Boolean = false,
    val imageErrorMessage: String? = null,
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
    val platformName: String = "",
) {
    val selectedDrive: UsbDrive?
        get() = drives.firstOrNull { it.id == selectedDriveId }

    val requiredSizeBytes: Long?
        get() = inspectedImage?.let { info -> info.uncompressedSizeHintBytes ?: info.sizeBytes }

    val hasValidImage: Boolean
        get() = inspectedImage != null && !isInspectingImage && imageErrorMessage == null

    val isImageOversizedForSelectedDrive: Boolean
        get() {
            val drive = selectedDrive ?: return false
            val imageBytes = requiredSizeBytes ?: return false
            return imageBytes > drive.sizeBytes
        }

    val canFlash: Boolean
        get() = selectedDrive != null && hasValidImage && !isImageOversizedForSelectedDrive
}

interface FlasherComponent {
    val state: Value<FlasherUiState>

    fun onRefreshDrives()

    fun onSelectDrive(driveId: String)

    fun onSelectImagePath(path: String)

    fun onBrowseImage()

    fun onClearImage()

    fun interface Factory {
        fun create(componentContext: ComponentContext): FlasherComponent
    }
}
