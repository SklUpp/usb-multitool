package usb.testutil

import usb.partition.VirtualPartition

object FatImage {
    fun partition(): VirtualPartition {
        val boot = ByteArray(512)
        boot[510] = 0x55.toByte()
        boot[511] = 0xAA.toByte()
        boot[11] = 0x00
        boot[12] = 0x02
        boot[13] = 0x01
        System.arraycopy("MSDOS5.0".toByteArray(), 0, boot, 3, 8)
        System.arraycopy("FAT16   ".toByteArray(), 0, boot, 54, 8)
        val disk = RecordingDisk.fromBytes(boot)
        return VirtualPartition(0UL, 1UL, disk)
    }
}
