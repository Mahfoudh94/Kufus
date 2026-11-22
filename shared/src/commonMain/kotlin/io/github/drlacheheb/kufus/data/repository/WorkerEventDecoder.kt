package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent

internal fun decodeWorkerDrivesLine(jsonLine: String): Result<List<UsbDrive>> =
    try {
        when (val event = DiskJson.decodeEvent(jsonLine)) {
            is WorkerEvent.DrivesListed -> Result.success(event.drives)
            is WorkerEvent.Error -> Result.failure(IllegalStateException("${event.code}: ${event.message}"))
            else -> Result.failure(IllegalStateException("Unexpected worker event: $event"))
        }
    } catch (
        @Suppress("TooGenericExceptionCaught") e: Exception,
    ) {
        Result.failure(e)
    }
