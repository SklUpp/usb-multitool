package usb.fs

/** Common Files-tab operations for FAT and EXT volumes. */
data class UsbFsDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

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
