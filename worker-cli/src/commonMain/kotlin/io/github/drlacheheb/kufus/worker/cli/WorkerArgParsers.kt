package io.github.drlacheheb.kufus.worker.cli

import io.github.drlacheheb.kufus.disk.flash.FlashRequest
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.FormatRequest
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions

internal const val FLASH_USAGE_MESSAGE =
    "Usage: --flash --image <path> --device <id> [--verify] [--eject] [--event-log <path>]"
internal const val FORMAT_USAGE_MESSAGE =
    "Usage: --format --device <id> --scheme <gpt|mbr> --fs <fat32|exfat|ntfs|ext4> [--label <name>]"
internal const val WINDOWS_FLASH_USAGE_MESSAGE =
    "Usage: --flash-windows --image <path> --device <id> --scheme <gpt|mbr> --fs <fat32|ntfs>"

/**
 * Pure CLI argument parsers for `kufus-worker` subcommands (free of OS/POSIX dependencies).
 */
internal object WorkerArgParsers {
    fun parseFlashRequest(args: Array<String>): Result<FlashRequest> {
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
            return Result.failure(IllegalArgumentException(FLASH_USAGE_MESSAGE))
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

    fun parseFormatRequest(args: Array<String>): Result<FormatRequest> =
        runCatching {
            val state = FormatArgState()
            var index = 1
            while (index < args.size) {
                index = applyFormatToken(state, args, index)
            }
            state.buildRequest()
        }

    fun parseWindowsFlashRequest(args: Array<String>): Result<WindowsFlashRequest> =
        runCatching {
            val state = WindowsFlashArgState()
            var index = 1
            while (index < args.size) {
                index = applyWindowsFlashToken(state, args, index)
            }
            state.buildRequest()
        }

    fun parseEjectDevice(args: Array<String>): String? =
        if (args.size == 3 && args[1] == "--device") {
            args[2].takeIf { it.isNotBlank() }
        } else {
            null
        }

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
                state.scheme = parseSchemeArgStrict(raw)
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
                state.clusterSizeBytes = parsePositiveIntArg(requireArgValue(args, index, token))
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

    private class WindowsFlashArgState {
        var imagePath: String? = null
        var deviceId: String? = null
        var scheme: PartitionScheme = PartitionScheme.GPT
        var filesystem: FilesystemType = FilesystemType.NTFS
        var label: String = ""
        var clusterSizeBytes: Int? = null
        var quickFormat: Boolean = true
        var ejectAfterWrite: Boolean = true
        var bypassRequirements: Boolean = false
        var bypassMicrosoftAccount: Boolean = false
        var disableDataCollection: Boolean = false
        var localUser: String? = null
        var localeTag: String? = null
        var uefiNtfsPath: String? = null
        var eventLogPath: String? = null

        fun buildRequest(): WindowsFlashRequest =
            WindowsFlashRequest(
                imagePath = requireNotNull(imagePath) { WINDOWS_FLASH_USAGE_MESSAGE },
                deviceId = requireNotNull(deviceId) { WINDOWS_FLASH_USAGE_MESSAGE },
                partitionScheme = scheme,
                filesystem = filesystem,
                volumeLabel = label,
                clusterSizeBytes = clusterSizeBytes,
                quickFormat = quickFormat,
                ejectAfterWrite = ejectAfterWrite,
                userExperience =
                    WindowsUserExperienceOptions(
                        bypassSystemRequirements = bypassRequirements,
                        bypassMicrosoftAccount = bypassMicrosoftAccount,
                        disableDataCollection = disableDataCollection,
                        localAccountUsername = localUser,
                        regionalLocaleTag = localeTag,
                    ),
                uefiNtfsImagePath = uefiNtfsPath,
                eventLogPath = eventLogPath,
            )
    }

    private fun applyWindowsFlashToken(
        state: WindowsFlashArgState,
        args: Array<String>,
        index: Int,
    ): Int {
        val token = args[index]
        return when {
            applyWindowsFlashStringToken(state, args, index, token) != null -> index + 2
            token == "--full-wipe" -> {
                state.quickFormat = false
                index + 1
            }
            token == "--no-eject" -> {
                state.ejectAfterWrite = false
                index + 1
            }
            token == "--bypass-requirements" -> {
                state.bypassRequirements = true
                index + 1
            }
            token == "--bypass-msa" -> {
                state.bypassMicrosoftAccount = true
                index + 1
            }
            token == "--disable-telemetry" -> {
                state.disableDataCollection = true
                index + 1
            }
            else -> throw IllegalArgumentException("Unknown --flash-windows argument: $token")
        }
    }

    private fun applyWindowsFlashStringToken(
        state: WindowsFlashArgState,
        args: Array<String>,
        index: Int,
        token: String,
    ): Unit? {
        when (token) {
            "--image" -> state.imagePath = requireArgValue(args, index, token)
            "--device" -> state.deviceId = requireArgValue(args, index, token)
            "--scheme" -> state.scheme = parseSchemeArgStrict(requireArgValue(args, index, token))
            "--fs" -> state.filesystem = parseWindowsFsArgStrict(requireArgValue(args, index, token))
            "--label" -> state.label = requireNotNull(args.getOrNull(index + 1)) { "Missing value for --label" }
            "--cluster-size" -> state.clusterSizeBytes = parsePositiveIntArg(requireArgValue(args, index, token))
            "--local-user" -> state.localUser = requireArgValue(args, index, token)
            "--locale" -> state.localeTag = requireArgValue(args, index, token)
            "--uefi-ntfs" -> state.uefiNtfsPath = requireArgValue(args, index, token)
            "--event-log" -> state.eventLogPath = requireArgValue(args, index, token)
            else -> return null
        }
        return Unit
    }

    private fun requireArgValue(
        args: Array<String>,
        index: Int,
        flagName: String,
    ): String =
        requireNotNull(args.getOrNull(index + 1)?.takeIf { it.isNotBlank() && it != flagName }) {
            "Missing value for $flagName"
        }

    private fun parseSchemeArgStrict(raw: String): PartitionScheme = requireNotNull(parsePartitionSchemeArg(raw)) { "Unsupported --scheme value: '$raw'" }

    private fun parseWindowsFsArgStrict(raw: String): FilesystemType =
        requireNotNull(parseFilesystemArg(raw)?.takeIf { it == FilesystemType.FAT32 || it == FilesystemType.NTFS }) {
            "Unsupported Windows --fs value: '$raw'"
        }

    private fun parsePositiveIntArg(raw: String): Int = requireNotNull(raw.toIntOrNull()?.takeIf { it > 0 }) { "Invalid --cluster-size value: '$raw'" }

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
}
