package io.github.drlacheheb.kufus.disk.format.ntfs

import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.io.readUInt16LeInt
import io.github.drlacheheb.kufus.disk.io.readUInt32LeLong
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.partition.PartitionTableBuilder
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class NtfsFormatterTest {
    @Test
    fun writesValidBootBackupSectorMftAndMftMirror() {
        val diskBytes = 48L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val target = NtfsArrayBlockTarget(image, sectorSizeBytes = 512)
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)

        NtfsFormatter.format(
            target = target,
            span = span,
            volumeLabel = "WIN11_TEST",
        )

        val partStart = span.startByteOffset.toInt()
        image.decodeToString(partStart + 3, partStart + 11) shouldBe "NTFS    "
        image[partStart + 510] shouldBe 0x55.toByte()
        image[partStart + 511] shouldBe 0xAA.toByte()

        val geom = NtfsFormatter.computeGeometry(span)
        // Backup boot sector sits in the last sector of the partition
        val backupStart = partStart + (geom.totalSectors * 512L).toInt()
        image.copyOfRange(partStart, partStart + 512) shouldBe image.copyOfRange(backupStart, backupStart + 512)

        // $MFT and $MFTMirr begin with FILE records and USA-protected signatures
        val mftOffset = partStart + (geom.mftLcn * geom.clusterBytes).toInt()
        val mirrOffset = partStart + (geom.mftMirrLcn * geom.clusterBytes).toInt()
        image.decodeToString(mftOffset, mftOffset + 4) shouldBe "FILE"
        image.copyOfRange(mftOffset, mftOffset + 4096) shouldBe image.copyOfRange(mirrOffset, mirrOffset + 4096)
    }

    @Test
    fun mftRecordAttributesAreSortedByType() {
        val diskBytes = 48L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        NtfsFormatter.format(NtfsArrayBlockTarget(image, sectorSizeBytes = 512), span, "SORTED")

        val geom = NtfsFormatter.computeGeometry(span)
        val mftOffset = span.startByteOffset.toInt() + (geom.mftLcn * geom.clusterBytes).toInt()
        for (recordIndex in 0 until 27) {
            val record = image.copyOfRange(mftOffset + recordIndex * 1024, mftOffset + (recordIndex + 1) * 1024)
            undoUsaFixup(record)
            val types = mutableListOf<Long>()
            var attr = readUInt16LeInt(record, 20)
            while (readUInt32LeLong(record, attr) != 0xFFFF_FFFFL) {
                types += readUInt32LeLong(record, attr)
                attr += readUInt32LeLong(record, attr + 4).toInt()
            }
            types shouldBe types.sorted()
        }
    }

    @Test
    fun encodesLargeClusterIndexAllocationAndBootSizesCorrectly() {
        val diskBytes = 64L * 1024L * 1024L
        val image = ByteArray(diskBytes.toInt())
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        NtfsFormatter.format(
            target = NtfsArrayBlockTarget(image, sectorSizeBytes = 512),
            span = span,
            volumeLabel = "NTFS64K",
            requestedClusterBytes = 65_536,
        )

        val partStart = span.startByteOffset.toInt()
        (image[partStart + 13].toInt() and 0xFF) shouldBe 128 // 65536 / 512
        image[partStart + 64] shouldBe (-10).toByte() // 1024-byte MFT records
        image[partStart + 68] shouldBe (-12).toByte() // 4096-byte index blocks

        val geom = NtfsFormatter.computeGeometry(span, requestedClusterBytes = 65_536)
        val mftOffset = partStart + (geom.mftLcn * geom.clusterBytes).toInt()
        val rootRec = image.copyOfRange(mftOffset + 5 * 1024, mftOffset + 6 * 1024)
        undoUsaFixup(rootRec)
        var attr = readUInt16LeInt(rootRec, 20)
        var foundIndexAlloc = false
        while (readUInt32LeLong(rootRec, attr) != 0xFFFF_FFFFL) {
            if (readUInt32LeLong(rootRec, attr) == 0xA0L) {
                foundIndexAlloc = true
                readUInt32LeLong(rootRec, attr + 40) shouldBe 65_536L // allocSize
                readUInt32LeLong(rootRec, attr + 48) shouldBe 4096L // realSize
            }
            attr += readUInt32LeLong(rootRec, attr + 4).toInt()
        }
        foundIndexAlloc shouldBe true
    }

    private fun undoUsaFixup(record: ByteArray) {
        val usaOffset = readUInt16LeInt(record, 4)
        val usaCount = readUInt16LeInt(record, 6)
        for (i in 1 until usaCount) {
            record[i * 512 - 2] = record[usaOffset + i * 2]
            record[i * 512 - 1] = record[usaOffset + i * 2 + 1]
        }
    }
}

private class NtfsArrayBlockTarget(
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
