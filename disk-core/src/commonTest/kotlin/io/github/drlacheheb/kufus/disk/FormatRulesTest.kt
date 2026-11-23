package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FormatRulesTest {
    @Test
    fun filtersExt4ToLinuxHostsOnly() {
        supportedFilesystemsForHost(HostOsKind.LINUX) shouldContain FilesystemType.EXT4
        supportedFilesystemsForHost(HostOsKind.WINDOWS) shouldNotContain FilesystemType.EXT4
        supportedFilesystemsForHost(HostOsKind.MACOS) shouldNotContain FilesystemType.EXT4

        resolveHostOsKind("Windows 11") shouldBe HostOsKind.WINDOWS
        resolveHostOsKind("Mac OS X") shouldBe HostOsKind.MACOS
        resolveHostOsKind("Linux") shouldBe HostOsKind.LINUX
    }

    @Test
    fun sanitizesVolumeLabelsPerFilesystemConstraints() {
        sanitizeVolumeLabel("my*usb:/drive_1234", FilesystemType.FAT32) shouldBe "MYUSBDRIVE_"
        sanitizeVolumeLabel("  Live*Installer? <Win>  ", FilesystemType.EXFAT) shouldBe "LiveInstaller W"
        sanitizeVolumeLabel("A".repeat(40), FilesystemType.NTFS).length shouldBe 32
        sanitizeVolumeLabel("éééééééééé", FilesystemType.EXT4).encodeToByteArray().size shouldBe 16
    }

    @Test
    fun guardsMbrAndFat32TwoTibCeilingWhilePermittingGptExfat() {
        val threeTibDrive =
            UsbDrive(
                id = "/dev/sdb",
                name = "sdb",
                model = "Large USB SSD",
                sizeBytes = 3_000_000_000_000L,
            )

        val mbrReason =
            formatBlockReason(
                scheme = PartitionScheme.MBR,
                filesystem = FilesystemType.EXFAT,
                hostOs = HostOsKind.WINDOWS,
                drive = threeTibDrive,
            )
        (mbrReason != null) shouldBe true

        val fat32Reason =
            formatBlockReason(
                scheme = PartitionScheme.GPT,
                filesystem = FilesystemType.FAT32,
                hostOs = HostOsKind.WINDOWS,
                drive = threeTibDrive,
            )
        (fat32Reason != null) shouldBe true

        formatBlockReason(
            scheme = PartitionScheme.GPT,
            filesystem = FilesystemType.EXFAT,
            hostOs = HostOsKind.WINDOWS,
            drive = threeTibDrive,
        ) shouldBe null
    }

    @Test
    fun buildsSafeLinuxMkfsArgvAndResolvesPartitionNodes() {
        buildLinuxMkfsCommand(
            filesystem = FilesystemType.NTFS,
            partitionPath = "/dev/sdb1",
            volumeLabel = "My*USB Data",
            clusterSizeBytes = 4096,
        ) shouldBe listOf("mkfs.ntfs", "-Q", "-F", "-L", "My*USB Data", "-c", "4096", "/dev/sdb1")

        buildLinuxMkfsCommand(
            filesystem = FilesystemType.EXFAT,
            partitionPath = "/dev/loop0p1",
            volumeLabel = "BACK/UP",
        ) shouldBe listOf("mkfs.exfat", "-F", "-L", "BACKUP", "/dev/loop0p1")

        resolveLinuxPartitionNode("/dev/sdb") shouldBe "/dev/sdb1"
        resolveLinuxPartitionNode("/dev/loop0") shouldBe "/dev/loop0p1"
        resolveLinuxPartitionNode("/dev/mmcblk0") shouldBe "/dev/mmcblk0p1"
    }
}
