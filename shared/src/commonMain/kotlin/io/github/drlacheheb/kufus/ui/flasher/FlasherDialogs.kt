package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drlacheheb.kufus.disk.format.core.filesystemDisplayName
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.format.core.targetSystemLabel
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.formatByteSize
import io.github.drlacheheb.kufus.ui.components.KufusActionButton
import io.github.drlacheheb.kufus.ui.components.KufusCheckboxRow
import io.github.drlacheheb.kufus.ui.components.KufusControlShape
import io.github.drlacheheb.kufus.ui.components.KufusFieldHeight
import io.github.drlacheheb.kufus.ui.components.KufusModalDialog
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.LabelSm
import io.github.drlacheheb.kufus.ui.theme.MonoCode
import io.github.drlacheheb.kufus.ui.theme.MonoDetail
import io.github.drlacheheb.kufus.ui.theme.UiLabelBold

@Composable
internal fun KufusDestructiveDriveDialog(
    title: String,
    subtitle: String,
    drive: UsbDrive,
    detailLines: String,
    ejectLabel: String,
    ejectChecked: Boolean,
    onEjectToggle: (Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    extraToggleLabel: String? = null,
    extraToggleChecked: Boolean = false,
    onExtraToggle: ((Boolean) -> Unit)? = null,
) {
    val palette = KufusTheme.palette
    KufusModalDialog(
        title = title,
        subtitle = subtitle,
        icon = Icons.Outlined.Warning,
        iconTint = palette.destructiveAccent,
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(
                text = "Cancel",
                enabled = true,
                onClick = onDismiss,
            )
            Button(
                onClick = onConfirm,
                shape = KufusControlShape,
                colors =
                    ButtonDefaults.buttonColors(
                        containerColor = palette.error,
                        contentColor = palette.onError,
                    ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
                modifier =
                    Modifier
                        .height(KufusFieldHeight)
                        .pointerHoverIcon(PointerIcon.Hand),
            ) {
                Text(
                    text = "OK",
                    style = UiLabelBold.copy(fontSize = 13.sp, color = palette.onError),
                )
            }
        },
    ) {
        Text(
            text = "ALL DATA ON THIS DEVICE WILL BE DESTROYED:",
            style = UiLabelBold.copy(fontSize = 12.sp, color = palette.error),
        )
        Surface(
            color = palette.errorContainer.copy(alpha = 0.35f),
            contentColor = palette.onErrorContainer,
            shape = KufusControlShape,
            border = BorderStroke(1.dp, palette.error.copy(alpha = 0.35f)),
        ) {
            Column(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                Text(
                    text = "${drive.model} (${formatByteSize(drive.sizeBytes)})",
                    style = UiLabelBold.copy(fontSize = 13.sp, color = palette.onErrorContainer),
                )
                val serialSuffix = drive.serial?.let { " • Serial: $it" }.orEmpty()
                Text(
                    text = "${drive.id}$serialSuffix",
                    style = MonoDetail.copy(color = palette.onErrorContainer.copy(alpha = 0.85f)),
                )
            }
        }
        Text(
            text = detailLines,
            style = MonoDetail.copy(color = palette.onSurfaceVariant, lineHeight = 17.sp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (extraToggleLabel != null && onExtraToggle != null) {
                KufusCheckboxRow(
                    label = extraToggleLabel,
                    checked = extraToggleChecked,
                    onCheckedChange = onExtraToggle,
                )
            }
            KufusCheckboxRow(
                label = ejectLabel,
                checked = ejectChecked,
                onCheckedChange = onEjectToggle,
            )
        }
    }
}

@Composable
internal fun ConfirmFormatDialog(
    drive: UsbDrive,
    formatOptions: FormatOptionsState,
    ejectAfterFormat: Boolean,
    onEjectToggle: (Boolean) -> Unit,
    onConfirmFormat: () -> Unit,
    onDismissConfirm: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val cleanLabel = sanitizeVolumeLabel(formatOptions.volumeLabel, formatOptions.filesystem).ifEmpty { "(Unlabeled)" }
    val wipeLabel = if (formatOptions.quickFormat) "Standard format" else "Full zero wipe + format"
    val details =
        buildString {
            append("Partition scheme: ${formatOptions.partitionScheme.name}")
            append(" (${targetSystemLabel(formatOptions.partitionScheme)})")
            append("\nFile system: ${filesystemDisplayName(formatOptions.filesystem)}")
            append(" • Cluster: ${formatClusterSizeLabel(formatOptions.clusterSizeBytes)}")
            append("\nVolume label: $cleanLabel • Mode: $wipeLabel")
        }

    KufusDestructiveDriveDialog(
        title = "WARNING — Format USB Drive",
        subtitle = "Permanent partition & filesystem overwrite",
        drive = drive,
        detailLines = details,
        ejectLabel = "Safe-eject USB drive after formatting",
        ejectChecked = ejectAfterFormat,
        onEjectToggle = onEjectToggle,
        onConfirm = onConfirmFormat,
        onDismiss = onDismissConfirm,
        modifier = modifier,
    )
}

@Composable
internal fun RufusImageInfoDialog(
    image: ImageInfo,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    val fileName = image.path.substringAfterLast('/').substringAfterLast('\\')
    val formatLabel =
        when (image.format) {
            ImageFormat.HYBRID_ISO -> "Bootable ISO"
            ImageFormat.WINDOWS_ISO -> "Windows ISO (UDF/WIM)"
            ImageFormat.RAW_IMAGE -> "Raw Disk Image"
            ImageFormat.COMPRESSED_IMAGE -> "Compressed (${image.compression.name})"
            ImageFormat.UNKNOWN -> "Unrecognized Image"
        }
    KufusModalDialog(
        title = fileName,
        subtitle = "Image Inspection",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(text = "OK", enabled = true, emphasized = true, onClick = onDismiss)
        },
    ) {
        Surface(
            color = palette.surfaceDim,
            shape = KufusControlShape,
            border = BorderStroke(1.dp, palette.outlineVariant),
            modifier = Modifier.fillMaxWidth(),
        ) {
            SelectionContainer {
                Column(
                    modifier = Modifier.padding(10.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    val detailStyle = MonoDetail.copy(color = palette.onSurfaceVariant)
                    Text("Path: ${image.path}", style = detailStyle)
                    Text("Format: $formatLabel", style = detailStyle)
                    Text("File size: ${formatByteSize(image.sizeBytes)}", style = detailStyle)
                    image.uncompressedSizeHintBytes?.let { hint ->
                        Text("Uncompressed size: ~${formatByteSize(hint)}", style = detailStyle)
                    }
                    image.windowsMetadata?.let { meta ->
                        val splitState =
                            when {
                                !meta.isSplitRequiredForFat32 -> "Not needed (<= 4 GiB)"
                                meta.isWimSplittable -> "Required on FAT32 (pure-Kotlin WIM splitter ready)"
                                else -> "Required on FAT32, unsupported (use NTFS)"
                            }
                        Text("ISO label: ${meta.volumeLabel.ifBlank { "(None)" }}", style = detailStyle)
                        Text("Install image: ${meta.installImagePath} (${formatByteSize(meta.installImageSizeBytes)})", style = detailStyle)
                        Text("Total payload: ${formatByteSize(meta.totalPayloadBytes)}", style = detailStyle)
                        Text("FAT32 split: $splitState", style = detailStyle)
                    }
                }
            }
        }
    }
}

@Composable
internal fun RufusLogDialog(
    entries: List<String>,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    KufusModalDialog(
        title = "Kufus Log",
        subtitle = "Session worker & formatting output",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(text = "Close", enabled = true, onClick = onDismiss)
        },
    ) {
        Surface(
            color = palette.inputBackground,
            shape = KufusControlShape,
            border = BorderStroke(1.dp, palette.outlineVariant),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 160.dp, max = 300.dp),
        ) {
            val logText =
                if (entries.isEmpty()) {
                    "No flash or format operations recorded in this session yet."
                } else {
                    entries.joinToString("\n")
                }
            Box(
                modifier =
                    Modifier
                        .verticalScroll(rememberScrollState())
                        .padding(10.dp),
            ) {
                SelectionContainer {
                    Text(
                        text = logText,
                        style = MonoCode.copy(fontSize = 11.sp, lineHeight = 16.sp, color = palette.onSurface),
                    )
                }
            }
        }
    }
}

@Composable
internal fun RufusAboutDialog(
    platformName: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    KufusModalDialog(
        title = "About Kufus",
        subtitle = "Kotlin USB Formatting Utility Suite (v1.0.0)",
        onDismiss = onDismiss,
        modifier = modifier,
        footer = {
            KufusActionButton(text = "OK", enabled = true, emphasized = true, onClick = onDismiss)
        },
    ) {
        if (platformName.isNotBlank()) {
            Text("Runtime: $platformName", style = MonoDetail.copy(color = palette.onSurfaceVariant))
        }
        Text(
            text = "Supports raw/ISO/GZ/XZ/ZST image flashing, SHA-256 verification, GPT/MBR partitioning, large-FAT32, exFAT, NTFS, and ext4.",
            style = LabelSm.copy(fontWeight = FontWeight.Normal, lineHeight = 18.sp, color = palette.onSurfaceVariant),
        )
    }
}
