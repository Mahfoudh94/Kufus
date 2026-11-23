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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Article
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.drlacheheb.kufus.disk.format.core.filesystemDisplayName
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.FlashPhase
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.model.formatByteSize
import io.github.drlacheheb.kufus.ui.components.KufusActionButton
import io.github.drlacheheb.kufus.ui.components.KufusControlShape
import io.github.drlacheheb.kufus.ui.components.KufusFieldHeight
import io.github.drlacheheb.kufus.ui.components.KufusSectionHeading
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@Composable
internal fun RufusStatusSection(
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
                        val pct = formatProgressPercent(flashState.bytesProcessed, flashState.totalBytes)
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
internal fun KufusSquareIconButton(
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

private fun buildStatusDetailLine(uiState: FlasherUiState): Pair<String, Boolean>? =
    when (val state = uiState.flashState) {
        is FlashOperationState.Flashing -> formatProgressMetrics(state) to false
        is FlashOperationState.Completed -> formatCompletionMessage(state) to false
        is FlashOperationState.Failed -> state.message to true
        FlashOperationState.Idle,
        is FlashOperationState.Confirming,
        is FlashOperationState.ConfirmingWindows,
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

internal fun formatWindowsConfirmDetails(
    image: ImageInfo,
    formatOptions: FormatOptionsState,
    options: WindowsUserExperienceOptions,
): String {
    val cleanLabel = sanitizeVolumeLabel(formatOptions.volumeLabel, formatOptions.filesystem).ifEmpty { "(Unlabeled)" }
    val splitNote =
        if (formatOptions.filesystem == FilesystemType.FAT32 && image.windowsMetadata?.isSplitRequiredForFat32 == true) {
            " • WIM Split: install.swm"
        } else {
            ""
        }
    val hasUnattend =
        options.bypassSystemRequirements ||
            options.bypassMicrosoftAccount ||
            options.disableDataCollection ||
            !options.localAccountUsername.isNullOrBlank() ||
            !options.regionalLocaleTag.isNullOrBlank()
    val unattendNote = if (hasUnattend) "\\autounattend.xml enabled" else "Standard untouched setup"
    return buildString {
        append("Image: ${fileDisplayName(image.path)} (${formatImageSubtitle(image)})")
        append("\nTarget: ${formatOptions.partitionScheme.name} (UEFI) • ${filesystemDisplayName(formatOptions.filesystem)} • $cleanLabel$splitNote")
        append("\nOptions: $unattendNote")
    }
}

private fun formatCompletionMessage(completed: FlashOperationState.Completed): String {
    val duration = formatElapsedMillis(completed.elapsedMs)
    val ejectSuffix = if (completed.ejected) " Safe to remove." else ""
    val detail = completed.summaryDetail?.let { " ($it)" }.orEmpty()
    if (completed.operationKind == ActiveOperationKind.FORMAT) {
        return "Format finished in $duration$detail.$ejectSuffix"
    }
    val verifySuffix = if (completed.verified) " • SHA-256 verified" else ""
    return "Flash finished in $duration$detail$verifySuffix.$ejectSuffix"
}
