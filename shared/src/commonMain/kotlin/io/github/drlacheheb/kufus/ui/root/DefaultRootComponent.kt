package io.github.drlacheheb.kufus.ui.root

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.ui.flasher.DefaultFlasherComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.serialization.Serializable
import kotlin.coroutines.CoroutineContext

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val diskRepository: DiskRepository,
    private val platformName: String = "",
    private val mainContext: CoroutineContext = Dispatchers.Main,
) : RootComponent,
    ComponentContext by componentContext {
    private val navigation = StackNavigation<Config>()

    override val childStack: Value<ChildStack<*, RootComponent.RootChild>> =
        childStack(
            source = navigation,
            serializer = Config.serializer(),
            initialConfiguration = Config.Flasher,
            handleBackButton = true,
            childFactory = ::createChild,
        )

    private fun createChild(
        config: Config,
        context: ComponentContext,
    ): RootComponent.RootChild =
        when (config) {
            is Config.Flasher -> {
                RootComponent.RootChild.Flasher(
                    DefaultFlasherComponent(
                        componentContext = context,
                        diskRepository = diskRepository,
                        platformName = platformName,
                        mainContext = mainContext,
                    ),
                )
            }
        }

    @Serializable
    private sealed interface Config {
        @Serializable
        data object Flasher : Config
    }
}
