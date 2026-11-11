package usb.ui

import usb.fs.FilesystemId
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageSnapshot
import usb.fs.FormatCatalog
import usb.partition.TableScheme
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.FolderReadProblem
import usb.ventoy.LayoutPartition
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import java.util.Locale
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
     * Format: `label` or `label#serial` (serial trimmed; blank/whitespace → no suffix).
     */
    val identity: String
        get() = identityString(label, serialNumber)

    /** True when a serial is present — required to resume a RESTORE map. */
    val hasStableSerial: Boolean
        get() = !serialNumber.isNullOrBlank()

    val capacityLabel: String get() = formatCapacity(capacityBytes)

    companion object {
        /** Fallback when USB product and device names are both absent. */
        const val DEFAULT_LABEL = "USB Storage"

        /**
         * Single identity-string rule used by [DeviceIdentity.identity] and any live
         * USB check that must match confirmations. No format drift across callsites.
         */
        fun identityString(label: String, serialNumber: String?): String {
            val serial = serialNumber?.trim().orEmpty()
            return if (serial.isNotEmpty()) "$label#$serial" else label
        }

        /** Label rule shared by every attach/probe path. Pure strings — no Android types. */
        fun labelOf(productName: String?, deviceName: String?): String =
            productName ?: deviceName ?: DEFAULT_LABEL

        /**
         * Build identity from already-resolved fields. Capacity and attachGeneration are
         * taken verbatim so probe/permission/refresh paths cannot silently alter them.
         */
        fun of(
            label: String,
            capacityBytes: ULong,
            serialNumber: String? = null,
            attachGeneration: Long = 0L,
        ): DeviceIdentity =
            DeviceIdentity(
                label = label,
                capacityBytes = capacityBytes,
                serialNumber = serialNumber,
                attachGeneration = attachGeneration,
            )

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

    /** Structured read-scan outcome; Compose formats via string resources (language-switch safe). */
    data class ReadScan(val outcome: ReadScanOutcome) : UiMessage

    /** Typed target-layout rejection; Compose localizes each problem, never `enum.name`. */
    data class InvalidTargetLayout(
        val problems: List<AutounattendProblem>,
    ) : UiMessage
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
    RawRangeInvalid,
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
    /** A bounded inspection failed; args = source label, exact reason. */
    SourceInspectionRejected,
    /** Args = map sector size, disk sector size. */
    RescueMapSectorSizeMismatch,
    /** Args = map sector count, live disk sector count. */
    RescueMapDiskTooSmall,
    /** Args = never-read sector count. */
    RescueMapPartialCopy,
    /** Args = backup sector size, disk sector size. */
    GptBackupSectorSizeMismatch,
    /** Args = captured disk sectors, live disk sectors. */
    GptBackupDiskMismatch,
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
    PartitionNameUnsupported,
    InvalidPartitionName,
    InvalidVolumeLabel,
    VolumeLabelUnsupported,
    FilesystemUnknown,
    SchemeAlreadyCurrent,
    SchemeUnknown,
    SchemeMbrTooLarge,
    CannotReadUsb,
    CannotReadPartitionTable,
    FolderListChanged,
    Success,
    CancelledStatus,
    FailedStatus,
    PartialStatus,
    RunningStatus,
    QueuedStatus,
    /** Existing unattend XML cannot be replaced without overwrite flag. */
    XmlOverwriteRequired,
    /** User must confirm replacing the existing unattend XML on the stick. */
    ConfirmXmlOverwrite,

    /** Whole in-app log was copied to the clipboard on an explicit action. */
    LogCopied,
}

/**
 * Clipboard payload of "copy all": one log line per row, order preserved. Pure so the
 * export shape is testable without Android; the warning about paths and serials is UI text.
 */
fun logClipboardText(lines: List<String>): String = lines.joinToString("\n")

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
    RENAME_PARTITION,
    RENAME_VOLUME_LABEL,
    INITIALIZE_PARTITION_TABLE,
    VENTOY_UNATTEND_XML_WRITE,
    VENTOY_UNATTEND_DISK_LAYOUT,
    ;

    /**
     * True when the host may keep an [usb.service.OperationHandle] for cooperative cancel
     * (progress loops that check isActive). Non-cooperative actions run to completion or fail.
     */
    val cooperative: Boolean
        get() =
            this == RAW_WRITE ||
                this == VENTOY_DOWNLOAD_INSTALL ||
                this == RESCUE_RESTORE
}

/**
 * Layout the user can initialize. Deliberately excludes HYBRID: an ambiguous MBR+GPT layout
 * is never written, it must be repaired or explicitly initialized over with a scheme choice.
 */
enum class PartitionScheme {
    Mbr,
    Gpt,
}

fun PartitionScheme.toTableScheme(): TableScheme =
    when (this) {
        PartitionScheme.Mbr -> TableScheme.MBR
        PartitionScheme.Gpt -> TableScheme.GPT
    }

