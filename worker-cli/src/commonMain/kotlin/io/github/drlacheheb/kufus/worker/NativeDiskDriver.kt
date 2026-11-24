package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.flash.FlashRequest
import io.github.drlacheheb.kufus.disk.model.FormatRequest
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.disk.model.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.platform.executePlatformEject
import io.github.drlacheheb.kufus.disk.platform.executePlatformFlash
import io.github.drlacheheb.kufus.disk.platform.executePlatformFormat
import io.github.drlacheheb.kufus.disk.platform.executePlatformWindowsFlash
import io.github.drlacheheb.kufus.disk.platform.flushNativeStdout
import io.github.drlacheheb.kufus.disk.platform.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.platform.launchPlatformElevatedProcess
import io.github.drlacheheb.kufus.disk.platform.listPlatformUsbDrives
import io.github.drlacheheb.kufus.disk.platform.watchPlatformUsbDrives

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

    fun launchElevatedProcess(
        executablePath: String,
        arguments: List<String>,
    ): Int = -1

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

    override fun launchElevatedProcess(
        executablePath: String,
        arguments: List<String>,
    ): Int = launchPlatformElevatedProcess(executablePath, arguments)

    override fun flushOutput() {
        flushNativeStdout()
    }
}
