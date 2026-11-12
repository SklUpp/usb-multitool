package usb.image

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.FileInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.GZIPInputStream
import org.apache.commons.compress.compressors.xz.XZCompressorInputStream

private const val ZERO_READ_LIMIT = 3

class CountedInputStream(
    private val input: InputStream,
    val decodedSizeHint: Long? = null,
) : InputStream() {
    var bytesRead: Long = 0L
        private set

    override fun read(): Int {
        val b = input.read()
        if (b >= 0) bytesRead++
        return b
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        val n = input.read(b, off, len)
        if (n > 0) bytesRead += n.toLong()
        return n
    }

    override fun close() = input.close()
}

/** Exact decoded length plus SHA-256 of the decoded bytes, used to bind confirmation. */
data class MeasuredSource(val bytes: Long, val sha256: String)

/** Thrown when the bounded measurement/close budget has no free reservation. */
class CloseCapacityException(
    message: String = "too many provider streams are still closing; retry after they finish",
) : Exception(message)

/** Reservation for the bounded close budget; releasing it twice is harmless. */
class CloseAdmission internal constructor(private val onRelease: () -> Unit) {
    private val released = java.util.concurrent.atomic.AtomicBoolean(false)

    fun release() {
        if (released.compareAndSet(false, true)) onRelease()
    }
}

/**
 * Tracks the stream currently used by a measurement pass so cancellation can close it.
 * Closing a provider stream does not guarantee that a read already blocked inside the
 * provider returns promptly; it does guarantee the descriptor is tracked. Every handle
 * reserves one admission token until its stream is closed, which bounds active + stuck
 * streams globally, and close ownership is claimed atomically so two workers can never
 * close the same stream. Cancellation retains the admission while `open()` may still
 * return; completion paths must pair [cancel] with [abandon] so a pass that never
 * attaches cannot leak a token.
 */
