package usb.multitool
import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.AttachmentState
import usb.files.FilesVolumeSession
import usb.files.MountedFilesVolume
import usb.files.FilesOperationPolicy

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.app.Application
import android.content.ContentResolver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.SavedStateHandle
import usb.ui.ShellTab
import usb.ui.ShellTabPersistence
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendState
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyGlobalSettings
import usb.ventoy.VentoySettingsDocument
import usb.ventoy.VentoySettingsDocumentException
import usb.ventoy.VentoySettingsSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import usb.device.DiskException
import usb.device.TransferTuning
import usb.device.MassStorageDiscovery
import usb.device.UsbDisk
import usb.device.UsbPermissionPolicy
import usb.device.withWriteTracking
import usb.service.DiskController
import usb.operation.DiskOperation
import usb.operation.syncDestructiveDisk
import usb.service.OperationHandle
import usb.service.OperationToken
import usb.service.OperationTokenGenerator
import usb.service.DiskService
import usb.ui.AppShell
import usb.ui.AppUiState
import usb.ui.BackupFolderAction
import usb.ui.BackupFolderLaunch
import usb.ui.BackupFolderLaunchQueue
import usb.ui.DeviceIdentity
import usb.ui.PendingBackupFolderAction
import usb.ui.DevicePresence
import usb.ui.FileEntry
import usb.ui.OperationProgress
import usb.ui.OperationTitle
import usb.ui.ReadScanOutcome
import usb.ui.RestoreSourceInspector
import usb.ui.readOnlyOperationProgress
import usb.ui.OperationState
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UiReducer
import usb.ui.WindowsImportRequestCodes
import usb.ui.WindowsImportPickerCoordinator
import usb.ui.AppLanguage
import usb.device.UsbDiskSession
import usb.device.UsbExclusiveClaim
import usb.diagnostics.AppTraceLogger
import usb.diagnostics.Diagnostics
import usb.diagnostics.redactTrace
import usb.diagnostics.Rescue
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueOperations
import usb.files.FilesOperations
import usb.diagnostics.BackupArchive
import usb.diagnostics.RescueArchiveOperations
import usb.diagnostics.RescueBackupOperations
import usb.diagnostics.RescueRestorePrep
import usb.saf.SafArchiveTarget
import usb.saf.SafBackupFolder
import usb.saf.SafPositionalImage
import usb.fs.UsbFsDirEntry
import usb.fs.UsbFsVolume
import usb.fs.FilesystemId
import usb.fs.Filesystem
import usb.fs.FormatCatalog
import usb.fs.Formatter
import usb.fs.PartitionVolumeSession
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.ui.filesystemUsageKey
import usb.ui.needsFilesystemUsageRequest
import usb.ui.fatChildPath
import usb.ui.RawMeasureError
import usb.ui.rawSourceKey
import usb.ui.FilesDownloadBinding
import usb.ui.FilesBatchSelection
import usb.ui.FilesBatchItem
import usb.ui.FilesConflict
import usb.ui.FilesConflictDecision
import usb.ui.PartitionBinding
import usb.ui.PartitionRef
import usb.ui.PartitionRole
import usb.ui.PartitionTab
import usb.ui.matches
import usb.ui.safePickPath
import usb.ui.toTableScheme
import usb.partition.GptBackup
import usb.partition.PartitionEditor
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.PartitionWriter
import usb.partition.TableInitializer
import usb.partition.VirtualPartition
import usb.partition.isProtectedSystemPartition
import usb.image.ImageSource
import usb.image.MeasurementHandle
import usb.image.CloseCapacityException
import usb.image.ImageStaging
import usb.image.stageRawImageChunks
import usb.image.RawWriter
import usb.ventoy.VentoyAssets
import usb.ventoy.VentoyDownloader
import usb.ventoy.VentoyOperations
import usb.ventoy.VentoyLayout
import usb.ventoy.VentoySetup
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.security.MessageDigest
import usb.ui.DestructiveAction

private const val WINDOWS_IMPORT_PREFS = "usb.multitool.windows_import"
private const val WINDOWS_IMPORT_NEXT_CODE_KEY = "next_request_code"

/** Durable high-water storage; allocation itself is serialized in [WindowsImportRequestCodes]. */
private class SharedPreferencesRequestCodeStorage(
    private val prefs: SharedPreferences,
) : WindowsImportRequestCodes.Storage {
    override fun readNext(): Int =
        prefs.getInt(
            WINDOWS_IMPORT_NEXT_CODE_KEY,
            WindowsImportRequestCodes.FIRST_REQUEST_CODE,
        )

    override fun writeNext(next: Int): Boolean =
        prefs.edit().putInt(WINDOWS_IMPORT_NEXT_CODE_KEY, next).commit()
}

