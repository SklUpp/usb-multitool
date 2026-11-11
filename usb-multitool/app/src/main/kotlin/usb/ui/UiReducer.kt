package usb.ui

import usb.fs.FR_NO_FILE
import usb.fs.FR_NO_PATH
import usb.fs.FilesystemId
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageSnapshot
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.TableScheme
import usb.ventoy.Autounattend
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendProblem
import usb.ventoy.AutounattendState
import usb.ventoy.DiskLayout
import usb.ventoy.FolderReadProblem
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlCatalog
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.XmlPath
import usb.ventoy.folderParent
import usb.ventoy.normalizeFolderPath
import usb.ventoy.autounattendPlanSha256
import usb.ventoy.diskLayoutInstallTargetPartitionId
import usb.ventoy.diskLayoutRemainingMb
import usb.ventoy.diskLayoutTotalMb


/**
 * Pure state transitions for the four-screen shell.
 * Compose and JVM tests drive the same reducer; no USB I/O here.
 */
sealed class UiIntent {
    data class SelectTab(val tab: ShellTab) : UiIntent()

    data class SelectPartition(val partitionId: String) : UiIntent()

    /**
     * Open the simplified Files picker. [partitionId] null means the Ventoy data partition
     * for Ventoy purposes, or the current selection for a raw source; an explicit id that
     * is missing fails closed instead of falling back to another partition.
     */
    data class BeginFilesPick(
        val purpose: FilesPickPurpose,
        val returnTab: ShellTab,
        val target: FilesPickTarget = FilesPickTarget.File,
        val partitionId: String? = null,
        val initialPath: String = "/",
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
        val files: List<FileEntry> = emptyList(),
        val freeRegions: List<FreeRegionUi> = emptyList(),
        val sectorSize: Int = 512,
        val tableValid: Boolean = true,
        val partitionTableDamaged: Boolean = false,
        val partitionTableAmbiguous: Boolean = false,
        val partitionScheme: TableScheme? = null,
        /** Raw-range map source of truth; null means the host has not described the disk. */
        val rawGeometry: usb.partition.RawRangeGeometry? = null,
    ) : UiIntent()

    data class OperationUpdated(val progress: OperationProgress?) : UiIntent()

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

    /** Rename the GPT entry of the selected partition (MBR has no name field). */
    data class RequestRenamePartition(val name: String) : UiIntent()

    /** Rename the filesystem label of the selected, already formatted partition. */
    data class RequestRenameVolumeLabel(val label: String) : UiIntent()

    /**
     * Write an empty MBR or GPT table, replacing the current one. The confirmation pins the
     * current scheme and every existing partition geometry; a mismatch aborts before writing.
     */
    data class RequestInitializePartitionTable(val scheme: PartitionScheme) : UiIntent()

