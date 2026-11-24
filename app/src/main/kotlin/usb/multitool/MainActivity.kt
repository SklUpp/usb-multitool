package usb.multitool

import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.AttachmentState
import usb.files.CloseEpochResult
import usb.files.FilesVolumeSession
import usb.files.FilesOperationPolicy
import usb.files.FilesOperationRunner

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.app.Application
import android.content.ContentResolver
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.graphics.Color as AndroidColor
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import usb.device.DiskException
import usb.device.TransferTuning
import usb.device.MassStorageDiscovery
import usb.device.UsbDisk
import usb.device.UsbExclusiveClaim
import usb.device.UsbPermissionPolicy
import usb.device.withWriteTracking
import usb.service.DiskController
import usb.operation.DiskOperation
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
import usb.ui.OperationFailure
import usb.ui.OperationProgress
import usb.ui.OperationTitle
import usb.ui.ReadScanOutcome
import usb.ui.SmartOutcome

import usb.ui.RestoreSourceInspector
import usb.ui.toOperationFailure
import usb.ui.toOperationResult
import usb.ui.OperationState
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UiReducer
import usb.ui.WindowsImportRequestCodes
import usb.ui.WindowsImportPickerCoordinator
import usb.ui.AppLanguage
import usb.diagnostics.AppTraceLogger
import usb.diagnostics.Diagnostics
import usb.diagnostics.redactTrace
import usb.diagnostics.Rescue
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueOperations
import usb.diagnostics.BackupArchive
import usb.diagnostics.RescueArchiveOperations
import usb.diagnostics.RescueBackupOperations
import usb.diagnostics.RescueRestorePrep
import usb.saf.SafArchiveTarget
import usb.saf.SafBackupFolder
import usb.saf.SafPositionalImage
import usb.fs.UsbFsDirEntry
import usb.fs.Filesystem
import usb.fs.FormatCatalog
import usb.fs.Formatter
import usb.ui.withStatus
import usb.ui.fatChildPath
import usb.ui.rawSourceKey
import usb.ui.FilesDownloadBinding
import usb.ui.FilesBatchSelection
import usb.ui.UiAttachmentPin
import usb.ui.FilesConflict
import usb.ui.FilesConflictDecision
import usb.ui.PartitionBinding
import usb.ui.PartitionRef
import usb.ui.PartitionRole
import usb.ui.PartitionTab
import usb.ui.PickerSlot
import usb.ui.PickerVerdict
import usb.ui.UploadTarget
import usb.ui.matches
import usb.ui.safePickPath
import usb.ui.stageVerdict
import usb.ui.toTableScheme
import usb.partition.GptBackup
import usb.partition.PartitionTable
import usb.image.ImageStaging
import usb.image.stageRawImageChunks
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

private fun <T> fingerprintOrNull(block: () -> T): T? = try {
    block()
} catch (cancel: kotlinx.coroutines.CancellationException) {
    throw cancel
} catch (_: DiskException) {
    null
} catch (_: IllegalArgumentException) {
    null
}
private const val WINDOWS_IMPORT_PREFS = "usb.multitool.windows_import"
private const val WINDOWS_IMPORT_NEXT_CODE_KEY = "next_request_code"