class MainViewModel(
    application: Application,
    private val savedStateHandle: SavedStateHandle,
) : AndroidViewModel(application) {
    /** Unit tests may construct without a host-owned handle. */
    constructor(application: Application) : this(application, SavedStateHandle())
    private companion object {
        const val LANGUAGE_KEY = "language"
        /** Persisted two-state theme; absent means the dark default. */
        const val THEME_KEY = "dark_theme"
        /** Persisted SAF tree shared by image+map folders and GPT copies. */
        const val BACKUP_FOLDER_URI_KEY = "backup_folder_uri"
        const val BACKUP_FOLDER_NAME_KEY = "backup_folder_name"
        const val PROGRESS_TICK_MS = 120L
        /** Log-append interval: coarser than the UI progress tick to keep the ring buffer readable. */
        const val LOG_TICK_MS = 3_000L
    }

    var state by mutableStateOf(AppUiState())
        private set

    /** Reducer picker events share one FIFO channel so launch/cancel ordering is explicit. */
    private val _pickerEvents = Channel<UiEffect>(Channel.UNLIMITED)
    val pickerEvents: Flow<UiEffect> = _pickerEvents.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Survives ViewModel scope cancellation so completion can still release the handle. */
    private val mainHandler =
        android.os.Handler(android.os.Looper.getMainLooper())
    private var activeHandle: OperationHandle? = null
    private var rawSourceHandle: OperationHandle? = null
    private var rawMeasureJob: Job? = null
    private var rawMeasureHandle: MeasurementHandle? = null
    private var rawMeasureRunning = false
    /** Latest source waiting for a blocked provider pass to return; never starts in parallel. */
    private var pendingRawMeasureEffect: UiEffect.MeasureRawSource? = null
    /**
     * Parked batch transfer waiting for ResolveFilesBatchConflict. USB lock is released
     * before dialog; ContinueFilesBatch re-acquires with fresh pin. Never second USB owner.
     */
    private var parkedBatchWork: ParkedFilesBatch? = null

    private sealed class ParkedFilesBatch {
        abstract val selection: FilesBatchSelection?
        data class Download(
            override val selection: FilesBatchSelection,
            val treeUri: String,
            val cursor: usb.files.FilesOperations.BatchDownloadCursor,
            val conflict: FilesConflict,
        ) : ParkedFilesBatch()
        data class UploadDocs(
            val directory: String,
            val remainingUris: List<String>,
            val identity: String,
            val attachGeneration: Long,
            val partitionId: String,
            val startLba: ULong?,
            val sectorCount: ULong?,
            val conflict: FilesConflict,
        ) : ParkedFilesBatch() {
            override val selection: FilesBatchSelection? get() = null
        }
    }

    private class FilesBatchConflictPark(
        val conflict: FilesConflict,
        val cursor: usb.files.FilesOperations.BatchDownloadCursor? = null,
        val remainingUris: List<String> = emptyList(),
    ) : Exception("batch conflict parked: ${conflict.pathLabel}")
    private var windowsImageHandle: OperationHandle? = null
    private val activeHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<OperationHandle>()
    private val operationGenerations = java.util.concurrent.ConcurrentHashMap<OperationToken, Long>()
    /** Attachment identity, published atomically: device and generation always change together. */
    private val attachment = AttachmentState<UsbDevice>()
    private val attachGeneration: Long get() = attachment.snapshot().generation
    private val attachedDevice: UsbDevice? get() = attachment.snapshot().device
    private fun nextOperationToken(): OperationToken = OperationTokenGenerator.next()
    /** Pick generations make slow SAF inspections lose to the newest selection. */
    private val gptPickGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val rescueArchivePickGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    var usbManager: UsbManager? = null
        private set

    /**
     * One token per ViewModel (survives rotation, not process death). A backup-folder pick
     * restored from saved state keeps the old token, so a new process can never consume it.
     */
    val backupFolderLaunchToken: String = java.util.UUID.randomUUID().toString()

    /**
     * Everything the pre-open hook needs, captured together at dispatch: generation/key from
     * one attachment snapshot and serial from that snapshot's device. [serial] is null only
     * on the immediate path, where the existing serial-less behavior is preserved.
     */
    private data class BackupPin(
        val generation: Long,
        val key: String,
        val serial: String?,
    )

    /** Attachment the next folder-gated backup was chosen for; consumed before open. */
    @Volatile
    private var expectedBackupPin: BackupPin? = null

    /** One-snapshot view used by the picker target check; same source as the dispatch pin. */
    internal fun attachmentSnapshot(): AttachmentState.Snapshot<UsbDevice> = attachment.snapshot()

    private fun deviceKey(device: UsbDevice?): String? =
        device?.let { "${it.deviceId}:${it.deviceName}" }

    /**
     * Immutable pin for a files operation accepted now: attach generation + device key.
     * Captured on the main thread before the operation is queued; null means detached and
     * the operation must fail before touching any volume. Both halves come from one
     * [AttachmentState] snapshot.
     */
    private fun currentAttachmentPin(): AttachmentPin? {
        val live = attachment.snapshot()
        return live.device?.let { device -> AttachmentPin(live.generation, deviceKey(device)!!) }
    }

    /**
     * The live attachment is still the one an operation was accepted for, read from one
     * snapshot. Never combine a generation read and a device read from different publishes:
     * a same-key replug (same deviceId/name, new generation) must still fail the check.
     */
    private fun attachmentMatches(generation: Long, key: String?): Boolean {
        val live = attachment.snapshot()
        return live.generation == generation && deviceKey(live.device) == key
    }

    /** One-snapshot variant of `attachGeneration == gen && attachedDevice?.deviceId == id`. */
    private fun attachmentIs(generation: Long, device: UsbDevice?): Boolean {
        val live = attachment.snapshot()
        return live.generation == generation && live.device?.deviceId == device?.deviceId
    }

    /** One-snapshot variant of `attachGeneration == gen && attachedDevice != null`. */
    private fun attachmentLive(generation: Long): Boolean {
        val live = attachment.snapshot()
        return live.generation == generation && live.device != null
    }

    private val languagePrefs =
        application.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    /**
     * Structured, redacted trace in app-private storage (`filesDir/trace`), two 512 KiB
     * files. The screen journal stays short; this file carries the detail for diagnosis.
     */
    private val trace = AppTraceLogger(getApplication<Application>().filesDir)

    /** Same redacted trace file, reachable from Activity-side picker callbacks. */
    fun traceEvent(event: String, vararg fields: Pair<String, Any?>) {
        trace.log(event, *fields)
    }

    init {
        val restoredTab = ShellTabPersistence.read(savedStateHandle) ?: ShellTab.Files
        val folder = readBackupFolder()
        state = state.copy(
            tab = restoredTab,
            language = readLanguage(),
            darkTheme = readDarkTheme(),
            backupFolderUri = folder.uri,
            backupFolderName = folder.name,
            backupFolderUsable = folder.usable,
        )
        // Seed the handle so a kill before any SelectTab still restores the same tab.
        ShellTabPersistence.write(savedStateHandle, restoredTab)
        DiskService.initialize(application)
        // Fail closed: never auto-delete staging after restart (name ≠ proof of ownership).
        // Leftovers stay on disk; user must re-stage in this process. Notify only.
        runCatching {
            val leftovers = ImageStaging.listStagingFiles(application.filesDir)
            if (leftovers.isNotEmpty()) {
                onIntent(
                    UiIntent.Status(
                        UiMessage.Key(
                            UiMessageKey.StagingLeftovers,
                            listOf(leftovers.size),
                        ),
                    ),
                )
            }
        }
        val storeRevision = state.ventoyStoreRevision
        scope.launch {
            val app = application
            val bundled = withContext(Dispatchers.IO) {
                VentoyAssets.bundledRelease(app.assets)
            }
            val storeState =
                withContext(Dispatchers.IO) {
                    when (val inspection =
                        usb.ventoy.VentoyReleaseStore.inspect(app.filesDir)) {
                        usb.ventoy.VentoyReleaseStore.Inspection.Absent ->
                            usb.ventoy.VentoyReleaseStoreState.Absent
                        usb.ventoy.VentoyReleaseStore.Inspection.Unknown ->
                            usb.ventoy.VentoyReleaseStoreState.Unknown
                        usb.ventoy.VentoyReleaseStore.Inspection.Corrupt ->
                            usb.ventoy.VentoyReleaseStoreState.Corrupt
                        is usb.ventoy.VentoyReleaseStore.Inspection.Verified -> {
                            val stored = inspection.stored
                            usb.ventoy.VentoyReleaseStoreState.Verified(
                                usb.ventoy.VentoyReleaseProvenance(
                                    release = stored.assets.release,
                                    archiveSha256 = stored.archiveSha256,
                                    sourceUrl = stored.sourceUrl,
                                    independentlyPinned = stored.independentlyPinned,
                                ),
                            )
                        }
                    }
                }
            onIntent(UiIntent.SetVentoyBundledRelease(bundled))
            onIntent(UiIntent.SetVentoyStoreState(storeRevision, storeState))
        }
        onIntent(UiIntent.CheckVentoyUpstream)
    }
    /**
     * Owns the mounted FAT volume of the selected partition via [FilesVolumeSession].
     * Every files operation runs inside its lock, so a close can never race a mounted read.
     * Ceiling: detach/onCleared can wait for one files operation; upgrade only if a single
     * FAT call ever becomes unbounded.
     */
    private val filesSession =
        FilesVolumeSession(
            currentAttachment = { currentAttachmentPin() },
            openMounted = { partitionId, writable, pin, lease ->
                openMountedFilesVolume(partitionId, writable, pin, lease)
            },
            claim = DiskController.get().usbClaim,
        )
    /** Key of the newest free-space request; a different key asks again. */
    @Volatile private var lastUsageKey: FilesystemUsageKey? = null
    /** Key whose measurement has not reported back yet; prevents duplicate requests. */
    @Volatile private var usageInFlightKey: FilesystemUsageKey? = null
    @Volatile private var stagedConsumedPath: String? = null

    /**
     * The one phone-import inspection allowed to open USB. Replaced/cleared atomically; workers
     * compare the immutable ticket instead of reading mutable state from a background thread.
     */
    private val inspectionOwner = InspectionOwnership()

    private fun inspectionAttachmentStillHere(ticket: InspectionTicket): Boolean {
        val live = attachment.snapshot()
        return live.generation == ticket.attachGeneration &&
            deviceKey(live.device) == ticket.deviceKey
    }

    /**
     * Disown the running inspection. The SAF job is cancelled best-effort (a blocking provider
     * may ignore it) so a stuck read cannot block a new import; the USB operation is asked to
     * stop only when the device is really going away, otherwise it checks ownership and closes.
     */
    private fun cancelActiveInspection(cancelUsb: Boolean) {
        val cancelled = inspectionOwner.cancel() ?: return
        cancelled.safJob?.cancel()
        if (cancelUsb) cancelled.usbHandle?.cancel()
    }

    override fun onCleared() {
        // Detach/clear must not crash the process if umount/sync fails on a yanked stick.
        cancelActiveInspection(cancelUsb = true)
        runCatching { closeFilesVolume(reportStatus = false) }
        _pickerEvents.close()
        super.onCleared()
        scope.cancel()
    }

    private fun readLanguage(): AppLanguage =
        runCatching {
            AppLanguage.valueOf(
                languagePrefs.getString(LANGUAGE_KEY, AppLanguage.System.name)
                    ?: AppLanguage.System.name,
            )
        }.getOrDefault(AppLanguage.System)

    private fun readDarkTheme(): Boolean =
        runCatching { languagePrefs.getBoolean(THEME_KEY, true) }.getOrDefault(true)

    private data class StoredBackupFolder(
        val uri: String?,
        val name: String?,
        val usable: Boolean,
    )

    private fun hasPersistedWrite(uriString: String): Boolean =
        runCatching {
            getApplication<Application>().contentResolver.persistedUriPermissions.any {
                it.isWritePermission && it.uri.toString() == uriString
            }
        }.getOrDefault(false)

    private fun readBackupFolder(): StoredBackupFolder {
        val uri = languagePrefs.getString(BACKUP_FOLDER_URI_KEY, null)
            ?.takeIf { it.isNotBlank() }
            ?: return StoredBackupFolder(null, null, false)
        val name = languagePrefs.getString(BACKUP_FOLDER_NAME_KEY, null)
        return StoredBackupFolder(uri, name, hasPersistedWrite(uri))
    }

    /** True when the persisted write grant still exists; the provider is checked on write. */
    fun hasUsableBackupFolder(): Boolean {
        val uri = state.backupFolderUri ?: return false
        return state.backupFolderUsable && hasPersistedWrite(uri)
    }

    /**
     * Persist the user's tree choice. Returns false when the grant was not persisted or
     * the provider cannot describe the tree; the caller then keeps the picker flow open.
     */
    fun onBackupFolderPicked(uri: Uri): Boolean {
        val resolver = getApplication<Application>().contentResolver
        runCatching {
            resolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        val persisted = hasPersistedWrite(uri.toString())
        val name = runCatching {
            SafBackupFolder(resolver, uri).displayName()
        }.getOrNull()
        val usable = persisted
        if (usable) {
            languagePrefs.edit()
                .putString(BACKUP_FOLDER_URI_KEY, uri.toString())
                .putString(BACKUP_FOLDER_NAME_KEY, name)
                .apply()
        }
        onIntent(UiIntent.SetBackupFolder(uri.toString(), name, usable))
        trace.log("picker.backup_folder", "uri" to uri.toString(), "usable" to usable)
        onIntent(UiIntent.AppendLog(logLine("Backup folder: ${name ?: "unnamed"}")))
        return usable
    }

    /**
     * Immediate path (folder already chosen): pin the live attachment now. A serial-less
     * device keeps its existing behavior here — [BackupPin.serial] stays null and the
     * pre-open hook checks generation/key only; the operation's own serial guard still
     * applies. Picker-gated dispatches never take this path.
     */
    fun requestBackupAction(action: BackupFolderAction) {
        val live = attachment.snapshot()
        val device = live.device
        val key = device?.let { UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
        if (key == null) {
            expectedBackupPin = null
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged)))
            return
        }
        expectedBackupPin =
            BackupPin(
                generation = live.generation,
                key = key,
                serial = device?.let { UsbDeviceIdentity.serialNumber(it) }?.trim()?.takeIf {
                    it.isNotEmpty()
                },
            )
        dispatchBackupAction(action)
    }

    /**
     * Picker path: the user chose the folder for [expected]. Verify that dispatch-time live
     * attachment is still that exact target, then pin generation/key/serial from this one
     * snapshot. All three values travel together; the pre-open hook checks the same snapshot
     * the disk is opened for.
     */
    fun requestPinnedBackupAction(
        action: BackupFolderAction,
        expected: PendingBackupFolderAction,
    ) {
        if (action == BackupFolderAction.ChangeFolder) {
            expectedBackupPin = null
            return
        }
        val live = attachment.snapshot()
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
            expectedBackupPin = null
            onIntent(
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
        expectedBackupPin = BackupPin(live.generation, key, serial)
        dispatchBackupAction(action)
    }

    private fun dispatchBackupAction(action: BackupFolderAction) {
        when (action) {
            BackupFolderAction.RescueBackup -> onIntent(UiIntent.StartRescueBackup)
            BackupFolderAction.GptCopy -> onIntent(UiIntent.CaptureGptBackup)
            BackupFolderAction.ChangeFolder -> Unit
        }
    }

    /**
     * Pre-open hook: [pinned] is the same attachment snapshot `openRawDisk` opens, so the
     * device checked here is exactly the device that would be opened. Consumes the pin and
     * fails closed on any mismatch. A null [BackupPin.serial] means the immediate path
     * deliberately keeps the old serial-less behavior; picker dispatches always pin one.
     */
    private fun requirePinnedBackupAttachment(pinned: AttachmentState.Snapshot<UsbDevice>) {
        val expected = expectedBackupPin
        expectedBackupPin = null
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

    fun onIntent(intent: UiIntent) {
        val previousTab = state.tab
        if (intent is UiIntent.SetLanguage) {
            languagePrefs.edit().putString(LANGUAGE_KEY, intent.language.name).apply()
        }
        if (intent is UiIntent.ToggleTheme) {
            // The reducer flips the same value; persisting the next state keeps a restart
            // on the theme the user just chose.
            languagePrefs.edit().putBoolean(THEME_KEY, !state.darkTheme).apply()
        }
        val filesDir = getApplication<Application>().filesDir
        // Surviving UI staged path after process death is not session-owned — clear label only.
        val staleStaged = state.stagedRawImagePath
        if (staleStaged != null && !ImageStaging.isManaged(filesDir, staleStaged)) {
            state = state.copy(
                stagedRawImagePath = null,
                stagedRawImageLabel = null,
                statusMessage = UiMessage.Key(UiMessageKey.StaleStagedImage),
            )
        }
        var stagingMutationFailed = false
        fun deleteOwnedOrReport(path: String?): Boolean {
            return runCatching { ImageStaging.deleteIfManaged(filesDir, path) }
                .onFailure { err ->
                    stagingMutationFailed = true
                    state = state.copy(
                        statusMessage = UiMessage.Key(
                            UiMessageKey.CannotDeleteStaging,
                            listOf(err.message ?: err.javaClass.simpleName),
                        ),
                    )
                }
                .getOrDefault(false)
        }
        when (intent) {
            is UiIntent.ClearStagedRawImage -> if (intent.deleteFile && !state.isRunning && state.operation?.state != OperationState.Queued && state.pendingConfirmation == null) deleteOwnedOrReport(state.stagedRawImagePath)
            is UiIntent.SetStagedRawImage -> if (!state.isRunning && state.operation?.state != OperationState.Queued && state.pendingConfirmation == null) {
                val previous = state.stagedRawImagePath
                if (previous != null && previous != intent.path) deleteOwnedOrReport(previous)
            }
            is UiIntent.SetImage -> if (intent.uri != null && state.actionsEnabled) deleteOwnedOrReport(state.stagedRawImagePath)
            else -> Unit
        }
        if (stagingMutationFailed) return
        val preserveNoDeviceOperation =
            intent is UiIntent.DeviceChanged &&
                intent.device == null &&
                (intent.presence == DevicePresence.Absent || intent.presence == DevicePresence.Disconnected) &&
                state.operation?.let { operationGenerations[it.token] == attachGeneration } == true
        val result = UiReducer.reduce(state, intent)
        val adjustedResult =
            if (preserveNoDeviceOperation && result.state.operation == null) {
                result.copy(
                    state = result.state.copy(
                        operation = state.operation,
                        statusMessage = state.statusMessage,
                    ),
                )
            } else {
                result
            }
        if (intent is UiIntent.OperationBegan) {
            operationGenerations[intent.progress.token] = attachGeneration
        }
        if (intent is UiIntent.CancelWindowsIsoImport) {
            // Best-effort cancel the SAF read and disown its ticket; the reducer already
            // released the SAF guard, so a stuck provider cannot block the next import.
            cancelActiveInspection(cancelUsb = false)
        }
        if (intent is UiIntent.CancelOperation) {
            trace.log(
                "operation.cancel.request",
                "device" to deviceKey(attachedDevice),
                "gen" to attachGeneration,
                "token" to intent.token.value,
            )
        }
        var acceptedOperationIntent = true
        val liveAttachmentGeneration = attachment.snapshot().generation
        if (intent is UiIntent.OperationBegan || intent is UiIntent.OperationUpdated) {
            val current = when (intent) {
                is UiIntent.OperationBegan -> intent.progress
                is UiIntent.OperationUpdated -> intent.progress
                else -> error("unreachable")
            }
            val operationGenerationMatches =
                operationGenerations[current.token]?.let { generation ->
                    val stateDevice = state.device
                    generation == liveAttachmentGeneration &&
                        (stateDevice == null || stateDevice.attachGeneration == generation)
                } == true
            acceptedOperationIntent = operationGenerationMatches &&
                adjustedResult.state !== state && adjustedResult.state.operation == current
            if (acceptedOperationIntent) {
                val previous = state.operation
                if (previous?.state != current.state ||
                    previous?.title != current.title ||
                    previous?.token != current.token
                ) {
                    val detail = current.errorMessage
                        ?: current.resultMessage
                        ?: current.readScanResult?.toLogLine()
                    val message = "${current.label}: ${current.state}" +
                        if (detail.isNullOrBlank()) "" else " — $detail"
                    android.util.Log.i("UsbMultitool", message)
                    trace.log(
                        "operation",
                        "name" to current.label,
                        "state" to current.state,
                        "token" to current.token.value,
                        "bytes" to current.bytesTotal,
                        "device" to deviceKey(attachedDevice),
                        "gen" to liveAttachmentGeneration,
                        "detail" to detail,
                    )
                    state = UiReducer.reduce(state, UiIntent.AppendLog(logLine(message))).state
                }
            }
        }
        if (acceptedOperationIntent &&
            (intent is UiIntent.OperationBegan || intent is UiIntent.OperationUpdated)
        ) {
            state = adjustedResult.state.copy(logLines = state.logLines)
        } else if (intent !is UiIntent.OperationBegan && intent !is UiIntent.OperationUpdated) {
            state = adjustedResult.state
        }
        var completionGenerationAccepted = false
        val completionResult =
            (intent as? UiIntent.OperationUpdated)
                ?.takeIf {
                    when (it.progress.state) {
                        OperationState.Completed,
                        OperationState.Cancelled,
                        OperationState.Partial,
                        OperationState.Failed,
                        -> true
                        OperationState.Queued, OperationState.Running -> false
                    }
                }
                ?.let { terminalIntent ->
                    operationGenerations.remove(terminalIntent.progress.token)?.let { generation ->
                        val stateDevice = state.device
                        completionGenerationAccepted =
                            liveAttachmentGeneration == generation &&
                                (stateDevice == null || stateDevice.attachGeneration == generation)
                        UiReducer.reduce(
                            state,
                            UiIntent.OperationCompletionEffects(
                                token = terminalIntent.progress.token,
                                attachmentGeneration = generation,
                                title = terminalIntent.progress.title,
                                state = terminalIntent.progress.state,
                            ),
                        )
                    }
                }
        if (completionResult != null && completionGenerationAccepted) state = completionResult.state
        if (acceptedOperationIntent) {
            adjustedResult.effects.forEach { effect ->
                handleEffect(effect)
            }
        }
        if (completionGenerationAccepted) {
            completionResult?.effects?.forEach { effect -> handleEffect(effect) }
        }
        if (acceptedOperationIntent || completionGenerationAccepted) refreshFilesystemUsage(intent)
        if (state.tab != previousTab) {
            ShellTabPersistence.write(savedStateHandle, state.tab)
        }

    }
    /** Post an intent to the main dispatcher; disk workers must never mutate state directly. */
    private fun onIntentDeferred(intent: UiIntent) {
        scope.launch(Dispatchers.Main.immediate) { onIntent(intent) }
    }

    private fun publishOperationStarted(
        handle: OperationHandle,
        title: OperationTitle,
        token: OperationToken,
        cancelable: Boolean,
    ) {
        scope.launch {
            while (handle.state == usb.operation.OperationState.Queued) {
                delay(PROGRESS_TICK_MS)
            }
            if (handle.state == usb.operation.OperationState.Running && state.operation?.token == token) {
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            title = title,
                            token = token,
                            cancelable = cancelable,
                        ),
                    ),
                )
            }
        }
    }
    fun handleEffect(effect: UiEffect) {
        when (effect) {
            is UiEffect.SubmitDestructive -> {
                val action = effect.action
                val opTitle = OperationTitle.Destructive(action)
                val actionName = opTitle.logLabel
                val uiState = state
                val confirmed = effect.confirmation
                val confirmedFormatId = confirmed.formatId
                var rawWriteOutcome: usb.image.RawWriteResult? = null
                val op = object : DiskOperation(name = actionName) {
                    /** Concrete outcome surfaced instead of the bare "Success" label. */
                    @Volatile var result: String? = null

                    override suspend fun run() {
                        // Close the FAT session, revalidate the confirmed attachment against
                        // the live snapshot, and take the exclusive USB claim in one step.
                        val raw = openRawDisk { live ->
                            require(live.generation == confirmed.identity.attachGeneration) {
                                "USB attach generation changed"
                            }
                            live.device ?: throw usb.device.DiskException.Disconnected()
                        } ?: throw AttachmentChangedException()
                        val dev = raw.device
                        val disk = raw.disk
                        try {
                            // Confirmation uses DeviceIdentity.identity (label#serial). RESTORE
                            // resume also requires the checkpoint disk identity to match the
                            // live stick (serial required) plus matching geometry.
                            val identity = confirmed.identity.identity
                            require(confirmed.identity.attachGeneration == attachGeneration) { "USB attach generation changed" }
                            require(confirmed.identity.capacityBytes == disk.geometry.capacityBytes) { "confirmed capacity does not match live device" }
                            val confirmation = usb.diagnostics.DestructiveConfirmation(
                                identity = identity,
                                capacityBytes = confirmed.identity.capacityBytes,
                            )
                            if (action == DestructiveAction.RESCUE_RESTORE) {
                                val serial = uiState.device?.serialNumber?.trim().orEmpty()
                                require(serial.isNotEmpty()) {
                                    "USB serial unavailable; refuse rescue restore without stable identity"
                                }
                                val liveSerial =
                                    runCatching { dev.serialNumber?.trim().orEmpty() }.getOrNull().orEmpty()
                                require(liveSerial.isNotEmpty() && liveSerial == serial) {
                                    "live USB serial '$liveSerial' does not match confirmed serial '$serial'"
                                }
                            }
                            val table = runCatching { PartitionTable.read(disk) }.getOrNull()
                            if (action == DestructiveAction.RAW_WRITE) {
                                confirmed.diskSectorCount?.let {
                                    require(it == disk.geometry.sectorCount) {
                                        "confirmed sector count $it does not match live ${disk.geometry.sectorCount}"
                                    }
                                }
                                confirmed.diskSectorSize?.let {
                                    require(it == disk.geometry.sectorSize) {
                                        "confirmed sector size $it does not match live ${disk.geometry.sectorSize}"
                                    }
                                }
                            }
                            // Both Long and ULong write overloads — Ventoy uses Long LBA.
                            val partitionDisk =
                                disk.withWriteTracking { mutationAttempted = true }
                            when (action) {
                                DestructiveAction.WIPE -> {
                                    Diagnostics.wipe(
                                        disk = disk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        operation = this,
                                    )
                                }
                                DestructiveAction.GPT_REPAIR -> {
                                    // Moves the backup entries + header to the current disk end,
                                    // fixes backupLba/lastUsable and recomputes both CRCs.
                                    val expected = confirmed.initialScheme
                                        ?: throw IllegalStateException(
                                            "initial scheme not confirmed for GPT repair",
                                        )
                                    val current = table
                                        ?: throw IllegalStateException("no partition table")
                                    if (current.scheme != expected.toTableScheme()) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition scheme changed since the confirmation " +
                                                "(${current.scheme} != ${expected.toTableScheme()})",
                                        )
                                    }
                                    val liveSnapshot = current.partitions.map {
                                        PartitionRef(it.startLba, it.sectorCount)
                                    }
                                    if (liveSnapshot != confirmed.initialPartitions) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition layout changed since the confirmation",
                                        )
                                    }
                                    val repaired = GptBackup.relocateBackup(partitionDisk, disk.geometry.sectorCount)
                                    result = "GPT backup relocated to LBA ${disk.geometry.sectorCount - 1UL}: " +
                                        "${repaired.partitions.size} partitions kept"
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.DELETE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        PartitionWriter.commit(partitionDisk, PartitionEditor.delete(current, index))
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.SET_PARTITION_ROLE -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        val role = confirmed.partitionRole
                                            ?: throw IllegalStateException("partition role missing")
                                        val type = when (role) {
                                            PartitionRole.Data -> PartitionType.BASIC
                                            PartitionRole.Efi -> PartitionType.EFI
                                        }
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.setType(current, index, type),
                                        )
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.SET_PARTITION_BOOTABLE -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        val bootable = confirmed.partitionBootable
                                            ?: throw IllegalStateException("bootable flag missing")
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.setFlags(current, index, bootable = bootable),
                                        )
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.CREATE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val start = confirmed.createStartLba ?: throw IllegalStateException("partition start not confirmed")
                                    val size = confirmed.createSectorCount ?: throw IllegalStateException("partition size not confirmed")
                                    val created = PartitionEditor.create(
                                        current,
                                        size,
                                        PartitionType.BASIC,
                                        start,
                                        name = confirmed.createName,
                                    )
                                    PartitionWriter.commit(partitionDisk, created)
                                    val formatId = confirmed.createFormatId
                                    if (formatId != null) {
                                        val supported = Formatter.requireSupported(formatId)
                                        // Format the exact confirmed range, not a re-probed one.
                                        val partition = VirtualPartition(start, size, partitionDisk)
                                        try {
                                            Formatter.format(
                                                partition,
                                                supported,
                                                label = confirmed.volumeLabel,
                                            )
                                        } catch (formatFailure: Throwable) {
                                            // The entry is already committed: never report "failed"
                                            // as if nothing was created.
                                            result = "Partition created at LBA $start ($size sectors); " +
                                                "formatting as ${FormatCatalog.label(supported)} failed: " +
                                                (formatFailure.message ?: formatFailure.javaClass.simpleName) +
                                                ". The partition exists but is unformatted."
                                            state = usb.operation.OperationState.Partial
                                            failureCause = formatFailure
                                            throw formatFailure
                                        }
                                        result = "Created partition at LBA $start ($size sectors), " +
                                            "formatted as ${FormatCatalog.label(supported)}" +
                                            if (confirmed.volumeLabel.isNotEmpty()) {
                                                ", label '${confirmed.volumeLabel}'"
                                            } else {
                                                ""
                                            }
                                    } else {
                                        result = "Created partition at LBA $start ($size sectors), unformatted"
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RENAME_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val name = confirmed.partitionName
                                        ?: throw IllegalStateException("partition name not confirmed")
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        // An ESP/VTOYEFI rename would drop the name that protects it;
                                        // the EFI write override never lifts this.
                                        require(!isProtectedSystemPartition(target)) {
                                            "${target.name} is a protected system partition and cannot be renamed"
                                        }
                                        val index = current.partitions.indexOf(target)
                                        require(index >= 0) { "confirmed partition geometry missing" }
                                        PartitionWriter.commit(
                                            partitionDisk,
                                            PartitionEditor.rename(current, index, name),
                                        )
                                    }
                                    result = "Renamed partition at LBA ${confirmed.partitionStartLba} to '$name'"
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RENAME_VOLUME_LABEL -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    val label = confirmed.volumeLabel
                                    require(label.isNotEmpty()) { "volume label not confirmed" }
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val partition = VirtualPartition(
                                            startLba = target.startLba,
                                            sectorCount = target.sectorCount,
                                            disk = partitionDisk,
                                        )
                                        val filesystemId = Filesystem.probe(partition)
                                        Formatter.setVolumeLabel(partition, filesystemId, label)
                                        result = "Volume label of ${target.name} changed to '$label' " +
                                            "(${FormatCatalog.label(filesystemId)})"
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.INITIALIZE_PARTITION_TABLE -> {
                                    val target = confirmed.targetScheme
                                        ?: throw IllegalStateException("target scheme not confirmed")
                                    val expected = confirmed.initialScheme
                                        ?: throw IllegalStateException("initial scheme not confirmed")
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    current.requireUnambiguousLayout()
                                    require(!current.gptPrimaryDamaged) {
                                        "GPT primary is damaged; restore a matching GPT backup first"
                                    }
                                    if (current.scheme != expected.toTableScheme()) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition scheme changed since the confirmation " +
                                                "(${current.scheme} != ${expected.toTableScheme()})",
                                        )
                                    }
                                    val liveSnapshot =
                                        current.partitions.map { PartitionRef(it.startLba, it.sectorCount) }
                                    if (liveSnapshot != confirmed.initialPartitions) {
                                        throw usb.diagnostics.ConfirmationMismatchException(
                                            "partition layout changed since the confirmation",
                                        )
                                    }
                                    result = TableInitializer.initialize(
                                        partitionDisk,
                                        current,
                                        target.toTableScheme(),
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.FORMAT_PARTITION -> {
                                    val current = table ?: throw usb.diagnostics.ConfirmationMismatchException("confirmed partition geometry missing")
                                    current.requireUnambiguousLayout()
                                    mutateConfirmedPartition(
                                        current.partitions,
                                        confirmed.partitionStartLba,
                                        confirmed.partitionSectorCount,
                                    ) { target ->
                                        requirePartitionMutationAllowed(
                                            target,
                                            uiState.vtoyEfiWriteStartLba,
                                            uiState.vtoyEfiWriteSectorCount,
                                        )
                                        val partition = VirtualPartition(
                                            startLba = target.startLba,
                                            sectorCount = target.sectorCount,
                                            disk = partitionDisk,
                                        )
                                        // FS pinned in the confirmation payload (effect.formatId).
                                        val formatId = Formatter.requireSupported(
                                            confirmedFormatId
                                                ?: throw IllegalStateException("format confirmation missing filesystem id"),
                                        )
                                        Formatter.format(partition, formatId)
                                    }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.GPT_RESTORE -> {
                                    // Snapshot on confirmation — never a later uiState.backupUri.
                                    val uri = confirmed.gptBackupUri
                                        ?: throw IllegalStateException("no GPT backup URI in confirmation")
                                    require(uiState.backupUri == null || uiState.backupUri == uri) {
                                        "GPT backup URI changed after confirmation"
                                    }
                                    val pSectors = GptBackup.primarySectors(disk.sectorSize)
                                    val bSectors = GptBackup.backupSectors(disk.sectorSize)
                                    val blob = readBackupBlob(uri, GptCriticalBackup.blobSize(
                                        disk,
                                        pSectors,
                                        bSectors,
                                    ))
                                    bytesTotal = blob.size.toLong()
                                    val restored = GptCriticalBackup.restore(
                                        disk = disk,
                                        blob = blob,
                                        primarySectors = pSectors,
                                        backupSectors = bSectors,
                                        operation = this,
                                    )
                                    report(blob.size.toLong())
                                    result = "Restored ${restored.sectorsRestored} GPT critical " +
                                        "sectors from ${uri.substringAfterLast('/')}: " +
                                        "${restored.partitions} partitions, backup at LBA " +
                                        "${restored.backup.currentLba} verified"
                                }
                                DestructiveAction.RESCUE_RESTORE -> {
                                    val imageUri = confirmed.rescueImageUri
                                        ?: throw IllegalStateException("no rescue image URI in confirmation")
                                    val mapUri = confirmed.rescueMapUri
                                        ?: throw IllegalStateException("no rescue map URI in confirmation")
                                    require(
                                        (uiState.rescueImageUri == null || uiState.rescueImageUri == imageUri) &&
                                            (uiState.rescueMapUri == null || uiState.rescueMapUri == mapUri),
                                    ) {
                                        "rescue source URI changed after confirmation"
                                    }
                                    val mapText = readTextDocument(
                                        mapUri,
                                        maxChars = RescueMap.MAX_ENCODED_CHARS,
                                    )
                                    val loaded = RescueMap.decode(mapText)
                                    val liveId = identity
                                    if (loaded.sectorSize != null && loaded.sectorSize != disk.sectorSize) {
                                        throw IllegalStateException(
                                            "rescue map sectorSize ${loaded.sectorSize} != disk ${disk.sectorSize}",
                                        )
                                    }
                                    require(loaded.sectorCount <= disk.geometry.sectorCount) {
                                        "rescue map covers ${loaded.sectorCount} sectors; disk has ${disk.geometry.sectorCount}"
                                    }
                                    // Policy: partial COPY is refused (NON_TRIED → zeros). RESTORE
                                    // resumes only when the checkpoint disk identity matches the live
                                    // stick (serial required) and geometry matches. After admission,
                                    // every copied range is re-read and rewritten unless it matches
                                    // the current image data. Do not change the image until the
                                    // operation finishes: it is read as-is, a change can leave mixed
                                    // data, and no snapshot/immutability or change detection is
                                    // promised. No image hash is involved.
                                    val liveHasSerial = uiState.device?.hasStableSerial == true
                                    val (restoreMap, prepNote) =
                                        RescueRestorePrep.prepare(
                                            loaded = loaded,
                                            liveIdentity = liveId,
                                            sectorSize = disk.sectorSize,
                                            liveHasSerial = liveHasSerial,
                                        )
                                    require(restoreMap.sectorCount <= (Long.MAX_VALUE.toULong() / disk.sectorSize.toULong())) {
                                        "rescue image size exceeds signed progress range"
                                    }
                                    val expected = (restoreMap.sectorCount * disk.sectorSize.toULong()).toLong()
                                    bytesTotal = expected
                                    val source =
                                        ImageSource.fromContentUri(
                                            Uri.parse(imageUri),
                                            getApplication<Application>().contentResolver,
                                        )
                                    val checkpointFile =
                                        File(
                                            backupDirectory(),
                                            "rescue-restore-${timestamp()}-${System.nanoTime()}.map",
                                        )
                                    val rescueResult = RescueOperations.restore(
                                        source = source,
                                        disk = disk,
                                        map = restoreMap,
                                        identity = liveId,
                                        confirmation = confirmation,
                                        checkpointFile = checkpointFile,
                                        operation = this,
                                    )
                                    result =
                                        "Rescue restore: " +
                                            "${restoreMap.copied.sumOf { it.last - it.first + 1UL }} copied, " +
                                            "${restoreMap.failed.sumOf { it.last - it.first + 1UL }} failed of " +
                                            "${restoreMap.sectorCount} sectors" +
                                            if (restoreMap.partial) {
                                                " (partial — checkpoint ${checkpointFile.name})"
                                            } else {
                                                ""
                                            } +
                                            (prepNote?.let { " — $it" } ?: "")
                                }
                                DestructiveAction.VENTOY_INSTALL -> {
                                    val assets = ventoyAssetsForInstall(confirmed.ventoyRelease)
                                    // First disk.write marks mutationAttempted (partitionDisk wrap).
                                    val settingsWritten =
                                        VentoyOperations.install(
                                            partitionDisk,
                                            assets,
                                            this,
                                            controls = confirmed.ventoyControls,
                                        )
                                    result =
                                        "Ventoy installed" +
                                            settingsSuffix(
                                                confirmed.ventoyControls.size,
                                                settingsWritten,
                                            )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.VENTOY_UPDATE -> {
                                    val assets = ventoyAssetsForInstall(confirmed.ventoyRelease)
                                    val settingsWritten =
                                        VentoyOperations.update(
                                            partitionDisk,
                                            assets,
                                            this,
                                            controls = confirmed.ventoyControls,
                                        )
                                    result =
                                        "Ventoy updated" +
                                            if (confirmed.ventoyControls.isEmpty()) {
                                                "; existing global settings preserved"
                                            } else {
                                                settingsSuffix(
                                                    confirmed.ventoyControls.size,
                                                    settingsWritten,
                                                )
                                            }
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RAW_WRITE -> {
                                    val stagedPath = uiState.stagedRawImagePath
                                    val source =
                                        if (stagedPath != null) {
                                            val filesDir = getApplication<Application>().filesDir
                                            require(ImageStaging.isManaged(filesDir, stagedPath)) {
                                                "refusing raw write: $stagedPath is not a staging file " +
                                                    "created in this app session (re-stage the image)"
                                            }
                                            val file = java.io.File(stagedPath)
                                            require(file.isFile) {
                                                "staged image missing: $stagedPath"
                                            }
                                            ImageSource.fromFile(file)
                                        } else {
                                            val uri = uiState.imageUri
                                                ?: throw IllegalStateException(
                                                    "no image selected for raw write",
                                                )
                                            ImageSource.fromContentUri(
                                                Uri.parse(uri),
                                                getApplication<Application>().contentResolver,
                                            )
                                        }
                                    // confirmation already bound to identity/capacity at dialog time;
                                    // requireMatch inside RawWriter re-checks the live disk.
                                    val range =
                                        usb.partition.RawWriteRange(
                                            startLba = confirmed.rawStartLba
                                                ?: throw IllegalStateException(
                                                    "raw write range start missing from confirmation",
                                                ),
                                            sectorCount = confirmed.rawSectorCount
                                                ?: throw IllegalStateException(
                                                    "raw write range size missing from confirmation",
                                                ),
                                        )
                                    rawWriteOutcome = RawWriter.write(
                                        source = source,
                                        disk = partitionDisk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        overwrite = true,
                                        range = range,
                                        expectedDecodedBytes = confirmed.rawDecodedBytes,
                                        expectedSha256 = confirmed.rawSourceSha256,
                                        operation = this,
                                    )
                                    if (stagedPath != null) {
                                        // Defer delete until terminal Completed (Partial/Failed keep file).
                                        stagedConsumedPath = stagedPath
                                    }
                                }
                                DestructiveAction.VENTOY_LINK_XML ->
                                    throw IllegalStateException("Ventoy XML links use dedicated link flow")
                                // Confirmation-only gates; execution is GenerateAutounattend /
                                // ConfirmDiskLayout effects, never this destructive runner.
                                DestructiveAction.VENTOY_UNATTEND_XML_WRITE,
                                DestructiveAction.VENTOY_UNATTEND_DISK_LAYOUT ->
                                    throw IllegalStateException(
                                        "Unattend XML write/layout uses dedicated generate flow",
                                    )
                            }
                            if (action == DestructiveAction.RAW_WRITE) {
                                // RawWriter already synced the medium after the last write
                                // and folded any sync failure into its readback verdict; a
                                // second sync would only duplicate that checked call.
                                if (state == usb.operation.OperationState.Completed) commitReached = true
                            } else {
                                syncDestructiveDisk(disk)
                            }
                        } finally {
                            disk.close()
                        }
                    }
                }
                val isCooperative = action.cooperative
                val token = nextOperationToken()
                val handle = DiskController.get().submit(op, token)
                if (isCooperative) activeHandle = handle
                activeHandles.add(handle)
                onIntent(
                    UiIntent.OperationBegan(
                        OperationProgress(
                            state = OperationState.Queued,
                            title = opTitle,
                            token = token,
                            cancelable = isCooperative,
                        ),
                    ),
                )
                scope.launch {
                    val ticker = launch {
                        while (true) {
                            delay(LOG_TICK_MS)
                            val done = op.bytesDone
                            val total = op.bytesTotal
                            if (handle.state == usb.operation.OperationState.Running) {
                                onIntent(UiIntent.OperationUpdated(OperationProgress(
                                    state = OperationState.Running,
                                    title = opTitle,
                                    token = token,
                                    bytesDone = done,
                                    bytesTotal = total,
                                    cancelable = isCooperative,
                                )))
                                val progress = if (total > 0) " ${(done.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else " $done bytes"
                                val message = "$actionName$progress"
                                if (state.operation?.token == token) {
                                    android.util.Log.i("UsbMultitool", message)
                                    onIntent(UiIntent.AppendLog(logLine(message)))
                                }
                            }
                        }
                    }
                    try {
                        val outcome = handle.await()
                        ticker.cancel()
                        val mappedState = usb.ui.serviceOperationUiState(outcome)
                        val errorMsg = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = mappedState,
                                    title = opTitle,
                                    token = token,
                                    errorMessage = errorMsg,
                                    resultMessage = op.result,
                                    rawWriteResult = rawWriteOutcome,
                                    cancelable = isCooperative,
                                ),
                            ),
                        )
                        if (mappedState == OperationState.Completed) {
                            val consumed = stagedConsumedPath
                            stagedConsumedPath = null
                            if (consumed != null && state.stagedRawImagePath == consumed) {
                                // Terminal Completed — delete managed staging + clear UI.
                                onIntent(UiIntent.ClearStagedRawImage(deleteFile = true))
                            }
                        } else {
                            // Partial/Failed/Cancelled: keep staging for retry; forget consume mark.
                            stagedConsumedPath = null
                        }
                        when (action) {
                            DestructiveAction.DELETE_PARTITION,
                            DestructiveAction.CREATE_PARTITION,
                            DestructiveAction.FORMAT_PARTITION,
                            DestructiveAction.GPT_REPAIR,
                            DestructiveAction.GPT_RESTORE,
                            DestructiveAction.RESCUE_RESTORE,
                            DestructiveAction.VENTOY_INSTALL,
                            DestructiveAction.VENTOY_UPDATE,
                            DestructiveAction.SET_PARTITION_ROLE,
                            DestructiveAction.SET_PARTITION_BOOTABLE,
                            DestructiveAction.RENAME_PARTITION,
                            DestructiveAction.RENAME_VOLUME_LABEL,
                            DestructiveAction.INITIALIZE_PARTITION_TABLE,
                            DestructiveAction.WIPE,
                            DestructiveAction.RAW_WRITE -> refreshPartitions()
                            else -> Unit
                        }
                    } finally {
                        ticker.cancel()
                        if (activeHandle === handle) activeHandle = null
                        activeHandles.remove(handle)
                    }
                }
            }
            UiEffect.ReadScan ->
                submitReadOnlyDiskOperation(OperationTitle.ReadScan) { disk, operation, sink ->
                    val report = Diagnostics.readScan(disk, operation = operation)
                    sink.readScanResult = ReadScanOutcome.from(report, disk.geometry.sectorCount)
                    // UI formats from sink; empty resultMessage avoids frozen language string.
                    ""
                }

            is UiEffect.RangeProbe -> {
                val start = effect.startLba
                val count = effect.sectorCount
                val device = state.device
                val attachment =
                    device?.let {
                        usb.image.RangeProbeAttachment(it.identity, it.attachGeneration)
                    }
                submitReadOnlyDiskOperation(OperationTitle.CheckRawRange) { disk, _, _ ->
                    val result =
                        usb.image.RangeProbe.sample(
                            disk = disk,
                            range = usb.partition.RawWriteRange(start, count),
                            attachment = attachment,
                            requestId = effect.probeRequestId,
                        )
                    onIntentDeferred(UiIntent.RangeProbeResulted(result))
                    "Range probe: ${result.finding}, " +
                        "${result.sampledSectors.size} sector(s) sampled"
                }
            }

            UiEffect.RescueBackup -> {
                var prepared: SafPositionalImage? = null
                lateinit var preparedTarget: SafArchiveTarget
                var preparedFolder: SafBackupFolder? = null
                var preparedArchive: Uri? = null
                var preparedImage: Uri? = null
                submitReadOnlyDiskOperation(
                    OperationTitle.RescueBackup,
                    beforeOpen = { snapshot ->
                        requirePinnedBackupAttachment(snapshot)
                        val folder = requireBackupFolder()
                        preparedFolder = folder
                        val folderName = BackupArchive.folderName("${timestamp()}-${System.nanoTime()}")
                        val archive = folder.createExclusiveSubfolder(folderName)
                        preparedArchive = archive
                        try {
                            val imageUri = folder.createExclusiveFile(
                                archive,
                                BackupArchive.IMAGE_NAME,
                                "application/octet-stream",
                            )
                            preparedImage = imageUri
                            val image = folder.openPositionalImage(imageUri)
                            prepared = image
                            preparedTarget = SafArchiveTarget(
                                image,
                                usb.diagnostics.CheckpointPublisher(
                                    store = usb.saf.SafCheckpointStore(folder, archive),
                                    baseName = BackupArchive.MAP_NAME,
                                ),
                            )
                        } catch (failure: Throwable) {
                            runCatching { prepared?.close() }.onFailure(failure::addSuppressed)
                            failure.addSuppressed(
                                IOException(
                                    "SAF backup retained after setup failure: " +
                                        "$archive${preparedImage?.let { ", $it" } ?: ""}",
                                ),
                            )
                            throw failure
                        }
                    },
                    onOpenFailure = { runCatching { prepared?.close() } },
                ) { disk, operation, _ ->
                    val folder = requireNotNull(preparedFolder)
                    val archiveUri = requireNotNull(preparedArchive)
                    try {
                        val sectorSize = disk.geometry.sectorSize
                        val deviceId = state.device
                            ?: throw IllegalStateException("no device identity for rescue map")
                        if (!deviceId.hasStableSerial) {
                            throw IllegalStateException(
                                "USB serial unavailable; refuse rescue map without stable identity " +
                                    "(resume and backup maps would be ambiguous across same-model sticks)",
                            )
                        }
                        val need = usb.diagnostics.RescueRangePolicy.requireSignedBytes(
                            disk.geometry.sectorCount,
                            disk.geometry.sectorSize,
                        )
                        operation.bytesTotal = need
                        folder.availableBytes()?.let { usable ->
                            if (usable >= 0 && usable < need) {
                                throw java.io.IOException(
                                    "not enough free space in the selected folder: " +
                                        "usable=$usable, need≈$need (provider estimate)",
                                )
                            }
                        }
                        val copyMap = RescueMap.forCopy(
                            disk.geometry.sectorCount,
                            sectorSize = sectorSize,
                            identity = deviceId.identity,
                        )
                        RescueArchiveOperations.copy(
                            disk = disk,
                            target = preparedTarget,
                            map = copyMap,
                            operation = operation,
                            checkpointTargetBytes = TransferTuning.rescueCheckpointBytes,
                        )
                        val copied = copyMap.copied.sumOf { it.last - it.first + 1UL }
                        val unreadable = copyMap.failed.sumOf { it.last - it.first + 1UL }
                        val note = if (copyMap.partial) {
                            " (partial/incomplete — map kept; re-run backup)"
                        } else ""
                        "Rescue backup: archive ${archiveUri.lastPathSegment}: " +
                            "(${BackupArchive.IMAGE_NAME} + ${BackupArchive.MAP_NAME}): " +
                            "$copied copied, $unreadable unreadable of ${copyMap.sectorCount} sectors$note"
                    } catch (failure: Throwable) {
                        if (!operation.mutationAttempted) {
                            prepared?.let { runCatching { it.close() }.onFailure(failure::addSuppressed) }
                            failure.addSuppressed(
                                java.io.IOException(
                                    "SAF backup retained after failure: " +
                                        "$archiveUri${preparedImage?.let { ", $it" } ?: ""}",
                                ),
                            )
                        }
                        throw failure
                    } finally {
                        runCatching { prepared?.close() }
                    }
                }
            }

            UiEffect.CaptureGptBackup ->
                submitReadOnlyDiskOperation(
                    OperationTitle.GptCriticalBackup,
                    beforeOpen = ::requirePinnedBackupAttachment,
                ) { disk, operation, _ ->
                    val folder = requireBackupFolder()
                    val blob = GptBackup.backupCriticalSectors(disk)
                    val info = GptCriticalBackup.inspectCaptured(
                        blob = blob,
                        disk = disk,
                        primarySectors = GptBackup.primarySectors(disk.sectorSize),
                        backupSectors = GptBackup.backupSectors(disk.sectorSize),
                    )
                    val name = BackupArchive.gptFileName("${timestamp()}-${System.nanoTime()}")
                    val fileUri = folder.createRootFile(name, "application/octet-stream")
                    val output = folder.openSyncedOutput(fileUri)
                    var touched = false
                    var closed = false
                    try {
                        operation.bytesTotal = blob.size.toLong()
                        output.output.write(blob)
                        touched = true
                        operation.mutationAttempted = true
                        output.sync()
                        operation.report(blob.size.toLong())
                        output.close()
                        closed = true
                    } catch (failure: Throwable) {
                        if (touched) {
                            when (operation.state) {
                                is usb.operation.OperationState.Partial,
                                is usb.operation.OperationState.Failed -> Unit
                                else -> {
                                    operation.state = usb.operation.OperationState.Partial
                                    if (operation.failureCause == null) {
                                        operation.failureCause = failure
                                    }
                                }
                            }
                        }
                        throw failure
                    } finally {
                        if (!closed) runCatching { output.close() }
                    }
                    "GPT critical backup saved: $name (${blob.size} bytes, " +
                        "${info.partitions} partitions, backup header at LBA " +
                        "${info.backup.currentLba})"
                }

            is UiEffect.CancelOperation -> {
                val current = state.operation
                if (current?.token == effect.token &&
                    current.cancelable &&
                    (current.state == OperationState.Running || current.state == OperationState.Queued)
                ) {
                    activeHandles.firstOrNull { it.token == effect.token }?.cancel()
                }
            }
            is UiEffect.LoadFiles -> {
                listDirectory(effect.path)
            }
            is UiEffect.LoadFilesystemUsage -> {
                measureFilesystemUsage(
                    effect.partitionId,
                    effect.usageKey,
                    effect.usageGeneration,
                )
            }
            is UiEffect.CreateFolder -> {
                createFolder(effect.path, effect.name)
            }
            is UiEffect.DeleteEntry -> {
                deleteEntry(effect.path, effect.name)
            }
            is UiEffect.RenameEntry -> {
                renameEntry(effect.path, effect.name, effect.newName, effect.binding)
            }
            is UiEffect.UploadFile -> {
                uploadFile(effect.path, effect.uri)
            }
            is UiEffect.DownloadFile -> {
                downloadFile(effect.binding, effect.uri)
            }
            is UiEffect.StageUsbImageForRawWrite -> {
                val binding = effect.binding
                if (binding != null) {
                    stageUsbImageForRawWrite(effect.path, effect.name, binding)
                } else {
                    onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.CannotStage)))
                }
            }

            is UiEffect.LoadRawSources -> {
                loadRawSourceCandidates(effect.binding, effect.requestGeneration)
            }

            UiEffect.CancelRawSources -> {
                rawSourceHandle?.cancel()
                rawSourceHandle = null
            }

            is UiEffect.MeasureRawSource -> measureRawSource(effect)

            UiEffect.CancelRawSourceMeasure -> cancelRawMeasurement()

            is UiEffect.LoadWindowsIsoCandidates -> {
                loadWindowsImageCandidates(effect.requestGeneration)
            }

            is UiEffect.InspectIsoImport -> {
                inspectImageImport(
                    effect.uri,
                    effect.requestGeneration,
                    effect.importKind,
                    effect.importDirectory,
                    effect.pickSessionId,
                )
            }

            UiEffect.CancelIsoImportInspection -> cancelActiveInspection(cancelUsb = false)

            is UiEffect.LaunchWindowsImport,
            is UiEffect.CancelPendingWindowsImport -> {
                _pickerEvents.trySend(effect)
            }

            is UiEffect.ImportIso -> {
                importImage(effect.isoImport, effect.overwrite)
            }

            is UiEffect.LinkXml -> {
                val imagePaths = effect.imagePaths
                if (imagePaths.isEmpty()) {
                    val token = nextOperationToken()
                    onIntent(
                        UiIntent.OperationBegan(
                            OperationProgress(
                                state = OperationState.Queued,
                                title = OperationTitle.AutounattendXmlLink,
                                token = token,
                                cancelable = false,
                            ),
                        ),
                    )
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                title = OperationTitle.AutounattendXmlLink,
                                token = token,
                                errorMessage = "No image selected for the Windows setup link",
                            ),
                        ),
                    )
                    return
                }
                val primaryImage = imagePaths.first()
                val confirmed = effect.confirmation
                val operationGeneration = attachGeneration
                val deviceIdentity = confirmed.identity.identity
                val linkTitle = OperationTitle.AutounattendXmlLink
                val op = object : DiskOperation(name = linkTitle.logLabel) {
                    @Volatile var result: String? = null

                    override suspend fun run() {
                        val raw = openRawDisk { live ->
                            require(live.generation == confirmed.identity.attachGeneration) {
                                "USB attach generation changed"
                            }
                            live.device ?: throw DiskException.Disconnected()
                        } ?: throw AttachmentChangedException()
                        val disk = raw.disk
                        val dev = raw.device
                        try {
                            require(disk.geometry.capacityBytes == confirmed.identity.capacityBytes) { "confirmed capacity does not match live device" }
                            // Trust boundary: serial read must not swallow SecurityException into label-only.
                            val liveIdentity = UsbDeviceIdentity.identityStringFailClosed(dev)
                            require(liveIdentity == deviceIdentity) { "confirmed identity does not match live USB" }
                            val linked = VentoySetup.linkImages(
                                disk = disk,
                                imagePaths = imagePaths,
                                templatePath = effect.templatePath
                                    ?: throw IllegalStateException(
                                        "Select an existing XML template before linking",
                                    ),
                                overwrite = effect.linkOverwrite,
                                allowDestructiveTemplate = effect.allowDestructiveTemplate,
                                expectedTemplateSha256 = confirmed.templateSha256,
                                onMutation = { this.mutationAttempted = true },
                            )
                            if (mutationAttempted) commitReached = true
                            result = "Linked ${imagePaths.size} image(s) to ${linked.templatePath}"
                        } finally {
                            disk.close()
                        }
                    }
                }
                val token = nextOperationToken()
                val handle = DiskController.get().submit(op, token)
                activeHandles.add(handle)
                onIntent(
                    UiIntent.OperationBegan(
                        OperationProgress(
                            state = OperationState.Queued,
                            title = OperationTitle.AutounattendXmlLink,
                            token = token,
                            cancelable = false,
                        ),
                    ),
                )
                publishOperationStarted(
                    handle,
                    OperationTitle.AutounattendXmlLink,
                    token,
                    cancelable = false,
                )
                scope.launch {
                    try {
                        val outcome = handle.await()
                        val mappedState = usb.ui.serviceOperationUiState(outcome)
                        val failure = (outcome as? usb.operation.OperationState.Failed)?.cause
                        val errorMsg = usb.ui.serviceOperationErrorMessage(outcome)
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = mappedState,
                                    title = OperationTitle.AutounattendXmlLink,
                                    token = token,
                                    errorMessage = errorMsg,
                                    resultMessage = op.result,
                                    cancelable = false,
                                ),
                            ),
                        )
                        var overwriteCause: Throwable? = failure
                        while (overwriteCause != null && overwriteCause !is usb.ventoy.VentoyOverwriteRequiredException) {
                            overwriteCause = overwriteCause.cause
                        }
                        if (overwriteCause is usb.ventoy.VentoyOverwriteRequiredException &&
                            state.operation?.token == token &&
                            attachmentLive(operationGeneration) &&
                            state.device?.identity == deviceIdentity
                        ) {
                            onIntent(
                                UiIntent.LinkOverwriteAvailable(
                                    primaryImage,
                                    imagePaths,
                                    effect.templatePath,
                                ),
                            )
                        }
                        if (mappedState == OperationState.Completed && state.operation?.token == token) {
                            onIntent(UiIntent.LoadWindowsImageLinks)
                        }
                    } finally {
                        activeHandles.remove(handle)
                    }
                }
            }

            is UiEffect.LoadWindowsLinks -> {
                loadWindowsImageLinks(effect.requestGeneration)
            }

            is UiEffect.UnlinkXml -> {
                if (effect.imagePaths.isNotEmpty()) unlinkWindowsImages(effect.imagePaths)
            }

            UiEffect.SaveWindowsControls -> saveWindowsControls()

            is UiEffect.LoadAutounattend ->
                loadAutounattend(effect.xmlName, effect.requestGeneration)

            is UiEffect.LoadAutounattendCandidates ->
                loadAutounattendCandidates(effect.xmlName, effect.requestGeneration)

            is UiEffect.DeleteBatch -> deleteBatch(effect.selection)
            is UiEffect.DownloadBatch -> downloadBatch(effect.selection, effect.treeUri)
            is UiEffect.UploadBatchDocs -> uploadBatchDocs(effect)
            is UiEffect.ContinueFilesBatch -> continueFilesBatch(effect.decision)
            is UiEffect.GenerateAutounattend -> generateAutounattend(effect)

            is UiEffect.WriteAutounattendRaw -> writeAutounattendRaw(effect)
            is UiEffect.CheckVentoyUpstream -> checkVentoyUpstream(effect.requestToken)
            is UiEffect.DownloadVentoyRelease ->
                downloadVentoyRelease(effect.requestToken, effect.upstreamVersion)
            is UiEffect.LoadVentoyGlobalSettings ->
                loadVentoyGlobalSettings(effect.requestToken, effect.source)

            is UiEffect.LoadVentoyFolders ->
                loadVentoyFolders(effect.requestToken, effect.path)

            is UiEffect.ApplyVentoyControls -> applyVentoyControls(effect)

            is UiEffect.BindAutounattend -> {
                if (effect.imagePaths.isNotEmpty()) {
                    bindAutounattend(
                        effect.xmlName,
                        effect.imagePaths,
                        effect.allowDestructiveTemplate,
                        effect.expectedTemplateSha256,
                    )
                }
            }

            is UiEffect.UnbindAutounattend -> {
                if (effect.imagePaths.isNotEmpty()) {
                    unbindAutounattend(effect.xmlName, effect.imagePaths.first())
                }
            }
            UiEffect.RunSelfTest -> {
                runSelfTest()
            }
        }
    }
    private fun runSelfTest() {
        if (state.operation?.state == OperationState.Queued || state.operation?.state == OperationState.Running) return
        val dev = attachedDevice
        val mgr = usbManager
        if (dev != null && mgr != null && state.presence == DevicePresence.Ready) {
            submitReadOnlyDiskOperation(OperationTitle.SelfTest, cancelable = false) { disk, _, _ ->
                val report = usb.diagnostics.SelfTestRunner.runAll(disk)
                check(report.allPassed) { report.summary() }
                report.summary()
            }
        } else {
            val token = nextOperationToken()
            val selfTestGeneration = attachment.snapshot().generation
            scope.launch {
                onIntent(
                    UiIntent.OperationBegan(
                        OperationProgress(
                            state = OperationState.Queued,
                            title = OperationTitle.SelfTestRunning,
                            token = token,
                            cancelable = false,
                        ),
                    ),
                )
                try {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Running,
                                title = OperationTitle.SelfTest,
                                token = token,
                                cancelable = false,
                            ),
                        ),
                    )
                    val report = withContext(Dispatchers.Default) {
                        usb.diagnostics.SelfTestRunner.runAll(disk = null)
                    }
                    val summary = report.summary()
                    val targetState = if (report.allPassed) OperationState.Completed else OperationState.Failed
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = targetState,
                                title = OperationTitle.SelfTest,
                                token = token,
                                resultMessage = if (report.allPassed) summary else null,
                                errorMessage = if (!report.allPassed) summary else null,
                                cancelable = false,
                            ),
                        ),
                    )
                    if (state.operation?.token == token &&
                        attachment.snapshot().generation == selfTestGeneration
                    ) onIntent(UiIntent.Status(UiMessage.Raw(summary)))
                } catch (t: Throwable) {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                title = OperationTitle.SelfTest,
                                token = token,
                                errorMessage = t.message ?: "Self-test failed",
                                cancelable = false,
                            ),
                        ),
                    )
                }
            }
        }
    }

    fun onSafImagePicked(uri: Uri) {
        trace.log("picker.image", "uri" to uri.toString())
        val resolver = getApplication<Application>().contentResolver
        val (name, size) = contentInfo(resolver, uri)
        onIntent(UiIntent.AppendLog(logLine("Image picked: ${name ?: "unnamed"}")))
        onIntent(UiIntent.SetImage(uri.toString(), name, size))
    }

    /**
     * Read-only inspection of the picked GPT backup, then one Set intent carrying the
     * result. The selected document is never modified; the stream is bounded before any
     * byte reaches validation. A newer pick bumps the generation, so a slow older result
     * cannot replace the current selection or its summary.
     */
    fun onGptBackupPicked(uri: Uri) {
        val target = uri.toString()
        trace.log("picker.gpt_backup", "uri" to target)
        val generation = gptPickGeneration.incrementAndGet()
        val resolver = getApplication<Application>().contentResolver
        val diskSectorSize =
            state.diskSectorSize.takeIf { state.presence == DevicePresence.Ready && state.device != null }
        scope.launch(Dispatchers.IO) {
            val (name, size) = contentInfo(resolver, uri)
            val inspection =
                try {
                    val input = resolver.openInputStream(uri)
                        ?: throw IllegalStateException("cannot open $target for reading")
                    val blob = input.use { RestoreSourceInspector.readBounded(it) }
                    RestoreSourceInspector.inspectGpt(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        blob = blob,
                        diskSectorSize = diskSectorSize,
                    )
                } catch (failure: Exception) {
                    usb.ui.GptBackupInspection(
                        uri = target,
                        name = name,
                        sizeBytes = size,
                        error = failure.message ?: failure.javaClass.simpleName,
                    )
                }
            withContext(Dispatchers.Main.immediate) {
                if (generation == gptPickGeneration.get()) {
                    onIntent(UiIntent.AppendLog(logLine("GPT backup picked: ${name ?: "unnamed"}")))
                    onIntent(UiIntent.SetBackupFile(target, inspection, generation))
                    if (inspection.error == null &&
                        inspection.summary != null &&
                        state.actionsEnabled
                    ) {
                        // The chosen file already passed bounded validation: show the
                        // destructive warning now instead of demanding another tap.
                        onIntent(
                            UiIntent.RequestDestructive(usb.ui.DestructiveAction.GPT_RESTORE),
                        )
                    }
                }
            }
        }
    }

    private fun classifyRescueImage(
        resolver: ContentResolver,
        uri: Uri,
        name: String?,
    ): usb.image.ImageFormat? =
        runCatching {
            resolver.openInputStream(uri)?.use { input ->
                val header = ByteArray(6)
                var read = 0
                while (read < header.size) {
                    val n = input.read(header, read, header.size - read)
                    if (n <= 0) break
                    read += n
                }
                usb.image.classifyImageContent(name, header.copyOf(read))
            }
        }.getOrNull()

    /**
     * A restore folder is the unit: exactly one `.img` and one `.map`. Listing, map decode
     * and image metadata happen off the main thread; a newer folder pick invalidates an
     * older result. On success the existing destructive confirmation opens with the same
     * inspection types as the old per-file pickers.
     */
    fun onRescueArchiveFolderPicked(uri: Uri) {
        trace.log("picker.rescue_folder", "uri" to uri.toString())
        onIntent(UiIntent.AppendLog(logLine("Restore folder picked")))
        val generation = rescueArchivePickGeneration.incrementAndGet()
        val resolver = getApplication<Application>().contentResolver
        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.CheckingArchive)))
        scope.launch(Dispatchers.IO) {
            var invalid: BackupArchive.Selection.Invalid? = null
            var imageUri: String? = null
            var mapUri: String? = null
            var imageInspection: usb.ui.RescueImageInspection? = null
            var mapInspection: usb.ui.RescueMapInspection? = null
            var failureMessage: String? = null
            try {
                val folder = SafBackupFolder(resolver, uri)
                val entries = folder.listRootFiles().map {
                    BackupArchive.Entry(
                        name = it.name,
                        uri = it.uri.toString(),
                        isDirectory = it.isDirectory,
                        sizeBytes = it.sizeBytes,
                    )
                }
                when (val selection = BackupArchive.select(entries)) {
                    is BackupArchive.Selection.Invalid -> invalid = selection
                    is BackupArchive.Selection.Ready -> {
                        val pair = selection.pair
                        imageUri = pair.image.uri
                        val imageFormat = classifyRescueImage(
                            resolver,
                            Uri.parse(pair.image.uri),
                            pair.image.name,
                        )
                        imageInspection = RestoreSourceInspector.inspectImage(
                            uri = pair.image.uri,
                            name = pair.image.name,
                            sizeBytes = pair.image.sizeBytes,
                            format = imageFormat,
                        )
                        val imageBytes = pair.image.sizeBytes
                        val rejections = mutableListOf<String>()
                        // Canonical first, then newest generation. A truncated/older map
                        // never hides a valid one; a partial COPY is not chosen.
                        for (candidate in pair.maps) {
                            val inspection = try {
                                RestoreSourceInspector.inspectMap(
                                    uri = candidate.uri,
                                    name = candidate.name,
                                    sizeBytes = candidate.sizeBytes,
                                    text = folder.readText(
                                        Uri.parse(candidate.uri),
                                        RescueMap.MAX_ENCODED_CHARS,
                                    ),
                                )
                            } catch (failure: Throwable) {
                                rejections += "${candidate.name}: " +
                                    (failure.message ?: failure.javaClass.simpleName)
                                continue
                            }
                            val summary = inspection.summary
                            if (inspection.error != null || summary == null) {
                                rejections += "${candidate.name}: " +
                                    (inspection.error ?: "map details unavailable")
                                continue
                            }
                            if (summary.kind == usb.diagnostics.MapKind.COPY &&
                                summary.nonTriedSectors > 0UL
                            ) {
                                rejections += "${candidate.name}: incomplete checkpoint " +
                                    "(${summary.nonTriedSectors} nontried sectors)"
                                continue
                            }
                            val liveSectorSize = state.diskSectorSize.takeIf { it > 0 }
                            if (summary.sectorSize != null && liveSectorSize != null &&
                                summary.sectorSize != liveSectorSize
                            ) {
                                rejections += "${candidate.name}: sector size " +
                                    "${summary.sectorSize} != $liveSectorSize"
                                continue
                            }
                            if (liveSectorSize != null) {
                                val liveSectors = state.device?.capacityBytes?.let {
                                    it / liveSectorSize.toULong()
                                }
                                if (liveSectors != null && summary.sectorCount > liveSectors) {
                                    rejections += "${candidate.name}: covers " +
                                        "${summary.sectorCount} > $liveSectors sectors"
                                    continue
                                }
                            }
                            val sectorSize = summary.sectorSize
                                ?: state.diskSectorSize.takeIf { it > 0 }
                            if (sectorSize != null) {
                                when (
                                    usb.ui.rescueImageLengthIssue(
                                        imageBytes,
                                        summary.sectorCount,
                                        sectorSize,
                                        imageFormat,
                                    )
                                ) {
                                    usb.ui.RescueImageLengthIssue.SHORT -> {
                                        val expected = BackupArchive.expectedImageBytes(
                                            summary.sectorCount,
                                            sectorSize,
                                        )
                                        rejections += "${candidate.name}: image is " +
                                            "$imageBytes bytes; map needs at least $expected"
                                        continue
                                    }
                                    usb.ui.RescueImageLengthIssue.OVERFLOW -> {
                                        rejections += "${candidate.name}: geometry overflows"
                                        continue
                                    }
                                    null -> Unit
                                }
                            }
                            mapUri = candidate.uri
                            mapInspection = inspection
                            break
                        }
                        if (mapUri == null) {
                            failureMessage = rejections.joinToString("; ").ifEmpty {
                                "no readable map for ${pair.image.name}"
                            }
                        }
                    }
                }
            } catch (failure: Throwable) {
                failureMessage = failure.message ?: failure.javaClass.simpleName
            }
            withContext(Dispatchers.Main.immediate) {
                if (generation != rescueArchivePickGeneration.get()) return@withContext
                val rejection = failureMessage
                if (rejection != null) {
                    onIntent(UiIntent.SetRescueImage(null, null, generation))
                    onIntent(UiIntent.SetRescueMap(null, null, generation))
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(UiMessageKey.ArchiveUnreadable, listOf(rejection)),
                        ),
                    )
                    return@withContext
                }
                invalid?.let {
                    onIntent(UiIntent.SetRescueImage(null, null, generation))
                    onIntent(UiIntent.SetRescueMap(null, null, generation))
                    onIntent(UiIntent.Status(UiReducer.archiveInvalidMessage(it)))
                    return@withContext
                }
                onIntent(UiIntent.SetRescueImage(imageUri, imageInspection, generation))
                onIntent(UiIntent.SetRescueMap(mapUri, mapInspection, generation))
                if (mapInspection?.error == null && state.actionsEnabled) {
                    onIntent(
                        UiIntent.RequestDestructive(usb.ui.DestructiveAction.RESCUE_RESTORE),
                    )
                }
            }
        }
    }

    fun onUsbDeviceAttached(
        device: UsbDevice?,
        hasPermission: Boolean,
        manager: UsbManager? = null,
    ) {
        if (device == null) return
        val key = deviceKey(device)
        val prevKey = deviceKey(attachedDevice)
        val isNewDevice = prevKey != null && prevKey != key
        val sameDevice = prevKey != null && prevKey == key
        if (sameDevice && (state.isRunning || activeHandles.isNotEmpty())) {
            // onStart re-probe must not wipe the known geometry during a running operation.
            return
        }
        if (sameDevice && inspectionOwner.active?.let(::inspectionAttachmentStillHere) == true) {
            // A live phone-import inspection owns this attachment; a same-device re-probe
            // would flip presence to Busy and orphan its Inspected result. A real detach or
            // a different device never reaches this branch.
            return
        }
        if (isNewDevice) {
            cancelActiveInspection(cancelUsb = true)
            usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
            activeHandle = null
        }
        // One volatile publish: the device and its generation become visible together, so
        // no reader can observe the new stick with the previous generation (or the reverse).
        val gen = attachment.publish(device, bumpGeneration = !sameDevice)
        trace.log(
            "usb.attach",
            "device" to key,
            "gen" to gen,
            "same" to sameDevice,
            "permission" to hasPermission,
        )
        // Same-device re-probes (onStart) repeat the trace line but not the journal line.
        if (!sameDevice) {
            onIntent(UiIntent.AppendLog(logLine("USB attached: $key · generation $gen")))
        }
        // Fence after the device switch: any mount still opening for the previous stick was
        // allocated at or below it and is closed below; a mount that can now succeed allocates
        // a larger epoch, so the delayed close never touches this attach's volume.
        val previousFence = filesSession.mountFence()
        if (isNewDevice) {
            scope.launch(Dispatchers.IO) { closeFilesVolumeUpToEpoch(previousFence) }
        }
        DiskController.get().onConnected(key)
        if (manager != null) {
            usbManager = manager
        }
        val mgr = usbManager
        // Stamp the live attach generation on transitional presence so DeviceChanged can
        // keep stick-scoped Ventoy/XML state across Busy → Ready on the same stick.
        val prior = state.device?.takeIf {
            sameDevice && it.identity == UsbDeviceIdentity.of(device).identity
        }
        val identity =
            UsbDeviceIdentity.of(
                device,
                capacityBytes = prior?.capacityBytes ?: 0uL,
                attachGeneration = gen,
            )
        if (!hasPermission || mgr == null) {
            onIntent(
                UiIntent.DeviceChanged(
                    presence = DevicePresence.PermissionRequired,
                    device = identity,
                    partitions = emptyList(),
                    files = emptyList(),
                ),
            )
            return
        }

        onIntent(
            UiIntent.DeviceChanged(
                presence = DevicePresence.Busy,
                device = identity,
                partitions = emptyList(),
                files = emptyList(),
            ),
        )
        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.Probing)))

        val op = object : DiskOperation(name = OperationTitle.ProbeDevice.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == gen) { "USB attach generation changed" }
                    require(deviceKey(live.device) == deviceKey(device)) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    val installedRelease =
                        if (isTransport) {
                            usb.ventoy.VentoyInstalledVersion.Present(null)
                        } else {
                            readInstalledVentoyRelease(disk)
                        }
                    withContext(Dispatchers.Main.immediate) {
                        if (!attachmentIs(gen, device)) {
                            return@withContext
                        }
                        if (this@MainViewModel.state.isRunning) {
                            // Keep the geometry the running operation is using.
                            return@withContext
                        }
                        val probedIdentity =
                            UsbDeviceIdentity.of(
                                device,
                                capacityBytes = capacity,
                                attachGeneration = gen,
                            )
                        onIntent(
                            UiIntent.DeviceChanged(
                                presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                device = if (isTransport) identity else probedIdentity,
                                partitions = partitions,
                                freeRegions = tableResult.getOrNull()?.freeRegions?.map { usb.ui.FreeRegionUi(it.startLba, it.sectorCount) } ?: emptyList(),
                                sectorSize = disk.sectorSize,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                                partitionTableDamaged =
                                    !isTransport && tableResult.getOrNull()?.gptPrimaryDamaged == true,
                                partitionTableAmbiguous =
                                    !isTransport && tableResult.getOrNull()?.hybridAmbiguous == true,
                                partitionScheme = tableResult.getOrNull()?.scheme,
                                rawGeometry =
                                    usb.partition.RawRangeGeometry.from(
                                        tableResult.getOrNull(),
                                        disk.geometry.sectorCount,
                                        disk.geometry.sectorSize,
                                    ),
                            ),
                        )
                        if (!isTransport) {
                            val read = installedRelease
                            onIntent(
                                UiIntent.SetVentoyInstalledRelease(
                                    release = (read as? usb.ventoy.VentoyInstalledVersion.Present)?.release,
                                    attachGeneration = gen,
                                    identity = probedIdentity.identity,
                                    error = (read as? usb.ventoy.VentoyInstalledVersion.ReadFailed)?.message,
                                ),
                            )
                        }

                        tableResult.onSuccess {
                            if (partitions.isEmpty()) {
                                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.NoPartitionsFound)))
                            }
                            // DeviceChanged(Ready) already emits LoadFiles for the retained
                            // path; a second quiet list here would only race the first.
                        }.onFailure { err ->
                            val key = if (isTransport) {
                                UiMessageKey.CannotReadUsb
                            } else {
                                UiMessageKey.CannotReadPartitionTable
                            }
                            onIntent(UiIntent.Status(
                                UiMessage.Key(key, listOf(err.message ?: err.javaClass.simpleName)),
                            ))
                        }
                    }
                    failure?.let { err ->
                        state = usb.operation.OperationState.Failed(err)
                        failureCause = err
                    }
                } finally {
                    try {
                        disk.close()
                    } catch (closeErr: Throwable) {
                        val primary = failureCause
                        if (primary != null) {
                            if (primary !== closeErr) primary.addSuppressed(closeErr)
                        } else {
                            throw closeErr
                        }
                    }
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                when (val outcome = handle.await()) {
                    is usb.operation.OperationState.Failed -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (attachmentIs(gen, device)) {
                                val isTransport =
                                    outcome.cause is DiskException ||
                                        outcome.cause.cause is DiskException
                                // Soft-table path already painted Ready/tableValid=false in run().
                                if (state.presence != DevicePresence.Ready || isTransport) {
                                    onIntent(
                                        UiIntent.DeviceChanged(
                                            presence =
                                                if (isTransport) DevicePresence.Disconnected
                                                else DevicePresence.Ready,
                                            device = if (isTransport) identity else state.device ?: identity,
                                            partitions = if (isTransport) emptyList() else state.partitions,
                                            files = if (isTransport) emptyList() else state.files,
                                            tableValid = false,
                                        ),
                                    )

                                }
                                val key =
                                    if (isTransport) UiMessageKey.CannotOpenUsb
                                    else UiMessageKey.CannotReadPartitionTable
                                onIntent(UiIntent.Status(
                                    UiMessage.Key(
                                        key,
                                        listOf(outcome.cause.message ?: outcome.cause.javaClass.simpleName),
                                    ),
                                ))
                            }
                        }
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (attachmentIs(gen, device)) {
                                onIntent(
                                    UiIntent.DeviceChanged(
                                        presence = DevicePresence.Disconnected,
                                        device = identity,
                                        partitions = emptyList(),
                                        files = emptyList(),
                                    ),
                                )
                                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ProbeCancelled)))
                            }
                        }
                    }
                    else -> Unit
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    /**
     * Real `VENTOY_VERSION` from VTOYEFI, read-only. Absence, FAT/IO failure and
     * cancellation stay distinct; this never walks up to bundled bytes.
     */
    private fun readInstalledVentoyRelease(
        disk: usb.device.BlockDevice,
    ): usb.ventoy.VentoyInstalledVersion =
        try {
            usb.ventoy.classifyVentoyVersion(usb.ventoy.VentoyLayout.probe(disk))
        } catch (cancel: kotlinx.coroutines.CancellationException) {
            throw cancel
        } catch (unsupported: usb.ventoy.UnsupportedSectorSizeException) {
            usb.ventoy.VentoyInstalledVersion.Present(null)
        } catch (failure: Throwable) {
            usb.ventoy.VentoyInstalledVersion.ReadFailed(
                failure.message ?: failure.javaClass.simpleName,
            )
        }

    fun refreshPartitions() {
        val dev = attachedDevice ?: return
        if (usbManager == null) return
        val gen = attachGeneration
        val op = object : DiskOperation(name = OperationTitle.RefreshPartitions.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == gen) { "USB attach generation changed" }
                    require(deviceKey(live.device) == deviceKey(dev)) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    val installedRelease =
                        if (isTransport) {
                            usb.ventoy.VentoyInstalledVersion.Present(null)
                        } else {
                            readInstalledVentoyRelease(disk)
                        }
                    withContext(Dispatchers.Main.immediate) {
                        if (!attachmentIs(gen, dev)) {
                            return@withContext
                        }
                        val identity =
                            UsbDeviceIdentity.of(
                                dev,
                                capacityBytes = capacity,
                                attachGeneration = gen,
                            )
                        onIntent(
                            UiIntent.DeviceChanged(
                                presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                device = identity,
                                partitions = partitions,
                                freeRegions = tableResult.getOrNull()?.freeRegions?.map { usb.ui.FreeRegionUi(it.startLba, it.sectorCount) } ?: emptyList(),
                                sectorSize = disk.sectorSize,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                                partitionTableDamaged =
                                    !isTransport && tableResult.getOrNull()?.gptPrimaryDamaged == true,
                                partitionTableAmbiguous =
                                    !isTransport && tableResult.getOrNull()?.hybridAmbiguous == true,
                                partitionScheme = tableResult.getOrNull()?.scheme,
                                rawGeometry =
                                    usb.partition.RawRangeGeometry.from(
                                        tableResult.getOrNull(),
                                        disk.geometry.sectorCount,
                                        disk.geometry.sectorSize,
                                    ),
                            ),
                        )
                        if (!isTransport) {
                            val read = installedRelease
                            onIntent(
                                UiIntent.SetVentoyInstalledRelease(
                                    release = (read as? usb.ventoy.VentoyInstalledVersion.Present)?.release,
                                    attachGeneration = gen,
                                    identity = identity.identity,
                                    error = (read as? usb.ventoy.VentoyInstalledVersion.ReadFailed)?.message,
                                ),
                            )
                        }

                        tableResult.onFailure { err ->
                            val key = if (isTransport) {
                                UiMessageKey.CannotReadUsb
                            } else {
                                UiMessageKey.CannotReadPartitionTable
                            }
                            onIntent(UiIntent.Status(
                                UiMessage.Key(key, listOf(err.message ?: err.javaClass.simpleName)),
                            ))
                        }
                    }
                    failure?.let { err ->
                        state = usb.operation.OperationState.Failed(err)
                        failureCause = err
                    }
                } finally {
                    try {
                        disk.close()
                    } catch (closeErr: Throwable) {
                        val primary = failureCause
                        if (primary != null) {
                            if (primary !== closeErr) primary.addSuppressed(closeErr)
                        } else {
                            throw closeErr
                        }
                    }
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed) {
                    withContext(Dispatchers.Main.immediate) {
                        if (attachmentIs(gen, dev)) {
                            val isTransport =
                                outcome.cause is DiskException ||
                                    outcome.cause.cause is DiskException
                            onIntent(
                                UiIntent.DeviceChanged(
                                    presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                    device = state.device
                                        ?: UsbDeviceIdentity.of(dev, attachGeneration = gen),
                                    partitions = emptyList(),
                                    files = emptyList(),
                                    tableValid = false,
                                ),
                            )
                            val key = if (isTransport) {
                                UiMessageKey.CannotReadUsb
                            } else {
                                UiMessageKey.CannotReadPartitionTable
                            }
                            onIntent(UiIntent.Status(
                                UiMessage.Key(
                                    key,
                                    listOf(outcome.cause.message ?: outcome.cause.javaClass.simpleName),
                                ),
                            ))
                        }
                    }
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }
    fun onUsbDeviceDetached(device: UsbDevice? = null) {
        val live = attachment.snapshot()
        if (device == null && live.device == null) return
        if (device != null && device.deviceId != live.device?.deviceId) return
        val detachedKey = deviceKey(device ?: live.device)
        val detachedGeneration = attachment.publish(null, bumpGeneration = true)
        if (detachedKey != null) {
            trace.log("usb.detach", "device" to detachedKey, "gen" to detachedGeneration)
            onIntent(
                UiIntent.AppendLog(
                    logLine("USB detached: $detachedKey · generation $detachedGeneration"),
                ),
            )
        }
        lastUsageKey = null
        usageInFlightKey = null
        cancelActiveInspection(cancelUsb = true)
        usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
        // Keep handles registered until their workers finish real close; cancellation is not close.
        activeHandle = null
        // No mount can start while attachedDevice is null, so every mount allocated up to this
        // fence belongs to the detached stick. The close below runs on IO; a new attach that
        // bumps the generation first (pending permission) still cannot hide this stale mount.
        val detachedFence = filesSession.mountFence()
        scope.launch(Dispatchers.IO) { closeFilesVolumeUpToEpoch(detachedFence) }
        DiskController.get().onDisconnected()
        onIntent(UiIntent.DeviceChanged(presence = DevicePresence.Disconnected))
    }

    /**
     * Close the mount allocated at or before [fence], never a newer one. Epoch (not attach
     * generation) identifies the volume, so a new attach before this close runs cannot make
     * it skip the stale mount or close the fresh mount. Same serialization as any files op.
     * A close/sync failure stays latched inside the session; detach has no UI to report to.
     */
    private fun closeFilesVolumeUpToEpoch(fence: Long) {
        if (fence <= 0L) return
        val result = filesSession.withLock { filesSession.closeIfEpochAtMostLocked(fence) }
        trace.log("volume.close.epoch", "fence" to fence, "result" to result)
    }

    /** Drop the mounted volume (and its USB claim) without blocking user code. */
    /**
     * Drop the mounted files volume. Returns a close/sync failure if one occurred.
     * When [reportStatus] is true and the UI is still alive, posts a visible status line
     * (detach/onCleared use false — no crash, no orphaned status after clear).
     */
    private fun closeFilesVolume(reportStatus: Boolean = false): Throwable? {
        val error = filesSession.close()
        // A close failure is latched inside the session; the trace keeps the first cause.
        trace.log("volume.close", "error" to error?.message, "latched" to (error != null))
        if (error != null && reportStatus) {
            post(
                UiIntent.Status(
                    UiMessage.Key(
                        UiMessageKey.VolumeSyncUnconfirmed,
                        listOf(error.message ?: error.javaClass.simpleName),
                    ),
                ),
            )
        }
        return error
    }

    /**
     * Open disk + filesystem for [partitionId]. Called only from [FilesVolumeSession]
     * after any prior volume was closed. Failure path closes the disk claim.
     */
    private fun openMountedFilesVolume(
        partitionId: String,
        writable: Boolean,
        pin: AttachmentPin,
        lease: UsbExclusiveClaim.Lease,
    ): MountedFilesVolume {
        trace.log(
            "volume.mount.start",
            "part" to partitionId,
            "writable" to writable,
            "gen" to pin.attachGeneration,
        )
        val device = attachedDevice ?: throw DiskException.Disconnected()
        val manager = usbManager ?: throw DiskException.Disconnected()
        // Pin check before the USB claim: a paused operation must not open another stick.
        requireAttachment(pin, device)
        val disk = UsbDiskSession.open(manager, device, lease)
        lateinit var mountedPartition: usb.partition.Partition
        val volume = try {
            val index = partitionId.toIntOrNull()
                ?: throw IllegalArgumentException("invalid partition id $partitionId")
            val table = PartitionTable.read(disk)
            // A detach/re-attach can happen during the claim or table read: re-verify the pin
            // against the live attachment before trusting this geometry or partition range.
            requireAttachment(pin, device)
            if (writable) requireWritableMountAllowed(table)
            mountedPartition = table.partitions.getOrNull(index - 1)
                ?: throw IllegalArgumentException("partition $partitionId no longer exists")
            PartitionVolumeSession.open(
                disk,
                mountedPartition.startLba,
                mountedPartition.sectorCount,
                readOnly = !writable,
            )
        } catch (failure: Throwable) {
            // PartitionVolumeSession already closes on mount failure; a second close is
            // idempotent, but if it still throws keep the mount cause and suppress close.
            try {
                disk.close()
            } catch (closeErr: Throwable) {
                if (closeErr !== failure) failure.addSuppressed(closeErr)
            }
            trace.log(
                "volume.mount.fail",
                "part" to partitionId,
                "writable" to writable,
                "error" to (failure.message ?: failure.javaClass.simpleName),
            )
            throw failure
        }
        trace.log("volume.mount.ok", "part" to partitionId, "writable" to writable)
        val mountedFilesystem = when (volume) {
            is usb.fs.FatVolume -> when (volume.kind) {
                usb.fs.FatKind.EXFAT -> usb.fs.FilesystemId.EXFAT
                usb.fs.FatKind.FAT12, usb.fs.FatKind.FAT16, usb.fs.FatKind.FAT32 -> usb.fs.FilesystemId.FAT
                usb.fs.FatKind.UNKNOWN -> null
            }
            is usb.fs.ExtVolume -> usb.fs.FilesystemId.EXT
            is usb.fs.NtfsVolume -> usb.fs.FilesystemId.NTFS
            else -> null
        }
        return MountedFilesVolume(
            partitionId,
            disk,
            volume,
            writable = writable,
            startLba = mountedPartition.startLba,
            sectorCount = mountedPartition.sectorCount,
            sectorSize = disk.sectorSize,
            filesystemId = mountedFilesystem,
        )
    }

    /** Raw disk that owns the USB claim plus the live device it was opened for. */
    private class RawUsbSession(val disk: UsbDisk, val device: UsbDevice)

    /**
     * Close the mounted files volume, revalidate the attachment, and open a raw disk while
     * the exclusive USB claim is held.
     *
     * Close and claim are one files-session critical section, so a usage probe or files
     * operation cannot mount the stick between them. [revalidate] then runs after the close
     * (the umount/sync can take seconds) and before the open, against one atomic attachment
     * snapshot, and returns the device to open or null to abort without opening. The same
     * snapshot is checked once more right after the open: a detach/replug during the USB
     * open closes the fresh disk and throws before the caller can mutate anything.
     *
     * The returned disk releases the claim when it is closed; null means [revalidate]
     * aborted (the claim is already released, nothing was opened).
     */
    private fun openRawDisk(
        revalidate: (AttachmentState.Snapshot<UsbDevice>) -> UsbDevice? = { it.device },
    ): RawUsbSession? =
        filesSession.openAfterCloseClaimed { lease ->
            val pinned = attachment.snapshot()
            val device = revalidate(pinned)
            if (device == null) {
                lease.close()
                return@openAfterCloseClaimed null
            }
            val manager = usbManager ?: throw DiskException.Disconnected()
            val disk =
                try {
                    UsbDiskSession.open(manager, device, lease)
                } catch (failure: Throwable) {
                    lease.close()
                    throw failure
                }
            // Second fence: the open itself can straddle a detach/replug.
            val live = attachment.snapshot()
            if (live.generation != pinned.generation ||
                deviceKey(live.device) != deviceKey(device)
            ) {
                var closeFailure: Throwable? = null
                try {
                    disk.close()
                } catch (t: Throwable) {
                    closeFailure = t
                }
                throw AttachmentChangedException().also { changed ->
                    closeFailure?.let(changed::addSuppressed)
                }
            }
            RawUsbSession(disk, device)
        }

    /** Fail unless [pin] still describes the live attachment opened for [device]. */
    private fun requireAttachment(pin: AttachmentPin, device: UsbDevice) {
        // One snapshot: never compare the pin against a device/generation pair from
        // different publishes.
        val live = attachment.snapshot()
        if (live.generation != pin.attachGeneration ||
            deviceKey(device) != pin.deviceKey ||
            deviceKey(live.device) != pin.deviceKey
        ) {
            throw AttachmentChangedException()
        }
    }

    /** Runs [action] with the partition selected at submit time, or reports why not. */
    private inline fun withPartitionId(action: (String) -> Unit) {
        val partitionId = state.selectedPartitionId
        if (partitionId == null) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SelectPartitionFirst)))
        } else {
            action(partitionId)
        }
    }

    /** Runs [action] with the selected partition, refusing writes it must not take. */
    private inline fun withWritablePartitionId(action: (String) -> Unit) =
        withPartitionId { partitionId ->
            val block = UiReducer.writeBlockMessage(state)
            if (block == null) action(partitionId) else onIntent(UiIntent.Status(block))
        }

    /**
     * Ask for a filesystem-space measurement when the key the space row would render
     * changed, and again after any invalidation — a finished operation, a partition
     * refresh or a resume can clear the snapshot while the key stays identical, and a
     * key-only dedupe would leave the row "unavailable" for good. A request for the same
     * key is not repeated while its answer is still in flight.
     */
    private fun refreshFilesystemUsage(intent: UiIntent) {
        if (intent is UiIntent.FilesystemUsageResulted &&
            intent.generation == state.filesystemUsageGeneration
        ) {
            // The newest request answered (published or dropped as stale): nothing is in
            // flight any more. An older answer must not clear a newer request's marker,
            // or the next intent would ask again for a measurement already on its way.
            usageInFlightKey = null
        }
        val partition = state.selectedPartition()
        val key = partition?.let { state.filesystemUsageKey(it) }
        if (key == null) {
            // No measurable selection: forget the last key so a re-attach asks again.
            lastUsageKey = null
            usageInFlightKey = null
            return
        }
        val finished =
            intent is UiIntent.OperationUpdated &&
                intent.progress.state != OperationState.Running &&
                intent.progress.state != OperationState.Queued
        if (!state.needsFilesystemUsageRequest(key, lastUsageKey, usageInFlightKey, finished)) return
        val result = UiReducer.reduce(state, UiIntent.RequestFilesystemUsage)
        if (result.effects.isEmpty()) return
        lastUsageKey = key
        usageInFlightKey = key
        state = result.state
        result.effects.forEach { effect -> handleEffect(effect) }
    }

    /**
     * Read free/total bytes of the mounted volume on an IO thread while holding the files session lock,
     * so the read-only query cannot race a mount, a mutation or a close. A volume that will
     * not open reports "unavailable" instead of an invented value, and the answer is dropped
     * when the attachment changed since the request.
     */
    private fun measureFilesystemUsage(
        partitionId: String,
        key: FilesystemUsageKey,
        generation: Long,
    ) {
        val live = attachment.snapshot()
        val expectedGeneration = live.generation
        val expectedIdentity = state.device?.identity
        val pin = live.device?.let { AttachmentPin(live.generation, deviceKey(it)!!) }
        scope.launch(Dispatchers.IO) {
            val snapshot =
                filesSession.withLock {
                    if (attachGeneration != expectedGeneration ||
                        attachGeneration != key.attachGeneration ||
                        expectedIdentity == null ||
                        expectedIdentity != key.identity
                    ) {
                        null
                    } else {
                        try {
                            filesSession.filesystemUsageForLocked(pin, partitionId, key)
                        } catch (failure: Throwable) {
                            FilesystemUsageSnapshot(
                                key,
                                FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure),
                            )
                        }
                    }
                } ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                onIntent(UiIntent.FilesystemUsageResulted(generation, snapshot.key, snapshot.usage, snapshot.access))
            }
        }
    }

    /** Re-list the displayed directory from the real volume. */
    fun listDirectory(path: String) = withPartitionId { partitionId ->
        val generation = state.filesListGeneration
        val identity = state.device?.identity
        val attachGen = state.device?.attachGeneration ?: 0L
        val pin = currentAttachmentPin()
        submitFilesOperation(
            title = OperationTitle.ListPath(path),
            listFailure = { message ->
                UiIntent.FilesListFailed(
                    path = path,
                    message = message,
                    generation = generation,
                    identity = identity,
                    partitionId = partitionId,
                    attachGeneration = attachGen,
                )
            },
        ) {
            val entries =
                usb.files.FilesOperations.list(
                    filesSession.volumeForLocked(pin, partitionId),
                    path,
                )
            post(
                UiIntent.FilesListed(
                    path = path,
                    files = entries.map { it.toFileEntry() },
                    generation = generation,
                    identity = identity,
                    partitionId = partitionId,
                    attachGeneration = attachGen,
                ),
            )
        }
    }


    private fun createFolder(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.NewFolder(name),
                onSuccess = {
                    // The terminal mutation already bumped the list generation and asked the
                    // Files tab for one re-list; only the concrete result line is added here.
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(UiMessageKey.CreatedFolder, listOf(target)),
                        ),
                    )
                },
            ) { operation ->
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    operation.mutationAttempted = true
                    volume.mkdir(target)
                    volume.sync()
                    operation.commitReached = true
                }
            }
        }
    }

    private fun deleteEntry(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.DeleteEntry(name),
                onSuccess = {
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(UiMessageKey.DeletedPath, listOf(target)),
                        ),
                    )
                },
            ) { operation ->
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    try {
                        operation.mutationAttempted = true
                        usb.files.FilesOperations.delete(volume, target)
                        operation.commitReached = true
                    } catch (failure: Throwable) {
                        throw userVisibleDeleteError(name, target, failure)
                    }
                }
            }
        }
    }

    private fun deleteBatch(selection: FilesBatchSelection) {
        if (!selection.matches(state)) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return
        }
        withWritablePartitionId { partitionId ->
            if (partitionId != selection.partitionId) {
                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.DeleteBatch(selection.items.size),
                onSuccess = {
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.DeletedPath,
                                listOf("${selection.items.size} entries"),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(selection.matches(state)) { "batch delete target changed" }
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    for (item in selection.items) {
                        if (!operation.isActive()) {
                            throw kotlinx.coroutines.CancellationException("Delete cancelled")
                        }
                        val target = fatChildPath(selection.directory, item.name)
                        try {
                            FilesOperations.deleteRecursively(
                                volume = volume,
                                target = target,
                                operation = operation,
                            )
                        } catch (failure: Throwable) {
                            throw userVisibleDeleteError(item.name, target, failure)
                        }
                    }
                    // Commit only after every selected root deleted; mutationAttempted
                    // from first delete keeps mid-cancel as Partial, not Completed.
                    operation.commitReached = true
                }
            }
        }
    }
    private fun renameEntry(path: String, from: String, to: String, binding: PartitionBinding) {
        val target = fatChildPath(path, from)
        val renamed = fatChildPath(path, to)
        withWritablePartitionId { partitionId ->
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.RenameEntry(from),
                onSuccess = {
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.RenamedPath,
                                listOf(from, to),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(partitionId == binding.partitionId) { "rename target partition changed" }
                require(binding.matches(state)) { "rename target changed" }
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    // The only mutation below; mark it after binding/policy checks and
                    // volume acquisition so a rename or post-rename sync failure is Partial.
                    operation.mutationAttempted = true
                    usb.files.FilesOperations.rename(volume, target, renamed)
                    operation.commitReached = true
                }
            }
        }
    }

    /** Map engine delete failures to a clear status line (nonempty dir, missing, …). */
    private fun userVisibleDeleteError(name: String, target: String, failure: Throwable): Throwable {
        val friendly = FilesOperationPolicy.deleteError(name, target, failure.message.orEmpty())
        return if (friendly != null) IllegalStateException(friendly, failure) else failure
    }

    /** Stream a SAF document into [path] in fixed-size chunks, reporting real bytes. */
    private fun uploadFile(path: String, uri: String) {
        val resolver = getApplication<Application>().contentResolver
        var uploaded: String? = null
        withWritablePartitionId { partitionId ->
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.Upload,
                onSuccess = {
                    val name = uploaded
                    if (name != null) {
                        onIntent(
                            UiIntent.Status(
                                UiMessage.Key(UiMessageKey.UploadedFile, listOf(name, path)),
                            ),
                        )
                    }
                },
            ) { operation ->
                val document = Uri.parse(uri)
                val (reportedName, reportedSize) = contentInfo(resolver, document)
                val rawName = reportedName ?: document.lastPathSegment ?: uri
                val name = FilesOperationPolicy.requireChildName(rawName, uri)
                uploaded = name
                val target = fatChildPath(path, name)
                operation.bytesTotal = reportedSize ?: -1L

                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    operation.mutationAttempted = true
                    FilesOperations.upload(
                        resolver = resolver,
                        uri = document,
                        target = target,
                        volume = volume,
                        operation = operation,
                        chunkBytes = TransferTuning.jniCapBytes,
                        onCleanupFailure = { cleanupError ->
                            // Engine already adds the cleanup failure as suppressed to the
                            // primary upload error; keep a persistent log line as well so the
                            // warning survives the terminal status.
                            val detail = cleanupError.message ?: cleanupError.javaClass.simpleName
                            post(UiIntent.AppendLog(logLine("Upload cleanup failed for $name: $detail")))
                            post(
                                UiIntent.Status(
                                    UiMessage.Key(
                                        UiMessageKey.UploadCleanupFailed,
                                        listOf(name, detail),
                                    ),
                                ),
                            )
                        },
                    )
                    operation.commitReached = true
                }
            }
        }
    }

    /** Copy a volume file into a SAF document chosen by the activity. */

    /**
     * Stream [name] from the mounted USB volume into app filesDir/staging, then release the
     * volume so the user can confirm a destructive raw write on the (same) disk.
     */
    private fun stageUsbImageForRawWrite(path: String, name: String, binding: PartitionBinding) {
        val filesDir = getApplication<Application>().filesDir
        val pin = currentAttachmentPin()
        // Holder: publish SetStaged only after copy+sync+close succeed; onSuccess runs on Main.
        val stagedPathHolder = arrayOfNulls<String>(1)
        val stagedPublished = java.util.concurrent.atomic.AtomicBoolean(false)
        submitFilesOperation(
            title = OperationTitle.StageForRawWrite(name),
            onSuccessWithToken = { stageToken ->
                val stagedPath = stagedPathHolder[0]
                if (stagedPath != null && state.operation?.token == stageToken) {
                    val stagedFile = java.io.File(stagedPath)
                    val compressedMagic =
                        runCatching {
                            java.io.FileInputStream(stagedFile).use {
                                usb.image.ImageFormatNames.readCompressedMagic(it)
                            }
                        }.getOrDefault(false)
                    onIntent(
                        UiIntent.SetStagedRawImage(
                            path = stagedPath,
                            label = name,
                            sizeBytes = stagedFile.length(),
                            compressedMagic = compressedMagic,
                        ),
                    )
                    stagedPublished.set(
                        state.operation?.token == stageToken &&
                            !state.isRunning &&
                            state.pendingConfirmation == null &&
                            state.stagedRawImagePath == stagedPath
                    )
                }
            },
            onTerminal = { outcome, terminalToken ->
                // Late-cancel race after last isActive check: executor may still mark
                // Cancelled/Failed without throwing — drop unpublished staged copy.
                // Never throw from terminal callback (would crash Main handler).
                if (outcome !is usb.operation.OperationState.Completed || !stagedPublished.get()) {
                    val orphan = stagedPathHolder[0]
                    stagedPathHolder[0] = null
                    try {
                        ImageStaging.deleteIfManaged(filesDir, orphan)
                    } catch (err: Throwable) {
                        // Keep the terminal state and its cause; expose cleanup as suppressed
                        // plus a persistent log line so the warning is not lost.
                        (outcome as? usb.operation.OperationState.Failed)
                            ?.cause
                            ?.addSuppressed(err)
                        val detail = err.message ?: err.javaClass.simpleName
                        onIntent(UiIntent.AppendLog(logLine("Upload cleanup failed: $detail")))
                        if (state.operation?.token == terminalToken) {
                            onIntent(
                                UiIntent.Status(
                                    UiMessage.Key(
                                        UiMessageKey.DeleteStagingFailed,
                                        listOf(detail),
                                    ),
                                ),
                            )
                        }
                    }
                }
            },
        ) { operation ->
            require(binding.matches(state)) { "raw source target changed" }
            val target = fatChildPath(path, name)
            val volume = filesSession.volumeForLocked(pin, binding.partitionId)
            val size = volume.stat(target).size
                operation.bytesTotal = size
                ImageStaging.requireUsableSpaceEstimate(filesDir, size)
                var primary: Throwable? = null
                try {
                    val stagedCopy = stageRawImageChunks(
                        filesDir = filesDir,
                        volume = volume,
                        sourcePath = target,
                        sourceSize = size,
                        sourceName = name,
                        operation = operation,
                        chunkBytes = TransferTuning.jniCapBytes,
                    )
                    stagedPathHolder[0] = stagedCopy.absolutePath
                } catch (t: Throwable) {
                    primary = t
                    stagedPathHolder[0] = null
                    throw t
                } finally {
                    // Release USB before Partitions confirm / raw claim.
                    val closeErr = closeFilesVolume(reportStatus = false)
                    if (closeErr != null) {
                        // No publish without successful source close; drop the staged copy.
                        val pending = stagedPathHolder[0]
                        stagedPathHolder[0] = null
                        val cleanupErr = pending?.let {
                            runCatching { ImageStaging.deleteIfManaged(filesDir, it) }
                                .onFailure { err ->
                                    val detail = err.message ?: err.javaClass.simpleName
                                    post(UiIntent.AppendLog(logLine("Staging cleanup failed: $detail")))
                                }
                                .exceptionOrNull()
                        }
                        throw usb.fs.mergeCloseFailure(primary, closeErr, cleanupErr)
                    }
                }
        }
    }

    // ---------------------------------------------------------------------
    // Source choosers: raw image, Ventoy link candidates, and phone import.
    // ---------------------------------------------------------------------

    /**
     * Read-only decode-count pass for the raw source. No temporary uncompressed file:
     * bytes are discarded as they are decoded, and cancellation is checked between
     * chunks. The result is bound to the reducer generation; a stale pass is ignored.
     */
    private fun measureRawSource(effect: UiEffect.MeasureRawSource) {
        MeasurementHandle.retryDeferredCloses()
        if (rawMeasureRunning) {
            // Keep newest source pending. The old provider read remains guarded until its
            // coroutine returns; cancellation only asks the provider stream to close.
            pendingRawMeasureEffect = effect
            onIntent(
                UiIntent.RawSourceMeasurementWaiting(
                    generation = effect.generation,
                    source = effect.source,
                ),
            )
            rawMeasureHandle?.cancel()
            rawMeasureJob?.cancel()
            return
        }
        startRawMeasurement(effect)
    }

    private fun startPendingRawMeasurement() {
        val effect = pendingRawMeasureEffect ?: return
        pendingRawMeasureEffect = null
        // A newer pick/cancel may have superseded this request while the provider was
        // blocked. Never revive a stale URI or generation.
        if (state.rawSizeGeneration != effect.generation || state.rawSourceKey != effect.source) {
            return
        }
        onIntent(
            UiIntent.RawSourceMeasurementStarted(
                generation = effect.generation,
                source = effect.source,
            ),
        )
        startRawMeasurement(effect)
    }

    private fun startRawMeasurement(effect: UiEffect.MeasureRawSource) {
        rawMeasureHandle?.cancel()
        val handle = try {
            MeasurementHandle()
        } catch (_: CloseCapacityException) {
            onIntent(measureError(effect, RawMeasureError.CloseCapacity))
            return
        }
        rawMeasureHandle = handle
        rawMeasureRunning = true
        val resolver = getApplication<Application>().contentResolver
        val job =
            try {
                scope.launch(Dispatchers.IO) {
                    val measurementScope = this
                    val outcome = runCatching {
                        val source =
                            if (effect.staged) {
                                ImageSource.fromFile(java.io.File(effect.source))
                            } else {
                                ImageSource.fromContentUri(Uri.parse(effect.source), resolver)
                            }
                        source.measureDecoded(
                            isActive = { measurementScope.isActive },
                            limitBytes = effect.limitBytes,
                            handle = handle,
                        )
                    }
                    val failure = outcome.exceptionOrNull()
                    if (failure !is java.util.concurrent.CancellationException &&
                        measurementScope.isActive
                    ) {
                        val detail = failure?.let { it.message ?: it.javaClass.simpleName }
                        val measured = outcome.getOrNull()
                        withContext(Dispatchers.Main.immediate) {
                            onIntent(
                                UiIntent.RawSourceMeasured(
                                    generation = effect.generation,
                                    source = effect.source,
                                    bytes = measured?.bytes,
                                    sha256 = measured?.sha256,
                                    error = detail,
                                    errorKind =
                                        if (failure == null) null else RawMeasureError.Failed,
                                ),
                            )
                        }
                    }
                }
            } catch (failure: Throwable) {
                handle.cancel()
                handle.abandon()
                rawMeasureRunning = false
                rawMeasureHandle = null
                rawMeasureJob = null
                throw failure
            }
        rawMeasureJob = job
        job.invokeOnCompletion {
            // Runs even if the coroutine was cancelled before its body started; for a
            // blocked provider read this is delayed until the read actually returns.
            // Handle release must not depend on [scope], which is cancelled on destroy.
            handle.cancel()
            handle.abandon()
            mainHandler.post {
                rawMeasureRunning = false
                if (rawMeasureJob === job) rawMeasureJob = null
                if (rawMeasureHandle === handle) rawMeasureHandle = null
                startPendingRawMeasurement()
            }
        }
    }

    private fun measureError(
        effect: UiEffect.MeasureRawSource,
        kind: RawMeasureError,
    ): UiIntent.RawSourceMeasured =
        UiIntent.RawSourceMeasured(
            generation = effect.generation,
            source = effect.source,
            bytes = null,
            errorKind = kind,
        )

    /** Close the tracked provider stream, if any. No new pass is queued. */
    private fun cancelRawMeasurement() {
        pendingRawMeasureEffect = null
        rawMeasureHandle?.cancel()
        rawMeasureHandle = null
        rawMeasureJob?.cancel()
        rawMeasureJob = null
    }
    private fun loadRawSourceCandidates(binding: PartitionBinding, generation: Long) {
        val pin = currentAttachmentPin()
        val identity = state.device?.identity
        rawSourceHandle?.cancel()
        rawSourceHandle = submitFilesOperation(
            title = OperationTitle.ListRawSources,
            quiet = true,
            listFailure = { message ->
                UiIntent.RawSourceCandidatesFailed(
                    generation = generation,
                    identity = identity,
                    message = message,
                )
            },
        ) { operation ->
            require(binding.matches(state)) { "raw source partition changed" }
            val volume = filesSession.volumeForLocked(pin, binding.partitionId)
            val listing = usb.image.RawSourceChooser.list(
                volume = volume,
                root = "/",
                maxEntries = 1000,
                maxDepth = 4,
                isActive = { operation.isActive() },
            )
            if (currentAttachmentPin() == pin && state.device?.identity == identity) {
                post(
                    UiIntent.RawSourceCandidatesLoaded(
                        generation = generation,
                        identity = identity,
                        candidates = listing.candidates.map {
                            usb.ui.RawSourceCandidate(
                                path = it.path,
                                name = it.name,
                                sizeBytes = it.sizeBytes,
                                partitionId = binding.partitionId,
                            )
                        },
                        truncated = listing.truncated,
                        unreadable = listing.unreadable.size,
                    ),
                )
            }
        }
    }

    private fun loadWindowsImageCandidates(generation: Long) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val identity = state.device?.identity
        windowsImageHandle?.cancel()
        val op = object : DiskOperation(OperationTitle.ListDataPartitionFiles.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val listing = usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                        usb.image.RawSourceChooser.list(
                            volume = volume,
                            root = "/",
                            maxEntries = 1000,
                            maxDepth = 5,
                            isActive = { isActive() },
                        )
                    }
                    if (attachmentMatches(expectedAttach, expectedKey) &&
                        this@MainViewModel.state.device?.identity == identity
                    ) {
                        post(
                            UiIntent.WindowsIsoCandidatesLoaded(
                                generation = generation,
                                identity = identity,
                                candidates = listing.candidates.map {
                                    usb.ui.RawSourceCandidate(
                                        path = it.path,
                                        name = it.name,
                                        sizeBytes = it.sizeBytes,
                                        partitionId = "1",
                                    )
                                },
                                error = listing.unreadable.firstOrNull()?.let {
                                    "${listing.unreadable.size} folder(s) could not be read"
                                },
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        windowsImageHandle = handle
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey) &&
                    state.device?.identity == identity
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.WindowsIsoCandidatesLoaded(
                            generation = generation,
                            identity = identity,
                            candidates = emptyList(),
                            error = message,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadWindowsImageLinks(generation: Long) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val identity = state.device?.identity
        val op = object : DiskOperation(OperationTitle.ReadXmlBindings.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val links = usb.ventoy.VentoySetup.readLinks(disk)
                    if (attachmentMatches(expectedAttach, expectedKey) &&
                        this@MainViewModel.state.device?.identity == identity
                    ) {
                        post(
                            UiIntent.WindowsImageLinksLoaded(
                                generation = generation,
                                identity = identity,
                                links = links.map {
                                    usb.ui.WindowsImageLink(it.image, it.templates)
                                },
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey) &&
                    state.device?.identity == identity
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.WindowsImageLinksLoaded(
                            generation = generation,
                            identity = identity,
                            links = emptyList(),
                            error = message,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun inspectImageImport(
        uri: String,
        generation: Long,
        kind: usb.ui.WindowsImportKind,
        destinationDirectory: String?,
        pickSessionId: String?,
    ) {
        val ticket =
            InspectionTicket(
                generation = generation,
                attachGeneration = attachGeneration,
                deviceKey = deviceKey(attachedDevice),
                identity = state.device?.identity,
            )
        val resolver = getApplication<Application>().contentResolver
        val document = Uri.parse(uri)
        if (!inspectionOwner.begin(ticket)) {
            // The reducer gate normally prevents a second inspection; fail closed with a
            // visible reason instead of leaving only a cleared guard.
            post(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportInspectionBusy)))
            post(UiIntent.WindowsIsoImportSafFinished(generation))
            return
        }
        fun reportAttachmentChanged() {
            post(
                UiIntent.Status(
                    UiMessage.Raw("USB attachment changed; re-select the file to import"),
                ),
            )
        }
        // The disk is closed (or was never opened) before this runs, and the slot is freed
        // before Finished is posted: the guard that Finished ends can therefore go away only
        // once the slot is free, so a next import admitted right after it is never reported as
        // Busy. The reducer generation-gates a late Finished, so it cannot clear a newer guard.
        fun finishInspection() {
            inspectionOwner.release(ticket)
            post(UiIntent.WindowsIsoImportFinished(generation))
        }
        // Same ordering for the SAF-only endings: no disk was opened, so freeing the slot first
        // keeps the gap between this post and the coroutine end from reporting Busy.
        fun finishSafInspection() {
            inspectionOwner.release(ticket)
            post(UiIntent.WindowsIsoImportSafFinished(generation))
        }
        // SAF metadata/template reads are slow and need no USB: run them first, off the
        // single disk worker, so the queue is not blocked on ContentResolver I/O.
        val safJob =
            scope.launch(Dispatchers.IO) {
                try {
                val name: String
                val sizeBytes: Long?
                val directory: String
                val target: String
                val markers: List<String>
                try {
                    val (reportedName, reportedSize) = contentInfo(resolver, document)
                    sizeBytes = reportedSize
                    val rawName = reportedName ?: document.lastPathSegment
                        ?: throw IllegalArgumentException("picker returned no file name")
                    name = usb.ventoy.VentoyIsoLibrary.requireImportName(rawName)
                    directory =
                        destinationDirectory?.let {
                            usb.ventoy.VentoyIsoLibrary.normalizeDirectory(it)
                        } ?: if (kind == usb.ui.WindowsImportKind.XmlTemplate) {
                            "/ventoy"
                        } else {
                            "/ISO"
                        }
                    target = if (directory == "/") "/$name" else "$directory/$name"
                    require(
                        !target.equals("/ventoy/ventoy.json", ignoreCase = true),
                    ) { "refusing to overwrite the Ventoy config with a template" }
                    markers =
                        if (kind == usb.ui.WindowsImportKind.XmlTemplate) {
                            inspectPhoneTemplate(resolver, document)
                        } else {
                            emptyList()
                        }
                } catch (failure: Throwable) {
                    if (inspectionOwner.owned(ticket)) {
                        post(
                            UiIntent.WindowsIsoImportRejected(
                                problem = importInspectionProblem(failure),
                                reason = failure.message ?: failure.javaClass.simpleName,
                                generation = generation,
                            ),
                        )
                    } else if (!inspectionAttachmentStillHere(ticket)) {
                        reportAttachmentChanged()
                    }
                    finishSafInspection()
                    return@launch
                }
                if (!inspectionOwner.owned(ticket)) {
                    // Cancelled or replugged during the read: no USB work at all.
                    if (!inspectionAttachmentStillHere(ticket)) reportAttachmentChanged()
                    finishSafInspection()
                    return@launch
                }
                post(UiIntent.WindowsIsoImportUsbStarted(generation))
                val runEntered = java.util.concurrent.atomic.AtomicBoolean(false)
                val op =
                    object : DiskOperation(name = "Inspect phone import") {
                        override suspend fun run() {
                            runEntered.set(true)
                            try {
                                inspectImageImportOnDisk(
                                    ticket = ticket,
                                    uri = uri,
                                    kind = kind,
                                    name = name,
                                    sizeBytes = sizeBytes,
                                    directory = directory,
                                    target = target,
                                    markers = markers,
                                    destinationDirectory = destinationDirectory,
                                    pickSessionId = pickSessionId,
                                )
                            } finally {
                                // The disk is closed here; only now may the USB guard end.
                                finishInspection()
                            }
                        }
                    }
                val token = nextOperationToken()
                val handle =
                    try {
                        DiskController.get().submit(op, token)
                    } catch (failure: Throwable) {
                        finishInspection()
                        return@launch
                    }
                inspectionOwner.trackUsbHandle(ticket, handle)
                try {
                    handle.await()
                } finally {
                    inspectionOwner.trackUsbHandle(ticket, null)
                    if (!runEntered.get()) {
                        // The op was rejected/cancelled before run() started, so it cannot post
                        // the closing Finished itself; only this fallback may end the guard.
                        finishInspection()
                    }
                }
                } finally {
                    // Safe net: free the slot even if this job stopped before any finish path.
                    inspectionOwner.release(ticket)
                }
            }
        // Registers the job for its own ticket; if a cancel or a newer import took the slot
        // while this job was launching, the owner cancels it instead of storing an orphan.
        inspectionOwner.trackSafJob(ticket, safJob)
    }

    /**
     * One phone-file inspection body, executed on the disk controller queue so no list or
     * import mount can interleave with the direct session it opens.
     */
    private fun inspectImageImportOnDisk(
        ticket: InspectionTicket,
        uri: String,
        kind: usb.ui.WindowsImportKind,
        name: String,
        sizeBytes: Long?,
        directory: String,
        target: String,
        markers: List<String>,
        destinationDirectory: String?,
        pickSessionId: String?,
    ) {
        fun reportAttachmentChanged() {
            post(
                UiIntent.Status(
                    UiMessage.Raw("USB attachment changed; re-select the file to import"),
                ),
            )
        }
        if (!inspectionOwner.owned(ticket)) {
            // Queued while the picker was cancelled or the stick was replaced.
            if (!inspectionAttachmentStillHere(ticket)) reportAttachmentChanged()
            return
        }
        try {
                // Ownership and attachment are revalidated after the mount close (which can
                // take seconds): a cancel/replug during the queue wait or the close must
                // never open a stale device.
                val raw = openRawDisk { live ->
                    when {
                        !inspectionOwner.owned(ticket) -> {
                            if (!inspectionAttachmentStillHere(ticket)) reportAttachmentChanged()
                            null
                        }
                        live.device == null ||
                            live.generation != ticket.attachGeneration ||
                            deviceKey(live.device) != ticket.deviceKey -> {
                            reportAttachmentChanged()
                            null
                        }
                        else -> live.device
                    }
                } ?: return
                val disk = raw.disk
                val resolvedTargetName: String
                val exists: Boolean
                try {
                    val pair =
                        usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                            if (destinationDirectory != null && directory != "/") {
                                val chosen = volume.statOrNull(directory)
                                if (chosen == null || !chosen.isDirectory) {
                                    throw usb.ventoy.ImportDirectoryMissingException(
                                        "import folder \"$directory\" does not exist on the Ventoy partition",
                                    )
                                }
                            }
                            if (kind == usb.ui.WindowsImportKind.XmlTemplate) {
                                // WIN-06: never collide with an existing template; number from image stem.
                                val imageHint =
                                    state.windowsSelectedImages.firstOrNull() ?: name
                                val allocated =
                                    usb.ventoy.VentoySetup.allocateXmlPath(
                                        volume = volume,
                                        imagePath = imageHint,
                                        directory = directory,
                                    )
                                val allocatedName = allocated.substringAfterLast('/')
                                allocatedName to false
                            } else {
                                name to (volume.statOrNull(target) != null)
                            }
                        }
                    resolvedTargetName = pair.first
                    exists = pair.second
                } finally {
                    disk.close()
                }
                if (inspectionOwner.owned(ticket)) {
                    post(
                        UiIntent.WindowsIsoImportInspected(
                            uri = uri,
                            name = name,
                            sizeBytes = sizeBytes,
                            targetName = resolvedTargetName,
                            exists = exists,
                            generation = ticket.generation,
                            kind = kind,
                            templateMarkers = markers,
                            directory = directory,
                            pickSessionId = pickSessionId,
                        ),
                    )
                    if (markers.isNotEmpty()) {
                        post(
                            UiIntent.Status(
                                UiMessage.Raw(
                                    "Template has destructive sections: " +
                                        markers.joinToString(", ") +
                                        "; binding it will need a separate confirmation",
                                ),
                            ),
                        )
                    }
                } else if (!inspectionAttachmentStillHere(ticket)) {
                    // The stick changed under the picker: say so instead of dropping the result.
                    reportAttachmentChanged()
                }
                // Cancelled mid-probe: the result belongs to an obsolete dialog; stay silent.
            } catch (failure: Throwable) {
                if (!inspectionOwner.owned(ticket)) {
                    // Cancel/replug while probing: never publish a result for it.
                    if (!inspectionAttachmentStillHere(ticket)) reportAttachmentChanged()
                    return
                }
                val folderMissing = failure is usb.ventoy.ImportDirectoryMissingException
                val problem = importInspectionProblem(failure)
                post(
                    UiIntent.WindowsIsoImportRejected(
                        problem = problem,
                        reason = failure.message ?: failure.javaClass.simpleName,
                        generation = ticket.generation,
                    ),
                )
                if (folderMissing) {
                    // The chosen folder vanished between listing and inspection: say why and
                    // reopen the folder picker instead of leaving only a generic I/O error.
                    post(
                        UiIntent.Status(
                            UiMessage.Raw(
                                failure.message ?: "import folder is gone; pick it again",
                            ),
                        ),
                    )
                    post(
                        UiIntent.ReopenImportFolderPick(
                            kind = kind,
                            identity = ticket.identity,
                            attachGeneration = ticket.attachGeneration,
                            requestGeneration = ticket.generation,
                        ),
                    )
                }
            }
    }

    private fun importInspectionProblem(failure: Throwable): UnreadableProblem =
        when (failure) {
            is usb.ventoy.NotUnattendRootException -> UnreadableProblem.NotUnattendRoot
            is usb.ventoy.TemplateTooLargeException -> UnreadableProblem.TooLarge
            is usb.ventoy.EmptyTemplateException -> UnreadableProblem.Empty
            is usb.ventoy.InvalidTemplateException -> UnreadableProblem.MalformedXml
            is usb.ventoy.ImportDirectoryMissingException -> UnreadableProblem.IoFailure
            is java.io.IOException -> UnreadableProblem.IoFailure
            else -> UnreadableProblem.MalformedXml
        }

    private fun inspectPhoneTemplate(
        resolver: android.content.ContentResolver,
        document: android.net.Uri,
    ): List<String> {
        val limit = usb.ventoy.VentoySetup.MAX_TEMPLATE_BYTES
        val input = resolver.openInputStream(document)
            ?: throw java.io.FileNotFoundException("cannot open $document")
        val bytes =
            input.use { stream ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    val read = stream.read(buffer)
                    if (read <= 0) break
                    total += read
                    if (total > limit) {
                        throw usb.ventoy.TemplateTooLargeException(
                            "XML template is larger than $limit bytes; refusing to inspect it",
                        )
                    }
                    output.write(buffer, 0, read)
                }
                output.toByteArray()
            }
        if (bytes.isEmpty()) throw usb.ventoy.EmptyTemplateException("XML template is empty")
        return usb.ventoy.WindowsTemplate.inspect(bytes, requireUnattendRoot = true).destructiveMarkers
    }

    private fun importImage(proposal: usb.ui.IsoImportProposal, overwrite: Boolean) {
        if (attachGeneration != proposal.attachGeneration ||
            state.device?.identity != proposal.identity
        ) {
            onIntent(UiIntent.Status(UiMessage.Raw("USB attachment changed; re-select the file")))
            return
        }
        if (proposal.exists && !overwrite) {
            onIntent(UiIntent.Status(UiMessage.Raw("Overwrite was not confirmed; nothing was imported")))
            return
        }
        if (!proposal.exists && overwrite) {
            onIntent(UiIntent.Status(UiMessage.Raw("Overwrite was not confirmed for a new file")))
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val opTitle = OperationTitle.ImportTarget(proposal.targetName)
        val label = opTitle.logLabel
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedIdentity = proposal.identity
        val expectedPickSession = proposal.pickSessionId
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null
            @Volatile var copiedPath: String? = null

            override suspend fun run() {
                expectedPickSession?.let { session ->
                    require(this@MainViewModel.state.filesPickMode?.sessionId == session) {
                        "Pick session changed before the copy"
                    }
                }
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) {
                        "USB attach generation changed before the copy"
                    }
                    require(deviceKey(live.device) == expectedKey) {
                        "USB device changed before the copy"
                    }
                    require(this@MainViewModel.state.device?.identity == expectedIdentity) {
                        "USB identity changed before the copy"
                    }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val probe = usb.ventoy.VentoyLayout.probe(disk)
                        ?: throw usb.ventoy.IncompatibleVentoyException(
                            "No Ventoy data partition on this disk",
                        )
                    proposal.partitionStartLba?.let { expected ->
                        require(probe.part1StartLba == expected) {
                            "Ventoy data partition start changed since the file was chosen"
                        }
                    }
                    proposal.partitionSectorCount?.let { expected ->
                        require(probe.part1SectorCount == expected) {
                            "Ventoy data partition size changed since the file was chosen"
                        }
                    }
                    val sectorSize = disk.sectorSize.toULong()
                    val maxBytes =
                        if (probe.part1SectorCount <= Long.MAX_VALUE.toULong() / sectorSize) {
                            (probe.part1SectorCount * sectorSize).toLong()
                        } else {
                            Long.MAX_VALUE
                        }
                    bytesTotal = proposal.sizeBytes ?: -1L
                    val outcome = usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                        require(
                            attachmentMatches(expectedAttach, expectedKey) &&
                                this@MainViewModel.state.device?.identity == expectedIdentity,
                        ) {
                            "USB attachment changed before the copy reached the write"
                        }
                        expectedPickSession?.let { session ->
                            require(this@MainViewModel.state.filesPickMode?.sessionId == session) {
                                "Pick session changed before the copy reached the write"
                            }
                        }
                        val input = resolver.openInputStream(Uri.parse(proposal.uri))
                            ?: throw java.io.FileNotFoundException("cannot open ${proposal.uri}")
                        usb.ventoy.VentoyIsoLibrary.import(
                            volume = volume,
                            input = input,
                            rawName = proposal.targetName,
                            operation = this,
                            overwrite = overwrite,
                            expectedSize = proposal.sizeBytes,
                            maxBytes = maxBytes,
                            importDirectory = proposal.directory,
                            onCleanupFailure = { cleanup ->
                                val detail = cleanup.message ?: cleanup.javaClass.simpleName
                                post(UiIntent.AppendLog(logLine("Import cleanup: $detail")))
                            },
                        )
                    }
                    copiedPath = outcome.targetPath
                    result = "Imported ${outcome.bytes} bytes to ${outcome.targetPath}"
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandle = handle
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = opTitle,
                    token = token,
                    bytesTotal = proposal.sizeBytes ?: -1L,
                    cancelable = true,
                ),
            ),
        )
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (handle.state == usb.operation.OperationState.Running) {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Running,
                                    title = opTitle,
                                    token = token,
                                    bytesDone = op.bytesDone,
                                    bytesTotal = op.bytesTotal,
                                    cancelable = true,
                                ),
                            ),
                        )
                    }
                }
            }
            try {
                val outcome = handle.await()
                ticker.cancel()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = opTitle,
                            token = token,
                            bytesDone = op.bytesDone,
                            bytesTotal = op.bytesTotal,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && mappedState == OperationState.Completed) {
                    if (proposal.kind == usb.ui.WindowsImportKind.XmlTemplate) {
                        onIntent(
                            UiIntent.WindowsTemplateImported(
                                "${proposal.directory.trimEnd('/')}/${proposal.targetName}",
                            ),
                        )
                    } else {
                        op.copiedPath?.let { target ->
                            onIntent(
                                UiIntent.FilesPickCopySucceeded(
                                    path = target,
                                    sizeBytes = proposal.sizeBytes,
                                    pickSessionId = proposal.pickSessionId,
                                ),
                            )
                        }
                    }
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                    if (op.failureCause is usb.ventoy.ImportDirectoryMissingException) {
                        // The cached folder list just proved stale: re-read it and reopen the
                        // picker for the same import kind on this exact attachment.
                        onIntent(
                            UiIntent.ReopenImportFolderPick(
                                kind = proposal.kind,
                                identity = proposal.identity,
                                attachGeneration = proposal.attachGeneration,
                                // The failed terminal already bumped the inspection generation
                                // (catalog invalidation); capture the live one, not the pre-import id.
                                requestGeneration = state.isoImportGeneration,
                            ),
                        )
                    }
                }
            } finally {
                ticker.cancel()
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            }
        }
    }

    private fun unlinkWindowsImages(paths: List<String>) {
        val opTitle = OperationTitle.RemoveXmlBindings
        val label = opTitle.logLabel
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                val raw = openRawDisk() ?: throw DiskException.Disconnected()
                val disk = raw.disk
                try {
                    val changed = usb.ventoy.VentoySetup.unlinkImages(
                        disk = disk,
                        imagePaths = paths,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    result =
                        if (changed) {
                            "Removed ${paths.size} binding(s); image and XML files were kept"
                        } else {
                            "No matching binding was present; nothing changed"
                        }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandle = handle
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = opTitle,
                    token = token,
                    cancelable = true,
                ),
            ),
        )
        publishOperationStarted(handle, opTitle, token, cancelable = true)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = opTitle,
                            token = token,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && mappedState == OperationState.Completed) {
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            }
        }
    }

    private fun saveWindowsControls() {
        val bypass = state.vtoyBypassChecks
        val localAccount = state.vtoyLocalAccount
        if (bypass == null && localAccount == null) {
            onIntent(
                UiIntent.Status(
                    UiMessage.Raw("No Windows control change to save (global Ventoy flags)"),
                ),
            )
            return
        }
        val opTitle = OperationTitle.SaveGlobalWindowsControls
        val label = opTitle.logLabel
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                val raw = openRawDisk() ?: throw DiskException.Disconnected()
                val disk = raw.disk
                try {
                    usb.ventoy.VentoySetup.saveControls(
                        disk = disk,
                        options = usb.ventoy.SetupOptions(
                            bypassChecks = bypass,
                            localAccount = localAccount,
                        ),
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    result = "Global controls saved: " +
                        "VTOY_WIN11_BYPASS_CHECK=${bypass ?: "unchanged"}, " +
                        "VTOY_WIN11_BYPASS_NRO=${localAccount ?: "unchanged"}"
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = opTitle,
                    token = token,
                    cancelable = false,
                ),
            ),
        )
        publishOperationStarted(handle, opTitle, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = opTitle,
                            token = token,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && message != null) onIntent(UiIntent.Status(UiMessage.Raw(message)))
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadAutounattend(xmlName: String, generation: Long) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val op = object : DiskOperation(OperationTitle.ReadUnattendXml.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val read = usb.ventoy.VentoySetup.readAutounattend(disk, xmlName)
                    if (attachmentMatches(expectedAttach, expectedKey)) {
                        post(
                            UiIntent.AutounattendLoaded(
                                xmlName = xmlName,
                                state = read.state,
                                bindings =
                                    if (read.bindings.isEmpty()) {
                                        emptyList()
                                    } else {
                                        read.bindings
                                    },
                                generation = generation,
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey)
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    val problem =
                        when (outcome.cause) {
                            is usb.ventoy.VentoySetup.TemplateTooLargeException ->
                                UnreadableProblem.TooLarge
                            is java.io.IOException -> UnreadableProblem.IoFailure
                            else -> UnreadableProblem.MalformedXml
                        }
                    post(
                        UiIntent.AutounattendLoaded(
                            xmlName = xmlName,
                            state = AutounattendState.Unreadable(
                                problem = problem,
                                reason = message,
                                sourcePath = usb.ventoy.VentoySetup.xmlPath(xmlName),
                            ),
                            bindings = emptyList(),
                            generation = generation,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadAutounattendCandidates(xmlName: String, generation: Long) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val op = object : DiskOperation(OperationTitle.ListVentoyImages.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val templatePath = usb.ventoy.VentoySetup.xmlPath(xmlName)
                    val links = usb.ventoy.VentoySetup.readLinks(disk)
                    val result =
                        usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                            val (roots, limitedBy) =
                                usb.ventoy.VentoySetup.readSearchScope(volume)
                            val listing = usb.image.RawSourceChooser.list(
                                volume = volume,
                                root = "/",
                                maxEntries = 1000,
                                maxDepth = 5,
                                isActive = { isActive() },
                            )
                            val bindings =
                                listing.candidates.map { file ->
                                    val linked =
                                        links.any { link ->
                                            link.image.equals(file.path, ignoreCase = true) &&
                                                link.templates.any {
                                                    it.equals(templatePath, ignoreCase = true)
                                                }
                                        }
                                    AutounattendBinding(
                                        imagePath = file.path,
                                        bound = linked,
                                    )
                                }
                            usb.ui.AutounattendImageCandidates(
                                images = bindings,
                                searchScope = roots,
                                scopeLimitedBy = limitedBy,
                                truncated = listing.truncated,
                                error =
                                    listing.unreadable.firstOrNull()?.let {
                                        "${listing.unreadable.size} folder(s) could not be read"
                                    },
                            )
                        }
                    if (attachmentMatches(expectedAttach, expectedKey)) {
                        post(
                            UiIntent.AutounattendCandidatesLoaded(
                                xmlName,
                                result,
                                generation = generation,
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey)
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.AutounattendCandidatesLoaded(
                            xmlName,
                            usb.ui.AutounattendImageCandidates(
                                images = emptyList(),
                                searchScope = emptyList(),
                                scopeLimitedBy = null,
                                error = message,
                            ),
                            generation = generation,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun writeAutounattendRaw(effect: UiEffect.WriteAutounattendRaw) {
        val xmlName = effect.xmlName
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val opTitle = OperationTitle.WriteUnattendXml
        val op = object : DiskOperation(name = opTitle.logLabel) {
            @Volatile var result: String? = null
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    usb.ventoy.VentoySetup.writeAutounattendRaw(
                        disk = disk, xmlName = xmlName, rawBytes = effect.rawBytes,
                        overwriteXml = effect.overwriteXml,
                        allowDestructiveTemplate = effect.allowDestructiveTemplate,
                        expectedTemplateSha256 = effect.expectedTemplateSha256,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    result = "Wrote ${usb.ventoy.VentoySetup.xmlPath(xmlName)}"
                } finally { disk.close() }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(UiIntent.OperationBegan(OperationProgress(
            state = OperationState.Queued, title = opTitle, token = token, cancelable = false,
        )))
        publishOperationStarted(handle, opTitle, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(UiIntent.OperationUpdated(OperationProgress(
                    state = mappedState, title = opTitle, token = token,
                    errorMessage = message, resultMessage = op.result, cancelable = false,
                )))
                if (state.operation?.token == token && mappedState == OperationState.Completed) {
                    onIntent(UiIntent.LoadAutounattend(xmlName))
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally { activeHandles.remove(handle) }
        }
    }

    private fun generateAutounattend(effect: UiEffect.GenerateAutounattend) {
        val xmlName = effect.xmlName
        val params = effect.autounattendParams
        val imagePaths = effect.imagePaths
        val overwriteXml = effect.overwriteXml
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val opTitle = OperationTitle.GenerateUnattendXml
        val label = opTitle.logLabel
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null
            @Volatile var generatedXmlPath: String? = null

            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    // Integrity: confirmed plan must match the UI-proposed xmlName first.
                    // Never invent a plan hash when the effect carries null.
                    val confirmedPlan = effect.expectedPlanSha256
                    if (confirmedPlan != null) {
                        val proposed =
                            usb.ventoy.autounattendPlanSha256(xmlName, params, imagePaths)
                        if (confirmedPlan != proposed) {
                            throw usb.ventoy.VentoySetup.PlanChangedException(
                                "The generation plan changed; confirm it again",
                            )
                        }
                    }
                    val writeName: String =
                        if (!overwriteXml) {
                            // WIN-06 create-new: allocate under /ventoy from selected image stem.
                            val imageHint = imagePaths.firstOrNull() ?: xmlName
                            usb.ventoy.VentoySetup.allocateXmlPath(
                                disk = disk,
                                imagePath = imageHint,
                            )
                        } else {
                            xmlName
                        }
                    // Resolve once; GenerateResult keeps basename only.
                    val resolvedWrite = usb.ventoy.VentoySetup.xmlPath(writeName)
                    // Pass confirmed plan through. create-new may change the path component;
                    // generateAutounattend still receives the confirmed hash (or null) and
                    // must not treat a resealed path as a new confirmation.
                    // When create-new allocates a different name, reseal only after the
                    // original confirmation checked above; null stays null.
                    val writePlan =
                        if (!overwriteXml && confirmedPlan != null) {
                            usb.ventoy.autounattendPlanSha256(resolvedWrite, params, imagePaths)
                        } else {
                            confirmedPlan
                        }
                    usb.ventoy.VentoySetup.generateAutounattend(
                        disk = disk,
                        xmlName = resolvedWrite,
                        params = params,
                        imagePaths = imagePaths,
                        overwriteXml = overwriteXml,
                        allowDestructiveTemplate = effect.allowDestructiveTemplate,
                        expectedTemplateSha256 = effect.expectedTemplateSha256,
                        expectedPlanSha256 = writePlan,
                        // Preserve imported layouts without editor-only capacity metadata; validate size only on explicit layout Set.
                        requireDeclaredDiskSize = params.intents.diskLayout == usb.ventoy.XmlFieldIntent.Set,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    generatedXmlPath = resolvedWrite
                    result = "Wrote $resolvedWrite"
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = opTitle,
                    token = token,
                    cancelable = false,
                ),
            ),
        )
        publishOperationStarted(handle, opTitle, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = opTitle,
                            token = token,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && mappedState == OperationState.Completed) {
                    op.generatedXmlPath?.let { path ->
                        onIntent(UiIntent.WindowsTemplateImported(path))
                    }
                    onIntent(UiIntent.LoadWindowsImageLinks)
                    onIntent(UiIntent.ClearWindowsSelection)
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }


    private fun checkVentoyUpstream(requestToken: Long) {
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    usb.ventoy.VentoyDownloader.checkLatestRelease()
                        ?: throw IllegalStateException("upstream latest release unavailable")
                }
            }
            outcome.fold(
                onSuccess = { tag ->
                    onIntent(
                        UiIntent.SetVentoyUpstreamRelease(
                            requestToken = requestToken,
                            tag = tag,
                            pinned = VentoyDownloader.pinnedSourceOrNull(tag) != null,
                        ),
                    )
                },
                onFailure = { failure ->
                    onIntent(
                        UiIntent.SetVentoyUpstreamRelease(
                            requestToken = requestToken,
                            tag = null,
                            error = failure.message ?: failure.javaClass.simpleName,
                        ),
                    )
                },
            )
        }
    }

    /**
     * Downloads and verifies a release without opening the USB device. The verified bytes
     * are published in app-private storage before the UI records the release, so a restart
     * can reuse exactly what was checked. Failure leaves the previous release untouched.
     */
    private fun downloadVentoyRelease(requestToken: Long, upstreamVersion: String?) {
        val app = getApplication<Application>()
        val filesDir = app.filesDir
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    val job = kotlinx.coroutines.currentCoroutineContext()[kotlinx.coroutines.Job]
                    val downloaded =
                        if (upstreamVersion != null && VentoyDownloader.pinnedSourceOrNull(upstreamVersion) == null) {
                            VentoyDownloader.downloadUnpinnedRelease(
                                cacheDir = app.cacheDir,
                                release = upstreamVersion,
                                isActive = { job?.isActive == true },
                            )
                        } else {
                            val source = VentoyDownloader.pinnedDownloadSource(upstreamVersion)
                            VentoyDownloader.downloadRelease(
                                cacheDir = app.cacheDir,
                                release = source.release,
                                isActive = { job?.isActive == true },
                            )
                        }
                    usb.ventoy.VentoyReleaseStore.save(
                        filesDir = filesDir,
                        download = downloaded,
                        syncDirectory = ::fsyncDirectory,
                    )
                }
            }
            outcome.fold(
                onSuccess = { stored ->
                    onIntent(
                        UiIntent.VentoyReleaseDownloaded(
                            requestToken = requestToken,
                            provenance = usb.ventoy.VentoyReleaseProvenance(
                                release = stored.assets.release,
                                archiveSha256 = stored.archiveSha256,
                                sourceUrl = stored.sourceUrl,
                                independentlyPinned = stored.independentlyPinned,
                            ),
                        ),
                    )
                },
                onFailure = { failure ->
                    usb.ventoy.VentoyReleaseStore.fatalCauseOrNull(failure)?.let { throw it }
                    when (failure) {
                        is usb.ventoy.VentoyReleaseStore.PromotionButUnconfirmedException -> {
                            val stored = failure.stored
                            onIntent(
                                UiIntent.VentoyReleaseStoreChanged(
                                    requestToken = requestToken,
                                    provenance = usb.ventoy.VentoyReleaseProvenance(
                                        release = stored.assets.release,
                                        archiveSha256 = stored.archiveSha256,
                                        sourceUrl = stored.sourceUrl,
                                        independentlyPinned = stored.independentlyPinned,
                                    ),
                                    message = getApplication<Application>().getString(
                                        R.string.ventoy_promotion_unconfirmed,
                                        failure.requestedRelease,
                                        stored.assets.release,
                                    ),
                                ),
                            )
                        }

                        is usb.ventoy.VentoyReleaseStore.PublishedButUnconfirmedException -> {
                            val stored = failure.stored
                            onIntent(
                                UiIntent.VentoyReleaseStoreChanged(
                                    requestToken = requestToken,
                                    provenance = usb.ventoy.VentoyReleaseProvenance(
                                        release = stored.assets.release,
                                        archiveSha256 = stored.archiveSha256,
                                        sourceUrl = stored.sourceUrl,
                                        independentlyPinned = stored.independentlyPinned,
                                    ),
                                    message = getApplication<Application>().getString(
                                        R.string.ventoy_download_unconfirmed,
                                        stored.assets.release,
                                    ),
                                ),
                            )
                        }

                        is usb.ventoy.VentoyReleaseStore.StoreUnknownException -> {
                            onIntent(
                                UiIntent.VentoyReleaseStoreUnknown(
                                    requestToken = requestToken,
                                    message = getApplication<Application>().getString(
                                        R.string.ventoy_download_unknown,
                                        failure.requestedRelease,
                                    ),
                                ),
                            )
                        }

                        is usb.ventoy.VentoyReleaseStore.StoreChangedException -> {
                            val stored = failure.stored
                            onIntent(
                                UiIntent.VentoyReleaseStoreChanged(
                                    requestToken = requestToken,
                                    provenance = usb.ventoy.VentoyReleaseProvenance(
                                        release = stored.assets.release,
                                        archiveSha256 = stored.archiveSha256,
                                        sourceUrl = stored.sourceUrl,
                                        independentlyPinned = stored.independentlyPinned,
                                    ),
                                    message = getApplication<Application>().getString(
                                        R.string.ventoy_download_recovered,
                                        failure.requestedRelease,
                                        stored.assets.release,
                                    ),
                                ),
                            )
                        }

                        else -> onIntent(
                            UiIntent.VentoyReleaseDownloadFailed(
                                requestToken,
                                failure.message ?: failure.javaClass.simpleName,
                            ),
                        )
                    }
                },
            )
        }
    }

    /** Makes a directory-entry change durable; failures abort the download, not hidden. */
    private fun fsyncDirectory(dir: File) {
        val fd = android.system.Os.open(
            dir.path,
            android.system.OsConstants.O_RDONLY,
            0,
        )
        try {
            android.system.Os.fsync(fd)
        } catch (failure: android.system.ErrnoException) {
            // EINVAL means this filesystem does not fsync directory entries; all file
            // data was already fsynced. Any other errno is a real failure.
            if (failure.errno != android.system.OsConstants.EINVAL) throw failure
        } finally {
            android.system.Os.close(fd)
        }
    }

    /**
     * Resolves exactly the release named in the confirmation. No external-storage or
     * bundled fallback is allowed: a missing/changed store aborts before any write.
     */
    private fun ventoyAssetsForInstall(source: usb.ventoy.VentoyReleaseSource?): VentoyAssets {
        val app = getApplication<Application>()
        return usb.ventoy.VentoyInstallAssets.resolve(
            source = source,
            loadBundled = { VentoyAssets.loadFromAssets(app.assets, strict = true) },
            loadStored = { usb.ventoy.VentoyReleaseStore.load(app.filesDir) },
        )
    }

    private fun loadVentoyGlobalSettings(
        requestToken: Long,
        source: VentoySettingsSource,
    ) {
        if (source is VentoySettingsSource.Document) {
            loadVentoySettingsDocument(requestToken, source)
            return
        }
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedEpoch = state.ventoyCacheEpoch
        val op = object : DiskOperation(OperationTitle.ReadVentoyGlobalSettings.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val snapshot =
                        if (source is VentoySettingsSource.UsbFile) {
                            VentoySetup.readGlobalSettingsSnapshotAt(disk, source.path)
                        } else null
                    val settings = snapshot?.settings ?: VentoySetup.readGlobalSettings(disk)
                    val pinnedSource =
                        if (source is VentoySettingsSource.UsbFile) source.copy(snapshotSha256 = snapshot!!.sha256)
                        else source
                    if (attachmentMatches(expectedAttach, expectedKey) &&
                        this@MainViewModel.state.ventoySettingsSource.sameTarget(source) &&
                        isActive()
                    ) {
                        post(
                            UiIntent.VentoyGlobalSettingsLoaded(
                                settings,
                                epoch = expectedEpoch,
                                requestToken = requestToken,
                                source = pinnedSource,
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey) &&
                    this@MainViewModel.state.ventoySettingsSource.sameTarget(source) &&
                    outcome.cause !is kotlinx.coroutines.CancellationException
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.VentoyGlobalSettingsLoaded(
                            if (outcome.cause is usb.ventoy.IncompatibleVentoyException &&
                                source is VentoySettingsSource.Default
                            ) {
                                // No Ventoy layout is the honest "no ventoy.json yet" state,
                                // not a read failure. A chosen file never becomes Missing just
                                // because the data partition vanished.
                                VentoyGlobalSettings.Missing
                            } else {
                                VentoyGlobalSettings.ReadError(message)
                            },
                            epoch = expectedEpoch,
                            requestToken = requestToken,
                            source = source,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    /** Phone document is read-only import source; validated bytes are pinned by hash. */
    private fun loadVentoySettingsDocument(
        requestToken: Long,
        source: VentoySettingsSource.Document,
    ) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val expectedEpoch = state.ventoyCacheEpoch
        val op = object : DiskOperation(OperationTitle.ReadVentoyGlobalSettings.logLabel) {
            override suspend fun run() {
                val bytes = readSettingsDocumentBytes(source)
                val text = strictUtf8(bytes)
                val settings =
                    try {
                        VentoySetup.parseGlobalSettings(text)
                    } catch (failure: Exception) {
                        VentoyGlobalSettings.ReadError(
                            failure.message ?: failure.javaClass.simpleName,
                        )
                    }
                val pinned = source.copy(snapshotSha256 = sha256(bytes))
                if (attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey &&
                    this@MainViewModel.state.ventoySettingsSource.sameTarget(source) &&
                    isActive()
                ) {
                    post(
                        UiIntent.VentoyGlobalSettingsLoaded(
                            settings,
                            epoch = expectedEpoch,
                            requestToken = requestToken,
                            source = pinned,
                        ),
                    )
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey &&
                    this@MainViewModel.state.ventoySettingsSource.sameTarget(source) &&
                    outcome.cause !is kotlinx.coroutines.CancellationException
                ) {
                    post(
                        UiIntent.VentoyGlobalSettingsLoaded(
                            VentoyGlobalSettings.ReadError(
                                outcome.cause.message ?: outcome.cause.javaClass.simpleName,
                            ),
                            epoch = expectedEpoch,
                            requestToken = requestToken,
                            source = source,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    /** Read/validate one SAF document; every failure is a distinct ReadError, never Empty. */
    private fun readSettingsDocument(
        source: VentoySettingsSource.Document,
    ): VentoyGlobalSettings =
        try {
            usb.ventoy.VentoySetup.parseGlobalSettings(readSettingsDocumentText(source))
        } catch (failure: Exception) {
            VentoyGlobalSettings.ReadError(
                failure.message ?: failure.javaClass.simpleName,
            )
        }

    /**
     * Read the exact document bytes without parsing. Used by both the validation read and the
     * in-place merge, so the write path re-reads immediately before it opens the output stream.
     */
    private fun readSettingsDocumentText(
        source: VentoySettingsSource.Document,
    ): String {
        val resolver = getApplication<Application>().contentResolver
        val uri =
            try {
                Uri.parse(source.uri)
            } catch (failure: Exception) {
                throw usb.ventoy.VentoySettingsDocumentException(
                    "selected settings document URI is invalid: " +
                        (failure.message ?: "parse failed"),
                    failure,
                )
            }
        val type =
            try {
                resolver.getType(uri)
            } catch (failure: Exception) {
                throw usb.ventoy.VentoySettingsDocumentException(
                    "selected settings document type could not be read: " +
                        (failure.message ?: failure.javaClass.simpleName),
                    failure,
                )
            }
        if (type == DocumentsContract.Document.MIME_TYPE_DIR) {
            throw usb.ventoy.VentoySettingsDocumentException(
                "selected settings document is a directory, not a file",
            )
        }
        var displayName: String? = null
        var expectedBytes: Long? = null
        try {
            resolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                null,
                null,
                null,
            )?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (nameIndex >= 0 && !cursor.isNull(nameIndex)) {
                        displayName = cursor.getString(nameIndex)
                    }
                    val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
                    if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) {
                        expectedBytes = cursor.getLong(sizeIndex)
                    }
                }
            }
        } catch (failure: Exception) {
            // Metadata is advisory; reading the stream still decides. Do not fake a size.
        }
        if (expectedBytes != null && expectedBytes!! > VentoySettingsDocument.MAX_BYTES) {
            throw usb.ventoy.VentoySettingsDocumentException(
                "selected settings document is $expectedBytes bytes; " +
                    "limit is ${VentoySettingsDocument.MAX_BYTES}",
            )
        }
        return try {
            val input =
                resolver.openInputStream(uri)
                    ?: throw usb.ventoy.VentoySettingsDocumentException(
                        "selected settings document could not be opened",
                    )
            input.use { VentoySettingsDocument.read(it, expectedBytes) }
        } catch (failure: java.io.FileNotFoundException) {
            throw usb.ventoy.VentoySettingsDocumentException(
                "selected settings document is no longer available: " +
                    (failure.message ?: displayName ?: source.uri),
                failure,
            )
        } catch (failure: SecurityException) {
            throw usb.ventoy.VentoySettingsDocumentException(
                "no permission to read the selected settings document; choose it again",
                failure,
            )
        } catch (failure: usb.ventoy.VentoySettingsDocumentException) {
            throw failure
        } catch (failure: Exception) {
            throw usb.ventoy.VentoySettingsDocumentException(
                "selected settings document could not be read: " +
                    (failure.message ?: failure.javaClass.simpleName),
                failure,
            )
        }
    }
    private fun readSettingsDocumentBytes(source: VentoySettingsSource.Document): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        val uri = Uri.parse(source.uri)
        val expectedBytes = resolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val index = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (index >= 0 && !cursor.isNull(index)) cursor.getLong(index) else null
            } else null
        }
        if (expectedBytes != null && expectedBytes > VentoySettingsDocument.MAX_BYTES) {
            throw VentoySettingsDocumentException("selected settings document exceeds ${VentoySettingsDocument.MAX_BYTES} bytes")
        }
        return resolver.openInputStream(uri)?.use {
            VentoySettingsDocument.readBytes(it, expectedBytes)
        } ?: throw VentoySettingsDocumentException("selected settings document could not be opened")
    }

    private fun loadVentoyFolders(requestToken: Long, path: String) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedEpoch = state.ventoyCacheEpoch
        val op = object : DiskOperation(OperationTitle.ListVentoyFolders.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val folders = usb.ventoy.VentoySetup.listSubdirectories(disk, path)
                    if (attachmentMatches(expectedAttach, expectedKey)) {
                        post(
                            UiIntent.VentoyFoldersLoaded(
                                folders,
                                epoch = expectedEpoch,
                                requestToken = requestToken,
                                path = path,
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachmentMatches(expectedAttach, expectedKey)
                ) {
                    val problem =
                        (outcome.cause as? usb.ventoy.VentoySetup.FolderUnavailableException)
                            ?.problem
                            ?: usb.ventoy.FolderReadProblem.Unreadable
                    post(
                        UiIntent.VentoyFoldersLoaded(
                            emptyList(),
                            problem,
                            epoch = expectedEpoch,
                            requestToken = requestToken,
                            path = path,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun settingsSuffix(selected: Int, written: Boolean): String =
        when {
            selected == 0 -> "; no global settings chosen"
            !written -> "; selected global settings made no change"
            else -> "; $selected global setting" + (if (selected == 1) " saved" else "s saved")
        }

    private fun applyVentoyControls(effect: UiEffect.ApplyVentoyControls) {
        val source = effect.source
        if (source is VentoySettingsSource.Document) {
            applyVentoyControlsDocument(effect, source)
            return
        }
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedIdentity = state.ventoySettingsSourceIdentity
        if (!state.ventoySettingsSource.sameTarget(source)) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            return
        }
        val title = OperationTitle.SetVentoyGlobalSetting
        val op = object : DiskOperation(title.logLabel) {
            @Volatile var result: String? = null

            override suspend fun run() {
                require(effect.attachGeneration == null || effect.attachGeneration == expectedAttach) {
                    "USB attach generation changed"
                }
                require(effect.identity == null || expectedIdentity == effect.identity) {
                    "settings source belongs to another attachment"
                }
                require(effect.partitionId == null || effect.partitionId == "1") {
                    "Ventoy data partition changed"
                }
                require(this@MainViewModel.state.ventoySettingsSource.sameTarget(source)) {
                    "settings source changed"
                }
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val probe = VentoyLayout.probe(disk)
                        ?: throw IllegalStateException("Ventoy data partition is unavailable")
                    require(effect.partitionStartLba == null || effect.partitionStartLba == probe.part1StartLba) {
                        "Ventoy data partition start changed"
                    }
                    require(effect.partitionSectorCount == null || effect.partitionSectorCount == probe.part1SectorCount) {
                        "Ventoy data partition size changed"
                    }
                    val changed =
                        when (source) {
                            VentoySettingsSource.Default ->
                                VentoySetup.applyGlobalControls(
                                    disk = disk,
                                    settings = effect.settings,
                                    onMutation = { mutationAttempted = true },
                                )
                            is VentoySettingsSource.UsbFile ->
                                VentoySetup.applyImportedFile(
                                    disk = disk,
                                    sourcePath = source.path,
                                    expectedSha256 = source.snapshotSha256,
                                    settings = effect.settings,
                                    onMutation = { mutationAttempted = true },
                                )
                            is VentoySettingsSource.Document -> error("handled by import path")
                        }
                    if (mutationAttempted) commitReached = true
                    result =
                        if (changed) {
                            "Applied ${effect.settings.size} Ventoy global setting" +
                                if (effect.settings.size == 1) "" else "s"
                        } else {
                            "No Ventoy setting changed"
                        }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = title,
                    token = token,
                    cancelable = false,
                ),
            ),
        )
        publishOperationStarted(handle, title, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = title,
                            token = token,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    )
                )
                if (state.operation?.token == token && mappedState != OperationState.Completed && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    /** Import validated phone JSON into the stick default config; never writes phone URI. */
    private fun applyVentoyControlsDocument(
        effect: UiEffect.ApplyVentoyControls,
        source: VentoySettingsSource.Document,
    ) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedIdentity = state.ventoySettingsSourceIdentity
        if (!state.ventoySettingsSource.sameTarget(source)) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            return
        }
        val title = OperationTitle.SetVentoyGlobalSetting
        val op = object : DiskOperation(title.logLabel) {
            @Volatile var result: String? = null

            override suspend fun run() {
                require(effect.attachGeneration == null || effect.attachGeneration == expectedAttach) {
                    "USB attach generation changed"
                }
                require(effect.identity == null || expectedIdentity == effect.identity) {
                    "settings source belongs to another attachment"
                }
                require(effect.partitionId == null || effect.partitionId == "1") {
                    "Ventoy data partition changed"
                }
                val importedBytes = readSettingsDocumentBytes(source)
                val imported = strictUtf8(importedBytes)
                val actualHash = sha256(importedBytes)
                require(source.snapshotSha256 == null || source.snapshotSha256 == actualHash) {
                    "phone settings changed after validation; choose the file again"
                }
                require(attachmentMatches(expectedAttach, expectedKey)) {
                    "USB device changed"
                }
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val probe = VentoyLayout.probe(disk)
                        ?: throw IllegalStateException("Ventoy data partition is unavailable")
                    require(effect.partitionStartLba == null || effect.partitionStartLba == probe.part1StartLba) {
                        "Ventoy data partition start changed"
                    }
                    require(effect.partitionSectorCount == null || effect.partitionSectorCount == probe.part1SectorCount) {
                        "Ventoy data partition size changed"
                    }
                    val changed = VentoySetup.applyImportedDocument(
                        disk = disk,
                        importedJson = imported,
                        settings = effect.settings,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    result = if (changed) {
                        "Imported Ventoy global settings to ${VentoySetup.CONFIG_PATH}"
                    } else {
                        "No Ventoy setting changed"
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = title,
                    token = token,
                    cancelable = false,
                ),
            ),
        )
        publishOperationStarted(handle, title, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = title,
                            token = token,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    )
                )
                if (state.operation?.token == token && mappedState != OperationState.Completed && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun bindAutounattend(
        xmlName: String,
        imagePaths: List<String>,
        allowDestructiveTemplate: Boolean,
        expectedTemplateSha256: String?,
    ) {
        // Pinned like generate and raw write: a different stick between tap and run must fail,
        // not silently edit another disk that happens to hold the same path.
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val bindTitle = OperationTitle.BindImagesToUnattendXml
        val op = object : DiskOperation(bindTitle.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    usb.ventoy.VentoySetup.bindImages(
                        disk = disk,
                        xmlName = xmlName,
                        imagePaths = imagePaths,
                        allowDestructiveTemplate = allowDestructiveTemplate,
                        expectedTemplateSha256 = expectedTemplateSha256,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                } finally {
                    disk.close()
                }
            }
        }
        submitBindingOperation(op, bindTitle, xmlName)
    }

    private fun unbindAutounattend(xmlName: String, imagePath: String) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val unbindTitle = OperationTitle.UnbindImageFromUnattendXml
        val op = object : DiskOperation(unbindTitle.logLabel) {
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    // false means nothing was written: reporting "Completed" there would claim
                    // a change that never happened.
                    val removed = usb.ventoy.VentoySetup.unbindImage(
                        disk = disk,
                        xmlName = xmlName,
                        imagePath = imagePath,
                        onMutation = { mutationAttempted = true },
                    )
                    require(removed) {
                        "No binding for $imagePath in $xmlName; nothing was changed"
                    }
                    if (mutationAttempted) commitReached = true
                } finally {
                    disk.close()
                }
            }
        }
        submitBindingOperation(op, unbindTitle, xmlName)
    }

    private fun submitBindingOperation(op: DiskOperation, title: OperationTitle, xmlName: String) {
        val token = nextOperationToken()
        val handle = DiskController.get().submit(op, token)
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = title,
                    token = token,
                    cancelable = false,
                ),
            ),
        )
        publishOperationStarted(handle, title, token, cancelable = false)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.ui.serviceOperationUiState(outcome)
                val message = usb.ui.serviceOperationErrorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = title,
                            token = token,
                            errorMessage = message,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && mappedState == OperationState.Completed) {
                    onIntent(UiIntent.LoadWindowsImageLinks)
                    onIntent(UiIntent.ClearWindowsSelection)
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun downloadFile(binding: FilesDownloadBinding, uri: String) {
        val resolver = getApplication<Application>().contentResolver
        val pin = currentAttachmentPin()
        submitFilesOperation(
            title = OperationTitle.DownloadEntry(binding.name),
            onSuccess = {
                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SavedFile, listOf(binding.name))))
            },
        ) { operation ->
            require(FilesOperationPolicy.requireChildName(binding.name, "download") == binding.name) {
                "download target name changed"
            }
            require(binding.matches(state)) { "download target changed" }
            val volume = filesSession.volumeForLocked(pin, binding.partitionId)
            FilesOperations.download(
                resolver = resolver,
                target = binding.targetPath,
                destination = Uri.parse(uri),
                volume = volume,
                operation = operation,
            )
        }
    }

    private fun downloadBatch(selection: FilesBatchSelection, treeUri: String) {
        if (!selection.matches(state)) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return
        }
        parkedBatchWork = null
        val resolver = getApplication<Application>().contentResolver
        val pin = currentAttachmentPin()
        val rootNames = selection.items.map { it.name }
        var parkCursor = usb.files.FilesOperations.BatchDownloadCursor(remainingRoots = rootNames)
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        submitFilesOperation(
            title = OperationTitle.DownloadBatch(selection.items.size),
            onSuccess = {
                onIntent(
                    UiIntent.Status(
                        UiMessage.Key(UiMessageKey.SavedFile, listOf("${selection.items.size} entries")),
                    ),
                )
            },
            onTerminal = { _, _ -> Unit },
        ) { operation ->
            require(selection.matches(state)) { "batch download target changed" }
            val volume = filesSession.volumeForLocked(pin, selection.partitionId)
            val tree = Uri.parse(treeUri)
            val folder = SafBackupFolder(resolver, tree)
            val destParent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            try {
                FilesOperations.downloadChildren(
                    resolver = resolver,
                    volume = volume,
                    parentPath = selection.directory,
                    names = rootNames,
                    destTree = folder,
                    destParent = destParent,
                    operation = operation,
                    conflict = { conflict ->
                        val park =
                            FilesBatchConflictPark(
                                conflict = conflict,
                                cursor = parkCursor,
                            )
                        lastPark.set(park)
                        throw park
                    },
                    onCursor = { parkCursor = it },
                )
            } catch (park: FilesBatchConflictPark) {
                lastPark.compareAndSet(null, park)
                throw park
            }
        }.also { handle ->
            watchBatchPark(
                handle = handle,
                lastPark = lastPark,
                stillValid = { selection.matches(state) },
                onPark = { park ->
                    parkedBatchWork =
                        ParkedFilesBatch.Download(
                            selection = selection,
                            treeUri = treeUri,
                            cursor = park.cursor ?: parkCursor,
                            conflict = park.conflict,
                        )
                    onIntent(UiIntent.FilesBatchConflict(park.conflict))
                },
            )
        }
    }

    private fun uploadBatchDocsPinMatches(
        identity: String,
        attachGeneration: Long,
        partitionId: String,
        directory: String,
        startLba: ULong?,
        sectorCount: ULong?,
    ): Boolean {
        val device = state.device ?: return false
        if (device.identity != identity || device.attachGeneration != attachGeneration) return false
        if (state.selectedPartitionId != partitionId || state.currentPath != directory) return false
        val partition = state.partitions.firstOrNull { it.id == partitionId } ?: return false
        return partition.startLba == startLba && partition.sectorCount == sectorCount
    }

    private fun uploadBatchDocs(effect: UiEffect.UploadBatchDocs) {
        if (!uploadBatchDocsPinMatches(
                identity = effect.identity,
                attachGeneration = effect.attachGeneration,
                partitionId = effect.partitionId,
                directory = effect.directory,
                startLba = effect.startLba,
                sectorCount = effect.sectorCount,
            )
        ) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return
        }
        parkedBatchWork = null
        val resolver = getApplication<Application>().contentResolver
        val remaining = effect.uris.toMutableList()
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        withWritablePartitionId { partitionId ->
            if (partitionId != effect.partitionId) {
                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.UploadBatch(effect.uris.size),
                onSuccess = {
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.UploadedFile,
                                listOf("${effect.uris.size}", effect.directory),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(
                    uploadBatchDocsPinMatches(
                        identity = effect.identity,
                        attachGeneration = effect.attachGeneration,
                        partitionId = effect.partitionId,
                        directory = effect.directory,
                        startLba = effect.startLba,
                        sectorCount = effect.sectorCount,
                    ),
                ) { "batch upload target changed" }
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    try {
                        FilesOperations.uploadDocuments(
                            resolver = resolver,
                            uris = remaining.map(Uri::parse),
                            destDir = effect.directory,
                            volume = volume,
                            operation = operation,
                            chunkBytes = TransferTuning.jniCapBytes,
                            conflict = { conflict ->
                                val park =
                                    FilesBatchConflictPark(
                                        conflict = conflict,
                                        remainingUris = remaining.toList(),
                                    )
                                lastPark.set(park)
                                throw park
                            },
                            onDocumentSettled = { uri ->
                                remaining.remove(uri.toString())
                            },
                        )
                    } catch (park: FilesBatchConflictPark) {
                        lastPark.compareAndSet(null, park)
                        throw park
                    }
                }
            }.also { handle ->
                watchBatchPark(
                    handle = handle,
                    lastPark = lastPark,
                    stillValid = {
                        uploadBatchDocsPinMatches(
                            identity = effect.identity,
                            attachGeneration = effect.attachGeneration,
                            partitionId = effect.partitionId,
                            directory = effect.directory,
                            startLba = effect.startLba,
                            sectorCount = effect.sectorCount,
                        )
                    },
                    onPark = { park ->
                        parkedBatchWork =
                            ParkedFilesBatch.UploadDocs(
                                directory = effect.directory,
                                remainingUris = park.remainingUris.ifEmpty { remaining.toList() },
                                identity = effect.identity,
                                attachGeneration = effect.attachGeneration,
                                partitionId = effect.partitionId,
                                startLba = effect.startLba,
                                sectorCount = effect.sectorCount,
                                conflict = park.conflict,
                            )
                        onIntent(UiIntent.FilesBatchConflict(park.conflict))
                    },
                )
            }
        }
    }

    private fun continueFilesBatch(decision: FilesConflictDecision) {
        val parked = parkedBatchWork
        parkedBatchWork = null
        if (parked == null) return
        when (parked) {
            is ParkedFilesBatch.Download -> {
                if (!parked.selection.matches(state)) {
                    onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
                    return
                }
                when (decision) {
                    FilesConflictDecision.Cancel -> {
                        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.CancelledStatus)))
                    }
                    FilesConflictDecision.Skip,
                    FilesConflictDecision.ReplaceFile,
                    -> resumeParkedDownload(parked, decision)
                }
            }
            is ParkedFilesBatch.UploadDocs -> {
                when (decision) {
                    FilesConflictDecision.Cancel -> {
                        onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.CancelledStatus)))
                    }
                    FilesConflictDecision.Skip,
                    FilesConflictDecision.ReplaceFile,
                    -> resumeParkedUpload(parked, decision)
                }
            }
        }
    }

    private fun resumeParkedDownload(
        parked: ParkedFilesBatch.Download,
        decision: FilesConflictDecision,
    ) {
        val selection = parked.selection
        val resolver = getApplication<Application>().contentResolver
        val pin = currentAttachmentPin()
        var parkCursor = parked.cursor
        val conflictLabel = parked.conflict.pathLabel
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        submitFilesOperation(
            title = OperationTitle.DownloadBatch(selection.items.size),
            onSuccess = {
                onIntent(
                    UiIntent.Status(
                        UiMessage.Key(UiMessageKey.SavedFile, listOf("${selection.items.size} entries")),
                    ),
                )
            },
        ) { operation ->
            require(selection.matches(state)) { "batch download target changed" }
            val volume = filesSession.volumeForLocked(pin, selection.partitionId)
            val tree = Uri.parse(parked.treeUri)
            val folder = SafBackupFolder(resolver, tree)
            val destParent = DocumentsContract.buildDocumentUriUsingTree(
                tree,
                DocumentsContract.getTreeDocumentId(tree),
            )
            var first = true
            FilesOperations.downloadChildren(
                resolver = resolver,
                volume = volume,
                parentPath = selection.directory,
                names = parkCursor.remainingRoots,
                destTree = folder,
                destParent = destParent,
                operation = operation,
                resume = parkCursor,
                conflict = { conflict ->
                    if (first && conflict.pathLabel == conflictLabel) {
                        first = false
                        if (conflict.isDirectory && decision == FilesConflictDecision.ReplaceFile) {
                            throw IOException(
                                FilesOperationPolicy.directoryConflictSkipOnlyMessage(conflict.pathLabel),
                            )
                        }
                        decision
                    } else {
                        val park =
                            FilesBatchConflictPark(
                                conflict = conflict,
                                cursor = parkCursor,
                            )
                        lastPark.set(park)
                        throw park
                    }
                },
                onCursor = { parkCursor = it },
            )
        }.also { handle ->
            watchBatchPark(
                handle = handle,
                lastPark = lastPark,
                stillValid = { selection.matches(state) },
                onPark = { park ->
                    parkedBatchWork =
                        ParkedFilesBatch.Download(
                            selection = selection,
                            treeUri = parked.treeUri,
                            cursor = park.cursor ?: parkCursor,
                            conflict = park.conflict,
                        )
                    onIntent(UiIntent.FilesBatchConflict(park.conflict))
                },
            )
        }
    }

    private fun resumeParkedUpload(
        parked: ParkedFilesBatch.UploadDocs,
        decision: FilesConflictDecision,
    ) {
        if (!uploadBatchDocsPinMatches(
                identity = parked.identity,
                attachGeneration = parked.attachGeneration,
                partitionId = parked.partitionId,
                directory = parked.directory,
                startLba = parked.startLba,
                sectorCount = parked.sectorCount,
            )
        ) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return
        }
        val resolver = getApplication<Application>().contentResolver
        val remaining = parked.remainingUris.toMutableList()
        // Capture before onDocumentSettled mutates remaining; success must not report 0.
        val resumeBatchCount = remaining.size
        val conflictLabel = parked.conflict.pathLabel
        val lastPark = java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>(null)
        withWritablePartitionId { partitionId ->
            if (partitionId != parked.partitionId) {
                onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
                return@withWritablePartitionId
            }
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.UploadBatch(resumeBatchCount),
                onSuccess = {
                    onIntent(
                        UiIntent.Status(
                            UiMessage.Key(
                                UiMessageKey.UploadedFile,
                                listOf("$resumeBatchCount", parked.directory),
                            ),
                        ),
                    )
                },
            ) { operation ->
                require(
                    uploadBatchDocsPinMatches(
                        identity = parked.identity,
                        attachGeneration = parked.attachGeneration,
                        partitionId = parked.partitionId,
                        directory = parked.directory,
                        startLba = parked.startLba,
                        sectorCount = parked.sectorCount,
                    ),
                ) { "batch upload target changed" }
                filesSession.withWritableVolume(pin, partitionId, operation) { volume ->
                    var first = true
                    FilesOperations.uploadDocuments(
                        resolver = resolver,
                        uris = remaining.map(Uri::parse),
                        destDir = parked.directory,
                        volume = volume,
                        operation = operation,
                        chunkBytes = TransferTuning.jniCapBytes,
                        conflict = { conflict ->
                            if (first && conflict.pathLabel == conflictLabel) {
                                first = false
                                if (conflict.isDirectory && decision == FilesConflictDecision.ReplaceFile) {
                                    throw IOException(
                                        FilesOperationPolicy.directoryConflictSkipOnlyMessage(
                                            conflict.pathLabel,
                                        ),
                                    )
                                }
                                decision
                            } else {
                                val park =
                                    FilesBatchConflictPark(
                                        conflict = conflict,
                                        remainingUris = remaining.toList(),
                                    )
                                lastPark.set(park)
                                throw park
                            }
                        },
                        onDocumentSettled = { uri ->
                            remaining.remove(uri.toString())
                        },
                    )
                }
            }.also { handle ->
                watchBatchPark(
                    handle = handle,
                    lastPark = lastPark,
                    stillValid = {
                        uploadBatchDocsPinMatches(
                            identity = parked.identity,
                            attachGeneration = parked.attachGeneration,
                            partitionId = parked.partitionId,
                            directory = parked.directory,
                            startLba = parked.startLba,
                            sectorCount = parked.sectorCount,
                        )
                    },
                    onPark = { park ->
                        parkedBatchWork =
                            ParkedFilesBatch.UploadDocs(
                                directory = parked.directory,
                                remainingUris = park.remainingUris.ifEmpty { remaining.toList() },
                                identity = parked.identity,
                                attachGeneration = parked.attachGeneration,
                                partitionId = parked.partitionId,
                                startLba = parked.startLba,
                                sectorCount = parked.sectorCount,
                                conflict = park.conflict,
                            )
                        onIntent(UiIntent.FilesBatchConflict(park.conflict))
                    },
                )
            }
        }
    }

    /**
     * Parallel observer for parked conflicts. Park may land as Failed (no prior mutation)
     * or Partial (after mutationAttempted via markPartialIfMutated). Cancelled with a park
     * cause is also recovered. Dialog must appear; Partial honesty for real failures stays.
     * [lastPark] is filled before the park exception escapes the op body so Partial
     * terminals still surface the dialog without reflecting into OperationHandle.
     * [stillValid] rejects stale parks after detach/partition change cleared the pin.
     */
    private fun watchBatchPark(
        handle: OperationHandle,
        lastPark: java.util.concurrent.atomic.AtomicReference<FilesBatchConflictPark?>,
        stillValid: () -> Boolean,
        onPark: (FilesBatchConflictPark) -> Unit,
    ) {
        scope.launch {
            val outcome = handle.await()
            fun fromThrowable(cause: Throwable?): FilesBatchConflictPark? {
                var cur: Throwable? = cause
                var depth = 0
                while (cur != null && depth < 8) {
                    if (cur is FilesBatchConflictPark) return cur
                    cur = cur.cause
                    depth++
                }
                return null
            }
            // lastPark is primary (filled before park escapes). Failed may also carry park
            // as cause. Partial/Cancelled rely on lastPark — sealed outcomes have no cause.
            val park =
                lastPark.get()
                    ?: (outcome as? usb.operation.OperationState.Failed)?.let {
                        fromThrowable(it.cause)
                    }
            if (park != null && stillValid()) {
                onPark(park)
            }
        }
    }
    private fun contentInfo(resolver: ContentResolver, uri: Uri): Pair<String?, Long?> {
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = try {
            resolver.query(uri, columns, null, null, null)
        } catch (_: RuntimeException) {
            // Metadata is optional; the stream itself is still opened (and may fail loudly).
            null
        } ?: return Pair(null, null)
        return cursor.use { query ->
            if (!query.moveToFirst()) {
                Pair(null, null)
            } else {
                val nameIndex = query.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                val sizeIndex = query.getColumnIndex(OpenableColumns.SIZE)
                val name =
                    if (nameIndex >= 0 && !query.isNull(nameIndex)) {
                        query.getString(nameIndex)
                    } else {
                        null
                    }
                val size =
                    if (sizeIndex >= 0 && !query.isNull(sizeIndex)) {
                        query.getLong(sizeIndex)
                    } else {
                        null
                    }
                Pair(name, size)
            }
        }
    }

    /**
     * Enqueue one real FAT operation on the shared disk thread. The mounted volume and
     * the volume side of a mutation are serialized by the files session lock; [onSuccess] re-lists
     * the affected directory after a mutation so the UI never shows invented entries.
     *
     * O2: on body failure after a real [FilesVolumeSession.volumeForLocked] acquire, the mount
     * is closed under the same lock before unlock — never a deferred launch that could close B.
     * Fail-before-acquire leaves the RO cache of another op untouched. Success keeps RO cache.
     */
    private fun submitFilesOperation(
        title: OperationTitle,
        bytesTotal: Long = -1L,
        /**
         * Bound failure intent (FilesListFailed / RawSourceCandidatesFailed).
         * Quiet work must supply this so errors never land as free Status on op B.
         */
        listFailure: ((String) -> UiIntent)? = null,
        onSuccess: (() -> Unit)? = null,
        onSuccessWithToken: ((OperationToken) -> Unit)? = null,
        onTerminal: ((usb.operation.OperationState, OperationToken) -> Unit)? = null,
        quiet: Boolean = listFailure != null,
        body: (DiskOperation) -> Unit,
    ): OperationHandle {
        val label = title.logLabel
        // Close failure must survive CancellationException paths where OperationExecutor
        // leaves failureCause null for read-only cancel. Terminal stays Cancelled.
        val closeDiagnostic = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val operation = object : DiskOperation(name = label, bytesTotal = bytesTotal) {
            override suspend fun run() {
                filesSession.withLock {
                    var acquireToken = 0L
                    var bodyError: Throwable? = null
                    try {
                        filesSession.withAcquireRecorder({ token -> acquireToken = token }) {
                            body(this)
                        }
                    } catch (t: Throwable) {
                        bodyError = t
                        throw t
                    } finally {
                        // O2: error after acquire → close before next op can enter the lock.
                        // Partial/writable already closed inside withWritableVolume → NotOwned.
                        // Fail-before-acquire (token 0) → do not touch foreign mount.
                        val err = bodyError
                        if (err != null && acquireToken != 0L) {
                            when (
                                val released =
                                    filesSession.releaseAcquiredAfterErrorLocked(acquireToken, err)
                            ) {
                                is usb.files.CloseEpochResult.ClosedFailed ->
                                    closeDiagnostic.set(released.error)
                                else -> Unit
                            }
                        }
                    }
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(operation, token)
        if (!quiet) activeHandle = handle
        activeHandles.add(handle)
        if (FilesOperationPolicy.publishOperationProgress(quiet)) {
            onIntent(
                UiIntent.OperationBegan(
                    OperationProgress(
                        state = OperationState.Queued,
                        title = title,
                        token = token,
                        bytesTotal = bytesTotal,
                    ),
                ),
            )
        }
        scope.launch {
            val ticker = launch {
                var lastLogAt = android.os.SystemClock.elapsedRealtime()
                var lastLogged = -1L
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (handle.state == usb.operation.OperationState.Running) {
                        if (FilesOperationPolicy.publishOperationProgress(quiet)) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Running,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                    ),
                                ),
                            )
                        }
                        val now = android.os.SystemClock.elapsedRealtime()
                        if (now - lastLogAt >= LOG_TICK_MS && operation.bytesDone != lastLogged && state.operation?.token == token) {
                            lastLogAt = now
                            lastLogged = operation.bytesDone
                            val total = operation.bytesTotal
                            val detail = if (total > 0) " ${(lastLogged.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else " $lastLogged bytes"
                            val message = "$label$detail"
                            android.util.Log.i("UsbMultitool", message)
                            onIntent(UiIntent.AppendLog(logLine(message)))
                        }
                    }
                }
            }
            try {
                val outcome = handle.await()
                ticker.cancel()
                val plan =
                    FilesOperationPolicy.planTerminal(
                        quiet = quiet,
                        hasBoundFailure = listFailure != null,
                    )
                fun publishCloseDiagnostics(cause: Throwable?) {
                    val stillCurrent = activeHandle === handle || quiet
                    when (
                        val d =
                            FilesOperationPolicy.planCloseDiagnostic(
                                primary = cause,
                                quiet = quiet,
                                hasBoundFailure = listFailure != null,
                                opStillCurrent = stillCurrent || activeHandle === handle,
                                explicitClose = closeDiagnostic.get(),
                            )
                    ) {
                        usb.files.CloseDiagnosticPublish.None -> Unit
                        is usb.files.CloseDiagnosticPublish.LogOnly ->
                            onIntent(UiIntent.AppendLog(logLine(d.message)))
                    }
                }
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val cause = outcome.cause
                        val message = FilesOperationPolicy.primaryErrorMessage(cause)
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!(message))
                        } else if (state.operation?.token == token) {
                            if (plan.postStatus) {
                                onIntent(UiIntent.Status(UiMessage.Raw(message)))
                            } else {
                                onIntent(UiIntent.AppendLog(logLine("$label failed: $message")))
                            }
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Failed,
                                        title = title,
                                        token = token,
                                        errorMessage = message,
                                    ),
                                )
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome, token)
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!("Cancelled"))
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Cancelled,
                                        title = title,
                                        token = token,
                                    ),
                                ),
                            )
                        }
                        // RO cancel: executor drops failureCause; close still logged via closeDiagnostic.
                        // Terminal stays Cancelled — never promote to Failed for close-on-cancel.
                        publishCloseDiagnostics(operation.failureCause)
                        onTerminal?.invoke(outcome, token)
                    }
                    is usb.operation.OperationState.Partial -> {
                        // withWritableVolume already closed under lock; do not close again.
                        val cause = operation.failureCause
                        val message =
                            FilesOperationPolicy.primaryErrorMessage(
                                cause ?: Exception("Incomplete operation"),
                            )
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!(message))
                        } else if (state.operation?.token == token && plan.postStatus) {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Partial,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                        errorMessage = message,
                                    ),
                                ),
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome, token)
                    }
                    else -> {
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Completed,
                                        title = title,
                                        token = token,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                    ),
                                ),
                            )
                        }
                        if (state.operation?.token == token) onSuccess?.invoke()
                        onSuccessWithToken?.invoke(token)
                        onTerminal?.invoke(outcome, token)
                    }
                }
            } finally {
                ticker.cancel()
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            }
        }
        return handle
    }

    /**
     * Read-only disk work. The mounted FAT volume is closed first because it holds the
     * USB claim; [body] then runs on the shared disk thread and returns the summary shown
     * to the user. Structured extras (e.g. read-scan numbers) go on [ReadOnlySink].
     * Nothing here writes to the stick.
     */
    private class ReadOnlySink {
        @Volatile var readScanResult: ReadScanOutcome? = null
    }

    private fun submitReadOnlyDiskOperation(
        title: OperationTitle,
        cancelable: Boolean = true,
        /**
         * Runs after the files mount is closed and before the raw USB open with the **same**
         * attachment snapshot the open is pinned to: revalidate against this value only, so
         * the check and the open cannot observe two different publishes. Throw to abort; the
         * USB claim is released and nothing is opened.
         */
        beforeOpen: ((AttachmentState.Snapshot<UsbDevice>) -> Unit)? = null,
        onOpenFailure: (() -> Unit)? = null,
        body: (UsbDisk, DiskOperation, ReadOnlySink) -> String,
    ) {
        val sink = ReadOnlySink()
        val label = title.logLabel
        val operation = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                var beforeOpenAttempted = false
                var beforeOpenSucceeded = false
                val raw = try {
                    openRawDisk { live ->
                        beforeOpenAttempted = beforeOpen != null
                        beforeOpen?.invoke(live)
                        beforeOpenSucceeded = true
                        live.device ?: throw DiskException.Disconnected()
                    } ?: throw DiskException.Disconnected()
                } catch (failure: Throwable) {
                    if (beforeOpenAttempted || beforeOpenSucceeded) {
                        runCatching { onOpenFailure?.invoke() }.onFailure(failure::addSuppressed)
                    }
                    throw failure
                }
                val disk = raw.disk
                try {
                    result = body(disk, this, sink)
                } finally {
                    disk.close()
                }
            }
        }
        val token = nextOperationToken()
        val handle = DiskController.get().submit(operation, token)
        if (cancelable) activeHandle = handle
        activeHandles.add(handle)
        onIntent(
            UiIntent.OperationBegan(
                OperationProgress(
                    state = OperationState.Queued,
                    title = title,
                    token = token,
                    cancelable = cancelable,
                ),
            ),
        )
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (handle.state == usb.operation.OperationState.Running) {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Running,
                                    title = title,
                                    token = token,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    cancelable = cancelable,
                                ),
                            ),
                        )
                    }
                }
            }
            val logTicker = launch {
                var lastLogged = -1L
                while (true) {
                    delay(LOG_TICK_MS)
                    if (handle.state == usb.operation.OperationState.Running && state.operation?.token == token) {
                        val done = operation.bytesDone
                        val total = operation.bytesTotal
                        if (done != lastLogged) {
                            lastLogged = done
                            val pct = if (total > 0) " ${(done.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else ""
                            val message = "$label$pct"
                            android.util.Log.i("UsbMultitool", message)
                            onIntent(UiIntent.AppendLog(logLine(message)))
                        }
                    }
                }
            }
            try {
                val outcome = handle.await()
                ticker.cancel()
                logTicker.cancel()
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                        if (state.operation?.token == token) {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        onIntent(
                            UiIntent.OperationUpdated(
                                readOnlyOperationProgress(
                                    token = token,
                                    outcome = outcome,
                                    title = title,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    resultMessage = operation.result?.takeIf { it.isNotEmpty() },
                                    failureCause = operation.failureCause,
                                    cancelable = cancelable,
                                    readScanResult = sink.readScanResult,
                                ),
                            ),
                        )
                        if (state.operation?.token == token) {
                            scope.launch(Dispatchers.IO) { runCatching { closeFilesVolume(reportStatus = true) } }
                        }
                    }
                    else -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                readOnlyOperationProgress(
                                    token = token,
                                    outcome = outcome,
                                    title = title,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    resultMessage = operation.result?.takeIf { it.isNotEmpty() },
                                    failureCause = operation.failureCause,
                                    cancelable = cancelable,
                                    readScanResult = sink.readScanResult,
                                ),
                            ),
                        )
                    }
                }
            } finally {
                ticker.cancel()
                logTicker.cancel()
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            }
        }
    }

    /**
     * App-owned external directory (`Android/data/<package>/files`): no storage permission
     * needed and the exact path can be reported. Fails loudly instead of inventing a path.
     */
    private fun requireBackupFolder(): SafBackupFolder {
        val uri = state.backupFolderUri
            ?: throw IllegalStateException("backup folder not selected")
        require(hasPersistedWrite(uri)) {
            "backup folder access was lost; choose the folder again"
        }
        return SafBackupFolder(
            getApplication<Application>().contentResolver,
            Uri.parse(uri),
        )
    }

    private fun backupDirectory(): File =
        getApplication<Application>().getExternalFilesDir(null)
            ?: throw IllegalStateException(
                "external files directory is unavailable; cannot save the backup",
            )

    /** Read the selected SAF document, bounded by the size this device needs. */
        private fun readTextDocument(
        uriString: String,
        maxChars: Int = RescueMap.MAX_ENCODED_CHARS,
    ): String {
        val resolver = getApplication<Application>().contentResolver
        val uri = Uri.parse(uriString)
        resolver.openInputStream(uri).use { input ->
            require(input != null) { "cannot open $uriString" }
            val reader = input.bufferedReader(Charsets.UTF_8)
            val sb = StringBuilder()
            val buf = CharArray(8192)
            var total = 0
            while (true) {
                val n = reader.read(buf)
                if (n < 0) break
                total += n
                require(total <= maxChars) {
                    "document exceeds $maxChars characters: $uriString"
                }
                sb.append(buf, 0, n)
            }
            return sb.toString()
        }
    }

    private fun strictUtf8(bytes: ByteArray): String =
        try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (failure: java.nio.charset.CharacterCodingException) {
            throw VentoySettingsDocumentException("selected settings document is not valid UTF-8", failure)
        }

