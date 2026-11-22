package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.UsbDrive

interface DiskRepository {
    suspend fun listUsbDrives(): Result<List<UsbDrive>>
}
