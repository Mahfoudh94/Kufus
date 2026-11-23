package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.filesystemDisplayName
import io.github.drlacheheb.kufus.disk.formatByteSize
import io.github.drlacheheb.kufus.disk.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.targetSystemLabel
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.LabelXs
import io.github.drlacheheb.kufus.ui.theme.MonoCode
import io.github.drlacheheb.kufus.ui.theme.MonoDetail
import io.github.drlacheheb.kufus.ui.theme.UiLabelBold

private const val THIRTY_TWO_GIB_BYTES = 34_359_738_368L
internal val KufusFieldHeight = 36.dp
internal val KufusControlShape = RoundedCornerShape(4.dp)

@Composable
internal fun KufusSectionHeading(
    title: String,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            text = title,
            fontSize = 15.sp,
            fontWeight = FontWeight.SemiBold,
            color = palette.onSurface,
        )
        HorizontalDivider(
            modifier = Modifier.weight(1f),
            thickness = 1.dp,
            color = palette.border,
        )
    }
}

@Composable
internal fun KufusFieldLabel(
    text: String,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    Text(
        text = text,
        style = LabelXs.copy(color = palette.onSurfaceVariant),
        modifier = modifier,
    )
}

@Composable
internal fun KufusDropdownBox(
    selectedLabel: String,
    enabled: Boolean,
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    dropdownContent: @Composable () -> Unit,
) {
    val palette = KufusTheme.palette
    val bgColor = if (enabled) palette.inputBackground else palette.surfaceContainerLow
    val borderColor =
        when {
            !enabled -> palette.border.copy(alpha = 0.55f)
            expanded -> palette.primary
            else -> palette.border
        }
    val textColor = if (enabled) palette.onSurface else palette.outline
    val menuColor = if (palette.isDark) palette.surfaceContainerLow else palette.surfaceContainer

    Box(modifier = modifier) {
        Surface(
            color = bgColor,
            shape = KufusControlShape,
            border = BorderStroke(1.dp, borderColor),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .height(KufusFieldHeight)
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                    .clickable(enabled = enabled) { onExpandedChange(true) },
        ) {
            Row(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(
                    text = selectedLabel,
                    color = textColor,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Icon(
                    imageVector = Icons.Outlined.ArrowDropDown,
                    contentDescription = null,
                    tint = if (enabled) palette.onSurfaceVariant else palette.outline,
                    modifier = Modifier.size(18.dp),
                )
            }
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            shape = KufusControlShape,
            containerColor = menuColor,
            border = BorderStroke(1.dp, palette.border),
        ) {
            dropdownContent()
        }
    }
}

@Composable
internal fun KufusDropdownMenuItem(
    text: String,
    onClick: () -> Unit,
) {
    val palette = KufusTheme.palette
    DropdownMenuItem(
        text = {
            Text(
                text = text,
                fontSize = 13.sp,
                color = palette.onSurface,
            )
        },
        onClick = onClick,
        colors =
            MenuDefaults.itemColors(
                textColor = palette.onSurface,
                leadingIconColor = palette.onSurfaceVariant,
                trailingIconColor = palette.onSurfaceVariant,
            ),
        modifier = Modifier.pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
internal fun KufusCompactTextField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    val interactionSource = remember { MutableInteractionSource() }
    val isFocused by interactionSource.collectIsFocusedAsState()
    val bgColor = if (enabled) palette.inputBackground else palette.surfaceContainerLow
    val borderColor =
        when {
            !enabled -> palette.border.copy(alpha = 0.55f)
            isFocused -> palette.primary
            else -> palette.border
        }
    val textColor = if (enabled) palette.onSurface else palette.outline

    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = true,
        textStyle =
            TextStyle(
                fontFamily = FontFamily.Monospace,
                fontSize = 13.sp,
                fontWeight = FontWeight.Normal,
                color = textColor,
            ),
        cursorBrush = SolidColor(palette.primary),
        interactionSource = interactionSource,
        modifier =
            modifier
                .fillMaxWidth()
                .height(KufusFieldHeight)
                .pointerHoverIcon(if (enabled) PointerIcon.Text else PointerIcon.Default),
        decorationBox = { innerTextField ->
            Box(
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(KufusFieldHeight)
                        .background(bgColor, KufusControlShape)
                        .border(1.dp, borderColor, KufusControlShape)
                        .padding(horizontal = 10.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                if (value.isEmpty()) {
                    Text(
                        text = placeholder,
                        color = palette.onSurfaceVariant.copy(alpha = 0.45f),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 13.sp,
                        maxLines = 1,
                    )
                }
                innerTextField()
            }
        },
    )
}

@Composable
internal fun KufusActionButton(
    text: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    emphasized: Boolean = false,
    destructive: Boolean = false,
) {
    val palette = KufusTheme.palette
    if (emphasized && enabled) {
        Button(
            onClick = onClick,
            enabled = true,
            shape = KufusControlShape,
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = palette.primary,
                    contentColor = palette.onPrimary,
                ),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.dp),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier =
                modifier
                    .height(KufusFieldHeight)
                    .pointerHoverIcon(PointerIcon.Hand),
        ) {
            Text(
                text = text,
                style = UiLabelBold.copy(fontSize = 13.sp, color = palette.onPrimary),
                maxLines = 1,
            )
        }
    } else {
        val borderColor =
            when {
                !enabled -> palette.border.copy(alpha = 0.55f)
                destructive -> palette.error.copy(alpha = 0.45f)
                else -> palette.border
            }
        val containerColor =
            when {
                !enabled -> palette.surfaceContainerLow
                destructive -> palette.errorContainer.copy(alpha = 0.25f)
                else -> Color.Transparent
            }
        val contentColor =
            when {
                !enabled -> palette.outline.copy(alpha = 0.55f)
                destructive -> palette.error
                else -> palette.onSurface
            }

        OutlinedButton(
            onClick = onClick,
            enabled = enabled,
            shape = KufusControlShape,
            border = BorderStroke(1.dp, borderColor),
            colors =
                ButtonDefaults.outlinedButtonColors(
                    containerColor = containerColor,
                    contentColor = contentColor,
                    disabledContainerColor = palette.surfaceContainerLow,
                    disabledContentColor = palette.outline.copy(alpha = 0.55f),
                ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier =
                modifier
                    .height(KufusFieldHeight)
                    .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
        ) {
            Text(
                text = text,
                style = UiLabelBold.copy(fontSize = 13.sp, color = contentColor),
                maxLines = 1,
            )
        }
    }
}

@Composable
internal fun KufusCollapsibleRow(
    expanded: Boolean,
    collapsedLabel: String,
    expandedLabel: String,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = KufusTheme.palette
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable(onClick = onToggle)
                .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(
            imageVector = if (expanded) Icons.Outlined.KeyboardArrowUp else Icons.Outlined.KeyboardArrowDown,
            contentDescription = null,
            tint = palette.outline,
            modifier = Modifier.size(18.dp),
        )
        Text(
            text = if (expanded) expandedLabel else collapsedLabel,
            fontSize = 12.sp,
            color = palette.onSurfaceVariant,
        )
    }
}

