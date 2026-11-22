package usb.diagnostics

import java.nio.ByteBuffer
import java.util.concurrent.CancellationException
import usb.device.DiskException
import usb.device.UsbDisk
import usb.image.ImageCancelledException
import usb.operation.DiskOperation
import usb.device.TransferTuning
import usb.operation.OperationState

/**
 * Drives the real SectorForge `sf-core` engine (MIT, pinned in
 * `third_party/README.md`) through [SectorForgeNative].
 *
 * The engine owns both passes: it copies healthy chunks first, parking damaged
 * ones, then binary-splits the parked chunks down to single sectors. This class
 * only adapts the app's callbacks and durable boundary:
 *
 * - reads/writes cross into [UsbDisk] / [RescueSink] through direct buffers;
 * - only a medium error is reported to the engine as a read failure, so sf-core
 *   owns the bad-sector decision; disconnect/transport/sink failures abort the run;
 * - the app map is repainted from engine snapshots at [checkpointEverySectors]
 *   cadence, and [checkpoint] runs only after the caller has synced image bytes;
 * - identity, `Partial`, and the refusal to publish COPIED for untouched sectors
 *   stay exactly as before.
 */
internal fun rescueChunkBytes(jniCapBytes: Int, sectorSize: Int): Int {
    require(jniCapBytes > 0 && sectorSize > 0)
    val cap = jniCapBytes.toLong().coerceAtLeast(sectorSize.toLong())
    return (cap - cap % sectorSize)
        .coerceAtLeast(sectorSize.toLong())
        .coerceAtMost(Int.MAX_VALUE.toLong())
        .toInt()
}

internal object SectorForgeEngine {
    /** Attempts per sector; sf-core defaults to 3, the app keeps one attempt. */
    private const val MAX_RETRIES = 1

    fun run(
        disk: UsbDisk,
        destination: RescueSink,
        map: RescueMap,
        checkpointEverySectors: ULong,
        checkpoint: (RescueMap) -> Unit,
        operation: DiskOperation,
        maxRetries: Int = MAX_RETRIES,
    ): RescueMap {
        // Argument contract first, before the native check and before any disk read.
        // Retries and a resume with known FAILED sectors revisit earlier image offsets,
        // so those need a seekable sink. A fresh archive copy passes maxRetries = 0 and
        // may use a monotonic append-only sink: a forward-only run works, and the first
        // backward write throws in asRescueSink, ending the run Partial instead of
        // shifting image bytes.
        val seekableRequired = maxRetries > 0 || map.failed.isNotEmpty()
        if (seekableRequired) {
            check(destination.positional) {
                "rescue copy needs a seekable image sink (sf-core retries damaged sectors)"
            }
        }
        check(SectorForgeNative.available) {
            "SectorForge native engine is not compiled into this build; refusing to fake a rescue"
        }
        val sectorSize = disk.geometry.sectorSize
        val sizeBytes = disk.geometry.capacityBytes
        require(sizeBytes <= Long.MAX_VALUE.toULong()) { "disk capacity exceeds the signed offset range" }
        val seeds = SectorForgeBlocks.seeds(map)
        val inheritedFailed = map.failed
        val writerUncertainWrites = ArrayList<ULongRange>()
        val reader = Reader(disk, sectorSize)
        val writer = Writer(destination, sectorSize, writerUncertainWrites)
        val progress =
            Progress(
                map,
                operation,
                sectorSize,
                inheritedFailed,
                checkpoint,
                destination,
                sizeBytes.toLong(),
                maxRetries,
                writerUncertainWrites,
            )
        val chunk = rescueChunkBytes(TransferTuning.jniCapBytes, sectorSize)
        val checkpointBytes =
            (checkpointEverySectors * sectorSize.toULong()).toLong().coerceAtLeast(1L)
        var failure: Throwable? = null
        var handle = 0L
        try {
            prepareResume(map, inheritedFailed, destination, sizeBytes.toLong(), checkpoint)
            handle =
                SectorForgeNative.nativeStart(
                    reader,
                    writer,
                    progress,
                    seeds,
                    sizeBytes.toLong(),
                    sectorSize,
                    chunk,
                    chunk,
                    maxRetries,
                    false,
                    checkpointBytes,
                )
            if (handle == 0L) throw IllegalStateException("SectorForge engine refused the rescue run")
            when (val status = SectorForgeNative.nativeExecute(handle)) {
                SectorForgeNative.STATUS_OK -> failure = progress.failure ?: reader.failure ?: writer.failure
                SectorForgeNative.STATUS_CANCELLED ->
                    failure = progress.failure ?: reader.failure ?: writer.failure ?: ImageCancelledException("rescue copy cancelled")
                SectorForgeNative.STATUS_FATAL ->
                    failure = progress.failure ?: reader.failure ?: writer.failure
                        ?: IllegalStateException("SectorForge engine aborted the rescue")
                else -> failure = IllegalStateException("SectorForge engine lost the run (status $status)")
            }
        } catch (e: Throwable) {
            failure = e
            throw e
        } finally {
            fun record(cause: Throwable) {
                val previous = failure
                if (previous == null) failure = cause
                else if (previous !== cause) previous.addSuppressed(cause)
            }
            if (handle != 0L) {
                try {
                    val snapshot = SectorForgeNative.blocks(handle)
                    SectorForgeBlocks.apply(
                        map,
                        snapshot,
                        sectorSize,
                        inheritedFailed,
                        writerUncertainWrites,
                    )
                } catch (cause: Throwable) {
                    record(cause)
                } finally {
                    try { SectorForgeNative.nativeFree(handle) } catch (cause: Throwable) { record(cause) }
                }
            }
            var imageReady = false
            try {
                destination.ensureLength(sizeBytes.toLong())
                imageReady = true
            } catch (cause: Throwable) {
                record(cause)
            }
            if (imageReady) {
                try {
                    checkpoint(map.snapshot())
                } catch (cause: Throwable) {
                    record(cause)
                }
            }
            if (operation.state == OperationState.Queued || operation.state == OperationState.Running) {
                val cause = failure
                when {
                    cause is CancellationException || !operation.isActive() -> {
                        operation.state = OperationState.Partial
                        operation.failureCause = Rescue.mergeAbort(
                            operation.failureCause,
                            cause ?: ImageCancelledException("rescue copy cancelled"),
                        )
                    }
                    cause != null -> {
                        operation.state = OperationState.Partial
                        operation.failureCause = Rescue.mergeAbort(operation.failureCause, cause)
                    }
                    map.failed.isNotEmpty() || map.partial -> operation.state = OperationState.Partial
                    else -> {
                        // Durable commit (sync/close/checkpoint) is owned by RescueBackupOperations.
                        operation.state = OperationState.Completed
                    }
                }
            }
        }
        val cause = failure
        if (cause != null && cause !is CancellationException) throw cause
        return map
    }