    /** One raw tool: write the explicitly chosen absolute sector range. */
    data class RequestWriteRawRange(val startLba: ULong, val sectorCount: ULong) : UiIntent()

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
    data class SetStagedRawImage(val path: String, val label: String) : UiIntent()
    data class ClearStagedRawImage(val deleteFile: Boolean = true) : UiIntent()

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
    /** Select the existing XML template; it is never generated or rewritten. */
    data class SelectWindowsTemplate(val path: String) : UiIntent()
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
    ) : UiIntent()

    /** Remove auto_install entries only; image and XML files stay on the stick. */
    data class RequestUnlinkWindowsImages(val imagePaths: List<String>) : UiIntent()
    data object ConfirmWindowsUnlink : UiIntent()
    data object CancelWindowsUnlink : UiIntent()

    /** Read `/ventoy/<xmlName>` and its bindings; non-destructive. */
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

    /**
     * Validate and generate. When [params].diskLayout.writeLayout is true this only opens
     * [diskLayoutConfirmation]; generation happens on ConfirmDiskLayout (acknowledged).
     */
    data class RequestGenerateAutounattend(
        val xmlName: String,
        val params: AutounattendParams,
        val imagePaths: List<String> = emptyList(),
        val overwriteXml: Boolean = false,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
        val planSha256: String? = null,
    ) : UiIntent()
    data class ConfirmDiskLayout(val acknowledged: Boolean) : UiIntent()
    data object DismissDiskLayout : UiIntent()

    /** Global ventoy.json control entries, raw values included. */
    data object LoadVentoyGlobalSettings : UiIntent()
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
    ) : UiIntent()
    /** Only catalog-known keys and validated values. null value removes the key. */
    data class StageVentoyControl(val key: String, val value: String?) : UiIntent()
    /** Forget one staged change without touching the stick. */
    data class DiscardStagedVentoyControl(val key: String) : UiIntent()
    /** Write the staged controls to an existing Ventoy stick, or retry after a Partial. */
    data object RequestApplyVentoyControls : UiIntent()

    data class RequestBindAutounattend(
        val xmlName: String,
        val imagePaths: List<String>,
        val allowDestructiveTemplate: Boolean = false,
        val expectedTemplateSha256: String? = null,
    ) : UiIntent()
    data class RequestUnbindAutounattend(
        val xmlName: String,
        val imagePath: String,
    ) : UiIntent()
    data class AutounattendBindingsLoaded(
        val bindings: List<AutounattendBinding>,
        val error: String? = null,
    ) : UiIntent()

    /** Real copy finished; the imported template path is now selectable without another tap. */
    data class WindowsTemplateImported(val path: String) : UiIntent()

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
    data class SetRescueMap(
        val uri: String?,
        val inspection: RescueMapInspection? = null,
        val requestId: Long = 0L,
    ) : UiIntent()

    /** Read-only scan of every sector; reports unreadable ranges. */
    data object StartReadScan : UiIntent()

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

    data object CancelOperation : UiIntent()

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

    /** Create a real folder named [name] inside [AppUiState.currentPath]. */
    data class CreateFolder(val name: String) : UiIntent()

    /** Delete entry [name] from [AppUiState.currentPath] on the volume. */
    data class DeleteEntry(val name: String) : UiIntent()

    /** Rename entry [from] to [to] on exactly the captured device/partition. */
    data class RenameEntry(
        val from: String,
        val to: String,
        val binding: PartitionBinding,
    ) : UiIntent()

    /** Request confirmation to change the selected partition's on-disk type (ESP or data). */
    data class RequestSetPartitionRole(val role: PartitionRole) : UiIntent()

    /** Request confirmation to change the selected partition's legacy bootable flag. */
    data class RequestSetPartitionBootable(val bootable: Boolean) : UiIntent()

    /** Stream the SAF [uri] into [AppUiState.currentPath] in chunks. */
    data class UploadFile(val uri: String) : UiIntent()

    /** Copy entry [name] from [AppUiState.currentPath] to the SAF destination [uri]. */
    data class DownloadFile(
        val name: String,
        val uri: String,
    ) : UiIntent()

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

    /**
     * Drop the in-app log immediately, without confirmation and regardless of a running
     * operation. New lines may still arrive; Android logcat is not touched.
     */
    data object ClearLog : UiIntent()
    /** Store the latest Ventoy release tag fetched from GitHub (null = check failed). */
    data class SetLatestVentoyRelease(val tag: String?) : UiIntent()

    /**
     * Ask for a read-only free-space measurement of the selected partition. The host
     * answers with [FilesystemUsageResulted]; it runs under the files lock, off the main
     * thread, and only when no operation or confirmation is pending.
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
    ) : UiIntent()
}

sealed interface UiEffect {
    data class SubmitDestructive(
        val action: DestructiveAction,
        val confirmation: DestructiveConfirmation,
    ) : UiEffect

    data object CancelOperation : UiEffect

    data class LinkXml(
        val imagePaths: List<String>,
        val templatePath: String?,
        val allowDestructiveTemplate: Boolean,
        val linkOverwrite: Boolean,
        val confirmation: DestructiveConfirmation,
    ) : UiEffect

    data class LoadFiles(val path: String) : UiEffect

    data class CreateFolder(val path: String, val name: String) : UiEffect

    data class DeleteEntry(val path: String, val name: String) : UiEffect

    data class RenameEntry(
        val path: String,
        val name: String,
        val newName: String,
        val binding: PartitionBinding,
    ) : UiEffect

    data class UploadFile(val path: String, val uri: String) : UiEffect

    data class DownloadFile(val path: String, val name: String, val uri: String) : UiEffect

    data class StageUsbImageForRawWrite(
        val path: String,
        val name: String,
        val binding: PartitionBinding? = null,
    ) : UiEffect

    data object ReadScan : UiEffect

    data object RescueBackup : UiEffect

    data object CaptureGptBackup : UiEffect

    data object RunSelfTest : UiEffect

    data class LoadFilesystemUsage(
        val partitionId: String,
        val usageKey: FilesystemUsageKey,
        val usageGeneration: Long,
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
    ) : UiEffect

    data class LoadVentoyGlobalSettings(val requestToken: Long) : UiEffect

    /** Read one directory level; [path] is normalized before it reaches the host. */
    data class LoadVentoyFolders(val requestToken: Long, val path: String = "/") : UiEffect

    data class ApplyVentoyControls(
        val settings: List<VentoyControlSetting>,
    ) : UiEffect

    data class BindAutounattend(
        val xmlName: String,
        val imagePaths: List<String>,
        val allowDestructiveTemplate: Boolean,
        val expectedTemplateSha256: String?,
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

    fun reduce(state: AppUiState, intent: UiIntent): ReduceResult =
        when (intent) {
            is UiIntent.SelectTab -> {
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
                    ReduceResult(state.copy(statusMessage = pickExitBlock))
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
                                statusMessage =
                                    if (cancelHost) {
                                        UiMessage.Key(UiMessageKey.ImportInspectionCancelling)
                                    } else {
                                        null
                                    },
                                filesPickSession = newFilesPickSession(),
                                filesPickMode = null,
                                pendingIsoImport = null,
                                deferredIsoImport = null,
                                isoImportError = null,
                                isoImportSafInFlight = null,
                                isoImportGeneration = state.isoImportGeneration + 1,
                            )
                        } else {
                            state.copy(tab = intent.tab, statusMessage = null)
                        }
                    // Entering Files re-reads the open folder: changes may have happened while
                    // another tab owned the screen. One request per entry, none while one is
                    // already outstanding.
                    val base =
                        if (enteringFiles &&
                            !state.filesPending &&
                            state.presence == DevicePresence.Ready &&
                            state.actionsEnabled
                        ) {
                            filesReload(switched)
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

            is UiIntent.SelectPartition ->
                if (state.filesPickMode != null) {
                    // The session is pinned to one partition; switching would browse another
                    // volume while the caller still thinks it is choosing from the pinned one.
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                    )
                } else if (!state.actionsEnabled) {
                    // Switching mid-operation would post a Running LoadFiles over the live
                    // operation and orphan it from Cancel; report only why nothing changed.
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state.copy(
                            selectedPartitionId = intent.partitionId,
                            currentPath = "/",
                            files = emptyList(),
                            filesError = null,
                            filesListGeneration = state.filesListGeneration + 1L,
                            filesPending = true,
                            // A value measured for the previous partition is not current.
                            filesystemUsage = null,
                            rawSourceCandidates = null,
                            rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                            rawSourceCandidatesIdentity = null,
                            rawSourceCandidatesTruncated = false,
                            rawSourceCandidatesError = null,
                            pendingIsoImport = null,
                            deferredIsoImport = null,
                            isoImportError = null,
                            isoImportGeneration = state.isoImportGeneration + 1,
                            // Ventoy/XML catalogs are stick-scoped, not partition-scoped.
                        ),
                        effects = listOf(UiEffect.LoadFiles(path = "/")),
                    )
                }

            is UiIntent.BeginFilesPick -> beginFilesPick(state, intent)

            UiIntent.CancelFilesPick -> cancelFilesPick(state)

            is UiIntent.ConfirmFilesPickFile -> confirmFilesPickFile(state, intent.name)

            UiIntent.ConfirmFilesPickFolder -> confirmFilesPickFolder(state)

            is UiIntent.FilesPickCopySucceeded ->
                filesPickCopySucceeded(
                    state,
                    intent.path,
                    intent.sizeBytes,
                    intent.pickSessionId,
                )

            is UiIntent.ClearFilesPickSelection ->
                if (state.filesPickSelections.isEmpty()) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            filesPickSelections =
                                if (intent.purpose == null) {
                                    emptyMap()
                                } else {
                                    state.filesPickSelections - intent.purpose
                                },
                        ),
                    )
                }

            is UiIntent.SetVtoyEfiWritable ->
                when {
                    !intent.enabled ->
                        if (state.vtoyEfiWritePartitionId == null ||
                            state.vtoyEfiWritePartitionId == intent.partitionId
                        ) {
                            ReduceResult(
                                state.copy(
                                    vtoyEfiWritePartitionId = null,
                                    vtoyEfiWriteStartLba = null,
                                    vtoyEfiWriteSectorCount = null,
                                ),
                            )
                        } else {
                            ReduceResult(state)
                        }
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else -> {
                        val target = state.partitions.firstOrNull {
                            it.id == intent.partitionId && it.readOnly
                        }
                        val start = target?.startLba
                        val count = target?.sectorCount
                        when {
                            target == null ->
                                ReduceResult(
                                    state.copy(
                                        statusMessage = message(
                                            UiMessageKey.EfiPartitionMissing,
                                            intent.partitionId,
                                        ),
                                    ),
                                )
                            start == null || count == null ->
                                ReduceResult(
                                    state.copy(
                                        statusMessage = UiMessage.Key(UiMessageKey.EfiGeometryUnknown),
                                    ),
                                )
                            else ->
                                ReduceResult(
                                    state.copy(
                                        vtoyEfiWritePartitionId = target.id,
                                        vtoyEfiWriteStartLba = start,
                                        vtoyEfiWriteSectorCount = count,
                                    ),
                                )
                        }
                    }
                }
            is UiIntent.SetVtoyBypassChecks ->
                ReduceResult(state.copy(vtoyBypassChecks = intent.enabled))
            is UiIntent.SetVtoyLocalAccount ->
                ReduceResult(state.copy(vtoyLocalAccount = intent.enabled))

            is UiIntent.SelectFormatFilesystem -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state)
                } else {
                    val id = intent.id
                    if (id !in FormatCatalog.supportedFormatIds()) {
                        ReduceResult(
                            state.copy(
                                statusMessage = message(UiMessageKey.FormatUnsupported, FormatCatalog.label(id)),
                            ),
                        )
                    } else {
                        ReduceResult(state.copy(selectedFormatId = id, statusMessage = null))
                    }
                }
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
                // Busy/PermissionRequired probe on the live stick must not wipe browse
                // state: host sends empty partitions until Ready.
                val probingRetain =
                    sameStick &&
                        (intent.presence == DevicePresence.Busy ||
                            intent.presence == DevicePresence.PermissionRequired)
                val partitions =
                    if (probingRetain && intent.partitions.isEmpty()) {
                        state.partitions
                    } else {
                        intent.partitions
                    }
                val selected =
                    partitions
                        .firstOrNull { it.id == state.selectedPartitionId }
                        ?.id
                        ?: partitions.firstOrNull()?.id
                val candidateMatchesDevice =
                    intent.presence == DevicePresence.Ready &&
                        trustedStick &&
                        intent.device?.identity == state.linkOverwriteCandidateIdentity &&
                        intent.device?.attachGeneration == state.linkOverwriteCandidateGeneration
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
                val retainedEfi = state.vtoyEfiWritePartitionId?.takeIf { retainPrivileged }?.let { id ->
                    partitions.firstOrNull {
                        it.id == id &&
                            it.readOnly &&
                            it.startLba == state.vtoyEfiWriteStartLba &&
                            it.sectorCount == state.vtoyEfiWriteSectorCount
                    }
                }
                // Keep the open folder on the same stick while the previously selected
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
                // Only an open Files tab mounts; elsewhere the next tab entry re-lists.
                val loadFilesNow = reloadFiles && state.tab == ShellTab.Files && actionsAfter
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
                ReduceResult(
                    state.copy(
                        presence = intent.presence,
                        device = device,
                        vtoyEfiWritePartitionId = retainedEfi?.id,
                        vtoyEfiWriteStartLba = retainedEfi?.startLba,
                        vtoyEfiWriteSectorCount = retainedEfi?.sectorCount,
                        partitions = partitions,
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
                            if (probingRetain && intent.partitions.isEmpty()) {
                                state.partitionTableDamaged
                            } else {
                                intent.partitionTableDamaged
                            },
                        partitionTableAmbiguous =
                            if (probingRetain && intent.partitions.isEmpty()) {
                                state.partitionTableAmbiguous
                            } else {
                                intent.partitionTableAmbiguous
                            },
                        partitionScheme =
                            if (probingRetain && intent.partitions.isEmpty()) {
                                state.partitionScheme
                            } else {
                                intent.partitionScheme
                            },
                        rawGeometry =
                            if (probingRetain && intent.partitions.isEmpty() && intent.rawGeometry == null) {
                                state.rawGeometry
                            } else {
                                intent.rawGeometry
                            },
                        rangeProbe = if (probingRetain) state.rangeProbe else null,
                        rangeProbeRequestedRange =
                            if (probingRetain) state.rangeProbeRequestedRange else null,
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
                            reloadFiles -> emptyList()
                            probingRetain -> state.files
                            else -> intent.files
                        },
                        filesError = if (probingRetain) state.filesError else null,
                        filesListGeneration = filesGeneration,
                        filesPending = loadFilesNow,
                        // A refresh may describe another attachment or geometry.
                        filesystemUsage = if (probingRetain) state.filesystemUsage else null,
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
                        windowsTemplatePath =
                            if (keepVentoy) state.windowsTemplatePath else null,
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
                        linkOverwriteCandidatePaths = emptyList(),
                        linkOverwriteCandidateTemplatePath = null,
                        autounattendState =
                            if (keepVentoy) state.autounattendState else AutounattendState.Missing,
                        autounattendBindings =
                            if (keepVentoy) state.autounattendBindings else null,
                        autounattendCandidates =
                            if (keepVentoy) state.autounattendCandidates else null,
                        autounattendRequestGeneration =
                            if (keepVentoy) {
                                state.autounattendRequestGeneration
                            } else {
                                state.autounattendRequestGeneration + 1
                            },
                        autounattendLoadInFlight =
                            if (keepVentoy) state.autounattendLoadInFlight else false,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                        ventoyGlobalSettings =
                            if (keepVentoy) state.ventoyGlobalSettings else null,
                        ventoyFolderPath =
                            if (keepVentoy) state.ventoyFolderPath else "/",
                        pendingVentoyControls =
                            if (keepVentoy) state.pendingVentoyControls else emptyList(),
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
                        linkOverwriteCandidate =
                            state.linkOverwriteCandidate.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateIdentity =
                            state.linkOverwriteCandidateIdentity.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateGeneration =
                            state.linkOverwriteCandidateGeneration.takeIf { candidateMatchesDevice },
                        statusMessage =
                            if (intent.presence == DevicePresence.Ready) {
                                // Probe posts Probing before Ready; keep other statuses (errors).
                                state.statusMessage.takeUnless {
                                    it is UiMessage.Key && it.key == UiMessageKey.Probing
                                }
                            } else {
                                presenceMessage(intent.presence)
                            },
                    ),
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
                )
            }

            is UiIntent.OperationUpdated -> {
                val progress = intent.progress
                val terminal =
                    when (progress?.state) {
                        OperationState.Completed,
                        OperationState.Partial,
                        OperationState.Failed,
                        OperationState.Cancelled,
                        -> true
                        else -> false
                    }
                val base =
                    state.copy(
                        operation = progress,
                        // A settings write succeeded and its bytes are final; a Partial keeps
                        // the staged list for an explicit retry.
                        pendingVentoyControls =
                            if (terminal &&
                                progress != null &&
                                progress.state == OperationState.Completed &&
                                progress.title.clearsPendingVentoyControls()
                            ) {
                                emptyList()
                            } else {
                                state.pendingVentoyControls
                            },
                        // A write, format or file mutation can change free space while the
                        // geometry stays identical, so the old number is not current.
                        filesystemUsage =
                            when (progress?.state) {
                                OperationState.Completed,
                                OperationState.Cancelled,
                                OperationState.Failed,
                                OperationState.Partial,
                                -> null
                                else -> state.filesystemUsage
                            },
                        presence =
                            when {
                                progress?.state == OperationState.Running ->
                                    if (state.presence == DevicePresence.Ready ||
                                        state.presence == DevicePresence.Busy
                                    ) {
                                        DevicePresence.Busy
                                    } else {
                                        state.presence
                                    }
                                progress?.state == OperationState.Completed ||
                                    progress?.state == OperationState.Cancelled ||
                                    progress?.state == OperationState.Failed ||
                                    progress?.state == OperationState.Partial ->
                                    if (state.presence == DevicePresence.Busy) {
                                        DevicePresence.Ready
                                    } else {
                                        state.presence
                                    }
                                else -> state.presence
                            },
                        statusMessage =
                            when (progress?.state) {
                                OperationState.Completed ->
                                    // Keep a more specific status (for example a staged-image
                                    // hint) when the operation has no result message.
                                    progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: progress.resultMessage?.let(UiMessage::Raw)
                                        ?: state.statusMessage?.takeIf { !it.isDefaultStateStatus() }
                                        ?: UiMessage.Key(UiMessageKey.Success)
                                OperationState.Cancelled ->
                                    progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: UiMessage.Key(UiMessageKey.CancelledStatus)
                                OperationState.Failed ->
                                    progress.errorMessage?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.FailedStatus)
                                OperationState.Partial ->
                                    progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: (progress.resultMessage ?: progress.errorMessage)
                                            ?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.PartialStatus)
                                OperationState.Running -> UiMessage.Key(UiMessageKey.RunningStatus)
                                OperationState.Queued -> UiMessage.Key(UiMessageKey.QueuedStatus)
                                null -> state.statusMessage
                            },
                    )
                // Disk layout/content mutations leave retained Ventoy/XML caches and the open
                // directory listing wrong. Catalogs are dropped here; the Files list is
                // cleared and re-read only when the Files tab is the visible one. Deferred
                // import-folder work is released by the reduce() wrapper below.
                val invalidates =
                    terminal && progress != null && progress.title.invalidatesVentoyStickCaches()
                if (invalidates) {
                    filesReload(dropVentoyStickCaches(base, keepTemplatePath = true))
                } else {
                    ReduceResult(base)
                }
            }

            UiIntent.InvalidateVentoyStickCaches ->
                ReduceResult(dropVentoyStickCaches(state, keepTemplatePath = true))

            is UiIntent.RequestCreatePartition -> {
                val device = state.device
                val valid = intent.sectorCount > 0UL && intent.startLba <= ULong.MAX_VALUE - (intent.sectorCount - 1UL) &&
                    state.diskSectorSize > 0 && (1024 * 1024) % state.diskSectorSize == 0 &&
                    intent.startLba % (1024 * 1024 / state.diskSectorSize).toULong() == 0UL && state.freeRegions.any { region ->
                    intent.startLba >= region.startLba &&
                        intent.startLba - region.startLba < region.sectorCount &&
                        intent.sectorCount <= region.sectorCount - (intent.startLba - region.startLba)
                }
                val requestedName = intent.name?.let(PartitionName::normalize)?.takeIf { it.isNotEmpty() }
                val nameProblem: UiMessage? = when {
                    requestedName == null -> null
                    // GPT is the only scheme with a name field; unknown counts as unsupported.
                    state.partitionScheme != TableScheme.GPT ->
                        UiMessage.Key(UiMessageKey.PartitionNameUnsupported)
                    else -> PartitionName.problem(requestedName)?.let {
                        UiMessage.Key(UiMessageKey.InvalidPartitionName)
                    }
                }
                val formatId = intent.formatId
                val formatProblem: UiMessage? = when {
                    formatId == null -> null
                    formatId !in FormatCatalog.supportedFormatIds() ->
                        message(UiMessageKey.FormatUnsupported, FormatCatalog.label(formatId))
                    else -> null
                }
                val labelProblem: UiMessage? = when {
                    intent.volumeLabel.isBlank() -> null
                    formatId == null -> UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
                    else -> VolumeLabel.problem(intent.volumeLabel, formatId)?.let {
                        UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
                    }
                }
                when {
                    state.partitionTableAmbiguous ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Key(UiMessageKey.PartitionTableAmbiguous),
                            ),
                        )
                    state.partitionTableDamaged ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.GptPrimaryDamaged)),
                        )
                    device == null || !state.actionsEnabled || !state.tableValid || !valid ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidFreeRegion)),
                        )
                    nameProblem != null -> ReduceResult(state.copy(statusMessage = nameProblem))
                    formatProblem != null -> ReduceResult(state.copy(statusMessage = formatProblem))
                    labelProblem != null -> ReduceResult(state.copy(statusMessage = labelProblem))
                    else -> {
                        val base = DestructiveConfirmation.request(device, DestructiveAction.CREATE_PARTITION)
                        ReduceResult(state.copy(pendingConfirmation = base.copy(
                            createStartLba = intent.startLba,
                            createSectorCount = intent.sectorCount,
                            createName = requestedName,
                            createFormatId = formatId,
                            volumeLabel = VolumeLabel.normalize(intent.volumeLabel),
                        )))
                    }
                }
            }

            is UiIntent.RequestRenamePartition -> {
                val device = state.device
                val target = state.selectedPartition()
                val normalized = PartitionName.normalize(intent.name)
                val problem = when {
                    device == null || !state.actionsEnabled -> presenceMessage(state.presence)
                    target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
                    state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
                    state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
                    !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
                    // Unknown or MBR scheme cannot store a GPT name; never drop it silently.
                    state.partitionScheme != TableScheme.GPT ->
                        UiMessage.Key(UiMessageKey.PartitionNameUnsupported)
                    // Renaming a protected ESP/VTOYEFI entry would drop the name-based write
                    // protection, so the EFI override does not lift this refusal.
                    target.readOnly -> message(UiMessageKey.ReadOnlyPartition, target.name)
                    target.startLba == null || target.sectorCount == null ->
                        UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
                    normalized.isEmpty() -> UiMessage.Key(UiMessageKey.InvalidPartitionName)
                    else -> PartitionName.problem(normalized)?.let {
                        UiMessage.Key(UiMessageKey.InvalidPartitionName)
                    } ?: if (normalized == target.name) {
                        UiMessage.Key(UiMessageKey.RenameSameName)
                    } else {
                        null
                    }
                }
                if (problem != null || device == null || target == null) {
                    ReduceResult(state.copy(statusMessage = problem))
                } else {
                    val base = DestructiveConfirmation.request(
                        device,
                        DestructiveAction.RENAME_PARTITION,
                        target.name,
                        target = target,
                    )
                    ReduceResult(
                        state.copy(pendingConfirmation = base.copy(partitionName = normalized)),
                    )
                }
            }

            is UiIntent.RequestRenameVolumeLabel -> {
                val device = state.device
                val target = state.selectedPartition()
                val filesystemId = target?.filesystemId
                val normalized = VolumeLabel.normalize(intent.label)
                val writeBlock = if (state.partitionTableAmbiguous) null else writeBlockMessage(state)
                val problem = when {
                    device == null || !state.actionsEnabled -> presenceMessage(state.presence)
                    target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
                    state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
                    writeBlock != null -> writeBlock
                    target.startLba == null || target.sectorCount == null ->
                        UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
                    filesystemId == null -> UiMessage.Key(UiMessageKey.FilesystemUnknown)
                    !FormatCatalog.supportsVolumeLabelRename(filesystemId) ->
                        message(UiMessageKey.VolumeLabelUnsupported, FormatCatalog.label(filesystemId))
                    normalized.isEmpty() -> UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
                    else -> VolumeLabel.problem(normalized, filesystemId)?.let {
                        UiMessage.Key(UiMessageKey.InvalidVolumeLabel)
                    }
                }
                if (problem != null || device == null || target == null) {
                    ReduceResult(state.copy(statusMessage = problem))
                } else {
                    val base = DestructiveConfirmation.request(
                        device,
                        DestructiveAction.RENAME_VOLUME_LABEL,
                        target.name,
                        target = target,
                    )
                    ReduceResult(
                        state.copy(pendingConfirmation = base.copy(volumeLabel = normalized)),
                    )
                }
            }

            is UiIntent.RequestInitializePartitionTable -> {
                val device = state.device
                val currentScheme = state.partitionScheme?.toPartitionScheme()
                val snapshot = state.partitions.map { tab ->
                    val start = tab.startLba
                    val count = tab.sectorCount
                    if (start == null || count == null) null else PartitionRef(start, count)
                }
                val problem = when {
                    device == null || !state.actionsEnabled -> presenceMessage(state.presence)
                    state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
                    state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
                    !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
                    currentScheme == null -> UiMessage.Key(UiMessageKey.SchemeUnknown)
                    currentScheme == intent.scheme -> UiMessage.Key(UiMessageKey.SchemeAlreadyCurrent)
                    snapshot.any { it == null } -> UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
                    intent.scheme == PartitionScheme.Mbr &&
                        device.capacityBytes > mbrAddressLimitBytes(state.diskSectorSize) ->
                        UiMessage.Key(UiMessageKey.SchemeMbrTooLarge)
                    else -> null
                }
                if (problem != null || device == null) {
                    ReduceResult(state.copy(statusMessage = problem))
                } else {
                    val base = DestructiveConfirmation.request(
                        device,
                        DestructiveAction.INITIALIZE_PARTITION_TABLE,
                    )
                    ReduceResult(
                        state.copy(
                            pendingConfirmation = base.copy(
                                initialScheme = currentScheme,
                                targetScheme = intent.scheme,
                                initialPartitions = snapshot.filterNotNull(),
                            ),
                        ),
                    )
                }
            }

            is UiIntent.RequestWriteRawRange -> {
                val device = state.device
                val geometry = state.rawGeometry
                val hasSource = state.imageUri != null || state.stagedRawImagePath != null
                when {
                    device == null || !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !hasSource ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectImageFirst)),
                        )
                    geometry == null ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Key(UiMessageKey.CannotReadPartitionTable),
                            ),
                        )
                    else -> {
                        val check =
                            usb.partition.RawRangePlan.check(
                                intent.startLba,
                                intent.sectorCount,
                                geometry,
                            )
                        if (check is usb.partition.RawRangeCheck.Rejected) {
                            ReduceResult(
                                state.copy(
                                    statusMessage = UiMessage.Key(UiMessageKey.RawRangeInvalid),
                                ),
                            )
                        } else {
                            ReduceResult(
                                state.copy(
                                    pendingConfirmation =
                                        DestructiveConfirmation.request(
                                            device,
                                            DestructiveAction.RAW_WRITE,
                                            rawStartLba = intent.startLba,
                                            rawSectorCount = intent.sectorCount,
                                            diskSectorCount = geometry.diskSectorCount,
                                            diskSectorSize = geometry.sectorSize,
                                            rawSourceName = rawImageSourceName(state),
                                        ),
                                    rangeProbe =
                                        state.rangeProbe?.takeIf {
                                            it.range.startLba == intent.startLba &&
                                                it.range.sectorCount == intent.sectorCount
                                        },
                                ),
                            )
                        }
                    }
                }
            }

            is UiIntent.RequestRangeProbe -> {
                val geometry = state.rawGeometry
                if (geometry == null ||
                    state.presence != DevicePresence.Ready ||
                    !state.actionsEnabled ||
                    intent.sectorCount == 0UL
                ) {
                    ReduceResult(
                        state.copy(
                            rangeProbe = null,
                            rangeProbeRequestedRange = null,
                        ),
                    )
                } else {
                    val requestId = (state.rangeProbeRequestId ?: 0L) + 1L
                    val requested =
                        usb.partition.RawWriteRange(intent.startLba, intent.sectorCount)
                    ReduceResult(
                        state.copy(
                            rangeProbe = null,
                            rangeProbeRequestId = requestId,
                            rangeProbeRequestedRange = requested,
                        ),
                        effects = listOf(
                            UiEffect.RangeProbe(
                                startLba = intent.startLba,
                                sectorCount = intent.sectorCount,
                                probeRequestId = requestId,
                            ),
                        ),
                    )
                }
            }

            is UiIntent.RangeProbeResulted -> {
                val device = state.device
                val geometry = state.rawGeometry
                val requestedRange = state.rangeProbeRequestedRange
                val requestId = state.rangeProbeRequestId
                val attachment =
                    device?.let {
                        usb.image.RangeProbeAttachment(it.identity, it.attachGeneration)
                    }
                val accepted =
                    requestId != null &&
                        requestedRange != null &&
                        attachment != null &&
                        geometry != null &&
                        intent.result.requestId == requestId &&
                        intent.result.matches(
                            requestedRange,
                            geometry.diskSectorCount,
                            geometry.sectorSize,
                            attachment,
                        )
                // A late answer for another request, range, attachment or geometry describes
                // something the user no longer sees: it is ignored, never allowed to clear a
                // newer probe that is already on screen.
                if (accepted) {
                    ReduceResult(state.copy(rangeProbe = intent.result))
                } else {
                    ReduceResult(state)
                }
            }

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
                        intent.action == DestructiveAction.GPT_RESTORE && state.backupUri == null ->
                            UiMessage.Key(UiMessageKey.ChooseGptBackup)
                        intent.action == DestructiveAction.RESCUE_RESTORE &&
                            (state.rescueImageUri == null || state.rescueMapUri == null) ->
                            UiMessage.Key(UiMessageKey.ChooseRescueFiles)
                        intent.action == DestructiveAction.GPT_RESTORE ->
                            gptRestoreInspectionProblem(state)
                        intent.action == DestructiveAction.RESCUE_RESTORE ->
                            rescueRestoreInspectionProblem(state)
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
                        else -> null
                    }
                if (device != null && state.actionsEnabled && missingInput != null) {
                    ReduceResult(state.copy(statusMessage = missingInput))
                } else if (device == null || !state.actionsEnabled) {
                    ReduceResult(
                        state.copy(
                            statusMessage =
                                when {
                                    device == null -> presenceMessage(state.presence)
                                    state.isRunning -> UiMessage.Key(UiMessageKey.WaitForOperation)
                                    else -> presenceMessage(state.presence)
                                },
                        ),
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
                                    rawStartLba =
                                        if (intent.action == DestructiveAction.RAW_WRITE) 0UL else null,
                                    rawSectorCount =
                                        if (intent.action == DestructiveAction.RAW_WRITE) {
                                            state.rawGeometry?.diskSectorCount
                                                ?: defaultWholeDiskSectors(state)
                                        } else {
                                            null
                                        },
                                    diskSectorCount =
                                        if (intent.action == DestructiveAction.RAW_WRITE) {
                                            state.rawGeometry?.diskSectorCount
                                                ?: defaultWholeDiskSectors(state)
                                        } else {
                                            null
                                        },
                                    diskSectorSize =
                                        if (intent.action == DestructiveAction.RAW_WRITE) {
                                            state.rawGeometry?.sectorSize ?: state.diskSectorSize
                                        } else {
                                            null
                                        },
                                    rawSourceName =
                                        if (intent.action == DestructiveAction.RAW_WRITE) {
                                            rawImageSourceName(state)
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
                                    // the confirmed install/update will write.
                                    ventoyControls = state.pendingVentoyControls,
                                ),
                        ),
                    )
                }
            }

            is UiIntent.RequestSetPartitionRole ->
                requestPartitionEdit(
                    state,
                    DestructiveAction.SET_PARTITION_ROLE,
                    role = intent.role,
                )

            is UiIntent.RequestSetPartitionBootable ->
                requestPartitionEdit(
                    state,
                    DestructiveAction.SET_PARTITION_BOOTABLE,
                    bootable = intent.bootable,
                )

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
                            pending.targetScheme == pending.initialScheme ||
                            state.partitionScheme?.toPartitionScheme() != pending.initialScheme ||
                            !state.tableValid) -> true
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
                            state.pendingAutounattendGeneration == null) -> true
                    // The confirmed control snapshot must still be writable.
                    (action == DestructiveAction.VENTOY_INSTALL ||
                        action == DestructiveAction.VENTOY_UPDATE ||
                        action == DestructiveAction.VENTOY_DOWNLOAD_INSTALL) &&
                        pending?.ventoyControls?.let {
                            VentoyControlCatalog.problem(it) != null
                        } == true -> true
                    else -> false
                }
                // Fail-closed: null device/pending/action never reach effect branches.
                if (invalidState || action == null || pending == null || device == null) {
                    ReduceResult(state.copy(pendingConfirmation = null))
                } else if (action == DestructiveAction.VENTOY_LINK_XML) {
                    val paths =
                        pending.imagePaths.takeIf { it.isNotEmpty() }
                            ?: pending.isoName?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                            ?: emptyList()
                    if (paths.isEmpty()) {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                statusMessage = UiMessage.Key(UiMessageKey.NoIsoForLink),
                            ),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                statusMessage = message(
                                    UiMessageKey.WritingXml,
                                    paths.first().substringAfterLast('/') +
                                        if (paths.size > 1) " +${paths.size - 1}" else "",
                                ),
                            ),
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
                    val pendingGen = state.pendingAutounattendGeneration
                    val target = pending.xmlTargetName
                    if (pendingGen == null ||
                        target.isNullOrBlank() ||
                        pendingGen.xmlName != target ||
                        pendingGen.identity != device.identity ||
                        pendingGen.attachGeneration != device.attachGeneration
                    ) {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation = null,
                                pendingAutounattendGeneration = null,
                                statusMessage = UiMessage.Key(UiMessageKey.TargetChanged),
                            ),
                        )
                    } else {
                        val layout = pendingGen.params.diskLayout
                        val layoutProblems =
                            if (layout != null && layout.writeLayout) {
                                targetLayoutProblems(layout)
                            } else {
                                emptyList()
                            }
                        if (layoutProblems.isNotEmpty()) {
                            ReduceResult(
                                state.copy(
                                    pendingConfirmation = null,
                                    pendingAutounattendGeneration = null,
                                    statusMessage = layoutProblemMessage(layoutProblems),
                                ),
                            )
                        } else if (layout != null && layout.writeLayout) {
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
                                    diskLayoutConfirmation = layoutConfirmation(layout, plan),
                                    statusMessage = UiMessage.Raw(
                                        "Confirm the target PC disk layout before generating XML",
                                    ),
                                ),
                            )
                        } else {
                            ReduceResult(
                                state.copy(
                                    pendingConfirmation = null,
                                    pendingAutounattendGeneration = null,
                                    statusMessage = UiMessage.Raw("Generating unattend XML"),
                                ),
                                effects = listOf(
                                    UiEffect.GenerateAutounattend(
                                        xmlName = pendingGen.xmlName,
                                        autounattendParams = pendingGen.params,
                                        imagePaths = pendingGen.imagePaths,
                                        overwriteXml = true,
                                        allowDestructiveTemplate = pendingGen.allowDestructiveTemplate,
                                        expectedTemplateSha256 = pendingGen.expectedTemplateSha256,
                                        expectedPlanSha256 = pendingGen.expectedPlanSha256,
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
                val clearGen =
                    state.pendingConfirmation?.action ==
                        DestructiveAction.VENTOY_UNATTEND_XML_WRITE
                ReduceResult(
                    state.copy(
                        pendingConfirmation = null,
                        pendingAutounattendGeneration =
                            if (clearGen) null else state.pendingAutounattendGeneration,
                    ),
                )
            }
            is UiIntent.RequestLinkOverwrite -> {
                val device = state.device
                val candidateMatches =
                    state.linkOverwriteCandidate == intent.fileName &&
                        state.linkOverwriteCandidateIdentity == device?.identity &&
                        state.linkOverwriteCandidateGeneration == device?.attachGeneration
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                } else if (!candidateMatches) {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                        linkOverwriteCandidatePaths = emptyList(),
                        linkOverwriteCandidateTemplatePath = null,
                        statusMessage = UiMessage.Key(UiMessageKey.LinkMismatch),
                    ))
                } else {
                    val paths =
                        intent.imagePaths.takeIf { it.isNotEmpty() }
                            ?: state.linkOverwriteCandidatePaths.takeIf { it.isNotEmpty() }
                            ?: listOf(intent.fileName)
                    ReduceResult(state.copy(
                        pendingConfirmation = DestructiveConfirmation.request(
                            device,
                            DestructiveAction.VENTOY_LINK_XML,
                            isoName = intent.fileName,
                            linkOverwrite = true,
                            imagePaths = paths,
                            templatePath = intent.templatePath ?: state.linkOverwriteCandidateTemplatePath,
                        ),
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                        linkOverwriteCandidatePaths = emptyList(),
                        linkOverwriteCandidateTemplatePath = null,
                        statusMessage = null,
                    ))
                }
            }
            is UiIntent.LinkOverwriteAvailable -> {
                val device = state.device
                if (!state.actionsEnabled || device == null) {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = null,
                        linkOverwriteCandidateIdentity = null,
                        linkOverwriteCandidateGeneration = null,
                        linkOverwriteCandidatePaths = emptyList(),
                        linkOverwriteCandidateTemplatePath = null,
                    ))
                } else {
                    ReduceResult(state.copy(
                        linkOverwriteCandidate = intent.fileName,
                        linkOverwriteCandidateIdentity = device.identity,
                        linkOverwriteCandidateGeneration = device.attachGeneration,
                        linkOverwriteCandidatePaths = intent.imagePaths,
                        linkOverwriteCandidateTemplatePath = intent.templatePath,
                        statusMessage = UiMessage.Key(UiMessageKey.LinkOverwriteNeeded),
                    ))
                }
            }

            is UiIntent.IsoActionRequested -> {
                if (!state.actionsEnabled) {
                    ReduceResult(
                        state.copy(statusMessage = presenceMessage(state.presence)),
                    )
                } else when (intent.action) {
                    IsoAction.SendToRawWriter -> {
                        val name = intent.fileName.substringAfterLast('/')
                        ReduceResult(
                            state.copy(statusMessage = message(UiMessageKey.StagingFile, name)),
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
                                state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)),
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
                                    statusMessage = null,
                                ),
                            )
                        }
                    }
                }
            }

            is UiIntent.SetImage -> {
                val uri = intent.uri
                if (!state.actionsEnabled) {
                    // Busy / pending confirm — do not clear staged source under a live raw write.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            imageUri = uri,
                            rangeProbe = null,
                            rangeProbeRequestedRange = null,
                            imageName = if (uri != null) intent.name else null,
                            imageSizeBytes = if (uri != null) intent.sizeBytes else null,
                            stagedRawImagePath = if (uri != null) null else state.stagedRawImagePath,
                            stagedRawImageLabel = if (uri != null) null else state.stagedRawImageLabel,
                            statusMessage =
                                if (uri == null) {
                                    UiMessage.Key(UiMessageKey.ImageCleared)
                                } else {
                                    UiMessage.Key(
                                        UiMessageKey.ImageReady,
                                        listOf(intent.name ?: uri),
                                    )
                                },
                        ),
                    )
                }
            }

            is UiIntent.SetStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotChangeStaged)))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = intent.path,
                            stagedRawImageLabel = intent.label,
                            imageUri = null,
                            rangeProbe = null,
                            rangeProbeRequestedRange = null,
                            imageName = null,
                            imageSizeBytes = null,
                            statusMessage = message(UiMessageKey.StagedImageReady, intent.label),
                        ),
                    )
                }

            is UiIntent.ClearStagedRawImage ->
                if (state.isRunning || state.operation?.state == OperationState.Queued || state.pendingConfirmation != null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.CannotClearStaged)))
                } else {
                    ReduceResult(
                        state.copy(
                            stagedRawImagePath = null,
                            stagedRawImageLabel = null,
                            rangeProbe = null,
                            rangeProbeRequestedRange = null,
                            statusMessage = UiMessage.Key(UiMessageKey.StagedCleared),
                        ),
                    )
                }
            is UiIntent.LoadRawSourceCandidates -> {
                val device = state.device
                val partition = state.selectedPartition()
                when {
                    !state.actionsEnabled ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    if (state.isRunning ||
                                        state.operation?.state == OperationState.Queued ||
                                        state.pendingConfirmation != null
                                    ) {
                                        UiMessage.Key(UiMessageKey.WaitForOperation)
                                    } else {
                                        presenceMessage(state.presence)
                                    },
                            ),
                        )
                    device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    partition == null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
                    else -> {
                        val generation = state.rawSourceCandidatesGeneration + 1
                        ReduceResult(
                            state.copy(
                                rawSourceCandidates = null,
                                rawSourceCandidatesGeneration = generation,
                                rawSourceCandidatesIdentity = device.identity,
                                rawSourceCandidatesTruncated = false,
                                rawSourceCandidatesError = null,
                            ),
                            effects = listOf(
                                UiEffect.LoadRawSources(
                                    binding = PartitionBinding(
                                        device = device,
                                        partitionId = partition.id,
                                        startLba = partition.startLba,
                                        sectorCount = partition.sectorCount,
                                    ),
                                    requestGeneration = generation,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.RawSourceCandidatesLoaded -> {
                val device = state.device
                val partitionId = state.selectedPartition()?.id
                if (device == null ||
                    intent.generation != state.rawSourceCandidatesGeneration ||
                    intent.identity != device.identity
                ) {
                    // Late result for another attachment, partition, or request.
                    ReduceResult(state)
                } else {
                    val accepted =
                        intent.candidates.filter {
                            it.partitionId == partitionId &&
                                it.path.startsWith("/") &&
                                it.path != "/" &&
                                it.name.isNotBlank()
                        }
                    val notice = when {
                        intent.truncated ->
                            UiMessage.Raw(
                                "Raw source list truncated at ${accepted.size} files; " +
                                    "use Files to pick from the rest",
                            )
                        intent.unreadable > 0 ->
                            UiMessage.Raw(
                                "${intent.unreadable} folder(s) could not be read; " +
                                    "files in them are not listed",
                            )
                        else -> state.statusMessage
                    }
                    ReduceResult(
                        state.copy(
                            rawSourceCandidates = accepted,
                            rawSourceCandidatesTruncated = intent.truncated,
                            rawSourceCandidatesError = null,
                            statusMessage = notice,
                        ),
                    )
                }
            }

            is UiIntent.RawSourceCandidatesFailed -> {
                val device = state.device
                if (device == null ||
                    intent.generation != state.rawSourceCandidatesGeneration ||
                    intent.identity != device.identity
                ) {
                    // Stale chooser A — keep B status/candidates; log so close/I/O is not silent.
                    ReduceResult(appendLogLine(state, "Raw sources (stale): ${intent.message}"))
                } else {
                    // Bound chooser error always; global status only when no other op owns the slot.
                    val opOwnsStatus =
                        state.operation?.state == OperationState.Running ||
                            state.operation?.state == OperationState.Queued
                    val next =
                        state.copy(
                            rawSourceCandidates = emptyList(),
                            rawSourceCandidatesError = intent.message,
                            rawSourceCandidatesTruncated = false,
                            statusMessage =
                                if (opOwnsStatus) {
                                    state.statusMessage
                                } else {
                                    UiMessage.Raw(intent.message)
                                },
                        )
                    if (opOwnsStatus) {
                        ReduceResult(appendLogLine(next, "Raw sources: ${intent.message}"))
                    } else {
                        ReduceResult(next)
                    }
                }
            }

            is UiIntent.SelectRawSource -> {
                val device = state.device
                val partition = state.selectedPartition()
                val candidate =
                    state.rawSourceCandidates?.firstOrNull {
                        it.path == intent.path &&
                            it.name == intent.name &&
                            it.partitionId == partition?.id
                    }
                when {
                    !state.actionsEnabled ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    if (state.pendingConfirmation != null) {
                                        UiMessage.Key(UiMessageKey.WaitForOperation)
                                    } else {
                                        presenceMessage(state.presence)
                                    },
                            ),
                        )
                    device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    partition == null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
                    candidate == null ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    UiMessage.Raw("Selected source is no longer listed; reload the list"),
                            ),
                        )
                    else -> {
                        val name = candidate.path.substringAfterLast('/')
                        val parent = candidate.path.substringBeforeLast('/', "").ifEmpty { "/" }
                        if (name != candidate.name || name.isEmpty()) {
                            ReduceResult(
                                state.copy(statusMessage = UiMessage.Raw("Invalid source path ${candidate.path}")),
                            )
                        } else {
                            ReduceResult(
                                state.copy(statusMessage = message(UiMessageKey.StagingFile, name)),
                                effects = listOf(
                                    UiEffect.StageUsbImageForRawWrite(
                                        path = parent,
                                        name = name,
                                        binding = PartitionBinding(
                                            device = device,
                                            partitionId = partition.id,
                                            startLba = partition.startLba,
                                            sectorCount = partition.sectorCount,
                                        ),
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            UiIntent.CancelRawSourceLoad ->
                ReduceResult(
                    state.copy(
                        rawSourceCandidates = null,
                        rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                        rawSourceCandidatesIdentity = null,
                        rawSourceCandidatesTruncated = false,
                        rawSourceCandidatesError = null,
                    ),
                    effects = listOf(UiEffect.CancelRawSources),
                )

            UiIntent.LoadWindowsIsoCandidates -> {
                val device = state.device
                when {
                    !state.actionsEnabled ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    if (state.isRunning ||
                                        state.operation?.state == OperationState.Queued ||
                                        state.pendingConfirmation != null
                                    ) {
                                        UiMessage.Key(UiMessageKey.WaitForOperation)
                                    } else {
                                        presenceMessage(state.presence)
                                    },
                            ),
                        )
                    device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else -> {
                        val generation = state.windowsIsoCandidatesGeneration + 1
                        ReduceResult(
                            state.copy(
                                windowsIsoCandidates = null,
                                windowsIsoCandidatesGeneration = generation,
                                windowsIsoCandidatesIdentity = device.identity,
                                windowsIsoCandidatesError = null,
                            ),
                            effects = listOf(
                                UiEffect.LoadWindowsIsoCandidates(
                                    requestGeneration = generation,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.WindowsIsoCandidatesLoaded -> {
                val device = state.device
                if (device == null ||
                    intent.generation != state.windowsIsoCandidatesGeneration ||
                    intent.identity != device.identity
                ) {
                    ReduceResult(state)
                } else {
                    val files =
                        intent.candidates
                            .asSequence()
                            .filter { it.path.startsWith("/") && it.path != "/" && it.name.isNotBlank() }
                            .distinctBy { it.path.lowercase() }
                            .sortedBy { it.path.lowercase() }
                            .toList()
                    ReduceResult(
                        state.copy(
                            windowsIsoCandidates = files,
                            windowsIsoCandidatesError = intent.error,
                            // Only fires while Ventoy tab open (or manual refresh).
                            statusMessage = intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                        ),
                    )
                }
            }

            is UiIntent.SelectWindowsIso -> {
                val device = state.device
                val selected =
                    state.windowsIsoCandidates?.firstOrNull {
                        it.path.equals(intent.isoName, ignoreCase = true) ||
                            it.name.equals(intent.isoName, ignoreCase = true)
                    }
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    device == null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                    selected == null ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "\"${intent.isoName}\" is not on the Ventoy data partition; reload the list or import it",
                                ),
                            ),
                        )
                    else -> {
                        ReduceResult(
                            state.copy(
                                pendingConfirmation =
                                    DestructiveConfirmation.request(
                                        device = device,
                                        action = DestructiveAction.VENTOY_LINK_XML,
                                        isoName = selected.path,
                                        linkOverwrite = false,
                                    ),
                                statusMessage = null,
                            ),
                        )
                    }
                }
            }

            is UiIntent.RequestImportWindowsIso -> {
                val uri = intent.uri.trim()
                val pick = state.filesPickMode
                val pickProblem =
                    if (pick == null) {
                        // Images exist only inside the simplified picker. A late/duplicate
                        // result after its closure must never fall back to the default
                        // /ISO folder; XML templates keep their independent flow.
                        if (intent.kind == WindowsImportKind.Image) {
                            UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                        } else {
                            null
                        }
                    } else {
                        val directory = safePickPath(intent.directory ?: state.currentPath)
                        when {
                            pick.purpose != FilesPickPurpose.WindowsImage ||
                                intent.kind != WindowsImportKind.Image ->
                                UiMessage.Key(UiMessageKey.FilesPickActive)
                            intent.pickSessionId != pick.sessionId ->
                                UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                            !pick.matches(state) -> UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                            directory == null || directory != safePickPath(state.currentPath) ->
                                UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)
                            else -> null
                        }
                    }
                val staleContext =
                    intent.expectedAttachGeneration != null &&
                        (
                            state.device?.identity != intent.expectedIdentity ||
                                state.device?.attachGeneration !=
                                intent.expectedAttachGeneration
                        )
                val importDirectory = pick?.let { state.currentPath } ?: intent.directory
                if (staleContext || pickProblem != null) {
                    ReduceResult(
                        state.copy(
                            statusMessage =
                                pickProblem
                                    ?: UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
                        ),
                    )
                } else when {
                    !state.actionsEnabled ||
                        state.pendingIsoImport != null ||
                        state.isoImportSafInFlight != null ||
                        state.isoImportUsbInFlight != null ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    if (state.isoImportSafInFlight != null ||
                                        state.isoImportUsbInFlight != null ||
                                        state.isRunning ||
                                        state.operation?.state == OperationState.Queued ||
                                        state.pendingConfirmation != null
                                    ) {
                                        UiMessage.Key(UiMessageKey.WaitForOperation)
                                    } else {
                                        presenceMessage(state.presence)
                                    },
                            ),
                        )
                    state.device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    uri.isEmpty() ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Raw("No file selected for ISO import")))
                    else -> {
                        val generation = state.isoImportGeneration + 1
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                deferredIsoImport = null,
                                isoImportError = null,
                                isoImportSafInFlight = generation,
                                isoImportGeneration = generation,
                            ),
                            effects = listOf(
                                UiEffect.InspectIsoImport(
                                    uri = uri,
                                    requestGeneration = generation,
                                    importKind = intent.kind,
                                    importDirectory = importDirectory,
                                    pickSessionId = pick?.sessionId,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.WindowsIsoImportRejected ->
                if (intent.generation != state.isoImportGeneration) {
                    // A cancelled/replaced inspection must not overwrite the live result nor
                    // release the in-flight guard of the newer one.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                            isoImportError = intent.problem,
                        ),
                    )
                }

            is UiIntent.WindowsIsoImportSafFinished ->
                if (state.isoImportSafInFlight != intent.generation) {
                    ReduceResult(state)
                } else {
                    // The blocking read ended; a stuck provider no longer blocks new imports.
                    ReduceResult(state.copy(isoImportSafInFlight = null))
                }

            is UiIntent.WindowsIsoImportUsbStarted -> {
                val started = state.isoImportSafInFlight == intent.generation
                if (!started || intent.generation != state.isoImportGeneration) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            isoImportSafInFlight = null,
                            isoImportUsbInFlight = intent.generation,
                        ),
                    )
                }
            }

            is UiIntent.WindowsIsoImportFinished -> {
                val usbDone = state.isoImportUsbInFlight == intent.generation
                val safDone = state.isoImportSafInFlight == intent.generation
                if (!usbDone && !safDone) {
                    ReduceResult(state)
                } else {
                    // Disk is closed now; the deferred folder read may be released by the
                    // wrapper in this same reduce.
                    ReduceResult(
                        state.copy(
                            isoImportUsbInFlight = if (usbDone) null else state.isoImportUsbInFlight,
                            isoImportSafInFlight = if (safDone) null else state.isoImportSafInFlight,
                        ),
                    )
                }
            }

            is UiIntent.WindowsIsoImportInspected -> {
                val device = state.device
                val pick = state.filesPickMode
                val pickMismatch =
                    if (intent.pickSessionId == null) {
                        // A non-picker inspection must not land inside a live picker.
                        pick != null
                    } else {
                        pick == null ||
                            pick.sessionId != intent.pickSessionId ||
                            pick.purpose != FilesPickPurpose.WindowsImage ||
                            !pick.matches(state) ||
                            safePickPath(intent.directory ?: state.currentPath) !=
                            safePickPath(state.currentPath)
                    }
                if (device == null || intent.generation != state.isoImportGeneration) {
                    // Stale result for a cancelled/replaced inspection: keep the live guard.
                    ReduceResult(state)
                } else if (pickMismatch) {
                    // The pinned folder or attachment moved while the SAF read was in flight.
                    // Drop the result and disown its generation; nothing is written.
                    ReduceResult(
                        state.copy(
                            pendingIsoImport = null,
                            deferredIsoImport = null,
                            isoImportSafInFlight = null,
                            isoImportGeneration = state.isoImportGeneration + 1,
                            statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
                        ),
                        effects =
                            if (state.isoImportSafInFlight != null ||
                                state.isoImportUsbInFlight != null
                            ) {
                                listOf(UiEffect.CancelIsoImportInspection)
                            } else {
                                emptyList()
                            },
                    )
                } else {
                    val proposal =
                        IsoImportProposal(
                            uri = intent.uri,
                            name = intent.name,
                            targetName = intent.targetName,
                            sizeBytes = intent.sizeBytes,
                            exists = intent.exists,
                            identity = device.identity,
                            attachGeneration = device.attachGeneration,
                            requestGeneration = intent.generation,
                            kind = intent.kind,
                            directory =
                                intent.directory
                                    ?: if (intent.kind == WindowsImportKind.XmlTemplate) {
                                        "/ventoy"
                                    } else {
                                        "/ISO"
                                    },
                            templateMarkers = intent.templateMarkers,
                            partitionStartLba = pick?.startLba,
                            partitionSectorCount = pick?.sectorCount,
                            pickSessionId = pick?.sessionId,
                        )
                    if (intent.exists) {
                        // No write until the user explicitly accepts replacement. Kept even
                        // during a same-stick probe: the dialog itself waits for actionsEnabled.
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = proposal,
                                deferredIsoImport = null,
                                isoImportError = null,
                                statusMessage = UiMessage.Raw(
                                    "${importTarget(proposal.directory, intent.targetName)} already exists; confirm overwrite to replace it",
                                ),
                            ),
                        )
                    } else if (!state.actionsEnabled) {
                        // A same-stick probe turned the UI non-actionable after the SAF read
                        // started. Hold the result; the wrapper starts the import on the first
                        // quiet actionable intent instead of losing the user's pick.
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                deferredIsoImport = proposal,
                                isoImportError = null,
                                statusMessage = UiMessage.Raw(
                                    "Import of ${intent.targetName} waits for the stick to settle",
                                ),
                            ),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                deferredIsoImport = null,
                                statusMessage = UiMessage.Raw(
                                    "Importing ${intent.targetName} into ${proposal.directory}",
                                ),
                            ),
                            effects = listOf(
                                UiEffect.ImportIso(
                                    isoImport = proposal,
                                    overwrite = false,
                                    requestGeneration = intent.generation,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.ConfirmWindowsIsoImport -> {
                val proposal = state.pendingIsoImport
                val device = state.device
                val pick = state.filesPickMode
                val proposalSession = proposal?.pickSessionId
                val pickMoved =
                    proposal != null &&
                        when {
                            proposalSession == null -> pick != null
                            pick == null -> true
                            pick.sessionId != proposalSession -> true
                            pick.purpose != FilesPickPurpose.WindowsImage -> true
                            !pick.matches(state) -> true
                            else ->
                                safePickPath(proposal.directory) !=
                                    safePickPath(state.currentPath)
                        }
                when {
                    proposal == null || device == null ->
                        ReduceResult(state.copy(pendingIsoImport = null))
                    pickMoved ->
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
                            ),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    proposal.identity != device.identity ||
                        proposal.attachGeneration != device.attachGeneration ||
                        proposal.requestGeneration != state.isoImportGeneration ->
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                statusMessage = UiMessage.Raw("USB attachment changed; re-select the ISO"),
                            ),
                        )
                    proposal.exists && !intent.overwrite ->
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                statusMessage = UiMessage.Raw(
                                    "Overwrite of ${importTarget(proposal.directory, proposal.targetName)} was not confirmed",
                                ),
                            ),
                        )
                    else -> {
                        val overwrite = proposal.exists && intent.overwrite
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                statusMessage = UiMessage.Raw(
                                    "Importing ${proposal.targetName} into ${proposal.directory}",
                                ),
                            ),
                            effects = listOf(
                                UiEffect.ImportIso(
                                    isoImport = proposal,
                                    overwrite = overwrite,
                                    requestGeneration = proposal.requestGeneration,
                                ),
                            ),
                        )
                    }
                }
            }

            UiIntent.CancelWindowsIsoImport ->
                // Cancel invalidates the pending result/dialog and disowns the SAF read, so a
                // stuck provider cannot block a new import. The USB guard stays until the host
                // reports the closed disk (WindowsIsoImportFinished).
                ReduceResult(
                    state.copy(
                        pendingIsoImport = null,
                        deferredIsoImport = null,
                        isoImportError = null,
                        isoImportSafInFlight = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                    ),
                )

            is UiIntent.ReopenImportFolderPick -> {
                val device = state.device
                // Detach/permission probe means no attachment owns the failure; a running
                // operation still pins the same attachment and only defers the re-read.
                val attachmentStillShown =
                    device != null &&
                        device.identity == intent.identity &&
                        device.attachGeneration == intent.attachGeneration &&
                        intent.requestGeneration == state.isoImportGeneration &&
                        (
                            state.presence == DevicePresence.Ready ||
                                (state.presence == DevicePresence.Busy && state.isRunning)
                        )
                if (!attachmentStillShown) {
                    // Another attachment now owns the screen; a stale failure must not
                    // reopen the picker over it.
                    ReduceResult(state)
                } else {
                    // The cached folder list is proven stale: drop it, restart from the root
                    // (the old folder itself may be the one that vanished) and invalidate
                    // every in-flight answer under a new token. A busy/confirming UI only
                    // defers the re-read and the picker demand; neither may start USB work
                    // or open a dialog over a destructive confirmation.
                    val token = state.ventoyFoldersRequest + 1L
                    val path = "/"
                    val loadNow = state.actionsEnabled
                    if (state.filesPickMode != null) {
                        // The picker's own folder vanished: restart the Files listing from
                        // root and keep the pin. No Ventoy folder cache is involved.
                        ReduceResult(
                            state.copy(
                                currentPath = path,
                                files = emptyList(),
                                filesError = null,
                                filesListGeneration = state.filesListGeneration + 1L,
                                filesPending = loadNow,
                                pendingImportFolderPick = if (loadNow) intent.kind else null,
                                deferredImportFolderPick = if (loadNow) null else intent.kind,
                                pendingImportFolderPickLoad = !loadNow,
                            ),
                            effects =
                                if (loadNow) {
                                    listOf(UiEffect.LoadFiles(path = path))
                                } else {
                                    emptyList()
                                },
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                ventoyFolderPath = path,
                                ventoyFolders = null,
                                ventoyFoldersError = null,
                                ventoyFoldersLoading = loadNow,
                                ventoyFoldersPending = false,
                                ventoyFoldersRequest = token,
                                pendingImportFolderPick = if (loadNow) intent.kind else null,
                                deferredImportFolderPick = if (loadNow) null else intent.kind,
                                pendingImportFolderPickLoad = !loadNow,
                            ),
                            effects =
                                if (loadNow) {
                                    listOf(
                                        UiEffect.LoadVentoyFolders(
                                            requestToken = token,
                                            path = path,
                                        ),
                                    )
                                } else {
                                    emptyList()
                                },
                        )
                    }
                }
            }

            UiIntent.ImportFolderPickOpened ->
                // Consume only the UI demand: a deferred folder read keeps its own flag and
                // kind, so a terminal/confirmation release still fires afterwards.
                ReduceResult(state.copy(pendingImportFolderPick = null))

            UiIntent.SaveWindowsControls ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state,
                        effects = listOf(UiEffect.SaveWindowsControls),
                    )
                }

            is UiIntent.ToggleWindowsImage -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    // Same source the bindings UI renders; windowsIsoCandidates is template picker only.
                    val image =
                        state.autounattendCandidates?.images?.firstOrNull {
                            it.imagePath == intent.path
                        }
                    when {
                        image == null ->
                            ReduceResult(
                                state.copy(
                                    statusMessage = UiMessage.Raw(
                                        "Selected image is no longer listed; reload the list",
                                    ),
                                ),
                            )
                        // Bound rows use Unbind; problem rows stay untoggleable.
                        image.bound || image.problem != null ->
                            ReduceResult(state)
                        else -> {
                            val already = state.windowsSelectedImages.any { it == image.imagePath }
                            val selected =
                                if (already) {
                                    state.windowsSelectedImages.filterNot { it == image.imagePath }
                                } else {
                                    state.windowsSelectedImages + image.imagePath
                                }
                            ReduceResult(state.copy(windowsSelectedImages = selected))
                        }
                    }
                }
            }

            UiIntent.ClearWindowsSelection ->
                ReduceResult(state.copy(windowsSelectedImages = emptyList()))

            is UiIntent.SelectWindowsTemplate -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    val template =
                        state.windowsIsoCandidates?.firstOrNull { it.path == intent.path }
                    if (template == null) {
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Selected XML template is no longer listed; reload it",
                                ),
                            ),
                        )
                    } else {
                        // Re-selecting the same path changes no LaunchedEffect key, so the reload
                        // has to be driven by the intent itself.
                        val generation = state.autounattendRequestGeneration + 1
                        ReduceResult(
                            state.copy(
                                windowsTemplatePath = template.path,
                                autounattendState = AutounattendState.Missing,
                                autounattendBindings = null,
                                autounattendCandidates = null,
                                autounattendRequestGeneration = generation,
                                autounattendLoadInFlight = true,
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ),
                            effects = listOf(
                                UiEffect.LoadAutounattend(
                                    xmlName = template.path,
                                    requestGeneration = generation,
                                ),
                                UiEffect.LoadAutounattendCandidates(
                                    xmlName = template.path,
                                    requestGeneration = generation,
                                ),
                            ),
                        )
                    }
                }
            }

            UiIntent.ClearWindowsTemplate ->
                ReduceResult(
                    state.copy(
                        windowsTemplatePath = null,
                        autounattendState = AutounattendState.Missing,
                        autounattendBindings = null,
                        autounattendCandidates = null,
                        autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
                        autounattendLoadInFlight = false,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                )

            is UiIntent.WindowsTemplateImported -> {
                // The import overwrote the file this section was showing; nothing changes the
                // LaunchedEffect key, so the fresh bytes are read here.
                val generation = state.autounattendRequestGeneration + 1
                ReduceResult(
                    state.copy(
                        windowsTemplatePath = intent.path,
                        autounattendState = AutounattendState.Missing,
                        autounattendBindings = null,
                        autounattendCandidates = null,
                        autounattendRequestGeneration = generation,
                        autounattendLoadInFlight = true,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                    effects = listOf(
                        UiEffect.LoadAutounattend(
                            xmlName = intent.path,
                            requestGeneration = generation,
                        ),
                        UiEffect.LoadAutounattendCandidates(
                            xmlName = intent.path,
                            requestGeneration = generation,
                        ),
                    ),
                )
            }

            is UiIntent.RequestLinkWindowsImages -> {
                val device = state.device
                val selected = state.windowsSelectedImages
                val known =
                    selected.isNotEmpty() &&
                        selected.all { path ->
                            state.autounattendCandidates?.images?.any {
                                it.imagePath == path && !it.bound && it.problem == null
                            } == true
                        }
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    device == null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                    selected.isEmpty() ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Raw("Select at least one image")))
                    (intent.templatePath ?: state.windowsTemplatePath) == null ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Select an existing XML template from the stick or import one first",
                                ),
                            ),
                        )
                    !known ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "The image list changed; reload it before linking",
                                ),
                            ),
                        )
                    else -> {
                        val paths = selected
                        ReduceResult(
                            state.copy(
                                pendingConfirmation =
                                    DestructiveConfirmation.request(
                                        device = device,
                                        action = DestructiveAction.VENTOY_LINK_XML,
                                        isoName = paths.first(),
                                        imagePaths = paths,
                                        templatePath = intent.templatePath ?: state.windowsTemplatePath,
                                        allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                        templateSha256 = intent.expectedTemplateSha256,
                                    ),
                                statusMessage = null,
                            ),
                        )
                    }
                }
            }

            UiIntent.LoadWindowsImageLinks -> {
                val device = state.device
                when {
                    !state.actionsEnabled ->
                        ReduceResult(
                            state.copy(
                                statusMessage =
                                    if (state.isRunning ||
                                        state.operation?.state == OperationState.Queued ||
                                        state.pendingConfirmation != null
                                    ) {
                                        UiMessage.Key(UiMessageKey.WaitForOperation)
                                    } else {
                                        presenceMessage(state.presence)
                                    },
                            ),
                        )
                    device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else -> {
                        val generation = state.windowsImageLinksGeneration + 1
                        ReduceResult(
                            state.copy(
                                windowsImageLinks = null,
                                windowsImageLinksGeneration = generation,
                                windowsImageLinksIdentity = device.identity,
                            ),
                            effects = listOf(
                                UiEffect.LoadWindowsLinks(
                                    requestGeneration = generation,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.WindowsImageLinksLoaded -> {
                val device = state.device
                if (device == null ||
                    intent.generation != state.windowsImageLinksGeneration ||
                    intent.identity != device.identity
                ) {
                    ReduceResult(state)
                } else {
                    val links =
                        intent.links.filter {
                            it.image.startsWith("/") &&
                                it.image != "/" &&
                                it.templates.isNotEmpty()
                        }
                    ReduceResult(
                        state.copy(
                            windowsImageLinks = links,
                            statusMessage = intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                        ),
                    )
                }
            }

            is UiIntent.RequestUnlinkWindowsImages -> {
                val device = state.device
                val known =
                    intent.imagePaths.isNotEmpty() &&
                        intent.imagePaths.all { path ->
                            state.windowsImageLinks?.any { it.image == path } == true
                        }
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    device == null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink)))
                    !known ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "These bindings changed; reload the list before removing them",
                                ),
                            ),
                        )
                    else ->
                        ReduceResult(
                            state.copy(
                                pendingWindowsUnlink = intent.imagePaths,
                                statusMessage = UiMessage.Raw(
                                    "Remove ${intent.imagePaths.size} binding(s)? " +
                                        "Image files and XML files are kept",
                                ),
                            ),
                        )
                }
            }

            UiIntent.ConfirmWindowsUnlink -> {
                val paths = state.pendingWindowsUnlink
                when {
                    paths.isNullOrEmpty() ->
                        ReduceResult(state.copy(pendingWindowsUnlink = null))
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    state.device == null ->
                        ReduceResult(
                            state.copy(
                                pendingWindowsUnlink = null,
                                statusMessage = UiMessage.Key(UiMessageKey.NoDeviceForLink),
                            ),
                        )
                    else ->
                        ReduceResult(
                            state.copy(
                                pendingWindowsUnlink = null,
                                statusMessage = UiMessage.Raw(
                                    "Removing ${paths.size} binding(s); files stay on the stick",
                                ),
                            ),
                            effects = listOf(
                                UiEffect.UnlinkXml(
                                    imagePaths = paths,
                                ),
                            ),
                        )
                }
            }

            UiIntent.CancelWindowsUnlink ->
                ReduceResult(state.copy(pendingWindowsUnlink = null))

            is UiIntent.LoadAutounattend ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    val generation = state.autounattendRequestGeneration + 1
                    // The document and the whole-partition image list are two asynchronous
                    // reads of the same state: they must share one generation, or the later
                    // result is dropped as stale and the tab claims the file does not exist.
                    ReduceResult(
                        state.copy(
                            autounattendRequestGeneration = generation,
                            autounattendLoadInFlight = true,
                        ),
                        effects = listOf(
                            UiEffect.LoadAutounattend(
                                xmlName = intent.xmlName,
                                requestGeneration = generation,
                            ),
                            UiEffect.LoadAutounattendCandidates(
                                xmlName = intent.xmlName,
                                requestGeneration = generation,
                            ),
                        ),
                    )
                }

            is UiIntent.AutounattendLoaded ->
                if (intent.generation != state.autounattendRequestGeneration) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            autounattendState = intent.state,
                            autounattendBindings = intent.bindings,
                            autounattendLoadInFlight = false,
                        ),
                    )
                }

            is UiIntent.AutounattendCandidatesLoaded ->
                if (intent.generation != state.autounattendRequestGeneration) {
                    ReduceResult(state)
                } else {
                    val live =
                        intent.candidates.images
                            .asSequence()
                            .filter { !it.bound && it.problem == null }
                            .map { it.imagePath }
                            .toSet()
                    ReduceResult(
                        state.copy(
                            autounattendCandidates = intent.candidates,
                            windowsSelectedImages =
                                state.windowsSelectedImages.filter { it in live },
                        ),
                    )
                }

            is UiIntent.RequestGenerateAutounattend -> {
                val layout = intent.params.diskLayout
                val device = state.device
                val loadedTarget =
                    when (val loaded = state.autounattendState) {
                        is AutounattendState.Parsed -> loaded.document.sourcePath
                        is AutounattendState.Unreadable -> loaded.sourcePath
                        AutounattendState.Missing -> null
                    }
                val sameTarget = loadedTarget != null && sameXmlTarget(loadedTarget, intent.xmlName)
                val planSha256 =
                    autounattendPlanSha256(intent.xmlName, intent.params, intent.imagePaths)
                val planMatches = intent.planSha256 == null || intent.planSha256 == planSha256
                val layoutProblems =
                    if (layout != null && layout.writeLayout) targetLayoutProblems(layout) else emptyList()
                when {
                    device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !planMatches ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Generation plan changed; review and confirm it again",
                                ),
                            ),
                        )
                    layoutProblems.isNotEmpty() ->
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                                statusMessage = layoutProblemMessage(layoutProblems),
                            ),
                        )
                    state.autounattendState is AutounattendState.Unreadable && sameTarget ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Existing XML cannot be safely extended; choose another file",
                                ),
                            ),
                        )
                    state.autounattendState is AutounattendState.Parsed &&
                        sameTarget && !intent.overwriteXml ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Key(UiMessageKey.XmlOverwriteRequired),
                            ),
                        )
                    // F4: replacing an existing Parsed XML needs an explicit destructive confirm
                    // (source name on the dialog) before layout ack or generate.
                    state.autounattendState is AutounattendState.Parsed &&
                        sameTarget && intent.overwriteXml && device != null -> {
                        val pendingGen =
                            PendingAutounattendGeneration(
                                xmlName = intent.xmlName,
                                params = intent.params,
                                imagePaths = intent.imagePaths,
                                overwriteXml = true,
                                allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                identity = device.identity,
                                attachGeneration = device.attachGeneration,
                                expectedTemplateSha256 = intent.expectedTemplateSha256,
                                expectedPlanSha256 = planSha256,
                            )
                        ReduceResult(
                            state.copy(
                                pendingAutounattendGeneration = pendingGen,
                                pendingConfirmation =
                                    DestructiveConfirmation.request(
                                        device,
                                        DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                                        xmlTargetName = intent.xmlName,
                                    ),
                                statusMessage = UiMessage.Key(UiMessageKey.ConfirmXmlOverwrite),
                            ),
                        )
                    }
                    device != null && layout != null && layout.writeLayout ->
                        ReduceResult(
                            state.copy(
                                pendingAutounattendGeneration =
                                    PendingAutounattendGeneration(
                                        xmlName = intent.xmlName,
                                        params = intent.params,
                                        imagePaths = intent.imagePaths,
                                        overwriteXml = intent.overwriteXml,
                                        allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                        identity = device.identity,
                                        attachGeneration = device.attachGeneration,
                                        expectedTemplateSha256 = intent.expectedTemplateSha256,
                                        expectedPlanSha256 = planSha256,
                                    ),
                                diskLayoutConfirmation =
                                    layoutConfirmation(layout, planSha256),
                                statusMessage = UiMessage.Raw(
                                    "Confirm the target PC disk layout before generating XML",
                                ),
                            ),
                        )
                    else ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Raw("Generating unattend XML")),
                            effects = listOf(
                                UiEffect.GenerateAutounattend(
                                    xmlName = intent.xmlName,
                                    autounattendParams = intent.params,
                                    imagePaths = intent.imagePaths,
                                    overwriteXml = intent.overwriteXml,
                                    allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                    expectedTemplateSha256 = intent.expectedTemplateSha256,
                                    expectedPlanSha256 = planSha256,
                                ),
                            ),
                        )
                }
            }

            is UiIntent.ConfirmDiskLayout -> {
                val pending = state.pendingAutounattendGeneration
                when {
                    !intent.acknowledged ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "The target-PC disk warning must be acknowledged explicitly",
                                ),
                            ),
                        )
                    pending == null ->
                        ReduceResult(state.copy(diskLayoutConfirmation = null))
                    pending.identity != state.device?.identity ||
                        pending.attachGeneration != state.device?.attachGeneration ->
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                                statusMessage = UiMessage.Raw(
                                    "USB attachment changed; re-open the unattend generator",
                                ),
                            ),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else -> {
                        val layout = pending.params.diskLayout
                        val layoutProblems =
                            if (layout != null && layout.writeLayout) {
                                targetLayoutProblems(layout)
                            } else {
                                emptyList()
                            }
                        val plan =
                            autounattendPlanSha256(
                                pending.xmlName,
                                pending.params,
                                pending.imagePaths,
                            )
                        val snapshotMatches =
                            state.diskLayoutConfirmation?.planSha256 == plan &&
                                (pending.expectedPlanSha256 == null ||
                                    pending.expectedPlanSha256 == plan)
                        if (layoutProblems.isNotEmpty()) {
                            ReduceResult(
                                state.copy(
                                    diskLayoutConfirmation = null,
                                    pendingAutounattendGeneration = null,
                                    statusMessage = layoutProblemMessage(layoutProblems),
                                ),
                            )
                        } else if (!snapshotMatches) {
                            ReduceResult(
                                state.copy(
                                    diskLayoutConfirmation = null,
                                    pendingAutounattendGeneration = null,
                                    statusMessage = UiMessage.Raw(
                                        "Generation plan changed; review and confirm it again",
                                    ),
                                ),
                            )
                        } else {
                            ReduceResult(
                                state.copy(
                                    diskLayoutConfirmation = null,
                                    pendingAutounattendGeneration = null,
                                    statusMessage = UiMessage.Raw(
                                        "Generating unattend XML with disk layout",
                                    ),
                                ),
                                effects = listOf(
                                    UiEffect.GenerateAutounattend(
                                        xmlName = pending.xmlName,
                                        autounattendParams = pending.params,
                                        imagePaths = pending.imagePaths,
                                        overwriteXml = pending.overwriteXml,
                                        allowDestructiveTemplate = pending.allowDestructiveTemplate,
                                        expectedTemplateSha256 = pending.expectedTemplateSha256,
                                        expectedPlanSha256 = pending.expectedPlanSha256,
                                    ),
                                ),
                            )
                        }
                    }
                }
            }

            UiIntent.DismissDiskLayout ->
                ReduceResult(
                    state.copy(
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                )

            UiIntent.LoadVentoyGlobalSettings -> {
                val token = state.ventoyGlobalSettingsRequest + 1L
                ReduceResult(
                    state.copy(ventoyGlobalSettingsRequest = token),
                    effects = listOf(UiEffect.LoadVentoyGlobalSettings(requestToken = token)),
                )
            }

            UiIntent.LoadVentoyFolders ->
                // Refresh keeps the current rows until the new answer lands; the token still
                // disowns every older read. A busy/confirming UI marks the request as pending
                // so the release pass can start it once USB work is allowed; it must not
                // create an endless retry by itself.
                if (!state.actionsEnabled) {
                    ReduceResult(
                        state.copy(
                            ventoyFoldersPending = true,
                            statusMessage =
                                if (state.presence == DevicePresence.Ready) {
                                    UiMessage.Key(UiMessageKey.WaitForOperation)
                                } else {
                                    presenceMessage(state.presence)
                                },
                        ),
                    )
                } else {
                    startFolderRead(state, state.ventoyFolderPath, keepList = true)
                }

            is UiIntent.OpenVentoyFolder -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state)
                } else {
                    val target =
                        try {
                            normalizeFolderPath(intent.path)
                        } catch (_: IllegalArgumentException) {
                            null
                        }
                    when {
                        target == null || target !in state.ventoyFolders.orEmpty() ->
                            // The row came from a list that no longer matches the state.
                            ReduceResult(
                                state.copy(
                                    statusMessage = UiMessage.Key(UiMessageKey.FolderListChanged),
                                ),
                            )
                        else -> startFolderRead(state, target, keepList = false)
                    }
                }
            }

            UiIntent.VentoyFolderUp ->
                if (!state.actionsEnabled) {
                    ReduceResult(state)
                } else {
                    when (val parent = folderParent(state.ventoyFolderPath)) {
                        null -> ReduceResult(state)
                        else -> startFolderRead(state, parent, keepList = false)
                    }
                }

            is UiIntent.VentoyFoldersLoaded ->
                if (intent.epoch != state.ventoyCacheEpoch ||
                    intent.requestToken != state.ventoyFoldersRequest ||
                    intent.path != state.ventoyFolderPath
                ) {
                    // Invalidation, navigation or a newer refresh already owns the answer.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            ventoyFolders = intent.folders,
                            ventoyFoldersError = intent.error,
                            ventoyFoldersLoading = false,
                            ventoyFoldersPending = false,
                        ),
                    )
                }

            is UiIntent.VentoyGlobalSettingsLoaded ->
                if (intent.epoch != state.ventoyCacheEpoch ||
                    intent.requestToken != state.ventoyGlobalSettingsRequest
                ) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            ventoyGlobalSettings = intent.settings,
                            // Only fires while Ventoy tab open (or manual refresh); surface
                            // a real read/parse failure without turning it into "empty".
                            statusMessage =
                                when (val loaded = intent.settings) {
                                    is VentoyGlobalSettings.ReadError ->
                                        if (loaded.damagedLayout) {
                                            // The card has a localized explanation; do not leak
                                            // an English technical string into the status bar.
                                            state.statusMessage
                                        } else {
                                            UiMessage.Raw(loaded.message)
                                        }
                                    else -> state.statusMessage
                                },
                        ),
                    )
                }

            is UiIntent.StageVentoyControl -> {
                val problem =
                    if (intent.value == null) {
                        // Removal matches an existing key verbatim; no creation pattern.
                        VentoyControlCatalog.problem(
                            listOf(VentoyControlSetting(intent.key, null)),
                        )
                    } else {
                        VentoyControlCatalog.keyProblem(intent.key)
                            ?: VentoyControlCatalog.valueProblem(intent.key, intent.value)
                    }
                if (problem != null) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Raw(problem)))
                } else {
                    val staged = LinkedHashMap<String, VentoyControlSetting>()
                    state.pendingVentoyControls.forEach { staged[it.key] = it }
                    staged[intent.key] = VentoyControlSetting(intent.key, intent.value)
                    ReduceResult(
                        state.copy(
                            pendingVentoyControls = staged.values.toList(),
                            statusMessage = null,
                        ),
                    )
                }
            }

            is UiIntent.DiscardStagedVentoyControl ->
                ReduceResult(
                    state.copy(
                        pendingVentoyControls =
                            state.pendingVentoyControls.filterNot { it.key == intent.key },
                    ),
                )

            UiIntent.RequestApplyVentoyControls ->
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    state.pendingVentoyControls.isEmpty() ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw("No Ventoy setting changes to apply"),
                            ),
                        )
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.ApplyVentoyControls(
                                        settings = state.pendingVentoyControls,
                                    ),
                                ),
                        )
                }

            is UiIntent.RequestBindAutounattend ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state,
                        effects = listOf(
                            UiEffect.BindAutounattend(
                                xmlName = intent.xmlName,
                                imagePaths = intent.imagePaths,
                                allowDestructiveTemplate = intent.allowDestructiveTemplate,
                                expectedTemplateSha256 = intent.expectedTemplateSha256,
                            ),
                        ),
                    )
                }

            is UiIntent.RequestUnbindAutounattend ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state,
                        effects = listOf(
                            UiEffect.UnbindAutounattend(
                                xmlName = intent.xmlName,
                                imagePaths = listOf(intent.imagePath),
                            ),
                        ),
                    )
                }

            is UiIntent.AutounattendBindingsLoaded ->
                ReduceResult(
                    state.copy(
                        autounattendBindings = intent.bindings,
                        statusMessage =
                            intent.error?.let(UiMessage::Raw) ?: state.statusMessage,
                    ),
                )

            is UiIntent.SetBackupFolder ->
                ReduceResult(
                    state.copy(
                        backupFolderUri = intent.uri,
                        backupFolderName = intent.name,
                        backupFolderUsable = intent.uri != null && intent.usable,
                        statusMessage =
                            when {
                                intent.uri == null -> UiMessage.Key(UiMessageKey.ChooseBackupFolder)
                                intent.usable -> message(
                                    UiMessageKey.BackupFolderReady,
                                    intent.name ?: intent.uri.orEmpty(),
                                )
                                else -> UiMessage.Key(UiMessageKey.BackupFolderPermissionLost)
                            },
                    ),
                )

            is UiIntent.SetBackupFile -> {
                if (intent.requestId < state.backupSelectionGeneration) {
                    ReduceResult(state)
                } else {
                    val clearConfirm =
                        state.pendingConfirmation?.action == DestructiveAction.GPT_RESTORE
                    val inspection = intent.inspection?.takeIf { it.uri == intent.uri }
                    val inspectionError = inspection?.error
                    ReduceResult(
                        state.copy(
                            backupUri = intent.uri,
                            backupInspection = inspection,
                            backupSelectionGeneration = intent.requestId,
                            pendingConfirmation =
                                if (clearConfirm) null else state.pendingConfirmation,
                            statusMessage =
                                when {
                                    intent.uri == null -> UiMessage.Key(UiMessageKey.BackupCleared)
                                    inspection != null && inspectionError != null -> message(
                                        UiMessageKey.SourceInspectionRejected,
                                        inspection.displayName,
                                        inspectionError,
                                    )
                                    else -> message(
                                        UiMessageKey.BackupReady,
                                        DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                                    )
                                },
                        ),
                    )
                }
            }

            UiIntent.StartReadScan -> nonDestructiveEffect(state, UiEffect.ReadScan)

            UiIntent.StartRescueBackup -> nonDestructiveEffect(state, UiEffect.RescueBackup)

            UiIntent.CaptureGptBackup -> nonDestructiveEffect(state, UiEffect.CaptureGptBackup)

            is UiIntent.SetLanguage -> ReduceResult(state.copy(language = intent.language))

            UiIntent.ToggleTheme ->
                ReduceResult(state.copy(darkTheme = !state.darkTheme))

            UiIntent.RunSelfTest ->
                if (state.isRunning) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.OperationInProgress)))
                } else {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelfTestStarting)),
                        effects = listOf(UiEffect.RunSelfTest),
                    )
                }

            is UiIntent.SetRescueImage -> {
                if (intent.requestId < state.rescueImageSelectionGeneration) {
                    ReduceResult(state)
                } else {
                    val clearConfirm =
                        state.pendingConfirmation?.action == DestructiveAction.RESCUE_RESTORE
                    ReduceResult(
                        state.copy(
                            rescueImageUri = intent.uri,
                            rescueImageInspection =
                                intent.inspection?.takeIf { it.uri == intent.uri },
                            rescueImageSelectionGeneration = intent.requestId,
                            pendingConfirmation =
                                if (clearConfirm) null else state.pendingConfirmation,
                            statusMessage =
                                if (intent.uri == null) {
                                    UiMessage.Key(UiMessageKey.RescueImageCleared)
                                } else {
                                    message(
                                        UiMessageKey.RescueImageReady,
                                        DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                                    )
                                },
                        ),
                    )
                }
            }

            is UiIntent.SetRescueMap -> {
                if (intent.requestId < state.rescueMapSelectionGeneration) {
                    ReduceResult(state)
                } else {
                    val clearConfirm =
                        state.pendingConfirmation?.action == DestructiveAction.RESCUE_RESTORE
                    val inspection = intent.inspection?.takeIf { it.uri == intent.uri }
                    val inspectionError = inspection?.error
                    ReduceResult(
                        state.copy(
                            rescueMapUri = intent.uri,
                            rescueMapInspection = inspection,
                            rescueMapSelectionGeneration = intent.requestId,
                            pendingConfirmation =
                                if (clearConfirm) null else state.pendingConfirmation,
                            statusMessage =
                                when {
                                    intent.uri == null -> UiMessage.Key(UiMessageKey.RescueMapCleared)
                                    inspection != null && inspectionError != null -> message(
                                        UiMessageKey.SourceInspectionRejected,
                                        inspection.displayName,
                                        inspectionError,
                                    )
                                    else -> message(
                                        UiMessageKey.RescueMapReady,
                                        DestructiveConfirmation.fileLabel(intent.uri) ?: intent.uri,
                                    )
                                },
                        ),
                    )
                }
            }

            is UiIntent.Status -> ReduceResult(state.copy(statusMessage = intent.message))

            UiIntent.CancelOperation ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect.CancelOperation),
                )

            is UiIntent.OpenDirectory -> {
                val target = safePickPath(intent.path)
                val mode = state.filesPickMode
                when {
                    target == null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                        )
                    mode != null && !mode.matches(state) ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                        )
                    else ->
                        ReduceResult(
                            state.copy(
                                currentPath = target,
                                files = emptyList(),
                                filesError = null,
                                filesListGeneration = state.filesListGeneration + 1L,
                                filesPending = true,
                            ),
                            effects = listOf(UiEffect.LoadFiles(path = target)),
                        )
                }
            }

            UiIntent.NavigateUp -> {
                val mode = state.filesPickMode
                if (mode != null && !mode.matches(state)) {
                    abortFilesPickPinLost(state)
                } else {
                    val target = fatParentPath(state.currentPath)
                    if (target == state.currentPath) {
                        ReduceResult(state)
                    } else {
                        ReduceResult(
                            state.copy(
                                currentPath = target,
                                files = emptyList(),
                                filesError = null,
                                filesListGeneration = state.filesListGeneration + 1L,
                                filesPending = true,
                            ),
                            effects = listOf(UiEffect.LoadFiles(path = target)),
                        )
                    }
                }
            }

            UiIntent.RefreshFiles -> {
                val mode = state.filesPickMode
                if (mode != null && !mode.matches(state)) {
                    abortFilesPickPinLost(state)
                } else if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    // A manual refresh preempts an in-flight list: its older answer is
                    // dropped by the generation bump.
                    filesReload(state)
                }
            }

            is UiIntent.CreateFolder -> {
                val name = intent.name.trim()
                val block = writeBlockMessage(state)
                when {
                    state.filesPickMode != null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(name) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidFolderName)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.CreateFolder(
                                        path = state.currentPath,
                                        name = name,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.DeleteEntry -> {
                val block = writeBlockMessage(state)
                when {
                    state.filesPickMode != null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(intent.name) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectEntryToDelete)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.DeleteEntry(
                                        path = state.currentPath,
                                        name = intent.name,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.RenameEntry -> {
                val block = writeBlockMessage(state)
                when {
                    state.filesPickMode != null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    bindingProblem(state, intent.binding) != null ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.TargetChanged)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    !validEntryName(intent.from) || !validEntryName(intent.to) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.InvalidEntryName)))
                    intent.from == intent.to ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.RenameSameName)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.RenameEntry(
                                        path = state.currentPath,
                                        name = intent.from,
                                        newName = intent.to,
                                        binding = intent.binding,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.UploadFile -> {
                val block = writeBlockMessage(state)
                when {
                    state.filesPickMode != null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.UploadFile(
                                        path = state.currentPath,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.DownloadFile ->
                when {
                    state.filesPickMode != null ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !validEntryName(intent.name) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectFileToDownload)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect.DownloadFile(
                                        path = state.currentPath,
                                        name = intent.name,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }

            is UiIntent.FilesListed ->
                if (!filesListMatches(
                        state,
                        intent.path,
                        intent.generation,
                        intent.identity,
                        intent.partitionId,
                        intent.attachGeneration,
                    )
                ) {
                    // Late result for another path, partition, attachment or request.
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            files = intent.files,
                            filesError = null,
                            filesPending = false,
                        ),
                    )
                }

            is UiIntent.FilesListFailed ->
                if (!filesListMatches(
                        state,
                        intent.path,
                        intent.generation,
                        intent.identity,
                        intent.partitionId,
                        intent.attachGeneration,
                    )
                ) {
                    ReduceResult(state)
                } else if (
                    intent.path != "/" &&
                    intent.path.isNotEmpty() &&
                    isMissingDirectoryListError(intent.message)
                ) {
                    // Only a proven absent directory falls back to root. Unknown I/O
                    // stays visible so a disk error is never painted as "empty folder".
                    val generation = state.filesListGeneration + 1L
                    ReduceResult(
                        state.copy(
                            currentPath = "/",
                            files = emptyList(),
                            filesError = null,
                            filesListGeneration = generation,
                            filesPending = true,
                            statusMessage = UiMessage.Raw(intent.message),
                        ),
                        effects = listOf(UiEffect.LoadFiles(path = "/")),
                    )
                } else {
                    ReduceResult(
                        state.copy(
                            files = emptyList(),
                            filesError = intent.message,
                            filesPending = false,
                            statusMessage = UiMessage.Raw(intent.message),
                        ),
                    )
                }

            is UiIntent.AppendLog -> {
                val trimmed = intent.line.take(500) // guard runaway lines
                val updated =
                    if (state.logLines.size >= AppUiState.LOG_MAX_LINES) {
                        state.logLines.drop(state.logLines.size - AppUiState.LOG_MAX_LINES + 1) + trimmed
                    } else {
                        state.logLines + trimmed
                    }
                ReduceResult(state.copy(logLines = updated))
            }

            // No confirmation, no operation gate: the user asked for an empty log now.
            // Appends keep working afterwards, and nothing outside logLines is touched.
            UiIntent.ClearLog -> ReduceResult(state.copy(logLines = emptyList()))

            is UiIntent.SetLatestVentoyRelease ->
                ReduceResult(state.copy(latestVentoyRelease = intent.tag))

            is UiIntent.RequestFilesystemUsage -> {
                val generation = state.filesystemUsageGeneration + 1L
                val requested = state.copy(filesystemUsageGeneration = generation)
                ReduceResult(requested, effects = filesystemUsageEffects(requested, generation))
            }

            is UiIntent.FilesystemUsageResulted ->
                when {
                    // A newer request is in flight; this answer describes an older selection.
                    intent.generation != state.filesystemUsageGeneration -> ReduceResult(state)
                    // Attachment, geometry or filesystem changed after the request: the value
                    // belongs to something else, so it is dropped rather than published.
                    state.selectedPartition()?.let { state.filesystemUsageKey(it) } != intent.key ->
                        ReduceResult(state)
                    else ->
                        ReduceResult(
                            state.copy(
                                filesystemUsage = FilesystemUsageSnapshot(intent.key, intent.usage),
                            ),
                        )
                }
        }.let { result -> releaseDeferredVentoyFolderRead(result) }
            .let { result -> releaseDeferredImportFolderPick(result) }
            .let { result -> releaseDeferredIsoImport(result) }

    /**
     * Starts a folder read requested while USB work was not allowed. Runs after every
     * intent, so the Busy/Permission/confirmation -> Ready edge itself releases it even
     * though the screen's LaunchedEffect may not restart. One-shot: the pending flag is
     * cleared before the effect, and a failed read settles into an error, so an unusable
     * folder can never turn into a retry loop.
     */
    private fun releaseDeferredVentoyFolderRead(result: ReduceResult): ReduceResult {
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
            started.copy(state = started.state.copy(statusMessage = null))
        } else {
            started
        }
    }

    /**
     * Start one directory read. The token is the single ordering key: both navigation and
     * refresh bump it, so only the newest answer may publish rows for [path].
     */
    private fun startFolderRead(
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
    private fun releaseDeferredImportFolderPick(result: ReduceResult): ReduceResult {
        val next = result.state
        if (!next.pendingImportFolderPickLoad || !next.actionsEnabled) return result
        // The USB phase holds the disk; the SAF phase runs off the worker and cannot clash.
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
    private fun releaseDeferredIsoImport(result: ReduceResult): ReduceResult {
        val state = result.state
        val proposal = state.deferredIsoImport ?: return result
        if (!state.actionsEnabled || state.isoImportUsbInFlight != null) return result
        // Never start the import while the Files listing still holds the mounted volume.
        if (state.filesPending) return result
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
                statusMessage =
                    UiMessage.Raw(
                        "Importing ${proposal.targetName} into ${proposal.directory}",
                    ),
            ),
            effects = listOf(
                UiEffect.ImportIso(
                    isoImport = proposal,
                    overwrite = false,
                    requestGeneration = proposal.requestGeneration,
                ),
            ),
        )
    }

    /** Ring-append one log line without clobbering status or bound fields. */
    private fun appendLogLine(state: AppUiState, line: String): AppUiState {
        val trimmed = line.take(500)
        val updated =
            if (state.logLines.size >= AppUiState.LOG_MAX_LINES) {
                state.logLines.drop(state.logLines.size - AppUiState.LOG_MAX_LINES + 1) + trimmed
            } else {
                state.logLines + trimmed
            }
        return state.copy(logLines = updated)
    }

    /**
     * One measurement request for the live selection, or nothing when it cannot be bound:
     * a running/queued operation would make the free-space number meaningless, and an
     * incomplete key (no device, geometry or filesystem) cannot be validated later.
     */
    private fun filesystemUsageEffects(state: AppUiState, generation: Long): List<UiEffect> {
        if (!state.actionsEnabled) return emptyList()
        val partition = state.selectedPartition() ?: return emptyList()
        val key = state.filesystemUsageKey(partition) ?: return emptyList()
        return listOf(
            UiEffect.LoadFilesystemUsage(
                partitionId = partition.id,
                usageKey = key,
                usageGeneration = generation,
            ),
        )
    }

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

    /** Read-only disk work still needs a ready device; otherwise report why nothing ran. */
    private fun nonDestructiveEffect(state: AppUiState, effect: UiEffect): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        } else {
            ReduceResult(state, effects = listOf(effect))
        }

    /**
     * Whole-disk default when no geometry snapshot is available yet (e.g. an unreadable
     * table): sector count from the confirmed capacity, never from a screen estimate.
     */
    private fun defaultWholeDiskSectors(state: AppUiState): ULong? {
        val device = state.device ?: return null
        if (state.diskSectorSize <= 0) return null
        val sectors = device.capacityBytes / state.diskSectorSize.toULong()
        return sectors.takeIf { it > 0UL }
    }

    /**
     * Prefer staged label, then SAF display name, then last path segment of the URI/path.
     * Never invent a name when none is known — confirm is refused instead.
     */
    private fun rawImageSourceName(state: AppUiState): String? =
        state.stagedRawImageLabel?.takeIf { it.isNotBlank() }
            ?: state.imageName?.takeIf { it.isNotBlank() }
            ?: DestructiveConfirmation.fileLabel(state.stagedRawImagePath)
            ?: DestructiveConfirmation.fileLabel(state.imageUri)

    private fun OperationTitle.clearsPendingVentoyControls(): Boolean =
        when (this) {
            is OperationTitle.Destructive ->
                action == DestructiveAction.VENTOY_INSTALL ||
                    action == DestructiveAction.VENTOY_UPDATE ||
                    action == DestructiveAction.VENTOY_DOWNLOAD_INSTALL
            OperationTitle.SetVentoyGlobalSetting -> true
            else -> false
        }

    /**
     * Titles that rewrite the data partition or partition table enough that retained
     * Ventoy/XML catalogs and the open XML document must not stay on screen.
     * Read-only probe/list titles never match.
     */
    private fun OperationTitle.invalidatesVentoyStickCaches(): Boolean =
        when (this) {
            is OperationTitle.Destructive ->
                when (action) {
                    DestructiveAction.WIPE,
                    DestructiveAction.VENTOY_INSTALL,
                    DestructiveAction.VENTOY_UPDATE,
                    DestructiveAction.VENTOY_DOWNLOAD_INSTALL,
                    DestructiveAction.RAW_WRITE,
                    DestructiveAction.FORMAT_PARTITION,
                    DestructiveAction.DELETE_PARTITION,
                    DestructiveAction.CREATE_PARTITION,
                    DestructiveAction.GPT_REPAIR,
                    DestructiveAction.GPT_RESTORE,
                    DestructiveAction.RESCUE_RESTORE,
                    DestructiveAction.INITIALIZE_PARTITION_TABLE,
                    DestructiveAction.SET_PARTITION_ROLE,
                    DestructiveAction.SET_PARTITION_BOOTABLE,
                    DestructiveAction.RENAME_PARTITION,
                    DestructiveAction.RENAME_VOLUME_LABEL,
                    DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                    DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT,
                    DestructiveAction.VENTOY_LINK_XML,
                    -> true
                }
            is OperationTitle.ImportTarget,
            is OperationTitle.FormatTarget,
            is OperationTitle.DeleteEntry,
            is OperationTitle.RenameEntry,
            is OperationTitle.NewFolder,
            OperationTitle.Upload,
            OperationTitle.BindImagesToUnattendXml,
            OperationTitle.UnbindImageFromUnattendXml,
            OperationTitle.GenerateUnattendXml,
            OperationTitle.RemoveXmlBindings,
            OperationTitle.SetVentoyGlobalSetting,
            OperationTitle.SaveGlobalWindowsControls,
            -> true
            else -> false
        }

    /**
     * Clear stick-scoped Ventoy/XML catalogs and bump generations/epoch so in-flight
     * loads cannot land on a post-mutation state. [keepTemplatePath] leaves the chosen
     * XML path so the Ventoy tab can re-read without another pick; document/candidates
     * still go to Missing/null. Does not emit mount effects.
     */
    private fun dropVentoyStickCaches(
        state: AppUiState,
        keepTemplatePath: Boolean,
    ): AppUiState =
        state.copy(
            windowsIsoCandidates = null,
            windowsIsoCandidatesGeneration = state.windowsIsoCandidatesGeneration + 1,
            windowsIsoCandidatesIdentity = null,
            windowsIsoCandidatesError = null,
            windowsImageLinks = null,
            windowsImageLinksGeneration = state.windowsImageLinksGeneration + 1,
            windowsImageLinksIdentity = null,
            windowsSelectedImages = emptyList(),
            windowsTemplatePath = if (keepTemplatePath) state.windowsTemplatePath else null,
            autounattendState = AutounattendState.Missing,
            autounattendBindings = null,
            autounattendCandidates = null,
            autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
            autounattendLoadInFlight = false,
            diskLayoutConfirmation = null,
            pendingAutounattendGeneration = null,
            ventoyGlobalSettings = null,
            ventoyFolderPath = "/",
            ventoyFolders = null,
            ventoyFoldersError = null,
            ventoyFoldersLoading = false,
            ventoyFoldersPending = false,
            ventoyCacheEpoch = state.ventoyCacheEpoch + 1,
            pendingWindowsUnlink = null,
            linkOverwriteCandidatePaths = emptyList(),
            linkOverwriteCandidateTemplatePath = null,
            // Phone-import inspection is stick-scoped; a mutation must not keep an overwrite
            // prompt bound to a pre-mutation generation.
            pendingIsoImport = null,
            deferredIsoImport = null,
            isoImportError = null,
            isoImportGeneration = state.isoImportGeneration + 1,
            // The import-folder demand is NOT dropped here: on the same attachment the user
            // must still be asked to choose again after an unrelated mutation (R6).
        )
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

    /** MBR addresses at most 2^32-1 sectors of [sectorSize] bytes. */
    private fun mbrAddressLimitBytes(sectorSize: Int): ULong =
        0xFFFF_FFFFUL * sectorSize.coerceAtLeast(1).toULong()

    private fun UiMessage.isDefaultStateStatus(): Boolean =
        this is UiMessage.Key &&
            key in setOf(
                UiMessageKey.RunningStatus,
                UiMessageKey.QueuedStatus,
            )

    /** Null when [binding] still names the selected device, partition and geometry. */
    private fun bindingProblem(state: AppUiState, binding: PartitionBinding): UiMessage? =
        if (state.device == null) {
            presenceMessage(state.presence)
        } else if (!binding.matches(state)) {
            UiMessage.Key(UiMessageKey.TargetChanged)
        } else {
            null
        }

    /**
     * Start one fresh directory-list request for [AppUiState.currentPath], or only mark the
     * listing stale when the Files tab is not the visible one or nothing may be mounted.
     * The generation is bumped here, at request time, so a late answer to an older request
     * (manual refresh, tab entry, finished mutation) can never paint over this one.
     */
    private fun filesReload(state: AppUiState): ReduceResult {
        val loadNow =
            state.presence == DevicePresence.Ready &&
                state.actionsEnabled &&
                state.tab == ShellTab.Files &&
                state.selectedPartition() != null
        return ReduceResult(
            state.copy(
                files = emptyList(),
                filesError = null,
                filesListGeneration = state.filesListGeneration + 1L,
                filesPending = loadNow,
            ),
            effects = if (loadNow) listOf(UiEffect.LoadFiles(path = state.currentPath)) else emptyList(),
        )
    }

    /**
     * True only when a directory-list answer still names the displayed path, the
     * selected partition, the live attachment and the newest request id. A quiet list
     * finished after the user switched partition or the stick reattached must not paint
     * foreign entries or clear a fresher error.
     */
    private fun filesListMatches(
        state: AppUiState,
        path: String,
        generation: Long,
        identity: String?,
        partitionId: String,
        attachGeneration: Long,
    ): Boolean {
        val device = state.device ?: return false
        return path == state.currentPath &&
            generation == state.filesListGeneration &&
            identity == device.identity &&
            attachGeneration == device.attachGeneration &&
            partitionId == state.selectedPartitionId
    }


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
    internal fun isMissingDirectoryListError(message: String): Boolean {
        val m = message
        // FatFs R0.16 via fat_jni.cpp throw_fat("list failed: " + rc)
        if (m == "list failed: $FR_NO_FILE" || m == "list failed: $FR_NO_PATH") return true
        // EXT / NTFS list ENOENT (ext_jni / ntfs_jni throw "Path not found")
        if (m == "Path not found" || m.startsWith("Path not found:")) return true
        if (m.contains("No such file")) return true
        if (m.contains("ENOENT")) return true
        return false
    }




    /**
     * Build a confirmation for a partition-table edit. The payload pins the selected
     * partition ID and geometry so the host can re-check them against the live table.
     */
    private fun requestPartitionEdit(
        state: AppUiState,
        action: DestructiveAction,
        role: PartitionRole? = null,
        bootable: Boolean? = null,
    ): ReduceResult {
        val device = state.device
        val target = state.selectedPartition()
        val problem = when {
            device == null || !state.actionsEnabled -> presenceMessage(state.presence)
            target == null -> UiMessage.Key(UiMessageKey.SelectPartitionFirst)
            state.partitionTableAmbiguous -> UiMessage.Key(UiMessageKey.PartitionTableAmbiguous)
            state.partitionTableDamaged -> UiMessage.Key(UiMessageKey.GptPrimaryDamaged)
            !state.tableValid -> UiMessage.Key(UiMessageKey.TableDamaged)
            target.startLba == null || target.sectorCount == null ->
                UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)
            !state.isEfiWriteAllowed(target) ->
                message(UiMessageKey.ReadOnlyPartition, target.name)
            else -> null
        }
        if (problem != null || device == null || target == null) {
            return ReduceResult(state.copy(statusMessage = problem))
        }
        val base = DestructiveConfirmation.request(device, action, target.name, target = target)
        return ReduceResult(
            state.copy(
                pendingConfirmation = base.copy(
                    partitionRole = role,
                    partitionBootable = bootable,
                ),
            ),
        )
    }

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

    /** A single FAT entry name: no separators, no dot entries. */
    private fun validEntryName(name: String): Boolean =
        name.isNotBlank() &&
            name != "." &&
            name != ".." &&
            '/' !in name &&
            '\\' !in name

    /**
     * Bare names resolve under /ventoy; a full path is itself. Display-only path join —
     * rewrite gate uses [sameXmlTarget] / [XmlPath.resolve].
     */
    private fun importTarget(directory: String, name: String): String =
        if (directory == "/") "/$name" else "$directory/$name"

    /**
     * Editor policy: a target layout must carry the declared disk size, so the sum can be
     * checked before any confirmation. The XML writer keeps the weaker default because the
     * declared size is not part of the Windows layout.
     */
    private fun targetLayoutProblems(layout: DiskLayout): List<AutounattendProblem> =
        Autounattend.validate(
            AutounattendParams(diskLayout = layout),
            requireDeclaredDiskSize = true,
        ).problems

    private fun layoutProblemMessage(
        problems: List<AutounattendProblem>,
    ): UiMessage.InvalidTargetLayout =
        UiMessage.InvalidTargetLayout(problems.distinct())

    /** Immutable snapshot of exactly what the acknowledgement dialog shows. */
    private fun layoutConfirmation(
        layout: DiskLayout,
        planSha256: String,
    ): DiskLayoutConfirmation =
        DiskLayoutConfirmation(
            diskId = layout.diskId,
            partitionCount = layout.partitions.size,
            writeLayout = layout.writeLayout,
            planSha256 = planSha256,
            partitions = layout.partitions.toList(),
            declaredSizeMb = layout.declaredSizeMb,
            totalMb = diskLayoutTotalMb(layout.partitions),
            remainingMb = diskLayoutRemainingMb(layout),
            installTargetPartitionId = diskLayoutInstallTargetPartitionId(layout.partitions),
            wipe = layout.wipe,
        )

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
    private fun gptRestoreInspectionProblem(state: AppUiState): UiMessage? {
        val uri = state.backupUri ?: return null
        val inspection = state.backupInspection?.takeIf { it.uri == uri } ?: return null
        inspection.error?.let {
            return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(inspection.displayName, it),
            )
        }
        val summary = inspection.summary
            ?: return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(inspection.displayName, "backup details unavailable"),
            )
        if (summary.sectorSize != state.diskSectorSize) {
            return UiMessage.Key(
                UiMessageKey.GptBackupSectorSizeMismatch,
                listOf(summary.sectorSize.toString(), state.diskSectorSize.toString()),
            )
        }
        val liveSectors = liveSectorCount(state) ?: return null
        if (summary.originalSectorCount != liveSectors) {
            return UiMessage.Key(
                UiMessageKey.GptBackupDiskMismatch,
                listOf(summary.originalSectorCount.toString(), liveSectors.toString()),
            )
        }
        return null
    }

    /**
     * Refuse the destructive rescue confirmation when a bounded inspection failed or the
     * map cannot apply to the live disk. A raw ISO/IMG is a stream prefix: a known size
     * shorter than map sectors × sector size is blocked, extra trailing bytes are allowed.
     * Compressed sources never compare their stored (encoded) size. Overflow of
     * sectors × sector size is refused. This proves geometry agreement, never physical
     * media integrity.
     */
    private fun rescueRestoreInspectionProblem(state: AppUiState): UiMessage? {
        val imageUri = state.rescueImageUri ?: return null
        val mapUri = state.rescueMapUri ?: return null
        val image = state.rescueImageInspection?.takeIf { it.uri == imageUri }
        val map = state.rescueMapInspection?.takeIf { it.uri == mapUri }
        if (image == null && map == null) return null
        map?.error?.let {
            return UiMessage.Key(
                UiMessageKey.SourceInspectionRejected,
                listOf(map.displayName, it),
            )
        }
        val summary = map?.summary
            ?: return map?.let {
                UiMessage.Key(
                    UiMessageKey.SourceInspectionRejected,
                    listOf(it.displayName, "map details unavailable"),
                )
            }
        if (summary.kind == usb.diagnostics.MapKind.COPY && summary.nonTriedSectors > 0UL) {
            return UiMessage.Key(
                UiMessageKey.RescueMapPartialCopy,
                listOf(summary.nonTriedSectors.toString()),
            )
        }
        summary.sectorSize?.let { mapSectorSize ->
            if (mapSectorSize != state.diskSectorSize) {
                return UiMessage.Key(
                    UiMessageKey.RescueMapSectorSizeMismatch,
                    listOf(mapSectorSize.toString(), state.diskSectorSize.toString()),
                )
            }
        }
        liveSectorCount(state)?.let { live ->
            if (summary.sectorCount > live) {
                return UiMessage.Key(
                    UiMessageKey.RescueMapDiskTooSmall,
                    listOf(summary.sectorCount.toString(), live.toString()),
                )
            }
        }
        // Magic decides raw vs compressed before SIZE is used. Compressed sources are
        // decoded by the stream; a raw image may be longer than the map requirement
        // (restore reads only mapped sectors), but must never be shorter.
        val sectorSize = summary.sectorSize ?: state.diskSectorSize.takeIf { it > 0 }
        if (sectorSize != null) {
            val actual = image?.sizeBytes?.takeIf { it >= 0L }
            when (rescueImageLengthIssue(actual, summary.sectorCount, sectorSize, image?.format)) {
                RescueImageLengthIssue.SHORT ->
                    return UiMessage.Key(
                        UiMessageKey.RescueMapImageTooShort,
                        listOf(
                            actual.toString(),
                            usb.diagnostics.BackupArchive.expectedImageBytes(
                                summary.sectorCount,
                                sectorSize,
                            ).toString(),
                        ),
                    )
                RescueImageLengthIssue.OVERFLOW ->
                    return UiMessage.Key(UiMessageKey.RescueMapImageGeometryOverflow)
                null -> Unit
            }
        }
        // A COPY map's identity is the source disk. Restoring onto a replacement disk is
        // the primary disaster-recovery path and RescueRestorePrep pins the destination,
        // discards inherited progress when identity differs, and rewrites all sectors.
        // The confirmation shows the source identity difference instead of blocking.
        return null
    }

    /**
     * UUID rather than a process counter: a SAF result can survive process death, while a
     * new process would restart a counter and accidentally reuse its token.
     *
     * Deliberately the only non-deterministic value in a pick state transition: tests must
     * assert token equality/inequality or pinned fields, never a concrete UUID. Everything
     * else (path, identity, geometry, selection) stays reproducible.
     */
    private fun newFilesPickSession(): String = java.util.UUID.randomUUID().toString()

    /**
     * A picker may not disappear under a running/queued operation or a destructive
     * confirmation: the copy progress and its outcome must stay visible.
     */
    private fun filesPickExitBlock(state: AppUiState): UiMessage? =
        when {
            state.operation?.state == OperationState.Running ||
                state.operation?.state == OperationState.Queued ->
                UiMessage.Key(UiMessageKey.WaitForOperation)
            state.pendingConfirmation != null -> UiMessage.Key(UiMessageKey.WaitForOperation)
            else -> null
        }

    /**
     * Paths are absolute on the pinned partition. `.`/`..` and NUL are refused so a picker
     * cannot climb out of the listed folder even if an intent is forged.
     */
    private fun safePickPath(path: String): String? {
        if ('\u0000' in path) return null
        val normalized = normalizeFatPath(path)
        val segments = normalized.split('/').filter { it.isNotEmpty() }
        if (segments.any { it == "." || it == ".." }) return null
        return normalized
    }

    private fun beginFilesPick(state: AppUiState, intent: UiIntent.BeginFilesPick): ReduceResult {
        if (state.filesPickMode != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
            )
        }
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        }
        if (state.pendingIsoImport != null ||
            state.isoImportSafInFlight != null ||
            state.isoImportUsbInFlight != null
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.WaitForOperation)),
            )
        }
        val device =
            state.device
                ?: return ReduceResult(
                    state.copy(statusMessage = presenceMessage(state.presence)),
                )
        val explicit = intent.partitionId
        val partition =
            when {
                explicit != null -> state.partitions.firstOrNull { it.id == explicit }
                intent.purpose == FilesPickPurpose.RawSource ->
                    state.selectedPartitionId?.let { id ->
                        state.partitions.firstOrNull { it.id == id }
                    }
                else -> state.ventoyDataPartition()
            }
        val path = safePickPath(intent.initialPath)
        return when {
            partition == null ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            UiMessage.Key(
                                if (intent.purpose == FilesPickPurpose.RawSource) {
                                    UiMessageKey.SelectPartitionFirst
                                } else {
                                    UiMessageKey.VentoyDataPartitionMissing
                                },
                            ),
                    ),
                )
            partition.startLba == null || partition.sectorCount == null ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.PartitionGeometryUnknown)),
                )
            intent.purpose == FilesPickPurpose.WindowsImage && partition.readOnly ->
                ReduceResult(
                    state.copy(
                        statusMessage =
                            message(UiMessageKey.ReadOnlyPartition, partition.name),
                    ),
                )
            path == null ->
                ReduceResult(
                    state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
                )
            else -> {
                val session = newFilesPickSession()
                val mode =
                    FilesPickMode(
                        sessionId = session,
                        purpose = intent.purpose,
                        returnTab = intent.returnTab,
                        target = intent.target,
                        identity = device.identity,
                        attachGeneration = device.attachGeneration,
                        partitionId = partition.id,
                        startLba = partition.startLba,
                        sectorCount = partition.sectorCount,
                    )
                ReduceResult(
                    state.copy(
                        tab = ShellTab.Files,
                        filesPickSession = session,
                        filesPickMode = mode,
                        selectedPartitionId = partition.id,
                        currentPath = path,
                        files = emptyList(),
                        filesError = null,
                        filesListGeneration = state.filesListGeneration + 1L,
                        filesPending = true,
                        pendingIsoImport = null,
                        deferredIsoImport = null,
                        isoImportError = null,
                        isoImportSafInFlight = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                        statusMessage = null,
                    ),
                    effects = listOf(UiEffect.LoadFiles(path = path)),
                )
            }
        }
    }

    private fun cancelFilesPick(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        val blocked = filesPickExitBlock(state)
        if (blocked != null) {
            return ReduceResult(state.copy(statusMessage = blocked))
        }
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                statusMessage =
                    if (hostInspection) {
                        UiMessage.Key(UiMessageKey.ImportInspectionCancelling)
                    } else {
                        null
                    },
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                // The USB guard ends only when the host reports the closed disk (Finished).
                isoImportGeneration = state.isoImportGeneration + 1,
            ),
            effects =
                buildList {
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                    add(UiEffect.CancelPendingWindowsImport(mode.sessionId))
                },
        )
    }

    /** The pin no longer names the live attachment/partition: close it, write nothing. */
    private fun abortFilesPickPinLost(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid),
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                isoImportGeneration = state.isoImportGeneration + 1,
            ),
            effects =
                buildList {
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                    add(UiEffect.CancelPendingWindowsImport(mode.sessionId))
                },
        )
    }

    private fun finishFilesPick(
        state: AppUiState,
        mode: FilesPickMode,
        selection: FilesPickSelection,
        status: UiMessage? = null,
        effect: UiEffect? = null,
    ): ReduceResult {
        if (!mode.matches(state)) return abortFilesPickPinLost(state)
        val hostInspection =
            state.isoImportSafInFlight != null || state.isoImportUsbInFlight != null
        val staleInspection =
            hostInspection ||
                state.pendingIsoImport != null ||
                state.deferredIsoImport != null ||
                state.isoImportError != null
        return ReduceResult(
            state.copy(
                tab = mode.returnTab,
                filesPickSession = newFilesPickSession(),
                filesPickMode = null,
                filesPickSelections = state.filesPickSelections + (mode.purpose to selection),
                statusMessage = status,
                pendingIsoImport = null,
                deferredIsoImport = null,
                isoImportError = null,
                isoImportSafInFlight = null,
                // The USB guard itself is released only by host Finished after disk close.
                isoImportGeneration =
                    if (staleInspection) {
                        state.isoImportGeneration + 1
                    } else {
                        state.isoImportGeneration
                    },
            ),
            effects =
                buildList {
                    effect?.let(::add)
                    if (hostInspection) add(UiEffect.CancelIsoImportInspection)
                },
        )
    }

    private fun confirmFilesPickFile(state: AppUiState, name: String): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.target != FilesPickTarget.File) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortFilesPickPinLost(state)
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        }
        // A stale row must not be confirmed while the listing is loading or failed.
        if (state.filesPending || state.filesError != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val entry = state.files.firstOrNull { it.name == name && !it.isDirectory }
        if (entry == null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val path = fatChildPath(state.currentPath, entry.name)
        val selection = mode.selection(path, entry.name, entry.sizeBytes, isDirectory = false)
        if (mode.purpose == FilesPickPurpose.RawSource) {
            val device =
                state.device
                    ?: return abortFilesPickPinLost(state)
            val binding =
                PartitionBinding(
                    device = device,
                    partitionId = mode.partitionId,
                    startLba = mode.startLba,
                    sectorCount = mode.sectorCount,
                )
            return finishFilesPick(
                state = state,
                mode = mode,
                selection = selection,
                status = UiMessage.Key(UiMessageKey.StagingFile, listOf(entry.name)),
                effect =
                    UiEffect.StageUsbImageForRawWrite(
                        path = state.currentPath,
                        name = entry.name,
                        binding = binding,
                    ),
            )
        }
        return finishFilesPick(state, mode, selection)
    }

    private fun confirmFilesPickFolder(state: AppUiState): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.target != FilesPickTarget.Folder) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortFilesPickPinLost(state)
        if (!state.actionsEnabled) {
            return ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        }
        val path = safePickPath(state.currentPath)
        if (path == null || state.filesPending || state.filesError != null) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        val name = if (path == "/") "/" else path.substringAfterLast('/')
        return finishFilesPick(
            state = state,
            mode = mode,
            selection = mode.selection(path, name, sizeBytes = 0UL, isDirectory = true),
        )
    }

    private fun filesPickCopySucceeded(
        state: AppUiState,
        rawPath: String,
        sizeBytes: Long?,
        pickSessionId: String?,
    ): ReduceResult {
        val mode =
            state.filesPickMode
                ?: return ReduceResult(state)
        if (mode.purpose != FilesPickPurpose.WindowsImage ||
            mode.target != FilesPickTarget.File
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickActive)),
            )
        }
        if (pickSessionId != mode.sessionId) {
            // A copy started by an older session must never select into this one.
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        if (!mode.matches(state)) return abortFilesPickPinLost(state)
        val path = safePickPath(rawPath)
        val leaf = path?.substringAfterLast('/').orEmpty()
        if (path == null ||
            leaf.isEmpty() ||
            fatChildPath(state.currentPath, leaf) != path
        ) {
            return ReduceResult(
                state.copy(statusMessage = UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
        }
        return finishFilesPick(
            state = state,
            mode = mode,
            selection =
                mode.selection(
                    path = path,
                    name = leaf,
                    sizeBytes = sizeBytes?.takeIf { it >= 0L }?.toULong() ?: 0UL,
                    isDirectory = false,
                ),
        )
    }

    /** Live sector count when the attachment exposes it; null keeps the check unavailable. */
    private fun liveSectorCount(state: AppUiState): ULong? {
        state.rawGeometry?.diskSectorCount?.let { return it }
        val capacity = state.device?.capacityBytes ?: return null
        val sectorSize = state.diskSectorSize
        if (capacity == 0UL || sectorSize <= 0) return null
        return capacity / sectorSize.toULong()
    }
}
