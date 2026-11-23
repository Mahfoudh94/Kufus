package io.github.drlacheheb.kufus.disk.parser

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.filterSafeUsbDrives

private const val LINUX_SECTOR_BYTES = 512L
private const val SCSI_TYPE_ROM = 5
private const val OCTAL_RADIX = 8
private const val OCTAL_ESCAPE_LEN = 3

private val systemMountRoots = setOf("/", "/boot", "/boot/efi", "/etc", "/usr", "/var", "/home", "[SWAP]")

private val virtualBlockPrefixes = listOf("loop", "ram", "zram", "dm-", "md", "sr", "fd", "nbd")

data class LinuxSysfsBlockRecord(
    val name: String,
    val sizeSectors: Long,
    val isUsbTransport: Boolean,
    val isRemovable: Boolean,
    val isHidden: Boolean = false,
    val scsiType: Int? = null,
    val vendor: String? = null,
    val model: String? = null,
    val serial: String? = null,
    val partitionNames: List<String> = emptyList(),
)

data class LinuxMountEntry(
    val devicePath: String,
    val mountPoint: String,
)

fun isVirtualBlockDeviceName(name: String): Boolean = virtualBlockPrefixes.any { prefix -> name.startsWith(prefix) }

fun parseProcMounts(mountsText: String): List<LinuxMountEntry> =
    mountsText
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val fields = line.split(' ', '\t').filter { it.isNotEmpty() }
            if (fields.size < 2) {
                null
            } else {
                LinuxMountEntry(
                    devicePath = unescapeProcField(fields[0]),
                    mountPoint = unescapeProcField(fields[1]),
                )
            }
        }.toList()

fun parseProcSwaps(swapsText: String): List<LinuxMountEntry> =
    swapsText
        .lineSequence()
        .drop(1)
        .map { it.trim() }
        .filter { it.isNotEmpty() }
        .mapNotNull { line ->
            val source = line.split(' ', '\t').firstOrNull { it.isNotEmpty() } ?: return@mapNotNull null
            LinuxMountEntry(devicePath = source, mountPoint = "[SWAP]")
        }.toList()

fun buildLinuxUsbDrives(
    records: List<LinuxSysfsBlockRecord>,
    mounts: List<LinuxMountEntry>,
    slavesByBlock: Map<String, List<String>> = emptyMap(),
): List<UsbDrive> {
    val mountsByBlock = propagateMountsToSlaves(groupDirectMounts(mounts), slavesByBlock)
    val candidates =
        records.mapNotNull { record ->
            record.toUsbDriveOrNull(mountsByBlock)
        }
    return filterSafeUsbDrives(candidates)
}

private fun LinuxSysfsBlockRecord.toUsbDriveOrNull(mountsByBlock: Map<String, Set<String>>): UsbDrive? {
    if (!isEligibleUsbDisk()) return null
    val sizeBytes = sizeSectors * LINUX_SECTOR_BYTES
    val deviceKeys = listOf(name) + partitionNames
    val mountPoints =
        deviceKeys
            .flatMap { key -> mountsByBlock[key].orEmpty() }
            .distinct()
    return UsbDrive(
        id = "/dev/$name",
        name = name,
        model = displayModel(),
        sizeBytes = sizeBytes,
        serial = serial?.trim()?.takeIf { it.isNotEmpty() },
        mountPoints = mountPoints,
        isRemovable = isRemovable || isUsbTransport,
        isSystemDisk = mountPoints.any(::isLinuxSystemMountPoint),
    )
}

private fun LinuxSysfsBlockRecord.isEligibleUsbDisk(): Boolean {
    if (isVirtualBlockDeviceName(name) || isHidden || scsiType == SCSI_TYPE_ROM) return false
    return isUsbTransport
}

private fun LinuxSysfsBlockRecord.displayModel(): String =
    listOfNotNull(vendor?.trim(), model?.trim())
        .filter { it.isNotEmpty() }
        .joinToString(separator = " ")
        .ifEmpty { name }

private fun groupDirectMounts(mounts: List<LinuxMountEntry>): Map<String, Set<String>> {
    val grouped = linkedMapOf<String, LinkedHashSet<String>>()
    mounts.forEach { entry ->
        if (entry.devicePath.startsWith("/dev/") && entry.mountPoint.isNotBlank()) {
            val blockName = entry.devicePath.substringAfterLast('/')
            if (blockName.isNotEmpty()) {
                grouped.getOrPut(blockName) { linkedSetOf() }.add(entry.mountPoint)
            }
        }
    }
    return grouped
}

private fun propagateMountsToSlaves(
    directMounts: Map<String, Set<String>>,
    slavesByBlock: Map<String, List<String>>,
): Map<String, Set<String>> {
    val propagated = linkedMapOf<String, LinkedHashSet<String>>()
    directMounts.forEach { (blockName, mountPoints) ->
        propagateSingleMountSet(blockName, mountPoints, slavesByBlock, propagated)
    }
    return propagated
}

private fun propagateSingleMountSet(
    rootBlockName: String,
    mountPoints: Set<String>,
    slavesByBlock: Map<String, List<String>>,
    target: MutableMap<String, LinkedHashSet<String>>,
) {
    val visited = hashSetOf(rootBlockName)
    val queue = ArrayDeque(listOf(rootBlockName))
    while (queue.isNotEmpty()) {
        val current = queue.removeFirst()
        target.getOrPut(current) { linkedSetOf() }.addAll(mountPoints)
        slavesByBlock[current].orEmpty().forEach { slave ->
            if (visited.add(slave)) {
                queue.addLast(slave)
            }
        }
    }
}

private fun unescapeProcField(value: String): String {
    if (!value.contains('\\')) return value
    return buildString(value.length) {
        var index = 0
        while (index < value.length) {
            val decoded = parseOctalEscape(value, index)
            if (decoded != null) {
                append(decoded)
                index += 1 + OCTAL_ESCAPE_LEN
            } else {
                append(value[index])
                index++
            }
        }
    }
}

private fun parseOctalEscape(
    value: String,
    index: Int,
): Char? {
    if (value[index] != '\\' || index + OCTAL_ESCAPE_LEN >= value.length) return null
    val octalDigits = value.substring(index + 1, index + 1 + OCTAL_ESCAPE_LEN)
    if (!octalDigits.all { it in '0'..'7' }) return null
    return octalDigits.toInt(OCTAL_RADIX).toChar()
}

private fun isLinuxSystemMountPoint(mountPoint: String): Boolean =
    mountPoint in systemMountRoots ||
        mountPoint.startsWith("/usr/") ||
        mountPoint.startsWith("/var/") ||
        mountPoint.startsWith("/boot/")
