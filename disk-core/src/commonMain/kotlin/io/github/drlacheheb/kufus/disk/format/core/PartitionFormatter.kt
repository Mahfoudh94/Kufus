package io.github.drlacheheb.kufus.disk.format.core

import io.github.drlacheheb.kufus.disk.io.BlockWriter
import io.github.drlacheheb.kufus.disk.model.DEFAULT_FORMAT_VOLUME_LABEL
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.partition.PartitionSpan

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
