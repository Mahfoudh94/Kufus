package io.github.drlacheheb.kufus.disk

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

const val FAT32_MAX_FILE_BYTES: Long = 4_294_967_295L

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
data class WindowsIsoMetadata(
    val volumeLabel: String = "",
    val installImagePath: String = "",
    val installImageSizeBytes: Long = 0L,
    val largestFileBytes: Long = 0L,
    val totalPayloadBytes: Long = 0L,
    val isSplitRequiredForFat32: Boolean = false,
    val isWimSplittable: Boolean = false,
    val hasExistingUnattendXml: Boolean = false,
)

@Serializable
data class ImageInfo(
    val path: String,
    val sizeBytes: Long,
    val format: ImageFormat,
    val compression: CompressionFormat = CompressionFormat.NONE,
    val uncompressedSizeHintBytes: Long? = null,
    val windowsMetadata: WindowsIsoMetadata? = null,
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

@Serializable
data class WindowsUserExperienceOptions(
    val bypassSystemRequirements: Boolean = true,
    val bypassMicrosoftAccount: Boolean = true,
    val disableDataCollection: Boolean = true,
    val localAccountUsername: String? = null,
    val regionalLocaleTag: String? = null,
)

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

data class WindowsFlashRequest(
    val imagePath: String,
    val deviceId: String,
    val partitionScheme: PartitionScheme = PartitionScheme.GPT,
    val filesystem: FilesystemType = FilesystemType.NTFS,
    val volumeLabel: String = "",
    val clusterSizeBytes: Int? = null,
    val quickFormat: Boolean = true,
    val ejectAfterWrite: Boolean = true,
    val userExperience: WindowsUserExperienceOptions = WindowsUserExperienceOptions(),
    val uefiNtfsImagePath: String? = null,
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
