package io.github.drlacheheb.kufus.disk.parser

import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import kotlin.test.Test

class MacosDiskMapperTest {
    @Test
    fun mapsExternalUsbDisksAndExcludesInternalAndVirtualContainers() {
        val disks =
            listOf(
                MacosWholeDiskEntry(
                    bsdName = "disk0",
                    modelName = "APPLE SSD AP0512",
                    busProtocol = "Apple Fabric",
                    sizeBytes = 500_107_862_016L,
                    isInternal = true,
                    isRemovable = false,
                    isVirtual = false,
                ),
                MacosWholeDiskEntry(
                    bsdName = "disk2",
                    modelName = "SanDisk Ultra USB",
                    busProtocol = "USB",
                    sizeBytes = 63_333_990_400L,
                    isInternal = false,
                    isRemovable = true,
                    isVirtual = false,
                ),
                MacosWholeDiskEntry(
                    bsdName = "disk3",
                    modelName = "Synthesized APFS",
                    busProtocol = "USB",
                    sizeBytes = 63_333_990_400L,
                    isInternal = false,
                    isRemovable = false,
                    isVirtual = true,
                ),
                MacosWholeDiskEntry(
                    bsdName = "disk4",
                    modelName = "Thunderbolt NVMe",
                    busProtocol = "PCI-Express",
                    sizeBytes = 1_000_000_000_000L,
                    isInternal = false,
                    isRemovable = true,
                    isVirtual = false,
                ),
            )
        val mounts =
            listOf(
                MacosMountEntry(parentWholeBsdName = "disk0", volumePath = "/"),
                MacosMountEntry(parentWholeBsdName = "disk2", volumePath = "/Volumes/USB64"),
            )

        val drives = buildMacosUsbDrives(disks = disks, mounts = mounts)

        drives shouldContainExactly
            listOf(
                UsbDrive(
                    id = "/dev/disk2",
                    name = "disk2",
                    model = "SanDisk Ultra USB",
                    sizeBytes = 63_333_990_400L,
                    mountPoints = listOf("/Volumes/USB64"),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
            )
    }

    @Test
    fun excludesExternalUsbDriveHostingMacosSystemVolume() {
        val disks =
            listOf(
                MacosWholeDiskEntry(
                    bsdName = "disk2",
                    modelName = "External Boot SSD",
                    busProtocol = "",
                    sizeBytes = 256_000_000_000L,
                    isInternal = false,
                    isRemovable = false,
                    isVirtual = false,
                    isUsbBus = true,
                ),
            )
        val mounts =
            listOf(
                MacosMountEntry(parentWholeBsdName = "disk2", volumePath = "/System/Volumes/Data"),
            )

        buildMacosUsbDrives(disks = disks, mounts = mounts).shouldBeEmpty()
    }
}
