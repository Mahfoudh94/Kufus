package io.github.drlacheheb.kufus.disk

private val macosSystemRoots = setOf("/", "/System", "/System/Volumes/Data", "/System/Volumes/VM", "/System/Volumes/Preboot")

data class DiskutilListEntry(
    val deviceIdentifier: String,
    val sizeBytes: Long,
    val mountPoints: List<String>,
)

data class DiskutilDeviceInfo(
    val deviceIdentifier: String,
    val mediaName: String,
    val busProtocol: String,
    val isInternal: Boolean,
    val isRemovable: Boolean,
    val isVirtual: Boolean,
    val totalSizeBytes: Long,
    val mountPoint: String?,
)

fun parseDiskutilListEntries(plistXml: String): List<DiskutilListEntry> {
    val root = parsePlistRootDict(plistXml) ?: return emptyList()
    return root.array("AllDisksAndPartitions").mapNotNull { item ->
        val dict = item as? PlistValue.Dict ?: return@mapNotNull null
        val id = dict.string("DeviceIdentifier") ?: return@mapNotNull null
        DiskutilListEntry(
            deviceIdentifier = id,
            sizeBytes = dict.long("Size") ?: 0L,
            mountPoints = collectPlistMountPoints(dict),
        )
    }
}

fun parseDiskutilInfo(plistXml: String): DiskutilDeviceInfo? {
    val root = parsePlistRootDict(plistXml) ?: return null
    val id = root.string("DeviceIdentifier") ?: return null
    val mediaName =
        root.string("MediaName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: root.string("IORegistryEntryName")?.trim()?.takeIf { it.isNotEmpty() }
            ?: id
    val protocol =
        root.string("BusProtocol")?.trim()
            ?: root.string("Protocol")?.trim()
            ?: ""
    val isInternal = root.bool("Internal") ?: false
    val isRemovable =
        (root.bool("RemovableMediaOrExternalDevice") == true) ||
            (root.bool("RemovableMedia") == true) ||
            !isInternal
    val isVirtual = root.string("VirtualOrPhysical").equals("Virtual", ignoreCase = true)
    return DiskutilDeviceInfo(
        deviceIdentifier = id,
        mediaName = mediaName,
        busProtocol = protocol,
        isInternal = isInternal,
        isRemovable = isRemovable,
        isVirtual = isVirtual,
        totalSizeBytes = root.long("TotalSize") ?: root.long("Size") ?: 0L,
        mountPoint = root.string("MountPoint")?.trim()?.takeIf { it.isNotEmpty() },
    )
}

fun buildMacosUsbDrives(
    listPlistXml: String,
    infoPlistByDiskId: Map<String, String>,
): List<UsbDrive> {
    val entries = parseDiskutilListEntries(listPlistXml)
    val drives =
        entries.mapNotNull { entry ->
            val infoXml = infoPlistByDiskId[entry.deviceIdentifier] ?: return@mapNotNull null
            val info = parseDiskutilInfo(infoXml) ?: return@mapNotNull null
            if (info.isInternal || info.isVirtual || !info.busProtocol.equals("USB", ignoreCase = true)) {
                return@mapNotNull null
            }
            val mountPoints = (entry.mountPoints + listOfNotNull(info.mountPoint)).distinct()
            UsbDrive(
                id = "/dev/${info.deviceIdentifier}",
                name = info.deviceIdentifier,
                model = info.mediaName,
                sizeBytes = if (info.totalSizeBytes > 0L) info.totalSizeBytes else entry.sizeBytes,
                mountPoints = mountPoints,
                isRemovable = info.isRemovable,
                isSystemDisk = mountPoints.any(::isMacosSystemMountPoint),
            )
        }
    return filterSafeUsbDrives(drives)
}

fun isValidBsdDiskIdentifier(identifier: String): Boolean =
    identifier.startsWith("disk") &&
        identifier.length > 4 &&
        identifier.substring(4).all { it.isDigit() }

private fun collectPlistMountPoints(dict: PlistValue.Dict): List<String> =
    buildList {
        dict.string("MountPoint")?.takeIf { it.isNotBlank() }?.let(::add)
        dict.array("Partitions").mapNotNull { it as? PlistValue.Dict }.forEach { addAll(collectPlistMountPoints(it)) }
        dict.array("APFSVolumes").mapNotNull { it as? PlistValue.Dict }.forEach { addAll(collectPlistMountPoints(it)) }
    }.distinct()

private fun isMacosSystemMountPoint(mountPoint: String): Boolean = mountPoint in macosSystemRoots || mountPoint.startsWith("/System/")
