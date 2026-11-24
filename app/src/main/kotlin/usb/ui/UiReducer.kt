package usb.ui

import usb.fs.FilesystemId
import usb.fs.FilesystemUsage
import usb.fs.FilesystemAccess
import usb.fs.FilesystemUsageKey
import usb.partition.RawSchemeProblem
import usb.partition.TableScheme
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendState
import usb.ventoy.XmlFieldIntent
import usb.ventoy.FolderReadProblem
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoyReleaseSource
import usb.ventoy.VentoySettingsSource
import usb.ventoy.XmlPath
import usb.ventoy.autounattendPlanSha256
import usb.service.OperationToken

/**
 * Pure state transitions for the four-screen shell.
 * Compose and JVM tests drive the same reducer; no USB I/O here.
 */
sealed class UiIntent {
    data class SelectTab(val tab: ShellTab) : UiIntent()

    data class SelectPartition(val partitionId: String) : UiIntent()
    data class SelectFilesPickPartition(val partitionId: String) : UiIntent()

    /**
     * Open simplified Files picker. Partition and attachment expectations come from the
     * dialog opener; mismatches fail closed instead of binding a stale callback to new USB.
     */
    data class BeginFilesPick(
        val purpose: FilesPickPurpose,
        val returnTab: ShellTab,
        val target: FilesPickTarget = FilesPickTarget.File,
        val partitionId: String? = null,
        val initialPath: String = "/",
        val launchSource: FilesPickLaunchSource = FilesPickLaunchSource.Files,
        val expectedIdentity: String? = null,
        val expectedAttachGeneration: Long? = null,
    ) : UiIntent()

    data class WindowsImportPickerFinished(
        val pickSessionId: String,
        val cancelled: Boolean,
        val failure: UiMessageKey? = null,
        val launchSource: FilesPickLaunchSource = FilesPickLaunchSource.Files,
    ) : UiIntent()
    /** Leave the picker without a result and return to [FilesPickMode.returnTab]. */
    data object CancelFilesPick : UiIntent()

    /** Select one regular [name] from the current listing; the reducer builds the path. */
    data class ConfirmFilesPickFile(val name: String) : UiIntent()

    /** Confirm the currently open folder (root included). */
    data object ConfirmFilesPickFolder : UiIntent()

    /**
     * The host finished a phone-to-stick copy of [path]. Posted only after the operation is
     * Completed and the volume is durably closed; never on Cancelled/Partial/Failed.
     */
    data class FilesPickCopySucceeded(
        val path: String,
        val sizeBytes: Long? = null,
        /** Session that started the copy; a newer session never accepts this result. */
        val pickSessionId: String? = null,
    ) : UiIntent()

    /** Drop the stored result for one purpose, or every purpose when null. */
    data class ClearFilesPickSelection(val purpose: FilesPickPurpose? = null) : UiIntent()
    /** Request application EFI write-protection override; reducer snapshots exact binding. */
    data class RequestSetVtoyEfiWritable(val target: PartitionBinding) : UiIntent()
    /** Confirm exact EFI override target after explicit user approval. */
    data class ConfirmSetVtoyEfiWritable(val target: PartitionBinding) : UiIntent()
    /** Confirm previously requested EFI override against current exact attachment geometry. */
    data object DismissEfiWriteConfirmation : UiIntent()
    /** Lift or restore write protection for exactly [partitionId] on the current attachment. */
    data class SetVtoyEfiWritable(val partitionId: String, val enabled: Boolean) : UiIntent()
    data class SetVtoyBypassChecks(val enabled: Boolean?) : UiIntent()
    data class SetVtoyLocalAccount(val enabled: Boolean?) : UiIntent()

    /** Choose filesystem for the next Format action (must be in FormatCatalog.supportedFormatIds). */
    data class SelectFormatFilesystem(val id: FilesystemId) : UiIntent()

    data class DeviceChanged(
        val presence: DevicePresence,
        val device: DeviceIdentity? = null,
        val partitions: List<PartitionTab> = emptyList(),
        val wholeDiskVolume: PartitionTab? = null,
        val files: List<FileEntry> = emptyList(),
        val freeRegions: List<FreeRegionUi> = emptyList(),
        val sectorSize: Int = 512,
        val tableValid: Boolean = true,
        val partitionTableDamaged: Boolean = false,
        val partitionTableAmbiguous: Boolean = false,
        val partitionScheme: TableScheme? = null,
        val partitionTableRecognized: Boolean = true,
        val partitionTableRecognition: usb.partition.TableRecognition =
            usb.partition.TableRecognition.RECOGNIZED,
        val metadataFingerprint: String? = null,
        val initializationMetadataFingerprintGpt: String? = null,
        val initializationMetadataFingerprintMbr: String? = null,
        val rawGeometry: usb.partition.RawRangeGeometry? = null,
        /** False when release failed: stale Busy geometry must not survive. */
        val retainExisting: Boolean = true,
    ) : UiIntent()
    data class OperationBegan(val progress: OperationProgress) : UiIntent()
    data class OperationUpdated(val progress: OperationProgress) : UiIntent()
    data class OperationCleared(val token: OperationToken) : UiIntent()
    data class OperationCompletionEffects(
        val token: OperationToken,
        val attachmentGeneration: Long,
        val title: OperationTitle,
        val state: OperationState,
    ) : UiIntent()
    data class RequestDestructive(val action: DestructiveAction) : UiIntent()
    /**
     * Create a partition in the validated free region, optionally formatting it in the same
     * operation. [name] is a GPT entry name (null/blank = none); [formatId] null = create only;
     * [volumeLabel] is written by the mkfs of [formatId].
     */
    data class RequestCreatePartition(
        val startLba: ULong,
        val sectorCount: ULong,
        val name: String? = null,
        val formatId: FilesystemId? = null,
        val volumeLabel: String = "",
    ) : UiIntent()

    /** Rename GPT entry against the dialog's pinned device and geometry. */
    data class RequestRenamePartition(
        val name: String,
        val target: PartitionBinding? = null,
    ) : UiIntent()

    /** Rename filesystem label against the dialog's pinned device and geometry. */
    data class RequestRenameVolumeLabel(
        val label: String,
        val target: PartitionBinding? = null,
    ) : UiIntent()

    /**
     * Write an empty MBR or GPT table, replacing the current one. The confirmation pins the
     * current scheme and every existing partition geometry; a mismatch aborts before writing.
     */
    data class RequestInitializePartitionTable(val scheme: PartitionScheme) : UiIntent()

    /** One raw tool: the start LBA; the end comes from the decoded image size. */
    data class RequestRawWrite(val startLba: ULong) : UiIntent()

    /** Presence hint for the drafted raw range; never a veto and never contents. */
    data class RequestRangeProbe(val startLba: ULong, val sectorCount: ULong) : UiIntent()
    data class RangeProbeResulted(val result: usb.image.RangeProbeResult) : UiIntent()

    data object ConfirmDestructive : UiIntent()

    data object DismissConfirmation : UiIntent()

    data class RequestLinkOverwrite(
        val fileName: String,
        val imagePaths: List<String> = emptyList(),
        val templatePath: String? = null,
    ) : UiIntent()

    data object DismissLinkOverwrite : UiIntent()

    data class LinkOverwriteAvailable(
        val fileName: String,
        val imagePaths: List<String> = emptyList(),
        val templatePath: String? = null,
    ) : UiIntent()

    data class IsoActionRequested(
        val fileName: String,
        val action: IsoAction,
    ) : UiIntent()

    data class SetImage(
        val uri: String?,
        /** Provider DISPLAY_NAME, so the UI never shows an opaque document id. */
        val name: String? = null,
        val sizeBytes: Long? = null,
    ) : UiIntent()
    data class SetStagedRawImage(
        val path: String,
        val label: String,
        /** Stored byte length of the staged copy; null = unknown, so no range is offered. */
        val sizeBytes: Long? = null,
        /** The staged bytes start with gzip/xz magic, so the name must not size the range. */
        val compressedMagic: Boolean = false,
    ) : UiIntent()
    data class ClearStagedRawImage(val deleteFile: Boolean = true) : UiIntent()

    /**
     * Result of the cancellable decode-count pass for [source]. Accepted only for the
     * current [generation] and active source; bytes null means the pass failed.
     */
    data class RawSourceMeasured(
        val generation: Long,
        val source: String?,
        val bytes: Long?,
        val sha256: String? = null,
        val error: String? = null,
        val errorKind: RawMeasureError? = null,
    ) : UiIntent()
    /** Previous provider read is still closing or blocked; keep current source waiting. */
    data class RawSourceMeasurementWaiting(
        val generation: Long,
        val source: String?,
    ) : UiIntent()

    /** Queued source may start only after previous provider read has actually completed. */
    data class RawSourceMeasurementStarted(
        val generation: Long,
        val source: String?,
    ) : UiIntent()

    /** Toggle the optional post-write source/disk readback for raw writes. */
    data class SetRawVerifyReadback(val enabled: Boolean) : UiIntent()

    /** User explicitly stops a stuck decode-count pass. */
    data object CancelRawSourceMeasurement : UiIntent()

    /** Restart a failed/cancelled decode-count pass for the still-selected source. */
    data object RetryRawSourceMeasurement : UiIntent()

    /** Scan the selected partition for raw-write sources; no extension filter. */
    data object LoadRawSourceCandidates : UiIntent()
    data class RawSourceCandidatesLoaded(
        val generation: Long,
        val identity: String?,
        val candidates: List<RawSourceCandidate>,
        val truncated: Boolean = false,
        val unreadable: Int = 0,
    ) : UiIntent()

    /**
     * Bound raw-source scan failure. Applied only while [generation] and [identity]
     * still match the open chooser request — never a free [Status] that can clobber B.
     */
    data class RawSourceCandidatesFailed(
        val generation: Long,
        val identity: String?,
        val message: String,
    ) : UiIntent()

    /** Stage [path] from the selected partition; selection alone never writes. */
    data class SelectRawSource(val path: String, val name: String) : UiIntent()
    data object CancelRawSourceLoad : UiIntent()

    /** List `.iso` names from the real Ventoy data partition `/ISO`. */
    data object LoadWindowsIsoCandidates : UiIntent()
    data class WindowsIsoCandidatesLoaded(
        val generation: Long,
        val identity: String?,
        /** Absolute paths on the Ventoy data partition; no extension or `/ISO` filter. */
        val candidates: List<RawSourceCandidate>,
        val error: String? = null,
    ) : UiIntent()
    /** Feed one existing data-partition image path into VENTOY_LINK_XML confirmation. */
    data class SelectWindowsIso(val isoName: String) : UiIntent()

    /** Inspect a SAF document for import into the Ventoy data partition. */
    data class RequestImportWindowsIso(
        val uri: String,
        val kind: WindowsImportKind = WindowsImportKind.Image,
        val directory: String? = null,
        /** Immutable pick session that launched the picker; null for a non-picker import. */
        val pickSessionId: String? = null,
        /** Attachment the system picker was launched for; stale XML results are rejected. */
        val expectedIdentity: String? = null,
        val expectedAttachGeneration: Long? = null,
    ) : UiIntent()
    data class WindowsIsoImportInspected(
        val uri: String,
        val name: String,
        val sizeBytes: Long?,
        val targetName: String,
        val exists: Boolean,
        val generation: Long,
        val kind: WindowsImportKind = WindowsImportKind.Image,
        val templateMarkers: List<String> = emptyList(),
        val directory: String? = null,
        /** Echo of the pick session; a result for an older session is dropped. */
        val pickSessionId: String? = null,
        /** Part-1 geometry probed at inspect time; folder-dialog flows carry it too. */
        val partitionStartLba: ULong? = null,
        val partitionSectorCount: ULong? = null,
    ) : UiIntent()
    /** Only an explicit true may replace an existing file. */
    data class ConfirmWindowsIsoImport(val overwrite: Boolean) : UiIntent()
    data class WindowsIsoImportRejected(
        val problem: UnreadableProblem,
        val reason: String? = null,
        /** Inspection generation this result belongs to; stale results are ignored. */
        val generation: Long = 0L,
    ) : UiIntent()
    /**
     * The host closed the disk for inspection [generation]. This is the only signal that ends
     * the USB guard; UI cancel must not, because direct I/O may still be open.
     */
    data class WindowsIsoImportFinished(val generation: Long) : UiIntent()
    /** SAF-phase inspection [generation] ended (result already posted or stale). */
    data class WindowsIsoImportSafFinished(val generation: Long) : UiIntent()
    /** The SAF phase of inspection [generation] handed over to the disk phase. */
    data class WindowsIsoImportUsbStarted(val generation: Long) : UiIntent()
    data object CancelWindowsIsoImport : UiIntent()

    /**
     * A phone import failed because the chosen folder no longer exists. Pinned to the
     * attachment that failed: clears the stale folder list and asks the import card to
     * reopen the folder picker for [kind]. While the UI is not actionable (operation
     * running/queued or a confirmation open) both the re-read and the picker demand are
     * deferred; they are released by the first intent that makes the screen actionable
     * again, so no USB work races the operation and no dialog covers a confirmation.
     */
    data class ReopenImportFolderPick(
        val kind: WindowsImportKind,
        val identity: String?,
        val attachGeneration: Long,
        /** Inspection generation that proved the folder missing; stale failures are ignored. */
        val requestGeneration: Long,
    ) : UiIntent()

    /** The import card consumed [ReopenImportFolderPick] and shows the folder picker. */
    data object ImportFolderPickOpened : UiIntent()

    /** Persist the two global Ventoy Windows flags; they are not per-image. */
    data object SaveWindowsControls : UiIntent()

    /** Add or remove one data-partition image from the pending XML selection. */
    data class ToggleWindowsImage(val path: String) : UiIntent()
    data object ClearWindowsSelection : UiIntent()
    /** Pin the image row whose XML pick is in flight; state survives the tab switch. */
    data class SetWindowsLinkTarget(val image: String?) : UiIntent()
    /**
     * Consume the already-confirmed shared Files pick for [FilesPickPurpose.WindowsTemplate].
     * Takes no path: the reducer atomically reads and clears
     * `filesPickSelections[WindowsTemplate]`, so forged intents can never inject an
     * arbitrary path. The consumed selection is retained as [AppUiState.windowsTemplatePin]
     * for lifetime revalidation. Phone imports keep [WindowsTemplateImported].
     */
    data object SelectWindowsTemplate : UiIntent()
    data object ClearWindowsTemplate : UiIntent()
    /** One explicit confirmation covers every selected image and the template. */
    data class RequestLinkWindowsImages(
        val templatePath: String? = null,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
    ) : UiIntent()

    data object LoadWindowsImageLinks : UiIntent()
    data class WindowsImageLinksLoaded(
        val generation: Long,
        val identity: String?,
        val links: List<WindowsImageLink>,
        val error: String? = null,
        val sourceKey: String? = null,
    ) : UiIntent()

