package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FormatEngine
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.PartitionTableBuilder
import io.github.drlacheheb.kufus.disk.WorkerEvent
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.F_OK
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.access
import platform.posix.close
import platform.posix.closedir
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.opendir
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.system
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FORMAT_TEST_DISK_BYTES = 48L * 1024L * 1024L
private const val SECTOR_512 = 512
private const val SLICE_COPY_BUF_BYTES = 262_144
private const val TEMP_PATH_MAX = 4096

class LinuxFormatIntegrationTest {
    @Test
    fun formatsGptAndMbrFat32ImagesPassingRealFsckVfatValidation() {
        val root = createTempDir()
        try {
            listOf(PartitionScheme.GPT, PartitionScheme.MBR).forEachIndexed { index, scheme ->
                val diskPath = "$root/virtual-usb-$index.img"
                val slicePath = "$root/part1-$index.vfat"
                createSparseFile(diskPath, FORMAT_TEST_DISK_BYTES)

                val target = LocalPosixFileTarget(diskPath, SECTOR_512, FORMAT_TEST_DISK_BYTES)
                val events = mutableListOf<WorkerEvent>()
                val fullWipe = index == 1
                val completed =
                    FormatEngine.execute(
                        request =
                            FormatRequest(
                                deviceId = diskPath,
                                partitionScheme = scheme,
                                filesystem = FilesystemType.FAT32,
                                volumeLabel = "KUFUS_${scheme.name}",
                                quickFormat = !fullWipe,
                            ),
                        target = target,
                        progressIntervalMs = 0L,
                        clockMs = { 50L },
                        onEvent = events::add,
                    )

                assertEquals(false, completed.verified)
                assertTrue(events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.PARTITIONING })
                if (fullWipe) {
                    assertTrue(events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.WIPING && it.bytesProcessed == FORMAT_TEST_DISK_BYTES })
                }

                val span = PartitionTableBuilder.computeSinglePartitionSpan(FORMAT_TEST_DISK_BYTES, SECTOR_512, scheme)
                extractFileSlice(diskPath, slicePath, span.startByteOffset, span.sizeBytes)

                if (posixPathExists("/usr/bin/fsck.vfat")) {
                    val rc = runShellCheck("/usr/bin/fsck.vfat -n '$slicePath' >/dev/null 2>&1")
                    assertEquals(0, rc, "fsck.vfat failed for $scheme FAT32 partition")
                }
            }
        } finally {
            removeDirRecursively(root)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun posixPathExists(path: String): Boolean = access(path, F_OK) == 0

@OptIn(ExperimentalForeignApi::class)
private fun runShellCheck(command: String): Int = system(command)

@OptIn(ExperimentalForeignApi::class)
private fun extractFileSlice(
    sourcePath: String,
    destPath: String,
    startOffset: Long,
    lengthBytes: Long,
) {
    val inFd = open(sourcePath, O_RDONLY)
    if (inFd < 0) error("Failed opening $sourcePath")
    val outFd = open(destPath, O_RDWR or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
    if (outFd < 0) {
        close(inFd)
        error("Failed creating $destPath")
    }
    try {
        val buf = ByteArray(SLICE_COPY_BUF_BYTES)
        var remaining = lengthBytes
        var offset = startOffset
        var destOffset = 0L
        buf.usePinned { pinned ->
            while (remaining > 0L) {
                val step = minOf(buf.size.toLong(), remaining).toInt()
                val n = pread(inFd, pinned.addressOf(0), step.toULong(), offset)
                if (n <= 0L) break
                val written = pwrite(outFd, pinned.addressOf(0), n.toULong(), destOffset)
                if (written != n) error("Slice write failed")
                offset += n
                destOffset += n
                remaining -= n
            }
        }
        fsync(outFd)
    } finally {
        close(outFd)
        close(inFd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class LocalPosixFileTarget(
    private val path: String,
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
) : BlockFlashTarget {
    private var fd = -1

    override fun unmountAndLock() {
        fd = open(path, O_RDWR)
        if (fd < 0) error("Unable to open $path")
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        var written = 0
        buffer.usePinned { pinned ->
            while (written < length) {
                val n = pwrite(fd, pinned.addressOf(written), (length - written).toULong(), offset + written)
                if (n <= 0L) error("pwrite failed at ${offset + written}")
                written += n.toInt()
            }
        }
    }

    override fun syncAndFlush() {
        if (fd >= 0) fsync(fd)
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int = 0

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun createTempDir(): String =
    memScoped {
        val template = "/tmp/kufus-fmt-XXXXXX".cstr.ptr
        val created = mkdtemp(template)?.toKString() ?: error("mkdtemp failed")
        val buf = allocArray<ByteVar>(TEMP_PATH_MAX)
        realpath(created, buf)?.toKString() ?: created
    }

@OptIn(ExperimentalForeignApi::class)
private fun createSparseFile(
    path: String,
    sizeBytes: Long,
) {
    val fd = open(path, O_RDWR or O_CREAT or O_TRUNC, S_IRUSR or S_IWUSR)
    if (fd < 0) error("open failed for $path")
    try {
        if (ftruncate(fd, sizeBytes) != 0) error("ftruncate failed")
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun removeDirRecursively(path: String) {
    if (unlink(path) == 0) return
    val dir = opendir(path) ?: return
    val children =
        try {
            buildList {
                while (true) {
                    val entry = readdir(dir) ?: break
                    val name = entry.pointed.d_name.toKString()
                    if (name != "." && name != "..") {
                        add(name)
                    }
                }
            }
        } finally {
            closedir(dir)
        }
    children.forEach { child -> removeDirRecursively("$path/$child") }
    rmdir(path)
}
