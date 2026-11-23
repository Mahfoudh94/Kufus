package io.github.drlacheheb.kufus.data.process

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.test.Test

class ProcessCommandRunnerTest {
    @Test
    fun streamsNonBlankLinesAndCapturesExitCodeAndStderr() =
        runTest {
            if (File.separatorChar != '/') return@runTest
            val runner = ProcessCommandRunner()
            val lines = mutableListOf<String>()

            val result =
                runner.streamLines(
                    command = listOf("/bin/sh", "-c", "echo first; echo ''; echo second; echo err-line >&2; exit 2"),
                    onLine = lines::add,
                )

            val output = result.getOrThrow()
            lines shouldContainExactly listOf("first", "second")
            output.exitCode shouldBe 2
            output.stderr shouldBe "err-line"
        }

    @Test
    fun cancellingStreamLinesTerminatesBlockingSubprocessPromptly() =
        runBlocking {
            if (File.separatorChar != '/') return@runBlocking
            val runner = ProcessCommandRunner()
            val startMs = System.currentTimeMillis()

            val firstLine =
                withTimeout(5_000L) {
                    runner
                        .streamLines(listOf("/bin/sh", "-c", "echo ready; exec sleep 30"))
                        .first()
                }
            val elapsedMs = System.currentTimeMillis() - startMs

            firstLine shouldBe "ready"
            elapsedMs shouldBeLessThan 3_000L
            runner.terminateActiveProcesses()
        }
}
