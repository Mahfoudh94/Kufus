package io.github.drlacheheb.kufus.disk

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.free
import kotlinx.cinterop.nativeHeap
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.usePinned
import platform.posix.memset
import platform.zlib.Z_BUF_ERROR
import platform.zlib.Z_NO_FLUSH
import platform.zlib.Z_OK
import platform.zlib.Z_STREAM_END
import platform.zlib.inflate
import platform.zlib.inflateEnd
import platform.zlib.inflateInit2
import platform.zlib.inflateReset
import platform.zlib.z_stream
import kotlin.time.TimeSource

private const val GZIP_WINDOW_BITS = 31
private const val GZIP_INPUT_BUFFER_BYTES = 262_144

/**
 * Sequential platform file reader backing raw and compressed flash image sources.
 */
interface NativeStreamHandle {
    fun readBytes(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int

    fun close()
}

expect fun openPlatformReadableFile(path: String): Result<NativeStreamHandle>

expect fun openPlatformFlashTarget(
    devicePath: String,
    expectedCapacityBytes: Long,
    rescanPartitionsOnClose: Boolean = true,
): Result<BlockFlashTarget>

/**
 * Executes an end-to-end native flash job with USB safety re-verification and NDJSON progress events.
 */
fun executePlatformFlash(
    request: FlashRequest,
    shouldCancel: () -> Boolean = { false },
    onEvent: (WorkerEvent) -> Unit,
): Result<WorkerEvent.Completed> =
    runCatching {
        val safeDrive = verifySafeFlashTargetDevice(request.deviceId)
        val imageInfo = inspectPlatformImage(request.imagePath).getOrThrow()
        val unsupportedReason = unsupportedFlashReason(imageInfo)
        if (unsupportedReason != null) {
            throw FlashStepException(code = "UNSUPPORTED_IMAGE", message = unsupportedReason)
        }
        val source = openFlashImageSource(request.imagePath, imageInfo)
        val target =
            try {
                openPlatformFlashTarget(
                    devicePath = safeDrive.id,
                    expectedCapacityBytes = safeDrive.sizeBytes,
                    rescanPartitionsOnClose = !request.ejectAfterWrite,
                ).getOrThrow()
            } catch (error: Throwable) {
                runCatching { source.close() }
                throw error
            }
        val startMark = TimeSource.Monotonic.markNow()
        val flashCompleted =
            FlashEngine.execute(
                source = source,
                target = target,
                verifyAfterWrite = request.verifyAfterWrite,
                clockMs = { startMark.elapsedNow().inWholeMilliseconds },
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )
        if (!request.ejectAfterWrite) {
            return@runCatching flashCompleted
        }
        performOptionalPostEject(
            deviceId = safeDrive.id,
            shouldCancel = shouldCancel,
            elapsedBaseMs = flashCompleted.elapsedMs,
            verified = flashCompleted.verified,
            startMark = startMark,
            onEvent = onEvent,
        )
    }

/**
 * Executes an end-to-end native partition + format job with USB safety re-verification and NDJSON progress events.
 */
fun executePlatformFormat(
    request: FormatRequest,
    shouldCancel: () -> Boolean = { false },
    onEvent: (WorkerEvent) -> Unit,
): Result<WorkerEvent.Completed> =
    runCatching {
        val safeDrive = verifySafeFlashTargetDevice(request.deviceId)
        val hostOs = currentNativeHostOs()
        if (!isFilesystemSupportedOnHost(request.filesystem, hostOs)) {
            throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "${filesystemDisplayName(request.filesystem)} formatting is not supported on ${hostOs.name.lowercase()}",
            )
        }
        val target =
            openPlatformFlashTarget(
                devicePath = safeDrive.id,
                expectedCapacityBytes = safeDrive.sizeBytes,
                rescanPartitionsOnClose = true,
            ).getOrThrow()
        val startMark = TimeSource.Monotonic.markNow()
        val formatCompleted =
            FormatEngine.execute(
                request = request,
                target = target,
                clockMs = { startMark.elapsedNow().inWholeMilliseconds },
                shouldCancel = shouldCancel,
                onEvent = onEvent,
            )
        if (!request.ejectAfterFormat) {
            finalizePlatformFormattedDisk(safeDrive.id, request)
            return@runCatching formatCompleted
        }
        performOptionalPostEject(
            deviceId = safeDrive.id,
            shouldCancel = shouldCancel,
            elapsedBaseMs = formatCompleted.elapsedMs,
            verified = false,
            startMark = startMark,
            onEvent = onEvent,
        )
    }

