package usb.ui

import usb.fs.FilesystemAccess
import usb.fs.FilesystemId
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsagePhase
import usb.fs.FilesystemUsageSnapshot
import usb.partition.TableScheme
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.FolderReadProblem
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyBundledControlKeyCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoyControlKeyCatalog
import usb.ventoy.VentoyReleaseProvenance
import usb.ventoy.VentoyReleaseSource
import usb.ventoy.VentoyReleaseStoreState
import usb.ventoy.VentoySettingsSource

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

    /**
     * Human-facing identity for dialogs: `label (capacity)`. Display only — [identity]
     * stays the matching/revalidation format and must never be replaced by this.
     */
    val displayName: String get() = "$label ($capacityLabel)"

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
            val unit = capacityUnit(bytes)
            return String.format(unit.pattern, bytes.toDouble() / unit.divisor) + " " + unit.suffix
        }

        private class CapacityUnit(val pattern: String, val divisor: Double, val suffix: String)

        private fun capacityUnit(bytes: ULong): CapacityUnit =
            when {
                bytes < 1024uL * 1024uL -> CapacityUnit("%.1f", 1024.0, "KiB")
                bytes < 1024uL * 1024uL * 1024uL -> CapacityUnit("%.1f", 1024.0 * 1024.0, "MiB")
                bytes < 1024uL * 1024uL * 1024uL * 1024uL ->
                    CapacityUnit("%.2f", 1024.0 * 1024.0 * 1024.0, "GiB")
                else -> CapacityUnit("%.2f", 1024.0 * 1024.0 * 1024.0 * 1024.0, "TiB")
            }
    }
}


/** Authored, translatable text. [Raw] holds system or engine text and is shown unchanged. */
sealed interface UiMessage {
    data class Key(
        val key: UiMessageKey,
        val args: List<Any> = emptyList(),
    ) : UiMessage

    data class Raw(val text: String) : UiMessage

    /** Typed operation failure; Compose maps once via OperationPresentation.failureText. */
    data class Failure(val failure: OperationFailure) : UiMessage

    /** Structured read-scan outcome; Compose formats via string resources (language-switch safe). */
    data class ReadScan(val outcome: ReadScanOutcome) : UiMessage

    /** Structured SMART probe outcome; Compose formats via string resources. */
    data class Smart(val outcome: SmartOutcome) : UiMessage


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
    SmartReadStarting,

    InvalidFolderName,
    SelectEntryToDelete,
    SelectFileToDownload,
    SelectPartitionFirst,
    ReadOnlyPartition,
    SelectedFileMissing,
    /** A simplified pick session owns the screen; finish or cancel it first. */
    FilesPickActive,
    /** The picked entry or pinned attachment/geometry is no longer valid. */
    FilesPickTargetInvalid,
    /** No writable Ventoy data partition is attached. */
    VentoyDataPartitionMissing,
    VentoyDataPartitionRequired,
    /** Another phone-file inspection still holds the host slot; nothing was started. */
    ImportInspectionBusy,
    /** A system file picker is already open; the second launch was ignored. */
    ImportPickerBusy,
    /** A blocking SAF read was disowned; it ends when the provider returns. */
    ImportInspectionCancelling,
    /** A system picker never returned; its context was dropped so a new choice is possible. */
    ImportPickerTimedOut,
    /** The finite per-installation picker slot range is exhausted; no code is reused. */
    ImportPickerExhausted,
    /** The durable request-code mark could not be secured or the picker could not open. */
    ImportPickerUnavailable,
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
    /** No SAF tree has been chosen for backup output. */
    ChooseBackupFolder,
    /** Args = display name. */
    BackupFolderReady,
    /** Persisted tree grant or document is gone; the user must choose it again. */
    BackupFolderPermissionLost,
    /** The attached stick changed while the folder picker was open; nothing was started. */
    BackupFolderTargetChanged,
    /** A restored backup action belonged to a dead process and was dropped. */
    BackupFolderActionLost,
    /** GPT picker target has no serial, so same-key replug cannot be ruled out. */
    BackupFolderTargetUnverifiable,
    /** Folder validation in progress; real bytes are not invented. */
    CheckingArchive,
    /** The picked folder contains no files. */
    ArchiveEmpty,
    ArchiveMissingImage,
    ArchiveMissingMap,
    /** Args = comma-separated names. */
    ArchiveMultipleImages,
    /** Args = comma-separated names of files that are neither .img nor .map. */
    ArchiveUnexpectedFiles,
    /** Args = exact provider/read failure. */
    ArchiveUnreadable,
    /** A bounded inspection failed; args = source label, exact reason. */
    SourceInspectionRejected,
    /** Args = map sector size, disk sector size. */
    RescueMapSectorSizeMismatch,
    RescueMapSectorSizeUnknown,
    /** Args = map sector count, live disk sector count. */
    RescueMapDiskTooSmall,
    /** Args = actual image bytes, minimum required bytes. */
    RescueMapImageTooShort,
    /** Map sector count × sector size overflows the supported length. */
    RescueMapImageGeometryOverflow,
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
    /** App-scoped eject: caches flushed and owned volume/disk closed for this app only. */
    EjectSucceeded,
    /** Eject started; completion arrives as EjectResulted. */
    EjectStarted,
    /** Owned volume/disk refused eject: another session holds the USB claim. */
    EjectBusy,
    /** Stale eject or mismatched attachment; nothing was touched. */
    EjectStale,
    /** Explicit rescan is the only way back after a successful eject. */
    EjectRescanRequired,
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
    GptRepairRequiresGpt,
    SchemeBackupMetadataUnknown,
    SchemeBackupUnverified,
    SchemeMbrTooLarge,
    SchemeMetadataUnverified,
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

