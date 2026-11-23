package io.github.drlacheheb.kufus.disk.format.core

import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.WindowsIsoMetadata
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
    fun resolvesLinuxPartitionNodes() {
        resolveLinuxPartitionNode("/dev/sdb") shouldBe "/dev/sdb1"
        resolveLinuxPartitionNode("/dev/loop0") shouldBe "/dev/loop0p1"
        resolveLinuxPartitionNode("/dev/mmcblk0") shouldBe "/dev/mmcblk0p1"
    }

    @Test
    fun resolvesWindowsIsoFilesystemsAndValidationRulesPerHost() {
        supportedWindowsFilesystemsForHost(HostOsKind.LINUX) shouldContain FilesystemType.NTFS
        supportedWindowsFilesystemsForHost(HostOsKind.MACOS) shouldBe listOf(FilesystemType.FAT32)

        val largeMeta =
            WindowsIsoMetadata(
                volumeLabel = "CCCOMA_X64",
                installImagePath = "sources/install.wim",
                installImageSizeBytes = 5_000_000_000L,
                largestFileBytes = 5_000_000_000L,
                totalPayloadBytes = 5_600_000_000L,
                isSplitRequiredForFat32 = true,
                isWimSplittable = true,
            )
        defaultWindowsFilesystemForHost(HostOsKind.LINUX, largeMeta) shouldBe FilesystemType.NTFS
        defaultWindowsFilesystemForHost(HostOsKind.MACOS, largeMeta) shouldBe FilesystemType.FAT32

        val smallUsb = UsbDrive("/dev/sdb", "sdb", "Small Stick", sizeBytes = 4_000_000_000L)
        val bigUsb = UsbDrive("/dev/sdb", "sdb", "Big Stick", sizeBytes = 16_000_000_000L)
        val winImage = ImageInfo("/tmp/win11.iso", 5_800_000_000L, ImageFormat.WINDOWS_ISO, windowsMetadata = largeMeta)

        (windowsInstallBlockReason(PartitionScheme.GPT, FilesystemType.FAT32, HostOsKind.MACOS, smallUsb, winImage) != null) shouldBe true
        windowsInstallBlockReason(PartitionScheme.GPT, FilesystemType.FAT32, HostOsKind.MACOS, bigUsb, winImage) shouldBe null

        val nonSplittable = winImage.copy(windowsMetadata = largeMeta.copy(isWimSplittable = false))
        (windowsInstallBlockReason(PartitionScheme.GPT, FilesystemType.FAT32, HostOsKind.MACOS, bigUsb, nonSplittable) != null) shouldBe true
    }
}
