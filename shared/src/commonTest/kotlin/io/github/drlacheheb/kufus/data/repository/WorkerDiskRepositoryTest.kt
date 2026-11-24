package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.disk.model.DiskJson
import io.github.drlacheheb.kufus.disk.model.UsbDrive
import io.github.drlacheheb.kufus.disk.model.WorkerEvent
import io.github.drlacheheb.kufus.host.HostCommandOutput
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WorkerDiskRepositoryTest {
    @Test
    fun listsUsbDrivesViaWorkerCliSubprocess() =
        runTest {
            val expectedDrive =
                UsbDrive(
                    id = "/dev/sdb",
                    name = "sdb",
                    model = "SanDisk Extreme",
                    sizeBytes = 64_000_000_000L,
                )
            val recordedCommands = mutableListOf<List<String>>()
            val line = DiskJson.encodeEvent(WorkerEvent.DrivesListed(listOf(expectedDrive)))
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> {
                                recordedCommands += command
                                return Result.success(HostCommandOutput(exitCode = 0, stdout = "$line\n", stderr = ""))
                            }

                            override fun streamLines(command: List<String>): Flow<String> = emptyFlow()
                        },
                )

            val result = repository.listUsbDrives()

            recordedCommands shouldContainExactly listOf(listOf("/opt/kufus/kufus-worker", "--list"))
            result.getOrThrow() shouldContainExactly listOf(expectedDrive)
        }

    @Test
    fun returnsFailureWhenListWorkerBinaryIsMissingOrFails() =
        runTest {
            val missingRepo =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { null },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> = Result.success(HostCommandOutput(0, "", ""))

                            override fun streamLines(command: List<String>): Flow<String> = emptyFlow()
                        },
                )
            missingRepo.listUsbDrives().isFailure shouldBe true

            val errorRepo =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> =
                                Result.success(HostCommandOutput(exitCode = 1, stdout = "", stderr = "permission denied"))

                            override fun streamLines(command: List<String>): Flow<String> = emptyFlow()
                        },
                )
            errorRepo.listUsbDrives().exceptionOrNull()?.message shouldBe "permission denied"
        }

    @Test
    fun streamsLiveUsbUpdatesFromWatchWorkerProcess() =
        runTest {
            val drive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "SanDisk Extreme", sizeBytes = 64_000_000_000L)
            val recordedCommands = mutableListOf<List<String>>()
            val line1 = DiskJson.encodeEvent(WorkerEvent.DrivesListed(emptyList()))
            val line2 = DiskJson.encodeEvent(WorkerEvent.DrivesListed(listOf(drive)))
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> = Result.success(HostCommandOutput(0, "", ""))

                            override fun streamLines(command: List<String>): Flow<String> {
                                recordedCommands += command
                                return flowOf(line1, "   ", line2)
                            }
                        },
                )

            val emissions = repository.observeUsbDrives().toList()

            recordedCommands shouldContainExactly listOf(listOf("/opt/kufus/kufus-worker", "--watch"))
            emissions.size shouldBe 2
            emissions[0].getOrThrow() shouldContainExactly emptyList()
            emissions[1].getOrThrow() shouldContainExactly listOf(drive)
        }

    @Test
    fun emitsFailureWhenWatchWorkerBinaryIsMissing() =
        runTest {
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { null },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> = Result.success(HostCommandOutput(0, "", ""))

                            override fun streamLines(command: List<String>): Flow<String> = emptyFlow()
                        },
                )

            val emissions = repository.observeUsbDrives().toList()

            emissions.size shouldBe 1
            emissions.first().isFailure shouldBe true
        }

    @Test
    fun emitsWorkerErrorEventWhenWatchOutputsErrorLine() =
        runTest {
            val errorJson = DiskJson.encodeEvent(WorkerEvent.Error(code = "WATCH_FAILED", message = "netlink error"))
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<HostCommandOutput> = Result.success(HostCommandOutput(0, "", ""))

                            override fun streamLines(command: List<String>): Flow<String> = flowOf(errorJson)
                        },
                )

            val emissions = repository.observeUsbDrives().toList()

            emissions.size shouldBe 1
            emissions.first().exceptionOrNull()?.message shouldBe "WATCH_FAILED: netlink error"
        }
}
