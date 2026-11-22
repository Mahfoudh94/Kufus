package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.WorkerEvent

private const val WORKER_VERSION = "kufus-worker 1.0.0"

fun interface WorkerCommandHandler {
    fun execute(args: Array<String>): Int
}

class WorkerCliApplication(
    private val driver: NativeDiskDriver,
    private val printer: (String) -> Unit = ::println,
) {
    private val handlers: Map<String, WorkerCommandHandler> =
        mapOf(
            "--list" to WorkerCommandHandler { handleList() },
            "--watch" to WorkerCommandHandler { handleWatch() },
            "--inspect" to WorkerCommandHandler { commandArgs -> handleInspect(commandArgs) },
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
            emitEvent(
                WorkerEvent.Error(
                    code = "INVALID_ARGS",
                    message = "Usage: --inspect <image-path>",
                ),
            )
            return 1
        }
        return driver.inspectImage(imagePath).fold(
            onSuccess = { info ->
                emitEvent(WorkerEvent.ImageInspected(info))
                0
            },
            onFailure = { error ->
                emitEvent(
                    WorkerEvent.Error(
                        code = "INSPECT_FAILED",
                        message = error.message ?: "Image inspection failed",
                    ),
                )
                1
            },
        )
    }

    private fun handleVersion(): Int {
        printer(WORKER_VERSION)
        driver.flushOutput()
        return 0
    }

    private fun emitEvent(event: WorkerEvent) {
        printer(DiskJson.encodeEvent(event))
        driver.flushOutput()
    }
}
