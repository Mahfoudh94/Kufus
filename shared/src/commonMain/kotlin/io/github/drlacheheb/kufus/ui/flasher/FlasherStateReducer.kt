package io.github.drlacheheb.kufus.ui.flasher

import io.github.drlacheheb.kufus.disk.format.core.defaultWindowsFilesystemForHost
import io.github.drlacheheb.kufus.disk.format.core.filesystemDisplayName
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.format.core.supportedClusterSizes
import io.github.drlacheheb.kufus.disk.model.DEFAULT_FORMAT_VOLUME_LABEL
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.FlashPhase
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.WorkerEvent

private const val MAX_OPERATION_LOG_ENTRIES = 100

/**
 * Pure state-transition functions for [FlasherUiState], keeping state reduction decoupled from
 * coroutine and repository orchestration inside [DefaultFlasherComponent].
 */
internal object FlasherStateReducer {
    fun applyInspectedImage(
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

    fun applyDriveList(
        current: FlasherUiState,
        drives: List<UsbDrive>,
    ): FlasherUiState =
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

    fun applyWorkerEvent(
        current: FlasherUiState,
        event: WorkerEvent,
        operationKind: ActiveOperationKind,
        summaryDetail: String?,
    ): FlasherUiState =
        when (event) {
            is WorkerEvent.Started -> applyStarted(current, event, operationKind)
            is WorkerEvent.Progress -> applyProgress(current, event, operationKind)
            is WorkerEvent.Completed -> applyCompleted(current, event, operationKind, summaryDetail)
            is WorkerEvent.Error ->
                current.copy(
                    flashState = FlashOperationState.Failed(event.message),
                    operationLog = appendLogLine(current.operationLog, "Error [${event.code}]: ${event.message}"),
                )
            is WorkerEvent.DrivesListed,
            is WorkerEvent.ImageInspected,
            -> current
        }

    fun buildFormatSummaryDetail(
        scheme: PartitionScheme,
        filesystem: FilesystemType,
        sanitizedLabel: String,
    ): String {
        val labelSuffix = if (sanitizedLabel.isNotEmpty()) " • $sanitizedLabel" else ""
        return "${scheme.name} • ${filesystemDisplayName(filesystem)}$labelSuffix"
    }

    private fun applyStarted(
        current: FlasherUiState,
        event: WorkerEvent.Started,
        operationKind: ActiveOperationKind,
    ): FlasherUiState {
        val initialPhase = if (operationKind == ActiveOperationKind.FORMAT) FlashPhase.WIPING else FlashPhase.WRITING
        return current.copy(
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

    private fun applyProgress(
        current: FlasherUiState,
        event: WorkerEvent.Progress,
        operationKind: ActiveOperationKind,
    ): FlasherUiState {
        val previousPhase = (current.flashState as? FlashOperationState.Flashing)?.phase
        val updatedLog =
            if (previousPhase != event.phase) {
                appendLogLine(current.operationLog, "Phase: ${event.phase.name.lowercase()}")
            } else {
                current.operationLog
            }
        return current.copy(
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

    private fun applyCompleted(
        current: FlasherUiState,
        event: WorkerEvent.Completed,
        operationKind: ActiveOperationKind,
        summaryDetail: String?,
    ): FlasherUiState =
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

    private fun appendLogLine(
        existing: List<String>,
        entry: String,
    ): List<String> = (existing + entry).takeLast(MAX_OPERATION_LOG_ENTRIES)
}
