package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.flow

class WorkerDiskRepository(
    private val workerLocator: WorkerExecutableLocator,
    private val commandRunner: CommandRunner,
) : DiskRepository {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> {
        val workerPath =
            workerLocator.locateWorkerPath()
                ?: return Result.failure(IllegalStateException("Native worker executable not found"))
        return commandRunner.run(listOf(workerPath, "--list")).fold(
            onSuccess = { output ->
                val payload = output.stdout.lineSequence().firstOrNull { it.isNotBlank() }
                when {
                    payload != null -> decodeWorkerDrivesLine(payload)
                    output.exitCode != 0 -> {
                        val detail = output.stderr.trim().ifEmpty { "Worker exited with code ${output.exitCode}" }
                        Result.failure(IllegalStateException(detail))
                    }
                    else -> Result.failure(IllegalStateException("Worker returned empty output for --list"))
                }
            },
            onFailure = { error -> Result.failure(error) },
        )
    }

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
