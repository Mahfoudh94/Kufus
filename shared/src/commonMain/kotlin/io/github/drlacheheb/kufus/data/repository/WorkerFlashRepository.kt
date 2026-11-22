package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.host.ElevationLauncher
import io.github.drlacheheb.kufus.host.ElevationResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

private const val EVENT_LOG_POLL_MS = 40L
private const val PKEXEC_DISMISSED_CODE = 126

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
    private val elevationLauncher: ElevationLauncher,
    private val eventLogFactory: FlashEventLogFactory,
) : FlashRepository {
    override fun flashImage(request: FlashRequest): Flow<WorkerEvent> =
        channelFlow {
            val workerPath = workerLocator.locateWorkerPath()
            if (workerPath == null) {
                trySend(WorkerEvent.Error(code = "WORKER_MISSING", message = "Native worker executable not found"))
                return@channelFlow
            }

            try {
                if (elevationLauncher.streamsLiveOutput) {
                    executeStreamingLaunch(workerPath, request) { event -> trySend(event) }
                } else {
                    executeEventLogLaunch(workerPath, request) { event -> trySend(event) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                trySend(WorkerEvent.Error(code = "FLASH_FAILED", message = e.message ?: "Flash execution failed"))
            }
        }

    private suspend fun executeStreamingLaunch(
        workerPath: String,
        request: FlashRequest,
        emitEvent: (WorkerEvent) -> Unit,
    ) {
        var sawTerminal = false
        val arguments = buildFlashArguments(request, eventLogPath = null)
        val result =
            elevationLauncher.streamElevated(workerPath, arguments) { line ->
                val event = decodeLineToWorkerEvent(line)
                if (event.isTerminalEvent()) sawTerminal = true
                emitEvent(event)
            }
        if (!sawTerminal) {
            emitElevationFallback(result, emitEvent)
        }
    }

    private suspend fun ProducerScope<WorkerEvent>.executeEventLogLaunch(
        workerPath: String,
        request: FlashRequest,
        emitEvent: (WorkerEvent) -> Unit,
    ) {
        val session = eventLogFactory.createSession()
        try {
            var sawTerminal = false
            val arguments = buildFlashArguments(request, eventLogPath = session.path)
            val tailJob =
                launch {
                    while (isActive) {
                        session.drainLines { line ->
                            val event = decodeLineToWorkerEvent(line)
                            if (event.isTerminalEvent()) sawTerminal = true
                            emitEvent(event)
                        }
                        delay(EVENT_LOG_POLL_MS)
                    }
                }
            val result = elevationLauncher.launchElevated(workerPath, arguments)
            tailJob.cancelAndJoin()
            session.drainLines { line ->
                val event = decodeLineToWorkerEvent(line)
                if (event.isTerminalEvent()) sawTerminal = true
                emitEvent(event)
            }
            if (!sawTerminal) {
                emitElevationFallback(result, emitEvent)
            }
        } finally {
            session.closeAndDelete()
        }
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

private fun decodeLineToWorkerEvent(line: String): WorkerEvent =
    decodeWorkerFlashLine(line).getOrElse { error ->
        WorkerEvent.Error(code = "FLASH_FAILED", message = error.message ?: "Flash worker error")
    }

private fun WorkerEvent.isTerminalEvent(): Boolean = this is WorkerEvent.Completed || this is WorkerEvent.Error

private fun emitElevationFallback(
    result: Result<ElevationResult>,
    emitEvent: (WorkerEvent) -> Unit,
) {
    result
        .onSuccess { elevation ->
            val code = if (elevation.exitCode == PKEXEC_DISMISSED_CODE || elevation.exitCode < 0) "ELEVATION_CANCELLED" else "FLASH_FAILED"
            val message =
                elevation.stderr.trim().ifEmpty {
                    if (code == "ELEVATION_CANCELLED") {
                        "Administrator authorization was cancelled or declined."
                    } else {
                        "Elevated flash worker exited unexpectedly (code ${elevation.exitCode})."
                    }
                }
            emitEvent(WorkerEvent.Error(code = code, message = message))
        }.onFailure { error ->
            emitEvent(
                WorkerEvent.Error(
                    code = "ELEVATION_FAILED",
                    message = error.message ?: "Failed to launch elevated worker",
                ),
            )
        }
}
