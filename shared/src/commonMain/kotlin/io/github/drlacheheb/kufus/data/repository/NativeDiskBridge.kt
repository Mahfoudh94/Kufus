package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.UsbDrive

fun interface NativeDiskBridge {
    suspend fun listUsbDrives(): Result<List<UsbDrive>>
}
