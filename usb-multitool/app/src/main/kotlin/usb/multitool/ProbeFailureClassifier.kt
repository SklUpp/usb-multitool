package usb.multitool

import usb.device.DiskException
import usb.device.UsbExclusiveClaim
import usb.files.EjectedException
import usb.ui.DevicePresence

/**
 * Maps probe/refresh failures to [DevicePresence].
 *
 * Full cause-chain scan with priority (first match wins while walking root→cause):
 * BusyException/EjectedException → Busy;
 * SecurityException → PermissionRequired;
 * Disconnected/CSW/InvalidCsw/NonMeaningfulCsw/Closed → Disconnected;
 * after the whole chain, Sense/Unsupported/IoFailure/other DiskException/unknown → Ready.
 *
 * [DiskException.IoFailure] wrappers must not short-circuit: OperationExecutor wraps
 * non-DiskException failures as IoFailure(message, cause).
 */
internal object ProbeFailureClassifier {
    fun presenceOf(failure: Throwable?): DevicePresence {
        var t = failure
        while (t != null) {
            when (t) {
                is UsbExclusiveClaim.BusyException,
                is EjectedException -> return DevicePresence.Busy
                is SecurityException -> return DevicePresence.PermissionRequired
                is DiskException.Disconnected,
                is DiskException.CswReadFailure,
                is DiskException.InvalidCsw,
                is DiskException.NonMeaningfulCsw,
                is DiskException.Closed -> return DevicePresence.Disconnected
                // Sense / Unsupported / IoFailure / other DiskException / unknown:
                // keep walking so a wrapped Busy/Disconnected cause still wins.
                else -> Unit
            }
            t = t.cause
        }
        return DevicePresence.Ready
    }

    fun isTransportDisconnect(failure: Throwable?): Boolean =
        presenceOf(failure) == DevicePresence.Disconnected
}
