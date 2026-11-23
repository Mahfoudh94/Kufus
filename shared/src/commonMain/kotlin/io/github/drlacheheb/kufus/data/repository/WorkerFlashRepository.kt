package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.host.ElevationLauncher
import kotlinx.coroutines.flow.Flow

interface FlashEventLogSession {
    val path: String

    suspend fun drainLines(onLine: suspend (String) -> Unit)

    suspend fun closeAndDelete()
}

fun interface FlashEventLogFactory {
    suspend fun createSession(): FlashEventLogSession
}

class WorkerFlashRepository(
    private val workerLocator: WorkerExecutableLocator,
    elevationLauncher: ElevationLauncher,
    eventLogFactory: FlashEventLogFactory,
) : FlashRepository {
    private val executor =
        ElevatedWorkerExecutor(
            workerLocator = workerLocator,
            elevationLauncher = elevationLauncher,
            eventLogFactory = eventLogFactory,
        )

    override fun flashImage(request: FlashRequest): Flow<WorkerEvent> =
        executor.execute(
            failureCode = "FLASH_FAILED",
            failureVerb = "Flash",
            buildArguments = { eventLogPath -> buildFlashArguments(request, eventLogPath) },
        )

    override fun flashWindowsIso(request: WindowsFlashRequest): Flow<WorkerEvent> {
        val resolvedUefiNtfs =
            when {
                !request.uefiNtfsImagePath.isNullOrBlank() -> request.uefiNtfsImagePath
                request.filesystem == FilesystemType.NTFS -> workerLocator.locateCompanionFile("uefi-ntfs.img")
                else -> null
            }
        return executor.execute(
            failureCode = "WINDOWS_FLASH_FAILED",
            failureVerb = "Windows installer preparation",
            buildArguments = { eventLogPath -> buildWindowsFlashArguments(request, resolvedUefiNtfs, eventLogPath) },
        )
    }
}

internal fun buildFlashArguments(
    request: FlashRequest,
    eventLogPath: String?,
): List<String> =
    buildList {
        add("--flash")
        add("--image")
        add(request.imagePath)
        add("--device")
        add(request.deviceId)
        if (request.verifyAfterWrite) {
            add("--verify")
        }
        if (request.ejectAfterWrite) {
            add("--eject")
        }
        if (!eventLogPath.isNullOrBlank()) {
            add("--event-log")
            add(eventLogPath)
        }
    }

internal fun buildWindowsFlashArguments(
    request: WindowsFlashRequest,
    resolvedUefiNtfsPath: String?,
    eventLogPath: String?,
): List<String> =
    buildList {
        add("--flash-windows")
        add("--image")
        add(request.imagePath)
        add("--device")
        add(request.deviceId)
        add("--scheme")
        add(request.partitionScheme.name.lowercase())
        add("--fs")
        add(request.filesystem.name.lowercase())
        if (request.volumeLabel.isNotBlank()) {
            add("--label")
            add(request.volumeLabel)
        }
        request.clusterSizeBytes?.let { cluster ->
            add("--cluster-size")
            add(cluster.toString())
        }
        if (!request.quickFormat) add("--full-wipe")
        if (!request.ejectAfterWrite) add("--no-eject")
        appendWindowsUserExperienceArgs(request)
        resolvedUefiNtfsPath?.takeIf { it.isNotBlank() }?.let { asset ->
            add("--uefi-ntfs")
            add(asset)
        }
        if (!eventLogPath.isNullOrBlank()) {
            add("--event-log")
            add(eventLogPath)
        }
    }

private fun MutableList<String>.appendWindowsUserExperienceArgs(request: WindowsFlashRequest) {
    val ux = request.userExperience
    if (ux.bypassSystemRequirements) add("--bypass-requirements")
    if (ux.bypassMicrosoftAccount) add("--bypass-msa")
    if (ux.disableDataCollection) add("--disable-telemetry")
    ux.localAccountUsername?.takeIf { it.isNotBlank() }?.let { user ->
        add("--local-user")
        add(user)
    }
    ux.regionalLocaleTag?.takeIf { it.isNotBlank() }?.let { locale ->
        add("--locale")
        add(locale)
    }
}
