package io.github.drlacheheb.kufus

import io.github.drlacheheb.kufus.di.appModule
import io.github.drlacheheb.kufus.disk.model.HostOsKind
import io.github.drlacheheb.kufus.domain.repository.DiskRepository
import io.github.drlacheheb.kufus.domain.repository.FlashRepository
import io.github.drlacheheb.kufus.domain.repository.FormatRepository
import io.github.drlacheheb.kufus.domain.repository.ImageFilePicker
import io.github.drlacheheb.kufus.domain.repository.ImageRepository
import io.github.drlacheheb.kufus.host.ElevationLauncher
import io.github.drlacheheb.kufus.host.NativeLibrarySpec
import io.github.drlacheheb.kufus.host.WorkerBinarySpec
import io.github.drlacheheb.kufus.host.hostPlatformModule
import io.github.drlacheheb.kufus.ui.flasher.FlasherComponent
import org.koin.dsl.koinApplication
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class DesktopKoinGraphTest {
    @Test
    fun appAndHostModulesResolveAllRequiredDependencies() {
        val koinApp =
            koinApplication {
                modules(appModule, hostPlatformModule)
            }
        try {
            assertNotNull(koinApp.koin.get<WorkerBinarySpec>())
            assertNotNull(koinApp.koin.get<NativeLibrarySpec>())
            assertNotNull(koinApp.koin.get<ElevationLauncher>())
            assertNotNull(koinApp.koin.get<DiskRepository>())
            assertNotNull(koinApp.koin.get<ImageRepository>())
            assertNotNull(koinApp.koin.get<ImageFilePicker>())
            assertNotNull(koinApp.koin.get<FlashRepository>())
            assertNotNull(koinApp.koin.get<FormatRepository>())
            assertNotNull(koinApp.koin.get<HostOsKind>())
            assertNotNull(koinApp.koin.get<FlasherComponent.Factory>())
        } finally {
            koinApp.close()
        }
    }

    @Test
    fun bypassesGlibcExitCleanupOnlyOnLinux() {
        assertTrue(shouldBypassGlibcExitCleanup("Linux"))
        assertFalse(shouldBypassGlibcExitCleanup("Windows 11"))
        assertFalse(shouldBypassGlibcExitCleanup("Mac OS X"))
    }
}
