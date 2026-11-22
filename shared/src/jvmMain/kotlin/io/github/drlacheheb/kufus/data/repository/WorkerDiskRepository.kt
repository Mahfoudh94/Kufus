package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.LSBLK_COLUMNS
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.buildMacosUsbDrives
import io.github.drlacheheb.kufus.disk.isValidBsdDiskIdentifier
import io.github.drlacheheb.kufus.disk.parseDiskutilListEntries
import io.github.drlacheheb.kufus.disk.parseLsblkJson
import io.github.drlacheheb.kufus.disk.parseWindowsPowerShellDisksJson
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

private const val WINDOWS_DISK_QUERY_SCRIPT =
    "Get-Disk | ForEach-Object { " +
        "\$letters = @(Get-Partition -DiskNumber \$_.Number -ErrorAction SilentlyContinue " +
        "| Where-Object { \$_.DriveLetter } | ForEach-Object { [string]\$_.DriveLetter }); " +
        "[PSCustomObject]@{ number = [int]\$_.Number; friendlyName = \$_.FriendlyName; " +
        "serialNumber = \$_.SerialNumber; size = [long]\$_.Size; busType = [string]\$_.BusType; " +
        "isBoot = [bool]\$_.IsBoot; isSystem = [bool]\$_.IsSystem; driveLetters = \$letters } " +
        "} | ConvertTo-Json -Depth 4 -Compress"

class WorkerDiskRepository : DiskRepository {
    override suspend fun listUsbDrives(): Result<List<UsbDrive>> =
        withContext(Dispatchers.IO) {
            val workerResult = findWorkerExecutable()?.let(::runWorkerList)
            if (workerResult != null && workerResult.isSuccess) {
                workerResult
            } else {
                runPlatformFallback(workerResult)
            }
        }

    private fun runPlatformFallback(workerFailure: Result<List<UsbDrive>>?): Result<List<UsbDrive>> {
        val osName = currentOsName()
        return when {
            osName.contains("linux") -> runLinuxLsblkFallback()
            osName.contains("mac") -> runMacosDiskutilFallback()
            osName.contains("windows") -> runWindowsPowerShellFallback()
            workerFailure != null -> workerFailure
            else -> Result.failure(IllegalStateException("Unsupported OS for disk scanning: $osName"))
        }
    }

    private fun runWorkerList(workerFile: File): Result<List<UsbDrive>> =
        runNonCancellable {
            val output = runProcess(listOf(workerFile.absolutePath, "--list"))
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
            val output = runProcess(listOf("lsblk", "-J", "-b", "-o", LSBLK_COLUMNS))
            parseLsblkJson(output)
        }

    private fun runMacosDiskutilFallback(): Result<List<UsbDrive>> =
        runNonCancellable {
            val listXml = runProcess(listOf("diskutil", "list", "-plist", "external", "physical"))
            val entries = parseDiskutilListEntries(listXml)
            val infoById =
                buildMap {
                    entries.forEach { entry ->
                        val diskId = entry.deviceIdentifier
                        if (isValidBsdDiskIdentifier(diskId)) {
                            put(diskId, runProcess(listOf("diskutil", "info", "-plist", "/dev/$diskId")))
                        }
                    }
                }
            buildMacosUsbDrives(listXml, infoById)
        }

    private fun runWindowsPowerShellFallback(): Result<List<UsbDrive>> =
        runNonCancellable {
            val output =
                runProcess(
                    listOf(
                        "powershell.exe",
                        "-NoProfile",
                        "-NonInteractive",
                        "-Command",
                        WINDOWS_DISK_QUERY_SCRIPT,
                    ),
                )
            val systemDrive = System.getenv("SystemDrive")?.takeIf { it.isNotBlank() } ?: "C:"
            parseWindowsPowerShellDisksJson(output, systemDrive)
        }

    private fun runProcess(command: List<String>): String {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        val exitCode = process.waitFor()
        if (exitCode != 0) {
            val errorText = process.errorStream.bufferedReader().use { it.readText().trim() }
            error("${command.first()} failed with code $exitCode: $errorText")
        }
        return output
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
