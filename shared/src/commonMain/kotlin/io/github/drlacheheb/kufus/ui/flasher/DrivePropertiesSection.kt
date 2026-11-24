package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.drlacheheb.kufus.disk.format.core.targetSystemLabel
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.formatByteSize
import io.github.drlacheheb.kufus.ui.components.KufusActionButton
import io.github.drlacheheb.kufus.ui.components.KufusCollapsibleRow
import io.github.drlacheheb.kufus.ui.components.KufusControlGap
import io.github.drlacheheb.kufus.ui.components.KufusControlShape
import io.github.drlacheheb.kufus.ui.components.KufusDropdownBox
import io.github.drlacheheb.kufus.ui.components.KufusDropdownMenuItem
import io.github.drlacheheb.kufus.ui.components.KufusFieldHeight
import io.github.drlacheheb.kufus.ui.components.KufusFieldLabel
import io.github.drlacheheb.kufus.ui.components.KufusSectionHeading
import io.github.drlacheheb.kufus.ui.components.KufusSquareIconButton
import io.github.drlacheheb.kufus.ui.theme.CaptionXs
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.MonoDetail

@Composable
internal fun RufusDrivePropertiesSection(
    uiState: FlasherUiState,
    interactive: Boolean,
    formatFieldsEnabled: Boolean,
    showAdvancedDrive: Boolean,
    onToggleAdvancedDrive: () -> Unit,
    onShowImageInfo: () -> Unit,
    actions: FlasherActions,
) {
    val palette = KufusTheme.palette
    var deviceMenuOpen by remember { mutableStateOf(false) }
    var bootMenuOpen by remember { mutableStateOf(false) }
    var schemeMenuOpen by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KufusSectionHeading(title = "Drive Properties")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KufusFieldLabel(text = "Device")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KufusControlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val deviceLabel =
                    uiState.selectedDrive?.let(::formatDriveDropdownLabel)
                        ?: if (uiState.isScanning) "Scanning USB drives..." else "No USB devices detected"
                KufusDropdownBox(
                    selectedLabel = deviceLabel,
                    enabled = interactive && uiState.drives.isNotEmpty(),
                    expanded = deviceMenuOpen,
                    onExpandedChange = { deviceMenuOpen = it },
                    modifier = Modifier.weight(1f),
                ) {
                    uiState.drives.forEach { drive ->
                        KufusDropdownMenuItem(
                            text = formatDriveDropdownLabel(drive),
                            selected = drive.id == uiState.selectedDriveId,
                            onClick = {
                                deviceMenuOpen = false
                                actions.onSelectDrive(drive.id)
                            },
                        )
                    }
                }
                KufusSquareIconButton(
                    icon = Icons.Outlined.Refresh,
                    contentDescription = "Refresh USB drives",
                    enabled = interactive && !uiState.isScanning,
                    onClick = actions.onRefreshDrives,
                )
            }
        }

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KufusFieldLabel(text = "Boot selection")
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(KufusControlGap),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val bootLabel = formatBootSelectionLabel(uiState)
                KufusDropdownBox(
                    selectedLabel = bootLabel,
                    enabled = interactive && !uiState.isInspectingImage,
                    expanded = bootMenuOpen,
                    onExpandedChange = { bootMenuOpen = it },
                    modifier = Modifier.weight(1f),
                ) {
                    KufusDropdownMenuItem(
                        text = "Non-bootable",
                        selected = uiState.bootSelectionMode == BootSelectionMode.NON_BOOTABLE,
                        onClick = {
                            bootMenuOpen = false
                            actions.onSelectBootSelectionMode(BootSelectionMode.NON_BOOTABLE)
                        },
                    )
                    KufusDropdownMenuItem(
                        text = "Disk or ISO image",
                        selected = uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO,
                        onClick = {
                            bootMenuOpen = false
                            actions.onSelectBootSelectionMode(BootSelectionMode.DISK_OR_ISO)
                        },
                    )
                }

                if (uiState.isInspectingImage) {
                    Box(
                        modifier = Modifier.size(KufusFieldHeight),
                        contentAlignment = Alignment.Center,
                    ) {
                        CircularProgressIndicator(
                            color = palette.primary,
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                        )
                    }
                } else {
                    KufusSquareIconButton(
                        icon = Icons.Outlined.CheckCircle,
                        contentDescription = "Inspect image info",
                        enabled = uiState.inspectedImage != null,
                        tint = palette.primary,
                        onClick = onShowImageInfo,
                    )
                }

                if (uiState.selectedImagePath != null) {
                    KufusSquareIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Clear selected image",
                        enabled = interactive,
                        onClick = actions.onClearImage,
                    )
                }

                KufusActionButton(
                    text = "SELECT",
                    enabled = interactive && !uiState.isInspectingImage,
                    emphasized = uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && uiState.inspectedImage == null,
                    onClick = actions.onBrowseImage,
                )
            }

            InlineNoticeMessage(uiState = uiState)
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KufusFieldLabel(text = "Partition scheme")
                KufusDropdownBox(
                    selectedLabel = uiState.formatOptions.partitionScheme.name,
                    enabled = formatFieldsEnabled,
                    expanded = schemeMenuOpen,
                    onExpandedChange = { schemeMenuOpen = it },
                ) {
                    PartitionScheme.entries.forEach { scheme ->
                        KufusDropdownMenuItem(
                            text = "${scheme.name} (${targetSystemLabel(scheme)})",
                            selected = scheme == uiState.formatOptions.partitionScheme,
                            onClick = {
                                schemeMenuOpen = false
                                actions.onSelectPartitionScheme(scheme)
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KufusFieldLabel(text = "Target system")
                val targetLabel =
                    if (uiState.isWindowsIsoSelected) {
                        "UEFI (non-CSM)"
                    } else {
                        targetSystemLabel(uiState.formatOptions.partitionScheme)
                    }
                KufusDropdownBox(
                    selectedLabel = targetLabel,
                    enabled = false,
                    expanded = false,
                    onExpandedChange = {},
                ) {}
            }
        }

        KufusCollapsibleRow(
            expanded = showAdvancedDrive,
            collapsedLabel = "Show advanced drive properties",
            expandedLabel = "Hide advanced drive properties",
            onToggle = onToggleAdvancedDrive,
        )

        if (showAdvancedDrive) {
            AdvancedDriveDetailsBox(drive = uiState.selectedDrive)
        }
    }
}

