package usb.testutil

import java.nio.ByteBuffer
import usb.device.BlockGeometry
import usb.device.FakeBotSession
import usb.device.UsbDisk

/** In-memory disk; subclasses may inject read failures for a chosen LBA. */
open class RecordingDisk(
    sectorCount: ULong = 262144UL, // 128 MiB default
    sectorSize: Int = 512,
) : UsbDisk(
    session = FakeBotSession(),
    geometry = BlockGeometry(sectorSize, sectorCount),
) {
    val reads = mutableListOf<ULong>()
    val writes = mutableListOf<ULong>()
    val writtenRanges = mutableListOf<Pair<Long, Int>>()
    val bytes = mutableMapOf<ULong, ByteArray>()

    var readCount: Long = 0
        private set
    var writeCount: Long = 0
        private set
    var syncCount: Long = 0
        private set
    var bytesWritten: Int = 0
        private set

    fun resetStats() {
        readCount = 0
        writeCount = 0
        syncCount = 0
        bytesWritten = 0
        writtenRanges.clear()
        reads.clear()
        writes.clear()
    }

    fun wasRangeWritten(startLba: Long, count: Long): Boolean {
        val endLba = startLba + count
        return writtenRanges.any { (wStart, wCount) ->
            val wEnd = wStart + wCount
            wStart < endLba && wEnd > startLba
        }
    }

    override fun read(lba: ULong, destination: ByteBuffer) {
        reads.add(lba)
        readCount++
        val count = destination.remaining() / sectorSize
        for (i in 0 until count) {
            val cur = lba + i.toULong()
            val data = bytes[cur]
            if (data != null) {
                destination.put(data)
            } else {
                destination.put(ByteArray(sectorSize))
            }
        }
    }

    override fun write(lba: ULong, source: ByteBuffer) {
        writes.add(lba)
        writeCount++
        val count = source.remaining() / sectorSize
        writtenRanges.add(Pair(lba.toLong(), count))
        val n = source.remaining()
        bytesWritten += n

        for (i in 0 until count) {
            val cur = lba + i.toULong()
            val buf = ByteArray(sectorSize)
            source.get(buf)
            bytes[cur] = buf
        }
    }

    override fun sync() {
        syncCount++
    }

    override fun read(lba: Long, destination: ByteBuffer) = read(lba.toULong(), destination)
    override fun write(lba: Long, source: ByteBuffer) = write(lba.toULong(), source)

    fun bytes(): ByteArray {
        val total = (sectorCount * sectorSize.toULong()).toInt()
        val out = ByteArray(total)
        for ((lba, buf) in bytes) {
            val off = (lba * sectorSize.toULong()).toInt()
            if (off + buf.size <= out.size) {
                System.arraycopy(buf, 0, out, off, buf.size)
            }
        }
        return out
    }

    companion object {
        fun fromBytes(data: ByteArray, sectorSize: Int = 512): RecordingDisk {
            val count = (data.size + sectorSize - 1) / sectorSize
            val disk = RecordingDisk(sectorSize = sectorSize, sectorCount = count.toULong())
            for (i in 0 until count) {
                val start = i * sectorSize
                val len = minOf(sectorSize, data.size - start)
                val full = ByteArray(sectorSize)
                if (len > 0) {
                    System.arraycopy(data, start, full, 0, len)
                }
                disk.bytes[i.toULong()] = full
            }
            return disk
        }
    }
}
