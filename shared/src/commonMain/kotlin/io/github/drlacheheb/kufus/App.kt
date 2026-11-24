package io.github.drlacheheb.kufus

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import io.github.drlacheheb.kufus.ui.components.ProvidePlatformContextMenu
import io.github.drlacheheb.kufus.ui.flasher.FlasherScreen
import io.github.drlacheheb.kufus.ui.root.RootComponent
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@Composable
fun App(
    rootComponent: RootComponent,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    showWindowBorder: Boolean = false,
    onToggleTheme: () -> Unit = {},
    onCloseApp: () -> Unit = {},
) {
    KufusTheme(darkTheme = isDarkTheme) {
        ProvidePlatformContextMenu {
            val palette = KufusTheme.palette
            val containerModifier =
                modifier
                    .fillMaxSize()
                    .background(palette.background)
                    .let { base -> if (showWindowBorder) base.border(1.dp, palette.border) else base }
            Box(modifier = containerModifier) {
                Children(
                    stack = rootComponent.childStack,
                    animation = stackAnimation(fade()),
                ) { child ->
                    when (val instance = child.instance) {
                        is RootComponent.RootChild.Flasher -> {
                            FlasherScreen(
                                component = instance.component,
                                isDarkTheme = isDarkTheme,
                                onToggleTheme = onToggleTheme,
                                onCloseApp = onCloseApp,
                            )
                        }
                    }
                }
            }
        }
    }
}
