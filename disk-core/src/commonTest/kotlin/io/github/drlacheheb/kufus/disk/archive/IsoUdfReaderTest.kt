package io.github.drlacheheb.kufus.disk.archive

import io.github.drlacheheb.kufus.disk.io.SeekableByteReader
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class IsoUdfReaderTest {
    @Test
    fun parsesUdfDirectoryTreeAndStreamsFileBytesWithoutExternalTools() {
        val wimBytes = "MSWIM\u0000\u0000\u0000test-wim-payload".encodeToByteArray()
        val efiBytes = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)
        val image =
            buildSyntheticUdfWindowsIso(
                volumeLabel = "WIN11_X64",
                wimPayload = wimBytes,
                efiPayload = efiBytes,
            )

        val reader = TestByteReader(image)
        val archive = requireNotNull(IsoUdfReader.open(reader))
        archive.isUdf shouldBe true
        archive.volumeLabel shouldBe "WIN11_X64"

        val paths = archive.entries.map { it.relativePath }
        paths shouldContain "efi/boot/bootx64.efi"
        paths shouldContain "sources/install.wim"

        val wimEntry = archive.entries.first { it.relativePath == "sources/install.wim" }
        wimEntry.sizeBytes shouldBe wimBytes.size.toLong()

        val collected = ByteArray(wimBytes.size)
        var cursor = 0
        IsoUdfReader.streamFile(reader, wimEntry, ByteArray(8)) { chunk, len ->
            chunk.copyInto(collected, destinationOffset = cursor, endIndex = len)
            cursor += len
        }
        collected.decodeToString() shouldBe wimBytes.decodeToString()
    }

    @Test
    fun detectsUdfWindowsMetadataInsideImageInspector() {
        val wimBytes = ByteArray(256)
        "MSWIM\u0000\u0000\u0000".encodeToByteArray().copyInto(wimBytes, destinationOffset = 0)
        val image =
            buildSyntheticUdfWindowsIso(
                volumeLabel = "CCCOMA_X64",
                wimPayload = wimBytes,
                efiPayload = byteArrayOf(1, 2, 3, 4),
            )
        val info = inspectImage("/tmp/win11.iso", TestByteReader(image))

        info.format shouldBe ImageFormat.WINDOWS_ISO
        val meta = requireNotNull(info.windowsMetadata)
        meta.volumeLabel shouldBe "CCCOMA_X64"
        meta.installImagePath shouldBe "sources/install.wim"
        meta.installImageSizeBytes shouldBe wimBytes.size.toLong()
    }

    private fun buildSyntheticUdfWindowsIso(
        volumeLabel: String,
        wimPayload: ByteArray,
        efiPayload: ByteArray,
    ): ByteArray {
        val sectorSize = 2048
        val totalSectors = 320
        val img = ByteArray(totalSectors * sectorSize)
        val partStart = 280L

        // Sector 256: Anchor Volume Descriptor Pointer (Tag 2) -> VDS at sector 32
        writeUdfTag(img, 256 * sectorSize, 2)
        writeLe32(img, 256 * sectorSize + 16, (4 * sectorSize).toLong())
        writeLe32(img, 256 * sectorSize + 20, 32L)

        // Sector 32: Partition Descriptor (Tag 5), partNum=0, start=280, len=40
        writeUdfTag(img, 32 * sectorSize, 5)
        writeLe16(img, 32 * sectorSize + 22, 0)
        writeLe32(img, 32 * sectorSize + 188, partStart)
        writeLe32(img, 32 * sectorSize + 192, 40L)

        // Sector 33: Logical Volume Descriptor (Tag 6), label, blockSize=2048, FSD at rel block 0
        writeUdfTag(img, 33 * sectorSize, 6)
        writeUdfDString(img, 33 * sectorSize + 84, volumeLabel)
        writeLe32(img, 33 * sectorSize + 212, sectorSize.toLong())
        writeUdfLongAd(img, 33 * sectorSize + 248, sectorSize.toLong(), 0L, 0)

        // Sector 34: Terminating Descriptor (Tag 8)
        writeUdfTag(img, 34 * sectorSize, 8)

        // Partition block 0 (abs 280): File Set Descriptor (Tag 256), root ICB at block 1
        writeUdfTag(img, (partStart + 0).toInt() * sectorSize, 256)
        writeUdfLongAd(img, (partStart + 0).toInt() * sectorSize + 400, sectorSize.toLong(), 1L, 0)

        // Blocks 1/2: root dir (fid: efi -> blk 3, sources -> blk 5)
        writeUdfDirWithChildren(img, partStart, 1L, 2L, listOf("efi" to (3L to true), "sources" to (5L to true)))
        // Blocks 3/4: efi dir (fid: boot -> blk 7)
        writeUdfDirWithChildren(img, partStart, 3L, 4L, listOf("boot" to (7L to true)))
        // Blocks 7/8: efi/boot dir (fid: bootx64.efi -> blk 9)
        writeUdfDirWithChildren(img, partStart, 7L, 8L, listOf("bootx64.efi" to (9L to false)))
        // Blocks 5/6: sources dir (fid: install.wim -> blk 11)
        writeUdfDirWithChildren(img, partStart, 5L, 6L, listOf("install.wim" to (11L to false)))

        // File bootx64.efi: FE at block 9, payload at block 10
        writeUdfFileFe(img, partStart, 9L, 10L, efiPayload)
        // File install.wim: FE at block 11, payload at block 12
        writeUdfFileFe(img, partStart, 11L, 12L, wimPayload)

        return img
    }

    private fun writeUdfDirWithChildren(
        img: ByteArray,
        partStart: Long,
        feBlock: Long,
        dataBlock: Long,
        children: List<Pair<String, Pair<Long, Boolean>>>,
    ) {
        val sectorSize = 2048
        val dataOffset = (partStart + dataBlock).toInt() * sectorSize
        var pos = 0
        for ((name, target) in children) {
            val (childIcbBlock, isDir) = target
            val nameBytes = name.encodeToByteArray()
            val fiLen = 1 + nameBytes.size
            val recLen = ((38 + fiLen + 3) / 4) * 4
            writeUdfTag(img, dataOffset + pos, 257)
            img[dataOffset + pos + 18] = if (isDir) 0x02 else 0x00
            img[dataOffset + pos + 19] = fiLen.toByte()
            writeUdfLongAd(img, dataOffset + pos + 20, sectorSize.toLong(), childIcbBlock, 0)
            writeLe16(img, dataOffset + pos + 36, 0)
            img[dataOffset + pos + 38] = 8
            nameBytes.copyInto(img, destinationOffset = dataOffset + pos + 39)
            pos += recLen
        }
        val feOffset = (partStart + feBlock).toInt() * sectorSize
        writeUdfTag(img, feOffset, 261)
        writeLe16(img, feOffset + 34, 0) // short_ad
        writeLe64(img, feOffset + 56, pos.toLong())
        writeLe32(img, feOffset + 168, 0L)
        writeLe32(img, feOffset + 172, 8L)
        writeLe32(img, feOffset + 176, pos.toLong())
        writeLe32(img, feOffset + 180, dataBlock)
    }

    private fun writeUdfFileFe(
        img: ByteArray,
        partStart: Long,
        feBlock: Long,
        dataBlock: Long,
        payload: ByteArray,
    ) {
        val sectorSize = 2048
        payload.copyInto(img, destinationOffset = (partStart + dataBlock).toInt() * sectorSize)
        val feOffset = (partStart + feBlock).toInt() * sectorSize
        writeUdfTag(img, feOffset, 261)
        writeLe16(img, feOffset + 34, 0)
        writeLe64(img, feOffset + 56, payload.size.toLong())
        writeLe32(img, feOffset + 168, 0L)
        writeLe32(img, feOffset + 172, 8L)
        writeLe32(img, feOffset + 176, payload.size.toLong())
        writeLe32(img, feOffset + 180, dataBlock)
    }

    private fun writeUdfTag(
        img: ByteArray,
        offset: Int,
        tagId: Int,
    ) = writeLe16(img, offset, tagId)

    private fun writeUdfLongAd(
        img: ByteArray,
        offset: Int,
        lenBytes: Long,
        block: Long,
        partRef: Int,
    ) {
        writeLe32(img, offset, lenBytes)
        writeLe32(img, offset + 4, block)
        writeLe16(img, offset + 8, partRef)
    }

    private fun writeUdfDString(
        img: ByteArray,
        offset: Int,
        text: String,
    ) {
        img[offset] = 8
        text.encodeToByteArray().copyInto(img, destinationOffset = offset + 1)
    }

    @Test
    fun parsesMultiSectorUdfDirectoryWithSectorZeroPaddingAndFixedDStringLabel() {
        val sectorSize = 2048
        val partStart = 280L
        val label = "WIN11_LTSC_26100"
        val (img, wimBytes) = buildMultiSectorUdfBase(sectorSize, partStart, label)

        // Write 32 dummy FIDs filling sector 4 -> remaining bytes in sector 4 are 0x00,
        // then place install.wim FID at start of sector 5 (block 5).
        val dirSec0 = (partStart + 4L).toInt() * sectorSize
        var pos = 0
        for (i in 0 until 32) {
            pos += writeSingleUdfFid(img, dirSec0 + pos, "file_${i.toString().padStart(2, '0')}.dll", 11L, false)
        }
        val dirSec1 = (partStart + 5L).toInt() * sectorSize
        val sec1Bytes = writeSingleUdfFid(img, dirSec1, "install.wim", 10L, false)
        writeUdfRawDirFe(img, partStart, 3L, 4L, (sectorSize + sec1Bytes).toLong())

        val info = inspectImage("/tmp/26100.iso", TestByteReader(img))
        info.format shouldBe ImageFormat.WINDOWS_ISO
        val meta = requireNotNull(info.windowsMetadata)
        meta.volumeLabel shouldBe label
        meta.installImagePath shouldBe "sources/install.wim"
        meta.installImageSizeBytes shouldBe wimBytes.size.toLong()
    }

    @Test
    fun parsesMultiSectorUdfDirectoryWhenFidStraddlesBlockBoundary() {
        val sectorSize = 2048
        val partStart = 280L
        val label = "WIN11_LTSC_26100"
        val (img, wimBytes) = buildMultiSectorUdfBase(sectorSize, partStart, label)

        // Pack FIDs cleanly across the 2048-byte sector boundary so one FID starts at offset 2020 (28 bytes before boundary)
        // matching Microsoft oscdimg UDF directory layout.
        val dirBase = (partStart + 4L).toInt() * sectorSize
        var pos = 0
        for (i in 0 until 39) {
            pos += writeSingleUdfFid(img, dirBase + pos, "file_${i.toString().padStart(2, '0')}.dll", 11L, false)
        }
        // Next FID begins at pos < 2048 with < 38 bytes remaining in sector 0 and crosses 2048
        val remAtBoundary = sectorSize - pos
        (remAtBoundary in 1 until 38) shouldBe true
        pos += writeSingleUdfFid(img, dirBase + pos, "dlmanifests", 11L, false)
        pos += writeSingleUdfFid(img, dirBase + pos, "install.wim", 10L, false)
        writeUdfRawDirFe(img, partStart, 3L, 4L, pos.toLong())

        val info = inspectImage("/tmp/26100-straddle.iso", TestByteReader(img))
        info.format shouldBe ImageFormat.WINDOWS_ISO
        val meta = requireNotNull(info.windowsMetadata)
        meta.installImagePath shouldBe "sources/install.wim"
        meta.installImageSizeBytes shouldBe wimBytes.size.toLong()
    }

    private fun buildMultiSectorUdfBase(
        sectorSize: Int,
        partStart: Long,
        label: String,
    ): Pair<ByteArray, ByteArray> {
        val img = ByteArray(450 * sectorSize)
        writeUdfTag(img, 256 * sectorSize, 2)
        writeLe32(img, 256 * sectorSize + 16, (4 * sectorSize).toLong())
        writeLe32(img, 256 * sectorSize + 20, 32L)
        writeUdfTag(img, 32 * sectorSize, 5)
        writeLe16(img, 32 * sectorSize + 22, 0)
        writeLe32(img, 32 * sectorSize + 188, partStart)
        writeLe32(img, 32 * sectorSize + 192, 160L)
        writeUdfTag(img, 33 * sectorSize, 6)
        writeUdfDString(img, 33 * sectorSize + 84, label)
        img[33 * sectorSize + 84 + 127] = (label.length + 1).toByte()
        writeLe32(img, 33 * sectorSize + 212, sectorSize.toLong())
        writeUdfLongAd(img, 33 * sectorSize + 248, sectorSize.toLong(), 0L, 0)
        writeUdfTag(img, 34 * sectorSize, 8)
        writeUdfTag(img, partStart.toInt() * sectorSize, 256)
        writeUdfLongAd(img, partStart.toInt() * sectorSize + 400, sectorSize.toLong(), 1L, 0)

        writeUdfDirWithChildren(img, partStart, 1L, 2L, listOf("sources" to (3L to true)))
        val wimBytes = "MSWIM\u0000\u0000\u0000win11-ltsc".encodeToByteArray()
        writeUdfFileFe(img, partStart, 10L, 12L, wimBytes)
        writeUdfFileFe(img, partStart, 11L, 13L, byteArrayOf(1, 2, 3))
        return img to wimBytes
    }

    private fun writeSingleUdfFid(
        img: ByteArray,
        offset: Int,
        name: String,
        childIcbBlock: Long,
        isDir: Boolean,
    ): Int {
        val nameBytes = name.encodeToByteArray()
        val fiLen = 1 + nameBytes.size
        val recLen = ((38 + fiLen + 3) / 4) * 4
        writeUdfTag(img, offset, 257)
        img[offset + 18] = if (isDir) 0x02 else 0x00
        img[offset + 19] = fiLen.toByte()
        writeUdfLongAd(img, offset + 20, 2048L, childIcbBlock, 0)
        writeLe16(img, offset + 36, 0)
        img[offset + 38] = 8
        nameBytes.copyInto(img, destinationOffset = offset + 39)
        return recLen
    }

    private fun writeUdfRawDirFe(
        img: ByteArray,
        partStart: Long,
        feBlock: Long,
        dataStartBlock: Long,
        totalInfoBytes: Long,
    ) {
        val feOffset = (partStart + feBlock).toInt() * 2048
        writeUdfTag(img, feOffset, 261)
        writeLe16(img, feOffset + 34, 0)
        writeLe64(img, feOffset + 56, totalInfoBytes)
        writeLe32(img, feOffset + 168, 0L)
        writeLe32(img, feOffset + 172, 8L)
        writeLe32(img, feOffset + 176, totalInfoBytes)
        writeLe32(img, feOffset + 180, dataStartBlock)
    }

    private fun writeLe16(
        img: ByteArray,
        offset: Int,
        value: Int,
    ) {
        img[offset] = (value and 0xFF).toByte()
        img[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeLe32(
        img: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 4) img[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
    }

    private fun writeLe64(
        img: ByteArray,
        offset: Int,
        value: Long,
    ) {
        for (i in 0 until 8) img[offset + i] = ((value ushr (8 * i)) and 0xFFL).toByte()
    }
}

internal class TestByteReader(
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
