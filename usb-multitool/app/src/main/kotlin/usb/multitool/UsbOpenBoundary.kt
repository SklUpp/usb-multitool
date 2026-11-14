package usb.multitool

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import usb.device.DiskException
import usb.device.UsbDisk
import usb.device.UsbDiskSession
import usb.device.UsbExclusiveClaim
import usb.diagnostics.AppTraceLogger
import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.AttachmentState
import usb.files.FilesVolumeSession
import usb.files.MountedFilesVolume
import usb.fs.ExtVolume
import usb.fs.FatKind
import usb.fs.FatVolume
import usb.fs.FilesystemId
import usb.fs.NtfsVolume
import usb.fs.PartitionVolumeSession
import usb.partition.PartitionTable

/** USB disk/filesystem open boundary. Ownership remains with [filesSession] and its claim. */
internal class UsbOpenBoundary(
    private val currentAttachment: () -> AttachmentState.Snapshot<UsbDevice>,
    private val usbManager: () -> UsbManager?,
    private val filesSession: FilesVolumeSession,
    private val trace: AppTraceLogger,
    private val deviceKey: (UsbDevice?) -> String?,
    private val openDisk: (UsbManager, UsbDevice, UsbExclusiveClaim.Lease) -> UsbDisk =
        { manager, device, lease -> UsbDiskSession.open(manager, device, lease) },
) {
    /** Raw disk that owns the USB claim plus the live device it was opened for. */
    internal class RawUsbSession(val disk: UsbDisk, val device: UsbDevice)

    fun openMountedFilesVolume(
        partitionId: String,
        writable: Boolean,
        pin: AttachmentPin,
        lease: UsbExclusiveClaim.Lease,
    ): MountedFilesVolume {
        trace.log(
            "volume.mount.start",
            "part" to partitionId,
            "writable" to writable,
            "gen" to pin.attachGeneration,
        )
        val live = currentAttachment()
        val device = live.device ?: throw DiskException.Disconnected()
        val manager = usbManager() ?: throw DiskException.Disconnected()
        // Pin check before the USB claim: a paused operation must not open another stick.
        requireAttachment(pin, device)
        val disk = openDisk(manager, device, lease)
        lateinit var mountedPartition: usb.partition.Partition
        val volume = try {
            val index = partitionId.toIntOrNull()
                ?: throw IllegalArgumentException("invalid partition id $partitionId")
            val table = PartitionTable.read(disk)
            // A detach/re-attach can happen during the claim or table read: re-verify the pin
            // against the live attachment before trusting this geometry or partition range.
            requireAttachment(pin, device)
            if (writable) requireWritableMountAllowed(table)
            mountedPartition = table.partitions.getOrNull(index - 1)
                ?: throw IllegalArgumentException("partition $partitionId no longer exists")
            PartitionVolumeSession.open(
                disk,
                mountedPartition.startLba,
                mountedPartition.sectorCount,
                readOnly = !writable,
            )
        } catch (failure: Throwable) {
            // PartitionVolumeSession already closes on mount failure; a second close is
            // idempotent, but if it still throws keep the mount cause and suppress close.
            try {
                disk.close()
            } catch (closeErr: Throwable) {
                if (closeErr !== failure) failure.addSuppressed(closeErr)
            }
            trace.log(
                "volume.mount.fail",
                "part" to partitionId,
                "writable" to writable,
                "error" to (failure.message ?: failure.javaClass.simpleName),
            )
            throw failure
        }
        trace.log("volume.mount.ok", "part" to partitionId, "writable" to writable)
        val mountedFilesystem = when (volume) {
            is FatVolume -> when (volume.kind) {
                FatKind.EXFAT -> FilesystemId.EXFAT
                FatKind.FAT12, FatKind.FAT16, FatKind.FAT32 -> FilesystemId.FAT
                FatKind.UNKNOWN -> null
            }
            is ExtVolume -> FilesystemId.EXT
            is NtfsVolume -> FilesystemId.NTFS
            else -> null
        }
        return MountedFilesVolume(
            partitionId,
            disk,
            volume,
            writable = writable,
            startLba = mountedPartition.startLba,
            sectorCount = mountedPartition.sectorCount,
            sectorSize = disk.sectorSize,
            filesystemId = mountedFilesystem,
        )
    }

    /** Close mount, claim USB, fence slow open against detach/replug. */
    fun openRawDisk(
        revalidate: (AttachmentState.Snapshot<UsbDevice>) -> UsbDevice? = { it.device },
    ): RawUsbSession? =
        filesSession.openAfterCloseClaimed { lease ->
            val pinned = currentAttachment()
            val device = revalidate(pinned)
            if (device == null) {
                lease.close()
                return@openAfterCloseClaimed null
            }
            val manager = usbManager() ?: throw DiskException.Disconnected()
            val disk =
                try {
                    openDisk(manager, device, lease)
                } catch (failure: Throwable) {
                    lease.close()
                    throw failure
                }
            // Second fence: the open itself can straddle a detach/replug.
            val live = currentAttachment()
            if (live.generation != pinned.generation ||
                deviceKey(live.device) != deviceKey(device)
            ) {
                var closeFailure: Throwable? = null
                try {
                    disk.close()
                } catch (t: Throwable) {
                    closeFailure = t
                }
                throw AttachmentChangedException().also { changed ->
                    closeFailure?.let(changed::addSuppressed)
                }
            }
            RawUsbSession(disk, device)
        }

    private fun requireAttachment(pin: AttachmentPin, device: UsbDevice) {
        // One snapshot: never compare pin against device/generation pair from different publishes.
        val live = currentAttachment()
        if (live.generation != pin.attachGeneration ||
            deviceKey(device) != pin.deviceKey ||
            deviceKey(live.device) != pin.deviceKey
        ) {
            throw AttachmentChangedException()
        }
    }
}
