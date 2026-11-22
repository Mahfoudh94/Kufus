package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.get
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.BusTypeUsb
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.DeviceIoControl
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GET_LENGTH_INFORMATION
import platform.windows.GetLogicalDriveStringsW
import platform.windows.GetWindowsDirectoryW
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.IOCTL_DISK_GET_LENGTH_INFO
import platform.windows.IOCTL_STORAGE_GET_DEVICE_NUMBER
import platform.windows.IOCTL_STORAGE_QUERY_PROPERTY
import platform.windows.MAX_PATH
import platform.windows.OPEN_EXISTING
import platform.windows.PropertyStandardQuery
import platform.windows.STORAGE_DEVICE_DESCRIPTOR
import platform.windows.STORAGE_DEVICE_NUMBER
import platform.windows.STORAGE_PROPERTY_QUERY
import platform.windows.StorageDeviceProperty

private const val MAX_PHYSICAL_DRIVES = 32
private const val DESCRIPTOR_BUFFER_SIZE = 1024
private const val LOGICAL_DRIVES_BUFFER_CHARS = 512

@OptIn(ExperimentalForeignApi::class)
actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val systemDrive = detectWindowsSystemDrive()
    val mountsByDisk = mapVolumeLettersToDiskNumbers()
    val drives =
        (0 until MAX_PHYSICAL_DRIVES).mapNotNull { index ->
            queryPhysicalDrive(
                diskIndex = index,
                mountPoints = mountsByDisk[index].orEmpty(),
                systemDrive = systemDrive,
            )
        }
    return filterSafeUsbDrives(drives)
}

