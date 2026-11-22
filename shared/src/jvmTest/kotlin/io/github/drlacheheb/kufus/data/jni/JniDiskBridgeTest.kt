package io.github.drlacheheb.kufus.data.jni

import io.github.drlacheheb.kufus.data.repository.DefaultNativeLibraryLocator
import io.github.drlacheheb.kufus.data.repository.NativeLibraryLocator
import io.github.drlacheheb.kufus.host.NativeLibrarySpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class JniDiskBridgeTest {
    @Test
    fun invokesCompiledNativeSharedLibraryWhenPresent() =
        runTest {
            val locator =
                DefaultNativeLibraryLocator(
                    nativeLibrarySpec =
                        NativeLibrarySpec(
                            targetName = "linuxX64",
                            libraryNames = listOf("libkufusnative.so"),
                        ),
                )
            if (locator.locateLibraryPath() == null) return@runTest

            val bridge = JniDiskBridge(libraryLocator = locator)
            val result = bridge.listUsbDrives()

            result.isSuccess shouldBe true
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
    fun returnsFailureWhenElevationNativeLibraryIsMissing() =
        runTest {
            val bridge = JniElevationBridge(libraryLocator = NativeLibraryLocator { null })

            val result = bridge.launchElevated("/usr/bin/kufus-worker", listOf("--flash"))

            result.isFailure shouldBe true
            result.exceptionOrNull()?.message shouldBe "Native shared library not found"
        }
}
