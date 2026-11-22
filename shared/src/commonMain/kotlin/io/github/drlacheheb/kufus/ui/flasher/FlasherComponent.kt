package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.runtime.Immutable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.unsupportedFlashReason

@Immutable
sealed interface FlashOperationState {
    @Immutable
    data object Idle : FlashOperationState

    @Immutable
    data class Confirming(
        val verifyAfterWrite: Boolean = true,
        val ejectAfterWrite: Boolean = true,
    ) : FlashOperationState

    @Immutable
    data class Flashing(
        val phase: FlashPhase,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
    ) : FlashOperationState

    @Immutable
    data class Completed(
        val verified: Boolean,
        val elapsedMs: Long,
        val ejected: Boolean = false,
    ) : FlashOperationState

    @Immutable
    data class Failed(
        val message: String,
    ) : FlashOperationState
}

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
    val flashState: FlashOperationState = FlashOperationState.Idle,
    val platformName: String = "",
) {
    val selectedDrive: UsbDrive?
        get() = drives.firstOrNull { it.id == selectedDriveId }

    val requiredSizeBytes: Long?
        get() = inspectedImage?.let { info -> info.uncompressedSizeHintBytes ?: info.sizeBytes }

    val isFlashing: Boolean
        get() = flashState is FlashOperationState.Flashing

    val isImageOversizedForSelectedDrive: Boolean
        get() {
            val drive = selectedDrive ?: return false
            val imageBytes = requiredSizeBytes ?: return false
            return imageBytes > drive.sizeBytes
        }

    val flashBlockReason: String?
        get() {
            val info = inspectedImage
            val drive = selectedDrive
            return when {
                info == null -> "Select an image to flash."
                isInspectingImage -> "Inspecting image header..."
                imageErrorMessage != null -> imageErrorMessage
                else ->
                    unsupportedFlashReason(info)
                        ?: when {
                            drive == null -> "Plug in and select a target USB drive."
                            isImageOversizedForSelectedDrive -> "Selected USB drive does not have enough capacity."
                            else -> null
                        }
            }
        }

    val canFlash: Boolean
        get() = flashBlockReason == null && !isFlashing
}

interface FlasherComponent {
    val state: Value<FlasherUiState>

    fun onRefreshDrives()

    fun onSelectDrive(driveId: String)

    fun onSelectImagePath(path: String)

    fun onBrowseImage()

    fun onClearImage()

    fun onRequestFlash()

    fun onToggleVerifyAfterWrite(enabled: Boolean)

    fun onToggleEjectAfterWrite(enabled: Boolean)

    fun onConfirmFlash()

    fun onDismissConfirmation()

    fun onCancelFlash()

    fun onDismissFlashResult()

    fun interface Factory {
        fun create(componentContext: ComponentContext): FlasherComponent
    }
}
