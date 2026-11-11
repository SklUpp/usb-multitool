package usb.diagnostics

import java.io.OutputStream
import java.nio.ByteBuffer
import usb.device.DiskException
import usb.device.UsbDisk
import usb.image.CountedInputStream
import usb.image.ImageCancelledException
import usb.image.ImageSource
import usb.image.ImageVerifyException
import usb.operation.DiskOperation
import usb.operation.OperationState

data class BenchmarkReport(
    val bytesWritten: Long,
    val elapsedMs: Long,
    val startLba: ULong,
    val sectors: ULong,
)

class UnsafeBenchmarkTargetException(message: String) : Exception(message)

object Diagnostics {
    /** One SCSI-sized batch per iteration; per-sector reads only after a medium error. */
    internal const val SCAN_CHUNK_BYTES = 1024 * 1024

    /**
     * Read-only surface scan. Reads [SCAN_CHUNK_BYTES] at a time and falls back to
     * sector-by-sector inside a chunk only when the device reports a medium error.
     * Disconnect, closed session and every other non-medium error abort the scan
     * instead of painting the rest of the disk as unreadable.
     *
     * Average read speed uses only successful read bytes over monotonic elapsed time
     * for this scan window. Failed sectors and cancellation shrink the numerator
     * honestly; no extra I/O is issued for timing.
     *
     * @param monotonicNanos injectable monotonic clock (defaults to [System.nanoTime])
     */
    fun readScan(
        disk: UsbDisk,
        operation: DiskOperation = DiskOperation("read-scan"),
        monotonicNanos: () -> Long = System::nanoTime,
    ): ScanReport {
        val sectorSize = disk.geometry.sectorSize
        val total = disk.geometry.sectorCount
        require(disk.geometry.capacityBytes <= Long.MAX_VALUE.toULong()) { "Disk capacity exceeds supported progress range" }
        operation.bytesTotal = disk.geometry.capacityBytes.toLong()
        val chunkSectors = maxOf(1, SCAN_CHUNK_BYTES / sectorSize).toULong()
        val buf = ByteBuffer.allocate(chunkSectors.toInt() * sectorSize)
        val failed = mutableListOf<ULongRange>()
        fun markFailed(lba: ULong) {
            val last = failed.lastOrNull()
            if (last != null && last.last + 1UL == lba) failed[failed.lastIndex] = last.first..lba
            else failed += lba..lba
        }
        var successfulBytes = 0L
        val started = monotonicNanos()
        var lba = 0UL
        while (lba < total) {
            if (!operation.isActive()) break
            val n = minOf(chunkSectors, total - lba).toInt()
            buf.clear()
            buf.limit(n * sectorSize)
            try {
                disk.read(lba, buf) // never writes
                successfulBytes += n.toLong() * sectorSize.toLong()
            } catch (e: DiskException.SenseError) {
                if (!e.isMediumError) throw e
                for (i in 0 until n) {
                    if (!operation.isActive()) {
                        val scanned = lba + i.toULong()
                        operation.report((scanned * sectorSize.toULong()).toLong())
                        val elapsed = (monotonicNanos() - started).coerceAtLeast(0L)
                        return ScanReport(
                            sectorsRead = scanned,
                            failed = failed,
                            successfulBytes = successfulBytes,
                            elapsedNanos = elapsed,
                        )
                    }
                    buf.clear()
                    buf.limit(sectorSize)
                    try {
                        disk.read(lba + i.toULong(), buf)
                        successfulBytes += sectorSize.toLong()
                    } catch (inner: DiskException.SenseError) {
                        if (!inner.isMediumError) throw inner
                        markFailed(lba + i.toULong())
                    }
                }
            }
            lba += n.toULong()
            operation.report((lba * sectorSize.toULong()).toLong())
        }
        val elapsed = (monotonicNanos() - started).coerceAtLeast(0L)
        return ScanReport(
            sectorsRead = lba,
            failed = failed,
            successfulBytes = successfulBytes,
            elapsedNanos = elapsed,
        )
    }

