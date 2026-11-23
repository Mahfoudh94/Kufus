package io.github.drlacheheb.kufus.disk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UsbDrive(
    val id: String,
    val name: String,
    val model: String,
    val sizeBytes: Long,
    val serial: String? = null,
    val mountPoints: List<String> = emptyList(),
    val isRemovable: Boolean = true,
    val isSystemDisk: Boolean = false,
)

@Serializable
enum class ImageFormat {
    RAW_IMAGE,
    HYBRID_ISO,
    WINDOWS_ISO,
    COMPRESSED_IMAGE,
    UNKNOWN,
}

@Serializable
enum class CompressionFormat {
    NONE,
    GZIP,
    XZ,
    ZSTD,
}

@Serializable
data class ImageInfo(
    val path: String,
    val sizeBytes: Long,
    val format: ImageFormat,
    val compression: CompressionFormat = CompressionFormat.NONE,
    val uncompressedSizeHintBytes: Long? = null,
)

@Serializable
enum class FlashPhase {
    UNMOUNTING,
    WIPING,
    PARTITIONING,
    WRITING,
    FORMATTING,
    VERIFYING,
    EJECTING,
}

@Serializable
enum class PartitionScheme {
    GPT,
    MBR,
}

@Serializable
enum class FilesystemType {
    FAT32,
    EXFAT,
    NTFS,
    EXT4,
}

@Serializable
enum class HostOsKind {
    LINUX,
    WINDOWS,
    MACOS,
}

data class FormatRequest(
    val deviceId: String,
    val partitionScheme: PartitionScheme = PartitionScheme.GPT,
    val filesystem: FilesystemType = FilesystemType.FAT32,
    val volumeLabel: String = "",
    val clusterSizeBytes: Int? = null,
    val quickFormat: Boolean = true,
    val ejectAfterFormat: Boolean = false,
    val eventLogPath: String? = null,
)

@Serializable
sealed interface WorkerEvent {
    @Serializable
    @SerialName("drives_listed")
    data class DrivesListed(
        val drives: List<UsbDrive>,
    ) : WorkerEvent

    @Serializable
    @SerialName("image_inspected")
    data class ImageInspected(
        val info: ImageInfo,
    ) : WorkerEvent

    @Serializable
    @SerialName("started")
    data class Started(
        val totalBytes: Long,
    ) : WorkerEvent

    @Serializable
    @SerialName("progress")
    data class Progress(
        val phase: FlashPhase,
        val bytesProcessed: Long,
        val totalBytes: Long,
        val speedBytesPerSec: Long,
    ) : WorkerEvent

    @Serializable
    @SerialName("completed")
    data class Completed(
        val verified: Boolean,
        val elapsedMs: Long,
        val ejected: Boolean = false,
    ) : WorkerEvent

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
    ) : WorkerEvent
}
