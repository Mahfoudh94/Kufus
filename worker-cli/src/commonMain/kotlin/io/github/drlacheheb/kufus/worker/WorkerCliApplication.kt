package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.WorkerEvent

private const val WORKER_VERSION = "kufus-worker 1.0.0"

fun interface WorkerCommandHandler {
    fun execute(): Int
}

class WorkerCliApplication(
    private val driver: NativeDiskDriver,
    private val printer: (String) -> Unit = ::println,
) {
    private val handlers: Map<String, WorkerCommandHandler> =
        mapOf(
            "--list" to WorkerCommandHandler { handleList() },
            "--watch" to WorkerCommandHandler { handleWatch() },
            "--version" to WorkerCommandHandler { handleVersion() },
        )

    fun run(args: Array<String>): Int {
        val flag = args.firstOrNull()
        val handler = handlers[flag]
        if (handler != null) return handler.execute()
        val error =
            WorkerEvent.Error(
                code = "INVALID_ARGS",
                message = "Supported flags: ${handlers.keys.joinToString(", ")}",
            )
        printer(DiskJson.encodeEvent(error))
        driver.flushOutput()
        return 1
    }

    private fun handleList(): Int {
        val drives = driver.listUsbDrives()
        printer(DiskJson.encodeEvent(WorkerEvent.DrivesListed(drives)))
        driver.flushOutput()
        return 0
    }

    private fun handleWatch(): Int {
        driver.watchUsbDrives(
            onDrivesUpdated = { drives ->
                printer(DiskJson.encodeEvent(WorkerEvent.DrivesListed(drives)))
                driver.flushOutput()
            },
        )
        return 0
    }

    private fun handleVersion(): Int {
        printer(WORKER_VERSION)
        driver.flushOutput()
        return 0
    }
}
