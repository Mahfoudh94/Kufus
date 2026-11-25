package io.github.drlacheheb.kufus.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WindowControlsTest {
    @Test
    fun `window actions defaults execute safely without exceptions`() {
        val actions = WindowActions()

        actions.isMaximized shouldBe false
        actions.canMaximize shouldBe true
        actions.onMinimize()
        actions.onMaximizeRestore()
        actions.onClose()
        actions.onPreferredContentHeightChange(600.dp)
    }

    @Test
    fun `window actions invoke provided callbacks`() {
        var minimizeCalled = false
        var maximizeCalled = false
        var closeCalled = false
        var reportedHeight: Dp? = null

        val actions =
            WindowActions(
                onMinimize = { minimizeCalled = true },
                onMaximizeRestore = { maximizeCalled = true },
                onClose = { closeCalled = true },
                isMaximized = true,
                canMaximize = false,
                onPreferredContentHeightChange = { reportedHeight = it },
            )

        actions.onMinimize()
        minimizeCalled shouldBe true

        actions.onMaximizeRestore()
        maximizeCalled shouldBe true

        actions.onClose()
        closeCalled shouldBe true

        actions.isMaximized shouldBe true
        actions.canMaximize shouldBe false

        actions.onPreferredContentHeightChange(584.dp)
        reportedHeight shouldBe 584.dp
    }
}
