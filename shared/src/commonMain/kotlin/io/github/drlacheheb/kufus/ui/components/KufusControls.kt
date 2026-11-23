package io.github.drlacheheb.kufus.ui.components

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowDropDown
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.KeyboardArrowDown
import androidx.compose.material.icons.outlined.KeyboardArrowUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
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
import io.github.drlacheheb.kufus.ui.theme.KufusTheme
import io.github.drlacheheb.kufus.ui.theme.LabelXs
import io.github.drlacheheb.kufus.ui.theme.UiLabelBold

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
