package usb.fs

import usb.EngineMissingException
import usb.device.UsbDisk
import usb.partition.VirtualPartition

/**
 * Mounts the filesystem that lives in one partition region of [disk].
 *
 * [VirtualPartition] applies [startLba] exactly once, so engines only ever see
 * partition-relative LBAs. On success the caller owns both the returned volume and
 * [disk]; on failure the disk handle is closed here so a failed mount never leaks
 * the exclusive USB claim.
 *
 * EXT: prefer [readOnly]=true for browse/list/download. RW mounts mark the on-disk
 * superblock dirty under lwext4 — only open RW for explicit mutations, then close.
 */
object PartitionVolumeSession {
    fun open(
        disk: UsbDisk,
        startLba: ULong,
        sectorCount: ULong,
        readOnly: Boolean = true,
    ): UsbFsVolume {
        require(sectorCount > 0UL) { "partition sectorCount must be positive" }
        require(sectorCount <= Long.MAX_VALUE.toULong()) { "partition too large: $sectorCount sectors" }
        return try {
            val partition = VirtualPartition(startLba = startLba, sectorCount = sectorCount, disk = disk)
            when (Filesystem.probe(partition)) {
                FilesystemId.FAT, FilesystemId.EXFAT ->
                    FatFs.mount(
                        callback = BlockDeviceFatCallback(partition),
                        sectorSize = partition.sectorSize,
                        sectorCount = partition.sectorCount.toLong(),
                    )
                FilesystemId.EXT -> ExtFs.mount(partition, readOnly = readOnly)
                FilesystemId.NTFS ->
                    try {
                        NtfsFs.mount(partition, readOnly = readOnly)
                    } catch (failure: UnsatisfiedLinkError) {
                        throw EngineMissingException("ntfs-3g")
                    }
            }
        } catch (failure: Throwable) {
            // Close must not erase the mount/probe cause: attach as suppressed.
            try {
                disk.close()
            } catch (closeErr: Throwable) {
                if (closeErr !== failure) failure.addSuppressed(closeErr)
            }
            throw failure
        }
    }
}
