package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.Platform
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.data.repository.WorkerExecutableLocator
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

class PlatformModuleJvmTest {
    @Test
    fun resolvesDiskRepositoryWhenHostBinarySpecIsProvided() {
        val fakeHostModule =
            module {
                single {
                    WorkerBinarySpec(
                        targetName = "linuxX64",
                        executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
                    )
                }
            }
        val koinApp =
            koinApplication {
                modules(appModule, fakeHostModule)
            }
        try {
            koinApp.koin.get<Platform>()
            koinApp.koin.get<CommandRunner>()
            koinApp.koin.get<WorkerExecutableLocator>()
            koinApp.koin.get<FlasherComponent.Factory>()
            koinApp.koin.get<DiskRepository>().shouldBeInstanceOf<WorkerDiskRepository>()
        } finally {
            koinApp.close()
        }
    }
}
