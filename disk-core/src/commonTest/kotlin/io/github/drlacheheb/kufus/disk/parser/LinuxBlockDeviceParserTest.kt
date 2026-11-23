package io.github.drlacheheb.kufus.disk.parser

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.formatByteSize
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LinuxBlockDeviceParserTest {
    @Test
    fun parsesUsbDrivesFromSysfsAndProcMountsAndExcludesNvmeZramAndOptical() {
        val mounts =
            parseProcMounts(
                """
                /dev/dm-0 / btrfs rw,relatime 0 0
                /dev/nvme0n1p2 /boot ext4 rw,relatime 0 0
                /dev/sda1 /run/media/user/USB\04064 vfat rw,nosuid 0 0
                """.trimIndent(),
            ) +
                parseProcSwaps(
                    """
                    Filename				Type		Size		Used		Priority
                    /dev/zram0                              partition	8388604		0		100
                    """.trimIndent(),
                )

        val records =
            listOf(
                LinuxSysfsBlockRecord(
                    name = "sda",
                    sizeSectors = 123_699_200L,
                    isUsbTransport = true,
                    isRemovable = true,
                    vendor = "Generic ",
                    model = "Flash Disk      ",
                    partitionNames = listOf("sda1"),
                ),
                LinuxSysfsBlockRecord(
                    name = "sdb",
                    sizeSectors = 16_007_264L,
                    isUsbTransport = true,
                    isRemovable = true,
                    vendor = "SCSI    ",
                    model = "DISK            ",
                    serial = "ABC123",
                ),
                LinuxSysfsBlockRecord(
                    name = "sr0",
                    sizeSectors = 2_000_000L,
                    isUsbTransport = true,
                    isRemovable = true,
                    scsiType = 5,
                    model = "DVD Writer",
                ),
                LinuxSysfsBlockRecord(
                    name = "nvme0n1",
                    sizeSectors = 1_000_215_216L,
                    isUsbTransport = false,
                    isRemovable = false,
                    model = "SAMSUNG MZVL2512HCJQ-00BH1",
                    partitionNames = listOf("nvme0n1p2", "nvme0n1p3"),
                ),
                LinuxSysfsBlockRecord(
                    name = "zram0",
                    sizeSectors = 16_777_216L,
                    isUsbTransport = false,
                    isRemovable = false,
                ),
            )

        val drives =
            buildLinuxUsbDrives(
                records = records,
                mounts = mounts,
                slavesByBlock = mapOf("dm-0" to listOf("nvme0n1p3")),
            )

        drives shouldContainExactly
            listOf(
                UsbDrive(
                    id = "/dev/sda",
                    name = "sda",
                    model = "Generic Flash Disk",
                    sizeBytes = 63_333_990_400L,
                    mountPoints = listOf("/run/media/user/USB 64"),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
                UsbDrive(
                    id = "/dev/sdb",
                    name = "sdb",
                    model = "SCSI DISK",
                    sizeBytes = 8_195_719_168L,
                    serial = "ABC123",
                    mountPoints = emptyList(),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
            )
    }

    @Test
    fun excludesUsbDriveContainingEncryptedRootSlave() {
        val mounts = parseProcMounts("/dev/dm-0 / ext4 rw 0 0")
        val records =
            listOf(
                LinuxSysfsBlockRecord(
                    name = "sdc",
                    sizeSectors = 250_000_000L,
                    isUsbTransport = true,
                    isRemovable = false,
                    model = "External Boot SSD",
                    partitionNames = listOf("sdc1"),
                ),
            )

        val drives =
            buildLinuxUsbDrives(
                records = records,
                mounts = mounts,
                slavesByBlock = mapOf("dm-0" to listOf("sdc1")),
            )

        drives.shouldBeEmpty()
    }

    @Test
    fun listsNonRemovableExternalUsbSsdSafely() {
        val records =
            listOf(
                LinuxSysfsBlockRecord(
                    name = "sdd",
                    sizeSectors = 1_000_000L,
                    isUsbTransport = true,
                    isRemovable = false,
                    model = "NVMe Enclosure",
                ),
            )

        val drives = buildLinuxUsbDrives(records = records, mounts = emptyList())

        drives.size shouldBe 1
        drives.first().isRemovable shouldBe true
    }

    @Test
    fun formatByteSizeFormatsHumanReadableUnits() {
        formatByteSize(0L) shouldBe "0 B"
        formatByteSize(63_333_990_400L) shouldBe "63.3 GB"
        formatByteSize(8_195_719_168L) shouldBe "8.2 GB"
    }
}
