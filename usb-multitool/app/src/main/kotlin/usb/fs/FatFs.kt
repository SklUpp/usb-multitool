package usb.fs

/**
 * FatFs R0.16 facade. Block callbacks use relative LBAs only;
 * VirtualPartition (or image file) applies the partition start once.
 */
enum class FatKind(val code: Int) {
    UNKNOWN(0),
    FAT12(12),
    FAT16(16),
    FAT32(32),
    EXFAT(64);

    companion object {
        fun fromCode(code: Int): FatKind =
            entries.firstOrNull { it.code == code } ?: UNKNOWN
    }
}

enum class FatMkfsType(val code: Int) {
    FAT(1),
    FAT32(2),
    EXFAT(4),
    ANY(7),
}

data class FatDirEntry(
    val name: String,
    val isDirectory: Boolean,
    val size: Long,
    val attrib: Byte = 0,
)

class FatFsException(message: String) : Exception(message)

class FileTooLargeException(message: String) : Exception(message)

/** Pure write-policy helpers (host-testable without JNI). */
internal fun resolvePlannedTotal(offset: Long, dataSize: Int, plannedTotalSize: Long): Long =
    maxOf(offset + dataSize.toLong(), plannedTotalSize)

internal fun requireWithinFileLimit(planned: Long, max: Long, kind: FatKind) {
    if (planned > max) {
        throw FileTooLargeException("planned size $planned exceeds $kind limit $max")
    }
}

internal fun requireFullWrite(wrote: Int, expected: Int) {
    if (wrote < expected) {
        throw FatFsException("short write: wrote $wrote of $expected bytes")
    }
}


/** JNI block backend: relative LBA sector I/O. Return 0 on success. */
interface FatBlockCallback {
    fun readSectors(lba: Long, buffer: ByteArray): Int
    fun writeSectors(lba: Long, buffer: ByteArray): Int
    fun sync(): Int
}

object FatFsNative {
    init {
        try {
            System.loadLibrary("usb_native")
        } catch (_: UnsatisfiedLinkError) {
            // Host unit tests may run without the shared library.
        }
    }

    @JvmStatic external fun nativeOpenBlock(callback: FatBlockCallback, sectorSize: Int, sectorCount: Long): Long
    @JvmStatic external fun nativeCloseBlock(handle: Long)
    @JvmStatic external fun nativeMount(blockHandle: Long, sectorSize: Int, sectorCount: Long, outKind: IntArray): Long
    @JvmStatic external fun nativeUmount(volume: Long)
    @JvmStatic external fun nativeSync(volume: Long)
    @JvmStatic external fun nativeList(volume: Long, path: String): Array<FatDirEntry>
    @JvmStatic external fun nativeStat(volume: Long, path: String): FatDirEntry
    @JvmStatic external fun nativeMkdir(volume: Long, path: String)
    @JvmStatic external fun nativeDelete(volume: Long, path: String)
    @JvmStatic external fun nativeRename(volume: Long, from: String, to: String)
    @JvmStatic external fun nativeRead(volume: Long, path: String, offset: Long, length: Int): ByteArray
    @JvmStatic external fun nativeWrite(
        volume: Long,
        path: String,
        offset: Long,
        data: ByteArray,
        plannedTotalSize: Long,
        create: Boolean,
    ): Int
    @JvmStatic external fun nativeMkfs(blockHandle: Long, sectorSize: Int, sectorCount: Long, type: Int)
    @JvmStatic external fun nativeProbe(blockHandle: Long, sectorSize: Int, sectorCount: Long): Int
    @JvmStatic external fun nativeMaxFileSize(kind: Int): Long
}

class FatVolume internal constructor(
    val kind: FatKind,
    private val volumeHandle: Long,
    private val blockHandle: Long,
    private val ownsBlock: Boolean,
) : AutoCloseable {
    @Volatile private var open = true

    fun isOpen(): Boolean = open

    fun list(path: String = "/"): List<FatDirEntry> {
        ensureOpen()
        return FatFsNative.nativeList(volumeHandle, path).toList()
    }

    fun stat(path: String): FatDirEntry {
        ensureOpen()
        return FatFsNative.nativeStat(volumeHandle, path)
    }

    fun mkdir(path: String) {
        ensureOpen()
        FatFsNative.nativeMkdir(volumeHandle, path)
    }

    fun delete(path: String) {
        ensureOpen()
        FatFsNative.nativeDelete(volumeHandle, path)
    }

    fun rename(from: String, to: String) {
        ensureOpen()
        FatFsNative.nativeRename(volumeHandle, from, to)
    }

    fun read(path: String, offset: Long = 0L, length: Int): ByteArray {
        ensureOpen()
        return FatFsNative.nativeRead(volumeHandle, path, offset, length)
    }

    fun write(
        path: String,
        data: ByteArray,
        offset: Long = 0L,
        create: Boolean = true,
        plannedTotalSize: Long = offset + data.size.toLong(),
    ) {
        ensureOpen()
        val planned = resolvePlannedTotal(offset, data.size, plannedTotalSize)
        requireWithinFileLimit(planned, FatFsNative.nativeMaxFileSize(kind.code), kind)
        val wrote = FatFsNative.nativeWrite(volumeHandle, path, offset, data, planned, create)
        requireFullWrite(wrote, data.size)
    }



    fun sync() {
        ensureOpen()
        FatFsNative.nativeSync(volumeHandle)
    }

    override fun close() {
        if (!open) return
        open = false
        FatFsNative.nativeUmount(volumeHandle)
        if (ownsBlock) FatFsNative.nativeCloseBlock(blockHandle)
    }

    private fun ensureOpen() {
        check(open) { "volume closed" }
    }
}

object FatFs {
    fun openBlock(callback: FatBlockCallback, sectorSize: Int, sectorCount: Long): Long =
        FatFsNative.nativeOpenBlock(callback, sectorSize, sectorCount).also {
            require(it != 0L) { "nativeOpenBlock failed" }
        }

    fun closeBlock(handle: Long) = FatFsNative.nativeCloseBlock(handle)

    fun mount(callback: FatBlockCallback, sectorSize: Int, sectorCount: Long): FatVolume {
        val block = openBlock(callback, sectorSize, sectorCount)
        return try {
            mountHandle(block, sectorSize, sectorCount, ownsBlock = true)
        } catch (t: Throwable) {
            closeBlock(block)
            throw t
        }
    }

    fun mountHandle(blockHandle: Long, sectorSize: Int, sectorCount: Long, ownsBlock: Boolean = false): FatVolume {
        val kindOut = IntArray(1)
        val vol = FatFsNative.nativeMount(blockHandle, sectorSize, sectorCount, kindOut)
        if (vol == 0L) throw FatFsException("mount returned null handle")
        return FatVolume(FatKind.fromCode(kindOut[0]), vol, blockHandle, ownsBlock)
    }

    fun mkfs(callback: FatBlockCallback, sectorSize: Int, sectorCount: Long, type: FatMkfsType) {
        val block = openBlock(callback, sectorSize, sectorCount)
        try {
            FatFsNative.nativeMkfs(block, sectorSize, sectorCount, type.code)
        } finally {
            closeBlock(block)
        }
    }

    fun probe(callback: FatBlockCallback, sectorSize: Int, sectorCount: Long): FatKind {
        val block = openBlock(callback, sectorSize, sectorCount)
        return try {
            FatKind.fromCode(FatFsNative.nativeProbe(block, sectorSize, sectorCount))
        } finally {
            closeBlock(block)
        }
    }

    fun maxFileSize(kind: FatKind): Long = FatFsNative.nativeMaxFileSize(kind.code)
}
