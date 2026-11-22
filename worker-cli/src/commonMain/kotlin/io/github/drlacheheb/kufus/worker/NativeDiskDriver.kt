package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.listPlatformUsbDrives

interface NativeDiskDriver {
    fun listUsbDrives(): List<UsbDrive>
}

class PlatformNativeDiskDriver : NativeDiskDriver {
    override fun listUsbDrives(): List<UsbDrive> = filterSafeUsbDrives(listPlatformUsbDrives())
}
