package io.github.drlacheheb.kufus.domain.repository

import io.github.drlacheheb.kufus.disk.flash.FlashRequest
import io.github.drlacheheb.kufus.disk.model.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow

fun interface FlashRepository {
    fun flashImage(request: FlashRequest): Flow<WorkerEvent>

    fun flashWindowsIso(request: WindowsFlashRequest): Flow<WorkerEvent> = emptyFlow()
}
