package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.flushNativeStdout
import io.github.drlacheheb.kufus.disk.listPlatformUsbDrives
import io.github.drlacheheb.kufus.disk.watchPlatformUsbDrives

interface NativeDiskDriver {
    fun listUsbDrives(): List<UsbDrive>

    fun watchUsbDrives(
        onDrivesUpdated: (List<UsbDrive>) -> Unit,
        shouldContinue: () -> Boolean = { true },
    )

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

    override fun flushOutput() {
        flushNativeStdout()
    }
}
