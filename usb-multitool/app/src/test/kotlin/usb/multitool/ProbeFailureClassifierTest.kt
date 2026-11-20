package usb.multitool

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import usb.device.DiskException
import usb.device.UsbExclusiveClaim
import usb.files.EjectedException
import usb.ui.DevicePresence

class ProbeFailureClassifierTest {
    @Test
    fun busyClaimIsBusyNotDisconnected() {
        assertEquals(
            DevicePresence.Busy,
            ProbeFailureClassifier.presenceOf(UsbExclusiveClaim.BusyException()),
        )
        assertFalse(ProbeFailureClassifier.isTransportDisconnect(UsbExclusiveClaim.BusyException()))
    }

    @Test
    fun ejectedIsBusy() {
        assertEquals(DevicePresence.Busy, ProbeFailureClassifier.presenceOf(EjectedException()))
    }

    @Test
    fun securityExceptionIsPermissionRequired() {
        assertEquals(
            DevicePresence.PermissionRequired,
            ProbeFailureClassifier.presenceOf(SecurityException("no usb permission")),
        )
    }

    @Test
    fun onlyTransportClassesDisconnect() {
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(DiskException.Disconnected()),
        )
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(DiskException.CswReadFailure("csw")),
        )
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(DiskException.InvalidCsw("bad")),
        )
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(DiskException.NonMeaningfulCsw("nm")),
        )
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(DiskException.Closed()),
        )
        assertTrue(ProbeFailureClassifier.isTransportDisconnect(DiskException.Disconnected()))
    }

    @Test
    fun senseUnsupportedAndIoStayReady() {
        assertEquals(
            DevicePresence.Ready,
            ProbeFailureClassifier.presenceOf(DiskException.SenseError(0x70, 0x02, 0x3A, 0x00)),
        )
        assertEquals(
            DevicePresence.Ready,
            ProbeFailureClassifier.presenceOf(DiskException.UnsupportedCommand("no")),
        )
        assertEquals(
            DevicePresence.Ready,
            ProbeFailureClassifier.presenceOf(DiskException.IoFailure("READ CAPACITY(16) failed")),
        )
        assertFalse(
            ProbeFailureClassifier.isTransportDisconnect(
                DiskException.SenseError(0x70, 0x03, 0x11, 0x00),
            ),
        )
    }

    @Test
    fun ioFailureWrapperDoesNotHideBusyEjectedSecurityOrDisconnect() {
        // OperationExecutor.classifyThrown wraps non-DiskException in IoFailure(message, cause).
        assertEquals(
            DevicePresence.Busy,
            ProbeFailureClassifier.presenceOf(
                DiskException.IoFailure("op failed", UsbExclusiveClaim.BusyException()),
            ),
        )
        assertEquals(
            DevicePresence.Busy,
            ProbeFailureClassifier.presenceOf(
                DiskException.IoFailure("op failed", EjectedException()),
            ),
        )
        assertEquals(
            DevicePresence.PermissionRequired,
            ProbeFailureClassifier.presenceOf(
                DiskException.IoFailure("op failed", SecurityException("denied")),
            ),
        )
        assertEquals(
            DevicePresence.Disconnected,
            ProbeFailureClassifier.presenceOf(
                DiskException.IoFailure("op failed", DiskException.Disconnected()),
            ),
        )
    }

    @Test
    fun causeChainIsWalked() {
        val wrapped = IllegalStateException(
            "wrap",
            UsbExclusiveClaim.BusyException(),
        )
        assertEquals(DevicePresence.Busy, ProbeFailureClassifier.presenceOf(wrapped))
        val transportWrapped = RuntimeException(DiskException.InvalidCsw("sig"))
        assertEquals(DevicePresence.Disconnected, ProbeFailureClassifier.presenceOf(transportWrapped))
    }

    @Test
    fun nullAndUnknownStayReady() {
        assertEquals(DevicePresence.Ready, ProbeFailureClassifier.presenceOf(null))
        assertEquals(DevicePresence.Ready, ProbeFailureClassifier.presenceOf(IllegalArgumentException("x")))
    }
}
