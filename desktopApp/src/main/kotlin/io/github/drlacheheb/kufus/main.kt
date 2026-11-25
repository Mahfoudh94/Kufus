package io.github.drlacheheb.kufus

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.Dp
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
import io.github.drlacheheb.kufus.data.process.CommandRunner
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
import java.awt.Toolkit
import java.io.InputStream
import javax.imageio.ImageIO
import javax.swing.SwingUtilities
import kotlin.system.exitProcess
import java.awt.Window as AwtWindow

private const val WINDOW_WIDTH_DP = 520
private const val INITIAL_WINDOW_HEIGHT_DP = 576
private const val MIN_WINDOW_HEIGHT_DP = 420
private const val MAX_FALLBACK_WINDOW_HEIGHT_DP = 900
private const val SCREEN_VERTICAL_MARGIN_DP = 48

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

internal fun cleanupAppResources(
    lifecycle: LifecycleRegistry,
    koin: Koin,
) {
    if (lifecycle.state != Lifecycle.State.DESTROYED) {
        runCatching { runOnUiThread { lifecycle.destroy() } }
    }
    koin.getOrNull<CommandRunner>()?.terminateActiveProcesses()
    runCatching { stopKoin() }
    runCatching { System.out.flush() }
    runCatching { System.err.flush() }
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
        cleanupAppResources(lifecycle, koin)
        exitProcess(0)
    }
}

internal fun clampDynamicWindowHeight(
    preferredHeight: Dp,
    maxAvailableHeight: Dp,
    minHeight: Dp = MIN_WINDOW_HEIGHT_DP.dp,
): Dp {
    val effectiveMax = maxOf(minHeight, maxAvailableHeight)
    return preferredHeight.coerceIn(minHeight, effectiveMax)
}

private fun computeMaxWindowHeightDp(window: AwtWindow): Dp {
    val config = window.graphicsConfiguration ?: return MAX_FALLBACK_WINDOW_HEIGHT_DP.dp
    val bounds = config.bounds
    val insets = Toolkit.getDefaultToolkit().getScreenInsets(config)
    val usableHeight = bounds.height - insets.top - insets.bottom
    val maxHeight = (usableHeight - SCREEN_VERTICAL_MARGIN_DP).coerceAtLeast(MIN_WINDOW_HEIGHT_DP)
    return maxHeight.dp
}

@Composable
private fun KufusMainWindow(
    rootComponent: RootComponent,
    lifecycle: LifecycleRegistry,
    onExit: () -> Unit,
) {
    val windowState =
        rememberWindowState(
            size = DpSize(WINDOW_WIDTH_DP.dp, INITIAL_WINDOW_HEIGHT_DP.dp),
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
        resizable = false,
    ) {
        val onPreferredContentHeightChange: (Dp) -> Unit =
            remember(window, windowState) {
                { preferredHeight ->
                    val clampedHeight =
                        clampDynamicWindowHeight(
                            preferredHeight = preferredHeight,
                            maxAvailableHeight = computeMaxWindowHeightDp(window),
                        )
                    val targetSize = DpSize(WINDOW_WIDTH_DP.dp, clampedHeight)
                    if (windowState.size != targetSize) {
                        windowState.size = targetSize
                    }
                }
            }

        val windowActions =
            remember(window, windowState, onExit, onPreferredContentHeightChange) {
                WindowActions(
                    onMinimize = { windowState.isMinimized = true },
                    onClose = onExit,
                    isMaximized = false,
                    canMaximize = false,
                    onPreferredContentHeightChange = onPreferredContentHeightChange,
                    draggableArea = { modifier, content ->
                        WindowDraggableArea(modifier = modifier) {
                            content()
                        }
                    },
                )
            }

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