private fun readBackupBlob(uri: String, expectedBytes: Int): ByteArray {
        val resolver = getApplication<Application>().contentResolver
        val input = resolver.openInputStream(Uri.parse(uri))
            ?: throw IllegalStateException("cannot open $uri for reading")
        return input.use { stream ->
            val buffer = ByteArray(expectedBytes + 1)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            require(read == expectedBytes) {
                "selected file holds $read bytes; this device needs $expectedBytes " +
                    "(${GptBackup.PRIMARY_SECTORS} + ${GptBackup.BACKUP_SECTORS} GPT sectors)"
            }
            buffer.copyOf(read)
        }
    }

    private fun timestamp(): String =
        SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())

    private fun logLine(message: String): String {
        val ts = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
        return "[$ts] $message"
    }

    /** Disk thread -> main thread hop for result intents. */
    private fun post(intent: UiIntent) {
        scope.launch { onIntent(intent) }
    }
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private fun UsbFsDirEntry.toFileEntry(): FileEntry =
        FileEntry(
            name = name,
            isDirectory = isDirectory,
            sizeBytes = size.coerceAtLeast(0L).toULong(),
        )

}

class MainActivity : ComponentActivity() {
    private val viewModel by lazy {
        ViewModelProvider(this)[MainViewModel::class.java]
    }

