package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.memcpy
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DISK_GEOMETRY
import platform.windows.DWORDVar
import platform.windows.DeviceIoControl
import platform.windows.ERROR_ACCESS_DENIED
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_BEGIN
import platform.windows.FILE_FLAG_NO_BUFFERING
import platform.windows.FILE_FLAG_SEQUENTIAL_SCAN
import platform.windows.FILE_FLAG_WRITE_THROUGH
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FILE_TYPE_DISK
import platform.windows.FSCTL_DISMOUNT_VOLUME
import platform.windows.FSCTL_LOCK_VOLUME
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_READ
import platform.windows.GENERIC_WRITE
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.IOCTL_DISK_EJECT_MEDIA
import platform.windows.IOCTL_DISK_GET_DRIVE_GEOMETRY
import platform.windows.IOCTL_DISK_UPDATE_PROPERTIES
import platform.windows.IOCTL_STORAGE_EJECT_MEDIA
import platform.windows.IOCTL_STORAGE_MEDIA_REMOVAL
import platform.windows.LARGE_INTEGER
import platform.windows.MEM_COMMIT
import platform.windows.MEM_RELEASE
import platform.windows.MEM_RESERVE
import platform.windows.OPEN_EXISTING
import platform.windows.PAGE_READWRITE
import platform.windows.PREVENT_MEDIA_REMOVAL
import platform.windows.ReadFile
import platform.windows.SetFilePointerEx
import platform.windows.Sleep
import platform.windows.VirtualAlloc
import platform.windows.VirtualFree
import platform.windows.WriteFile

private const val DEFAULT_WIN_SECTOR_BYTES = 512
private const val LOCK_RETRY_ATTEMPTS = 5
private const val LOCK_RETRY_DELAY_MS = 200u
private const val PHYSICAL_DRIVE_PREFIX = "\\\\.\\PhysicalDrive"

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformReadableFile(path: String): Result<NativeStreamHandle> {
    if (path.isBlank()) {
        return Result.failure(IllegalArgumentException("Image path cannot be blank"))
    }
    val handle =
        memScoped {
            val shareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt()
            val flags = (FILE_ATTRIBUTE_NORMAL or FILE_FLAG_SEQUENTIAL_SCAN).toUInt()
            val h = CreateFileW(path.wcstr.ptr, GENERIC_READ, shareMode, null, OPEN_EXISTING.toUInt(), flags, null)
            if (h == null || h == INVALID_HANDLE_VALUE) null else h
        } ?: return Result.failure(IllegalStateException("Unable to open image '$path' (error ${GetLastError()})"))

    if (GetFileType(handle) != FILE_TYPE_DISK.toUInt()) {
        CloseHandle(handle)
        return Result.failure(IllegalArgumentException("Image path '$path' is not a regular file"))
    }
    return Result.success(Win32ReadableStream(handle))
}

actual fun openPlatformFlashTarget(
    devicePath: String,
    expectedCapacityBytes: Long,
    rescanPartitionsOnClose: Boolean,
): Result<BlockFlashTarget> {
    val diskIndex =
        parsePhysicalDriveIndex(devicePath)
            ?: return Result.failure(FlashStepException("INVALID_DEVICE", "Windows target must be PhysicalDriveN: '$devicePath'"))
    if (expectedCapacityBytes <= 0L) {
        return Result.failure(FlashStepException("INVALID_DEVICE", "Target drive capacity must be positive"))
    }
    return Result.success(
        Win32BlockFlashTarget(
            devicePath = devicePath,
            diskIndex = diskIndex,
            initialSectorSizeBytes = DEFAULT_WIN_SECTOR_BYTES,
            initialCapacityBytes = expectedCapacityBytes,
            rescanPartitionsOnClose = rescanPartitionsOnClose,
        ),
    )
}

