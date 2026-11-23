package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import kotlinx.coroutines.flow.Flow

fun interface FormatRepository {
    fun formatDrive(request: FormatRequest): Flow<WorkerEvent>
}
