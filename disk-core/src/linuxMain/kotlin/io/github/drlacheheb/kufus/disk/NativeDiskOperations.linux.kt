package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.toKString
import platform.posix.AF_NETLINK
import platform.posix.F_OK
import platform.posix.MSG_DONTWAIT
import platform.posix.POLLIN
import platform.posix.SOCK_DGRAM
import platform.posix.access
import platform.posix.bind
import platform.posix.close
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fgets
import platform.posix.fopen
import platform.posix.opendir
import platform.posix.poll
import platform.posix.pollfd
import platform.posix.readdir
import platform.posix.readlink
import platform.posix.realpath
import platform.posix.recv
import platform.posix.socket
import platform.posix.usleep

private const val DEFAULT_SYS_BLOCK_ROOT = "/sys/block"
private const val DEFAULT_SYS_DEVICES_PREFIX = "/sys/devices/"
private const val DEFAULT_PROC_MOUNTS_PATH = "/proc/mounts"
private const val DEFAULT_PROC_SWAPS_PATH = "/proc/swaps"
private const val PATH_BUFFER_BYTES = 4096
private const val TEXT_BUFFER_BYTES = 4096
private const val NETLINK_KOBJECT_UEVENT = 15
private const val SOCKADDR_NL_BYTES = 12
private const val UEVENT_MULTICAST_GROUP = 1u
private const val UEVENT_POLL_TIMEOUT_MS = 500
private const val UEVENT_DEBOUNCE_MICROS = 150_000u
private const val POLL_FALLBACK_MICROS = 1_000_000u
private const val UEVENT_BUFFER_BYTES = 8192

data class LinuxSysfsPaths(
    val sysBlockRoot: String = DEFAULT_SYS_BLOCK_ROOT,
    val sysDevicesPrefix: String = DEFAULT_SYS_DEVICES_PREFIX,
    val procMountsPath: String = DEFAULT_PROC_MOUNTS_PATH,
    val procSwapsPath: String = DEFAULT_PROC_SWAPS_PATH,
)

actual fun listPlatformUsbDrives(): List<UsbDrive> = listLinuxSysfsUsbDrives(LinuxSysfsPaths())

fun listLinuxSysfsUsbDrives(paths: LinuxSysfsPaths): List<UsbDrive> {
    val blockEntries = listDirectoryEntries(paths.sysBlockRoot)
    val slavesByBlock = collectSlavesByBlock(paths.sysBlockRoot, blockEntries)
    val records = blockEntries.mapNotNull { blockName -> readSysfsBlockRecord(paths, blockName) }
    val mounts = readLinuxMounts(paths)
    return buildLinuxUsbDrives(
        records = records,
        mounts = mounts,
        slavesByBlock = slavesByBlock,
    )
}

actual fun watchPlatformUsbDrives(
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    val initial = listPlatformUsbDrives()
    onDrivesUpdated(initial)
    val netlinkFd = bindUeventSocket()
    if (netlinkFd < 0) {
        runPollingWatchLoop(initial, onDrivesUpdated, shouldContinue)
        return
    }
    try {
        runNetlinkWatchLoop(netlinkFd, initial, onDrivesUpdated, shouldContinue)
    } finally {
        close(netlinkFd)
    }
}

private fun runNetlinkWatchLoop(
    netlinkFd: Int,
    initialDrives: List<UsbDrive>,
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    var lastEmitted = initialDrives
    while (shouldContinue()) {
        if (!awaitRelevantUevent(netlinkFd)) continue
        usleep(UEVENT_DEBOUNCE_MICROS)
        drainUevents(netlinkFd)
        val updated = listPlatformUsbDrives()
        if (updated != lastEmitted) {
            lastEmitted = updated
            onDrivesUpdated(updated)
        }
    }
}

actual fun launchPlatformElevatedProcess(
    executablePath: String,
    arguments: List<String>,
): Int = -1