@OptIn(ExperimentalForeignApi::class)
actual fun ejectPlatformDevice(devicePath: String): Result<Unit> =
    runCatching {
        val diskIndex =
            parsePhysicalDriveIndex(devicePath)
                ?: throw FlashStepException("INVALID_DEVICE", "Windows target must be PhysicalDriveN: '$devicePath'")
        val volumeHandles =
            mapVolumeLettersToDiskNumbers()[diskIndex]
                .orEmpty()
                .map(::lockAndDismountVolume)
        try {
            val driveHandle = openWin32PhysicalDrive(devicePath, flags = 0u)
            try {
                allowMediaRemoval(driveHandle)
                if (!issueSimpleControlCode(driveHandle, IOCTL_STORAGE_EJECT_MEDIA.toUInt()) &&
                    !issueSimpleControlCode(driveHandle, IOCTL_DISK_EJECT_MEDIA.toUInt())
                ) {
                    throw FlashStepException(
                        "EJECT_FAILED",
                        "Unable to eject '$devicePath' (error ${GetLastError()})",
                    )
                }
            } finally {
                CloseHandle(driveHandle)
            }
        } finally {
            volumeHandles.forEach { volume -> CloseHandle(volume) }
        }
    }

internal fun parsePhysicalDriveIndex(devicePath: String): Int? =
    devicePath
        .takeIf { it.startsWith(PHYSICAL_DRIVE_PREFIX, ignoreCase = true) }
        ?.substring(PHYSICAL_DRIVE_PREFIX.length)
        ?.toIntOrNull()

