package io.github.drlacheheb.kufus.host

import org.koin.core.module.Module
import org.koin.dsl.module

val hostPlatformModule: Module =
    module {
        single {
            WorkerBinarySpec(
                targetName = "linuxX64",
                executableNames = listOf("kufus-worker", "kufus-worker.kexe"),
            )
        }
        single<ElevationLauncher> { LinuxElevationLauncher(commandExecutor = get()) }
    }
