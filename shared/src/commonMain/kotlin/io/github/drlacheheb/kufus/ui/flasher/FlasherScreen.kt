package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeContentPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.arkivanov.decompose.extensions.compose.subscribeAsState
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.formatByteSize
import io.github.drlacheheb.kufus.disk.targetSystemLabel
import io.github.drlacheheb.kufus.ui.components.KufusTopBar
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.MonoDetail
import kotlin.math.roundToInt

private const val PERCENT_SCALE = 100
private const val SECONDS_PER_HOUR_TIMER = 3600L
private const val SECONDS_PER_MIN_TIMER = 60L

@Immutable
data class FlasherActions(
    val onBrowseImage: () -> Unit = {},
    val onSelectImagePath: (String) -> Unit = {},
    val onClearImage: () -> Unit = {},
    val onRefreshDrives: () -> Unit = {},
    val onSelectDrive: (String) -> Unit = {},
    val onSelectBootSelectionMode: (BootSelectionMode) -> Unit = {},
    val onSelectPartitionScheme: (PartitionScheme) -> Unit = {},
    val onSelectFilesystem: (io.github.drlacheheb.kufus.disk.FilesystemType) -> Unit = {},
    val onUpdateVolumeLabel: (String) -> Unit = {},
    val onSelectClusterSize: (Int?) -> Unit = {},
    val onToggleQuickFormat: (Boolean) -> Unit = {},
    val onFlashClick: () -> Unit = {},
    val onVerifyToggle: (Boolean) -> Unit = {},
    val onEjectToggle: (Boolean) -> Unit = {},
    val onConfirmFlash: () -> Unit = {},
    val onFormatClick: () -> Unit = {},
    val onFormatEjectToggle: (Boolean) -> Unit = {},
    val onConfirmFormat: () -> Unit = {},
    val onDismissConfirm: () -> Unit = {},
    val onCancelFlash: () -> Unit = {},
    val onDismissResult: () -> Unit = {},
    val onToggleTheme: () -> Unit = {},
    val onCloseApp: () -> Unit = {},
)

@Composable
fun FlasherScreen(
    component: FlasherComponent,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onCloseApp: () -> Unit = {},
) {
    val uiState by component.state.subscribeAsState()
    val actions =
        remember(component, onToggleTheme, onCloseApp) {
            FlasherActions(
                onBrowseImage = component::onBrowseImage,
                onSelectImagePath = component::onSelectImagePath,
                onClearImage = component::onClearImage,
                onRefreshDrives = component::onRefreshDrives,
                onSelectDrive = component::onSelectDrive,
                onSelectBootSelectionMode = component::onSelectBootSelectionMode,
                onSelectPartitionScheme = component::onSelectPartitionScheme,
                onSelectFilesystem = component::onSelectFilesystem,
                onUpdateVolumeLabel = component::onUpdateVolumeLabel,
                onSelectClusterSize = component::onSelectClusterSize,
                onToggleQuickFormat = component::onToggleQuickFormat,
                onFlashClick = component::onRequestFlash,
                onVerifyToggle = component::onToggleVerifyAfterWrite,
                onEjectToggle = component::onToggleEjectAfterWrite,
                onConfirmFlash = component::onConfirmFlash,
                onFormatClick = component::onRequestFormat,
                onFormatEjectToggle = component::onToggleEjectAfterFormat,
                onConfirmFormat = component::onConfirmFormat,
                onDismissConfirm = component::onDismissConfirmation,
                onCancelFlash = component::onCancelFlash,
                onDismissResult = component::onDismissFlashResult,
                onToggleTheme = onToggleTheme,
                onCloseApp = onCloseApp,
            )
        }

    FlasherContent(
        uiState = uiState,
        actions = actions,
        isDarkTheme = isDarkTheme,
        modifier = modifier,
    )
}

