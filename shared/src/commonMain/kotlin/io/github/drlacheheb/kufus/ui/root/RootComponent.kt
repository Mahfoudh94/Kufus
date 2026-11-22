package io.github.drlacheheb.kufus.ui.root

import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent

interface RootComponent {
    val childStack: Value<ChildStack<*, RootChild>>

    sealed interface RootChild {
        class Flasher(
            val component: FlasherComponent,
        ) : RootChild
    }
}
