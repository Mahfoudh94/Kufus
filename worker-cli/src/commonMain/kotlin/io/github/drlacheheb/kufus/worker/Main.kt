package io.github.drlacheheb.kufus.worker

fun main(args: Array<String>) {
    WorkerCliApplication(driver = PlatformNativeDiskDriver()).run(args)
}
