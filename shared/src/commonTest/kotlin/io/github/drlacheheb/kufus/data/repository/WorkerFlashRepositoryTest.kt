package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.WindowsFlashRequest
import io.github.drlacheheb.kufus.disk.WindowsUserExperienceOptions
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.host.ElevationLauncher
import io.github.drlacheheb.kufus.host.ElevationResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WorkerFlashRepositoryTest {
    @Test
    fun streamsEventsDirectlyWhenLauncherSupportsLiveStdout() =
        runTest {
            var recordedArgs: List<String> = emptyList()
            val repo =
                WorkerFlashRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus/kufus-worker" },
                    elevationLauncher =
                        object : ElevationLauncher {
                            override val streamsLiveOutput: Boolean = true

                            override suspend fun launchElevated(
                                executablePath: String,
                                arguments: List<String>,
                            ): Result<ElevationResult> = error("streamElevated should be called")

                            override suspend fun streamElevated(
                                executablePath: String,
                                arguments: List<String>,
                                onLine: (String) -> Unit,
                            ): Result<ElevationResult> {
                                recordedArgs = arguments
                                onLine(DiskJson.encodeEvent(WorkerEvent.Started(4096L)))
                                onLine(DiskJson.encodeEvent(WorkerEvent.Progress(FlashPhase.WRITING, 2048L, 4096L, 500_000L)))
                                onLine(DiskJson.encodeEvent(WorkerEvent.Completed(verified = true, elapsedMs = 20L)))
                                return Result.success(ElevationResult(exitCode = 0))
                            }
                        },
                    eventLogFactory = FlashEventLogFactory { error("event log unused on streaming launcher") },
                )

            val events = repo.flashImage(FlashRequest("/a.iso", "/dev/sdb", verifyAfterWrite = true, ejectAfterWrite = true)).toList()

            recordedArgs shouldContainExactly listOf("--flash", "--image", "/a.iso", "--device", "/dev/sdb", "--verify", "--eject")
            events.size shouldBe 3
            events.last() shouldBe WorkerEvent.Completed(verified = true, elapsedMs = 20L)
        }

    @Test
    fun tailsEventLogWhenLauncherBuffersOutputUntilExit() =
        runTest {
            val bufferedLines = mutableListOf<String>()
            var sessionClosed = false
            var capturedArgs: List<String> = emptyList()
            val fakeSession =
                object : FlashEventLogSession {
                    override val path: String = "/tmp/fake-flash.ndjson"

                    override suspend fun drainLines(onLine: suspend (String) -> Unit) {
                        val copy = bufferedLines.toList()
                        bufferedLines.clear()
                        copy.forEach { onLine(it) }
                    }

                    override suspend fun closeAndDelete() {
                        sessionClosed = true
                    }
                }
            val repo =
                WorkerFlashRepository(
                    workerLocator = WorkerExecutableLocator { "C:\\kufus-worker.exe" },
                    elevationLauncher =
                        ElevationLauncher { _, arguments ->
                            capturedArgs = arguments
                            bufferedLines += DiskJson.encodeEvent(WorkerEvent.Started(1024L))
                            bufferedLines += DiskJson.encodeEvent(WorkerEvent.Completed(verified = false, elapsedMs = 5L))
                            Result.success(ElevationResult(exitCode = 0))
                        },
                    eventLogFactory = FlashEventLogFactory { fakeSession },
                )

            val events =
                repo
                    .flashImage(
                        FlashRequest("D:\\disk.img", "\\\\.\\PhysicalDrive1", verifyAfterWrite = false, ejectAfterWrite = false),
                    ).toList()

            capturedArgs shouldContainExactly
                listOf(
                    "--flash",
                    "--image",
                    "D:\\disk.img",
                    "--device",
                    "\\\\.\\PhysicalDrive1",
                    "--event-log",
                    "/tmp/fake-flash.ndjson",
                )
            sessionClosed shouldBe true
            events.size shouldBe 2
            events.last() shouldBe WorkerEvent.Completed(verified = false, elapsedMs = 5L)
        }

    @Test
    fun emitsStructuredCancellationOrMissingWorkerErrors() =
        runTest {
            val missingWorkerRepo =
                WorkerFlashRepository(
                    workerLocator = WorkerExecutableLocator { null },
                    elevationLauncher = ElevationLauncher { _, _ -> Result.success(ElevationResult(0)) },
                    eventLogFactory = FlashEventLogFactory { error("unused") },
                )
            val missingEvents = missingWorkerRepo.flashImage(FlashRequest("/a.iso", "/dev/sdb")).toList()
            (missingEvents.single() as WorkerEvent.Error).code shouldBe "WORKER_MISSING"

            val cancelledRepo =
                WorkerFlashRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus-worker" },
                    elevationLauncher =
                        object : ElevationLauncher {
                            override val streamsLiveOutput: Boolean = true

                            override suspend fun launchElevated(
                                executablePath: String,
                                arguments: List<String>,
                            ): Result<ElevationResult> = Result.success(ElevationResult(126))

                            override suspend fun streamElevated(
                                executablePath: String,
                                arguments: List<String>,
                                onLine: (String) -> Unit,
                            ): Result<ElevationResult> = Result.success(ElevationResult(exitCode = 126))
                        },
                    eventLogFactory = FlashEventLogFactory { error("unused") },
                )
            val cancelledEvents = cancelledRepo.flashImage(FlashRequest("/a.iso", "/dev/sdb")).toList()
            (cancelledEvents.single() as WorkerEvent.Error).code shouldBe "ELEVATION_CANCELLED"
        }

    @Test
    fun buildsWindowsInstallerArgumentsWithResolvedUefiNtfsCompanionAssetAndUserExperienceFlags() =
        runTest {
            var recordedArgs: List<String> = emptyList()
            val locator =
                object : WorkerExecutableLocator {
                    override fun locateWorkerPath(): String = "/opt/kufus/kufus-worker"

                    override fun locateCompanionFile(fileName: String): String? =
                        if (fileName == "uefi-ntfs.img") {
                            "/opt/kufus/uefi-ntfs.img"
                        } else {
                            null
                        }
                }
            val repo =
                WorkerFlashRepository(
                    workerLocator = locator,
                    elevationLauncher =
                        object : ElevationLauncher {
                            override val streamsLiveOutput: Boolean = true

                            override suspend fun launchElevated(
                                executablePath: String,
                                arguments: List<String>,
                            ): Result<ElevationResult> = Result.success(ElevationResult(0))

                            override suspend fun streamElevated(
                                executablePath: String,
                                arguments: List<String>,
                                onLine: (String) -> Unit,
                            ): Result<ElevationResult> {
                                recordedArgs = arguments
                                onLine(DiskJson.encodeEvent(WorkerEvent.Completed(verified = false, elapsedMs = 45L)))
                                return Result.success(ElevationResult(exitCode = 0))
                            }
                        },
                    eventLogFactory = FlashEventLogFactory { error("unused") },
                )

            val events =
                repo
                    .flashWindowsIso(
                        WindowsFlashRequest(
                            imagePath = "/tmp/Win11.iso",
                            deviceId = "/dev/sdc",
                            partitionScheme = PartitionScheme.GPT,
                            filesystem = FilesystemType.NTFS,
                            volumeLabel = "WIN11_PRO",
                            clusterSizeBytes = 4096,
                            quickFormat = false,
                            ejectAfterWrite = false,
                            userExperience =
                                WindowsUserExperienceOptions(
                                    bypassSystemRequirements = true,
                                    bypassMicrosoftAccount = true,
                                    disableDataCollection = true,
                                    localAccountUsername = "UserOne",
                                    regionalLocaleTag = "en-US",
                                ),
                        ),
                    ).toList()

            recordedArgs shouldContainExactly
                listOf(
                    "--flash-windows",
                    "--image",
                    "/tmp/Win11.iso",
                    "--device",
                    "/dev/sdc",
                    "--scheme",
                    "gpt",
                    "--fs",
                    "ntfs",
                    "--label",
                    "WIN11_PRO",
                    "--cluster-size",
                    "4096",
                    "--full-wipe",
                    "--no-eject",
                    "--bypass-requirements",
                    "--bypass-msa",
                    "--disable-telemetry",
                    "--local-user",
                    "UserOne",
                    "--locale",
                    "en-US",
                    "--uefi-ntfs",
                    "/opt/kufus/uefi-ntfs.img",
                )
            events.single() shouldBe WorkerEvent.Completed(verified = false, elapsedMs = 45L)
        }
}
