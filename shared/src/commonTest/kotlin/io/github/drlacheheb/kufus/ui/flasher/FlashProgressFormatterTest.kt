package io.github.drlacheheb.kufus.ui.flasher

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class FlashProgressFormatterTest {
    @Test
    fun calculatesFractionEtaAndFormatsDurationsCleanly() {
        calculateProgressFraction(0L, 0L) shouldBe null
        calculateProgressFraction(500L, 1000L) shouldBe 0.5f
        calculateProgressFraction(2000L, 1000L) shouldBe 1.0f

        formatProgressPercent(0L, 0L) shouldBe 0
        formatProgressPercent(999L, 1000L) shouldBe 99
        formatProgressPercent(1000L, 1000L) shouldBe 100

        estimateRemainingSeconds(500L, 1000L, 0L) shouldBe null
        estimateRemainingSeconds(500L, 1000L, 250L) shouldBe 2L
        estimateRemainingSeconds(1000L, 1000L, 250L) shouldBe 0L

        formatDurationSeconds(45L) shouldBe "45s"
        formatDurationSeconds(125L) shouldBe "2m 5s"
        formatDurationSeconds(3665L) shouldBe "1h 1m"
        formatElapsedMillis(400L) shouldBe "1s"
        formatElapsedMillis(62_600L) shouldBe "1m 3s"
    }
}
