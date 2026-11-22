package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.Platform
import io.github.drlacheheb.kufus.getPlatform
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformModule(): Module

val appModule: Module =
    module {
        single<Platform> { getPlatform() }
        includes(platformModule())
    }
