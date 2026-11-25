package io.github.drlacheheb.kufus.disk.flash

import kotlin.native.concurrent.ObsoleteWorkersApi
import kotlin.native.concurrent.TransferMode
import kotlin.native.concurrent.Worker

internal actual fun createBackgroundSlotExecutor(name: String): BackgroundSlotExecutor = NativeBackgroundSlotExecutor(name)

private class SlotOutcome<T>(
    val value: T?,
    val error: Throwable?,
)

@OptIn(ObsoleteWorkersApi::class)
private class NativeBackgroundSlotExecutor(
    name: String,
) : BackgroundSlotExecutor {
    private val worker = Worker.start(name = name)

    override fun <T> submit(task: () -> T): BackgroundSlotTask<T> {
        val future =
            worker.execute(TransferMode.UNSAFE, { task }) { work ->
                try {
                    SlotOutcome(value = work(), error = null)
                } catch (
                    @Suppress("TooGenericExceptionCaught") error: Throwable,
                ) {
                    SlotOutcome(value = null, error = error)
                }
            }
        return object : BackgroundSlotTask<T> {
            @Suppress("UNCHECKED_CAST")
            override fun await(): T {
                val outcome = future.result
                outcome.error?.let { throw it }
                return outcome.value as T
            }
        }
    }

    override fun close() {
        worker.requestTermination(processScheduledJobs = false).result
    }
}
