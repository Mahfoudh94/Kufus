package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith

class PartitionFormatterRegistryTest {
    @Test
    fun resolvesAllBuiltInFilesystemsByDefault() {
        FilesystemType.entries.forEach { fs ->
            PartitionFormatterRegistry.Default.formatterFor(fs).filesystem shouldBe fs
        }
    }

    @Test
    fun injectsCustomFormatterIntoFormatEngineWithoutTouchingConcreteFormatters() {
        var capturedOptions: PartitionFormatOptions? = null
        var capturedSpan: PartitionSpan? = null
        val customFormatter =
            object : PartitionFormatter {
                override val filesystem: FilesystemType = FilesystemType.NTFS

                override fun format(
                    writer: BlockWriter,
                    span: PartitionSpan,
                    options: PartitionFormatOptions,
                    shouldCancel: () -> Boolean,
                ) {
                    capturedSpan = span
                    capturedOptions = options
                    writer.writeAt(span.startByteOffset, ByteArray(writer.sectorSizeBytes) { 0x42 }, writer.sectorSizeBytes)
                }
            }
        val registry = PartitionFormatterRegistry(listOf(customFormatter))
        val diskBytes = 8L * 1024L * 1024L
        val backing = ByteArray(diskBytes.toInt())
        val target = StubFlashTarget(backing, sectorSizeBytes = 512)

        FormatEngine.execute(
            request =
                FormatRequest(
                    deviceId = "/dev/sdb",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.NTFS,
                    volumeLabel = "INJECTED",
                    clusterSizeBytes = 16_384,
                    quickFormat = true,
                ),
            target = target,
            clockMs = { 10L },
            onEvent = {},
            formatterRegistry = registry,
        )

        capturedOptions shouldBe PartitionFormatOptions(volumeLabel = "INJECTED", requestedClusterBytes = 16_384)
        backing[checkNotNull(capturedSpan).startByteOffset.toInt()] shouldBe 0x42.toByte()

        assertFailsWith<FlashStepException> {
            registry.formatterFor(FilesystemType.EXT4)
        }.code shouldBe "UNSUPPORTED_FILESYSTEM"
    }
}

private class StubFlashTarget(
    private val backing: ByteArray,
    override val sectorSizeBytes: Int,
) : BlockFlashTarget {
    override val capacityBytes: Long = backing.size.toLong()

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

    override fun close() = Unit
}
