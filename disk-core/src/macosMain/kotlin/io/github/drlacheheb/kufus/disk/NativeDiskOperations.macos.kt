package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.toKString
import platform.posix.fgets
import platform.posix.pclose
import platform.posix.popen

private const val DISKUTIL_LIST_COMMAND = "diskutil list -plist external physical 2>/dev/null"
private const val READ_BUFFER_BYTES = 4096

actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val listXml = runPosixCommand(DISKUTIL_LIST_COMMAND) ?: return emptyList()
    val entries = runCatching { parseDiskutilListEntries(listXml) }.getOrDefault(emptyList())
    if (entries.isEmpty()) return emptyList()
    val infoById =
        buildMap {
            entries.forEach { entry ->
                val diskId = entry.deviceIdentifier
                if (isValidBsdDiskIdentifier(diskId)) {
                    runPosixCommand("diskutil info -plist /dev/$diskId 2>/dev/null")?.let { infoXml ->
                        put(diskId, infoXml)
                    }
                }
            }
        }
    return runCatching { buildMacosUsbDrives(listXml, infoById) }.getOrDefault(emptyList())
}

@OptIn(ExperimentalForeignApi::class)
private fun runPosixCommand(command: String): String? {
    val pipe = popen(command, "r") ?: return null
    return try {
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
}
