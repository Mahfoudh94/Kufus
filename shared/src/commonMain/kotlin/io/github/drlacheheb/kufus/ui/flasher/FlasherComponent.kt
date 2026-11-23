package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.runtime.Immutable
import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.disk.DEFAULT_FORMAT_VOLUME_LABEL
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.HostOsKind
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.formatBlockReason
import io.github.drlacheheb.kufus.disk.supportedClusterSizes
import io.github.drlacheheb.kufus.disk.supportedFilesystemsForHost
import io.github.drlacheheb.kufus.disk.supportedWindowsFilesystemsForHost
import io.github.drlacheheb.kufus.disk.unsupportedFlashReason
import io.github.drlacheheb.kufus.disk.windowsInstallBlockReason

@Immutable
enum class BootSelectionMode {
    DISK_OR_ISO,
    NON_BOOTABLE,
}

@Immutable
enum class ActiveOperationKind {
    FLASH,
    FORMAT,
}

@Immutable
data class FormatOptionsState(
    val partitionScheme: PartitionScheme = PartitionScheme.GPT,
    val filesystem: FilesystemType = FilesystemType.FAT32,
    val volumeLabel: String = DEFAULT_FORMAT_VOLUME_LABEL,
    val clusterSizeBytes: Int? = null,
    val quickFormat: Boolean = true,
)

@Immutable
sealed interface FlashOperationState {
    @Immutable
    data object Idle : FlashOperationState

    @Immutable
    data class ConfirmingWindows(
        val options: WindowsUserExperienceOptions = WindowsUserExperienceOptions(),
        val ejectAfterWrite: Boolean = true,
        val defaultUsername: String? = null,
        val defaultLocaleTag: String? = null,
    ) : FlashOperationState

    @Immutable
    data class Confirming(
        val verifyAfterWrite: Boolean = true,
        val ejectAfterWrite: Boolean = true,
        val windowsOptions: WindowsUserExperienceOptions? = null,
    ) : FlashOperationState

    @Immutable
    data class ConfirmingFormat(
        val ejectAfterFormat: Boolean = false,
    ) : FlashOperationState

    @Immutable
    data class Flashing(
        val phase: FlashPhase,
        val bytesProcessed: Long = 0L,
        val totalBytes: Long = 0L,
        val speedBytesPerSec: Long = 0L,
        val operationKind: ActiveOperationKind = ActiveOperationKind.FLASH,
    ) : FlashOperationState

    @Immutable
    data class Completed(
        val verified: Boolean,
        val elapsedMs: Long,
        val ejected: Boolean = false,
        val operationKind: ActiveOperationKind = ActiveOperationKind.FLASH,
        val summaryDetail: String? = null,
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
    val bootSelectionMode: BootSelectionMode = BootSelectionMode.DISK_OR_ISO,
    val selectedImagePath: String? = null,
    val inspectedImage: ImageInfo? = null,
    val isInspectingImage: Boolean = false,
    val imageErrorMessage: String? = null,
    val isScanning: Boolean = false,
    val errorMessage: String? = null,
    val flashState: FlashOperationState = FlashOperationState.Idle,
    val formatOptions: FormatOptionsState = FormatOptionsState(),
    val hostOs: HostOsKind = HostOsKind.LINUX,
    val platformName: String = "",
    val operationLog: List<String> = emptyList(),
    val lastElapsedMs: Long = 0L,
) {
    val selectedDrive: UsbDrive?
        get() = drives.firstOrNull { it.id == selectedDriveId }

    val requiredSizeBytes: Long?
        get() = inspectedImage?.let { info -> info.uncompressedSizeHintBytes ?: info.sizeBytes }

    val isFlashing: Boolean
        get() = flashState is FlashOperationState.Flashing

    val isFormattingMode: Boolean
        get() = bootSelectionMode == BootSelectionMode.NON_BOOTABLE

    val isWindowsIsoSelected: Boolean
        get() = bootSelectionMode == BootSelectionMode.DISK_OR_ISO && inspectedImage?.format == ImageFormat.WINDOWS_ISO

    val availableFilesystems: List<FilesystemType>
        get() = if (isWindowsIsoSelected) supportedWindowsFilesystemsForHost(hostOs) else supportedFilesystemsForHost(hostOs)

    val availableClusterSizes: List<Int?>
        get() = supportedClusterSizes(formatOptions.filesystem)

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
                info.format == ImageFormat.WINDOWS_ISO ->
                    windowsInstallBlockReason(
                        scheme = formatOptions.partitionScheme,
                        filesystem = formatOptions.filesystem,
                        hostOs = hostOs,
                        drive = drive,
                        image = info,
                    )
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
        get() = bootSelectionMode == BootSelectionMode.DISK_OR_ISO && flashBlockReason == null && !isFlashing

    val formatBlockReason: String?
        get() =
            formatBlockReason(
                scheme = formatOptions.partitionScheme,
                filesystem = formatOptions.filesystem,
                hostOs = hostOs,
                drive = selectedDrive,
            )

    val canFormat: Boolean
        get() = bootSelectionMode == BootSelectionMode.NON_BOOTABLE && formatBlockReason == null && !isFlashing
}

@Suppress("TooManyFunctions")
interface FlasherComponent {
    val state: Value<FlasherUiState>

    fun onRefreshDrives()

    fun onSelectDrive(driveId: String)

    fun onSelectBootSelectionMode(mode: BootSelectionMode)

    fun onSelectImagePath(path: String)

    fun onBrowseImage()

    fun onClearImage()

    fun onSelectPartitionScheme(scheme: PartitionScheme)

    fun onSelectFilesystem(filesystem: FilesystemType)

    fun onUpdateVolumeLabel(label: String)

    fun onSelectClusterSize(clusterSizeBytes: Int?)

    fun onToggleQuickFormat(enabled: Boolean)

    fun onRequestFlash()

    fun onUpdateWindowsUserExperience(options: WindowsUserExperienceOptions)

    fun onConfirmWindowsUserExperience()

    fun onToggleVerifyAfterWrite(enabled: Boolean)

    fun onToggleEjectAfterWrite(enabled: Boolean)

    fun onConfirmFlash()

    fun onRequestFormat()

    fun onToggleEjectAfterFormat(enabled: Boolean)

    fun onConfirmFormat()

    fun onDismissConfirmation()

    fun onCancelFlash()

    fun onDismissFlashResult()

    fun interface Factory {
        fun create(componentContext: ComponentContext): FlasherComponent
    }
}
