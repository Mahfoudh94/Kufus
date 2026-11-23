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
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.linux.MNT_DETACH
import platform.linux.SYS_syncfs
import platform.linux.mount
import platform.linux.umount
import platform.linux.umount2
import platform.posix.EBUSY
import platform.posix.EEXIST
import platform.posix.EINTR
import platform.posix.O_CREAT
import platform.posix.O_DIRECTORY
import platform.posix.O_RDONLY
import platform.posix.O_TRUNC
import platform.posix.O_WRONLY
import platform.posix.S_IRGRP
import platform.posix.S_IROTH
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.errno
import platform.posix.fsync
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.open
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.strerror
import platform.posix.syscall
import platform.posix.usleep
import platform.posix.write

private const val LINUX_DIR_PRIVATE_MODE = 0x1C0u
private const val LINUX_DIR_PUBLIC_MODE = 0x1EDu
private const val LINUX_MAX_MOUNT_ATTEMPTS = 32
private const val LINUX_UMOUNT_MAX_RETRIES = 20
private const val LINUX_UMOUNT_RETRY_MICROS = 200_000u

@OptIn(ExperimentalForeignApi::class)
actual fun mountPlatformFormattedPartition(
    devicePath: String,
    partitionIndex: Int,
    filesystem: FilesystemType,
): Result<MountedPartitionHandle> =
    runCatching {
        val canonicalDisk = resolveRealPath(devicePath) ?: devicePath
        val partNode = waitForLinuxPartitionNode(canonicalDisk, partitionIndex)
        val mountDir = createPrivateLinuxMountDir()
        try {
            mountLinuxPartitionNode(partNode, mountDir, filesystem)
        } catch (error: Throwable) {
            runCatching { rmdir(mountDir) }
            throw error
        }
        LinuxMountedPartitionHandle(mountDir)
    }

@OptIn(ExperimentalForeignApi::class)
private fun createPrivateLinuxMountDir(): String {
    val pid = getpid()
    for (attempt in 0 until LINUX_MAX_MOUNT_ATTEMPTS) {
        val candidate = "/tmp/kufus-win-$pid-$attempt"
        if (mkdir(candidate, LINUX_DIR_PRIVATE_MODE) == 0) {
            return candidate
        }
    }
    throw FlashStepException("MOUNT_FAILED", "Unable to create private temporary mountpoint under /tmp")
}

