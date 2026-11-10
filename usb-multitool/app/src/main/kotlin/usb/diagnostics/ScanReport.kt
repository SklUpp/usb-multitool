package usb.diagnostics

import usb.formatBinaryBytesPerSecond

data class ScanReport(
    val sectorsRead: ULong,
    val failed: List<ULongRange>,
    /** Bytes returned by successful SCSI reads only; medium-error sectors excluded. */
    val successfulBytes: Long = 0L,
    /** Monotonic elapsed time covering the scan I/O window (includes failed attempts). */
    val elapsedNanos: Long = 0L,
) {
    /**
     * Mean successful-read rate in bytes/s.
     * Null when no successful bytes were read or elapsed time is non-positive
     * (undefined / unavailable — never invent a bus or write speed).
     */
    val averageBytesPerSecond: Long?
        get() {
            if (successfulBytes <= 0L || elapsedNanos <= 0L) return null
            return (successfulBytes.toDouble() * 1_000_000_000.0 / elapsedNanos.toDouble())
                .toLong()
                .coerceAtLeast(0L)
        }

    /** Binary unit label for [averageBytesPerSecond], or null when rate is undefined. */
    fun formatAverageReadSpeed(): String? =
        averageBytesPerSecond?.let { formatBinaryBytesPerSecond(it) }
}
