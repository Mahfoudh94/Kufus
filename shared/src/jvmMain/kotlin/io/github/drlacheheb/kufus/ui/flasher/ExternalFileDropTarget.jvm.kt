package io.github.drlacheheb.kufus.ui.flasher

import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.DragData
import androidx.compose.ui.draganddrop.dragData
import java.net.URI
import java.nio.file.Path

@OptIn(ExperimentalComposeUiApi::class)
@Composable
actual fun Modifier.externalFileDropTarget(
    enabled: Boolean,
    onDragHoverChange: (Boolean) -> Unit,
    onFileDrop: (String) -> Unit,
): Modifier {
    val currentEnabled by rememberUpdatedState(enabled)
    val currentHoverChange by rememberUpdatedState(onDragHoverChange)
    val currentFileDrop by rememberUpdatedState(onFileDrop)

    val target =
        remember {
            object : DragAndDropTarget {
                override fun onEntered(event: DragAndDropEvent) {
                    if (currentEnabled) currentHoverChange(true)
                }

                override fun onExited(event: DragAndDropEvent) {
                    currentHoverChange(false)
                }

                override fun onEnded(event: DragAndDropEvent) {
                    currentHoverChange(false)
                }

                override fun onDrop(event: DragAndDropEvent): Boolean {
                    currentHoverChange(false)
                    if (!currentEnabled) return false
                    val droppedPath = extractFirstDroppedFilePath(event.dragData()) ?: return false
                    currentFileDrop(droppedPath)
                    return true
                }
            }
        }

    return this.dragAndDropTarget(
        shouldStartDragAndDrop = { event ->
            currentEnabled && event.dragData() is DragData.FilesList
        },
        target = target,
    )
}

@OptIn(ExperimentalComposeUiApi::class)
internal fun extractFirstDroppedFilePath(dragData: DragData): String? {
    val filesList = dragData as? DragData.FilesList ?: return null
    val firstUri = filesList.readFiles().firstOrNull() ?: return null
    return uriStringToLocalPath(firstUri)
}

internal fun uriStringToLocalPath(uriString: String): String? =
    runCatching {
        val uri = URI(uriString.trim())
        if (uri.scheme.equals("file", ignoreCase = true)) {
            Path
                .of(uri)
                .toAbsolutePath()
                .normalize()
                .toString()
        } else {
            null
        }
    }.getOrNull()
