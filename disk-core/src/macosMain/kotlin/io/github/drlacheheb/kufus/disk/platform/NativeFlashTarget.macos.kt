package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRunLoopGetCurrent
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.DiskArbitration.DADiskCreateFromBSDName
import platform.DiskArbitration.DADiskEject
import platform.DiskArbitration.DADiskRef
import platform.DiskArbitration.DADiskUnmount
import platform.DiskArbitration.DADissenterGetStatus
import platform.DiskArbitration.DADissenterGetStatusString
import platform.DiskArbitration.DADissenterRef
import platform.DiskArbitration.DASessionCreate
import platform.DiskArbitration.DASessionScheduleWithRunLoop
import platform.DiskArbitration.DASessionUnscheduleFromRunLoop
import platform.DiskArbitration.kDADiskEjectOptionDefault
import platform.DiskArbitration.kDADiskUnmountOptionWhole
import platform.posix.EACCES
import platform.posix.EINTR
import platform.posix.EPERM
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.fsync
import platform.posix.open
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.read
import platform.posix.stat
import platform.posix.strerror

private const val MACOS_RAW_SECTOR_BYTES = 4096
private const val UNMOUNT_SLICE_SECONDS = 0.25
private const val MAX_UNMOUNT_SLICES = 60

private class MacosUnmountState {
    var finished: Boolean = false
    var errorMessage: String? = null
}

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformReadableFile(path: String): Result<NativeStreamHandle> {
    if (path.isBlank()) {
        return Result.failure(IllegalArgumentException("Image path cannot be blank"))
    }
    val fd = open(path, O_RDONLY)
    if (fd < 0) {
        return Result.failure(IllegalStateException("Unable to open image '$path': ${macosErrorText(errno)}"))
    }
    return memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0 || (st.st_mode.toInt() and S_IFMT) != S_IFREG) {
            close(fd)
            return Result.failure(IllegalArgumentException("Image path '$path' is not a regular file"))
        }
        Result.success(MacosReadableStream(fd))
    }
}

actual fun openPlatformFlashTarget(
    devicePath: String,
    expectedCapacityBytes: Long,
    rescanPartitionsOnClose: Boolean,
): Result<BlockFlashTarget> {
    if (!devicePath.startsWith("/dev/disk") && !devicePath.startsWith("/dev/rdisk")) {
        return Result.failure(FlashStepException("INVALID_DEVICE", "macOS target must be /dev/diskN: '$devicePath'"))
    }
    if (expectedCapacityBytes <= 0L) {
        return Result.failure(FlashStepException("INVALID_DEVICE", "Target capacity must be positive"))
    }
    val bsdName = devicePath.removePrefix("/dev/").removePrefix("r")
    return Result.success(
        MacosBlockFlashTarget(
            bsdName = bsdName,
            rawDevicePath = "/dev/r$bsdName",
            sectorSizeBytes = MACOS_RAW_SECTOR_BYTES,
            capacityBytes = expectedCapacityBytes,
        ),
    )
}

actual fun ejectPlatformDevice(devicePath: String): Result<Unit> =
    runCatching {
        if (!devicePath.startsWith("/dev/disk") && !devicePath.startsWith("/dev/rdisk")) {
            throw FlashStepException("INVALID_DEVICE", "macOS target must be /dev/diskN: '$devicePath'")
        }
        val bsdName = devicePath.removePrefix("/dev/").removePrefix("r")
        unmountWholeMacosDisk(bsdName)
        ejectWholeMacosDisk(bsdName)
    }

