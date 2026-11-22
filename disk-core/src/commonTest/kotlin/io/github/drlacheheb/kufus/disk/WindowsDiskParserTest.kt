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
}
