package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.executePlatformEject
import io.github.drlacheheb.kufus.disk.executePlatformFlash
import io.github.drlacheheb.kufus.disk.executePlatformFormat
import io.github.drlacheheb.kufus.disk.executePlatformWindowsFlash
import io.github.drlacheheb.kufus.disk.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.flushNativeStdout
import io.github.drlacheheb.kufus.disk.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.listPlatformUsbDrives
import io.github.drlacheheb.kufus.disk.watchPlatformUsbDrives

interface NativeDiskDriver {
    fun listUsbDrives(): List<UsbDrive>

    fun watchUsbDrives(
        onDrivesUpdated: (List<UsbDrive>) -> Unit,
        shouldContinue: () -> Boolean = { true },
    )

    fun inspectImage(path: String): Result<ImageInfo>

    fun executeFlash(
        request: FlashRequest,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed>

    fun executeFormat(
        request: FormatRequest,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed> = Result.failure(UnsupportedOperationException("Formatting unsupported"))

    fun executeWindowsFlash(
        request: WindowsFlashRequest,
        shouldCancel: () -> Boolean = { false },
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed> = Result.failure(UnsupportedOperationException("Windows installer preparation unsupported"))

    fun ejectDevice(devicePath: String): Result<Unit>

    fun flushOutput() = Unit
}

class PlatformNativeDiskDriver : NativeDiskDriver {
    override fun listUsbDrives(): List<UsbDrive> = filterSafeUsbDrives(listPlatformUsbDrives())

    override fun watchUsbDrives(
        onDrivesUpdated: (List<UsbDrive>) -> Unit,
        shouldContinue: () -> Boolean,
    ) {
        watchPlatformUsbDrives(
            onDrivesUpdated = { drives -> onDrivesUpdated(filterSafeUsbDrives(drives)) },
            shouldContinue = shouldContinue,
        )
    }

    override fun inspectImage(path: String): Result<ImageInfo> = inspectPlatformImage(path)

    override fun executeFlash(
        request: FlashRequest,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed> = executePlatformFlash(request, shouldCancel, onEvent)

    override fun executeFormat(
        request: FormatRequest,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed> = executePlatformFormat(request, shouldCancel, onEvent)

    override fun executeWindowsFlash(
        request: WindowsFlashRequest,
        shouldCancel: () -> Boolean,
        onEvent: (WorkerEvent) -> Unit,
    ): Result<WorkerEvent.Completed> = executePlatformWindowsFlash(request, shouldCancel, onEvent)

    override fun ejectDevice(devicePath: String): Result<Unit> = executePlatformEject(devicePath)

    override fun flushOutput() {
        flushNativeStdout()
    }
}
