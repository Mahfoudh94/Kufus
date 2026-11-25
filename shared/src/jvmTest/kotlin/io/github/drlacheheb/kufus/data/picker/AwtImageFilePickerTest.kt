package io.github.drlacheheb.kufus.data.picker

import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class AwtImageFilePickerTest {
    @Test
    fun acceptsSupportedImageAndCompressedExtensionsCaseInsensitively() {
        isSupportedImageFilename("ubuntu-24.04.iso") shouldBe true
        isSupportedImageFilename("DISK.IMG") shouldBe true
        isSupportedImageFilename("firmware.bin") shouldBe true
        isSupportedImageFilename("backup.raw") shouldBe true
        isSupportedImageFilename("fedora.iso.gz") shouldBe true
        isSupportedImageFilename("arch.img.xz") shouldBe true
        isSupportedImageFilename("alpine.img.zst") shouldBe true
    }

    @Test
    fun rejectsUnsupportedAndExtensionlessFilenames() {
        isSupportedImageFilename("readme.txt") shouldBe false
        isSupportedImageFilename("image") shouldBe false
        isSupportedImageFilename("archive.zip") shouldBe false
        isSupportedImageFilename(".iso") shouldBe true
    }

    @Test
    fun returnsSelectedPathFromInjectedDialogLauncher() =
        runTest {
            val picker = AwtImageFilePicker(dialogLauncher = { "/home/user/ubuntu.iso" })

            picker.pickImagePath() shouldBe "/home/user/ubuntu.iso"
        }

    @Test
    fun ordersLinuxNativeFilePickersByDesktopEnvironment() {
        buildLinuxFilePickerCommands("KDE").map { it.first() } shouldBe listOf("kdialog", "zenity")
        buildLinuxFilePickerCommands("GNOME").map { it.first() } shouldBe listOf("zenity", "kdialog")
        buildLinuxFilePickerCommands(null).map { it.first() } shouldBe listOf("zenity", "kdialog")
    }
}
