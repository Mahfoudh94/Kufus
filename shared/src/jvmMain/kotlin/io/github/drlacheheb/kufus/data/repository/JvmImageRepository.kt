package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.archive.inspectImage
import io.github.drlacheheb.kufus.disk.io.SeekableByteReader
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption

class JvmImageRepository(
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ImageRepository {
    override suspend fun inspectImage(path: String): Result<ImageInfo> =
        withContext(ioDispatcher) {
            runCatching {
                require(path.isNotBlank()) { "Image path cannot be blank" }
                val filePath = Path.of(path)
                check(Files.exists(filePath)) { "Unable to open file '$path'" }
                require(Files.isRegularFile(filePath)) { "Image path '$path' is not a regular file" }
                FileChannel.open(filePath, StandardOpenOption.READ).use { channel ->
                    inspectImage(path, FileChannelByteReader(channel, channel.size()))
                }
            }
        }
}

private class FileChannelByteReader(
    private val channel: FileChannel,
    private val fileSize: Long,
) : SeekableByteReader {
    override fun sizeBytes(): Long = fileSize

    override fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0L || offset >= fileSize || length <= 0) return ByteArray(0)
        val toRead = minOf(length.toLong(), fileSize - offset).toInt()
        val buffer = ByteArray(toRead)
        val totalRead = readInto(offset, buffer, destinationOffset = 0, length = toRead)
        return if (totalRead == toRead) buffer else buffer.copyOf(totalRead)
    }

    override fun readInto(
        offset: Long,
        destination: ByteArray,
        destinationOffset: Int,
        length: Int,
    ): Int {
        if (offset < 0L || offset >= fileSize || length <= 0) return 0
        val toRead = minOf(length.toLong(), fileSize - offset).toInt()
        var totalRead = 0
        while (totalRead < toRead) {
            val read =
                channel.read(
                    ByteBuffer.wrap(destination, destinationOffset + totalRead, toRead - totalRead),
                    offset + totalRead,
                )
            if (read <= 0) break
            totalRead += read
        }
        return totalRead
    }
}
