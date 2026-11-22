package io.github.drlacheheb.kufus.ui.flasher

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ExternalFileDropTargetTest {
    @Test
    fun parsesLocalFileUrisAndDecodesSpaces() {
        uriStringToLocalPath("file:///tmp/ubuntu-24.04.iso") shouldBe "/tmp/ubuntu-24.04.iso"
        uriStringToLocalPath("file:/tmp/my%20distro.img.xz") shouldBe "/tmp/my distro.img.xz"
    }

    @Test
    fun rejectsNonFileUrisAndMalformedInput() {
        uriStringToLocalPath("https://example.com/ubuntu.iso") shouldBe null
        uriStringToLocalPath("not a valid uri") shouldBe null
        uriStringToLocalPath("   ") shouldBe null
    }
}
