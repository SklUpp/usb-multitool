package usb.diagnostics

/**
 * Native SectorForge engine (`libsf_bridge.a` inside `libusb_native.so`).
 *
 * Kotlin keeps ownership of the device and the image file; the Rust engine calls
 * back per read/write and reports progress. Nothing here simulates a rescue: when
 * the engine was not compiled into this build, [available] is false and
 * [SectorForgeEngine] refuses to start instead of falling back to a different
 * implementation.
 */
internal object SectorForgeNative {
    /** `sf_run_execute` results, mirrored from `sf-bridge/src/lib.rs`. */
    const val STATUS_OK = 0
    const val STATUS_CANCELLED = 1
    const val STATUS_FATAL = 2
    const val STATUS_BAD_HANDLE = 3

    /** `read_at` results reported to the engine. */
    const val READ_MEDIUM = 1
    const val READ_CANCELLED = 3

    private val libraryLoaded: Boolean = runCatching { System.loadLibrary("usb_native") }.isSuccess

    val available: Boolean by lazy { libraryLoaded && runCatching { nativeEnginePresent() }.getOrDefault(false) }

    @JvmStatic external fun nativeEnginePresent(): Boolean

    @JvmStatic external fun nativeStart(
        reader: Any,
        writer: Any,
        progress: Any,
        seeds: LongArray,
        sizeBytes: Long,
        sectorSize: Int,
        initialChunk: Int,
        skipBytes: Int,
        maxRetries: Int,
        reverse: Boolean,
        checkpointBytes: Long,
    ): Long

    @JvmStatic external fun nativeExecute(handle: Long): Int

    @JvmStatic external fun nativeBlocks(handle: Long, out: LongArray): Long

    @JvmStatic external fun nativeFree(handle: Long)

    /** Full block snapshot; grows the buffer once if the map is more fragmented. */
    fun blocks(handle: Long): LongArray {
        var capacity = 32
        while (true) {
            val out = LongArray(capacity * 3)
            val needed = nativeBlocks(handle, out)
            check(needed >= 0) { "SectorForge engine lost the run handle" }
            if (needed <= capacity) return out.copyOf((needed * 3).toInt())
            capacity = needed.toInt()
        }
    }
}
