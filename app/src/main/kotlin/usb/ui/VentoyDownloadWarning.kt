package usb.ui

/**
 * Release download is the only Ventoy action that starts network traffic. Installing or
 * updating from bytes that are already stored must not show this pre-download warning;
 * the stick is written only by the separately confirmed Install/Update action.
 */
object VentoyDownloadWarning {
    /** Ask before every new download; a download already in flight is never asked again. */
    fun requiresConfirmation(downloadInFlight: Boolean): Boolean = !downloadInFlight
}
