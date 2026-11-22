package io.github.drlacheheb.kufus.data.repository

fun interface WorkerExecutableLocator {
    fun locateWorkerPath(): String?
}
