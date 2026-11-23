package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindowsInstallerEngineTest {
    @Test
    fun createsFat32WindowsInstallerPartitionCopiesFilesAndWritesAutounattendXml() {
        val efiPayload = byteArrayOf(0x4D, 0x5A, 0x01, 0x02)
        val wimPayload = "MSWIM\u0000\u0000\u0000win-esd-content".encodeToByteArray()
        val isoBytes = buildSimpleIsoWithSources(volumeLabel = "WIN11_X64", efiPayload = efiPayload, wimPayload = wimPayload)

        val diskBytes = 48L * 1024L * 1024L
        val target = MemoryBlockTarget(capacityBytes = diskBytes, sectorSizeBytes = 512)
        val writtenFiles = mutableMapOf<String, ByteArray>()
        val createdDirs = mutableSetOf<String>()
        var unmountedCleanly = false
        val events = mutableListOf<WorkerEvent>()
        val timeline = mutableListOf<String>()

        val request =
            WindowsFlashRequest(
                imagePath = "/tmp/win11.iso",
                deviceId = "/dev/sdb",
                partitionScheme = PartitionScheme.GPT,
                filesystem = FilesystemType.FAT32,
                volumeLabel = "WIN11_X64",
                userExperience =
                    WindowsUserExperienceOptions(
                        bypassSystemRequirements = true,
                        bypassMicrosoftAccount = true,
                        disableDataCollection = true,
                        localAccountUsername = "UserOne",
                    ),
            )

        val completed =
            WindowsInstallerEngine.execute(
                request = request,
                isoReader = ByteArrayReader(isoBytes),
                target = target,
                uefiNtfsBytes = null,
                clockMs = { 100L },
                onEvent = { event ->
                    events += event
                    if (event is WorkerEvent.Progress && event.phase == FlashPhase.WRITING) {
                        timeline += "WRITING:${event.bytesProcessed}/${event.totalBytes}"
                    }
                },
                mountPartition = { _, _ ->
                    FakeMountedPartition(createdDirs, writtenFiles) {
                        unmountedCleanly = true
                        timeline += "UNMOUNT"
                    }
                },
            )

        completed.verified shouldBe false
        unmountedCleanly shouldBe true
        createdDirs shouldContain "efi/boot"
        createdDirs shouldContain "sources"
        writtenFiles.getValue("efi/boot/bootx64.efi") shouldBe efiPayload
        writtenFiles.getValue("sources/install.esd") shouldBe wimPayload
        val xml = writtenFiles.getValue("autounattend.xml").decodeToString()
        xml shouldContain "BypassTPMCheck"
        xml shouldContain "BypassNRO"
        xml shouldContain "<Name>UserOne</Name>"
        val unmountIdx = timeline.indexOf("UNMOUNT")
        val finalWriteIdx = timeline.indexOfLast { it.startsWith("WRITING:") }
        val finalProgress = events.filterIsInstance<WorkerEvent.Progress>().last { it.phase == FlashPhase.WRITING }
        (unmountIdx in 0 until finalWriteIdx) shouldBe true
        (finalProgress.bytesProcessed == finalProgress.totalBytes) shouldBe true
    }

    @Test
    fun createsDualNtfsAndUefiNtfsPartitionsAndRejectsCorruptBootAsset() {
        assertFailsWith<FlashStepException> {
            WindowsInstallerEngine.verifyUefiNtfsBytes(ByteArray(1_048_576))
        }.code shouldBe "CORRUPT_ASSET"

        val isoBytes =
            buildSimpleIsoWithSources(
                volumeLabel = "WIN11_X64",
                efiPayload = byteArrayOf(0x4D, 0x5A),
                wimPayload = "MSWIM\u0000\u0000\u0000".encodeToByteArray(),
            )
        val diskBytes = 48L * 1024L * 1024L
        val target = MemoryBlockTarget(capacityBytes = diskBytes, sectorSizeBytes = 512)
        val fakeEsp = ByteArray(1_048_576) { 0x5E }
        val writtenFiles = mutableMapOf<String, ByteArray>()

        val request =
            WindowsFlashRequest(
                imagePath = "/tmp/win11.iso",
                deviceId = "/dev/sdb",
                partitionScheme = PartitionScheme.GPT,
                filesystem = FilesystemType.NTFS,
                volumeLabel = "WIN11_NTFS",
            )

        WindowsInstallerEngine.execute(
            request = request,
            isoReader = ByteArrayReader(isoBytes),
            target = target,
            uefiNtfsBytes = fakeEsp,
            clockMs = { 100L },
            onEvent = {},
            mountPartition = { partIdx, fs ->
                partIdx shouldBe 1
                fs shouldBe FilesystemType.NTFS
                FakeMountedPartition(mutableSetOf(), writtenFiles) {}
            },
            uefiNtfsVerifier = { _ -> },
        )

        val dual = PartitionTableBuilder.computeDualWindowsPartitionSpans(diskBytes, 512, PartitionScheme.GPT)
        val ntfsBoot = ByteArray(512)
        target.readAt(dual.dataSpan.startByteOffset, ntfsBoot, ntfsBoot.size)
        ntfsBoot.decodeToString(3, 11) shouldBe "NTFS    "
        ntfsBoot[510] shouldBe 0x55.toByte()
        ntfsBoot[511] shouldBe 0xAA.toByte()
        val ntfsBackupBoot = ByteArray(512)
        target.readAt(dual.dataSpan.startByteOffset + (dual.dataSpan.sectorCount - 1L) * 512L, ntfsBackupBoot, ntfsBackupBoot.size)
        ntfsBackupBoot shouldBe ntfsBoot
        val espSlice = ByteArray(fakeEsp.size)
        target.readAt(dual.uefiNtfsSpan.startByteOffset, espSlice, espSlice.size)
        espSlice shouldBe fakeEsp
    }

    @Test
    fun substitutesNoPromptBootloaderWhenBootx64MatchesCdboot() {
        val promptingLoader = "CDBOOT_PROMPT_BINARY".encodeToByteArray()
        val silentLoader = "CDBOOT_NO_PROMPT_OK!".encodeToByteArray()
        val isoBytes =
            buildIsoWithCdbootPair(
                bootx64 = promptingLoader,
                cdboot = promptingLoader,
                cdbootNoPrompt = silentLoader,
                wimPayload = "MSWIM\u0000\u0000\u0000".encodeToByteArray(),
            )
        val target = MemoryBlockTarget(capacityBytes = 48L * 1024L * 1024L, sectorSizeBytes = 512)
        val written = mutableMapOf<String, ByteArray>()

        WindowsInstallerEngine.execute(
            request =
                WindowsFlashRequest(
                    imagePath = "/tmp/win.iso",
                    deviceId = "/dev/sdb",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.FAT32,
                    volumeLabel = "WIN11",
                ),
            isoReader = ByteArrayReader(isoBytes),
            target = target,
            uefiNtfsBytes = null,
            clockMs = { 50L },
            onEvent = {},
            mountPartition = { _, _ -> FakeMountedPartition(mutableSetOf(), written) {} },
        )

        written.getValue("efi/boot/bootx64.efi") shouldBe silentLoader
    }

    private fun buildIsoWithCdbootPair(
        bootx64: ByteArray,
        cdboot: ByteArray,
        cdbootNoPrompt: ByteArray,
        wimPayload: ByteArray,
    ): ByteArray {
        val sec = 2048
        val img = ByteArray(48 * sec)
        img[16 * sec] = 1
        "CD001".encodeToByteArray().copyInto(img, 16 * sec + 1)
        "WIN11".padEnd(32, ' ').encodeToByteArray().copyInto(img, 16 * sec + 40, endIndex = 32)
        writeLeInt(img, 16 * sec + 156 + 2, 20)
        writeLeInt(img, 16 * sec + 156 + 10, sec)

        var r = 20 * sec
        r += writeIsoRec(img, r, "efi", 21, sec, true)
        writeIsoRec(img, r, "sources", 22, sec, true)

        var efiPos = 21 * sec
        efiPos += writeIsoRec(img, efiPos, "boot", 23, sec, true)
        writeIsoRec(img, efiPos, "microsoft", 26, sec, true)
        writeIsoRec(img, 23 * sec, "bootx64.efi;1", 24, bootx64.size, false)
        bootx64.copyInto(img, 24 * sec)

        writeIsoRec(img, 26 * sec, "boot", 27, sec, true)
        var msBoot = 27 * sec
        msBoot += writeIsoRec(img, msBoot, "cdboot.efi;1", 28, cdboot.size, false)
        writeIsoRec(img, msBoot, "cdboot_noprompt.efi;1", 29, cdbootNoPrompt.size, false)
        cdboot.copyInto(img, 28 * sec)
        cdbootNoPrompt.copyInto(img, 29 * sec)

        writeIsoRec(img, 22 * sec, "install.esd;1", 25, wimPayload.size, false)
        wimPayload.copyInto(img, 25 * sec)
        return img
    }

    private fun buildSimpleIsoWithSources(
        volumeLabel: String,
        efiPayload: ByteArray,
        wimPayload: ByteArray,
    ): ByteArray {
        val sec = 2048
        val img = ByteArray(40 * sec)
        img[16 * sec] = 1
        "CD001".encodeToByteArray().copyInto(img, 16 * sec + 1)
        val paddedLabel = volumeLabel.padEnd(32, ' ').encodeToByteArray()
        paddedLabel.copyInto(img, 16 * sec + 40, endIndex = 32)
        writeLeInt(img, 16 * sec + 156 + 2, 20)
        writeLeInt(img, 16 * sec + 156 + 10, sec)

        // Root dir @20: efi(@21), sources(@22)
        var r = 20 * sec
        r += writeIsoRec(img, r, "efi", 21, sec, true)
        writeIsoRec(img, r, "sources", 22, sec, true)

        // efi @21: boot(@23)
        writeIsoRec(img, 21 * sec, "boot", 23, sec, true)
        // efi/boot @23: bootx64.efi(@24)
        writeIsoRec(img, 23 * sec, "bootx64.efi;1", 24, efiPayload.size, false)
        efiPayload.copyInto(img, 24 * sec)

        // sources @22: install.esd(@25)
        writeIsoRec(img, 22 * sec, "install.esd;1", 25, wimPayload.size, false)
        wimPayload.copyInto(img, 25 * sec)
        return img
    }

    private fun writeIsoRec(
        img: ByteArray,
        offset: Int,
        name: String,
        lba: Int,
        len: Int,
        isDir: Boolean,
    ): Int {
        val nameBytes = name.encodeToByteArray()
        val recLen = 33 + nameBytes.size + if ((33 + nameBytes.size) % 2 == 1) 1 else 0
        img[offset] = recLen.toByte()
        writeLeInt(img, offset + 2, lba)
        writeLeInt(img, offset + 10, len)
        img[offset + 25] = if (isDir) 0x02 else 0x00
        img[offset + 32] = nameBytes.size.toByte()
        nameBytes.copyInto(img, offset + 33)
        return recLen
    }

    private class FakeMountedPartition(
        private val dirs: MutableSet<String>,
        private val files: MutableMap<String, ByteArray>,
        private val onUnmount: () -> Unit,
    ) : MountedPartitionHandle {
        override fun ensureDirectory(relativeDirPath: String) {
            dirs += relativeDirPath
        }

        override fun openFileForWrite(relativeFilePath: String): WritableMountedFile {
            val acc = mutableListOf<Byte>()
            return object : WritableMountedFile {
                override fun write(
                    bytes: ByteArray,
                    length: Int,
                ) {
                    for (i in 0 until length) acc.add(bytes[i])
                }

                override fun close() {
                    files[relativeFilePath] = acc.toByteArray()
                }
            }
        }

        override fun sync() = Unit

        override fun unmountAndClose() {
            onUnmount()
        }
    }

    private class ByteArrayReader(
        private val bytes: ByteArray,
    ) : SeekableByteReader {
        override fun sizeBytes(): Long = bytes.size.toLong()

        override fun readAt(
            offset: Long,
            length: Int,
        ): ByteArray {
            if (offset < 0L || offset >= bytes.size.toLong() || length <= 0) return ByteArray(0)
            val start = offset.toInt()
            val end = minOf(bytes.size, start + length)
            return bytes.copyOfRange(start, end)
        }
    }

    private class MemoryBlockTarget(
        override val capacityBytes: Long,
        override val sectorSizeBytes: Int,
    ) : BlockFlashTarget {
        private val disk = ByteArray(capacityBytes.toInt())

        override fun unmountAndLock() = Unit

        override fun writeAt(
            offset: Long,
            buffer: ByteArray,
            length: Int,
        ) {
            buffer.copyInto(disk, destinationOffset = offset.toInt(), endIndex = length)
        }

        override fun syncAndFlush() = Unit

        override fun readAt(
            offset: Long,
            buffer: ByteArray,
            length: Int,
        ): Int {
            disk.copyInto(buffer, startIndex = offset.toInt(), endIndex = offset.toInt() + length)
            return length
        }

        override fun close() = Unit
    }
}
