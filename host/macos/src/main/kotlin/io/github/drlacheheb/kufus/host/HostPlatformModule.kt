package io.github.drlacheheb.kufus.host

import org.koin.core.module.Module
import org.koin.dsl.module

val hostPlatformModule: Module =
    module {
        single {
            WorkerBinarySpec(
                targetName = "macosArm64",
                executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
            )
        }
        single {
            NativeLibrarySpec(
                targetName = "macosArm64",
                libraryNames = listOf("libkufusnative.dylib"),
            )
        }
        single<ElevationLauncher> { MacosElevationLauncher(commandExecutor = get()) }
    }
