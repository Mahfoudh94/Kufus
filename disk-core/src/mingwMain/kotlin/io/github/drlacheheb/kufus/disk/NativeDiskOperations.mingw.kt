package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaque
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.get
import kotlinx.cinterop.interpretCPointer
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.nativeNullPtr
import kotlinx.cinterop.plus
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.posix.memcpy
import platform.windows.BusTypeUsb
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.CreateWindowExW
import platform.windows.DEVICE_NOTIFY_WINDOW_HANDLE
import platform.windows.DWORDVar
import platform.windows.DefWindowProcW
import platform.windows.DestroyWindow
import platform.windows.DeviceIoControl
import platform.windows.DispatchMessageW
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.GET_LENGTH_INFORMATION
import platform.windows.GUID_DEVINTERFACE_DISK
import platform.windows.GWLP_USERDATA
import platform.windows.GetExitCodeProcess
import platform.windows.GetLogicalDriveStringsW
import platform.windows.GetWindowLongPtrW
import platform.windows.GetWindowsDirectoryW
import platform.windows.HANDLE
import platform.windows.HWND
import platform.windows.HWND_MESSAGE
import platform.windows.INFINITE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.IOCTL_DISK_GET_LENGTH_INFO
import platform.windows.IOCTL_STORAGE_GET_DEVICE_NUMBER
import platform.windows.IOCTL_STORAGE_QUERY_PROPERTY
import platform.windows.MAX_PATH
import platform.windows.MSG
import platform.windows.MsgWaitForMultipleObjects
import platform.windows.OPEN_EXISTING
import platform.windows.PM_REMOVE
import platform.windows.PeekMessageW
import platform.windows.PropertyStandardQuery
import platform.windows.QS_ALLINPUT
import platform.windows.RegisterClassExW
import platform.windows.RegisterDeviceNotificationW
import platform.windows.SEE_MASK_NOCLOSEPROCESS
import platform.windows.SHELLEXECUTEINFOW
import platform.windows.STORAGE_DEVICE_DESCRIPTOR
import platform.windows.STORAGE_DEVICE_NUMBER
import platform.windows.STORAGE_PROPERTY_QUERY
import platform.windows.SW_HIDE
import platform.windows.SetWindowLongPtrW
import platform.windows.ShellExecuteExW
import platform.windows.Sleep
import platform.windows.StorageDeviceProperty
import platform.windows.TranslateMessage
import platform.windows.UnregisterDeviceNotification
import platform.windows.WM_DEVICECHANGE
import platform.windows.WNDCLASSEXW
import platform.windows.WaitForSingleObject

private const val MAX_PHYSICAL_DRIVES = 32
private const val DESCRIPTOR_BUFFER_SIZE = 1024
private const val LOGICAL_DRIVES_BUFFER_CHARS = 512
private const val WATCH_TIMEOUT_MS = 500u
private const val WATCH_DEBOUNCE_MS = 200u
private const val DBT_DEVTYP_DEVICEINTERFACE_VAL = 5u