@OptIn(ExperimentalForeignApi::class)
private fun detectWindowsSystemDrive(): String =
    memScoped {
        val buffer = allocArray<UShortVar>(MAX_PATH)
        val length = GetWindowsDirectoryW(buffer, MAX_PATH.toUInt()).toInt()
        if (length >= 2) {
            normalizeWindowsDriveLetter(buffer.toKString().take(2))
        } else {
            "C:"
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun mapVolumeLettersToDiskNumbers(): Map<Int, List<String>> =
    memScoped {
        val buffer = allocArray<UShortVar>(LOGICAL_DRIVES_BUFFER_CHARS)
        val length = GetLogicalDriveStringsW(LOGICAL_DRIVES_BUFFER_CHARS.toUInt(), buffer).toInt()
        if (length <= 0 || length >= LOGICAL_DRIVES_BUFFER_CHARS) return emptyMap()
        val mapping = linkedMapOf<Int, MutableList<String>>()
        var offset = 0
        while (offset < length && buffer[offset] != 0.toUShort()) {
            val rootPtr = buffer + offset
            val rootStr = rootPtr?.toKString().orEmpty()
            offset += rootStr.length + 1
            val driveLetter = normalizeWindowsDriveLetter(rootStr)
            if (driveLetter.isNotEmpty()) {
                val diskNumber = queryVolumeDiskNumber("\\\\.\\$driveLetter")
                if (diskNumber != null) {
                    mapping.getOrPut(diskNumber) { mutableListOf() }.add(driveLetter)
                }
            }
        }
        mapping
    }

@OptIn(ExperimentalForeignApi::class)
private fun queryVolumeDiskNumber(volumePath: String): Int? =
    memScoped {
        val handle = openReadMetadataHandle(volumePath) ?: return null
        try {
            val deviceNumber = alloc<STORAGE_DEVICE_NUMBER>()
            val bytesReturned = alloc<DWORDVar>()
            val ok =
                DeviceIoControl(
                    hDevice = handle,
                    dwIoControlCode = IOCTL_STORAGE_GET_DEVICE_NUMBER.toUInt(),
                    lpInBuffer = null,
                    nInBufferSize = 0u,
                    lpOutBuffer = deviceNumber.ptr,
                    nOutBufferSize = sizeOf<STORAGE_DEVICE_NUMBER>().toUInt(),
                    lpBytesReturned = bytesReturned.ptr,
                    lpOverlapped = null,
                )
            if (ok != 0) deviceNumber.DeviceNumber.toInt() else null
        } finally {
            CloseHandle(handle)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun queryPhysicalDrive(
    diskIndex: Int,
    mountPoints: List<String>,
    systemDrive: String,
): UsbDrive? {
    val devicePath = "\\\\.\\PhysicalDrive$diskIndex"
    val handle = openReadMetadataHandle(devicePath) ?: return null
    try {
        return memScoped {
            val query = alloc<STORAGE_PROPERTY_QUERY>()
            query.PropertyId = StorageDeviceProperty
            query.QueryType = PropertyStandardQuery
            val descriptorBuffer = allocArray<ByteVar>(DESCRIPTOR_BUFFER_SIZE)
            val bytesReturned = alloc<DWORDVar>()
            val ok =
                DeviceIoControl(
                    hDevice = handle,
                    dwIoControlCode = IOCTL_STORAGE_QUERY_PROPERTY.toUInt(),
                    lpInBuffer = query.ptr,
                    nInBufferSize = sizeOf<STORAGE_PROPERTY_QUERY>().toUInt(),
                    lpOutBuffer = descriptorBuffer,
                    nOutBufferSize = DESCRIPTOR_BUFFER_SIZE.toUInt(),
                    lpBytesReturned = bytesReturned.ptr,
                    lpOverlapped = null,
                )
            if (ok == 0) return null
            val descriptor = descriptorBuffer.reinterpret<STORAGE_DEVICE_DESCRIPTOR>().pointed
            if (descriptor.BusType != BusTypeUsb) return null
            val sizeBytes = queryDiskLength(handle) ?: return null
            val vendor = readCStringAtOffset(descriptorBuffer, bytesReturned.value, descriptor.VendorIdOffset)
            val product = readCStringAtOffset(descriptorBuffer, bytesReturned.value, descriptor.ProductIdOffset)
            val serial = readCStringAtOffset(descriptorBuffer, bytesReturned.value, descriptor.SerialNumberOffset)
            val diskName = "PhysicalDrive$diskIndex"
            val isSystem = mountPoints.any { isWindowsSystemMount(it, systemDrive) }
            UsbDrive(
                id = devicePath,
                name = diskName,
                model = buildWindowsDriveModel(vendor, product, diskName),
                sizeBytes = sizeBytes,
                serial = serial,
                mountPoints = mountPoints,
                isRemovable = descriptor.RemovableMedia.toInt() != 0 || descriptor.BusType == BusTypeUsb,
                isSystemDisk = isSystem,
            )
        }
    } finally {
        CloseHandle(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun queryDiskLength(handle: HANDLE): Long? =
    memScoped {
        val lengthInfo = alloc<GET_LENGTH_INFORMATION>()
        val bytesReturned = alloc<DWORDVar>()
        val ok =
            DeviceIoControl(
                hDevice = handle,
                dwIoControlCode = IOCTL_DISK_GET_LENGTH_INFO.toUInt(),
                lpInBuffer = null,
                nInBufferSize = 0u,
                lpOutBuffer = lengthInfo.ptr,
                nOutBufferSize = sizeOf<GET_LENGTH_INFORMATION>().toUInt(),
                lpBytesReturned = bytesReturned.ptr,
                lpOverlapped = null,
            )
        if (ok != 0) lengthInfo.Length.QuadPart else null
    }

@OptIn(ExperimentalForeignApi::class)
private fun openReadMetadataHandle(path: String): HANDLE? =
    memScoped {
        val handle =
            CreateFileW(
                lpFileName = path.wcstr.ptr,
                dwDesiredAccess = 0u,
                dwShareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE).toUInt(),
                lpSecurityAttributes = null,
                dwCreationDisposition = OPEN_EXISTING.toUInt(),
                dwFlagsAndAttributes = 0u,
                hTemplateFile = null,
            )
        if (handle == INVALID_HANDLE_VALUE) null else handle
    }

@OptIn(ExperimentalForeignApi::class)
private fun readCStringAtOffset(
    basePtr: CPointer<ByteVar>,
    totalBytes: UInt,
    offset: UInt,
): String? {
    if (offset == 0u || offset >= totalBytes) return null
    return (basePtr + offset.toInt())?.toKString()?.trim()?.takeIf { it.isNotEmpty() }
}
