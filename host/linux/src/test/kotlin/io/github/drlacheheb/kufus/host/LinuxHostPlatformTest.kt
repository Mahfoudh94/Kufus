package io.github.drlacheheb.kufus.host

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.coroutines.Continuation
import kotlin.coroutines.EmptyCoroutineContext
import kotlin.coroutines.startCoroutine
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxHostPlatformTest {
    @Test
    fun launchesElevatedCommandViaPkexec() {
        var recordedCommand: List<String> = emptyList()
        val launcher =
            LinuxElevationLauncher { command ->
                recordedCommand = command
                Result.success(HostCommandOutput(exitCode = 0, stdout = "ok", stderr = ""))
            }

        val result =
            runSync {
                launcher.launchElevated("/usr/bin/kufus-worker", listOf("--flash", "/dev/sdb"))
            }.getOrThrow()

        recordedCommand shouldContainExactly listOf("pkexec", "/usr/bin/kufus-worker", "--flash", "/dev/sdb")
        result shouldBe ElevationResult(exitCode = 0, stdout = "ok", stderr = "")
    }

    @Test
    fun streamsElevatedLinesViaHostCommandExecutor() {
        val streamedLines = mutableListOf<String>()
        var recordedCommand: List<String> = emptyList()
        val launcher =
            LinuxElevationLauncher(
                object : HostCommandExecutor {
                    override suspend fun run(command: List<String>): Result<HostCommandOutput> = error("streamLines should be preferred")

                    override suspend fun streamLines(
                        command: List<String>,
                        onLine: (String) -> Unit,
                    ): Result<ElevatedLineStreamResult> {
                        recordedCommand = command
                        onLine("{\"type\":\"started\",\"totalBytes\":1024}")
                        onLine("{\"type\":\"completed\",\"verified\":true,\"elapsedMs\":10}")
                        return Result.success(ElevatedLineStreamResult(exitCode = 0))
                    }
                },
            )

        val result =
            runSync {
                launcher.streamElevated("/usr/bin/kufus-worker", listOf("--flash"), streamedLines::add)
            }.getOrThrow()

        launcher.streamsLiveOutput shouldBe true
        recordedCommand shouldContainExactly listOf("pkexec", "/usr/bin/kufus-worker", "--flash")
        streamedLines.size shouldBe 2
        result shouldBe ElevationResult(exitCode = 0)
    }

    @Test
    fun rejectsBlankExecutablePath() {
        val launcher = LinuxElevationLauncher { Result.success(HostCommandOutput(0, "", "")) }

        assertFailsWith<IllegalArgumentException> {
            runSync { launcher.launchElevated("  ", emptyList()) }
        }
    }

    @Test
    fun hostModuleProvidesLinuxArtifactsAndElevationLauncher() {
        val supportModule =
            module {
                single { HostCommandExecutor { Result.success(HostCommandOutput(0, "", "")) } }
            }
        val koinApp =
            koinApplication {
                modules(hostPlatformModule, supportModule)
            }
        try {
            val workerSpec = koinApp.koin.get<WorkerBinarySpec>()
            workerSpec.targetName shouldBe "linuxX64"
            workerSpec.executableNames shouldContainExactly listOf("kufus-worker", "kufus-worker.kexe")
            val nativeSpec = koinApp.koin.get<NativeLibrarySpec>()
            nativeSpec.targetName shouldBe "linuxX64"
            nativeSpec.libraryNames shouldContainExactly listOf("libkufusnative.so")
            koinApp.koin.get<ElevationLauncher>().shouldBeInstanceOf<LinuxElevationLauncher>()
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun rendersValidPolkitPolicyXmlAndEscapesSpecialPathCharacters() {
        val packagedXml = PolkitPolicyGenerator.renderPolicyXml()
        packagedXml shouldContain "<action id=\"$POLKIT_WORKER_ACTION_ID\">"
        packagedXml shouldContain "<annotate key=\"org.freedesktop.policykit.exec.path\">$PACKAGED_LINUX_WORKER_PATH</annotate>"
        packagedXml shouldContain "<allow_active>auth_admin</allow_active>"

        val customXml = PolkitPolicyGenerator.renderPolicyXml("/opt/Kufus & Test/<bin>/kufus-worker")
        customXml shouldContain "/opt/Kufus &amp; Test/&lt;bin&gt;/kufus-worker"

        assertFailsWith<IllegalArgumentException> {
            PolkitPolicyGenerator.renderPolicyXml("  ")
        }
    }

    private fun <T> runSync(block: suspend () -> T): T {
        var outcome: Result<T>? = null
        block.startCoroutine(
            Continuation(EmptyCoroutineContext) { result ->
                outcome = result
            },
        )
        return requireNotNull(outcome).getOrThrow()
    }
}
