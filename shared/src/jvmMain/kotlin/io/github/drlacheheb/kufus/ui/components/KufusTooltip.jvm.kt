package io.github.drlacheheb.kufus.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import io.github.drlacheheb.kufus.ui.theme.CaptionXs
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun KufusTooltip(
    tooltip: String,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (tooltip.isBlank()) {
        content()
        return
    }
    val palette = KufusTheme.palette
    TooltipArea(
        tooltip = {
            Surface(
                color = palette.surfaceContainer,
                shape = KufusControlShape,
                border = BorderStroke(1.dp, palette.border),
                shadowElevation = 4.dp,
            ) {
                Text(
                    text = tooltip,
                    style = CaptionXs.copy(color = palette.onSurface),
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                )
            }
        },
        modifier = modifier,
        delayMillis = 450,
        tooltipPlacement =
            TooltipPlacement.ComponentRect(
                anchor = Alignment.TopCenter,
                alignment = Alignment.TopCenter,
                offset = DpOffset(0.dp, (-6).dp),
            ),
        content = content,
    )
}
