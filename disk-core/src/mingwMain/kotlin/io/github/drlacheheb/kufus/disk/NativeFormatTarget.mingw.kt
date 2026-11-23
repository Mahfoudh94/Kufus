package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.CFunction
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.invoke
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CloseHandle
import platform.windows.DWORDVar
import platform.windows.DeviceIoControl
import platform.windows.FindFirstVolumeW
import platform.windows.FindNextVolumeW
import platform.windows.FindVolumeClose
import platform.windows.FreeLibrary
import platform.windows.GetLastError
import platform.windows.GetProcAddress
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.IOCTL_DISK_UPDATE_PROPERTIES
import platform.windows.IOCTL_STORAGE_GET_DEVICE_NUMBER
import platform.windows.LoadLibraryW
import platform.windows.MAX_PATH
import platform.windows.STORAGE_DEVICE_NUMBER
import platform.windows.Sleep
import kotlin.native.concurrent.ThreadLocal

private const val WIN32_VOLUME_POLL_ATTEMPTS = 50
private const val WIN32_VOLUME_POLL_DELAY_MS = 200u
private const val FMIFS_HARDDISK = 0x0Cu
private const val FMIFS_CB_PROGRESS = 0u
private const val FMIFS_CB_DONE = 11u
private const val FMIFS_CB_INCOMPATIBLEMEDIA = 6u
private const val FMIFS_CB_ACCESSDENIED = 7u
private const val FMIFS_CB_WRITEPROTECTED = 8u
private const val FMIFS_CB_CANTLOCK = 9u
private const val FMIFS_CB_CANTQUICKFORMAT = 10u

private class Win32FormatSessionState {
    var doneReceived: Boolean = false
    var succeeded: Boolean = false
    var failureCode: String = "FORMAT_FAILED"
    var failureReason: String? = null
    var onProgress: ((Long, Long) -> Unit)? = null
}

@ThreadLocal
private var activeWin32FormatSession: Win32FormatSessionState? = null

@OptIn(ExperimentalForeignApi::class)
private typealias Win32FormatExProc =
    CFunction<
        (
            CPointer<UShortVar>?,
            UInt,
            CPointer<UShortVar>?,
            CPointer<UShortVar>?,
            UByte,
            UInt,
            CPointer<CFunction<(UInt, UInt, COpaquePointer?) -> UByte>>?,
        ) -> Unit,
    >

actual fun currentNativeHostOs(): HostOsKind = HostOsKind.WINDOWS

@OptIn(ExperimentalForeignApi::class)
actual fun formatPlatformPartitionFilesystem(
    devicePath: String,
    partitionIndex: Int,
    request: FormatRequest,
    onProgress: (Long, Long) -> Unit,
): Result<Unit> =
    runCatching {
        val diskIndex =
            parsePhysicalDriveIndex(devicePath)
                ?: throw FlashStepException("INVALID_DEVICE", "Windows target must be PhysicalDriveN: '$devicePath'")
        val fsName =
            when (request.filesystem) {
                FilesystemType.EXFAT -> "EXFAT"
                FilesystemType.NTFS -> "NTFS"
                FilesystemType.FAT32,
                FilesystemType.EXT4,
                ->
                    throw FlashStepException(
                        code = "UNSUPPORTED_FILESYSTEM",
                        message = "External Windows formatter does not handle ${request.filesystem}.",
                    )
            }
        refreshWin32DiskProperties(devicePath)
        val volumeRoot = waitForWin32PartitionVolumeRoot(diskIndex, partitionIndex)
        val cleanLabel = sanitizeVolumeLabel(request.volumeLabel, request.filesystem)
        invokeWin32FormatEx(
            volumeRoot = volumeRoot,
            filesystemName = fsName,
            volumeLabel = cleanLabel,
            clusterSizeBytes = request.clusterSizeBytes ?: 0,
            onProgress = onProgress,
        )
    }

actual fun finalizePlatformFormattedDisk(
    devicePath: String,
    request: FormatRequest,
) = Unit

