package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.archive.inspectImage
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EINTR
import platform.posix.O_RDONLY
import platform.posix.S_IFMT
import platform.posix.S_IFREG
import platform.posix.close
import platform.posix.errno
import platform.posix.fstat
import platform.posix.open
import platform.posix.pread
import platform.posix.stat
import platform.posix.strerror

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformSeekableReader(path: String): Result<CloseableSeekableReader> {
    if (path.isBlank()) {
        return Result.failure(IllegalArgumentException("Image path cannot be blank"))
    }
    val fd = open(path, O_RDONLY)
    if (fd < 0) {
        return Result.failure(IllegalStateException("Unable to open file '$path': ${posixErrorMessage(errno)}"))
    }
    val sizeBytes =
        queryRegularFileSize(fd, path).getOrElse { error ->
            close(fd)
            return Result.failure(error)
        }
    return Result.success(PosixFileReader(fd, sizeBytes))
}

@OptIn(ExperimentalForeignApi::class)
actual fun inspectPlatformImage(path: String): Result<ImageInfo> =
    openPlatformSeekableReader(path).mapCatching { reader ->
        try {
            inspectImage(path, reader)
        } finally {
            reader.close()
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun queryRegularFileSize(
    fd: Int,
    path: String,
): Result<Long> =
    memScoped {
        val st = alloc<stat>()
        if (fstat(fd, st.ptr) != 0) {
            return Result.failure(IllegalStateException("Unable to stat image '$path': ${posixErrorMessage(errno)}"))
        }
        val isRegular = (st.st_mode.toInt() and S_IFMT) == S_IFREG
        if (!isRegular) {
            return Result.failure(IllegalArgumentException("Image path '$path' is not a regular file"))
        }
        if (st.st_size < 0L) {
            return Result.failure(IllegalStateException("Invalid negative size for '$path'"))
        }
        Result.success(st.st_size)
    }

@OptIn(ExperimentalForeignApi::class)
private class PosixFileReader(
    private val fd: Int,
    private val fileSize: Long,
) : CloseableSeekableReader {
    override fun sizeBytes(): Long = fileSize

    override fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0L || offset >= fileSize || length <= 0) return ByteArray(0)
        val toRead = minOf(length.toLong(), fileSize - offset).toInt()
        val buffer = ByteArray(toRead)
        var totalRead = 0
        buffer.usePinned { pinned ->
            while (totalRead < toRead) {
                val chunk =
                    pread(
                        fd,
                        pinned.addressOf(totalRead),
                        (toRead - totalRead).toULong(),
                        offset + totalRead,
                    )
                totalRead += handleReadChunk(chunk, offset + totalRead) ?: break
            }
        }
        return if (totalRead == toRead) buffer else buffer.copyOf(totalRead)
    }

    override fun close() {
        close(fd)
    }
}

private fun handleReadChunk(
    chunk: Long,
    offset: Long,
): Int? {
    if (chunk > 0L) return chunk.toInt()
    if (chunk == 0L) return null
    if (errno == EINTR) return 0
    error("Read failed at offset $offset: ${posixErrorMessage(errno)}")
}

@OptIn(ExperimentalForeignApi::class)
private fun posixErrorMessage(errorNumber: Int): String = strerror(errorNumber)?.toKString() ?: "errno $errorNumber"
