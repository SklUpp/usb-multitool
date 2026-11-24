package usb

import java.util.Locale

/** Binary rate label; Locale.US keeps unit tests deterministic. UI localizes the sentence. */
fun formatBinaryBytesPerSecond(bytesPerSecond: Long): String {
    require(bytesPerSecond >= 0L) { "rate must be non-negative" }
    if (bytesPerSecond < 1024L) return "$bytesPerSecond B/s"
    val kib = bytesPerSecond / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB/s", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.2f MiB/s", mib)
    val gib = mib / 1024.0
    return String.format(Locale.US, "%.2f GiB/s", gib)
}
/** Binary byte count for a progress row; Locale.US keeps unit tests deterministic. */
fun formatBinarySize(bytes: Long): String {
    require(bytes >= 0L) { "size must be non-negative" }
    if (bytes < 1024L) return "$bytes B"
    val kib = bytes / 1024.0
    if (kib < 1024.0) return String.format(Locale.US, "%.1f KiB", kib)
    val mib = kib / 1024.0
    if (mib < 1024.0) return String.format(Locale.US, "%.2f MiB", mib)
    return String.format(Locale.US, "%.2f GiB", mib / 1024.0)
}
/**
 * Whole seconds left from [bytesDone] to [bytesTotal] at [bytesPerSecond].
 * Null (omit ETA) when the total is unknown, speed is not positive, or nothing
 * remains. Ceiling division without `remaining + speed - 1` overflow.
 */
fun remainingSeconds(bytesDone: Long, bytesTotal: Long, bytesPerSecond: Long): Long? {
    if (bytesTotal <= 0L || bytesPerSecond <= 0L) return null
    val remaining = bytesTotal - bytesDone.coerceAtLeast(0L)
    if (remaining <= 0L) return null
    return (remaining - 1L) / bytesPerSecond + 1L
}

/** Numeric clock label; the surrounding sentence stays a localized resource. */
fun formatEtaDuration(totalSeconds: Long): String {
    require(totalSeconds >= 0L) { "ETA must be non-negative" }
    val hours = totalSeconds / 3600L
    val minutes = (totalSeconds % 3600L) / 60L
    val seconds = totalSeconds % 60L
    val mm = minutes.toString().padStart(2, '0')
    val ss = seconds.toString().padStart(2, '0')
    return if (hours > 0L) "$hours:$mm:$ss" else "${totalSeconds / 60L}:$ss"
}
