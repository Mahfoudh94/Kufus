package io.github.drlacheheb.kufus.host

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith

class MacosHostPlatformTest {
    @Test
    fun buildsOsascriptElevatedCommandWithEscapedArguments() {
        val builder = MacosElevationCommandBuilder()

        val elevated =
            builder.buildElevatedCommand(
                listOf("/Applications/Kufus.app/Contents/Resources/kufus-worker", "--flash", "a\"b\\c'd"),
            )

        elevated shouldContainExactly
            listOf(
                "osascript",
                "-e",
                "do shell script " +
                    "\"'/Applications/Kufus.app/Contents/Resources/kufus-worker' '--flash' 'a\\\"b\\\\c'\\\\''d'\" " +
                    "with administrator privileges",
            )
    }

    @Test
    fun rejectsEmptyCommand() {
        val builder = MacosElevationCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.buildElevatedCommand(emptyList())
        }
    }

    @Test
    fun hostModuleProvidesMacosBinarySpecAndElevationBuilder() {
        val koinApp =
            koinApplication {
                modules(hostPlatformModule)
            }
        try {
            val spec = koinApp.koin.get<WorkerBinarySpec>()
            spec.targetName shouldBe "macosArm64"
            spec.executableNames shouldContainExactly listOf("kufus-worker", "kufus-worker.kexe")
            koinApp.koin.get<ElevationCommandBuilder>().shouldBeInstanceOf<MacosElevationCommandBuilder>()
        } finally {
            koinApp.close()
        }
    }
}
