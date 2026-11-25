package io.github.drlacheheb.kufus.ui.components

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class WindowControlsTest {
    @Test
    fun `window actions defaults execute safely without exceptions`() {
        val actions = WindowActions()

        actions.onMinimize()
        actions.onClose()
        actions.onPreferredContentHeightChange(600.dp)
    }

    @Test
    fun `window actions invoke provided callbacks`() {
        var minimizeCalled = false
        var closeCalled = false
        var reportedHeight: Dp? = null

        val actions =
            WindowActions(
                onMinimize = { minimizeCalled = true },
                onClose = { closeCalled = true },
                onPreferredContentHeightChange = { reportedHeight = it },
            )

        actions.onMinimize()
        minimizeCalled shouldBe true

        actions.onClose()
        closeCalled shouldBe true

        actions.onPreferredContentHeightChange(584.dp)
        reportedHeight shouldBe 584.dp
    }
}