class MeasurementHandle private constructor(
    acquireAdmission: Boolean,
    transferredAdmission: CloseAdmission?,
) {
    private val lock = Any()
    private var stream: InputStream? = null
    @Volatile private var cancelled = false
    private var closeDeferred = false
    private var closeInFlight = false
    /** Owned reservation; transferred to a close worker when the stream is dispatched. */
    private var admission: CloseAdmission? = null

    init {
        admission =
            transferredAdmission ?: if (acquireAdmission) {
                if (!ADMISSIONS.tryAcquire()) throw CloseCapacityException()
                CloseAdmission { ADMISSIONS.release() }
            } else {
                null
            }
    }

    constructor() : this(acquireAdmission = true, transferredAdmission = null)

    /** Adopt a reservation that was acquired by the caller before an earlier open. */
    constructor(admission: CloseAdmission) : this(
        acquireAdmission = false,
        transferredAdmission = admission,
    )

    fun isActive(): Boolean = !cancelled

    fun attach(input: InputStream) {
        val cancelledNow =
            synchronized(lock) {
                if (cancelled) {
                    true
                } else {
                    stream = input
                    false
                }
            }
        if (cancelledNow) {
            // Open finished after cancel: the caller still owns the admission, so route
            // the descriptor through the same bounded closer instead of blocking here.
            if (!dispatchLateAttach(input)) {
                synchronized(lock) {
                    stream = input
                    closeInFlight = false
                    closeDeferred = true
                }
                DEFERRED_HANDLES.add(this)
                retryDeferredCloses()
            }
        }
    }

    /** Attempt to close a stream that arrived after cancellation. */
    private fun dispatchLateAttach(input: InputStream): Boolean {
        synchronized(lock) {
            if (closeInFlight || closeDeferred) return false
            closeInFlight = true
        }
        if (dispatchClose(input)) return true
        synchronized(lock) { closeInFlight = false }
        return false
    }

    /** Measurement completed normally: close on the measuring IO thread. */
    fun release(input: InputStream) {
        val owns = claimRelease(input) ?: return
        try {
            runCatching { input.close() }
        } finally {
            finishRelease()
        }
    }

    /** Like [release], but a close failure propagates (readback keeps use{} semantics). */
    fun releaseOrThrow(input: InputStream) {
        val owns = claimRelease(input) ?: return
        try {
            input.close()
        } finally {
            finishRelease()
        }
    }

    private fun claimRelease(input: InputStream): InputStream? =
        synchronized(lock) {
            if (stream === input && !closeDeferred && !closeInFlight) {
                stream = null
                closeInFlight = true
                input
            } else {
                null
            }
        }

    private fun finishRelease() {
        synchronized(lock) { closeInFlight = false }
        releaseAdmission()
    }

    /**
     * Marks the pass cancelled and hands the tracked stream to a bounded background
     * closer. When no stream is attached yet the admission is retained because `open()`
     * may still return; call [abandon] once it is certain no stream will arrive (for
     * example from coroutine completion or after a failed open). Returns false when
     * every closer slot is occupied by a stuck close: the stream stays tracked here and
     * in the shared deferred set until a slot frees.
     */
    fun cancel(): Boolean {
        cancelled = true
        val current = synchronized(lock) {
            if (closeInFlight) return true
            if (closeDeferred) return false
            val value = stream
            if (value != null) {
                stream = null
                closeInFlight = true
            }
            value
        }
        if (current == null) {
            // open() may still be in flight. Keep the admission until attach() routes
            // the late descriptor or abandon() proves no descriptor will arrive.
            return true
        }
        if (dispatchClose(current)) return true
        synchronized(lock) {
            stream = current
            closeInFlight = false
            closeDeferred = true
        }
        DEFERRED_HANDLES.add(this)
        retryDeferredCloses()
        return false
    }

    /**
     * Releases the admission when no stream can arrive (open failed, coroutine ended
     * before attach, or a finished pass). Never drops a tracked/deferred descriptor.
     */
    fun abandon() {
        val releasable =
            synchronized(lock) {
                stream == null && !closeInFlight && !closeDeferred
            }
        if (releasable) releaseAdmission()
    }

    /** True when the deferred close has been handed to a worker. */
    fun retryDeferredClose(): Boolean {
        val current = synchronized(lock) {
            if (!closeDeferred) {
                DEFERRED_HANDLES.remove(this)
                return true
            }
            if (closeInFlight) return false
            val value = stream
            if (value == null) {
                closeDeferred = false
                DEFERRED_HANDLES.remove(this)
                return true
            }
            stream = null
            closeInFlight = true
            value
        }
        if (!dispatchClose(current)) {
            synchronized(lock) {
                if (stream == null) stream = current
                closeInFlight = false
                closeDeferred = true
            }
            return false
        }
        synchronized(lock) { closeDeferred = false }
        DEFERRED_HANDLES.remove(this)
        return true
    }

    /** Atomically claimed by exactly one closer before the stream is dispatched. */
    private fun dispatchClose(input: InputStream): Boolean {
        if (!CLOSE_SLOTS.tryAcquire()) return false
        try {
            CLOSE_EXECUTOR.execute {
                try {
                    runCatching { input.close() }
                } finally {
                    finishClose()
                    CLOSE_SLOTS.release()
                    retryDeferredCloses()
                }
            }
            return true
        } catch (_: Throwable) {
            CLOSE_SLOTS.release()
            return false
        }
    }

    private fun finishClose() {
        synchronized(lock) {
            closeInFlight = false
            closeDeferred = false
        }
        releaseAdmission()
    }

    private fun releaseAdmission() {
        val owned =
            synchronized(lock) {
                val value = admission
                admission = null
                value
            }
        owned?.release()
    }

    companion object {
        /** Bounded so cancelled/stuck provider closes cannot pile up forever. */
        private const val MAX_CONCURRENT_CLOSES = 2
        /** Deferred streams held here until one of the bounded close workers frees up. */
        private const val MAX_DEFERRED_CLOSES = 2
        /** Admission covers active measurement streams plus deferred/in-flight closes. */
        private const val MAX_ADMISSIONS = MAX_CONCURRENT_CLOSES + MAX_DEFERRED_CLOSES
        private val CLOSE_SLOTS = java.util.concurrent.Semaphore(MAX_CONCURRENT_CLOSES)
        private val ADMISSIONS = java.util.concurrent.Semaphore(MAX_ADMISSIONS)
        private val DEFERRED_HANDLES =
            java.util.concurrent.ConcurrentHashMap.newKeySet<MeasurementHandle>()
        private val CLOSE_EXECUTOR =
            java.util.concurrent.Executors.newFixedThreadPool(MAX_CONCURRENT_CLOSES) { runnable ->
                Thread(runnable, "raw-measure-close").apply { isDaemon = true }
            }

        /** Reserve budget before opening a source; null means fail closed. */
        fun admitOrNull(): CloseAdmission? =
            if (ADMISSIONS.tryAcquire()) {
                CloseAdmission { ADMISSIONS.release() }
            } else {
                null
            }

        fun pendingCloseCount(): Int = DEFERRED_HANDLES.size

        /** Remaining tokens; lets tests synchronize on prior closes releasing them. */
        internal fun availableAdmissions(): Int = ADMISSIONS.availablePermits()

        /** Remaining bounded close workers; lets tests wait for prior closes to finish. */
        internal fun availableCloseWorkers(): Int = CLOSE_SLOTS.availablePermits()

        fun retryDeferredCloses() {
            if (DEFERRED_HANDLES.isEmpty()) return
            for (handle in DEFERRED_HANDLES) {
                if (!handle.retryDeferredClose() && CLOSE_SLOTS.availablePermits() == 0) return
            }
        }
    }
}

