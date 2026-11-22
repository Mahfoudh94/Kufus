package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class LsblkParserTest {
    @Test
    fun parsesUsbDrivesAndExcludesNvmeLuksAndZram() {
        val sampleJson =
            """
            {
              "blockdevices": [
                {
                  "name": "sda",
                  "path": "/dev/sda",
                  "size": 63333990400,
                  "type": "disk",
                  "tran": "usb",
                  "rm": true,
                  "hotplug": true,
                  "model": "Flash Disk      ",
                  "vendor": "Generic ",
                  "serial": null,
                  "mountpoints": [],
                  "children": [
                    {
                      "name": "sda1",
                      "path": "/dev/sda1",
                      "size": 63329796096,
                      "type": "part",
                      "mountpoints": ["/run/media/user/USB64"]
                    }
                  ]
                },
                {
                  "name": "sdb",
                  "path": "/dev/sdb",
                  "size": 8195719168,
                  "type": "disk",
                  "tran": "usb",
                  "rm": true,
                  "hotplug": true,
                  "model": "DISK            ",
                  "vendor": "SCSI    ",
                  "serial": null,
                  "mountpoints": []
                },
                {
                  "name": "zram0",
                  "path": "/dev/zram0",
                  "size": 8589934592,
                  "type": "disk",
                  "tran": null,
                  "rm": false,
                  "hotplug": false,
                  "mountpoints": ["[SWAP]"]
                },
                {
                  "name": "nvme0n1",
                  "path": "/dev/nvme0n1",
                  "size": 512110190592,
                  "type": "disk",
                  "tran": "nvme",
                  "rm": false,
                  "hotplug": false,
                  "model": "SAMSUNG MZVL2512HCJQ-00BH1",
                  "children": [
                    {
                      "name": "nvme0n1p3",
                      "type": "part",
                      "children": [
                        {
                          "name": "luks-root",
                          "type": "crypt",
                          "mountpoints": ["/", "/var", "/home"]
                        }
                      ]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        val drives = parseLsblkJson(sampleJson)

        drives shouldContainExactly
            listOf(
                UsbDrive(
                    id = "/dev/sda",
                    name = "sda",
                    model = "Generic Flash Disk",
                    sizeBytes = 63_333_990_400L,
                    mountPoints = listOf("/run/media/user/USB64"),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
                UsbDrive(
                    id = "/dev/sdb",
                    name = "sdb",
                    model = "SCSI DISK",
                    sizeBytes = 8_195_719_168L,
                    mountPoints = emptyList(),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
            )
    }

    @Test
    fun excludesUsbDriveContainingRootMount() {
        val sampleJson =
            """
            {
              "blockdevices": [
                {
                  "name": "sdc",
                  "path": "/dev/sdc",
                  "size": 128000000000,
                  "type": "disk",
                  "tran": "usb",
                  "rm": false,
                  "hotplug": true,
                  "model": "External Boot SSD",
                  "children": [
                    {
                      "name": "sdc1",
                      "type": "part",
                      "mountpoints": ["/"]
                    }
                  ]
                }
              ]
            }
            """.trimIndent()

        parseLsblkJson(sampleJson).shouldBeEmpty()
    }

    @Test
    fun formatByteSizeFormatsHumanReadableUnits() {
        formatByteSize(0L) shouldBe "0 B"
        formatByteSize(63_333_990_400L) shouldBe "63.3 GB"
        formatByteSize(8_195_719_168L) shouldBe "8.2 GB"
    }
}
