package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragHoverChange: (Boolean) -> Unit,
    onFileDrop: (String) -> Unit,
): Modifier
