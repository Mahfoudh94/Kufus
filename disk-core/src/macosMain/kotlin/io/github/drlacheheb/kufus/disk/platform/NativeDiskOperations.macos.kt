package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.parser.MacosMountEntry
import io.github.drlacheheb.kufus.disk.parser.MacosWholeDiskEntry
import io.github.drlacheheb.kufus.disk.parser.buildMacosUsbDrives
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.LongVar
import kotlinx.cinterop.StableRef
import kotlinx.cinterop.UIntVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.asStableRef
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.staticCFunction
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value
import platform.CoreFoundation.CFArrayRef
import platform.CoreFoundation.CFBooleanGetTypeID
import platform.CoreFoundation.CFBooleanGetValue
import platform.CoreFoundation.CFDictionaryGetValue
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFGetTypeID
import platform.CoreFoundation.CFNumberGetTypeID
import platform.CoreFoundation.CFNumberGetValue
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRunLoopGetCurrent
import platform.CoreFoundation.CFRunLoopRunInMode
import platform.CoreFoundation.CFStringGetCString
import platform.CoreFoundation.CFStringGetLength
import platform.CoreFoundation.CFStringGetMaximumSizeForEncoding
import platform.CoreFoundation.CFStringGetTypeID
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFURLCopyFileSystemPath
import platform.CoreFoundation.CFURLGetTypeID
import platform.CoreFoundation.kCFAllocatorDefault
import platform.CoreFoundation.kCFNumberSInt64Type
import platform.CoreFoundation.kCFRunLoopDefaultMode
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFURLPOSIXPathStyle
import platform.DiskArbitration.DADiskCopyDescription
import platform.DiskArbitration.DADiskCreateFromIOMedia
import platform.DiskArbitration.DADiskRef
import platform.DiskArbitration.DARegisterDiskAppearedCallback
import platform.DiskArbitration.DARegisterDiskDescriptionChangedCallback
import platform.DiskArbitration.DARegisterDiskDisappearedCallback
import platform.DiskArbitration.DASessionCreate
import platform.DiskArbitration.DASessionRef
import platform.DiskArbitration.DASessionScheduleWithRunLoop
import platform.DiskArbitration.DASessionUnscheduleFromRunLoop
import platform.DiskArbitration.DAUnregisterCallback
import platform.DiskArbitration.kDADiskDescriptionDeviceInternalKey
import platform.DiskArbitration.kDADiskDescriptionDeviceModelKey
import platform.DiskArbitration.kDADiskDescriptionDeviceProtocolKey
import platform.DiskArbitration.kDADiskDescriptionDeviceVendorKey
import platform.DiskArbitration.kDADiskDescriptionMediaBSDNameKey
import platform.DiskArbitration.kDADiskDescriptionMediaEjectableKey
import platform.DiskArbitration.kDADiskDescriptionMediaNameKey
import platform.DiskArbitration.kDADiskDescriptionMediaRemovableKey
import platform.DiskArbitration.kDADiskDescriptionMediaSizeKey
import platform.DiskArbitration.kDADiskDescriptionMediaWholeKey
import platform.DiskArbitration.kDADiskDescriptionVolumePathKey
import platform.IOKit.IOIteratorNext
import platform.IOKit.IOObjectConformsTo
import platform.IOKit.IOObjectRelease
import platform.IOKit.IOObjectRetain
import platform.IOKit.IORegistryEntryGetParentEntry
import platform.IOKit.IOServiceGetMatchingServices
import platform.IOKit.IOServiceMatching
import platform.IOKit.kIOMainPortDefault
import platform.IOKit.kIOMediaClass
import platform.IOKit.kIOServicePlane
import platform.darwin.KERN_SUCCESS

private const val MAX_IOREGISTRY_DEPTH = 16
private const val RUNLOOP_SLICE_SECONDS = 0.5

private val virtualMediaClasses =
    listOf("AppleAPFSMedia", "IOHDIXHDDrive", "CoreStoragePhysical", "CoreStorageLogical")

private val usbControllerClasses =
    listOf("IOUSBHostDevice", "IOUSBDevice")

