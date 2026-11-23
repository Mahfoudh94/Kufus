package io.github.drlacheheb.kufus.data.repository

import io.github.drlacheheb.kufus.disk.DiskJson
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.host.ElevationLauncher
import io.github.drlacheheb.kufus.host.ElevationResult
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class WorkerFormatRepositoryTest {
    @Test
    fun streamsFormatProgressAndBuildsExpectedCliArguments() =
        runTest {
            var recordedArgs: List<String> = emptyList()
            val repo =
                WorkerFormatRepository(
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
                                onLine(DiskJson.encodeEvent(WorkerEvent.Started(8192L)))
                                onLine(DiskJson.encodeEvent(WorkerEvent.Progress(FlashPhase.WIPING, 4096L, 8192L, 1_000_000L)))
                                onLine(DiskJson.encodeEvent(WorkerEvent.Progress(FlashPhase.PARTITIONING, 0L, 0L, 0L)))
                                onLine(DiskJson.encodeEvent(WorkerEvent.Completed(verified = false, elapsedMs = 35L)))
                                return Result.success(ElevationResult(exitCode = 0))
                            }
                        },
                    eventLogFactory = FlashEventLogFactory { error("event log unused on streaming launcher") },
                )

            val events =
                repo
                    .formatDrive(
                        FormatRequest(
                            deviceId = "/dev/sdb",
                            partitionScheme = PartitionScheme.GPT,
                            filesystem = FilesystemType.FAT32,
                            volumeLabel = "my*label",
                            clusterSizeBytes = 32_768,
                            quickFormat = false,
                            ejectAfterFormat = true,
                        ),
                    ).toList()

            recordedArgs shouldContainExactly
                listOf(
                    "--format",
                    "--device",
                    "/dev/sdb",
                    "--scheme",
                    "gpt",
                    "--fs",
                    "fat32",
                    "--label",
                    "MYLABEL",
                    "--cluster-size",
                    "32768",
                    "--full-wipe",
                    "--eject",
                )
            events.size shouldBe 4
            events.last() shouldBe WorkerEvent.Completed(verified = false, elapsedMs = 35L)
        }

    @Test
    fun emitsStructuredMissingWorkerAndElevationCancelErrors() =
        runTest {
            val missingWorkerRepo =
                WorkerFormatRepository(
                    workerLocator = WorkerExecutableLocator { null },
                    elevationLauncher = ElevationLauncher { _, _ -> Result.success(ElevationResult(0)) },
                    eventLogFactory = FlashEventLogFactory { error("unused") },
                )
            val missingEvents = missingWorkerRepo.formatDrive(FormatRequest("/dev/sdb")).toList()
            (missingEvents.single() as WorkerEvent.Error).code shouldBe "WORKER_MISSING"

            val cancelledRepo =
                WorkerFormatRepository(
                    workerLocator = WorkerExecutableLocator { "/opt/kufus-worker" },
                    elevationLauncher =
                        ElevationLauncher { _, _ ->
                            Result.success(ElevationResult(exitCode = 126))
                        },
                    eventLogFactory =
                        FlashEventLogFactory {
                            object : FlashEventLogSession {
                                override val path: String = "/tmp/format-log.ndjson"

                                override suspend fun drainLines(onLine: suspend (String) -> Unit) = Unit

                                override suspend fun closeAndDelete() = Unit
                            }
                        },
                )
            val cancelledEvents = cancelledRepo.formatDrive(FormatRequest("/dev/sdb")).toList()
            (cancelledEvents.single() as WorkerEvent.Error).code shouldBe "ELEVATION_CANCELLED"
        }
}