    /**
     * Write-speed probe over an explicit target range that must sit entirely
     * outside partition metadata (LBA 0..[PARTITION_METADATA_SECTORS]-1).
     * Confirmation mismatch throws before any write.
     */
    fun writeBenchmark(
        disk: UsbDisk,
        identity: String,
        confirmation: DestructiveConfirmation,
        startLba: ULong,
        sectors: ULong,
        operation: DiskOperation = DiskOperation("write-bench"),
    ): BenchmarkReport {
        confirmation.requireMatch(identity, disk)
        require(sectors > 0UL) { "benchmark sector count must be > 0" }
        if (startLba < PARTITION_METADATA_SECTORS) {
            throw UnsafeBenchmarkTargetException(
                "benchmark startLba=$startLba overlaps partition metadata 0..${PARTITION_METADATA_SECTORS - 1UL}",
            )
        }
        require(startLba < disk.geometry.sectorCount) { "startLba out of range" }
        require(startLba + sectors <= disk.geometry.sectorCount) {
            "benchmark range past end of disk"
        }
        val buf = ByteBuffer.allocate(disk.geometry.sectorSize)
        val start = System.nanoTime()
        var written = 0L
        var lba = startLba
        val end = startLba + sectors
        while (lba < end) {
            if (!operation.isActive()) break
            buf.clear()
            disk.write(lba, buf)
            written += disk.geometry.sectorSize.toLong()
            lba++
            operation.report(written)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        return BenchmarkReport(
            bytesWritten = written,
            elapsedMs = elapsed.coerceAtLeast(0L),
            startLba = startLba,
            sectors = lba - startLba,
        )
    }

    fun wipe(
        disk: UsbDisk,
        identity: String,
        confirmation: DestructiveConfirmation,
        operation: DiskOperation = DiskOperation("wipe"),
    ) {
        confirmation.requireMatch(identity, disk)
        val zeros = ByteBuffer.allocate(disk.geometry.sectorSize)
        var lba = 0UL
        try {
            while (lba < disk.geometry.sectorCount) {
                if (!operation.isActive()) {
                    if (operation.mutationAttempted) operation.state = OperationState.Partial
                    break
                }
                zeros.clear()
                operation.mutationAttempted = true
                disk.write(lba, zeros)
                lba++
                operation.report((lba * disk.geometry.sectorSize.toULong()).toLong())
            }
            // Coordinator performs single disk.sync after mutation.
        } catch (failure: Throwable) {
            if (operation.mutationAttempted && operation.state != OperationState.Partial) {
                operation.state = OperationState.Partial
                operation.failureCause = failure
            }
            throw failure
        }
    }
}

/**
 * Sector-oriented sink for rescue copies; never buffers a full disk.
 * The rescue image is dense and positional: the sector for [lba] lives at
 * offset `lba * sectorSize`, so a failed sector leaves a zero-filled gap and
 * [Rescue.restore] can walk the image LBA by LBA without shifting data.
 */
fun interface RescueSink {
    fun writeSector(lba: ULong, sector: ByteArray)

    fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
        for (offset in 0 until length step sectorSize) {
            writeSector(lba + (offset / sectorSize).toULong(), bytes.copyOfRange(offset, offset + sectorSize))
        }
    }
}

/**
 * Adapt an appending [OutputStream] whose first byte is LBA 0.
 * A skipped LBA would shift every later sector, so a gap is rejected instead.
 */
fun OutputStream.asRescueSink(sectorSize: Int): RescueSink {
    var next = 0UL
    return object : RescueSink {
        override fun writeSector(lba: ULong, sector: ByteArray) = writeBatch(lba, sector, sector.size, sectorSize)
        override fun writeBatch(lba: ULong, bytes: ByteArray, length: Int, sectorSize: Int) {
            require(length > 0 && length <= bytes.size && length % sectorSize == 0)
            check(lba == next) { "rescue image is positional: expected LBA $next, got $lba" }
            write(bytes, 0, length)
            next += (length / sectorSize).toULong()
        }
    }
}