fun TableScheme.toPartitionScheme(): PartitionScheme? =
    when (this) {
        TableScheme.MBR -> PartitionScheme.Mbr
        TableScheme.GPT -> PartitionScheme.Gpt
        TableScheme.HYBRID -> null
    }

/** Pinned geometry of one pre-existing partition; a table rewrite must see exactly these. */
data class PartitionRef(
    val startLba: ULong,
    val sectorCount: ULong,
)

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
    /** Absolute raw-write range confirmed by the user; null for every other action. */
    val rawStartLba: ULong? = null,
    val rawSectorCount: ULong? = null,
    /** Geometry the raw range was drafted against; refuses a re-plug with other geometry. */
    val diskSectorCount: ULong? = null,
    val diskSectorSize: Int? = null,
    /**
     * Display name of the raw image source (SAF name or staged label). Shown on RAW_WRITE
     * confirmation; never an opaque document id alone when a real name is known.
     */
    val rawSourceName: String? = null,
    /** Full SAF/content URI snapshotted for GPT_RESTORE — not only a display label. */
    val gptBackupUri: String? = null,
    /** Display label for GPT backup when a basename exists; optional for opaque URIs. */
    val gptBackupName: String? = null,
    /** Full URI snapshotted for RESCUE_RESTORE image. */
    val rescueImageUri: String? = null,
    val rescueImageName: String? = null,
    /** Full URI snapshotted for RESCUE_RESTORE map. */
    val rescueMapUri: String? = null,
    val rescueMapName: String? = null,
    /** Pre-confirmation facts actually inspected for this GPT backup file. */
    val gptBackupInspection: GptBackupInspection? = null,
    /** Pre-confirmation facts for the selected rescue image (SAF metadata only). */
    val rescueImageInspection: RescueImageInspection? = null,
    /** Pre-confirmation facts for the selected rescue map (bounded read + decode). */
    val rescueMapInspection: RescueMapInspection? = null,
    /**
     * Target XML path/name for VENTOY_UNATTEND_XML_WRITE (overwrite of existing unattend).
     * Generation params live in [AppUiState.pendingAutounattendGeneration].
     */
    val xmlTargetName: String? = null,
    val partitionRole: PartitionRole? = null,
    val partitionBootable: Boolean? = null,
    /** Normalized GPT name for CREATE_PARTITION (null = no name). */
    val createName: String? = null,
    /** Filesystem to apply to the partition this action creates (null = create only). */
    val createFormatId: FilesystemId? = null,
    /** Volume label written while creating/formatting, or the new label for RENAME_VOLUME_LABEL. */
    val volumeLabel: String = "",
    /** New GPT entry name for RENAME_PARTITION. */
    val partitionName: String? = null,
    /** Scheme the table had when the confirmation was created (null = unknown, refused). */
    val initialScheme: PartitionScheme? = null,
    /** Scheme the table will be initialized to. */
    val targetScheme: PartitionScheme? = null,
    /** Exact partition geometries of the table being replaced. */
    val initialPartitions: List<PartitionRef> = emptyList(),
    /** Multiple image paths for one VENTOY_LINK_XML confirmation. */
    val imagePaths: List<String> = emptyList(),
    /** Existing XML template path on the Ventoy data partition (never rewritten). */
    val templatePath: String? = null,
    /** Separate opt-in for templates that wipe/partition a disk. */
    val allowDestructiveTemplate: Boolean = false,
    /** SHA-256 of the inspected template bytes this confirmation applies to. */
    val templateSha256: String? = null,
    /**
     * Exact global control writes chosen when the confirmation was opened. Only install,
     * download-install, and update carry it; later edits on screen cannot change it.
     */
    val ventoyControls: List<VentoyControlSetting> = emptyList(),
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
            rawStartLba: ULong? = null,
            rawSectorCount: ULong? = null,
            diskSectorCount: ULong? = null,
            diskSectorSize: Int? = null,
            rawSourceName: String? = null,
            gptBackupUri: String? = null,
            gptBackupName: String? = null,
            rescueImageUri: String? = null,
            rescueImageName: String? = null,
            rescueMapUri: String? = null,
            rescueMapName: String? = null,
            gptBackupInspection: GptBackupInspection? = null,
            rescueImageInspection: RescueImageInspection? = null,
            rescueMapInspection: RescueMapInspection? = null,
            xmlTargetName: String? = null,
            imagePaths: List<String> = emptyList(),
            templatePath: String? = null,
            allowDestructiveTemplate: Boolean = false,
            templateSha256: String? = null,
            ventoyControls: List<VentoyControlSetting> = emptyList(),
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
                rawStartLba = if (action == DestructiveAction.RAW_WRITE) rawStartLba else null,
                rawSectorCount = if (action == DestructiveAction.RAW_WRITE) rawSectorCount else null,
                diskSectorCount =
                    if (action == DestructiveAction.RAW_WRITE) diskSectorCount else null,
                diskSectorSize =
                    if (action == DestructiveAction.RAW_WRITE) diskSectorSize else null,
                rawSourceName =
                    if (action == DestructiveAction.RAW_WRITE) rawSourceName else null,
                gptBackupUri =
                    if (action == DestructiveAction.GPT_RESTORE) gptBackupUri else null,
                gptBackupName =
                    if (action == DestructiveAction.GPT_RESTORE) gptBackupName else null,
                rescueImageUri =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueImageUri else null,
                rescueImageName =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueImageName else null,
                rescueMapUri =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueMapUri else null,
                rescueMapName =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueMapName else null,
                gptBackupInspection =
                    if (action == DestructiveAction.GPT_RESTORE) gptBackupInspection else null,
                rescueImageInspection =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueImageInspection else null,
                rescueMapInspection =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueMapInspection else null,
                xmlTargetName =
                    if (action == DestructiveAction.VENTOY_UNATTEND_XML_WRITE) xmlTargetName else null,
                imagePaths = if (action == DestructiveAction.VENTOY_LINK_XML) imagePaths else emptyList(),
                templatePath = if (action == DestructiveAction.VENTOY_LINK_XML) templatePath else null,
                allowDestructiveTemplate =
                    if (action == DestructiveAction.VENTOY_LINK_XML) allowDestructiveTemplate else false,
                templateSha256 =
                    if (action == DestructiveAction.VENTOY_LINK_XML) templateSha256 else null,
                ventoyControls =
                    if (action == DestructiveAction.VENTOY_INSTALL ||
                        action == DestructiveAction.VENTOY_UPDATE ||
                        action == DestructiveAction.VENTOY_DOWNLOAD_INSTALL
                    ) {
                        ventoyControls
                    } else {
                        emptyList()
                    },
            )

        /**
         * Optional display basename only. Trailing `/` or blank → null (no invented name).
         */
        fun fileLabel(uriOrPath: String?): String? {
            if (uriOrPath.isNullOrBlank()) return null
            if (uriOrPath.endsWith('/')) return null
            return uriOrPath.substringAfterLast('/').takeIf { it.isNotBlank() }
        }

        /**
         * Basename when present, else full URI/path so opaque SAF still shows a label
         * without blocking the operation.
         */
        fun displayLabel(uriOrPath: String?): String? =
            fileLabel(uriOrPath) ?: uriOrPath?.takeIf { it.isNotBlank() }
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
        DestructiveAction.RENAME_PARTITION -> "rename partition name"
        DestructiveAction.RENAME_VOLUME_LABEL -> "rename volume label"
        DestructiveAction.INITIALIZE_PARTITION_TABLE -> "initialize partition table"
        DestructiveAction.VENTOY_UNATTEND_XML_WRITE -> "write generated unattend XML"
        DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT -> "wipe/partition target PC disk via unattend"
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
    /** Current filesystem volume label, when the engine can read it. */
    val volumeLabel: String? = null,
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