    /** Downloaded release store is unknown/corrupt; install/update must not fall back. */
    VentoyStoreUnavailable,
    /** Whole in-app log was copied to the clipboard on an explicit action. */
    LogCopied,

    /** The chosen settings path is relative, empty, or traverses outside the volume. */
    SettingsSourceInvalid,
    /** The chosen file was pinned to another attachment or partition. */
    SettingsSourceStale,
    /** The chosen file disappeared or cannot be read; Update is refused. */
    SettingsSourceUnavailable,
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
     * (progress loops that check isActive). Native filesystem formatting is deliberately
     * excluded: mkfs engines expose blocking calls without a cancellation callback, so the UI
     * must not promise interruption while they run.
     */
    val cooperative: Boolean
        get() =
            this == RAW_WRITE ||
                this == RESCUE_RESTORE
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
    /** Absolute raw-write range confirmed by the user; null for every other action. */
    val rawStartLba: ULong? = null,
    val rawSectorCount: ULong? = null,
    /** Decoded image bytes the confirmed range was derived from; a changed source is refused. */
    val rawDecodedBytes: Long? = null,
    /** SHA-256 of the decoded bytes; a SAF provider returning other bytes is refused. */
    val rawSourceSha256: String? = null,
    /** Geometry the raw range was drafted against; refuses a re-plug with other geometry. */
    val diskSectorCount: ULong? = null,
    val diskSectorSize: Int? = null,
    /**
     * Display name of the raw image source (SAF name or staged label). Shown on RAW_WRITE
     * confirmation; never an opaque document id alone when a real name is known.
     */
    val rawSourceName: String? = null,
    /** Whether the confirmed raw write runs the source/disk readback pass. */
    val rawVerifyReadback: Boolean = true,
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
    /** SAF tree for checkpoint publication; null keeps the app-private fallback. */
    val rescueArchiveTreeUri: String? = null,
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
    /** Scheme the table had when confirmation was created; MBR is baseline for unrecognized media. */
    val initialScheme: PartitionScheme? = null,
    /** Whether bounded parser recognized a supported table at confirmation time. */
    val initialTableRecognized: Boolean = true,
    val initialTableRecognition: usb.partition.TableRecognition =
        usb.partition.TableRecognition.RECOGNIZED,
    val initialMetadataFingerprint: String? = null,
    val initialInitializationMetadataFingerprint: String? = null,
    /** Disk geometry pinned by confirmation. */
    val initialDiskSectorCount: ULong? = null,
    val initialDiskSectorSize: Int? = null,
    /** Scheme the table will be initialized to. */
    val targetScheme: PartitionScheme? = null,
    /** Exact partition geometries of the table being replaced. */
    val initialPartitions: List<PartitionRef> = emptyList(),
    /**
     * Display names aligned with [initialPartitions] for the confirmation text only.
     * Never used for revalidation: identity stays the start/count snapshot.
     */
    val initialPartitionNames: List<String> = emptyList(),
    /** Multiple image paths for one VENTOY_LINK_XML confirmation. */
    val imagePaths: List<String> = emptyList(),
    /** Existing XML template path on the Ventoy data partition (never rewritten). */
    val templatePath: String? = null,
    /** Separate opt-in for templates that wipe/partition a disk. */
    val allowDestructiveTemplate: Boolean = false,
    /** SHA-256 of the inspected template bytes this confirmation applies to. */
    val templateSha256: String? = null,
    /**
     * Exact global control writes chosen when the confirmation was opened. Only install
     * and update carry it; later edits on screen cannot change it.
     */
    val ventoyControls: List<VentoyControlSetting> = emptyList(),
    /**
     * Exact release payload confirmed for install/update. The host revalidates this
     * snapshot and aborts on mismatch instead of falling back to another release.
     */
    val ventoyRelease: VentoyReleaseSource? = null,
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
            rawDecodedBytes: Long? = null,
            rawSourceSha256: String? = null,
            diskSectorCount: ULong? = null,
            diskSectorSize: Int? = null,
            rawSourceName: String? = null,
            rawVerifyReadback: Boolean = true,
            gptBackupUri: String? = null,
            gptBackupName: String? = null,
            rescueImageUri: String? = null,
            rescueImageName: String? = null,
            rescueMapUri: String? = null,
            rescueMapName: String? = null,
            rescueArchiveTreeUri: String? = null,
            gptBackupInspection: GptBackupInspection? = null,
            rescueImageInspection: RescueImageInspection? = null,
            rescueMapInspection: RescueMapInspection? = null,
            xmlTargetName: String? = null,
            imagePaths: List<String> = emptyList(),
            templatePath: String? = null,
            allowDestructiveTemplate: Boolean = false,
            templateSha256: String? = null,
            ventoyControls: List<VentoyControlSetting> = emptyList(),
            ventoyRelease: VentoyReleaseSource? = null,
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
                rawDecodedBytes =
                    if (action == DestructiveAction.RAW_WRITE) rawDecodedBytes else null,
                rawSourceSha256 =
                    if (action == DestructiveAction.RAW_WRITE) rawSourceSha256 else null,
                diskSectorCount =
                    if (action == DestructiveAction.RAW_WRITE) diskSectorCount else null,
                diskSectorSize =
                    if (action == DestructiveAction.RAW_WRITE ||
                        action == DestructiveAction.DELETE_PARTITION
                    ) {
                        diskSectorSize
                    } else {
                        null
                    },
                rawSourceName =
                    if (action == DestructiveAction.RAW_WRITE) rawSourceName else null,
                rawVerifyReadback = rawVerifyReadback,
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
                rescueArchiveTreeUri =
                    if (action == DestructiveAction.RESCUE_RESTORE) rescueArchiveTreeUri else null,
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
                        action == DestructiveAction.VENTOY_UPDATE
                    ) {
                        ventoyControls
                    } else {
                        emptyList()
                    },
                ventoyRelease =
                    if (action == DestructiveAction.VENTOY_INSTALL ||
                        action == DestructiveAction.VENTOY_UPDATE
                    ) {
                        ventoyRelease
                    } else {
                        null
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
            fileLabel(uriOrPath)
                ?: uriOrPath?.takeIf { it.isNotBlank() }?.let(::normalizedRestorePath)
    }
}
fun DestructiveAction.displayName(): String =
    when (this) {
        DestructiveAction.WIPE -> "wipe"
        DestructiveAction.VENTOY_INSTALL -> "Ventoy install"
        DestructiveAction.VENTOY_UPDATE -> "Ventoy update (keeps the ISO partition)"
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
    /** App preferences and local settings; persisted by [ShellTabPersistence] by name. */
    Settings,
}


