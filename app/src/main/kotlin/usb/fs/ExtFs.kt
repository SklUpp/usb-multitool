package usb.fs

import usb.device.BlockDevice
import java.io.Closeable
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

    /** Registry lock protects map/reservation only; never hold it across native callbacks. */
    private val registryLock = Any()
    private val openVolumes = WeakHashMap<BlockDevice, ExtVolume>()
    /** Prevents concurrent duplicate native mounts while mount callback is in flight. */
    private val mountingDevices = WeakHashMap<BlockDevice, Boolean>()

    fun mount(device: BlockDevice, readOnly: Boolean = true): ExtVolume {
        ensureNative()
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        synchronized(registryLock) {
            if (openVolumes.containsKey(device) || mountingDevices.containsKey(device)) {
                throw ExtException("EXT volume already mounted")
            }
            mountingDevices[device] = true
        }
        try {
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
            synchronized(registryLock) { openVolumes[device] = volume }
            return volume
        } finally {
            synchronized(registryLock) { mountingDevices.remove(device) }
        }
    }

    fun mkfs(device: BlockDevice, label: String = "") {
        ensureNative()
        require(device.sectorSize in 512..4096)
        require(device.sectorCount > 0uL)
        val rc = nativeMkfs(device, device.sectorSize, device.sectorCount.toLong(), label)
        if (rc != 0) throw ExtException("EXT mkfs failed (rc=$rc)")
    }

    fun probe(device: BlockDevice): Boolean {
        ensureNative()
        return nativeProbe(device, device.sectorSize, device.sectorCount.toLong())
    }

    internal fun unregisterVolume(device: BlockDevice, volume: ExtVolume) {
        synchronized(registryLock) {
            if (openVolumes[device] === volume) openVolumes.remove(device)
        }
    }
    @JvmStatic
    private external fun nativeMount(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        readOnly: Boolean,
        outErrno: IntArray,
    ): Long

    @JvmStatic private external fun nativeMkfs(device: BlockDevice, sectorSize: Int, sectorCount: Long, label: String): Int
    @JvmStatic private external fun nativeProbe(device: BlockDevice, sectorSize: Int, sectorCount: Long): Boolean

    /** Writes total/free bytes into [out]; true only with both values written. */
    @JvmStatic internal external fun nativeUsage(handle: Long, out: LongArray): Boolean

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
        exclusiveCreate: Boolean,
    ): Int
    @JvmStatic internal external fun nativeMkdir(handle: Long, path: String)
    @JvmStatic internal external fun nativeDelete(handle: Long, path: String)
    @JvmStatic internal external fun nativeRename(handle: Long, from: String, to: String)
}

class ExtVolume internal constructor(
    private val handle: Long,
    val device: BlockDevice,
    val readOnly: Boolean,
) : Closeable, AutoCloseable, UsbFsVolume, UsbFsUsageVolume, UsbFsAccessVolume {
    override val access: FilesystemAccess =
        if (readOnly) FilesystemAccess.ReadOnly else FilesystemAccess.ReadWrite
    // Every native operation shares this monitor with close; callbacks run outside registryLock.
    @Volatile private var open: Boolean = true

    fun isOpen(): Boolean = open

    private fun ensureOpen() {
        check(open) { "EXT volume closed" }
    }

    private fun ensureWritable() {
        ensureOpen()
        if (readOnly) throw ExtException("Read-only EXT volume")
    }

    @Synchronized
    override fun space(): FilesystemSpace {
        ensureOpen()
        val out = LongArray(2)
        if (!ExtFs.nativeUsage(handle, out)) {
            throw ExtException("space query failed")
        }
        return FilesystemSpace(totalBytes = out[0].toULong(), freeBytes = out[1].toULong())
    }

    @Synchronized
    override fun list(path: String): List<UsbFsDirEntry> {
        ensureOpen()
        val entries = ExtFs.nativeList(handle, path)
            ?: throw ExtException("Failed to list: $path")
        return entries.map { UsbFsDirEntry(it.name, it.isDirectory, it.size) }
    }

    override fun stat(path: String): UsbFsDirEntry =
        statOrNull(path) ?: throw ExtException("Path not found: $path")

    @Synchronized
    override fun statOrNull(path: String): UsbFsDirEntry? {
        ensureOpen()
        val err = IntArray(1)
        val st = ExtFs.nativeStat(handle, path, err) ?: return mapExtStatOrNullMiss(path, err[0])
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        return UsbFsDirEntry(name, st.isDirectory, st.size)
    }

    @Synchronized
    override fun read(path: String, offset: Long, length: Int): ByteArray {
        ensureOpen()
        requireIoRange(offset, length, MAX_FAT_EXT_IO_BYTES)
        return ExtFs.nativeRead(handle, path, offset, length)
            ?: throw ExtException("Failed to read: $path")
    }

    @Synchronized
    override fun write(
        path: String,
        data: ByteArray,
        offset: Long,
        create: Boolean,
        plannedTotalSize: Long,
        exclusiveCreate: Boolean,
    ) {
        ensureWritable()
        requireIoRange(offset, data.size, MAX_FAT_EXT_IO_BYTES)
        val wrote = ExtFs.nativeWrite(handle, path, offset, data, create, exclusiveCreate)
        if (wrote < 0 || wrote != data.size) {
            throw ExtException("short write on $path: $wrote of ${data.size}")
        }
    }

    @Synchronized
    override fun mkdir(path: String) {
        ensureWritable()
        ExtFs.nativeMkdir(handle, path)
    }

    @Synchronized
    override fun delete(path: String) {
        ensureWritable()
        try {
            ExtFs.nativeDelete(handle, path)
        } catch (failure: ExtException) {
            if (failure.message == "Directory not empty") {
                throw ExtException("Directory not empty: $path")
            }
            throw failure
        }
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        ensureWritable()
        ExtFs.nativeRename(handle, from, to)
    }

    @Synchronized
    override fun sync() {
        ensureOpen()
        if (ExtFs.nativeSync(handle) != 0) throw ExtException("EXT sync failed")
    }

    /**
     * Serialized like [NtfsVolume.close]: the open→closed flip and the native teardown
     * must be one critical section, else two concurrent closes both see `open == true`
     * and call [ExtFs.nativeClose] on the same handle (native UAF). A second close after
     * the first returns or throws is a no-op.
     */
    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        // nativeClose always frees the engine slot after tearing down the lwext4
        // mountpoint (even when VALID_FS sb write fails). On-disk ERROR_FS may
        // remain — we do not retry umount or clear the flag; run e2fsck on a PC.
        // FilesVolumeSession latches this failure and refuses remount in-process.
        try {
            val rc = ExtFs.nativeClose(handle)
            if (rc != 0) {
                throw ExtException(
                    "EXT close/sync failed (rc=$rc). If the volume will not remount, run e2fsck on a PC",
                )
            }
        } finally {
            // Keep registry entry until native teardown returns; direct remount cannot overlap close.
            ExtFs.unregisterVolume(device, this)
        }
    }
}
