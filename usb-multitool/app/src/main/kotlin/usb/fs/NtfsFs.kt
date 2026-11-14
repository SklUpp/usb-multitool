package usb.fs

import usb.requireLinkedEngine
import usb.device.BlockDevice
import java.io.Closeable
import java.util.WeakHashMap

data class NtfsDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
)

data class NtfsStat(
    val size: Long,
    val isDirectory: Boolean,
)

class NtfsException(message: String) : Exception(message)

/** Map NTFS nativeStat miss: null only for absence; I/O still throws. */
internal fun mapNtfsStatOrNullMiss(path: String, errno: Int): UsbFsDirEntry? {
    // Android/Bionic ENOENT is 2.
    if (errno == 2 /* ENOENT */) return null
    throw NtfsException("stat failed for $path (errno=$errno)")
}

/**
 * NTFS-3G filesystem facade over relative-LBA [BlockDevice].
 * Default mount is read-only; RW requires a clean volume (dirty → refuse RW).
 */
object NtfsFs {
    const val CLEAR_DIRTY_CONFIRMATION: String = "CONFIRM_NTFS_CLEAR_DIRTY"

    @Volatile
    private var nativeLoaded: Boolean = false

    private fun ensureNative() {
        if (!nativeLoaded) {
            synchronized(this) {
                if (!nativeLoaded) {
                    try {
                        System.loadLibrary("usb_native")
                    } catch (failure: UnsatisfiedLinkError) {
                        // Pure JVM unit tests lack the shared library; keep going so
                        // confirmation-token gates can still run. Device/host paths
                        // that actually call externals map UnsatisfiedLinkError below.
                        nativeLoaded = true
                        return
                    }
                    nativeLoaded = true
                }
            }
        }
    }

    private fun <T> withNativeEngine(op: () -> T): T {
        ensureNative()
        return requireLinkedEngine("ntfs-3g", op)
    }


    fun interface MountedVolumeGate {
        fun closeAll(device: BlockDevice)

        companion object {
            val NOOP: MountedVolumeGate = MountedVolumeGate { }
        }
    }

    private val registryLock = Any()
    private val openVolumes = WeakHashMap<BlockDevice, NtfsVolume>()
    private val mountingDevices = WeakHashMap<BlockDevice, Boolean>()

    /**
     * @param readOnly default true (browse-safe). RW mount refused when the on-disk
     *   dirty flag is set — clear via [clearDirtyFlag] first (ntfsfix equivalent).
     */
    fun mount(device: BlockDevice, readOnly: Boolean = true): NtfsVolume {
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        synchronized(registryLock) {
            if (openVolumes.containsKey(device) || mountingDevices.containsKey(device)) {
                throw NtfsException("NTFS volume already mounted")
            }
            mountingDevices[device] = true
        }
        try {
            return withNativeEngine {
                if (!readOnly && isDirty(device)) {
                    throw NtfsException(
                        "NTFS volume is dirty; refusing read-write mount. " +
                            "Run ntfsfix on a PC, or clear the dirty flag only if you accept the risk.",
                    )
                }
                val err = IntArray(1)
                val handle = nativeMount(
                    device,
                    device.sectorSize,
                    device.sectorCount.toLong(),
                    readOnly,
                    err,
                )
                if (handle == 0L) {
                    throw when (err[0]) {
                        16 /* EBUSY */ -> NtfsException(
                            "NTFS volume is dirty; refusing read-write mount. " +
                                "Run ntfsfix on a PC, or clear the dirty flag only if you accept the risk.",
                        )
                        else -> NtfsException("Failed to mount NTFS volume (errno=${err[0]})")
                    }
                }
                val volume = NtfsVolume(handle, device, readOnly)
                synchronized(registryLock) { openVolumes[device] = volume }
                volume
            }
        } finally {
            synchronized(registryLock) { mountingDevices.remove(device) }
        }
    }


