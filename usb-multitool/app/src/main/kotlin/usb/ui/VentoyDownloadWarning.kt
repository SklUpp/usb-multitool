package usb.ui

/**
 * Release download is the only Ventoy action that starts network traffic and then
 * rewrites the stick. Installing from assets that are already present must not show
 * this pre-download warning.
 */
object VentoyDownloadWarning {
    fun requiresConfirmation(action: DestructiveAction): Boolean =
        action == DestructiveAction.VENTOY_DOWNLOAD_INSTALL
}
