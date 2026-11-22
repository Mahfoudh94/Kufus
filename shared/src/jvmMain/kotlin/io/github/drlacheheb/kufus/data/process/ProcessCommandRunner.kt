package io.github.drlacheheb.kufus.data.process

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ProcessCommandRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommandRunner {
    override suspend fun run(command: List<String>): Result<CommandOutput> =
        withContext(ioDispatcher) {
            try {
                val process = ProcessBuilder(command).redirectErrorStream(false).start()
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                Result.success(CommandOutput(exitCode = exitCode, stdout = stdout, stderr = stderr))
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(e)
            }
        }
}
