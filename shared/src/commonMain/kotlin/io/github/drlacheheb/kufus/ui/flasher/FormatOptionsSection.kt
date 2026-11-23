package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.ui.components.KufusCollapsibleRow
import io.github.drlacheheb.kufus.ui.components.KufusCompactTextField
import io.github.drlacheheb.kufus.ui.components.KufusDropdownBox
import io.github.drlacheheb.kufus.ui.components.KufusDropdownMenuItem
import io.github.drlacheheb.kufus.ui.components.KufusFieldLabel
import io.github.drlacheheb.kufus.ui.components.KufusSectionHeading
import io.github.drlacheheb.kufus.ui.components.kufusCheckboxColors
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

private const val THIRTY_TWO_GIB_BYTES = 34_359_738_368L

@Composable
internal fun RufusFormatOptionsFields(
    uiState: FlasherUiState,
    formatFieldsEnabled: Boolean,
    showAdvancedFormat: Boolean,
    onToggleAdvancedFormat: () -> Unit,
    onUpdateVolumeLabel: (String) -> Unit,
    onSelectFilesystem: (FilesystemType) -> Unit,
    onSelectClusterSize: (Int?) -> Unit,
    onToggleQuickFormat: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    val opts = uiState.formatOptions
    val selectedDrive = uiState.selectedDrive
    val isLargeFat32 = selectedDrive != null && selectedDrive.sizeBytes > THIRTY_TWO_GIB_BYTES
    val fsLabel =
        if (uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && uiState.inspectedImage == null) {
            "FAT32 (Default)"
        } else {
            formatFilesystemDropdownLabel(opts.filesystem, isLargeFat32)
        }
    var fsMenuOpen by remember { mutableStateOf(false) }
    var clusterMenuOpen by remember { mutableStateOf(false) }

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        KufusSectionHeading(title = "Format Options")

        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            KufusFieldLabel(text = "Volume label")
            KufusCompactTextField(
                value = if (formatFieldsEnabled) opts.volumeLabel else "",
                onValueChange = onUpdateVolumeLabel,
                enabled = formatFieldsEnabled,
                placeholder = if (formatFieldsEnabled) "KUFUS" else "Controlled by image",
            )
            if (formatFieldsEnabled) {
                val sanitized = sanitizeVolumeLabel(opts.volumeLabel, opts.filesystem)
                if (sanitized != opts.volumeLabel.trim()) {
                    Text(
                        text = "Formatted label: ${sanitized.ifEmpty { "(Unlabeled)" }}",
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onSurfaceVariant,
                    )
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KufusFieldLabel(text = "File system")
                KufusDropdownBox(
                    selectedLabel = fsLabel,
                    enabled = formatFieldsEnabled,
                    expanded = fsMenuOpen,
                    onExpandedChange = { fsMenuOpen = it },
                ) {
                    uiState.availableFilesystems.forEach { fs ->
                        KufusDropdownMenuItem(
                            text = formatFilesystemDropdownLabel(fs, isLargeFat32),
                            onClick = {
                                fsMenuOpen = false
                                onSelectFilesystem(fs)
                            },
                        )
                    }
                }
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                KufusFieldLabel(text = "Cluster size")
                KufusDropdownBox(
                    selectedLabel = formatClusterSizeLabel(opts.clusterSizeBytes),
                    enabled = formatFieldsEnabled,
                    expanded = clusterMenuOpen,
                    onExpandedChange = { clusterMenuOpen = it },
                ) {
                    uiState.availableClusterSizes.forEach { clusterBytes ->
                        KufusDropdownMenuItem(
                            text = formatClusterSizeLabel(clusterBytes),
                            onClick = {
                                clusterMenuOpen = false
                                onSelectClusterSize(clusterBytes)
                            },
                        )
                    }
                }
            }
        }

        KufusCollapsibleRow(
            expanded = showAdvancedFormat,
            collapsedLabel = "Show advanced format options",
            expandedLabel = "Hide advanced format options",
            onToggle = onToggleAdvancedFormat,
        )

        if (showAdvancedFormat) {
            Column(
                modifier = Modifier.padding(start = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .pointerHoverIcon(if (formatFieldsEnabled) PointerIcon.Hand else PointerIcon.Default)
                            .clickable(enabled = formatFieldsEnabled) {
                                onToggleQuickFormat(!opts.quickFormat)
                            },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = opts.quickFormat,
                        onCheckedChange = onToggleQuickFormat,
                        enabled = formatFieldsEnabled,
                        colors = kufusCheckboxColors(),
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Quick format",
                        fontSize = 13.sp,
                        color = if (formatFieldsEnabled) palette.onSurface else palette.outline,
                    )
                }
                if (formatFieldsEnabled && !opts.quickFormat) {
                    Text(
                        text = "Full wipe: every USB sector will be overwritten with zeroes before formatting.",
                        fontSize = 11.sp,
                        color = palette.tertiary,
                    )
                }
            }
        }
    }
}

internal fun formatFilesystemDropdownLabel(
    filesystem: FilesystemType,
    isLargeDriveForFat32: Boolean,
): String =
    when (filesystem) {
        FilesystemType.FAT32 -> if (isLargeDriveForFat32) "Large FAT32 (Default)" else "FAT32 (Default)"
        FilesystemType.EXFAT -> "exFAT"
        FilesystemType.NTFS -> "NTFS"
        FilesystemType.EXT4 -> "ext4"
    }

internal fun formatClusterSizeLabel(clusterSizeBytes: Int?): String =
    when {
        clusterSizeBytes == null -> "Default"
        clusterSizeBytes >= 1024 -> "${clusterSizeBytes / 1024} kilobytes"
        else -> "$clusterSizeBytes bytes"
    }
