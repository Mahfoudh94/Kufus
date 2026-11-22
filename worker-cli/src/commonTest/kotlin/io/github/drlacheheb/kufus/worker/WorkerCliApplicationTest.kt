package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
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

    private fun fakeDriver(
        drives: List<UsbDrive> = emptyList(),
        watchAction: ((List<UsbDrive>) -> Unit) -> Unit = {},
        inspectAction: (String) -> Result<ImageInfo> = { Result.failure(UnsupportedOperationException()) },
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

            override fun flushOutput() {
                onFlush()
            }
        }
}
