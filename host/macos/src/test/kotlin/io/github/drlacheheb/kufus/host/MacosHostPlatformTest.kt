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

class MacosHostPlatformTest {
    @Test
    fun launchesElevatedCommandViaOsascriptWithEscapedArguments() {
        var recordedCommand: List<String> = emptyList()
        val launcher =
            MacosElevationLauncher { command ->
                recordedCommand = command
                Result.success(HostCommandOutput(exitCode = 0, stdout = "", stderr = ""))
            }

        val result =
            runSync {
                launcher.launchElevated(
                    "/Applications/Kufus.app/Contents/Resources/kufus-worker",
                    listOf("--flash", "a\"b\\c'd"),
                )
            }.getOrThrow()

        recordedCommand shouldContainExactly
            listOf(
                "osascript",
                "-e",
                "do shell script " +
                    "\"'/Applications/Kufus.app/Contents/Resources/kufus-worker' '--flash' 'a\\\"b\\\\c'\\\\''d'\" " +
                    "with administrator privileges",
            )
        result shouldBe ElevationResult(exitCode = 0)
    }

    @Test
    fun rejectsBlankExecutablePath() {
        val launcher = MacosElevationLauncher { Result.success(HostCommandOutput(0, "", "")) }

        assertFailsWith<IllegalArgumentException> {
            runSync { launcher.launchElevated(" ", emptyList()) }
        }
    }

    @Test
    fun hostModuleProvidesMacosArtifactsAndElevationLauncher() {
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
            workerSpec.targetName shouldBe "macosArm64"
            workerSpec.executableNames shouldContainExactly listOf("kufus-worker", "kufus-worker.kexe")
            val nativeSpec = koinApp.koin.get<NativeLibrarySpec>()
            nativeSpec.targetName shouldBe "macosArm64"
            nativeSpec.libraryNames shouldContainExactly listOf("libkufusnative.dylib")
            koinApp.koin.get<ElevationLauncher>().shouldBeInstanceOf<MacosElevationLauncher>()
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
