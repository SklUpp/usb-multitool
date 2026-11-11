package usb.multitool
import usb.files.AttachmentChangedException
import usb.files.AttachmentPin
import usb.files.FilesVolumeSession
import usb.files.MountedFilesVolume
import usb.files.requireFilesVolumeClosed
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
import android.os.Environment
import android.provider.OpenableColumns
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import usb.ventoy.AutounattendBinding
import usb.ventoy.AutounattendState
import usb.ventoy.UnreadableProblem
import usb.ventoy.VentoyControlSetting
import usb.ventoy.VentoyGlobalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import usb.device.DiskException
import usb.device.MassStorageDiscovery
import usb.device.UsbDisk
import usb.device.withWriteTracking
import usb.service.DiskController
import usb.operation.DiskOperation
import usb.operation.syncDestructiveDisk
import usb.service.OperationHandle
import usb.service.DiskService
import usb.ui.AppShell
import usb.ui.AppUiState
import usb.ui.DeviceIdentity
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
import usb.ui.AppLanguage
import usb.device.UsbDiskSession
import usb.diagnostics.Diagnostics
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
import usb.fs.UsbFsDirEntry
import usb.fs.ExtException
import usb.fs.NtfsException
import usb.fs.FatFsException
import usb.fs.UsbFsVolume
import usb.fs.FilesystemId
import usb.fs.Filesystem
import usb.fs.FormatCatalog
import usb.fs.Formatter
import usb.fs.PartitionVolumeSession
import usb.fs.FilesystemUsage
import usb.fs.FilesystemUsageKey
import usb.fs.FilesystemUsageProbe
import usb.fs.FilesystemUsageReason
import usb.fs.FilesystemUsageSnapshot
import usb.ui.filesystemUsageKey
import usb.ui.needsFilesystemUsageRequest
import usb.ui.fatChildPath
import usb.partition.GptBackup
import usb.partition.PartitionEditor
import usb.ui.PartitionBinding
import usb.ui.PartitionRef
import usb.ui.PartitionRole
import usb.ui.matches
import usb.ui.PartitionTab
import usb.ui.toTableScheme
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.PartitionWriter
import usb.partition.TableInitializer
import usb.partition.VirtualPartition
import usb.partition.isProtectedSystemPartition
import usb.image.ImageSource
import usb.image.ImageStaging
import usb.image.stageRawImageChunks
import usb.image.RawWriter
import usb.ventoy.VentoyAssets
import usb.ventoy.VentoyDownloader
import usb.ventoy.VentoyOperations
import usb.ventoy.VentoyAssetOperations
import usb.ventoy.VentoySetup
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val LANGUAGE_KEY = "language"
        /** Persisted two-state theme; absent means the dark default. */
        const val THEME_KEY = "dark_theme"
        /** Persisted SAF tree shared by image+map folders and GPT copies. */
        const val BACKUP_FOLDER_URI_KEY = "backup_folder_uri"
        const val BACKUP_FOLDER_NAME_KEY = "backup_folder_name"
        /**
         * One 1 MiB chunk per filesystem call. The FAT engine opens, seeks, writes and
         * syncs per call, so a larger chunk cuts that overhead 16×. The buffer is
         * allocated per operation and dropped when it returns — no idle retention.
         */
        const val COPY_CHUNK_BYTES = 1024 * 1024
        const val PROGRESS_TICK_MS = 120L
        /** Log-append interval: coarser than the UI progress tick to keep the ring buffer readable. */
        const val LOG_TICK_MS = 3_000L
    }

    var state by mutableStateOf(AppUiState())
        private set

    /**
     * Reducer-side cancellation of a pending SAF launch. The queue lives in the Activity,
     * so the effect crosses this flow; replay covers a recreation before collection starts.
     * The payload is the pick session, so a late cancel cannot clear a newer launch.
     */
    private val _pickerCancellations = Channel<String>(Channel.UNLIMITED)
    val pickerCancellations: Flow<String> = _pickerCancellations.receiveAsFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeHandle: OperationHandle? = null
    private var rawSourceHandle: OperationHandle? = null
    private var windowsImageHandle: OperationHandle? = null
    private val activeHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<OperationHandle>()
    @Volatile
    private var attachGeneration = 0L
    /** Pick generations make slow SAF inspections lose to the newest selection. */
    private val gptPickGeneration = java.util.concurrent.atomic.AtomicLong(0)
    private val rescueArchivePickGeneration = java.util.concurrent.atomic.AtomicLong(0)
    @Volatile
    var attachedDevice: UsbDevice? = null
        private set
    @Volatile
    var usbManager: UsbManager? = null
        private set

    private fun deviceKey(device: UsbDevice?): String? =
        device?.let { "${it.deviceId}:${it.deviceName}" }

    /**
     * Immutable pin for a files operation accepted now: attach generation + device key.
     * Captured on the main thread before the operation is queued; null means detached and
     * the operation must fail before touching any volume.
     */
    private fun currentAttachmentPin(): AttachmentPin? =
        attachedDevice?.let { device -> AttachmentPin(attachGeneration, deviceKey(device)!!) }

    private val languagePrefs =
        application.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    init {
        val folder = readBackupFolder()
        state = state.copy(
            language = readLanguage(),
            darkTheme = readDarkTheme(),
            backupFolderUri = folder.uri,
            backupFolderName = folder.name,
            backupFolderUsable = folder.usable,
        )
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
        scope.launch {
            val tag = kotlinx.coroutines.withContext(Dispatchers.IO) { usb.ventoy.VentoyDownloader.checkLatestRelease() }
            onIntent(UiIntent.SetLatestVentoyRelease(tag))
        }
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
            openMounted = { partitionId, writable, pin ->
                openMountedFilesVolume(partitionId, writable, pin)
            },
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

    private fun inspectionAttachmentStillHere(ticket: InspectionTicket): Boolean =
        attachGeneration == ticket.attachGeneration && deviceKey(attachedDevice) == ticket.deviceKey

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
        _pickerCancellations.close()
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
        return usable
    }

    fun onIntent(intent: UiIntent) {
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
        val result = UiReducer.reduce(state, intent)
        if (intent is UiIntent.CancelWindowsIsoImport) {
            // Best-effort cancel the SAF read and disown its ticket; the reducer already
            // released the SAF guard, so a stuck provider cannot block the next import.
            cancelActiveInspection(cancelUsb = false)
        }
        if (intent is UiIntent.OperationUpdated && intent.progress != null) {
            val current = intent.progress
            val previous = state.operation
            if (previous?.state != current.state || previous?.title != current.title) {
                val detail = current.errorMessage
                    ?: current.resultMessage
                    ?: current.readScanResult?.toLogLine()
                val message = "${current.label}: ${current.state}" + if (detail.isNullOrBlank()) "" else " — $detail"
                android.util.Log.i("UsbMultitool", message)
                state = UiReducer.reduce(state, UiIntent.AppendLog(logLine(message))).state
            }
        }
        state = if (intent is UiIntent.OperationUpdated) result.state.copy(logLines = state.logLines) else result.state
        result.effects.forEach { effect ->
            handleEffect(effect)
        }
        refreshFilesystemUsage(intent)
    }

    /** Post an intent to the main dispatcher; disk workers must never mutate state directly. */
    private fun onIntentDeferred(intent: UiIntent) {
        scope.launch(Dispatchers.Main.immediate) { onIntent(intent) }
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
                        // Release the FAT session and its USB claim before raw/partition work.
                        requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                        val dev = attachedDevice ?: throw usb.device.DiskException.Disconnected()
                        val mgr = usbManager ?: throw usb.device.DiskException.Disconnected()
                        val disk = UsbDiskSession.open(mgr, dev)
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
                                    val assets = VentoyDownloader.loadWithFallback(
                                        externalDir = File(
                                            Environment.getExternalStorageDirectory(),
                                            "ventoy",
                                        ),
                                        assetManager = getApplication<Application>().assets,
                                        strict = true,
                                    )
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
                                    val assets = VentoyDownloader.loadWithFallback(
                                        externalDir = File(
                                            Environment.getExternalStorageDirectory(),
                                            "ventoy",
                                        ),
                                        assetManager = getApplication<Application>().assets,
                                        strict = true,
                                    )
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
                                DestructiveAction.VENTOY_DOWNLOAD_INSTALL -> {
                                    val released = VentoyAssetOperations.download(
                                        cacheDir = getApplication<Application>().cacheDir,
                                        operation = this,
                                        isActive = { isActive() },
                                    )
                                    bytesTotal = -1L
                                    val settingsWritten =
                                        VentoyOperations.install(
                                            partitionDisk,
                                            released.assets,
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
                            syncDestructiveDisk(disk)
                        } finally {
                            disk.close()
                        }
                    }
                }
                val isCooperative = action.cooperative
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            title = opTitle,
                            cancelable = isCooperative,
                        ),
                    ),
                )
                val handle = DiskController.get().submit(op)
                if (isCooperative) activeHandle = handle
                activeHandles.add(handle)
                scope.launch {
                    val ticker = launch {
                        while (true) {
                            delay(LOG_TICK_MS)
                            val done = op.bytesDone
                            val total = op.bytesTotal
                            onIntent(UiIntent.OperationUpdated(OperationProgress(
                                state = OperationState.Running,
                                title = opTitle,
                                bytesDone = done,
                                bytesTotal = total,
                                cancelable = isCooperative,
                            )))
                            val progress = if (total > 0) " ${(done.toDouble() / total * 100).toInt().coerceIn(0, 100)}%" else " $done bytes"
                            val message = "$actionName$progress"
                            android.util.Log.i("UsbMultitool", message)
                            onIntent(UiIntent.AppendLog(logLine(message)))
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
                            DestructiveAction.VENTOY_DOWNLOAD_INSTALL,
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

            UiEffect.RescueBackup ->
                submitReadOnlyDiskOperation(OperationTitle.RescueBackup) { disk, operation, _ ->
                    val folder = requireBackupFolder()
                    val stamp = "${timestamp()}-${System.nanoTime()}"
                    val folderName = BackupArchive.folderName(stamp)
                    val sectorSize = disk.geometry.sectorSize
                    val deviceId =
                        state.device
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
                    // Provider-reported estimate only; null means it was not reported.
                    folder.availableBytes()?.let { usable ->
                        if (usable >= 0 && usable < need) {
                            throw java.io.IOException(
                                "not enough free space in the selected folder: " +
                                    "usable=$usable, need≈$need (provider estimate)",
                            )
                        }
                    }
                    val archiveUri = folder.createSubfolder(folderName)
                    // Fail closed before the first image byte: write+fsync+read-back must work.
                    folder.probeWriteSupport(archiveUri)
                    val imageUri = folder.createFile(
                        archiveUri,
                        BackupArchive.IMAGE_NAME,
                        "application/octet-stream",
                    )
                    val imageOut = folder.openSyncedOutput(imageUri)
                    try {
                        val target = SafArchiveTarget(
                            image = imageOut,
                            checkpoints = usb.diagnostics.CheckpointPublisher(
                                store = usb.saf.SafCheckpointStore(folder, archiveUri),
                                baseName = BackupArchive.MAP_NAME,
                            ),
                            sectorSize = sectorSize,
                        )
                        val copyMap =
                            RescueMap.forCopy(
                                disk.geometry.sectorCount,
                                sectorSize = sectorSize,
                                identity = deviceId.identity,
                            )
                        RescueArchiveOperations.copy(
                            disk = disk,
                            target = target,
                            map = copyMap,
                            operation = operation,
                        )
                        val copied = copyMap.copied.sumOf { it.last - it.first + 1UL }
                        val unreadable = copyMap.failed.sumOf { it.last - it.first + 1UL }
                        val note =
                            if (copyMap.partial) {
                                " (partial/incomplete — map kept; re-run backup)"
                            } else {
                                ""
                            }
                        "Rescue backup: folder $folderName " +
                            "(${BackupArchive.IMAGE_NAME} + ${BackupArchive.MAP_NAME}): " +
                            "$copied copied, $unreadable unreadable of " +
                            "${copyMap.sectorCount} sectors$note"
                    } finally {
                        runCatching { imageOut.close() }
                    }
                }

            UiEffect.CaptureGptBackup ->
                submitReadOnlyDiskOperation(OperationTitle.GptCriticalBackup) { disk, operation, _ ->
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

            UiEffect.CancelOperation -> {
                activeHandle?.cancel()
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
                downloadFile(effect.path, effect.name, effect.uri)
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

            is UiEffect.CancelPendingWindowsImport -> {
                _pickerCancellations.trySend(effect.pickSessionId)
            }

            is UiEffect.ImportIso -> {
                importImage(effect.isoImport, effect.overwrite)
            }

            is UiEffect.LinkXml -> {
                val imagePaths = effect.imagePaths
                if (imagePaths.isEmpty()) {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                title = OperationTitle.AutounattendXmlLink,
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
                        requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                        require(attachGeneration == confirmed.identity.attachGeneration) { "USB attach generation changed" }
                        val dev = attachedDevice ?: throw usb.device.DiskException.Disconnected()
                        val mgr = usbManager ?: throw usb.device.DiskException.Disconnected()
                        val disk = UsbDiskSession.open(mgr, dev)
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
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            title = OperationTitle.AutounattendXmlLink,
                            cancelable = false,
                        ),
                    ),
                )
                val handle = DiskController.get().submit(op)
                activeHandles.add(handle)
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
                            attachGeneration == operationGeneration &&
                            attachedDevice != null &&
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
                        if (mappedState == OperationState.Completed) {
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

            is UiEffect.GenerateAutounattend -> generateAutounattend(effect)

            is UiEffect.LoadVentoyGlobalSettings ->
                loadVentoyGlobalSettings(effect.requestToken)

            is UiEffect.LoadVentoyFolders ->
                loadVentoyFolders(effect.requestToken, effect.path)

            is UiEffect.ApplyVentoyControls -> applyVentoyControls(effect.settings)

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
        val dev = attachedDevice
        val mgr = usbManager
        if (dev != null && mgr != null && state.presence == DevicePresence.Ready) {
            submitReadOnlyDiskOperation(OperationTitle.SelfTest, cancelable = false) { disk, _, _ ->
                val report = usb.diagnostics.SelfTestRunner.runAll(disk)
                check(report.allPassed) { report.summary() }
                report.summary()
            }
        } else {
            scope.launch {
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(state = OperationState.Running, title = OperationTitle.SelfTestRunning, cancelable = false),
                    ),
                )
                try {
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
                                resultMessage = if (report.allPassed) summary else null,
                                errorMessage = if (!report.allPassed) summary else null,
                                cancelable = false,
                            ),
                        ),
                    )
                    onIntent(UiIntent.Status(UiMessage.Raw(summary)))
                } catch (t: Throwable) {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                title = OperationTitle.SelfTest,
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
        val resolver = getApplication<Application>().contentResolver
        val (name, size) = contentInfo(resolver, uri)
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
        val gen = if (sameDevice) attachGeneration else ++attachGeneration
        if (isNewDevice) {
            cancelActiveInspection(cancelUsb = true)
            usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
            activeHandles.clear()
            activeHandle = null
        }
        attachedDevice = device
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
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val disk = UsbDiskSession.open(mgr, device)
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration != gen || attachedDevice?.deviceId != device.deviceId) {
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                when (val outcome = handle.await()) {
                    is usb.operation.OperationState.Failed -> {
                        withContext(Dispatchers.Main.immediate) {
                            if (attachGeneration == gen && attachedDevice?.deviceId == device.deviceId) {
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
                            if (attachGeneration == gen && attachedDevice?.deviceId == device.deviceId) {
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

    fun refreshPartitions() {
        val dev = attachedDevice ?: return
        val mgr = usbManager ?: return
        val gen = attachGeneration
        val op = object : DiskOperation(name = OperationTitle.RefreshPartitions.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.let { mapPartitionTabs(disk, it.partitions) } ?: emptyList()
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration != gen || attachedDevice?.deviceId != dev.deviceId) {
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed) {
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration == gen && attachedDevice?.deviceId == dev.deviceId) {
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
        if (device != null && device.deviceId != attachedDevice?.deviceId) return
        ++attachGeneration
        attachedDevice = null
        lastUsageKey = null
        usageInFlightKey = null
        cancelActiveInspection(cancelUsb = true)
        usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
        activeHandles.clear()
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
        filesSession.withLock { filesSession.closeIfEpochAtMostLocked(fence) }
    }

    /** Drop the mounted volume (and its USB claim) without blocking user code. */
    /**
     * Drop the mounted files volume. Returns a close/sync failure if one occurred.
     * When [reportStatus] is true and the UI is still alive, posts a visible status line
     * (detach/onCleared use false — no crash, no orphaned status after clear).
     */
    private fun closeFilesVolume(reportStatus: Boolean = false): Throwable? {
        val error = filesSession.close()
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
    ): MountedFilesVolume {
        val device = attachedDevice ?: throw DiskException.Disconnected()
        val manager = usbManager ?: throw DiskException.Disconnected()
        // Pin check before the USB claim: a paused operation must not open another stick.
        requireAttachment(pin, device)
        val disk = UsbDiskSession.open(manager, device)
        val volume = try {
            val index = partitionId.toIntOrNull()
                ?: throw IllegalArgumentException("invalid partition id $partitionId")
            val table = PartitionTable.read(disk)
            // A detach/re-attach can happen during the claim or table read: re-verify the pin
            // against the live attachment before trusting this geometry or partition range.
            requireAttachment(pin, device)
            if (writable) requireWritableMountAllowed(table)
            val partition = table.partitions.getOrNull(index - 1)
                ?: throw IllegalArgumentException("partition $partitionId no longer exists")
            PartitionVolumeSession.open(
                disk,
                partition.startLba,
                partition.sectorCount,
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
            throw failure
        }
        return MountedFilesVolume(partitionId, disk, volume, writable = writable)
    }

    /** Fail unless [pin] still describes the live attachment opened for [device]. */
    private fun requireAttachment(pin: AttachmentPin, device: UsbDevice) {
        val live = attachedDevice
        if (attachGeneration != pin.attachGeneration ||
            deviceKey(device) != pin.deviceKey ||
            live == null ||
            deviceKey(live) != pin.deviceKey
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
                intent.progress?.state != null &&
                intent.progress?.state != OperationState.Running &&
                intent.progress?.state != OperationState.Queued
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
        val expectedGeneration = attachGeneration
        val expectedIdentity = state.device?.identity
        val pin = currentAttachmentPin()
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
                            val volume = filesSession.volumeForLocked(pin, partitionId)
                            FilesystemUsageProbe.measureLocked(volume, key)
                        } catch (failure: Throwable) {
                            FilesystemUsageSnapshot(
                                key,
                                FilesystemUsage.Unavailable(FilesystemUsageReason.IoFailure),
                            )
                        }
                    }
                } ?: return@launch
            withContext(Dispatchers.Main.immediate) {
                onIntent(UiIntent.FilesystemUsageResulted(generation, snapshot.key, snapshot.usage))
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
                        volume.sync()
                        operation.commitReached = true
                    } catch (failure: Throwable) {
                        throw userVisibleDeleteError(name, target, failure)
                    }
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
                    volume.sync()
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
                        chunkBytes = COPY_CHUNK_BYTES,
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
                    volume.sync()
                    operation.commitReached = true
                }
            }
        }
    }

    /**
     * True when a cleanup/delete failure means the target was already gone.
     * FAT: FR_NO_FILE via FatFsException. EXT: ExtException "Path not found" / delete failed ENOENT.
     */
    private fun isAbsentPathError(error: Throwable): Boolean {
        when (error) {
            is FatFsException -> {
                val msg = error.message.orEmpty()
                return msg.endsWith("delete failed: ${usb.fs.FR_NO_FILE}") ||
                    msg.endsWith("stat failed: ${usb.fs.FR_NO_FILE}")
            }
            is ExtException -> {
                val msg = error.message.orEmpty()
                return msg.startsWith("Path not found") ||
                    msg.contains("No such file") ||
                    msg.contains("ENOENT")
            }
            is NtfsException -> {
                val msg = error.message.orEmpty()
                return msg.startsWith("Path not found") ||
                    msg.contains("No such file") ||
                    msg.contains("ENOENT") ||
                    msg.contains("errno=2")
            }
            else -> return false
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
        submitFilesOperation(
            title = OperationTitle.StageForRawWrite(name),
            onSuccess = {
                val stagedPath = stagedPathHolder[0]
                if (stagedPath != null) {
                    onIntent(UiIntent.SetStagedRawImage(stagedPath, name))
                }
            },
            onTerminal = { outcome ->
                // Late-cancel race after last isActive check: executor may still mark
                // Cancelled/Failed without throwing — drop unpublished staged copy.
                // Never throw from terminal callback (would crash Main handler).
                if (outcome !is usb.operation.OperationState.Completed) {
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
                        chunkBytes = COPY_CHUNK_BYTES,
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val identity = state.device?.identity
        windowsImageHandle?.cancel()
        val op = object : DiskOperation(OperationTitle.ListDataPartitionFiles.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
                    if (attachGeneration == expectedAttach &&
                        deviceKey(attachedDevice) == expectedKey &&
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
        val handle = DiskController.get().submit(op)
        windowsImageHandle = handle
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val identity = state.device?.identity
        val op = object : DiskOperation(OperationTitle.ReadXmlBindings.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val links = usb.ventoy.VentoySetup.readLinks(disk)
                    if (attachGeneration == expectedAttach &&
                        deviceKey(attachedDevice) == expectedKey &&
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
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
                val handle =
                    try {
                        DiskController.get().submit(op)
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
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                // Final check right before the direct open: a cancel/replug during the queue
                // wait must never open a stale device.
                if (!inspectionOwner.owned(ticket)) {
                    if (!inspectionAttachmentStillHere(ticket)) reportAttachmentChanged()
                    return
                }
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                val exists =
                    try {
                        usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                            if (destinationDirectory != null && directory != "/") {
                                val chosen = volume.statOrNull(directory)
                                if (chosen == null || !chosen.isDirectory) {
                                    throw usb.ventoy.ImportDirectoryMissingException(
                                        "import folder \"$directory\" does not exist on the Ventoy partition",
                                    )
                                }
                            }
                            volume.statOrNull(target) != null
                        }
                    } finally {
                        disk.close()
                    }
                if (inspectionOwner.owned(ticket)) {
                    post(
                        UiIntent.WindowsIsoImportInspected(
                            uri = uri,
                            name = name,
                            sizeBytes = sizeBytes,
                            targetName = name,
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val expectedIdentity = proposal.identity
        val expectedPickSession = proposal.pickSessionId
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null
            @Volatile var copiedPath: String? = null

            override suspend fun run() {
                require(attachGeneration == expectedAttach) {
                    "USB attach generation changed before the copy"
                }
                require(deviceKey(attachedDevice) == expectedKey) {
                    "USB device changed before the copy"
                }
                require(this@MainViewModel.state.device?.identity == expectedIdentity) {
                    "USB identity changed before the copy"
                }
                expectedPickSession?.let { session ->
                    require(this@MainViewModel.state.filesPickMode?.sessionId == session) {
                        "Pick session changed before the copy"
                    }
                }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
                            attachGeneration == expectedAttach &&
                                deviceKey(attachedDevice) == expectedKey &&
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
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = opTitle,
                    bytesTotal = proposal.sizeBytes ?: -1L,
                    cancelable = true,
                ),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandle = handle
        activeHandles.add(handle)
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Running,
                                title = opTitle,
                                bytesDone = op.bytesDone,
                                bytesTotal = op.bytesTotal,
                                cancelable = true,
                            ),
                        ),
                    )
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
                            bytesDone = op.bytesDone,
                            bytesTotal = op.bytesTotal,
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState == OperationState.Completed) {
                    if (proposal.kind == usb.ui.WindowsImportKind.XmlTemplate) {
                        onIntent(
                            UiIntent.WindowsTemplateImported(
                                "${proposal.directory.trimEnd('/')}/${proposal.targetName}",
                            ),
                        )
                    } else {
                        // The operation is terminal only after the volume/disk closed. Auto
                        // selection is therefore allowed exactly here, never on Partial.
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
                    // ISO catalog already invalidated by OperationUpdated; Ventoy tab reloads.
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (message != null) {
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
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = opTitle,
                    cancelable = true,
                ),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandle = handle
        activeHandles.add(handle)
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
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState == OperationState.Completed) {
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (message != null) {
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
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Running, title = opTitle, cancelable = false),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
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
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (message != null) onIntent(UiIntent.Status(UiMessage.Raw(message)))
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadAutounattend(xmlName: String, generation: Long) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val op = object : DiskOperation(OperationTitle.ReadUnattendXml.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val read = usb.ventoy.VentoySetup.readAutounattend(disk, xmlName)
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val op = object : DiskOperation(OperationTitle.ListVentoyImages.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey
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

    private fun generateAutounattend(effect: UiEffect.GenerateAutounattend) {
        val xmlName = effect.xmlName
        val params = effect.autounattendParams
        val imagePaths = effect.imagePaths
        val overwriteXml = effect.overwriteXml
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val opTitle = OperationTitle.GenerateUnattendXml
        val label = opTitle.logLabel
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                require(attachGeneration == expectedAttach) { "USB attach generation changed" }
                require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    usb.ventoy.VentoySetup.generateAutounattend(
                        disk = disk,
                        xmlName = xmlName,
                        params = params,
                        imagePaths = imagePaths,
                        overwriteXml = overwriteXml,
                        allowDestructiveTemplate = effect.allowDestructiveTemplate,
                        expectedTemplateSha256 = effect.expectedTemplateSha256,
                        expectedPlanSha256 = effect.expectedPlanSha256,
                        // UI generation is the editor path; direct non-editor callers keep false.
                        requireDeclaredDiskSize = true,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                    result = "Wrote ${usb.ventoy.VentoySetup.xmlPath(xmlName)}"
                } finally {
                    disk.close()
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Running, title = opTitle, cancelable = false),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
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
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState == OperationState.Completed) {
                    // OperationUpdated already dropped XML/catalogs (Missing + epoch).
                    // Ventoy tab reloads when open; avoid a second off-tab mount here.
                    onIntent(UiIntent.LoadWindowsImageLinks)
                    onIntent(UiIntent.ClearWindowsSelection)
                } else if (message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }


    private fun loadVentoyGlobalSettings(requestToken: Long) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val expectedEpoch = state.ventoyCacheEpoch
        val op = object : DiskOperation(OperationTitle.ReadVentoyGlobalSettings.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val settings = usb.ventoy.VentoySetup.readGlobalSettings(disk)
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
                        post(
                            UiIntent.VentoyGlobalSettingsLoaded(
                                settings,
                                epoch = expectedEpoch,
                                requestToken = requestToken,
                            ),
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey
                ) {
                    val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                    post(
                        UiIntent.VentoyGlobalSettingsLoaded(
                            if (outcome.cause is usb.ventoy.IncompatibleVentoyException) {
                                // No Ventoy layout is the honest "no ventoy.json yet" state,
                                // not a read failure.
                                VentoyGlobalSettings.Missing
                            } else {
                                VentoyGlobalSettings.ReadError(message)
                            },
                            epoch = expectedEpoch,
                            requestToken = requestToken,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadVentoyFolders(requestToken: Long, path: String) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val expectedEpoch = state.ventoyCacheEpoch
        val op = object : DiskOperation(OperationTitle.ListVentoyFolders.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val folders = usb.ventoy.VentoySetup.listSubdirectories(disk, path)
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
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
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                if (outcome is usb.operation.OperationState.Failed &&
                    attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey
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

    private fun applyVentoyControls(settings: List<VentoyControlSetting>) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val title = OperationTitle.SetVentoyGlobalSetting
        val op = object : DiskOperation(title.logLabel) {
            @Volatile var result: String? = null

            override suspend fun run() {
                require(attachGeneration == expectedAttach) { "USB attach generation changed" }
                require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val changed =
                        usb.ventoy.VentoySetup.applyGlobalControls(
                            disk = disk,
                            settings = settings,
                            onMutation = { mutationAttempted = true },
                        )
                    if (mutationAttempted) commitReached = true
                    result =
                        if (changed) {
                            "Applied ${settings.size} Ventoy global setting" +
                                if (settings.size == 1) "" else "s"
                        } else {
                            "No Ventoy setting changed"
                        }
                } finally {
                    disk.close()
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = title,
                    cancelable = false,
                ),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
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
                            errorMessage = message,
                            resultMessage = op.result,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState != OperationState.Completed && message != null) {
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val bindTitle = OperationTitle.BindImagesToUnattendXml
        val op = object : DiskOperation(bindTitle.logLabel) {
            override suspend fun run() {
                require(attachGeneration == expectedAttach) { "USB attach generation changed" }
                require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val unbindTitle = OperationTitle.UnbindImageFromUnattendXml
        val op = object : DiskOperation(unbindTitle.logLabel) {
            override suspend fun run() {
                require(attachGeneration == expectedAttach) { "USB attach generation changed" }
                require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
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
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Running, title = title, cancelable = false),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
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
                            errorMessage = message,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState == OperationState.Completed) {
                    // OperationUpdated dropped document to Missing + bumped epoch; tab reloads.
                    onIntent(UiIntent.LoadWindowsImageLinks)
                    onIntent(UiIntent.ClearWindowsSelection)
                } else if (message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun downloadFile(path: String, name: String, uri: String) {
        val resolver = getApplication<Application>().contentResolver
        withPartitionId { partitionId ->
            val pin = currentAttachmentPin()
            submitFilesOperation(
                title = OperationTitle.DownloadEntry(name),
                onSuccess = { onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SavedFile, listOf(name)))) },
            ) { operation ->
                val target = fatChildPath(path, name)
                val destination = Uri.parse(uri)
                val volume = filesSession.volumeForLocked(pin, partitionId)
                FilesOperations.download(
                    resolver = resolver,
                    target = target,
                    destination = destination,
                    volume = volume,
                    operation = operation,
                )
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
        onTerminal: ((usb.operation.OperationState) -> Unit)? = null,
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
        if (FilesOperationPolicy.publishOperationProgress(quiet)) {
            onIntent(
                UiIntent.OperationUpdated(
                    OperationProgress(
                        state = OperationState.Running,
                        title = title,
                        bytesTotal = bytesTotal,
                    ),
                ),
            )
        }
        val handle = DiskController.get().submit(operation)
        if (!quiet) activeHandle = handle
        activeHandles.add(handle)
        scope.launch {
            val ticker = launch {
                var lastLogAt = android.os.SystemClock.elapsedRealtime()
                var lastLogged = -1L
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (FilesOperationPolicy.publishOperationProgress(quiet)) {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Running,
                                    title = title,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                ),
                            ),
                        )
                    }
                    val now = android.os.SystemClock.elapsedRealtime()
                    if (now - lastLogAt >= LOG_TICK_MS && operation.bytesDone != lastLogged) {
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
                        } else if (plan.postStatus) {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        } else {
                            onIntent(UiIntent.AppendLog(logLine("$label failed: $message")))
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Failed,
                                        title = title,
                                        errorMessage = message,
                                    ),
                                ),
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome)
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        if (plan.postBoundFailure) {
                            onIntent(listFailure!!("Cancelled"))
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(state = OperationState.Cancelled, title = title),
                                ),
                            )
                        }
                        // RO cancel: executor drops failureCause; close still logged via closeDiagnostic.
                        // Terminal stays Cancelled — never promote to Failed for close-on-cancel.
                        publishCloseDiagnostics(operation.failureCause)
                        onTerminal?.invoke(outcome)
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
                        } else if (plan.postStatus) {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Partial,
                                        title = title,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                        errorMessage = message,
                                    ),
                                ),
                            )
                        }
                        publishCloseDiagnostics(cause)
                        onTerminal?.invoke(outcome)
                    }
                    else -> {
                        if (plan.postOperationProgress) {
                            onIntent(
                                UiIntent.OperationUpdated(
                                    OperationProgress(
                                        state = OperationState.Completed,
                                        title = title,
                                        bytesDone = operation.bytesDone,
                                        bytesTotal = operation.bytesTotal,
                                    ),
                                ),
                            )
                        }
                        onSuccess?.invoke()
                        onTerminal?.invoke(outcome)
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
        body: (UsbDisk, DiskOperation, ReadOnlySink) -> String,
    ) {
        val sink = ReadOnlySink()
        val label = title.logLabel
        val operation = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val device = attachedDevice ?: throw DiskException.Disconnected()
                val manager = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(manager, device)
                try {
                    result = body(disk, this, sink)
                } finally {
                    disk.close()
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Running, title = title, cancelable = cancelable),
            ),
        )
        val handle = DiskController.get().submit(operation)
        if (cancelable) activeHandle = handle
        activeHandles.add(handle)
        scope.launch {
            val ticker = launch {
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Running,
                                title = title,
                                bytesDone = operation.bytesDone,
                                bytesTotal = operation.bytesTotal,
                                cancelable = cancelable,
                            ),
                        ),
                    )
                }
            }
            val logTicker = launch {
                var lastLogged = -1L
                while (true) {
                    delay(LOG_TICK_MS)
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
            try {
                val outcome = handle.await()
                ticker.cancel()
                logTicker.cancel()
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val message = outcome.cause.message ?: outcome.cause.javaClass.simpleName
                        onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        onIntent(
                            UiIntent.OperationUpdated(
                                readOnlyOperationProgress(
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
                        // The USB claim may be gone; reopen for the next attempt.
                        scope.launch(Dispatchers.IO) { runCatching { closeFilesVolume(reportStatus = true) } }
                    }
                    else -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                readOnlyOperationProgress(
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

    /** Common SAF tree for image+map folders and GPT copies. */
    private enum class PendingFolderAction {
        None,
        RescueBackup,
        GptCopy,
    }

    private var pendingFolderAction = PendingFolderAction.None

    private val backupFolderPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri: Uri? ->
        val action = pendingFolderAction
        pendingFolderAction = PendingFolderAction.None
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or
                    Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        if (viewModel.onBackupFolderPicked(uri)) {
            when (action) {
                PendingFolderAction.RescueBackup ->
                    viewModel.onIntent(UiIntent.StartRescueBackup)
                PendingFolderAction.GptCopy ->
                    viewModel.onIntent(UiIntent.CaptureGptBackup)
                PendingFolderAction.None -> Unit
            }
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

    /** One pending SAF context per Activity; codes come from the process-wide allocator. */
    private val windowsImportQueue: usb.ui.WindowsImportLaunchQueue by lazy {
        usb.ui.WindowsImportLaunchQueue(requestCodeAllocator(applicationContext))
    }

    private val windowsImportTimeoutHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private var windowsImportTimeoutCode: Int? = null
    private val windowsImportTimeoutRunnable =
        Runnable {
            val code = windowsImportTimeoutCode ?: return@Runnable
            windowsImportTimeoutCode = null
            // Old code is retired, so a late result can no longer join a newer launch.
            windowsImportQueue.resetByRequestCode(code)
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerTimedOut)))
        }

    private fun armWindowsImportTimeout(requestCode: Int) {
        windowsImportTimeoutHandler.removeCallbacks(windowsImportTimeoutRunnable)
        windowsImportTimeoutCode = requestCode
        windowsImportTimeoutHandler.postDelayed(
            windowsImportTimeoutRunnable,
            WINDOWS_IMPORT_TIMEOUT_MS,
        )
    }

    private fun disarmWindowsImportTimeout() {
        windowsImportTimeoutHandler.removeCallbacks(windowsImportTimeoutRunnable)
        windowsImportTimeoutCode = null
    }

    /**
     * SAF document picked for a phone-to-stick import. The legacy request code identifies
     * the launch, so a duplicate/late result cannot consume a newer pending context. The
     * ActivityResult lambda API has no per-launch identifier; modern registry keys cannot be
     * unregistered publicly, so requestCode is the honest discriminator here.
     */
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val context = windowsImportQueue.take(requestCode) ?: return
        disarmWindowsImportTimeout()
        if (resultCode != android.app.Activity.RESULT_OK) return
        val uri = data?.data ?: return
        val liveDevice = viewModel.state.device
        if (context.identity != liveDevice?.identity ||
            context.attachGeneration != (liveDevice?.attachGeneration ?: 0L)
        ) {
            viewModel.onIntent(
                UiIntent.Status(UiMessage.Key(UiMessageKey.FilesPickTargetInvalid)),
            )
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

    /** Fails closed if a picker is already open; stores the context before launching. */
    private fun launchWindowsImport(
        kind: usb.ui.WindowsImportKind,
        directory: String?,
        pickSessionId: String?,
    ) {
        val start =
            windowsImportQueue.begin(
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
        val intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "*/*"
            }
        val launched = runCatching {
            startActivityForResult(intent, context.requestCode)
        }
        if (launched.isFailure) {
            disarmWindowsImportTimeout()
            windowsImportQueue.resetByRequestCode(context.requestCode)
            viewModel.onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)))
        }
    }

    /** Entry name waiting for the SAF destination chosen in [downloadLauncher]. */
    private var pendingUsbPermissionKey: String? = null
    private var pendingDownloadName: String? = null
    private var selectedUsbDeviceKey: String? = null

    private val downloadLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream"),
    ) { uri: Uri? ->
        val name = pendingDownloadName
        pendingDownloadName = null
        if (uri != null && name != null) {
            viewModel.onIntent(UiIntent.DownloadFile(name, uri.toString()))
        }
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
                    }
                    viewModel.onUsbDeviceDetached(device)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val callbackKey = device?.let { usb.device.UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
                    val liveDevice = usb.device.UsbPermissionPolicy.currentMatch(device, usbManager?.deviceList?.values.orEmpty())
                    if (liveDevice != null && usb.device.UsbPermissionPolicy.accepts(pendingUsbPermissionKey, callbackKey, selectedUsbDeviceKey) && MassStorageDiscovery.findBotInterface(liveDevice) != null) {
                        pendingUsbPermissionKey = null
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
    private fun startWithBackupFolder(action: PendingFolderAction) {
        if (viewModel.hasUsableBackupFolder()) {
            when (action) {
                PendingFolderAction.RescueBackup -> viewModel.onIntent(UiIntent.StartRescueBackup)
                PendingFolderAction.GptCopy -> viewModel.onIntent(UiIntent.CaptureGptBackup)
                PendingFolderAction.None -> Unit
            }
        } else {
            pendingFolderAction = action
            backupFolderPicker.launch(null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A re-delivered CreateDocument result arrives after the activity is recreated
        // (rotation/process death); without this it would find no pending name and drop it.
        pendingDownloadName = savedInstanceState?.getString(STATE_PENDING_DOWNLOAD)
        // The document picker may recreate the activity; without these two the result would
        // arrive as an image bound for /ISO, so an XML chosen for /ventoy would be misfiled and
        // its marker scan skipped.
        val savedState = savedInstanceState
        val restoredKind =
            savedState
                ?.getString(STATE_PENDING_WINDOWS_KIND)
                ?.let { stored ->
                    runCatching { usb.ui.WindowsImportKind.valueOf(stored) }.getOrNull()
                }
        val restoredRequestCode =
            savedState?.getInt(STATE_PENDING_WINDOWS_REQUEST, -1) ?: -1
        val requestCodeRange =
            WindowsImportRequestCodes.FIRST_REQUEST_CODE..
                WindowsImportRequestCodes.LAST_REQUEST_CODE
        if (savedState != null &&
            restoredKind != null &&
            restoredRequestCode in requestCodeRange
        ) {
            val restored =
                windowsImportQueue.restore(
                    usb.ui.PendingWindowsImport(
                        kind = restoredKind,
                        directory = savedState.getString(STATE_PENDING_WINDOWS_DIRECTORY),
                        pickSessionId =
                            savedState.getString(STATE_PENDING_WINDOWS_PICK_SESSION),
                        identity = savedState.getString(STATE_PENDING_WINDOWS_IDENTITY),
                        attachGeneration =
                            savedState.getLong(STATE_PENDING_WINDOWS_ATTACH, -1L),
                        requestCode = restoredRequestCode,
                    ),
                )
            if (!restored) {
                // The durable mark could not be raised: drop the context instead of
                // accepting a result for an unsecured code.
                viewModel.onIntent(
                    UiIntent.Status(UiMessage.Key(UiMessageKey.ImportPickerUnavailable)),
                )
            }
        }
        pickerEventsScope.launch {
            viewModel.pickerCancellations.collect { session ->
                if (windowsImportQueue.resetByPickSession(session)) {
                    // Only the matching launch was cleared; a newer pending keeps its timeout.
                    disarmWindowsImportTimeout()
                }
            }
        }
        pendingFolderAction =
            savedInstanceState?.getString(STATE_PENDING_FOLDER_ACTION)?.let { stored ->
                runCatching { PendingFolderAction.valueOf(stored) }
                    .getOrDefault(PendingFolderAction.None)
            } ?: PendingFolderAction.None
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
                onRequestDownload = { name ->
                    pendingDownloadName = name
                    downloadLauncher.launch(name)
                },
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
                onPickBackup = {
                    gptBackupPickerLauncher.launch(arrayOf("*/*"))
                },
                onPickBackupFolder = {
                    pendingFolderAction = PendingFolderAction.None
                    backupFolderPicker.launch(null)
                },
                onStartRescueBackup = {
                    startWithBackupFolder(PendingFolderAction.RescueBackup)
                },
                onCreateGptCopy = {
                    startWithBackupFolder(PendingFolderAction.GptCopy)
                },
                onPickRestoreFolder = {
                    rescueArchiveFolderPicker.launch(null)
                },
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Delivery of the reducer cancel event can race destruction/cancellation. The live
        // pick session is authoritative: a pending launch for a session that no longer
        // exists is retired before any late result can be considered.
        windowsImportQueue.pending?.let { pending ->
            val live = viewModel.state.device
            if (pending.identity != live?.identity ||
                pending.attachGeneration != (live?.attachGeneration ?: 0L)
            ) {
                // The stick changed while the picker was away: its URI is not ours.
                windowsImportQueue.resetByRequestCode(pending.requestCode)
                disarmWindowsImportTimeout()
                return@let
            }
            val session = pending.pickSessionId
            if (session != null && viewModel.state.filesPickMode?.sessionId != session) {
                windowsImportQueue.resetByPickSession(session)
                disarmWindowsImportTimeout()
                return@let
            }
            // A restored/returned screen must not stay busy forever if the result was lost.
            armWindowsImportTimeout(pending.requestCode)
        }
    }

    override fun onPause() {
        // The picker itself is in front; do not expire a context the user is browsing.
        disarmWindowsImportTimeout()
        super.onPause()
    }

    override fun onDestroy() {
        disarmWindowsImportTimeout()
        pickerEventsScope.cancel()
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_DOWNLOAD, pendingDownloadName)
        outState.putString(STATE_PENDING_FOLDER_ACTION, pendingFolderAction.name)
        windowsImportQueue.pending?.let { context ->
            outState.putString(STATE_PENDING_WINDOWS_KIND, context.kind.name)
            context.directory?.let {
                outState.putString(STATE_PENDING_WINDOWS_DIRECTORY, it)
            }
            context.pickSessionId?.let {
                outState.putString(STATE_PENDING_WINDOWS_PICK_SESSION, it)
            }
            context.identity?.let {
                outState.putString(STATE_PENDING_WINDOWS_IDENTITY, it)
            }
            outState.putLong(STATE_PENDING_WINDOWS_ATTACH, context.attachGeneration)
            outState.putInt(STATE_PENDING_WINDOWS_REQUEST, context.requestCode)
        }
    }

    override fun onStart() {
        super.onStart()
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
        checkAttachedDevices()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbReceiver) }
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
            viewModel.onUsbDeviceDetached()
            viewModel.onIntent(UiIntent.DeviceChanged(DevicePresence.Absent))
        }
    }

    private fun selectUsbDevice(device: UsbDevice) {
        val key = usb.device.UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        if (selectedUsbDeviceKey != key) pendingUsbPermissionKey = null
        selectedUsbDeviceKey = key
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        pendingUsbPermissionKey = "${device.deviceId}:${device.deviceName}"
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) PendingIntent.FLAG_IMMUTABLE else 0
        val permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION).setPackage(packageName),
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                runCatching {
                    startActivity(intent)
                }.onFailure {
                    runCatching {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
            }
        }
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "usb.multitool.USB_PERMISSION"
        private const val STATE_PENDING_DOWNLOAD = "usb.multitool.PENDING_DOWNLOAD"
        private const val STATE_PENDING_FOLDER_ACTION = "usb.multitool.PENDING_FOLDER_ACTION"
        private const val STATE_PENDING_WINDOWS_KIND = "usb.multitool.PENDING_WINDOWS_KIND"
        private const val STATE_PENDING_WINDOWS_DIRECTORY =
            "usb.multitool.PENDING_WINDOWS_DIRECTORY"
        private const val STATE_PENDING_WINDOWS_PICK_SESSION =
            "usb.multitool.PENDING_WINDOWS_PICK_SESSION"
        private const val STATE_PENDING_WINDOWS_IDENTITY =
            "usb.multitool.PENDING_WINDOWS_IDENTITY"
        private const val STATE_PENDING_WINDOWS_ATTACH =
            "usb.multitool.PENDING_WINDOWS_ATTACH"
        private const val STATE_PENDING_WINDOWS_REQUEST =
            "usb.multitool.PENDING_WINDOWS_REQUEST"
        private const val WINDOWS_IMPORT_TIMEOUT_MS = 5 * 60 * 1000L

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
