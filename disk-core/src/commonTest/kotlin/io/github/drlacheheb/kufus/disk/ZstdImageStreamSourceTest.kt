package io.github.drlacheheb.kufus.disk

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class ZstdImageStreamSourceTest {
    private fun mixedPayload(): ByteArray {
        val out = ByteArray(5000)
        for (i in out.indices) {
            out[i] =
                if ((i / 100) % 2 == 0) {
                    ((i * 73 + 11) and 0xFF).toByte()
                } else {
                    if (i >= 80) out[i - 80] else 0x5A.toByte()
                }
        }
        return out
    }

    @Test
    fun decodesRealZstdCompressedNoSizeMultiBlockRawAndRleFrames() {
        val expectedMixed = mixedPayload()
        // Pipe-compressed zstd (no Frame_Content_Size, checksum enabled) and -19 compressed
        decodeAll(decodeHex(ZSTD_PIPE_NO_SIZE_HEX), 19, 23).contentEquals(expectedMixed) shouldBe true
        decodeAll(decodeHex(ZSTD_LEVEL_19_HEX), 17, 13).contentEquals(expectedMixed) shouldBe true

        // Multi-block repetitive frame (> 128 KiB triggers >1 block + Repeat FSE / Treeless)
        val expectedMulti = "KUFUS-ZSTD-MULTI-BLOCK-0123456789ABCDEF!".repeat(4000).encodeToByteArray()
        decodeAll(decodeHex(ZSTD_MULTI_BLOCK_HEX), 31, 4096).contentEquals(expectedMulti) shouldBe true

        // Raw block check (first 16 bytes + total length 400)
        val decodedRaw = decodeAll(decodeHex(ZSTD_RAW_BLOCK_HEX), 64, 128)
        decodedRaw.size shouldBe 400
        decodedRaw.copyOfRange(0, 16).contentEquals(decodeHex("390c8c7d7247342cd8100f2f6f770d65")) shouldBe true

        // RLE block (4000 bytes of 0x55)
        val decodedRle = decodeAll(decodeHex(ZSTD_RLE_BLOCK_HEX), 11, 256)
        decodedRle.size shouldBe 4000
        decodedRle.all { it == 0x55.toByte() } shouldBe true
    }

    @Test
    fun skipsZstdSkippableFramesDecodesConcatenatedFramesAndRejectsCorruption() {
        val expectedMixed = mixedPayload()
        // Valid Zstd skippable frame (magic 0x184D2A53 LE = 53 2A 4D 18, length = 4, payload = DE AD BE EF)
        val skippableFrame = byteArrayOf(0x53, 0x2A, 0x4D, 0x18, 0x04, 0x00, 0x00, 0x00, 0xDE.toByte(), 0xAD.toByte(), 0xBE.toByte(), 0xEF.toByte())
        val firstFrame = decodeHex(ZSTD_PIPE_NO_SIZE_HEX)
        val secondFrame = decodeHex(ZSTD_RLE_BLOCK_HEX)
        val combined = skippableFrame + firstFrame + skippableFrame + secondFrame

        val decoded = decodeAll(combined, inputChunkSize = 53, outputChunkSize = 1024)
        decoded.contentEquals(expectedMixed + ByteArray(4000) { 0x55 }) shouldBe true

        val badChecksum = firstFrame.copyOf().apply { this[size - 1] = (this[size - 1].toInt() xor 0xFF).toByte() }
        shouldThrow<FlashStepException> {
            decodeAll(badChecksum, 1024, 1024)
        }.code shouldBe "CORRUPT_ZSTD"

        val truncated = firstFrame.copyOf(firstFrame.size - 2)
        shouldThrow<FlashStepException> {
            decodeAll(truncated, 1024, 1024)
        }.code shouldBe "CORRUPT_ZSTD"
    }

    private fun decodeAll(
        compressed: ByteArray,
        inputChunkSize: Int,
        outputChunkSize: Int,
    ): ByteArray {
        val decoder = ZstdImageStreamSource(ByteArrayStreamingSource(compressed, inputChunkSize), null)
        val out = mutableListOf<Byte>()
        val buf = ByteArray(outputChunkSize)
        try {
            while (true) {
                val read = decoder.readChunk(buf)
                if (read == 0) break
                for (i in 0 until read) out += buf[i]
            }
        } finally {
            decoder.close()
        }
        return out.toByteArray()
    }

    private fun decodeHex(hex: String): ByteArray =
        ByteArray(hex.length / 2) { index ->
            hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
        }

    companion object {
        private const val ZSTD_PIPE_NO_SIZE_HEX =
            "28b52ffd0458750e0004100b549de62f78c10a539ce52e77c009529be42d76bf08519ae32c75be075099e22b74bd064f" +
                "98e12a73bc054e97e02972bb044d96df2871ba034c95de2770b9024b94dd266fb8014a93dc256eb7004992db246db6ff" +
                "4891da236cb5fe4790d9226bb4fd46135ca5ee3780c9125ba4ed367fc8115aa3ec357ec71059a2eb347dc60f58a1ea3" +
                "37cc50e57a0e9327bc40d569fe8317ac30c559ee73079c21b64adf63f88d11a63acf53e87d01962abf43d86cf1861aaf" +
                "33c85ce1760a9f23b84cd165fa8f13a83cc155ea7f03982cb145da6ef3881ca8fd8216ab3fc458ed72069b2fb448dd61" +
                "f68b1fa438cd51e67b0f9428bd41d66aff8418ad31c65aef74089d271a8e1ebff470700e34a64e8011240101015b8040" +
                "a8144a01088042e21112446042e7dd3cac636d91f9576d5cc0eb23b6d696c9385c915191b246892e2b141c2512ba7707" +
                "69674d485ae6f76cc18dd4857ccce134627f542b3e324a33babbad941f6e82ebc32304a342a46173e0053c8a49a664b7" +
                "6265d6076a572ca3267af1c38f6a9000a30abcb81414ce09d1236c073967e0f086c923fa201b018b83f659a6f80480c1" +
                "432f9aac0001343643ae234c0c5c0fd309de3b1018e03f6204e810d88c1b5171ba057c930d16015c46e71ce"

        private const val ZSTD_LEVEL_19_HEX =
            "28b52ffd0468e50a0014100b549de62f78c10a539ce52e77c009529be42d76bf08519ae32c75be075099e22b74bd064f" +
                "98e12a73bc054e97e02972bb044d96df2871ba034c95de2770b9024b94dd266fb8014a93dc256eb7004992db246db6ff" +
                "4891da236cb5fe4790d9226bb4fd46135ca5ee3780c9125ba4ed367fc8115aa3ec357ec71059a2eb347dc60f58a1ea3" +
                "37cc50e57a0e9327bc40d569fe8317ac30c559ee73079c21b64adf63f88d11a63acf53e87d01962abf43d86cf1861aaf" +
                "33c85ce1760a9f23b84cd165fa8f13a83cc155ea7f03982cb145da6ef3881ca8fd8216ab3fc458ed72069b2fb448dd61" +
                "f68b1fa438cd51e67b0f9428bd41d66aff8418ad31c65aef74089d27f47a8c1abff571b207a633812780781fff7148c1" +
                "174867e609d8374067506ea14d435a83368e7a0ce609d415d057506ed0cea14ac33a86b50a7a09d419dc13a05750d427" +
                "d964190ac8d67e54104a6308048b0014925c314de55c46e71ce"

        private const val ZSTD_MULTI_BLOCK_HEX =
            "28b52ffd045894010084024b554655532d5a5354442d4d554c54492d424c4f434b2d3031323334353637383941424344" +
                "4546210100a8fe5fb92a0345000008390100fcf00e84dc393a96"

        private const val ZSTD_RAW_BLOCK_HEX =
            "28b52ffd0458810c00390c8c7d7247342cd8100f2f6f770d65d670e58e0351d8ae8e4f6eac342fc231b7b08716eb3fc1" +
                "2896b96223177494287733c28ee8ba53bdb56b8824577d53ecc28a70a61c7510a1cd89216ca16cffcaea4987477e86db" +
                "ccb97046fc2e18384e51d820c5c3ef80053a88ae3996de50e801865b3698654ebf5200a5fa0939b99d7a1d7b282bf823" +
                "4041f35487d86c669fccbfe0e73d7e7320ad0a757003241e752210a924798ef86d43f27cf2d0613031dcb5d8d2ef1b3" +
                "21fcead377f6261e547d85d8eec7f26e23219072f7955d0f8f66dcd1e54c201c787e892d8f94f61976f1d1fa01d19f45" +
                "01d295f232278ce3d7e1429d6a18568a07a87ca4399eaa12504ea33256d8743b2237dbd9150e09a04993544873b364f8" +
                "b906baf6887fa801a2fd88d1601aa428652e2da0439264c12bd4bdc41159dba14b76b7f34b5d04f79535ad30c5baad27" +
                "f885137c313f07166ebb39c74720c62cca88e238eb3cca90e3b855b871337deb0a0df3bc5618216df0064badc23a9a0" +
                "3f999ed1a7ce974162d7c2599acf009b926bdca4eee2e26df256cf298dc9"

        private const val ZSTD_RLE_BLOCK_HEX = "28b52ffd04584d000010555501009bf70116a9e1d87e"
    }
}
