package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Ext4FormatterTest {
    @Test
    fun writesValidSuperblockGroupDescriptorsInodesAndChecksums() {
        val diskBytes = 48L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val target = Ext4ArrayBlockTarget(image, sectorSizeBytes = 512)
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)

        Ext4Formatter.format(
            target = target,
            span = span,
            volumeLabel = "LINUX_USB",
        )

        val partStart = span.startByteOffset.toInt()
        val sbOff = partStart + 1024
        readUInt16LeInt(image, sbOff + 0x38) shouldBe 0xEF53
        image.decodeToString(sbOff + 0x78, sbOff + 0x78 + 9) shouldBe "LINUX_USB"
        val sbStoredCsum = readUInt32LeLong(image, sbOff + 0x3FC).toInt()
        val sbCalcCsum = Crc32c.computeRaw(-1, image, sbOff, 0x3FC)
        sbStoredCsum shouldBe sbCalcCsum

        val geom = Ext4Formatter.computeGeometry(span)
        geom.groups.size shouldBe 1
        // Verify root directory '.' and '..' entries
        val rootOff = partStart + (geom.rootDirBlock * 4096L).toInt()
        readUInt32LeLong(image, rootOff) shouldBe 2L
        image[rootOff + 8] shouldBe '.'.code.toByte()
        image[rootOff + 12 + 8] shouldBe '.'.code.toByte()
        image[rootOff + 12 + 9] shouldBe '.'.code.toByte()
        readUInt32LeLong(image, rootOff + 24) shouldBe 11L
        image.decodeToString(rootOff + 32, rootOff + 42) shouldBe "lost+found"
    }

    @Test
    fun dropsUnusableTinyTrailingPartialBlockGroup() {
        val sectorsPerBlock = 4096L / 512L
        val tinyGroup1Sectors = (32_768L + 2L) * sectorsPerBlock
        val span1 = PartitionSpan(sectorSizeBytes = 512, totalSectors = tinyGroup1Sectors + 2048L, startLba = 2048L, lastLba = 2048L + tinyGroup1Sectors - 1L)
        val geom1 = Ext4Formatter.computeGeometry(span1)
        geom1.groups.size shouldBe 1
        geom1.groups.all { it.freeBlocksCount >= 0L } shouldBe true

        val tinyFlexTailSectors = (16L * 32_768L + 100L) * sectorsPerBlock
        val span16 =
            PartitionSpan(
                sectorSizeBytes = 512,
                totalSectors = tinyFlexTailSectors + 2048L,
                startLba = 2048L,
                lastLba = 2048L + tinyFlexTailSectors - 1L,
            )
        val geom16 = Ext4Formatter.computeGeometry(span16)
        geom16.groups.size shouldBe 16
        geom16.groups.all { it.freeBlocksCount >= 0L } shouldBe true
    }
}

private class Ext4ArrayBlockTarget(
    private val backing: ByteArray,
    override val sectorSizeBytes: Int,
) : BlockWriter {
    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        buffer.copyInto(backing, destinationOffset = offset.toInt(), startIndex = 0, endIndex = length)
    }
}
