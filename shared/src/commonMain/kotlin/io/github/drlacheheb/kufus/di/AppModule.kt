package io.github.drlacheheb.kufus.di

import io.github.drlacheheb.kufus.disk.format.core.resolveHostOsKind
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.domain.repository.HostUserEnvironment
import io.github.drlacheheb.kufus.platform.Platform
import io.github.drlacheheb.kufus.platform.getPlatform
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
                    hostUserEnvironment = getOrNull<HostUserEnvironment>() ?: HostUserEnvironment.Empty,
                    platformName = get<Platform>().name,
                    hostOs = getOrNull<HostOsKind>() ?: resolveHostOsKind(get<Platform>().name),
                )
            }
        }
        includes(platformModule())
    }
