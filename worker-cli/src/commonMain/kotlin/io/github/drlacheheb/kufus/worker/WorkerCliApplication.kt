package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.FlashStepException
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.WorkerEvent
import kotlinx.cinterop.ExperimentalForeignApi
import platform.posix.FILE
import platform.posix.fclose
import platform.posix.fflush
import platform.posix.fopen
import platform.posix.fputs

private const val WORKER_VERSION = "kufus-worker 1.0.0"

fun interface WorkerCommandHandler {
    fun execute(args: Array<String>): Int
}

fun interface WorkerEventSink {
    fun writeLine(line: String)

    fun close() = Unit
}

class WorkerCliApplication(
    private val driver: NativeDiskDriver,
    private val printer: (String) -> Unit = ::println,
    private val eventLogSinkFactory: (String) -> WorkerEventSink = ::openPosixEventLogSink,
) {
    private val handlers: Map<String, WorkerCommandHandler> =
        mapOf(
            "--list" to WorkerCommandHandler { handleList() },
            "--watch" to WorkerCommandHandler { handleWatch() },
            "--inspect" to WorkerCommandHandler { commandArgs -> handleInspect(commandArgs) },
            "--flash" to WorkerCommandHandler { commandArgs -> handleFlash(commandArgs) },
            "--format" to WorkerCommandHandler { commandArgs -> handleFormat(commandArgs) },
            "--eject" to WorkerCommandHandler { commandArgs -> handleEject(commandArgs) },
            "--version" to WorkerCommandHandler { handleVersion() },
        )

    fun run(args: Array<String>): Int {
        val flag = args.firstOrNull()
        val handler = handlers[flag]
        if (handler != null) return handler.execute(args)
        emitEvent(
            WorkerEvent.Error(
                code = "INVALID_ARGS",
                message = "Supported flags: ${handlers.keys.joinToString(", ")}",
            ),
        )
        return 1
    }

    private fun handleList(): Int {
        val drives = driver.listUsbDrives()
        emitEvent(WorkerEvent.DrivesListed(drives))
        return 0
    }

    private fun handleWatch(): Int {
        driver.watchUsbDrives(
            onDrivesUpdated = { drives -> emitEvent(WorkerEvent.DrivesListed(drives)) },
        )
        return 0
    }

    private fun handleInspect(args: Array<String>): Int {
        val imagePath = args.getOrNull(1)?.takeIf { it.isNotBlank() }
        if (args.size != 2 || imagePath == null) {
            emitEvent(
                WorkerEvent.Error(
                    code = "INVALID_ARGS",
                    message = "Usage: --inspect <image-path>",
                ),
            )
            return 1
        }
        return driver.inspectImage(imagePath).fold(
            onSuccess = { info ->
                emitEvent(WorkerEvent.ImageInspected(info))
                0
            },
            onFailure = { error ->
                emitEvent(
                    WorkerEvent.Error(
                        code = "INSPECT_FAILED",
                        message = error.message ?: "Image inspection failed",
                    ),
                )
                1
            },
        )
    }

    private fun handleFlash(args: Array<String>): Int {
        val request =
            parseFlashRequest(args).getOrElse { error ->
                emitEvent(
                    WorkerEvent.Error(
                        code = "INVALID_ARGS",
                        message =
                            error.message
                                ?: "Usage: --flash --image <path> --device <id> [--verify] [--eject] [--event-log <path>]",
                    ),
                )
                return 1
            }

        return runWithOptionalEventLog(
            eventLogPath = request.eventLogPath,
            fallbackErrorCode = "FLASH_FAILED",
            fallbackMessage = "Flash operation failed",
        ) { sink ->
            driver.executeFlash(
                request = request,
                onEvent = { event -> emitEvent(event, sink) },
            )
        }
    }

    private fun handleFormat(args: Array<String>): Int {
        val request =
            parseFormatRequest(args).getOrElse { error ->
                emitEvent(
                    WorkerEvent.Error(
                        code = "INVALID_ARGS",
                        message =
                            error.message
                                ?: "Usage: --format --device <id> --scheme <gpt|mbr> --fs <fat32|exfat|ntfs|ext4> [--label <name>]",
                    ),
                )
                return 1
            }

        return runWithOptionalEventLog(
            eventLogPath = request.eventLogPath,
            fallbackErrorCode = "FORMAT_FAILED",
            fallbackMessage = "Format operation failed",
        ) { sink ->
            driver.executeFormat(
                request = request,
                onEvent = { event -> emitEvent(event, sink) },
            )
        }
    }

    private fun runWithOptionalEventLog(
        eventLogPath: String?,
        fallbackErrorCode: String,
        fallbackMessage: String,
        action: (WorkerEventSink?) -> Result<WorkerEvent.Completed>,
    ): Int {
        val eventLogSink =
            eventLogPath?.let { path ->
                runCatching { eventLogSinkFactory(path) }.getOrElse { error ->
                    emitEvent(
                        WorkerEvent.Error(
                            code = "EVENT_LOG_OPEN_FAILED",
                            message = error.message ?: "Unable to open event log '$path'",
                        ),
                    )
                    return 1
                }
            }

        try {
            return action(eventLogSink).fold(
                onSuccess = { completed ->
                    emitEvent(completed, eventLogSink)
                    0
                },
                onFailure = { error ->
                    val code = (error as? FlashStepException)?.code ?: fallbackErrorCode
                    emitEvent(
                        WorkerEvent.Error(
                            code = code,
                            message = error.message ?: fallbackMessage,
                        ),
                        eventLogSink,
                    )
                    1
                },
            )
        } finally {
            runCatching { eventLogSink?.close() }
        }
    }

    private fun handleEject(args: Array<String>): Int {
        val deviceId = parseEjectDevice(args)
        if (deviceId == null) {
            emitEvent(
                WorkerEvent.Error(
                    code = "INVALID_ARGS",
                    message = "Usage: --eject --device <id>",
                ),
            )
            return 1
        }
        return driver.ejectDevice(deviceId).fold(
            onSuccess = {
                emitEvent(WorkerEvent.Completed(verified = false, elapsedMs = 0L, ejected = true))
                0
            },
            onFailure = { error ->
                val code = (error as? FlashStepException)?.code ?: "EJECT_FAILED"
                emitEvent(WorkerEvent.Error(code = code, message = error.message ?: "Failed to eject '$deviceId'"))
                1
            },
        )
    }

    private fun handleVersion(): Int {
        printer(WORKER_VERSION)
        driver.flushOutput()
        return 0
    }

    private fun emitEvent(
        event: WorkerEvent,
        eventLogSink: WorkerEventSink? = null,
    ) {
        val encoded = DiskJson.encodeEvent(event)
        printer(encoded)
        driver.flushOutput()
        eventLogSink?.let { sink ->
            runCatching { sink.writeLine(encoded) }
        }
    }
}

