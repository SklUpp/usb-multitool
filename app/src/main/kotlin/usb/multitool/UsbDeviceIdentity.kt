package usb.multitool

import android.hardware.usb.UsbDevice
import usb.ui.DeviceIdentity

/**
 * Maps [UsbDevice] fields into pure [DeviceIdentity] without embedding Android types
 * in UI models. Label, serial, identity string, capacity, and attachGeneration rules
 * live on [DeviceIdentity]; this only reads the platform device safely.
 */
internal object UsbDeviceIdentity {
    fun label(device: UsbDevice): String =
        DeviceIdentity.labelOf(device.productName, device.deviceName)

    /** Null when the OS throws or returns blank-only after trim is still stored raw. */
    fun serialNumber(device: UsbDevice): String? =
        runCatching { device.serialNumber }.getOrNull()

    fun of(
        device: UsbDevice,
        capacityBytes: ULong = 0uL,
        attachGeneration: Long = 0L,
    ): DeviceIdentity =
        DeviceIdentity.of(
            label = label(device),
            capacityBytes = capacityBytes,
            serialNumber = serialNumber(device),
            attachGeneration = attachGeneration,
        )

    /**
     * Live confirmation string with fail-closed serial read: [UsbDevice.getSerialNumber]
     * exceptions propagate. Use only on trust boundaries (e.g. Ventoy link match).
     * Format still [DeviceIdentity.identityString] — only the read policy differs from [of].
     */
    fun identityStringFailClosed(device: UsbDevice): String =
        DeviceIdentity.identityString(label(device), device.serialNumber)

    /** Soft identity string (serial via [serialNumber]); not for trust-boundary match. */
    fun identityString(device: UsbDevice): String = of(device).identity
}
