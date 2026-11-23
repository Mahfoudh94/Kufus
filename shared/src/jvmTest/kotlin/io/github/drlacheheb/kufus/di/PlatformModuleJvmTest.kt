package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.Platform
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.repository.NativeDiskBridge
import io.github.drlacheheb.kufus.data.repository.NativeImageBridge
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.data.repository.WorkerExecutableLocator
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.HostUserEnvironment
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import io.github.drlacheheb.kufus.host.ElevationLauncher
import io.github.drlacheheb.kufus.host.ElevationResult
import io.github.drlacheheb.kufus.host.HostCommandExecutor
import io.github.drlacheheb.kufus.host.NativeElevationBridge
import io.github.drlacheheb.kufus.host.NativeLibrarySpec
import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import io.kotest.matchers.types.shouldBeInstanceOf
import org.koin.dsl.koinApplication
import org.koin.dsl.module
import kotlin.test.Test

class PlatformModuleJvmTest {
    @Test
    fun resolvesFullDependencyGraphWhenHostArtifactsAreProvided() {
        val fakeHostModule =
            module {
                single {
                    WorkerBinarySpec(
                        targetName = "linuxX64",
                        executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
                    )
                }
                single {
                    NativeLibrarySpec(
                        targetName = "linuxX64",
                        libraryNames = listOf("libkufusnative.so"),
                    )
                }
                single<ElevationLauncher> {
                    ElevationLauncher { _, _ -> Result.success(ElevationResult(exitCode = 0)) }
                }
            }
        val koinApp =
            koinApplication {
                modules(appModule, fakeHostModule)
            }
        try {
            koinApp.koin.get<Platform>()
            koinApp.koin.get<CommandRunner>()
            koinApp.koin.get<HostCommandExecutor>()
            koinApp.koin.get<WorkerExecutableLocator>()
            koinApp.koin.get<NativeLibraryLocator>()
            koinApp.koin.get<NativeDiskBridge>()
            koinApp.koin.get<NativeImageBridge>()
            koinApp.koin.get<ImageRepository>()
            koinApp.koin.get<ImageFilePicker>()
            koinApp.koin.get<FlashRepository>()
            koinApp.koin.get<HostUserEnvironment>()
            koinApp.koin.get<NativeElevationBridge>()
            koinApp.koin.get<FlasherComponent.Factory>()
            koinApp.koin.get<DiskRepository>().shouldBeInstanceOf<WorkerDiskRepository>()
        } finally {
            koinApp.close()
        }
    }
}
