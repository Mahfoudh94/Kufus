package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.IntVar
import kotlinx.cinterop.ULongVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.linux.BLKFLSBUF
import platform.linux.BLKGETSIZE64
import platform.linux.BLKROGET
import platform.linux.BLKRRPART
import platform.linux.BLKSSZGET
import platform.linux.umount
import platform.linux.umount2
import platform.posix.EACCES
import platform.posix.EINTR
import platform.posix.EINVAL
import platform.posix.ENOENT
import platform.posix.EPERM
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_WRONLY
import platform.posix.S_IFBLK
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.ioctl
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror
import platform.posix.write

private const val DEFAULT_SECTOR_BYTES = 512
private const val DEFAULT_EJECT_SYS_BLOCK_ROOT = "/sys/block"
private const val PROC_MOUNTS_PATH = "/proc/mounts"
private const val PROC_SWAPS_PATH = "/proc/swaps"

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformReadableFile(path: String): Result<NativeStreamHandle> {
    if (path.isBlank()) {
        return Result.failure(IllegalArgumentException("Image path cannot be blank"))
    }
    val fd = open(path, O_RDONLY)
    if (fd < 0) {
        return Result.failure(IllegalStateException("Unable to open image '$path': ${linuxErrorText(errno)}"))
    }
    return memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0 || (st.st_mode.toInt() and S_IFMT) != S_IFREG) {
            close(fd)
            return Result.failure(IllegalArgumentException("Image path '$path' is not a regular file"))
        }
        Result.success(LinuxReadableStream(fd))
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformFlashTarget(
    devicePath: String,
    expectedCapacityBytes: Long,
    rescanPartitionsOnClose: Boolean,
): Result<BlockFlashTarget> =
    memScoped {
        if (!devicePath.startsWith("/dev/")) {
            return Result.failure(FlashStepException("INVALID_DEVICE", "Linux target must be a /dev block device: '$devicePath'"))
        }
        val st = alloc<stat>()
        if (stat(devicePath, st.ptr) != 0) {
            return Result.failure(
                FlashStepException("DEVICE_NOT_FOUND", "Unable to inspect block device '$devicePath': ${linuxErrorText(errno)}"),
            )
        }
        if ((st.st_mode.toInt() and S_IFMT) != S_IFBLK) {
            return Result.failure(FlashStepException("UNSAFE_DEVICE", "Target '$devicePath' is not a block device"))
        }
        val canonicalDevice = resolveRealPath(devicePath) ?: devicePath
        val (sectorBytes, probedCapacity) = probeLinuxGeometry(canonicalDevice, expectedCapacityBytes)
        Result.success(
            LinuxBlockFlashTarget(
                devicePath = devicePath,
                canonicalDevicePath = canonicalDevice,
                sectorSizeBytes = sectorBytes,
                capacityBytes = probedCapacity,
                rescanPartitionsOnClose = rescanPartitionsOnClose,
            ),
        )
    }

actual fun ejectPlatformDevice(devicePath: String): Result<Unit> = ejectLinuxBlockDevice(devicePath, DEFAULT_EJECT_SYS_BLOCK_ROOT)

/**
 * Unmounts any mounted partitions for [devicePath] and detaches its SCSI block node via `<sysBlockRoot>/<name>/device/delete`.
 */
@OptIn(ExperimentalForeignApi::class)
fun ejectLinuxBlockDevice(
    devicePath: String,
    sysBlockRoot: String = DEFAULT_EJECT_SYS_BLOCK_ROOT,
): Result<Unit> =
    runCatching {
        val blockName = extractSafeBlockDeviceName(devicePath)
        val canonical = resolveRealPath(devicePath) ?: devicePath
        unmountTargetPartitions(devicePath, canonical)
        val deletePath = "$sysBlockRoot/$blockName/device/delete"
        val fd = open(deletePath, O_WRONLY)
        if (fd < 0) {
            throw FlashStepException(
                code = "EJECT_FAILED",
                message = "Unable to detach '$devicePath' via '$deletePath': ${linuxErrorText(errno)}",
            )
        }
        try {
            val payload = byteArrayOf('1'.code.toByte(), '\n'.code.toByte())
            payload.usePinned { pinned ->
                val written = write(fd, pinned.addressOf(0), payload.size.toULong())
                if (written != payload.size.toLong()) {
                    throw FlashStepException(
                        code = "EJECT_FAILED",
                        message = "Failed writing eject signal to '$deletePath': ${linuxErrorText(errno)}",
                    )
                }
            }
        } finally {
            close(fd)
        }
    }