/** GPT critical backups in the shared backup folder use this deterministic prefix. */
private fun isGptBackupName(name: String): Boolean =
    name.startsWith("gpt-critical-", ignoreCase = true) &&
        name.endsWith(".bin", ignoreCase = true)



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
        const val LOG_TICK_MS = 1_000L
    }

    var state by mutableStateOf(AppUiState())
        private set

    /** Owns download/batch picker slots and the FIFO effect channel for launches. */
    internal val safPicker =
        SafPickerCoordinator(
            resolver = application.contentResolver,
            intents = { onIntent(it) },
        )
    val pickerEvents: Flow<UiEffect> = safPicker.effects

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    /** Survives ViewModel scope cancellation so completion can still release the handle. */
    private var activeHandle: OperationHandle? = null
    private var rawSourceHandle: OperationHandle? = null
    internal val pendingDownloadBinding: FilesDownloadBinding?
        get() = safPicker.pendingDownloadBinding
    internal val pendingDownloadRequestId: Long
        get() = safPicker.pendingDownloadRequestId
    @Volatile private var pendingRawStagingPath: String? = null
    private val rawMeasurement by lazy {
        RawSourceMeasurementCoordinator(
            scope = scope,
            resolver = { getApplication<Application>().contentResolver },
            state = { state },
            onIntent = ::onIntent,
        )
    }
    // Parked batch transfer and conflict types now live on FilesCoordinator.
    private var windowsImageHandle: OperationHandle? = null
    private val activeHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<OperationHandle>()
    private val operationGenerations = java.util.concurrent.ConcurrentHashMap<OperationToken, Long>()
    /** Attachment identity, published atomically: device and generation always change together. */
    private val attachment = AttachmentState<UsbDevice>()
    private val attachGeneration: Long get() = attachment.snapshot().generation
    private val attachedDevice: UsbDevice? get() = attachment.snapshot().device
    private fun nextOperationToken(): OperationToken = OperationTokenGenerator.next()
    // GPT/rescue/folder pick generations live on SafBackupCoordinator.
    private val ventoyUpstreamCache by lazy {
        usb.ventoy.VentoyUpstreamCache(getApplication<Application>())
    }
    @Volatile
    var usbManager: UsbManager? = null
        private set

    /**
     * One token per ViewModel (survives rotation, not process death). A backup-folder pick
     * restored from saved state keeps the old token, so a new process can never consume it.
     */
    val backupFolderLaunchToken: String = java.util.UUID.randomUUID().toString()

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

    private val safBackup by lazy {
        SafBackupCoordinator(
            scope = scope,
            resolver = getApplication<Application>().contentResolver,
            intents = { onIntent(it) },
            log = { msg ->
                val parts = msg.split(' ', limit = 2)
                if (parts.size == 2 && '=' in parts[1]) {
                    val fields =
                        parts[1].split(' ').mapNotNull { token ->
                            val eq = token.indexOf('=')
                            if (eq <= 0) null
                            else token.substring(0, eq) to token.substring(eq + 1)
                        }
                    trace.log(parts[0], *fields.toTypedArray())
                } else {
                    trace.log(msg)
                }
            },
            prefs = languagePrefs,
            backupFolderUriKey = BACKUP_FOLDER_URI_KEY,
            backupFolderNameKey = BACKUP_FOLDER_NAME_KEY,
            liveState = { state },
        )
    }


    private val backupActions by lazy {
        BackupActionCoordinator(
            attachment = { attachment.snapshot() },
            state = { state },
            safBackup = safBackup,
            intents = ::onIntent,
            logFolderLost = { trace.log("backup.folder_lost", "action" to it.name) },
        )
    }

    private val filesCoordinator by lazy {
        FilesCoordinator(
            scope = scope,
            volume = filesSession,
            runner = filesOperationRunner,
            submitter = DiskController.get(),
            pin = ::currentAttachmentPin,
            state = { state },
            intents = { onIntent(it) },
            log = { msg ->
                val parts = msg.split(' ', limit = 2)
                if (parts.size == 2 && '=' in parts[1]) {
                    val fields =
                        parts[1].split(' ').mapNotNull { token ->
                            val eq = token.indexOf('=')
                            if (eq <= 0) null
                            else token.substring(0, eq) to token.substring(eq + 1)
                        }
                    trace.log(parts[0], *fields.toTypedArray())
                } else {
                    trace.log(msg)
                }
            },
            onHandleStarted = { handle, makeCurrent ->
                if (makeCurrent) activeHandle = handle
                activeHandles.add(handle)
            },
            onHandleFinished = { handle ->
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            },
            resolver = { getApplication<Application>().contentResolver },
            nextToken = ::nextOperationToken,
        )
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
        // Purge a server answer cached by a previous app version before it can be used.
        ventoyUpstreamCache.readRelease()
        if (folder.usable) {
            folder.uri?.let { stored ->
                // The shared folder survives restarts; restore its paired artifacts
                // without forcing the user to pick it again.
                safBackup.rescanPersistedFolder(
                    state.copy(
                        backupFolderUri = stored,
                        backupFolderUsable = true,
                    ),
                )
            }
        }
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
    }
    /**
     * Owns the mounted FAT volume of the selected partition via [FilesVolumeSession].
     * Every files operation runs inside its lock, so a close can never race a mounted read.
     * Ceiling: detach/onCleared can wait for one files operation; upgrade only if a single
     * FAT call ever becomes unbounded.
     */
    private val filesSession: FilesVolumeSession =
        FilesVolumeSession(
            currentAttachment = { currentAttachmentPin() },
            openMounted = { partitionId, writable, pin, lease ->
                usbOpenBoundary.openMountedFilesVolume(partitionId, writable, pin, lease)
            },
            claim = DiskController.get().usbClaim,
        )
    private val filesOperationRunner = FilesOperationRunner(filesSession)
    private val usbOpenBoundary: UsbOpenBoundary by lazy {
        UsbOpenBoundary(
            currentAttachment = { attachment.snapshot() },
            usbManager = { usbManager },
            filesSession = filesSession,
            trace = trace,
            deviceKey = ::deviceKey,
        )
    }
    private val destructive by lazy {
        DestructiveOperationCoordinator(
            application = getApplication(),
            scope = scope,
            state = { state },
            attachGeneration = { attachGeneration },
            openRawDisk = ::openRawDisk,
            intents = ::onIntent,
            logLine = ::logLine,
            nextToken = ::nextOperationToken,
            activeHandle = { activeHandle },
            setActiveHandle = { activeHandle = it },
            activeHandles = activeHandles,
            refreshPartitions = ::refreshPartitions,
            readBlob = safBackup::readBlob,
            readText = ::readTextDocument,
            contentInfo = ::contentInfo,
            contentLastModified = ::contentLastModified,
            backupDirectory = ::backupDirectory,
            timestamp = ::timestamp,
            ventoyAssets = ::ventoyAssetsForInstall,
            settingsSuffix = ::settingsSuffix,
        )
    }
    private val filesystemUsage by lazy {
        FilesystemUsageCoordinator(
            scope = scope,
            state = { state },
            updateState = { state = it },
            attachment = { attachment.snapshot() },
            deviceKey = ::deviceKey,
            session = filesSession,
            intents = ::onIntent,
            deferredIntents = ::onIntentDeferred,
        )
    }

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


    override fun onCleared() {
        usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
        pendingRawStagingPath?.let { path ->
            if (state.stagedRawImagePath != path) {
                runCatching { ImageStaging.deleteIfManaged(getApplication<Application>().filesDir, path) }
            }
        }
        pendingRawStagingPath = null
        safPicker.clearDownload()
        inspectionOwner.cancel(cancelUsb = true)
        // Cancel usage/mount jobs first so async close cannot observe an empty session
        // while a still-running measure mounts after the close.
        filesystemUsage.cancel(markWaiting = false)
        val sessionToClose = filesSession
        safPicker.closeEffects()
        super.onCleared()
        scope.cancel()
        // Close off the main thread: umount/sync can block for seconds–minutes.
        // Process-lifetime DiskController scope survives this ViewModel cancel.
        DiskController.get().runProcessIo {
            val error = try {
                sessionToClose.close()
            } catch (t: Throwable) {
                t
            }
            if (error != null) {
                trace.log(
                    "volume.close.onCleared",
                    "error" to (error.message ?: error.javaClass.simpleName),
                )
            }
        }
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

    fun hasUsableBackupFolder(): Boolean = backupActions.hasUsableFolder()

    fun startCreateWithBackupFolder(action: BackupFolderAction) =
        backupActions.startCreate(action)

    fun onBackupFolderPicked(uri: Uri): Boolean = backupActions.onFolderPicked(uri)

    fun requestBackupAction(action: BackupFolderAction) = backupActions.request(action)

    fun requestPinnedBackupAction(
        action: BackupFolderAction,
        expected: PendingBackupFolderAction,
    ) = backupActions.requestPinned(action, expected)

    fun onIntent(intent: UiIntent) {
        // A reset must beat every inspection already in flight, not just the UI fields.
        when (intent) {
            UiIntent.ResetRescueSelection -> safBackup.invalidateRescueSelection()
            UiIntent.ResetGptSelection -> safBackup.invalidateGptSelection()
            else -> Unit
        }
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
            state =
                state.copy(
                    stagedRawImagePath = null,
                    stagedRawImageLabel = null,
                ).withStatus(UiMessage.Key(UiMessageKey.StaleStagedImage))
        }
        var stagingMutationFailed = false
        fun deleteOwnedOrReport(path: String?): Boolean {
            return runCatching { ImageStaging.deleteIfManaged(filesDir, path) }
                .onFailure { err ->
                    stagingMutationFailed = true
                    state = state.withStatus(
                        UiMessage.Key(
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
        var adjustedResult =
            if (preserveNoDeviceOperation && result.state.operation == null) {
                result.copy(
                    state = result.state.copy(
                        operation = state.operation,
                        // The same attachment blipped: keep the scoped log with the
                        // carried operation, not only the journal.
                        operationLogLines = state.operationLogLines,
                        // Carried forward, not a new emission: no sequence bump.
                        statusMessage = state.statusMessage,
                        statusSequence = state.statusSequence,
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
            inspectionOwner.cancel(cancelUsb = false)
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
                    val detail = current.failure?.toString()
                        ?: current.resultMessage
                        ?: current.readScanResult?.toLogLine()
                        ?: current.smartResult?.toLogLine()
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
                    // Apply to the reduced state so a new operation's cleared
                    // operationLogLines receive this first line instead of being
                    // overwritten by the pre-begin lists in the merge below.
                    adjustedResult = adjustedResult.copy(
                        state = UiReducer.reduce(
                            adjustedResult.state,
                            UiIntent.AppendLog(logLine(message)),
                        ).state,
                    )
                }
            }
        }
        if (acceptedOperationIntent &&
            (intent is UiIntent.OperationBegan || intent is UiIntent.OperationUpdated)
        ) {
            state = adjustedResult.state
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
        if (acceptedOperationIntent && intent is UiIntent.OperationBegan) {
            // Fence background measurement before any effects can start user I/O.
            filesystemUsage.cancel(markWaiting = true)
            filesystemUsage.resetPlan()
        }
        if (acceptedOperationIntent) {
            adjustedResult.effects.forEach { effect ->
                handleEffect(effect)
            }
        }
        if (completionGenerationAccepted) {
            completionResult?.effects?.forEach { effect -> handleEffect(effect) }
        }
        if (acceptedOperationIntent || completionGenerationAccepted) filesystemUsage.refresh(intent)
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
            is UiEffect.SubmitDestructive -> destructive.run(effect)
            UiEffect.ReadScan ->
                submitReadOnlyDiskOperation(OperationTitle.ReadScan) { disk, operation, sink ->
                    val report = Diagnostics.readScan(disk, operation = operation)
                    sink.readScanResult = ReadScanOutcome.from(report, disk.geometry.sectorCount)
                    // UI formats from sink; empty resultMessage avoids frozen language string.
                    ""
                }

            UiEffect.SmartRead ->
                submitReadOnlyDiskOperation(OperationTitle.SmartRead) { disk, operation, sink ->
                    val telemetry = disk.readSmartTelemetry { operation.isActive() }
                    sink.smartResult = SmartOutcome.from(telemetry)
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
                        backupActions.requirePinned(snapshot)
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
                        scope.launch(Dispatchers.Main.immediate) {
                            onRescueArchiveFolderPicked(folder.treeUri, quiet = true)
                        }
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
                    beforeOpen = backupActions::requirePinned,
                ) { disk, operation, _ ->
                    val folder = requireBackupFolder()
                    val blob = GptBackup.backupCriticalSectors(disk)
                    val geo = GptBackup.criticalGeometry(disk)
                    val info = GptCriticalBackup.inspectCaptured(
                        blob = blob,
                        disk = disk,
                        primarySectors = geo.primarySectors,
                        backupSectors = geo.backupSectors,
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
                    scope.launch(Dispatchers.Main.immediate) {
                        safBackup.rescanGptInFolder(folder.treeUri)
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
                // Legacy selected-only effect funnels into the one batch owner.
                filesystemUsage.startBatch(listOf(effect.partitionId to effect.usageKey))
            }
            is UiEffect.LoadFilesystemUsageBatch -> {
                filesystemUsage.startBatch(effect.steps)
            }
            is UiEffect.EjectDevice -> runEject(effect.ejectGeneration)
            is UiEffect.RescanAfterEject -> rescanAfterEject(effect.ejectGeneration)

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
            is UiEffect.DownloadFile -> downloadFile(effect.binding, effect.uri)
            is UiEffect.DownloadPickerReady -> safPicker.trySend(effect)
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

            is UiEffect.MeasureRawSource -> rawMeasurement.measure(effect)

            UiEffect.CancelRawSourceMeasure -> rawMeasurement.cancel()

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

            UiEffect.CancelIsoImportInspection -> inspectionOwner.cancel(cancelUsb = false)

            is UiEffect.LaunchWindowsImport,
            is UiEffect.CancelPendingWindowsImport -> {
                safPicker.trySend(effect)
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
                                resultMessage = "No image selected for the Windows setup link",
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
                        val result = outcome.toOperationResult()
                        val failure = (outcome as? usb.operation.OperationState.Failed)?.cause
                        onIntent(
                            UiIntent.OperationUpdated(
                                result.toProgress(
                                    token = token,
                                    title = OperationTitle.AutounattendXmlLink,
                                    cancelable = false,
                                    resultMessage = op.result,
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
                        if (result.state == OperationState.Completed && state.operation?.token == token) {
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
            is UiEffect.FetchVentoyControlKeyCatalog ->
                fetchVentoyControlKeyCatalog(effect.requestToken, effect.release)
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
                        effect.overwrite,
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
                                resultMessage = summary,
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
                                failure = t.toOperationFailure(),
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
        safBackup.onGptBackupPicked(uri, state)
    }

    /**
     * Manual restore-single-file picker. A `.map` name is decoded as a sector map; every
     * other document is inspected as a rescue image. The shared folder choice is cleared so
     * the visible path is the exact document the user picked.
     */
    fun onRescueFilePicked(uri: Uri) {
        safBackup.onRescueFilePicked(uri, state)
    }


    fun onRescueArchiveFolderPicked(uri: Uri, quiet: Boolean = false) {
        safBackup.onRescueArchiveFolderPicked(uri, quiet, state)
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
        if (sameDevice && state.presence == DevicePresence.Ready && !state.deviceBusy) {
            // Returning from a SAF picker must not flip a live, already-probed stick to Busy:
            // the picker result is delivered before a re-probe can publish Ready again, and
            // the queued upload would be rejected with "device busy".
            return
        }
        if (sameDevice && inspectionOwner.active?.let(::inspectionAttachmentStillHere) == true) {
            // A live phone-import inspection owns this attachment; a same-device re-probe
            // would flip presence to Busy and orphan its Inspected result. A real detach or
            // a different device never reaches this branch.
            return
        }
        if (isNewDevice) {
            inspectionOwner.cancel(cancelUsb = true)
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
            onIntent(UiIntent.AppendLog(logLine("USB attached: $key, generation $gen")))
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

        var probeCloseFailure: Throwable? = null
        val op = object : DiskOperation(name = OperationTitle.ProbeDevice.logLabel) {
            // A probe is a short metadata read, not long I/O: no service notification.
            override val foregroundService: Boolean = false
            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == gen) { "USB attach generation changed" }
                    require(deviceKey(live.device) == deviceKey(device)) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                var publish: (suspend () -> Unit)? = null
                val result = ProbePublication.run(
                    body = {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val failurePresence = ProbeFailureClassifier.presenceOf(failure)
                    val isTransport = failurePresence == DevicePresence.Disconnected
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    val wholeDiskVolume = mapWholeDiskTab(disk)
                    val installedRelease =
                        if (isTransport) {
                            usb.ventoy.VentoyInstalledVersion.Present(null)
                        } else {
                            readInstalledVentoyRelease(disk)
                        }
                    val metadataFingerprint = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.metadataLbas(table)) }
                    }
                    val initializationMetadataFingerprintGpt = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.initializationMetadataLbas(table, usb.partition.TableScheme.GPT)) }
                    }
                    val initializationMetadataFingerprintMbr = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.initializationMetadataLbas(table, usb.partition.TableScheme.MBR)) }
                    }
                    publish = { withContext(Dispatchers.Main.immediate) {
                        if (!attachmentIs(gen, device)) {
                            return@withContext
                        }
                        if (this@MainViewModel.state.isRunning) {
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
                                presence = if (failure == null) DevicePresence.Ready else failurePresence,
                                device = if (isTransport) identity else probedIdentity,
                                partitions = partitions,
                                wholeDiskVolume = wholeDiskVolume,
                                freeRegions = tableResult.getOrNull()?.freeRegions?.map { usb.ui.FreeRegionUi(it.startLba, it.sectorCount) } ?: emptyList(),
                                sectorSize = disk.sectorSize,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                                partitionTableDamaged =
                                    !isTransport && tableResult.getOrNull()?.gptPrimaryDamaged == true,
                                partitionTableAmbiguous =
                                    !isTransport && tableResult.getOrNull()?.hybridAmbiguous == true,
                                partitionTableRecognition =
                                    tableResult.getOrNull()?.recognition
                                        ?: usb.partition.TableRecognition.UNRECOGNIZED,
                                partitionTableRecognized =
                                    !isTransport && tableResult.getOrNull()?.recognized == true,
                                partitionScheme = tableResult.getOrNull()?.scheme,
                                metadataFingerprint = metadataFingerprint,
                                initializationMetadataFingerprintGpt = initializationMetadataFingerprintGpt,
                                initializationMetadataFingerprintMbr = initializationMetadataFingerprintMbr,
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
                    } }
                        failure?.let { err ->
                            state = usb.operation.OperationState.Failed(err)
                            failureCause = err
                        }
                        failure
                    },
                    close = { disk.close() },
                    publish = { publish?.invoke() },
                )
                probeCloseFailure = result.closeFailure
                result.failure?.let { failure ->
                    state = usb.operation.OperationState.Failed(failure)
                    failureCause = failure
                    throw failure
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
                                val classified = ProbeFailureClassifier.presenceOf(outcome.cause)
                                val closeFailed = probeCloseFailure != null
                                val isTransport = classified == DevicePresence.Disconnected
                                if (state.presence != DevicePresence.Ready ||
                                    classified != DevicePresence.Ready ||
                                    closeFailed
                                ) {
                                    onIntent(UiIntent.DeviceChanged(
                                        presence = when {
                                            classified == DevicePresence.Disconnected ->
                                                DevicePresence.Disconnected
                                            classified == DevicePresence.Busy ->
                                                DevicePresence.Busy
                                            classified == DevicePresence.PermissionRequired ->
                                                DevicePresence.PermissionRequired
                                            closeFailed -> DevicePresence.Busy
                                            else -> DevicePresence.Ready
                                        },
                                        device = identity,
                                        partitions = emptyList(),
                                        tableValid = false,
                                        retainExisting = false,
                                        rawGeometry = null,
                                    ))
                                }
                                val key = when {
                                    closeFailed -> UiMessageKey.VolumeSyncUnconfirmed
                                    isTransport -> UiMessageKey.CannotOpenUsb
                                    classified == DevicePresence.Busy -> UiMessageKey.DeviceBusy
                                    classified == DevicePresence.PermissionRequired ->
                                        UiMessageKey.PermissionRequired
                                    else -> UiMessageKey.CannotReadPartitionTable
                                }
                                val detail = buildString {
                                    append(outcome.cause.message ?: outcome.cause.javaClass.simpleName)
                                    probeCloseFailure?.let { closeFailure ->
                                        if (closeFailure !== outcome.cause && outcome.cause.suppressed.none { it === closeFailure }) {
                                            append("; release: ")
                                            append(closeFailure.message ?: closeFailure.javaClass.simpleName)
                                        }
                                    }
                                }
                                onIntent(UiIntent.Status(UiMessage.Key(key, listOf(detail))))
                            }
                        }
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (attachmentIs(gen, device)) {
                                onIntent(UiIntent.DeviceChanged(
                                    presence = DevicePresence.Disconnected,
                                    device = identity,
                                    partitions = emptyList(),
                                    files = emptyList(),
                                ))
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
        val snap = attachment.snapshot()
        val dev = snap.device ?: return
        if (usbManager == null) return
        val gen = snap.generation
        val op = object : DiskOperation(name = OperationTitle.RefreshPartitions.logLabel) {
            override val foregroundService: Boolean = false
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
                    val failurePresence = ProbeFailureClassifier.presenceOf(failure)
                    val isTransport = failurePresence == DevicePresence.Disconnected
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    val wholeDiskVolume = mapWholeDiskTab(disk)
                    val installedRelease =
                        if (isTransport) {
                            usb.ventoy.VentoyInstalledVersion.Present(null)
                        } else {
                            readInstalledVentoyRelease(disk)
                        }
                    val metadataFingerprint = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.metadataLbas(table)) }
                    }
                    val initializationMetadataFingerprintGpt = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.initializationMetadataLbas(table, usb.partition.TableScheme.GPT)) }
                    }
                    val initializationMetadataFingerprintMbr = tableResult.getOrNull()?.let { table ->
                        fingerprintOrNull { usb.partition.metadataFingerprint(disk, usb.partition.initializationMetadataLbas(table, usb.partition.TableScheme.MBR)) }
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
                                presence = if (failure == null) DevicePresence.Ready else failurePresence,
                                device = identity,
                                partitions = partitions,
                                wholeDiskVolume = wholeDiskVolume,
                                freeRegions = tableResult.getOrNull()?.freeRegions?.map { usb.ui.FreeRegionUi(it.startLba, it.sectorCount) } ?: emptyList(),
                                sectorSize = disk.sectorSize,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                                partitionTableDamaged =
                                    !isTransport && tableResult.getOrNull()?.gptPrimaryDamaged == true,
                                partitionTableAmbiguous =
                                    !isTransport && tableResult.getOrNull()?.hybridAmbiguous == true,
                                partitionTableRecognized =
                                    !isTransport && tableResult.getOrNull()?.recognized == true,
                                partitionScheme = tableResult.getOrNull()?.scheme,
                                partitionTableRecognition =
                                    tableResult.getOrNull()?.recognition
                                        ?: usb.partition.TableRecognition.UNRECOGNIZED,
                                metadataFingerprint = metadataFingerprint,
                                initializationMetadataFingerprintGpt = initializationMetadataFingerprintGpt,
                                initializationMetadataFingerprintMbr = initializationMetadataFingerprintMbr,
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
                            val classified = ProbeFailureClassifier.presenceOf(outcome.cause)
                            onIntent(
                                UiIntent.DeviceChanged(
                                    presence = classified,
                                    device = state.device
                                        ?: UsbDeviceIdentity.of(dev, attachGeneration = gen),
                                    partitions = emptyList(),
                                    files = emptyList(),
                                    tableValid = false,
                                ),
                            )
                            val key = when (classified) {
                                DevicePresence.Disconnected -> UiMessageKey.CannotReadUsb
                                DevicePresence.Busy -> UiMessageKey.DeviceBusy
                                DevicePresence.PermissionRequired -> UiMessageKey.PermissionRequired
                                else -> UiMessageKey.CannotReadPartitionTable
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
        rescanPersistedBackupFolderIfNeeded()
    }

    /** A folder restore scan can lose its attach ticket; retry once USB is live. */
    private fun rescanPersistedBackupFolderIfNeeded() {
        safBackup.rescanPersistedFolder(state)
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
                    logLine("USB detached: $detachedKey, generation $detachedGeneration"),
                ),
            )
        }
        filesystemUsage.resetPlan()
        filesystemUsage.cancel(markWaiting = false)
        inspectionOwner.cancel(cancelUsb = true)
        usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
        // Keep handles registered until their workers finish real close; cancellation is not close.
        activeHandle = null
        filesCoordinator.clearParked()
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

    /**
     * App-scoped eject on IO: pin attachment+generation, run the session eject under
     * the files lock, fence stale completions, never auto-reopen. Mounted path uses
     * the explicit sync/close order; unmounted path opens the pinned raw disk only to
     * sync/close it. A foreign claim owner yields busy without touching the device.
     */
    private fun runEject(ejectGeneration: Long) {
        val live = attachment.snapshot()
        val pin = currentAttachmentPin()
        val liveDevice = state.device
        val pinIdentity = liveDevice?.identity
        val pinGeneration = live.generation
        if (pin == null || liveDevice == null || pinIdentity == null) {
            onIntent(
                UiIntent.EjectResulted(
                    ejectGeneration = ejectGeneration,
                    identity = pinIdentity,
                    attachGeneration = pinGeneration,
                    succeeded = false,
                    busy = false,
                    message = null,
                ),
            )
            return
        }
        if (state.isRunning || state.operation?.state == OperationState.Queued ||
            DiskController.get().executor.hasOutstandingWork() || activeHandles.isNotEmpty()
        ) {
            onIntent(
                UiIntent.EjectResulted(
                    ejectGeneration = ejectGeneration,
                    identity = pinIdentity,
                    attachGeneration = pinGeneration,
                    succeeded = false,
                    busy = true,
                ),
            )
            return
        }
        scope.launch(Dispatchers.IO) {
            val result: CloseEpochResult = filesSession.withLock {
                filesSession.ejectLocked(
                    pin = pin,
                    openEjectedDisk = { lease -> usbOpenBoundary.openEjectedDisk(lease, pin) },
                    releaseEjectedLease = { lease -> lease.confirmHigherLevelClose() },
                )
            }
            trace.log("volume.eject", "result" to result, "gen" to pinGeneration)
            withContext(Dispatchers.Main.immediate) {
                if (!attachmentIs(pinGeneration, live.device)) {
                    // Stale completion for a detached/swapped stick: never attribute
                    // the old eject verdict to the new device. A confirmed failure
                    // still surfaces its cause (claim stays latched fail-closed);
                    // busy/stale stays a rescan prompt with nothing touched.
                    if (result is CloseEpochResult.ClosedFailed) {
                        onIntent(UiIntent.Status(UiMessage.Raw(
                            result.error.message ?: result.error.javaClass.simpleName,
                        )))
                    }
                    onIntent(
                        UiIntent.EjectResulted(
                            ejectGeneration = ejectGeneration,
                            identity = pinIdentity,
                            attachGeneration = pinGeneration,
                            succeeded = false,
                        ),
                    )
                    return@withContext
                }
                when (result) {
                    is CloseEpochResult.ClosedOk ->
                        onIntent(
                            UiIntent.EjectResulted(
                                ejectGeneration = ejectGeneration,
                                identity = pinIdentity,
                                attachGeneration = pinGeneration,
                                succeeded = true,
                            ),
                        )
                    is CloseEpochResult.ClosedFailed ->
                        onIntent(
                            UiIntent.EjectResulted(
                                ejectGeneration = ejectGeneration,
                                identity = pinIdentity,
                                attachGeneration = pinGeneration,
                                succeeded = false,
                                message = result.error.message ?: result.error.javaClass.simpleName,
                            ),
                        )
                    is CloseEpochResult.NotOwned ->
                        onIntent(
                            UiIntent.EjectResulted(
                                ejectGeneration = ejectGeneration,
                                identity = pinIdentity,
                                attachGeneration = pinGeneration,
                                succeeded = false,
                                busy = true,
                            ),
                        )
                }
            }
        }
    }
    /**
     * Explicit rescan after a successful eject: clear the session eject marker, then
     * run a fresh probe through the normal attach path. Never called automatically;
     * a latched unconfirmed close still refuses remount.
     */
    private fun rescanAfterEject(ejectGeneration: Long) {
        val pin = currentAttachmentPin()
        val live = attachment.snapshot()
        val cleared = filesSession.withLock { filesSession.consumeEjectedForRescan(pin) }
        if (!cleared) {
            onIntent(UiIntent.EjectResulted(
                ejectGeneration = ejectGeneration,
                identity = state.device?.identity,
                attachGeneration = live.generation,
                succeeded = false,
            ))
            return
        }
        val device = live.device
        if (device == null) {
            onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.NoDevice)))
            return
        }
        onUsbDeviceAttached(device, hasPermission = usbManager?.hasPermission(device) == true)
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

    private fun openRawDisk(
        revalidate: (AttachmentState.Snapshot<UsbDevice>) -> UsbDevice? = { it.device },
    ): UsbOpenBoundary.RawUsbSession? = usbOpenBoundary.openRawDisk(revalidate)

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

    fun listDirectory(path: String) = filesCoordinator.list(path)

    private fun createFolder(path: String, name: String) {
        filesCoordinator.createFolder(path, name)
    }

    private fun deleteEntry(path: String, name: String) {
        filesCoordinator.deleteEntry(path, name)
    }

    private fun deleteBatch(selection: FilesBatchSelection) {
        filesCoordinator.deleteBatch(selection)
    }

    private fun renameEntry(path: String, from: String, to: String, binding: PartitionBinding) {
        filesCoordinator.renameEntry(path, from, to, binding)
    }

    private fun uploadFile(path: String, uri: String) {
        filesCoordinator.uploadFile(path, uri)
    }

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
                    if (stagedPublished.get()) pendingRawStagingPath = null
                }
            },
            onTerminal = { outcome, terminalToken ->
                // Late-cancel race after last isActive check: executor may still mark
                // Cancelled/Failed without throwing — drop unpublished staged copy.
                // Never throw from terminal callback (would crash Main handler).
                if (outcome !is usb.operation.OperationState.Completed || !stagedPublished.get()) {
                    val orphan = stagedPathHolder[0]
                    stagedPathHolder[0] = null
                    if (pendingRawStagingPath == orphan) pendingRawStagingPath = null
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
            val cleanup: () -> Unit = {
                if (!stagedPublished.get()) {
                    val orphan = stagedPathHolder[0]
                    stagedPathHolder[0] = null
                    if (pendingRawStagingPath == orphan) pendingRawStagingPath = null
                    if (orphan != null) {
                        try {
                            ImageStaging.deleteIfManaged(filesDir, orphan)
                        } catch (failure: Throwable) {
                            operation.failureCause = operation.failureCause ?: failure
                            traceEvent("staging.cleanup_failed", "path" to orphan, "error" to (failure.message ?: failure.javaClass.simpleName))
                        }
                    }
                }
            }
            operation.onInactive(cleanup)
            require(binding.matches(state)) { "raw source target changed" }
            val target = fatChildPath(path, name)
            val volume = filesSession.volumeForLocked(pin, binding.partitionId)
            val size = volume.stat(target).size
            operation.bytesTotal = size
            ImageStaging.requireUsableSpaceEstimate(filesDir, size)
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
                pendingRawStagingPath = stagedCopy.absolutePath
                if (!operation.isActive()) cleanup()
            } finally {
                val closeErr = closeFilesVolume(reportStatus = false)
                if (closeErr != null) {
                    cleanup()
                    throw usb.fs.mergeCloseFailure(null, closeErr, null)
                }
            }
        }
    }

    // ---------------------------------------------------------------------
    // Source choosers: raw image, Ventoy link candidates, and phone import.
    // ---------------------------------------------------------------------

    private fun loadRawSourceCandidates(binding: PartitionBinding, generation: Long) {
        val pin = currentAttachmentPin()
        val identity = state.device?.identity
        rawSourceHandle?.cancel()
        rawSourceHandle = submitFilesOperation(
            title = OperationTitle.ListRawSources,
            quiet = true,
            foregroundService = false,
            listFailure = { failure, diagnostic ->
                UiIntent.RawSourceCandidatesFailed(
                    generation = generation,
                    identity = identity,
                    failure = failure,
                    diagnostic = diagnostic,
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
            override val foregroundService: Boolean = false

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
        val source = state.ventoySettingsSource
        val sourceKey = source.cacheKey()
        val op = object : DiskOperation(OperationTitle.ReadXmlBindings.logLabel) {
            override val foregroundService: Boolean = false

            override suspend fun run() {
                val links =
                    if (source is VentoySettingsSource.Document) {
                        usb.ventoy.WindowsSetup.parseLinks(
                            strictUtf8(readSettingsDocumentBytes(source)),
                        )
                    } else {
                        val raw = openRawDisk { live ->
                            require(live.generation == expectedAttach) { "USB attach generation changed" }
                            require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                            live.device ?: throw DiskException.Disconnected()
                        } ?: throw AttachmentChangedException()
                        val disk = raw.disk
                        try {
                            when (source) {
                                is VentoySettingsSource.UsbFile ->
                                    usb.ventoy.VentoySetup.readLinksAt(disk, source.path)
                                else -> usb.ventoy.VentoySetup.readLinks(disk)
                            }
                        } finally {
                            disk.close()
                        }
                    }
                if (attachmentMatches(expectedAttach, expectedKey) &&
                    this@MainViewModel.state.device?.identity == identity &&
                    this@MainViewModel.state.ventoySettingsSource.sameTarget(source)
                ) {
                    post(
                        UiIntent.WindowsImageLinksLoaded(
                            generation = generation,
                            identity = identity,
                            links = links.map {
                                usb.ui.WindowsImageLink(it.image, it.templates)
                            },
                            sourceKey = sourceKey,
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
                    attachmentMatches(expectedAttach, expectedKey) &&
                    state.device?.identity == identity &&
                    state.ventoySettingsSource.sameTarget(source)
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.WindowsImageLinksLoaded(
                            generation = generation,
                            identity = identity,
                            links = emptyList(),
                            error = message,
                            sourceKey = sourceKey,
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
                val inspectPart1Start: ULong?
                val inspectPart1Count: ULong?
                try {
                    val inspectProbe = usb.ventoy.VentoyLayout.probe(disk)
                    inspectPart1Start = inspectProbe?.part1StartLba
                    inspectPart1Count = inspectProbe?.part1SectorCount
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
                            partitionStartLba = inspectPart1Start,
                            partitionSectorCount = inspectPart1Count,
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
                                    bytesPerSecond = op.currentBytesPerSecond(),
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = opTitle,
                            bytesDone = op.bytesDone,
                            bytesTotal = op.bytesTotal,
                            cancelable = false,
                            resultMessage = op.result,
                        ),
                    ),
                )
                if (state.operation?.token == token && result.state == OperationState.Completed) {
                    val importStart = proposal.partitionStartLba
                    val importCount = proposal.partitionSectorCount
                    val importIdentity = proposal.identity
                    val templateStale =
                        attachGeneration != proposal.attachGeneration ||
                            state.device?.identity != proposal.identity ||
                            importIdentity == null ||
                            importStart == null ||
                            importCount == null
                    if (proposal.kind == usb.ui.WindowsImportKind.XmlTemplate && templateStale) {
                        onIntent(
                            UiIntent.Status(
                                UiMessage.Raw("USB attachment changed; template was not selected"),
                            ),
                        )
                    } else if (proposal.kind == usb.ui.WindowsImportKind.XmlTemplate &&
                        importIdentity != null && importStart != null && importCount != null
                    ) {
                        onIntent(
                            UiIntent.WindowsTemplateImported(
                                path = "${proposal.directory.trimEnd('/')}/${proposal.targetName}",
                                identity = importIdentity,
                                attachGeneration = proposal.attachGeneration,
                                partitionStartLba = importStart,
                                partitionSectorCount = importCount,
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = opTitle,
                            cancelable = false,
                            resultMessage = op.result,
                        ),
                    ),
                )
                if (state.operation?.token == token && result.state == OperationState.Completed) {
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = opTitle,
                            cancelable = false,
                            resultMessage = op.result,
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
            override val foregroundService: Boolean = false

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

    private fun isVentoyImagePath(path: String): Boolean =
        path.substringAfterLast('.', "").lowercase(Locale.ROOT) in
            setOf("iso", "img", "vhd", "vhdx", "wim")

    private fun loadAutounattendCandidates(xmlName: String, generation: Long) {
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val source = state.ventoySettingsSource
        val op = object : DiskOperation(OperationTitle.ListVentoyImages.logLabel) {
            override val foregroundService: Boolean = false

            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val templatePath = usb.ventoy.VentoySetup.xmlPath(xmlName)
                    val links =
                        when (source) {
                            is VentoySettingsSource.Document ->
                                usb.ventoy.WindowsSetup.parseLinks(
                                    strictUtf8(readSettingsDocumentBytes(source)),
                                )
                            is VentoySettingsSource.UsbFile ->
                                usb.ventoy.VentoySetup.readLinksAt(disk, source.path)
                            VentoySettingsSource.Default ->
                                usb.ventoy.VentoySetup.readLinks(disk)
                        }
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
                                listing.candidates
                                    .filter { isVentoyImagePath(it.path) }
                                    .map { file ->
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
                    if (attachmentMatches(expectedAttach, expectedKey) &&
                        this@MainViewModel.state.ventoySettingsSource.sameTarget(source)
                    ) {
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
                    attachmentMatches(expectedAttach, expectedKey) &&
                    this@MainViewModel.state.ventoySettingsSource.sameTarget(source)
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
        val renameFromXmlName = effect.renameFromXmlName
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
                        renameFromXmlName = renameFromXmlName,
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(UiIntent.OperationUpdated(result.toProgress(
                    token = token, title = opTitle,
                    cancelable = false, resultMessage = op.result,
                )))
                if (state.operation?.token == token && result.state == OperationState.Completed) {
                    onIntent(UiIntent.LoadAutounattend(xmlName))
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally { activeHandles.remove(handle) }
        }
    }

    private fun generateAutounattend(effect: UiEffect.GenerateAutounattend) {
        val xmlName = effect.xmlName
        val renameFromXmlName = effect.renameFromXmlName
        val params = effect.autounattendParams
        val imagePaths = effect.imagePaths
        val overwriteXml = effect.overwriteXml
        val expected = attachment.snapshot()
        val expectedAttach = expected.generation
        val expectedKey = deviceKey(expected.device)
        val expectedIdentity = state.device?.identity
        val expectedPart1 = state.partitions.firstOrNull { it.id == "1" }
        val expectedPart1Start = expectedPart1?.startLba
        val expectedPart1Count = expectedPart1?.sectorCount
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
                    // Probe under the same claim: physical part-1 geometry must match the
                    // pre-op snapshot before allocation or any write. Null pre-op means
                    // unprovable — refuse rather than writing to an unknown layout.
                    val writeProbe = usb.ventoy.VentoyLayout.probe(disk)
                        ?: throw usb.ventoy.IncompatibleVentoyException(
                            "No Ventoy data partition on this disk",
                        )
                    expectedPart1Start?.let { expected ->
                        require(writeProbe.part1StartLba == expected) {
                            "Ventoy data partition start changed since the write was confirmed"
                        }
                    } ?: throw AttachmentChangedException()
                    expectedPart1Count?.let { expected ->
                        require(writeProbe.part1SectorCount == expected) {
                            "Ventoy data partition size changed since the write was confirmed"
                        }
                    } ?: throw AttachmentChangedException()
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
                            xmlName
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
                        renameFromXmlName = renameFromXmlName,
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = opTitle,
                            cancelable = false,
                            resultMessage = op.result,
                        ),
                    ),
                )
                if (state.operation?.token == token && result.state == OperationState.Completed) {
                    val live = state.partitions.firstOrNull { it.id == "1" }
                    val start = expectedPart1Start
                    val count = expectedPart1Count
                    val identity = expectedIdentity
                    val generatedStale =
                        attachGeneration != expectedAttach ||
                            identity == null ||
                            state.device?.identity != identity ||
                            start == null ||
                            count == null ||
                            live == null ||
                            live.startLba != start ||
                            live.sectorCount != count
                    op.generatedXmlPath?.let { path ->
                        if (!generatedStale && identity != null && start != null && count != null) {
                            onIntent(
                                UiIntent.WindowsTemplateImported(
                                    path = path,
                                    identity = identity,
                                    attachGeneration = expectedAttach,
                                    partitionStartLba = start,
                                    partitionSectorCount = count,
                                ),
                            )
                        } else {
                            onIntent(
                                UiIntent.Status(
                                    UiMessage.Raw("USB attachment changed; template was not selected"),
                                ),
                            )
                        }
                    }
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (state.operation?.token == token && message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }


    private fun checkVentoyUpstream(requestToken: Long) {
        // One successful server answer is reused until the app version changes;
        // reinstall clears app data, update changes the stored version code.
        ventoyUpstreamCache.readRelease()?.let { cached ->
            onIntent(
                UiIntent.SetVentoyUpstreamRelease(
                    requestToken = requestToken,
                    tag = cached,
                    pinned = VentoyDownloader.pinnedSourceOrNull(cached) != null,
                ),
            )
            return
        }
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    usb.ventoy.VentoyDownloader.checkLatestRelease()
                        ?: throw IllegalStateException("upstream latest release unavailable")
                }
            }
            outcome.fold(
                onSuccess = { tag ->
                    ventoyUpstreamCache.saveRelease(tag)
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
     * Manual Plugson control-key catalog refresh. Runs on Dispatchers.IO and never opens
     * or claims the USB device. Failure posts an error without clearing a previous catalog;
     * success never writes ventoy.json — the user still stages and taps Write.
     */
    private fun fetchVentoyControlKeyCatalog(requestToken: Long, release: String) {
        scope.launch {
            val outcome = runCatching {
                withContext(Dispatchers.IO) {
                    usb.ventoy.VentoyControlKeyCatalogClient.fetch(release = release)
                }
            }
            outcome.fold(
                onSuccess = { result ->
                    when (result) {
                        is usb.ventoy.VentoyControlKeyCatalogResult.Ok ->
                            onIntent(
                                UiIntent.SetVentoyControlKeyCatalog(
                                    requestToken = requestToken,
                                    catalog = result.catalog,
                                ),
                            )
                        is usb.ventoy.VentoyControlKeyCatalogResult.Failed ->
                            onIntent(
                                UiIntent.SetVentoyControlKeyCatalog(
                                    requestToken = requestToken,
                                    error = result.message,
                                ),
                            )
                    }
                },
                onFailure = { failure ->
                    onIntent(
                        UiIntent.SetVentoyControlKeyCatalog(
                            requestToken = requestToken,
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
            override val foregroundService: Boolean = false

            override suspend fun run() {
                val raw = openRawDisk { live ->
                    require(live.generation == expectedAttach) { "USB attach generation changed" }
                    require(deviceKey(live.device) == expectedKey) { "USB device changed" }
                    live.device ?: throw DiskException.Disconnected()
                } ?: throw AttachmentChangedException()
                val disk = raw.disk
                try {
                    val probe = VentoyLayout.probe(disk)
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
                                partitionId = if (probe != null) "1" else null,
                                partitionStartLba = probe?.part1StartLba,
                                partitionSectorCount = probe?.part1SectorCount,
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
            override val foregroundService: Boolean = false

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
            override val foregroundService: Boolean = false

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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = title,
                            cancelable = false,
                            resultMessage = op.result,
                        ),
                    )
                )
                if (state.operation?.token == token && result.state != OperationState.Completed && message != null) {
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = title,
                            cancelable = false,
                            resultMessage = op.result,
                        ),
                    )
                )
                if (state.operation?.token == token && result.state != OperationState.Completed && message != null) {
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
        overwrite: Boolean,
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
                        overwrite = overwrite,
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
                val result = outcome.toOperationResult(op.failureCause)
                val message = result.toMessage()
                onIntent(
                    UiIntent.OperationUpdated(
                        result.toProgress(
                            token = token,
                            title = title,
                            cancelable = false,
                        ),
                    ),
                )
                if (state.operation?.token == token && result.state == OperationState.Completed) {
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
    internal fun stageDownloadForPicker(binding: FilesDownloadBinding) {
        safPicker.stageDownload(binding, state)
    }

    internal fun claimPickerLaunch(requestId: Long, binding: FilesDownloadBinding): Boolean =
        safPicker.claimLaunch(requestId, binding)

    internal fun pickerLaunchFailed(
        binding: FilesDownloadBinding,
        requestId: Long = pendingDownloadRequestId,
    ) {
        safPicker.launchFailed(binding, requestId)
    }

    internal fun submitDownloadResult(binding: FilesDownloadBinding, uri: Uri): Boolean =
        safPicker.submitResult(binding, uri, state)

    /** Process-death re-arm for the download slot; request id becomes 1. */
    internal fun restorePendingDownload(binding: FilesDownloadBinding): Boolean =
        safPicker.restorePending(binding)

    /** A CreateDocument result that will not be streamed must not leave an empty file. */
    internal fun discardPickedDownload(uri: Uri) {
        safPicker.discard(uri)
    }

    private fun deletePickedDownload(uri: Uri) {
        safPicker.discard(uri)
    }



    internal fun downloadFile(binding: FilesDownloadBinding, uri: String) {
        filesCoordinator.downloadFile(binding, uri)
    }

    private fun downloadBatch(selection: FilesBatchSelection, treeUri: String) {
        filesCoordinator.downloadBatch(selection, treeUri)
    }

    private fun uploadBatchDocs(effect: UiEffect.UploadBatchDocs) {
        filesCoordinator.uploadBatchDocs(effect)
    }

    private fun continueFilesBatch(decision: FilesConflictDecision) {
        filesCoordinator.continueBatch(decision)
    }
    private fun contentLastModified(resolver: ContentResolver, uri: Uri): Long? =
        runCatching {
            resolver.query(
                uri,
                arrayOf(android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED),
                null,
                null,
                null,
            )?.use { query ->
                if (!query.moveToFirst()) {
                    null
                } else {
                    val index = query.getColumnIndex(
                        android.provider.DocumentsContract.Document.COLUMN_LAST_MODIFIED,
                    )
                    if (index >= 0 && !query.isNull(index)) query.getLong(index) else null
                }
            }
        }.getOrNull()

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
        listFailure: ((OperationFailure?, String?) -> UiIntent)? = null,
        onSuccess: (() -> Unit)? = null,
        onSuccessWithToken: ((OperationToken) -> Unit)? = null,
        onTerminal: ((usb.operation.OperationState, OperationToken) -> Unit)? = null,
        quiet: Boolean = listFailure != null,
        foregroundService: Boolean = true,
        body: (DiskOperation) -> Unit,
    ): OperationHandle {
        val label = title.logLabel
        val submission = filesOperationRunner.create(
            name = label,
            bytesTotal = bytesTotal,
            foregroundService = foregroundService,
            body = body,
        )
        val operation = submission.operation
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
                                        bytesPerSecond = operation.currentBytesPerSecond(),
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
                                explicitClose = submission.closeFailure,
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
                        val failure = cause.toOperationFailure()
                        val message = FilesOperationPolicy.technicalDetail(cause)
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!(failure, message))
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
                                        failure = failure,
                                    ),
                                )
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome, token)
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!(null, "Cancelled"))
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
                        val failure = cause?.toOperationFailure()
                        val message =
                            FilesOperationPolicy.technicalDetail(
                                cause ?: Exception("Incomplete operation"),
                            )
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!(failure, message))
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
                                        failure = failure,
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
        @Volatile var smartResult: SmartOutcome? = null
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
                                    bytesPerSecond = operation.currentBytesPerSecond(),
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
                val result = outcome.toOperationResult(operation.failureCause)
                val progress = result.toProgress(
                    token = token,
                    title = title,
                    bytesDone = operation.bytesDone,
                    bytesTotal = operation.bytesTotal,
                    bytesPerSecond = operation.currentBytesPerSecond(),
                    cancelable = cancelable,
                    resultMessage = operation.result?.takeIf { it.isNotEmpty() },
                    readScanResult = sink.readScanResult,
                    smartResult = sink.smartResult,
                )
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val message = result.toMessage()
                            ?: outcome.cause.message
                            ?: outcome.cause.javaClass.simpleName
                        if (state.operation?.token == token) {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        onIntent(UiIntent.OperationUpdated(progress))
                        if (state.operation?.token == token) {
                            scope.launch(Dispatchers.IO) { runCatching { closeFilesVolume(reportStatus = true) } }
                        }
                    }
                    else -> {
                        onIntent(UiIntent.OperationUpdated(progress))
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
    private fun requireBackupFolder(): SafBackupFolder =
        safBackup.requireBackupFolder(state) as SafBackupFolder

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
    private fun readBackupBlob(uri: String, maxBytes: Int): ByteArray =
        safBackup.readBlob(uri, maxBytes)

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

    private val rescueFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onRescueFilePicked(uri)
    }

    private val gptFilePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onGptBackupPicked(uri)
    }

    /**
     * SAF document chosen as read-only Ventoy settings import input; Apply writes USB target.
     *
     * No attachment pin is kept here. The reducer pins the picked file to the live
     * attachment when it accepts the selection and drops it when that stick is replaced, so
     * a result that arrives after an Activity re-creation (or after a re-attach bumps the
     * attach generation) is still delivered instead of being discarded on the way in.
     */
    private val ventoySettingsPickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        // Picker cancel is silent by contract; nothing was chosen, so nothing is claimed.
        if (uri == null) return@registerForActivityResult
        // The document is only ever read. Asking for WRITE too makes the whole call throw
        // when a provider granted READ alone, which drops the persistable grant entirely.
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION,
            )
        }
        viewModel.onIntent(
            UiIntent.SelectVentoySettingsDocument(
                uri = uri.toString(),
                displayName = documentDisplayName(uri),
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

    /** Backup picker queue owns action pin and process-death marker lifecycle. */

    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        // Consumed for every result (including cancel) so a stale context cannot outlive it.
        val pending = backupFolderLaunches.take()
        if (uri == null) {
            backupFolderLaunches.clearLostAction()
            return@registerForActivityResult
        }
        // Fail closed before touching the grant/folder: the stick may have been swapped
        // while the system picker was in front.
        if (pending != null) {
            val check = checkBackupTarget(pending)
            if (check != BackupTargetCheck.Attached) {
                backupFolderLaunches.clearLostAction()
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
            if (backupFolderLaunches.takeLostAction()) {
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
    /** Multi/dir download pin before OpenDocumentTree; cleared on detach/result. */
    private val batchDownloadPicker: PickerSlot<FilesBatchSelection>
        get() = viewModel.safPicker.batchDownload
    /** Upload pin before OpenMultipleDocuments; cleared on detach/result. */
    private val batchUploadPicker: PickerSlot<UploadTarget>
        get() = viewModel.safPicker.batchUpload
    private val batchDownloadTreeLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val selection = batchDownloadPicker.clear()
        if (uri == null) return@registerForActivityResult
        if (selection == null) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        if (selection.stageVerdict(viewModel.state) != PickerVerdict.Ok) {
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
        val target = batchUploadPicker.clear()
        if (uris.isEmpty()) return@registerForActivityResult
        uris.forEach { uri ->
            runCatching {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION,
                )
            }
        }
        if (target == null) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)))
            return@registerForActivityResult
        }
        if (target.verdict(viewModel.state) != PickerVerdict.Ok) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.TargetChanged)))
            return@registerForActivityResult
        }
        viewModel.onIntent(
            UiIntent.UploadBatchDocs(
                directory = target.directory,
                uris = uris.map { it.toString() },
                attachment = target.attachment,
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
        val target = UploadTarget(
            directory = live.currentPath,
            attachment = UiAttachmentPin(device, partition),
        )
        batchUploadPicker.clear()
        batchUploadPicker.stage(target)
        runCatching { batchUploadDocsLauncher.launch(arrayOf("*/*")) }
            .onFailure {
                batchUploadPicker.clear()
                viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
            }
    }

    private val usbAttachController by lazy {
        UsbAttachController(
            context = this,
            listener =
                object : UsbAttachController.Listener {
                    override fun onAttached(
                        device: UsbDevice,
                        hasPermission: Boolean,
                        manager: UsbManager,
                    ) {
                        viewModel.onUsbDeviceAttached(device, hasPermission, manager)
                    }

                    override fun onDetached(device: UsbDevice?) {
                        viewModel.onUsbDeviceDetached(device)
                    }

                    override fun onPermissionResult(
                        device: UsbDevice?,
                        granted: Boolean,
                        manager: UsbManager?,
                    ) {
                        if (device != null && manager != null) {
                            viewModel.onUsbDeviceAttached(device, granted, manager)
                        }
                    }

                    override fun onSelectionCleared() {
                        batchDownloadPicker.clear()
                        batchUploadPicker.clear()
                    }
                },
        )
    }

    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val binding = viewModel.pendingDownloadBinding
        if (binding == null) {
            if (uri != null) viewModel.discardPickedDownload(uri)
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.StaleStagedImage)))
            return@registerForActivityResult
        }
        if (uri == null) {
            viewModel.pickerLaunchFailed(binding)
            return@registerForActivityResult
        }
        viewModel.submitDownloadResult(binding, uri)
    }

    /**
     * Records the pinned action before the picker opens. [BackupFolderAction.ChangeFolder]
     * first cancels a pending write action: a manual folder change must never resume an
     * earlier backup against a stick the user was no longer looking at.
     */
    private fun openBackupFolderPicker(action: BackupFolderAction) {
        if (action == BackupFolderAction.ChangeFolder) backupFolderLaunches.cancel()
        backupFolderLaunches.clearLostAction()
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

    /** Keep system bar icons light and bars black; edge-to-edge content supplies backgrounds on API 35+. */
    private fun configureDarkSystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = AndroidColor.BLACK
        window.navigationBarColor = AndroidColor.BLACK
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = false
            isAppearanceLightNavigationBars = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureDarkSystemBars()
        // Transfer knobs are read once per operation, so persisted values must be live
        // before the first disk action, not only after visiting Diagnostics.
        usb.diagnostics.PerformancePreferences.load(applicationContext)
        // A re-delivered CreateDocument result arrives after the activity is recreated
        // (rotation/process death); without this it would find no pending name and drop it.
        if (viewModel.safPicker.restore(savedInstanceState)) {
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.StaleStagedImage)))
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
                    is UiEffect.DownloadPickerReady -> {
                        if (!viewModel.claimPickerLaunch(event.requestId, event.binding)) return@collect
                        try {
                            downloadLauncher.launch(event.suggestedName)
                        } catch (_: RuntimeException) {
                            viewModel.pickerLaunchFailed(event.binding, event.requestId)
                            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
                        }
                    }
                    is UiEffect.CancelPendingWindowsImport -> windowsImportCoordinator.cancelByPickSession(event.pickSessionId)
                    is UiEffect.LaunchWindowsImport -> launchPinnedWindowsImport(event)
                    else -> Unit
                }
            }
        }
        if (backupFolderLaunches.restoreState(savedState)) {
            viewModel.onIntent(
                UiIntent.Status(UiMessage.Key(UiMessageKey.BackupFolderActionLost)),
            )
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
                viewModel.stageDownloadForPicker(binding)
            },
                onRequestBatchDownload = { selection ->
                    batchDownloadPicker.clear()
                    batchDownloadPicker.stage(selection)
                    runCatching { batchDownloadTreeLauncher.launch(null) }
                        .onFailure {
                            batchDownloadPicker.clear()
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
                    // Any name and MIME type: the engine validates the JSON, not the label.
                    ventoySettingsPickerLauncher.launch(arrayOf("*/*"))
                },
                onPickBackupFolder = {
                    openBackupFolderPicker(BackupFolderAction.ChangeFolder)
                },
                onPickRescueFile = { rescueFilePicker.launch(arrayOf("*/*")) },
                onPickGptFile = { gptFilePicker.launch(arrayOf("*/*")) },
                onStartRescueBackup = {
                    viewModel.startCreateWithBackupFolder(BackupFolderAction.RescueBackup)
                },
                onCreateGptCopy = {
                    viewModel.startCreateWithBackupFolder(BackupFolderAction.GptCopy)
                },
                // Live persisted-grant check for the Diagnostics create buttons; the write
                // path still re-verifies it in requireBackupFolder() before opening the disk.
                backupFolderGrantValid = { viewModel.hasUsableBackupFolder() },
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
        usbAttachController.stop()
        windowsImportCoordinator.onDestroy()
        pickerEventsScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        windowsImportCoordinator.saveState(outState)
        viewModel.safPicker.save(outState)
        backupFolderLaunches.saveState(outState)
    }

    override fun onStart() {
        super.onStart()
        usbAttachController.start()
        if (!usbAttachController.probeAttachedDevice(intent)) {
            viewModel.onUsbDeviceDetached()
            viewModel.onIntent(UiIntent.DeviceChanged(DevicePresence.Absent))
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (!usbAttachController.probeAttachedDevice(intent)) {
            viewModel.onUsbDeviceDetached()
            viewModel.onIntent(UiIntent.DeviceChanged(DevicePresence.Absent))
        }
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
        // USB permission action/token/counter live on UsbAttachController.
        // Picker STATE_PENDING_* keys live on SafPickerCoordinator.

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
