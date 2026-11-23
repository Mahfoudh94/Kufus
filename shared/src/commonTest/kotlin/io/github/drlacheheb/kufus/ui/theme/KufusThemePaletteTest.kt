package io.github.drlacheheb.kufus.ui.theme

import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test

class KufusThemePaletteTest {
    @Test
    fun `dark and light palettes expose matching mode flag and distinct surface colors`() {
        val dark = kufusPalette(isDark = true)
        val light = kufusPalette(isDark = false)

        dark.isDark shouldBe true
        light.isDark shouldBe false

        dark.background shouldBe DarkBackground
        light.background shouldBe LightBackground
        dark.background shouldNotBe light.background
        dark.onSurface shouldNotBe light.onSurface
        dark.primary shouldNotBe light.primary
    }
}