    /** Collects reducer picker cancellations while this Activity instance is alive. */
    private val pickerEventsScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* result handled by state */ }

    private val imagePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onSafImagePicked(uri)
    }

    /** SAF file chosen for the next GPT critical-sector restore. */
    private val gptBackupPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onGptBackupPicked(uri)
    }

    private var ventoySettingsPickerIdentity: String? = null
    private var ventoySettingsPickerGeneration: Long? = null

    /**
     * SAF document chosen as read-only Ventoy settings import input; Apply writes USB target.
     */
    private val ventoySettingsPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val expectedIdentity = ventoySettingsPickerIdentity
        val expectedGeneration = ventoySettingsPickerGeneration
        ventoySettingsPickerIdentity = null
        ventoySettingsPickerGeneration = null
        if (uri == null) return@registerForActivityResult
        val live = viewModel.state.device
        if (live == null ||
            live.identity != expectedIdentity ||
            live.attachGeneration != expectedGeneration
        ) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SettingsSourceStale)))
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.onIntent(
            UiIntent.SelectVentoySettingsDocument(
                uri = uri.toString(),
                displayName = documentDisplayName(uri),
                identity = expectedIdentity,
                attachGeneration = expectedGeneration,
            ),
        )
    }

    private fun documentDisplayName(uri: Uri): String? =
        runCatching {
            contentResolver.query(
                uri,
                arrayOf(OpenableColumns.DISPLAY_NAME),
                null,
                null,
                null,
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (index < 0) null else cursor.getString(index)
            }
        }.getOrNull()

    /** Common SAF tree for image+map folders and GPT copies. */
    private val backupFolderLaunches by lazy {
        BackupFolderLaunchQueue(viewModel.backupFolderLaunchToken)
    }

    /**
     * A restored pending action was dropped after process death. Kept until the picker's
     * late result (or a retry) so a folder rebind is not reported as a successful start.
     */
    private var lostBackupAction = false

    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        // Consumed for every result (including cancel) so a stale context cannot outlive it.
        val pending = backupFolderLaunches.take()
        if (uri == null) {
            lostBackupAction = false
            return@registerForActivityResult
        }
        // Fail closed before touching the grant/folder: the stick may have been swapped
        // while the system picker was in front.
        if (pending != null) {
            val check = checkBackupTarget(pending)
            if (check != BackupTargetCheck.Attached) {
                lostBackupAction = false
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(backupTargetMessage(check))),
                )
                return@registerForActivityResult
            }
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (!viewModel.onBackupFolderPicked(uri)) {
            return@registerForActivityResult
        }
        if (pending == null) {
            // The action died with the old process: keep the folder choice but say so.
            if (lostBackupAction) {
                lostBackupAction = false
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderActionLost)),
                )
            }
            return@registerForActivityResult
        }
        // Persisting the grant/querying the tree took time; re-check before the write starts.
        val check = checkBackupTarget(pending)
        if (check != BackupTargetCheck.Attached) {
            viewModel.onIntent(
                UiIntent.Status(UiMessage.Key(backupTargetMessage(check))),
            )
            return@registerForActivityResult
        }
        viewModel.requestPinnedBackupAction(pending.action, pending)
    }

    private enum class BackupTargetCheck { Attached, Changed, Unverifiable }

    /**
     * The receiver is unregistered between onStop/onStart, so state.device may predate the
     * picker. Enumerate the live device and require the pinned key, identity, serial and the
     * current state generation before anything is persisted or dispatched. Without a serial
     * a same-key replug is unobservable, so that target is rejected as unverifiable.
     */
    private fun checkBackupTarget(pending: PendingBackupFolderAction): BackupTargetCheck {
        if (pending.action == BackupFolderAction.ChangeFolder) {
            return BackupTargetCheck.Attached
        }
        val live = liveBackupDevice(pending) ?: return BackupTargetCheck.Changed
        val liveKey = UsbPermissionPolicy.key(live.deviceId, live.deviceName)
        val liveIdentity = UsbDeviceIdentity.identityString(live)
        if (pending.identity != liveIdentity) return BackupTargetCheck.Changed
        val liveSerial =
            UsbDeviceIdentity.serialNumber(live)?.trim()?.takeIf { it.isNotEmpty() }
                ?: return BackupTargetCheck.Unverifiable
        val attachment = viewModel.attachmentSnapshot()
        if (attachment.device == null) return BackupTargetCheck.Changed
        return if (pending.accepts(liveIdentity, attachment.generation, liveKey, liveSerial)) {
            BackupTargetCheck.Attached
        } else {
            BackupTargetCheck.Changed
        }
    }

    private fun backupTargetMessage(check: BackupTargetCheck): UiMessageKey =
        if (check == BackupTargetCheck.Unverifiable) {
            UiMessageKey.BackupFolderTargetUnverifiable
        } else {
            UiMessageKey.BackupFolderTargetChanged
        }

    /** The exact pinned device, enumerated live instead of read from stale attach state. */
    private fun liveBackupDevice(pending: PendingBackupFolderAction): UsbDevice? {
        val pinnedKey = pending.deviceKey ?: return null
        return (getSystemService(Context.USB_SERVICE) as? UsbManager)
            ?.deviceList
            ?.values
            ?.firstOrNull {
                UsbPermissionPolicy.key(it.deviceId, it.deviceName) == pinnedKey &&
                    MassStorageDiscovery.findBotInterface(it) != null
            }
    }

    /** Folder containing exactly one image and its map; read-only for the restore. */
    private val rescueArchiveFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.onRescueArchiveFolderPicked(uri)
    }

    /**
     * SAF `CreateDocument` target for the redacted trace (`trace.log.1` + `trace.log`).
     * Falls back to the in-memory journal when no trace file exists yet, so the export is
     * never an empty file on a fresh install.
     */
    private val traceExportLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain"),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        val fallbackLines = viewModel.state.logLines.toList()
        pickerEventsScope.launch {
            val written = withContext(Dispatchers.IO) {
                runCatching {
                    val traceText = AppTraceLogger(filesDir).snapshot()
                    val text = traceText.ifBlank {
                        redactTrace(fallbackLines.joinToString("\n"))
                    }
                    contentResolver.openOutputStream(uri)?.use { out ->
                        out.write(text.toByteArray(Charsets.UTF_8))
                    } ?: error("cannot open the export target")
                }.isSuccess
            }
            Toast.makeText(
                this@MainActivity,
                if (written) R.string.msg_trace_exported else R.string.msg_trace_export_failed,
                Toast.LENGTH_SHORT,
            ).show()
        }
    }

    /** Windows SAF picker queue, timeout, recreation state, and result fencing. */
    private val windowsImportCoordinator: WindowsImportPickerCoordinator by lazy {
        WindowsImportPickerCoordinator(
            queue = usb.ui.WindowsImportLaunchQueue(requestCodeAllocator(applicationContext)),
            onTimeout = { context ->
                context.pickSessionId?.let { session ->
                    viewModel.onIntent(
                        UiIntent.WindowsImportPickerFinished(
                            pickSessionId = session,
                            cancelled = false,
                            failure = UiMessageKey.ImportPickerTimedOut,
                            launchSource = context.launchSource,
                        ),
                    )
                } ?: viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerTimedOut)))
            },
            onInvalidated = { context ->
                val session = context.pickSessionId
                if (session != null && viewModel.state.filesPickMode?.sessionId == session) {
                    viewModel.onIntent(
                        UiIntent.WindowsImportPickerFinished(
                            pickSessionId = session,
                            cancelled = false,
                            failure = UiMessageKey.FilesPickTargetInvalid,
                            launchSource = context.launchSource,
                        ),
                    )
                }
            },
        )
    }

    /** SAF document picked for a phone-to-stick import. */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val context = windowsImportCoordinator.takeResult(requestCode) ?: return
        val session = context.pickSessionId
        val uri = data?.data
        if (resultCode != android.app.Activity.RESULT_OK || uri == null) {
            viewModel.traceEvent("picker.windows_import.cancel", "requestCode" to requestCode)
            session?.let {
                viewModel.onIntent(
                    UiIntent.WindowsImportPickerFinished(
                        pickSessionId = it,
                        cancelled = true,
                        launchSource = context.launchSource,
                    ),
                )
            }
            return
        }
        val liveDevice = viewModel.state.device
        val livePartition = context.partitionId?.let { id ->
            viewModel.state.partitions.firstOrNull { it.id == id }
        }
        if (context.identity != liveDevice?.identity ||
            context.attachGeneration != (liveDevice?.attachGeneration ?: 0L) ||
            (context.partitionId != null &&
                (livePartition == null ||
                    livePartition.startLba != context.startLba ||
                    livePartition.sectorCount != context.sectorCount))
        ) {
            session?.let {
                viewModel.onIntent(
                    UiIntent.WindowsImportPickerFinished(
                        pickSessionId = it,
                        cancelled = false,
                        failure = UiMessageKey.FilesPickTargetInvalid,
                        launchSource = context.launchSource,
                    ),
                )
            } ?: viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return
        }
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onIntent(
            UiIntent.RequestImportWindowsIso(
                uri = uri.toString(),
                kind = context.kind,
                directory = context.directory,
                pickSessionId = context.pickSessionId,
                expectedIdentity = context.identity,
                expectedAttachGeneration = context.attachGeneration,
            ),
        )
    }

    /** Launch reducer-owned phone effect only while exact session/pin/path remains live. */
    private fun launchPinnedWindowsImport(effect: UiEffect.LaunchWindowsImport) {
        fun liveMatches(): Boolean {
            val current = viewModel.state
            val mode = current.filesPickMode ?: return false
            val device = current.device ?: return false
            val partition = current.partitions.firstOrNull { it.id == effect.partitionId }
                ?: return false
            return mode.sessionId == effect.pickSessionId &&
                mode.purpose == usb.ui.FilesPickPurpose.WindowsImage &&
                mode.matches(current) &&
                current.currentPath == safePickPath(effect.directory) &&
                device.identity == effect.identity &&
                device.attachGeneration == effect.attachGeneration &&
                partition.startLba == effect.startLba &&
                partition.sectorCount == effect.sectorCount
        }
        if (!liveMatches()) return
        val start = windowsImportCoordinator.begin(
            kind = effect.kind,
            directory = effect.directory,
            pickSessionId = effect.pickSessionId,
            identity = effect.identity,
            attachGeneration = effect.attachGeneration,
            partitionId = effect.partitionId,
            startLba = effect.startLba,
            sectorCount = effect.sectorCount,
            launchSource = usb.ui.FilesPickLaunchSource.Phone,
        )
        when (start) {
            usb.ui.WindowsImportLaunch.Busy -> {
                viewModel.onIntent(
                    UiIntent.WindowsImportPickerFinished(
                        effect.pickSessionId,
                        cancelled = false,
                        failure = UiMessageKey.ImportPickerBusy,
                        launchSource = usb.ui.FilesPickLaunchSource.Phone,
                    ),
                )
                return
            }
            usb.ui.WindowsImportLaunch.Exhausted -> {
                viewModel.onIntent(
                    UiIntent.WindowsImportPickerFinished(
                        effect.pickSessionId,
                        cancelled = false,
                        failure = UiMessageKey.ImportPickerExhausted,
                        launchSource = usb.ui.FilesPickLaunchSource.Phone,
                    ),
                )
                return
            }
            usb.ui.WindowsImportLaunch.StorageFailed -> {
                viewModel.onIntent(
                    UiIntent.WindowsImportPickerFinished(
                        effect.pickSessionId,
                        cancelled = false,
                        failure = UiMessageKey.ImportPickerUnavailable,
                        launchSource = usb.ui.FilesPickLaunchSource.Phone,
                    ),
                )
                return
            }
            is usb.ui.WindowsImportLaunch.Starting -> Unit
        }
        val context = (start as usb.ui.WindowsImportLaunch.Starting).context
        if (!liveMatches()) {
            windowsImportCoordinator.cancelByPickSession(effect.pickSessionId)
            viewModel.onIntent(
                UiIntent.WindowsImportPickerFinished(
                    effect.pickSessionId,
                    cancelled = false,
                    failure = UiMessageKey.FilesPickTargetInvalid,
                    launchSource = usb.ui.FilesPickLaunchSource.Phone,
                ),
            )
            return
        }
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        if (runCatching { startActivityForResult(intent, context.requestCode) }.isFailure) {
            windowsImportCoordinator.launchFailed(context.requestCode)
            viewModel.onIntent(
                UiIntent.WindowsImportPickerFinished(
                    effect.pickSessionId,
                    cancelled = false,
                    failure = UiMessageKey.ImportPickerUnavailable,
                    launchSource = usb.ui.FilesPickLaunchSource.Phone,
                ),
            )
        }
    }

    /** Fails closed if a picker is already open; stores context before launching. */
    private fun launchWindowsImport(
        kind: usb.ui.WindowsImportKind,
        directory: String?,
        pickSessionId: String?,
    ) {
        val start = windowsImportCoordinator.begin(
            kind = kind,
            directory = directory,
            pickSessionId = pickSessionId,
            identity = viewModel.state.device?.identity,
            attachGeneration = viewModel.state.device?.attachGeneration ?: 0L,
        )
        if (start == usb.ui.WindowsImportLaunch.Busy) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerBusy)))
            return
        }
        if (start == usb.ui.WindowsImportLaunch.Exhausted) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerExhausted)))
            return
        }
        if (start == usb.ui.WindowsImportLaunch.StorageFailed) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
            return
        }
        val context = (start as usb.ui.WindowsImportLaunch.Starting).context
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        if (runCatching { startActivityForResult(intent, context.requestCode) }.isFailure) {
            windowsImportCoordinator.launchFailed(context.requestCode)
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
        }
    }


    /** Exact source selected before SAF destination; stale callback is rejected, never retargeted. */
    private var pendingDownloadBinding: FilesDownloadBinding? = null
    /** Multi/dir download pin before OpenDocumentTree; cleared on detach/result. */
    private var pendingBatchDownload: FilesBatchSelection? = null
    /** Upload pin before OpenMultipleDocuments; cleared on detach/result. */
    private var pendingBatchUploadDirectory: String? = null
    private var pendingBatchUploadIdentity: String? = null
    private var pendingBatchUploadAttach: Long = Long.MIN_VALUE
    private var pendingBatchUploadPartition: String? = null
    private var pendingBatchUploadStart: String? = null
    private var pendingBatchUploadCount: String? = null

    private val batchDownloadTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val selection = pendingBatchDownload
        pendingBatchDownload = null
        if (uri == null) return@registerForActivityResult
        if (selection == null) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        if (!selection.matches(viewModel.state)) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        viewModel.onIntent(UiIntent.DownloadBatch(selection, uri.toString()))
    }

    private val batchUploadDocsLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments(),
    ) { uris: List<Uri> ->
        val directory = pendingBatchUploadDirectory
        val identity = pendingBatchUploadIdentity
        val attach = pendingBatchUploadAttach
        val partition = pendingBatchUploadPartition
        val start = pendingBatchUploadStart?.toULongOrNull()
        val count = pendingBatchUploadCount?.toULongOrNull()
        pendingBatchUploadDirectory = null
        pendingBatchUploadIdentity = null
        pendingBatchUploadAttach = Long.MIN_VALUE
        pendingBatchUploadPartition = null
        pendingBatchUploadStart = null
        pendingBatchUploadCount = null
        if (uris.isEmpty()) return@registerForActivityResult
        if (directory == null || identity == null || partition == null || attach == Long.MIN_VALUE) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        val live = viewModel.state
        val device = live.device
        val livePartition = live.partitions.firstOrNull { it.id == partition }
        if (device == null || device.identity != identity || device.attachGeneration != attach ||
            live.selectedPartitionId != partition || live.currentPath != directory ||
            livePartition == null || livePartition.startLba != start || livePartition.sectorCount != count
        ) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return@registerForActivityResult
        }
        viewModel.onIntent(
            UiIntent.UploadBatchDocs(
                directory = directory,
                uris = uris.map { it.toString() },
                identity = identity,
                attachGeneration = attach,
                partitionId = partition,
                startLba = start,
                sectorCount = count,
            ),
        )
    }

    private fun launchBatchUpload() {
        val live = viewModel.state
        val device = live.device
        val partition = live.selectedPartition()
        if (device == null || partition == null) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return
        }
        pendingBatchUploadDirectory = live.currentPath
        pendingBatchUploadIdentity = device.identity
        pendingBatchUploadAttach = device.attachGeneration
        pendingBatchUploadPartition = partition.id
        pendingBatchUploadStart = partition.startLba?.toString()
        pendingBatchUploadCount = partition.sectorCount?.toString()
        runCatching { batchUploadDocsLauncher.launch(arrayOf("*/*")) }
            .onFailure {
                pendingBatchUploadDirectory = null
                pendingBatchUploadIdentity = null
                pendingBatchUploadAttach = Long.MIN_VALUE
                pendingBatchUploadPartition = null
                pendingBatchUploadStart = null
                pendingBatchUploadCount = null
                viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
            }
    }

    private var pendingUsbPermissionKey: String? = null
    private var pendingUsbPermissionToken: String? = null
    private var deniedUsbPermissionKey: String? = null
    private var selectedUsbDeviceKey: String? = null
    private var usbReceiverRegistered = false

    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val binding = pendingDownloadBinding
        pendingDownloadBinding = null
        if (uri == null) return@registerForActivityResult
        if (binding == null) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        if (!binding.matches(viewModel.state)) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        viewModel.onIntent(UiIntent.DownloadFile(binding, uri.toString()))
    }
    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager
            val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    if (device != null && usbManager != null && MassStorageDiscovery.findBotInterface(device) != null) {
                        selectUsbDevice(device)
                        val hasPerm = usbManager.hasPermission(device)
                        viewModel.onUsbDeviceAttached(device, hasPerm, usbManager)
                        if (!hasPerm) requestUsbPermission(usbManager, device)
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    if (device == null || selectedUsbDeviceKey == usb.device.UsbPermissionPolicy.key(device.deviceId, device.deviceName)) {
                        selectedUsbDeviceKey = null
                        pendingUsbPermissionKey = null
                        pendingUsbPermissionToken = null
                        deniedUsbPermissionKey = null
                        pendingBatchDownload = null
                        pendingBatchUploadDirectory = null
                        pendingBatchUploadIdentity = null
                        pendingBatchUploadAttach = Long.MIN_VALUE
                        pendingBatchUploadPartition = null
                        pendingBatchUploadStart = null
                        pendingBatchUploadCount = null
                    }
                    viewModel.onUsbDeviceDetached(device)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val callbackKey = device?.let { usb.device.UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
                    val callbackToken = intent.getStringExtra(EXTRA_USB_PERMISSION_TOKEN)
                    val liveDevice = usb.device.UsbPermissionPolicy.currentMatch(device, usbManager?.deviceList?.values.orEmpty())
                    if (liveDevice != null && usb.device.UsbPermissionPolicy.accepts(
                            pendingUsbPermissionKey,
                            callbackKey,
                            selectedUsbDeviceKey,
                            pendingUsbPermissionToken,
                            callbackToken,
                        ) && MassStorageDiscovery.findBotInterface(liveDevice) != null
                    ) {
                        pendingUsbPermissionKey = null
                        pendingUsbPermissionToken = null
                        if (!granted) deniedUsbPermissionKey = callbackKey
                        viewModel.onUsbDeviceAttached(liveDevice, granted, usbManager)
                    }
                }
            }
        }
    }

    /**
     * Create actions need the common tree. When it is missing or its grant is gone, show
     * the tree picker first and resume exactly that action after the user chooses.
     */
    private fun startWithBackupFolder(action: BackupFolderAction) {
        if (viewModel.hasUsableBackupFolder()) {
            viewModel.requestBackupAction(action)
        } else {
            openBackupFolderPicker(action)
        }
    }

    /**
     * Records the pinned action before the picker opens. [BackupFolderAction.ChangeFolder]
     * first cancels a pending write action: a manual folder change must never resume an
     * earlier backup against a stick the user was no longer looking at.
     */
    private fun openBackupFolderPicker(action: BackupFolderAction) {
        if (action == BackupFolderAction.ChangeFolder) backupFolderLaunches.cancel()
        lostBackupAction = false
        val live = viewModel.attachmentSnapshot()
        val device = live.device
        val start =
            backupFolderLaunches.begin(
                action = action,
                identity = device?.let { UsbDeviceIdentity.identityString(it) },
                attachGeneration = live.generation,
                deviceKey =
                    device?.let { UsbPermissionPolicy.key(it.deviceId, it.deviceName) },
                serialNumber =
                    device?.let { UsbDeviceIdentity.serialNumber(it) }?.trim(),
            )
        when (start) {
            is BackupFolderLaunch.Starting -> {
                if (runCatching { backupFolderPicker.launch(null) }.isFailure) {
                    backupFolderLaunches.cancel()
                    viewModel.onIntent(
                        UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)),
                    )
                }
            }
            BackupFolderLaunch.Busy ->
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerBusy)),
                )
            BackupFolderLaunch.NoTarget ->
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderTargetChanged)),
                )
            BackupFolderLaunch.Unverifiable ->
                viewModel.onIntent(
                    UiIntent.Status(
                        UiMessage.Key(UiMessageKey.BackupFolderTargetUnverifiable),
                    ),
                )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Transfer knobs are read once per operation, so persisted values must be live
        // before the first disk action, not only after visiting Diagnostics.
        usb.diagnostics.PerformancePreferences.load(applicationContext)
        // A re-delivered CreateDocument result arrives after the activity is recreated
        // (rotation/process death); without this it would find no pending name and drop it.
        ventoySettingsPickerIdentity = savedInstanceState?.getString(STATE_VENTOY_SETTINGS_IDENTITY)
        ventoySettingsPickerGeneration = savedInstanceState?.getLong(STATE_VENTOY_SETTINGS_ATTACH, Long.MIN_VALUE)
            ?.takeIf { it != Long.MIN_VALUE }
        pendingDownloadBinding = savedInstanceState?.let { saved ->
            val directory = saved.getString(STATE_PENDING_DOWNLOAD_DIRECTORY)
            val name = saved.getString(STATE_PENDING_DOWNLOAD_NAME)
            val size = saved.getString(STATE_PENDING_DOWNLOAD_SIZE)?.toULongOrNull()
            val identity = saved.getString(STATE_PENDING_DOWNLOAD_IDENTITY)
            val partition = saved.getString(STATE_PENDING_DOWNLOAD_PARTITION)
            val start = saved.getString(STATE_PENDING_DOWNLOAD_START)?.toULongOrNull()
            val count = saved.getString(STATE_PENDING_DOWNLOAD_COUNT)?.toULongOrNull()
            val generation = saved.getLong(STATE_PENDING_DOWNLOAD_ATTACH, Long.MIN_VALUE)
            if (directory != null && name != null && size != null && identity != null &&
                partition != null && generation != Long.MIN_VALUE
            ) {
                FilesDownloadBinding(directory, name, size, identity, generation, partition, start, count)
            } else null
        }
        pendingBatchDownload = savedInstanceState?.let { saved ->
            val directory = saved.getString(STATE_PENDING_BATCH_DIRECTORY) ?: return@let null
            val identity = saved.getString(STATE_PENDING_BATCH_IDENTITY) ?: return@let null
            val partition = saved.getString(STATE_PENDING_BATCH_PARTITION) ?: return@let null
            val generation = saved.getLong(STATE_PENDING_BATCH_ATTACH, Long.MIN_VALUE)
            val names = saved.getStringArray(STATE_PENDING_BATCH_NAMES) ?: return@let null
            val dirs = saved.getBooleanArray(STATE_PENDING_BATCH_DIRS) ?: return@let null
            val sizes = saved.getStringArray(STATE_PENDING_BATCH_SIZES) ?: return@let null
            if (generation == Long.MIN_VALUE || names.size != dirs.size || names.size != sizes.size) return@let null
            val items = names.indices.map { index ->
                FilesBatchItem(names[index], dirs[index], sizes[index].toULongOrNull() ?: return@let null)
            }
            FilesBatchSelection(
                directory = directory,
                items = items,
                identity = identity,
                attachGeneration = generation,
                partitionId = partition,
                startLba = saved.getString(STATE_PENDING_BATCH_START)?.toULongOrNull(),
                sectorCount = saved.getString(STATE_PENDING_BATCH_COUNT)?.toULongOrNull(),
            )
        }
        savedInstanceState?.let { saved ->
            pendingBatchUploadDirectory = saved.getString(STATE_PENDING_BATCH_UPLOAD_DIRECTORY)
            pendingBatchUploadIdentity = saved.getString(STATE_PENDING_BATCH_UPLOAD_IDENTITY)
            pendingBatchUploadPartition = saved.getString(STATE_PENDING_BATCH_UPLOAD_PARTITION)
            pendingBatchUploadStart = saved.getString(STATE_PENDING_BATCH_UPLOAD_START)
            pendingBatchUploadCount = saved.getString(STATE_PENDING_BATCH_UPLOAD_COUNT)
            val attach = saved.getLong(STATE_PENDING_BATCH_UPLOAD_ATTACH, Long.MIN_VALUE)
            pendingBatchUploadAttach = if (
                pendingBatchUploadDirectory != null && pendingBatchUploadIdentity != null &&
                pendingBatchUploadPartition != null && attach != Long.MIN_VALUE
            ) attach else {
                pendingBatchUploadDirectory = null
                pendingBatchUploadIdentity = null
                pendingBatchUploadPartition = null
                pendingBatchUploadStart = null
                pendingBatchUploadCount = null
                Long.MIN_VALUE
            }
        }
         val savedState = savedInstanceState
        // Windows picker state is restored as one fenced context; coordinator owns its
        // request-code, timeout, and saved-state invariants.
        when (windowsImportCoordinator.restore(savedInstanceState)) {
            WindowsImportPickerCoordinator.RestoreResult.Unavailable ->
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)),
                )
            WindowsImportPickerCoordinator.RestoreResult.Ignored,
            WindowsImportPickerCoordinator.RestoreResult.Restored -> Unit
        }
        pickerEventsScope.launch {
            viewModel.pickerEvents.collect { event ->
                when (event) {
                    is UiEffect.CancelPendingWindowsImport ->
                        windowsImportCoordinator.cancelByPickSession(event.pickSessionId)
                    is UiEffect.LaunchWindowsImport -> launchPinnedWindowsImport(event)
                    else -> Unit
                }
            }
        }
        val restoredFolderAction =
            savedState
                ?.getString(STATE_PENDING_FOLDER_ACTION)
                ?.let { stored -> runCatching { BackupFolderAction.valueOf(stored) }.getOrNull() }
        lostBackupAction = savedState?.getBoolean(STATE_PENDING_FOLDER_LOST, false) ?: false
        if (savedState != null && restoredFolderAction != null) {
            // restore() drops the context when the token belongs to a dead process.
            val restored =
                backupFolderLaunches.restore(
                    PendingBackupFolderAction(
                        action = restoredFolderAction,
                        identity = savedState.getString(STATE_PENDING_FOLDER_IDENTITY),
                        attachGeneration = savedState.getLong(STATE_PENDING_FOLDER_ATTACH, 0L),
                        deviceKey = savedState.getString(STATE_PENDING_FOLDER_DEVICE_KEY),
                        serialNumber = savedState.getString(STATE_PENDING_FOLDER_SERIAL),
                        sessionToken = savedState.getString(STATE_PENDING_FOLDER_TOKEN).orEmpty(),
                    ),
                )
            if (!restored && restoredFolderAction != BackupFolderAction.ChangeFolder) {
                lostBackupAction = true
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderActionLost)),
                )
            }
        }
        requestPermissionsIfNeeded()
        setContent {
            AppShell(
                state = viewModel.state,
                onIntent = { intent ->
                    viewModel.onIntent(intent)
                },
                onPickImage = {
                    // A raw image may be called anything; filtering by MIME hid real files.
                    imagePickerLauncher.launch(arrayOf("*/*"))
                },
                onRequestDownload = { binding ->
                    pendingDownloadBinding = binding
                    downloadLauncher.launch(binding.name)
                },
                onRequestBatchDownload = { selection ->
                    pendingBatchDownload = selection
                    runCatching { batchDownloadTreeLauncher.launch(null) }
                        .onFailure {
                            pendingBatchDownload = null
                            viewModel.onIntent(
                                UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)),
                            )
                        }
                },
                onRequestBatchUpload = { launchBatchUpload() },
                onPickWindowsFile = { kind, directory ->
                    // Images and XML templates may have any name or MIME type; filtering hid
                    // real files, and the host validates the content, not the extension.
                    launchWindowsImport(kind, directory, pickSessionId = null)
                },
                onPickWindowsImage = { directory, sessionId ->
                    launchWindowsImport(
                        usb.ui.WindowsImportKind.Image,
                        directory,
                        sessionId,
                    )
                },
                onPickVentoySettingsFile = {
                    val live = viewModel.state.device
                    ventoySettingsPickerIdentity = live?.identity
                    ventoySettingsPickerGeneration = live?.attachGeneration
                    ventoySettingsPickerLauncher.launch(arrayOf("*/*"))
                },
                onPickBackup = {
                    gptBackupPickerLauncher.launch(arrayOf("*/*"))
                },
                onPickBackupFolder = {
                    openBackupFolderPicker(BackupFolderAction.ChangeFolder)
                },
                onStartRescueBackup = {
                    startWithBackupFolder(BackupFolderAction.RescueBackup)
                },
                onCreateGptCopy = {
                    startWithBackupFolder(BackupFolderAction.GptCopy)
                },
                onPickRestoreFolder = {
                    rescueArchiveFolderPicker.launch(null)
                },
                onExportTrace = {
                    traceExportLauncher.launch("usb-multitool-trace.log")
                },
            )
        }
    }
    override fun onResume() {
        super.onResume()
        val live = viewModel.state.device
        windowsImportCoordinator.onResume(
            identity = live?.identity,
            attachGeneration = live?.attachGeneration ?: 0L,
            pickSessionId = viewModel.state.filesPickMode?.sessionId,
            partitionId = viewModel.state.filesPickMode?.partitionId,
            startLba = viewModel.state.filesPickMode?.startLba,
            sectorCount = viewModel.state.filesPickMode?.sectorCount,
        )
        backupFolderLaunches.pending?.let { pending ->
            if (pending.action == BackupFolderAction.ChangeFolder) {
                // Nothing to resume: the result itself only rebinds the folder. Retiring the
                // context here keeps a lost manual picker from blocking the next backup.
                backupFolderLaunches.cancel()
                return@let
            }
            val check = checkBackupTarget(pending)
            if (check != BackupTargetCheck.Attached) {
                backupFolderLaunches.cancel()
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(backupTargetMessage(check))),
                )
            }
        }
    }

    override fun onPause() {
        // The picker itself is in front; do not expire a context the user is browsing.
        windowsImportCoordinator.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (usbReceiverRegistered) {
            runCatching { unregisterReceiver(usbReceiver) }
            usbReceiverRegistered = false
        }
        windowsImportCoordinator.onDestroy()
        pickerEventsScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        windowsImportCoordinator.saveState(outState)
        outState.putString(STATE_VENTOY_SETTINGS_IDENTITY, ventoySettingsPickerIdentity)
        ventoySettingsPickerGeneration?.let {
            outState.putLong(STATE_VENTOY_SETTINGS_ATTACH, it)
        }
        pendingDownloadBinding?.let { binding ->
            outState.putString(STATE_PENDING_DOWNLOAD_DIRECTORY, binding.directory)
            outState.putString(STATE_PENDING_DOWNLOAD_NAME, binding.name)
            outState.putString(STATE_PENDING_DOWNLOAD_SIZE, binding.sizeBytes.toString())
            outState.putString(STATE_PENDING_DOWNLOAD_IDENTITY, binding.identity)
            outState.putLong(STATE_PENDING_DOWNLOAD_ATTACH, binding.attachGeneration)
            outState.putString(STATE_PENDING_DOWNLOAD_PARTITION, binding.partitionId)
            binding.startLba?.let { outState.putString(STATE_PENDING_DOWNLOAD_START, it.toString()) }
            binding.sectorCount?.let { outState.putString(STATE_PENDING_DOWNLOAD_COUNT, it.toString()) }
        }
        pendingBatchDownload?.let { selection ->
            outState.putString(STATE_PENDING_BATCH_DIRECTORY, selection.directory)
            outState.putStringArray(STATE_PENDING_BATCH_NAMES, selection.items.map { it.name }.toTypedArray())
            outState.putBooleanArray(STATE_PENDING_BATCH_DIRS, selection.items.map { it.isDirectory }.toBooleanArray())
            outState.putStringArray(STATE_PENDING_BATCH_SIZES, selection.items.map { it.sizeBytes.toString() }.toTypedArray())
            outState.putString(STATE_PENDING_BATCH_IDENTITY, selection.identity)
            outState.putLong(STATE_PENDING_BATCH_ATTACH, selection.attachGeneration)
            outState.putString(STATE_PENDING_BATCH_PARTITION, selection.partitionId)
            selection.startLba?.let { outState.putString(STATE_PENDING_BATCH_START, it.toString()) }
            selection.sectorCount?.let { outState.putString(STATE_PENDING_BATCH_COUNT, it.toString()) }
        }
        if (pendingBatchUploadDirectory != null && pendingBatchUploadIdentity != null &&
            pendingBatchUploadPartition != null && pendingBatchUploadAttach != Long.MIN_VALUE
        ) {
            outState.putString(STATE_PENDING_BATCH_UPLOAD_DIRECTORY, pendingBatchUploadDirectory)
            outState.putString(STATE_PENDING_BATCH_UPLOAD_IDENTITY, pendingBatchUploadIdentity)
            outState.putLong(STATE_PENDING_BATCH_UPLOAD_ATTACH, pendingBatchUploadAttach)
            outState.putString(STATE_PENDING_BATCH_UPLOAD_PARTITION, pendingBatchUploadPartition)
            outState.putString(STATE_PENDING_BATCH_UPLOAD_START, pendingBatchUploadStart)
            outState.putString(STATE_PENDING_BATCH_UPLOAD_COUNT, pendingBatchUploadCount)
        }
        backupFolderLaunches.pending?.let { pending ->
            outState.putString(STATE_PENDING_FOLDER_ACTION, pending.action.name)
            outState.putString(STATE_PENDING_FOLDER_IDENTITY, pending.identity)
            outState.putLong(STATE_PENDING_FOLDER_ATTACH, pending.attachGeneration)
            outState.putString(STATE_PENDING_FOLDER_DEVICE_KEY, pending.deviceKey)
            outState.putString(STATE_PENDING_FOLDER_SERIAL, pending.serialNumber)
            outState.putString(STATE_PENDING_FOLDER_TOKEN, pending.sessionToken)
        }
        outState.putBoolean(STATE_PENDING_FOLDER_LOST, lostBackupAction)
    }

    override fun onStart() {
        super.onStart()
        if (!usbReceiverRegistered) {
            val filter = IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
            // Only system USB attach/detach and our own PendingIntent broadcast arrive here,
            // so the receiver is not exported (ContextCompat keeps the 2-arg call below API 33).
            ContextCompat.registerReceiver(
                this,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            usbReceiverRegistered = true
        }
        checkAttachedDevices()
    }

    override fun onStop() {
        super.onStop()
    }



    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        checkAttachedDevices()
    }


    private fun checkAttachedDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }
        intent?.removeExtra(UsbManager.EXTRA_DEVICE)

        val currentDevices = usbManager.deviceList
        val validFromIntent = deviceFromIntent?.let { dev ->
            currentDevices[dev.deviceName]?.takeIf { MassStorageDiscovery.findBotInterface(it) != null }
        }
        val device = validFromIntent
            ?: currentDevices.values.firstOrNull { MassStorageDiscovery.findBotInterface(it) != null }
        if (device != null) {
            selectUsbDevice(device)
            val hasPerm = usbManager.hasPermission(device)
            viewModel.onUsbDeviceAttached(device, hasPerm, usbManager)
            if (!hasPerm) {
                requestUsbPermission(usbManager, device)
            }
        } else {
            selectedUsbDeviceKey = null
            pendingUsbPermissionKey = null
            pendingUsbPermissionToken = null
            deniedUsbPermissionKey = null
            viewModel.onUsbDeviceDetached()
            viewModel.onIntent(UiIntent.DeviceChanged(DevicePresence.Absent))
        }
    }

    private fun selectUsbDevice(device: UsbDevice, resetPermission: Boolean = false) {
        val key = usb.device.UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        if (resetPermission || selectedUsbDeviceKey != key) {
            pendingUsbPermissionKey = null
            pendingUsbPermissionToken = null
            deniedUsbPermissionKey = null
        }
        selectedUsbDeviceKey = key
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        val key = usb.device.UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        if (pendingUsbPermissionKey == key || deniedUsbPermissionKey == key) return
        val requestCode = usbPermissionRequestCounter.incrementAndGet().toInt()
        val token = java.util.UUID.randomUUID().toString()
        pendingUsbPermissionKey = key
        pendingUsbPermissionToken = token
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_MUTABLE
        } else {
            0
        }
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            requestCode,
            Intent(ACTION_USB_PERMISSION)
                .setPackage(packageName)
                .putExtra(EXTRA_USB_PERMISSION_TOKEN, token),
            flags,
        )
        usbManager.requestPermission(device, permissionIntent)
    }

    private fun requestPermissionsIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        // MANAGE_EXTERNAL_STORAGE stays declared in the manifest only; the app never asks
        // for it at startup because Ventoy assets and downloads are app-private now.
    }
    companion object {
        private const val ACTION_USB_PERMISSION = "usb.multitool.USB_PERMISSION"
        private const val EXTRA_USB_PERMISSION_TOKEN = "usb.multitool.USB_PERMISSION_TOKEN"
        private val usbPermissionRequestCounter = java.util.concurrent.atomic.AtomicLong()
        private const val STATE_PENDING_DOWNLOAD_DIRECTORY = "usb.multitool.PENDING_DOWNLOAD_DIRECTORY"
        private const val STATE_PENDING_DOWNLOAD_NAME = "usb.multitool.PENDING_DOWNLOAD_NAME"
        private const val STATE_PENDING_DOWNLOAD_SIZE = "usb.multitool.PENDING_DOWNLOAD_SIZE"
        private const val STATE_PENDING_DOWNLOAD_IDENTITY = "usb.multitool.PENDING_DOWNLOAD_IDENTITY"
        private const val STATE_PENDING_DOWNLOAD_ATTACH = "usb.multitool.PENDING_DOWNLOAD_ATTACH"
        private const val STATE_PENDING_DOWNLOAD_PARTITION = "usb.multitool.PENDING_DOWNLOAD_PARTITION"
        private const val STATE_PENDING_DOWNLOAD_START = "usb.multitool.PENDING_DOWNLOAD_START"
        private const val STATE_PENDING_DOWNLOAD_COUNT = "usb.multitool.PENDING_DOWNLOAD_COUNT"
        private const val STATE_PENDING_BATCH_DIRECTORY = "usb.multitool.PENDING_BATCH_DIRECTORY"
        private const val STATE_PENDING_BATCH_NAMES = "usb.multitool.PENDING_BATCH_NAMES"
        private const val STATE_PENDING_BATCH_DIRS = "usb.multitool.PENDING_BATCH_DIRS"
        private const val STATE_PENDING_BATCH_SIZES = "usb.multitool.PENDING_BATCH_SIZES"
        private const val STATE_PENDING_BATCH_IDENTITY = "usb.multitool.PENDING_BATCH_IDENTITY"
        private const val STATE_PENDING_BATCH_ATTACH = "usb.multitool.PENDING_BATCH_ATTACH"
        private const val STATE_PENDING_BATCH_PARTITION = "usb.multitool.PENDING_BATCH_PARTITION"
        private const val STATE_PENDING_BATCH_START = "usb.multitool.PENDING_BATCH_START"
        private const val STATE_PENDING_BATCH_COUNT = "usb.multitool.PENDING_BATCH_COUNT"
        private const val STATE_PENDING_BATCH_UPLOAD_DIRECTORY = "usb.multitool.PENDING_BATCH_UPLOAD_DIRECTORY"
        private const val STATE_PENDING_BATCH_UPLOAD_IDENTITY = "usb.multitool.PENDING_BATCH_UPLOAD_IDENTITY"
        private const val STATE_PENDING_BATCH_UPLOAD_ATTACH = "usb.multitool.PENDING_BATCH_UPLOAD_ATTACH"
        private const val STATE_PENDING_BATCH_UPLOAD_PARTITION = "usb.multitool.PENDING_BATCH_UPLOAD_PARTITION"
        private const val STATE_PENDING_BATCH_UPLOAD_START = "usb.multitool.PENDING_BATCH_UPLOAD_START"
        private const val STATE_PENDING_BATCH_UPLOAD_COUNT = "usb.multitool.PENDING_BATCH_UPLOAD_COUNT"
        private const val STATE_PENDING_FOLDER_ACTION = "usb.multitool.PENDING_FOLDER_ACTION"
        private const val STATE_PENDING_FOLDER_IDENTITY =
            "usb.multitool.PENDING_FOLDER_IDENTITY"
        private const val STATE_PENDING_FOLDER_ATTACH =
            "usb.multitool.PENDING_FOLDER_ATTACH"
        private const val STATE_PENDING_FOLDER_DEVICE_KEY =
            "usb.multitool.PENDING_FOLDER_DEVICE_KEY"
        private const val STATE_PENDING_FOLDER_SERIAL =
            "usb.multitool.PENDING_FOLDER_SERIAL"
        private const val STATE_PENDING_FOLDER_LOST =
            "usb.multitool.PENDING_FOLDER_LOST"
        private const val STATE_PENDING_FOLDER_TOKEN =
            "usb.multitool.PENDING_FOLDER_TOKEN"
        private const val STATE_VENTOY_SETTINGS_IDENTITY =
            "usb.multitool.VENTOY_SETTINGS_IDENTITY"
        private const val STATE_VENTOY_SETTINGS_ATTACH =
            "usb.multitool.VENTOY_SETTINGS_ATTACH"

        @Volatile
        private var sharedImportCodes: WindowsImportRequestCodes? = null

        /**
         * One allocator per process, shared by every Activity instance that the USB
         * attach intent may create. Its lock makes read-advance-write durable and atomic.
         */
        private fun requestCodeAllocator(context: Context): WindowsImportRequestCodes =
            sharedImportCodes ?: synchronized(this) {
                sharedImportCodes ?: WindowsImportRequestCodes(
                    SharedPreferencesRequestCodeStorage(
                        context.applicationContext.getSharedPreferences(
                            WINDOWS_IMPORT_PREFS,
                            Context.MODE_PRIVATE,
                        ),
                    ),
                ).also { sharedImportCodes = it }
            }
    }
}
