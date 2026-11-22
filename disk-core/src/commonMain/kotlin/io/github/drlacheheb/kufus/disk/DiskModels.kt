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

fun filterSafeUsbDrives(drives: List<UsbDrive>): List<UsbDrive> =
    drives.filter { drive ->
        drive.isRemovable && !drive.isSystemDisk && drive.sizeBytes > 0L
    }

fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1000.0 && unitIndex < units.lastIndex) {
        value /= 1000.0
        unitIndex++
    }
    if (unitIndex == 0) return "$bytes B"
    val roundedTenths = (value * 10.0 + 0.5).toLong()
    val whole = roundedTenths / 10L
    val decimal = roundedTenths % 10L
    return "$whole.$decimal ${units[unitIndex]}"
}