@Composable
private fun InlineNoticeMessage(uiState: FlasherUiState) {
    val palette = KufusTheme.palette
    val messagePair = buildInlineNoticePair(uiState)
    if (messagePair != null) {
        Text(
            text = messagePair.first,
            style = CaptionXs.copy(color = if (messagePair.second) palette.error else palette.onSurfaceVariant),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

private fun buildInlineNoticePair(uiState: FlasherUiState): Pair<String, Boolean>? {
    uiState.errorMessage?.let { return it to true }
    if (uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO) {
        return buildDiskOrIsoInlineNotice(uiState)
    }
    val formatBlock = uiState.formatBlockReason
    return if (uiState.selectedDrive != null && formatBlock != null) {
        formatBlock to true
    } else {
        null
    }
}

private fun buildDiskOrIsoInlineNotice(uiState: FlasherUiState): Pair<String, Boolean> {
    uiState.imageErrorMessage?.let { return it to true }
    val inspected = uiState.inspectedImage
    if (inspected != null && inspected.format == ImageFormat.WINDOWS_ISO) {
        return buildWindowsInlineNotice(uiState, inspected)
    }
    if (uiState.isImageOversizedForSelectedDrive) {
        val needed = formatByteSize(uiState.requiredSizeBytes ?: 0L)
        val cap = uiState.selectedDrive?.let { formatByteSize(it.sizeBytes) }.orEmpty()
        return "Image ($needed) exceeds selected drive capacity ($cap)." to true
    }
    return when {
        inspected == null ->
            "Drag & drop an ISO/IMG/GZ/XZ/ZST image here, or switch Boot selection to Non-bootable." to false
        inspected.format == ImageFormat.COMPRESSED_IMAGE ->
            "${formatImageSubtitle(inspected)} (decompressed on the fly)" to false
        else -> formatImageSubtitle(inspected) to false
    }
}

private fun buildWindowsInlineNotice(
    uiState: FlasherUiState,
    inspected: ImageInfo,
): Pair<String, Boolean> {
    val block = uiState.flashBlockReason
    if (uiState.selectedDrive != null && block != null) {
        return block to true
    }
    val meta = inspected.windowsMetadata
    val payload = formatByteSize(meta?.totalPayloadBytes ?: inspected.sizeBytes)
    val notice =
        when {
            uiState.formatOptions.filesystem == FilesystemType.NTFS && uiState.hostOs == HostOsKind.MACOS ->
                "Select FAT32 on macOS to prepare this Windows ISO ($payload)."
            uiState.formatOptions.filesystem == FilesystemType.NTFS ->
                "Windows ISO ($payload) • Dual-partition NTFS + UEFI:NTFS bootloader"
            meta?.isSplitRequiredForFat32 == true ->
                "Windows ISO ($payload) • FAT32 with pure-Kotlin WIM splitter (install.swm)"
            else ->
                "Windows ISO ($payload) • Single FAT32 UEFI bootable partition"
        }
    return notice to false
}

@Composable
private fun AdvancedDriveDetailsBox(drive: UsbDrive?) {
    val palette = KufusTheme.palette
    Surface(
        color = palette.surfaceContainerLow,
        shape = KufusControlShape,
        border = BorderStroke(1.dp, palette.border),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            if (drive == null) {
                Text("No external USB drive selected.", style = MonoDetail.copy(color = palette.outline))
            } else {
                val detailStyle = MonoDetail.copy(color = palette.onSurfaceVariant)
                Text("Device ID: ${drive.id} • Capacity: ${formatByteSize(drive.sizeBytes)}", style = detailStyle)
                val mounts = if (drive.mountPoints.isEmpty()) "None" else drive.mountPoints.joinToString(", ")
                Text("Serial: ${drive.serial ?: "Unavailable"} • Mounts: $mounts", style = detailStyle)
            }
        }
    }
}

private fun formatDriveDropdownLabel(drive: UsbDrive): String = "${drive.model} (${drive.id}) [${formatByteSize(drive.sizeBytes)}]"

private fun formatBootSelectionLabel(uiState: FlasherUiState): String =
    when (uiState.bootSelectionMode) {
        BootSelectionMode.NON_BOOTABLE -> "Non-bootable"
        BootSelectionMode.DISK_OR_ISO ->
            uiState.selectedImagePath?.let(::fileDisplayName) ?: "Disk or ISO image (Please select)"
    }

internal fun fileDisplayName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

internal fun formatImageSubtitle(image: ImageInfo): String {
    val formatLabel =
        when (image.format) {
            ImageFormat.HYBRID_ISO -> "Bootable ISO"
            ImageFormat.WINDOWS_ISO -> "Windows ISO"
            ImageFormat.RAW_IMAGE -> "Raw Disk Image"
            ImageFormat.COMPRESSED_IMAGE -> "Compressed (${image.compression.name})"
            ImageFormat.UNKNOWN -> "Unrecognized Image"
        }
    val sizePart = formatByteSize(image.sizeBytes)
    val hintBytes = image.uncompressedSizeHintBytes
    return if (hintBytes != null) "$formatLabel • $sizePart (~${formatByteSize(hintBytes)} uncompressed)" else "$formatLabel • $sizePart"
}
