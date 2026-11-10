package usb.ui

import usb.fs.FilesystemId
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageSnapshot
import usb.fs.FormatCatalog
import usb.fs.VolumeLabel
import usb.partition.PartitionName
import usb.partition.TableScheme
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendParams
import usb.ventoy.AutounattendState
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.autounattendPlanSha256

/**
 * Pure state transitions for the four-screen shell.
 * Compose and JVM tests drive the same reducer; no USB I/O here.
 */
sealed class UiIntent {
    data class SelectTab(val tab: ShellTab) : UiIntent()

    data class SelectPartition(val partitionId: String) : UiIntent()
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
    ) : UiIntent()
    /** Only an explicit true may replace an existing file. */
    data class ConfirmWindowsIsoImport(val overwrite: Boolean) : UiIntent()
    data class WindowsIsoImportRejected(
        val problem: UnreadableProblem,
        val reason: String? = null,
    ) : UiIntent()
    data object CancelWindowsIsoImport : UiIntent()

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
    data object LoadVentoyFolders : UiIntent()
    data class VentoyFoldersLoaded(
        val paths: List<String>,
        val error: String? = null,
    ) : UiIntent()
    data class VentoyGlobalSettingsLoaded(
        val settings: VentoyGlobalSettings,
    ) : UiIntent()
    /** Only known keys. null value removes the key; "0"/"1" set it. */
    data class RequestSetVentoyGlobalSetting(val key: String, val value: String?) : UiIntent()

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

    /** SAF [uri] of the GPT critical-sector backup chosen for the next restore. */
    data class SetBackupFile(val uri: String?) : UiIntent()
    data class SetRescueImage(val uri: String?) : UiIntent()
    data class SetRescueMap(val uri: String?) : UiIntent()

    /** Read-only scan of every sector; reports unreadable ranges. */
    data object StartReadScan : UiIntent()

    /** Read-only full image plus rescue map into the app's external files dir. */
    data object StartRescueBackup : UiIntent()

    /** Capture the GPT critical sectors into the app's external files dir. */
    data object CaptureGptBackup : UiIntent()

    /** Cycle dark theme: System (null) -> Dark (true) -> Light (false) -> System (null). */
    data object ToggleTheme : UiIntent()

    /** Switch the in-app language; persisted by the host. */
    data class SetLanguage(val language: AppLanguage) : UiIntent()

    /** Run the automated self-test suite over all functional engines. */
    data object RunSelfTest : UiIntent()

    data class Status(val message: UiMessage?) : UiIntent()

    data object CancelOperation : UiIntent()

    /**
     * Enter the absolute FAT directory [path]. Clears [AppUiState.files] and emits a
     * [UiEffect.Kind.LoadFiles] effect, so nothing is displayed for an unlisted path.
     */
    data class OpenDirectory(val path: String) : UiIntent()

    /** Go to the parent of [AppUiState.currentPath]; no-op at the root. */
    data object NavigateUp : UiIntent()

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

    /** Result of a real FAT list of [path]; applied only while that path is displayed. */
    data class FilesListed(
        val path: String,
        val files: List<FileEntry>,
    ) : UiIntent()

    /** Real list failure for [path]; displayed entries are voided, never faked. */
    data class FilesListFailed(
        val path: String,
        val message: String,
    ) : UiIntent()

    /** Append a timestamped line to the bounded operation log. */
    data class AppendLog(val line: String) : UiIntent()
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