private class WindowsWatchState {
    var dirty: Boolean = false
}

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
actual fun watchPlatformUsbDrives(
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    val initial = listPlatformUsbDrives()
    onDrivesUpdated(initial)
    val state = WindowsWatchState()
    val stateRef = StableRef.create(state)
    val window = createDeviceWatcherWindow(stateRef)
    if (window == null) {
        stateRef.dispose()
        return
    }
    val notifyHandle = registerDiskInterfaceNotification(window)
    try {
        runWindowsWatchLoop(window, state, initial, onDrivesUpdated, shouldContinue)
    } finally {
        if (notifyHandle != null) {
            UnregisterDeviceNotification(notifyHandle)
        }
        SetWindowLongPtrW(window, GWLP_USERDATA, 0L)
        DestroyWindow(window)
        stateRef.dispose()
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun runWindowsWatchLoop(
    window: HWND,
    state: WindowsWatchState,
    initialDrives: List<UsbDrive>,
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    var lastEmitted = initialDrives
    while (shouldContinue()) {
        MsgWaitForMultipleObjects(0u, null, 0, WATCH_TIMEOUT_MS, QS_ALLINPUT.toUInt())
        pumpWindowMessages(window)
        if (!state.dirty) continue
        Sleep(WATCH_DEBOUNCE_MS)
        state.dirty = false
        pumpWindowMessages(window)
        val updated = listPlatformUsbDrives()
        if (updated != lastEmitted) {
            lastEmitted = updated
            onDrivesUpdated(updated)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun launchPlatformElevatedProcess(
    executablePath: String,
    arguments: List<String>,
): Int {
    val formattedArgs = formatWindowsCommandArguments(arguments)
    return memScoped {
        val execInfo = alloc<SHELLEXECUTEINFOW>()
        execInfo.cbSize = sizeOf<SHELLEXECUTEINFOW>().toUInt()
        execInfo.fMask = SEE_MASK_NOCLOSEPROCESS.toUInt()
        execInfo.lpVerb = "runas".wcstr.ptr
        execInfo.lpFile = executablePath.wcstr.ptr
        execInfo.lpParameters = formattedArgs?.wcstr?.ptr
        execInfo.nShow = SW_HIDE
        if (ShellExecuteExW(execInfo.ptr) == 0) {
            return -1
        }
        val processHandle = execInfo.hProcess ?: return -1
        try {
            WaitForSingleObject(processHandle, INFINITE)
            val exitCode = alloc<DWORDVar>()
            if (GetExitCodeProcess(processHandle, exitCode.ptr) != 0) exitCode.value.toInt() else -1
        } finally {
            CloseHandle(processHandle)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createDeviceWatcherWindow(stateRef: StableRef<WindowsWatchState>): HWND? =
    memScoped {
        val className = "KufusDiskWatcherWindow".wcstr.ptr
        val wndClass = alloc<WNDCLASSEXW>()
        wndClass.cbSize = sizeOf<WNDCLASSEXW>().toUInt()
        wndClass.lpfnWndProc =
            staticCFunction { hwnd, message, wParam, lParam ->
                if (message == WM_DEVICECHANGE.toUInt()) {
                    val rawPtr = GetWindowLongPtrW(hwnd, GWLP_USERDATA)
                    if (rawPtr != 0L) {
                        interpretWatchState(rawPtr)?.dirty = true
                    }
                }
                DefWindowProcW(hwnd, message, wParam, lParam)
            }
        wndClass.lpszClassName = className
        RegisterClassExW(wndClass.ptr)
        val hwnd =
            CreateWindowExW(
                dwExStyle = 0u,
                lpClassName = className,
                lpWindowName = null,
                dwStyle = 0u,
                X = 0,
                Y = 0,
                nWidth = 0,
                nHeight = 0,
                hWndParent = HWND_MESSAGE,
                hMenu = null,
                hInstance = null,
                lpParam = null,
            ) ?: return null
        SetWindowLongPtrW(hwnd, GWLP_USERDATA, stateRef.asCPointer().rawValue.toLong())
        hwnd
    }

@OptIn(ExperimentalForeignApi::class)
private fun interpretWatchState(rawPtr: Long): WindowsWatchState? {
    val pointer = interpretCPointer<COpaque>(nativeNullPtr + rawPtr) ?: return null
    return pointer.asStableRef<WindowsWatchState>().get()
}

private const val DEV_BROADCAST_DEVICEINTERFACE_HEADER_BYTES = 12
private const val DEV_BROADCAST_DEVICEINTERFACE_SIZE = 32u
private const val GUID_BYTE_SIZE = 16uL

@OptIn(ExperimentalForeignApi::class)
private fun registerDiskInterfaceNotification(hwnd: HWND): HANDLE? =
    memScoped {
        val filterBytes = allocArray<ByteVar>(DEV_BROADCAST_DEVICEINTERFACE_SIZE.toInt())
        val headerWords = filterBytes.reinterpret<UIntVar>()
        headerWords[0] = DEV_BROADCAST_DEVICEINTERFACE_SIZE
        headerWords[1] = DBT_DEVTYP_DEVICEINTERFACE_VAL
        headerWords[2] = 0u
        memcpy(filterBytes + DEV_BROADCAST_DEVICEINTERFACE_HEADER_BYTES, GUID_DEVINTERFACE_DISK.ptr, GUID_BYTE_SIZE)
        RegisterDeviceNotificationW(hwnd, filterBytes, DEVICE_NOTIFY_WINDOW_HANDLE.toUInt())
    }

@OptIn(ExperimentalForeignApi::class)
private fun pumpWindowMessages(hwnd: HWND) {
    memScoped {
        val msg = alloc<MSG>()
        while (PeekMessageW(msg.ptr, hwnd, 0u, 0u, PM_REMOVE.toUInt()) != 0) {
            TranslateMessage(msg.ptr)
            DispatchMessageW(msg.ptr)
        }
    }
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
internal fun mapVolumeLettersToDiskNumbers(): Map<Int, List<String>> =
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
internal fun queryDiskLength(handle: HANDLE): Long? =
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
internal fun openReadMetadataHandle(path: String): HANDLE? =
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
