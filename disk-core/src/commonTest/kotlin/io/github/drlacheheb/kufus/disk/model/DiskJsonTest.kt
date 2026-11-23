package io.github.drlacheheb.kufus.disk.model

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DiskJsonTest {
    @Test
    fun roundTripDrivesListedEvent() {
        val drive =
            UsbDrive(
                id = "/dev/sdb",
                name = "sdb",
                model = "SanDisk Ultra",
                sizeBytes = 32_000_000_000L,
                mountPoints = listOf("/media/usb"),
            )
        val event = WorkerEvent.DrivesListed(drives = listOf(drive))

        val encoded = DiskJson.encodeEvent(event)
        val decoded = DiskJson.decodeEvent(encoded)

        decoded shouldBe event

        listOf(FlashPhase.WIPING, FlashPhase.PARTITIONING, FlashPhase.FORMATTING).forEach { phase ->
            val progressEvent = WorkerEvent.Progress(phase = phase, bytesProcessed = 50L, totalBytes = 100L, speedBytesPerSec = 0L)
            DiskJson.decodeEvent(DiskJson.encodeEvent(progressEvent)) shouldBe progressEvent
        }
    }

    @Test
    fun filterSafeUsbDrivesExcludesSystemAndNonRemovableDrives() {
        val safeDrive =
            UsbDrive(
                id = "/dev/sdb",
                name = "sdb",
                model = "USB Flash",
                sizeBytes = 16_000_000_000L,
                isRemovable = true,
                isSystemDisk = false,
            )
        val systemDrive =
            UsbDrive(
                id = "/dev/nvme0n1",
                name = "nvme0n1",
                model = "System SSD",
                sizeBytes = 512_000_000_000L,
                isRemovable = true,
                isSystemDisk = true,
            )
        val fixedDrive =
            UsbDrive(
                id = "/dev/sda",
                name = "sda",
                model = "Internal HDD",
                sizeBytes = 1_000_000_000_000L,
                isRemovable = false,
                isSystemDisk = false,
            )

        val result = filterSafeUsbDrives(listOf(safeDrive, systemDrive, fixedDrive))

        result shouldContainExactly listOf(safeDrive)
    }
}