data class UiEffect(
    val kind: Kind,
    val action: DestructiveAction? = null,
    val isoName: String? = null,
    val isoAction: IsoAction? = null,
    val path: String? = null,
    val name: String? = null,
    /** Target name for [Kind.RenameEntry]. */
    val newName: String? = null,
    /** Exact device/partition target captured for this mutation. */
    val binding: PartitionBinding? = null,
    val uri: String? = null,
    val formatId: FilesystemId? = null,
    val linkOverwrite: Boolean = false,
    val confirmation: DestructiveConfirmation? = null,
    /** Partition to measure for [Kind.LoadFilesystemUsage]. */
    val partitionId: String? = null,
    /** Exact attachment/geometry the measurement is valid for; the host re-checks it live. */
    val usageKey: FilesystemUsageKey? = null,
    /** Request id echoed back in [UiIntent.FilesystemUsageResulted]. */
    val usageGeneration: Long? = null,
    /** Absolute range for [Kind.RangeProbe]. */
    val startLba: ULong? = null,
    val sectorCount: ULong? = null,
    /** Echoed back in [usb.image.RangeProbeResult] so stale results can be dropped. */
    val probeRequestId: Long? = null,
    /** Logical request id for async listings; stale results are dropped by the reducer. */
    val requestGeneration: Long? = null,
    /** Inspected SAF ISO import (uri, target, existence, attachment pin). */
    val isoImport: IsoImportProposal? = null,
    /** True only after the user explicitly confirmed replacing an existing ISO. */
    val overwrite: Boolean = false,
    /** Multiple image paths for one XML link (empty = use [isoName]). */
    val imagePaths: List<String> = emptyList(),
    /** Existing XML template path on the data partition; never rewritten. */
    val templatePath: String? = null,
    val allowDestructiveTemplate: Boolean = false,
    val expectedTemplateSha256: String? = null,
    val expectedPlanSha256: String? = null,
    /** Image or XML template destination for phone import. */
    val importKind: WindowsImportKind = WindowsImportKind.Image,
    /** User-selected existing destination folder; null = legacy default. */
    val importDirectory: String? = null,
    /** Autounattend generation/read/bind payloads. */
    val xmlName: String? = null,
    val autounattendParams: AutounattendParams? = null,
    val overwriteXml: Boolean = false,
    val settingKey: String? = null,
    val settingValue: String? = null,
) {
    enum class Kind {
        SubmitDestructive,
        CancelOperation,
        LinkXml,
        LoadFiles,
        CreateFolder,
        DeleteEntry,
        RenameEntry,
        UploadFile,
        DownloadFile,
        StageUsbImageForRawWrite,
        ReadScan,
        RescueBackup,
        CaptureGptBackup,
        RunSelfTest,
        LoadFilesystemUsage,
        RangeProbe,
        LoadRawSources,
        CancelRawSources,
        LoadWindowsIsoCandidates,
        InspectIsoImport,
        ImportIso,
        LoadWindowsLinks,
        UnlinkXml,
        SaveWindowsControls,
        LoadAutounattend,
        /** Same generation as [LoadAutounattend]: the host reads the image list beside the XML. */
        LoadAutounattendCandidates,
        GenerateAutounattend,
        LoadVentoyGlobalSettings,
        LoadVentoyFolders,
        SetVentoyGlobalSetting,
        BindAutounattend,
        UnbindAutounattend,
    }
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
            is UiIntent.SelectTab ->
                ReduceResult(state.copy(tab = intent.tab, statusMessage = null))

            is UiIntent.SelectPartition ->
                if (!state.actionsEnabled) {
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
                            // A value measured for the previous partition is not current.
                            filesystemUsage = null,
                            rawSourceCandidates = null,
                            rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                            rawSourceCandidatesIdentity = null,
                            rawSourceCandidatesTruncated = false,
                            pendingIsoImport = null,
                            isoImportError = null,
                            isoImportGeneration = state.isoImportGeneration + 1,
                            windowsSelectedImages = emptyList(),
                            windowsTemplatePath = null,
                            windowsImageLinks = null,
                            windowsImageLinksGeneration = state.windowsImageLinksGeneration + 1,
                            windowsImageLinksIdentity = null,
                            pendingWindowsUnlink = null,
                            autounattendState = AutounattendState.Missing,
                            autounattendBindings = null,
                            autounattendCandidates = null,
                            autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
                            diskLayoutConfirmation = null,
                            pendingAutounattendGeneration = null,
                            ventoyGlobalSettings = null,
                        ventoyFolders = null,
                        ventoyFoldersError = null,
                        ),
                        effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = "/")),
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
                val selected =
                    intent.partitions
                        .firstOrNull { it.id == state.selectedPartitionId }
                        ?.id
                        ?: intent.partitions.firstOrNull()?.id
                val candidateMatchesDevice =
                    intent.presence == DevicePresence.Ready &&
                        intent.device?.identity == state.linkOverwriteCandidateIdentity &&
                        intent.device?.attachGeneration == state.linkOverwriteCandidateGeneration
                // The EFI write override belongs to one physical attachment: a partition
                // refresh keeps it, a new attach generation / other stick / detach drops it.
                val incoming = intent.device
                val sameAttachment =
                    intent.presence == DevicePresence.Ready &&
                        incoming != null &&
                        incoming.identity == state.device?.identity &&
                        incoming.attachGeneration == state.device?.attachGeneration
                val retainedEfi = state.vtoyEfiWritePartitionId?.takeIf { sameAttachment }?.let { id ->
                    intent.partitions.firstOrNull {
                        it.id == id &&
                            it.readOnly &&
                            it.startLba == state.vtoyEfiWriteStartLba &&
                            it.sectorCount == state.vtoyEfiWriteSectorCount
                    }
                }
                ReduceResult(
                    state.copy(
                        presence = intent.presence,
                        device = intent.device,
                        vtoyEfiWritePartitionId = retainedEfi?.id,
                        vtoyEfiWriteStartLba = retainedEfi?.startLba,
                        vtoyEfiWriteSectorCount = retainedEfi?.sectorCount,
                        partitions = intent.partitions,
                        freeRegions = intent.freeRegions,
                        diskSectorSize = intent.sectorSize,
                        tableValid = intent.tableValid,
                        partitionTableDamaged = intent.partitionTableDamaged,
                        partitionTableAmbiguous = intent.partitionTableAmbiguous,
                        partitionScheme = intent.partitionScheme,
                        rawGeometry = intent.rawGeometry,
                        rangeProbe = null,
                        rangeProbeRequestedRange = null,
                        selectedPartitionId = selected,
                        currentPath = "/",
                        files = intent.files,
                        filesError = null,
                        // A refresh may describe another attachment or geometry.
                        filesystemUsage = null,
                        pendingConfirmation = null,
                        rawSourceCandidates = null,
                        rawSourceCandidatesGeneration = state.rawSourceCandidatesGeneration + 1,
                        rawSourceCandidatesIdentity = null,
                        rawSourceCandidatesTruncated = false,
                        windowsIsoCandidates = null,
                        windowsIsoCandidatesGeneration = state.windowsIsoCandidatesGeneration + 1,
                        windowsIsoCandidatesIdentity = null,
                        windowsIsoCandidatesError = null,
                        pendingIsoImport = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                        windowsSelectedImages = emptyList(),
                        windowsTemplatePath = null,
                        windowsImageLinks = null,
                        windowsImageLinksGeneration = state.windowsImageLinksGeneration + 1,
                        windowsImageLinksIdentity = null,
                        pendingWindowsUnlink = null,
                        linkOverwriteCandidatePaths = emptyList(),
                        linkOverwriteCandidateTemplatePath = null,
                        autounattendState = AutounattendState.Missing,
                        autounattendBindings = null,
                        autounattendCandidates = null,
                        autounattendRequestGeneration = state.autounattendRequestGeneration + 1,
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                        ventoyGlobalSettings = null,
                        ventoyFolders = null,
                        ventoyFoldersError = null,
                        linkOverwriteCandidate = state.linkOverwriteCandidate.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateIdentity = state.linkOverwriteCandidateIdentity.takeIf { candidateMatchesDevice },
                        linkOverwriteCandidateGeneration = state.linkOverwriteCandidateGeneration.takeIf { candidateMatchesDevice },
                        statusMessage =
                            if (intent.presence == DevicePresence.Ready) {
                                state.statusMessage
                            } else {
                                presenceMessage(intent.presence)
                            },
                    ),
                )
            }

            is UiIntent.OperationUpdated ->
                ReduceResult(
                    state.copy(
                        operation = intent.progress,
                        // A write, format or file mutation can change free space while the
                        // geometry stays identical, so the old number is not current.
                        filesystemUsage =
                            when (intent.progress?.state) {
                                OperationState.Completed,
                                OperationState.Cancelled,
                                OperationState.Failed,
                                OperationState.Partial,
                                -> null
                                else -> state.filesystemUsage
                            },
                        presence =
                            when {
                                intent.progress?.state == OperationState.Running ->
                                    if (state.presence == DevicePresence.Ready ||
                                        state.presence == DevicePresence.Busy
                                    ) {
                                        DevicePresence.Busy
                                    } else {
                                        state.presence
                                    }
                                intent.progress?.state == OperationState.Completed ||
                                    intent.progress?.state == OperationState.Cancelled ||
                                    intent.progress?.state == OperationState.Failed ||
                                    intent.progress?.state == OperationState.Partial ->
                                    if (state.presence == DevicePresence.Busy) {
                                        DevicePresence.Ready
                                    } else {
                                        state.presence
                                    }
                                else -> state.presence
                            },
                        statusMessage =
                            when (intent.progress?.state) {
                                OperationState.Completed ->
                                    // Keep a more specific status (for example a staged-image
                                    // hint) when the operation has no result message.
                                    intent.progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: intent.progress.resultMessage?.let(UiMessage::Raw)
                                        ?: state.statusMessage?.takeIf { !it.isDefaultStateStatus() }
                                        ?: UiMessage.Key(UiMessageKey.Success)
                                OperationState.Cancelled ->
                                    intent.progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: UiMessage.Key(UiMessageKey.CancelledStatus)
                                OperationState.Failed ->
                                    intent.progress.errorMessage?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.FailedStatus)
                                OperationState.Partial ->
                                    intent.progress.readScanResult?.let(UiMessage::ReadScan)
                                        ?: (intent.progress.resultMessage ?: intent.progress.errorMessage)
                                            ?.let(UiMessage::Raw)
                                        ?: UiMessage.Key(UiMessageKey.PartialStatus)
                                OperationState.Running -> UiMessage.Key(UiMessageKey.RunningStatus)
                                OperationState.Queued -> UiMessage.Key(UiMessageKey.QueuedStatus)
                                null -> state.statusMessage
                            },
                    ),
                )

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
                            UiEffect(
                                UiEffect.Kind.RangeProbe,
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
                    action == DestructiveAction.INITIALIZE_PARTITION_TABLE &&
                        (pending?.targetScheme == null ||
                            pending.initialScheme == null ||
                            pending.targetScheme == pending.initialScheme ||
                            state.partitionScheme?.toPartitionScheme() != pending.initialScheme ||
                            !state.tableValid) -> true
                    action == DestructiveAction.RENAME_PARTITION &&
                        (pending?.partitionName.isNullOrBlank() ||
                            state.partitionScheme != TableScheme.GPT ||
                            !state.tableValid) -> true
                    action == DestructiveAction.RENAME_VOLUME_LABEL &&
                        (pending?.volumeLabel.isNullOrBlank() ||
                            state.selectedPartitionId == null) -> true
                    action == DestructiveAction.SET_PARTITION_ROLE &&
                        (pending?.partitionRole == null || !state.tableValid) -> true
                    action == DestructiveAction.SET_PARTITION_BOOTABLE &&
                        (pending?.partitionBootable == null || !state.tableValid) -> true
                    action == DestructiveAction.RAW_WRITE &&
                        (pending?.rawStartLba == null || pending.rawSectorCount == null) -> true
                    (action == DestructiveAction.DELETE_PARTITION || action == DestructiveAction.FORMAT_PARTITION) &&
                        state.selectedPartitionId == null -> true
                    else -> false
                }
                if (invalidState || action == null) {
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
                                UiEffect(
                                    UiEffect.Kind.LinkXml,
                                    isoName = paths.first(),
                                    imagePaths = paths,
                                    templatePath = pending.templatePath,
                                    allowDestructiveTemplate = pending.allowDestructiveTemplate,
                                    isoAction = IsoAction.LinkXml,
                                    linkOverwrite = pending.linkOverwrite,
                                    confirmation = pending,
                                ),
                            ),
                        )
                    }
                } else {
                    ReduceResult(
                        state.copy(pendingConfirmation = null),
                        effects = listOf(
                            UiEffect(
                                UiEffect.Kind.SubmitDestructive,
                                action = action,
                                formatId = pending.formatId,
                                confirmation = pending,
                                isoName = pending.isoName,
                                linkOverwrite = pending.linkOverwrite,
                            ),
                        ),
                    )
                }
            }

            UiIntent.DismissConfirmation ->
                ReduceResult(state.copy(pendingConfirmation = null))
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
                                UiEffect(
                                    UiEffect.Kind.StageUsbImageForRawWrite,
                                    name = name,
                                    path = state.currentPath,
                                    isoName = name,
                                    isoAction = intent.action,
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
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.LoadRawSources,
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
                            statusMessage = notice,
                        ),
                    )
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
                                    UiEffect(
                                        UiEffect.Kind.StageUsbImageForRawWrite,
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
                    ),
                    effects = listOf(UiEffect(UiEffect.Kind.CancelRawSources)),
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
                                UiEffect(
                                    UiEffect.Kind.LoadWindowsIsoCandidates,
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
                when {
                    !state.actionsEnabled || state.pendingIsoImport != null ->
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
                    state.device == null ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    uri.isEmpty() ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Raw("No file selected for ISO import")))
                    else -> {
                        val generation = state.isoImportGeneration + 1
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                isoImportError = null,
                                isoImportGeneration = generation,
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.InspectIsoImport,
                                    uri = uri,
                                    requestGeneration = generation,
                                    importKind = intent.kind,
                                    importDirectory = intent.directory,
                                ),
                            ),
                        )
                    }
                }
            }

            is UiIntent.WindowsIsoImportRejected ->
                ReduceResult(
                    state.copy(
                        pendingIsoImport = null,
                        isoImportError = intent.problem,
                    ),
                )

            is UiIntent.WindowsIsoImportInspected -> {
                val device = state.device
                if (device == null ||
                    intent.generation != state.isoImportGeneration ||
                    !state.actionsEnabled
                ) {
                    ReduceResult(state)
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
                        )
                    if (intent.exists) {
                        // No write until the user explicitly accepts replacement.
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = proposal,
                                isoImportError = null,
                                statusMessage = UiMessage.Raw(
                                    "${importTarget(proposal.directory, intent.targetName)} already exists; confirm overwrite to replace it",
                                ),
                            ),
                        )
                    } else {
                        ReduceResult(
                            state.copy(
                                pendingIsoImport = null,
                                statusMessage = UiMessage.Raw(
                                    "Importing ${intent.targetName} into ${proposal.directory}",
                                ),
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.ImportIso,
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
                when {
                    proposal == null || device == null ->
                        ReduceResult(state.copy(pendingIsoImport = null))
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
                                UiEffect(
                                    UiEffect.Kind.ImportIso,
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
                ReduceResult(
                    state.copy(
                        pendingIsoImport = null,
                        isoImportError = null,
                        isoImportGeneration = state.isoImportGeneration + 1,
                    ),
                )

            UiIntent.SaveWindowsControls ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state,
                        effects = listOf(UiEffect(UiEffect.Kind.SaveWindowsControls)),
                    )
                }

            is UiIntent.ToggleWindowsImage -> {
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    val candidate =
                        state.windowsIsoCandidates?.firstOrNull { it.path == intent.path }
                    if (candidate == null) {
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Selected image is no longer listed; reload the list",
                                ),
                            ),
                        )
                    } else {
                        val already = state.windowsSelectedImages.any { it.path == candidate.path }
                        val selected =
                            if (already) {
                                state.windowsSelectedImages.filterNot { it.path == candidate.path }
                            } else {
                                state.windowsSelectedImages + candidate
                            }
                        ReduceResult(state.copy(windowsSelectedImages = selected))
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
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.LoadAutounattend,
                                    xmlName = template.path,
                                    requestGeneration = generation,
                                ),
                                UiEffect(
                                    UiEffect.Kind.LoadAutounattendCandidates,
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
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                    effects = listOf(
                        UiEffect(
                            UiEffect.Kind.LoadAutounattend,
                            xmlName = intent.path,
                            requestGeneration = generation,
                        ),
                        UiEffect(
                            UiEffect.Kind.LoadAutounattendCandidates,
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
                        selected.all { chosen ->
                            state.windowsIsoCandidates?.any { it.path == chosen.path } == true
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
                        val paths = selected.map { it.path }
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
                                UiEffect(
                                    UiEffect.Kind.LoadWindowsLinks,
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
                                UiEffect(
                                    UiEffect.Kind.UnlinkXml,
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
                        state.copy(autounattendRequestGeneration = generation),
                        effects = listOf(
                            UiEffect(
                                UiEffect.Kind.LoadAutounattend,
                                xmlName = intent.xmlName,
                                requestGeneration = generation,
                            ),
                            UiEffect(
                                UiEffect.Kind.LoadAutounattendCandidates,
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
                        ),
                    )
                }

            is UiIntent.AutounattendCandidatesLoaded ->
                if (intent.generation != state.autounattendRequestGeneration) {
                    ReduceResult(state)
                } else {
                    ReduceResult(state.copy(autounattendCandidates = intent.candidates))
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
                                statusMessage = UiMessage.Raw(
                                    "Replacing an existing XML requires the separate XML-write confirmation",
                                ),
                            ),
                        )
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
                                    DiskLayoutConfirmation(
                                        diskId = layout.diskId,
                                        partitionCount = layout.partitions.size,
                                        writeLayout = true,
                                        planSha256 = planSha256,
                                    ),
                                statusMessage = UiMessage.Raw(
                                    "Confirm the target PC disk layout before generating XML",
                                ),
                            ),
                        )
                    else ->
                        ReduceResult(
                            state.copy(statusMessage = UiMessage.Raw("Generating unattend XML")),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.GenerateAutounattend,
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
                    else ->
                        ReduceResult(
                            state.copy(
                                diskLayoutConfirmation = null,
                                pendingAutounattendGeneration = null,
                                statusMessage = UiMessage.Raw("Generating unattend XML with disk layout"),
                            ),
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.GenerateAutounattend,
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

            UiIntent.DismissDiskLayout ->
                ReduceResult(
                    state.copy(
                        diskLayoutConfirmation = null,
                        pendingAutounattendGeneration = null,
                    ),
                )

            UiIntent.LoadVentoyGlobalSettings ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect(UiEffect.Kind.LoadVentoyGlobalSettings)),
                )

            UiIntent.LoadVentoyFolders ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect(UiEffect.Kind.LoadVentoyFolders)),
                )

            is UiIntent.VentoyFoldersLoaded ->
                ReduceResult(
                    state.copy(
                        ventoyFolders = intent.paths,
                        ventoyFoldersError = intent.error,
                    ),
                )

            is UiIntent.VentoyGlobalSettingsLoaded ->
                ReduceResult(
                    state.copy(
                        ventoyGlobalSettings = intent.settings.entries,
                        statusMessage =
                            intent.settings.error?.let(UiMessage::Raw) ?: state.statusMessage,
                    ),
                )

            is UiIntent.RequestSetVentoyGlobalSetting -> {
                val known =
                    intent.key == "VTOY_WIN11_BYPASS_CHECK" ||
                        intent.key == "VTOY_WIN11_BYPASS_NRO"
                when {
                    !known ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "\"${intent.key}\" is not a validated Ventoy control key",
                                ),
                            ),
                        )
                    intent.value != null && intent.value != "0" && intent.value != "1" ->
                        ReduceResult(
                            state.copy(
                                statusMessage = UiMessage.Raw(
                                    "Value must be exactly \"0\", \"1\", or null to remove the key",
                                ),
                            ),
                        )
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    else ->
                        ReduceResult(
                            state,
                            effects = listOf(
                                UiEffect(
                                    UiEffect.Kind.SetVentoyGlobalSetting,
                                    settingKey = intent.key,
                                    settingValue = intent.value,
                                ),
                            ),
                        )
                }
            }

            is UiIntent.RequestBindAutounattend ->
                if (!state.actionsEnabled) {
                    ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                } else {
                    ReduceResult(
                        state,
                        effects = listOf(
                            UiEffect(
                                UiEffect.Kind.BindAutounattend,
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
                            UiEffect(
                                UiEffect.Kind.UnbindAutounattend,
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

            is UiIntent.SetBackupFile ->
                ReduceResult(
                    state.copy(
                        backupUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.BackupCleared)
                            } else {
                                message(
                                    UiMessageKey.BackupReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
                    ),
                )

            UiIntent.StartReadScan -> nonDestructiveEffect(state, UiEffect.Kind.ReadScan)

            UiIntent.StartRescueBackup -> nonDestructiveEffect(state, UiEffect.Kind.RescueBackup)

            UiIntent.CaptureGptBackup -> nonDestructiveEffect(state, UiEffect.Kind.CaptureGptBackup)

            is UiIntent.SetLanguage -> ReduceResult(state.copy(language = intent.language))

            UiIntent.ToggleTheme -> {
                val next = when (state.darkTheme) {
                    null -> true
                    true -> false
                    false -> null
                }
                ReduceResult(state.copy(darkTheme = next))
            }

            UiIntent.RunSelfTest ->
                if (state.isRunning) {
                    ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.OperationInProgress)))
                } else {
                    ReduceResult(
                        state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelfTestStarting)),
                        effects = listOf(UiEffect(UiEffect.Kind.RunSelfTest)),
                    )
                }

            is UiIntent.SetRescueImage ->
                ReduceResult(
                    state.copy(
                        rescueImageUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.RescueImageCleared)
                            } else {
                                message(
                                    UiMessageKey.RescueImageReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
                    ),
                )

            is UiIntent.SetRescueMap ->
                ReduceResult(
                    state.copy(
                        rescueMapUri = intent.uri,
                        statusMessage =
                            if (intent.uri == null) {
                                UiMessage.Key(UiMessageKey.RescueMapCleared)
                            } else {
                                message(
                                    UiMessageKey.RescueMapReady,
                                    intent.uri.substringAfterLast('/'),
                                )
                            },
                    ),
                )

            is UiIntent.Status -> ReduceResult(state.copy(statusMessage = intent.message))

            UiIntent.CancelOperation ->
                ReduceResult(
                    state,
                    effects = listOf(UiEffect(UiEffect.Kind.CancelOperation)),
                )

            is UiIntent.OpenDirectory -> {
                val target = normalizeFatPath(intent.path)
                ReduceResult(
                    state.copy(currentPath = target, files = emptyList(), filesError = null),
                    effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = target)),
                )
            }

            UiIntent.NavigateUp -> {
                val target = fatParentPath(state.currentPath)
                if (target == state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(currentPath = target, files = emptyList(), filesError = null),
                        effects = listOf(UiEffect(UiEffect.Kind.LoadFiles, path = target)),
                    )
                }
            }

            is UiIntent.CreateFolder -> {
                val name = intent.name.trim()
                val block = writeBlockMessage(state)
                when {
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
                                    UiEffect(
                                        UiEffect.Kind.CreateFolder,
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
                                    UiEffect(
                                        UiEffect.Kind.DeleteEntry,
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
                                    UiEffect(
                                        UiEffect.Kind.RenameEntry,
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
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    block != null ->
                        ReduceResult(state.copy(statusMessage = block))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.UploadFile,
                                        path = state.currentPath,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }
            }

            is UiIntent.DownloadFile ->
                when {
                    !state.actionsEnabled ->
                        ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
                    !validEntryName(intent.name) ->
                        ReduceResult(state.copy(statusMessage = UiMessage.Key(UiMessageKey.SelectFileToDownload)))
                    else ->
                        ReduceResult(
                            state,
                            effects =
                                listOf(
                                    UiEffect(
                                        UiEffect.Kind.DownloadFile,
                                        path = state.currentPath,
                                        name = intent.name,
                                        uri = intent.uri,
                                    ),
                                ),
                        )
                }

            is UiIntent.FilesListed ->
                if (intent.path != state.currentPath) {
                    // Late result for a directory the user already left.
                    ReduceResult(state)
                } else {
                    ReduceResult(state.copy(files = intent.files, filesError = null))
                }

            is UiIntent.FilesListFailed ->
                if (intent.path != state.currentPath) {
                    ReduceResult(state)
                } else {
                    ReduceResult(
                        state.copy(
                            files = emptyList(),
                            filesError = intent.message,
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
            UiEffect(
                kind = UiEffect.Kind.LoadFilesystemUsage,
                partitionId = partition.id,
                usageKey = key,
                usageGeneration = generation,
            ),
        )
    }

    /** Read-only disk work still needs a ready device; otherwise report why nothing ran. */
    private fun nonDestructiveEffect(state: AppUiState, kind: UiEffect.Kind): ReduceResult =
        if (!state.actionsEnabled) {
            ReduceResult(state.copy(statusMessage = presenceMessage(state.presence)))
        } else {
            ReduceResult(state, effects = listOf(UiEffect(kind)))
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
     * Bare names resolve under /ventoy; a full path is itself. This only decides whether a
     * rewrite confirmation applies to the currently loaded file, never where to write.
     */
    private fun importTarget(directory: String, name: String): String =
        if (directory == "/") "/$name" else "$directory/$name"

    internal fun sameXmlTarget(loaded: String, requested: String): Boolean {
        val a = loaded.replace('\\', '/').trim().trimStart('/')
        val cleanRequested = requested.replace('\\', '/').trim()
        val expected =
            if (cleanRequested.contains("/")) {
                cleanRequested.trimStart('/')
            } else {
                "ventoy/${cleanRequested.trimStart('/')}"
            }
        return a.equals(expected, ignoreCase = true)
    }
}
