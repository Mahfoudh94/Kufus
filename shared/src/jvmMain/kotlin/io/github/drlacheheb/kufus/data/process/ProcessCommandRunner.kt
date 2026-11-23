package io.github.drlacheheb.kufus.data.process

import io.github.drlacheheb.kufus.host.ElevatedLineStreamResult
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
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private const val TERMINATE_GRACE_MS = 100L

class ProcessCommandRunner(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : CommandRunner {
    private val activeProcesses = ConcurrentHashMap.newKeySet<Process>()

    override suspend fun run(command: List<String>): Result<HostCommandOutput> =
        withContext(ioDispatcher) {
            val processRef = AtomicReference<Process?>(null)
            try {
                val process = startTrackedProcess(command, processRef)
                if (!isActive) {
                    terminateProcess(process)
                    throw CancellationException("Command cancelled")
                }
                val stderrFuture = CompletableFuture.supplyAsync { readAllText(process.errorStream) }
                val stdout = readAllText(process.inputStream)
                val stderr = stderrFuture.get()
                val exitCode = process.waitFor()
                Result.success(HostCommandOutput(exitCode = exitCode, stdout = stdout, stderr = stderr))
            } catch (e: CancellationException) {
                terminateProcess(processRef.getAndSet(null))
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                terminateProcess(processRef.getAndSet(null))
                Result.failure(e)
            } finally {
                processRef.get()?.let(activeProcesses::remove)
            }
        }

    override suspend fun streamLines(
        command: List<String>,
        onLine: (String) -> Unit,
    ): Result<ElevatedLineStreamResult> =
        withContext(ioDispatcher) {
            val processRef = AtomicReference<Process?>(null)
            try {
                val process = startTrackedProcess(command, processRef)
                if (!isActive) {
                    terminateProcess(process)
                    throw CancellationException("Command cancelled")
                }
                val stderrFuture = CompletableFuture.supplyAsync { readAllText(process.errorStream) }
                process.inputStream.bufferedReader().use { reader ->
                    var line = reader.readLine()
                    while (line != null && isActive) {
                        if (line.isNotBlank()) {
                            onLine(line)
                        }
                        line = reader.readLine()
                    }
                }
                val exitCode = process.waitFor()
                val stderr = stderrFuture.get().trim()
                Result.success(ElevatedLineStreamResult(exitCode = exitCode, stderr = stderr))
            } catch (e: CancellationException) {
                terminateProcess(processRef.getAndSet(null))
                throw e
            } catch (
                @Suppress("TooGenericExceptionCaught") e: Exception,
            ) {
                terminateProcess(processRef.getAndSet(null))
                Result.failure(e)
            } finally {
                processRef.get()?.let(activeProcesses::remove)
            }
        }

    override fun streamLines(command: List<String>): Flow<String> =
        callbackFlow {
            val processRef = AtomicReference<Process?>(null)
            val closed = AtomicBoolean(false)
            val readerJob =
                launch(ioDispatcher) {
                    var startedProcess: Process? = null
                    try {
                        val process = ProcessBuilder(command).redirectErrorStream(false).start()
                        startedProcess = process
                        activeProcesses.add(process)
                        processRef.set(process)
                        if (closed.get() || !isActive) {
                            terminateProcess(process)
                            return@launch
                        }
                        streamProcessOutput(process)
                    } catch (e: CancellationException) {
                        terminateProcess(startedProcess)
                        throw e
                    } catch (
                        @Suppress("TooGenericExceptionCaught") e: Exception,
                    ) {
                        if (!closed.get()) {
                            close(e)
                        }
                    } finally {
                        startedProcess?.let(activeProcesses::remove)
                    }
                }
            awaitClose {
                closed.set(true)
                terminateProcess(processRef.getAndSet(null))
                readerJob.cancel()
            }
        }

    override fun terminateActiveProcesses() {
        val snapshot = activeProcesses.toList()
        snapshot.forEach { process ->
            terminateProcess(process)
            activeProcesses.remove(process)
        }
    }

    private fun startTrackedProcess(
        command: List<String>,
        processRef: AtomicReference<Process?>,
    ): Process {
        val process = ProcessBuilder(command).redirectErrorStream(false).start()
        activeProcesses.add(process)
        processRef.set(process)
        return process
    }

    private suspend fun ProducerScope<String>.streamProcessOutput(process: Process) {
        process.inputStream.bufferedReader().use { reader ->
            var line = reader.readLine()
            while (line != null && isActive) {
                send(line)
                line = reader.readLine()
            }
        }
        if (!isActive) return
        val exitCode = process.waitFor()
        if (exitCode != 0 && isActive) {
            val errorText = readAllText(process.errorStream).trim()
            close(IllegalStateException("Process exited with code $exitCode: $errorText"))
        } else {
            close()
        }
    }

    private fun readAllText(stream: InputStream): String = stream.bufferedReader().use { it.readText() }

    private fun terminateProcess(process: Process?) {
        if (process == null) return
        activeProcesses.remove(process)
        runCatching { process.outputStream.close() }
        runCatching { process.inputStream.close() }
        runCatching { process.errorStream.close() }
        process.destroy()
        runCatching { process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS) }
        if (process.isAlive) {
            process.destroyForcibly()
            runCatching { process.waitFor(TERMINATE_GRACE_MS, TimeUnit.MILLISECONDS) }
        }
    }
}
