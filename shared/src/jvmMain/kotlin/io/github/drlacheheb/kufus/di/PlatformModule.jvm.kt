package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.data.repository.WorkerDiskRepository
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import org.koin.core.module.Module
import org.koin.dsl.module

actual fun platformModule(): Module =
    module {
        single<DiskRepository> { WorkerDiskRepository() }
    }
