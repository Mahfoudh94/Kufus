package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

class WorkerDiskRepository(
    private val nativeDiskBridge: NativeDiskBridge,
    private val workerLocator: WorkerExecutableLocator,
    private val commandRunner: CommandRunner,
) : DiskRepository {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> = nativeDiskBridge.listUsbDrives()

    override fun observeUsbDrives(): Flow<Result<List<UsbDrive>>> =
        flow {
            val workerPath =
                workerLocator.locateWorkerPath()
                    ?: error("Native worker executable not found")
            commandRunner
                .streamLines(listOf(workerPath, "--watch"))
                .filter { line -> line.isNotBlank() }
                .collect { line ->
                    emit(decodeWorkerDrivesLine(line))
                }
        }.catch { error ->
            emit(Result.failure(error))
        }
}
