package io.github.drlacheheb.kufus.disk.format

import io.github.drlacheheb.kufus.disk.format.core.PartitionFormatOptions
import io.github.drlacheheb.kufus.disk.format.core.PartitionFormatter
import io.github.drlacheheb.kufus.disk.format.exfat.ExfatFormatter
import io.github.drlacheheb.kufus.disk.format.ext4.Ext4Formatter
import io.github.drlacheheb.kufus.disk.format.fat32.Fat32Formatter
import io.github.drlacheheb.kufus.disk.format.ntfs.NtfsFormatter
import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.partition.PartitionSpan

/**
 * Registry mapping [FilesystemType] values to [PartitionFormatter] implementations so high-level
 * engines remain decoupled from concrete filesystem formatters.
 */
class PartitionFormatterRegistry(
    formatters: List<PartitionFormatter> = defaultFormatters(),
) {
    private val byFilesystem: Map<FilesystemType, PartitionFormatter> =
        formatters.associateBy(PartitionFormatter::filesystem)

    fun formatterFor(filesystem: FilesystemType): PartitionFormatter =
        byFilesystem[filesystem]
            ?: throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "No built-in formatter registered for $filesystem.",
            )

    fun format(
        filesystem: FilesystemType,
        writer: BlockWriter,
        span: PartitionSpan,
        options: PartitionFormatOptions,
        shouldCancel: () -> Boolean = { false },
    ) {
        formatterFor(filesystem).format(writer, span, options, shouldCancel)
    }

    companion object {
        fun defaultFormatters(): List<PartitionFormatter> =
            listOf(
                Fat32Formatter,
                ExfatFormatter,
                NtfsFormatter,
                Ext4Formatter,
            )

        val Default: PartitionFormatterRegistry = PartitionFormatterRegistry()
    }
}
