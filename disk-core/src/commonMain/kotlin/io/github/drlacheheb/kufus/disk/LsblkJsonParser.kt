package io.github.drlacheheb.kufus.disk

import kotlinx.serialization.Serializable

const val LSBLK_COLUMNS: String =
    "NAME,PATH,SIZE,TYPE,TRAN,RM,HOTPLUG,MODEL,VENDOR,SERIAL,MOUNTPOINTS"

private val systemMountRoots = setOf("/", "/boot", "/boot/efi", "/etc", "/usr", "/var", "/home", "[SWAP]")

@Serializable
private data class LsblkOutput(
    val blockdevices: List<LsblkDevice> = emptyList(),
)

@Serializable
private data class LsblkDevice(
    val name: String,
    val path: String? = null,
    val size: Long? = null,
    val type: String? = null,
    val tran: String? = null,
    val rm: Boolean? = null,
    val hotplug: Boolean? = null,
    val model: String? = null,
    val vendor: String? = null,
    val serial: String? = null,
    val mountpoints: List<String?> = emptyList(),
    val children: List<LsblkDevice> = emptyList(),
)

fun parseLsblkJson(jsonText: String): List<UsbDrive> {
    if (jsonText.isBlank()) return emptyList()
    val parsed = DiskJson.codec.decodeFromString(LsblkOutput.serializer(), jsonText)
    val candidates =
        parsed.blockdevices
            .filter { it.type == "disk" }
            .filter { it.tran == "usb" || (it.hotplug == true && it.rm == true) }
            .map { device ->
                val mounts = device.collectMountPoints()
                UsbDrive(
                    id = device.path ?: "/dev/${device.name}",
                    name = device.name,
                    model = device.displayModel(),
                    sizeBytes = device.size ?: 0L,
                    serial = device.serial?.trim()?.takeIf { it.isNotEmpty() },
                    mountPoints = mounts,
                    isRemovable = device.rm == true || device.tran == "usb",
                    isSystemDisk = mounts.any(::isSystemMountPoint),
                )
            }
    return filterSafeUsbDrives(candidates)
}

private fun LsblkDevice.collectMountPoints(): List<String> =
    buildList {
        mountpoints.filterNotNull().filter { it.isNotBlank() }.forEach(::add)
        children.forEach { child -> addAll(child.collectMountPoints()) }
    }.distinct()

private fun LsblkDevice.displayModel(): String =
    listOfNotNull(vendor?.trim(), model?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .ifEmpty { name }

private fun isSystemMountPoint(mountPoint: String): Boolean =
    mountPoint in systemMountRoots ||
        mountPoint.startsWith("/usr/") ||
        mountPoint.startsWith("/var/") ||
        mountPoint.startsWith("/boot/")
