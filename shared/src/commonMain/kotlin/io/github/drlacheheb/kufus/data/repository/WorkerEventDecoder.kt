package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent

internal fun decodeWorkerDrivesLine(jsonLine: String): Result<List<UsbDrive>> =
    decodeWorkerEventLine(jsonLine) { event ->
        if (event is WorkerEvent.DrivesListed) event.drives else null
    }

internal fun decodeWorkerImageLine(jsonLine: String): Result<ImageInfo> =
    decodeWorkerEventLine(jsonLine) { event ->
        if (event is WorkerEvent.ImageInspected) event.info else null
    }

internal fun decodeWorkerFlashLine(jsonLine: String): Result<WorkerEvent> =
    try {
        when (val event = DiskJson.decodeEvent(jsonLine)) {
            is WorkerEvent.Started,
            is WorkerEvent.Progress,
            is WorkerEvent.Completed,
            -> Result.success(event)
            is WorkerEvent.Error -> Result.failure(IllegalStateException("${event.code}: ${event.message}"))
            else -> Result.failure(IllegalStateException("Unexpected flash event: $event"))
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        Result.failure(e)
    }

private inline fun <T> decodeWorkerEventLine(
    jsonLine: String,
    extract: (WorkerEvent) -> T?,
): Result<T> =
    try {
        when (val event = DiskJson.decodeEvent(jsonLine)) {
            is WorkerEvent.Error -> Result.failure(IllegalStateException("${event.code}: ${event.message}"))
            else -> {
                val extracted = extract(event)
                if (extracted != null) {
                    Result.success(extracted)
                } else {
                    Result.failure(IllegalStateException("Unexpected worker event: $event"))
                }
            }
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        Result.failure(e)
    }
