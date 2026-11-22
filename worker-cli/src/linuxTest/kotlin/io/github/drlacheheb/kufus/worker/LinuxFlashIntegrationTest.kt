package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.FlashEngine
import io.github.drlacheheb.kufus.disk.FlashStepException
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.disk.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.openFlashImageSource
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.O_CREAT
import platform.posix.O_RDONLY
import platform.posix.O_RDWR
import platform.posix.O_TRUNC
import platform.posix.S_IRUSR
import platform.posix.S_IWUSR
import platform.posix.close
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fsync
import platform.posix.ftruncate
import platform.posix.fwrite
import platform.posix.memset
import platform.posix.mkdtemp
import platform.posix.open
import platform.posix.opendir
import platform.posix.pread
import platform.posix.pwrite
import platform.posix.read
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.unlink
import platform.zlib.Z_DEFAULT_COMPRESSION
import platform.zlib.Z_DEFAULT_STRATEGY
import platform.zlib.Z_DEFLATED
import platform.zlib.Z_FINISH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.deflate
import platform.zlib.deflateEnd
import platform.zlib.deflateInit2
import platform.zlib.z_stream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

private const val PATH_BUF_SIZE = 4096
private const val GZIP_WINDOW_BITS = 31
private const val DEFAULT_MEM_LEVEL = 8
private const val TEST_IMAGE_BYTES = 131_584
private const val TEST_TARGET_BYTES = 262_144L
private const val CHUNK_ALIGN_512 = 512

