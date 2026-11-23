package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.WorkerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

fun interface FlashRepository {
    fun flashImage(request: FlashRequest): Flow<WorkerEvent>

    fun flashWindowsIso(request: WindowsFlashRequest): Flow<WorkerEvent> = emptyFlow()
}
