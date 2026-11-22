package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.data.process.CommandOutput
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WorkerDiskRepositoryTest {
    @Test
    fun returnsDrivesWhenWorkerOutputsDrivesListedEvent() =
        runTest {
            val expectedDrive =
                UsbDrive(
                    id = "/dev/sdb",
                    name = "sdb",
                    model = "SanDisk Extreme",
                    sizeBytes = 64_000_000_000L,
                )
            val recordedCommands = mutableListOf<List<String>>()
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> {
                                recordedCommands += command
                                val json = DiskJson.encodeEvent(WorkerEvent.DrivesListed(listOf(expectedDrive)))
                                return Result.success(CommandOutput(exitCode = 0, stdout = "$json\n", stderr = ""))
                            }
                        },
                )

            val result = repository.listUsbDrives()

            recordedCommands shouldContainExactly listOf(listOf("/opt/kufus/kufus-worker", "--list"))
            result.getOrThrow() shouldContainExactly listOf(expectedDrive)
        }

    @Test
    fun returnsFailureWithoutRunningCommandWhenWorkerBinaryIsMissing() =
        runTest {
            var invoked = false
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { null },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> {
                                invoked = true
                                return Result.success(CommandOutput(exitCode = 0, stdout = "", stderr = ""))
                            }
                        },
                )

            val result = repository.listUsbDrives()

            invoked shouldBe false
            result.isFailure shouldBe true
        }

    @Test
    fun returnsWorkerErrorEventMessageWhenWorkerReportsError() =
        runTest {
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> {
                                val json =
                                    DiskJson.encodeEvent(
                                        WorkerEvent.Error(code = "PERM_DENIED", message = "Root privileges required"),
                                    )
                                return Result.success(CommandOutput(exitCode = 1, stdout = json, stderr = ""))
                            }
                        },
                )

            val result = repository.listUsbDrives()

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "PERM_DENIED: Root privileges required"
        }

    @Test
    fun returnsFailureWithStderrWhenWorkerFailsWithoutOutput() =
        runTest {
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> =
                                Result.success(CommandOutput(exitCode = 127, stdout = "", stderr = "missing shared lib"))
                        },
                )

            val result = repository.listUsbDrives()

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Worker failed with code 127: missing shared lib"
        }

    @Test
    fun propagatesCommandRunnerFailure() =
        runTest {
            val expectedError = IllegalStateException("spawn failed")
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> = Result.failure(expectedError)
                        },
                )

            val result = repository.listUsbDrives()

            result.exceptionOrNull() shouldBe expectedError
        }

    @Test
    fun returnsFailureWhenWorkerOutputsMalformedJson() =
        runTest {
            val repository =
                WorkerDiskRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    commandRunner =
                        object : CommandRunner {
                            override suspend fun run(command: List<String>): Result<CommandOutput> =
                                Result.success(CommandOutput(exitCode = 0, stdout = "{not-valid-json", stderr = ""))
                        },
                )

            val result = repository.listUsbDrives()

            result.isFailure shouldBe true
        }
}