@OptIn(ExperimentalForeignApi::class)
private fun mountLinuxPartitionNode(
    partNode: String,
    mountDir: String,
    filesystem: FilesystemType,
) {
    when (filesystem) {
        FilesystemType.FAT32 -> {
            if (mount(partNode, mountDir, "vfat", 0uL, null) != 0) {
                val cli = runPosixCommandNoShell(listOf("mount", "-t", "vfat", partNode, mountDir))
                if (cli.exitCode != 0) {
                    throw FlashStepException(
                        code = "MOUNT_FAILED",
                        message = "Failed to mount FAT32 partition '$partNode': ${cli.output.ifBlank { linuxErrText(errno) }}",
                    )
                }
            }
        }
        FilesystemType.NTFS -> mountLinuxNtfsNode(partNode, mountDir)
        else -> throw FlashStepException("UNSUPPORTED_FILESYSTEM", "Unsupported Windows installer filesystem: $filesystem")
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun mountLinuxNtfsNode(
    partNode: String,
    mountDir: String,
) {
    if (mount(partNode, mountDir, "ntfs3", 0uL, null) == 0) return
    val firstErr = errno
    if (runPosixCommandNoShell(listOf("mount", "-t", "ntfs-3g", partNode, mountDir)).exitCode == 0) return
    val fallback = runPosixCommandNoShell(listOf("mount", partNode, mountDir))
    if (fallback.exitCode != 0) {
        throw FlashStepException(
            code = "MOUNT_FAILED",
            message = "Failed to mount NTFS partition '$partNode': ${fallback.output.ifBlank { linuxErrText(firstErr) }}",
        )
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LinuxMountedPartitionHandle(
    private val mountDir: String,
) : MountedPartitionHandle {
    private var unmounted = false

    override fun ensureDirectory(relativeDirPath: String) {
        val parts = relativeDirPath.split('/').filter { it.isNotEmpty() }
        var current = mountDir
        for (segment in parts) {
            current = "$current/$segment"
            ensureSinglePosixDirectory(current)
        }
    }

    override fun openFileForWrite(relativeFilePath: String): WritableMountedFile {
        val fullPath = "$mountDir/${relativeFilePath.trimStart('/')}"
        val mode = S_IRUSR or S_IWUSR or S_IRGRP or S_IROTH
        val fd = open(fullPath, O_WRONLY or O_CREAT or O_TRUNC, mode)
        if (fd < 0) {
            throw FlashStepException(
                code = "FILE_CREATE_FAILED",
                message = "Unable to create '$fullPath': ${linuxErrText(errno)}",
            )
        }
        return LinuxWritableMountedFile(fullPath, fd)
    }

    override fun sync() {
        syncMountedFilesystem(mountDir)
    }

    override fun unmountAndClose() {
        if (unmounted) return
        unmounted = true
        syncMountedFilesystem(mountDir)
        var cleanlyUnmounted = false
        var attempt = 0
        var shouldRetry = true
        while (!cleanlyUnmounted && shouldRetry && attempt < LINUX_UMOUNT_MAX_RETRIES) {
            if (umount(mountDir) == 0) {
                cleanlyUnmounted = true
            } else if (errno != EBUSY) {
                shouldRetry = false
            } else {
                usleep(LINUX_UMOUNT_RETRY_MICROS)
                attempt++
            }
        }
        if (!cleanlyUnmounted && runPosixCommandNoShell(listOf("umount", mountDir)).exitCode != 0) {
            umount2(mountDir, MNT_DETACH)
        }
        rmdir(mountDir)
    }

    private fun syncMountedFilesystem(path: String) {
        val dirFd = open(path, O_RDONLY or O_DIRECTORY)
        if (dirFd >= 0) {
            try {
                syscall(SYS_syncfs.toLong(), dirFd)
                fsync(dirFd)
            } finally {
                close(dirFd)
            }
        }
    }

    private fun ensureSinglePosixDirectory(path: String) {
        if (mkdir(path, LINUX_DIR_PUBLIC_MODE) == 0) return
        if (errno == EEXIST && isExistingDirectory(path)) return
        throw FlashStepException("DIR_CREATE_FAILED", "Unable to create directory '$path': ${linuxErrText(errno)}")
    }

    private fun isExistingDirectory(path: String): Boolean =
        memScoped {
            val st = alloc<stat>()
            stat(path, st.ptr) == 0 && (st.st_mode.toInt() and platform.posix.S_IFMT) == platform.posix.S_IFDIR
        }
}

@OptIn(ExperimentalForeignApi::class)
private class LinuxWritableMountedFile(
    private val fullPath: String,
    private val fd: Int,
) : WritableMountedFile {
    private var closed = false
    private var bytesSinceSync = 0L

    override fun write(
        bytes: ByteArray,
        length: Int,
    ) {
        if (length <= 0) return
        bytes.usePinned { pinned ->
            var offset = 0
            while (offset < length) {
                val written = write(fd, pinned.addressOf(offset), (length - offset).toULong())
                if (written > 0L) {
                    offset += written.toInt()
                } else if (written < 0L && errno == EINTR) {
                    continue
                } else {
                    throw FlashStepException("FILE_WRITE_FAILED", "Write error on '$fullPath': ${linuxErrText(errno)}")
                }
            }
        }
        bytesSinceSync += length
        if (bytesSinceSync >= LINUX_WRITE_SYNC_WINDOW_BYTES) {
            flushAndDropLinuxFdCache(fd, "FILE_WRITE_FAILED", fullPath, fullSync = false)
            bytesSinceSync = 0L
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        try {
            flushAndDropLinuxFdCache(fd, "FILE_WRITE_FAILED", fullPath, fullSync = true)
        } finally {
            close(fd)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun linuxErrText(code: Int): String = strerror(code)?.toKString() ?: "errno $code"
