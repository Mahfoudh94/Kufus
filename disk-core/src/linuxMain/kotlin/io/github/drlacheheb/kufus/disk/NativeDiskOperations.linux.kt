package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

private const val LSBLK_COMMAND = "lsblk -J -b -o $LSBLK_COLUMNS 2>/dev/null"
private const val READ_BUFFER_BYTES = 4096

@OptIn(ExperimentalForeignApi::class)
actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val pipe = popen(LSBLK_COMMAND, "r") ?: return emptyList()
    val output =
        try {
            memScoped {
                val buffer = allocArray<ByteVar>(READ_BUFFER_BYTES)
                buildString {
                    while (fgets(buffer, READ_BUFFER_BYTES, pipe) != null) {
                        append(buffer.toKString())
                    }
                }
            }
        } finally {
            pclose(pipe)
        }
    return runCatching { parseLsblkJson(output) }.getOrDefault(emptyList())
}
