package usb.device

import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.hardware.usb.UsbInterface
import android.hardware.usb.UsbManager
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

data class MassStorageInterface(
    val device: UsbDevice,
    val usbInterface: UsbInterface,
    val inEndpoint: UsbEndpoint,
    val outEndpoint: UsbEndpoint,
)

object MassStorageDiscovery {
    private const val CLASS_MASS_STORAGE = UsbConstants.USB_CLASS_MASS_STORAGE
    private const val SUBCLASS_SCSI = 0x06
    private const val PROTOCOL_BOT = 0x50

    /** Find first SCSI transparent / Bulk-Only interface on [device]. */
    fun findBotInterface(device: UsbDevice): MassStorageInterface? {
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            if (intf.interfaceClass != CLASS_MASS_STORAGE) continue
            if (intf.interfaceSubclass != SUBCLASS_SCSI) continue
            if (intf.interfaceProtocol != PROTOCOL_BOT) continue
            var inn: UsbEndpoint? = null
            var out: UsbEndpoint? = null
            for (e in 0 until intf.endpointCount) {
                val ep = intf.getEndpoint(e)
                if (ep.type != UsbConstants.USB_ENDPOINT_XFER_BULK) continue
                when (ep.direction) {
                    UsbConstants.USB_DIR_IN -> if (inn == null) inn = ep
                    UsbConstants.USB_DIR_OUT -> if (out == null) out = ep
                }
            }
            if (inn != null && out != null) {
                return MassStorageInterface(device, intf, inn, out)
            }
        }
        return null
    }
}

/**
 * Owns one [UsbDeviceConnection] and claims the BOT interface with force=true.
 * All bulkTransfer calls loop until [length] bytes move or the pipe fails.
 */
class AndroidUsbBulkChannel private constructor(
    private val connection: UsbDeviceConnection,
    private val usbInterface: UsbInterface,
    private val inEndpoint: UsbEndpoint,
    private val outEndpoint: UsbEndpoint,
) : UsbBulkChannel {
    private val closed = AtomicBoolean(false)

    override val interfaceId: Int get() = usbInterface.id

    override fun bulkIn(destination: ByteBuffer, length: Int, timeoutMs: Int): Int =
        bulkTransfer(inEndpoint, destination, length, timeoutMs)

    override fun bulkOut(source: ByteBuffer, length: Int, timeoutMs: Int): Int =
        bulkTransfer(outEndpoint, source, length, timeoutMs)

    override fun controlTransfer(
        requestType: Int,
        request: Int,
        value: Int,
        index: Int,
        buffer: ByteArray?,
        length: Int,
    ): Int {
        ensureOpen()
        return connection.controlTransfer(
            requestType,
            request,
            value,
            index,
            buffer,
            length,
            BotConstants.TRANSFER_TIMEOUT_MS,
        )
    }

    override fun clearHaltIn() = clearHalt(inEndpoint)

    override fun clearHaltOut() = clearHalt(outEndpoint)

    override fun isConnected(): Boolean = !closed.get()

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        runCatching { connection.releaseInterface(usbInterface) }
        connection.close()
    }

    private fun clearHalt(endpoint: UsbEndpoint) {
        ensureOpen()
        val result = connection.controlTransfer(
            BotConstants.REQUEST_TYPE_ENDPOINT_OUT,
            BotConstants.CLEAR_FEATURE,
            BotConstants.ENDPOINT_HALT,
            endpoint.address,
            null,
            0,
            BotConstants.TRANSFER_TIMEOUT_MS,
        )
        if (result < 0) {
            throw DiskException.IoFailure(
                "Clear Feature HALT failed on ep 0x${endpoint.address.toString(16)}",
            )
        }
    }

    private fun bulkTransfer(
        endpoint: UsbEndpoint,
        buffer: ByteBuffer,
        length: Int,
        timeoutMs: Int,
    ): Int {
        ensureOpen()
        require(length >= 0)
        require(buffer.remaining() >= length) {
            "buffer remaining ${buffer.remaining()} < requested $length"
        }
        if (length == 0) return 0

        val array: ByteArray
        val baseOffset: Int
        if (buffer.hasArray()) {
            array = buffer.array()
            baseOffset = buffer.arrayOffset() + buffer.position()
        } else {
            array = ByteArray(length)
            baseOffset = 0
            if (endpoint.direction == UsbConstants.USB_DIR_OUT) {
                val dup = buffer.duplicate()
                dup.limit(dup.position() + length)
                dup.get(array)
            }
        }

        var transferred = 0
        while (transferred < length) {
            val chunk = minOf(length - transferred, BotConstants.MAX_BULK_REQUEST)
            val n = connection.bulkTransfer(
                endpoint,
                array,
                baseOffset + transferred,
                chunk,
                timeoutMs,
            )
            if (n < 0) {
                throw DiskException.IoFailure(
                    "bulkTransfer failed on ep 0x${endpoint.address.toString(16)}",
                )
            }
            if (n == 0) {
                throw DiskException.IoFailure("bulkTransfer returned 0 bytes")
            }
            transferred += n
            // A short bulk IN packet ends the BOT data phase. The next IN
            // URB would read the 13-byte CSW into the caller's buffer.
            if (endpoint.direction == UsbConstants.USB_DIR_IN && n < chunk) break
        }

        if (!buffer.hasArray() && endpoint.direction == UsbConstants.USB_DIR_IN) {
            buffer.put(array, 0, transferred)
        } else {
            buffer.position(buffer.position() + transferred)
        }
        return transferred
    }

    private fun ensureOpen() {
        if (closed.get()) throw DiskException.Closed()
    }

    companion object {
        fun open(manager: UsbManager, target: MassStorageInterface): AndroidUsbBulkChannel {
            val connection = manager.openDevice(target.device)
                ?: throw DiskException.IoFailure("UsbManager.openDevice returned null")
            if (!connection.claimInterface(target.usbInterface, true)) {
                connection.close()
                throw DiskException.IoFailure("claimInterface(force=true) failed")
            }
            return AndroidUsbBulkChannel(
                connection,
                target.usbInterface,
                target.inEndpoint,
                target.outEndpoint,
            )
        }
    }
}