/**
 * Partition-level write state shown on the Files screen. Only a verified fact is reported:
 * [ReadOnly] means guaranteed EFI write protection. Browsing always opens the volume
 * read-only and a writable mount is created per mutation, so the current state has no
 * confirmed writable fact; every unprotected partition is [Unknown], never claimed as RW.
 */
enum class PartitionWriteAccess {
    ReadOnly,
    Unknown,
}

data class FileEntry(
    val name: String,
    val isDirectory: Boolean,
    val sizeBytes: ULong,
) {
    val isIso: Boolean
        get() = !isDirectory && name.endsWith(".iso", ignoreCase = true)
}

/** Path shown relative to the root of a mounted volume, with both slashes: "/" or "/a/b/". */
fun folderPathLabel(path: String): String {
    val trimmed = path.trim('/')
    return if (trimmed.isEmpty()) "/" else "/$trimmed/"
}

/**
 * Extension-derived format of a real file name, uppercase; null when the name has no
 * extension or starts with a dot. Nothing is inferred beyond the characters in the name.
 */
fun fileFormatLabel(name: String, isDirectory: Boolean): String? {
    if (isDirectory) return null
    val dot = name.lastIndexOf('.')
    if (dot !in 1 until name.lastIndex) return null
    return name.substring(dot + 1).uppercase(Locale.ROOT)
}

/**
 * Display split of a real entry name: folders stay whole (dots and all), files keep their
 * extension in a separately ellipsized suffix, and names without an extension stay whole.
 */
data class FileNameParts(val base: String, val suffix: String)

fun fileNameParts(name: String, isDirectory: Boolean): FileNameParts {
    if (isDirectory) return FileNameParts(name, "")
    val dot = name.lastIndexOf('.')
    return if (dot in 1 until name.lastIndex) {
        FileNameParts(name.substring(0, dot), name.substring(dot))
    } else {
        FileNameParts(name, "")
    }
}

enum class IsoAction {
    LinkXml,
    /** Stage USB file into app filesDir, then queue raw write (Partitions confirm). */
    SendToRawWriter,
}

