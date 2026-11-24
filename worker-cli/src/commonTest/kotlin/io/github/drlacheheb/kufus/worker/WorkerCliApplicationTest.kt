package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.flash.FlashRequest
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.DiskJson
import io.github.drlacheheb.kufus.disk.model.FilesystemType
import io.github.drlacheheb.kufus.disk.model.FlashPhase
import io.github.drlacheheb.kufus.disk.model.FormatRequest
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.disk.model.ImageInfo
import io.github.drlacheheb.kufus.disk.model.PartitionScheme
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.worker.io.WorkerEventSink
import kotlin.test.Test
import kotlin.test.assertEquals

class WorkerCliApplicationTest {
    @Test
    fun listFlagOutputsDrivesListedNdjson() {
        val fakeDrive =
            UsbDrive(
                id = "/dev/sdb",
                name = "sdb",
                model = "Test USB",
                sizeBytes = 16_000_000_000L,
            )
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver = fakeDriver(drives = listOf(fakeDrive)),
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--list"))

        assertEquals(0, exitCode)
        assertEquals(1, output.size)
        assertEquals(WorkerEvent.DrivesListed(listOf(fakeDrive)), DiskJson.decodeEvent(output.first()))
    }

    @Test
    fun watchFlagStreamsAndFlushesMultipleDriveSnapshots() {
        val drive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "Test USB", sizeBytes = 16_000_000_000L)
        val output = mutableListOf<String>()
        var flushes = 0
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        watchAction = { onUpdated ->
                            onUpdated(emptyList())
                            onUpdated(listOf(drive))
                        },
                        onFlush = { flushes++ },
                    ),
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--watch"))

        assertEquals(0, exitCode)
        assertEquals(2, flushes)
        assertEquals(2, output.size)
        assertEquals(WorkerEvent.DrivesListed(emptyList()), DiskJson.decodeEvent(output[0]))
        assertEquals(WorkerEvent.DrivesListed(listOf(drive)), DiskJson.decodeEvent(output[1]))
    }

    @Test
    fun inspectFlagOutputsImageInspectedNdjson() {
        val expectedInfo =
            ImageInfo(
                path = "/images/ubuntu.iso.gz",
                sizeBytes = 1_500_000_000L,
                format = ImageFormat.COMPRESSED_IMAGE,
                compression = CompressionFormat.GZIP,
                uncompressedSizeHintBytes = 3_200_000_000L,
            )
        val output = mutableListOf<String>()
        var flushes = 0
        var requestedPath: String? = null
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        inspectAction = { path ->
                            requestedPath = path
                            Result.success(expectedInfo)
                        },
                        onFlush = { flushes++ },
                    ),
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--inspect", "/images/ubuntu.iso.gz"))

        assertEquals(0, exitCode)
        assertEquals(1, flushes)
        assertEquals("/images/ubuntu.iso.gz", requestedPath)
        assertEquals(WorkerEvent.ImageInspected(expectedInfo), DiskJson.decodeEvent(output.single()))
    }

    @Test
    fun inspectFlagWithoutPathOutputsInvalidArgsError() {
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver = fakeDriver(),
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--inspect"))

        assertEquals(1, exitCode)
        val decoded = DiskJson.decodeEvent(output.single()) as WorkerEvent.Error
        assertEquals("INVALID_ARGS", decoded.code)
    }

    @Test
    fun inspectFlagOutputsInspectFailedErrorOnDriverFailure() {
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver = fakeDriver(inspectAction = { Result.failure(IllegalStateException("File not found")) }),
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--inspect", "/missing.iso"))

        assertEquals(1, exitCode)
        val decoded = DiskJson.decodeEvent(output.single()) as WorkerEvent.Error
        assertEquals("INSPECT_FAILED", decoded.code)
        assertEquals("File not found", decoded.message)
    }

    @Test
    fun flashFlagParsesArgumentsStreamsNdjsonAndMirrorsToEventLog() {
        val output = mutableListOf<String>()
        val logLines = mutableListOf<String>()
        var sinkClosed = false
        var openedLogPath: String? = null
        var capturedRequest: FlashRequest? = null
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        flashAction = { request, onEvent ->
                            capturedRequest = request
                            onEvent(WorkerEvent.Started(2048L))
                            onEvent(WorkerEvent.Progress(FlashPhase.WRITING, 2048L, 2048L, 1_000_000L))
                            Result.success(WorkerEvent.Completed(verified = request.verifyAfterWrite, elapsedMs = 42L))
                        },
                    ),
                printer = output::add,
                eventLogSinkFactory = { path ->
                    openedLogPath = path
                    object : WorkerEventSink {
                        override fun writeLine(line: String) {
                            logLines += line
                        }

                        override fun close() {
                            sinkClosed = true
                        }
                    }
                },
            )

        val exitCode =
            app.run(
                arrayOf(
                    "--flash",
                    "--image",
                    "/images/linux.iso",
                    "--device",
                    "/dev/sdb",
                    "--verify",
                    "--eject",
                    "--event-log",
                    "/tmp/flash.ndjson",
                ),
            )

        assertEquals(0, exitCode)
        assertEquals("/tmp/flash.ndjson", openedLogPath)
        assertEquals(true, sinkClosed)
        assertEquals(
            FlashRequest(
                imagePath = "/images/linux.iso",
                deviceId = "/dev/sdb",
                verifyAfterWrite = true,
                ejectAfterWrite = true,
                eventLogPath = "/tmp/flash.ndjson",
            ),
            capturedRequest,
        )
        assertEquals(output, logLines)
        assertEquals(3, output.size)
        assertEquals(WorkerEvent.Completed(verified = true, elapsedMs = 42L), DiskJson.decodeEvent(output.last()))
    }

    @Test
    fun formatFlagParsesArgumentsStreamsNdjsonAndHandlesInvalidArgs() {
        val output = mutableListOf<String>()
        var capturedRequest: FormatRequest? = null
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        formatAction = { request, onEvent ->
                            capturedRequest = request
                            onEvent(WorkerEvent.Progress(FlashPhase.PARTITIONING, 0L, 0L, 0L))
                            onEvent(WorkerEvent.Progress(FlashPhase.FORMATTING, 100L, 100L, 0L))
                            Result.success(WorkerEvent.Completed(verified = false, elapsedMs = 120L, ejected = request.ejectAfterFormat))
                        },
                    ),
                printer = output::add,
            )

        val exitCode =
            app.run(
                arrayOf(
                    "--format",
                    "--device",
                    "/dev/sdb",
                    "--scheme",
                    "mbr",
                    "--fs",
                    "fat32",
                    "--label",
                    "MYUSB",
                    "--cluster-size",
                    "32768",
                    "--full-wipe",
                    "--eject",
                ),
            )

        assertEquals(0, exitCode)
        assertEquals(
            FormatRequest(
                deviceId = "/dev/sdb",
                partitionScheme = PartitionScheme.MBR,
                filesystem = FilesystemType.FAT32,
                volumeLabel = "MYUSB",
                clusterSizeBytes = 32_768,
                quickFormat = false,
                ejectAfterFormat = true,
            ),
            capturedRequest,
        )
        assertEquals(3, output.size)
        assertEquals(WorkerEvent.Completed(verified = false, elapsedMs = 120L, ejected = true), DiskJson.decodeEvent(output.last()))

        val badOut = mutableListOf<String>()
        assertEquals(1, WorkerCliApplication(driver = fakeDriver(), printer = badOut::add).run(arrayOf("--format", "--device", "/dev/sdb")))
        assertEquals("INVALID_ARGS", (DiskJson.decodeEvent(badOut.single()) as WorkerEvent.Error).code)
    }

    @Test
    fun ejectFlagEjectsTargetDeviceAndRejectsMissingDeviceArg() {
        val output = mutableListOf<String>()
        var ejectedPath: String? = null
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        ejectAction = { path ->
                            ejectedPath = path
                            Result.success(Unit)
                        },
                    ),
                printer = output::add,
            )

        assertEquals(0, app.run(arrayOf("--eject", "--device", "/dev/sdb")))
        assertEquals("/dev/sdb", ejectedPath)
        assertEquals(WorkerEvent.Completed(verified = false, elapsedMs = 0L, ejected = true), DiskJson.decodeEvent(output.single()))

        val badOut = mutableListOf<String>()
        assertEquals(1, WorkerCliApplication(driver = fakeDriver(), printer = badOut::add).run(arrayOf("--eject")))
        assertEquals("INVALID_ARGS", (DiskJson.decodeEvent(badOut.single()) as WorkerEvent.Error).code)
    }

    @Test
    fun flashFlagEmitsStructuredStepErrorAndHandlesInvalidArgs() {
        val badArgsOut = mutableListOf<String>()
        val badApp = WorkerCliApplication(driver = fakeDriver(), printer = badArgsOut::add)
        assertEquals(1, badApp.run(arrayOf("--flash", "--image", "/a.iso")))
        assertEquals("INVALID_ARGS", (DiskJson.decodeEvent(badArgsOut.single()) as WorkerEvent.Error).code)

        val failOut = mutableListOf<String>()
        val failApp =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        flashAction = { _, _ ->
                            Result.failure(FlashStepException(code = "UNSAFE_DEVICE", message = "Internal disk blocked"))
                        },
                    ),
                printer = failOut::add,
            )
        assertEquals(1, failApp.run(arrayOf("--flash", "--image", "/a.iso", "--device", "/dev/sda")))
        val error = DiskJson.decodeEvent(failOut.single()) as WorkerEvent.Error
        assertEquals("UNSAFE_DEVICE", error.code)
        assertEquals("Internal disk blocked", error.message)
    }

    @Test
    fun elevateFlagForwardsExecutableAndArgumentsAndReportsFailures() {
        var capturedExe: String? = null
        var capturedArgs: List<String> = emptyList()
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        elevateAction = { exe, args ->
                            capturedExe = exe
                            capturedArgs = args
                            0
                        },
                    ),
                printer = output::add,
            )

        val code = app.run(arrayOf("--elevate", "C:\\Kufus\\kufus-worker.exe", "--flash", "--device", "\\\\.\\PhysicalDrive1"))
        assertEquals(0, code)
        assertEquals("C:\\Kufus\\kufus-worker.exe", capturedExe)
        assertEquals(listOf("--flash", "--device", "\\\\.\\PhysicalDrive1"), capturedArgs)
        assertEquals(emptyList(), output)

        val failOut = mutableListOf<String>()
        val failApp =
            WorkerCliApplication(
                driver = fakeDriver(elevateAction = { _, _ -> -1 }),
                printer = failOut::add,
            )
        assertEquals(1, failApp.run(arrayOf("--elevate", "C:\\Kufus\\kufus-worker.exe")))
        assertEquals("ELEVATION_FAILED", (DiskJson.decodeEvent(failOut.single()) as WorkerEvent.Error).code)

        val missingOut = mutableListOf<String>()
        assertEquals(1, WorkerCliApplication(driver = fakeDriver(), printer = missingOut::add).run(arrayOf("--elevate")))
        assertEquals("INVALID_ARGS", (DiskJson.decodeEvent(missingOut.single()) as WorkerEvent.Error).code)
    }

    @Test
    fun versionFlagOutputsWorkerVersion() {
        val output = mutableListOf<String>()
        val app = WorkerCliApplication(driver = fakeDriver(), printer = output::add)

        val exitCode = app.run(arrayOf("--version"))

        assertEquals(0, exitCode)
        assertEquals(listOf("kufus-worker 1.0.0"), output)
    }

    @Test
    fun unknownFlagOutputsErrorNdjsonAndNonZeroCode() {
        val output = mutableListOf<String>()
        val app = WorkerCliApplication(driver = fakeDriver(), printer = output::add)

        val exitCode = app.run(arrayOf("--unknown"))

        assertEquals(1, exitCode)
        val decoded = DiskJson.decodeEvent(output.first()) as WorkerEvent.Error
        assertEquals("INVALID_ARGS", decoded.code)
    }

    @Test
    fun flashWindowsFlagParsesOptionsAndDispatchesDriver() {
        val output = mutableListOf<String>()
        var captured: WindowsFlashRequest? = null
        val app =
            WorkerCliApplication(
                driver =
                    fakeDriver(
                        windowsFlashAction = { req, onEvent ->
                            captured = req
                            onEvent(WorkerEvent.Progress(FlashPhase.WRITING, 512L, 1024L, 1_000_000L))
                            Result.success(WorkerEvent.Completed(verified = false, elapsedMs = 250L, ejected = true))
                        },
                    ),
                printer = output::add,
            )

        val code =
            app.run(
                arrayOf(
                    "--flash-windows",
                    "--image",
                    "/isos/win11.iso",
                    "--device",
                    "/dev/sdb",
                    "--scheme",
                    "gpt",
                    "--fs",
                    "ntfs",
                    "--label",
                    "WIN11",
                    "--uefi-ntfs",
                    "/app/uefi-ntfs.img",
                    "--bypass-requirements",
                    "--bypass-msa",
                    "--disable-telemetry",
                    "--local-user",
                    "admin",
                    "--locale",
                    "en-US",
                ),
            )

        assertEquals(0, code)
        val req = requireNotNull(captured)
        assertEquals("/isos/win11.iso", req.imagePath)
        assertEquals("/dev/sdb", req.deviceId)
        assertEquals(FilesystemType.NTFS, req.filesystem)
        assertEquals("WIN11", req.volumeLabel)
        assertEquals("/app/uefi-ntfs.img", req.uefiNtfsImagePath)
        assertEquals(true, req.userExperience.bypassSystemRequirements)
        assertEquals(true, req.userExperience.bypassMicrosoftAccount)
        assertEquals(true, req.userExperience.disableDataCollection)
        assertEquals("admin", req.userExperience.localAccountUsername)
        assertEquals("en-US", req.userExperience.regionalLocaleTag)
    }

    private fun fakeDriver(
        drives: List<UsbDrive> = emptyList(),
        watchAction: ((List<UsbDrive>) -> Unit) -> Unit = {},
        inspectAction: (String) -> Result<ImageInfo> = { Result.failure(UnsupportedOperationException()) },
        flashAction: (FlashRequest, (WorkerEvent) -> Unit) -> Result<WorkerEvent.Completed> =
            { _, _ -> Result.failure(UnsupportedOperationException()) },
        formatAction: (FormatRequest, (WorkerEvent) -> Unit) -> Result<WorkerEvent.Completed> =
            { _, _ -> Result.failure(UnsupportedOperationException()) },
        windowsFlashAction: (WindowsFlashRequest, (WorkerEvent) -> Unit) -> Result<WorkerEvent.Completed> =
            { _, _ -> Result.failure(UnsupportedOperationException()) },
        ejectAction: (String) -> Result<Unit> = { Result.failure(UnsupportedOperationException()) },
        elevateAction: (String, List<String>) -> Int = { _, _ -> -1 },
        onFlush: () -> Unit = {},
    ): NativeDiskDriver =
        object : NativeDiskDriver {
            override fun listUsbDrives(): List<UsbDrive> = drives

            override fun watchUsbDrives(
                onDrivesUpdated: (List<UsbDrive>) -> Unit,
                shouldContinue: () -> Boolean,
            ) {
                watchAction(onDrivesUpdated)
            }

            override fun inspectImage(path: String): Result<ImageInfo> = inspectAction(path)

            override fun executeFlash(
                request: FlashRequest,
                shouldCancel: () -> Boolean,
                onEvent: (WorkerEvent) -> Unit,
            ): Result<WorkerEvent.Completed> = flashAction(request, onEvent)

            override fun executeFormat(
                request: FormatRequest,
                shouldCancel: () -> Boolean,
                onEvent: (WorkerEvent) -> Unit,
            ): Result<WorkerEvent.Completed> = formatAction(request, onEvent)

            override fun executeWindowsFlash(
                request: WindowsFlashRequest,
                shouldCancel: () -> Boolean,
                onEvent: (WorkerEvent) -> Unit,
            ): Result<WorkerEvent.Completed> = windowsFlashAction(request, onEvent)

            override fun ejectDevice(devicePath: String): Result<Unit> = ejectAction(devicePath)

            override fun launchElevatedProcess(
                executablePath: String,
                arguments: List<String>,
            ): Int = elevateAction(executablePath, arguments)

            override fun flushOutput() {
                onFlush()
            }
        }
}
