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
import java.io.IOException
import javax.swing.SwingUtilities

private val supportedImageExtensions = setOf("iso", "img", "bin", "raw", "gz", "xz", "zst")
private const val PICKER_TITLE = "Select OS Image"

private sealed interface NativePickerAttempt {
    data class Resolved(
        val selectedPath: String?,
    ) : NativePickerAttempt

    data object Unavailable : NativePickerAttempt
}

class AwtImageFilePicker(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val dialogLauncher: () -> String? = ::launchPlatformFileDialog,
) : ImageFilePicker {
    override suspend fun pickImagePath(): String? = withContext(ioDispatcher) { dialogLauncher() }
}

internal fun isSupportedImageFilename(fileName: String): Boolean {
    val extension = fileName.substringAfterLast('.', missingDelimiterValue = "").lowercase()
    return extension in supportedImageExtensions
}

internal fun buildLinuxFilePickerCommands(desktopEnv: String?): List<List<String>> {
    val kdialogCmd =
        listOf(
            "kdialog",
            "--title",
            PICKER_TITLE,
            "--getopenfilename",
            "",
            "Disk Images (*.iso *.img *.bin *.raw *.gz *.xz *.zst)|All Files (*)",
        )
    val zenityCmd =
        listOf(
            "zenity",
            "--file-selection",
            "--title=$PICKER_TITLE",
            "--file-filter=Disk Images | *.iso *.img *.bin *.raw *.gz *.xz *.zst *.ISO *.IMG *.BIN *.RAW *.GZ *.XZ *.ZST",
            "--file-filter=All Files | *",
        )
    val preferKde = desktopEnv?.contains("kde", ignoreCase = true) == true
    return if (preferKde) listOf(kdialogCmd, zenityCmd) else listOf(zenityCmd, kdialogCmd)
}

private fun launchPlatformFileDialog(): String? {
    if (GraphicsEnvironment.isHeadless()) return null
    val osName = System.getProperty("os.name").orEmpty().lowercase()
    if (osName.contains("linux")) {
        val desktopEnv = System.getenv("XDG_CURRENT_DESKTOP")
        for (command in buildLinuxFilePickerCommands(desktopEnv)) {
            when (val attempt = tryLinuxNativePicker(command)) {
                is NativePickerAttempt.Resolved -> return attempt.selectedPath
                NativePickerAttempt.Unavailable -> Unit
            }
        }
    }
    return launchAwtFileDialog()
}

private fun tryLinuxNativePicker(command: List<String>): NativePickerAttempt {
    val process =
        try {
            ProcessBuilder(command).redirectErrorStream(false).start()
        } catch (_: IOException) {
            return NativePickerAttempt.Unavailable
        }
    val stdout =
        process.inputStream
            .bufferedReader()
            .use { it.readText() }
            .trim()
    val exitCode = process.waitFor()
    return when {
        exitCode == 0 && stdout.isNotEmpty() ->
            NativePickerAttempt.Resolved(
                File(stdout)
                    .toPath()
                    .toAbsolutePath()
                    .normalize()
                    .toString(),
            )
        exitCode == 1 -> NativePickerAttempt.Resolved(null)
        else -> NativePickerAttempt.Unavailable
    }
}

private fun launchAwtFileDialog(): String? {
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
    val dialog = FileDialog(owner, PICKER_TITLE, FileDialog.LOAD)
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
