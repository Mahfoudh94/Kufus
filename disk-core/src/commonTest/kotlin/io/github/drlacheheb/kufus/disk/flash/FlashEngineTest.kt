package io.github.drlacheheb.kufus.disk.flash

import io.github.drlacheheb.kufus.disk.io.BlockFlashTarget
import io.github.drlacheheb.kufus.disk.io.FlashImageSource
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.FlashPhase
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class FlashEngineTest {
    @Test
    fun writesAlignedChunksFlushesAndVerifiesWrittenData() {
        val sourceBytes = ByteArray(1500) { index -> ((index * 31 + 7) and 0xFF).toByte() }
        val eventTimeline = mutableListOf<String>()
        val target =
            FakeBlockFlashTarget(
                sectorSizeBytes = 512,
                capacityBytes = 8192L,
                onSync = { eventTimeline += "SYNC" },
            )
        val events = mutableListOf<WorkerEvent>()
        var timeMs = 100L

        val completed =
            FlashEngine.execute(
                source = ChunkedArraySource(sourceBytes, maxReadSize = 400, advertisedSize = sourceBytes.size.toLong()),
                target = target,
                verifyAfterWrite = true,
                chunkSizeBytes = 1024,
                progressIntervalMs = 0L,
                clockMs = {
                    timeMs += 50L
                    timeMs
                },
                onEvent = { event ->
                    events += event
                    if (event is WorkerEvent.Progress) {
                        eventTimeline += "${event.phase}:${event.bytesProcessed}/${event.totalBytes}"
                    }
                },
            )

        completed.verified shouldBe true
        target.unmounted shouldBe true
        target.synced shouldBe true
        target.closed shouldBe true
        target.writtenOffsets shouldContain 0L
        target.writtenOffsets shouldContain 1024L
        target.storage.copyOfRange(0, sourceBytes.size).contentEquals(sourceBytes) shouldBe true
        target.storage.copyOfRange(sourceBytes.size, 1536).all { it == 0.toByte() } shouldBe true
        events.first().shouldBeInstanceOf<WorkerEvent.Progress>().phase shouldBe FlashPhase.UNMOUNTING
        events.any { it is WorkerEvent.Started && it.totalBytes == 1500L } shouldBe true
        events.any { it is WorkerEvent.Progress && it.phase == FlashPhase.VERIFYING && it.bytesProcessed == 1500L } shouldBe true
        val syncIdx = eventTimeline.indexOf("SYNC")
        val finalWriteIdx = eventTimeline.indexOf("WRITING:1500/1500")
        val firstVerifyIdx = eventTimeline.indexOfFirst { it.startsWith("VERIFYING:") }
        (syncIdx in 0 until finalWriteIdx && finalWriteIdx < firstVerifyIdx) shouldBe true
    }

    @Test
    fun detectsVerificationCorruptionAfterWrite() {
        val sourceBytes = ByteArray(1024) { 0x42 }
        val target =
            FakeBlockFlashTarget(
                sectorSizeBytes = 512,
                capacityBytes = 4096L,
                corruptByteOnRead = 500,
            )

        val exception =
            shouldThrow<FlashStepException> {
                FlashEngine.execute(
                    source = ChunkedArraySource(sourceBytes, maxReadSize = 1024, advertisedSize = null),
                    target = target,
                    verifyAfterWrite = true,
                    chunkSizeBytes = 512,
                    clockMs = { 0L },
                    onEvent = {},
                )
            }

        exception.code shouldBe "VERIFY_FAILED"
        target.closed shouldBe true
    }

    @Test
    fun rejectsOversizedImageBeforeUnmountingAndSupportsCancellation() {
        val smallTarget = FakeBlockFlashTarget(sectorSizeBytes = 512, capacityBytes = 512L)
        val tooLarge =
            shouldThrow<FlashStepException> {
                FlashEngine.execute(
                    source = ChunkedArraySource(ByteArray(1024), maxReadSize = 512, advertisedSize = 1024L),
                    target = smallTarget,
                    clockMs = { 0L },
                    onEvent = {},
                )
            }
        tooLarge.code shouldBe "IMAGE_TOO_LARGE"
        smallTarget.unmounted shouldBe false

        val cancelTarget = FakeBlockFlashTarget(sectorSizeBytes = 512, capacityBytes = 4096L)
        var calls = 0
        val cancelled =
            shouldThrow<FlashStepException> {
                FlashEngine.execute(
                    source = ChunkedArraySource(ByteArray(2048), maxReadSize = 512, advertisedSize = 2048L),
                    target = cancelTarget,
                    chunkSizeBytes = 512,
                    clockMs = { 0L },
                    shouldCancel = { ++calls >= 3 },
                    onEvent = {},
                )
            }
        cancelled.code shouldBe "CANCELLED"
    }

    @Test
    fun keepsTotalBytesZeroDuringWriteWhenUncompressedSizeIsUnknown() {
        val sourceBytes = ByteArray(2048) { 0x19 }
        val target = FakeBlockFlashTarget(sectorSizeBytes = 512, capacityBytes = 4096L)
        val events = mutableListOf<WorkerEvent>()
        var timeMs = 0L

        FlashEngine.execute(
            source = ChunkedArraySource(sourceBytes, maxReadSize = 512, advertisedSize = null),
            target = target,
            verifyAfterWrite = false,
            chunkSizeBytes = 512,
            progressIntervalMs = 0L,
            clockMs = {
                timeMs += 50L
                timeMs
            },
            onEvent = events::add,
        )

        val writingEvents =
            events
                .filterIsInstance<WorkerEvent.Progress>()
                .filter { it.phase == FlashPhase.WRITING }
        writingEvents.dropLast(1).all { it.totalBytes == 0L } shouldBe true
        writingEvents.last().totalBytes shouldBe 2048L
    }

    @Test
    fun validatesUnsupportedImageFormatsUniformly() {
        unsupportedFlashReason(ImageInfo("/a.iso", 1024L, ImageFormat.HYBRID_ISO)) shouldBe null
        unsupportedFlashReason(ImageInfo("/a.img", 1024L, ImageFormat.RAW_IMAGE)) shouldBe null
        unsupportedFlashReason(
            ImageInfo("/a.gz", 1024L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.GZIP),
        ) shouldBe null
        unsupportedFlashReason(
            ImageInfo("/a.xz", 1024L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.XZ),
        ) shouldBe null
        unsupportedFlashReason(
            ImageInfo("/a.zst", 1024L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.ZSTD),
        ) shouldBe null
        unsupportedFlashReason(ImageInfo("/win.iso", 1024L, ImageFormat.WINDOWS_ISO)) shouldBe
            "Windows ISO requires UEFI/NTFS installer preparation rather than raw byte writing."
        unsupportedFlashReason(ImageInfo("/bad.bin", 1024L, ImageFormat.UNKNOWN)) shouldBe
            "Unrecognized image format — choose a valid bootable ISO, raw IMG, or compressed (.gz/.xz/.zst) image."
    }

    @Test
    fun streamsMultiChunkDataThroughDoubleBufferedPipelineAndPropagatesProducerErrors() {
        val sourceBytes = ByteArray(10_000) { index -> ((index * 73 + 19) and 0xFF).toByte() }
        val target = FakeBlockFlashTarget(sectorSizeBytes = 512, capacityBytes = 16_384L)
        var directOffsetReads = 0
        val source =
            object : FlashImageSource {
                override val knownUncompressedSizeBytes: Long = sourceBytes.size.toLong()
                private var offset = 0

                override fun readChunk(buffer: ByteArray): Int = readChunk(buffer, 0, buffer.size)

                override fun readChunk(
                    buffer: ByteArray,
                    offset: Int,
                    maxLength: Int,
                ): Int {
                    if (this.offset >= sourceBytes.size) return 0
                    directOffsetReads++
                    val count = minOf(maxLength, 333, sourceBytes.size - this.offset)
                    sourceBytes.copyInto(buffer, destinationOffset = offset, startIndex = this.offset, endIndex = this.offset + count)
                    this.offset += count
                    return count
                }

                override fun close() = Unit
            }

        val completed =
            FlashEngine.execute(
                source = source,
                target = target,
                verifyAfterWrite = true,
                chunkSizeBytes = 1024,
                clockMs = { 10L },
                onEvent = {},
            )

        completed.verified shouldBe true
        (directOffsetReads > 10) shouldBe true
        target.storage.copyOfRange(0, sourceBytes.size).contentEquals(sourceBytes) shouldBe true

        val failingSource =
            object : FlashImageSource {
                override val knownUncompressedSizeBytes: Long = 4096L
                private var calls = 0

                override fun readChunk(buffer: ByteArray): Int {
                    if (++calls >= 2) throw FlashStepException("CORRUPT_ZSTD", "bad frame")
                    buffer.fill(0x5A)
                    return buffer.size
                }

                override fun close() = Unit
            }
        val failureTarget = FakeBlockFlashTarget(sectorSizeBytes = 512, capacityBytes = 8192L)
        val err =
            shouldThrow<FlashStepException> {
                FlashEngine.execute(
                    source = failingSource,
                    target = failureTarget,
                    chunkSizeBytes = 1024,
                    clockMs = { 0L },
                    onEvent = {},
                )
            }
        err.code shouldBe "CORRUPT_ZSTD"
        failureTarget.closed shouldBe true
    }
}