    /** Remove auto_install entries only; image and XML files stay on the stick. */
    data class RequestUnlinkWindowsImages(val imagePaths: List<String>) : UiIntent()
    data object ConfirmWindowsUnlink : UiIntent()
    data object CancelWindowsUnlink : UiIntent()

    data object LoadAutounattendCandidatesOnly : UiIntent()
    data class LoadAutounattend(val xmlName: String) : UiIntent()
    data class AutounattendLoaded(
        val xmlName: String,
        val state: AutounattendState,
        val bindings: List<AutounattendBinding> = emptyList(),
        val generation: Long = 0L,
    ) : UiIntent()
    data class AutounattendCandidatesLoaded(
        val xmlName: String,
        val candidates: AutounattendImageCandidates,
        val generation: Long = 0L,
    ) : UiIntent()

    data class RequestGenerateAutounattend(
        val xmlName: String,
        val params: AutounattendParams,
        val imagePaths: List<String> = emptyList(),
        val overwriteXml: Boolean = false,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
        val planSha256: String? = null,
        val renameFromXmlName: String? = null,
    ) : UiIntent()

    /**
     * Apply raw full-document XML text. Validates before confirmation; Cancel leaves disk
     * untouched. Existing same-target files require overwrite confirmation.
     */
    data class RequestWriteAutounattendRaw(
        val xmlName: String,
        val rawBytes: ByteArray,
        val overwriteXml: Boolean = false,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
        val identity: String? = null,
        val attachGeneration: Long = 0L,
        val renameFromXmlName: String? = null,
    ) : UiIntent() {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is RequestWriteAutounattendRaw) return false
            return xmlName == other.xmlName &&
                rawBytes.contentEquals(other.rawBytes) &&
                overwriteXml == other.overwriteXml &&
                allowDestructiveTemplate == other.allowDestructiveTemplate &&
                expectedTemplateSha256 == other.expectedTemplateSha256 &&
                identity == other.identity &&
                attachGeneration == other.attachGeneration &&
                renameFromXmlName == other.renameFromXmlName
        }

        override fun hashCode(): Int {
            var result = xmlName.hashCode()
            result = 31 * result + rawBytes.contentHashCode()
            result = 31 * result + overwriteXml.hashCode()
            result = 31 * result + allowDestructiveTemplate.hashCode()
            result = 31 * result + (expectedTemplateSha256?.hashCode() ?: 0)
            result = 31 * result + (identity?.hashCode() ?: 0)
            result = 31 * result + attachGeneration.hashCode()
            result = 31 * result + (renameFromXmlName?.hashCode() ?: 0)
            return result
        }
    }
    data class ConfirmDiskLayout(val acknowledged: Boolean) : UiIntent()
    data object DismissDiskLayout : UiIntent()

    /** Global ventoy.json control entries, raw values included. */
    data object LoadVentoyGlobalSettings : UiIntent()
    /**
     * Use the file chosen by the shared Files picker. [identity]/[attachGeneration] came from
     * that picker and must still match the attached stick or the choice is stale.
     */
    data class SelectVentoySettingsUsbFile(
        val path: String,
        val identity: String? = null,
        val attachGeneration: Long? = null,
        val partitionId: String? = null,
        val partitionStartLba: ULong? = null,
        val partitionSectorCount: ULong? = null,
    ) : UiIntent()
    /** Phone document chosen with OpenDocument as read-only import input. */
    data class SelectVentoySettingsDocument(
        val uri: String,
        val displayName: String? = null,
        val identity: String? = null,
        val attachGeneration: Long? = null,
    ) : UiIntent()
    /** Go back to Ventoy's own `/ventoy/ventoy.json`. */
    data object UseDefaultVentoySettingsFile : UiIntent()
    /** Re-read the direct children of [AppUiState.ventoyFolderPath]. */
    data object LoadVentoyFolders : UiIntent()
    /** Enter one listed child folder; unlisted values are ignored as stale. */
    data class OpenVentoyFolder(val path: String) : UiIntent()
    /** Go to the parent folder; a no-op at the data-partition root. */
    data object VentoyFolderUp : UiIntent()
    /**
     * Drop stick-scoped Ventoy/XML catalogs after a disk mutation. Keeps the template path
     * so the Ventoy tab can re-read; never mounts by itself.
     */
    data object InvalidateVentoyStickCaches : UiIntent()
    data class VentoyFoldersLoaded(
        /** Direct child directories of [path], absolute; never recursive. */
        val folders: List<String>,
        /** Localized by the UI; no raw engine text crosses this boundary. */
        val error: FolderReadProblem? = null,
        /** Must match [AppUiState.ventoyCacheEpoch] or the post is stale. */
        val epoch: Long = 0L,
        /** Must match [AppUiState.ventoyFoldersRequest], else an older list arrived late. */
        val requestToken: Long = 0L,
        /** Must match [AppUiState.ventoyFolderPath] or the answer belongs to another folder. */
        val path: String = "/",
    ) : UiIntent()
    data class VentoyGlobalSettingsLoaded(
        val settings: VentoyGlobalSettings,
        /** Must match [AppUiState.ventoyCacheEpoch] or the post is stale. */
        val epoch: Long = 0L,
        /** Must match [AppUiState.ventoyGlobalSettingsRequest], else an older read arrived late. */
        val requestToken: Long = 0L,
        /** Echoes the read source; a result for another file is stale. */
        val source: VentoySettingsSource = VentoySettingsSource.Default,
        /** Live data-partition geometry from the host read; null keeps existing pins. */
        val partitionId: String? = null,
        val partitionStartLba: ULong? = null,
        val partitionSectorCount: ULong? = null,
    ) : UiIntent()
    /** Only catalog-known keys and validated values. null value removes the key. */
    data class StageVentoyControl(val key: String, val value: String?) : UiIntent()
    /** Forget one staged change without touching the stick. */
    data class DiscardStagedVentoyControl(val key: String) : UiIntent()
    /** Restore editor draft on Cancel without touching the selected source. */
    data class RestoreVentoyControls(val settings: List<VentoyControlSetting>) : UiIntent()
    data class CommitVentoyControls(
        val removedKeys: Set<String>,
        val settings: List<VentoyControlSetting>,
        val duplicateKeys: Set<String> = emptySet(),
        /**
         * Pending-only keys dropped in the editor (no on-stick original). Unlike
         * [removedKeys], these are erased from staging rather than staged as removals.
         */
        val discardedKeys: Set<String> = emptySet(),
    ) : UiIntent()
    data class CommitAndApplyVentoyControls(
        val removedKeys: Set<String>,
        val settings: List<VentoyControlSetting>,
        val duplicateKeys: Set<String> = emptySet(),
        val discardedKeys: Set<String> = emptySet(),
    ) : UiIntent()
    /** Legacy direct apply path retained for retry/partial operation callers. */
    data object RequestApplyVentoyControls : UiIntent()
    /** Manual Plugson control-key catalog refresh; never auto-runs and never writes ventoy.json. */
    data object RefreshVentoyControlKeyCatalog : UiIntent()
    /**
     * Answer to [RefreshVentoyControlKeyCatalog]. An older [requestToken] is stale. Failure
     * keeps the previous catalog (network error ≠ empty list).
     */
    data class SetVentoyControlKeyCatalog(
        val requestToken: Long,
        val catalog: usb.ventoy.VentoyControlKeyCatalog? = null,
        val error: String? = null,
    ) : UiIntent()
    data object CheckVentoyUpstream : UiIntent()
    /**
     * Answer to [CheckVentoyUpstream]. An older [requestToken] is stale; the check is
     * global, so an in-flight answer lands even if the stick detached mid-flight.
     */
    data class SetVentoyUpstreamRelease(
        val requestToken: Long,
        val tag: String?,
        val pinned: Boolean? = null,
        val error: String? = null,
    ) : UiIntent()
    /** Legacy direct request; reducer rejects an unpinned latest without a bound confirmation. */
    data object RequestDownloadVentoyRelease : UiIntent()
    /** Download only exact latest fact shown in the risk dialog. */
    data class ConfirmVentoyReleaseDownload(
        val release: String,
        val pinned: Boolean,
        val upstreamRequest: Long,
    ) : UiIntent()
    data class VentoyReleaseDownloaded(
        val requestToken: Long,
        val provenance: usb.ventoy.VentoyReleaseProvenance,
    ) : UiIntent()
    data class VentoyReleaseDownloadFailed(
        val requestToken: Long,
        val message: String,
    ) : UiIntent()
    /**
     * The save failed after changing the readable store: either a recovered/promoted
     * release remains, or publication happened without a confirmed directory fsync.
     * Keep the new provenance and the warning; this is never a clean success.
     */
    data class VentoyReleaseStoreChanged(
        val requestToken: Long,
        val provenance: usb.ventoy.VentoyReleaseProvenance,
        val message: String,
    ) : UiIntent()

    /** The store was mutated but cannot be verified; drop cached provenance. */
    data class VentoyReleaseStoreUnknown(
        val requestToken: Long,
        val message: String,
    ) : UiIntent()
    /** Bundled release read from the APK assets at startup. */
    data class SetVentoyBundledRelease(val release: String?) : UiIntent()
    /**
     * App-private store fact loaded at startup. [revision] is captured before the IO, so a
     * late load cannot overwrite a download/store change that finished while it read.
     */
    data class SetVentoyStoreState(
        val revision: Long,
        val storeState: usb.ventoy.VentoyReleaseStoreState,
    ) : UiIntent()
    /**
     * Real `VENTOY_VERSION` read from the stick. Dropped unless the attachment pin still
     * matches, so a probe from the previous stick cannot show its version on the next one.
     * [error] is a FAT/IO failure and stays distinct from an absent marker.
     */
    data class SetVentoyInstalledRelease(
        val release: String?,
        val attachGeneration: Long,
        val identity: String?,
        val error: String? = null,
    ) : UiIntent()

    data class RequestBindAutounattend(
        val xmlName: String,
        val imagePaths: List<String>,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
        val overwrite: Boolean = false,
    ) : UiIntent()
    data class RequestUnbindAutounattend(
        val xmlName: String,
        val imagePath: String,
    ) : UiIntent()
    data class AutounattendBindingsLoaded(
        val bindings: List<AutounattendBinding>,
        val error: String? = null,
    ) : UiIntent()

    /**
     * Real phone copy (or generated file) finished; the host verified the copy on this
     * attachment. Pin still revalidated against live state; a stale attachment or
     * partition geometry refuses instead of reading another stick's bytes. Write
     * errors surface via [UiEffect.OperationUpdated], never hidden here.
     */
    data class WindowsTemplateImported(
        val path: String,
        val identity: String,
        val attachGeneration: Long,
        val partitionStartLba: ULong,
        val partitionSectorCount: ULong,
    ) : UiIntent()

    /**
     * Persisted SAF tree for image+map folders and GPT copies. [usable] is false when the
     * grant is gone; the UI then asks again instead of writing to an unknown tree.
     */
    data class SetBackupFolder(
        val uri: String?,
        val name: String? = null,
        val usable: Boolean = false,
    ) : UiIntent()

    /** SAF [uri] of the GPT critical-sector backup chosen for the next restore. */
    data class SetBackupFile(
        val uri: String?,
        val inspection: GptBackupInspection? = null,
        /** Monotonic pick id; a result older than the stored id is dropped. */
        val requestId: Long = 0L,
    ) : UiIntent()
    data class SetRescueImage(
        val uri: String?,
        val inspection: RescueImageInspection? = null,
        val requestId: Long = 0L,
    ) : UiIntent()
    data class SetRescueArchiveTree(val treeUri: String?) : UiIntent()

    /** Clear the image+map restore pair without touching the shared backup folder. */
    data object ResetRescueSelection : UiIntent()

    /** Clear the GPT restore file without touching the shared backup folder. */
    data object ResetGptSelection : UiIntent()

    data class SetRescueMap(
        val uri: String?,
        val inspection: RescueMapInspection? = null,
        val requestId: Long = 0L,
    ) : UiIntent()

    /** Read-only scan of every sector; reports unreadable ranges. */
    data object StartReadScan : UiIntent()

    /** Manual read-only SAT SMART / IDENTIFY probe. Never auto-started. */
    data object StartSmartRead : UiIntent()


    /** Read-only full image plus rescue map into the app's external files dir. */
    data object StartRescueBackup : UiIntent()

    /** Capture the GPT critical sectors into the app's external files dir. */
    data object CaptureGptBackup : UiIntent()

    /** Toggle the only two theme states; dark is the default. */
    data object ToggleTheme : UiIntent()

    /** Switch the in-app language; persisted by the host. */
    data class SetLanguage(val language: AppLanguage) : UiIntent()

    /** Run the automated self-test suite over all functional engines. */
    data object RunSelfTest : UiIntent()

    data class Status(val message: UiMessage?) : UiIntent()

    data class CancelOperation(val token: OperationToken) : UiIntent()

    /**
     * Enter the absolute FAT directory [path]. Clears [AppUiState.files] and emits a
     * [UiEffect.LoadFiles] effect, so nothing is displayed for an unlisted path.
     */
    data class OpenDirectory(val path: String) : UiIntent()

    /** Go to the parent of [AppUiState.currentPath]; no-op at the root. */
    data object NavigateUp : UiIntent()

    /**
     * Manual re-list of [AppUiState.currentPath]. Always starts a new request, so an older
     * in-flight list answer is dropped instead of painting the screen after this one.
     */
    data object RefreshFiles : UiIntent()

    /** Create a real folder named [name] inside the captured [path] on the pinned volume. */
    data class CreateFolder(
        val name: String,
        val path: String,
        val binding: PartitionBinding,
    ) : UiIntent()

    /** Delete entry [name] from [AppUiState.currentPath] on the volume. */
    data class DeleteEntry(val name: String) : UiIntent()

    /** Rename entry [from] to [to] on exactly the captured device/partition. */
    data class RenameEntry(
        val from: String,
        val to: String,
        val binding: PartitionBinding,
    ) : UiIntent()

    /** Request confirmation to change selected partition type, with optional exact target. */
    data class RequestSetPartitionRole(
        val role: PartitionRole,
        val target: PartitionBinding? = null,
    ) : UiIntent()

    /** Request confirmation to change selected partition bootable flag, with optional target. */
    data class RequestSetPartitionBootable(
        val bootable: Boolean,
        val target: PartitionBinding? = null,
    ) : UiIntent()

    /** Stream the SAF [uri] into [AppUiState.currentPath] in chunks. */
    data class UploadFile(val uri: String) : UiIntent()

    /** Copy exact [binding] from the pinned directory to SAF destination [uri]. */
    data class DownloadFile(
        val binding: FilesDownloadBinding,
        val uri: String,
        /** Destination already picked; listing may be stale after process death. */
        val fromPicker: Boolean = false,
    ) : UiIntent()

    /** Delete every entry in [selection] after UI confirmation; reducer re-checks pin/write. */
    data class DeleteBatch(val selection: FilesBatchSelection) : UiIntent()

    /** Copy [selection] into SAF tree [treeUri]; requires [FilesBatchSelection.matches]. */
    data class DownloadBatch(
        val selection: FilesBatchSelection,
        val treeUri: String,
    ) : UiIntent()

    /**
     * Upload pinned SAF documents into [directory] on the captured partition geometry.
     * Pin fields must still match before the effect is emitted.
     */
    data class UploadBatchDocs(
        val directory: String,
        val uris: List<String>,
        val attachment: UiAttachmentPin,
    ) : UiIntent()

    /** Host parked a batch name collision; UI shows Skip/Replace(file)/Cancel. */
    data class FilesBatchConflict(val conflict: FilesConflict) : UiIntent()

    /**
     * Answer [AppUiState.pendingFilesBatchConflict]. ReplaceFile is rejected for directories.
     * Releases dialog state then emits [UiEffect.ContinueFilesBatch] for the host to resume
     * with a fresh pin — never holds the USB lock across the dialog.
     */
    data class ResolveFilesBatchConflict(val decision: FilesConflictDecision) : UiIntent()


    /**
     * Result of a real FAT list of [path] on [partitionId]. Applied only while that path,
     * partition, identity, attach generation and [generation] still match the display.
     */
    data class FilesListed(
        val path: String,
        val files: List<FileEntry>,
        val generation: Long,
        val identity: String?,
        val partitionId: String,
        val attachGeneration: Long,
    ) : UiIntent()

    /**
     * Real list failure for [path] on [partitionId]; displayed entries are voided, never
     * faked. Same freshness guards as [FilesListed].
     */
    data class FilesListFailed(
        val path: String,
        val message: String,
        val generation: Long,
        val identity: String?,
        val partitionId: String,
        val attachGeneration: Long,
    ) : UiIntent()

    /** Append a timestamped line to the bounded operation log. */
    data class AppendLog(val line: String) : UiIntent()

    /** Explicit app-scoped eject request from the overflow menu; host fences it. */
    data object RequestEject : UiIntent()
    /**
     * Host eject verdict for [ejectGeneration]. Accepted only while the same attachment
     * is live and [ejectGeneration] is still newest; anything else is stale and dropped.
     */
    data class EjectResulted(
        val ejectGeneration: Long,
        val identity: String?,
        val attachGeneration: Long,
        val succeeded: Boolean,
        val busy: Boolean = false,
        val message: String? = null,
    ) : UiIntent()
    /** Explicit rescan after a successful eject; never automatic and never after failure. */
    data object RequestEjectRescan : UiIntent()

    /**
     * Drop the in-app log immediately, without confirmation and regardless of a running
     * operation. New lines may still arrive; Android logcat is not touched.
     */
    data object ClearLog : UiIntent()

    /**
     * Ask for a sequential free-space batch of every measurable partition. The host
     * answers with [FilesystemUsageStepResulted] / [FilesystemUsageStepDeferred] per
     * step under the files lock. Selected rows read the map; this intent replaces a
     * parallel selected-only measure.
     */
    data object RequestFilesystemUsageBatch : UiIntent()

    /**
     * Legacy selected-only request kept for tests and rebinding. Production host
     * schedules [RequestFilesystemUsageBatch] instead so one claim owns measurement.
     */
    data object RequestFilesystemUsage : UiIntent()

    /**
     * Measurement bound to [key]. Published only when [generation] is still the newest
     * request and [key] still matches the live attachment, partition and filesystem;
     * anything else is a stale value and is dropped instead of being shown.
     */
    data class FilesystemUsageResulted(
        val generation: Long,
        val key: FilesystemUsageKey,
        val usage: FilesystemUsage,
        /** Driver mount mode only; not physical USB write capability. */
        val access: FilesystemAccess = FilesystemAccess.Unknown,
    ) : UiIntent()

    /**
     * One sequential batch step. Accepted when [generation] matches and [key] still
     * describes the live partition with [partitionId] (not only the selection).
     */
    data class FilesystemUsageStepResulted(
        val generation: Long,
        val partitionId: String,
        val key: FilesystemUsageKey,
        val usage: FilesystemUsage,
        val access: FilesystemAccess = FilesystemAccess.Unknown,
    ) : UiIntent()

    /**
     * Batch paused for this step: exclusive claim busy or a user operation owns the
     * session. Marks [FilesystemUsagePhase.Waiting]; never stores Unavailable.
     */
    data class FilesystemUsageStepDeferred(
        val generation: Long,
        val partitionId: String,
        val key: FilesystemUsageKey,
    ) : UiIntent()

    /**
     * Host is about to contend for the files session lock for [partitionId]. Shown as
     * Waiting: Calculating is published only after the lock is held and space() starts.
     */
    data class FilesystemUsageStepWaiting(
        val generation: Long,
        val partitionId: String,
        val key: FilesystemUsageKey,
    ) : UiIntent()

    /**
     * Host entered the session lock and is about to call space() for [partitionId].
     * Calculating must not be shown while merely waiting on the lock.
     */
    data class FilesystemUsageStepCalculating(
        val generation: Long,
        val partitionId: String,
        val key: FilesystemUsageKey,
    ) : UiIntent()

    /** Cancel in-flight batch phases; keep measured [AppUiState.partitionUsage]. */
    data object CancelFilesystemUsageBatch : UiIntent()
}