@OptIn(ExperimentalForeignApi::class)
private class Win32ReadableStream(
    private val handle: HANDLE,
) : NativeStreamHandle {
    private var closed = false

    override fun readBytes(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (closed || maxLength <= 0) return 0
        return memScoped {
            val bytesRead = alloc<DWORDVar>()
            val ok =
                buffer.usePinned { pinned ->
                    ReadFile(
                        hFile = handle,
                        lpBuffer = pinned.addressOf(offset),
                        nNumberOfBytesToRead = maxLength.toUInt(),
                        lpNumberOfBytesRead = bytesRead.ptr,
                        lpOverlapped = null,
                    )
                }
            if (ok == 0) {
                error("Failed reading source image (error ${GetLastError()})")
            }
            bytesRead.value.toInt()
        }
    }

    override fun close() {
        if (!closed) {
            closed = true
            CloseHandle(handle)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class Win32BlockFlashTarget(
    private val devicePath: String,
    private val diskIndex: Int,
    initialSectorSizeBytes: Int,
    initialCapacityBytes: Long,
    private val rescanPartitionsOnClose: Boolean,
) : BlockFlashTarget {
    override var sectorSizeBytes: Int = initialSectorSizeBytes
        private set
    override var capacityBytes: Long = initialCapacityBytes
        private set

    private val lockedVolumeHandles = mutableListOf<HANDLE>()
    private var driveHandle: HANDLE? = null
    private var alignedBuffer: COpaquePointer? = null
    private var alignedBufferSize: Int = 0

    override fun unmountAndLock() {
        val volumeLetters = mapVolumeLettersToDiskNumbers()[diskIndex].orEmpty()
        volumeLetters.forEach { letter ->
            lockedVolumeHandles += lockAndDismountVolume(letter)
        }
        val handle = openWin32PhysicalDriveForWrite(devicePath)
        driveHandle = handle
        querySectorSize(handle)?.let { queried ->
            if (queried >= DEFAULT_WIN_SECTOR_BYTES) {
                sectorSizeBytes = queried
            }
        }
        queryDiskLength(handle)?.let { queriedLength ->
            if (queriedLength > 0L) {
                capacityBytes = queriedLength
            }
        }
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        val handle = ensureDriveHandle()
        val transferPtr = ensureAlignedBuffer(length)
        buffer.usePinned { pinned ->
            memcpy(transferPtr, pinned.addressOf(0), length.toULong())
        }
        seekWin32Handle(handle, offset)
        var totalWritten = 0
        memScoped {
            val writtenVar = alloc<DWORDVar>()
            while (totalWritten < length) {
                val slicePtr = interpretOffsetPointer(transferPtr, totalWritten)
                val ok =
                    WriteFile(
                        hFile = handle,
                        lpBuffer = slicePtr,
                        nNumberOfBytesToWrite = (length - totalWritten).toUInt(),
                        lpNumberOfBytesWritten = writtenVar.ptr,
                        lpOverlapped = null,
                    )
                if (ok == 0 || writtenVar.value == 0u) {
                    throw FlashStepException(
                        "WRITE_FAILED",
                        "Write failed on '$devicePath' at offset ${offset + totalWritten} (error ${GetLastError()})",
                    )
                }
                totalWritten += writtenVar.value.toInt()
            }
        }
    }

    override fun syncAndFlush() {
        val handle = ensureDriveHandle()
        if (FlushFileBuffers(handle) == 0) {
            throw FlashStepException("SYNC_FAILED", "FlushFileBuffers failed on '$devicePath' (error ${GetLastError()})")
        }
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        val handle = ensureDriveHandle()
        val transferPtr = ensureAlignedBuffer(length)
        seekWin32Handle(handle, offset)
        var totalRead = 0
        memScoped {
            val readVar = alloc<DWORDVar>()
            while (totalRead < length) {
                val slicePtr = interpretOffsetPointer(transferPtr, totalRead)
                val ok =
                    ReadFile(
                        hFile = handle,
                        lpBuffer = slicePtr,
                        nNumberOfBytesToRead = (length - totalRead).toUInt(),
                        lpNumberOfBytesRead = readVar.ptr,
                        lpOverlapped = null,
                    )
                if (ok == 0) {
                    throw FlashStepException(
                        "VERIFY_READ_FAILED",
                        "Readback failed on '$devicePath' at offset ${offset + totalRead} (error ${GetLastError()})",
                    )
                }
                val chunk = readVar.value.toInt()
                if (chunk == 0) break
                totalRead += chunk
            }
        }
        if (totalRead > 0) {
            buffer.usePinned { pinned ->
                memcpy(pinned.addressOf(0), transferPtr, totalRead.toULong())
            }
        }
        return totalRead
    }

    override fun close() {
        lockedVolumeHandles.forEach { volume -> CloseHandle(volume) }
        lockedVolumeHandles.clear()
        driveHandle?.let { handle ->
            if (rescanPartitionsOnClose) {
                issueSimpleControlCode(handle, IOCTL_DISK_UPDATE_PROPERTIES.toUInt())
            }
            CloseHandle(handle)
            driveHandle = null
        }
        alignedBuffer?.let { ptr ->
            VirtualFree(ptr, 0uL, MEM_RELEASE.toUInt())
            alignedBuffer = null
            alignedBufferSize = 0
        }
    }

    private fun ensureDriveHandle(): HANDLE = driveHandle ?: throw FlashStepException("DEVICE_NOT_OPEN", "Drive '$devicePath' is not open")

    private fun ensureAlignedBuffer(requiredBytes: Int): COpaquePointer {
        val current = alignedBuffer
        if (current != null && alignedBufferSize >= requiredBytes) {
            return current
        }
        current?.let { VirtualFree(it, 0uL, MEM_RELEASE.toUInt()) }
        val allocated =
            VirtualAlloc(
                lpAddress = null,
                dwSize = requiredBytes.toULong(),
                flAllocationType = (MEM_COMMIT or MEM_RESERVE).toUInt(),
                flProtect = PAGE_READWRITE.toUInt(),
            ) ?: throw FlashStepException("OUT_OF_MEMORY", "VirtualAlloc failed for $requiredBytes bytes")
        alignedBuffer = allocated
        alignedBufferSize = requiredBytes
        return allocated
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun interpretOffsetPointer(
    base: COpaquePointer,
    offsetBytes: Int,
): COpaquePointer =
    (base.reinterpret<ByteVar>() + offsetBytes)
        ?: error("Invalid pointer arithmetic offset $offsetBytes")

@OptIn(ExperimentalForeignApi::class)
private fun lockAndDismountVolume(driveLetter: String): HANDLE {
    val volumePath = "\\\\.\\$driveLetter"
    val handle =
        memScoped {
            val access = GENERIC_READ or GENERIC_WRITE.toUInt()
            val share = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt()
            val h = CreateFileW(volumePath.wcstr.ptr, access, share, null, OPEN_EXISTING.toUInt(), 0u, null)
            if (h == null || h == INVALID_HANDLE_VALUE) null else h
        } ?: throw FlashStepException(
            "UNMOUNT_FAILED",
            "Unable to open volume '$volumePath' for locking (error ${GetLastError()})",
        )

    return try {
        acquireVolumeLock(handle, volumePath)
        dismountLockedVolume(handle, volumePath)
        handle
    } catch (error: Throwable) {
        CloseHandle(handle)
        throw error
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun acquireVolumeLock(
    handle: HANDLE,
    volumePath: String,
) {
    for (attempt in 0 until LOCK_RETRY_ATTEMPTS) {
        if (issueSimpleControlCode(handle, FSCTL_LOCK_VOLUME.toUInt())) return
        if (attempt + 1 < LOCK_RETRY_ATTEMPTS) {
            Sleep(LOCK_RETRY_DELAY_MS)
        }
    }
    throw FlashStepException("UNMOUNT_FAILED", "Volume '$volumePath' is busy or in use (error ${GetLastError()})")
}

@OptIn(ExperimentalForeignApi::class)
private fun dismountLockedVolume(
    handle: HANDLE,
    volumePath: String,
) {
    if (!issueSimpleControlCode(handle, FSCTL_DISMOUNT_VOLUME.toUInt())) {
        throw FlashStepException("UNMOUNT_FAILED", "Failed dismounting volume '$volumePath' (error ${GetLastError()})")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun openWin32PhysicalDriveForWrite(devicePath: String): HANDLE =
    openWin32PhysicalDrive(
        devicePath = devicePath,
        flags = FILE_FLAG_NO_BUFFERING.toUInt() or FILE_FLAG_WRITE_THROUGH,
    )

@OptIn(ExperimentalForeignApi::class)
internal fun openWin32PhysicalDrive(
    devicePath: String,
    flags: UInt,
): HANDLE =
    memScoped {
        val access = GENERIC_READ or GENERIC_WRITE.toUInt()
        val share = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt()
        val handle = CreateFileW(devicePath.wcstr.ptr, access, share, null, OPEN_EXISTING.toUInt(), flags, null)
        if (handle != null && handle != INVALID_HANDLE_VALUE) return handle
        val err = GetLastError()
        val code = if (err == ERROR_ACCESS_DENIED.toUInt()) "PERMISSION_DENIED" else "DEVICE_OPEN_FAILED"
        throw FlashStepException(code, "Unable to open '$devicePath' for raw write (error $err)")
    }

@OptIn(ExperimentalForeignApi::class)
private fun allowMediaRemoval(handle: HANDLE) {
    memScoped {
        val request = alloc<PREVENT_MEDIA_REMOVAL>()
        request.PreventMediaRemoval = 0u
        val bytesReturned = alloc<DWORDVar>()
        DeviceIoControl(
            hDevice = handle,
            dwIoControlCode = IOCTL_STORAGE_MEDIA_REMOVAL.toUInt(),
            lpInBuffer = request.ptr,
            nInBufferSize = sizeOf<PREVENT_MEDIA_REMOVAL>().toUInt(),
            lpOutBuffer = null,
            nOutBufferSize = 0u,
            lpBytesReturned = bytesReturned.ptr,
            lpOverlapped = null,
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun querySectorSize(handle: HANDLE): Int? =
    memScoped {
        val geometry = alloc<DISK_GEOMETRY>()
        val bytesReturned = alloc<DWORDVar>()
        val ok =
            DeviceIoControl(
                hDevice = handle,
                dwIoControlCode = IOCTL_DISK_GET_DRIVE_GEOMETRY.toUInt(),
                lpInBuffer = null,
                nInBufferSize = 0u,
                lpOutBuffer = geometry.ptr,
                nOutBufferSize = sizeOf<DISK_GEOMETRY>().toUInt(),
                lpBytesReturned = bytesReturned.ptr,
                lpOverlapped = null,
            )
        if (ok != 0) geometry.BytesPerSector.toInt() else null
    }

@OptIn(ExperimentalForeignApi::class)
internal fun issueSimpleControlCode(
    handle: HANDLE,
    controlCode: UInt,
): Boolean =
    memScoped {
        val bytesReturned = alloc<DWORDVar>()
        DeviceIoControl(
            hDevice = handle,
            dwIoControlCode = controlCode,
            lpInBuffer = null,
            nInBufferSize = 0u,
            lpOutBuffer = null,
            nOutBufferSize = 0u,
            lpBytesReturned = bytesReturned.ptr,
            lpOverlapped = null,
        ) != 0
    }

@OptIn(ExperimentalForeignApi::class)
private fun seekWin32Handle(
    handle: HANDLE,
    offset: Long,
) {
    val seekTarget =
        cValue<LARGE_INTEGER> {
            QuadPart = offset
        }
    if (SetFilePointerEx(handle, seekTarget, null, FILE_BEGIN.toUInt()) == 0) {
        throw FlashStepException("WRITE_FAILED", "SetFilePointerEx failed at offset $offset (error ${GetLastError()})")
    }
}
