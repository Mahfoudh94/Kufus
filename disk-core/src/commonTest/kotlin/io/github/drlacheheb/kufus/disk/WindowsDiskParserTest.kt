package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WindowsDiskParserTest {
    @Test
    fun parsesArrayOfPowerShellDisksAndExcludesNvmeAndBootDisks() {
        val sampleJson =
            """
            [
              {
                "number": 0,
                "friendlyName": "SAMSUNG MZVL2512HCJQ",
                "serialNumber": "S63Z123",
                "size": 512110190592,
                "busType": "NVMe",
                "isBoot": true,
                "isSystem": true,
                "driveLetters": ["C"]
              },
              {
                "number": 1,
                "friendlyName": "Generic Flash Disk USB Device",
                "serialNumber": "000123",
                "size": 63333990400,
                "busType": "USB",
                "isBoot": false,
                "isSystem": false,
                "driveLetters": ["E:"]
              },
              {
                "number": 2,
                "friendlyName": "External USB SSD",
                "serialNumber": "SSD999",
                "size": 256000000000,
                "busType": "USB",
                "isBoot": false,
                "isSystem": false,
                "driveLetters": []
              }
            ]
            """.trimIndent()

        val drives = parseWindowsPowerShellDisksJson(sampleJson, systemDrive = "C:")

        drives shouldContainExactly
            listOf(
                UsbDrive(
                    id = "\\\\.\\PhysicalDrive1",
                    name = "PhysicalDrive1",
                    model = "Generic Flash Disk USB Device",
                    sizeBytes = 63_333_990_400L,
                    serial = "000123",
                    mountPoints = listOf("E:"),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
                UsbDrive(
                    id = "\\\\.\\PhysicalDrive2",
                    name = "PhysicalDrive2",
                    model = "External USB SSD",
                    sizeBytes = 256_000_000_000L,
                    serial = "SSD999",
                    mountPoints = emptyList(),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
            )
    }

    @Test
    fun parsesSingleObjectFromPowerShellConvertToJson() {
        val sampleJson =
            """
            {
              "number": 2,
              "friendlyName": "SanDisk Ultra",
              "size": 32000000000,
              "busType": "USB",
              "isBoot": false,
              "isSystem": false,
              "driveLetters": ["F:\\"]
            }
            """.trimIndent()

        val drives = parseWindowsPowerShellDisksJson(sampleJson)

        drives shouldContainExactly
            listOf(
                UsbDrive(
                    id = "\\\\.\\PhysicalDrive2",
                    name = "PhysicalDrive2",
                    model = "SanDisk Ultra",
                    sizeBytes = 32_000_000_000L,
                    mountPoints = listOf("F:"),
                    isRemovable = true,
                    isSystemDisk = false,
                ),
            )
    }

    @Test
    fun excludesUsbDriveContainingSystemDriveLetter() {
        val sampleJson =
            """
            {
              "number": 1,
              "friendlyName": "Windows To Go USB",
              "size": 128000000000,
              "busType": "USB",
              "isBoot": false,
              "isSystem": false,
              "driveLetters": ["C:"]
            }
            """.trimIndent()

        parseWindowsPowerShellDisksJson(sampleJson, systemDrive = "C:").shouldBeEmpty()
    }

    @Test
    fun formatsWindowsDriveModelCleanly() {
        buildWindowsDriveModel(" Generic ", " Flash Disk ", "PhysicalDrive1") shouldBe "Generic Flash Disk"
        buildWindowsDriveModel(null, null, "PhysicalDrive1") shouldBe "PhysicalDrive1"
    }
}
