package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.Platform
import io.github.drlacheheb.kufus.disk.HostOsKind
import io.github.drlacheheb.kufus.disk.resolveHostOsKind
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.getPlatform
import io.github.drlacheheb.kufus.ui.flasher.DefaultFlasherComponent
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import kotlinx.coroutines.flow.emptyFlow
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
                    imageRepository = get(),
                    imageFilePicker = get(),
                    flashRepository = get(),
                    formatRepository = getOrNull<FormatRepository>() ?: FormatRepository { emptyFlow() },
                    platformName = get<Platform>().name,
                    hostOs = getOrNull<HostOsKind>() ?: resolveHostOsKind(get<Platform>().name),
                )
            }
        }
        includes(platformModule())
    }