data class AppUiState(
    val presence: DevicePresence = DevicePresence.Absent,
    val device: DeviceIdentity? = null,
    val tab: ShellTab = ShellTab.Files,
    val language: AppLanguage = AppLanguage.System,
    val partitions: List<PartitionTab> = emptyList(),
    /** Read-only LBA-0 volume for ISO/DD images, including hybrid images with a table. */
    val wholeDiskVolume: PartitionTab? = null,
    val freeRegions: List<FreeRegionUi> = emptyList(),
    /** True after this attachment has produced an explicit partition result. */
    val partitionSelectionInitialized: Boolean = false,
    val diskSectorSize: Int = 512,
    val selectedPartitionId: String? = null,
    /** Partition ID of the one EFI system partition whose write protection is lifted.
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
    /** Bounded parser recognition; false is not proof that media contains no data. */
    val partitionTableRecognized: Boolean = true,
    val partitionTableRecognition: usb.partition.TableRecognition =
        usb.partition.TableRecognition.RECOGNIZED,
    val metadataFingerprint: String? = null,
    val initializationMetadataFingerprintGpt: String? = null,
    val initializationMetadataFingerprintMbr: String? = null,
    val partitionTableAmbiguous: Boolean = false,
    /**
     * On-disk table scheme of the current attachment. Null = unread/unknown, in which case
     * GPT-only features (partition names) stay unavailable rather than being guessed.
     */
    val partitionScheme: TableScheme? = null,
    /** Last issued [FilesPickMode.sessionId]; replaced at Begin/exit/attach loss. */
    val filesPickSession: String = "",
    /** Active simplified pick session; null on the ordinary Files screen. */
    val filesPickMode: FilesPickMode? = null,
    /**
     * Confirmed choices keyed by purpose. Each entry is pinned to the attachment and
     * partition geometry it was read from; a different stick never reuses it.
     */
    val filesPickSelections: Map<FilesPickPurpose, FilesPickSelection> = emptyMap(),
    /** Absolute FAT path currently displayed; [files] are the entries of this directory. */
    val currentPath: String = "/",
    val files: List<FileEntry> = emptyList(),
    /**
     * Id of newest directory-list request. Answers echo this value plus path, selected
     * partition, identity and attach generation; anything else is dropped.
     */
    val filesListGeneration: Long = 0L,
    /** Current directory listing lifecycle; Stale is deferred demand, Loading emitted LoadFiles. */
    val filesListingState: FilesListingState = FilesListingState.Stale,
    /** Real directory-list failure; non-null only after [FilesListingState.Failed]. */
    val filesFailure: OperationFailure? = null,
    /**
     * Active batch name conflict awaiting Skip/ReplaceFile/Cancel. Cleared by
     * ResolveFilesBatchConflict; continuation parks outside AppUiState.
     */
    val pendingFilesBatchConflict: FilesConflict? = null,
    /**
     * Last read-only free-space measurement of the selected partition.
     * Kept for selected-row access / write-state; batch results also land in
     * [partitionUsage]. Rendered only while its key still matches the live
     * selection; invalidated on a new attachment, geometry change and every
     * finished write, so a value measured before a write can never be shown.
     */
    val filesystemUsage: FilesystemUsageSnapshot? = null,
    /** Id of the newest selected/batch measurement request; answers to older requests are dropped. */
    val filesystemUsageGeneration: Long = 0L,
    /**
     * Measured free-space by exact [FilesystemUsageKey]. Survives partition
     * selection changes; cleared on attachment/geometry/write invalidation.
     */
    val partitionUsage: Map<FilesystemUsageKey, FilesystemUsageSnapshot> = emptyMap(),
    /**
     * In-flight batch phases keyed by partition id. Cleared when the batch
     * finishes, is cancelled, or the attachment is replaced. Never stores a
     * fabricated percent.
     */
    val partitionUsagePhases: Map<String, FilesystemUsagePhase> = emptyMap(),
    /** Monotonic fence for every batch; stale step answers are ignored after invalidation. */
    val filesystemUsageBatchGeneration: Long = 0L,
    val operation: OperationProgress? = null,
    val pendingConfirmation: DestructiveConfirmation? = null,
    /** Exact EFI override target awaiting explicit UI confirmation. */
    val pendingEfiWriteBinding: PartitionBinding? = null,
    val linkOverwriteCandidate: String? = null,
    /** Device identity that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateIdentity: String? = null,
    /** Attach generation that produced [linkOverwriteCandidate]. */
    val linkOverwriteCandidateGeneration: Long? = null,
    /** Full set of image paths whose link needs explicit overwrite. */
    val linkOverwriteCandidatePaths: List<String> = emptyList(),
    val linkOverwriteCandidateTemplatePath: String? = null,
    /**
     * True while an accepted eject runs on IO; blocks files/disk intents and the
     * overflow eject entry. Cleared only by [UiIntent.EjectResulted].
     */
    val ejectInFlight: Boolean = false,
    /** Monotonic id of the newest eject request; stale completions are dropped. */
    val ejectGeneration: Long = 0L,
    /** Attachment identity pinned at eject accept; completion must still match it. */
    val ejectIdentity: String? = null,
    /** Attach generation pinned at eject accept; completion must still match it. */
    val ejectAttachGeneration: Long? = null,
    /**
     * Successful eject marker: owned caches flushed and closed for this attachment.
     * No automatic reopen or probe may clear it; only explicit rescan or a new
     * attachment generation does.
     */
    val ejected: Boolean = false,

    val statusMessage: UiMessage? = null,
    /**
     * Bumps on every new transient message, even when [statusMessage] repeats the previous
     * value, so the shell can show each emission once. Clearing a message never bumps.
     */
    val statusSequence: Long = 0L,
    /**
     * [statusSequence] at the moment the current operation reached a terminal state.
     * A later sequence belongs to a newer standalone rejection and must stay visible
     * instead of being masked by the still-present terminal operation.
     */
    val operationStatusSequence: Long = 0L,
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
    val rawSourceCandidatesFailure: OperationFailure? = null,
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
    /** Image row waiting for its own XML file; survives leaving the Ventoy screen. */
    val windowsLinkTargetImage: String? = null,
    /** Absolute image paths ticked in the XML section before one explicit bind. */
    val windowsSelectedImages: List<String> = emptyList(),
    /** Existing auto_install bindings; null = not loaded. */
    val windowsImageLinks: List<WindowsImageLink>? = null,
    val windowsImageLinksGeneration: Long = 0L,
    val windowsImageLinksIdentity: String? = null,
    /** [VentoySettingsSource.cacheKey] that produced [windowsImageLinks]. */
    val windowsImageLinksSource: String? = null,
    /** XML template file on the stick; never generated or rewritten by the app. */
    val windowsTemplatePath: String? = null,
    /** Provenance of [windowsTemplatePath]; null when no template is selected. */
    val windowsTemplatePin: FilesPickSelection? = null,
    /** Bindings selected for removal; confirmation only edits ventoy.json. */
    val pendingWindowsUnlink: List<String>? = null,
    /** Images to drop from selection only after a successful unlink reload. */
    val windowsPendingRemoval: Set<String> = emptySet(),
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
    /**
     * Raw full-text XML apply waiting for overwrite confirmation. Distinct from
     * [pendingAutounattendGeneration]; Cancel clears this without writing.
     */
    val pendingAutounattendRawWrite: PendingAutounattendRawWrite? = null,
    /** Real `control` read result; null = not loaded. Missing/Empty/ReadError stay distinct. */
    val ventoyGlobalSettings: VentoyGlobalSettings? = null,
    /**
     * Explicit control writes chosen by the user. Default applies merge in place; selected USB
     * or phone sources are imported into `/ventoy/ventoy.json` after identity/source checks.
     * A null value removes one key. Empty means no row override for a whole-document import.
     */
    val pendingVentoyControls: List<VentoyControlSetting> = emptyList(),
    /** Folder the lazy browser currently shows. Always normalized and absolute; `/` is the
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
    /** File the global-settings block reads and edits; default is Ventoy's own path. */
    val ventoySettingsSource: VentoySettingsSource = VentoySettingsSource.Default,
    /**
     * Identity/attach generation the source was chosen on. A result for another stick is
     * stale even if the path looks the same. Reset with the source, not with its rows.
     */
    val ventoySettingsSourceIdentity: String? = null,
    val ventoySettingsSourceAttachGeneration: Long? = null,
    /** Geometry of the Ventoy data partition when a USB file was chosen (null = not pinned). */
    val ventoySettingsSourcePartitionId: String? = null,
    val ventoySettingsSourceStartLba: ULong? = null,
    val ventoySettingsSourceSectorCount: ULong? = null,
    /** A source read is on its way; null settings alone is not "in progress". */
    val ventoySettingsSourceLoading: Boolean = false,
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
    /**
     * User-chosen SAF tree shared by image+map folders and GPT copies. Persisted by the
     * host; [backupFolderUsable] is false when the persisted grant is gone or the tree
     * cannot be queried, so the next create asks for the folder again instead of writing
     * to an unknown location.
     */
    val backupFolderUri: String? = null,
    val backupFolderName: String? = null,
    val backupFolderUsable: Boolean = false,
    /** Stored byte length of the staged copy; null means unknown and no range is offered. */
    val stagedRawImageSizeBytes: Long? = null,
    /**
     * Exact decoded length measured for the active raw source. Null until a cheap stored
     * size is trusted or the cancellable decode-count pass finishes.
     */
    val rawDecodedSizeBytes: Long? = null,
    /** Source key the measurement belongs to; a changed source invalidates it. */
    val rawDecodedSizeSource: String? = null,
    val rawDecodedSizeError: String? = null,
    val rawDecodedErrorKind: RawMeasureError? = null,
    /** SHA-256 of the measured decoded bytes; binds confirmation to SAF content. */
    val rawDecodedSha256: String? = null,
    /** Opt-in readback pass after a raw write; disabled skips a full source+disk reread. */
    val rawVerifyReadback: Boolean = true,
    /** True while the decode-count pass for the current source is running. */
    val rawSizeMeasuring: Boolean = false,
    /** Waiting for a positive capacity/geometry before a bounded pass may start. */
    val rawMeasurementPending: Boolean = false,
    /** Waiting for the previous provider read to return; never means size is measured. */
    val rawMeasurementWaitingForPrevious: Boolean = false,
    /** Monotonic id so a late measurement cannot describe a newer source. */
    val rawSizeGeneration: Long = 0L,
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
    /** SAF tree the restore image/map came from; checkpoints are published back here. */
    val rescueArchiveTreeUri: String? = null,
    /**
     * Last manual SMART probe for the current attachment. Cleared on detach / other stick.
     * Never auto-started.
     */
    val smartResult: SmartOutcome? = null,

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
     * Log lines scoped to the current operation (and idle gap after clear). Same cap as
     * [logLines]; cleared when a new operation begins and when the operation is cleared.
     * Status dialog reads this list, not the full journal.
     */
    val operationLogLines: List<String> = emptyList(),
    /**
     * Release bundled with this APK. Read from the real bundled assets at startup; null
     * until that read lands. Never guessed from the current download.
     */
    val bundledVentoyRelease: String? = null,
    /**
     * `VENTOY_VERSION` read back from the attached stick's VTOYEFI partition. Null means
     * "not detected" and renders as n/a. Cleared when the attachment changes.
     */
    val installedVentoyRelease: String? = null,
    /**
     * FAT/IO failure while reading the installed marker. Distinct from an absent marker
     * ([installedVentoyRelease] == null with this null), which renders as n/a.
     */
    val installedVentoyReadError: String? = null,
    /**
     * Latest official Ventoy release reported by upstream. Null means unavailable;
     * [upstreamVentoyError] explains a failed check.
     */
    val upstreamVentoyRelease: String? = null,
    val upstreamVentoyError: String? = null,
    /** True/false when upstream check classified a tag; null until then. */
    val upstreamVentoyPinned: Boolean? = null,
    /**
     * Explicit app-private release store fact. Only [VentoyReleaseStoreState.Verified]
     * may feed Install/Update from downloaded bytes; [VentoyReleaseStoreState.Absent] may
     * use bundled assets. Unknown/corrupt stores block destructive source selection.
     */
    val ventoyStoreState: VentoyReleaseStoreState = VentoyReleaseStoreState.Absent,
    /** Incremented whenever the readable store changes, fencing late startup loads. */
    val ventoyStoreRevision: Long = 0L,
    /** Monotonic upstream-check id; answers with an older id are stale and dropped. */
    val ventoyUpstreamRequest: Long = 0L,
    val ventoyUpstreamChecking: Boolean = false,
    /** Monotonic download id; answers with an older id are stale and dropped. */
    val ventoyDownloadRequest: Long = 0L,
    val ventoyDownloadInFlight: Boolean = false,
    /** Last download failure, kept until the next attempt; never replaces the release fact. */
    val ventoyDownloadError: String? = null,
    /**
     * Official Plugson control-key catalog. Starts with the bundled pinned-release keys so
     * the picker works offline; a successful manual refresh replaces it. A network failure
     * keeps the previous value and sets [ventoyControlKeyCatalogError] instead of pretending
     * the list is empty.
     */
    val ventoyControlKeyCatalog: VentoyControlKeyCatalog? =
        VentoyBundledControlKeyCatalog.catalog,
    val ventoyControlKeyCatalogError: String? = null,
    val ventoyControlKeyCatalogRequest: Long = 0L,
    val ventoyControlKeyCatalogLoading: Boolean = false,
) {
    companion object { const val LOG_MAX_LINES = 200 }
    /** Verified downloaded bytes, only when the store fact is trustworthy. */
    val downloadedVentoyRelease: VentoyReleaseProvenance?
        get() = (ventoyStoreState as? VentoyReleaseStoreState.Verified)?.provenance

    /** Install/update may either use verified downloaded bytes or the bundled fallback. */
    val ventoyInstallAvailable: Boolean
        get() = ventoyStoreState is VentoyReleaseStoreState.Absent ||
            ventoyStoreState is VentoyReleaseStoreState.Verified

    /** Attachment physically usable: present, not ejecting or ejected. */
    val deviceReady: Boolean
        get() = presence == DevicePresence.Ready && !ejectInFlight && !ejected

    /** One serialized disk operation owns the attachment; local UI work stays allowed. */
    val deviceBusy: Boolean
        get() =
            operation?.state == OperationState.Running ||
                operation?.state == OperationState.Queued

    val actionsEnabled: Boolean
        get() = deviceReady && !deviceBusy && pendingConfirmation == null

    val isRunning: Boolean
        get() = operation?.state == OperationState.Running

    /** Selected target only; missing selection never falls back to another partition. */
    fun selectedPartition(): PartitionTab? =
        selectedPartitionId?.let { id -> partitions.firstOrNull { it.id == id } }

    /** Files-tab volumes: real partitions plus the read-only whole-disk image volume. */
    val filesVolumes: List<PartitionTab>
        get() = partitions + listOfNotNull(wholeDiskVolume)

    fun selectedFilesVolume(): PartitionTab? =
        selectedPartitionId?.let { id -> filesVolumes.firstOrNull { it.id == id } }
            ?: filesVolumes.firstOrNull()

    /**
     * Ventoy data partition: fixed part1 of the Ventoy layout, never the EFI part. Geometry
     * is required, so a pick session cannot pin a partition whose sectors are unknown.
     */
    fun ventoyDataPartition(): PartitionTab? =
        partitions.firstOrNull {
            it.id == "1" &&
                !it.readOnly &&
                !it.isEfiType &&
                it.startLba != null &&
                it.sectorCount != null
        }

    /** Latest confirmed pick for [purpose], or null when nothing was confirmed. */
    fun filesPickSelection(purpose: FilesPickPurpose): FilesPickSelection? =
        filesPickSelections[purpose]

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
     * Write state for [partition]. EFI policy blocks writes before driver facts; otherwise
     * report only access from a matching mounted-handle measurement (map or selected).
     */
    fun partitionWriteAccess(partition: PartitionTab): PartitionWriteAccess {
        if (partition.filesystemId == FilesystemId.ISO) {
            return PartitionWriteAccess.ReadOnly
        }
        if (!isEfiWriteAllowed(partition)) return PartitionWriteAccess.ReadOnly
        val access = partitionUsageSnapshot(partition)?.access
        return when (access) {
            FilesystemAccess.ReadOnly -> PartitionWriteAccess.DriverReadOnly
            FilesystemAccess.ReadWrite -> PartitionWriteAccess.ReadWrite
            null, FilesystemAccess.Unknown -> PartitionWriteAccess.Unknown
        }
    }

    /** Write-protected system partitions (ESP, EFI type, or VTOYEFI name). */
    val efiPartitions: List<PartitionTab>
        get() = partitions.filter { it.readOnly }
}

