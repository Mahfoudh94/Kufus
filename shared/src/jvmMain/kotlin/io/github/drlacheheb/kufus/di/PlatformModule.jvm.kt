package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.data.picker.AwtImageFilePicker
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.process.ProcessCommandRunner
import io.github.drlacheheb.kufus.data.repository.DefaultWorkerExecutableLocator
import io.github.drlacheheb.kufus.data.repository.FlashEventLogFactory
import io.github.drlacheheb.kufus.data.repository.JvmFlashEventLogFactory
import io.github.drlacheheb.kufus.data.repository.JvmHostUserEnvironment
import io.github.drlacheheb.kufus.data.repository.JvmImageRepository
import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.data.repository.WorkerExecutableLocator
import io.github.drlacheheb.kufus.data.repository.WorkerFlashRepository
import io.github.drlacheheb.kufus.data.repository.WorkerFormatRepository
import io.github.drlacheheb.kufus.disk.format.core.resolveHostOsKind
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.domain.repository.HostUserEnvironment
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import io.github.drlacheheb.kufus.host.HostCommandExecutor
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<CommandRunner> { ProcessCommandRunner() }
        single<HostCommandExecutor> { get<CommandRunner>() }
        single<WorkerExecutableLocator> { DefaultWorkerExecutableLocator(workerBinarySpec = get()) }
        single<ImageFilePicker> { AwtImageFilePicker() }
        single<ImageRepository> { JvmImageRepository() }
        single<FlashEventLogFactory> { JvmFlashEventLogFactory() }
        single<HostUserEnvironment> { JvmHostUserEnvironment() }
        single<HostOsKind> { resolveHostOsKind(System.getProperty("os.name").orEmpty()) }
        single<FlashRepository> {
            WorkerFlashRepository(
                workerLocator = get(),
                elevationLauncher = get(),
                eventLogFactory = get(),
            )
        }
        single<FormatRepository> {
            WorkerFormatRepository(
                workerLocator = get(),
                elevationLauncher = get(),
                eventLogFactory = get(),
            )
        }
        single<DiskRepository> {
            WorkerDiskRepository(
                workerLocator = get(),
                commandRunner = get(),
            )
        }
    }
