package io.github.drlacheheb.kufus.ui.flasher

private const val SECONDS_PER_MINUTE = 60L
private const val SECONDS_PER_HOUR = 3600L
private const val MILLIS_PER_SECOND = 1000L
private const val PERCENT_SCALE = 100.0
private const val MAX_INCOMPLETE_PERCENT = 99
private const val COMPLETE_PERCENT = 100

internal fun calculateProgressFraction(
    bytesProcessed: Long,
    totalBytes: Long,
): Float? {
    if (totalBytes <= 0L) return null
    return (bytesProcessed.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
}

internal fun formatProgressPercent(
    bytesProcessed: Long,
    totalBytes: Long,
): Int {
    if (totalBytes <= 0L) return 0
    if (bytesProcessed >= totalBytes) return COMPLETE_PERCENT
    val raw = ((bytesProcessed.coerceAtLeast(0L).toDouble() / totalBytes.toDouble()) * PERCENT_SCALE).toInt()
    return raw.coerceIn(0, MAX_INCOMPLETE_PERCENT)
}

internal fun estimateRemainingSeconds(
    bytesProcessed: Long,
    totalBytes: Long,
    speedBytesPerSec: Long,
): Long? {
    if (totalBytes <= 0L || speedBytesPerSec <= 0L) return null
    val remaining = (totalBytes - bytesProcessed).coerceAtLeast(0L)
    if (remaining == 0L) return 0L
    return (remaining + speedBytesPerSec - 1L) / speedBytesPerSec
}

internal fun formatDurationSeconds(totalSeconds: Long): String {
    val safeSeconds = totalSeconds.coerceAtLeast(0L)
    val hours = safeSeconds / SECONDS_PER_HOUR
    val minutes = (safeSeconds % SECONDS_PER_HOUR) / SECONDS_PER_MINUTE
    val seconds = safeSeconds % SECONDS_PER_MINUTE
    return when {
        hours > 0L -> "${hours}h ${minutes}m"
        minutes > 0L -> "${minutes}m ${seconds}s"
        else -> "${seconds}s"
    }
}

internal fun formatElapsedMillis(elapsedMs: Long): String {
    val roundedSeconds = ((elapsedMs.coerceAtLeast(0L) + MILLIS_PER_SECOND / 2L) / MILLIS_PER_SECOND).coerceAtLeast(1L)
    return formatDurationSeconds(roundedSeconds)
}