sealed interface UiEffect {
    data class SubmitDestructive(
        val action: DestructiveAction,
        val confirmation: DestructiveConfirmation,
    ) : UiEffect

    data class CancelOperation(val token: OperationToken) : UiEffect

    data class LinkXml(
        val imagePaths: List<String>,
        val templatePath: String?,
        val allowDestructiveTemplate: Boolean,
        val linkOverwrite: Boolean,
        val confirmation: DestructiveConfirmation,
    ) : UiEffect

    data class LoadFiles(val path: String) : UiEffect
    data class LaunchWindowsImport(
        val kind: WindowsImportKind = WindowsImportKind.Image,
        val directory: String,
        val pickSessionId: String,
        val identity: String?,
        val attachGeneration: Long,
        val partitionId: String,
        val startLba: ULong,
        val sectorCount: ULong,
    ) : UiEffect

    /** Host-run app-scoped eject: sync+close owned resources, then release. */
    data class EjectDevice(val ejectGeneration: Long) : UiEffect
    /** Host-run explicit rescan after a successful eject; never emitted automatically. */
    data class RescanAfterEject(val ejectGeneration: Long) : UiEffect

    data class CreateFolder(val path: String, val name: String) : UiEffect

    data class DeleteEntry(val path: String, val name: String) : UiEffect

    data class RenameEntry(
        val path: String,
        val name: String,
        val newName: String,
        val binding: PartitionBinding,
    ) : UiEffect

    data class UploadFile(val path: String, val uri: String) : UiEffect

    data class DownloadFile(
        val binding: FilesDownloadBinding,
        val uri: String,
    ) : UiEffect
    data class DownloadPickerReady(
        val binding: FilesDownloadBinding,
        val suggestedName: String,
        val requestId: Long,
    ) : UiEffect
    data class DeleteBatch(val selection: FilesBatchSelection) : UiEffect

    data class DownloadBatch(val selection: FilesBatchSelection, val treeUri: String) : UiEffect

    data class UploadBatchDocs(
        val directory: String,
        val uris: List<String>,
        val attachment: UiAttachmentPin,
    ) : UiEffect

    /**
     * Resume a parked batch transfer after the conflict dialog. Host re-acquires USB,
     * re-pins selection, then applies [decision]. Never a second USB owner.
     */
    data class ContinueFilesBatch(val decision: FilesConflictDecision) : UiEffect


    data class StageUsbImageForRawWrite(
        val path: String,
        val name: String,
        val binding: PartitionBinding? = null,
    ) : UiEffect

    data object ReadScan : UiEffect

    data object SmartRead : UiEffect


    data object RescueBackup : UiEffect

    data object CaptureGptBackup : UiEffect

    data object RunSelfTest : UiEffect

    data class LoadFilesystemUsage(
        val partitionId: String,
        val usageKey: FilesystemUsageKey,
        val usageGeneration: Long,
    ) : UiEffect

    /**
     * Sequential batch: host measures [steps] one at a time under the files lock.
     * [usageGeneration] fences every step publish.
     */
    data class LoadFilesystemUsageBatch(
        val usageGeneration: Long,
        val steps: List<Pair<String, FilesystemUsageKey>>,
    ) : UiEffect

    data class RangeProbe(
        val startLba: ULong,
        val sectorCount: ULong,
        val probeRequestId: Long,
    ) : UiEffect

    data class LoadRawSources(
        val binding: PartitionBinding,
        val requestGeneration: Long,
    ) : UiEffect

    data object CancelRawSources : UiEffect

    /** Disown any running phone-file inspection; the USB guard ends only on host Finished. */
    data object CancelIsoImportInspection : UiEffect

    /** Drop only a pending system picker owned by [pickSessionId]; newer launches survive. */
    data class CancelPendingWindowsImport(val pickSessionId: String) : UiEffect
    /** Read-only decode-count pass for a raw source; no temporary uncompressed file. */
    data class MeasureRawSource(
        val source: String,
        val staged: Boolean,
        val generation: Long,
        /** Disk capacity bound; a larger decoded stream is refused during the pass. */
        val limitBytes: Long? = null,
    ) : UiEffect

    data object CancelRawSourceMeasure : UiEffect

    data class LoadWindowsIsoCandidates(val requestGeneration: Long) : UiEffect

    data class InspectIsoImport(
        val uri: String,
        val requestGeneration: Long,
        val importKind: WindowsImportKind,
        val importDirectory: String?,
        val pickSessionId: String? = null,
    ) : UiEffect

    data class ImportIso(
        val isoImport: IsoImportProposal,
        val overwrite: Boolean,
        val requestGeneration: Long,
    ) : UiEffect

    data class LoadWindowsLinks(val requestGeneration: Long) : UiEffect

    data class UnlinkXml(val imagePaths: List<String>) : UiEffect

    data object SaveWindowsControls : UiEffect

    data class LoadAutounattend(
        val xmlName: String,
        val requestGeneration: Long,
    ) : UiEffect

    /** Same generation as [LoadAutounattend]: the host reads the image list beside the XML. */
    data class LoadAutounattendCandidates(
        val xmlName: String,
        val requestGeneration: Long,
    ) : UiEffect

    data class GenerateAutounattend(
        val xmlName: String,
        val autounattendParams: AutounattendParams,
        val imagePaths: List<String>,
        val overwriteXml: Boolean,
        val allowDestructiveTemplate: Boolean,
        val expectedTemplateSha256: String?,
        val expectedPlanSha256: String?,
        val renameFromXmlName: String? = null,
    ) : UiEffect

    data class WriteAutounattendRaw(
        val xmlName: String,
        val rawBytes: ByteArray,
        val overwriteXml: Boolean,
        val allowDestructiveTemplate: Boolean,
        val expectedTemplateSha256: String?,
        val renameFromXmlName: String? = null,
    ) : UiEffect {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WriteAutounattendRaw) return false
            return xmlName == other.xmlName &&
                rawBytes.contentEquals(other.rawBytes) &&
                overwriteXml == other.overwriteXml &&
                allowDestructiveTemplate == other.allowDestructiveTemplate &&
                expectedTemplateSha256 == other.expectedTemplateSha256 &&
                renameFromXmlName == other.renameFromXmlName
        }

        override fun hashCode(): Int {
            var result = xmlName.hashCode()
            result = 31 * result + rawBytes.contentHashCode()
            result = 31 * result + overwriteXml.hashCode()
            result = 31 * result + allowDestructiveTemplate.hashCode()
            result = 31 * result + (expectedTemplateSha256?.hashCode() ?: 0)
            result = 31 * result + (renameFromXmlName?.hashCode() ?: 0)
            return result
        }
    }


    /** Read-only GitHub check; [requestToken] fences a superseded request. */
    data class CheckVentoyUpstream(val requestToken: Long) : UiEffect
    /**
     * Fetch and verify a release into app-private storage. No USB device is opened, no
     * volume is mounted and no disk byte is written.
     */
    data class DownloadVentoyRelease(
        val requestToken: Long,
        /** Upstream latest; the host picks it only when this build pins it. */
        val upstreamVersion: String? = null,
    ) : UiEffect

    /**
     * HTTPS fetch of the official Plugson control-key page. Host must run this on
     * Dispatchers.IO outside any USB claim/fencing; failure is not an empty catalog.
     */
    data class FetchVentoyControlKeyCatalog(
        val requestToken: Long,
        val release: String,
    ) : UiEffect

    data class LoadVentoyGlobalSettings(
        val requestToken: Long,
        /** File to read; the host does not fall back to the default path. */
        val source: VentoySettingsSource = VentoySettingsSource.Default,
    ) : UiEffect

    /** Read one directory level; [path] is normalized before it reaches the host. */
    data class LoadVentoyFolders(val requestToken: Long, val path: String = "/") : UiEffect

    /**
     * Apply staged controls. Default edits merge in place; USB/phone selected files are imported
     * into the standard `/ventoy/ventoy.json`, with host-side identity and source checks.
     */
    data class ApplyVentoyControls(
        val settings: List<VentoyControlSetting>,
        val source: VentoySettingsSource = VentoySettingsSource.Default,
        val identity: String? = null,
        val attachGeneration: Long? = null,
        val partitionId: String? = null,
        val partitionStartLba: ULong? = null,
        val partitionSectorCount: ULong? = null,
    ) : UiEffect
    data class BindAutounattend(
        val xmlName: String,
        val imagePaths: List<String>,
        val allowDestructiveTemplate: Boolean,
        val expectedTemplateSha256: String?,
        val overwrite: Boolean = false,
    ) : UiEffect

    data class UnbindAutounattend(
        val xmlName: String,
        val imagePaths: List<String>,
    ) : UiEffect
}

data class ReduceResult(
    val state: AppUiState,
    val effects: List<UiEffect> = emptyList(),
)

/** Production scheduling seam: DeviceChanged state feeds this exact request path. */
internal data class FilesystemUsageSchedule(
    val state: AppUiState,
    val key: FilesystemUsageKey,
    val effect: UiEffect.LoadFilesystemUsage,
)

/** Production scheduling seam for the sequential batch (selected first). */
internal data class FilesystemUsageBatchSchedule(
    val state: AppUiState,
    val steps: List<Pair<String, FilesystemUsageKey>>,
    val effect: UiEffect.LoadFilesystemUsageBatch,
)

internal fun scheduleFilesystemUsageBatchRequest(
    state: AppUiState,
    lastBatchKeys: Set<FilesystemUsageKey>?,
    batchInFlight: Boolean,
    operationFinished: Boolean,
): FilesystemUsageBatchSchedule? {
    if (!state.needsFilesystemUsageBatch(lastBatchKeys, batchInFlight, operationFinished)) return null
    val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsageBatch)
    val effect = result.effects.singleOrNull() as? UiEffect.LoadFilesystemUsageBatch ?: return null
    return FilesystemUsageBatchSchedule(result.state, effect.steps, effect)
}

