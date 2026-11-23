package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

interface DiskRepository {
    suspend fun listUsbDrives(): Result<List<UsbDrive>>

    fun observeUsbDrives(): Flow<Result<List<UsbDrive>>> = emptyFlow()
}
