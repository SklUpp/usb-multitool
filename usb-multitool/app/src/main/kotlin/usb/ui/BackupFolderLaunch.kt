package usb.ui

/**
 * Work that fires as soon as the shared SAF backup folder is chosen.
 *
 * [ChangeFolder] is the manual "change folder" path: it rebinds the folder but starts no
 * operation. [RescueBackup] and [GptCopy] write to the attached stick and must be pinned.
 */
enum class BackupFolderAction {
    RescueBackup,
    GptCopy,
    ChangeFolder,
}

/**
 * One backup-folder pick in flight. The ActivityResult contract carries no request code,
 * so the exact action and the attachment it belongs to travel here instead.
 *
 * [sessionToken] is minted per ViewModel (one process session). A context restored after
 * process death keeps the old token and is therefore never accepted by the new process.
 */
data class PendingBackupFolderAction(
    val action: BackupFolderAction,
    /** Null only for [BackupFolderAction.ChangeFolder]: a write action must name a device. */
    val identity: String?,
    val attachGeneration: Long,
    /** `deviceId:deviceName`; distinguishes same-model replugs before attach state refreshes. */
    val deviceKey: String?,
    /** Non-blank USB serial for write actions: the only observable same-key replug proof. */
    val serialNumber: String?,
    val sessionToken: String,
) {
    /**
     * True when the live attachment is still the one this action was chosen for. A null
     * identity/key/serial never matches a backup action; a manual change has no target.
     */
    fun accepts(
        identity: String?,
        attachGeneration: Long,
        deviceKey: String?,
        serialNumber: String?,
    ): Boolean =
        when (action) {
            BackupFolderAction.ChangeFolder -> true
            BackupFolderAction.RescueBackup,
            BackupFolderAction.GptCopy ->
                this.identity != null &&
                this.deviceKey != null &&
                this.serialNumber != null &&
                this.identity == identity &&
                this.attachGeneration == attachGeneration &&
                this.deviceKey == deviceKey &&
                this.serialNumber == serialNumber
        }
}

/** Result of asking the queue to start a folder pick; busy/no-target are explicit. */
sealed interface BackupFolderLaunch {
    data class Starting(val pending: PendingBackupFolderAction) : BackupFolderLaunch

    /** A picker (or a resumed action) is already in flight. */
    data object Busy : BackupFolderLaunch

    /** A backup action without an attached device cannot be pinned to a target. */
    data object NoTarget : BackupFolderLaunch

    /** A backup action has no USB serial, so a same-key replug cannot be ruled out. */
    data object Unverifiable : BackupFolderLaunch
}

/**
 * Exactly one pending backup-folder action per Activity. Fail closed:
 *
 * - [begin] records the pinned target (identity, generation, device key, serial) before the
 *   system picker opens and rejects a second launch, so a double tap cannot replace it;
 *   a write action without a serial is [BackupFolderLaunch.Unverifiable], never silent;
 * - [take] consumes the context only for the process session that created it, so a late
 *   result after process death cannot start work;
 * - [accepts] is checked at the callback, again before dispatch, and again before the disk
 *   is opened, so a stick swapped while the picker was open cannot receive the backup.
 */
class BackupFolderLaunchQueue(private val sessionToken: String) {
    var pending: PendingBackupFolderAction? = null
        private set

    /** Records [action] before its picker starts; never replaces an existing context. */
    fun begin(
        action: BackupFolderAction,
        identity: String?,
        attachGeneration: Long,
        deviceKey: String?,
        serialNumber: String?,
    ): BackupFolderLaunch {
        if (pending != null) return BackupFolderLaunch.Busy
        val serial = serialNumber?.trim()?.takeIf { it.isNotEmpty() }
        if (action != BackupFolderAction.ChangeFolder) {
            if (identity == null || deviceKey == null) return BackupFolderLaunch.NoTarget
            if (serial == null) return BackupFolderLaunch.Unverifiable
        }
        val context =
            PendingBackupFolderAction(
                action = action,
                identity = identity,
                attachGeneration = attachGeneration,
                deviceKey = deviceKey,
                serialNumber = serial,
                sessionToken = sessionToken,
            )
        pending = context
        return BackupFolderLaunch.Starting(context)
    }

    /**
     * Consume the pending context. A context from another process session is dropped and
     * never returned, even if restore state was forged or replayed.
     */
    fun take(): PendingBackupFolderAction? {
        val context = pending ?: return null
        pending = null
        return context.takeIf { it.sessionToken == sessionToken }
    }

    /**
     * Restore a context saved across a configuration change. False means it belonged to a
     * dead process and was dropped; the caller must not launch the saved action.
     */
    fun restore(context: PendingBackupFolderAction): Boolean {
        if (context.sessionToken != sessionToken) return false
        if (context.action != BackupFolderAction.ChangeFolder) {
            val serial = context.serialNumber?.trim()
            if (context.identity == null ||
                context.deviceKey == null ||
                serial.isNullOrEmpty() ||
                serial != context.serialNumber
            ) {
                return false
            }
        }
        if (pending != null) return false
        pending = context
        return true
    }

    /** User explicitly cancelled or started a manual folder change. */
    fun cancel() {
        pending = null
    }
}
