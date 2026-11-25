package io.github.drlacheheb.kufus

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

class DynamicWindowHeightTest {
    @Test
    fun `returns preferred height when within bounds`() {
        assertEquals(
            584.dp,
            clampDynamicWindowHeight(
                preferredHeight = 584.dp,
                maxAvailableHeight = 900.dp,
                minHeight = 420.dp,
            ),
        )
    }

    @Test
    fun `clamps preferred height to available screen height`() {
        assertEquals(
            640.dp,
            clampDynamicWindowHeight(
                preferredHeight = 760.dp,
                maxAvailableHeight = 640.dp,
                minHeight = 420.dp,
            ),
        )
    }

    @Test
    fun `clamps preferred height to minimum floor`() {
        assertEquals(
            420.dp,
            clampDynamicWindowHeight(
                preferredHeight = 200.dp,
                maxAvailableHeight = 900.dp,
                minHeight = 420.dp,
            ),
        )
    }

    @Test
    fun `handles screen smaller than minimum height without throwing`() {
        assertEquals(
            420.dp,
            clampDynamicWindowHeight(
                preferredHeight = 500.dp,
                maxAvailableHeight = 360.dp,
                minHeight = 420.dp,
            ),
        )
    }
}
