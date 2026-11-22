package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.data.jni.JniDiskBridge
import io.github.drlacheheb.kufus.data.jni.JniElevationBridge
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.process.ProcessCommandRunner
import io.github.drlacheheb.kufus.data.repository.DefaultNativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.DefaultWorkerExecutableLocator
import io.github.drlacheheb.kufus.data.repository.NativeDiskBridge
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.data.repository.WorkerExecutableLocator
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.host.HostCommandExecutor
import io.github.drlacheheb.kufus.host.NativeElevationBridge
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<CommandRunner> { ProcessCommandRunner() }
        single<HostCommandExecutor> { get<CommandRunner>() }
        single<WorkerExecutableLocator> { DefaultWorkerExecutableLocator(workerBinarySpec = get()) }
        single<NativeLibraryLocator> { DefaultNativeLibraryLocator(nativeLibrarySpec = get()) }
        single<NativeDiskBridge> { JniDiskBridge(libraryLocator = get()) }
        single<NativeElevationBridge> { JniElevationBridge(libraryLocator = get()) }
        single<DiskRepository> {
            WorkerDiskRepository(
                nativeDiskBridge = get(),
                workerLocator = get(),
                commandRunner = get(),
            )
        }
    }