private fun runPollingWatchLoop(
    initialDrives: List<UsbDrive>,
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    var lastEmitted = initialDrives
    while (shouldContinue()) {
        usleep(POLL_FALLBACK_MICROS)
        if (!shouldContinue()) return
        val updated = listPlatformUsbDrives()
        if (updated != lastEmitted) {
            lastEmitted = updated
            onDrivesUpdated(updated)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun bindUeventSocket(): Int {
    val fd = socket(AF_NETLINK, SOCK_DGRAM, NETLINK_KOBJECT_UEVENT)
    if (fd < 0) return -1
    memScoped {
        val rawAddr = allocArray<UIntVar>(SOCKADDR_NL_BYTES / 4)
        rawAddr[0] = AF_NETLINK.toUInt()
        rawAddr[1] = 0u
        rawAddr[2] = UEVENT_MULTICAST_GROUP
        if (bind(fd, rawAddr.reinterpret(), SOCKADDR_NL_BYTES.toUInt()) != 0) {
            close(fd)
            return -1
        }
    }
    return fd
}

@OptIn(ExperimentalForeignApi::class)
private fun awaitRelevantUevent(fd: Int): Boolean =
    memScoped {
        val pollDesc = alloc<pollfd>()
        pollDesc.fd = fd
        pollDesc.events = POLLIN.toShort()
        pollDesc.revents = 0
        if (poll(pollDesc.ptr, 1u, UEVENT_POLL_TIMEOUT_MS) <= 0) return false
        drainUevents(fd)
    }

@OptIn(ExperimentalForeignApi::class)
private fun drainUevents(fd: Int): Boolean =
    memScoped {
        val buffer = allocArray<ByteVar>(UEVENT_BUFFER_BYTES)
        var relevant = false
        while (true) {
            val bytesRead = recv(fd, buffer, UEVENT_BUFFER_BYTES.toULong(), MSG_DONTWAIT).toInt()
            if (bytesRead <= 0) break
            if (containsBlockOrUsbSubsystem(buffer, bytesRead)) {
                relevant = true
            }
        }
        relevant
    }

@OptIn(ExperimentalForeignApi::class)
private fun containsBlockOrUsbSubsystem(
    buffer: CPointer<ByteVar>,
    byteCount: Int,
): Boolean {
    val text =
        buildString(byteCount) {
            for (index in 0 until byteCount) {
                val ch = buffer[index].toInt().toChar()
                append(if (ch == '\u0000') '\n' else ch)
            }
        }
    return text.contains("SUBSYSTEM=block") || text.contains("SUBSYSTEM=usb")
}

private fun collectSlavesByBlock(
    sysBlockRoot: String,
    blockEntries: List<String>,
): Map<String, List<String>> =
    buildMap {
        blockEntries.forEach { blockName ->
            val slaves = listDirectoryEntries("$sysBlockRoot/$blockName/slaves")
            if (slaves.isNotEmpty()) {
                put(blockName, slaves)
            }
        }
    }

private fun readLinuxMounts(paths: LinuxSysfsPaths): List<LinuxMountEntry> {
    val rawMounts =
        parseProcMounts(readTextFile(paths.procMountsPath)) +
            parseProcSwaps(readTextFile(paths.procSwapsPath))
    return rawMounts.map { entry ->
        if (entry.devicePath.startsWith("/dev/")) {
            entry.copy(devicePath = resolveRealPath(entry.devicePath) ?: entry.devicePath)
        } else {
            entry
        }
    }
}

private fun readSysfsBlockRecord(
    paths: LinuxSysfsPaths,
    blockName: String,
): LinuxSysfsBlockRecord? {
    if (isVirtualBlockDeviceName(blockName)) return null
    val blockDir = "${paths.sysBlockRoot}/$blockName"
    if (!pathExists("$blockDir/device")) return null
    val canonicalSysfsPath = resolveRealPath(blockDir) ?: return null
    val sizeSectors = readTrimmedText("$blockDir/size")?.toLongOrNull() ?: 0L
    val isHidden = readTrimmedText("$blockDir/hidden") == "1"
    val isRemovable = readTrimmedText("$blockDir/removable") == "1"
    val scsiType = readTrimmedText("$blockDir/device/type")?.toIntOrNull()
    val vendor = readTrimmedText("$blockDir/device/vendor")
    val model = readTrimmedText("$blockDir/device/model")
    val usbAncestors = findUsbSubsystemAncestors(canonicalSysfsPath, paths.sysDevicesPrefix)
    val serial =
        readTrimmedText("$blockDir/device/serial")
            ?: usbAncestors.firstNotNullOfOrNull { ancestor -> readTrimmedText("$ancestor/serial") }
    val partitionNames =
        listDirectoryEntries(blockDir).filter { child ->
            pathExists("$blockDir/$child/partition")
        }
    return LinuxSysfsBlockRecord(
        name = blockName,
        sizeSectors = sizeSectors,
        isUsbTransport = usbAncestors.isNotEmpty(),
        isRemovable = isRemovable,
        isHidden = isHidden,
        scsiType = scsiType,
        vendor = vendor,
        model = model,
        serial = serial,
        partitionNames = partitionNames,
    )
}

private fun findUsbSubsystemAncestors(
    canonicalSysfsPath: String,
    sysDevicesPrefix: String,
): List<String> =
    buildList {
        var current: String? = canonicalSysfsPath
        while (current != null && current.startsWith(sysDevicesPrefix)) {
            val subsystemTarget = readSymlinkTarget("$current/subsystem")
            if (subsystemTarget != null && subsystemTarget.substringAfterLast('/') == "usb") {
                add(current)
            }
            current = parentSysfsPath(current, sysDevicesPrefix)
        }
    }

private fun parentSysfsPath(
    path: String,
    sysDevicesPrefix: String,
): String? {
    val slashIndex = path.lastIndexOf('/')
    if (slashIndex <= sysDevicesPrefix.length) return null
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
internal fun resolveRealPath(path: String): String? =
    memScoped {
        val buffer = allocArray<ByteVar>(PATH_BUFFER_BYTES)
        if (realpath(path, buffer) != null) buffer.toKString() else null
    }

private fun readTrimmedText(path: String): String? = readTextFile(path).trim().takeIf { it.isNotEmpty() }

@OptIn(ExperimentalForeignApi::class)
internal fun readTextFile(path: String): String {
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