object Rescue {
    /**
     * Disk → dense positional image. Only [DiskException.SenseError.isMediumError]
     * marks a sector FAILED (zero-filled gap). Disconnect / transport / sink errors
     * abort immediately without marking FAILED. [checkpoint] runs only after the
     * caller has synced image data for the painted sectors (see MainActivity).
     */
    fun copy(
        disk: UsbDisk,
        destination: RescueSink,
        map: RescueMap = RescueMap.forCopy(disk.geometry.sectorCount),
        checkpointEverySectors: ULong = 4096UL,
        checkpoint: (RescueMap) -> Unit = {},
        operation: DiskOperation = DiskOperation("rescue-copy"),
    ): RescueMap {
        require(map.kind == MapKind.COPY) { "rescue copy needs a COPY map" }
        require(map.sectorCount == disk.geometry.sectorCount) {
            "COPY map sectorCount ${map.sectorCount} != disk ${disk.geometry.sectorCount}"
        }
        val sectorSize = disk.geometry.sectorSize
        if (map.sectorSize != null) {
            require(map.sectorSize == sectorSize) {
                "COPY map sectorSize ${map.sectorSize} != disk $sectorSize"
            }
        }
        val chunkSectors = maxOf(1, Diagnostics.SCAN_CHUNK_BYTES / sectorSize)
        val buf = ByteBuffer.allocate(chunkSectors * sectorSize)
        val zeros = ByteArray(sectorSize)
        var sinceCheckpoint = 0UL
        var aborted: Throwable? = null
        try {
            for (range in map.nonTried + map.failed) {
                var lba = range.first
                var fallbackUntil = 0UL
                while (lba <= range.last) {
                    if (!operation.isActive()) {
                        aborted = ImageCancelledException("rescue copy cancelled")
                        return map
                    }
                    try {
                        val count = minOf(chunkSectors.toULong(), range.last - lba + 1UL).toInt()
                        buf.clear()
                        buf.limit(count * sectorSize)
                        val batchOk = if (lba < fallbackUntil) {
                            false
                        } else try {
                            disk.read(lba, buf)
                            true
                        } catch (e: DiskException.SenseError) {
                            if (!e.isMediumError) throw e
                            fallbackUntil = lba + count.toULong()
                            false
                        }
                        if (batchOk) {
                            destination.writeBatch(lba, buf.array(), count * sectorSize, sectorSize)
                            map.markCopied(lba, count.toULong())
                            lba += count.toULong()
                            sinceCheckpoint += count.toULong()
                        } else {
                            buf.clear()
                            buf.limit(sectorSize)
                            val sectorOk = try {
                                disk.read(lba, buf)
                                true
                            } catch (e: DiskException.SenseError) {
                                if (!e.isMediumError) throw e
                                false
                            }
                            if (sectorOk) {
                                destination.writeBatch(lba, buf.array(), sectorSize, sectorSize)
                                map.markCopied(lba, 1UL)
                            } else {
                                destination.writeSector(lba, zeros)
                                map.markFailed(lba, 1UL)
                            }
                            lba++
                            sinceCheckpoint++
                        }
                    } catch (e: Exception) {
                        aborted = e
                        throw e
                    }
                    operation.report((lba * sectorSize.toULong()).toLong())
                    if (sinceCheckpoint >= checkpointEverySectors) {
                        checkpoint(map.snapshot())
                        sinceCheckpoint = 0UL
                    }
                }
            }
        } finally {
            // Final checkpoint even on cancel/abort so Partial retains a usable map.
            try {
                checkpoint(map.snapshot())
            } catch (cp: Exception) {
                if (aborted != null) aborted.addSuppressed(cp)
                else if (operation.isActive()) throw cp
                else operation.failureCause = mergeAbort(operation.failureCause, cp)
            }
            if (operation.state == OperationState.Queued || operation.state == OperationState.Running) {
                when {
                    aborted is ImageCancelledException || !operation.isActive() -> {
                        operation.state = OperationState.Partial
                        operation.failureCause =
                            mergeAbort(operation.failureCause, aborted ?: ImageCancelledException("rescue copy cancelled"))
                    }
                    aborted != null -> {
                        operation.state = OperationState.Partial
                        operation.failureCause = mergeAbort(operation.failureCause, aborted)
                    }
                    map.failed.isNotEmpty() || map.partial -> operation.state = OperationState.Partial
                    else -> {
                        // Durable commit (sync/close/checkpoint) is owned by RescueBackupOperations.
                        operation.state = OperationState.Completed
                    }
                }
            }
        }
        return map
    }