internal fun scheduleFilesystemUsageRequest(
    state: AppUiState,
    lastRequestedKey: FilesystemUsageKey?,
    inFlightKey: FilesystemUsageKey?,
    operationFinished: Boolean,
): FilesystemUsageSchedule? {
    val partition = state.selectedPartition() ?: return null
    val key = state.filesystemUsageKey(partition) ?: return null
    if (!state.needsFilesystemUsageRequest(key, lastRequestedKey, inFlightKey, operationFinished)) return null
    val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsage)
    val effect = result.effects.singleOrNull() as? UiEffect.LoadFilesystemUsage ?: return null
    return FilesystemUsageSchedule(result.state, key, effect)
}

object UiReducer {
    /**
     * Actions that rewrite the partition layout. Ambiguity blocks them unconditionally:
     * unlike an EFI read-only partition, no override may lift this.
     */
    private val layoutMutations = setOf(
        DestructiveAction.CREATE_PARTITION,
        DestructiveAction.DELETE_PARTITION,
        DestructiveAction.FORMAT_PARTITION,
        DestructiveAction.SET_PARTITION_ROLE,
        DestructiveAction.SET_PARTITION_BOOTABLE,
        DestructiveAction.RENAME_PARTITION,
        DestructiveAction.INITIALIZE_PARTITION_TABLE,
        DestructiveAction.GPT_REPAIR,
    )

    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult {
        val result =
            FilesReducer.reduce(state, intent)
                ?: PartitionsReducer.reduce(state, intent)
                ?: VentoyReducer.reduce(state, intent)
                ?: DiagnosticsReducer.reduce(state, intent)
                ?: OperationReducer.reduce(state, intent)
                ?: global(state, intent)
        return applyDeferredReleases(state, intent, result)
    }

