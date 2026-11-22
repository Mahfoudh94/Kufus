package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

@OptIn(ExperimentalCoroutinesApi::class)
class DefaultFlasherComponentTest {
    @Test
    fun loadsAvailableUsbDrivesOnInitialization() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val expectedDrive =
                UsbDrive(
                    id = "/dev/sdb",
                    name = "sdb",
                    model = "SanDisk Extreme",
                    sizeBytes = 64_000_000_000L,
                )
            val repository =
                object : DiskRepository {
                    override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(expectedDrive))
                }

            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository = repository,
                    platformName = "Linux",
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()

            component.state.value.drives shouldContainExactly listOf(expectedDrive)
            component.state.value.selectedDriveId shouldBe "/dev/sdb"
            component.state.value.isScanning shouldBe false
            component.state.value.errorMessage shouldBe null
        }

    @Test
    fun exposesErrorMessageWhenDriveScanFails() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val repository =
                object : DiskRepository {
                    override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.failure(IllegalStateException("native bridge unavailable"))
                }

            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository = repository,
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()

            component.state.value.isScanning shouldBe false
            component.state.value.errorMessage shouldBe "native bridge unavailable"
            component.state.value.drives shouldBe emptyList()
        }

    @Test
    fun updatesSelectedDriveWhenUserSelectsAnotherDrive() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val firstDrive = UsbDrive(id = "/dev/sda", name = "sda", model = "Drive A", sizeBytes = 16_000_000_000L)
            val secondDrive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "Drive B", sizeBytes = 32_000_000_000L)
            val repository =
                object : DiskRepository {
                    override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(firstDrive, secondDrive))
                }

            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository = repository,
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()
            component.onSelectDrive("/dev/sdb")

            component.state.value.selectedDriveId shouldBe "/dev/sdb"
        }

    @Test
    fun updatesDrivesAutomaticallyWhenHotplugStreamEmits() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val lifecycle = LifecycleRegistry().apply { onCreate() }
            val pluggedDrive = UsbDrive(id = "/dev/sdc", name = "sdc", model = "Hotplug USB", sizeBytes = 32_000_000_000L)
            val updates = MutableSharedFlow<Result<List<UsbDrive>>>()
            val repository =
                object : DiskRepository {
                    override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(emptyList())

                    override fun observeUsbDrives(): Flow<Result<List<UsbDrive>>> = updates
                }

            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = lifecycle),
                    diskRepository = repository,
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()
            component.state.value
                .drives
                .shouldBeEmpty()

            updates.emit(Result.success(listOf(pluggedDrive)))
            advanceUntilIdle()
            component.state.value.drives shouldContainExactly listOf(pluggedDrive)
            component.state.value.selectedDriveId shouldBe "/dev/sdc"

            updates.emit(Result.success(emptyList()))
            advanceUntilIdle()
            val afterUnplug = component.state.value
            afterUnplug.drives.shouldBeEmpty()
            afterUnplug.selectedDriveId shouldBe null

            lifecycle.onDestroy()
        }
}
