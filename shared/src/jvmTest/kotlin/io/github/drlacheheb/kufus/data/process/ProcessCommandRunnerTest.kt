package io.github.drlacheheb.kufus.data.process

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
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
}
