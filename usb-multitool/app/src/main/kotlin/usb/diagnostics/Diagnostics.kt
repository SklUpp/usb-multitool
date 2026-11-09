package usb.diagnostics

import java.io.OutputStream
import java.nio.ByteBuffer
import usb.device.DiskException
import usb.device.UsbDisk
import usb.fs.MountedVolume
import usb.image.CountedInputStream
import usb.image.ImageCancelledException
import usb.image.ImageSource
import usb.service.DiskOperation
import usb.service.OperationState

data class ScanReport(
    val sectorsRead: ULong,
    val failed: List<ULongRange>,
)

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
     */
    fun readScan(disk: UsbDisk, operation: DiskOperation = DiskOperation("read-scan")): ScanReport {
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
        var lba = 0UL
        while (lba < total) {
            if (!operation.isActive()) break
            val n = minOf(chunkSectors, total - lba).toInt()
            buf.clear()
            buf.limit(n * sectorSize)
            try {
                disk.read(lba, buf) // never writes
            } catch (e: DiskException.SenseError) {
                if (!e.isMediumError) throw e
                for (i in 0 until n) {
                    if (!operation.isActive()) {
                        val scanned = lba + i.toULong()
                        operation.report((scanned * sectorSize.toULong()).toLong())
                        return ScanReport(sectorsRead = scanned, failed = failed)
                    }
                    buf.clear()
                    buf.limit(sectorSize)
                    try {
                        disk.read(lba + i.toULong(), buf)
                    } catch (inner: DiskException.SenseError) {
                        if (!inner.isMediumError) throw inner
                        markFailed(lba + i.toULong())
                    }
                }
            }
            lba += n.toULong()
            operation.report((lba * sectorSize.toULong()).toLong())
        }
        return ScanReport(sectorsRead = lba, failed = failed)
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
        openVolumes: List<MountedVolume> = emptyList(),
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
        closeMountedVolumes(openVolumes)
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
        openVolumes: List<MountedVolume> = emptyList(),
        operation: DiskOperation = DiskOperation("wipe"),
    ) {
        confirmation.requireMatch(identity, disk)
        closeMountedVolumes(openVolumes)
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
            DdrescueNative.persistHint(map)
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
                    else -> operation.state = OperationState.Completed
                }
            }
        }
        return map
    }

    /**
     * Image → disk. Requires a [MapKind.RESTORE] map (never a COPY map). Confirmation
     * and size checks must run before the first write (caller / [restore] preflight).
     * Medium write errors → FAILED; disconnect/transport → abort Partial.
     */
    fun restore(
        source: ImageSource,
        disk: UsbDisk,
        map: RescueMap,
        identity: String,
        confirmation: DestructiveConfirmation,
        openVolumes: List<MountedVolume> = emptyList(),
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

        closeMountedVolumes(openVolumes)
        var sinceCheckpoint = 0UL
        var aborted: Throwable? = null
        var wroteAny = false
        try {
            source.open().use { input ->
                val sector = ByteArray(sectorSize)
                var lba = 0UL
                while (lba < map.sectorCount) {
                    val n = readFully(input, sector)
                    if (n == 0) {
                        if (lba < map.sectorCount) {
                            aborted =
                                IllegalStateException(
                                    "rescue image ended at LBA $lba; map expects ${map.sectorCount} sectors",
                                )
                        }
                        break
                    }
                    if (n < sectorSize) {
                        aborted =
                            IllegalStateException(
                                "rescue image truncated mid-sector at LBA $lba ($n of $sectorSize bytes)",
                            )
                        break
                    }
                    if (!operation.isActive()) {
                        aborted = ImageCancelledException("rescue restore cancelled")
                        break
                    }
                    val status = map.statusAt(lba)
                    if (status != RescueStatus.COPIED) {
                        try {
                            disk.write(lba, ByteBuffer.wrap(sector))
                            map.markCopied(lba, 1UL)
                            wroteAny = true
                        } catch (e: DiskException.SenseError) {
                            if (!e.isMediumError) {
                                aborted = e
                                break
                            }
                            map.markFailed(lba, 1UL)
                            wroteAny = true
                        } catch (e: DiskException) {
                            aborted = e
                            break
                        } catch (e: Exception) {
                            aborted = e
                            break
                        }
                    }
                    lba++
                    sinceCheckpoint++
                    operation.report((lba * sectorSize.toULong()).toLong())
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
            }
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
                    else -> operation.state = OperationState.Completed
                }
            }
        }
        if (aborted != null && operation.state is OperationState.Failed) {
            throw aborted
        }
        return map
    }

    private fun mergeAbort(existing: Throwable?, next: Throwable): Throwable {
        if (existing == null) return next
        if (existing !== next) existing.addSuppressed(next)
        return existing
    }

    private fun readFully(input: CountedInputStream, buffer: ByteArray): Int {
        var off = 0
        while (off < buffer.size) {
            val n = input.read(buffer, off, buffer.size - off)
            if (n < 0) return off
            off += n
        }
        return off
    }
}

internal object DdrescueNative {
    @Volatile var libraryLoaded: Boolean = false
        private set

    @Volatile var engineLinked: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("usb_native")
            libraryLoaded = true
            engineLinked = nativeEngineLinked()
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
            engineLinked = false
        }
    }

    fun persistHint(map: RescueMap) {
        // In-memory map is authoritative on JVM. Native mapfile flush needs engine.
        if (!libraryLoaded || !engineLinked) return
        nativePersist(map.sectorCount.toLong(), map.partial)
    }

    @JvmStatic external fun nativePersist(sectorCount: Long, partial: Boolean)

    @JvmStatic external fun nativeEngineLinked(): Boolean
}
