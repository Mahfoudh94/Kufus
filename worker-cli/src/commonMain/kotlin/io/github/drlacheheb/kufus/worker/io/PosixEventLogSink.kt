package io.github.drlacheheb.kufus.worker.io

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs

fun interface WorkerEventSink {
    fun writeLine(line: String)

    fun close() = Unit
}

/**
 * Opens an append-only POSIX file sink mirroring NDJSON [io.github.drlacheheb.kufus.disk.model.WorkerEvent] lines.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun openPosixEventLogSink(path: String): WorkerEventSink {
    val file = fopen(path, "ab") ?: error("Unable to open event log '$path'")
    return object : WorkerEventSink {
        private var closed = false

        override fun writeLine(line: String) {
            if (closed) return
            val targetFile: kotlinx.cinterop.CPointer<FILE> = file
            fputs(line, targetFile)
            fputs("\n", targetFile)
            fflush(targetFile)
        }

        override fun close() {
            if (!closed) {
                closed = true
                fclose(file)
            }
        }
    }
}
