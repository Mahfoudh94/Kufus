package io.github.drlacheheb.kufus.disk

/**
 * Formatting parameters passed to a [PartitionFormatter].
 *
 * [requestedClusterBytes] is an optional allocation-unit hint; fixed-block filesystems (such as ext4)
 * may ignore it in favor of their canonical block size.
 */
data class PartitionFormatOptions(
    val volumeLabel: String = DEFAULT_FORMAT_VOLUME_LABEL,
    val requestedClusterBytes: Int? = null,
)

/**
 * Strategy contract for initializing a [filesystem] inside a [PartitionSpan] using only sector-aligned writes.
 */
interface PartitionFormatter {
    val filesystem: FilesystemType

    fun format(
        writer: BlockWriter,
        span: PartitionSpan,
        options: PartitionFormatOptions,
        shouldCancel: () -> Boolean = { false },
    )
}

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
