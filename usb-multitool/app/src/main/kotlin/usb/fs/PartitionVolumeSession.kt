package usb.fs

import usb.device.UsbDisk
import usb.partition.VirtualPartition

/**
 * Mounts the FAT volume that lives in one partition region of [disk].
 *
 * [VirtualPartition] applies [startLba] exactly once, so FatFs only ever sees
 * partition-relative LBAs. On success the caller owns both the returned volume and
 * [disk]; on failure the disk handle is closed here so a failed mount never leaks
 * the exclusive USB claim.
 */
object PartitionVolumeSession {
    fun open(disk: UsbDisk, startLba: ULong, sectorCount: ULong): FatVolume {
        require(sectorCount > 0UL) { "partition sectorCount must be positive" }
        require(sectorCount <= Long.MAX_VALUE.toULong()) { "partition too large: $sectorCount sectors" }
        return try {
            val partition = VirtualPartition(startLba = startLba, sectorCount = sectorCount, disk = disk)
            FatFs.mount(
                callback = BlockDeviceFatCallback(partition),
                sectorSize = partition.sectorSize,
                sectorCount = partition.sectorCount.toLong(),
            )
        } catch (failure: Throwable) {
            runCatching { disk.close() }
            throw failure
        }
    }
}
