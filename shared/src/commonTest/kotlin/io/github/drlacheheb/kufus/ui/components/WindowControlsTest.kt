package io.github.drlacheheb.kufus.ui.components

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WindowControlsTest {
    @Test
    fun `window actions defaults execute safely without exceptions`() {
        val actions = WindowActions()

        actions.isMaximized shouldBe false
        actions.onMinimize()
        actions.onMaximizeRestore()
        actions.onClose()
    }

    @Test
    fun `window actions invoke provided callbacks`() {
        var minimizeCalled = false
        var maximizeCalled = false
        var closeCalled = false

        val actions =
            WindowActions(
                onMinimize = { minimizeCalled = true },
                onMaximizeRestore = { maximizeCalled = true },
                onClose = { closeCalled = true },
                isMaximized = true,
            )

        actions.onMinimize()
        minimizeCalled shouldBe true

        actions.onMaximizeRestore()
        maximizeCalled shouldBe true

        actions.onClose()
        closeCalled shouldBe true

        actions.isMaximized shouldBe true
    }
}
