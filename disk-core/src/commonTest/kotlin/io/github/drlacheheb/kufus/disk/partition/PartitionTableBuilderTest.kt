package io.github.drlacheheb.kufus.disk.partition

import io.github.drlacheheb.kufus.disk.io.Crc32
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PartitionTableBuilderTest {
    @Test
    fun buildsOneMibAlignedMbrWithExpectedTypeAndBootSignature() {
        val diskBytes = 128L * 1024L * 1024L
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.MBR)

        span.startLba shouldBe 2048L
        span.startByteOffset shouldBe 1_048_576L
        span.lastLba shouldBe (diskBytes / 512L) - 1L

        val mbr = PartitionTableBuilder.buildMbrSector(span, FilesystemType.FAT32)
        mbr[510] shouldBe 0x55.toByte()
        mbr[511] shouldBe 0xAA.toByte()
        mbr[450] shouldBe 0x0C.toByte()
        readLeInt(mbr, 454) shouldBe 2048
        readLeInt(mbr, 458) shouldBe span.sectorCount.toInt()
    }

    @Test
    fun buildsValidPrimaryAndBackupGptWithMatchingCrc32() {
        val diskBytes = 256L * 1024L * 1024L
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        val layout = PartitionTableBuilder.buildGptLayout(span, FilesystemType.EXFAT, partitionName = "KUFUS")

        layout.protectiveMbrSector[450] shouldBe 0xEE.toByte()
        layout.protectiveMbrSector[510] shouldBe 0x55.toByte()
        layout.protectiveMbrSector[511] shouldBe 0xAA.toByte()

        layout.primaryHeaderSector.decodeToString(0, 8) shouldBe "EFI PART"
        layout.backupHeaderSector.decodeToString(0, 8) shouldBe "EFI PART"

        val entriesCrc = Crc32.compute(layout.primaryEntriesBytes, 0, layout.primaryEntriesBytes.size).toInt()
        readLeInt(layout.primaryHeaderSector, 88) shouldBe entriesCrc
        readLeInt(layout.backupHeaderSector, 88) shouldBe entriesCrc

        verifyGptHeaderCrc(layout.primaryHeaderSector)
        verifyGptHeaderCrc(layout.backupHeaderSector)
        layout.backupEntriesOffsetBytes shouldBe (span.totalSectors - 33L) * 512L
        layout.backupHeaderOffsetBytes shouldBe (span.totalSectors - 1L) * 512L
    }

    @Test
    fun rejectsMbrWhenCapacityExceedsTwoTib() {
        assertFailsWith<FlashStepException> {
            PartitionTableBuilder.computeSinglePartitionSpan(
                diskSizeBytes = 3_000_000_000_000L,
                sectorSizeBytes = 512,
                scheme = PartitionScheme.MBR,
            )
        }.code shouldBe "DISK_TOO_LARGE_FOR_MBR"
    }

    @Test
    fun buildsDualWindowsPartitionGptAndMbrLayouts() {
        val diskBytes = 256L * 1024L * 1024L
        val dualGpt = PartitionTableBuilder.computeDualWindowsPartitionSpans(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        (dualGpt.dataSpan.lastLba + 1L) shouldBe dualGpt.uefiNtfsSpan.startLba
        dualGpt.uefiNtfsSpan.sizeBytes shouldBe 1_048_576L

        val layout =
            PartitionTableBuilder.buildMultiPartitionGptLayout(
                partitions =
                    listOf(
                        PartitionSpec(dualGpt.dataSpan, FilesystemType.NTFS, "WIN11", PartitionRole.DATA),
                        PartitionSpec(dualGpt.uefiNtfsSpan, FilesystemType.FAT32, "UEFI_NTFS", PartitionRole.EFI_SYSTEM),
                    ),
            )
        verifyGptHeaderCrc(layout.primaryHeaderSector)
        verifyGptHeaderCrc(layout.backupHeaderSector)
        readLeInt(layout.primaryEntriesBytes, 32) shouldBe dualGpt.dataSpan.startLba.toInt()
        readLeInt(layout.primaryEntriesBytes, 128 + 32) shouldBe dualGpt.uefiNtfsSpan.startLba.toInt()

        val dualMbr = PartitionTableBuilder.computeDualWindowsPartitionSpans(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.MBR)
        val mbr =
            PartitionTableBuilder.buildMultiPartitionMbrSector(
                listOf(
                    PartitionSpec(dualMbr.dataSpan, FilesystemType.NTFS, "WIN11", PartitionRole.DATA),
                    PartitionSpec(dualMbr.uefiNtfsSpan, FilesystemType.FAT32, "UEFI_NTFS", PartitionRole.EFI_SYSTEM),
                ),
            )
        mbr[446 + 4] shouldBe 0x07.toByte()
        mbr[446 + 16 + 4] shouldBe 0xEF.toByte()
        readLeInt(mbr, 446 + 16 + 8) shouldBe dualMbr.uefiNtfsSpan.startLba.toInt()
    }

    private fun verifyGptHeaderCrc(header: ByteArray) {
        val stored = readLeInt(header, 16)
        val cleared = header.copyOf()
        cleared.fill(0, fromIndex = 16, toIndex = 20)
        Crc32.compute(cleared, 0, 92).toInt() shouldBe stored
    }

    private fun readLeInt(
        bytes: ByteArray,
        offset: Int,
    ): Int =
        (bytes[offset].toInt() and 0xFF) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 8) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 3].toInt() and 0xFF) shl 24)
}
