package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.windows.MountedPartitionHandle
import io.github.drlacheheb.kufus.disk.windows.WritableMountedFile
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.EEXIST
import platform.posix.FILE
import platform.posix.F_OK
import platform.posix.S_IFDIR
import platform.posix.S_IFMT
import platform.posix.access
import platform.posix.errno
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fileno
import platform.posix.fopen
import platform.posix.fsync
import platform.posix.fwrite
import platform.posix.getpid
import platform.posix.mkdir
import platform.posix.rmdir
import platform.posix.stat
import platform.posix.strerror
import platform.posix.sync
import platform.posix.usleep

private const val MACOS_DIR_PRIVATE_MODE = 0x1C0
private const val MACOS_DIR_PUBLIC_MODE = 0x1ED
private const val MACOS_SLICE_WAIT_ATTEMPTS = 50
private const val MACOS_SLICE_WAIT_MICROS = 100_000u
private const val MACOS_MAX_MOUNT_ATTEMPTS = 32
private const val MACOS_LARGE_FILE_SYNC_BYTES = 16_777_216L

@OptIn(ExperimentalForeignApi::class)
actual fun mountPlatformFormattedPartition(
    devicePath: String,
    partitionIndex: Int,
    filesystem: FilesystemType,
): Result<MountedPartitionHandle> =
    runCatching {
        if (filesystem != FilesystemType.FAT32) {
            throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "Stock macOS only mounts FAT32 natively for Windows installer creation.",
            )
        }
        val bsdName = devicePath.removePrefix("/dev/").removePrefix("r")
        val blockSlice = waitForMacosBlockPartitionSlice(bsdName, partitionIndex)
        runCatching { unmountWholeMacosDisk(bsdName) }
        val mountDir = createPrivateMacosMountDir()
        val mountRes = runMacosCommandNoShell(listOf("/sbin/mount_msdos", blockSlice, mountDir))
        if (mountRes.exitCode != 0) {
            val diskutilRes = runMacosCommandNoShell(listOf("/usr/sbin/diskutil", "mount", "-mountPoint", mountDir, blockSlice))
            if (diskutilRes.exitCode != 0) {
                rmdir(mountDir)
                throw FlashStepException(
                    code = "MOUNT_FAILED",
                    message = "Failed to mount FAT32 slice '$blockSlice': ${diskutilRes.output.ifBlank { mountRes.output }}",
                )
            }
        }
        MacosMountedPartitionHandle(mountDir)
    }

@OptIn(ExperimentalForeignApi::class)
private fun waitForMacosBlockPartitionSlice(
    bsdName: String,
    partitionIndex: Int,
): String {
    val blockSlice = "/dev/${bsdName}s$partitionIndex"
    repeat(MACOS_SLICE_WAIT_ATTEMPTS) {
        if (access(blockSlice, F_OK) == 0) return blockSlice
        usleep(MACOS_SLICE_WAIT_MICROS)
    }
    throw FlashStepException("PARTITION_NOT_FOUND", "macOS partition node '$blockSlice' did not appear.")
}

@OptIn(ExperimentalForeignApi::class)
private fun createPrivateMacosMountDir(): String {
    val pid = getpid()
    for (attempt in 0 until MACOS_MAX_MOUNT_ATTEMPTS) {
        val candidate = "/tmp/kufus-win-$pid-$attempt"
        if (mkdir(candidate, MACOS_DIR_PRIVATE_MODE.toUShort()) == 0) return candidate
    }
    throw FlashStepException("MOUNT_FAILED", "Unable to create private mount directory under /tmp")
}

@OptIn(ExperimentalForeignApi::class)
private class MacosMountedPartitionHandle(
    private val mountDir: String,
) : MountedPartitionHandle {
    private var unmounted = false

    override fun ensureDirectory(relativeDirPath: String) {
        val parts = relativeDirPath.split('/').filter { it.isNotEmpty() }
        var current = mountDir
        for (segment in parts) {
            current = "$current/$segment"
            ensureSingleDir(current)
        }
    }

    override fun openFileForWrite(relativeFilePath: String): WritableMountedFile {
        val fullPath = "$mountDir/${relativeFilePath.trimStart('/')}"
        val file =
            fopen(fullPath, "wb")
                ?: throw FlashStepException(
                    code = "FILE_CREATE_FAILED",
                    message = "Unable to create '$fullPath': ${macosMountErr(errno)}",
                )
        return MacosWritableMountedFile(fullPath, file)
    }

    override fun sync() {
        platform.posix.sync()
    }

    override fun unmountAndClose() {
        if (unmounted) return
        unmounted = true
        platform.posix.sync()
        val first = runMacosCommandNoShell(listOf("/usr/sbin/diskutil", "unmount", mountDir))
        if (first.exitCode != 0) {
            runMacosCommandNoShell(listOf("/usr/sbin/diskutil", "unmount", "force", mountDir))
        }
        rmdir(mountDir)
    }

    private fun ensureSingleDir(path: String) {
        if (mkdir(path, MACOS_DIR_PUBLIC_MODE.toUShort()) == 0) return
        if (errno == EEXIST && isExistingDir(path)) return
        throw FlashStepException("DIR_CREATE_FAILED", "Unable to create directory '$path': ${macosMountErr(errno)}")
    }

    private fun isExistingDir(path: String): Boolean =
        memScoped {
            val st = alloc<stat>()
            stat(path, st.ptr) == 0 && (st.st_mode.toInt() and S_IFMT) == S_IFDIR
        }
}

@OptIn(ExperimentalForeignApi::class)
private class MacosWritableMountedFile(
    private val fullPath: String,
    private val file: CPointer<FILE>,
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
                val written = fwrite(pinned.addressOf(offset), 1u, (length - offset).toULong(), file).toInt()
                if (written <= 0) {
                    throw FlashStepException("FILE_WRITE_FAILED", "Write error on '$fullPath': ${macosMountErr(errno)}")
                }
                offset += written
            }
        }
        bytesSinceSync += length
        if (bytesSinceSync >= MACOS_LARGE_FILE_SYNC_BYTES) {
            fflush(file)
            val fd = fileno(file)
            if (fd >= 0) fsync(fd)
            bytesSinceSync = 0L
        }
    }

    override fun close() {
        if (closed) return
        closed = true
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun macosMountErr(code: Int): String = strerror(code)?.toKString() ?: "errno $code"
