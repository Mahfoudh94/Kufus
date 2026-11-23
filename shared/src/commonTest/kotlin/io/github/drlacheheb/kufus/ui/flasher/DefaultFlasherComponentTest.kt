package io.github.drlacheheb.kufus.ui.flasher

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import io.github.drlacheheb.kufus.disk.CompressionFormat
import io.github.drlacheheb.kufus.disk.FilesystemType
import io.github.drlacheheb.kufus.disk.FlashPhase
import io.github.drlacheheb.kufus.disk.FlashRequest
import io.github.drlacheheb.kufus.disk.FormatRequest
import io.github.drlacheheb.kufus.disk.HostOsKind
import io.github.drlacheheb.kufus.disk.ImageFormat
import io.github.drlacheheb.kufus.disk.ImageInfo
import io.github.drlacheheb.kufus.disk.PartitionScheme
import io.github.drlacheheb.kufus.disk.UsbDrive
import io.github.drlacheheb.kufus.disk.WorkerEvent
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.flow
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

    @Test
    fun transitionsConfirmationProgressCompletionAndBlocksUnsupportedFormats() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val usbDrive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "SanDisk", sizeBytes = 16_000_000_000L)
            val isoInfo = ImageInfo("/ubuntu.iso", 2_000_000_000L, ImageFormat.HYBRID_ISO)
            val flashStream = MutableSharedFlow<WorkerEvent>()
            var recordedRequest: FlashRequest? = null
            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(usbDrive))
                        },
                    imageRepository = ImageRepository { Result.success(isoInfo) },
                    flashRepository =
                        FlashRepository { request ->
                            recordedRequest = request
                            flashStream
                        },
                    mainContext = testDispatcher,
                )

            component.onSelectImagePath("/ubuntu.iso")
            advanceUntilIdle()
            component.state.value.canFlash shouldBe true

            component.onRequestFlash()
            component.state.value.flashState shouldBe FlashOperationState.Confirming(verifyAfterWrite = true, ejectAfterWrite = true)
            component.onToggleVerifyAfterWrite(false)
            component.onToggleEjectAfterWrite(false)
            component.state.value.flashState shouldBe FlashOperationState.Confirming(verifyAfterWrite = false, ejectAfterWrite = false)

            component.onConfirmFlash()
            advanceUntilIdle()
            recordedRequest shouldBe
                FlashRequest(imagePath = "/ubuntu.iso", deviceId = "/dev/sdb", verifyAfterWrite = false, ejectAfterWrite = false)
            component.state.value.isFlashing shouldBe true

            flashStream.emit(WorkerEvent.Started(2_000_000_000L))
            flashStream.emit(WorkerEvent.Progress(FlashPhase.WRITING, 1_000_000_000L, 2_000_000_000L, 120_000_000L))
            advanceUntilIdle()
            component.state.value.flashState shouldBe
                FlashOperationState.Flashing(FlashPhase.WRITING, 1_000_000_000L, 2_000_000_000L, 120_000_000L)

            flashStream.emit(WorkerEvent.Progress(FlashPhase.EJECTING, 0L, 0L, 0L))
            advanceUntilIdle()
            component.state.value.flashState shouldBe FlashOperationState.Flashing(FlashPhase.EJECTING, 0L, 0L, 0L)

            flashStream.emit(WorkerEvent.Completed(verified = false, elapsedMs = 15_000L, ejected = true))
            advanceUntilIdle()
            component.state.value.flashState shouldBe
                FlashOperationState.Completed(verified = false, elapsedMs = 15_000L, ejected = true)

            component.onDismissFlashResult()
            component.state.value.flashState shouldBe FlashOperationState.Idle
        }

    @Test
    fun enablesCompressedImagesGuardsCapacityAndSupportsCancellation() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val usbDrive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "SanDisk", sizeBytes = 16_000_000_000L)
            var currentImage = ImageInfo("/win.iso", 2_000_000_000L, ImageFormat.WINDOWS_ISO)
            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(usbDrive))
                        },
                    imageRepository = ImageRepository { Result.success(currentImage) },
                    flashRepository =
                        FlashRepository {
                            flow {
                                emit(WorkerEvent.Started(1024L))
                                kotlinx.coroutines.awaitCancellation()
                            }
                        },
                    mainContext = testDispatcher,
                )

            component.onSelectImagePath("/win.iso")
            advanceUntilIdle()
            component.state.value.canFlash shouldBe false

            currentImage = ImageInfo("/huge.img.xz", 1_000_000_000L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.XZ, 20_000_000_000L)
            component.onSelectImagePath("/huge.img.xz")
            advanceUntilIdle()
            component.state.value.isImageOversizedForSelectedDrive shouldBe true
            component.state.value.canFlash shouldBe false

            currentImage = ImageInfo("/arch.img.xz", 1_000_000_000L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.XZ, 2_500_000_000L)
            component.onSelectImagePath("/arch.img.xz")
            advanceUntilIdle()
            component.state.value.canFlash shouldBe true

            currentImage = ImageInfo("/fedora.img.zst", 800_000_000L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.ZSTD, 2_500_000_000L)
            component.onSelectImagePath("/fedora.img.zst")
            advanceUntilIdle()
            component.state.value.canFlash shouldBe true

            currentImage = ImageInfo("/debian.iso.gz", 500_000_000L, ImageFormat.COMPRESSED_IMAGE, CompressionFormat.GZIP, 1_200_000_000L)
            component.onSelectImagePath("/debian.iso.gz")
            advanceUntilIdle()
            component.state.value.canFlash shouldBe true

            component.onRequestFlash()
            component.onConfirmFlash()
            advanceUntilIdle()
            component.state.value.isFlashing shouldBe true

            component.onCancelFlash()
            advanceUntilIdle()
            component.state.value.flashState shouldBe FlashOperationState.Idle
        }

    @Test
    fun supportsRufusFormatModeHostFilesystemsMbrLimitAndUncheckedEjectDefault() =
        runTest {
            val testDispatcher = StandardTestDispatcher(testScheduler)
            val hugeDrive = UsbDrive(id = "/dev/sdb", name = "sdb", model = "Large SSD", sizeBytes = 4_000_000_000_000L)
            val normalDrive = UsbDrive(id = "/dev/sdc", name = "sdc", model = "64GB USB", sizeBytes = 64_000_000_000L)
            val formatEvents = MutableSharedFlow<WorkerEvent>()
            var recordedFormatRequest: FormatRequest? = null

            val component =
                DefaultFlasherComponent(
                    componentContext = DefaultComponentContext(lifecycle = LifecycleRegistry()),
                    diskRepository =
                        object : DiskRepository {
                            override suspend fun listUsbDrives(): Result<List<UsbDrive>> = Result.success(listOf(hugeDrive, normalDrive))
                        },
                    imageRepository = ImageRepository { Result.success(ImageInfo("/live.iso", 1_000_000_000L, ImageFormat.HYBRID_ISO)) },
                    formatRepository =
                        FormatRepository { request ->
                            recordedFormatRequest = request
                            formatEvents
                        },
                    hostOs = HostOsKind.WINDOWS,
                    mainContext = testDispatcher,
                )

            advanceUntilIdle()
            component.state.value.availableFilesystems shouldContainExactly
                listOf(FilesystemType.FAT32, FilesystemType.EXFAT, FilesystemType.NTFS)

            component.onSelectBootSelectionMode(BootSelectionMode.NON_BOOTABLE)
            component.state.value.bootSelectionMode shouldBe BootSelectionMode.NON_BOOTABLE
            // 4TB drive blocks MBR & FAT32, allows GPT + exFAT
            component.onSelectPartitionScheme(PartitionScheme.MBR)
            component.state.value.canFormat shouldBe false
            component.onSelectPartitionScheme(PartitionScheme.GPT)
            component.onSelectFilesystem(FilesystemType.EXFAT)
            component.state.value.canFormat shouldBe true

            // Switch to normal 64GB USB + large FAT32 + full wipe
            component.onSelectDrive("/dev/sdc")
            component.onSelectFilesystem(FilesystemType.FAT32)
            component.onUpdateVolumeLabel("my_usb*2026")
            component.onSelectClusterSize(32_768)
            component.onToggleQuickFormat(false)
            component.state.value.canFormat shouldBe true

            // Confirm dialog must default ejectAfterFormat = false
            component.onRequestFormat()
            component.state.value.flashState shouldBe FlashOperationState.ConfirmingFormat(ejectAfterFormat = false)

            component.onConfirmFormat()
            advanceUntilIdle()
            recordedFormatRequest shouldBe
                FormatRequest(
                    deviceId = "/dev/sdc",
                    partitionScheme = PartitionScheme.GPT,
                    filesystem = FilesystemType.FAT32,
                    volumeLabel = "MY_USB2026",
                    clusterSizeBytes = 32_768,
                    quickFormat = false,
                    ejectAfterFormat = false,
                )

            formatEvents.emit(WorkerEvent.Started(normalDrive.sizeBytes))
            formatEvents.emit(WorkerEvent.Progress(FlashPhase.WIPING, 32_000_000_000L, 64_000_000_000L, 200_000_000L))
            formatEvents.emit(WorkerEvent.Progress(FlashPhase.PARTITIONING, 0L, 0L, 0L))
            formatEvents.emit(WorkerEvent.Completed(verified = false, elapsedMs = 12_000L, ejected = false))
            advanceUntilIdle()

            component.state.value.flashState shouldBe
                FlashOperationState.Completed(
                    verified = false,
                    elapsedMs = 12_000L,
                    ejected = false,
                    operationKind = ActiveOperationKind.FORMAT,
                    summaryDetail = "GPT • FAT32 • MY_USB2026",
                )

            // Dropping/selecting an image switches back to DISK_OR_ISO automatically
            component.onSelectImagePath("/live.iso")
            advanceUntilIdle()
            component.state.value.bootSelectionMode shouldBe BootSelectionMode.DISK_OR_ISO
        }
}
