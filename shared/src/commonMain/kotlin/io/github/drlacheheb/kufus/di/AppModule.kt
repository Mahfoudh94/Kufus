package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.Platform
import io.github.drlacheheb.kufus.getPlatform
import io.github.drlacheheb.kufus.ui.flasher.DefaultFlasherComponent
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import org.koin.core.module.Module
import org.koin.dsl.module

expect fun platformModule(): Module

val appModule: Module =
    module {
        single<Platform> { getPlatform() }
        single<FlasherComponent.Factory> {
            FlasherComponent.Factory { componentContext ->
                DefaultFlasherComponent(
                    componentContext = componentContext,
                    diskRepository = get(),
                    platformName = get<Platform>().name,
                )
            }
        }
        includes(platformModule())
    }
