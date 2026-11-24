package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.DiskJson
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.worker.cli.FLASH_USAGE_MESSAGE
import io.github.drlacheheb.kufus.worker.cli.FORMAT_USAGE_MESSAGE
import io.github.drlacheheb.kufus.worker.cli.WINDOWS_FLASH_USAGE_MESSAGE
import io.github.drlacheheb.kufus.worker.cli.WorkerArgParsers
import io.github.drlacheheb.kufus.worker.io.WorkerEventSink
import io.github.drlacheheb.kufus.worker.io.openPosixEventLogSink

private const val WORKER_VERSION = "kufus-worker 1.0.0"

fun interface WorkerCommandHandler {
    fun execute(args: Array<String>): Int
}

class WorkerCliApplication(
    private val driver: NativeDiskDriver,
    private val printer: (String) -> Unit = ::println,
    private val eventLogSinkFactory: (String) -> WorkerEventSink = ::openPosixEventLogSink,
) {
    private val handlers: Map<String, WorkerCommandHandler> =
        mapOf(
            "--list" to WorkerCommandHandler { handleList() },
            "--watch" to WorkerCommandHandler { handleWatch() },
            "--inspect" to WorkerCommandHandler { commandArgs -> handleInspect(commandArgs) },
            "--flash" to WorkerCommandHandler { commandArgs -> handleFlash(commandArgs) },
            "--flash-windows" to WorkerCommandHandler { commandArgs -> handleFlashWindows(commandArgs) },
            "--format" to WorkerCommandHandler { commandArgs -> handleFormat(commandArgs) },
            "--eject" to WorkerCommandHandler { commandArgs -> handleEject(commandArgs) },
            "--elevate" to WorkerCommandHandler { commandArgs -> handleElevate(commandArgs) },
            "--version" to WorkerCommandHandler { handleVersion() },
        )

    fun run(args: Array<String>): Int {
        val flag = args.firstOrNull()
        val handler = handlers[flag]
        if (handler != null) return handler.execute(args)
        emitEvent(
            WorkerEvent.Error(
                code = "INVALID_ARGS",
                message = "Supported flags: ${handlers.keys.joinToString(", ")}",
            ),
        )
        return 1
    }

    private fun handleList(): Int {
        val drives = driver.listUsbDrives()
        emitEvent(WorkerEvent.DrivesListed(drives))
        return 0
    }

    private fun handleWatch(): Int {
        driver.watchUsbDrives(
            onDrivesUpdated = { drives -> emitEvent(WorkerEvent.DrivesListed(drives)) },
        )
        return 0
    }

    private fun handleInspect(args: Array<String>): Int {
        val imagePath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (args.size != 2 || imagePath == null) {
            emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = "Usage: --inspect <image-path>"))
            return 1
        }
        return driver.inspectImage(imagePath).fold(
            onSuccess = { info ->
                emitEvent(WorkerEvent.ImageInspected(info))
                0
            },
            onFailure = { error ->
                emitEvent(WorkerEvent.Error(code = "INSPECT_FAILED", message = error.message ?: "Image inspection failed"))
                1
            },
        )
    }

    private fun handleFlash(args: Array<String>): Int {
        val request =
            WorkerArgParsers.parseFlashRequest(args).getOrElse { error ->
                emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = error.message ?: FLASH_USAGE_MESSAGE))
                return 1
            }

        return runWithOptionalEventLog(
            eventLogPath = request.eventLogPath,
            fallbackErrorCode = "FLASH_FAILED",
            fallbackMessage = "Flash operation failed",
        ) { sink ->
            driver.executeFlash(request = request, onEvent = { event -> emitEvent(event, sink) })
        }
    }

    private fun handleFormat(args: Array<String>): Int {
        val request =
            WorkerArgParsers.parseFormatRequest(args).getOrElse { error ->
                emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = error.message ?: FORMAT_USAGE_MESSAGE))
                return 1
            }

        return runWithOptionalEventLog(
            eventLogPath = request.eventLogPath,
            fallbackErrorCode = "FORMAT_FAILED",
            fallbackMessage = "Format operation failed",
        ) { sink ->
            driver.executeFormat(request = request, onEvent = { event -> emitEvent(event, sink) })
        }
    }

    private fun handleFlashWindows(args: Array<String>): Int {
        val request =
            WorkerArgParsers.parseWindowsFlashRequest(args).getOrElse { error ->
                emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = error.message ?: WINDOWS_FLASH_USAGE_MESSAGE))
                return 1
            }

        return runWithOptionalEventLog(
            eventLogPath = request.eventLogPath,
            fallbackErrorCode = "WINDOWS_FLASH_FAILED",
            fallbackMessage = "Windows installer preparation failed",
        ) { sink ->
            driver.executeWindowsFlash(request = request, onEvent = { event -> emitEvent(event, sink) })
        }
    }

    private fun runWithOptionalEventLog(
        eventLogPath: String?,
        fallbackErrorCode: String,
        fallbackMessage: String,
        action: (WorkerEventSink?) -> Result<WorkerEvent.Completed>,
    ): Int {
        val eventLogSink =
            eventLogPath?.let { path ->
                runCatching { eventLogSinkFactory(path) }.getOrElse { error ->
                    emitEvent(
                        WorkerEvent.Error(
                            code = "EVENT_LOG_OPEN_FAILED",
                            message = error.message ?: "Unable to open event log '$path'",
                        ),
                    )
                    return 1
                }
            }

        try {
            return action(eventLogSink).fold(
                onSuccess = { completed ->
                    emitEvent(completed, eventLogSink)
                    0
                },
                onFailure = { error ->
                    val code = (error as? FlashStepException)?.code ?: fallbackErrorCode
                    emitEvent(WorkerEvent.Error(code = code, message = error.message ?: fallbackMessage), eventLogSink)
                    1
                },
            )
        } finally {
            runCatching { eventLogSink?.close() }
        }
    }

    private fun handleEject(args: Array<String>): Int {
        val deviceId = WorkerArgParsers.parseEjectDevice(args)
        if (deviceId == null) {
            emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = "Usage: --eject --device <id>"))
            return 1
        }
        return driver.ejectDevice(deviceId).fold(
            onSuccess = {
                emitEvent(WorkerEvent.Completed(verified = false, elapsedMs = 0L, ejected = true))
                0
            },
            onFailure = { error ->
                val code = (error as? FlashStepException)?.code ?: "EJECT_FAILED"
                emitEvent(WorkerEvent.Error(code = code, message = error.message ?: "Failed to eject '$deviceId'"))
                1
            },
        )
    }

    private fun handleElevate(args: Array<String>): Int {
        val executablePath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (executablePath == null) {
            emitEvent(WorkerEvent.Error(code = "INVALID_ARGS", message = "Usage: --elevate <executable-path> [args...]"))
            return 1
        }
        val exitCode = driver.launchElevatedProcess(executablePath, args.drop(2))
        if (exitCode < 0) {
            emitEvent(
                WorkerEvent.Error(
                    code = "ELEVATION_FAILED",
                    message = "Unable to launch elevated process '$executablePath'",
                ),
            )
            return 1
        }
        return exitCode
    }

    private fun handleVersion(): Int {
        printer(WORKER_VERSION)
        driver.flushOutput()
        return 0
    }

    private fun emitEvent(
        event: WorkerEvent,
        eventLogSink: WorkerEventSink? = null,
    ) {
        val encoded = DiskJson.encodeEvent(event)
        printer(encoded)
        driver.flushOutput()
        eventLogSink?.let { sink ->
            runCatching { sink.writeLine(encoded) }
        }
    }
}
