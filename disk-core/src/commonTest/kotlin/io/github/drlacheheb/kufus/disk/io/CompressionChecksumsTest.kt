package io.github.drlacheheb.kufus.disk.io

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

    @Test
    fun matchesStandardCrc32cVectorAndRawSeededChaining() {
        val vector = "123456789".encodeToByteArray()
        Crc32c.compute(vector) shouldBe 0xE306_9283L

        val uuidSeed = Crc32c.computeRaw(seed = -1, input = ByteArray(16) { it.toByte() })
        val chained =
            Crc32c(uuidSeed)
                .apply {
                    updateIntLe(42)
                    update(vector)
                }.rawState()
        val combined = ByteArray(4 + vector.size)
        writeLeInt(combined, 0, 42)
        vector.copyInto(combined, destinationOffset = 4)
        Crc32c.computeRaw(uuidSeed, combined) shouldBe chained
    }

    @Test
    fun matchesStandardCrc64EcmaAndXxHash64TestVectors() {
        val vector = "123456789".encodeToByteArray()
        val crc64 =
            Crc64Ecma().apply {
                update(vector, offset = 0, length = 3)
                update(vector, offset = 3, length = vector.size - 3)
            }
        crc64.value() shouldBe 0x995DC9BBDF1939FAuL

        val xxhash =
            XxHash64().apply {
                update(vector, offset = 0, length = 5)
                update(vector, offset = 5, length = vector.size - 5)
            }
        xxhash.digest() shouldBe 0x8CB841DB40E6AE83uL
        xxhash.digestLow32() shouldBe 0x40E6AE83u

        val longInput = "Kufus Zstd Checksum Vector!".repeat(5).encodeToByteArray()
        val longHash =
            XxHash64().apply {
                update(longInput, offset = 0, length = 17)
                update(longInput, offset = 17, length = longInput.size - 17)
            }
        longHash.digestLow32() shouldBe 0x2AACD45Eu
    }
}
