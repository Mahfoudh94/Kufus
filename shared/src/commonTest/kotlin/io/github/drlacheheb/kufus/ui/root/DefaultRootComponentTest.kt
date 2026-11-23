package io.github.drlacheheb.kufus.ui.root

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.decompose.value.MutableValue
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.ui.flasher.BootSelectionMode
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

                override fun onSelectBootSelectionMode(mode: BootSelectionMode) = Unit

                override fun onSelectImagePath(path: String) = Unit

                override fun onBrowseImage() = Unit

                override fun onClearImage() = Unit

                override fun onSelectPartitionScheme(scheme: PartitionScheme) = Unit

                override fun onSelectFilesystem(filesystem: FilesystemType) = Unit

                override fun onUpdateVolumeLabel(label: String) = Unit

                override fun onSelectClusterSize(clusterSizeBytes: Int?) = Unit

                override fun onToggleQuickFormat(enabled: Boolean) = Unit

                override fun onRequestFlash() = Unit

                override fun onToggleVerifyAfterWrite(enabled: Boolean) = Unit

                override fun onToggleEjectAfterWrite(enabled: Boolean) = Unit

                override fun onConfirmFlash() = Unit

                override fun onRequestFormat() = Unit

                override fun onToggleEjectAfterFormat(enabled: Boolean) = Unit

                override fun onConfirmFormat() = Unit

                override fun onDismissConfirmation() = Unit

                override fun onCancelFlash() = Unit

                override fun onDismissFlashResult() = Unit
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
