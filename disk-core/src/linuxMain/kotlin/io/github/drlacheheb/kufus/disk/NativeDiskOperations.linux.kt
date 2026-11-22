package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.F_OK
import platform.posix.access
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.readlink
import platform.posix.realpath

private const val SYS_BLOCK_ROOT = "/sys/block"
private const val SYS_DEVICES_PREFIX = "/sys/devices/"
private const val PROC_MOUNTS_PATH = "/proc/mounts"
private const val PROC_SWAPS_PATH = "/proc/swaps"
private const val PATH_BUFFER_BYTES = 4096
private const val TEXT_BUFFER_BYTES = 4096

actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val blockEntries = listDirectoryEntries(SYS_BLOCK_ROOT)
    val slavesByBlock = collectSlavesByBlock(blockEntries)
    val records = blockEntries.mapNotNull(::readSysfsBlockRecord)
    val mounts = readLinuxMounts()
    return buildLinuxUsbDrives(
        records = records,
        mounts = mounts,
        slavesByBlock = slavesByBlock,
    )
}

private fun collectSlavesByBlock(blockEntries: List<String>): Map<String, List<String>> =
    buildMap {
        blockEntries.forEach { blockName ->
            val slaves = listDirectoryEntries("$SYS_BLOCK_ROOT/$blockName/slaves")
            if (slaves.isNotEmpty()) {
                put(blockName, slaves)
            }
        }
    }

private fun readLinuxMounts(): List<LinuxMountEntry> {
    val rawMounts = parseProcMounts(readTextFile(PROC_MOUNTS_PATH)) + parseProcSwaps(readTextFile(PROC_SWAPS_PATH))
    return rawMounts.map { entry ->
        if (entry.devicePath.startsWith("/dev/")) {
            entry.copy(devicePath = resolveRealPath(entry.devicePath) ?: entry.devicePath)
        } else {
            entry
        }
    }
}

private fun readSysfsBlockRecord(blockName: String): LinuxSysfsBlockRecord? {
    if (isVirtualBlockDeviceName(blockName)) return null
    val blockDir = "$SYS_BLOCK_ROOT/$blockName"
    if (!pathExists("$blockDir/device")) return null
    val canonicalSysfsPath = resolveRealPath(blockDir) ?: return null
    val sizeSectors = readTrimmedText("$blockDir/size")?.toLongOrNull() ?: 0L
    val isHidden = readTrimmedText("$blockDir/hidden") == "1"
    val isRemovable = readTrimmedText("$blockDir/removable") == "1"
    val scsiType = readTrimmedText("$blockDir/device/type")?.toIntOrNull()
    val vendor = readTrimmedText("$blockDir/device/vendor")
    val model = readTrimmedText("$blockDir/device/model")
    val usbTransport = hasUsbSubsystemAncestor(canonicalSysfsPath)
    val serial =
        readTrimmedText("$blockDir/device/serial")
            ?: findUsbAncestorSerial(canonicalSysfsPath)
    val partitionNames =
        listDirectoryEntries(blockDir).filter { child ->
            pathExists("$blockDir/$child/partition")
        }
    return LinuxSysfsBlockRecord(
        name = blockName,
        sizeSectors = sizeSectors,
        isUsbTransport = usbTransport,
        isRemovable = isRemovable,
        isHidden = isHidden,
        scsiType = scsiType,
        vendor = vendor,
        model = model,
        serial = serial,
        partitionNames = partitionNames,
    )
}

private fun hasUsbSubsystemAncestor(canonicalSysfsPath: String): Boolean {
    var current: String? = canonicalSysfsPath
    while (current != null && current.startsWith(SYS_DEVICES_PREFIX)) {
        val subsystemTarget = readSymlinkTarget("$current/subsystem")
        if (subsystemTarget != null && subsystemTarget.substringAfterLast('/') == "usb") {
            return true
        }
        current = parentSysfsPath(current)
    }
    return false
}

private fun findUsbAncestorSerial(canonicalSysfsPath: String): String? {
    var current: String? = canonicalSysfsPath
    while (current != null && current.startsWith(SYS_DEVICES_PREFIX)) {
        val subsystemTarget = readSymlinkTarget("$current/subsystem")
        if (subsystemTarget != null && subsystemTarget.substringAfterLast('/') == "usb") {
            val serial = readTrimmedText("$current/serial")
            if (serial != null) return serial
        }
        current = parentSysfsPath(current)
    }
    return null
}

private fun parentSysfsPath(path: String): String? {
    val slashIndex = path.lastIndexOf('/')
    if (slashIndex <= SYS_DEVICES_PREFIX.length) return null
    return path.substring(0, slashIndex)
}

@OptIn(ExperimentalForeignApi::class)
private fun pathExists(path: String): Boolean = access(path, F_OK) == 0

@OptIn(ExperimentalForeignApi::class)
private fun listDirectoryEntries(dirPath: String): List<String> {
    val dir = opendir(dirPath) ?: return emptyList()
    return try {
        buildList {
            while (true) {
                val entry = readdir(dir) ?: break
                val name = entry.pointed.d_name.toKString()
                if (name != "." && name != "..") {
                    add(name)
                }
            }
        }
    } finally {
        closedir(dir)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readSymlinkTarget(path: String): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_BUFFER_BYTES)
        val length = readlink(path, buffer, (PATH_BUFFER_BYTES - 1).toULong())
        if (length <= 0L || length >= PATH_BUFFER_BYTES.toLong()) {
            null
        } else {
            buffer[length.toInt()] = 0
            buffer.toKString()
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun resolveRealPath(path: String): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_BUFFER_BYTES)
        if (realpath(path, buffer) != null) buffer.toKString() else null
    }

private fun readTrimmedText(path: String): String? = readTextFile(path).trim().takeIf { it.isNotEmpty() }

@OptIn(ExperimentalForeignApi::class)
private fun readTextFile(path: String): String {
    val file = fopen(path, "r") ?: return ""
    return try {
        memScoped {
            val buffer = allocArray<ByteVar>(TEXT_BUFFER_BYTES)
            buildString {
                while (fgets(buffer, TEXT_BUFFER_BYTES, file) != null) {
                    append(buffer.toKString())
                }
            }
        }
    } finally {
        fclose(file)
    }
}
