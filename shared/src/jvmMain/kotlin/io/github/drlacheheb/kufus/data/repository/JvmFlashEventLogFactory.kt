package io.github.drlacheheb.kufus.data.repository

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.Path

private const val NEWLINE_BYTE: Byte = '\n'.code.toByte()

class JvmFlashEventLogFactory(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : FlashEventLogFactory {
    override suspend fun createSession(): FlashEventLogSession =
        withContext(ioDispatcher) {
            val filePath = Files.createTempFile("kufus-flash-", ".ndjson")
            JvmFlashEventLogSession(filePath, ioDispatcher)
        }
}

private class JvmFlashEventLogSession(
    private val filePath: Path,
    private val ioDispatcher: CoroutineDispatcher,
) : FlashEventLogSession {
    override val path: String = filePath.toAbsolutePath().toString()
    private var readCursor: Long = 0L

    override suspend fun drainLines(onLine: suspend (String) -> Unit) {
        val completeLines =
            withContext(ioDispatcher) {
                readCompleteLinesFromCursor()
            }
        completeLines.forEach { line -> onLine(line) }
    }

    override suspend fun closeAndDelete() {
        withContext(ioDispatcher) {
            runCatching { Files.deleteIfExists(filePath) }
        }
    }

    private fun readCompleteLinesFromCursor(): List<String> {
        if (!Files.exists(filePath)) return emptyList()
        RandomAccessFile(filePath.toFile(), "r").use { raf ->
            val fileLength = raf.length()
            if (fileLength <= readCursor) return emptyList()
            val unreadCount = (fileLength - readCursor).toInt()
            val rawBytes = ByteArray(unreadCount)
            raf.seek(readCursor)
            raf.readFully(rawBytes)
            val lastNewline = rawBytes.indexOfLast { it == NEWLINE_BYTE }
            if (lastNewline < 0) return emptyList()
            readCursor += lastNewline + 1L
            val chunk = rawBytes.decodeToString(startIndex = 0, endIndex = lastNewline)
            return chunk
                .lineSequence()
                .map { it.trimEnd('\r') }
                .filter { it.isNotBlank() }
                .toList()
        }
    }
}
