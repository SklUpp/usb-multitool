package usb.device

import android.hardware.usb.UsbDevice
object UsbPermissionPolicy {
    fun key(deviceId: Int, deviceName: String): String = "$deviceId:$deviceName"

    fun accepts(pendingKey: String?, callbackKey: String?, currentKey: String?): Boolean =
        pendingKey != null && pendingKey == callbackKey && callbackKey == currentKey
    fun accepts(
        pendingKey: String?,
        callbackKey: String?,
        currentKey: String?,
        pendingToken: String?,
        callbackToken: String?,
    ): Boolean =
        accepts(pendingKey, callbackKey, currentKey) &&
            pendingToken != null && pendingToken == callbackToken

    fun currentMatch(requested: UsbDevice?, devices: Collection<UsbDevice>): UsbDevice? =
        requested?.let { wanted -> devices.firstOrNull { it.deviceId == wanted.deviceId && it.deviceName == wanted.deviceName } }
}
