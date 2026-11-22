package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
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

    @Test
    fun inspectsSelectedImageEvaluatesDriveCapacityAndClearsCleanly() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val smallDrive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "Small USB", sizeBytes = 4_000_000_000L)
            val largeDrive = UsbDrive(id = "/dev/sdc", name = "sdc", model = "Large USB", sizeBytes = 16_000_000_000L)
            val expectedInfo =
                ImageInfo(
                    path = "/images/ubuntu.iso.gz",
                    sizeBytes = 1_800_000_000L,
                    format = ImageFormat.COMPRESSED_IMAGE,
                    compression = CompressionFormat.GZIP,
                    uncompressedSizeHintBytes = 6_000_000_000L,
                )
            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(smallDrive, largeDrive))
                        },
                    imageRepository = ImageRepository { Result.success(expectedInfo) },
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()
            component.onSelectImagePath("/images/ubuntu.iso.gz")
            advanceUntilIdle()

            val stateOnSmallDrive = component.state.value
            stateOnSmallDrive.selectedImagePath shouldBe "/images/ubuntu.iso.gz"
            stateOnSmallDrive.inspectedImage shouldBe expectedInfo
            stateOnSmallDrive.isInspectingImage shouldBe false
            stateOnSmallDrive.requiredSizeBytes shouldBe 6_000_000_000L
            stateOnSmallDrive.isImageOversizedForSelectedDrive shouldBe true
            stateOnSmallDrive.canFlash shouldBe false

            component.onSelectDrive("/dev/sdc")
            val stateOnLargeDrive = component.state.value
            stateOnLargeDrive.isImageOversizedForSelectedDrive shouldBe false
            stateOnLargeDrive.canFlash shouldBe true

            component.onClearImage()
            val clearedState = component.state.value
            clearedState.selectedImagePath shouldBe null
            clearedState.inspectedImage shouldBe null
            clearedState.canFlash shouldBe false
        }

    @Test
    fun handlesBrowsingCancellationAndInspectionErrors() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            var nextPick: String? = null
            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(emptyList())
                        },
                    imageRepository = ImageRepository { Result.failure(IllegalStateException("Unreadable image")) },
                    imageFilePicker = ImageFilePicker { nextPick },
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()
            component.onBrowseImage()
            advanceUntilIdle()
            component.state.value.selectedImagePath shouldBe null
            component.state.value.imageErrorMessage shouldBe null

            nextPick = "/bad/image.iso"
            component.onBrowseImage()
            advanceUntilIdle()
            component.state.value.selectedImagePath shouldBe "/bad/image.iso"
            component.state.value.inspectedImage shouldBe null
            component.state.value.imageErrorMessage shouldBe "Unreadable image"
        }

    @Test
    fun ignoresStaleImageInspectionResultWhenReplacedByNewSelection() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val firstGate = CompletableDeferred<Result<ImageInfo>>()
            val secondInfo =
                ImageInfo(
                    path = "/second.iso",
                    sizeBytes = 1024L,
                    format = ImageFormat.HYBRID_ISO,
                )
            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(emptyList())
                        },
                    imageRepository =
                        ImageRepository { path ->
                            if (path == "/first.iso") firstGate.await() else Result.success(secondInfo)
                        },
                    mainContext = testDispatcher,
                )

            component.onSelectImagePath("/first.iso")
            component.onSelectImagePath("/second.iso")
            advanceUntilIdle()
            firstGate.complete(
                Result.success(ImageInfo(path = "/first.iso", sizeBytes = 512L, format = ImageFormat.RAW_IMAGE)),
            )
            advanceUntilIdle()

            component.state.value.selectedImagePath shouldBe "/second.iso"
            component.state.value.inspectedImage shouldBe secondInfo
        }
}
