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
                    },
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--list"))

        assertEquals(0, exitCode)
        assertEquals(1, output.size)
        assertEquals(WorkerEvent.DrivesListed(listOf(fakeDrive)), DiskJson.decodeEvent(output.first()))
    }

    @Test
    fun versionFlagOutputsWorkerVersion() {
        val output = mutableListOf<String>()
        val app =
            WorkerCliApplication(
                driver =
                    object : NativeDiskDriver {
                        override fun listUsbDrives(): List<UsbDrive> = emptyList()
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
                    },
                printer = output::add,
            )

        val exitCode = app.run(arrayOf("--unknown"))

        assertEquals(1, exitCode)
        val decoded = DiskJson.decodeEvent(output.first()) as WorkerEvent.Error
        assertEquals("INVALID_ARGS", decoded.code)
    }
}
