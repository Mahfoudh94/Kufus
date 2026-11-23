package io.github.drlacheheb.kufus.worker

import io.github.drlacheheb.kufus.disk.platform.openPlatformSeekableReader
import io.github.drlacheheb.kufus.disk.windows.WindowsInstallerEngine
import kotlin.test.Test
import kotlin.test.assertEquals

class LinuxUefiNtfsVerificationTest {
    @Test
    fun packagedUefiNtfsImageMatchesExpectedSha256() {
        val candidates =
            listOf(
                "packaging/uefi/uefi-ntfs.img",
                "../packaging/uefi/uefi-ntfs.img",
            )
        val opened =
            candidates.firstNotNullOfOrNull { path ->
                openPlatformSeekableReader(path).getOrNull()
            } ?: error("Missing packaged uefi-ntfs.img")
        try {
            assertEquals(1_048_576L, opened.sizeBytes())
            val bytes = opened.readAt(0L, opened.sizeBytes().toInt())
            WindowsInstallerEngine.verifyUefiNtfsBytes(bytes)
        } finally {
            opened.close()
        }
    }
}
