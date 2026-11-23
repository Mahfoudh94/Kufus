package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.windows.MountedPartitionHandle
import io.github.drlacheheb.kufus.disk.windows.WritableMountedFile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import kotlinx.cinterop.wcstr
import platform.windows.CREATE_ALWAYS
import platform.windows.CloseHandle
import platform.windows.CreateDirectoryW
import platform.windows.CreateFileW
import platform.windows.DWORDVar
import platform.windows.ERROR_ALREADY_EXISTS
import platform.windows.FILE_ATTRIBUTE_NORMAL
import platform.windows.FILE_SHARE_READ
import platform.windows.FlushFileBuffers
import platform.windows.GENERIC_WRITE
import platform.windows.GetLastError
import platform.windows.HANDLE
import platform.windows.INVALID_HANDLE_VALUE
import platform.windows.WriteFile

@OptIn(ExperimentalForeignApi::class)
actual fun mountPlatformFormattedPartition(
    devicePath: String,
    partitionIndex: Int,
    filesystem: FilesystemType,
): Result<MountedPartitionHandle> =
    runCatching {
        val diskIndex =
            parsePhysicalDriveIndex(devicePath)
                ?: throw FlashStepException("INVALID_DEVICE", "Windows target must be PhysicalDriveN: '$devicePath'")
        refreshWin32DiskProperties(devicePath)
        val volumeRoot = waitForWin32PartitionVolumeRoot(diskIndex, partitionIndex)
        Win32MountedPartitionHandle(volumeRoot)
    }

@OptIn(ExperimentalForeignApi::class)
private class Win32MountedPartitionHandle(
    private val volumeRootWithTrailingSlash: String,
) : MountedPartitionHandle {
    override fun ensureDirectory(relativeDirPath: String) {
        val parts = relativeDirPath.split('/').filter { it.isNotEmpty() }
        var current = volumeRootWithTrailingSlash.trimEnd('\\')
        for (segment in parts) {
            current = "$current\\$segment"
            memScoped {
                val created = CreateDirectoryW(current.wcstr.ptr, null)
                if (created == 0 && GetLastError() != ERROR_ALREADY_EXISTS.toUInt()) {
                    throw FlashStepException("DIR_CREATE_FAILED", "Unable to create directory '$current' (error ${GetLastError()})")
                }
            }
        }
    }

    override fun openFileForWrite(relativeFilePath: String): WritableMountedFile {
        val relWin = relativeFilePath.trimStart('/').replace('/', '\\')
        val fullPath = "${volumeRootWithTrailingSlash.trimEnd('\\')}\\$relWin"
        val handle =
            memScoped {
                val h =
                    CreateFileW(
                        lpFileName = fullPath.wcstr.ptr,
                        dwDesiredAccess = GENERIC_WRITE,
                        dwShareMode = FILE_SHARE_READ.toUInt(),
                        lpSecurityAttributes = null,
                        dwCreationDisposition = CREATE_ALWAYS.toUInt(),
                        dwFlagsAndAttributes = FILE_ATTRIBUTE_NORMAL.toUInt(),
                        hTemplateFile = null,
                    )
                if (h == null || h == INVALID_HANDLE_VALUE) null else h
            } ?: throw FlashStepException("FILE_CREATE_FAILED", "Unable to create '$fullPath' (error ${GetLastError()})")
        return Win32WritableMountedFile(fullPath, handle)
    }

    override fun sync() = Unit

    override fun unmountAndClose() = Unit
}

@OptIn(ExperimentalForeignApi::class)
private class Win32WritableMountedFile(
    private val fullPath: String,
    private val handle: HANDLE,
) : WritableMountedFile {
    private var closed = false

    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        if (length <= 0) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < length) {
                val written = writeChunk(offset, length - offset, pinned)
                offset += written
            }
        }
    }

    private fun writeChunk(
        offset: Int,
        remaining: Int,
        pinned: kotlinx.cinterop.Pinned<ByteArray>,
    ): Int =
        memScoped {
            val writtenVar = alloc<DWORDVar>()
            val ok =
                WriteFile(
                    hFile = handle,
                    lpBuffer = pinned.addressOf(offset),
                    nNumberOfBytesToWrite = remaining.toUInt(),
                    lpNumberOfBytesWritten = writtenVar.ptr,
                    lpOverlapped = null,
                )
            if (ok == 0 || writtenVar.value == 0u) {
                throw FlashStepException("FILE_WRITE_FAILED", "Write error on '$fullPath' (error ${GetLastError()})")
            }
            writtenVar.value.toInt()
        }

    override fun close() {
        if (closed) return
        closed = true
        FlushFileBuffers(handle)
        CloseHandle(handle)
    }
}
