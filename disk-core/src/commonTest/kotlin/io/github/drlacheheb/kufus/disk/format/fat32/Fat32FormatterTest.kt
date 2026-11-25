package io.github.drlacheheb.kufus.disk.format.fat32

import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.partition.PartitionTableBuilder
import io.kotest.matchers.longs.shouldBeGreaterThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Fat32FormatterTest {
    @Test
    fun computesValidGeometryForLargeOnePointFiveTibVolume() {
        val diskBytes = 1_500_000_000_000L
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        val geometry = Fat32Formatter.computeGeometry(span)

        geometry.sectorsPerCluster shouldBe 64
        geometry.dataClusterCount shouldBeGreaterThanOrEqual 65_525L
        geometry.dataClusterCount shouldBeLessThanOrEqual 0x0FFFFFF4L
    }

    @Test
    fun writesValidBootSectorFsInfoFatsAndVolumeLabelEntry() {
        val diskBytes = 64L * 1024L * 1024L
        val diskImage = ByteArray(diskBytes.toInt())
        val target = ByteArrayBlockTarget(diskImage, sectorSizeBytes = 512)
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)

        Fat32Formatter.format(
            target = target,
            span = span,
            volumeLabel = "my_drive_99",
        )

        val partStart = span.startByteOffset.toInt()
        diskImage[partStart + 510] shouldBe 0x55.toByte()
        diskImage[partStart + 511] shouldBe 0xAA.toByte()
        diskImage.decodeToString(partStart + 82, partStart + 90) shouldBe "FAT32   "
        diskImage.decodeToString(partStart + 71, partStart + 82) shouldBe "MY_DRIVE_99"

        // Verify FSInfo signature at sector 1 and backup boot sector at sector 6
        diskImage[partStart + 512] shouldBe 0x52.toByte()
        diskImage[partStart + 513] shouldBe 0x52.toByte()
        diskImage[partStart + 6 * 512 + 510] shouldBe 0x55.toByte()
        diskImage[partStart + 6 * 512 + 511] shouldBe 0xAA.toByte()

        // Verify FAT1 entries 0..2
        val geometry = Fat32Formatter.computeGeometry(span)
        val fat1Offset = partStart + geometry.reservedSectors * 512
        diskImage[fat1Offset] shouldBe 0xF8.toByte()
        diskImage[fat1Offset + 8] shouldBe 0xFF.toByte()
        diskImage[fat1Offset + 11] shouldBe 0x0F.toByte()

        // Verify root directory volume label entry at cluster 2
        val rootOffset = fat1Offset + (geometry.fatSectors32.toInt() * 2 * 512)
        diskImage.decodeToString(rootOffset, rootOffset + 11) shouldBe "MY_DRIVE_99"
        diskImage[rootOffset + 11] shouldBe 0x08.toByte()
    }

    @Test
    fun alignsDataClustersToOneMibAndUses32KibClustersOnEightGbDrive() {
        val diskBytes = 8_200_000_000L
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        val geometry = Fat32Formatter.computeGeometry(span)

        geometry.sectorsPerCluster shouldBe 64
        val dataStartBytes = span.startByteOffset + (geometry.reservedSectors.toLong() + 2L * geometry.fatSectors32) * 512L
        (dataStartBytes % 1_048_576L) shouldBe 0L
        geometry.dataClusterCount shouldBeGreaterThanOrEqual 65_525L
    }
}

private class ByteArrayBlockTarget(
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
