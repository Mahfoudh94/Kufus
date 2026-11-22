package io.github.drlacheheb.kufus.disk

private const val CRC32_POLYNOMIAL = 0xEDB88320.toInt()
private const val TABLE_SIZE = 256
private const val BITS_PER_BYTE = 8

/**
 * Standard IEEE 802.3 CRC-32 checksum used across XZ headers, indexes, footers, and block checks.
 */
internal class Crc32 {
    private var state = -1

    fun update(
        input: ByteArray,
        offset: Int = 0,
        length: Int = input.size - offset,
    ) {
        for (i in offset until (offset + length)) {
            val tableIndex = (state xor (input[i].toInt() and 0xFF)) and 0xFF
            state = (state ushr BITS_PER_BYTE) xor crc32Table[tableIndex]
        }
    }

    fun value(): Long = (state.inv().toLong()) and 0xFFFF_FFFFL

    companion object {
        private val crc32Table =
            IntArray(TABLE_SIZE) { entry ->
                var crc = entry
                repeat(BITS_PER_BYTE) {
                    crc = if ((crc and 1) != 0) (crc ushr 1) xor CRC32_POLYNOMIAL else (crc ushr 1)
                }
                crc
            }

        fun compute(
            input: ByteArray,
            offset: Int = 0,
            length: Int = input.size - offset,
        ): Long = Crc32().apply { update(input, offset, length) }.value()
    }
}
