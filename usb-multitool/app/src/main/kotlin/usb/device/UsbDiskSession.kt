package usb.device

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Discovers a BOT mass-storage interface, claims it with force=true,
 * probes capacity, and returns an exclusive [UsbDisk].
 *
 * [lease] is the caller's [UsbExclusiveClaim.Lease]; it moves to the returned disk and is
 * released by [UsbDisk.close]. Pass it only after the previous session is closed/claimed
 * under a single critical section, or two sessions will interleave bulk transfers.
 */
object UsbDiskSession {
    fun open(
        manager: UsbManager,
        device: UsbDevice,
        lease: UsbExclusiveClaim.Lease,
        lun: Byte = 0,
    ): UsbDisk {
        val target = MassStorageDiscovery.findBotInterface(device)
            ?: throw DiskException.IoFailure("no SCSI BOT mass-storage interface on device")
        val channel = AndroidUsbBulkChannel.open(manager, target)
        val session = BotSessionImpl(channel, lun = lun)
        return try {
            initialize(session)
            UsbDisk(session, lease = lease)
        } catch (e: Exception) {
            try {
                session.close()
            } finally {
                lease.close()
            }
            throw e
        }
    }

    internal fun initialize(session: BotSession) {
        // TUR may report UNIT ATTENTION while media settles; clear it once.
        val tur = session.transfer(ScsiCommand.testUnitReady(), null)
        when (tur.status) {
            CswStatus.PASSED -> Unit
            CswStatus.FAILED -> {
                val senseBuffer = java.nio.ByteBuffer.allocate(18)
                val sense = session.transfer(ScsiCommand.requestSense(18), senseBuffer)
                if (sense.status != CswStatus.PASSED) {
                    throw DiskException.IoFailure("REQUEST SENSE failed during USB initialization: ${sense.status}")
                }
                senseBuffer.flip()
                val parsed = UsbDisk.parseSense(senseBuffer)
                if (!parsed.isUnitAttention && !(parsed.senseKey == 0x02 && parsed.asc == 0x04)) throw parsed
            }
            CswStatus.PHASE_ERROR -> {
                session.bulkOnlyReset()
                throw DiskException.IoFailure("phase error on TEST UNIT READY")
            }
        }
    }
}