class ImageSource private constructor(
    private val openStream: () -> CountedInputStream,
    initialFormat: ImageFormat,
    declaredDecodedSize: Long? = null,
    /** True for an app-private staged file or in-memory bytes that cannot be swapped. */
    val snapshot: Boolean = false,
) {
    /** Updated on [open] when content sniffing reconciles name vs magic. */
    var format: ImageFormat = initialFormat
        private set

    /**
     * Decoded length claimed by the source. Cleared on [open] when the live stream proves
     * the bytes are compressed, so a provider SIZE is never mistaken for a decoded size.
     */
    var declaredDecodedSize: Long? = declaredDecodedSize
        private set

    /**
     * Size that may size a raw write range: only an uncompressed image whose decoded
     * length equals the declared length. Compressed or unknown streams return null and
     * must be refused before the first sector write.
     */
    val decodedSizeForRawWrite: Long?
        get() = declaredDecodedSize.takeIf {
            format == ImageFormat.ISO || format == ImageFormat.IMG
        }

    fun open(): CountedInputStream = openStream()

    /**
     * Decode the source once, count decoded bytes and hash them. Read-only, cancellable
     * between chunks, no temporary uncompressed copy. [limitBytes] bounds the pass so a
     * decompression bomb stops before the UI/disk can be exhausted.
     */
    fun measureDecoded(
        isActive: () -> Boolean = { true },
        limitBytes: Long? = null,
        chunkBytes: Int = MEASURE_CHUNK_BYTES,
        handle: MeasurementHandle? = null,
    ): MeasuredSource {
        require(chunkBytes > 0) { "chunkBytes must be positive" }
        val buffer = ByteArray(chunkBytes)
        val digest = java.security.MessageDigest.getInstance("SHA-256")
        val input =
            try {
                open()
            } catch (failure: Throwable) {
                handle?.abandon()
                throw failure
            }
        handle?.attach(input)
        try {
            var total = 0L
            var zeroReads = 0
            while (true) {
                if (!isActive() || handle?.isActive() == false) {
                    throw java.util.concurrent.CancellationException(
                        "raw source measurement cancelled after $total bytes",
                    )
                }
                val read = input.read(buffer)
                if (read < 0) break
                if (read == 0) {
                    if (++zeroReads >= ZERO_READ_LIMIT) {
                        throw java.io.IOException("stream made no progress while measuring")
                    }
                    continue
                }
                zeroReads = 0
                total += read.toLong()
                if (total < 0L) {
                    throw IllegalStateException("decoded size overflows Long")
                }
                if (limitBytes != null && total > limitBytes) {
                    throw ImageTooLargeException(
                        "decoded source exceeds the $limitBytes byte limit",
                    )
                }
                digest.update(buffer, 0, read)
            }
            return MeasuredSource(total, digest.digest().toHex())
        } finally {
            if (handle == null) {
                runCatching { input.close() }
            } else {
                handle.release(input)
            }
        }
    }

    /** Length-only convenience for callers that do not need the digest. */
    fun measureDecodedBytes(
        isActive: () -> Boolean = { true },
        limitBytes: Long? = null,
        chunkBytes: Int = MEASURE_CHUNK_BYTES,
    ): Long = measureDecoded(isActive, limitBytes, chunkBytes).bytes

    private fun noteFormat(detected: ImageFormat) {
        format = detected
        if (detected == ImageFormat.GZIP || detected == ImageFormat.XZ) {
            declaredDecodedSize = null
        }
    }

    companion object {
        private const val MEASURE_CHUNK_BYTES = 1 shl 20

        fun open(uri: Uri): CountedInputStream = fromUri(uri).open()

        fun fromUri(uri: Uri): ImageSource {
            val path = uri.toString()
            val format = detectImageFormat(path)
            val declaredSize = if (format == ImageFormat.ISO || format == ImageFormat.IMG) {
                queryFileSize(uri)
            } else null
            return ImageSource(
                openStream = {
                    val raw = openRaw(uri)
                    wrap(raw, format, declaredSize)
                },
                initialFormat = format,
                declaredDecodedSize = declaredSize,
            )
        }

        /** SAF/content URIs: the only stream source Android can read without a path. */
        fun fromContentUri(uri: Uri, resolver: android.content.ContentResolver): ImageSource {
            // lastPathSegment on content:// is often an opaque doc id (msf:123), never a
            // filename. Prefer OpenableColumns.DISPLAY_NAME; magic comes from the live stream.
            val displayName = queryOpenableString(resolver, uri, android.provider.OpenableColumns.DISPLAY_NAME)
            val size = queryOpenableLong(resolver, uri, android.provider.OpenableColumns.SIZE)
            return fromContent(
                openRaw = {
                    resolver.openInputStream(uri)
                        ?: throw java.io.FileNotFoundException("cannot open $uri")
                },
                displayName = displayName,
                size = size,
            )
        }

        /**
         * Content-URI decode without Android types. Used by [fromContentUri] and host tests.
         * Format is decided on the stream that will be written: read up to 6 magic bytes
         * (tolerating short reads), reconcile with DISPLAY_NAME, prepend the prefix so no
         * payload is lost. Open/read failures propagate — never fall back to raw IMG.
         */
        internal fun fromContent(
            openRaw: () -> InputStream,
            displayName: String?,
            size: Long?,
            snapshot: Boolean = false,
        ): ImageSource {
            val named = displayName?.let(::detectImageFormat)
            val provisional = when (named) {
                ImageFormat.GZIP, ImageFormat.XZ, ImageFormat.ISO -> named
                else -> ImageFormat.IMG
            }
            // SIZE from the provider is the on-disk/compressed length. Do not publish it as
            // decodedSize until magic on the live stream confirms an uncompressed image;
            // a mislabeled .iso that is actually gzip must never early-reject on SIZE.
            lateinit var source: ImageSource
            source = ImageSource(
                openStream = {
                    val raw = openRaw()
                    try {
                        val header = ByteArray(6)
                        val n = readPrefix(raw, header)
                        val format = classifyImageContent(displayName, header.copyOf(n))
                        source.noteFormat(format)
                        val restored = prependPrefix(header, n, raw)
                        val decodedSize =
                            if (format == ImageFormat.ISO || format == ImageFormat.IMG) size else null
                        wrap(restored, format, decodedSize)
                    } catch (t: Throwable) {
                        runCatching { raw.close() }
                        throw t
                    }
                },
                initialFormat = provisional,
                // A provider SIZE is the stored length. For an uncompressed provisional
                // image that is the decoded length too; magic on open clears it if the
                // stream turns out compressed.
                declaredDecodedSize = size?.takeIf {
                    it > 0L && (provisional == ImageFormat.ISO || provisional == ImageFormat.IMG)
                },
                snapshot = snapshot,
            )
            return source
        }

        fun fromFile(file: java.io.File): ImageSource {
            val size = if (file.exists() && file.isFile) file.length() else null
            return fromContent(
                openRaw = { FileInputStream(file) },
                displayName = file.name,
                size = size,
                // App-private staged copy: its bytes are not swapped by an external provider.
                snapshot = true,
            )
        }

        /** [decodedSize] must be the exact decoded length. Pass null when unsure: a
         *  compressed stream's footer is not a reliable length (gzip ISIZE is mod 2^32 and
         *  concatenated members report only the last one), and a wrong hint can reject a
         *  valid image or end it early, so never guess it here. */
        fun fromBytes(
            bytes: ByteArray,
            format: ImageFormat = detectImageFormatBytes(bytes),
            decodedSize: Long? = null,
        ): ImageSource = ImageSource(
            openStream = { wrap(ByteArrayInputStream(bytes), format, decodedSize) },
            initialFormat = format,
            declaredDecodedSize = decodedSize,
            snapshot = true,
        )

        private fun openRaw(uri: Uri): InputStream {
            val scheme = uri.scheme?.lowercase()
            return when (scheme) {
                "http", "https" -> {
                    val conn = URL(uri.toString()).openConnection() as HttpURLConnection
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 15_000
                    conn.readTimeout = 60_000
                    conn.inputStream
                }
                "file" -> FileInputStream(uri.path!!)
                else -> FileInputStream(uri.path ?: uri.toString())
            }
        }
        private fun queryFileSize(uri: Uri): Long? {
            return runCatching {
                val scheme = uri.scheme?.lowercase()
                if (scheme == null || scheme == "file") {
                    val path = if (scheme == "file") uri.path else (uri.path ?: uri.toString())
                    if (path != null) {
                        val file = java.io.File(path)
                        if (file.exists() && file.isFile) file.length() else null
                    } else null
                } else null
            }.getOrNull()
        }

        private fun queryOpenableString(
            resolver: android.content.ContentResolver,
            uri: Uri,
            column: String,
        ): String? =
            runCatching {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getString(0) else null
                }
            }.getOrNull()

        private fun queryOpenableLong(
            resolver: android.content.ContentResolver,
            uri: Uri,
            column: String,
        ): Long? =
            runCatching {
                resolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst() && !cursor.isNull(0)) cursor.getLong(0) else null
                }
            }.getOrNull()

        /** Read up to [buffer].size bytes, tolerating short reads, stopping at EOF. */
        private fun readPrefix(input: InputStream, buffer: ByteArray): Int {
            var off = 0
            var zeroReads = 0
            while (off < buffer.size) {
                val n = input.read(buffer, off, buffer.size - off)
                if (n < 0) break
                if (n == 0) {
                    if (++zeroReads >= ZERO_READ_LIMIT) {
                        throw java.io.IOException("stream made no progress while reading prefix")
                    }
                    continue
                }
                zeroReads = 0
                off += n
            }
            return off
        }

        private fun prependPrefix(header: ByteArray, n: Int, rest: InputStream): InputStream {
            if (n <= 0) return rest
            return java.io.SequenceInputStream(ByteArrayInputStream(header, 0, n), rest)
        }




        private fun wrap(
            raw: InputStream,
            format: ImageFormat,
            decodedSize: Long? = null,
        ): CountedInputStream {
            val decoded: InputStream = when (format) {
                ImageFormat.GZIP -> GZIPInputStream(raw)
                ImageFormat.XZ -> XZCompressorInputStream(raw, true)
                ImageFormat.ISO, ImageFormat.IMG -> raw
            }
            return CountedInputStream(decoded, decodedSize)
        }
    }
}

private fun ByteArray.toHex(): String {
    val digits = "0123456789abcdef"
    val out = StringBuilder(size * 2)
    for (b in this) {
        val v = b.toInt() and 0xFF
        out.append(digits[v ushr 4]).append(digits[v and 0x0F])
    }
    return out.toString()
}