/**
 * Executes a UEFI Windows installer ISO preparation workflow with safety verification and NDJSON progress.
 */
fun executePlatformWindowsFlash(
    request: WindowsFlashRequest,
    shouldCancel: () -> Boolean = { false },
    onEvent: (WorkerEvent) -> Unit,
): Result<WorkerEvent.Completed> =
    runCatching {
        val safeDrive = verifySafeFlashTargetDevice(request.deviceId)
        val hostOs = currentNativeHostOs()
        if (request.filesystem !in supportedWindowsFilesystemsForHost(hostOs)) {
            throw FlashStepException(
                code = "UNSUPPORTED_FILESYSTEM",
                message = "${filesystemDisplayName(request.filesystem)} Windows install is unsupported on ${hostOs.name.lowercase()}.",
            )
        }
        val uefiNtfsBytes =
            if (request.filesystem == FilesystemType.NTFS) {
                loadVerifiedUefiNtfsAsset(request.uefiNtfsImagePath)
            } else {
                null
            }
        runWindowsInstallerPipeline(request, safeDrive, uefiNtfsBytes, shouldCancel, onEvent)
    }

private fun runWindowsInstallerPipeline(
    request: WindowsFlashRequest,
    safeDrive: UsbDrive,
    uefiNtfsBytes: ByteArray?,
    shouldCancel: () -> Boolean,
    onEvent: (WorkerEvent) -> Unit,
): WorkerEvent.Completed {
    val isoReader = openPlatformSeekableReader(request.imagePath).getOrThrow()
    try {
        val target =
            openPlatformFlashTarget(
                devicePath = safeDrive.id,
                expectedCapacityBytes = safeDrive.sizeBytes,
                rescanPartitionsOnClose = true,
            ).getOrThrow()
        val startMark = TimeSource.Monotonic.markNow()
        val completed =
            WindowsInstallerEngine.execute(
                request = request,
                isoReader = isoReader,
                target = target,
                uefiNtfsBytes = uefiNtfsBytes,
                clockMs = { startMark.elapsedNow().inWholeMilliseconds },
                shouldCancel = shouldCancel,
                onEvent = onEvent,
                mountPartition = { partitionIndex, filesystem ->
                    mountPlatformFormattedPartition(safeDrive.id, partitionIndex, filesystem).getOrThrow()
                },
            )
        if (!request.ejectAfterWrite) {
            finalizePlatformFormattedDisk(
                safeDrive.id,
                FormatRequest(deviceId = safeDrive.id, partitionScheme = request.partitionScheme, filesystem = request.filesystem),
            )
            return completed
        }
        return performOptionalPostEject(safeDrive.id, shouldCancel, completed.elapsedMs, false, startMark, onEvent)
    } finally {
        runCatching { isoReader.close() }
    }
}

private fun loadVerifiedUefiNtfsAsset(explicitPath: String?): ByteArray {
    val candidate =
        listOfNotNull(
            explicitPath?.takeIf { it.isNotBlank() },
            "packaging/uefi/uefi-ntfs.img",
            "../packaging/uefi/uefi-ntfs.img",
            "uefi-ntfs.img",
        ).firstNotNullOfOrNull { path ->
            openPlatformSeekableReader(path).getOrNull()?.let { reader -> path to reader }
        } ?: throw FlashStepException(
            code = "MISSING_ASSET",
            message = "Required UEFI:NTFS bootloader image ('uefi-ntfs.img') was not found.",
        )

    val (_, reader) = candidate
    return try {
        val size = reader.sizeBytes().coerceIn(0L, 2_097_152L).toInt()
        val bytes = reader.readAt(0L, size)
        WindowsInstallerEngine.verifyUefiNtfsBytes(bytes)
        bytes
    } finally {
        runCatching { reader.close() }
    }
}

