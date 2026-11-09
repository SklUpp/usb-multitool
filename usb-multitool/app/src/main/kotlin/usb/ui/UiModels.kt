package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog

/**
 * Pure UI/domain models. No Android USB imports.
 * Compose screens and JVM tests share these types.
 */

data class DeviceIdentity(
    val label: String,
    val capacityBytes: ULong,
    /** USB serial when the OS exposes it; null/blank → resume refused. */
    val serialNumber: String? = null,
    /** Attach generation distinguishes same-model devices without serial. */
    val attachGeneration: Long = 0L,
) {
    /**
     * Stable identity for confirmations and rescue maps.
     * Prefers serial so two sticks of the same model do not collide.
     */
    val identity: String
        get() {
            val serial = serialNumber?.trim().orEmpty()
            return if (serial.isNotEmpty()) "$label#$serial" else label
        }

    /** True when a serial is present — required to resume a RESTORE map. */
    val hasStableSerial: Boolean
        get() = !serialNumber.isNullOrBlank()

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
    VENTOY_UPDATE,
    VENTOY_DOWNLOAD_INSTALL,
    RAW_WRITE,
    FORMAT_PARTITION,
    DELETE_PARTITION,
    CREATE_PARTITION,
    GPT_REPAIR,
    GPT_RESTORE,
    RESCUE_RESTORE,
    VENTOY_LINK_XML,
}

data class DestructiveConfirmation(
    val action: String,
    val identity: DeviceIdentity,
    val message: String,
    val formatId: FilesystemId? = null,
    val isoName: String? = null,
    val linkOverwrite: Boolean = false,
    val partitionId: String? = null,
    val partitionStartLba: ULong? = null,
    val partitionSectorCount: ULong? = null,
) {
    companion object {
        fun request(
            device: DeviceIdentity,
            action: DestructiveAction,
            targetPartition: String? = null,
            formatId: FilesystemId? = null,
            isoName: String? = null,
            linkOverwrite: Boolean = false,
            target: PartitionTab? = null,
        ): DestructiveConfirmation {
            val actionLabel = action.displayName()
            val targetSuffix = if (targetPartition != null) " (partition $targetPartition)" else ""
            val formatSuffix =
                if (action == DestructiveAction.FORMAT_PARTITION && formatId != null) {
                    " as ${FormatCatalog.label(formatId)}"
                } else {
                    ""
                }
            val rescueHint =
                if (action == DestructiveAction.RESCUE_RESTORE) {
                    " Restores onto the currently attached disk (${device.identity}). " +
                        "If the map names a different stick, that is a disk swap — " +
                        "all NON_TRIED gaps and FAILED sectors become zeros on this disk. " +
                        "Partial COPY maps are refused."
                } else {
                    ""
                }
            val linkHint =
                if (action == DestructiveAction.VENTOY_LINK_XML) {
                    val iso = isoName?.substringAfterLast('/') ?: "(ISO)"
                    if (linkOverwrite) {
                        " Overwrites existing autounattend.xml / ventoy.json link for $iso."
                    } else {
                        " Writes autounattend.xml and merges ventoy.json for $iso " +
                            "(refuses if a link already exists — confirm overwrite)."
                    }
                } else {
                    ""
                }
            val message =
                "Confirm $actionLabel$formatSuffix$targetSuffix on ${device.identity} (${device.capacityLabel}). " +
                    "This changes the USB device and cannot be undone from this app." + rescueHint + linkHint
            return DestructiveConfirmation(
                action = actionLabel,
                identity = device,
                message = message,
                formatId = if (action == DestructiveAction.FORMAT_PARTITION) formatId else null,
                isoName = if (action == DestructiveAction.VENTOY_LINK_XML) isoName else null,
                linkOverwrite = linkOverwrite,
                partitionId = target?.id,
                partitionStartLba = target?.startLba,
                partitionSectorCount = target?.sectorCount,
            )
        }
    }
}
fun DestructiveAction.displayName(): String =
    when (this) {
        DestructiveAction.WIPE -> "wipe"
        DestructiveAction.VENTOY_INSTALL -> "Ventoy install"
        DestructiveAction.VENTOY_UPDATE -> "Ventoy update (keeps the ISO partition)"
        DestructiveAction.VENTOY_DOWNLOAD_INSTALL -> "Ventoy download and install"
        DestructiveAction.RAW_WRITE -> "raw image write"
        DestructiveAction.FORMAT_PARTITION -> "format partition"
        DestructiveAction.DELETE_PARTITION -> "delete partition"
        DestructiveAction.CREATE_PARTITION -> "create partition"
        DestructiveAction.GPT_REPAIR -> "GPT backup repair"
        DestructiveAction.GPT_RESTORE -> "restore GPT backup"
        DestructiveAction.RESCUE_RESTORE -> "restore rescue image"
        DestructiveAction.VENTOY_LINK_XML -> "link Ventoy Windows setup XML"
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
    val filesystemId: FilesystemId? = null,
    val startLba: ULong? = null,
    val sectorCount: ULong? = null,
) {
    val filesystemLabel: String
        get() = when (filesystemId) {
            FilesystemId.FAT -> "FAT"
            FilesystemId.EXFAT -> "exFAT"
            FilesystemId.NTFS -> "NTFS"
            FilesystemId.EXT -> "EXT"
            null -> ""
        }
}

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
    /** Stage USB file into app filesDir, then queue raw write (Partitions confirm). */
    SendToRawWriter,
}

