package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.flash.FlashEngine
import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.disk.platform.ejectLinuxBlockDevice
import io.github.drlacheheb.kufus.disk.platform.flushAndDropLinuxFdCache
import io.github.drlacheheb.kufus.disk.platform.inspectPlatformImage
import io.github.drlacheheb.kufus.disk.platform.openFlashImageSource
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
import platform.posix.mkdir
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
private const val DIR_MODE = 0x1EDu
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

    @Test
    fun decompressesRealXzAndZstdImagesWritesToPosixTargetAndVerifiesSha256() {
        val root = createTempRoot()
        try {
            val expectedBytes =
                ByteArray(8192) { index -> ((index * 13 + (index ushr 6)) and 0xFF).toByte() }.apply {
                    this[510] = 0x55
                    this[511] = 0xAA.toByte()
                }
            val xzHex =
                "fd377a585a000004e6d6b44604c0fa028040210116000000000000006d8d3edce01fff01725d000003b803b2" +
                    "0dd7bfb2816d373500a4fce2a70daf2110f15c10937081268073391d516c4c31e33030210b6da0cb7a56aba3" +
                    "d1b25beff1a7599002af39e10619fd72513682ee423ed9b7c1ea0315f4274906dc72e418f90136504226a9a0" +
                    "041a2cd2742b9ffd540e9991b63c35e960aefd4fbc97b5835ab15b10ffedf8360c526da934b4a8ccb1e8bfca" +
                    "2562ebfdccdc563f41ff37715d46ff4b8dd390e3c162ac40632a7e3a3215fb7aa88dcf58e665fb05158b6483" +
                    "6c81bc8c4cc6dd4ccfdc5ba9555b0b86cc3500614e601528b019fe73cfb16c12090b67d02f3fa07cafc1444d" +
                    "42076cbdce7b8b4325b59ee17d8242674b1bb667b29b672c1036abf468072da40a996f03fe601919c689c278" +
                    "6545d7ee768cdf6ff329e8fcb389be41fb053925904d58d20defc5bae37f8c0f37ac409db988774e413f82fb" +
                    "04d1a17e46e09e9273397c8e6586f6aa3ccbfc68b0f8304507da0cb4718b703f344c00d3647de32a9f64e063" +
                    "8ad3e4ba41cb6a2dcdc4edd023000000d52925b9a7b1b8370001960380400000a86009d2b1c467fb02000000" +
                    "0004595a"
            val zstHex =
                "28b52ffd04584d0c00a410000d1a2734414e5b6875828f9ca9b6c3d0ddeaf704111e2b3845525f6c798693a0" +
                    "adbac7d4e1eefb0815222f3c495663707d8a97a4b1becbd8e5f2ff0c192633404d5a6774818e9ba8b5c2cfdc" +
                    "e9f603101d2a3744515e6b7885929facb9c6d3e055aaedfa0714212e3b4855626f7c8996a3b0bdcad7e4f1fe" +
                    "0b1825323f4c596673808d9aa7b4c1cedbe8f5020f1c293643505d6a7784919eabb8c5d2dfecf90613202d3a" +
                    "4754616e7b8895a2afbcc9d6e3f0fd0a1724313e4b5865727f8c99a6b3c0cddae7f4010e1b2835424f5c6976" +
                    "83909daab7c4d1deebf805121f2c394653606d7a8794a1aebbc8d5e2effc091623303d4a5764717e8b98a5b2" +
                    "bfccd9e6f3000d1a3e4b58657280d6a841c774d8ff0df035760312a816fcffff097f080718846210c7310ca3" +
                    "708403180c7110c71186a3708441180c3188c71186d17084010c0d3188e31186513886010c863188e3088751" +
                    "38c2010c86188ae308f11d010fa27110190ec178108ca3687888c6a3301c05c321361e85f14452361a0ec1e8" +
                    "0024005e22abc5d770a1"

            data class CompressedFixture(
                val filename: String,
                val compressedBytes: ByteArray,
                val expectedCompression: CompressionFormat,
                val expectedSizeHintBytes: Long?,
            )

            listOf(
                CompressedFixture("mbr.img.xz", decodeHexFixture(xzHex), CompressionFormat.XZ, 8192L),
                CompressedFixture("mbr.img.zst", decodeHexFixture(zstHex), CompressionFormat.ZSTD, null),
            ).forEach { fixture ->
                val imgPath = "$root/${fixture.filename}"
                val targetPath = "$root/${fixture.filename}.target.bin"
                writeFileBytes(imgPath, fixture.compressedBytes)
                createSizedFile(targetPath, 65_536L)

                val info = inspectPlatformImage(imgPath).getOrThrow()
                assertEquals(ImageFormat.COMPRESSED_IMAGE, info.format)
                assertEquals(fixture.expectedCompression, info.compression)
                assertEquals(fixture.expectedSizeHintBytes, info.uncompressedSizeHintBytes)

                val events = mutableListOf<WorkerEvent>()
                val source = openFlashImageSource(imgPath, info)
                val target = PosixTempFileFlashTarget(targetPath, sectorSizeBytes = CHUNK_ALIGN_512, capacityBytes = 65_536L)
                val completed =
                    FlashEngine.execute(
                        source = source,
                        target = target,
                        verifyAfterWrite = true,
                        chunkSizeBytes = 4096,
                        progressIntervalMs = 0L,
                        clockMs = { 5L },
                        onEvent = events::add,
                    )
                assertTrue(completed.verified)
                assertTrue(readFileBytes(targetPath, expectedBytes.size).contentEquals(expectedBytes))
                assertTrue(events.any { it is WorkerEvent.Started && it.totalBytes == (fixture.expectedSizeHintBytes ?: 0L) })
            }
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun ejectsBlockDeviceViaSysfsDeleteAndRejectsTraversalPaths() {
        val root = createTempRoot()
        try {
            val sysBlock = "$root/sys/block"
            val deviceDir = "$sysBlock/sdb/device"
            mkdir("$root/sys", DIR_MODE)
            mkdir(sysBlock, DIR_MODE)
            mkdir("$sysBlock/sdb", DIR_MODE)
            mkdir(deviceDir, DIR_MODE)
            val deleteFile = "$deviceDir/delete"
            createSizedFile(deleteFile, 0L)

            val ok = ejectLinuxBlockDevice("/dev/sdb", sysBlockRoot = sysBlock)
            assertTrue(ok.isSuccess)
            assertEquals("1\n", readFileBytes(deleteFile, 4).decodeToString())

            val bad = ejectLinuxBlockDevice("/dev/sdb/../sda", sysBlockRoot = sysBlock)
            assertTrue(bad.isFailure)
        } finally {
            deleteRecursively(root)
        }
    }

    @OptIn(ExperimentalForeignApi::class)
    @Test
    fun flushesAndDropsCacheOnRegularPosixFilesWithoutError() {
        val root = createTempRoot()
        try {
            val path = "$root/sync-window.bin"
            val payload = ByteArray(262_144) { idx -> (idx and 0xFF).toByte() }
            writeFileBytes(path, payload)
            val fd = open(path, O_RDWR)
            assertTrue(fd >= 0)
            try {
                flushAndDropLinuxFdCache(fd, "SYNC_FAILED", path, fullSync = false)
                flushAndDropLinuxFdCache(fd, "SYNC_FAILED", path, fullSync = true)
            } finally {
                close(fd)
            }
            assertTrue(readFileBytes(path, payload.size).contentEquals(payload))
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

private fun decodeHexFixture(hex: String): ByteArray =
    ByteArray(hex.length / 2) { index ->
        hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
    }