    /**
     * Format [device] as NTFS with the upstream mkntfs code over the same
     * block callbacks used for mounting. Quick format (filesystem structures
     * only), no root or FUSE. Closes nothing by itself — caller must close
     * every mounted view first.
     */
    fun mkfs(device: BlockDevice, label: String = "") = withNativeEngine {
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        val rc = nativeMkfs(
            device,
            device.sectorSize,
            device.sectorCount.toLong(),
            label,
        )
        if (rc != 0) throw NtfsException("NTFS mkfs failed (errno=${-rc})")
    }

    /** Replace `$VOLUME_NAME` on a mounted-for-write volume (ntfs-3g `ntfs_volume_rename`). */
    fun setLabel(device: BlockDevice, label: String) = withNativeEngine {
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        val rc = nativeSetLabel(
            device,
            device.sectorSize,
            device.sectorCount.toLong(),
            label,
        )
        if (rc != 0) throw NtfsException("NTFS label update failed (errno=${-rc})")
    }

    /** Read `$VOLUME_NAME`; null when the volume carries no label. */
    fun getLabel(device: BlockDevice): String? = withNativeEngine {
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        nativeGetLabel(device, device.sectorSize, device.sectorCount.toLong())
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    fun isDirty(device: BlockDevice): Boolean = withNativeEngine {
        require(device.sectorSize in 512..4096) { "Unsupported sector size: ${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: ${device.sectorCount}" }
        nativeCheckDirty(device, device.sectorSize, device.sectorCount.toLong())
    }


    fun clearDirtyFlag(
        device: BlockDevice,
        confirmationToken: String,
        volumeGate: MountedVolumeGate = MountedVolumeGate.NOOP,
    ): Boolean {
        if (confirmationToken != CLEAR_DIRTY_CONFIRMATION) {
            throw IllegalArgumentException(
                "Confirmation token mismatch: expected $CLEAR_DIRTY_CONFIRMATION",
            )
        }

        val mounted = synchronized(registryLock) {
            if (mountingDevices.containsKey(device)) {
                throw NtfsException("NTFS volume mount or close already in progress")
            }
            mountingDevices[device] = true
            openVolumes[device]
        }
        try {
            mounted?.close()
            synchronized(registryLock) {
                if (openVolumes[device] === mounted) openVolumes.remove(device)
            }
            volumeGate.closeAll(device)
            return withNativeEngine {
                nativeClearDirty(
                    device,
                    device.sectorSize,
                    device.sectorCount.toLong(),
                    confirmationToken,
                )
            }
        } finally {
            synchronized(registryLock) { mountingDevices.remove(device) }
        }
    }


    internal fun unregisterVolume(device: BlockDevice, volume: NtfsVolume) {
        synchronized(registryLock) {
            if (openVolumes[device] === volume) openVolumes.remove(device)
        }
    }

    @JvmStatic private external fun nativeMount(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        readOnly: Boolean,
        outErrno: IntArray,
    ): Long

    @JvmStatic private external fun nativeMkfs(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        label: String,
    ): Int

    @JvmStatic private external fun nativeSetLabel(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        label: String,
    ): Int

    @JvmStatic private external fun nativeGetLabel(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
    ): String?

    @JvmStatic private external fun nativeCheckDirty(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
    ): Boolean

    @JvmStatic private external fun nativeClearDirty(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        confirmationToken: String,
    ): Boolean

    @JvmStatic internal external fun nativeClose(handle: Long): Int
    @JvmStatic internal external fun nativeSync(handle: Long): Int
    @JvmStatic internal external fun nativeList(handle: Long, path: String): Array<NtfsDirEntry>?
    @JvmStatic internal external fun nativeStat(handle: Long, path: String, outErrno: IntArray): NtfsStat?
    @JvmStatic internal external fun nativeRead(
        handle: Long,
        path: String,
        offset: Long,
        length: Int,
        outErrno: IntArray,
    ): ByteArray?

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
    @JvmStatic internal external fun nativeIsVolumeDirty(handle: Long): Boolean

    /** Writes total/free bytes into [out]; true only with both values written. */
    @JvmStatic internal external fun nativeUsage(handle: Long, out: LongArray): Boolean
}

class NtfsVolume internal constructor(
    private val handle: Long,
    val device: BlockDevice,
    val readOnly: Boolean,
) : Closeable, AutoCloseable, UsbFsVolume, UsbFsUsageVolume, UsbFsAccessVolume {
    override val access: FilesystemAccess =
        if (readOnly) FilesystemAccess.ReadOnly else FilesystemAccess.ReadWrite
    @Volatile private var open: Boolean = true

    fun isOpen(): Boolean = open

    private fun ensureOpen() {
        check(open) { "NTFS volume closed" }
    }

    @Synchronized
    override fun space(): FilesystemSpace {
        ensureOpen()
        val out = LongArray(2)
        if (!NtfsFs.nativeUsage(handle, out)) {
            throw NtfsException("space query failed")
        }
        return FilesystemSpace(totalBytes = out[0].toULong(), freeBytes = out[1].toULong())
    }

    private fun ensureWritable() {
        ensureOpen()
        if (readOnly) throw NtfsException("Read-only NTFS volume")
    }

    @Synchronized
    override fun list(path: String): List<UsbFsDirEntry> {
        ensureOpen()
        val entries = NtfsFs.nativeList(handle, path)
            ?: throw NtfsException("Failed to list: $path")
        return entries.map { UsbFsDirEntry(it.name, it.isDirectory, it.size) }
    }

    override fun stat(path: String): UsbFsDirEntry =
        statOrNull(path) ?: throw NtfsException("Path not found: $path")

    @Synchronized
    override fun statOrNull(path: String): UsbFsDirEntry? {
        ensureOpen()
        val err = IntArray(1)
        val st = NtfsFs.nativeStat(handle, path, err) ?: return mapNtfsStatOrNullMiss(path, err[0])
        val name = path.trimEnd('/').substringAfterLast('/').ifEmpty { path }
        return UsbFsDirEntry(name, st.isDirectory, st.size)
    }

    @Synchronized
    override fun read(path: String, offset: Long, length: Int): ByteArray {
        ensureOpen()
        requireIoRange(offset, length, MAX_NTFS_IO_BYTES)
        val err = IntArray(1)
        val data = NtfsFs.nativeRead(handle, path, offset, length, err)
            ?: throw when (err[0]) {
                2 /* ENOENT */ -> NtfsException("Path not found: $path")
                1 /* EPERM */ -> NtfsException("Refusing to read NTFS metadata: $path")
                else -> NtfsException("Failed to read: $path (errno=${err[0]})")
            }
        return data
    }


    override fun write(
        path: String,
        data: ByteArray,
        offset: Long,
        create: Boolean,
        plannedTotalSize: Long,
        exclusiveCreate: Boolean,
    ) {
        ensureWritable()
        requireIoRange(offset, data.size, MAX_NTFS_IO_BYTES)
        val wrote = NtfsFs.nativeWrite(handle, path, offset, data, create, exclusiveCreate)
        if (wrote < 0 || wrote != data.size) {
            throw NtfsException("short write on $path: $wrote of ${data.size}")
        }
    }

    @Synchronized
    override fun mkdir(path: String) {
        ensureWritable()
        NtfsFs.nativeMkdir(handle, path)
    }

    @Synchronized
    override fun delete(path: String) {
        ensureWritable()
        try {
            NtfsFs.nativeDelete(handle, path)
        } catch (failure: NtfsException) {
            if (failure.message == "Directory not empty") {
                throw NtfsException("Directory not empty: $path")
            }
            throw failure
        }
    }

    @Synchronized
    override fun rename(from: String, to: String) {
        ensureWritable()
        NtfsFs.nativeRename(handle, from, to)
    }

    @Synchronized
    override fun sync() {
        ensureOpen()
        val rc = NtfsFs.nativeSync(handle)
        if (rc != 0) {
            throw NtfsException("Failed to sync NTFS volume: rc=$rc")
        }
    }

    @Synchronized
    fun isDirty(): Boolean {
        ensureOpen()
        return NtfsFs.nativeIsVolumeDirty(handle)
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        try {
            val rc = NtfsFs.nativeClose(handle)
            if (rc != 0) {
                throw NtfsException("Failed to close NTFS volume: rc=$rc")
            }
        } finally {
            // Keep registry entry until native teardown returns; direct remount cannot overlap close.
            NtfsFs.unregisterVolume(device, this)
        }
    }
}
