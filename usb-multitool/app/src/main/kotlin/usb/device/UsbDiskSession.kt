package usb.device

import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager

/**
 * Discovers a BOT mass-storage interface, claims it with force=true,
 * probes capacity, and returns an exclusive [UsbDisk].
 */
object UsbDiskSession {
    fun open(manager: UsbManager, device: UsbDevice, lun: Byte = 0): UsbDisk {
        val target = MassStorageDiscovery.findBotInterface(device)
            ?: throw DiskException.IoFailure("no SCSI BOT mass-storage interface on device")
        val channel = AndroidUsbBulkChannel.open(manager, target)
        val session = BotSessionImpl(channel, lun = lun)
        return try {
            // Optional TUR; ignore unit-attention once via sense then continue.
            runCatching {
                val tur = session.transfer(ScsiCommand.testUnitReady(), null)
                if (tur.status == CswStatus.FAILED) {
                    session.transfer(ScsiCommand.requestSense(18), java.nio.ByteBuffer.allocate(18))
                } else if (tur.status == CswStatus.PHASE_ERROR) {
                    session.bulkOnlyReset()
                }
            }
            UsbDisk(session)
        } catch (e: Exception) {
            session.close()
            throw e
        }
    }
}
