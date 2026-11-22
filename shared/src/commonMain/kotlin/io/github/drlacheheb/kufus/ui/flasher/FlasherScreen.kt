package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material.icons.outlined.Usb
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.formatByteSize
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100

private data class NoticeMessage(
    val text: String,
    val isError: Boolean,
)

@Composable
fun FlasherScreen(
    component: FlasherComponent,
    modifier: Modifier = Modifier,
) {
    val uiState by component.state.subscribeAsState()

    FlasherContent(
        uiState = uiState,
        onBrowseImage = component::onBrowseImage,
        onSelectImagePath = component::onSelectImagePath,
        onClearImage = component::onClearImage,
        onRefreshDrives = component::onRefreshDrives,
        onSelectDrive = component::onSelectDrive,
        onFlashClick = component::onRequestFlash,
        onVerifyToggle = component::onToggleVerifyAfterWrite,
        onEjectToggle = component::onToggleEjectAfterWrite,
        onConfirmFlash = component::onConfirmFlash,
        onDismissConfirm = component::onDismissConfirmation,
        onCancelFlash = component::onCancelFlash,
        onDismissResult = component::onDismissFlashResult,
        modifier = modifier,
    )
}

@Composable
fun FlasherContent(
    uiState: FlasherUiState,
    onBrowseImage: () -> Unit,
    onSelectImagePath: (String) -> Unit,
    onClearImage: () -> Unit,
    onRefreshDrives: () -> Unit,
    onSelectDrive: (String) -> Unit,
    onFlashClick: () -> Unit,
    onVerifyToggle: (Boolean) -> Unit,
    onEjectToggle: (Boolean) -> Unit,
    onConfirmFlash: () -> Unit,
    onDismissConfirm: () -> Unit,
    onCancelFlash: () -> Unit,
    onDismissResult: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val interactive = !uiState.isFlashing

    Column(
        modifier =
            modifier
                .safeContentPadding()
                .fillMaxSize()
                .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        FlasherHeader(
            platformName = uiState.platformName,
            isScanning = uiState.isScanning,
            controlsEnabled = interactive,
            onRefreshDrives = onRefreshDrives,
        )

        if (uiState.errorMessage != null) {
            Text(
                text = uiState.errorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }

        ImageDropzoneCard(
            uiState = uiState,
            controlsEnabled = interactive,
            onBrowseImage = onBrowseImage,
            onFileDrop = onSelectImagePath,
            onClearImage = onClearImage,
        )

        DriveListSection(
            drives = uiState.drives,
            selectedDriveId = uiState.selectedDriveId,
            controlsEnabled = interactive,
            onSelectDrive = onSelectDrive,
            modifier = Modifier.weight(1f),
        )

        FlashOperationSection(
            uiState = uiState,
            onFlashClick = onFlashClick,
            onCancelFlash = onCancelFlash,
            onDismissResult = onDismissResult,
        )
    }

    val confirmingState = uiState.flashState as? FlashOperationState.Confirming
    val selectedDrive = uiState.selectedDrive
    val selectedImage = uiState.inspectedImage
    if (confirmingState != null && selectedDrive != null && selectedImage != null) {
        ConfirmFlashDialog(
            drive = selectedDrive,
            image = selectedImage,
            verifyAfterWrite = confirmingState.verifyAfterWrite,
            ejectAfterWrite = confirmingState.ejectAfterWrite,
            onVerifyToggle = onVerifyToggle,
            onEjectToggle = onEjectToggle,
            onConfirmFlash = onConfirmFlash,
            onDismissConfirm = onDismissConfirm,
        )
    }
}

@Composable
private fun FlasherHeader(
    platformName: String,
    isScanning: Boolean,
    controlsEnabled: Boolean,
    onRefreshDrives: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            Text(
                text = "Kufus",
                style = MaterialTheme.typography.headlineMedium,
            )
            if (platformName.isNotEmpty()) {
                Text(
                    text = "Runtime: $platformName",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        OutlinedButton(
            onClick = onRefreshDrives,
            enabled = controlsEnabled && !isScanning,
        ) {
            Icon(
                imageVector = Icons.Outlined.Refresh,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(if (isScanning) "Scanning..." else "Refresh Drives")
        }
    }
}

@Composable
private fun ImageDropzoneCard(
    uiState: FlasherUiState,
    controlsEnabled: Boolean,
    onBrowseImage: () -> Unit,
    onFileDrop: (String) -> Unit,
    onClearImage: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var isDragHover by remember { mutableStateOf(false) }

    OutlinedCard(
        modifier =
            modifier
                .fillMaxWidth()
                .externalFileDropTarget(
                    enabled = controlsEnabled && !uiState.isInspectingImage,
                    onDragHoverChange = { hovered -> isDragHover = hovered },
                    onFileDrop = onFileDrop,
                ),
        border =
            if (isDragHover) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                CardDefaults.outlinedCardBorder()
            },
        colors =
            CardDefaults.outlinedCardColors(
                containerColor =
                    if (isDragHover) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surface
                    },
            ),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            when {
                uiState.isInspectingImage -> {
                    InspectingImageContent(imagePath = uiState.selectedImagePath.orEmpty())
                }
                uiState.inspectedImage != null -> {
                    SelectedImageContent(
                        image = uiState.inspectedImage,
                        notice = buildImageNotice(uiState),
                        controlsEnabled = controlsEnabled,
                        onBrowseImage = onBrowseImage,
                        onClearImage = onClearImage,
                    )
                }
                else -> {
                    EmptyDropzoneContent(
                        isDragHover = isDragHover,
                        controlsEnabled = controlsEnabled,
                        onBrowseImage = onBrowseImage,
                    )
                }
            }

            if (uiState.imageErrorMessage != null) {
                Text(
                    text = uiState.imageErrorMessage,
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun InspectingImageContent(imagePath: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "Inspecting image header...",
                style = MaterialTheme.typography.titleMedium,
            )
            if (imagePath.isNotEmpty()) {
                Text(
                    text = fileDisplayName(imagePath),
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyDropzoneContent(
    isDragHover: Boolean,
    controlsEnabled: Boolean,
    onBrowseImage: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Icon(
            imageVector = Icons.Outlined.UploadFile,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(36.dp),
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = if (isDragHover) "Drop image to inspect" else "Drop an OS image here",
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = "Supports ISO, IMG, BIN, RAW, GZ, XZ, and ZST",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        Button(
            onClick = onBrowseImage,
            enabled = controlsEnabled,
        ) {
            Icon(
                imageVector = Icons.Outlined.FolderOpen,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text("Choose Image")
        }
    }
}

@Composable
private fun SelectedImageContent(
    image: ImageInfo,
    notice: NoticeMessage?,
    controlsEnabled: Boolean,
    onBrowseImage: () -> Unit,
    onClearImage: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Description,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = fileDisplayName(image.path),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = formatImageSubtitle(image),
                    style = MaterialTheme.typography.bodySmall,
                )
                Text(
                    text = image.path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            OutlinedButton(
                onClick = onBrowseImage,
                enabled = controlsEnabled,
            ) {
                Text("Change")
            }
            IconButton(
                onClick = onClearImage,
                enabled = controlsEnabled,
            ) {
                Icon(
                    imageVector = Icons.Outlined.Close,
                    contentDescription = "Clear image",
                )
            }
        }

        if (notice != null) {
            ImageNoticeBanner(notice = notice)
        }
    }
}

@Composable
private fun ImageNoticeBanner(notice: NoticeMessage) {
    val containerColor =
        if (notice.isError) {
            MaterialTheme.colorScheme.errorContainer
        } else {
            MaterialTheme.colorScheme.secondaryContainer
        }
    val contentColor =
        if (notice.isError) {
            MaterialTheme.colorScheme.onErrorContainer
        } else {
            MaterialTheme.colorScheme.onSecondaryContainer
        }
    Surface(
        color = containerColor,
        contentColor = contentColor,
        shape = MaterialTheme.shapes.small,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = notice.text,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun DriveListSection(
    drives: List<UsbDrive>,
    selectedDriveId: String?,
    controlsEnabled: Boolean,
    onSelectDrive: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Icon(
                imageVector = Icons.Outlined.Usb,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Text(
                text = "Target USB Drive",
                style = MaterialTheme.typography.titleSmall,
            )
        }

        if (drives.isEmpty()) {
            Column(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = "No USB drives detected.",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text(
                    text = "Plug in a USB drive — Kufus detects drives automatically.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            return
        }

        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items = drives, key = { it.id }) { drive ->
                DriveCard(
                    drive = drive,
                    selected = drive.id == selectedDriveId,
                    enabled = controlsEnabled,
                    onSelect = { onSelectDrive(drive.id) },
                )
            }
        }
    }
}

@Composable
private fun DriveCard(
    drive: UsbDrive,
    selected: Boolean,
    enabled: Boolean,
    onSelect: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onSelect,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        border =
            if (selected) {
                BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
            } else {
                null
            },
        colors =
            CardDefaults.cardColors(
                containerColor =
                    if (selected) {
                        MaterialTheme.colorScheme.primaryContainer
                    } else {
                        MaterialTheme.colorScheme.surfaceVariant
                    },
            ),
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            RadioButton(
                selected = selected,
                onClick = onSelect,
                enabled = enabled,
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = drive.model,
                    style = MaterialTheme.typography.titleMedium,
                )
                val mountLabel =
                    if (drive.mountPoints.isEmpty()) {
                        "Not mounted"
                    } else {
                        "Mounted: ${drive.mountPoints.joinToString(", ")}"
                    }
                Text(
                    text = "${drive.id} • ${formatByteSize(drive.sizeBytes)} • $mountLabel",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun FlashOperationSection(
    uiState: FlasherUiState,
    onFlashClick: () -> Unit,
    onCancelFlash: () -> Unit,
    onDismissResult: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        when (val flashState = uiState.flashState) {
            is FlashOperationState.Flashing -> {
                FlashProgressCard(
                    flashState = flashState,
                    onCancelFlash = onCancelFlash,
                )
            }
            is FlashOperationState.Completed -> {
                FlashOutcomeBanner(
                    isSuccess = true,
                    text = formatCompletionMessage(flashState),
                    onDismiss = onDismissResult,
                )
            }
            is FlashOperationState.Failed -> {
                FlashOutcomeBanner(
                    isSuccess = false,
                    text = flashState.message,
                    onDismiss = onDismissResult,
                )
            }
            FlashOperationState.Idle,
            is FlashOperationState.Confirming,
            -> {
                ReadinessActionBar(
                    uiState = uiState,
                    onFlashClick = onFlashClick,
                )
            }
        }
    }
}

@Composable
private fun FlashProgressCard(
    flashState: FlashOperationState.Flashing,
    onCancelFlash: () -> Unit,
) {
    val fraction =
        if (flashState.phase == FlashPhase.UNMOUNTING || flashState.phase == FlashPhase.EJECTING) {
            null
        } else {
            calculateProgressFraction(flashState.bytesProcessed, flashState.totalBytes)
        }
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = flashPhaseLabel(flashState.phase),
                    style = MaterialTheme.typography.titleSmall,
                )
                if (fraction != null) {
                    Text(
                        text = "${(fraction * PERCENT_SCALE).roundToInt()}%",
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }

            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = formatProgressMetrics(flashState),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(modifier = Modifier.width(12.dp))
                OutlinedButton(onClick = onCancelFlash) {
                    Text("Cancel")
                }
            }
        }
    }
}

@Composable
private fun FlashOutcomeBanner(
    isSuccess: Boolean,
    text: String,
    onDismiss: () -> Unit,
) {
    Surface(
        color =
            if (isSuccess) {
                MaterialTheme.colorScheme.primaryContainer
            } else {
                MaterialTheme.colorScheme.errorContainer
            },
        contentColor =
            if (isSuccess) {
                MaterialTheme.colorScheme.onPrimaryContainer
            } else {
                MaterialTheme.colorScheme.onErrorContainer
            },
        shape = MaterialTheme.shapes.medium,
    ) {
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Icon(
                imageVector = if (isSuccess) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline,
                contentDescription = null,
            )
            Text(
                text = text,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("Dismiss")
            }
        }
    }
}

@Composable
private fun ReadinessActionBar(
    uiState: FlasherUiState,
    onFlashClick: () -> Unit,
) {
    val image = uiState.inspectedImage
    val drive = uiState.selectedDrive
    val message =
        when {
            uiState.canFlash && image != null && drive != null -> {
                "Ready: ${fileDisplayName(image.path)} -> ${drive.model} (${formatByteSize(drive.sizeBytes)})"
            }
            else -> uiState.flashBlockReason ?: "Select an image and a target USB drive."
        }
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodySmall,
            color =
                if (uiState.isImageOversizedForSelectedDrive || uiState.imageErrorMessage != null) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                },
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Button(
            onClick = onFlashClick,
            enabled = uiState.canFlash,
        ) {
            Icon(
                imageVector = Icons.Outlined.Bolt,
                contentDescription = null,
                modifier = Modifier.size(18.dp),
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text("Flash")
        }
    }
}

@Composable
private fun ConfirmFlashDialog(
    drive: UsbDrive,
    image: ImageInfo,
    verifyAfterWrite: Boolean,
    ejectAfterWrite: Boolean,
    onVerifyToggle: (Boolean) -> Unit,
    onEjectToggle: (Boolean) -> Unit,
    onConfirmFlash: () -> Unit,
    onDismissConfirm: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismissConfirm,
        icon = {
            Icon(
                imageVector = Icons.Outlined.Warning,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
            )
        },
        title = { Text("Confirm Erase & Flash") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    text = "All partitions and data on this target drive will be permanently erased:",
                    style = MaterialTheme.typography.bodyMedium,
                )
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    shape = MaterialTheme.shapes.small,
                ) {
                    Column(
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        Text(
                            text = "${drive.model} (${formatByteSize(drive.sizeBytes)})",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        val serialSuffix = drive.serial?.let { " • Serial: $it" }.orEmpty()
                        Text(
                            text = "${drive.id}$serialSuffix",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
                Text(
                    text = "Image: ${fileDisplayName(image.path)} (${formatImageSubtitle(image)})",
                    style = MaterialTheme.typography.bodySmall,
                )
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onVerifyToggle(!verifyAfterWrite) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = verifyAfterWrite,
                        onCheckedChange = onVerifyToggle,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Verify written data with SHA-256 after flashing",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .clickable { onEjectToggle(!ejectAfterWrite) },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = ejectAfterWrite,
                        onCheckedChange = onEjectToggle,
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Safe-eject USB drive after flashing",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        dismissButton = {
            TextButton(onClick = onDismissConfirm) {
                Text("Cancel")
            }
        },
        confirmButton = {
            Button(
                onClick = onConfirmFlash,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError,
                    ),
            ) {
                Text("Erase & Flash")
            }
        },
    )
}

private fun fileDisplayName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

private fun formatImageSubtitle(image: ImageInfo): String {
    val formatLabel =
        when (image.format) {
            ImageFormat.HYBRID_ISO -> "Bootable ISO"
            ImageFormat.WINDOWS_ISO -> "Windows ISO (UDF/WIM)"
            ImageFormat.RAW_IMAGE -> "Raw Disk Image"
            ImageFormat.COMPRESSED_IMAGE -> "Compressed (${image.compression.name})"
            ImageFormat.UNKNOWN -> "Unrecognized Format"
        }
    val sizePart = formatByteSize(image.sizeBytes)
    val hintBytes = image.uncompressedSizeHintBytes
    return if (hintBytes != null) {
        "$formatLabel • $sizePart (~${formatByteSize(hintBytes)} uncompressed)"
    } else {
        "$formatLabel • $sizePart"
    }
}

private fun buildImageNotice(uiState: FlasherUiState): NoticeMessage? {
    val image = uiState.inspectedImage ?: return null
    val drive = uiState.selectedDrive
    if (uiState.isImageOversizedForSelectedDrive && drive != null) {
        val needed = formatByteSize(uiState.requiredSizeBytes ?: image.sizeBytes)
        return NoticeMessage(
            text = "Image requires $needed, which exceeds ${drive.model} (${formatByteSize(drive.sizeBytes)}).",
            isError = true,
        )
    }
    return when (image.format) {
        ImageFormat.COMPRESSED_IMAGE ->
            if (image.compression == CompressionFormat.GZIP) {
                NoticeMessage(
                    text = "Compressed GZIP stream will be decompressed on the fly during write.",
                    isError = false,
                )
            } else {
                NoticeMessage(
                    text = "Compressed ${image.compression.name} streaming is not yet enabled in this version.",
                    isError = false,
                )
            }
        ImageFormat.WINDOWS_ISO ->
            NoticeMessage(
                text = "Windows ISO detected — requires NTFS/UEFI installer preparation rather than raw write.",
                isError = false,
            )
        ImageFormat.UNKNOWN ->
            NoticeMessage(
                text = "No standard MBR, GPT, or ISO-9660 signature found — verify the image before flashing.",
                isError = false,
            )
        ImageFormat.HYBRID_ISO,
        ImageFormat.RAW_IMAGE,
        -> null
    }
}

private fun flashPhaseLabel(phase: FlashPhase): String =
    when (phase) {
        FlashPhase.UNMOUNTING -> "Unmounting & locking target drive..."
        FlashPhase.WRITING -> "Writing image to USB drive..."
        FlashPhase.VERIFYING -> "Verifying written sectors..."
        FlashPhase.EJECTING -> "Safely ejecting target USB drive..."
    }

private fun formatProgressMetrics(flashState: FlashOperationState.Flashing): String {
    if (flashState.phase == FlashPhase.UNMOUNTING) {
        return "Requesting OS lock and flushing cached buffers"
    }
    if (flashState.phase == FlashPhase.EJECTING) {
        return "Flushing buffers and detaching target drive cleanly"
    }
    val progressPart =
        if (flashState.totalBytes > 0L) {
            "${formatByteSize(flashState.bytesProcessed)} / ${formatByteSize(flashState.totalBytes)}"
        } else {
            formatByteSize(flashState.bytesProcessed)
        }
    val speedPart =
        if (flashState.speedBytesPerSec > 0L) {
            " • ${formatByteSize(flashState.speedBytesPerSec)}/s"
        } else {
            ""
        }
    val etaSeconds =
        estimateRemainingSeconds(
            flashState.bytesProcessed,
            flashState.totalBytes,
            flashState.speedBytesPerSec,
        )
    val etaPart = etaSeconds?.let { " • ETA ${formatDurationSeconds(it)}" }.orEmpty()
    return "$progressPart$speedPart$etaPart"
}

private fun formatCompletionMessage(completed: FlashOperationState.Completed): String {
    val duration = formatElapsedMillis(completed.elapsedMs)
    val verifySuffix = if (completed.verified) " • Written data verified with SHA-256." else ""
    val ejectSuffix = if (completed.ejected) " Safe to remove USB drive." else ""
    return "Flash completed in $duration$verifySuffix$ejectSuffix"
}

@Preview
@Composable
@Suppress("UnusedPrivateMember")
private fun FlasherContentEmptyPreview() {
    MaterialTheme {
        FlasherContent(
            uiState = FlasherUiState(platformName = "Linux (Preview)"),
            onBrowseImage = {},
            onSelectImagePath = {},
            onClearImage = {},
            onRefreshDrives = {},
            onSelectDrive = {},
            onFlashClick = {},
            onVerifyToggle = {},
            onEjectToggle = {},
            onConfirmFlash = {},
            onDismissConfirm = {},
            onCancelFlash = {},
            onDismissResult = {},
        )
    }
}

@Preview
@Composable
@Suppress("UnusedPrivateMember")
private fun FlasherContentFlashingPreview() {
    MaterialTheme {
        FlasherContent(
            uiState =
                FlasherUiState(
                    drives =
                        listOf(
                            UsbDrive(
                                id = "/dev/sdb",
                                name = "sdb",
                                model = "SanDisk Extreme Pro",
                                sizeBytes = 64_000_000_000L,
                            ),
                        ),
                    selectedDriveId = "/dev/sdb",
                    selectedImagePath = "/home/user/ubuntu-24.04.iso.gz",
                    inspectedImage =
                        ImageInfo(
                            path = "/home/user/ubuntu-24.04.iso.gz",
                            sizeBytes = 2_400_000_000L,
                            format = ImageFormat.COMPRESSED_IMAGE,
                            compression = CompressionFormat.GZIP,
                            uncompressedSizeHintBytes = 5_800_000_000L,
                        ),
                    flashState =
                        FlashOperationState.Flashing(
                            phase = FlashPhase.WRITING,
                            bytesProcessed = 2_900_000_000L,
                            totalBytes = 5_800_000_000L,
                            speedBytesPerSec = 95_000_000L,
                        ),
                    platformName = "Linux (Preview)",
                ),
            onBrowseImage = {},
            onSelectImagePath = {},
            onClearImage = {},
            onRefreshDrives = {},
            onSelectDrive = {},
            onFlashClick = {},
            onVerifyToggle = {},
            onEjectToggle = {},
            onConfirmFlash = {},
            onDismissConfirm = {},
            onCancelFlash = {},
            onDismissResult = {},
        )
    }
}
