package usb.files

/**
 * Fail-closed gate after releasing [MountedFilesVolume] and before opening a raw/partition
 * disk session. Null means close succeeded (or nothing was open). Non-null must abort the
 * new operation so a half-closed volume cannot share the USB claim with a writer.
 *
 * Disconnect/detach cleanup must NOT use this — those paths swallow close errors on purpose.
 */
fun requireFilesVolumeClosed(closeError: Throwable?) {
    if (closeError != null) throw closeError
}
