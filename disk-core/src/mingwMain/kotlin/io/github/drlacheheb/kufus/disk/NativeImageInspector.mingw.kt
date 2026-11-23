package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.Pinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CloseHandle
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_BEGIN
import platform.windows.FILE_FLAG_RANDOM_ACCESS
import platform.windows.FILE_SHARE_DELETE
import platform.windows.FILE_SHARE_READ
import platform.windows.FILE_SHARE_WRITE
import platform.windows.FILE_TYPE_DISK
import platform.windows.GENERIC_READ
import platform.windows.GetFileSizeEx
import platform.windows.GetFileType
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.LARGE_INTEGER
import platform.windows.OPEN_EXISTING
import platform.windows.ReadFile
import platform.windows.SetFilePointerEx

@OptIn(ExperimentalForeignApi::class)
actual fun openPlatformSeekableReader(path: String): Result<CloseableSeekableReader> {
    if (path.isBlank()) {
        return Result.failure(IllegalArgumentException("Image path cannot be blank"))
    }
    val handle =
        openReadImageHandle(path)
            ?: return Result.failure(IllegalStateException("Unable to open file '$path' (error ${GetLastError()})"))
    val sizeBytes =
        queryWin32FileSize(handle, path).getOrElse { error ->
            CloseHandle(handle)
            return Result.failure(error)
        }
    return Result.success(Win32FileReader(handle, sizeBytes))
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
private fun openReadImageHandle(path: String): HANDLE? =
    memScoped {
        val shareMode = (FILE_SHARE_READ or FILE_SHARE_WRITE or FILE_SHARE_DELETE).toUInt()
        val attributes = (FILE_ATTRIBUTE_NORMAL or FILE_FLAG_RANDOM_ACCESS).toUInt()
        val handle =
            CreateFileW(
                lpFileName = path.wcstr.ptr,
                dwDesiredAccess = GENERIC_READ,
                dwShareMode = shareMode,
                lpSecurityAttributes = null,
                dwCreationDisposition = OPEN_EXISTING.toUInt(),
                dwFlagsAndAttributes = attributes,
                hTemplateFile = null,
            )
        if (handle == null || handle == INVALID_HANDLE_VALUE) null else handle
    }

@OptIn(ExperimentalForeignApi::class)
private fun queryWin32FileSize(
    handle: HANDLE,
    path: String,
): Result<Long> =
    memScoped {
        if (GetFileType(handle) != FILE_TYPE_DISK.toUInt()) {
            return Result.failure(IllegalArgumentException("Image path '$path' is not a regular file"))
        }
        val fileSize = alloc<LARGE_INTEGER>()
        if (GetFileSizeEx(handle, fileSize.ptr) == 0) {
            return Result.failure(IllegalStateException("Unable to stat image '$path' (error ${GetLastError()})"))
        }
        val bytes = fileSize.QuadPart
        if (bytes < 0L) {
            return Result.failure(IllegalStateException("Invalid negative size for '$path'"))
        }
        Result.success(bytes)
    }

@OptIn(ExperimentalForeignApi::class)
private class Win32FileReader(
    private val handle: HANDLE,
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
                val chunk = readWin32Chunk(handle, offset + totalRead, pinned, totalRead, toRead - totalRead)
                if (chunk == 0) break
                totalRead += chunk
            }
        }
        return if (totalRead == toRead) buffer else buffer.copyOf(totalRead)
    }

    override fun close() {
        CloseHandle(handle)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readWin32Chunk(
    handle: HANDLE,
    position: Long,
    pinned: Pinned<ByteArray>,
    bufferOffset: Int,
    remaining: Int,
): Int =
    memScoped {
        val seekTarget =
            cValue<LARGE_INTEGER> {
                QuadPart = position
            }
        if (SetFilePointerEx(handle, seekTarget, null, FILE_BEGIN.toUInt()) == 0) {
            error("Seek failed at offset $position (error ${GetLastError()})")
        }
        val bytesRead = alloc<DWORDVar>()
        val ok =
            ReadFile(
                hFile = handle,
                lpBuffer = pinned.addressOf(bufferOffset),
                nNumberOfBytesToRead = remaining.toUInt(),
                lpNumberOfBytesRead = bytesRead.ptr,
                lpOverlapped = null,
            )
        if (ok == 0) {
            error("Read failed at offset $position (error ${GetLastError()})")
        }
        bytesRead.value.toInt()
    }
