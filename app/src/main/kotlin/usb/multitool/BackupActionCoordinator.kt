package usb.multitool

import android.hardware.usb.UsbDevice
import android.net.Uri
import usb.device.UsbPermissionPolicy
import usb.files.AttachmentChangedException
import usb.files.AttachmentState
import usb.ui.AppUiState
import usb.ui.BackupFolderAction
import usb.ui.PendingBackupFolderAction
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey

/**
 * Folder-gated backup dispatch: pin the live attachment when the user asks for a create,
 * and consume that pin inside the pre-open hook. The immediate path keeps the existing
 * serial-less behavior; picker-gated dispatches always pin generation/key/serial from the
 * same snapshot the disk is opened for.
 */
internal class BackupActionCoordinator(
    private val attachment: () -> AttachmentState.Snapshot<UsbDevice>,
    private val state: () -> AppUiState,
    private val safBackup: SafBackupCoordinator,
    private val intents: (UiIntent) -> Unit,
    private val logFolderLost: (BackupFolderAction) -> Unit,
) {
    private data class BackupPin(
        val generation: Long,
        val key: String,
        val serial: String?,
    )

    /** Attachment the next folder-gated backup was chosen for; consumed before open. */
    @Volatile
    private var expectedPin: BackupPin? = null

    /** True when the persisted write grant still exists; the provider is checked on write. */
    fun hasUsableFolder(): Boolean = safBackup.hasUsableBackupFolder(state())

    /**
     * Create entry point for the rescue image and the GPT copy. Without a live persisted
     * grant it republishes the loss (Diagnostics then offers Settings) and starts nothing:
     * the SAF picker belongs to Settings, never to a create click. With a grant the usual
     * pinned dispatch runs; [requirePinned] still re-checks it right before I/O.
     */
    fun startCreate(action: BackupFolderAction) {
        if (hasUsableFolder()) {
            request(action)
            return
        }
        logFolderLost(action)
        intents(
            UiIntent.SetBackupFolder(
                uri = state().backupFolderUri,
                name = state().backupFolderName,
                usable = false,
            ),
        )
    }

    /** Persist the user's tree choice. */
    fun onFolderPicked(uri: Uri): Boolean = safBackup.onFolderPicked(uri)

    /**
     * Immediate path (folder already chosen): pin the live attachment now. A serial-less
     * device keeps its existing behavior here — [BackupPin.serial] stays null and the
     * pre-open hook checks generation/key only; the operation's own serial guard still
     * applies. Picker-gated dispatches never take this path.
     */
    fun request(action: BackupFolderAction) {
        val live = attachment()
        val device = live.device
        val key = device?.let { UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
        if (key == null) {
            expectedPin = null
            intents(UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged)))
            return
        }
        expectedPin =
            BackupPin(
                generation = live.generation,
                key = key,
                serial = device?.let { UsbDeviceIdentity.serialNumber(it) }?.trim()?.takeIf {
                    it.isNotEmpty()
                },
            )
        dispatch(action)
    }

    /**
     * Picker path: verify that dispatch-time live attachment is still that exact target,
     * then pin generation/key/serial from this one snapshot. All three values travel
     * together; the pre-open hook checks the same snapshot the disk is opened for.
     */
    fun requestPinned(action: BackupFolderAction, expected: PendingBackupFolderAction) {
        if (action == BackupFolderAction.ChangeFolder) {
            expectedPin = null
            return
        }
        val live = attachment()
        val device = live.device
        val key = device?.let { UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
        val serial =
            device?.let { UsbDeviceIdentity.serialNumber(it) }?.trim().orEmpty()
        val identity = device?.let { UsbDeviceIdentity.identityString(it) }
        if (key == null ||
            key != expected.deviceKey ||
            identity != expected.identity ||
            serial.isEmpty() ||
            serial != expected.serialNumber
        ) {
            expectedPin = null
            intents(
                UiIntent.Status(
                    UiMessage.Key(
                        if (serial.isEmpty()) {
                            UiMessageKey.BackupFolderTargetUnverifiable
                        } else {
                            UiMessageKey.BackupFolderTargetChanged
                        },
                    ),
                ),
            )
            return
        }
        expectedPin = BackupPin(live.generation, key, serial)
        dispatch(action)
    }

    /**
     * Pre-open hook: [pinned] is the same attachment snapshot `openRawDisk` opens, so the
     * device checked here is exactly the device that would be opened. Consumes the pin and
     * fails closed on any mismatch. A null serial means the immediate path deliberately
     * keeps the old serial-less behavior; picker dispatches always pin one.
     */
    fun requirePinned(pinned: AttachmentState.Snapshot<UsbDevice>) {
        val expected = expectedPin
        expectedPin = null
        val device = pinned.device ?: throw AttachmentChangedException()
        val key = UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        val serial = UsbDeviceIdentity.serialNumber(device)?.trim().orEmpty()
        if (expected == null ||
            pinned.generation != expected.generation ||
            key != expected.key ||
            (expected.serial != null &&
                (serial.isEmpty() || serial != expected.serial))
        ) {
            throw AttachmentChangedException()
        }
    }

    private fun dispatch(action: BackupFolderAction) {
        when (action) {
            BackupFolderAction.RescueBackup -> intents(UiIntent.StartRescueBackup)
            BackupFolderAction.GptCopy -> intents(UiIntent.CaptureGptBackup)
            BackupFolderAction.ChangeFolder -> Unit
        }
    }
}