/**
 * The only way transitions publish a transient message: every non-null message bumps
 * [AppUiState.statusSequence], so a repeated message is still a new emission for the shell.
 * Clearing with null keeps the sequence untouched.
 */
internal fun AppUiState.withStatus(message: UiMessage?): AppUiState =
    copy(
        statusMessage = message,
        statusSequence = if (message != null) statusSequence + 1 else statusSequence,
    )

/**
 * True while the pinned attachment, selected partition and exact geometry are still live.
 * A probe on the same stick keeps identity+generation, so a temporary Busy state does not
 * drop the session; only a real replace/detach or a changed partition does.
 */
fun FilesPickMode.matches(state: AppUiState): Boolean {
    val device = state.device ?: return false
    if (device.identity != identity) return false
    if (device.attachGeneration != attachGeneration) return false
    if (state.selectedPartitionId != partitionId) return false
    val live = state.filesVolumes.firstOrNull { it.id == partitionId } ?: return false
    return live.startLba == startLba && live.sectorCount == sectorCount
}

/** True when [binding] still names the selected device, partition and exact geometry. */
fun PartitionBinding.matches(state: AppUiState): Boolean {
    val device = state.device ?: return false
    if (device != this.device) return false
    if (state.selectedPartitionId != partitionId) return false
    val partition = state.filesVolumes.firstOrNull { it.id == partitionId } ?: return false
    return partition.startLba == startLba && partition.sectorCount == sectorCount
}