private fun extractSafeBlockDeviceName(devicePath: String): String {
    if (!devicePath.startsWith("/dev/")) {
        throw FlashStepException("INVALID_DEVICE", "Linux target must be a /dev block device: '$devicePath'")
    }
    val resolved = resolveRealPath(devicePath) ?: devicePath
    val blockName = resolved.removePrefix("/dev/")
    if (blockName.isEmpty() || blockName.contains('/') || blockName.contains("..")) {
        throw FlashStepException("INVALID_DEVICE", "Unsupported block device path for eject: '$devicePath'")
    }
    return blockName
}

@OptIn(ExperimentalForeignApi::class)
private fun probeLinuxGeometry(
    devicePath: String,
    fallbackCapacityBytes: Long,
): Pair<Int, Long> {
    val fd = open(devicePath, O_RDONLY)
    if (fd < 0) return DEFAULT_SECTOR_BYTES to fallbackCapacityBytes
    return try {
        memScoped {
            val sectorVar = alloc<IntVar>()
            val sizeVar = alloc<ULongVar>()
            val sectorSize =
                if (ioctl(fd, BLKSSZGET.toULong(), sectorVar.ptr) == 0 && sectorVar.value > 0) {
                    sectorVar.value
                } else {
                    DEFAULT_SECTOR_BYTES
                }
            val sizeBytes =
                if (ioctl(fd, BLKGETSIZE64, sizeVar.ptr) == 0 && sizeVar.value > 0uL) {
                    sizeVar.value.toLong()
                } else {
                    fallbackCapacityBytes
                }
            sectorSize to sizeBytes
        }
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LinuxReadableStream(
    private val fd: Int,
) : NativeStreamHandle {
    private var closed = false

    override fun readBytes(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (closed || maxLength <= 0) return 0
        return buffer.usePinned { pinned ->
            while (true) {
                val n = read(fd, pinned.addressOf(offset), maxLength.toULong())
                if (n >= 0L) return@usePinned n.toInt()
                if (errno != EINTR) {
                    error("Failed reading source image: ${linuxErrorText(errno)}")
                }
            }
            0
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            close(fd)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LinuxBlockFlashTarget(
    private val devicePath: String,
    private val canonicalDevicePath: String,
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
    private val rescanPartitionsOnClose: Boolean,
) : BlockFlashTarget {
    private var writeFd: Int = -1

    override fun unmountAndLock() {
        unmountTargetPartitions(devicePath, canonicalDevicePath)
        val fd = open(devicePath, O_RDWR)
        if (fd < 0) {
            val err = errno
            val code = if (err == EACCES || err == EPERM) "PERMISSION_DENIED" else "DEVICE_OPEN_FAILED"
            throw FlashStepException(code, "Unable to open '$devicePath' for writing: ${linuxErrorText(err)}")
        }
        memScoped {
            val readOnlyFlag = alloc<IntVar>()
            if (ioctl(fd, BLKROGET.toULong(), readOnlyFlag.ptr) == 0 && readOnlyFlag.value != 0) {
                close(fd)
                throw FlashStepException("DEVICE_READ_ONLY", "Block device '$devicePath' is hardware write-protected or read-only")
            }
        }
        writeFd = fd
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        val fd = ensureOpenFd()
        var written = 0
        buffer.usePinned { pinned ->
            while (written < length) {
                val n = pwrite(fd, pinned.addressOf(written), (length - written).toULong(), offset + written)
                if (n > 0L) {
                    written += n.toInt()
                } else if (n < 0L && errno == EINTR) {
                    continue
                } else {
                    throw FlashStepException(
                        "WRITE_FAILED",
                        "Write failed on '$devicePath' at offset ${offset + written}: ${linuxErrorText(errno)}",
                    )
                }
            }
        }
    }

    override fun syncAndFlush() {
        val fd = ensureOpenFd()
        if (fsync(fd) != 0) {
            throw FlashStepException("SYNC_FAILED", "Failed syncing '$devicePath': ${linuxErrorText(errno)}")
        }
        ioctl(fd, BLKFLSBUF.toULong())
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        val fd = ensureOpenFd()
        var total = 0
        var reachedEof = false
        buffer.usePinned { pinned ->
            while (total < length && !reachedEof) {
                val n = pread(fd, pinned.addressOf(total), (length - total).toULong(), offset + total)
                when {
                    n > 0L -> total += n.toInt()
                    n == 0L -> reachedEof = true
                    errno != EINTR ->
                        throw FlashStepException(
                            "VERIFY_READ_FAILED",
                            "Readback failed on '$devicePath' at offset ${offset + total}: ${linuxErrorText(errno)}",
                        )
                }
            }
        }
        return total
    }

    override fun close() {
        if (writeFd >= 0) {
            if (rescanPartitionsOnClose) {
                ioctl(writeFd, BLKRRPART.toULong())
            }
            close(writeFd)
            writeFd = -1
        }
    }

    private fun ensureOpenFd(): Int {
        if (writeFd < 0) {
            throw FlashStepException("DEVICE_NOT_OPEN", "Block device '$devicePath' is not open")
        }
        return writeFd
    }
}

private fun unmountTargetPartitions(
    devicePath: String,
    canonicalDevicePath: String,
) {
    val swaps = parseProcSwaps(readTextFile(PROC_SWAPS_PATH)).map(::resolveMountSource)
    swaps.firstOrNull { entry -> matchesDeviceOrPartition(entry.devicePath, devicePath, canonicalDevicePath) }?.let { swap ->
        throw FlashStepException("UNMOUNT_FAILED", "Target device has active swap partition: ${swap.devicePath}")
    }
    val mounts = parseProcMounts(readTextFile(PROC_MOUNTS_PATH)).map(::resolveMountSource)
    mounts
        .filter { entry -> matchesDeviceOrPartition(entry.devicePath, devicePath, canonicalDevicePath) }
        .sortedByDescending { it.mountPoint.length }
        .forEach { entry -> unmountSingleMountPoint(entry.mountPoint) }
}

private fun resolveMountSource(entry: LinuxMountEntry): LinuxMountEntry =
    if (entry.devicePath.startsWith("/dev/")) {
        entry.copy(devicePath = resolveRealPath(entry.devicePath) ?: entry.devicePath)
    } else {
        entry
    }

private fun matchesDeviceOrPartition(
    sourcePath: String,
    devicePath: String,
    canonicalDevicePath: String,
): Boolean = isSameOrPartition(sourcePath, canonicalDevicePath) || isSameOrPartition(sourcePath, devicePath)

private fun isSameOrPartition(
    candidate: String,
    wholeDisk: String,
): Boolean {
    if (candidate == wholeDisk) return true
    if (!candidate.startsWith(wholeDisk) || candidate.length <= wholeDisk.length) return false
    val next = candidate[wholeDisk.length]
    return next.isDigit() || (next == 'p' && candidate.getOrNull(wholeDisk.length + 1)?.isDigit() == true)
}

@OptIn(ExperimentalForeignApi::class)
private fun unmountSingleMountPoint(mountPoint: String) {
    if (umount2(mountPoint, 0) == 0 || umount(mountPoint) == 0) return
    val err = errno
    if (err == EINVAL || err == ENOENT) return
    throw FlashStepException("UNMOUNT_FAILED", "Unable to unmount '$mountPoint': ${linuxErrorText(err)}")
}

@OptIn(ExperimentalForeignApi::class)
private fun linuxErrorText(errorNumber: Int): String = strerror(errorNumber)?.toKString() ?: "errno $errorNumber"
