package io.github.drlacheheb.kufus.disk.flash

internal interface BackgroundSlotTask<out T> {
    fun await(): T
}

internal interface BackgroundSlotExecutor {
    fun <T> submit(task: () -> T): BackgroundSlotTask<T>

    fun close()
}

internal expect fun createBackgroundSlotExecutor(name: String): BackgroundSlotExecutor
