package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.WorkerEvent
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

/**
 * Runs an elevated `kufus-worker` command and decodes NDJSON progress/completion/error events.
 */
internal class ElevatedWorkerExecutor(
    private val workerLocator: WorkerExecutableLocator,
    private val elevationLauncher: ElevationLauncher,
    private val eventLogFactory: FlashEventLogFactory,
) {
    fun execute(
        failureCode: String,
        failureVerb: String,
        buildArguments: (eventLogPath: String?) -> List<String>,
    ): Flow<WorkerEvent> =
        channelFlow {
            val workerPath = workerLocator.locateWorkerPath()
            if (workerPath == null) {
                trySend(WorkerEvent.Error(code = "WORKER_MISSING", message = "Native worker executable not found"))
                return@channelFlow
            }

            try {
                if (elevationLauncher.streamsLiveOutput) {
                    executeStreamingLaunch(
                        workerPath = workerPath,
                        arguments = buildArguments(null),
                        failureCode = failureCode,
                        failureVerb = failureVerb,
                    ) { event -> trySend(event) }
                } else {
                    executeEventLogLaunch(
                        workerPath = workerPath,
                        buildArguments = buildArguments,
                        failureCode = failureCode,
                        failureVerb = failureVerb,
                    ) { event -> trySend(event) }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                trySend(
                    WorkerEvent.Error(
                        code = failureCode,
                        message = e.message ?: "$failureVerb execution failed",
                    ),
                )
            }
        }

    private suspend fun executeStreamingLaunch(
        workerPath: String,
        arguments: List<String>,
        failureCode: String,
        failureVerb: String,
        emitEvent: (WorkerEvent) -> Unit,
    ) {
        var sawTerminal = false
        val result =
            elevationLauncher.streamElevated(workerPath, arguments) { line ->
                val event = decodeLineToWorkerEvent(line, failureCode, failureVerb)
                if (event.isTerminalEvent()) sawTerminal = true
                emitEvent(event)
            }
        if (!sawTerminal) {
            emitElevationFallback(result, failureCode, failureVerb, emitEvent)
        }
    }

    private suspend fun ProducerScope<WorkerEvent>.executeEventLogLaunch(
        workerPath: String,
        buildArguments: (eventLogPath: String?) -> List<String>,
        failureCode: String,
        failureVerb: String,
        emitEvent: (WorkerEvent) -> Unit,
    ) {
        val session = eventLogFactory.createSession()
        try {
            var sawTerminal = false
            val arguments = buildArguments(session.path)
            val tailJob =
                launch {
                    while (isActive) {
                        session.drainLines { line ->
                            val event = decodeLineToWorkerEvent(line, failureCode, failureVerb)
                            if (event.isTerminalEvent()) sawTerminal = true
                            emitEvent(event)
                        }
                        delay(EVENT_LOG_POLL_MS)
                    }
                }
            val result = elevationLauncher.launchElevated(workerPath, arguments)
            tailJob.cancelAndJoin()
            session.drainLines { line ->
                val event = decodeLineToWorkerEvent(line, failureCode, failureVerb)
                if (event.isTerminalEvent()) sawTerminal = true
                emitEvent(event)
            }
            if (!sawTerminal) {
                emitElevationFallback(result, failureCode, failureVerb, emitEvent)
            }
        } finally {
            session.closeAndDelete()
        }
    }
}

private fun decodeLineToWorkerEvent(
    line: String,
    failureCode: String,
    failureVerb: String,
): WorkerEvent =
    decodeWorkerFlashLine(line).getOrElse { error ->
        WorkerEvent.Error(code = failureCode, message = error.message ?: "$failureVerb worker error")
    }

private fun WorkerEvent.isTerminalEvent(): Boolean = this is WorkerEvent.Completed || this is WorkerEvent.Error

private fun emitElevationFallback(
    result: Result<ElevationResult>,
    failureCode: String,
    failureVerb: String,
    emitEvent: (WorkerEvent) -> Unit,
) {
    result
        .onSuccess { elevation ->
            val code =
                if (elevation.exitCode == PKEXEC_DISMISSED_CODE || elevation.exitCode < 0) {
                    "ELEVATION_CANCELLED"
                } else {
                    failureCode
                }
            val message =
                elevation.stderr.trim().ifEmpty {
                    if (code == "ELEVATION_CANCELLED") {
                        "Administrator authorization was cancelled or declined."
                    } else {
                        "Elevated ${failureVerb.lowercase()} worker exited unexpectedly (code ${elevation.exitCode})."
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
