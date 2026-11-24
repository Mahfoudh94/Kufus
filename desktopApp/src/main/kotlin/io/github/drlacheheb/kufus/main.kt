package io.github.drlacheheb.kufus

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.extensions.compose.lifecycle.LifecycleController
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.github.drlacheheb.kufus.data.jni.KufusJniLibrary
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.di.appModule
import io.github.drlacheheb.kufus.host.hostPlatformModule
import io.github.drlacheheb.kufus.ui.components.LocalWindowActions
import io.github.drlacheheb.kufus.ui.components.WindowActions
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import io.github.drlacheheb.kufus.ui.root.DefaultRootComponent
import io.github.drlacheheb.kufus.ui.root.RootComponent
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
import java.awt.Dimension
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

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

internal fun shouldBypassGlibcExitCleanup(osName: String): Boolean = osName.lowercase().contains("linux")

internal fun cleanupAndResolveExitAction(
    lifecycle: LifecycleRegistry,
    koin: Koin,
    libraryLocator: NativeLibraryLocator?,
): () -> Nothing {
    if (lifecycle.state != Lifecycle.State.DESTROYED) {
        runCatching { runOnUiThread { lifecycle.destroy() } }
    }
    koin.getOrNull<CommandRunner>()?.terminateActiveProcesses()
    runCatching { stopKoin() }
    runCatching { System.out.flush() }
    runCatching { System.err.flush() }

    if (shouldBypassGlibcExitCleanup(System.getProperty("os.name").orEmpty())) {
        val loaded =
            KufusJniLibrary.isLoaded() ||
                libraryLocator?.locateLibraryPath()?.let { path ->
                    runCatching { KufusJniLibrary.loadIfNeeded(path) }.isSuccess
                } == true
        if (loaded) {
            return {
                KufusJniLibrary.terminateProcessImmediate(0)
                exitProcess(0)
            }
        }
    }
    return { exitProcess(0) }
}

fun main() {
    val koin =
        startKoin {
            modules(appModule, hostPlatformModule)
        }.koin

    val lifecycle = LifecycleRegistry()
    val flasherFactory = koin.get<FlasherComponent.Factory>()

    val rootComponent =
        runOnUiThread {
            DefaultRootComponent(
                componentContext = DefaultComponentContext(lifecycle = lifecycle),
                flasherFactory = flasherFactory,
            )
        }

    try {
        application(exitProcessOnExit = false) {
            KufusMainWindow(
                rootComponent = rootComponent,
                lifecycle = lifecycle,
                onExit = ::exitApplication,
            )
        }
    } finally {
        val libraryLocator = runCatching { koin.getOrNull<NativeLibraryLocator>() }.getOrNull()
        val exitAction = cleanupAndResolveExitAction(lifecycle, koin, libraryLocator)
        exitAction()
    }
}

@Composable
private fun KufusMainWindow(
    rootComponent: RootComponent,
    lifecycle: LifecycleRegistry,
    onExit: () -> Unit,
) {
    val windowState =
        rememberWindowState(
            size = DpSize(540.dp, 720.dp),
            position = WindowPosition.Aligned(Alignment.Center),
        )
    var isDarkTheme by remember { mutableStateOf(true) }

    val windowIcon =
        remember {
            val stream: InputStream? = object {}.javaClass.getResourceAsStream("/kufus-icon.png")
            stream?.use { ImageIO.read(it)?.toComposeImageBitmap()?.let(::BitmapPainter) }
        }

    LifecycleController(lifecycle, windowState)

    Window(
        onCloseRequest = onExit,
        state = windowState,
        title = "Kufus",
        icon = windowIcon,
        undecorated = true,
        resizable = true,
    ) {
        LaunchedEffect(window) {
            window.minimumSize = Dimension(480, 620)
        }

        val windowActions =
            WindowActions(
                onMinimize = { windowState.isMinimized = true },
                onMaximizeRestore = {
                    windowState.placement =
                        if (windowState.placement == WindowPlacement.Maximized) {
                            WindowPlacement.Floating
                        } else {
                            WindowPlacement.Maximized
                        }
                },
                onClose = onExit,
                isMaximized = windowState.placement == WindowPlacement.Maximized,
                draggableArea = { modifier, content ->
                    WindowDraggableArea(modifier = modifier) {
                        content()
                    }
                },
            )

        CompositionLocalProvider(LocalWindowActions provides windowActions) {
            App(
                rootComponent = rootComponent,
                isDarkTheme = isDarkTheme,
                showWindowBorder = true,
                onToggleTheme = { isDarkTheme = !isDarkTheme },
                onCloseApp = onExit,
            )
        }
    }
}
