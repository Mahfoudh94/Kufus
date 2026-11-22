package io.github.drlacheheb.kufus.worker

import kotlin.system.exitProcess

fun main(args: Array<String>) {
    val exitCode = WorkerCliApplication(driver = PlatformNativeDiskDriver()).run(args)
    if (exitCode != 0) exitProcess(exitCode)
}
