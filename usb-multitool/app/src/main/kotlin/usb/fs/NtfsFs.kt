package usb.fs

import usb.device.BlockDevice
import java.io.Closeable
import java.nio.ByteBuffer
import java.util.Collections
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

/**
 * NTFS-3G filesystem facade over relative-LBA [BlockDevice].
 * All byte offsets are translated to sectors without second partition offsets.
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
                    } catch (_: UnsatisfiedLinkError) {
                        // Pure JVM test environments
                    }
                    nativeLoaded = true
                }
            }
        }
    }

    /**
     * Gate invoked before dirty-flag clear writes to close all active volumes.
     */
    fun interface MountedVolumeGate {
        fun closeAll(device: BlockDevice)

        companion object {
            val NOOP: MountedVolumeGate = MountedVolumeGate { }
        }
    }

    private val openVolumes = Collections.synchronizedMap(WeakHashMap<BlockDevice, NtfsVolume>())

    fun mount(device: BlockDevice, readOnly: Boolean = false): NtfsVolume {
        ensureNative()
        require(device.sectorSize in 512..4096) { "Unsupported sector size: \${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: \${device.sectorCount}" }

        val handle = nativeMount(device, device.sectorSize, device.sectorCount.toLong(), readOnly)
        if (handle == 0L) {
            throw NtfsException("Failed to mount NTFS volume")
        }
        val volume = NtfsVolume(handle, device)
        openVolumes[device] = volume
        return volume
    }

    fun isDirty(device: BlockDevice): Boolean {
        ensureNative()
        require(device.sectorSize in 512..4096) { "Unsupported sector size: \${device.sectorSize}" }
        require(device.sectorCount > 0uL) { "Invalid sector count: \${device.sectorCount}" }
        return nativeCheckDirty(device, device.sectorSize, device.sectorCount.toLong())
    }

    /**
     * Clear volume dirty flag (ntfsfix equivalent).
     * Requires [confirmationToken] matching [CLEAR_DIRTY_CONFIRMATION].
     * Closes mounted volumes before clearing.
     */
    fun clearDirtyFlag(
        device: BlockDevice,
        confirmationToken: String,
        volumeGate: MountedVolumeGate = MountedVolumeGate.NOOP,
    ): Boolean {
        if (confirmationToken != CLEAR_DIRTY_CONFIRMATION) {
            throw IllegalArgumentException("Confirmation token mismatch: expected \$CLEAR_DIRTY_CONFIRMATION")
        }

        // Close any tracked open volumes on this device
        synchronized(openVolumes) {
            openVolumes[device]?.let { vol ->
                if (vol.isOpen()) {
                    vol.close()
                }
                openVolumes.remove(device)
            }
        }
        // Notify gate to close all other filesystem / disk references
        volumeGate.closeAll(device)

        ensureNative()
        return nativeClearDirty(
            device,
            device.sectorSize,
            device.sectorCount.toLong(),
            confirmationToken,
        )
    }

    internal fun unregisterVolume(device: BlockDevice) {
        openVolumes.remove(device)
    }

    @JvmStatic private external fun nativeMount(
        device: BlockDevice,
        sectorSize: Int,
        sectorCount: Long,
        readOnly: Boolean,
    ): Long

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
    @JvmStatic internal external fun nativeList(handle: Long, path: String): Array<NtfsDirEntry>
    @JvmStatic internal external fun nativeStat(handle: Long, path: String): NtfsStat
    @JvmStatic internal external fun nativeRead(handle: Long, path: String, offset: Long, length: Int): ByteArray
    @JvmStatic internal external fun nativeWrite(
        handle: Long,
        path: String,
        offset: Long,
        data: ByteArray,
        create: Boolean,
    ): Int
    @JvmStatic internal external fun nativeIsVolumeDirty(handle: Long): Boolean
}

class NtfsVolume internal constructor(
    private val handle: Long,
    val device: BlockDevice,
) : Closeable, AutoCloseable {
    @Volatile private var open: Boolean = true

    fun isOpen(): Boolean = open

    private fun ensureOpen() {
        check(open) { "NTFS volume closed" }
    }

    fun list(path: String = "/"): List<NtfsDirEntry> {
        ensureOpen()
        val entries = NtfsFs.nativeList(handle, path)
            ?: throw NtfsException("Failed to list: $path")
        return entries.toList()
    }

    fun stat(path: String): NtfsStat {
        ensureOpen()
        return NtfsFs.nativeStat(handle, path)
            ?: throw NtfsException("Path not found: $path")
    }

    fun read(path: String, offset: Long = 0L, length: Int): ByteArray {
        ensureOpen()
        return NtfsFs.nativeRead(handle, path, offset, length)
            ?: throw NtfsException("Failed to read: $path")
    }

    fun write(path: String, data: ByteArray, offset: Long = 0L, create: Boolean = true): Int {
        ensureOpen()
        return NtfsFs.nativeWrite(handle, path, offset, data, create)
    }

    fun sync() {
        ensureOpen()
        val rc = NtfsFs.nativeSync(handle)
        if (rc != 0) {
            throw NtfsException("Failed to sync NTFS volume: rc=\$rc")
        }
    }

    fun isDirty(): Boolean {
        ensureOpen()
        return NtfsFs.nativeIsVolumeDirty(handle)
    }

    @Synchronized
    override fun close() {
        if (!open) return
        open = false
        try {
            NtfsFs.unregisterVolume(device)
        } finally {
            val rc = NtfsFs.nativeClose(handle)
            if (rc != 0) {
                throw NtfsException("Failed to close NTFS volume: rc=$rc")
            }
        }
    }
}