private fun parseFlashRequest(args: Array<String>): Result<FlashRequest> {
    var imagePath: String? = null
    var deviceId: String? = null
    var verifyAfterWrite = false
    var ejectAfterWrite = false
    var eventLogPath: String? = null
    var index = 1

    while (index < args.size) {
        when (val token = args[index]) {
            "--image" -> {
                imagePath = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalArgumentException("Missing value for --image"))
                index += 2
            }
            "--device" -> {
                deviceId = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalArgumentException("Missing value for --device"))
                index += 2
            }
            "--event-log" -> {
                eventLogPath = args.getOrNull(index + 1)?.takeIf { it.isNotBlank() }
                    ?: return Result.failure(IllegalArgumentException("Missing value for --event-log"))
                index += 2
            }
            "--verify" -> {
                verifyAfterWrite = true
                index++
            }
            "--eject" -> {
                ejectAfterWrite = true
                index++
            }
            else -> return Result.failure(IllegalArgumentException("Unknown --flash argument: $token"))
        }
    }

    if (imagePath == null || deviceId == null) {
        return Result.failure(
            IllegalArgumentException("Usage: --flash --image <path> --device <id> [--verify] [--eject] [--event-log <path>]"),
        )
    }

    return Result.success(
        FlashRequest(
            imagePath = imagePath,
            deviceId = deviceId,
            verifyAfterWrite = verifyAfterWrite,
            ejectAfterWrite = ejectAfterWrite,
            eventLogPath = eventLogPath,
        ),
    )
}

private const val FORMAT_USAGE_MESSAGE =
    "Usage: --format --device <id> --scheme <gpt|mbr> --fs <fat32|exfat|ntfs|ext4> [--label <name>]"

