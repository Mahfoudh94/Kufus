package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.LSBLK_COLUMNS
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.parseLsblkJson
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

class WorkerDiskRepository : DiskRepository {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> =
        withContext(Dispatchers.IO) {
            val workerResult = findWorkerExecutable()?.let(::runWorkerList)
            when {
                workerResult != null && workerResult.isSuccess -> workerResult
                currentOsName().contains("linux") -> runLinuxLsblkFallback()
                workerResult != null -> workerResult
                else -> Result.failure(IllegalStateException("No disk worker binary found for ${currentOsName()}"))
            }
        }

    private fun runWorkerList(workerFile: File): Result<List<UsbDrive>> =
        runNonCancellable {
            val process =
                ProcessBuilder(workerFile.absolutePath, "--list")
                    .redirectErrorStream(true)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                error("Worker exited with code $exitCode: ${output.trim()}")
            }
            val firstLine =
                output.lineSequence().firstOrNull { it.isNotBlank() }
                    ?: error("Worker produced empty output")
            when (val event = DiskJson.decodeEvent(firstLine)) {
                is WorkerEvent.DrivesListed -> event.drives
                is WorkerEvent.Error -> error("${event.code}: ${event.message}")
                else -> error("Unexpected worker event: $event")
            }
        }

    private fun runLinuxLsblkFallback(): Result<List<UsbDrive>> =
        runNonCancellable {
            val process =
                ProcessBuilder("lsblk", "-J", "-b", "-o", LSBLK_COLUMNS)
                    .redirectErrorStream(false)
                    .start()
            val output = process.inputStream.bufferedReader().use { it.readText() }
            val exitCode = process.waitFor()
            if (exitCode != 0) {
                val errorText = process.errorStream.bufferedReader().use { it.readText().trim() }
                error("lsblk failed with code $exitCode: $errorText")
            }
            parseLsblkJson(output)
        }

    private fun findWorkerExecutable(): File? {
        val osName = currentOsName()
        val isWindows = osName.contains("windows")
        val binaryNames =
            if (isWindows) {
                listOf("kufus-worker.exe")
            } else {
                listOf("kufus-worker", "kufus-worker.kexe")
            }
        val targetName =
            when {
                isWindows -> "mingwX64"
                osName.contains("mac") -> "macosArm64"
                else -> "linuxX64"
            }
        val devDirs =
            listOf("", "../").flatMap { prefix ->
                listOf("releaseExecutable", "debugExecutable").map { variant ->
                    "${prefix}worker-cli/build/bin/$targetName/$variant"
                }
            }
        val candidateDirs = listOfNotNull(System.getProperty("compose.application.resources.dir")) + devDirs
        return candidateDirs
            .asSequence()
            .flatMap { dir -> binaryNames.asSequence().map { name -> File(dir, name) } }
            .firstOrNull { it.isFile && it.canExecute() }
    }

    private inline fun <T> runNonCancellable(block: () -> T): Result<T> =
        try {
            Result.success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (
            @Suppress("TooGenericExceptionCaught") e: Exception,
        ) {
            Result.failure(e)
        }

    private fun currentOsName(): String = System.getProperty("os.name").orEmpty().lowercase()
}
