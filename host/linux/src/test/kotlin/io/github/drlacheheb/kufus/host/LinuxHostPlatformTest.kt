package io.github.drlacheheb.kufus.host

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
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