private class FormatArgState {
    var deviceId: String? = null
    var scheme: PartitionScheme? = null
    var filesystem: FilesystemType? = null
    var label: String = ""
    var clusterSizeBytes: Int? = null
    var quickFormat: Boolean = true
    var ejectAfterFormat: Boolean = false
    var eventLogPath: String? = null

    fun buildRequest(): FormatRequest {
        val device = requireNotNull(deviceId) { FORMAT_USAGE_MESSAGE }
        val partScheme = requireNotNull(scheme) { FORMAT_USAGE_MESSAGE }
        val fs = requireNotNull(filesystem) { FORMAT_USAGE_MESSAGE }
        return FormatRequest(
            deviceId = device,
            partitionScheme = partScheme,
            filesystem = fs,
            volumeLabel = label,
            clusterSizeBytes = clusterSizeBytes,
            quickFormat = quickFormat,
            ejectAfterFormat = ejectAfterFormat,
            eventLogPath = eventLogPath,
        )
    }
}

private fun parseFormatRequest(args: Array<String>): Result<FormatRequest> =
    runCatching {
        val state = FormatArgState()
        var index = 1
        while (index < args.size) {
            index = applyFormatToken(state, args, index)
        }
        state.buildRequest()
    }

private fun applyFormatToken(
    state: FormatArgState,
    args: Array<String>,
    index: Int,
): Int =
    when (val token = args[index]) {
        "--device" -> {
            state.deviceId = requireArgValue(args, index, token)
            index + 2
        }
        "--scheme" -> {
            val raw = requireArgValue(args, index, token)
            state.scheme = requireNotNull(parsePartitionSchemeArg(raw)) { "Unsupported --scheme value: '$raw'" }
            index + 2
        }
        "--fs" -> {
            val raw = requireArgValue(args, index, token)
            state.filesystem = requireNotNull(parseFilesystemArg(raw)) { "Unsupported --fs value: '$raw'" }
            index + 2
        }
        "--label" -> {
            state.label = requireNotNull(args.getOrNull(index + 1)) { "Missing value for --label" }
            index + 2
        }
        "--cluster-size" -> {
            val raw = requireArgValue(args, index, token)
            state.clusterSizeBytes =
                requireNotNull(raw.toIntOrNull()?.takeIf { it > 0 }) { "Invalid --cluster-size value: '$raw'" }
            index + 2
        }
        "--event-log" -> {
            state.eventLogPath = requireArgValue(args, index, token)
            index + 2
        }
        "--full-wipe" -> {
            state.quickFormat = false
            index + 1
        }
        "--eject" -> {
            state.ejectAfterFormat = true
            index + 1
        }
        else -> throw IllegalArgumentException("Unknown --format argument: $token")
    }

private fun requireArgValue(
    args: Array<String>,
    index: Int,
    flagName: String,
): String =
    requireNotNull(args.getOrNull(index + 1)?.takeIf { it.isNotBlank() && it != flagName }) {
        "Missing value for $flagName"
    }

private fun parsePartitionSchemeArg(raw: String): PartitionScheme? =
    when (raw.lowercase()) {
        "gpt" -> PartitionScheme.GPT
        "mbr" -> PartitionScheme.MBR
        else -> null
    }

private fun parseFilesystemArg(raw: String): FilesystemType? =
    when (raw.lowercase()) {
        "fat32" -> FilesystemType.FAT32
        "exfat" -> FilesystemType.EXFAT
        "ntfs" -> FilesystemType.NTFS
        "ext4" -> FilesystemType.EXT4
        else -> null
    }

private fun parseEjectDevice(args: Array<String>): String? =
    if (args.size == 3 && args[1] == "--device") {
        args[2].takeIf { it.isNotBlank() }
    } else {
        null
    }

@OptIn(ExperimentalForeignApi::class)
private fun openPosixEventLogSink(path: String): WorkerEventSink {
    val file = fopen(path, "ab") ?: error("Unable to open event log '$path'")
    return object : WorkerEventSink {
        private var closed = false

        override fun writeLine(line: String) {
            if (closed) return
            val targetFile: kotlinx.cinterop.CPointer<FILE> = file
            fputs(line, targetFile)
            fputs("\n", targetFile)
            fflush(targetFile)
        }

        override fun close() {
            if (!closed) {
                closed = true
                fclose(file)
            }
        }
    }
}