@Composable
fun FlasherContent(
    uiState: FlasherUiState,
    actions: FlasherActions,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
) {
    val palette = KufusTheme.palette
    val interactive = !uiState.isFlashing
    var isDragHover by remember { mutableStateOf(false) }
    var showAdvancedDrive by remember { mutableStateOf(false) }
    var showAdvancedFormat by remember { mutableStateOf(false) }
    var showImageInfoDialog by remember { mutableStateOf(false) }
    var showLogDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    val formatFieldsEnabled = interactive && uiState.bootSelectionMode == BootSelectionMode.NON_BOOTABLE

    Surface(
        color = palette.background,
        contentColor = palette.onSurface,
        modifier =
            modifier
                .fillMaxSize()
                .externalFileDropTarget(
                    enabled = interactive && !uiState.isInspectingImage,
                    onDragHoverChange = { isDragHover = it },
                    onFileDrop = actions.onSelectImagePath,
                ),
        border = if (isDragHover) BorderStroke(2.dp, palette.primary) else null,
    ) {
        Column(
            modifier =
                Modifier
                    .safeContentPadding()
                    .fillMaxSize(),
        ) {
            KufusTopBar(
                title = "Kufus",
                subtitle = "v1.0.0",
            )

            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                RufusDrivePropertiesSection(
                    uiState = uiState,
                    interactive = interactive,
                    formatFieldsEnabled = formatFieldsEnabled,
                    showAdvancedDrive = showAdvancedDrive,
                    onToggleAdvancedDrive = { showAdvancedDrive = !showAdvancedDrive },
                    onShowImageInfo = { showImageInfoDialog = true },
                    actions = actions,
                )

                RufusFormatOptionsFields(
                    uiState = uiState,
                    formatFieldsEnabled = formatFieldsEnabled,
                    showAdvancedFormat = showAdvancedFormat,
                    onToggleAdvancedFormat = { showAdvancedFormat = !showAdvancedFormat },
                    onUpdateVolumeLabel = actions.onUpdateVolumeLabel,
                    onSelectFilesystem = actions.onSelectFilesystem,
                    onSelectClusterSize = actions.onSelectClusterSize,
                    onToggleQuickFormat = actions.onToggleQuickFormat,
                )

                RufusStatusSection(
                    uiState = uiState,
                    isDarkTheme = isDarkTheme,
                    onShowAbout = { showAboutDialog = true },
                    onShowLog = { showLogDialog = true },
                    actions = actions,
                )
            }

            KufusBottomStatusStrip(
                driveCount = uiState.drives.size,
                isScanning = uiState.isScanning,
                lastElapsedMs = uiState.lastElapsedMs,
            )
        }
    }

    FlasherModalDialogs(
        uiState = uiState,
        showImageInfoDialog = showImageInfoDialog,
        showLogDialog = showLogDialog,
        showAboutDialog = showAboutDialog,
        onDismissImageInfo = { showImageInfoDialog = false },
        onDismissLog = { showLogDialog = false },
        onDismissAbout = { showAboutDialog = false },
        actions = actions,
    )
}

