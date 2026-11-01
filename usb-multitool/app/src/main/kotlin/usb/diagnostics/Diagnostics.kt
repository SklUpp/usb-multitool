package usb.diagnostics

import java.nio.ByteBuffer
import usb.device.UsbDisk
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
)

class WipeNotConfirmedException : Exception("wipe requires explicit confirmation")

object Diagnostics {
    fun fakeCapacity(disk: UsbDisk, operation: DiskOperation = DiskOperation("f3probe")): FakeCapacityReport {
        // Upstream F3 probe via f3_bridge; without native lib, perform a bounded read probe.
        val rc = F3Native.probe(disk)
        if (rc >= 0 && F3Native.libraryLoaded) {
            return FakeCapacityReport(
                claimedSectors = disk.geometry.sectorCount,
                lastGoodSector = rc.toULong(),
                consistent = rc.toULong() + 1UL >= disk.geometry.sectorCount,
            )
        }
        val buf = ByteBuffer.allocate(disk.geometry.sectorSize)
        var lastGood = 0UL
        var lba = 0UL
        while (lba < disk.geometry.sectorCount && operation.isActive()) {
            try {
                buf.clear()
                disk.read(lba, buf)
                lastGood = lba
            } catch (_: Exception) {
                break
            }
            // Power-of-two stride like F3's early probe stages, not a full rewrite.
            lba = if (lba == 0UL) 1UL else lba * 2UL
            if (lba >= disk.geometry.sectorCount) break
            operation.report((lba * disk.geometry.sectorSize.toULong()).toLong())
        }
        return FakeCapacityReport(
            claimedSectors = disk.geometry.sectorCount,
            lastGoodSector = lastGood,
            consistent = lastGood + 1UL >= disk.geometry.sectorCount || disk.geometry.sectorCount <= 1UL,
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

    fun writeBenchmark(
        disk: UsbDisk,
        sectors: ULong = minOf(2048UL, disk.geometry.sectorCount),
        operation: DiskOperation = DiskOperation("write-bench"),
    ): BenchmarkReport {
        val buf = ByteBuffer.allocate(disk.geometry.sectorSize)
        val start = System.nanoTime()
        var written = 0L
        var lba = 0UL
        while (lba < sectors) {
            if (!operation.isActive()) break
            buf.clear()
            disk.write(lba, buf)
            written += disk.geometry.sectorSize.toLong()
            lba++
            operation.report(written)
        }
        val elapsed = (System.nanoTime() - start) / 1_000_000L
        return BenchmarkReport(bytesWritten = written, elapsedMs = elapsed.coerceAtLeast(0L))
    }

    fun wipe(
        disk: UsbDisk,
        confirmed: Boolean,
        operation: DiskOperation = DiskOperation("wipe"),
    ) {
        if (!confirmed) throw WipeNotConfirmedException()
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

object Rescue {
    fun copy(
        disk: UsbDisk,
        destination: ByteArray,
        map: RescueMap = RescueMap.whole(disk.geometry.sectorCount),
        operation: DiskOperation = DiskOperation("rescue-copy"),
    ): RescueMap {
        require(destination.size.toULong() >= disk.geometry.capacityBytes)
        val sectorSize = disk.geometry.sectorSize
        val buf = ByteBuffer.allocate(sectorSize)
        for (range in map.nonTried + map.failed) {
            var lba = range.first
            while (lba <= range.last) {
                if (!operation.isActive()) return map
                try {
                    buf.clear()
                    disk.read(lba, buf)
                    val offset = (lba * sectorSize.toULong()).toInt()
                    System.arraycopy(buf.array(), 0, destination, offset, sectorSize)
                    map.markCopied(lba, 1UL)
                } catch (_: Exception) {
                    map.markFailed(lba, 1UL)
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
        operation: DiskOperation = DiskOperation("rescue-restore"),
    ): RescueMap {
        // Resume only from the provided map; non-tried/failed ranges are rewritten.
        source.open().use { input ->
            val sectorSize = disk.geometry.sectorSize
            val sector = ByteArray(sectorSize)
            var lba = 0UL
            while (lba < map.sectorCount) {
                val n = input.read(sector)
                if (n < 0) break
                if (n < sectorSize) sector.fill(0, n, sectorSize)
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
                if (n < sectorSize) break
            }
        }
        return map
    }
}

internal object F3Native {
    @Volatile var libraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("usb_native")
            libraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
        }
    }

    fun probe(disk: UsbDisk): Int {
        if (!libraryLoaded) return -1
        return nativeProbe(disk.geometry.sectorSize, disk.geometry.sectorCount.toLong())
    }

    @JvmStatic external fun nativeProbe(sectorSize: Int, sectorCount: Long): Int
}

internal object DdrescueNative {
    @Volatile var libraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("usb_native")
            libraryLoaded = true
        } catch (_: UnsatisfiedLinkError) {
            libraryLoaded = false
        }
    }

    fun persistHint(map: RescueMap) {
        if (libraryLoaded) nativePersist(map.sectorCount.toLong(), map.partial)
    }

    @JvmStatic external fun nativePersist(sectorCount: Long, partial: Boolean)
}
