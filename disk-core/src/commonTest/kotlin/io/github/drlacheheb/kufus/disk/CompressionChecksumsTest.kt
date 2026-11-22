package io.github.drlacheheb.kufus.disk

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class CompressionChecksumsTest {
    @Test
    fun matchesStandardCrc32TestVectorAcrossChunkedUpdates() {
        val vector = "123456789".encodeToByteArray()
        Crc32.compute(vector) shouldBe 0xCBF4_3926L

        val streaming = Crc32()
        streaming.update(vector, offset = 0, length = 4)
        streaming.update(vector, offset = 4, length = vector.size - 4)
        streaming.value() shouldBe 0xCBF4_3926L
    }
}
