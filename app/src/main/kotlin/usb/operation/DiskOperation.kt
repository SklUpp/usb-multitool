package usb.operation

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CompletableDeferred

sealed class OperationState {
    data object Queued : OperationState()
    data object Running : OperationState()
    data object Completed : OperationState()
    data object Cancelled : OperationState()
    data object Partial : OperationState()
    data class Failed(val cause: Throwable) : OperationState()
}

/** Consistent ticker sample across counters and raw-write phase. */
data class DiskOperationProgressSnapshot(
    val bytesDone: Long,
    val bytesTotal: Long,
    val bytesPerSecond: Long,
    val phase: DiskOperationPhase?,
)

/** Streaming / flush / readback phase published during a raw write. */
enum class DiskOperationPhase {
    Write,
    Sync,
    Verify,
}


open class DiskOperation(
    open val name: String = "",
    /**
     * Whether this operation needs the foreground service and its wake lock. Metadata
     * reads (USB probe, partition refresh) are short and run with the screen the user
     * just opened, so they must not raise a second system notification on attach.
     */
    open val foregroundService: Boolean = true,
    @Volatile var bytesDone: Long = 0L,
    @Volatile var bytesTotal: Long = -1L,
    @Volatile var state: OperationState = OperationState.Queued,
    @Volatile var failureCause: Throwable? = null,
    @Volatile var mutationAttempted: Boolean = false,
    /** Non-null while a raw write is in Write/Sync/Verify; cleared after readback. */
    @Volatile var phase: DiskOperationPhase? = null,
    /** Set once cancellation can no longer undo the operation's committed result. */
    @Volatile var commitReached: Boolean = false,
    private val active: () -> Boolean = { true },
) {
    private val explicitActive = AtomicBoolean(true)
    private val inactive = CompletableDeferred<Unit>()
    private val inactiveListeners = java.util.concurrent.CopyOnWriteArrayList<() -> Unit>()

    fun isActive(): Boolean = explicitActive.get() && active()

    /** Register a best-effort cleanup (e.g. close an in-flight source stream) on cancel. */
    fun onInactive(listener: () -> Unit) {
        inactiveListeners.add(listener)
        if (!isActive()) runCatching(listener)
    }

    fun removeInactiveListener(listener: () -> Unit) {
        inactiveListeners.remove(listener)
    }

    fun report(bytes: Long) {
        report(bytes, System.nanoTime())
    }

    /**
     * Monotonic throughput sample hook. [nowNanos] injected for deterministic tests;
     * production passes [System.nanoTime]. Throughput stays 0 until two samples give
     * a positive elapsed window, and clamps to 0 on stall/backward/repeated progress.
     * A blocked engine issues no `report` calls; publishers must read speed through
     * [currentBytesPerSecond] so a stalled sample expires to 0 instead of replaying.
     */
    @Volatile var bytesPerSecond: Long = 0L
        private set
    private var sampleBytes: Long = 0L
    private var sampleNanos: Long = 0L
    private var hasSample: Boolean = false

    companion object {
        /** Stall window after the last sample after which published speed expires. */
        const val THROUGHPUT_STALE_NANOS: Long = 3_000_000_000L
    }

    /** Published speed; 0 when no sample or the last sample is older than the window. */
    @Synchronized
    fun currentBytesPerSecond(nowNanos: Long = System.nanoTime()): Long {
        if (!hasSample) return 0L
        val elapsed = nowNanos - sampleNanos
        if (elapsed < 0L || elapsed > THROUGHPUT_STALE_NANOS) return 0L
        return bytesPerSecond
    }

    @Synchronized
    fun report(bytes: Long, nowNanos: Long) {
        if (!hasSample) {
            sampleBytes = bytes
            sampleNanos = nowNanos
            hasSample = true
        } else {
            val deltaBytes = bytes - sampleBytes
            val deltaNanos = nowNanos - sampleNanos
            if (deltaBytes > 0L && deltaNanos > 0L) {
                val rate = deltaBytes.toDouble() * 1_000_000_000.0 / deltaNanos.toDouble()
                if (rate.isFinite()) bytesPerSecond = rate.toLong().coerceAtLeast(0L)
            } else if (deltaBytes <= 0L || deltaNanos <= 0L) {
                bytesPerSecond = 0L
            }
            sampleBytes = bytes
            sampleNanos = nowNanos
        }
        bytesDone = bytes
    }

    suspend fun awaitInactive() {
        inactive.await()
    }

    /**
     * Start progress for the next file in a batch (or a single transfer).
     * Resets [bytesDone] before publishing [bytesTotal] so the UI never shows a
     * false aggregate (e.g. previous file's done over the next file's smaller total).
     * Also resets the throughput window, so the next speed comes from the new
     * file's own samples. Pass [total] &lt; 0 when size is unknown
     * (indeterminate / bytes-only).
     */
    @Synchronized
    /** Human-readable name of the file a batch is currently processing. */
    @Volatile
    var currentFile: String? = null
        private set

    fun beginCurrentFile(total: Long, name: String? = null) {
        currentFile = name
        bytesDone = 0L
        bytesTotal = total
        bytesPerSecond = 0L
        sampleBytes = 0L
        sampleNanos = 0L
        hasSample = false
    }

    /**
     * Reset counters then publish [phase] under the same lock so a ticker sample cannot
     * pair Write's 100% bytes with Sync/Verify.
     */
    @Synchronized
    fun beginPhase(phase: DiskOperationPhase, total: Long) {
        beginCurrentFile(total)
        this.phase = phase
    }

    /** Read progress fields under one lock so phase and counters cannot tear. */
    @Synchronized
    fun progressSnapshot(): DiskOperationProgressSnapshot =
        DiskOperationProgressSnapshot(bytesDone, bytesTotal, currentBytesPerSecond(), phase)

    internal fun markInactive() {
        if (explicitActive.getAndSet(false)) {
            inactive.complete(Unit)
            inactiveListeners.forEach { runCatching(it) }
        }
    }

    open suspend fun run() {}
}
