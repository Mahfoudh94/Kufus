package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.process.ProcessCommandRunner
import io.github.drlacheheb.kufus.data.repository.DefaultWorkerExecutableLocator
import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.data.repository.WorkerExecutableLocator
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<CommandRunner> { ProcessCommandRunner() }
        single<WorkerExecutableLocator> { DefaultWorkerExecutableLocator(workerBinarySpec = get()) }
        single<DiskRepository> {
            WorkerDiskRepository(
                workerLocator = get(),
                commandRunner = get(),
            )
        }
    }
