package io.github.drlacheheb.kufus.disk.io

import io.kotest.matchers.shouldBe
import kotlin.test.Test

class Sha256Test {
    @Test
    fun matchesStandardFips180TestVectorsAcrossChunkedUpdates() {
        Sha256().digest().toHex() shouldBe "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        val abcDigest =
            Sha256().apply {
                update("a".encodeToByteArray())
                update("bc".encodeToByteArray())
            }
        abcDigest.digest().toHex() shouldBe "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"

        val longDigest = Sha256()
        val blockInput = "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray()
        longDigest.update(blockInput, offset = 0, length = 17)
        longDigest.update(blockInput, offset = 17, length = blockInput.size - 17)
        longDigest.digest().toHex() shouldBe "248d6a61d20638b8e5c026930c3e6039a33ce45964ff2167f6ecedd419db06c1"
    }
}

private fun ByteArray.toHex(): String =
    joinToString(separator = "") { byte ->
        (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
    }
