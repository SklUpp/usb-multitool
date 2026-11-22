package usb.fs

import usb.requireLinkedEngine
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
 * Missing native engines surface as [usb.EngineMissingException] via
 * [usb.requireLinkedEngine] with the real engine name and the original
 * [UnsatisfiedLinkError] as cause. Other mount/probe failures pass through
 * unchanged. Disk-close errors attach as suppressed on the primary.
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
    ): UsbFsVolume =
        openInternal(
            disk = disk,
            startLba = startLba,
            sectorCount = sectorCount,
            readOnly = readOnly,
            mount = ::mountProbed,
        )

    /**
     * Test seam: [mount] receives the probed id and partition view. Production uses
     * [mountProbed]; unit tests inject ULE / non-ULE failures without native libs.
     */
    internal fun openInternal(
        disk: UsbDisk,
        startLba: ULong,
        sectorCount: ULong,
        readOnly: Boolean = true,
        mount: (FilesystemId, VirtualPartition, Boolean) -> UsbFsVolume,
    ): UsbFsVolume {
        require(sectorCount > 0UL) { "partition sectorCount must be positive" }
        require(sectorCount <= Long.MAX_VALUE.toULong()) { "partition too large: $sectorCount sectors" }
        return try {
            val partition = VirtualPartition(startLba = startLba, sectorCount = sectorCount, disk = disk)
            val id = Filesystem.probe(partition)
            mount(id, partition, readOnly)
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

    /** Production engine name for [id]; single source for mount gates and tests. */
    internal fun engineFor(id: FilesystemId): String =
        when (id) {
            FilesystemId.FAT, FilesystemId.EXFAT -> "FatFs"
            FilesystemId.EXT -> "lwext4"
            FilesystemId.NTFS -> "ntfs-3g"
        }

    internal fun mountProbed(
        id: FilesystemId,
        partition: VirtualPartition,
        readOnly: Boolean,
    ): UsbFsVolume =
        requireLinkedEngine(engineFor(id)) {
            when (id) {
                FilesystemId.FAT, FilesystemId.EXFAT ->
                    FatFs.mount(
                        callback = BlockDeviceFatCallback(partition),
                        sectorSize = partition.sectorSize,
                        sectorCount = partition.sectorCount.toLong(),
                    )
                FilesystemId.EXT -> ExtFs.mount(partition, readOnly = readOnly)
                FilesystemId.NTFS -> NtfsFs.mount(partition, readOnly = readOnly)
            }
        }
}