    /**
     * Image → disk. Requires a [MapKind.RESTORE] map (never a COPY map). Confirmation,
     * identity and size checks run before the first write.
     * A COPIED range is skipped only after its disk content has been read back and
     * compared with the image bytes in the same pass; whatever the selected image
     * currently serves is compared, and ranges that differ are rewritten.
     * After sync the whole restored region is read back and compared with a fresh full
     * read of the image: Completed therefore means the disk matched that fresh read byte
     * for byte. This is not a snapshot-consistency proof — a source modified during the
     * operation is not guaranteed to be detected, and the source must be kept unchanged
     * for the whole restore.
     * Medium write errors → FAILED sectors; disconnect/transport → abort Partial.
     * The first write attempt is recorded before `disk.write` runs, so a command that
     * lands bytes and then fails is reported Partial instead of Failed.
     */
    fun restore(
        source: ImageSource,
        disk: UsbDisk,
        map: RescueMap,
        identity: String,
        confirmation: DestructiveConfirmation,
        checkpointEverySectors: ULong = 4096UL,
        checkpoint: (RescueMap) -> Unit = {},
        operation: DiskOperation = DiskOperation("rescue-restore"),
    ): RescueMap {
        require(map.kind == MapKind.RESTORE) {
            "rescue restore needs a RESTORE map (got ${map.kind}); refuse COPY map to avoid skipping good sectors"
        }
        confirmation.requireMatch(identity, disk)
        require(map.sectorCount <= disk.geometry.sectorCount) {
            "RESTORE map covers ${map.sectorCount} sectors but disk has only ${disk.geometry.sectorCount}"
        }
        val sectorSize = disk.geometry.sectorSize
        if (map.sectorSize != null) {
            require(map.sectorSize == sectorSize) {
                "RESTORE map sectorSize ${map.sectorSize} != disk $sectorSize"
            }
        }
        val expectedBytes = map.sectorCount.toLong() * sectorSize.toLong()
        require(expectedBytes / sectorSize.toLong() == map.sectorCount.toLong()) {
            "sectorCount*sectorSize overflows Long"
        }
        val declared = source.declaredDecodedSize
        if (declared != null) {
            require(declared >= expectedBytes) {
                "rescue image declares $declared bytes; need at least $expectedBytes for this map"
            }
        }

        // Resume only for the same destination. Without a proven disk identity, matching
        // geometry and the checkpoint's own geometry the map may describe another stick,
        // so inherited COPIED runs are discarded. Content is still verified per range.
        if (map.copied.isNotEmpty() &&
            (map.identity == null || map.identity != identity || map.sectorSize != sectorSize)
        ) {
            map.resetProgress()
        }
        var sinceCheckpoint = 0UL
        var aborted: Throwable? = null
        var wroteAny = false
        var coveredSectors = 0UL
        try {
            source.open().use { input ->
                val chunkSectors =
                    minOf(
                        maxOf(1, Diagnostics.SCAN_CHUNK_BYTES / sectorSize).toULong(),
                        map.sectorCount,
                    )
                val chunk = ByteArray((chunkSectors * sectorSize.toULong()).toInt())
                // Every sector is visited in one dense pass. COPIED runs are read back
                // from the disk and compared with the image bytes before they may be
                // skipped, so a stale or differently-sourced checkpoint rewrites what no
                // longer matches instead of being trusted. NON_TRIED/FAILED runs are
                // written; each run becomes one multi-sector command, not per sector.
                // ponytail: adjacent status runs stay separate commands; merge if a
                // profile shows status-boundary chatter.
                val schedule = rescueSchedule(map)
                val diskSectors by lazy { ByteArray(chunk.size) }
                var scheduleIndex = 0
                var lba = 0UL
                batches@ while (lba < map.sectorCount) {
                    val batchSectors = minOf(chunkSectors, map.sectorCount - lba).toInt()
                    val wantBytes = batchSectors * sectorSize
                    val have = readFully(input, chunk, wantBytes)
                    val full = have / sectorSize
                    var i = 0
                    while (i < full) {
                        if (!operation.isActive()) {
                            aborted = ImageCancelledException("rescue restore cancelled")
                            break@batches
                        }
                        val cur = lba + i.toULong()
                        while (scheduleIndex < schedule.size &&
                            schedule[scheduleIndex].first.last < cur
                        ) {
                            scheduleIndex++
                        }
                        val entry = schedule.getOrNull(scheduleIndex)
                        if (entry == null || entry.first.first > cur) {
                            // The map must cover every sector; never skip an unclassified one.
                            aborted =
                                IllegalStateException(
                                    "rescue map has no status for LBA $cur; refusing to skip unverified sectors",
                                )
                            break@batches
                        }
                        val run =
                            minOf((full - i).toULong(), entry.first.last - cur + 1UL).toInt()
                        val offset = i * sectorSize
                        val runBytes = run * sectorSize
                        if (entry.second == RescueStatus.COPIED) {
                            var matches = false
                            try {
                                matches =
                                    diskRegionMatches(
                                        disk,
                                        diskSectors,
                                        chunk,
                                        offset,
                                        cur,
                                        runBytes,
                                    )
                            } catch (e: DiskException.SenseError) {
                                if (!e.isMediumError) {
                                    aborted = e
                                    break@batches
                                }
                                // Unreadable sector: the checkpoint value cannot be
                                // trusted, so fall through and rewrite it.
                            } catch (e: Exception) {
                                aborted = e
                                break@batches
                            }
                            if (matches) {
                                i += run
                                continue
                            }
                        }
                        // Attempt is recorded before the command, not after its return:
                        // UsbDisk.write splits one call into several SCSI commands, so a
                        // disconnect can land bytes and still throw. Such a partial
                        // low-level write must report Partial, never a clean Failed.
                        if (!wroteAny) {
                            wroteAny = true
                            operation.mutationAttempted = true
                        }
                        try {
                            disk.write(cur, ByteBuffer.wrap(chunk, offset, run * sectorSize))
                            map.markCopied(cur, run.toULong())
                        } catch (e: DiskException.SenseError) {
                            if (!e.isMediumError) {
                                aborted = e
                                break@batches
                            }
                            // A multi-sector command cannot attribute the medium error:
                            // retry sector by sector so only the culprit becomes FAILED.
                            if (run == 1) {
                                map.markFailed(cur, 1UL)
                            } else {
                                var j = 0
                                while (j < run) {
                                    if (!operation.isActive()) {
                                        aborted =
                                            ImageCancelledException("rescue restore cancelled")
                                        break@batches
                                    }
                                    val sub = cur + j.toULong()
                                    try {
                                        disk.write(
                                            sub,
                                            ByteBuffer.wrap(
                                                chunk,
                                                offset + j * sectorSize,
                                                sectorSize,
                                            ),
                                        )
                                        map.markCopied(sub, 1UL)
                                    } catch (inner: DiskException.SenseError) {
                                        if (!inner.isMediumError) {
                                            aborted = inner
                                            break@batches
                                        }
                                        map.markFailed(sub, 1UL)
                                    } catch (inner: DiskException) {
                                        aborted = inner
                                        break@batches
                                    } catch (inner: Exception) {
                                        aborted = inner
                                        break@batches
                                    }
                                    j++
                                }
                            }
                        } catch (e: DiskException) {
                            aborted = e
                            break@batches
                        } catch (e: Exception) {
                            aborted = e
                            break@batches
                        }
                        i += run
                    }
                    lba += i.toULong()
                    sinceCheckpoint += i.toULong()
                    operation.report((lba * sectorSize.toULong()).toLong())
                    if (have < wantBytes) {
                        val rem = have % sectorSize
                        aborted =
                            if (rem == 0) {
                                IllegalStateException(
                                    "rescue image ended at LBA $lba; map expects ${map.sectorCount} sectors",
                                )
                            } else {
                                IllegalStateException(
                                    "rescue image truncated mid-sector at LBA $lba ($rem of $sectorSize bytes)",
                                )
                            }
                        break
                    }
                    if (sinceCheckpoint >= checkpointEverySectors) {
                        try {
                            disk.sync()
                        } catch (syncErr: Exception) {
                            aborted = syncErr
                            break
                        }
                        checkpoint(map.snapshot())
                        sinceCheckpoint = 0UL
                    }
                }
                coveredSectors = lba
            }
        } catch (e: Exception) {
            // Anything escaping the loop is still an aborted run: never publish Completed.
            aborted = mergeAbort(aborted, e)
            throw e
        } finally {
            var syncOk = !wroteAny
            try {
                if (wroteAny) {
                    disk.sync()
                    syncOk = true
                }
            } catch (syncErr: Exception) {
                aborted = mergeAbort(aborted, syncErr)
                syncOk = false
            }
            // Compare the settled disk with a fresh read of the image (byte equality,
            // the RawWriter standard). This does not prove the source stayed immutable;
            // a concurrent modification is not guaranteed to be detected.
            if (syncOk &&
                aborted == null &&
                coveredSectors >= map.sectorCount &&
                map.failed.isEmpty() &&
                operation.isActive()
            ) {
                try {
                    verifyRestored(source, disk, expectedBytes, sectorSize, operation)
                } catch (e: Exception) {
                    aborted = mergeAbort(aborted, e)
                }
            }
            // Data-before-map: never publish COPIED past an unconfirmed sync.
            if (syncOk) {
                try {
                    checkpoint(map.snapshot())
                } catch (cp: Exception) {
                    aborted = mergeAbort(aborted, cp)
                }
            }
            if (operation.state == OperationState.Queued || operation.state == OperationState.Running) {
                when {
                    aborted is ImageCancelledException ||
                        (aborted == null && !operation.isActive()) -> {
                        operation.state = OperationState.Partial
                        operation.failureCause =
                            mergeAbort(
                                operation.failureCause,
                                aborted ?: ImageCancelledException("rescue restore cancelled"),
                            )
                    }
                    aborted != null -> {
                        // Writes may already have landed — Partial, never silent Failed-as-clean.
                        operation.state =
                            if (wroteAny) OperationState.Partial
                            else OperationState.Failed(aborted)
                        operation.failureCause = mergeAbort(operation.failureCause, aborted)
                    }
                    map.failed.isNotEmpty() || map.partial -> operation.state = OperationState.Partial
                    else -> {
                        operation.commitReached = true
                        operation.state = OperationState.Completed
                    }
                }
            }
        }
        if (aborted != null && operation.state is OperationState.Failed) {
            throw aborted
        }
        return map
    }