    private fun global(state: AppUiState, intent: UiIntent): ReduceResult = when (intent) {
        is UiIntent.SelectTab -> {
            // A running or queued operation owns the USB lane: primary tab navigation
            // must not orphan it from Cancel/status. Settings/Diagnostics are read-only
            // destinations and may open while actionsEnabled stays false.
            val readOnlyDestination =
                intent.tab == ShellTab.Settings || intent.tab == ShellTab.Diagnostics
            // Leaving a read-only screen must work after opening it during an operation;
            // otherwise Back would trap the user there.
            val readOnlyTraversal = readOnlyDestination ||
                state.tab == ShellTab.Settings || state.tab == ShellTab.Diagnostics
            if (intent.tab != state.tab &&
                !readOnlyTraversal &&
                (state.isRunning || state.operation?.state == OperationState.Queued)
            ) {
                ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.WaitForOperation)))
            } else {
                val enteringFiles = intent.tab == ShellTab.Files && state.tab != ShellTab.Files
                // Any other tab leaves pick mode, exactly like CancelFilesPick. A running
                // import or confirmation keeps the screen: the user must see it finish.
                val pickExitBlock = if (intent.tab != ShellTab.Files &&
                    state.filesPickMode != null
                ) {
                    filesPickExitBlock(state)
                } else {
                    null
                }
            if (pickExitBlock != null) {
                ReduceResult(state.withStatus(pickExitBlock))
            } else {
                val leavingPick = intent.tab != ShellTab.Files && state.filesPickMode != null
                val cancelHost =
                    leavingPick &&
                        (state.isoImportSafInFlight != null ||
                            state.isoImportUsbInFlight != null)
                val switched =
                    if (leavingPick) {
                        state.copy(
                            tab = intent.tab,
                            filesPickSession = newFilesPickSession(),
                            filesPickMode = null,
                            pendingIsoImport = null,
                            deferredIsoImport = null,
                            isoImportError = null,
                            isoImportSafInFlight = null,
                            isoImportGeneration = state.isoImportGeneration + 1,
                        ).withStatus(if (cancelHost) {
                                    UiMessage.Key(UiMessageKey.ImportInspectionCancelling)
                                } else {
                                    null
                                })
                    } else {
                        state.copy(
                            tab = intent.tab,
                            // Carried forward, not a new emission: no sequence bump.
                            statusMessage = state.statusMessage.takeIf {
                                it is UiMessage.Key && it.key == UiMessageKey.VolumeSyncUnconfirmed
                            },
                        )
                    }
                // Entering Files re-reads the open folder: changes may have happened while
                // another tab owned the screen. One request per entry, none while one is
                // already outstanding.
                val base =
                    if (enteringFiles &&
                        switched.filesListingState != FilesListingState.Loading &&
                        switched.presence == DevicePresence.Ready &&
                        switched.actionsEnabled
                    ) {
                        OperationReducer.filesReload(switched)
                    } else {
                        ReduceResult(switched)
                    }
                if (leavingPick) {
                    base.copy(
                        effects =
                            base.effects +
                                buildList {
                                    state.filesPickMode?.sessionId?.let {
                                        add(UiEffect.CancelPendingWindowsImport(it))
                                    }
                                    if (cancelHost) {
                                        add(UiEffect.CancelIsoImportInspection)
                                    }
                                },
                    )
                } else {
                    base
                }
            }
            }
        }

        is UiIntent.SelectPartition ->
            if (state.filesPickMode != null) {
                // The session is pinned to one partition; switching would browse another
                // volume while the caller still thinks it is choosing from the pinned one.
                ReduceResult(
                    state.withStatus(UiMessage.Key(UiMessageKey.FilesPickActive)),
                )
            } else if (!state.actionsEnabled) {
                // Switching mid-operation would post a Running LoadFiles over the live
                // operation and orphan it from Cancel; report only why nothing changed.
                ReduceResult(state.withStatus(presenceMessage(state.presence)))
            } else {
                val clearConflict = state.pendingFilesBatchConflict != null
                // Batch map survives selection; legacy selected slot still clears so
                // existing refreshCycle / selected-only callers keep one request per select.
                ReduceResult(
                    state.copy(
                        selectedPartitionId = intent.partitionId,
                        currentPath = "/",
                        files = emptyList(),
                        filesError = null,
                        filesListGeneration = state.filesListGeneration + 1L,
                        filesListingState = FilesListingState.Loading,
                        filesystemUsage = null,
                        // Drop parked phases so an explicit reselect can resume missing keys.
                        partitionUsagePhases = emptyMap(),
                        filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                        rawSourceCandidates = null,
                        rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                        rawSourceCandidatesIdentity = null,
                        rawSourceCandidatesTruncated = false,
                        rawSourceCandidatesError = null,
                        pendingIsoImport = null,
                        deferredIsoImport = null,
                        isoImportError = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                        // Stale conflict belongs to previous partition pin — drop dialog.
                        pendingFilesBatchConflict = null,
                        // Ventoy/XML catalogs are stick-scoped, not partition-scoped.
                    ),
                    effects =
                        buildList {
                            add(UiEffect.LoadFiles(path = "/"))
                            // Discard parked USB work held for the old partition conflict.
                            if (clearConflict) {
                                add(UiEffect.ContinueFilesBatch(FilesConflictDecision.Cancel))
                            }
                        },
                )
            }

        is UiIntent.DeviceChanged -> {
            val incoming = intent.device
            val previous = state.device
            val sameStick =
                incoming != null &&
                    previous != null &&
                    incoming.identity == previous.identity &&
                    incoming.attachGeneration == previous.attachGeneration
            // Label-only identity is weak: EFI / destructive confirmation need the
            // same attach generation (sameStick) plus a stable USB serial on both sides.
            val trustedStick =
                sameStick &&
                    incoming != null &&
                    previous != null &&
                    deviceIdentityTrusted(previous, incoming)
            // Busy/PermissionRequired retains state only while release is confirmed.
            val probingRetain =
                sameStick &&
                    intent.retainExisting &&
                    (intent.presence == DevicePresence.Busy ||
                        intent.presence == DevicePresence.PermissionRequired)
            val partitions =
                if (probingRetain && intent.partitions.isEmpty()) {
                    state.partitions
                } else {
                    intent.partitions
                }
            val wholeDiskVolume =
                if (probingRetain && intent.wholeDiskVolume == null) {
                    state.wholeDiskVolume
                } else {
                    intent.wholeDiskVolume
                }
            val filesVolumes = partitions + listOfNotNull(wholeDiskVolume)
            // A refresh on the same attachment may not silently retarget actions. Keep
            // selection only when stable ID and exact geometry both match; a new attachment
            // starts with the first volume as its initial, explicit screen target.
            val retainedSelection = state.selectedPartitionId?.let { id ->
                val previousPartition = state.filesVolumes.firstOrNull { it.id == id }
                val refreshedPartition = filesVolumes.firstOrNull { it.id == id }
                refreshedPartition?.takeIf {
                    previousPartition?.startLba != null &&
                        previousPartition.sectorCount != null &&
                        it.startLba == previousPartition.startLba &&
                        it.sectorCount == previousPartition.sectorCount
                }?.id
            }
            val initialSelection =
                intent.presence == DevicePresence.Ready &&
                    filesVolumes.isNotEmpty() &&
                    (!sameStick ||
                        (!state.partitionSelectionInitialized && state.selectedPartitionId == null))
            val selected = when {
                probingRetain -> state.selectedPartitionId?.takeIf { id ->
                    filesVolumes.any { it.id == id }
                } ?: filesVolumes.firstOrNull()?.id
                initialSelection -> filesVolumes.firstOrNull()?.id
                // A vanished or re-geometried target must not leave Files with no
                // selectable volume: fall back to the first live one.
                sameStick -> retainedSelection ?: filesVolumes.firstOrNull()?.id
                else -> null
            }
            // Ventoy/XML is scoped to the current attachment.
            val keepVentoy =
                sameStick &&
                    intent.presence != DevicePresence.Disconnected &&
                    intent.presence != DevicePresence.Absent
            // Pick sessions/results live only while that exact stick is attached.
            val keepFilesPick = keepVentoy
            // The EFI write override belongs to one physical attachment: a partition
            // refresh keeps it only with a trusted identity match.
            val sameAttachment = intent.presence == DevicePresence.Ready && sameStick
            val retainPrivileged = trustedStick && (sameAttachment || probingRetain)
            val tableTrusted =
                if (probingRetain && intent.partitions.isEmpty()) {
                    state.tableValid &&
                        !state.partitionTableDamaged &&
                        !state.partitionTableAmbiguous
                } else {
                    intent.tableValid &&
                        !intent.partitionTableDamaged &&
                        !intent.partitionTableAmbiguous
                }
            val retainedEfi = state.vtoyEfiWritePartitionId
                ?.takeIf { retainPrivileged && tableTrusted }
                ?.let { id ->
                    partitions.firstOrNull {
                        it.id == id &&
                            it.readOnly &&
                            it.startLba == state.vtoyEfiWriteStartLba &&
                            it.sectorCount == state.vtoyEfiWriteSectorCount
                    }
                }
            val retainedPendingEfi = state.pendingEfiWriteBinding?.takeIf { pending ->
                retainPrivileged && tableTrusted && pending.device == intent.device &&
                    partitions.any {
                        it.id == pending.partitionId && it.readOnly &&
                            it.startLba == pending.startLba && it.sectorCount == pending.sectorCount
                    }
            }
            // partition is still known. Detach / other stick / missing id → root.
            val keepPath =
                sameStick &&
                    state.selectedPartitionId != null &&
                    selected == state.selectedPartitionId &&
                    intent.presence != DevicePresence.Disconnected &&
                    intent.presence != DevicePresence.Absent
            val path = if (keepPath) state.currentPath else "/"
            val reloadFiles =
                intent.presence == DevicePresence.Ready && selected != null
            // Gate on the state this intent produces, not on the pre-probe one:
            // Busy/PermissionRequired -> Ready must load on the default Files tab.
            val confirmationAfter =
                if (probingRetain && trustedStick) state.pendingConfirmation else null
            val actionsAfter =
                intent.presence == DevicePresence.Ready &&
                    state.operation?.state != OperationState.Running &&
                    state.operation?.state != OperationState.Queued &&
                    confirmationAfter == null
            // Successful eject survives only on the same attachment it was pinned
            // to. A new generation, detach or Absent clears it; Ready on the same
            // attachment never reopens automatically — only explicit rescan does.
            val ejectStillPinned =
                state.ejected &&
                    state.ejectIdentity != null &&
                    state.ejectAttachGeneration != null &&
                    incoming != null &&
                    incoming.identity == state.ejectIdentity &&
                    incoming.attachGeneration == state.ejectAttachGeneration &&
                    (intent.presence == DevicePresence.Ready ||
                        intent.presence == DevicePresence.Busy ||
                        intent.presence == DevicePresence.PermissionRequired)
            // Only an open Files tab mounts; elsewhere the next tab entry re-lists.
            // A successful eject suppresses the automatic reload: the stick stays
            // released until an explicit rescan or a new attachment generation.
            val loadFilesNow =
                reloadFiles && !ejectStillPinned && state.tab == ShellTab.Files && actionsAfter
            // Probe keeps the request id so a late list still binds; Ready/detach bump.
            val filesGeneration =
                if (probingRetain) {
                    state.filesListGeneration
                } else {
                    state.filesListGeneration + 1L
                }
            // Busy probe often reports capacity 0 before geometry is known — keep the
            // last confirmed capacity on the same attachment so identity stays honest.
            val device =
                if (probingRetain &&
                    incoming != null &&
                    previous != null &&
                    incoming.capacityBytes == 0uL &&
                    previous.capacityBytes > 0uL
                ) {
                    incoming.copy(capacityBytes = previous.capacityBytes)
                } else {
                    incoming
                }
            val retainedPickMode =
                state.filesPickMode?.takeIf { mode ->
                    keepFilesPick &&
                        mode.partitionId == selected &&
                        device != null &&
                        mode.identity == device.identity &&
                        mode.attachGeneration == device.attachGeneration &&
                        partitions.any {
                            it.id == mode.partitionId &&
                                it.startLba == mode.startLba &&
                                it.sectorCount == mode.sectorCount
                        }
                }
            // A geometry/attach/partition change under a live session invalidates the
            // session token and disowns its host inspection. The USB guard itself is
            // released only by the host's Finished after the disk close.
            val pickLost = state.filesPickMode != null && retainedPickMode == null
            // Any real attach/inspection invalidation disowns the host ticket. XML has
            // no pick mode, so gating this on pickLost alone could leave a blocking read
            // owning the inspection slot forever.
            val cancelPickInspection =
                (!keepVentoy || pickLost) &&
                    (state.isoImportSafInFlight != null ||
                        state.isoImportUsbInFlight != null)
            val retainedSelections =
                state.filesPickSelections.filterValues { selection ->
                    keepFilesPick &&
                        device != null &&
                        selection.identity == device.identity &&
                        selection.attachGeneration == device.attachGeneration &&
                        partitions.any {
                            it.id == selection.partitionId &&
                                it.startLba == selection.startLba &&
                                it.sectorCount == selection.sectorCount
                        }
                }
            // Every template carries a consumed pin — stick picks and phone/generated
            // copies alike — revalidated against the live attachment and part-1 geometry.
            val templatePinValid =
                keepVentoy &&
                    state.windowsTemplatePin != null &&
                    device != null &&
                    state.windowsTemplatePin.identity == device.identity &&
                    state.windowsTemplatePin.attachGeneration == device.attachGeneration &&
                    partitions.any {
                        it.id == "1" &&
                            it.id == state.windowsTemplatePin.partitionId &&
                            it.startLba == state.windowsTemplatePin.startLba &&
                            it.sectorCount == state.windowsTemplatePin.sectorCount
                    }
            ReduceResult(
                state.copy(
                    operation = state.operation?.takeIf { sameStick },
                    // A different stick must not inherit the previous operation's
                    // scoped log in the status dialog.
                    operationLogLines =
                        if (sameStick) state.operationLogLines else emptyList(),
                    presence = intent.presence,
                    device = device,
                    pendingEfiWriteBinding = retainedPendingEfi,
                    vtoyEfiWritePartitionId = retainedEfi?.id,
                    vtoyEfiWriteStartLba = retainedEfi?.startLba,
                    vtoyEfiWriteSectorCount = retainedEfi?.sectorCount,
                    partitionSelectionInitialized = when {
                        intent.presence == DevicePresence.Ready && !probingRetain -> true
                        !sameStick -> false
                        else -> state.partitionSelectionInitialized
                    },
                    partitions = partitions,
                    wholeDiskVolume = wholeDiskVolume,
                    freeRegions =
                        if (probingRetain && intent.partitions.isEmpty()) {
                            state.freeRegions
                        } else {
                            intent.freeRegions
                        },
                    diskSectorSize =
                        if (probingRetain && intent.partitions.isEmpty()) {
                            state.diskSectorSize
                        } else {
                            intent.sectorSize
                        },
                    tableValid =
                        if (probingRetain && intent.partitions.isEmpty()) {
                            state.tableValid
                        } else {
                            intent.tableValid
                        },
                    partitionTableDamaged =
                        if (probingRetain && intent.partitions.isEmpty()) state.partitionTableDamaged
                        else intent.partitionTableDamaged,
                    partitionTableAmbiguous =
                        if (probingRetain && intent.partitions.isEmpty()) state.partitionTableAmbiguous
                        else intent.partitionTableAmbiguous,
                    partitionTableRecognized =
                        if (probingRetain && intent.partitions.isEmpty()) state.partitionTableRecognized
                        else intent.partitionTableRecognized,
                    partitionScheme =
                        if (probingRetain && intent.partitions.isEmpty()) state.partitionScheme
                        else intent.partitionScheme,
                    partitionTableRecognition =
                        if (probingRetain && intent.partitions.isEmpty()) state.partitionTableRecognition
                        else intent.partitionTableRecognition,
                    metadataFingerprint =
                        if (probingRetain && intent.metadataFingerprint == null) state.metadataFingerprint
                        else intent.metadataFingerprint,
                    initializationMetadataFingerprintGpt =
                        if (probingRetain && intent.initializationMetadataFingerprintGpt == null) state.initializationMetadataFingerprintGpt
                        else intent.initializationMetadataFingerprintGpt,
                    initializationMetadataFingerprintMbr =
                        if (probingRetain && intent.initializationMetadataFingerprintMbr == null) state.initializationMetadataFingerprintMbr
                        else intent.initializationMetadataFingerprintMbr,
                    rawGeometry =
                        if (probingRetain && intent.partitions.isEmpty() && intent.rawGeometry == null) {
                            state.rawGeometry
                        } else {
                            intent.rawGeometry
                        },
                    rangeProbe = if (probingRetain) state.rangeProbe else null,
                    rangeProbeRequestedRange =
                        if (probingRetain) state.rangeProbeRequestedRange else null,
                    smartResult = if (sameStick) state.smartResult else null,

                    selectedPartitionId = selected,
                    currentPath = path,
                    filesPickSession =
                        if (pickLost) {
                            newFilesPickSession()
                        } else {
                            state.filesPickSession
                        },
                    filesPickMode = retainedPickMode,
                    filesPickSelections = retainedSelections,
                    // Probe keeps the last listing; Ready clears until FilesListed lands.
                    files = when {
                        ejectStillPinned -> emptyList()
                        reloadFiles -> emptyList()
                        probingRetain -> state.files
                        else -> emptyList()
                    },
                    filesError = if (probingRetain && !ejectStillPinned) state.filesError else null,
                    pendingFilesBatchConflict =
                        if (keepVentoy && !pickLost && !ejectStillPinned) state.pendingFilesBatchConflict else null,
                    filesListGeneration = filesGeneration,
                    filesListingState = when {
                        ejectStillPinned -> FilesListingState.Stale
                        loadFilesNow -> FilesListingState.Loading
                        reloadFiles -> FilesListingState.Stale
                        probingRetain -> state.filesListingState
                        else -> FilesListingState.Stale
                    },
                    // A refresh may describe another attachment or geometry.
                    filesystemUsage = if (probingRetain && !ejectStillPinned) {
                        state.filesystemUsage?.takeIf { snapshot ->
                            partitions.any { state.filesystemUsageKey(it) == snapshot.key }
                        }
                    } else null,
                    // Same attachment keeps only keys that still describe live geometry.
                    partitionUsage =
                        if (probingRetain && !ejectStillPinned) {
                            val liveKeys = partitions
                                .mapNotNull { state.filesystemUsageKey(it) }
                                .toSet()
                            state.partitionUsage.filterKeys { it in liveKeys }
                        } else {
                            emptyMap()
                        },
                    partitionUsagePhases =
                        if (probingRetain && !ejectStillPinned) {
                            val liveIds = partitions.map { it.id }.toSet()
                            state.partitionUsagePhases.filterKeys { it in liveIds }
                        } else {
                            emptyMap()
                        },
                    filesystemUsageBatchGeneration =
                        if (probingRetain && !ejectStillPinned) {
                            state.filesystemUsageBatchGeneration
                        } else {
                            // Invalidate in-flight answers for the previous attachment.
                            state.filesystemUsageBatchGeneration + 1L
                        },
                    ejected = ejectStillPinned,
                    // Same-attachment re-probe must not drop a running eject verdict:
                    // the host completion (EjectResulted) clears the flight, not the probe.
                    ejectInFlight = state.ejectInFlight && (ejectStillPinned || sameStick),
                    pendingConfirmation = confirmationAfter,
                    rawSourceCandidates =
                        if (probingRetain) state.rawSourceCandidates else null,
                    rawSourceCandidatesGeneration =
                        if (probingRetain) {
                            state.rawSourceCandidatesGeneration
                        } else {
                            state.rawSourceCandidatesGeneration + 1
                        },
                    rawSourceCandidatesIdentity =
                        if (probingRetain) state.rawSourceCandidatesIdentity else null,
                    rawSourceCandidatesTruncated =
                        if (probingRetain) state.rawSourceCandidatesTruncated else false,
                    rawSourceCandidatesError =
                        if (probingRetain) state.rawSourceCandidatesError else null,
                    windowsIsoCandidates =
                        if (keepVentoy) state.windowsIsoCandidates else null,
                    windowsIsoCandidatesGeneration =
                        if (keepVentoy) {
                            state.windowsIsoCandidatesGeneration
                        } else {
                            state.windowsIsoCandidatesGeneration + 1
                        },
                    windowsIsoCandidatesIdentity =
                        if (keepVentoy) state.windowsIsoCandidatesIdentity else null,
                    windowsIsoCandidatesError =
                        if (keepVentoy) state.windowsIsoCandidatesError else null,
                    // Same-stick probes (onStart re-read) must not orphan an inspected
                    // result the host is about to post; only a real attach change drops it.
                    pendingIsoImport =
                        if (keepVentoy && !pickLost) state.pendingIsoImport else null,
                    deferredIsoImport =
                        if (keepVentoy && !pickLost) state.deferredIsoImport else null,
                    isoImportError =
                        if (keepVentoy && !pickLost) state.isoImportError else null,
                    // Replug disowns the host ticket, and the worker releases its slot when
                    // it ends, so a stale guard cannot block the next stick; on the same
                    // stick the running inspection keeps its guard. A lost pin disowns it
                    // too, but a USB guard is kept until the host reports the closed disk.
                    isoImportSafInFlight =
                        if (keepVentoy && !pickLost) state.isoImportSafInFlight else null,
                    // The SAF ticket is disowned above, but the USB worker may still be
                    // closing the disk. Its guard stays until WindowsIsoImportFinished
                    // for this generation; clearing it here would admit a second
                    // inspection/import before the real close.
                    isoImportUsbInFlight = state.isoImportUsbInFlight,
                    pendingImportFolderPick =
                        if (keepVentoy && !pickLost) state.pendingImportFolderPick else null,
                    pendingImportFolderPickLoad =
                        if (keepVentoy && !pickLost) {
                            state.pendingImportFolderPickLoad
                        } else {
                            false
                        },
                    deferredImportFolderPick =
                        if (keepVentoy && !pickLost) state.deferredImportFolderPick else null,
                    // A same-stick probe must not invalidate the live inspection: its
                    // UsbStarted/Inspected posts are gated by this generation. Only a real
                    // attachment change or a lost pick pin bumps it (and disowns the host
                    // ticket); a late Inspected then cannot join the next session.
                    isoImportGeneration =
                        if (keepVentoy && !pickLost) {
                            state.isoImportGeneration
                        } else {
                            state.isoImportGeneration + 1
                        },
                    windowsSelectedImages =
                        if (keepVentoy) state.windowsSelectedImages else emptyList(),
                    windowsLinkTargetImage =
                        if (keepVentoy) state.windowsLinkTargetImage else null,
                    windowsPendingRemoval =
                        if (keepVentoy) state.windowsPendingRemoval else emptySet(),
                    windowsTemplatePath =
                        if (templatePinValid) state.windowsTemplatePath else null,
                    windowsImageLinks =
                        if (keepVentoy) state.windowsImageLinks else null,
                    windowsImageLinksGeneration =
                        if (keepVentoy) {
                            state.windowsImageLinksGeneration
                        } else {
                            state.windowsImageLinksGeneration + 1
                        },
                    windowsImageLinksIdentity =
                        if (keepVentoy) state.windowsImageLinksIdentity else null,
                    pendingWindowsUnlink = null,
                    // A refresh may change either file; never keep a half-populated
                    // overwrite dialog that can retarget with fallback values.
                    linkOverwriteCandidate = null,
                    linkOverwriteCandidateIdentity = null,
                    linkOverwriteCandidateGeneration = null,
                    linkOverwriteCandidatePaths = emptyList(),
                    linkOverwriteCandidateTemplatePath = null,
                    // A dropped stick pin must not keep reading another geometry's bytes:
                    // disown the pending document/candidate reads via a generation bump.
                    // Real write errors still surface via OperationUpdated, never here.
                    windowsTemplatePin =
                        if (templatePinValid) state.windowsTemplatePin else null,
                    autounattendState =
                        if (templatePinValid || !keepVentoy) {
                            if (keepVentoy) state.autounattendState else AutounattendState.Missing
                        } else {
                            AutounattendState.Missing
                        },
                    autounattendBindings =
                        if (templatePinValid) state.autounattendBindings else null,
                    autounattendCandidates =
                        if (templatePinValid) state.autounattendCandidates else null,
                    autounattendRequestGeneration =
                        if (templatePinValid || !keepVentoy) {
                            if (keepVentoy) state.autounattendRequestGeneration
                            else state.autounattendRequestGeneration + 1
                        } else {
                            state.autounattendRequestGeneration + 1
                        },
                    autounattendLoadInFlight =
                        if (templatePinValid) state.autounattendLoadInFlight else false,
                    diskLayoutConfirmation = null,
                    pendingAutounattendGeneration = null,
                    ventoyGlobalSettings =
                        if (keepVentoy) state.ventoyGlobalSettings else null,
                    ventoyFolderPath =
                        if (keepVentoy) state.ventoyFolderPath else "/",
                    pendingVentoyControls =
                        if (keepVentoy) state.pendingVentoyControls else emptyList(),
                    // A chosen path/document is pinned to this attachment; a new stick
                    // must not inherit it even when the path text matches.
                    ventoySettingsSource =
                        if (keepVentoy) {
                            state.ventoySettingsSource
                        } else {
                            VentoySettingsSource.Default
                        },
                    ventoySettingsSourceIdentity =
                        if (keepVentoy) state.ventoySettingsSourceIdentity else null,
                    ventoySettingsSourceAttachGeneration =
                        if (keepVentoy) {
                            state.ventoySettingsSourceAttachGeneration
                        } else {
                            null
                        },
                    ventoySettingsSourcePartitionId =
                        if (keepVentoy) state.ventoySettingsSourcePartitionId else null,
                    ventoySettingsSourceStartLba =
                        if (keepVentoy) state.ventoySettingsSourceStartLba else null,
                    ventoySettingsSourceSectorCount =
                        if (keepVentoy) state.ventoySettingsSourceSectorCount else null,
                    ventoySettingsSourceLoading =
                        if (keepVentoy) state.ventoySettingsSourceLoading else false,
                    ventoyFolders =
                        if (keepVentoy) state.ventoyFolders else null,
                    ventoyFoldersError =
                        if (keepVentoy) state.ventoyFoldersError else null,
                    ventoyFoldersLoading =
                        if (keepVentoy) state.ventoyFoldersLoading else false,
                    ventoyFoldersPending =
                        if (keepVentoy) state.ventoyFoldersPending else false,
                    ventoyCacheEpoch =
                        if (keepVentoy) state.ventoyCacheEpoch else state.ventoyCacheEpoch + 1,
                    // Installed facts belong to one attachment. The upstream check is
                    // a global network request, not tied to the stick: detach keeps
                    // its fields and fence untouched, so an in-flight answer lands
                    // on the same token. A device switch still clears installed rows.
                    installedVentoyRelease =
                        if (keepVentoy) state.installedVentoyRelease else null,
                    installedVentoyReadError =
                        if (keepVentoy) state.installedVentoyReadError else null,
                    upstreamVentoyRelease = state.upstreamVentoyRelease,
                    upstreamVentoyPinned = state.upstreamVentoyPinned,
                    ventoyUpstreamChecking = state.ventoyUpstreamChecking,
                    ventoyUpstreamRequest = state.ventoyUpstreamRequest,
                    ventoyDownloadError =
                        if (keepVentoy) state.ventoyDownloadError else null,
                ).let { next ->
                    if (intent.presence == DevicePresence.Ready) {
                        // Probe posts Probing before Ready; keep other statuses (errors),
                        // and carrying one forward must not bump the sequence.
                        next.copy(
                            statusMessage = state.statusMessage.takeUnless {
                                it is UiMessage.Key && it.key == UiMessageKey.Probing
                            },
                        )
                    } else {
                        next.withStatus(presenceMessage(intent.presence))
                    }
                },
                effects =
                    buildList {
                        if (loadFilesNow) add(UiEffect.LoadFiles(path = path))
                        if (pickLost) {
                            state.filesPickMode?.sessionId?.let {
                                add(UiEffect.CancelPendingWindowsImport(it))
                            }
                        }
                        if (cancelPickInspection) add(UiEffect.CancelIsoImportInspection)
                    },
            ).let { result ->
                // A source chosen before capacity/geometry was known waits without a
                // decode pass; start the bounded pass as soon as a positive bound exists.
                val resumed = PartitionsReducer.resumeRawMeasurement(result.state)
                if (resumed == null) {
                    result
                } else {
                    ReduceResult(resumed.first, result.effects + listOf(resumed.second))
                }
            }
        }

        UiIntent.InvalidateVentoyStickCaches ->
            ReduceResult(OperationReducer.dropVentoyStickCaches(state, keepTemplatePath = true))

        is UiIntent.RequestDestructive -> {
            val device = state.device
            val blockedByAmbiguity =
                state.partitionTableAmbiguous && intent.action in layoutMutations
            val blockedByDamagedGpt =
                state.partitionTableDamaged &&
                    intent.action in setOf(
                        DestructiveAction.CREATE_PARTITION,
                        DestructiveAction.DELETE_PARTITION,
                        DestructiveAction.RENAME_PARTITION,
                        DestructiveAction.GPT_REPAIR,
                    )
            val missingInput =
                when {
                    blockedByAmbiguity ->
                        UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
                    blockedByDamagedGpt -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
                    intent.action == DestructiveAction.RAW_WRITE &&
                        state.imageUri == null && state.stagedRawImagePath == null ->
                        UiMessage.Key(UiMessageKey.SelectImageFirst)
                    // Raw writing needs a start plus the decoded image size; the whole-disk
                    // shortcut would bypass the derived end bound.
                    intent.action == DestructiveAction.RAW_WRITE ->
                        UiMessage.Key(UiMessageKey.RawRangeInvalid)
                    intent.action == DestructiveAction.GPT_RESTORE && state.backupUri == null ->
                        UiMessage.Key(UiMessageKey.ChooseGptBackup)
                    intent.action == DestructiveAction.RESCUE_RESTORE &&
                        (state.rescueImageUri == null || state.rescueMapUri == null) ->
                        UiMessage.Key(UiMessageKey.ChooseRescueFiles)
                    intent.action == DestructiveAction.GPT_RESTORE ->
                        DiagnosticsReducer.gptRestoreInspectionProblem(state)
                    intent.action == DestructiveAction.RESCUE_RESTORE ->
                        DiagnosticsReducer.rescueRestoreInspectionProblem(state)
                    intent.action == DestructiveAction.GPT_REPAIR &&
                        state.partitionScheme?.toPartitionScheme() != PartitionScheme.Gpt ->
                        UiMessage.Key(UiMessageKey.GptRepairRequiresGpt)
                    intent.action == DestructiveAction.GPT_REPAIR && !state.tableValid ->
                        UiMessage.Key(UiMessageKey.TableDamaged)
                    (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) &&
                        state.selectedPartitionId == null ->
                        UiMessage.Key(UiMessageKey.SelectPartitionFirst)
                    (intent.action == DestructiveAction.DELETE_PARTITION || intent.action == DestructiveAction.FORMAT_PARTITION) ->
                        writeBlockMessage(state)
                    intent.action == DestructiveAction.CREATE_PARTITION && !state.tableValid ->
                        UiMessage.Key(UiMessageKey.CorruptTableCreate)
                    // Rename actions carry a value that RequestDestructive cannot supply;
                    // they must go through RequestRenamePartition/RequestRenameVolumeLabel.
                    intent.action == DestructiveAction.RENAME_PARTITION ->
                        UiMessage.Key(UiMessageKey.InvalidPartitionName)
                    intent.action == DestructiveAction.RENAME_VOLUME_LABEL ->
                        UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
                    // Scheme initialization needs its pinned target and snapshot.
                    intent.action == DestructiveAction.INITIALIZE_PARTITION_TABLE ->
                        UiMessage.Key(UiMessageKey.SchemeUnknown)
                    // An unreadable store must block install/update, never fall back.
                    (intent.action == DestructiveAction.VENTOY_INSTALL ||
                        intent.action == DestructiveAction.VENTOY_UPDATE) &&
                        !state.ventoyInstallAvailable ->
                        UiMessage.Key(UiMessageKey.VentoyStoreUnavailable)
                    else -> null
                }
            if (device != null && state.actionsEnabled && missingInput != null) {
                ReduceResult(state.withStatus(missingInput))
            } else if (device == null || !state.actionsEnabled) {
                ReduceResult(
                    state.withStatus(when {
                                device == null -> presenceMessage(state.presence)
                                state.isRunning -> UiMessage.Key(UiMessageKey.WaitForOperation)
                                else -> presenceMessage(state.presence)
                            }),
                )
            } else {
                val targetPart = if (intent.action == DestructiveAction.DELETE_PARTITION ||
                    intent.action == DestructiveAction.FORMAT_PARTITION
                ) {
                    state.selectedPartition()?.name ?: state.selectedPartitionId
                } else {
                    null
                }
                val formatId =
                    if (intent.action == DestructiveAction.FORMAT_PARTITION) {
                        state.selectedFormatId
                    } else {
                        null
                    }
                ReduceResult(
                    state.copy(
                        pendingConfirmation =
                            DestructiveConfirmation.request(
                                device,
                                intent.action,
                                targetPart,
                                formatId = formatId,
                                target = state.selectedPartition(),
                                diskSectorSize =
                                    if (intent.action == DestructiveAction.DELETE_PARTITION &&
                                        state.diskSectorSize > 0
                                    ) {
                                        state.diskSectorSize
                                    } else {
                                        null
                                    },
                                gptBackupUri =
                                    if (intent.action == DestructiveAction.GPT_RESTORE) {
                                        state.backupUri
                                    } else {
                                        null
                                    },
                                gptBackupName =
                                    if (intent.action == DestructiveAction.GPT_RESTORE) {
                                        DestructiveConfirmation.fileLabel(state.backupUri)
                                    } else {
                                        null
                                    },
                                rescueImageUri =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        state.rescueImageUri
                                    } else {
                                        null
                                    },
                                rescueImageName =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        DestructiveConfirmation.fileLabel(state.rescueImageUri)
                                    } else {
                                        null
                                    },
                                rescueMapUri =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        state.rescueMapUri
                                    } else {
                                        null
                                    },
                                rescueMapName =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        DestructiveConfirmation.fileLabel(state.rescueMapUri)
                                    } else {
                                        null
                                    },
                                rescueArchiveTreeUri =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        state.rescueArchiveTreeUri
                                    } else {
                                        null
                                    },
                                gptBackupInspection =
                                    if (intent.action == DestructiveAction.GPT_RESTORE) {
                                        state.backupInspection
                                            ?.takeIf { it.uri == state.backupUri }
                                    } else {
                                        null
                                    },
                                rescueImageInspection =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        state.rescueImageInspection
                                            ?.takeIf { it.uri == state.rescueImageUri }
                                    } else {
                                        null
                                    },
                                rescueMapInspection =
                                    if (intent.action == DestructiveAction.RESCUE_RESTORE) {
                                        state.rescueMapInspection
                                            ?.takeIf { it.uri == state.rescueMapUri }
                                    } else {
                                        null
                                    },
                                // Snapshot now: later edits on screen cannot change what
                                // the confirmed install/update will write. Staged edits for
                                // a chosen non-default file must never leak into the default
                                // ventoy.json; they are written only when that source is selected.
                                ventoyControls =
                                    if (state.ventoySettingsSource.isDefaultPath()) {
                                        state.pendingVentoyControls
                                    } else {
                                        emptyList()
                                    },
                                ventoyRelease = ventoyReleaseSource(state),
                            ).let { base ->
                                if (intent.action == DestructiveAction.GPT_REPAIR) {
                                    val snapshot = state.partitions.mapNotNull { tab ->
                                        val start = tab.startLba
                                        val count = tab.sectorCount
                                        if (start == null || count == null) {
                                            null
                                        } else {
                                            PartitionRef(start, count)
                                        }
                                    }
                                    val names = state.partitions.mapNotNull { tab ->
                                        val start = tab.startLba
                                        val count = tab.sectorCount
                                        if (start == null || count == null) {
                                            null
                                        } else {
                                            tab.name.ifBlank { tab.id }
                                        }
                                    }
                                    base.copy(
                                        initialScheme =
                                            state.partitionScheme?.toPartitionScheme(),
                                        initialPartitions = snapshot,
                                        initialPartitionNames = names,
                                    )
                                } else {
                                    base
                                }
                            },
                    ),
                )
            }
        }

        UiIntent.ConfirmDestructive -> {
            val pending = state.pendingConfirmation
            val device = state.device
            val action = pending?.action
            val invalidState = when {
                pending == null || device == null -> true
                state.presence != DevicePresence.Ready -> true
                state.isRunning -> true
                action == DestructiveAction.CREATE_PARTITION && !state.tableValid -> true
                state.partitionTableAmbiguous &&
                    action != null && action in layoutMutations -> true
                state.partitionTableDamaged &&
                    action in setOf(
                        DestructiveAction.CREATE_PARTITION,
                        DestructiveAction.DELETE_PARTITION,
                        DestructiveAction.GPT_REPAIR,
                        DestructiveAction.SET_PARTITION_ROLE,
                        DestructiveAction.SET_PARTITION_BOOTABLE,
                        DestructiveAction.RENAME_PARTITION,
                        DestructiveAction.INITIALIZE_PARTITION_TABLE,
                    ) -> true
                // Partition-targeted actions need a live selection (and matching id when snapshotted).
                action in setOf(
                    DestructiveAction.DELETE_PARTITION,
                    DestructiveAction.FORMAT_PARTITION,
                    DestructiveAction.SET_PARTITION_ROLE,
                    DestructiveAction.SET_PARTITION_BOOTABLE,
                    DestructiveAction.RENAME_PARTITION,
                    DestructiveAction.RENAME_VOLUME_LABEL,
                ) && state.selectedPartitionId == null -> true
                pending.partitionId != null &&
                    (state.selectedPartitionId != pending.partitionId ||
                        state.partitions.none { it.id == pending.partitionId }) -> true
                action == DestructiveAction.INITIALIZE_PARTITION_TABLE &&
                    (pending.targetScheme == null ||
                        pending.initialScheme == null ||
                        state.partitionTableRecognition == usb.partition.TableRecognition.SUPERFLOPPY ||
                        pending.initialTableRecognition != state.partitionTableRecognition ||
                        pending.initialMetadataFingerprint == null ||
                        pending.initialMetadataFingerprint != state.metadataFingerprint ||
                        pending.initialInitializationMetadataFingerprint == null ||
                        pending.initialInitializationMetadataFingerprint !=
                            (if (pending.targetScheme == PartitionScheme.Gpt) {
                                state.initializationMetadataFingerprintGpt
                            } else {
                                state.initializationMetadataFingerprintMbr
                            }) ||
                        (pending.initialTableRecognized && pending.targetScheme == pending.initialScheme) ||
                        pending.initialTableRecognized != state.partitionTableRecognized ||
                        (pending.initialTableRecognized &&
                            state.partitionScheme?.toPartitionScheme() != pending.initialScheme) ||
                        pending.initialDiskSectorSize != state.diskSectorSize ||
                        pending.initialDiskSectorCount !=
                            state.device?.capacityBytes?.div(state.diskSectorSize.coerceAtLeast(1).toULong()) ||
                        state.partitionTableAmbiguous ||
                        state.partitionTableDamaged ||
                        state.rawGeometry?.metadataProblem == RawSchemeProblem.GptBackupMetadataUnknown ||
                        (state.partitionTableRecognized && state.partitionScheme?.toPartitionScheme() == PartitionScheme.Gpt &&
                            state.rawGeometry == null) ||
                        state.partitions.mapNotNull { tab ->
                            val start = tab.startLba
                            val count = tab.sectorCount
                            if (start == null || count == null) null else PartitionRef(start, count)
                        } != pending.initialPartitions) -> true
                action == DestructiveAction.GPT_REPAIR &&
                    (pending.initialScheme != PartitionScheme.Gpt ||
                        state.partitionScheme?.toPartitionScheme() != PartitionScheme.Gpt ||
                        !state.tableValid ||
                        state.partitions.mapNotNull { tab ->
                            val start = tab.startLba
                            val count = tab.sectorCount
                            if (start == null || count == null) {
                                null
                            } else {
                                PartitionRef(start, count)
                            }
                        } != pending.initialPartitions) -> true
                action == DestructiveAction.RENAME_PARTITION &&
                    (pending.partitionName.isNullOrBlank() ||
                        state.partitionScheme != TableScheme.GPT ||
                        !state.tableValid) -> true
                action == DestructiveAction.RENAME_VOLUME_LABEL &&
                    (pending.volumeLabel.isNullOrBlank() ||
                        state.selectedPartitionId == null) -> true
                action == DestructiveAction.SET_PARTITION_ROLE &&
                    (pending.partitionRole == null || !state.tableValid) -> true
                action == DestructiveAction.SET_PARTITION_BOOTABLE &&
                    (pending.partitionBootable == null || !state.tableValid) -> true
                // RAW: range required; source name optional (opaque SAF still valid via state).
                action == DestructiveAction.RAW_WRITE &&
                    (pending.rawStartLba == null || pending.rawSectorCount == null) -> true
                // GPT/rescue: full URI snapshot required and must still match live state.
                action == DestructiveAction.GPT_RESTORE &&
                    (pending.gptBackupUri.isNullOrBlank() ||
                        pending.gptBackupUri != state.backupUri) -> true
                action == DestructiveAction.RESCUE_RESTORE &&
                    (pending.rescueImageUri.isNullOrBlank() ||
                        pending.rescueMapUri.isNullOrBlank() ||
                        pending.rescueImageUri != state.rescueImageUri ||
                        pending.rescueMapUri != state.rescueMapUri) -> true
                action == DestructiveAction.GPT_RESTORE &&
                    pending.gptBackupInspection?.let {
                        it.error != null || it.uri != pending.gptBackupUri
                    } == true -> true
                action == DestructiveAction.RESCUE_RESTORE &&
                    // Rescue images are inspected through SAF metadata only: no error state.
                    (pending.rescueImageInspection?.let {
                        it.uri != pending.rescueImageUri
                    } == true ||
                        pending.rescueMapInspection?.let {
                            it.error != null || it.uri != pending.rescueMapUri
                        } == true) -> true
                action == DestructiveAction.VENTOY_UNATTEND_XML_WRITE &&
                    (pending.xmlTargetName.isNullOrBlank() ||
                        (state.pendingAutounattendGeneration == null &&
                            state.pendingAutounattendRawWrite == null)) -> true
                // The confirmed control snapshot must still be writable.
                (action == DestructiveAction.VENTOY_INSTALL ||
                    action == DestructiveAction.VENTOY_UPDATE) &&
                    pending?.ventoyControls?.let {
                        VentoyControlCatalog.problem(it) != null
                    } == true -> true
                // A store that became unknown/corrupt must not be confirmed.
                (action == DestructiveAction.VENTOY_INSTALL ||
                    action == DestructiveAction.VENTOY_UPDATE) &&
                    !state.ventoyInstallAvailable -> true
                else -> false
            }
            // Fail-closed: null device/pending/action never reach effect branches.
            if (invalidState || action == null || pending == null || device == null) {
                val clearXmlWrite = action == DestructiveAction.VENTOY_UNATTEND_XML_WRITE
                ReduceResult(
                    state.copy(
                        pendingConfirmation = null,
                        pendingAutounattendRawWrite =
                            if (clearXmlWrite) null else state.pendingAutounattendRawWrite,
                        pendingAutounattendGeneration =
                            if (clearXmlWrite) null else state.pendingAutounattendGeneration,
                    ),
                )
            } else if (action == DestructiveAction.VENTOY_LINK_XML) {
                val paths =
                    pending.imagePaths.takeIf { it.isNotEmpty() }
                        ?: pending.isoName?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                        ?: emptyList()
                if (paths.isEmpty()) {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation = null,
                        ).withStatus(UiMessage.Key(UiMessageKey.NoIsoForLink)),
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation = null,
                        ).withStatus(message(
                                UiMessageKey.WritingXml,
                                paths.first().substringAfterLast('/') +
                                    if (paths.size > 1) " +${paths.size - 1}" else "",
                            )),
                        effects = listOf(
                            UiEffect.LinkXml(
                                imagePaths = paths,
                                templatePath = pending.templatePath,
                                allowDestructiveTemplate = pending.allowDestructiveTemplate,
                                linkOverwrite = pending.linkOverwrite,
                                confirmation = pending,
                            ),
                        ),
                    )
                }
            } else if (action == DestructiveAction.VENTOY_UNATTEND_XML_WRITE) {
                val pendingRaw = state.pendingAutounattendRawWrite
                val pendingGen = state.pendingAutounattendGeneration
                val target = pending.xmlTargetName
                if (pendingRaw != null) {
                    if (target.isNullOrBlank() ||
                        pendingRaw.xmlName != target ||
                        pendingRaw.identity != device.identity ||
                        pendingRaw.attachGeneration != device.attachGeneration
                    ) {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                pendingAutounattendRawWrite = null,
                            ).withStatus(UiMessage.Key(UiMessageKey.TargetChanged)),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                pendingAutounattendRawWrite = null,
                            ).withStatus(UiMessage.Raw("Writing unattend XML")),
                            effects = listOf(
                                UiEffect.WriteAutounattendRaw(
                                    xmlName = pendingRaw.xmlName,
                                    rawBytes = pendingRaw.rawBytes,
                                    overwriteXml = true,
                                    allowDestructiveTemplate = pendingRaw.allowDestructiveTemplate,
                                    expectedTemplateSha256 = pendingRaw.expectedTemplateSha256,
                                    renameFromXmlName = pendingRaw.renameFromXmlName,
                                ),
                            ),
                        )
                    }
                } else if (pendingGen == null ||
                    target.isNullOrBlank() ||
                    pendingGen.xmlName != target ||
                    pendingGen.identity != device.identity ||
                    pendingGen.attachGeneration != device.attachGeneration
                ) {
                    ReduceResult(
                        state.copy(
                            pendingConfirmation = null,
                            pendingAutounattendGeneration = null,
                        ).withStatus(UiMessage.Key(UiMessageKey.TargetChanged)),
                    )
                } else {
                    val layout = pendingGen.params.diskLayout
                    val layoutProblems =
                        if (layout != null && layout.writeLayout && pendingGen.params.intents.diskLayout == XmlFieldIntent.Set) {
                            VentoyReducer.targetLayoutProblems(layout)
                        } else {
                            emptyList()
                        }
                    if (layoutProblems.isNotEmpty()) {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ).withStatus(VentoyReducer.layoutProblemMessage(layoutProblems)),
                        )
                    } else if (layout != null && layout.writeLayout && pendingGen.params.intents.diskLayout == XmlFieldIntent.Set) {
                        // Overwrite acknowledged; still need separate target-PC layout ack.
                        val plan =
                            pendingGen.expectedPlanSha256
                                ?: autounattendPlanSha256(
                                    pendingGen.xmlName,
                                    pendingGen.params,
                                    pendingGen.imagePaths,
                                )
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                diskLayoutConfirmation = VentoyReducer.layoutConfirmation(layout, plan),
                            ).withStatus(UiMessage.Raw(
                                    "Confirm the target PC disk layout before generating XML",
                                )),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ).withStatus(UiMessage.Raw("Generating unattend XML")),
                            effects = listOf(
                                UiEffect.GenerateAutounattend(
                                    xmlName = pendingGen.xmlName,
                                    autounattendParams = pendingGen.params,
                                    imagePaths = pendingGen.imagePaths,
                                    overwriteXml = true,
                                    allowDestructiveTemplate = pendingGen.allowDestructiveTemplate,
                                    expectedTemplateSha256 = pendingGen.expectedTemplateSha256,
                                    expectedPlanSha256 = pendingGen.expectedPlanSha256,
                                    renameFromXmlName = pendingGen.renameFromXmlName,
                                ),
                            ),
                        )
                    }
                }
            } else {
                ReduceResult(
                    state.copy(pendingConfirmation = null),
                    effects = listOf(
                        UiEffect.SubmitDestructive(
                            action = action,
                            confirmation = pending,
                        ),
                    ),
                )
            }
        }

        UiIntent.DismissConfirmation -> {
            val clearXmlWrite =
                state.pendingConfirmation?.action ==
                    DestructiveAction.VENTOY_UNATTEND_XML_WRITE
            ReduceResult(
                state.copy(
                    pendingConfirmation = null,
                    pendingAutounattendGeneration =
                        if (clearXmlWrite) null else state.pendingAutounattendGeneration,
                    pendingAutounattendRawWrite =
                        if (clearXmlWrite) null else state.pendingAutounattendRawWrite,
                    // Confirmation held the session; drop parked phases so refresh can resume.
                    partitionUsagePhases = emptyMap(),
                    filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                ),
            )
        }
        is UiIntent.IsoActionRequested -> {
            if (!state.actionsEnabled) {
                ReduceResult(
                    state.withStatus(presenceMessage(state.presence)),
                )
            } else when (intent.action) {
                IsoAction.SendToRawWriter -> {
                    val name = intent.fileName.substringAfterLast('/')
                    ReduceResult(
                        state.withStatus(message(UiMessageKey.StagingFile, name)),
                        listOf(
                            UiEffect.StageUsbImageForRawWrite(
                                path = state.currentPath,
                                name = name,
                            ),
                        ),
                    )
                }
                IsoAction.LinkXml -> {
                    val device = state.device
                    if (device == null) {
                        ReduceResult(
                            state.withStatus(UiMessage.Key(UiMessageKey.NoDeviceForLink)),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation =
                                    DestructiveConfirmation.request(
                                        device = device,
                                        action = DestructiveAction.VENTOY_LINK_XML,
                                        isoName = intent.fileName,
                                        linkOverwrite = false,
                                    ),
                            ).withStatus(null),
                        )
                    }
                }
            }
        }

        is UiIntent.SetLanguage -> ReduceResult(state.copy(language = intent.language))

        UiIntent.ToggleTheme ->
            ReduceResult(state.copy(darkTheme = !state.darkTheme))

        UiIntent.RequestEject -> requestEject(state)
        is UiIntent.EjectResulted -> ejectResulted(state, intent)
        UiIntent.RequestEjectRescan -> requestEjectRescan(state)

        is UiIntent.Status -> ReduceResult(state.withStatus(intent.message))

        is UiIntent.AppendLog -> {
            val trimmed = intent.line.take(500) // guard runaway lines
            fun appendRing(lines: List<String>): List<String> =
                if (lines.size >= AppUiState.LOG_MAX_LINES) {
                    lines.drop(lines.size - AppUiState.LOG_MAX_LINES + 1) + trimmed
                } else {
                    lines + trimmed
                }
            ReduceResult(
                state.copy(
                    logLines = appendRing(state.logLines),
                    operationLogLines = appendRing(state.operationLogLines),
                ),
            )
        }

        // No confirmation, no operation gate: the user asked for an empty log now.
        // Appends keep working afterwards, and nothing outside logLines is touched.
        UiIntent.ClearLog -> ReduceResult(state.copy(logLines = emptyList()))

        else -> ReduceResult(state)
    }

    private fun applyDeferredReleases(
        state: AppUiState,
        intent: UiIntent,
        result: ReduceResult,
    ): ReduceResult {
        val afterFiles = run {
            val accepted = when (intent) {
                is UiIntent.OperationCompletionEffects ->
                    intent.state.isTerminal() &&
                        state.device?.attachGeneration == intent.attachmentGeneration
                else -> result.state !== state
            }
            releaseDeferredFilesListing(
                result,
                allowRelease = filesReleaseAllowed(intent, accepted),
            )
        }
        val afterVentoy = run {
            val accepted = when (intent) {
                is UiIntent.OperationCompletionEffects ->
                    intent.state.isTerminal() &&
                        state.device?.attachGeneration == intent.attachmentGeneration
                else -> afterFiles.state !== state
            }
            releaseDeferredVentoyFolderRead(
                afterFiles,
                allowRelease = deferredReleaseAllowed(intent, accepted),
            )
        }
        val afterImport = run {
            val accepted = when (intent) {
                is UiIntent.OperationCompletionEffects ->
                    intent.state.isTerminal() &&
                        state.device?.attachGeneration == intent.attachmentGeneration
                else -> afterVentoy.state !== state
            }
            releaseDeferredImportFolderPick(
                afterVentoy,
                allowRelease = deferredReleaseAllowed(intent, accepted),
            )
        }
        val accepted = when (intent) {
            is UiIntent.OperationCompletionEffects ->
                intent.state.isTerminal() &&
                    state.device?.attachGeneration == intent.attachmentGeneration
            else -> afterImport.state !== state
        }
        return releaseDeferredIsoImport(
            afterImport,
            allowRelease = deferredReleaseAllowed(intent, accepted),
        )
    }

    private fun deferredReleaseAllowed(intent: UiIntent, callbackAccepted: Boolean): Boolean = when (intent) {
        is UiIntent.VentoyFoldersLoaded,
        is UiIntent.VentoyGlobalSettingsLoaded,
        is UiIntent.FilesListed,
        is UiIntent.FilesListFailed,
        is UiIntent.WindowsIsoImportFinished,
        is UiIntent.WindowsIsoImportSafFinished,
        is UiIntent.OperationCompletionEffects,
        -> callbackAccepted
        is UiIntent.ConfirmDestructive,
        UiIntent.DismissConfirmation,
        UiIntent.DismissLinkOverwrite,
        UiIntent.DismissDiskLayout,
        is UiIntent.SelectTab,
        is UiIntent.DeviceChanged,
        UiIntent.ImportFolderPickOpened,
        is UiIntent.Status,
        -> callbackAccepted
        else -> false
    }
    private fun filesReleaseAllowed(intent: UiIntent, callbackAccepted: Boolean): Boolean =
        callbackAccepted &&
            when (intent) {
                is UiIntent.OperationCompletionEffects,
                is UiIntent.DeviceChanged,
                is UiIntent.SelectTab,
                UiIntent.DismissConfirmation,
                UiIntent.ImportFolderPickOpened,
                is UiIntent.Status,
                is UiIntent.WindowsIsoImportFinished,
                -> true
                else -> false
            }

    /** Start deferred Files load once current intent leaves attachment actionable. */
    private fun releaseDeferredFilesListing(
        result: ReduceResult,
        allowRelease: Boolean,
    ): ReduceResult {
        if (!allowRelease) return result
        val state = result.state
        if (state.pendingImportFolderPickLoad && state.filesPickMode == null) return result
        val pickerKind =
            if (state.pendingImportFolderPickLoad && state.filesPickMode != null) {
                state.pendingImportFolderPick ?: state.deferredImportFolderPick
            } else {
                null
            }
        if (state.filesListingState != FilesListingState.Stale ||
            state.tab != ShellTab.Files ||
            state.presence != DevicePresence.Ready ||
            !state.actionsEnabled ||
            state.isoImportUsbInFlight != null ||
            state.selectedFilesVolume() == null ||
            result.effects.isNotEmpty()
        ) {
            return result
        }
        val started = OperationReducer.filesStart(state)
        return if (pickerKind == null) {
            started
        } else {
            started.copy(
                state = started.state.copy(
                    pendingImportFolderPick = pickerKind,
                    pendingImportFolderPickLoad = false,
                    deferredImportFolderPick = null,
                ),
            )
        }
    }

    private fun requestEject(state: AppUiState): ReduceResult {
        val device = state.device
        when {
            state.ejectInFlight || state.ejected ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.EjectRescanRequired)))
            state.presence != DevicePresence.Ready || device == null ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDevice)))
            state.isRunning || state.operation?.state == OperationState.Queued ||
                state.pendingConfirmation != null || state.isoImportUsbInFlight != null ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.WaitForOperation)))
            else -> {
                val generation = state.ejectGeneration + 1L
                return ReduceResult(
                    state.copy(
                        ejectInFlight = true,
                        ejectGeneration = generation,
                        ejectIdentity = device.identity,
                        ejectAttachGeneration = device.attachGeneration,
                        ejected = false,
                    ).withStatus(UiMessage.Key(UiMessageKey.EjectStarted)),
                    effects = listOf(UiEffect.EjectDevice(generation)),
                )
            }
        }
    }

    private fun ejectResulted(state: AppUiState, intent: UiIntent.EjectResulted): ReduceResult {
        if (intent.ejectGeneration != state.ejectGeneration || !state.ejectInFlight) {
            return state
                .let(::ReduceResult)
                .let { result -> releaseDeferredFilesListing(result, allowRelease = true) }
        }
        val live = state.device
        val pinned =
            live != null &&
                state.ejectIdentity != null &&
                live.identity == state.ejectIdentity &&
                state.ejectAttachGeneration != null &&
                live.attachGeneration == state.ejectAttachGeneration &&
                live.identity == intent.identity &&
                live.attachGeneration == intent.attachGeneration
        if (!pinned) {
            return ReduceResult(
                state.copy(
                    ejectInFlight = false,
                ).withStatus(UiMessage.Key(UiMessageKey.EjectStale)),
            )
        } else if (intent.succeeded) {
            return ReduceResult(
                state.copy(
                    ejectInFlight = false,
                    ejected = true,
                    files = emptyList(),
                    filesError = null,
                    filesListingState = FilesListingState.Stale,
                    filesystemUsage = null,
                    partitionUsage = emptyMap(),
                    partitionUsagePhases = emptyMap(),
                    filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                ).withStatus(UiMessage.Key(
                        UiMessageKey.EjectSucceeded,
                        listOf(live.label),
                    )),
            )
        } else if (intent.busy) {
            return ReduceResult(
                state.copy(
                    ejectInFlight = false,
                    // Eject did not leave; release parked usage phases for a fresh batch.
                    partitionUsagePhases = emptyMap(),
                    filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                ).withStatus(UiMessage.Key(UiMessageKey.EjectBusy)),
            )
        } else {
            return ReduceResult(
                state.copy(
                    ejectInFlight = false,
                    partitionUsagePhases = emptyMap(),
                    filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                ).withStatus(intent.message?.let(UiMessage::Raw)
                        ?: UiMessage.Key(UiMessageKey.VolumeSyncUnconfirmed, listOf("eject"))),
            )
        }
    }

    private fun requestEjectRescan(state: AppUiState): ReduceResult {
        when {
            state.ejectInFlight ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.WaitForOperation)))
            !state.ejected ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.EjectRescanRequired)))
            state.presence != DevicePresence.Ready || state.device == null ->
                return ReduceResult(state.withStatus(UiMessage.Key(UiMessageKey.NoDevice)))
            else ->
                return ReduceResult(
                    state.copy(
                        ejected = false,
                        ejectIdentity = null,
                        ejectAttachGeneration = null,
                        filesListingState = FilesListingState.Stale,
                        filesystemUsage = null,
                        partitionUsage = emptyMap(),
                        partitionUsagePhases = emptyMap(),
                        filesystemUsageBatchGeneration = state.filesystemUsageBatchGeneration + 1L,
                    ),
                    effects = listOf(UiEffect.RescanAfterEject(state.ejectGeneration)),
                )
        }
    }

    /**
     * Starts a folder read requested while USB work was not allowed. Runs after every
     * intent, so the Busy/Permission/confirmation -> Ready edge itself releases it even
     * though the screen's LaunchedEffect may not restart. One-shot: the pending flag is
     * cleared before the effect, and a failed read settles into an error, so an unusable
     * folder can never turn into a retry loop.
     */
    private fun releaseDeferredVentoyFolderRead(
        result: ReduceResult,
        allowRelease: Boolean,
    ): ReduceResult {
        if (!allowRelease) return result
        val state = result.state
        if (!state.ventoyFoldersPending) return result
        if (state.tab != ShellTab.Ventoy) {
            // Nothing on screen shows the list; the next tab entry asks again.
            return result.copy(state = state.copy(ventoyFoldersPending = false))
        }
        if (!state.actionsEnabled) return result
        // Never stack a second mount next to effects from this intent.
        if (result.effects.isNotEmpty()) return result
        val started = startFolderRead(state, state.ventoyFolderPath, keepList = true)
        val status = started.state.statusMessage
        val blockedStatus =
            status is UiMessage.Key &&
                (status.key == UiMessageKey.DeviceBusy ||
                    status.key == UiMessageKey.WaitForOperation)
        return if (blockedStatus) {
            started.copy(state = started.state.withStatus(null))
        } else {
            started
        }
    }

    /**
     * Start one directory read. The token is the single ordering key: both navigation and
     * refresh bump it, so only the newest answer may publish rows for [path].
     */
    internal fun startFolderRead(
        state: AppUiState,
        path: String,
        keepList: Boolean,
    ): ReduceResult {
        val token = state.ventoyFoldersRequest + 1L
        return ReduceResult(
            state.copy(
                ventoyFolderPath = path,
                ventoyFolders = if (keepList) state.ventoyFolders else null,
                ventoyFoldersError = null,
                ventoyFoldersLoading = true,
                ventoyFoldersPending = false,
                ventoyFoldersRequest = token,
            ),
            effects = listOf(UiEffect.LoadVentoyFolders(requestToken = token, path = path)),
        )
    }

    /**
     * Runs after every intent: once mounting is allowed again (operation terminal, a
     * confirmation dismissed, an attach probe finished), a folder re-read that
     * [UiIntent.ReopenImportFolderPick] had to postpone starts here, and the picker demand
     * deferred with it is handed to the UI. Keeps the signal independent of the consumed
     * [AppUiState.pendingImportFolderPick], so a UI open cannot cancel the pending work.
     */
    private fun releaseDeferredImportFolderPick(
        result: ReduceResult,
        allowRelease: Boolean,
    ): ReduceResult {
        if (!allowRelease) return result
        val next = result.state
        if (next.filesPickMode != null) {
            if (!next.actionsEnabled || next.filesListingState != FilesListingState.Loaded) return result
            val pickerKind = next.pendingImportFolderPick ?: next.deferredImportFolderPick
            return result.copy(
                state = next.copy(
                    pendingImportFolderPick = pickerKind,
                    pendingImportFolderPickLoad = false,
                    deferredImportFolderPick = null,
                ),
            )
        }
        if (!next.pendingImportFolderPickLoad || !next.actionsEnabled) return result
        // The USB phase holds disk; SAF phase runs off worker and cannot clash.
        if (next.isoImportUsbInFlight != null) return result
        val pickerKind = next.pendingImportFolderPick ?: next.deferredImportFolderPick
        // Never add a USB mount next to other USB work from the same intent: a refused
        // confirmation (no effects) releases immediately, an accepted one (SubmitDestructive)
        // waits for the terminal, and a terminal that also reloads Files waits for the next
        // quiet intent (status/list result). Host direct inspection emits InspectIsoImport and
        // is covered by the same rule, because it is an effect too.
        if (result.effects.any { it !is UiEffect.LoadVentoyFolders }) return result
        if (next.ventoyFolders != null) {
            // A fresh list already landed; only the picker demand is still outstanding.
            return result.copy(
                state =
                    next.copy(
                        pendingImportFolderPick = pickerKind,
                        pendingImportFolderPickLoad = false,
                        deferredImportFolderPick = null,
                    ),
            )
        }
        // The intent may itself have asked for the folder list (manual Refresh): reuse it
        // instead of stacking a second mount on the same token contract.
        val alreadyLoading = result.effects.any { it is UiEffect.LoadVentoyFolders }
        val token = next.ventoyFoldersRequest + 1L
        val released =
            next.copy(
                pendingImportFolderPick = pickerKind,
                pendingImportFolderPickLoad = false,
                deferredImportFolderPick = null,
                ventoyFoldersLoading = true,
                ventoyFoldersRequest = if (alreadyLoading) next.ventoyFoldersRequest else token,
            )
        return result.copy(
            state = released,
            effects =
                if (alreadyLoading) {
                    result.effects
                } else {
                    result.effects +
                        UiEffect.LoadVentoyFolders(
                            requestToken = token,
                            path = next.ventoyFolderPath,
                        )
                },
        )
    }

    /**
     * Starts an inspected no-overwrite import that arrived while the UI was not actionable
     * (same-stick probe). Runs on the first quiet actionable intent so it never stacks a USB
     * write next to another effect; a changed attachment or generation drops it.
     */
    private fun releaseDeferredIsoImport(
        result: ReduceResult,
        allowRelease: Boolean,
    ): ReduceResult {
        if (!allowRelease) return result
        val state = result.state
        val proposal = state.deferredIsoImport ?: return result
        if (!state.actionsEnabled || state.isoImportUsbInFlight != null) return result
        if (state.filesListingState == FilesListingState.Loading) return result
        if (result.effects.isNotEmpty()) return result
        val device = state.device
        if (device == null ||
            proposal.identity != device.identity ||
            proposal.attachGeneration != device.attachGeneration ||
            proposal.requestGeneration != state.isoImportGeneration
        ) {
            return result.copy(state = state.copy(deferredIsoImport = null))
        }
        return ReduceResult(
            state.copy(
                deferredIsoImport = null,
            ).withStatus(UiMessage.Raw(
                        "Importing ${proposal.targetName} into ${proposal.directory}",
                    )),
            effects = listOf(
                UiEffect.ImportIso(
                    isoImport = proposal,
                    overwrite = false,
                    requestGeneration = proposal.requestGeneration,
                ),
            ),
        )
    }


    /**
     * Phase-only batch update. Stale generation, changed live key, or a cancelled batch
     * (phase entry already gone) leaves state untouched — a late Calculating/Deferred can
     * never revive or mislabel a finished or cancelled pass.
     */
    /** Localized reason for a folder that cannot be restored; never guesses a pair. */
    fun archiveInvalidMessage(invalid: usb.diagnostics.BackupArchive.Selection.Invalid): UiMessage {
        val names = invalid.detail.joinToString(", ")
        return when (invalid.problem) {
            usb.diagnostics.BackupArchive.Problem.Empty -> UiMessage.Key(UiMessageKey.ArchiveEmpty)
            usb.diagnostics.BackupArchive.Problem.MissingImage ->
                UiMessage.Key(UiMessageKey.ArchiveMissingImage)
            usb.diagnostics.BackupArchive.Problem.MissingMap ->
                UiMessage.Key(UiMessageKey.ArchiveMissingMap)
            usb.diagnostics.BackupArchive.Problem.MultipleImages ->
                UiMessage.Key(UiMessageKey.ArchiveMultipleImages, listOf(names))
            usb.diagnostics.BackupArchive.Problem.UnexpectedFiles ->
                UiMessage.Key(UiMessageKey.ArchiveUnexpectedFiles, listOf(names))
        }
    }


    /**
     * Store-size shortcut only when it really is the decoded length: a named,
     * uncompressed source with a positive reported size. Everything else (gz/xz,
     * unnamed SAF, missing SIZE) goes through the cancellable decode-count pass.
     */
    internal fun cheapRawDecodedBytes(name: String?, storedBytes: Long?): Long? {
        if (name.isNullOrBlank() || storedBytes == null || storedBytes <= 0L) return null
        if (usb.image.ImageFormatNames.isCompressed(name)) return null
        return storedBytes
    }

    /** Largest decoded image that could possibly fit the disk; bounds SAF measurement. */
    internal fun rawMeasureLimit(state: AppUiState): Long? {
        val geometry = state.rawGeometry
        val sectors = geometry?.diskSectorCount
        val sectorSize = geometry?.sectorSize ?: state.diskSectorSize.takeIf { it > 0 }
        if (sectors != null && sectorSize != null && sectorSize > 0) {
            val ss = sectorSize.toULong()
            if (sectors > Long.MAX_VALUE.toULong() / ss) return Long.MAX_VALUE
            return (sectors * ss).toLong()
        }
        val capacity = state.device?.capacityBytes ?: return null
        return if (capacity > Long.MAX_VALUE.toULong()) Long.MAX_VALUE else capacity.toLong()
    }

    /** Restart a terminal failed/cancelled pass without changing the selected source. */
    /**
     * Pins the payload the user is about to confirm. A downloaded release wins with its
     * full provenance; otherwise the bundled release is named explicitly. The host may
     * only load this source and must abort when it no longer matches.
     */
    private fun ventoyReleaseSource(state: AppUiState): VentoyReleaseSource? =
        when (val store = state.ventoyStoreState) {
            is usb.ventoy.VentoyReleaseStoreState.Verified ->
                VentoyReleaseSource.Downloaded(store.provenance)
            usb.ventoy.VentoyReleaseStoreState.Absent ->
                state.bundledVentoyRelease?.let { VentoyReleaseSource.Bundled(it) }
            usb.ventoy.VentoyReleaseStoreState.Unknown,
            usb.ventoy.VentoyReleaseStoreState.Corrupt -> null
        }

    fun presenceMessage(presence: DevicePresence): UiMessage =
        when (presence) {
            DevicePresence.Absent -> UiMessage.Key(UiMessageKey.NoDevice)
            DevicePresence.PermissionRequired -> UiMessage.Key(UiMessageKey.PermissionRequired)
            DevicePresence.Busy -> UiMessage.Key(UiMessageKey.DeviceBusy)
            DevicePresence.Disconnected -> UiMessage.Key(UiMessageKey.DeviceDisconnected)
            DevicePresence.Ready -> UiMessage.Key(UiMessageKey.Ready)
        }

    /** Small builder for keyed messages with format arguments. */
    fun message(key: UiMessageKey, vararg args: Any): UiMessage =
        UiMessage.Key(key, args.toList())

    /** MBR addresses at most 2^32-1 sectors. */
    internal fun mbrAddressLimitSectors(): ULong = 0xFFFF_FFFFUL


    /** Null when [binding] still names the selected device, partition and geometry. */
    /**
     * True when [incoming] is safe to treat as the same physical stick as [previous]
     * for privileged state (EFI write override, destructive confirmation).
     * Requires a stable USB serial on both sides; matching capacity alone is never
     * enough (two different sticks can share size). Caller already checked
     * identity string + attachGeneration via sameStick.
     */
    private fun deviceIdentityTrusted(previous: DeviceIdentity, incoming: DeviceIdentity): Boolean {
        if (!previous.hasStableSerial || !incoming.hasStableSerial) return false
        return previous.serialNumber?.trim() == incoming.serialNumber?.trim()
    }

    /**
     * Directory-list failures that mean the path is gone (safe to fall back to root).
     * FatFs absence is the exact nativeList shape `list failed: FR_NO_FILE|FR_NO_PATH`
     * from fat_jni — never a bare trailing ": 5" from an unrelated I/O exception.
     * EXT/NTFS absence is the exact jni message `Path not found` (ENOENT only).
     * Generic `directory listing failed` / EIO stay as [AppUiState.filesError].
     */
    /**
     * Explicit reason writes to the selected partition are refused, or null when allowed.
     * An EFI partition stays untouched unless the override names that exact partition;
     * lifting it for one ESP never unlocks another.
     */
    fun writeBlockMessage(state: AppUiState): UiMessage? {
        val partition = state.selectedPartition()
            ?: return UiMessage.Key(UiMessageKey.SelectPartitionFirst)
        // Unconditional before the EFI override: in an ambiguous layout the MBR record may
        // own the sectors under the selected GPT partition, so no override may lift the block.
        if (state.partitionTableAmbiguous) {
            return UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
        }
        if (state.isEfiWriteAllowed(partition)) return null
        return message(UiMessageKey.ReadOnlyPartition, partition.name)
    }

    /**
     * A single FAT entry name: no separators, no dot entries.
     * Policy twin: [usb.files.FilesOperationPolicy.requireChildName].
     */
    /**
     * Canonical equality with the writer via pure [XmlPath.resolve] (no VentoySetup/USB).
     */
    internal fun sameXmlTarget(loaded: String, requested: String): Boolean =
        try {
            XmlPath.resolve(loaded)
                .equals(XmlPath.resolve(requested), ignoreCase = true)
        } catch (_: IllegalArgumentException) {
            false
        }

    /**
     * Refuse the destructive GPT confirmation when the selected file failed bounded
     * validation or no longer matches the live geometry. A missing inspection is treated
     * as unavailable, not as a pass; the restore path still revalidates against the disk.
     */

    internal fun isMissingDirectoryListError(message: String): Boolean =
        FilesReducer.isMissingDirectoryListError(message)

}
