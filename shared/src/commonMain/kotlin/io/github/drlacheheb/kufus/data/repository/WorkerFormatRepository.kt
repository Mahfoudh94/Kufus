package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.host.ElevationLauncher
import kotlinx.coroutines.flow.Flow

class WorkerFormatRepository(
    workerLocator: WorkerExecutableLocator,
    elevationLauncher: ElevationLauncher,
    eventLogFactory: FlashEventLogFactory,
) : FormatRepository {
    private val executor =
        ElevatedWorkerExecutor(
            workerLocator = workerLocator,
            elevationLauncher = elevationLauncher,
            eventLogFactory = eventLogFactory,
        )

    override fun formatDrive(request: FormatRequest): Flow<WorkerEvent> =
        executor.execute(
            failureCode = "FORMAT_FAILED",
            failureVerb = "Format",
            buildArguments = { eventLogPath -> buildFormatArguments(request, eventLogPath) },
        )
}

internal fun buildFormatArguments(
    request: FormatRequest,
    eventLogPath: String?,
): List<String> =
    buildList {
        add("--format")
        add("--device")
        add(request.deviceId)
        add("--scheme")
        add(request.partitionScheme.name.lowercase())
        add("--fs")
        add(request.filesystem.name.lowercase())
        val cleanLabel = sanitizeVolumeLabel(request.volumeLabel, request.filesystem)
        if (cleanLabel.isNotEmpty()) {
            add("--label")
            add(cleanLabel)
        }
        request.clusterSizeBytes?.let { clusterBytes ->
            add("--cluster-size")
            add(clusterBytes.toString())
        }
        if (!request.quickFormat) {
            add("--full-wipe")
        }
        if (request.ejectAfterFormat) {
            add("--eject")
        }
        if (!eventLogPath.isNullOrBlank()) {
            add("--event-log")
            add(eventLogPath)
        }
    }
