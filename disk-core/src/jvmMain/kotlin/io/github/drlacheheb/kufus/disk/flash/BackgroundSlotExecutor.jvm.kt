package io.github.drlacheheb.kufus.disk.flash

import java.util.concurrent.Callable
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors

internal actual fun createBackgroundSlotExecutor(name: String): BackgroundSlotExecutor = JvmBackgroundSlotExecutor(name)

private class JvmBackgroundSlotExecutor(
    name: String,
) : BackgroundSlotExecutor {
    private val executor =
        Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, name).apply { isDaemon = true }
        }

    override fun <T> submit(task: () -> T): BackgroundSlotTask<T> {
        val future = executor.submit(Callable { task() })
        return object : BackgroundSlotTask<T> {
            override fun await(): T =
                try {
                    future.get()
                } catch (e: ExecutionException) {
                    throw e.cause ?: e
                }
        }
    }

    override fun close() {
        executor.shutdown()
    }
}
