package usb.ui

/**
 * Pure UI/domain models. No Android USB imports.
 * Compose screens and JVM tests share these types.
 */

data class DeviceIdentity(
    val label: String,
    val capacityBytes: ULong,
) {
    /** Stable identity string for confirmations and logs. */
    val identity: String get() = label

    val capacityLabel: String get() = formatCapacity(capacityBytes)

    companion object {
        fun formatCapacity(bytes: ULong): String {
            if (bytes < 1024uL) return "$bytes B"
            val kib = bytes.toDouble() / 1024.0
            if (kib < 1024.0) return String.format("%.1f KiB", kib)
            val mib = kib / 1024.0
            if (mib < 1024.0) return String.format("%.1f MiB", mib)
            val gib = mib / 1024.0
            if (gib < 1024.0) return String.format("%.2f GiB", gib)
            val tib = gib / 1024.0
            return String.format("%.2f TiB", tib)
        }
    }
}

enum class OperationState {
    Queued,
    Running,
    Completed,
    Cancelled,
    Failed,
    Partial,
}

/** Device presence / permission / link lifecycle for the shell. */
enum class DevicePresence {
    Absent,
    PermissionRequired,
    Ready,
    Busy,
    Disconnected,
}

enum class DestructiveAction {
    WIPE,
    VENTOY_INSTALL,
    RAW_WRITE,
    FORMAT_PARTITION,
    DELETE_PARTITION,
    CREATE_PARTITION,
    GPT_REPAIR,
    BACKUP_RESTORE,
    FAKE_CAPACITY,
    WRITE_BENCHMARK,
}

data class DestructiveConfirmation(
    val action: String,
    val identity: DeviceIdentity,
    val message: String,
) {
    companion object {
        fun request(device: DeviceIdentity, action: DestructiveAction): DestructiveConfirmation {
            val actionLabel = action.displayName()
            val message =
                "Confirm $actionLabel on ${device.identity} (${device.capacityLabel}). " +
                    "This changes the USB device and cannot be undone from this app."
            return DestructiveConfirmation(
                action = actionLabel,
                identity = device,
                message = message,
            )
        }
    }
}

fun DestructiveAction.displayName(): String =
    when (this) {
        DestructiveAction.WIPE -> "wipe"
        DestructiveAction.VENTOY_INSTALL -> "Ventoy install"
        DestructiveAction.RAW_WRITE -> "raw image write"
        DestructiveAction.FORMAT_PARTITION -> "format partition"
        DestructiveAction.DELETE_PARTITION -> "delete partition"
        DestructiveAction.CREATE_PARTITION -> "create partition"
        DestructiveAction.GPT_REPAIR -> "GPT backup repair"
        DestructiveAction.BACKUP_RESTORE -> "restore from backup"
        DestructiveAction.FAKE_CAPACITY -> "fake-capacity probe"
        DestructiveAction.WRITE_BENCHMARK -> "write throughput benchmark"
    }

enum class ShellTab {
    Files,
    Ventoy,
    Partitions,
    Diagnostics,
}

data class PartitionTab(
    val id: String,
    val name: String,
    val readOnly: Boolean,
    val isVtoyEfi: Boolean = false,
)

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: ULong,
) {
    val isIso: Boolean
        get() = !isDirectory && name.endsWith(".iso", ignoreCase = true)
}

enum class IsoAction {
    LinkXml,
    SendToRawWriter,
}

data class OperationProgress(
    val state: OperationState,
    val label: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val errorMessage: String? = null,
) {
    val fraction: Float
        get() =
            if (bytesTotal <= 0L) {
                0f
            } else {
                (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
            }
}

data class AppUiState(
    val presence: DevicePresence = DevicePresence.Absent,
    val device: DeviceIdentity? = null,
    val tab: ShellTab = ShellTab.Files,
    val partitions: List<PartitionTab> = emptyList(),
    val selectedPartitionId: String? = null,
    val vtoyEfiWriteEnabled: Boolean = false,
    val files: List<FileEntry> = emptyList(),
    val operation: OperationProgress? = null,
    val pendingConfirmation: DestructiveConfirmation? = null,
    val statusMessage: String? = null,
    /** SAF URI of the image queued for the next raw write. */
    val imageUri: String? = null,
) {
    val actionsEnabled: Boolean
        get() =
            presence == DevicePresence.Ready &&
                operation?.state != OperationState.Running &&
                operation?.state != OperationState.Queued &&
                pendingConfirmation == null

    val isRunning: Boolean
        get() = operation?.state == OperationState.Running

    fun selectedPartition(): PartitionTab? =
        partitions.firstOrNull { it.id == selectedPartitionId } ?: partitions.firstOrNull()
}
