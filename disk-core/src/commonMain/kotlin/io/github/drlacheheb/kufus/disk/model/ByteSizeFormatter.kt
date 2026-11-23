package io.github.drlacheheb.kufus.disk.model

/**
 * Formats a raw byte count into a human-readable SI size label (for example, `63.3 GB`).
 */
fun formatByteSize(bytes: Long): String {
    if (bytes <= 0L) return "0 B"
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unitIndex = 0
    while (value >= 1000.0 && unitIndex < units.lastIndex) {
        value /= 1000.0
        unitIndex++
    }
    if (unitIndex == 0) return "$bytes B"
    val roundedTenths = (value * 10.0 + 0.5).toLong()
    val whole = roundedTenths / 10L
    val decimal = roundedTenths % 10L
    return "$whole.$decimal ${units[unitIndex]}"
}
