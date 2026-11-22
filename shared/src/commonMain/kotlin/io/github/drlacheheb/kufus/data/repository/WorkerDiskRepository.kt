package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.data.process.CommandOutput
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.domain.repository.DiskRepository

class WorkerDiskRepository(
    private val workerLocator: WorkerExecutableLocator,
    private val commandRunner: CommandRunner,
) : DiskRepository {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> {
        val workerPath =
            workerLocator.locateWorkerPath()
                ?: return Result.failure(IllegalStateException("Native worker executable not found"))
        val output =
            commandRunner
                .run(listOf(workerPath, "--list"))
                .getOrElse { error -> return Result.failure(error) }
        return decodeDrivesResult(output)
    }

    private fun decodeDrivesResult(output: CommandOutput): Result<List<UsbDrive>> {
        val firstLine = output.stdout.lineSequence().firstOrNull { it.isNotBlank() }
        if (firstLine == null) {
            val detail = output.stderr.trim().ifEmpty { "Worker produced empty output" }
            val prefix = if (output.exitCode != 0) "Worker failed with code ${output.exitCode}: " else ""
            return Result.failure(IllegalStateException("$prefix$detail"))
        }
        return decodeWorkerEvent(firstLine, output.exitCode, output.stderr)
    }

    private fun decodeWorkerEvent(
        jsonLine: String,
        exitCode: Int,
        stderr: String,
    ): Result<List<UsbDrive>> =
        try {
            when (val event = DiskJson.decodeEvent(jsonLine)) {
                is WorkerEvent.DrivesListed ->
                    if (exitCode == 0) {
                        Result.success(event.drives)
                    } else {
                        Result.failure(
                            IllegalStateException("Worker failed with code $exitCode: ${stderr.trim()}"),
                        )
                    }
                is WorkerEvent.Error ->
                    Result.failure(IllegalStateException("${event.code}: ${event.message}"))
                else ->
                    Result.failure(IllegalStateException("Unexpected worker event: $event"))
            }
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(e)
        }
}
