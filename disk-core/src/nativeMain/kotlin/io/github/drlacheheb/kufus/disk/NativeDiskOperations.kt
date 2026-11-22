package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush

expect fun listPlatformUsbDrives(): List<UsbDrive>

expect fun watchPlatformUsbDrives(
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean = { true },
)

expect fun launchPlatformElevatedProcess(
    executablePath: String,
    arguments: List<String>,
): Int

expect fun inspectPlatformImage(path: String): Result<ImageInfo>

expect fun ejectPlatformDevice(devicePath: String): Result<Unit>

@OptIn(ExperimentalForeignApi::class)
fun flushNativeStdout() {
    fflush(null)
}