class LinuxFlashIntegrationTest {
    @Test
    fun decompressesConcatenatedGzipStreamWritesToPosixTargetAndVerifiesSha256() {
        val root = createTempRoot()
        try {
            val partA = ByteArray(TEST_IMAGE_BYTES / 2) { i -> ((i * 17 + 3) and 0xFF).toByte() }
            val partB = ByteArray(TEST_IMAGE_BYTES / 2) { i -> ((i * 29 + 11) and 0xFF).toByte() }
            val expectedBytes = partA + partB
            val gzBytes = gzipCompress(partA) + gzipCompress(partB)

            val gzPath = "$root/live.img.gz"
            val targetPath = "$root/virtual-usb.bin"
            writeFileBytes(gzPath, gzBytes)
            createSizedFile(targetPath, TEST_TARGET_BYTES)

            val info = inspectPlatformImage(gzPath).getOrThrow()
            assertEquals(ImageFormat.COMPRESSED_IMAGE, info.format)
            assertEquals(CompressionFormat.GZIP, info.compression)

            val events = mutableListOf<WorkerEvent>()
            val source = openFlashImageSource(gzPath, info)
            val target = PosixTempFileFlashTarget(targetPath, sectorSizeBytes = CHUNK_ALIGN_512, capacityBytes = TEST_TARGET_BYTES)

            val completed =
                FlashEngine.execute(
                    source = source,
                    target = target,
                    verifyAfterWrite = true,
                    chunkSizeBytes = 16_384,
                    progressIntervalMs = 0L,
                    clockMs = { 10L },
                    onEvent = events::add,
                )

            assertTrue(completed.verified)
            val actualWritten = readFileBytes(targetPath, expectedBytes.size)
            assertTrue(actualWritten.contentEquals(expectedBytes))
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun rejectsTruncatedGzipStreamDuringFlashExecution() {
        val root = createTempRoot()
        try {
            val raw = ByteArray(8192) { (it and 0xFF).toByte() }
            val validGz = gzipCompress(raw)
            val truncatedGz = validGz.copyOf(validGz.size - 8)
            val gzPath = "$root/corrupt.img.gz"
            val targetPath = "$root/virtual-usb.bin"
            writeFileBytes(gzPath, truncatedGz)
            createSizedFile(targetPath, 65_536L)

            val source =
                openFlashImageSource(
                    gzPath,
                    ImageInfo(gzPath, truncatedGz.size.toLong(), ImageFormat.COMPRESSED_IMAGE, CompressionFormat.GZIP),
                )
            val target = PosixTempFileFlashTarget(targetPath, sectorSizeBytes = CHUNK_ALIGN_512, capacityBytes = 65_536L)

            val failure =
                assertFailsWith<FlashStepException> {
                    FlashEngine.execute(
                        source = source,
                        target = target,
                        verifyAfterWrite = false,
                        chunkSizeBytes = 4096,
                        clockMs = { 0L },
                        onEvent = {},
                    )
                }
            assertEquals("CORRUPT_GZIP", failure.code)
        } finally {
            deleteRecursively(root)
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PosixTempFileFlashTarget(
    private val path: String,
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
) : BlockFlashTarget {
    private var fd = -1

    override fun unmountAndLock() {
        fd = open(path, O_RDWR)
        if (fd < 0) error("Unable to open temp target $path")
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
                if (n <= 0L) error("pwrite failed")
                written += n.toInt()
            }
        }
    }

    override fun syncAndFlush() {
        fsync(fd)
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        var total = 0
        buffer.usePinned { pinned ->
            while (total < length) {
                val n = pread(fd, pinned.addressOf(total), (length - total).toULong(), offset + total)
                if (n <= 0L) break
                total += n.toInt()
            }
        }
        return total
    }

    override fun close() {
        if (fd >= 0) {
            close(fd)
            fd = -1
        }
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun gzipCompress(input: ByteArray): ByteArray =
    memScoped {
        val stream = alloc<z_stream>()
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        val initRc =
            deflateInit2(
                stream.ptr,
                Z_DEFAULT_COMPRESSION,
                Z_DEFLATED,
                GZIP_WINDOW_BITS,
                DEFAULT_MEM_LEVEL,
                Z_DEFAULT_STRATEGY,
            )
        if (initRc != Z_OK) error("deflateInit2 failed: $initRc")
        val output = ByteArray(input.size + 4096)
        try {
            input.usePinned { inPinned ->
                output.usePinned { outPinned ->
                    stream.next_in = inPinned.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_in = input.size.toUInt()
                    stream.next_out = outPinned.addressOf(0).reinterpret<UByteVar>()
                    stream.avail_out = output.size.toUInt()
                    val rc = deflate(stream.ptr, Z_FINISH)
                    if (rc != Z_STREAM_END) error("deflate did not finish cleanly: $rc")
                }
            }
            val produced = output.size - stream.avail_out.toInt()
            output.copyOf(produced)
        } finally {
            deflateEnd(stream.ptr)
        }
    }

@OptIn(ExperimentalForeignApi::class)
private fun createTempRoot(): String =
    memScoped {
        val template = "/tmp/kufus-flash-XXXXXX".cstr.ptr
        val created = mkdtemp(template)?.toKString() ?: error("mkdtemp failed")
        val buf = allocArray<ByteVar>(PATH_BUF_SIZE)
        realpath(created, buf)?.toKString() ?: created
    }

@OptIn(ExperimentalForeignApi::class)
private fun createSizedFile(
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
private fun writeFileBytes(
    path: String,
    bytes: ByteArray,
) {
    val file = fopen(path, "wb") ?: error("fopen failed for $path")
    try {
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            if (written != bytes.size.toULong()) error("fwrite failed")
        }
    } finally {
        fclose(file)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun readFileBytes(
    path: String,
    count: Int,
): ByteArray {
    val fd = open(path, O_RDONLY)
    if (fd < 0) error("open failed for $path")
    return try {
        val buf = ByteArray(count)
        var total = 0
        buf.usePinned { pinned ->
            while (total < count) {
                val n = read(fd, pinned.addressOf(total), (count - total).toULong())
                if (n <= 0L) break
                total += n.toInt()
            }
        }
        if (total == count) buf else buf.copyOf(total)
    } finally {
        close(fd)
    }
}

@OptIn(ExperimentalForeignApi::class)
private fun deleteRecursively(path: String) {
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
    children.forEach { child -> deleteRecursively("$path/$child") }
    rmdir(path)
}