/** One regular file offered for a raw write; no extension filter is applied. */
data class RawSourceCandidate(
    val path: String,
    val name: String,
    val sizeBytes: Long,
    /** Partition the path belongs to; results never cross a partition change. */
    val partitionId: String,
)

/** Phone import destination: image files and XML templates live in different folders. */
enum class WindowsImportKind {
    Image,
    XmlTemplate,
}

/**
 * SAF source inspected against the real Ventoy data partition. [exists] requires an
 * explicit overwrite confirmation; [attachGeneration] pins the USB attachment.
 */
data class IsoImportProposal(
    val uri: String,
    val name: String,
    val targetName: String,
    val sizeBytes: Long?,
    val exists: Boolean,
    val identity: String?,
    val attachGeneration: Long,
    val requestGeneration: Long,
    val kind: WindowsImportKind = WindowsImportKind.Image,
    val directory: String = "/ISO",
    /** Destructive sections found in a phone XML at inspection time; empty for images. */
    val templateMarkers: List<String> = emptyList(),
)

/** One existing `auto_install` binding shown in the XML section. */
data class WindowsImageLink(val image: String, val templates: List<String>)

/**
 * Typed user-facing operation identity.
 * UI localizes via [usb.ui.operationLabel]; logs and [usb.operation.DiskOperation.name]
 * use stable English [logLabel] only — never reverse-parse localized text.
 */
sealed interface OperationTitle {
    /** English technical string for journals and DiskOperation.name. Never localized. */
    val logLabel: String

    data class Destructive(val action: DestructiveAction) : OperationTitle {
        override val logLabel: String get() = action.displayName()
    }

    data object ProbeDevice : OperationTitle {
        override val logLabel: String get() = "Probe device"
    }

    data object ReadScan : OperationTitle {
        override val logLabel: String get() = "Read scan"
    }

    data object RescueBackup : OperationTitle {
        override val logLabel: String get() = "Rescue backup"
    }

    data object GptCriticalBackup : OperationTitle {
        override val logLabel: String get() = "GPT critical backup"
    }

    data object RefreshPartitions : OperationTitle {
        override val logLabel: String get() = "Refresh partitions"
    }

    data object SelfTest : OperationTitle {
        override val logLabel: String get() = "Self-test"
    }

    /** Transient running banner before the terminal Self-test title. */
    data object SelfTestRunning : OperationTitle {
        override val logLabel: String get() = "Self-test: running..."
    }

    data object Upload : OperationTitle {
        override val logLabel: String get() = "Upload"
    }

    data object AutounattendXmlLink : OperationTitle {
        override val logLabel: String get() = "autounattend.xml link"
    }

    data object CheckRawRange : OperationTitle {
        override val logLabel: String get() = "Check raw range"
    }

    data object ListRawSources : OperationTitle {
        override val logLabel: String get() = "List raw sources"
    }

    data object ListDataPartitionFiles : OperationTitle {
        override val logLabel: String get() = "List data partition files"
    }

    data object ReadXmlBindings : OperationTitle {
        override val logLabel: String get() = "Read XML bindings"
    }

    data object RemoveXmlBindings : OperationTitle {
        override val logLabel: String get() = "Remove XML bindings"
    }

    data object SaveGlobalWindowsControls : OperationTitle {
        override val logLabel: String get() = "Save global Windows controls"
    }

    data object ReadUnattendXml : OperationTitle {
        override val logLabel: String get() = "Read unattend XML"
    }

    data object ListVentoyImages : OperationTitle {
        override val logLabel: String get() = "List Ventoy images"
    }

    data object GenerateUnattendXml : OperationTitle {
        override val logLabel: String get() = "Generate unattend XML"
    }

    data object ReadVentoyGlobalSettings : OperationTitle {
        override val logLabel: String get() = "Read Ventoy global settings"
    }

    data object ListVentoyFolders : OperationTitle {
        override val logLabel: String get() = "List Ventoy folders"
    }

    data object SetVentoyGlobalSetting : OperationTitle {
        override val logLabel: String get() = "Apply Ventoy global settings"
    }

    data object BindImagesToUnattendXml : OperationTitle {
        override val logLabel: String get() = "Bind images to unattend XML"
    }

    data object UnbindImageFromUnattendXml : OperationTitle {
        override val logLabel: String get() = "Unbind image from unattend XML"
    }

    data class ListPath(val path: String) : OperationTitle {
        override val logLabel: String get() = "List $path"
    }

    data class NewFolder(val name: String) : OperationTitle {
        override val logLabel: String get() = "New folder $name"
    }

    data class DeleteEntry(val name: String) : OperationTitle {
        override val logLabel: String get() = "Delete $name"
    }

    data class RenameEntry(val from: String) : OperationTitle {
        override val logLabel: String get() = "Rename $from"
    }

    data class DownloadEntry(val name: String) : OperationTitle {
        override val logLabel: String get() = "Download $name"
    }

