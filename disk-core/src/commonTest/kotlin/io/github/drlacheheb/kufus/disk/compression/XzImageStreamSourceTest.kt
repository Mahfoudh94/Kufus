package io.github.drlacheheb.kufus.disk.compression

import io.github.drlacheheb.kufus.disk.io.ByteArrayStreamingSource
import io.github.drlacheheb.kufus.disk.io.FlashStepException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class XzImageStreamSourceTest {
    private fun expectedPayload(): ByteArray {
        val buf = ByteArray(600) { index -> ((index * 29 + 3) and 0xFF).toByte() }
        // Embedded x86 CALL/JMP sequences
        byteArrayOf(0xE8.toByte(), 0x10, 0x20, 0x00, 0x00).copyInto(buf, destinationOffset = 20)
        byteArrayOf(0xE9.toByte(), 0x00, 0xFF.toByte(), 0xFF.toByte(), 0xFF.toByte()).copyInto(buf, destinationOffset = 25)
        byteArrayOf(0xE8.toByte(), 0x00, 0x00, 0x00, 0x00).copyInto(buf, destinationOffset = 31)
        // Embedded ARM64 BL and ADRP instructions
        byteArrayOf(0x00, 0x01, 0x00, 0x94.toByte()).copyInto(buf, destinationOffset = 128)
        byteArrayOf(0x80.toByte(), 0xFF.toByte(), 0xFF.toByte(), 0x97.toByte()).copyInto(buf, destinationOffset = 132)
        byteArrayOf(0x21, 0x00, 0x00, 0x90.toByte()).copyInto(buf, destinationOffset = 256)
        return buf
    }

    @Test
    fun decodesRealXzCrc64Crc32Sha256AndBcjStreamsAcrossSmallAndLargeChunks() {
        val expected = expectedPayload()
        val fixtures =
            listOf(
                XZ_DEFAULT_HEX,
                XZ_CRC32_HEX,
                XZ_SHA256_HEX,
                XZ_X86_HEX,
                XZ_ARM64_HEX,
            )

        fixtures.forEachIndexed { idx, hex ->
            val compressed = decodeHex(hex)
            // Exercise uneven input and output read chunk boundaries (13-byte input chunks, 17-byte output chunks)
            val decoded =
                runCatching { decodeAll(compressed, inputChunkSize = 13, outputChunkSize = 17) }
                    .getOrElse { error -> throw AssertionError("Fixture #$idx failed: ${error.message}", error) }
            decoded.contentEquals(expected) shouldBe true
        }
    }

    @Test
    fun decodesConcatenatedXzStreamsWithPaddingAndDetectsCorruption() {
        val expected = expectedPayload()
        val single = decodeHex(XZ_DEFAULT_HEX)
        val concatenated = single + ByteArray(4) + decodeHex(XZ_ARM64_HEX) + ByteArray(4)

        val decoded = decodeAll(concatenated, inputChunkSize = 64, outputChunkSize = 256)
        decoded.contentEquals(expected + expected) shouldBe true

        // Corrupt a checksum byte in the block footer
        val corrupted = single.copyOf().apply { this[size - 28] = (this[size - 28].toInt() xor 0x55).toByte() }
        shouldThrow<FlashStepException> {
            decodeAll(corrupted, inputChunkSize = 1024, outputChunkSize = 1024)
        }.code shouldBe "CORRUPT_XZ"

        val truncated = single.copyOf(single.size - 10)
        shouldThrow<FlashStepException> {
            decodeAll(truncated, inputChunkSize = 1024, outputChunkSize = 1024)
        }.code shouldBe "CORRUPT_XZ"
    }

    private fun decodeAll(
        compressed: ByteArray,
        inputChunkSize: Int,
        outputChunkSize: Int,
    ): ByteArray {
        val decoder = XzImageStreamSource(ByteArrayStreamingSource(compressed, inputChunkSize), null)
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
        private const val XZ_DEFAULT_HEX =
            "fd377a585a000004e6d6b44604c09e02d80421011600000000000000c0cba915e0025701165d000188c9f0ece689c77c" +
                "34e3e2f8c4992c227d7d9bf031b851632c8f95ab28158ba15246cd36d45826ccacbb6c97f1621536da7bdcc0efd58014" +
                "d04f29152c74be959af7106dc9aa6b415bd68b76677f63317ec0461fda28f8cc4701733db84c268f1e9200ab4b010b65" +
                "8b5b95db0071ec2a4da759042c0d4c59ac25070ba0b95e79097bceab62142e9c951b7cc5f3663af5bcf2a4a2edd41232" +
                "22e4a7b6e2fb4db736c697916d3ffdcbb78980f502dff6d551a74ae6481bfddfc012bda362058c14a1be369cb536bc56" +
                "05b8f88285502803300793bbe59987923e32cf92d6c758809c7c77a49aab2e27da6d8c743460de9a130aafe33df8bc35" +
                "981e0297995a43acad5d5539d4a3cbbc490347b619175782df930c7e00000000bc3e9508fac8ff5c0001ba02d8040000" +
                "048e2de6b1c467fb020000000004595a"

        private const val XZ_CRC32_HEX =
            "fd377a585a0000016922de3604c09e02d80421011600000000000000c0cba915e0025701165d000188c9f0ece689c77c" +
                "34e3e2f8c4992c227d7d9bf031b851632c8f95ab28158ba15246cd36d45826ccacbb6c97f1621536da7bdcc0efd58014" +
                "d04f29152c74be959af7106dc9aa6b415bd68b76677f63317ec0461fda28f8cc4701733db84c268f1e9200ab4b010b65" +
                "8b5b95db0071ec2a4da759042c0d4c59ac25070ba0b95e79097bceab62142e9c951b7cc5f3663af5bcf2a4a2edd41232" +
                "22e4a7b6e2fb4db736c697916d3ffdcbb78980f502dff6d551a74ae6481bfddfc012bda362058c14a1be369cb536bc56" +
                "05b8f88285502803300793bbe59987923e32cf92d6c758809c7c77a49aab2e27da6d8c743460de9a130aafe33df8bc35" +
                "981e0297995a43acad5d5539d4a3cbbc490347b619175782df930c7e000000005775a0950001b602d80400007f4eef91" +
                "3e300d8b020000000001595a"

        private const val XZ_SHA256_HEX =
            "fd377a585a00000ae1fb0ca104c09e02d80421011600000000000000c0cba915e0025701165d000188c9f0ece689c77c" +
                "34e3e2f8c4992c227d7d9bf031b851632c8f95ab28158ba15246cd36d45826ccacbb6c97f1621536da7bdcc0efd58014" +
                "d04f29152c74be959af7106dc9aa6b415bd68b76677f63317ec0461fda28f8cc4701733db84c268f1e9200ab4b010b65" +
                "8b5b95db0071ec2a4da759042c0d4c59ac25070ba0b95e79097bceab62142e9c951b7cc5f3663af5bcf2a4a2edd41232" +
                "22e4a7b6e2fb4db736c697916d3ffdcbb78980f502dff6d551a74ae6481bfddfc012bda362058c14a1be369cb536bc56" +
                "05b8f88285502803300793bbe59987923e32cf92d6c758809c7c77a49aab2e27da6d8c743460de9a130aafe33df8bc35" +
                "981e0297995a43acad5d5539d4a3cbbc490347b619175782df930c7e000000008ac5db373237cea6dad3779d5c458f42" +
                "dd149301f94fea56f320553faed894c50001d202d804000033038a02b6e9df1c02000000000a595a"

        private const val XZ_X86_HEX =
            "fd377a585a000004e6d6b44604c19f02d80404002101160000000000df9da273e0025701175d000188c9f0ece689c77c" +
                "34e3e2f8c4992c227d7d9bf031b85607bb3eb65ee6711380fb2e139736fca932b7a9f89fa1f77edd2cbae0c7d11ccb49" +
                "9577c3e4d90ad9c986c9c9959fe836a53db5cdc00061507e12bde0c631614bb5061cae3b232e3510be5be1228d80723" +
                "709e2b724c40e533ad2a94cf6ba593af48d60e75f53c08f512dc9600a8d3bf4bf1a687ee40fd2c8895e5befe86a0fb4a" +
                "ea2d076b58d57fe7fc4ac0a02c4f83442cfcea12055455fb37505c99b0e773bf38fd84ec25ef9f440aef07b98e93b51b" +
                "2951e4447bd3eb92d5abf613f235f9daed469f56c9f259b40dc4bd0002284f559037db5641864e405ad54b166e945ba3" +
                "bf25d36ac47554d22baed267406b4398a11d8fc3e51d2e11720ba24f63e000000bc3e9508fac8ff5c0001bb02d80400" +
                "00a15d712db1c467fb020000000004595a"

        private const val XZ_ARM64_HEX =
            "fd377a585a000004e6d6b44604c1a002d8040a0021011600000000002e5d2990e0025701185d000188c9f0ece689c77c" +
                "34e3e2f8c4992c227d7d9bf031b851632c8f95ab28158ba15246cd36d45826ccacbb6c97f1621536da7bdcc0efd58014" +
                "d04f29152c74be959af7106dc9aa6b415bd68b76677f63317ec0461fda28f8cc4701733db84c268f1e9200ab4b010b65" +
                "8b5b95f7a93ffd638594a9ac595564c92dfcdf026f1644ab1f9580f9da33333db7015c210a6899ff9c3319bab803c7de" +
                "7371b1b09dedbf18defc6f8a9f30c88f2e1c4f0f66f27d789d7f5ffc4d042e8546075b0721b36f35720a19591626beea" +
                "e49eaf6b12fd511e841af70590f4bcc139de44d847cc0f4830dba8896fabf32939e1baea2478e695d80ba8500c973b48" +
                "07c86252911a4a0fd1b796375386676aa86ed5550852b7f9f990c8a0cbfd0000bc3e9508fac8ff5c0001bc02d8040000" +
                "196d7430b1c467fb020000000004595a"
    }
}
