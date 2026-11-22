package io.github.drlacheheb.kufus.host

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WindowsHostPlatformTest {
    @Test
    fun buildsPowerShellRunAsElevatedCommandWithEscapedQuotes() {
        val builder = WindowsElevationCommandBuilder()

        val elevated =
            builder.buildElevatedCommand(
                listOf("C:\\Program Files\\Kufus\\kufus-worker.exe", "--drive", "O'Brien"),
            )

        elevated shouldContainExactly
            listOf(
                "powershell.exe",
                "-NoProfile",
                "-NonInteractive",
                "-Command",
                "\$process = Start-Process -FilePath 'C:\\Program Files\\Kufus\\kufus-worker.exe' " +
                    "-ArgumentList @('--drive', 'O''Brien') -Verb RunAs -Wait -PassThru; exit \$process.ExitCode",
            )
    }

    @Test
    fun omitsArgumentListWhenCommandHasOnlyExecutable() {
        val builder = WindowsElevationCommandBuilder()

        val elevated = builder.buildElevatedCommand(listOf("C:\\kufus-worker.exe"))

        elevated.last() shouldBe
            "\$process = Start-Process -FilePath 'C:\\kufus-worker.exe' -Verb RunAs -Wait -PassThru; exit \$process.ExitCode"
    }

    @Test
    fun rejectsEmptyCommand() {
        val builder = WindowsElevationCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.buildElevatedCommand(emptyList())
        }
    }

    @Test
    fun hostModuleProvidesWindowsBinarySpecAndElevationBuilder() {
        val koinApp =
            koinApplication {
                modules(hostPlatformModule)
            }
        try {
            val spec = koinApp.koin.get<WorkerBinarySpec>()
            spec.targetName shouldBe "mingwX64"
            spec.executableNames shouldContainExactly listOf("kufus-worker.exe")
            koinApp.koin.get<ElevationCommandBuilder>().shouldBeInstanceOf<WindowsElevationCommandBuilder>()
        } finally {
            koinApp.close()
        }
    }
}
