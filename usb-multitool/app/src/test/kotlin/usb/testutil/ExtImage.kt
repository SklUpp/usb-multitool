package usb.testutil

import java.nio.ByteBuffer
import java.nio.ByteOrder
import usb.partition.VirtualPartition

/** Minimal in-memory image with EXT magic at byte 1024 for JVM probe tests. */
object ExtImage {
    fun partition(sectorSize: Int = 512, sectorCount: ULong = 8UL): VirtualPartition {
        require(sectorCount * sectorSize.toULong() >= 2048u)
        val bytes = ByteArray((sectorCount * sectorSize.toULong()).toInt())
        // Superblock starts at byte 1024; magic at +0x38.
        val magicAt = 1024 + 0x38
        bytes[magicAt] = 0x53
        bytes[magicAt + 1] = 0xEF.toByte()
        val disk = RecordingDisk.fromBytes(bytes, sectorSize)
        return VirtualPartition(0UL, sectorCount, disk)
    }
}