    data class StageForRawWrite(val name: String) : OperationTitle {
        override val logLabel: String get() = "Stage $name for raw write"
    }

    data class FormatTarget(val target: String) : OperationTitle {
        override val logLabel: String get() = "Format $target"
    }

    data class ImportTarget(val targetName: String) : OperationTitle {
        override val logLabel: String get() = "Import $targetName"
    }
}

data class OperationProgress(
    val state: OperationState,
    val title: OperationTitle,
    val bytesDone: Long = 0L,
    val bytesTotal: Long = 0L,
    val bytesPerSecond: Long = 0L,
    val errorMessage: String? = null,
    /** Concrete outcome (paths, counts) shown instead of the bare "Success" label. */
    val resultMessage: String? = null,
    /**
     * Engine outcome of the last raw write, including the mandatory readback verdict.
     * Null for every other operation; a Partial state here must be surfaced as Partial.
     */
    val rawWriteResult: usb.image.RawWriteResult? = null,
    /**
     * Structured read-scan numbers (sectors, failed ranges, average successful-read rate).
     * Null for every other operation. Compose formats via string resources so a language
     * switch does not leave a frozen EN/RU sentence.
     */
    val readScanResult: ReadScanOutcome? = null,
    val cancelable: Boolean = true,
) {
    /**
     * Stable English technical label for journals and DiskOperation.name.
     * UI must not parse this; use [title] through [operationLabel].
     */
    val label: String get() = title.logLabel

    val fraction: Float
        get() =
            if (bytesTotal <= 0L) {
                0f
            } else {
                (bytesDone.toDouble() / bytesTotal.toDouble()).toFloat().coerceIn(0f, 1f)
            }
}

/**
 * Numbers from [usb.diagnostics.ScanReport] for UI. Rate null → "unavailable" string resource.
 */