/**
 * Display name the raw writer will show, preferring the staged label, then the SAF name.
 * Never invents a name: null means the confirmation must be refused.
 */
val AppUiState.rawWriteSourceName: String?
    get() = stagedRawImageLabel?.takeIf { it.isNotBlank() }
        ?: imageName?.takeIf { it.isNotBlank() }
        ?: DestructiveConfirmation.fileLabel(stagedRawImagePath)
        ?: DestructiveConfirmation.fileLabel(imageUri)

enum class RawMeasureError { Busy, CloseCapacity, Failed }

/** Active source key: staged copy wins over the SAF image. */
val AppUiState.rawSourceKey: String?
    get() = stagedRawImagePath ?: imageUri

/**
 * Decoded length that may size the raw range. Only an exact measurement for the active
 * source is accepted; a compressed or unknown source waits for the decode-count pass.
 * The writer independently re-measures and refuses a changed source before writing.
 */
fun AppUiState.rawWriteDecodedBytes(): Long? {
    val key = rawSourceKey ?: return null
    if (rawDecodedSizeSource != key) return null
    return rawDecodedSizeBytes
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
 * Measured snapshot for [partition]: [partitionUsage] first, then the selected
 * [filesystemUsage] fallback when its key still matches. Never invents space.
 */
fun AppUiState.partitionUsageSnapshot(partition: PartitionTab): FilesystemUsageSnapshot? {
    val key = filesystemUsageKey(partition) ?: return null
    partitionUsage[key]?.takeIf { it.matches(key) }?.let { return it }
    return filesystemUsage?.takeIf { it.matches(key) }
}

/** In-flight batch phase for [partition], or null when idle/measured. */
fun AppUiState.partitionUsagePhase(partition: PartitionTab): FilesystemUsagePhase? =
    partitionUsagePhases[partition.id]

/**
 * Measurable partitions for a sequential batch: selected first, then the rest in
 * table order. Entries without a full key are omitted (unsupported/unknown FS).
 */
fun AppUiState.measurableFilesystemUsageKeys(): List<Pair<String, FilesystemUsageKey>> {
    val selectedId = selectedPartitionId
    val ordered =
        if (selectedId == null) {
            partitions
        } else {
            val selected = partitions.firstOrNull { it.id == selectedId }
            val rest = partitions.filter { it.id != selectedId }
            if (selected == null) partitions else listOf(selected) + rest
        }
    return ordered.mapNotNull { partition ->
        filesystemUsageKey(partition)?.let { partition.id to it }
    }
}

/**
 * Whether the host must ask for a fresh selected measurement. Invalidation drops
 * snapshots, so a matching key alone is not proof. While a request for the same key
 * is still in flight the host does not ask again. Reads only the legacy selected
 * slot — [partitionUsage] is owned by the batch path and must not suppress this.
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
 * Whether any measurable partition still needs a batch pass. Used by the host so
 * selected rows read the map/cache and never start a parallel legacy measure.
 */
fun AppUiState.needsFilesystemUsageBatch(
    lastBatchKeys: Set<FilesystemUsageKey>?,
    batchInFlight: Boolean,
    operationFinished: Boolean,
): Boolean {
    val planned = measurableFilesystemUsageKeys()
    if (planned.isEmpty()) return false
    if (batchInFlight) return false
    // Paused (Waiting) work resumes only through an invalidation that drops the phases.
    if (partitionUsagePhases.isNotEmpty()) return false
    val plannedKeys = planned.map { it.second }.toSet()
    val missing = plannedKeys.any { key -> partitionUsage[key]?.matches(key) != true }
    if (!missing) return false
    // A finished write cleared the map; retry even though the plan is unchanged.
    if (operationFinished) return true
    return lastBatchKeys != plannedKeys
}

/**
 * Whole-MiB math for the new-partition slider. Exact [ULong] bounds: Float only carries
 * the thumb position, never the boundary value, so rounding cannot cross the free region.
 */