    internal fun prepareResume(
        map: RescueMap,
        inheritedFailed: List<ULongRange>,
        destination: RescueSink,
        sizeBytes: Long,
        checkpoint: (RescueMap) -> Unit,
    ) {
        if (inheritedFailed.isEmpty()) return
        // Publish NON_TRIED before a retry can partially overwrite old FAILED bytes.
        map.demoteFailedToNonTried()
        destination.ensureLength(sizeBytes)
        checkpoint(map.snapshot())
    }

    /** `UsbDisk` for the engine; a medium error is the only "bad sector" signal. */
    internal class Reader(private val disk: UsbDisk, private val sectorSize: Int) {
        @Volatile var failure: Throwable? = null

        fun readAt(offset: Long, buffer: ByteBuffer): Int {
            require(offset % sectorSize == 0L) { "unaligned read at $offset" }
            return try {
                disk.read((offset / sectorSize).toULong(), buffer)
                0
            } catch (e: DiskException.SenseError) {
                if (e.isMediumError) {
                    SectorForgeNative.READ_MEDIUM
                } else {
                    failure = e
                    SectorForgeNative.STATUS_FATAL
                }
            } catch (e: CancellationException) {
                // Cancellation must not be reported as a device fault.
                failure = e
                SectorForgeNative.READ_CANCELLED
            } catch (e: Exception) {
                failure = e
                SectorForgeNative.STATUS_FATAL
            }
        }
    }

    /** Seekable image sink for the engine; the image stays dense and positional. */
    internal class Writer(
        private val destination: RescueSink,
        private val sectorSize: Int,
        private val uncertainWrites: MutableList<ULongRange>,
    ) {
        @Volatile var failure: Throwable? = null
        private var scratch = ByteArray(0)

        fun writeAt(offset: Long, buffer: ByteBuffer): Int {
            require(offset % sectorSize == 0L) { "unaligned write at $offset" }
            val length = buffer.remaining()
            val range = (offset / sectorSize).toULong() until ((offset + length) / sectorSize).toULong()
            return try {
                if (scratch.size < length) scratch = ByteArray(length)
                buffer.get(scratch, 0, length)
                destination.writeBatch(range.first, scratch, length, sectorSize)
                0
            } catch (e: Exception) {
                uncertainWrites += range
                failure = e
                SectorForgeNative.STATUS_FATAL
            }
        }
    }

    /** Repaints the app map and publishes it at the engine's snapshot cadence. */
    internal class Progress(
        private val map: RescueMap,
        private val operation: DiskOperation,
        private val sectorSize: Int,
        private val inheritedFailed: List<ULongRange>,
        private val checkpoint: (RescueMap) -> Unit,
        private val destination: RescueSink,
        private val imageBytes: Long,
        private val maxRetries: Int = 1,
        private val uncertainWrites: List<ULongRange> = emptyList(),
    ) {
        private var highestReported = 0L
        var failure: Throwable? = null
            private set

        fun onProgress(
            finished: Long,
            bad: Long,
            pending: Long,
            position: Long,
            blocks: LongArray?,
        ): Boolean {
            return try {
                if (!operation.isActive()) return false
                if (position > highestReported) {
                    highestReported = position
                    operation.report(position)
                }
                if (blocks != null && blocks.isNotEmpty()) {
                    SectorForgeBlocks.apply(
                        map,
                        blocks,
                        sectorSize,
                        inheritedFailed,
                        uncertainWrites,
                    )
                    destination.ensureLength(imageBytes)
                    checkpoint(map.snapshot())
                }
                true
            } catch (cause: Throwable) {
                failure = cause
                false
            }
        }
    }
}