data class ReadScanOutcome(
    val sectorsRead: ULong,
    val totalSectors: ULong,
    val unreadableSectors: ULong,
    val failedRangeCount: Int,
    /** Null when successful bytes or monotonic elapsed make rate undefined. */
    val averageBytesPerSecond: Long?,
) {
    /** English technical line for file/log only; UI uses string resources. */
    fun toLogLine(): String {
        val speed = averageBytesPerSecond?.let { usb.formatBinaryBytesPerSecond(it) }
            ?: "unavailable"
        return "Read scan: $sectorsRead of $totalSectors sectors read, " +
            "$unreadableSectors unreadable in $failedRangeCount range(s), avg read $speed"
    }

    companion object {
        fun from(report: usb.diagnostics.ScanReport, totalSectors: ULong): ReadScanOutcome =
            ReadScanOutcome(
                sectorsRead = report.sectorsRead,
                totalSectors = totalSectors,
                unreadableSectors = report.failed.sumOf { it.last - it.first + 1UL },
                failedRangeCount = report.failed.size,
                averageBytesPerSecond = report.averageBytesPerSecond,
            )
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
    /**
     * On-disk table scheme of the current attachment. Null = unread/unknown, in which case
     * GPT-only features (partition names) stay unavailable rather than being guessed.
     */
    val partitionScheme: TableScheme? = null,
    /** Absolute FAT path currently displayed; [files] are the entries of this directory. */
    val currentPath: String = "/",
    val files: List<FileEntry> = emptyList(),
    /**
     * Id of the newest directory-list request, bumped when the request is emitted (path
     * change, tab entry, manual refresh, device change, finished mutation).
     * [UiIntent.FilesListed] / [UiIntent.FilesListFailed] answers must echo this value
     * together with path, selected partition, identity and attach generation; anything
     * else is dropped, so a late answer never overwrites a newer one.
     */
    val filesListGeneration: Long = 0L,
    /**
     * True while a real list for [currentPath] is outstanding. Empty [files] then means
     * "not loaded yet", never "folder is empty". Cleared by a matching list result or a
     * refresh/invalidations that defers mounting to the Files tab.
     */
    val filesPending: Boolean = false,
    /**
     * Real directory-list failure for [currentPath]. Set by [UiIntent.FilesListFailed] and
     * cleared by a successful listing, a path change or a device change. Never inferred
     * from status-message text.
     */
    val filesError: String? = null,
    /**
     * Last read-only free-space measurement of one exact attachment + partition geometry.
     * Rendered only while its key still matches the live selection; invalidated on a new
     * attachment, a different selection and every finished operation, so a value measured
     * before a write can never be shown as current.
     */
    val filesystemUsage: FilesystemUsageSnapshot? = null,
    /** Id of the newest measurement request; answers to older requests are dropped. */
    val filesystemUsageGeneration: Long = 0L,
    val operation: OperationProgress? = null,
    val pendingConfirmation: DestructiveConfirmation? = null,
    val linkOverwriteCandidate: String? = null,
    /** Device identity that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateIdentity: String? = null,
    /** Attach generation that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateGeneration: Long? = null,
    /** Full set of image paths whose link needs explicit overwrite. */
    val linkOverwriteCandidatePaths: List<String> = emptyList(),
    val linkOverwriteCandidateTemplatePath: String? = null,
    val statusMessage: UiMessage? = null,
    /** SAF URI of the image queued for the next raw write. */
    val imageUri: String? = null,
    /** Provider DISPLAY_NAME for [imageUri], never an opaque document id. */
    val imageName: String? = null,
    /** Provider SIZE for [imageUri] when it reported one. */
    val imageSizeBytes: Long? = null,
    /**
     * Async raw-source scan for the selected partition. Null = not loaded yet;
     * empty = loaded and none. Only accepted for [rawSourceCandidatesGeneration]
     * and [rawSourceCandidatesIdentity].
     */
    val rawSourceCandidates: List<RawSourceCandidate>? = null,
    val rawSourceCandidatesGeneration: Long = 0L,
    val rawSourceCandidatesIdentity: String? = null,
    val rawSourceCandidatesTruncated: Boolean = false,
    /**
     * Bound scan failure for the current [rawSourceCandidatesGeneration].
     * Stale chooser errors must not land here or in [statusMessage].
     */
    val rawSourceCandidatesError: String? = null,
    val windowsIsoCandidates: List<RawSourceCandidate>? = null,
    val windowsIsoCandidatesGeneration: Long = 0L,
    val windowsIsoCandidatesIdentity: String? = null,
    /**
     * Host note when the file listing was incomplete. Never rendered verbatim: the UI shows a
     * localized sentence and keeps this as the diagnostic detail.
     */
    val windowsIsoCandidatesError: String? = null,
    /** Inspected SAF ISO waiting only for an explicit overwrite decision. */
    val pendingIsoImport: IsoImportProposal? = null,
    /**
     * Inspected import with no overwrite decision needed (target does not exist) that arrived
     * while the UI was not actionable (same-stick probe). Emitted as [UiEffect.ImportIso] by
     * the first quiet, actionable reduce; dropped when the attachment or generation changes.
     */
    val deferredIsoImport: IsoImportProposal? = null,
    /** Typed reason the last phone-file inspection failed; null = no failure. */
    val isoImportError: UnreadableProblem? = null,
    val isoImportGeneration: Long = 0L,
    /**
     * Generation of the SAF-phase inspection in flight, or null. SAF reads run off the disk
     * worker and can block in the provider; cancel/attach change disowns them so they never
     * block a new import. Cleared by [UiIntent.WindowsIsoImportSafFinished].
     */
    val isoImportSafInFlight: Long? = null,
    /**
     * Generation of the USB-phase inspection that currently holds the disk. Set by
     * [UiIntent.WindowsIsoImportUsbStarted], cleared only by [UiIntent.WindowsIsoImportFinished]
     * after the host closed the disk, never by UI cancel.
     */
    val isoImportUsbInFlight: Long? = null,
    /**
     * Non-null when an import failed because the chosen folder vanished: the Ventoy import
     * card reopens the folder picker for this kind, then clears it with
     * [UiIntent.ImportFolderPickOpened]. Cleared by any attach change.
     */
    val pendingImportFolderPick: WindowsImportKind? = null,
    /**
     * True when the import-folder work was armed while no mount was allowed (operation
     * running/queued or a confirmation open). The folder re-read and the picker demand are
     * released by the first intent that makes the UI actionable again (terminal operation,
     * dismissed confirmation), so no USB work races the operation and no dialog covers a
     * destructive confirmation. Survives unrelated mutations on the same attachment.
     */
    val pendingImportFolderPickLoad: Boolean = false,
    /**
     * Import kind whose picker must open once mounting is allowed again. Kept apart from
     * [pendingImportFolderPick] so the UI consuming (clearing) the picker demand cannot
     * cancel the deferred folder re-read or the demand itself.
     */
    val deferredImportFolderPick: WindowsImportKind? = null,
    /** Absolute image paths ticked in the XML section before one explicit bind. */
    val windowsSelectedImages: List<String> = emptyList(),
    /** Existing auto_install bindings; null = not loaded. */
    val windowsImageLinks: List<WindowsImageLink>? = null,
    val windowsImageLinksGeneration: Long = 0L,
    val windowsImageLinksIdentity: String? = null,
    /** XML template file on the stick; never generated or rewritten by the app. */
    val windowsTemplatePath: String? = null,
    /** Bindings selected for removal; confirmation only edits ventoy.json. */
    val pendingWindowsUnlink: List<String>? = null,
    /** Reader result for the selected XML; Missing/Parsed/Unreadable. */
    val autounattendState: AutounattendState = AutounattendState.Missing,
    val autounattendBindings: List<AutounattendBinding>? = null,
    /** Whole-partition image candidates and real Ventoy discovery scope. */
    val autounattendCandidates: AutounattendImageCandidates? = null,
    /** Monotonic id for XML read/candidate requests; stale posts are dropped. */
    val autounattendRequestGeneration: Long = 0L,
    /**
     * True after [UiIntent.LoadAutounattend] / template select-import until
     * [UiIntent.AutounattendLoaded] for the current generation. Composition skips a second mount.
     */
    val autounattendLoadInFlight: Boolean = false,
    /** Separate target-PC disk-layout acknowledgement; no USB identity. */
    val diskLayoutConfirmation: DiskLayoutConfirmation? = null,
    val pendingAutounattendGeneration: PendingAutounattendGeneration? = null,
    /** Real `control` read result; null = not loaded. Missing/Empty/ReadError stay distinct. */
    val ventoyGlobalSettings: VentoyGlobalSettings? = null,
    /**
     * Explicit control writes chosen by the user. They are written after a confirmed
     * install/update, or by [UiIntent.RequestApplyVentoyControls] against an existing stick.
     * A null value removes the key. Empty = nothing chosen; update leaves the file untouched.
     */
    val pendingVentoyControls: List<VentoyControlSetting> = emptyList(),
    /**
     * Folder the lazy browser currently shows. Always normalized and absolute; `/` is the
     * data-partition root. Reset to the root when the attachment or the stick changes.
     */
    val ventoyFolderPath: String = "/",
    /**
     * Direct child directories of [ventoyFolderPath], absolute. One level only: no depth or
     * entry cap, so every existing folder can be reached by entering it. null = not read
     * for this path yet (or the answer is stale), never "no subfolders".
     */
    val ventoyFolders: List<String>? = null,
    /** Why the last read failed; the UI localizes it and never shows a raw engine message. */
    val ventoyFoldersError: FolderReadProblem? = null,
    /**
     * A read for [ventoyFolderPath] is on its way. Distinguishes "no subfolders" from
     * "not read yet" when a refresh keeps the previous rows on screen.
     */
    val ventoyFoldersLoading: Boolean = false,
    /**
     * A read was requested while the device was busy. It is released once the USB lane is
     * free again, so a null list cannot look like a read that never finishes.
     */
    val ventoyFoldersPending: Boolean = false,
    /**
     * Id of the newest folder-list request. Bumped when the request is emitted, so two
     * refreshes in one epoch cannot land out of order; answers must echo it plus
     * [ventoyCacheEpoch] and [ventoyFolderPath].
     */
    val ventoyFoldersRequest: Long = 0L,
    /** Same ordering contract as [ventoyFoldersRequest] for [ventoyGlobalSettings]. */
    val ventoyGlobalSettingsRequest: Long = 0L,
    /**
     * Bumped when stick-scoped Ventoy/XML caches must be considered stale (destructive
     * mutation, detach, new attachment). Ventoy-tab composition keys on this to re-read
     * without host mounting off-tab.
     */
    val ventoyCacheEpoch: Long = 0L,
    /**
     * Whatever the last raw-range probe found; null until a probe for the current
     * attachment/geometry/range completed. Presence hint only, never a veto.
     */
    val rangeProbe: usb.image.RangeProbeResult? = null,
    /**
     * Monotonic id of the latest probe request; never reset, so a late result from an
     * earlier request (even on the same range) cannot win a race.
     */
    val rangeProbeRequestId: Long? = null,
    /** Range that [rangeProbeRequestId] was requested for; results must match it too. */
    val rangeProbeRequestedRange: usb.partition.RawWriteRange? = null,
    /**
     * Disk geometry plus partition/table objects for the raw-range map. Null until a
     * partition refresh described the attached medium; a table read failure is an
     * explicit unreadable geometry, not an absent one.
     */
    val rawGeometry: usb.partition.RawRangeGeometry? = null,
    /**
     * Absolute path under app filesDir/staging for a USB→raw staging copy.
     * Preferred over [imageUri] when set. Cleared after apply/cancel + file delete.
     */
    val stagedRawImagePath: String? = null,
    val stagedRawImageLabel: String? = null,
    val backupUri: String? = null,
    /** Bounded inspection of [backupUri] (file facts only; live geometry is rechecked on request). */
    val backupInspection: GptBackupInspection? = null,
    /** Monotonic id of the newest backup pick; older inspection results are dropped. */
    val backupSelectionGeneration: Long = 0L,
    /** SAF URI of a dense rescue .img chosen for restore. */
    val rescueImageUri: String? = null,
    /** SAF metadata inspection of [rescueImageUri]; the image is never opened. */
    val rescueImageInspection: RescueImageInspection? = null,
    /** Monotonic id of the newest rescue-image pick; older results are dropped. */
    val rescueImageSelectionGeneration: Long = 0L,
    /** SAF URI of the matching .map (v2 RESTORE or converted from COPY). */
    val rescueMapUri: String? = null,
    /** Bounded read/decode of [rescueMapUri]. */
    val rescueMapInspection: RescueMapInspection? = null,
    /** Monotonic id of the newest rescue-map pick; older results are dropped. */
    val rescueMapSelectionGeneration: Long = 0L,
    /** User-selected target FS for the next Format action (picker). */
    val selectedFormatId: FilesystemId = FilesystemId.FAT,
    /** Theme preference: exactly two states, dark applied by default on first launch. */
    val darkTheme: Boolean = true,
    /**
     * Bounded operation log. Ring-buffered to [LOG_MAX_LINES]; oldest lines dropped.
     * Appended by [UiIntent.AppendLog]; kept across operations until the user asks for
     * [UiIntent.ClearLog], which clears only this list and never Android logcat.
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

    /**
     * Guaranteed write state of [partition] on this attachment, never a mount mode.
     * EFI protection is authoritative: without the explicit override the answer is
     * [PartitionWriteAccess.ReadOnly]. Everything else is [PartitionWriteAccess.Unknown]
     * because browsing mounts read-only and a writable mount exists only per mutation.
     */
    fun partitionWriteAccess(partition: PartitionTab): PartitionWriteAccess =
        if (!isEfiWriteAllowed(partition)) {
            PartitionWriteAccess.ReadOnly
        } else {
            PartitionWriteAccess.Unknown
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
 * Everything a filesystem free-space measurement is valid for, built from live state.
 * Null when the device, the partition geometry or the filesystem is unknown: a partial
 * key would let a value measured on another attachment or partition pass [FilesystemUsageSnapshot.matches].
 */
fun AppUiState.filesystemUsageKey(partition: PartitionTab): FilesystemUsageKey? {
    val device = device ?: return null
    val startLba = partition.startLba ?: return null
    val sectorCount = partition.sectorCount ?: return null
    val filesystem = partition.filesystemId ?: return null
    if (sectorCount == 0UL || diskSectorSize <= 0) return null
    return FilesystemUsageKey(
        attachGeneration = device.attachGeneration,
        identity = device.identity,
        partitionStartLba = startLba,
        partitionSectorCount = sectorCount,
        sectorSize = diskSectorSize,
        filesystem = filesystem,
    )
}

/**
 * Whether the host must ask for a fresh measurement. Any invalidation (selection change,
 * attachment refresh, finished operation) drops the snapshot, so a matching key alone is
 * not proof that the shown value is current. While a request for the same key is still in
 * flight the host does not ask again, and a published `Known`/`Unavailable` snapshot for
 * that key ends the cycle, so the refresh cannot loop.
 */
fun AppUiState.needsFilesystemUsageRequest(
    key: FilesystemUsageKey?,
    lastRequestedKey: FilesystemUsageKey?,
    inFlightKey: FilesystemUsageKey?,
    operationFinished: Boolean,
): Boolean {
    if (key == null) return false
    if (operationFinished) return true
    val measured = filesystemUsage?.matches(key) == true
    val pending = inFlightKey == key
    return !(key == lastRequestedKey && (measured || pending))
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


/** Whole-partition image discovery plus the real Ventoy search scope that may narrow it. */
data class AutounattendImageCandidates(
    val images: List<AutounattendBinding>,
    val searchScope: List<String>,
    val scopeLimitedBy: String?,
    val truncated: Boolean = false,
    val error: String? = null,
)


enum class UnattendField {
    Language,
    Locale,
    ComputerName,
    LocalAccountName,
    LocalAccountPassword,
    LocalAccountAdministrator,
    DiskLayout,
    DiskId,
    PartitionSize,
    PartitionRole,
    PartitionLabel,
    TpmBypass,
    SecureBootBypass,
    KeyboardLayout,
    DriveLetter,
    PartitionGuid,
    OemProductKey,
    EditionSelection,
}


/** Separate from DestructiveConfirmation: no USB identity or USB capacity is shown. */
data class DiskLayoutConfirmation(
    val diskId: Long?,
    val partitionCount: Int,
    val writeLayout: Boolean,
    /** SHA-256 of the exact params/image plan the user confirmed. */
    val planSha256: String? = null,
    /** Exact layout snapshot shown in the dialog, never re-read from the live form. */
    val partitions: List<LayoutPartition> = emptyList(),
    val declaredSizeMb: Long? = null,
    val totalMb: Long? = null,
    val remainingMb: Long? = null,
    /** First Primary receives ImageInstall; null means validation should have refused it. */
    val installTargetPartitionId: Int? = null,
    /** Mirrors WillWipeDisk exactly; false still rewrites the selected disk's table. */
    val wipe: Boolean = false,
)


/** Generation request waiting only for the separate target-disk acknowledgement. */
data class PendingAutounattendGeneration(
    val xmlName: String,
    val params: AutounattendParams,
    val imagePaths: List<String>,
    val overwriteXml: Boolean,
    val allowDestructiveTemplate: Boolean,
    val identity: String? = null,
    val attachGeneration: Long = 0L,
    val expectedTemplateSha256: String? = null,
    val expectedPlanSha256: String? = null,
)
