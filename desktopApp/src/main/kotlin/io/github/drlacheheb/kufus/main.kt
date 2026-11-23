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
import com.arkivanov.essenty.lifecycle.Lifecycle
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import io.github.drlacheheb.kufus.data.jni.KufusJniLibrary
import io.github.drlacheheb.kufus.data.process.CommandRunner
import io.github.drlacheheb.kufus.data.process.ProcessCommandRunner
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.di.appModule
import io.github.drlacheheb.kufus.host.hostPlatformModule
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import io.github.drlacheheb.kufus.ui.root.DefaultRootComponent
import org.koin.core.Koin
import org.koin.core.context.startKoin
import org.koin.core.context.stopKoin
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
    (koin.getOrNull<CommandRunner>() as? ProcessCommandRunner)?.terminateActiveProcesses()
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
    } finally {
        val libraryLocator = runCatching { koin.getOrNull<NativeLibraryLocator>() }.getOrNull()
        val exitAction = cleanupAndResolveExitAction(lifecycle, koin, libraryLocator)
        exitAction()
    }
}
