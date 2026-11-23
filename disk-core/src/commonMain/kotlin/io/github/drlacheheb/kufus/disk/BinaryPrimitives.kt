package io.github.drlacheheb.kufus.disk

private const val BYTE_MASK = 0xFFL
private const val SHORT_MASK = 0xFF
private const val SHIFT_8 = 8
private const val SHIFT_16 = 16
private const val SHIFT_24 = 24
private const val SHIFT_32 = 32
private const val SHIFT_40 = 40
private const val SHIFT_48 = 48
private const val SHIFT_56 = 56

internal fun readUInt16LeInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    (bytes[offset].toInt() and SHORT_MASK) or
        ((bytes[offset + 1].toInt() and SHORT_MASK) shl SHIFT_8)

internal fun readUInt16LeLong(
    bytes: ByteArray,
    offset: Int,
): Long = readUInt16LeInt(bytes, offset).toLong()

internal fun readUInt16BeInt(
    bytes: ByteArray,
    offset: Int,
): Int =
    ((bytes[offset].toInt() and SHORT_MASK) shl SHIFT_8) or
        (bytes[offset + 1].toInt() and SHORT_MASK)

internal fun readUInt32LeLong(
    bytes: ByteArray,
    offset: Int,
): Long =
    (bytes[offset].toLong() and BYTE_MASK) or
        ((bytes[offset + 1].toLong() and BYTE_MASK) shl SHIFT_8) or
        ((bytes[offset + 2].toLong() and BYTE_MASK) shl SHIFT_16) or
        ((bytes[offset + 3].toLong() and BYTE_MASK) shl SHIFT_24)

internal fun readUInt32LeInt(
    bytes: ByteArray,
    offset: Int,
): Int = readUInt32LeLong(bytes, offset).toInt()

internal fun readUInt64LeLong(
    bytes: ByteArray,
    offset: Int,
): Long =
    readUInt32LeLong(bytes, offset) or
        ((bytes[offset + 4].toLong() and BYTE_MASK) shl SHIFT_32) or
        ((bytes[offset + 5].toLong() and BYTE_MASK) shl SHIFT_40) or
        ((bytes[offset + 6].toLong() and BYTE_MASK) shl SHIFT_48) or
        ((bytes[offset + 7].toLong() and BYTE_MASK) shl SHIFT_56)

internal fun startsWithBytes(
    bytes: ByteArray,
    magic: ByteArray,
    offset: Int = 0,
): Boolean {
    if (offset < 0 || bytes.size < offset + magic.size) return false
    for (index in magic.indices) {
        if (bytes[offset + index] != magic[index]) return false
    }
    return true
}