private class ChunkedArraySource(
    private val data: ByteArray,
    private val maxReadSize: Int,
    advertisedSize: Long?,
) : FlashImageSource {
    override val knownUncompressedSizeBytes: Long? = advertisedSize
    private var offset = 0

    override fun readChunk(buffer: ByteArray): Int {
        if (offset >= data.size) return 0
        val count = minOf(buffer.size, maxReadSize, data.size - offset)
        data.copyInto(buffer, destinationOffset = 0, startIndex = offset, endIndex = offset + count)
        offset += count
        return count
    }

    override fun close() = Unit
}

private class FakeBlockFlashTarget(
    override val sectorSizeBytes: Int,
    override val capacityBytes: Long,
    private val corruptByteOnRead: Int? = null,
    private val onSync: () -> Unit = {},
) : BlockFlashTarget {
    val storage = ByteArray(capacityBytes.toInt())
    val writtenOffsets = mutableListOf<Long>()
    var unmounted = false
    var synced = false
    var closed = false

    override fun unmountAndLock() {
        unmounted = true
    }

    override fun writeAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ) {
        require(length % sectorSizeBytes == 0) { "Write length $length is not sector-aligned" }
        writtenOffsets += offset
        buffer.copyInto(storage, destinationOffset = offset.toInt(), startIndex = 0, endIndex = length)
    }

    override fun syncAndFlush() {
        synced = true
        onSync()
    }

    override fun readAt(
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        val count = minOf(length, storage.size - offset.toInt())
        storage.copyInto(buffer, destinationOffset = 0, startIndex = offset.toInt(), endIndex = offset.toInt() + count)
        if (corruptByteOnRead != null && corruptByteOnRead in 0 until count) {
            buffer[corruptByteOnRead] = (buffer[corruptByteOnRead].toInt() xor 0xFF).toByte()
        }
        return count
    }

    override fun close() {
        closed = true
    }
}