data class OperationProgress(
    val state: OperationState,
    val label: String,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val errorMessage: String? = null,
    /** Concrete outcome (paths, counts) shown instead of the bare "Success" label. */
    val resultMessage: String? = null,
    val cancelable: Boolean = true,
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
    val vtoyBypassTpm: Boolean = false,
    val vtoyBypassRam: Boolean = false,
    val vtoyLocalAccount: Boolean = false,
    val tableValid: Boolean = true,
    /** Absolute FAT path currently displayed; [files] are the entries of this directory. */
    val currentPath: String = "/",
    val files: List<FileEntry> = emptyList(),
    val operation: OperationProgress? = null,
    val pendingConfirmation: DestructiveConfirmation? = null,
    val linkOverwriteCandidate: String? = null,
    /** Device identity that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateIdentity: String? = null,
    val statusMessage: String? = null,
    /** SAF URI of the image queued for the next raw write. */
    val imageUri: String? = null,
    /**
     * Absolute path under app filesDir/staging for a USB→raw staging copy.
     * Preferred over [imageUri] when set. Cleared after apply/cancel + file delete.
     */
    val stagedRawImagePath: String? = null,
    val stagedRawImageLabel: String? = null,
    val backupUri: String? = null,
    /** SAF URI of a dense rescue .img chosen for restore. */
    val rescueImageUri: String? = null,
    /** SAF URI of the matching .map (v2 RESTORE or converted from COPY). */
    val rescueMapUri: String? = null,
    /** User-selected target FS for the next Format action (picker). */
    val selectedFormatId: FilesystemId = FilesystemId.FAT,
    /** Dark theme preference: null = system default, true = dark, false = light. */
    val darkTheme: Boolean? = null,
    /**
     * Bounded operation log. Ring-buffered to [LOG_MAX_LINES]; oldest lines dropped.
     * Appended by [UiIntent.AppendLog]; never cleared across operations so the user
     * can scroll back after completion.
     */
    val logLines: List<String> = emptyList(),
    /**
     * Latest Ventoy release tag fetched from GitHub API at startup (null until checked).
     * Shown in [VentoyScreen] alongside [VentoyAssets.PINNED_RELEASE].
     */
    val latestVentoyRelease: String? = null,
) {
    companion object { const val LOG_MAX_LINES = 200 }
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

/** Normalize a FAT path: leading slash, no trailing slash, root is "/". */
fun normalizeFatPath(path: String): String {
    val trimmed = path.trim().trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed"
}

/** Absolute FAT path of [name] inside directory [parent]; root is "/". */
fun fatChildPath(parent: String, name: String): String {
    val base = parent.trim().trim('/')
    val leaf = name.trim().trim('/')
    return when {
        leaf.isEmpty() -> if (base.isEmpty()) "/" else "/$base"
        base.isEmpty() -> "/$leaf"
        else -> "/$base/$leaf"
    }
}

/** Parent directory of absolute [path]; the root has no parent. */
fun fatParentPath(path: String): String {
    val trimmed = path.trim().trim('/')
    val cut = trimmed.lastIndexOf('/')
    return if (cut <= 0) "/" else "/" + trimmed.substring(0, cut)
}
