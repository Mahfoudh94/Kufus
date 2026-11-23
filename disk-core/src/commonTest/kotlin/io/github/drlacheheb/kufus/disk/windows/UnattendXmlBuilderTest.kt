package io.github.drlacheheb.kufus.disk.windows

import io.github.drlacheheb.kufus.disk.model.WindowsUserExperienceOptions
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

class UnattendXmlBuilderTest {
    @Test
    fun buildsValidAutounattendXmlWithBypassFlagsAndEscapedUsername() {
        val options =
            WindowsUserExperienceOptions(
                bypassSystemRequirements = true,
                bypassMicrosoftAccount = true,
                disableDataCollection = true,
                localAccountUsername = "Alex&<Win>",
                regionalLocaleTag = "en_US",
            )

        UnattendXmlBuilder.shouldGenerate(options) shouldBe true
        val xml = UnattendXmlBuilder.buildXml(options)
        xml shouldContain "BypassTPMCheck"
        xml shouldContain "BypassSecureBootCheck"
        xml shouldContain "BypassRAMCheck"
        xml shouldContain "BypassNRO"
        xml shouldContain "<ProtectYourPC>3</ProtectYourPC>"
        xml shouldContain "<Name>Alex&amp;Win</Name>"
        xml shouldContain "<InputLocale>en-US</InputLocale>"
        xml shouldNotContain "<Name>Alex&<Win></Name>"
        UnattendXmlBuilder.escapeXml("a&b<c>\"d'e") shouldBe "a&amp;b&lt;c&gt;&quot;d&apos;e"
    }

    @Test
    fun skipsGenerationWhenAllOptionsDisabled() {
        val disabled =
            WindowsUserExperienceOptions(
                bypassSystemRequirements = false,
                bypassMicrosoftAccount = false,
                disableDataCollection = false,
                localAccountUsername = "   ",
                regionalLocaleTag = null,
            )
        UnattendXmlBuilder.shouldGenerate(disabled) shouldBe false
        UnattendXmlBuilder.sanitizeLocalUsername("///...") shouldBe null
        UnattendXmlBuilder.sanitizeLocaleTag("en_GB") shouldBe "en-GB"
    }
}
