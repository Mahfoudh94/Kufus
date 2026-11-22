package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.filterSafeUsbDrives
import io.github.drlacheheb.kufus.disk.listPlatformUsbDrives

fun main(args: Array<String>) {
    when (args.firstOrNull()) {
        "--list" -> {
            val drives = filterSafeUsbDrives(listPlatformUsbDrives())
            println(DiskJson.encodeEvent(WorkerEvent.DrivesListed(drives)))
        }
        "--version" -> {
            println("kufus-worker 1.0.0")
        }
        else -> {
            val error =
                WorkerEvent.Error(
                    code = "INVALID_ARGS",
                    message = "Supported flags: --list, --version",
                )
            println(DiskJson.encodeEvent(error))
        }
    }
}
