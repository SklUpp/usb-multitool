package usb.fs

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.partition.VirtualPartition

class VolumeFile(
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
)

class MountedVolume internal constructor(
    val id: FilesystemId,
    val partition: VirtualPartition,
    private val nativeHandle: Long,
) {
    val root: VolumeFile = VolumeFile("/", isDirectory = true, size = 0L)

    @Volatile
    private var open: Boolean = true

    fun isOpen(): Boolean = open

    fun sync() {
        check(open) { "volume closed" }
        if (nativeHandle != 0L) {
            FilesystemNative.sync(nativeHandle)
        }
    }

    fun close() {
        if (!open) return
        open = false
        if (nativeHandle != 0L) {
            FilesystemNative.close(nativeHandle)
        }
    }
}

object Filesystem {
    fun mount(partition: VirtualPartition): MountedVolume {
        val id = probe(partition)
        // fs_bridge.c is not linked into libusb_native yet. A null handle would look
        // like a successful mount while sync/close are no-ops — refuse instead.
        if (!FilesystemNative.isLinked()) {
            throw UnsupportedFilesystemException(
                "filesystem engine bridge is not linked for $id",
            )
        }
        val handle = FilesystemNative.mount(id.ordinal, partition)
        if (handle == 0L) {
            throw UnsupportedFilesystemException("native mount failed for $id")
        }
        return MountedVolume(id, partition, handle)
    }

    fun probe(partition: VirtualPartition): FilesystemId {
        val sector = ByteArray(partition.sectorSize)
        val buf = ByteBuffer.wrap(sector)
        partition.read(0UL, buf)

        if (isExfat(sector)) return FilesystemId.EXFAT
        if (isFat(sector)) return FilesystemId.FAT
        if (isNtfs(sector)) return FilesystemId.NTFS
        if (isExt(partition)) return FilesystemId.EXT
        throw UnsupportedFilesystemException("no supported filesystem signature")
    }

    private fun isExfat(boot: ByteArray): Boolean =
        boot.size >= 11 &&
            boot[510] == 0x55.toByte() && boot[511] == 0xAA.toByte() &&
            oem(boot) == "EXFAT   "

    private fun isFat(boot: ByteArray): Boolean {
        if (boot.size < 512 || boot[510] != 0x55.toByte() || boot[511] != 0xAA.toByte()) return false
        if (oem(boot) == "EXFAT   " || oem(boot) == "NTFS    ") return false
        val fat16 = ascii(boot, 54, 8)
        val fat32 = ascii(boot, 82, 8)
        return fat16.startsWith("FAT") || fat32.startsWith("FAT") ||
            // BPB with valid bytes/sector and sectors/cluster, common for FAT images without FS type string
            (u16(boot, 11) in setOf(512, 1024, 2048, 4096) && boot[13].toInt() and 0xFF != 0)
    }

    private fun isNtfs(boot: ByteArray): Boolean =
        boot.size >= 11 &&
            boot[510] == 0x55.toByte() && boot[511] == 0xAA.toByte() &&
            oem(boot) == "NTFS    "

    private fun isExt(partition: VirtualPartition): Boolean {
        if (partition.sectorCount * partition.sectorSize.toULong() < 2048u) return false
        val block = ByteArray(1024)
        val absoluteByte = 1024L
        val sectorSize = partition.sectorSize
        val startSector = (absoluteByte / sectorSize).toULong()
        val offsetInSector = (absoluteByte % sectorSize).toInt()
        val need = offsetInSector + 1024
        val sectors = ((need + sectorSize - 1) / sectorSize)
        val raw = ByteArray(sectors * sectorSize)
        partition.read(startSector, ByteBuffer.wrap(raw))
        System.arraycopy(raw, offsetInSector, block, 0, 1024)
        val magic = u16(block, 0x38)
        return magic == 0xEF53
    }

    private fun oem(boot: ByteArray): String = ascii(boot, 3, 8)

    private fun ascii(bytes: ByteArray, offset: Int, len: Int): String =
        bytes.copyOfRange(offset, offset + len).toString(Charsets.US_ASCII)

    private fun u16(bytes: ByteArray, offset: Int): Int {
        val bb = ByteBuffer.wrap(bytes, offset, 2).order(ByteOrder.LITTLE_ENDIAN)
        return bb.short.toInt() and 0xFFFF
    }
}

class UnsupportedFilesystemException(message: String) : Exception(message)

/**
 * JNI partition boundary. Unit tests stay on the JVM probe path with a null handle.
 * Real engines load only when the shared library is present.
 */
internal object FilesystemNative {
    @Volatile
    private var libraryLoaded: Boolean = false

    init {
        try {
            System.loadLibrary("usb_native")
            libraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
        }
    }

    /** True only when fs_bridge JNI symbols are present in the loaded library. */
    fun isLinked(): Boolean {
        if (!libraryLoaded) return false
        return try {
            // Probe with impossible geometry so a real implementation returns quickly.
            nativeProbe(0L, 0L, 0)
            true
        } catch (_: UnsatisfiedLinkError) {
            false
        }
    }

    fun mount(idOrdinal: Int, partition: VirtualPartition): Long {
        if (!isLinked()) return 0L
        return nativeMount(
            idOrdinal,
            partition.startLba.toLong(),
            partition.sectorCount.toLong(),
            partition.sectorSize,
        )
    }

    fun sync(handle: Long) {
        if (libraryLoaded && handle != 0L) nativeSync(handle)
    }

    fun close(handle: Long) {
        if (libraryLoaded && handle != 0L) nativeClose(handle)
    }

    @JvmStatic external fun nativeMount(
        idOrdinal: Int,
        startLba: Long,
        sectorCount: Long,
        sectorSize: Int,
    ): Long

    @JvmStatic external fun nativeSync(handle: Long)

    @JvmStatic external fun nativeClose(handle: Long)

    @JvmStatic external fun nativeProbe(startLba: Long, sectorCount: Long, sectorSize: Int): Int
}
