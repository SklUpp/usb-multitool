package usb.partition

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.device.UsbDisk

object MbrTable {
    private const val PROTECTIVE_TYPE = 0xEE

    data class Result(
        val protective: Boolean,
        val partitions: List<Partition>,
    )

    fun read(disk: UsbDisk): Result {
        val sectorSize = disk.geometry.sectorSize
        val buf = ByteBuffer.allocate(sectorSize).order(ByteOrder.LITTLE_ENDIAN)
        disk.read(0UL, buf)
        buf.flip()
        if (buf.limit() < 512) return Result(false, emptyList())
        val sig = (buf.get(510).toInt() and 0xFF) or ((buf.get(511).toInt() and 0xFF) shl 8)
        if (sig != 0xAA55) return Result(false, emptyList())

        val entries = ArrayList<Partition>(4)
        var protective = false
        for (i in 0 until 4) {
            val off = 446 + i * 16
            val type = buf.get(off + 4).toInt() and 0xFF
            if (type == 0) continue
            val start = buf.getInt(off + 8).toUInt().toULong()
            val count = buf.getInt(off + 12).toUInt().toULong()
            if (count == 0UL) continue
            if (type == PROTECTIVE_TYPE) {
                protective = true
                continue
            }
            entries.add(
                Partition(
                    startLba = start,
                    sectorCount = count,
                    type = "0x%02X".format(type),
                    name = "P${i + 1}",
                    flags = if ((buf.get(off).toInt() and 0x80) != 0) 1L else 0L,
                ),
            )
        }
        rejectOverlaps(entries)
        return Result(protective, entries)
    }
}

internal fun rejectOverlaps(partitions: List<Partition>) {
    val sorted = partitions.sortedBy { it.startLba }
    for (i in 1 until sorted.size) {
        val prev = sorted[i - 1]
        val cur = sorted[i]
        val prevEnd = prev.startLba + prev.sectorCount
        require(prevEnd >= prev.startLba) { "partition end overflow" }
        require(cur.startLba >= prevEnd) {
            "overlapping partitions at LBA ${cur.startLba}"
        }
    }
}
