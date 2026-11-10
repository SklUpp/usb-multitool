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
