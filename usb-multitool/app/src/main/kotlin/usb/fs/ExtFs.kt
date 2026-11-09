package usb.fs

import usb.device.BlockDevice
import java.io.Closeable
import java.util.Collections
import java.util.WeakHashMap

data class ExtDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

data class ExtStat(
    val size: Long,
    val isDirectory: Boolean,
)

class ExtException(message: String) : Exception(message)

/** ENOENT only → null; any other errno → ExtException (never treat I/O as absent). */
internal fun mapExtStatOrNullMiss(path: String, errno: Int): UsbFsDirEntry? {
    if (errno == -2 /* ENOENT */) return null
    throw ExtException("stat failed for $path (errno=$errno)")
}

/**
 * EXT2/3/4 facade over relative-LBA [BlockDevice] via lwext4.
 * Dirty (needs_recovery) and unsupported incompat features refuse mount.
 */
object ExtFs {
    @Volatile
    private var nativeLoaded: Boolean = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            synchronized(this) {
                if (!nativeLoaded) {
                    try {
                        System.loadLibrary("usb_native")
                    } catch (_: UnsatisfiedLinkError) {
                        // Pure JVM test environments
                    }
                    nativeLoaded = true
                }
            }
        }
    }

    private val openVolumes = Collections.synchronizedMap(WeakHashMap<BlockDevice, ExtVolume>())

    fun mount(device: BlockDevice, readOnly: Boolean = true): ExtVolume {
        ensureNative()
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }

        val err = IntArray(1)
        val handle =
            nativeMount(device, device.sectorSize, device.sectorCount.toLong(), readOnly, err)
        if (handle == 0L) {
            throw when (err[0]) {
                16 /* EBUSY */ -> ExtException(
                    "EXT volume looks dirty/unclean; refusing mount. " +
                        "Do not clear the flag from this app — run e2fsck on a PC, then remount",
                )
                95 /* ENOTSUP */, 48 /* ENOTSUP darwin */ ->
                    ExtException("EXT volume uses unsupported features; refusing mount")
                else -> ExtException("Failed to mount EXT volume (errno=${err[0]})")
            }
        }
        val volume = ExtVolume(handle, device, readOnly = readOnly)
        openVolumes[device] = volume
        return volume
    }

    fun mkfs(device: BlockDevice) {
        ensureNative()
        require(device.sectorSize in 512..4096)
        require(device.sectorCount > 0uL)
        val rc = nativeMkfs(device, device.sectorSize, device.sectorCount.toLong())
        if (rc != 0) throw ExtException("EXT mkfs failed (rc=$rc)")
    }

    fun probe(device: BlockDevice): Boolean {
        ensureNative()
        return nativeProbe(device, device.sectorSize, device.sectorCount.toLong())
    }

    internal fun unregisterVolume(device: BlockDevice) {
        openVolumes.remove(device)
    }

    @JvmStatic
    private external fun nativeMount(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        readOnly: Boolean,
        outErrno: IntArray,
    ): Long

    @JvmStatic private external fun nativeMkfs(device: BlockDevice, sectorSize: Int, sectorCount: Long): Int
    @JvmStatic private external fun nativeProbe(device: BlockDevice, sectorSize: Int, sectorCount: Long): Boolean

    @JvmStatic internal external fun nativeClose(handle: Long): Int
    @JvmStatic internal external fun nativeSync(handle: Long): Int
    @JvmStatic internal external fun nativeList(handle: Long, path: String): Array<ExtDirEntry>
    @JvmStatic internal external fun nativeStat(handle: Long, path: String, outErrno: IntArray): ExtStat?
    @JvmStatic internal external fun nativeRead(handle: Long, path: String, offset: Long, length: Int): ByteArray
    @JvmStatic internal external fun nativeWrite(
        handle: Long,
        path: String,
        offset: Long,
        data: ByteArray,
        create: Boolean,
    ): Int
    @JvmStatic internal external fun nativeMkdir(handle: Long, path: String)
    @JvmStatic internal external fun nativeDelete(handle: Long, path: String)
    @JvmStatic internal external fun nativeRename(handle: Long, from: String, to: String)
}

class ExtVolume internal constructor(
    private val handle: Long,
    val device: BlockDevice,
    val readOnly: Boolean,
) : Closeable, AutoCloseable, UsbFsVolume {
    @Volatile private var open: Boolean = true

    fun isOpen(): Boolean = open

    private fun ensureOpen() {
        check(open) { "EXT volume closed" }
    }

    override fun list(path: String): List<UsbFsDirEntry> {
        ensureOpen()
        val entries = ExtFs.nativeList(handle, path)
            ?: throw ExtException("Failed to list: $path")
        return entries.map { UsbFsDirEntry(it.name, it.isDirectory, it.size) }
    }

    override fun stat(path: String): UsbFsDirEntry =
        statOrNull(path) ?: throw ExtException("Path not found: $path")

    override fun statOrNull(path: String): UsbFsDirEntry? {
        ensureOpen()
        val err = IntArray(1)
        val st = ExtFs.nativeStat(handle, path, err) ?: return mapExtStatOrNullMiss(path, err[0])
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        return UsbFsDirEntry(name, st.isDirectory, st.size)
    }

    override fun read(path: String, offset: Long, length: Int): ByteArray {
        ensureOpen()
        return ExtFs.nativeRead(handle, path, offset, length)
            ?: throw ExtException("Failed to read: $path")
    }

    override fun write(
        path: String,
        data: ByteArray,
        offset: Long,
        create: Boolean,
        plannedTotalSize: Long,
    ) {
        ensureOpen()
        val wrote = ExtFs.nativeWrite(handle, path, offset, data, create)
        if (wrote < 0 || wrote != data.size) {
            throw ExtException("short write on $path: $wrote of ${data.size}")
        }
    }

    override fun mkdir(path: String) {
        ensureOpen()
        ExtFs.nativeMkdir(handle, path)
    }

    override fun delete(path: String) {
        ensureOpen()
        try {
            ExtFs.nativeDelete(handle, path)
        } catch (failure: ExtException) {
            if (failure.message == "Directory not empty") {
                throw ExtException("Directory not empty: $path")
            }
            throw failure
        }
    }

    override fun rename(from: String, to: String) {
        ensureOpen()
        ExtFs.nativeRename(handle, from, to)
    }

    override fun sync() {
        ensureOpen()
        if (ExtFs.nativeSync(handle) != 0) throw ExtException("EXT sync failed")
    }

    override fun close() {
        if (!open) return
        open = false
        // nativeClose always frees the engine slot; a failed umount/sync may leave
        // on-disk ERROR_FS. We do not retry umount or clear the flag — run e2fsck.
        val rc = ExtFs.nativeClose(handle)
        ExtFs.unregisterVolume(device)
        if (rc != 0) {
            throw ExtException(
                "EXT close/sync failed (rc=$rc). If the volume will not remount, run e2fsck on a PC",
            )
        }
    }
}