private fun performOptionalPostEject(
    deviceId: String,
    shouldCancel: () -> Boolean,
    elapsedBaseMs: Long,
    verified: Boolean,
    startMark: kotlin.time.TimeSource.Monotonic.ValueTimeMark,
    onEvent: (WorkerEvent) -> Unit,
): WorkerEvent.Completed {
    if (shouldCancel()) {
        throw FlashStepException(code = "CANCELLED", message = "Operation cancelled by user")
    }
    onEvent(WorkerEvent.Progress(FlashPhase.EJECTING, 0L, 0L, 0L))
    ejectPlatformDevice(deviceId).getOrElse { error ->
        throw (error as? FlashStepException)
            ?: FlashStepException(code = "EJECT_FAILED", message = error.message ?: "Failed to eject '$deviceId'")
    }
    return WorkerEvent.Completed(
        verified = verified,
        ejected = true,
        elapsedMs = startMark.elapsedNow().inWholeMilliseconds.coerceAtLeast(elapsedBaseMs),
    )
}

/**
 * Verifies [deviceId] is a safe USB drive and performs a native OS eject/detach.
 */
fun executePlatformEject(deviceId: String): Result<Unit> =
    runCatching {
        val safeDrive = verifySafeFlashTargetDevice(deviceId)
        ejectPlatformDevice(safeDrive.id).getOrThrow()
    }

internal fun verifySafeFlashTargetDevice(deviceId: String): UsbDrive {
    if (deviceId.isBlank()) {
        throw FlashStepException(code = "INVALID_ARGS", message = "Target device path cannot be blank")
    }
    val safeDrives = filterSafeUsbDrives(listPlatformUsbDrives())
    return safeDrives.firstOrNull { it.id == deviceId }
        ?: throw FlashStepException(
            code = "UNSAFE_DEVICE",
            message = "Target '$deviceId' is missing, internal, or not a safe external USB drive",
        )
}

fun openFlashImageSource(
    imagePath: String,
    imageInfo: ImageInfo,
): FlashImageSource {
    val handle = openPlatformReadableFile(imagePath).getOrThrow()
    return try {
        when (imageInfo.compression) {
            CompressionFormat.NONE -> RawFileImageStreamSource(handle, imageInfo.sizeBytes)
            CompressionFormat.GZIP -> GzipImageStreamSource(handle, imageInfo.uncompressedSizeHintBytes)
            CompressionFormat.XZ -> XzImageStreamSource(NativeStreamingByteSource(handle), imageInfo.uncompressedSizeHintBytes)
            CompressionFormat.ZSTD -> ZstdImageStreamSource(NativeStreamingByteSource(handle), imageInfo.uncompressedSizeHintBytes)
        }
    } catch (error: Throwable) {
        runCatching { handle.close() }
        throw error
    }
}

internal class NativeStreamingByteSource(
    private val handle: NativeStreamHandle,
) : StreamingByteSource {
    override fun readBytes(
        buffer: ByteArray,
        offset: Int,
        maxLength: Int,
    ): Int {
        if (maxLength <= 0) return 0
        if (offset == 0) return handle.readBytes(buffer, offset = 0, maxLength = maxLength)
        val scratch = ByteArray(maxLength)
        val read = handle.readBytes(scratch, offset = 0, maxLength = maxLength)
        if (read > 0) scratch.copyInto(buffer, destinationOffset = offset, startIndex = 0, endIndex = read)
        return read
    }

    override fun close() {
        handle.close()
    }
}

