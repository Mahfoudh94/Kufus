package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.inspectPlatformImage
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.pointed
import kotlinx.cinterop.toKString
import kotlinx.cinterop.usePinned
import platform.posix.closedir
import platform.posix.fclose
import platform.posix.fopen
import platform.posix.fwrite
import platform.posix.mkdtemp
import platform.posix.opendir
import platform.posix.readdir
import platform.posix.realpath
import platform.posix.rmdir
import platform.posix.unlink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val PATH_BUFFER_SIZE = 4096
private const val SECTOR_512 = 512
private const val ISO_SECTOR_BYTES = 2048
private const val PVD_LBA = 16
private const val BRVD_LBA = 17
private const val BYTE_MASK = 0xFF
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val MBR_SIG_0 = 0x55
private const val MBR_SIG_1 = 0xAA
private const val MBR_OFFSET = 510
private const val GPT_IMAGE_BYTES = 2048
private const val ISO_IMAGE_BYTES = 36_864
private const val GZIP_MAGIC_0 = 0x1F
private const val GZIP_MAGIC_1 = 0x8B
private const val GZIP_DEFLATE = 0x08
private const val GZIP_HINT_BYTES = 4_194_304L

class LinuxImageInspectorIntegrationTest {
    @Test
    fun inspectsRealGptAndHybridIsoAndGzipFilesOnLinux() {
        val root = createTempRoot()
        try {
            val gptPath = "$root/disk.img"
            writeBytes(gptPath, createGptBytes())
            val gptResult = inspectPlatformImage(gptPath).getOrThrow()
            assertEquals(ImageFormat.RAW_IMAGE, gptResult.format)
            assertEquals(CompressionFormat.NONE, gptResult.compression)
            assertEquals(GPT_IMAGE_BYTES.toLong(), gptResult.sizeBytes)

            val isoPath = "$root/boot.iso"
            writeBytes(isoPath, createBootableIsoBytes())
            val isoResult = inspectPlatformImage(isoPath).getOrThrow()
            assertEquals(ImageFormat.HYBRID_ISO, isoResult.format)
            assertEquals(CompressionFormat.NONE, isoResult.compression)
            assertEquals(ISO_IMAGE_BYTES.toLong(), isoResult.sizeBytes)

            val gzPath = "$root/image.img.gz"
            writeBytes(gzPath, createGzipBytes(GZIP_HINT_BYTES))
            val gzResult = PlatformNativeDiskDriver().inspectImage(gzPath).getOrThrow()
            assertEquals(ImageFormat.COMPRESSED_IMAGE, gzResult.format)
            assertEquals(CompressionFormat.GZIP, gzResult.compression)
            assertEquals(GZIP_HINT_BYTES, gzResult.uncompressedSizeHintBytes)
        } finally {
            deleteRecursively(root)
        }
    }

    @Test
    fun rejectsMissingFileDirectoryAndBlankPath() {
        val root = createTempRoot()
        try {
            assertTrue(inspectPlatformImage("$root/does-not-exist.iso").isFailure)
            assertTrue(inspectPlatformImage(root).isFailure)
            assertTrue(inspectPlatformImage("   ").isFailure)
        } finally {
            deleteRecursively(root)
        }
    }
}

private fun createGptBytes(): ByteArray =
    ByteArray(GPT_IMAGE_BYTES).apply {
        this[MBR_OFFSET] = MBR_SIG_0.toByte()
        this[MBR_OFFSET + 1] = MBR_SIG_1.toByte()
        "EFI PART".encodeToByteArray().copyInto(this, destinationOffset = SECTOR_512)
    }

private fun createBootableIsoBytes(): ByteArray =
    ByteArray(ISO_IMAGE_BYTES).apply {
        val pvd = PVD_LBA * ISO_SECTOR_BYTES
        this[pvd] = 0x01
        "CD001".encodeToByteArray().copyInto(this, destinationOffset = pvd + 1)
        this[pvd + 6] = 0x01

        val brvd = BRVD_LBA * ISO_SECTOR_BYTES
        this[brvd] = 0x00
        "CD001".encodeToByteArray().copyInto(this, destinationOffset = brvd + 1)
        this[brvd + 6] = 0x01
        "EL TORITO SPECIFICATION".encodeToByteArray().copyInto(this, destinationOffset = brvd + 7)
    }

private fun createGzipBytes(uncompressedBytes: Long): ByteArray =
    ByteArray(32).apply {
        this[0] = GZIP_MAGIC_0.toByte()
        this[1] = GZIP_MAGIC_1.toByte()
        this[2] = GZIP_DEFLATE.toByte()
        this[size - 4] = (uncompressedBytes and BYTE_MASK.toLong()).toByte()
        this[size - 3] = ((uncompressedBytes shr SHIFT_8) and BYTE_MASK.toLong()).toByte()
        this[size - 2] = ((uncompressedBytes shr SHIFT_16) and BYTE_MASK.toLong()).toByte()
        this[size - 1] = ((uncompressedBytes shr SHIFT_24) and BYTE_MASK.toLong()).toByte()
    }

@OptIn(ExperimentalForeignApi::class)
private fun createTempRoot(): String =
    memScoped {
        val template = "/tmp/kufus-img-XXXXXX".cstr.ptr
        val created = mkdtemp(template)?.toKString() ?: error("mkdtemp failed")
        val buf = allocArray<ByteVar>(PATH_BUFFER_SIZE)
        realpath(created, buf)?.toKString() ?: created
    }

@OptIn(ExperimentalForeignApi::class)
private fun writeBytes(
    path: String,
    bytes: ByteArray,
) {
    val file = fopen(path, "wb") ?: error("Failed to open $path for writing")
    try {
        bytes.usePinned { pinned ->
            val written = fwrite(pinned.addressOf(0), 1u, bytes.size.toULong(), file)
            if (written != bytes.size.toULong()) {
                error("Failed to write $path completely")
            }
        }
    } finally {
        fclose(file)
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
