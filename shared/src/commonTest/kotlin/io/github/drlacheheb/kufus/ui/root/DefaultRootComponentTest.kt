package io.github.drlacheheb.kufus.ui.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import io.github.drlacheheb.kufus.ui.flasher.FlasherUiState
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class DefaultRootComponentTest {
    @Test
    fun delegatesInitialFlasherChildCreationToInjectedFactory() {
        val fakeFlasher =
            object : FlasherComponent {
                override val state: Value<FlasherUiState> = MutableValue(FlasherUiState())

                override fun onRefreshDrives() = Unit

                override fun onSelectDrive(driveId: String) = Unit
            }
        val root =
            DefaultRootComponent(
                componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                flasherFactory = FlasherComponent.Factory { fakeFlasher },
            )

        val activeChild = root.childStack.value.active.instance
        activeChild.shouldBeInstanceOf<RootComponent.RootChild.Flasher>()
    }
}
