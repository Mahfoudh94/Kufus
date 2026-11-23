package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.FlashRequest
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
    workerLocator: WorkerExecutableLocator,
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
