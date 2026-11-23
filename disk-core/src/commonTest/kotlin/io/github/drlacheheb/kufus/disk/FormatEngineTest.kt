package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class FormatEngineTest {
    @Test
    fun executesQuickFormatMbrAndFat32ClearingStaleSignatures() {
        val diskBytes = 40L * 1024L * 1024L
        val backing = ByteArray(diskBytes.toInt()) { 0x5A.toByte() }
        val target = ByteArrayBlockTargetForEngine(backing, sectorSizeBytes = 512)
        val events = mutableListOf<WorkerEvent>()
        var time = 0L

        val completed =
            FormatEngine.execute(
                request =
                    FormatRequest(
                        deviceId = "/dev/sdb",
                        partitionScheme = PartitionScheme.MBR,
                        filesystem = FilesystemType.FAT32,
                        volumeLabel = "USB_TEST",
                        quickFormat = true,
                    ),
                target = target,
                progressIntervalMs = 0L,
                clockMs = {
                    time += 5L
                    time
                },
                onEvent = events::add,
            )

        completed.verified shouldBe false
        target.closed shouldBe true
        backing[510] shouldBe 0x55.toByte()
        backing[511] shouldBe 0xAA.toByte()
        // Stale tail backup GPT signature area cleared
        backing[backing.size - 512] shouldBe 0.toByte()
        events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.PARTITIONING } shouldBe true
        events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.FORMATTING && it.bytesProcessed == 100L } shouldBe true
    }

    @Test
    fun zeroesAllSectorsOnFullWipeFormatsExfatInPureKotlinAndHonorsCancellation() {
        val diskBytes = 40L * 1024L * 1024L
        val backing = ByteArray(diskBytes.toInt()) { 0x7E.toByte() }
        val target = ByteArrayBlockTargetForEngine(backing, sectorSizeBytes = 512)
        val events = mutableListOf<WorkerEvent>()
        var time = 0L

        FormatEngine.execute(
            request =
                FormatRequest(
                    deviceId = "/dev/sdb",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.EXFAT,
                    volumeLabel = "EXUSB",
                    quickFormat = false,
                ),
            target = target,
            chunkSizeBytes = 4 * 1024 * 1024,
            progressIntervalMs = 0L,
            clockMs = {
                time += 10L
                time
            },
            onEvent = events::add,
        )

        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        val partStart = span.startByteOffset.toInt()
        backing.decodeToString(partStart + 3, partStart + 11) shouldBe "EXFAT   "
        target.closed shouldBe true
        events.any { it is WorkerEvent.Started && it.totalBytes == diskBytes } shouldBe true
        events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.WIPING && it.bytesProcessed == diskBytes } shouldBe true
        events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.FORMATTING && it.bytesProcessed == 100L } shouldBe true
        // Middle sectors zeroed before partitioning
        backing[20 * 1024 * 1024] shouldBe 0.toByte()

        var checks = 0
        val cancelTarget = ByteArrayBlockTargetForEngine(ByteArray(diskBytes.toInt()), sectorSizeBytes = 512)
        assertFailsWith<FlashStepException> {
            FormatEngine.execute(
                request =
                    FormatRequest(
                        deviceId = "/dev/sdb",
                        partitionScheme = PartitionScheme.GPT,
                        filesystem = FilesystemType.FAT32,
                        quickFormat = false,
                    ),
                target = cancelTarget,
                chunkSizeBytes = 1_048_576,
                clockMs = { 10L },
                shouldCancel = { ++checks > 3 },
                onEvent = {},
            )
        }.code shouldBe "CANCELLED"
        cancelTarget.closed shouldBe true
    }

    @Test
    fun formatsNtfsAndExt4UsingBuiltInFormatters() {
        val diskBytes = 48L * 1024L * 1024L
        val span = PartitionTableBuilder.computeSinglePartitionSpan(diskBytes, sectorSizeBytes = 512, scheme = PartitionScheme.GPT)
        val partStart = span.startByteOffset.toInt()

        val ntfsBacking = ByteArray(diskBytes.toInt())
        FormatEngine.execute(
            request =
                FormatRequest(
                    deviceId = "/dev/sdb",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.NTFS,
                    volumeLabel = "NTFS_PURE",
                    quickFormat = true,
                ),
            target = ByteArrayBlockTargetForEngine(ntfsBacking, sectorSizeBytes = 512),
            clockMs = { 10L },
            onEvent = {},
        )
        ntfsBacking.decodeToString(partStart + 3, partStart + 11) shouldBe "NTFS    "

        val ext4Backing = ByteArray(diskBytes.toInt())
        FormatEngine.execute(
            request =
                FormatRequest(
                    deviceId = "/dev/sdb",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.EXT4,
                    volumeLabel = "EXT4_PURE",
                    quickFormat = true,
                ),
            target = ByteArrayBlockTargetForEngine(ext4Backing, sectorSizeBytes = 512),
            clockMs = { 10L },
            onEvent = {},
        )
        readUInt16LeInt(ext4Backing, partStart + 1024 + 0x38) shouldBe 0xEF53
    }
}

private class ByteArrayBlockTargetForEngine(
    private val backing: ByteArray,
    override val sectorSizeBytes: Int,
) : BlockFlashTarget {
    override val capacityBytes: Long = backing.size.toLong()
    var closed: Boolean = false

    override fun unmountAndLock() = Unit

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        buffer.copyInto(backing, destinationOffset = offset.toInt(), startIndex = 0, endIndex = length)
    }

    override fun syncAndFlush() = Unit

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int = 0

    override fun close() {
        closed = true
    }
}
