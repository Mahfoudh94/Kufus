package io.github.drlacheheb.kufus.disk.parser

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.filterSafeUsbDrives

private val macosSystemRoots =
    setOf("/", "/System", "/System/Volumes/Data", "/System/Volumes/VM", "/System/Volumes/Preboot")

data class MacosWholeDiskEntry(
    val bsdName: String,
    val modelName: String,
    val busProtocol: String,
    val sizeBytes: Long,
    val isInternal: Boolean,
    val isRemovable: Boolean,
    val isVirtual: Boolean,
    val isUsbBus: Boolean = busProtocol.equals("USB", ignoreCase = true),
)

data class MacosMountEntry(
    val parentWholeBsdName: String,
    val volumePath: String,
)

fun buildMacosUsbDrives(
    disks: List<MacosWholeDiskEntry>,
    mounts: List<MacosMountEntry>,
): List<UsbDrive> {
    val mountsByDisk = groupMacosMounts(mounts)
    val candidates =
        disks.mapNotNull { disk ->
            disk.toUsbDriveOrNull(mountsByDisk[disk.bsdName].orEmpty())
        }
    return filterSafeUsbDrives(candidates)
}

private fun groupMacosMounts(mounts: List<MacosMountEntry>): Map<String, List<String>> =
    mounts
        .filter { it.volumePath.isNotBlank() }
        .groupBy(
            keySelector = { it.parentWholeBsdName },
            valueTransform = { it.volumePath },
        ).mapValues { (_, paths) -> paths.distinct() }

private fun MacosWholeDiskEntry.toUsbDriveOrNull(mountPoints: List<String>): UsbDrive? {
    if (!isEligibleExternalUsbDisk()) return null
    return UsbDrive(
        id = "/dev/$bsdName",
        name = bsdName,
        model = modelName.trim().ifEmpty { bsdName },
        sizeBytes = sizeBytes,
        mountPoints = mountPoints,
        isRemovable = isRemovable || !isInternal,
        isSystemDisk = mountPoints.any(::isMacosSystemMountPoint),
    )
}

private fun MacosWholeDiskEntry.isEligibleExternalUsbDisk(): Boolean {
    if (isInternal || isVirtual) return false
    return isUsbBus || busProtocol.equals("USB", ignoreCase = true)
}

private fun isMacosSystemMountPoint(mountPoint: String): Boolean {
    val normalized = mountPoint.trimEnd('/').ifEmpty { "/" }
    return normalized in macosSystemRoots || normalized.startsWith("/System/Volumes/")
}
