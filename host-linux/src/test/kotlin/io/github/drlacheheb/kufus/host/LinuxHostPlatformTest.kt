package io.github.drlacheheb.kufus.host

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFailsWith

class LinuxHostPlatformTest {
    @Test
    fun buildsPkexecElevatedCommand() {
        val builder = LinuxElevationCommandBuilder()

        val elevated = builder.buildElevatedCommand(listOf("/usr/bin/kufus-worker", "--list"))

        elevated shouldContainExactly listOf("pkexec", "/usr/bin/kufus-worker", "--list")
    }

    @Test
    fun rejectsEmptyCommand() {
        val builder = LinuxElevationCommandBuilder()

        assertFailsWith<IllegalArgumentException> {
            builder.buildElevatedCommand(emptyList())
        }
    }

    @Test
    fun hostModuleProvidesLinuxBinarySpecAndElevationBuilder() {
        val koinApp =
            koinApplication {
                modules(hostPlatformModule)
            }
        try {
            val spec = koinApp.koin.get<WorkerBinarySpec>()
            spec.targetName shouldBe "linuxX64"
            spec.executableNames shouldContainExactly listOf("kufus-worker", "kufus-worker.kexe")
            koinApp.koin.get<ElevationCommandBuilder>().shouldBeInstanceOf<LinuxElevationCommandBuilder>()
        } finally {
            koinApp.close()
        }
    }
}
