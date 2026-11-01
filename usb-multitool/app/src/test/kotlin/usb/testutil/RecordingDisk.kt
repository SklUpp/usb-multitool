package usb.testutil

import java.nio.ByteBuffer
import usb.device.BlockDevice

class RecordingDisk(
    override val sectorSize: Int = 512,
    override val sectorCount: ULong = 262144UL, // 128 MiB default
) : BlockDevice {

    private val sectors = mutableMapOf<Long, ByteArray>()
    val writtenRanges = mutableListOf<Pair<Long, Int>>()

    var readCount: Long = 0
        private set
    var writeCount: Long = 0
        private set
    var syncCount: Long = 0
        private set
    var bytesWritten: Long = 0
        private set

    fun resetStats() {
        readCount = 0
        writeCount = 0
        syncCount = 0
        bytesWritten = 0
        writtenRanges.clear()
    }

    fun wasRangeWritten(startLba: Long, count: Long): Boolean {
        val endLba = startLba + count
        return writtenRanges.any { (wStart, wCount) ->
            val wEnd = wStart + wCount
            wStart < endLba && wEnd > startLba
        }
    }

    override fun read(lba: Long, destination: ByteBuffer) {
        require(lba >= 0 && lba.toULong() < sectorCount) { "LBA out of bounds: $lba" }
        readCount++
        val count = destination.remaining() / sectorSize
        require(count > 0) { "Destination must hold at least 1 sector" }
        require((lba + count).toULong() <= sectorCount) { "Read exceeds disk bounds" }

        for (i in 0 until count) {
            val curLba = lba + i
            val data = sectors[curLba]
            if (data != null) {
                destination.put(data)
            } else {
                destination.put(ByteArray(sectorSize))
            }
        }
    }

    override fun write(lba: Long, source: ByteBuffer) {
        require(lba >= 0 && lba.toULong() < sectorCount) { "LBA out of bounds: $lba" }
        writeCount++
        val count = source.remaining() / sectorSize
        require(count > 0) { "Source must hold at least 1 sector" }
        require((lba + count).toULong() <= sectorCount) { "Write exceeds disk bounds" }

        writtenRanges.add(Pair(lba, count))
        bytesWritten += (count * sectorSize).toLong()

        for (i in 0 until count) {
            val curLba = lba + i
            val sec = ByteArray(sectorSize)
            source.get(sec)
            sectors[curLba] = sec
        }
    }

    override fun sync() {
        syncCount++
    }

    fun getSector(lba: Long): ByteArray? = sectors[lba]?.copyOf()

    fun putSector(lba: Long, data: ByteArray) {
        require(data.size == sectorSize)
        sectors[lba] = data.copyOf()
    }
}
