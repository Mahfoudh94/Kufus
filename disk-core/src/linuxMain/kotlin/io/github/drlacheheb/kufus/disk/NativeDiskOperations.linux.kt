package io.github.drlacheheb.kufus.disk

private const val LSBLK_COMMAND = "lsblk -J -b -o $LSBLK_COLUMNS 2>/dev/null"

actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val output = runPosixCommand(LSBLK_COMMAND) ?: return emptyList()
    return runCatching { parseLsblkJson(output) }.getOrDefault(emptyList())
}