@OptIn(ExperimentalForeignApi::class)
private fun refreshWin32DiskProperties(devicePath: String) {
    val handle = openWin32PhysicalDrive(devicePath, flags = 0u)
    try {
        issueSimpleControlCode(handle, IOCTL_DISK_UPDATE_PROPERTIES.toUInt())
    } finally {
        CloseHandle(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun waitForWin32PartitionVolumeRoot(
    diskIndex: Int,
    partitionNumber: Int,
): String {
    repeat(WIN32_VOLUME_POLL_ATTEMPTS) {
        findMatchingVolumeGuidRoot(diskIndex, partitionNumber)?.let { return it }
        Sleep(WIN32_VOLUME_POLL_DELAY_MS)
    }
    throw FlashStepException(
        code = "PARTITION_NOT_FOUND",
        message = "Windows did not expose a volume for PhysicalDrive$diskIndex partition $partitionNumber.",
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun findMatchingVolumeGuidRoot(
    diskIndex: Int,
    partitionNumber: Int,
): String? =
    memScoped {
        val buffer = allocArray<UShortVar>(MAX_PATH)
        val findHandle = FindFirstVolumeW(buffer, MAX_PATH.toUInt())
        if (findHandle == null || findHandle == INVALID_HANDLE_VALUE) return null
        try {
            do {
                val volumeRoot = buffer.toKString()
                if (matchesDiskPartitionVolume(volumeRoot, diskIndex, partitionNumber)) {
                    return volumeRoot
                }
            } while (FindNextVolumeW(findHandle, buffer, MAX_PATH.toUInt()) != 0)
            null
        } finally {
            FindVolumeClose(findHandle)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun matchesDiskPartitionVolume(
    volumeRootWithSlash: String,
    diskIndex: Int,
    partitionNumber: Int,
): Boolean {
    val volumeDevicePath = volumeRootWithSlash.trimEnd('\\')
    val handle = openReadMetadataHandle(volumeDevicePath) ?: return false
    try {
        return memScoped {
            val numberInfo = alloc<STORAGE_DEVICE_NUMBER>()
            val returned = alloc<DWORDVar>()
            val ok =
                DeviceIoControl(
                    hDevice = handle,
                    dwIoControlCode = IOCTL_STORAGE_GET_DEVICE_NUMBER.toUInt(),
                    lpInBuffer = null,
                    nInBufferSize = 0u,
                    lpOutBuffer = numberInfo.ptr,
                    nOutBufferSize = sizeOf<STORAGE_DEVICE_NUMBER>().toUInt(),
                    lpBytesReturned = returned.ptr,
                    lpOverlapped = null,
                )
            ok != 0 &&
                numberInfo.DeviceNumber.toInt() == diskIndex &&
                numberInfo.PartitionNumber.toInt() == partitionNumber
        }
    } finally {
        CloseHandle(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun invokeWin32FormatEx(
    volumeRoot: String,
    filesystemName: String,
    volumeLabel: String,
    clusterSizeBytes: Int,
    onProgress: (Long, Long) -> Unit,
) {
    val fmifsModule =
        memScoped {
            LoadLibraryW("fmifs.dll".wcstr.ptr)
        } ?: throw FlashStepException("TOOL_NOT_FOUND", "Unable to load Windows fmifs.dll (error ${GetLastError()}).")

    val session = Win32FormatSessionState().apply { this.onProgress = onProgress }
    activeWin32FormatSession = session
    try {
        val formatEx = resolveFormatExProc(fmifsModule)
        val callback =
            staticCFunction<UInt, UInt, COpaquePointer?, UByte> { command, _, actionInfo ->
                handleFmifsCallback(command, actionInfo)
                1u
            }
        memScoped {
            formatEx.invoke(
                volumeRoot.wcstr.ptr,
                FMIFS_HARDDISK,
                filesystemName.wcstr.ptr,
                volumeLabel.wcstr.ptr,
                1u,
                clusterSizeBytes.toUInt(),
                callback,
            )
        }
        if (!session.succeeded) {
            val message =
                session.failureReason
                    ?: "Windows $filesystemName formatting did not complete cleanly."
            throw FlashStepException(code = session.failureCode, message = message)
        }
        onProgress(100L, 100L)
    } finally {
        activeWin32FormatSession = null
        FreeLibrary(fmifsModule)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun resolveFormatExProc(fmifsModule: platform.windows.HMODULE): CPointer<Win32FormatExProc> {
    val procAddress =
        GetProcAddress(fmifsModule, "FormatEx")
            ?: throw FlashStepException("TOOL_NOT_FOUND", "FormatEx export not found in fmifs.dll.")
    return procAddress.reinterpret()
}

@OptIn(ExperimentalForeignApi::class)
private fun handleFmifsCallback(
    command: UInt,
    actionInfo: COpaquePointer?,
) {
    val session = activeWin32FormatSession ?: return
    when (command) {
        FMIFS_CB_PROGRESS -> {
            val rawPercent =
                if (actionInfo != null) {
                    val percentPtr = actionInfo.reinterpret<UIntVar>()
                    percentPtr.pointed.value.toLong()
                } else {
                    0L
                }
            session.onProgress?.invoke(rawPercent.coerceIn(0L, 100L), 100L)
        }
        FMIFS_CB_DONE -> {
            val okByte =
                if (actionInfo != null) {
                    val donePtr = actionInfo.reinterpret<UByteVar>()
                    donePtr.pointed.value
                } else {
                    0u
                }
            session.doneReceived = true
            session.succeeded = okByte != 0.toUByte()
            if (!session.succeeded && session.failureReason == null) {
                session.failureReason = "Windows FormatEx reported a formatting failure."
            }
        }
        FMIFS_CB_WRITEPROTECTED -> {
            session.failureCode = "DEVICE_READ_ONLY"
            session.failureReason = "Target USB volume is write-protected."
        }
        FMIFS_CB_ACCESSDENIED -> {
            session.failureCode = "PERMISSION_DENIED"
            session.failureReason = "Administrator privileges are required to format this volume."
        }
        FMIFS_CB_CANTLOCK -> {
            session.failureCode = "UNMOUNT_FAILED"
            session.failureReason = "Volume is in use by another process."
        }
        FMIFS_CB_INCOMPATIBLEMEDIA,
        FMIFS_CB_CANTQUICKFORMAT,
        -> {
            session.failureCode = "FORMAT_FAILED"
            session.failureReason = "Drive rejected $command during Windows format."
        }
    }
}