@Composable
private fun RufusDrivePropertiesSection(
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
                horizontalArrangement = Arrangement.spacedBy(8.dp),
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
                horizontalArrangement = Arrangement.spacedBy(6.dp),
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
                        onClick = {
                            bootMenuOpen = false
                            actions.onSelectBootSelectionMode(BootSelectionMode.NON_BOOTABLE)
                        },
                    )
                    KufusDropdownMenuItem(
                        text = "Disk or ISO image",
                        onClick = {
                            bootMenuOpen = false
                            actions.onSelectBootSelectionMode(BootSelectionMode.DISK_OR_ISO)
                        },
                    )
                }

                if (uiState.isInspectingImage) {
                    CircularProgressIndicator(
                        color = palette.primary,
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                    )
                } else {
                    KufusSquareIconButton(
                        icon = Icons.Outlined.CheckCircle,
                        contentDescription = "Inspect image info",
                        enabled = uiState.inspectedImage != null,
                        tint = palette.primary,
                        onClick = onShowImageInfo,
                    )
                }

                KufusActionButton(
                    text = "SELECT",
                    enabled = interactive && !uiState.isInspectingImage,
                    emphasized = uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && uiState.inspectedImage == null,
                    onClick = actions.onBrowseImage,
                )

                if (uiState.selectedImagePath != null) {
                    KufusSquareIconButton(
                        icon = Icons.Outlined.Close,
                        contentDescription = "Clear image",
                        enabled = interactive,
                        onClick = actions.onClearImage,
                    )
                }
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
                KufusDropdownBox(
                    selectedLabel = targetSystemLabel(uiState.formatOptions.partitionScheme),
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
    val globalError = uiState.errorMessage
    val imageError = uiState.imageErrorMessage
    val inspected = uiState.inspectedImage
    val formatBlock = uiState.formatBlockReason
    val messagePair: Pair<String, Boolean>? =
        when {
            globalError != null -> globalError to true
            uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && imageError != null -> imageError to true
            uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && uiState.isImageOversizedForSelectedDrive -> {
                val needed = formatByteSize(uiState.requiredSizeBytes ?: 0L)
                val cap = uiState.selectedDrive?.let { formatByteSize(it.sizeBytes) }.orEmpty()
                "Image ($needed) exceeds selected drive capacity ($cap)." to true
            }
            uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO && inspected != null -> {
                when {
                    inspected.format == ImageFormat.WINDOWS_ISO ->
                        "Windows ISO detected — UEFI/NTFS preparation is scheduled for Milestone 4." to true
                    inspected.format == ImageFormat.COMPRESSED_IMAGE ->
                        "${formatImageSubtitle(inspected)} (decompressed on the fly)" to false
                    else -> formatImageSubtitle(inspected) to false
                }
            }
            uiState.bootSelectionMode == BootSelectionMode.NON_BOOTABLE &&
                uiState.selectedDrive != null &&
                formatBlock != null -> formatBlock to true
            uiState.bootSelectionMode == BootSelectionMode.DISK_OR_ISO ->
                "Drag & drop an ISO/IMG/GZ/XZ/ZST image here, or switch Boot selection to Non-bootable." to false
            else -> null
        }

    if (messagePair != null) {
        Text(
            text = messagePair.first,
            fontSize = 11.sp,
            color = if (messagePair.second) palette.error else palette.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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

@Composable
private fun RufusStatusSection(
    uiState: FlasherUiState,
    isDarkTheme: Boolean,
    onShowAbout: () -> Unit,
    onShowLog: () -> Unit,
    actions: FlasherActions,
) {
    val palette = KufusTheme.palette
    val startEnabled = if (uiState.isFormattingMode) uiState.canFormat else uiState.canFlash
    val startAction = if (uiState.isFormattingMode) actions.onFormatClick else actions.onFlashClick

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        KufusSectionHeading(title = "Status")

        RufusStatusProgressBar(uiState = uiState, startEnabled = startEnabled)

        val statusDetail = buildStatusDetailLine(uiState)
        if (statusDetail != null) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = statusDetail.first,
                    fontSize = 12.sp,
                    color = if (statusDetail.second) palette.error else palette.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                if (uiState.flashState is FlashOperationState.Completed || uiState.flashState is FlashOperationState.Failed) {
                    TextButton(
                        onClick = actions.onDismissResult,
                        modifier =
                            Modifier
                                .height(28.dp)
                                .pointerHoverIcon(PointerIcon.Hand),
                    ) {
                        Text("Dismiss", fontSize = 12.sp, color = palette.primary)
                    }
                }
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                KufusSquareIconButton(
                    icon = Icons.Outlined.Info,
                    contentDescription = "About Kufus",
                    enabled = true,
                    tint = palette.primary,
                    onClick = onShowAbout,
                )
                KufusSquareIconButton(
                    icon = if (isDarkTheme) Icons.Outlined.LightMode else Icons.Outlined.DarkMode,
                    contentDescription = if (isDarkTheme) "Switch to light mode" else "Switch to dark mode",
                    enabled = true,
                    tint = palette.primary,
                    onClick = actions.onToggleTheme,
                )
                KufusSquareIconButton(
                    icon = Icons.AutoMirrored.Outlined.Article,
                    contentDescription = "Show the log",
                    enabled = true,
                    tint = palette.primary,
                    onClick = onShowLog,
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KufusActionButton(
                    text = "START",
                    enabled = startEnabled && !uiState.isFlashing,
                    emphasized = startEnabled && !uiState.isFlashing,
                    onClick = startAction,
                    modifier = Modifier.width(96.dp),
                )
                if (uiState.isFlashing) {
                    KufusActionButton(
                        text = "CANCEL",
                        enabled = true,
                        destructive = true,
                        onClick = actions.onCancelFlash,
                        modifier = Modifier.width(96.dp),
                    )
                } else {
                    KufusActionButton(
                        text = "CLOSE",
                        enabled = true,
                        onClick = actions.onCloseApp,
                        modifier = Modifier.width(96.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun RufusStatusProgressBar(
    uiState: FlasherUiState,
    startEnabled: Boolean,
) {
    val palette = KufusTheme.palette
    val flashState = uiState.flashState
    when (flashState) {
        is FlashOperationState.Flashing -> {
            val indeterminate =
                flashState.phase == FlashPhase.UNMOUNTING ||
                    flashState.phase == FlashPhase.PARTITIONING ||
                    flashState.phase == FlashPhase.EJECTING
            val fraction =
                if (indeterminate) null else calculateProgressFraction(flashState.bytesProcessed, flashState.totalBytes)
            Surface(
                color = palette.inputBackground,
                shape = KufusControlShape,
                border = BorderStroke(1.dp, palette.border),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    if (fraction != null) {
                        Box(
                            modifier =
                                Modifier
                                    .fillMaxWidth(fraction.coerceIn(0f, 1f))
                                    .fillMaxHeight()
                                    .align(Alignment.CenterStart)
                                    .background(palette.secondaryContainer),
                        )
                        val pct = (fraction * PERCENT_SCALE).roundToInt()
                        Text(
                            text = "${flashPhaseLabel(flashState.phase)} ($pct%)",
                            color = palette.onSecondaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    } else {
                        LinearProgressIndicator(
                            color = palette.secondaryContainer,
                            trackColor = palette.inputBackground,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Text(
                            text = flashPhaseLabel(flashState.phase),
                            color = palette.onSecondaryContainer,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                }
            }
        }
        else -> {
            val barColor: Color
            val borderColor: Color
            val textColor: Color
            val label: String
            when {
                flashState is FlashOperationState.Completed -> {
                    barColor = palette.secondaryContainer
                    borderColor = palette.secondary.copy(alpha = 0.55f)
                    textColor = palette.onSecondaryContainer
                    label = "COMPLETED"
                }
                flashState is FlashOperationState.Failed -> {
                    barColor = palette.errorContainer
                    borderColor = palette.error.copy(alpha = 0.45f)
                    textColor = palette.onErrorContainer
                    label = "FAILED"
                }
                startEnabled -> {
                    barColor = palette.secondaryContainer
                    borderColor = palette.secondary.copy(alpha = 0.55f)
                    textColor = palette.onSecondaryContainer
                    label = "READY"
                }
                else -> {
                    barColor = palette.surfaceContainerLowest
                    borderColor = palette.border
                    textColor = palette.outline
                    label =
                        when {
                            uiState.selectedDrive == null -> "NO USB DEVICE"
                            !uiState.isFormattingMode && uiState.inspectedImage == null -> "SELECT AN IMAGE"
                            !uiState.isFormattingMode && uiState.isInspectingImage -> "INSPECTING IMAGE..."
                            else -> "NOT READY"
                        }
                }
            }
            Surface(
                color = barColor,
                shape = KufusControlShape,
                border = BorderStroke(1.dp, borderColor),
                modifier =
                    Modifier
                        .fillMaxWidth()
                        .height(32.dp),
            ) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = label,
                        color = textColor,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        letterSpacing = 0.4.sp,
                    )
                }
            }
        }
    }
}

@Composable
private fun KufusBottomStatusStrip(
    driveCount: Int,
    isScanning: Boolean,
    lastElapsedMs: Long,
) {
    val palette = KufusTheme.palette
    Surface(
        color = palette.surfaceContainerLowest,
        modifier =
            Modifier
                .fillMaxWidth()
                .height(28.dp),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            HorizontalDivider(thickness = 1.dp, color = palette.border)
            Row(
                modifier =
                    Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val countLabel =
                    when {
                        isScanning -> "Scanning USB devices..."
                        driveCount == 1 -> "1 device found"
                        else -> "$driveCount devices found"
                    }
                val dotColor =
                    when {
                        isScanning -> palette.primary
                        driveCount > 0 -> palette.secondary
                        else -> palette.outline
                    }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .size(6.dp)
                                .background(dotColor, CircleShape),
                    )
                    Text(
                        text = countLabel,
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onSurfaceVariant,
                    )
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Box(
                        modifier =
                            Modifier
                                .width(1.dp)
                                .height(12.dp)
                                .background(palette.outlineVariant),
                    )
                    Text(
                        text = formatClockTimer(lastElapsedMs),
                        fontSize = 11.sp,
                        fontFamily = FontFamily.Monospace,
                        color = palette.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun KufusSquareIconButton(
    icon: ImageVector,
    contentDescription: String,
    enabled: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    tint: Color? = null,
) {
    val palette = KufusTheme.palette
    val resolvedTint = tint ?: palette.onSurfaceVariant
    Surface(
        color = if (enabled) palette.surfaceContainer else palette.surfaceContainerLow,
        shape = KufusControlShape,
        border = BorderStroke(1.dp, if (enabled) palette.border else palette.border.copy(alpha = 0.55f)),
        modifier =
            modifier
                .size(KufusFieldHeight)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
                .clickable(enabled = enabled, onClick = onClick),
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = if (enabled) resolvedTint else palette.outline.copy(alpha = 0.45f),
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun FlasherModalDialogs(
    uiState: FlasherUiState,
    showImageInfoDialog: Boolean,
    showLogDialog: Boolean,
    showAboutDialog: Boolean,
    onDismissImageInfo: () -> Unit,
    onDismissLog: () -> Unit,
    onDismissAbout: () -> Unit,
    actions: FlasherActions,
) {
    val selectedDrive = uiState.selectedDrive
    val selectedImage = uiState.inspectedImage

    val confirmingFlash = uiState.flashState as? FlashOperationState.Confirming
    if (confirmingFlash != null && selectedDrive != null && selectedImage != null) {
        KufusDestructiveDriveDialog(
            title = "WARNING — Erase & Flash",
            subtitle = "Permanent image write to USB drive",
            drive = selectedDrive,
            detailLines = "Image: ${fileDisplayName(selectedImage.path)} (${formatImageSubtitle(selectedImage)})",
            extraToggleLabel = "Verify written data with SHA-256",
            extraToggleChecked = confirmingFlash.verifyAfterWrite,
            onExtraToggle = actions.onVerifyToggle,
            ejectLabel = "Safe-eject USB drive after flashing",
            ejectChecked = confirmingFlash.ejectAfterWrite,
            onEjectToggle = actions.onEjectToggle,
            onConfirm = actions.onConfirmFlash,
            onDismiss = actions.onDismissConfirm,
        )
    }

    val confirmingFormat = uiState.flashState as? FlashOperationState.ConfirmingFormat
    if (confirmingFormat != null && selectedDrive != null) {
        ConfirmFormatDialog(
            drive = selectedDrive,
            formatOptions = uiState.formatOptions,
            ejectAfterFormat = confirmingFormat.ejectAfterFormat,
            onEjectToggle = actions.onFormatEjectToggle,
            onConfirmFormat = actions.onConfirmFormat,
            onDismissConfirm = actions.onDismissConfirm,
        )
    }

    if (showImageInfoDialog && selectedImage != null) {
        RufusImageInfoDialog(image = selectedImage, onDismiss = onDismissImageInfo)
    }
    if (showLogDialog) {
        RufusLogDialog(entries = uiState.operationLog, onDismiss = onDismissLog)
    }
    if (showAboutDialog) {
        RufusAboutDialog(platformName = uiState.platformName, onDismiss = onDismissAbout)
    }
}

private fun formatDriveDropdownLabel(drive: UsbDrive): String = "${drive.model} (${drive.id}) [${formatByteSize(drive.sizeBytes)}]"

private fun formatBootSelectionLabel(uiState: FlasherUiState): String =
    when (uiState.bootSelectionMode) {
        BootSelectionMode.NON_BOOTABLE -> "Non-bootable"
        BootSelectionMode.DISK_OR_ISO ->
            uiState.selectedImagePath?.let(::fileDisplayName) ?: "Disk or ISO image (Please select)"
    }

private fun fileDisplayName(path: String): String = path.substringAfterLast('/').substringAfterLast('\\')

private fun formatImageSubtitle(image: ImageInfo): String {
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

private fun buildStatusDetailLine(uiState: FlasherUiState): Pair<String, Boolean>? =
    when (val state = uiState.flashState) {
        is FlashOperationState.Flashing -> formatProgressMetrics(state) to false
        is FlashOperationState.Completed -> formatCompletionMessage(state) to false
        is FlashOperationState.Failed -> state.message to true
        FlashOperationState.Idle,
        is FlashOperationState.Confirming,
        is FlashOperationState.ConfirmingFormat,
        -> null
    }

private fun flashPhaseLabel(phase: FlashPhase): String =
    when (phase) {
        FlashPhase.UNMOUNTING -> "Unmounting & locking"
        FlashPhase.WIPING -> "Wiping USB sectors"
        FlashPhase.PARTITIONING -> "Creating partition table"
        FlashPhase.WRITING -> "Writing image"
        FlashPhase.FORMATTING -> "Formatting filesystem"
        FlashPhase.VERIFYING -> "Verifying SHA-256"
        FlashPhase.EJECTING -> "Ejecting USB drive"
    }

private fun formatProgressMetrics(flashState: FlashOperationState.Flashing): String =
    when (flashState.phase) {
        FlashPhase.UNMOUNTING -> "Requesting OS lock and flushing buffers..."
        FlashPhase.PARTITIONING -> "Writing MBR/GPT layout and clearing metadata..."
        FlashPhase.FORMATTING -> "Initializing filesystem structures (${flashState.bytesProcessed}%)"
        FlashPhase.EJECTING -> "Syncing buffers and detaching drive..."
        FlashPhase.WIPING,
        FlashPhase.WRITING,
        FlashPhase.VERIFYING,
        -> {
            val progressPart =
                if (flashState.totalBytes > 0L) {
                    "${formatByteSize(flashState.bytesProcessed)} / ${formatByteSize(flashState.totalBytes)}"
                } else {
                    formatByteSize(flashState.bytesProcessed)
                }
            val speedPart = if (flashState.speedBytesPerSec > 0L) " • ${formatByteSize(flashState.speedBytesPerSec)}/s" else ""
            val etaPart =
                estimateRemainingSeconds(flashState.bytesProcessed, flashState.totalBytes, flashState.speedBytesPerSec)
                    ?.let { " • ETA ${formatDurationSeconds(it)}" }
                    .orEmpty()
            "$progressPart$speedPart$etaPart"
        }
    }

private fun formatCompletionMessage(completed: FlashOperationState.Completed): String {
    val duration = formatElapsedMillis(completed.elapsedMs)
    val ejectSuffix = if (completed.ejected) " Safe to remove." else ""
    if (completed.operationKind == ActiveOperationKind.FORMAT) {
        val detail = completed.summaryDetail?.let { " ($it)" }.orEmpty()
        return "Format finished in $duration$detail.$ejectSuffix"
    }
    val verifySuffix = if (completed.verified) " • SHA-256 verified" else ""
    return "Flash finished in $duration$verifySuffix.$ejectSuffix"
}

private fun formatClockTimer(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs / 1000L).coerceAtLeast(0L)
    val hours = totalSeconds / SECONDS_PER_HOUR_TIMER
    val minutes = (totalSeconds % SECONDS_PER_HOUR_TIMER) / SECONDS_PER_MIN_TIMER
    val seconds = totalSeconds % SECONDS_PER_MIN_TIMER
    return "${twoDigits(hours)}:${twoDigits(minutes)}:${twoDigits(seconds)}"
}

private fun twoDigits(value: Long): String = if (value < 10L) "0$value" else value.toString()

@Preview
@Composable
@Suppress("UnusedPrivateMember")
private fun FlasherContentMqtlinFormatPreview() {
    KufusTheme(darkTheme = true) {
        FlasherContent(
            uiState =
                FlasherUiState(
                    drives =
                        listOf(
                            UsbDrive(
                                id = "/dev/sdb",
                                name = "sdb",
                                model = "NO_LABEL",
                                sizeBytes = 128_000_000_000L,
                            ),
                        ),
                    selectedDriveId = "/dev/sdb",
                    bootSelectionMode = BootSelectionMode.NON_BOOTABLE,
                    platformName = "Linux",
                ),
            actions = FlasherActions(),
            isDarkTheme = true,
        )
    }
}

@Preview
@Composable
@Suppress("UnusedPrivateMember")
private fun FlasherContentMqtlinReadyPreview() {
    KufusTheme(darkTheme = true) {
        FlasherContent(
            uiState =
                FlasherUiState(
                    drives =
                        listOf(
                            UsbDrive(
                                id = "/dev/sdb",
                                name = "sdb",
                                model = "Install Debian",
                                sizeBytes = 8_000_000_000L,
                            ),
                        ),
                    selectedDriveId = "/dev/sdb",
                    selectedImagePath = "/home/user/debian-11.4.0-amd64-netinst.iso",
                    inspectedImage =
                        ImageInfo(
                            path = "/home/user/debian-11.4.0-amd64-netinst.iso",
                            sizeBytes = 400_000_000L,
                            format = ImageFormat.HYBRID_ISO,
                        ),
                    platformName = "Linux",
                ),
            actions = FlasherActions(),
            isDarkTheme = true,
        )
    }
}

@Preview
@Composable
@Suppress("UnusedPrivateMember")
private fun FlasherContentLightPreview() {
    KufusTheme(darkTheme = false) {
        FlasherContent(
            uiState =
                FlasherUiState(
                    drives =
                        listOf(
                            UsbDrive(
                                id = "/dev/sdb",
                                name = "sdb",
                                model = "Install Debian",
                                sizeBytes = 8_000_000_000L,
                            ),
                        ),
                    selectedDriveId = "/dev/sdb",
                    selectedImagePath = "/home/user/debian-11.4.0-amd64-netinst.iso",
                    inspectedImage =
                        ImageInfo(
                            path = "/home/user/debian-11.4.0-amd64-netinst.iso",
                            sizeBytes = 400_000_000L,
                            format = ImageFormat.HYBRID_ISO,
                        ),
                    platformName = "Linux",
                ),
            actions = FlasherActions(),
            isDarkTheme = false,
        )
    }
}
