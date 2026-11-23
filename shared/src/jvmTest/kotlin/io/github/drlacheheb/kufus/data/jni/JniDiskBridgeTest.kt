package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.DefaultNativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.disk.model.CompressionFormat
import io.github.drlacheheb.kufus.disk.model.ImageFormat
import io.github.drlacheheb.kufus.host.NativeLibrarySpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.nio.file.Files
import kotlin.test.Test

private const val SECTOR_512 = 512
private const val MBR_SIG_OFFSET = 510
private const val MBR_SIG_0 = 0x55
private const val MBR_SIG_1 = 0xAA
private const val SAMPLE_IMAGE_BYTES = 2048

class JniDiskBridgeTest {
    private val hostLibraryLocator =
        DefaultNativeLibraryLocator(
            nativeLibrarySpec =
                NativeLibrarySpec(
                    targetName = "linuxX64",
                    libraryNames = listOf("libkufusnative.so"),
                ),
        )

    @Test
    fun invokesCompiledNativeSharedLibraryWhenPresent() =
        runTest {
            if (hostLibraryLocator.locateLibraryPath() == null) return@runTest

            val bridge = JniDiskBridge(libraryLocator = hostLibraryLocator)
            val result = bridge.listUsbDrives()

            result.isSuccess shouldBe true
        }

    @Test
    fun invokesCompiledNativeSharedLibraryForImageInspectionWhenPresent() =
        runTest {
            if (hostLibraryLocator.locateLibraryPath() == null) return@runTest
            val tempImage = Files.createTempFile("kufus-jni-img", ".img")
            try {
                val gptBytes =
                    ByteArray(SAMPLE_IMAGE_BYTES).apply {
                        this[MBR_SIG_OFFSET] = MBR_SIG_0.toByte()
                        this[MBR_SIG_OFFSET + 1] = MBR_SIG_1.toByte()
                        "EFI PART".encodeToByteArray().copyInto(this, destinationOffset = SECTOR_512)
                    }
                Files.write(tempImage, gptBytes)

                val bridge = JniImageBridge(libraryLocator = hostLibraryLocator)
                val result = bridge.inspectImage(tempImage.toAbsolutePath().toString())

                result.isSuccess shouldBe true
                val info = result.getOrThrow()
                info.format shouldBe ImageFormat.RAW_IMAGE
                info.compression shouldBe CompressionFormat.NONE
                info.sizeBytes shouldBe SAMPLE_IMAGE_BYTES.toLong()
            } finally {
                Files.deleteIfExists(tempImage)
            }
        }

    @Test
    fun returnsFailureWhenNativeLibraryIsMissing() =
        runTest {
            val bridge = JniDiskBridge(libraryLocator = NativeLibraryLocator { null })

            val result = bridge.listUsbDrives()

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Native shared library not found"
        }

    @Test
    fun returnsFailureWhenImageBridgeNativeLibraryIsMissing() =
        runTest {
            val bridge = JniImageBridge(libraryLocator = NativeLibraryLocator { null })

            val result = bridge.inspectImage("/tmp/ubuntu.iso")

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Native shared library not found"
        }

    @Test
    fun returnsFailureWhenElevationNativeLibraryIsMissing() =
        runTest {
            val bridge = JniElevationBridge(libraryLocator = NativeLibraryLocator { null })

            val result = bridge.launchElevated("/usr/bin/kufus-worker", listOf("--flash"))

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Native shared library not found"
        }
}
