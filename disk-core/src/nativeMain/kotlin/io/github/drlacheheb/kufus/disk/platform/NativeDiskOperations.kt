package io.github.drlacheheb.kufus.disk.platform

import io.github.drlacheheb.kufus.disk.io.SeekableByteReader
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.FormatRequest
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.windows.MountedPartitionHandle
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
