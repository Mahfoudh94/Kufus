package io.github.drlacheheb.kufus

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.arkivanov.decompose.extensions.compose.stack.Children
import com.arkivanov.decompose.extensions.compose.stack.animation.fade
import com.arkivanov.decompose.extensions.compose.stack.animation.stackAnimation
import io.github.drlacheheb.kufus.ui.flasher.FlasherScreen
import io.github.drlacheheb.kufus.ui.root.RootComponent
import io.github.drlacheheb.kufus.ui.theme.KufusTheme

@Composable
fun App(
    rootComponent: RootComponent,
    modifier: Modifier = Modifier,
    isDarkTheme: Boolean = true,
    onToggleTheme: () -> Unit = {},
    onCloseApp: () -> Unit = {},
) {
    KufusTheme(darkTheme = isDarkTheme) {
        val palette = KufusTheme.palette
        Box(
            modifier =
                modifier
                    .fillMaxSize()
                    .background(palette.background),
        ) {
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
