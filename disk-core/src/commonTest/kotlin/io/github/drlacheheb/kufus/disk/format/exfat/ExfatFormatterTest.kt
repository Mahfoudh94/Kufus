package io.github.drlacheheb.kufus.disk.format.exfat

import io.github.drlacheheb.kufus.disk.format.core.UnicodeUpcaseTable
import io.github.drlacheheb.kufus.disk.format.core.sanitizeVolumeLabel
import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.partition.PartitionTableBuilder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExfatFormatterTest {
    @Test
    fun writesValidBootRegionBackupVbrFatBitmapUpcaseAndRootDirectory() {
        val diskBytes = 48L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val target = ExfatArrayBlockTarget(image, sectorSizeBytes = 512)
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)

        ExfatFormatter.format(
            target = target,
            span = span,
            volumeLabel = "EXFAT_DRIVE",
        )

        val partStart = span.startByteOffset.toInt()
        image.decodeToString(partStart + 3, partStart + 11) shouldBe "EXFAT   "
        image[partStart + 510] shouldBe 0x55.toByte()
        image[partStart + 511] shouldBe 0xAA.toByte()
        // Backup boot region starts 12 sectors in
        val backupStart = partStart + 12 * 512
        image.copyOfRange(partStart, partStart + 12 * 512) shouldBe image.copyOfRange(backupStart, backupStart + 12 * 512)

        val geom = ExfatFormatter.computeGeometry(span, UnicodeUpcaseTable.buildExfatCompressedTable().size.toLong())
        val fatStart = partStart + geom.fatOffsetSectors.toInt() * 512
        image[fatStart] shouldBe 0xF8.toByte()
        image[fatStart + 4] shouldBe 0xFF.toByte()

        val rootStart =
            partStart +
                (geom.clusterHeapOffsetSectors.toInt() + (geom.rootCluster - 2) * geom.sectorsPerCluster) * 512
        image[rootStart] shouldBe 0x83.toByte() // Volume Label
        val cleanLabel = sanitizeVolumeLabel("EXFAT_DRIVE", FilesystemType.EXFAT)
        image[rootStart + 1] shouldBe cleanLabel.length.toByte()
        image[rootStart + 32] shouldBe 0x81.toByte() // Allocation Bitmap
        image[rootStart + 64] shouldBe 0x82.toByte() // Up-case Table
    }

    @Test
    fun clampsLongVolumeLabelsToElevenCodeUnits() {
        val diskBytes = 48L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)

        ExfatFormatter.format(
            target = ExfatArrayBlockTarget(image, sectorSizeBytes = 512),
            span = span,
            volumeLabel = "FIFTEEN_CHAR_LB",
        )

        val geom = ExfatFormatter.computeGeometry(span, UnicodeUpcaseTable.buildExfatCompressedTable().size.toLong())
        val rootStart =
            span.startByteOffset.toInt() +
                (geom.clusterHeapOffsetSectors.toInt() + (geom.rootCluster - 2) * geom.sectorsPerCluster) * 512
        image[rootStart] shouldBe 0x83.toByte()
        image[rootStart + 1] shouldBe 11.toByte()
    }
}

private class ExfatArrayBlockTarget(
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
