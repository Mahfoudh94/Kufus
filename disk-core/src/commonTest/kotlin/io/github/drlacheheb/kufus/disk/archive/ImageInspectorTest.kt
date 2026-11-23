package io.github.drlacheheb.kufus.disk.archive

import io.github.drlacheheb.kufus.disk.io.SeekableByteReader
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ImageInspectorTest {
    @Test
    fun detectsHybridIsoWithElToritoOrMbrSignature() {
        val image = ByteArray(40 * 2048)
        // MBR 0x55AA signature
        image[510] = 0x55
        image[511] = 0xAA.toByte()
        // ISO 9660 Primary Volume Descriptor at sector 16 (32768)
        writeIsoPvd(image, rootExtentLba = 20L, rootDataLength = 2048L)

        val info = inspectImage("/tmp/ubuntu.iso", ByteArraySeekableReader(image))

        info shouldBe
            ImageInfo(
                path = "/tmp/ubuntu.iso",
                sizeBytes = image.size.toLong(),
                format = ImageFormat.HYBRID_ISO,
                compression = CompressionFormat.NONE,
                uncompressedSizeHintBytes = null,
            )
    }

    @Test
    fun detectsWindowsIsoContainingSourcesInstallWim() {
        val image = ByteArray(30 * 2048)
        writeIsoPvd(image, rootExtentLba = 20L, rootDataLength = 2048L)
        writeIsoDirectoryRecord(
            image = image,
            offset = 20 * 2048,
            name = "SOURCES",
            extentLba = 21L,
            dataLength = 2048L,
            isDirectory = true,
        )
        writeIsoDirectoryRecord(
            image = image,
            offset = 21 * 2048,
            name = "INSTALL.ESD;1",
            extentLba = 22L,
            dataLength = 4096L,
            isDirectory = false,
        )

        val info = inspectImage("/tmp/win11.iso", ByteArraySeekableReader(image))

        info.format shouldBe ImageFormat.WINDOWS_ISO
        info.compression shouldBe CompressionFormat.NONE
    }

    @Test
    fun detectsRawMbrAndGptDiskImages() {
        val mbrOnly = ByteArray(4096)
        mbrOnly[510] = 0x55
        mbrOnly[511] = 0xAA.toByte()
        inspectImage("/tmp/disk.img", ByteArraySeekableReader(mbrOnly)).format shouldBe ImageFormat.RAW_IMAGE

        val gptOnly = ByteArray(4096)
        "EFI PART".encodeToByteArray().copyInto(gptOnly, destinationOffset = 512)
        inspectImage("/tmp/gpt.raw", ByteArraySeekableReader(gptOnly)).format shouldBe ImageFormat.RAW_IMAGE
    }

    @Test
    fun detectsGzipXzAndZstdCompressedImagesWithHints() {
        val gzipBytes = ByteArray(64)
        gzipBytes[0] = 0x1F
        gzipBytes[1] = 0x8B.toByte()
        gzipBytes[2] = 0x08
        // Trailer ISIZE = 128 (little-endian)
        gzipBytes[gzipBytes.size - 4] = 128.toByte()
        val gzipInfo = inspectImage("/tmp/os.img.gz", ByteArraySeekableReader(gzipBytes))
        gzipInfo.format shouldBe ImageFormat.COMPRESSED_IMAGE
        gzipInfo.compression shouldBe CompressionFormat.GZIP
        gzipInfo.uncompressedSizeHintBytes shouldBe 128L

        val xzBytes = byteArrayOf(0xFD.toByte(), 0x37, 0x7A, 0x58, 0x5A, 0x00, 0x00, 0x01)
        val xzInfo = inspectImage("/tmp/os.iso.xz", ByteArraySeekableReader(xzBytes))
        xzInfo.format shouldBe ImageFormat.COMPRESSED_IMAGE
        xzInfo.compression shouldBe CompressionFormat.XZ
        xzInfo.uncompressedSizeHintBytes shouldBe null

        // ZSTD with Single_Segment_Flag (0x20) + FCS_Flag = 1 (0x40 => 2-byte FCS + 256)
        val zstdBytes = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte(), 0x60, 0x04, 0x01, 0x00, 0x00)
        val zstdInfo = inspectImage("/tmp/os.img.zst", ByteArraySeekableReader(zstdBytes))
        zstdInfo.format shouldBe ImageFormat.COMPRESSED_IMAGE
        zstdInfo.compression shouldBe CompressionFormat.ZSTD
        zstdInfo.uncompressedSizeHintBytes shouldBe (0x0104L + 256L)
    }

    @Test
    fun extractsExactUncompressedSizeFromXzIndexAcrossSingleAndConcatenatedStreams() {
        val xzStream1 =
            decodeHexBytes(
                "fd377a585a000004e6d6b4460200210116000000742fe5a3e013eb00305d00259544cfe630c8fd88bebc67ea7ac37e" +
                    "0799c738b8ce6e17b5a15c112a2015c5f4f1fca1b917a7db2756df2443cd550000986997a6438576fd00014cec27" +
                    "00000093a30e6bb1c467fb020000000004595a",
            )
        val xzStream2 =
            decodeHexBytes(
                "fd377a585a000004e6d6b4460200210116000000742fe5a3e002bb00185d00299144705429fd2b0301765b9cce4090cb" +
                    "eac4a5bd50640000ead8b80156099702000134bc05000000418457beb1c467fb020000000004595a",
            )

        val singleInfo = inspectImage("/tmp/one.img.xz", ByteArraySeekableReader(xzStream1))
        singleInfo.format shouldBe ImageFormat.COMPRESSED_IMAGE
        singleInfo.compression shouldBe CompressionFormat.XZ
        singleInfo.uncompressedSizeHintBytes shouldBe 5100L

        val concatenated = xzStream1 + ByteArray(4) + xzStream2 + ByteArray(4)
        val multiInfo = inspectImage("/tmp/multi.img.xz", ByteArraySeekableReader(concatenated))
        multiInfo.uncompressedSizeHintBytes shouldBe 5800L

        val corruptedFooter = xzStream1.copyOf().apply { this[size - 5] = (this[size - 5].toInt() xor 0xFF).toByte() }
        inspectImage("/tmp/corrupt.img.xz", ByteArraySeekableReader(corruptedFooter)).uncompressedSizeHintBytes shouldBe null
    }

    @Test
    fun returnsUnknownForEmptyOrUnrecognizedData() {
        inspectImage("/tmp/empty.iso", ByteArraySeekableReader(ByteArray(0))).format shouldBe ImageFormat.UNKNOWN
        inspectImage("/tmp/random.bin", ByteArraySeekableReader("hello world".encodeToByteArray())).format shouldBe
            ImageFormat.UNKNOWN
    }

    private fun writeIsoPvd(
        image: ByteArray,
        rootExtentLba: Long,
        rootDataLength: Long,
    ) {
        val pvdOffset = 16 * 2048
        image[pvdOffset] = 1
        "CD001".encodeToByteArray().copyInto(image, destinationOffset = pvdOffset + 1)
        writeUInt32Le(image, pvdOffset + 156 + 2, rootExtentLba)
        writeUInt32Le(image, pvdOffset + 156 + 10, rootDataLength)
    }

    private fun writeIsoDirectoryRecord(
        image: ByteArray,
        offset: Int,
        name: String,
        extentLba: Long,
        dataLength: Long,
        isDirectory: Boolean,
    ) {
        val nameBytes = name.encodeToByteArray()
        val recordLen = 33 + nameBytes.size
        image[offset] = recordLen.toByte()
        writeUInt32Le(image, offset + 2, extentLba)
        writeUInt32Le(image, offset + 10, dataLength)
        image[offset + 25] = if (isDirectory) 0x02 else 0x00
        image[offset + 32] = nameBytes.size.toByte()
        nameBytes.copyInto(image, destinationOffset = offset + 33)
    }

    private fun writeUInt32Le(
        target: ByteArray,
        offset: Int,
        value: Long,
    ) {
        target[offset] = (value and 0xFFL).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFFL).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFFL).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFFL).toByte()
    }

    private fun decodeHexBytes(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }
}

private class ByteArraySeekableReader(
    private val bytes: ByteArray,
) : SeekableByteReader {
    override fun sizeBytes(): Long = bytes.size.toLong()

    override fun readAt(
        offset: Long,
        length: Int,
    ): ByteArray {
        if (offset < 0L || offset >= bytes.size.toLong() || length <= 0) return ByteArray(0)
        val start = offset.toInt()
        val end = minOf(bytes.size, start + length)
        return bytes.copyOfRange(start, end)
    }
}
