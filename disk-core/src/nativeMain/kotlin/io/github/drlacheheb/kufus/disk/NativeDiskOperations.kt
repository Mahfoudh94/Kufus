package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.fflush

interface CloseableSeekableReader : SeekableByteReader {
    fun close()
}

expect fun listPlatformUsbDrives(): List<UsbDrive>

expect fun watchPlatformUsbDrives(
    onDrivesUpdated: (List<UsbDrive>) -> Unit,
    shouldContinue: () -> Boolean = { true },
)

expect fun launchPlatformElevatedProcess(
    executablePath: String,
    arguments: List<String>,
): Int

expect fun openPlatformSeekableReader(path: String): Result<CloseableSeekableReader>

expect fun inspectPlatformImage(path: String): Result<ImageInfo>

expect fun ejectPlatformDevice(devicePath: String): Result<Unit>

expect fun currentNativeHostOs(): HostOsKind

expect fun formatPlatformPartitionFilesystem(
    devicePath: String,
    partitionIndex: Int,
    request: FormatRequest,
    onProgress: (Long, Long) -> Unit,
): Result<Unit>

expect fun mountPlatformFormattedPartition(
    devicePath: String,
    partitionIndex: Int,
    filesystem: FilesystemType,
): Result<MountedPartitionHandle>

expect fun finalizePlatformFormattedDisk(
    devicePath: String,
    request: FormatRequest,
)

@OptIn(ExperimentalForeignApi::class)
fun flushNativeStdout() {
    fflush(null)
}
