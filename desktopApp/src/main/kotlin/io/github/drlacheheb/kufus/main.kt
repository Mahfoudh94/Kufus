package io.github.drlacheheb.kufus

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.di.appModule
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.ui.root.DefaultRootComponent
import org.koin.core.context.startKoin
import javax.swing.SwingUtilities

fun <T> runOnUiThread(block: () -> T): T {
    if (SwingUtilities.isEventDispatchThread()) {
        return block()
    }
    var holder: Result<T>? = null
    SwingUtilities.invokeAndWait {
        holder = runCatching { block() }
    }
    val res = holder ?: error("Failed to dispatch to Event Dispatch Thread")
    return res.getOrThrow()
}

fun main() {
    val koin =
        startKoin {
            modules(appModule, io.github.drlacheheb.kufus.host.hostPlatformModule)
        }.koin

    val lifecycle = LifecycleRegistry()
    val diskRepository = koin.get<DiskRepository>()
    val platform = koin.get<Platform>()

    val rootComponent =
        runOnUiThread {
            DefaultRootComponent(
                componentContext = DefaultComponentContext(lifecycle = lifecycle),
                diskRepository = diskRepository,
                platformName = platform.name,
            )
        }

    application {
        val windowState =
            rememberWindowState(
                size = DpSize(720.dp, 540.dp),
                position = WindowPosition.Aligned(Alignment.Center),
            )

        LifecycleController(lifecycle, windowState)

        Window(
            onCloseRequest = ::exitApplication,
            state = windowState,
            title = "Kufus",
        ) {
            App(rootComponent = rootComponent)
        }
    }
}