@OptIn(ExperimentalForeignApi::class)
private class MacosReadableStream(
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
                    error("Failed reading source image: ${macosErrorText(errno)}")
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
private class MacosBlockFlashTarget(
    private val bsdName: String,
    private val rawDevicePath: String,
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
) : BlockFlashTarget {
    private var writeFd: Int = -1

    override fun unmountAndLock() {
        unmountWholeMacosDisk(bsdName)
        val fd = open(rawDevicePath, O_RDWR)
        if (fd < 0) {
            val err = errno
            val code = if (err == EACCES || err == EPERM) "PERMISSION_DENIED" else "DEVICE_OPEN_FAILED"
            throw FlashStepException(code, "Unable to open '$rawDevicePath' for writing: ${macosErrorText(err)}")
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
                        "Write failed on '$rawDevicePath' at offset ${offset + written}: ${macosErrorText(errno)}",
                    )
                }
            }
        }
    }

    override fun syncAndFlush() {
        val fd = ensureOpenFd()
        if (fsync(fd) != 0) {
            throw FlashStepException("SYNC_FAILED", "Failed syncing '$rawDevicePath': ${macosErrorText(errno)}")
        }
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
                            "Readback failed on '$rawDevicePath' at offset ${offset + total}: ${macosErrorText(errno)}",
                        )
                }
            }
        }
        return total
    }

    override fun close() {
        if (writeFd >= 0) {
            close(writeFd)
            writeFd = -1
        }
    }

    private fun ensureOpenFd(): Int {
        if (writeFd < 0) {
            throw FlashStepException("DEVICE_NOT_OPEN", "Raw device '$rawDevicePath' is not open")
        }
        return writeFd
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun unmountWholeMacosDisk(bsdName: String) {
    runDiskArbitrationOperation(
        bsdName = bsdName,
        errorCode = "UNMOUNT_FAILED",
        actionVerb = "unmount",
    ) { disk, callback, ctx ->
        DADiskUnmount(disk, kDADiskUnmountOptionWhole, callback, ctx)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun ejectWholeMacosDisk(bsdName: String) {
    runDiskArbitrationOperation(
        bsdName = bsdName,
        errorCode = "EJECT_FAILED",
        actionVerb = "eject",
    ) { disk, callback, ctx ->
        DADiskEject(disk, kDADiskEjectOptionDefault, callback, ctx)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun runDiskArbitrationOperation(
    bsdName: String,
    errorCode: String,
    actionVerb: String,
    invokeOperation: (
        DADiskRef,
        platform.DiskArbitration.DADiskUnmountCallback?,
        COpaquePointer?,
    ) -> Unit,
) {
    val session =
        DASessionCreate(kCFAllocatorDefault)
            ?: throw FlashStepException(errorCode, "Unable to create DiskArbitration session")
    val runLoop = CFRunLoopGetCurrent()
    DASessionScheduleWithRunLoop(session, runLoop, kCFRunLoopDefaultMode)
    val state = MacosUnmountState()
    val stateRef = StableRef.create(state)
    val disk = DADiskCreateFromBSDName(kCFAllocatorDefault, session, bsdName)
    if (disk == null) {
        DASessionUnscheduleFromRunLoop(session, runLoop, kCFRunLoopDefaultMode)
        stateRef.dispose()
        CFRelease(session)
        throw FlashStepException("DEVICE_NOT_FOUND", "Unable to lookup BSD disk '$bsdName'")
    }
    val callback =
        staticCFunction<DADiskRef?, DADissenterRef?, COpaquePointer?, Unit> { _, dissenter, ctx ->
            val targetState = ctx?.asStableRef<MacosUnmountState>()?.get() ?: return@staticCFunction
            if (dissenter != null) {
                val status = DADissenterGetStatus(dissenter)
                val statusText = DADissenterGetStatusString(dissenter)?.let(::cfStringToKotlin)
                targetState.errorMessage = statusText ?: "DiskArbitration refused operation (status $status)"
            }
            targetState.finished = true
        }
    try {
        invokeOperation(disk, callback, stateRef.asCPointer())
        awaitDiskArbitrationResult(bsdName, state, errorCode, actionVerb)
    } finally {
        CFRelease(disk)
        DASessionUnscheduleFromRunLoop(session, runLoop, kCFRunLoopDefaultMode)
        stateRef.dispose()
        CFRelease(session)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun awaitDiskArbitrationResult(
    bsdName: String,
    state: MacosUnmountState,
    errorCode: String,
    actionVerb: String,
) {
    var ticks = 0
    while (!state.finished && ticks < MAX_UNMOUNT_SLICES) {
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, UNMOUNT_SLICE_SECONDS, true)
        ticks++
    }
    if (!state.finished) {
        throw FlashStepException(errorCode, "Timed out waiting for '/dev/$bsdName' $actionVerb")
    }
    state.errorMessage?.let { reason ->
        throw FlashStepException(errorCode, "Unable to $actionVerb '/dev/$bsdName': $reason")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun macosErrorText(errorNumber: Int): String = strerror(errorNumber)?.toKString() ?: "errno $errorNumber"
