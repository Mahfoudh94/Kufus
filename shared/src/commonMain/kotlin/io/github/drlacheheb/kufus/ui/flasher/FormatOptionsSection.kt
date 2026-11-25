package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.ui.components.KufusCheckboxRow
import io.github.drlacheheb.kufus.ui.components.KufusCollapsibleRow
import io.github.drlacheheb.kufus.ui.components.KufusCompactTextField
import io.github.drlacheheb.kufus.ui.components.KufusDropdownBox
import io.github.drlacheheb.kufus.ui.components.KufusDropdownMenuItem
import io.github.drlacheheb.kufus.ui.components.KufusFieldLabel
import io.github.drlacheheb.kufus.ui.components.KufusSectionHeading
import io.github.drlacheheb.kufus.ui.theme.CaptionXs
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.MonoDetail

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
                placeholder = if (formatFieldsEnabled) "Volume label (optional)" else "Controlled by image",
            )
            if (formatFieldsEnabled) {
                val sanitized = sanitizeVolumeLabel(opts.volumeLabel, opts.filesystem)
                if (sanitized != opts.volumeLabel.trim()) {
                    Text(
                        text = "Formatted label: ${sanitized.ifEmpty { "(Unlabeled)" }}",
                        style = MonoDetail.copy(color = palette.onSurfaceVariant),
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
                            selected = fs == opts.filesystem,
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
                            selected = clusterBytes == opts.clusterSizeBytes,
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
            val fullZeroWipe = !opts.quickFormat
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                KufusCheckboxRow(
                    label = "Full zero wipe before formatting (slow)",
                    checked = fullZeroWipe,
                    onCheckedChange = { enabled -> onToggleQuickFormat(!enabled) },
                    enabled = formatFieldsEnabled,
                )
                if (formatFieldsEnabled && fullZeroWipe) {
                    Text(
                        text = "Overwrites every USB sector with zeroes so previous data cannot be recovered.",
                        style = CaptionXs.copy(color = palette.tertiary),
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