private class MacosWatchState {
    var dirty: Boolean = false
}

@OptIn(ExperimentalForeignApi::class)
actual fun listPlatformUsbDrives(): List<UsbDrive> {
    val session = DASessionCreate(kCFAllocatorDefault) ?: return emptyList()
    try {
        val iterator = createMediaIterator() ?: return emptyList()
        try {
            val wholeDisks = mutableListOf<MacosWholeDiskEntry>()
            val mounts = mutableListOf<MacosMountEntry>()
            collectMediaEntries(
                session = session,
                iterator = iterator,
                wholeDisks = wholeDisks,
                mounts = mounts,
            )
            return buildMacosUsbDrives(disks = wholeDisks, mounts = mounts)
        } finally {
            IOObjectRelease(iterator)
        }
    } finally {
        CFRelease(session)
    }
}

@OptIn(ExperimentalForeignApi::class)
actual fun watchPlatformUsbDrives(
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    val initial = listPlatformUsbDrives()
    onDrivesUpdated(initial)
    val session = DASessionCreate(kCFAllocatorDefault) ?: return
    val runLoop = CFRunLoopGetCurrent()
    val state = MacosWatchState()
    val stateRef = StableRef.create(state)
    val appearedCallback = staticCFunction<DADiskRef?, COpaquePointer?, Unit> { _, ctx -> markMacosWatchDirty(ctx) }
    val disappearedCallback = staticCFunction<DADiskRef?, COpaquePointer?, Unit> { _, ctx -> markMacosWatchDirty(ctx) }
    val changedCallback =
        staticCFunction<DADiskRef?, CFArrayRef?, COpaquePointer?, Unit> { _, _, ctx -> markMacosWatchDirty(ctx) }
    DARegisterDiskAppearedCallback(session, null, appearedCallback, stateRef.asCPointer())
    DARegisterDiskDisappearedCallback(session, null, disappearedCallback, stateRef.asCPointer())
    DARegisterDiskDescriptionChangedCallback(session, null, null, changedCallback, stateRef.asCPointer())
    DASessionScheduleWithRunLoop(session, runLoop, kCFRunLoopDefaultMode)
    try {
        runMacosRunLoopWatch(state, initial, onDrivesUpdated, shouldContinue)
    } finally {
        DAUnregisterCallback(session, appearedCallback, stateRef.asCPointer())
        DAUnregisterCallback(session, disappearedCallback, stateRef.asCPointer())
        DAUnregisterCallback(session, changedCallback, stateRef.asCPointer())
        DASessionUnscheduleFromRunLoop(session, runLoop, kCFRunLoopDefaultMode)
        stateRef.dispose()
        CFRelease(session)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun runMacosRunLoopWatch(
    state: MacosWatchState,
    initialDrives: List<UsbDrive>,
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean,
) {
    var lastEmitted = initialDrives
    while (shouldContinue()) {
        CFRunLoopRunInMode(kCFRunLoopDefaultMode, RUNLOOP_SLICE_SECONDS, true)
        if (!state.dirty) continue
        state.dirty = false
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

@OptIn(ExperimentalForeignApi::class)
private fun markMacosWatchDirty(context: COpaquePointer?) {
    context?.asStableRef<MacosWatchState>()?.get()?.dirty = true
}

@OptIn(ExperimentalForeignApi::class)
private fun createMediaIterator(): UInt? =
    memScoped {
        val matchingDict = IOServiceMatching(kIOMediaClass) ?: return null
        val iteratorVar = alloc<UIntVar>()
        val result = IOServiceGetMatchingServices(kIOMainPortDefault, matchingDict, iteratorVar.ptr)
        if (result == KERN_SUCCESS) iteratorVar.value else null
    }

@OptIn(ExperimentalForeignApi::class)
private fun collectMediaEntries(
    session: DASessionRef,
    iterator: UInt,
    wholeDisks: MutableList<MacosWholeDiskEntry>,
    mounts: MutableList<MacosMountEntry>,
) {
    var media = IOIteratorNext(iterator)
    while (media != 0u) {
        try {
            processMediaService(
                session = session,
                media = media,
                wholeDisks = wholeDisks,
                mounts = mounts,
            )
        } finally {
            IOObjectRelease(media)
        }
        media = IOIteratorNext(iterator)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun processMediaService(
    session: DASessionRef,
    media: UInt,
    wholeDisks: MutableList<MacosWholeDiskEntry>,
    mounts: MutableList<MacosMountEntry>,
) {
    val disk = DADiskCreateFromIOMedia(kCFAllocatorDefault, session, media) ?: return
    try {
        val description = DADiskCopyDescription(disk) ?: return
        try {
            val bsdName = dictionaryString(description, kDADiskDescriptionMediaBSDNameKey) ?: return
            val isWhole = dictionaryBool(description, kDADiskDescriptionMediaWholeKey) == true
            val isVirtual = isVirtualMediaService(media)
            if (isWhole) {
                wholeDisks += buildWholeDiskEntry(media, description, bsdName, isVirtual)
            }
            val volumePath = dictionaryUrlPath(description, kDADiskDescriptionVolumePathKey)
            if (volumePath != null) {
                val wholeOwner = findPhysicalWholeBsdName(session, media) ?: bsdName
                mounts += MacosMountEntry(parentWholeBsdName = wholeOwner, volumePath = volumePath)
            }
        } finally {
            CFRelease(description)
        }
    } finally {
        CFRelease(disk)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun buildWholeDiskEntry(
    media: UInt,
    description: CFDictionaryRef,
    bsdName: String,
    isVirtual: Boolean,
): MacosWholeDiskEntry {
    val vendor = dictionaryString(description, kDADiskDescriptionDeviceVendorKey)?.trim().orEmpty()
    val model = dictionaryString(description, kDADiskDescriptionDeviceModelKey)?.trim().orEmpty()
    val mediaName = dictionaryString(description, kDADiskDescriptionMediaNameKey)?.trim().orEmpty()
    val combinedModel =
        listOf(vendor, model)
            .filter { it.isNotEmpty() }
            .joinToString(separator = " ")
            .ifEmpty { mediaName.ifEmpty { bsdName } }
    val protocol = dictionaryString(description, kDADiskDescriptionDeviceProtocolKey)?.trim().orEmpty()
    val isInternal = dictionaryBool(description, kDADiskDescriptionDeviceInternalKey) == true
    val isRemovable =
        dictionaryBool(description, kDADiskDescriptionMediaRemovableKey) == true ||
            dictionaryBool(description, kDADiskDescriptionMediaEjectableKey) == true ||
            !isInternal
    val sizeBytes = dictionaryLong(description, kDADiskDescriptionMediaSizeKey) ?: 0L
    val isUsbBus = protocol.equals("USB", ignoreCase = true) || hasUsbParentService(media)
    return MacosWholeDiskEntry(
        bsdName = bsdName,
        modelName = combinedModel,
        busProtocol = protocol,
        sizeBytes = sizeBytes,
        isInternal = isInternal,
        isRemovable = isRemovable,
        isVirtual = isVirtual,
        isUsbBus = isUsbBus,
    )
}

@OptIn(ExperimentalForeignApi::class)
private fun findPhysicalWholeBsdName(
    session: DASessionRef,
    startMedia: UInt,
): String? {
    var current = startMedia
    IOObjectRetain(current)
    var depth = 0
    while (current != 0u && depth < MAX_IOREGISTRY_DEPTH) {
        val candidateBsd = extractPhysicalWholeBsdName(session, current)
        if (candidateBsd != null) {
            IOObjectRelease(current)
            return candidateBsd
        }
        val parent = readParentEntry(current)
        IOObjectRelease(current)
        current = parent
        depth++
    }
    if (current != 0u) {
        IOObjectRelease(current)
    }
    return null
}

@OptIn(ExperimentalForeignApi::class)
private fun extractPhysicalWholeBsdName(
    session: DASessionRef,
    entry: UInt,
): String? {
    if (isVirtualMediaService(entry)) return null
    val disk = DADiskCreateFromIOMedia(kCFAllocatorDefault, session, entry) ?: return null
    try {
        val desc = DADiskCopyDescription(disk) ?: return null
        try {
            val isWhole = dictionaryBool(desc, kDADiskDescriptionMediaWholeKey) == true
            return if (isWhole) dictionaryString(desc, kDADiskDescriptionMediaBSDNameKey) else null
        } finally {
            CFRelease(desc)
        }
    } finally {
        CFRelease(disk)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun hasUsbParentService(startEntry: UInt): Boolean {
    var current = startEntry
    IOObjectRetain(current)
    var depth = 0
    while (current != 0u && depth < MAX_IOREGISTRY_DEPTH) {
        if (conformsToAnyClass(current, usbControllerClasses)) {
            IOObjectRelease(current)
            return true
        }
        val parent = readParentEntry(current)
        IOObjectRelease(current)
        current = parent
        depth++
    }
    if (current != 0u) {
        IOObjectRelease(current)
    }
    return false
}

@OptIn(ExperimentalForeignApi::class)
private fun isVirtualMediaService(entry: UInt): Boolean = conformsToAnyClass(entry, virtualMediaClasses)

@OptIn(ExperimentalForeignApi::class)
private fun conformsToAnyClass(
    entry: UInt,
    classNames: List<String>,
): Boolean =
    memScoped {
        classNames.any { className -> IOObjectConformsTo(entry, className.cstr.ptr) != 0 }
    }

@OptIn(ExperimentalForeignApi::class)
private fun readParentEntry(entry: UInt): UInt =
    memScoped {
        val parentVar = alloc<UIntVar>()
        val status = IORegistryEntryGetParentEntry(entry, kIOServicePlane.cstr.ptr, parentVar.ptr)
        if (status == KERN_SUCCESS) parentVar.value else 0u
    }

@OptIn(ExperimentalForeignApi::class)
private fun dictionaryString(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): String? {
    val value: CFTypeRef = CFDictionaryGetValue(dictionary, key) ?: return null
    if (CFGetTypeID(value) != CFStringGetTypeID()) return null
    return cfStringToKotlin(value.reinterpret())
}

@OptIn(ExperimentalForeignApi::class)
private fun dictionaryBool(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): Boolean? {
    val value: CFTypeRef = CFDictionaryGetValue(dictionary, key) ?: return null
    if (CFGetTypeID(value) != CFBooleanGetTypeID()) return null
    return CFBooleanGetValue(value.reinterpret())
}

@OptIn(ExperimentalForeignApi::class)
private fun dictionaryLong(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): Long? {
    val value: CFTypeRef = CFDictionaryGetValue(dictionary, key) ?: return null
    if (CFGetTypeID(value) != CFNumberGetTypeID()) return null
    return memScoped {
        val resultVar = alloc<LongVar>()
        if (CFNumberGetValue(value.reinterpret(), kCFNumberSInt64Type, resultVar.ptr)) {
            resultVar.value
        } else {
            null
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun dictionaryUrlPath(
    dictionary: CFDictionaryRef,
    key: CFStringRef?,
): String? {
    val value: CFTypeRef = CFDictionaryGetValue(dictionary, key) ?: return null
    if (CFGetTypeID(value) != CFURLGetTypeID()) return null
    val cfPath = CFURLCopyFileSystemPath(value.reinterpret(), kCFURLPOSIXPathStyle) ?: return null
    try {
        return cfStringToKotlin(cfPath)
    } finally {
        CFRelease(cfPath)
    }
}

@OptIn(ExperimentalForeignApi::class)
internal fun cfStringToKotlin(cfString: CFStringRef): String? =
    memScoped {
        val maxSize = CFStringGetMaximumSizeForEncoding(CFStringGetLength(cfString), kCFStringEncodingUTF8) + 1L
        val buffer = allocArray<ByteVar>(maxSize.toInt())
        if (CFStringGetCString(cfString, buffer, maxSize, kCFStringEncodingUTF8)) {
            buffer.toKString()
        } else {
            null
        }
    }
