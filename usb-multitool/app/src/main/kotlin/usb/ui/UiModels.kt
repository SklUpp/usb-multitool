package usb.ui

import usb.fs.FilesystemId
import usb.fs.FormatCatalog
import kotlin.math.roundToLong

/**
 * Pure UI/domain models. No Android USB imports.
 * Compose screens and JVM tests share these types.
 */

data class DeviceIdentity(
    val label: String,
    val capacityBytes: ULong,
    /** USB serial when the OS exposes it; null/blank → RESTORE resume refused. */
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

/** Authored, translatable text. [Raw] holds system or engine text and is shown unchanged. */
sealed interface UiMessage {
    data class Key(
        val key: UiMessageKey,
        val args: List<Any> = emptyList(),
    ) : UiMessage

    data class Raw(val text: String) : UiMessage
}

enum class UiMessageKey {
    NoDevice,
    PermissionRequired,
    DeviceBusy,
    DeviceDisconnected,
    Ready,
    WaitForOperation,
    FormatUnsupported,
    InvalidFreeRegion,
    NoIsoForLink,
    NoDeviceForLink,
    LinkMismatch,
    LinkOverwriteNeeded,
    EfiPartitionMissing,
    EfiGeometryUnknown,
    StagingFile,
    CannotChangeStaged,
    StagedImageReady,
    CannotClearStaged,
    StagedCleared,
    OperationInProgress,
    SelfTestStarting,
    InvalidFolderName,
    SelectEntryToDelete,
    SelectFileToDownload,
    SelectPartitionFirst,
    ReadOnlyPartition,
    SelectedFileMissing,
    ImageCleared,
    ImageReady,
    WritingXml,
    BackupCleared,
    BackupReady,
    RescueImageCleared,
    RescueImageReady,
    RescueMapCleared,
    RescueMapReady,
    ChooseGptBackup,
    ChooseRescueFiles,
    SelectImageFirst,
    CorruptTableCreate,
    Probing,
    NoPartitionsFound,
    ProbeCancelled,
    ProbeFailed,
    SavedFile,
    UploadCleanupFailed,
    StagingLeftovers,
    StaleStagedImage,
    CannotDeleteStaging,
    CannotStage,
    CreatedFolder,
    DeletedPath,
    UploadedFile,
    CannotOpenUsb,
    VolumeSyncUnconfirmed,
    DeleteStagingFailed,
    InvalidEntryName,
    RenameSameName,
    RenamedPath,
    TargetChanged,
    TableDamaged,
    PartitionGeometryUnknown,
    GptPrimaryDamaged,
    PartitionTableAmbiguous,
    CannotReadUsb,
    CannotReadPartitionTable,
    Success,
    CancelledStatus,
    FailedStatus,
    PartialStatus,
    RunningStatus,
    QueuedStatus,
}

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
    SET_PARTITION_ROLE,
    SET_PARTITION_BOOTABLE,
}

