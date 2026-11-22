package io.github.drlacheheb.kufus.disk

private const val DISKUTIL_LIST_COMMAND = "diskutil list -plist external physical 2>/dev/null"

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
