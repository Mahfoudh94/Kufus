package io.github.drlacheheb.kufus.data.process

import io.github.drlacheheb.kufus.host.HostCommandExecutor
import io.github.drlacheheb.kufus.host.HostCommandOutput
import kotlinx.coroutines.flow.Flow

interface CommandRunner : HostCommandExecutor {
    override suspend fun run(command: List<String>): Result<HostCommandOutput>

    fun streamLines(command: List<String>): Flow<String>

    fun terminateActiveProcesses() = Unit
}
