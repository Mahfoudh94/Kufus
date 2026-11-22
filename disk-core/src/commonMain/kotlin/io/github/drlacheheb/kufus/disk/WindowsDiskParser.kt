package io.github.drlacheheb.kufus.disk

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

@Serializable
private data class PowerShellWindowsDisk(
    val number: Int? = null,
    val deviceId: String? = null,
    val friendlyName: String? = null,
    val model: String? = null,
    val serialNumber: String? = null,
    val size: Long? = null,
    val busType: String? = null,
    val interfaceType: String? = null,
    val isBoot: Boolean? = null,
    val isSystem: Boolean? = null,
    val driveLetters: List<String> = emptyList(),
)

fun buildWindowsDriveModel(
    vendorId: String?,
    productId: String?,
    fallbackName: String,
): String =
    listOfNotNull(vendorId?.trim(), productId?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .ifEmpty { fallbackName }

fun normalizeWindowsDriveLetter(pathOrLetter: String): String =
    pathOrLetter
        .trim()
        .trimEnd('\\', '/')
        .uppercase()

fun isWindowsSystemMount(
    mountPoint: String,
    systemDrivePrefix: String,
): Boolean {
    val normalizedMount = normalizeWindowsDriveLetter(mountPoint)
    val normalizedSystem = normalizeWindowsDriveLetter(systemDrivePrefix)
    return normalizedMount.isNotEmpty() && normalizedMount == normalizedSystem
}

fun parseWindowsPowerShellDisksJson(
    jsonText: String,
    systemDrive: String = "C:",
): List<UsbDrive> {
    val trimmed = jsonText.trim()
    if (trimmed.isEmpty()) return emptyList()
    val items = decodePowerShellItems(DiskJson.codec.parseToJsonElement(trimmed))
    val drives = items.mapNotNull { it.toUsbDriveOrNull(systemDrive) }
    return filterSafeUsbDrives(drives)
}

private fun decodePowerShellItems(root: JsonElement): List<PowerShellWindowsDisk> =
    when (root) {
        is JsonArray -> root.map { DiskJson.codec.decodeFromJsonElement(PowerShellWindowsDisk.serializer(), it) }
        is JsonObject -> listOf(DiskJson.codec.decodeFromJsonElement(PowerShellWindowsDisk.serializer(), root))
        else -> emptyList()
    }

private fun PowerShellWindowsDisk.toUsbDriveOrNull(systemDrive: String): UsbDrive? {
    val isUsb =
        busType.equals("USB", ignoreCase = true) ||
            interfaceType.equals("USB", ignoreCase = true)
    if (!isUsb) return null
    val diskNumber = number ?: deviceId?.toIntOrNull()
    val devicePath = resolveDevicePath(diskNumber) ?: return null
    val diskName = diskNumber?.let { "PhysicalDrive$it" } ?: devicePath
    val mounts = driveLetters.map(::normalizeWindowsDriveLetter).filter { it.isNotEmpty() }.distinct()
    val isSystem = (isBoot == true) || (isSystem == true) || mounts.any { isWindowsSystemMount(it, systemDrive) }
    return UsbDrive(
        id = devicePath,
        name = diskName,
        model = resolveModelName(diskName),
        sizeBytes = size ?: 0L,
        serial = serialNumber?.trim()?.takeIf { it.isNotEmpty() },
        mountPoints = mounts,
        isRemovable = true,
        isSystemDisk = isSystem,
    )
}

private fun PowerShellWindowsDisk.resolveDevicePath(diskNumber: Int?): String? =
    when {
        diskNumber != null -> "\\\\.\\PhysicalDrive$diskNumber"
        !deviceId.isNullOrBlank() -> deviceId
        else -> null
    }

private fun PowerShellWindowsDisk.resolveModelName(fallbackName: String): String =
    friendlyName?.trim()?.takeIf { it.isNotEmpty() }
        ?: model?.trim()?.takeIf { it.isNotEmpty() }
        ?: fallbackName