@Composable
internal fun kufusCheckboxColors() =
    with(KufusTheme.palette) {
        CheckboxDefaults.colors(
            checkedColor = primary,
            checkmarkColor = onPrimary,
            uncheckedColor = outline,
            disabledCheckedColor = outlineVariant,
            disabledUncheckedColor = border,
        )
    }

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

@Composable
internal fun KufusModalDialog(
    title: String,
    onDismiss: () -> Unit,
    footer: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    icon: ImageVector = Icons.Outlined.Info,
    iconTint: Color? = null,
    body: @Composable ColumnScope.() -> Unit,
) {
    val palette = KufusTheme.palette
    val resolvedIconTint = iconTint ?: palette.primary

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = palette.surfaceContainer,
            border = BorderStroke(1.dp, palette.border),
            modifier = modifier.width(460.dp),
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = resolvedIconTint.copy(alpha = 0.15f),
                        border = BorderStroke(1.dp, resolvedIconTint.copy(alpha = 0.30f)),
                        modifier = Modifier.size(36.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                imageVector = icon,
                                contentDescription = null,
                                tint = resolvedIconTint,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }

                    Column {
                        Text(
                            text = title,
                            style = UiLabelBold.copy(fontSize = 15.sp, color = palette.onSurface),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (!subtitle.isNullOrBlank()) {
                            Text(
                                text = subtitle,
                                fontSize = 12.sp,
                                color = palette.onSurfaceVariant,
                            )
                        }
                    }
                }

                body()

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    footer()
                }
            }
        }
    }
}

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
            style = MonoDetail.copy(color = palette.onSurfaceVariant),
            lineHeight = 17.sp,
        )
        if (extraToggleLabel != null && onExtraToggle != null) {
            DialogCheckboxRow(
                label = extraToggleLabel,
                checked = extraToggleChecked,
                onCheckedChange = onExtraToggle,
            )
        }
        DialogCheckboxRow(
            label = ejectLabel,
            checked = ejectChecked,
            onCheckedChange = onEjectToggle,
        )
    }
}

@Composable
private fun DialogCheckboxRow(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    val palette = KufusTheme.palette
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .pointerHoverIcon(PointerIcon.Hand)
                .clickable { onCheckedChange(!checked) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = kufusCheckboxColors(),
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = label, fontSize = 12.sp, color = palette.onSurfaceVariant)
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
    val wipeLabel = if (formatOptions.quickFormat) "Quick format" else "Full zero wipe + format"
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
                Text(
                    text = logText,
                    style = MonoCode.copy(fontSize = 11.sp, lineHeight = 16.sp, color = palette.onSurface),
                )
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
            fontSize = 12.sp,
            color = palette.onSurfaceVariant,
            lineHeight = 18.sp,
        )
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
