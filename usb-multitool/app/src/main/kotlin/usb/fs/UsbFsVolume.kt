package usb.fs

/** Common Files-tab operations for FAT and EXT volumes. */
data class UsbFsDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

/** Largest single JNI read/write for FAT and EXT volumes. */
internal const val MAX_FAT_EXT_IO_BYTES: Int = 8 * 1024 * 1024

/** Largest single JNI read/write for NTFS volumes. */
internal const val MAX_NTFS_IO_BYTES: Int = 16 * 1024 * 1024

/**
 * Reject impossible ranges before they reach JNI: negative offsets become huge
 * unsigned byte positions, and an oversized length allocates unbounded memory
 * on the native side before any engine check runs.
 */
internal fun requireIoRange(offset: Long, length: Int, maxBytes: Int) {
    require(offset >= 0) { "negative offset: $offset" }
    require(length >= 0) { "negative length: $length" }
    require(length <= maxBytes) { "request too large: $length > $maxBytes" }
}

interface UsbFsVolume : AutoCloseable {
    fun list(path: String = "/"): List<UsbFsDirEntry>
    fun stat(path: String): UsbFsDirEntry

    /** null only when the entry does not exist; I/O failures still throw. */
    fun statOrNull(path: String): UsbFsDirEntry?

    fun mkdir(path: String)
    fun delete(path: String)
    fun rename(from: String, to: String)
    fun read(path: String, offset: Long = 0L, length: Int): ByteArray
    fun write(
        path: String,
        data: ByteArray,
        offset: Long = 0L,
        create: Boolean = true,
        plannedTotalSize: Long = offset + data.size.toLong(),
    )
    fun sync()
}
