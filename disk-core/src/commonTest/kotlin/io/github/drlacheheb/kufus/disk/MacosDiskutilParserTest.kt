package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class MacosDiskutilParserTest {
    @Test
    fun parsesExternalUsbDrivesAndExcludesInternalAndVirtualDisks() {
        val listXml =
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
            <plist version="1.0">
            <dict>
                <key>AllDisksAndPartitions</key>
                <array>
                    <dict>
                        <key>DeviceIdentifier</key>
                        <string>disk0</string>
                        <key>Size</key>
                        <integer>500107862016</integer>
                        <key>Partitions</key>
                        <array>
                            <dict>
                                <key>DeviceIdentifier</key>
                                <string>disk0s1</string>
                                <key>MountPoint</key>
                                <string>/</string>
                            </dict>
                        </array>
                    </dict>
                    <dict>
                        <key>DeviceIdentifier</key>
                        <string>disk2</string>
                        <key>Size</key>
                        <integer>63333990400</integer>
                        <key>Partitions</key>
                        <array>
                            <dict>
                                <key>DeviceIdentifier</key>
                                <string>disk2s1</string>
                                <key>MountPoint</key>
                                <string>/Volumes/USB64</string>
                            </dict>
                        </array>
                    </dict>
                    <dict>
                        <key>DeviceIdentifier</key>
                        <string>disk3</string>
                        <key>Size</key>
                        <integer>256000000000</integer>
                        <key>APFSVolumes</key>
                        <array>
                            <dict>
                                <key>DeviceIdentifier</key>
                                <string>disk3s1</string>
                                <key>MountPoint</key>
                                <string>/Volumes/ExternalSSD</string>
                            </dict>
                        </array>
                    </dict>
                </array>
            </dict>
            </plist>
            """.trimIndent()

        val disk0Info =
            """
            <plist version="1.0"><dict>
                <key>DeviceIdentifier</key><string>disk0</string>
                <key>BusProtocol</key><string>Apple Fabric</string>
                <key>Internal</key><true/>
                <key>MediaName</key><string>APPLE SSD AP0512</string>
                <key>TotalSize</key><integer>500107862016</integer>
            </dict></plist>
            """.trimIndent()

        val disk2Info =
            """
            <plist version="1.0"><dict>
                <key>DeviceIdentifier</key><string>disk2</string>
                <key>BusProtocol</key><string>USB</string>
                <key>Internal</key><false/>
                <key>RemovableMediaOrExternalDevice</key><true/>
                <key>VirtualOrPhysical</key><string>Physical</string>
                <key>MediaName</key><string>SanDisk Ultra USB</string>
                <key>TotalSize</key><integer>63333990400</integer>
            </dict></plist>
            """.trimIndent()

        val disk3Info =
            """
            <plist version="1.0"><dict>
                <key>DeviceIdentifier</key><string>disk3</string>
                <key>BusProtocol</key><string>USB</string>
                <key>Internal</key><false/>
                <key>RemovableMedia</key><false/>
                <key>VirtualOrPhysical</key><string>Virtual</string>
                <key>MediaName</key><string>Synthesized APFS</string>
                <key>TotalSize</key><integer>256000000000</integer>
            </dict></plist>
            """.trimIndent()

        val drives =
            buildMacosUsbDrives(
                listPlistXml = listXml,
                infoPlistByDiskId =
                    mapOf(
                        "disk0" to disk0Info,
                        "disk2" to disk2Info,
                        "disk3" to disk3Info,
                    ),
            )

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
    fun validatesBsdDiskIdentifiersStrictly() {
        isValidBsdDiskIdentifier("disk2") shouldBe true
        isValidBsdDiskIdentifier("disk14") shouldBe true
        isValidBsdDiskIdentifier("disk") shouldBe false
        isValidBsdDiskIdentifier("disk2;rm -rf") shouldBe false
        isValidBsdDiskIdentifier("/dev/disk2") shouldBe false
    }
}