data class DestructiveConfirmation(
    val action: DestructiveAction,
    val identity: DeviceIdentity,
    val targetPartitionName: String? = null,
    val formatId: FilesystemId? = null,
    val isoName: String? = null,
    val linkOverwrite: Boolean = false,
    val partitionId: String? = null,
    val partitionStartLba: ULong? = null,
    val partitionSectorCount: ULong? = null,
    val createStartLba: ULong? = null,
    val createSectorCount: ULong? = null,
    val partitionRole: PartitionRole? = null,
    val partitionBootable: Boolean? = null,
) {
    companion object {
        fun request(
            device: DeviceIdentity,
            action: DestructiveAction,
            targetPartitionName: String? = null,
            formatId: FilesystemId? = null,
            isoName: String? = null,
            linkOverwrite: Boolean = false,
            target: PartitionTab? = null,
        ): DestructiveConfirmation =
            DestructiveConfirmation(
                action = action,
                identity = device,
                targetPartitionName = targetPartitionName,
                formatId = if (action == DestructiveAction.FORMAT_PARTITION) formatId else null,
                isoName = if (action == DestructiveAction.VENTOY_LINK_XML) isoName else null,
                linkOverwrite = linkOverwrite,
                partitionId = target?.id,
                partitionStartLba = target?.startLba,
                partitionSectorCount = target?.sectorCount,
            )
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
        DestructiveAction.SET_PARTITION_ROLE -> "change partition type"
        DestructiveAction.SET_PARTITION_BOOTABLE -> "change bootable flag"
    }

/** Persisted UI language. [System] follows the device locale. */
/** Boot role the user can assign to a partition; maps to BASIC or EFI on disk. */
enum class PartitionRole {
    Data,
    Efi,
}

enum class AppLanguage {
    System,
    Russian,
    English,
}

enum class ShellTab {
    Files,
    Ventoy,
    Partitions,
    Diagnostics,
}

/** Exact target captured when a file mutation is requested. */
data class PartitionBinding(
    val device: DeviceIdentity,
    val partitionId: String,
    val startLba: ULong?,
    val sectorCount: ULong?,
)

data class PartitionTab(
    val id: String,
    val name: String,
    /**
     * Conservative write protection: real ESP type, EFI type byte/GUID, or the VTOYEFI
     * name. Independent of the current on-disk type; not a mount mode.
     */
    val readOnly: Boolean,
    /** Real ESP type on disk (GPT ESP GUID or MBR 0xEF), not a name-based guess. */
    val isEfiType: Boolean = false,
    val filesystemId: FilesystemId? = null,
    val startLba: ULong? = null,
    val sectorCount: ULong? = null,
    /** Legacy BIOS bootable flag as stored on disk (MBR 0x80 / GPT attribute bit 2). */
    val bootable: Boolean = false,
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

data class FreeRegionUi(val startLba: ULong, val sectorCount: ULong)

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
    val language: AppLanguage = AppLanguage.System,
    val partitions: List<PartitionTab> = emptyList(),
    val freeRegions: List<FreeRegionUi> = emptyList(),
    val diskSectorSize: Int = 512,
    val selectedPartitionId: String? = null,
    /**
     * Partition ID of the one EFI system partition whose write protection is lifted for
     * the current attachment. Null means every EFI partition stays protected.
     * Not a mount mode: it never changes how a volume is opened or mounted.
     * Cleared when the device is replaced, re-attached, detached, or the partition list
     * stops containing that same EFI partition.
     */
    val vtoyEfiWritePartitionId: String? = null,
    /** Geometry of the overridden EFI partition; the override never follows an ID alone. */
    val vtoyEfiWriteStartLba: ULong? = null,
    val vtoyEfiWriteSectorCount: ULong? = null,
    /**
     * Single official VTOY_WIN11_BYPASS_CHECK control: TPM, RAM, Secure Boot and CPU checks.
     * Null leaves the existing ventoy.json value untouched; true writes 1; false writes 0.
     */
    val vtoyBypassChecks: Boolean? = null,
    /** Null leaves the existing VTOY_WIN11_BYPASS_LOCAL_ACCOUNT value untouched. */
    val vtoyLocalAccount: Boolean? = null,
    val tableValid: Boolean = true,
    /** GPT primary header/entries were invalid; data came from the backup GPT. */
    val partitionTableDamaged: Boolean = false,
    /** MBR and GPT both describe partitions; every layout mutation stays blocked. */
    val partitionTableAmbiguous: Boolean = false,
    /** Absolute FAT path currently displayed; [files] are the entries of this directory. */
    val currentPath: String = "/",
    val files: List<FileEntry> = emptyList(),
    /**
     * Real directory-list failure for [currentPath]. Set by [UiIntent.FilesListFailed] and
     * cleared by a successful listing, a path change or a device change. Never inferred
     * from status-message text.
     */
    val filesError: String? = null,
    val operation: OperationProgress? = null,
    val pendingConfirmation: DestructiveConfirmation? = null,
    val linkOverwriteCandidate: String? = null,
    /** Device identity that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateIdentity: String? = null,
    /** Attach generation that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateGeneration: Long? = null,
    val statusMessage: UiMessage? = null,
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

    /** True while one EFI partition has its write protection lifted for this attachment. */
    val vtoyEfiWriteEnabled: Boolean
        get() = vtoyEfiWritePartitionId != null

    /**
     * True when [partition] may be written. Non-EFI partitions are always allowed; an EFI
     * partition only when the override names exactly that partition on this attachment.
     */
    fun isEfiWriteAllowed(partition: PartitionTab): Boolean {
        if (!partition.readOnly) return true
        val allowedStart = vtoyEfiWriteStartLba ?: return false
        val allowedCount = vtoyEfiWriteSectorCount ?: return false
        return partition.id == vtoyEfiWritePartitionId &&
            partition.startLba == allowedStart &&
            partition.sectorCount == allowedCount
    }

    /** Write-protected system partitions (ESP, EFI type, or VTOYEFI name). */
    val efiPartitions: List<PartitionTab>
        get() = partitions.filter { it.readOnly }
}

/** True when [binding] still names the selected device, partition and exact geometry. */
fun PartitionBinding.matches(state: AppUiState): Boolean {
    val device = state.device ?: return false
    if (device != this.device) return false
    if (state.selectedPartitionId != partitionId) return false
    val partition = state.partitions.firstOrNull { it.id == partitionId } ?: return false
    return partition.startLba == startLba && partition.sectorCount == sectorCount
}

/**
 * Whole-MiB math for the new-partition slider. Exact [ULong] bounds: Float only carries
 * the thumb position, never the boundary value, so rounding cannot cross the free region.
 */
object PartitionRangeMath {
    /** Whole MiB units that fit into [availableSectors]; 0 when [sectorsPerMib] is 0. */
    fun availableMib(availableSectors: ULong, sectorsPerMib: ULong): ULong =
        if (sectorsPerMib == 0UL) 0UL else availableSectors / sectorsPerMib

    /** Snap a slider position to a whole MiB, clamped to [0, maxMib]. */
    fun snapToMib(position: Float, maxMib: ULong): ULong {
        if (maxMib == 0UL || position.isNaN()) return 0UL
        val rounded = position.roundToLong()
        if (rounded <= 0L) return 0UL
        val candidate = rounded.toULong()
        return if (candidate > maxMib) maxMib else candidate
    }

    /**
     * Sector selection for a snapped range, or null for an empty/inverted range or when
     * the computed end would pass [regionEnd]. All arithmetic stays in [ULong].
     */
    fun sectorsFor(
        firstAligned: ULong,
        regionEnd: ULong,
        startMib: ULong,
        endMib: ULong,
        sectorsPerMib: ULong,
    ): Pair<ULong, ULong>? {
        if (sectorsPerMib == 0UL || endMib <= startMib) return null
        val start = firstAligned + startMib * sectorsPerMib
        val count = (endMib - startMib) * sectorsPerMib
        if (start > regionEnd || count > regionEnd - start) return null
        return start to count
    }
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
