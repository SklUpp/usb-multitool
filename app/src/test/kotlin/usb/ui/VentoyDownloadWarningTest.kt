package usb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VentoyDownloadWarningTest {
    @Test
    fun warnsOnlyBeforeANewDownloadStarts() {
        assertTrue(VentoyDownloadWarning.requiresConfirmation(downloadInFlight = false))
        assertFalse(VentoyDownloadWarning.requiresConfirmation(downloadInFlight = true))
    }
}
