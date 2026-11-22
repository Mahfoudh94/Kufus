package io.github.drlacheheb.kufus.data.process

import io.github.drlacheheb.kufus.host.HostCommandOutput
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.ProducerScope
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ProcessCommandRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommandRunner {
    override suspend fun run(command: List<String>): Result<HostCommandOutput> =
        withContext(ioDispatcher) {
            try {
                val process = ProcessBuilder(command).redirectErrorStream(false).start()
                val stdout = process.inputStream.bufferedReader().use { it.readText() }
                val stderr = process.errorStream.bufferedReader().use { it.readText() }
                val exitCode = process.waitFor()
                Result.success(HostCommandOutput(exitCode = exitCode, stdout = stdout, stderr = stderr))
            } catch (e: CancellationException) {
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                Result.failure(e)
            }
        }

    override fun streamLines(command: List<String>): Flow<String> =
        callbackFlow {
            var activeProcess: Process? = null
            val readerJob =
                launch(ioDispatcher) {
                    try {
                        val process = ProcessBuilder(command).redirectErrorStream(false).start()
                        activeProcess = process
                        streamProcessOutput(process)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        close(e)
                    }
                }
            awaitClose {
                terminateProcess(activeProcess)
                readerJob.cancel()
            }
        }

    private suspend fun ProducerScope<String>.streamProcessOutput(process: Process) {
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null && isActive) {
                send(line)
                line = reader.readLine()
            }
        }
        val exitCode = process.waitFor()
        if (exitCode != 0 && isActive) {
            val errorText = process.errorStream.bufferedReader().use { it.readText().trim() }
            close(IllegalStateException("Process exited with code $exitCode: $errorText"))
        } else {
            close()
        }
    }

    private fun terminateProcess(process: Process?) {
        if (process == null) return
        process.destroy()
        if (process.isAlive) {
            process.destroyForcibly()
        }
    }
}
