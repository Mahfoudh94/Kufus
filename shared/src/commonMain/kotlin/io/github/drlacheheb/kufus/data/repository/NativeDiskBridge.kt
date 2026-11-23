package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.model.UsbDrive

fun interface NativeDiskBridge {
    suspend fun listUsbDrives(): Result<List<UsbDrive>>
}
