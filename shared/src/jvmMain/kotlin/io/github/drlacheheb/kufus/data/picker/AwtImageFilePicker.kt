package io.github.drlacheheb.kufus.data.picker

import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.awt.FileDialog
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.io.File
import java.io.FilenameFilter
import javax.swing.SwingUtilities

private val supportedImageExtensions = setOf("iso", "img", "bin", "raw", "gz", "xz", "zst")

class AwtImageFilePicker(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val dialogLauncher: () -> String? = ::launchAwtFileDialog,
) : ImageFilePicker {
    override suspend fun pickImagePath(): String? = withContext(ioDispatcher) { dialogLauncher() }
}

internal fun isSupportedImageFilename(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in supportedImageExtensions
}

private fun launchAwtFileDialog(): String? {
    if (GraphicsEnvironment.isHeadless()) return null
    if (SwingUtilities.isEventDispatchThread()) {
        return showModalFileDialog()
    }
    var selectedPath: String? = null
    SwingUtilities.invokeAndWait {
        selectedPath = showModalFileDialog()
    }
    return selectedPath
}

private fun showModalFileDialog(): String? {
    val owner = Frame.getFrames().firstOrNull { it.isVisible }
    val dialog = FileDialog(owner, "Select OS Image", FileDialog.LOAD)
    dialog.filenameFilter = FilenameFilter { _, name -> isSupportedImageFilename(name) }
    try {
        dialog.isVisible = true
        val directory = dialog.directory ?: return null
        val file = dialog.file ?: return null
        return File(directory, file)
            .toPath()
            .toAbsolutePath()
            .normalize()
            .toString()
    } finally {
        dialog.dispose()
    }
}