internal class RawFileImageStreamSource(
    private val handle: NativeStreamHandle,
    override val knownUncompressedSizeBytes: Long?,
) : FlashImageSource {
    override fun readChunk(buffer: ByteArray): Int = handle.readBytes(buffer, offset = 0, maxLength = buffer.size)

    override fun close() {
        handle.close()
    }
}

@OptIn(ExperimentalForeignApi::class)
internal class GzipImageStreamSource(
    private val handle: NativeStreamHandle,
    override val knownUncompressedSizeBytes: Long?,
) : FlashImageSource {
    private val inBuffer = ByteArray(GZIP_INPUT_BUFFER_BYTES)
    private var inOffset = 0
    private var inAvailable = 0
    private var eof = false
    private var streamEnded = false
    private var closed = false
    private val stream = nativeHeap.alloc<z_stream>()

    init {
        memset(stream.ptr, 0, sizeOf<z_stream>().toULong())
        val initStatus = inflateInit2(stream.ptr, GZIP_WINDOW_BITS)
        if (initStatus != Z_OK) {
            nativeHeap.free(stream)
            throw FlashStepException(
                code = "INVALID_GZIP",
                message = "Failed to initialize zlib gzip decoder (status $initStatus)",
            )
        }
    }

    override fun readChunk(buffer: ByteArray): Int {
        if (closed || streamEnded || buffer.isEmpty()) return 0
        while (!streamEnded) {
            refillInputIfNeeded()
            val produced = inflateStep(buffer)
            if (produced > 0) return produced
        }
        return 0
    }

    override fun close() {
        if (closed) return
        closed = true
        inflateEnd(stream.ptr)
        nativeHeap.free(stream)
        handle.close()
    }

    private fun refillInputIfNeeded() {
        if (inAvailable > 0 || eof) return
        val read = handle.readBytes(inBuffer, offset = 0, maxLength = inBuffer.size)
        if (read <= 0) {
            eof = true
        } else {
            inOffset = 0
            inAvailable = read
        }
    }

    private fun inflateStep(outBuffer: ByteArray): Int =
        inBuffer.usePinned { inPinned ->
            outBuffer.usePinned { outPinned ->
                stream.next_in =
                    if (inAvailable > 0) {
                        inPinned.addressOf(inOffset).reinterpret<UByteVar>()
                    } else {
                        null
                    }
                stream.avail_in = inAvailable.toUInt()
                stream.next_out = outPinned.addressOf(0).reinterpret<UByteVar>()
                stream.avail_out = outBuffer.size.toUInt()

                val status = inflate(stream.ptr, Z_NO_FLUSH)
                val consumed = inAvailable - stream.avail_in.toInt()
                inOffset += consumed
                inAvailable -= consumed
                val produced = outBuffer.size - stream.avail_out.toInt()
                handleInflateStatus(status, produced)
                produced
            }
        }

    private fun handleInflateStatus(
        status: Int,
        produced: Int,
    ) {
        when (status) {
            Z_STREAM_END -> handleMemberEnd()
            Z_OK -> Unit
            Z_BUF_ERROR -> {
                if (inAvailable == 0 && eof && produced == 0) {
                    throw FlashStepException(
                        code = "CORRUPT_GZIP",
                        message = "Unexpected end of compressed GZIP stream",
                    )
                }
            }
            else ->
                throw FlashStepException(
                    code = "CORRUPT_GZIP",
                    message = "GZIP decompression failed (zlib status $status)",
                )
        }
    }

    private fun handleMemberEnd() {
        refillInputIfNeeded()
        if (inAvailable > 0) {
            val resetStatus = inflateReset(stream.ptr)
            if (resetStatus != Z_OK) {
                throw FlashStepException(
                    code = "CORRUPT_GZIP",
                    message = "Failed to reset zlib stream for concatenated GZIP member (status $resetStatus)",
                )
            }
        } else {
            streamEnded = true
        }
    }
}
