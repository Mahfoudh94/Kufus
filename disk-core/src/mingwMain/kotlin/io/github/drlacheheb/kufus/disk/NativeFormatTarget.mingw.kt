package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import platform.windows.CloseHandle
import platform.windows.DWORDVar
import platform.windows.DeviceIoControl
import platform.windows.FindFirstVolumeW
import platform.windows.FindNextVolumeW
import platform.windows.FindVolumeClose
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.IOCTL_DISK_UPDATE_PROPERTIES
import platform.windows.IOCTL_STORAGE_GET_DEVICE_NUMBER
import platform.windows.MAX_PATH
import platform.windows.STORAGE_DEVICE_NUMBER
import platform.windows.Sleep

private const val WIN32_VOLUME_POLL_ATTEMPTS = 50
private const val WIN32_VOLUME_POLL_DELAY_MS = 200u

actual fun currentNativeHostOs(): HostOsKind = HostOsKind.WINDOWS

actual fun finalizePlatformFormattedDisk(
    devicePath: String,
    request: FormatRequest,
) = Unit

@OptIn(ExperimentalForeignApi::class)
internal fun refreshWin32DiskProperties(devicePath: String) {
    val handle = openWin32PhysicalDrive(devicePath, flags = 0u)
    try {
        issueSimpleControlCode(handle, IOCTL_DISK_UPDATE_PROPERTIES.toUInt())
    } finally {
        CloseHandle(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun waitForWin32PartitionVolumeRoot(
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
