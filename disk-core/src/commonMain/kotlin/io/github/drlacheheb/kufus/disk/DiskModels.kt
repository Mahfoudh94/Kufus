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
    UNKNOWN,
}

@Serializable
data class ImageInfo(
    val path: String,
    val sizeBytes: Long,
    val format: ImageFormat,
)

@Serializable
enum class FlashPhase {
    UNMOUNTING,
    WRITING,
    VERIFYING,
}

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
    ) : WorkerEvent

    @Serializable
    @SerialName("error")
    data class Error(
        val code: String,
        val message: String,
    ) : WorkerEvent
}