    /**
     * Sorted status schedule covering every sector of [map] exactly once.
     */
    private fun rescueSchedule(map: RescueMap): List<Pair<ULongRange, RescueStatus>> {
        val schedule = ArrayList<Pair<ULongRange, RescueStatus>>(3)
        for (r in map.copied) schedule += r to RescueStatus.COPIED
        for (r in map.failed) schedule += r to RescueStatus.FAILED
        for (r in map.nonTried) schedule += r to RescueStatus.NON_TRIED
        schedule.sortBy { it.first.first }
        return schedule
    }

    /**
     * Re-open the image and compare the whole restored region against the disk after
     * sync, mirroring [usb.image.RawWriter]'s verify pass. This proves byte equality
     * between the settled disk and one fresh read of the source; it does not prove
     * snapshot consistency across the operation, and a concurrent source modification
     * is not guaranteed to be detected. The source must not change during a restore.
     * Read/cancel failures propagate.
     */
    private fun verifyRestored(
        source: ImageSource,
        disk: UsbDisk,
        expectedBytes: Long,
        sectorSize: Int,
        operation: DiskOperation,
    ) {
        val chunkBytes = Diagnostics.SCAN_CHUNK_BYTES
        require(chunkBytes % sectorSize == 0) {
            "SCAN_CHUNK_BYTES must be a multiple of sectorSize"
        }
        val srcChunk = ByteArray(chunkBytes)
        val diskChunk = ByteArray(chunkBytes)
        source.open().use { input ->
            var offset = 0L
            var lba = 0UL
            while (offset < expectedBytes) {
                if (!operation.isActive()) {
                    throw ImageCancelledException("rescue verification cancelled after sync")
                }
                val want = minOf(chunkBytes.toLong(), expectedBytes - offset).toInt()
                val n = readFully(input, srcChunk, want)
                if (n < want) {
                    throw ImageVerifyException(
                        "restore verify source short at byte ${offset + n}: got $n of $want",
                    )
                }
                disk.read(lba, ByteBuffer.wrap(diskChunk, 0, want))
                var k = 0
                while (k < want) {
                    if (srcChunk[k] != diskChunk[k]) {
                        val abs = offset + k
                        throw ImageVerifyException(
                            "restore verification mismatch at byte $abs (LBA ${abs / sectorSize})",
                        )
                    }
                    k++
                }
                offset += want
                lba += (want / sectorSize).toULong()
            }
        }
    }

    /**
     * Read back [bytes] at [lba] and compare them with the image bytes at
     * [expectedOffset]. A checkpoint's COPIED range is skipped only on an exact match;
     * read failures propagate so the caller fails closed instead of trusting the map.
     */
    private fun diskRegionMatches(
        disk: UsbDisk,
        scratch: ByteArray,
        expected: ByteArray,
        expectedOffset: Int,
        lba: ULong,
        bytes: Int,
    ): Boolean {
        disk.read(lba, ByteBuffer.wrap(scratch, 0, bytes))
        var k = 0
        while (k < bytes) {
            if (scratch[k] != expected[expectedOffset + k]) return false
            k++
        }
        return true
    }

    private fun mergeAbort(existing: Throwable?, next: Throwable): Throwable {
        if (existing == null) return next
        if (existing !== next) existing.addSuppressed(next)
        return existing
    }

    private fun readFully(input: CountedInputStream, buffer: ByteArray, length: Int): Int {
        var off = 0
        while (off < length) {
            val n = input.read(buffer, off, length - off)
            if (n < 0) return off
            off += n
        }
        return off
    }
}
