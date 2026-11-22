package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WindowsDiskParserTest {
    @Test
    fun formatsWindowsDriveModelCleanly() {
        buildWindowsDriveModel(" Generic ", " Flash Disk ", "PhysicalDrive1") shouldBe "Generic Flash Disk"
        buildWindowsDriveModel(null, null, "PhysicalDrive1") shouldBe "PhysicalDrive1"
    }

    @Test
    fun normalizesDriveLettersAndDetectsSystemMounts() {
        normalizeWindowsDriveLetter(" f:\\ ") shouldBe "F:"
        isWindowsSystemMount("c:\\", "C:") shouldBe true
        isWindowsSystemMount("E:\\", "C:") shouldBe false
    }

    @Test
    fun quotesWindowsCommandLineArgumentsSafely() {
        formatWindowsCommandArguments(emptyList()) shouldBe null
        formatWindowsCommandArguments(listOf("--flash", "C:\\My Images\\ubuntu\"24\\.iso")) shouldBe
            "--flash \"C:\\My Images\\ubuntu\\\"24\\.iso\""
        formatWindowsCommandArguments(listOf("C:\\dir with space\\", "")) shouldBe
            "\"C:\\dir with space\\\\\" \"\""
    }
}
