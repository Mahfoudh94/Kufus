package io.github.drlacheheb.kufus.host

import org.koin.core.module.Module
import org.koin.dsl.module

val hostPlatformModule: Module =
    module {
        single {
            WorkerBinarySpec(
                targetName = "mingwX64",
                executableNames = listOf("kufus-worker.exe"),
            )
        }
        single<ElevationLauncher> { WindowsElevationLauncher(commandExecutor = get()) }
    }
