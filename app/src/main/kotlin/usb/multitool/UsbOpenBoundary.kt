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
/** Eject raw open went stale mid-open and the fenced disk close failed: latch/report this. */
class EjectStaleCloseException(cause: Throwable) : IllegalStateException(
    "USB attachment changed during eject and the fenced close was not confirmed: " +
        (cause.message ?: cause.javaClass.simpleName),
    cause,
) {
    init {
        cause.suppressed.forEach(::addSuppressed)
    }
}

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
        var mountedStart = 0UL
        var mountedCount = 0UL
        val volume = try {
            val wholeDisk = partitionId == WHOLE_DISK_VOLUME_ID
            // Raw image sectors may not form a valid MBR; the whole-volume path needs only
            // enough geometry to prove no table owns the data before mounting LBA 0.
            val table = if (wholeDisk) {
                runCatching { PartitionTable.read(disk) }.getOrNull()
            } else {
                PartitionTable.read(disk)
            }
            // A detach/re-attach can happen during the claim or table read: re-verify the pin
            // against the live attachment before trusting this geometry or partition range.
            requireAttachment(pin, device)
            if (wholeDisk) {
                require(!writable) { "whole-disk image volumes are read-only" }
                mountedStart = 0UL
                mountedCount = disk.sectorCount
                PartitionVolumeSession.open(
                    disk,
                    mountedStart,
                    mountedCount,
                    readOnly = true,
                )
            } else {
                val index = partitionId.toIntOrNull()
                    ?: throw IllegalArgumentException("invalid partition id $partitionId")
                val partitionTable = requireNotNull(table)
                if (writable) requireWritableMountAllowed(partitionTable)
                val mountedPartition = partitionTable.partitions.getOrNull(index - 1)
                    ?: throw IllegalArgumentException("partition $partitionId no longer exists")
                mountedStart = mountedPartition.startLba
                mountedCount = mountedPartition.sectorCount
                PartitionVolumeSession.open(
                    disk,
                    mountedStart,
                    mountedCount,
                    readOnly = !writable,
                )
            }
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
            is usb.fs.IsoVolume -> FilesystemId.ISO
            else -> null
        }
        return MountedFilesVolume(
            partitionId,
            disk,
            volume,
            writable = writable,
            startLba = mountedStart,
            sectorCount = mountedCount,
            sectorSize = disk.sectorSize,
            filesystemId = mountedFilesystem,
        )
    }

    /** Close mount, claim USB, fence slow open against detach/replug. */
    fun openRawDisk(
        revalidate: (AttachmentState.Snapshot<UsbDevice>) -> UsbDevice? = { it.device },
    ): RawUsbSession? =
        filesSession.openAfterCloseClaimed { lease ->
            openRawDiskClaimed(lease, revalidate)?.let { (disk, device) -> RawUsbSession(disk, device) }
        }

    /**
     * Eject-only raw open on an already-held session lease. Never takes a second claim
     * and never closes a mount: [FilesVolumeSession.ejectLocked] holds the session lock
     * while this runs. Pin/device/generation fenced before and after the USB open.
     */
    internal fun openEjectedDisk(
        lease: UsbExclusiveClaim.Lease,
        pin: AttachmentPin,
    ): UsbDisk {
        val pinned = currentAttachment()
        val device = pinned.device ?: throw DiskException.Disconnected()
        requireEjectAttachment(pin, pinned, device)
        val manager = usbManager() ?: throw DiskException.Disconnected()
        val disk = openDisk(manager, device, lease)
        val live = currentAttachment()
        val liveDevice = live.device
        if (live.generation != pinned.generation ||
            liveDevice == null ||
            deviceKey(liveDevice) != deviceKey(device) ||
            live.generation != pin.attachGeneration ||
            deviceKey(liveDevice) != pin.deviceKey
        ) {
            val changed = AttachmentChangedException()
            try {
                disk.close()
            } catch (closeErr: Throwable) {
                // Stale fence owns the verdict: surface the close outcome, fenced
                // under the attachment cause, so ejectLocked can latch/report it
                // instead of silently converting to NotOwned.
                changed.addSuppressed(closeErr)
                throw EjectStaleCloseException(closeErr)
            }
            throw changed
        }
        return disk
    }

    private fun openRawDiskClaimed(
        lease: UsbExclusiveClaim.Lease,
        revalidate: (AttachmentState.Snapshot<UsbDevice>) -> UsbDevice?,
    ): Pair<UsbDisk, UsbDevice>? {
        val pinned = currentAttachment()
        val device = revalidate(pinned)
        if (device == null) {
            lease.close()
            return null
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
        return disk to device
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

    private fun requireEjectAttachment(
        pin: AttachmentPin,
        pinned: AttachmentState.Snapshot<UsbDevice>,
        device: UsbDevice,
    ) {
        if (pinned.generation != pin.attachGeneration ||
            deviceKey(device) != pin.deviceKey ||
            deviceKey(pinned.device) != pin.deviceKey
        ) {
            throw AttachmentChangedException()
        }
    }
}
