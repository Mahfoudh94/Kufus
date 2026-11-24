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

class WindowsHostPlatformTest {
    @Test
    fun launchesElevatedCommandViaWorkerElevateFlag() {
        var recordedCommand: List<String> = emptyList()
        val launcher =
            WindowsElevationLauncher { command ->
                recordedCommand = command
                Result.success(HostCommandOutput(exitCode = 0, stdout = "", stderr = ""))
            }

        val result =
            runSync {
                launcher.launchElevated(
                    "C:\\Program Files\\Kufus\\kufus-worker.exe",
                    listOf("--drive", "O'Brien"),
                )
            }.getOrThrow()

        recordedCommand shouldContainExactly
            listOf(
                "C:\\Program Files\\Kufus\\kufus-worker.exe",
                "--elevate",
                "C:\\Program Files\\Kufus\\kufus-worker.exe",
                "--drive",
                "O'Brien",
            )
        result shouldBe ElevationResult(exitCode = 0)
    }

    @Test
    fun rejectsBlankExecutablePath() {
        val launcher = WindowsElevationLauncher { Result.success(HostCommandOutput(0, "", "")) }

        assertFailsWith<IllegalArgumentException> {
            runSync { launcher.launchElevated("", emptyList()) }
        }
    }

    @Test
    fun hostModuleProvidesWindowsArtifactsAndElevationLauncher() {
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
            workerSpec.targetName shouldBe "mingwX64"
            workerSpec.executableNames shouldContainExactly listOf("kufus-worker.exe")
            koinApp.koin.get<ElevationLauncher>().shouldBeInstanceOf<WindowsElevationLauncher>()
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
