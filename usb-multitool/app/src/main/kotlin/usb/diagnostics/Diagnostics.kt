package usb.diagnostics

import java.io.OutputStream
import java.nio.ByteBuffer
import usb.device.DiskException
import usb.device.UsbDisk
import usb.fs.MountedVolume
import usb.image.CountedInputStream
import usb.image.ImageSource
import usb.service.DiskOperation

data class FakeCapacityReport(
    val claimedSectors: ULong,
    val lastGoodSector: ULong,
    val consistent: Boolean,
)

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
    /**
     * f3probe is destructive: it writes pattern sectors and reads them back to
     * detect wrap-around falsified capacity, so it needs a confirmed target,
     * not just a [DiskOperation] progress sink.
     *
     * No probe is linked into `libusb_native` (third_party/f3 holds fetch
     * instructions only and the former `f3_bridge.c` shim returned
     * `sectorCount - 1` without touching the disk). Reporting a verdict we did
     * not measure would claim a disk is genuine when nothing was tested, so
     * this fails loudly instead.
     */
    fun fakeCapacity(disk: UsbDisk, operation: DiskOperation = DiskOperation("f3probe")): FakeCapacityReport {
        throw DiskException.UnsupportedCommand(
            "fake-capacity probe unavailable: f3probe is not built into libusb_native " +
                "and probing ${disk.geometry.sectorCount} sectors of this device needs " +
                "destructive write patterns with a signed confirmation",
        )
    }

    fun readScan(disk: UsbDisk, operation: DiskOperation = DiskOperation("read-scan")): ScanReport {
        val failed = mutableListOf<ULongRange>()
        val buf = ByteBuffer.allocate(disk.geometry.sectorSize)
        var lba = 0UL
        var failStart: ULong? = null
        while (lba < disk.geometry.sectorCount) {
            if (!operation.isActive()) break
            try {
                buf.clear()
                disk.read(lba, buf) // never writes
                if (failStart != null) {
                    failed += failStart until lba
                    failStart = null
                }
            } catch (_: Exception) {
                if (failStart == null) failStart = lba
            }
            lba++
            operation.report((lba * disk.geometry.sectorSize.toULong()).toLong())
        }
        if (failStart != null) failed += failStart until disk.geometry.sectorCount
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
        while (lba < disk.geometry.sectorCount) {
            if (!operation.isActive()) break
            zeros.clear()
            disk.write(lba, zeros)
            lba++
            operation.report((lba * disk.geometry.sectorSize.toULong()).toLong())
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
    /** Write [sector] bytes at absolute [lba]. */
    fun writeSector(lba: ULong, sector: ByteArray)
}

/**
 * Adapt an appending [OutputStream] whose first byte is LBA 0.
 * A skipped LBA would shift every later sector, so a gap is rejected instead.
 */
fun OutputStream.asRescueSink(sectorSize: Int): RescueSink {
    var next = 0UL
    return RescueSink { lba, sector ->
        require(sector.size == sectorSize)
        check(lba == next) {
            "rescue image is positional: expected LBA $next, got $lba"
        }
        write(sector)
        next = lba + 1UL
    }
}

object Rescue {
    fun copy(
        disk: UsbDisk,
        destination: RescueSink,
        map: RescueMap = RescueMap.whole(disk.geometry.sectorCount),
        operation: DiskOperation = DiskOperation("rescue-copy"),
    ): RescueMap {
        val sectorSize = disk.geometry.sectorSize
        val buf = ByteBuffer.allocate(sectorSize)
        val zeros = ByteArray(sectorSize)
        for (range in map.nonTried + map.failed) {
            var lba = range.first
            while (lba <= range.last) {
                if (!operation.isActive()) return map
                try {
                    buf.clear()
                    disk.read(lba, buf)
                    destination.writeSector(lba, buf.array().copyOf(sectorSize))
                    map.markCopied(lba, 1UL)
                } catch (_: Exception) {
                    map.markFailed(lba, 1UL)
                    // Keep the image dense: the unreadable sector becomes a zero
                    // gap at its own offset so every later sector stays aligned
                    // with its LBA in the map.
                    destination.writeSector(lba, zeros)
                }
                lba++
                operation.report((lba * sectorSize.toULong()).toLong())
            }
        }
        DdrescueNative.persistHint(map)
        return map
    }

    fun restore(
        source: ImageSource,
        disk: UsbDisk,
        map: RescueMap,
        openVolumes: List<MountedVolume> = emptyList(),
        operation: DiskOperation = DiskOperation("rescue-restore"),
    ): RescueMap {
        closeMountedVolumes(openVolumes)
        // Resume only from the provided map; non-tried/failed ranges are rewritten.
        source.open().use { input ->
            val sectorSize = disk.geometry.sectorSize
            val sector = ByteArray(sectorSize)
            var lba = 0UL
            while (lba < map.sectorCount) {
                // Always consume the sector at this LBA from the image so
                // COPIED skips stay aligned; never rewrite a COPIED sector.
                val n = readFully(input, sector)
                if (n == 0) break // clean EOF
                if (n < sectorSize) {
                    // EOF mid-sector ends cleanly; never zero-fill or desync ranges.
                    break
                }
                val status = map.statusAt(lba)
                if (status != RescueStatus.COPIED) {
                    if (!operation.isActive()) return map
                    try {
                        disk.write(lba, ByteBuffer.wrap(sector))
                        map.markCopied(lba, 1UL)
                    } catch (_: Exception) {
                        map.markFailed(lba, 1UL)
                    }
                }
                lba++
                operation.report((lba * sectorSize.toULong()).toLong())
            }
        }
        return map
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
