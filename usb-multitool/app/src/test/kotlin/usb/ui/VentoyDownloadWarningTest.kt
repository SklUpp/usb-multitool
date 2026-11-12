package usb.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VentoyDownloadWarningTest {

    @Test
    fun warnsOnlyBeforeDownloadingANewRelease() {
        assertTrue(
            VentoyDownloadWarning.requiresConfirmation(
                DestructiveAction.VENTOY_DOWNLOAD_INSTALL,
            ),
        )
        assertFalse(VentoyDownloadWarning.requiresConfirmation(DestructiveAction.VENTOY_INSTALL))
        assertFalse(VentoyDownloadWarning.requiresConfirmation(DestructiveAction.VENTOY_UPDATE))
        assertFalse(VentoyDownloadWarning.requiresConfirmation(DestructiveAction.WIPE))
    }
}
