package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.DiskJson
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
                driver =
                    object : NativeDiskDriver {
                        override fun listUsbDrives(): List<UsbDrive> = listOf(fakeDrive)

                        override fun watchUsbDrives(
                            onDrivesUpdated: (List<UsbDrive>) -> Unit,
                            shouldContinue: () -> Boolean,
                        ) = Unit
                    },
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
                    object : NativeDiskDriver {
                        override fun listUsbDrives(): List<UsbDrive> = emptyList()

                        override fun watchUsbDrives(
                            onDrivesUpdated: (List<UsbDrive>) -> Unit,
                            shouldContinue: () -> Boolean,
                        ) {
                            onDrivesUpdated(emptyList())
                            onDrivesUpdated(listOf(drive))
                        }

                        override fun flushOutput() {
                            flushes++
                        }
                    },
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
    fun versionFlagOutputsWorkerVersion() {
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver =
                    object : NativeDiskDriver {
                        override fun listUsbDrives(): List<UsbDrive> = emptyList()

                        override fun watchUsbDrives(
                            onDrivesUpdated: (List<UsbDrive>) -> Unit,
                            shouldContinue: () -> Boolean,
                        ) = Unit
                    },
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--version"))

        assertEquals(0, exitCode)
        assertEquals(listOf("kufus-worker 1.0.0"), output)
    }

    @Test
    fun unknownFlagOutputsErrorNdjsonAndNonZeroCode() {
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver =
                    object : NativeDiskDriver {
                        override fun listUsbDrives(): List<UsbDrive> = emptyList()

                        override fun watchUsbDrives(
                            onDrivesUpdated: (List<UsbDrive>) -> Unit,
                            shouldContinue: () -> Boolean,
                        ) = Unit
                    },
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--unknown"))

        assertEquals(1, exitCode)
        val decoded = DiskJson.decodeEvent(output.first()) as WorkerEvent.Error
        assertEquals("INVALID_ARGS", decoded.code)
    }
}
