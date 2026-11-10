package usb.multitool
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
import usb.ventoy.VentoyGlobalSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
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
import usb.ui.readOnlyOperationProgress
import usb.ui.OperationState
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiMessage
import usb.ui.UiMessageKey
import usb.ui.UiReducer
import usb.ui.AppLanguage
import usb.device.UsbDiskSession
import usb.diagnostics.Diagnostics
import usb.diagnostics.Rescue
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueOperations
import usb.files.FilesOperations
import usb.diagnostics.RescueBackupOperations
import usb.diagnostics.RescueRestorePrep
import usb.diagnostics.RescueSink
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

class MainViewModel(application: Application) : AndroidViewModel(application) {
    private companion object {
        const val LANGUAGE_KEY = "language"
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

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeHandle: OperationHandle? = null
    private var rawSourceHandle: OperationHandle? = null
    private var windowsImageHandle: OperationHandle? = null
    private val activeHandles = java.util.concurrent.ConcurrentHashMap.newKeySet<OperationHandle>()
    private var attachGeneration = 0L
    @Volatile
    var attachedDevice: UsbDevice? = null
        private set
    @Volatile
    var usbManager: UsbManager? = null
        private set

    private fun deviceKey(device: UsbDevice?): String? =
        device?.let { "${it.deviceId}:${it.deviceName}" }

    private val languagePrefs =
        application.getSharedPreferences("ui_prefs", Context.MODE_PRIVATE)

    init {
        state = state.copy(language = readLanguage())
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
        FilesVolumeSession { partitionId, writable -> openMountedFilesVolume(partitionId, writable) }
    /** Key of the newest free-space request; a different key asks again. */
    @Volatile private var lastUsageKey: FilesystemUsageKey? = null
    /** Key whose measurement has not reported back yet; prevents duplicate requests. */
    @Volatile private var usageInFlightKey: FilesystemUsageKey? = null
    @Volatile private var stagedConsumedPath: String? = null


    override fun onCleared() {
        // Detach/clear must not crash the process if umount/sync fails on a yanked stick.
        runCatching { closeFilesVolume(reportStatus = false) }
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

    fun onIntent(intent: UiIntent) {
        if (intent is UiIntent.SetLanguage) {
            languagePrefs.edit().putString(LANGUAGE_KEY, intent.language.name).apply()
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
        when (effect.kind) {
            UiEffect.Kind.SubmitDestructive -> {
                val action = effect.action
                val opTitle = action?.let { OperationTitle.Destructive(it) }
                    ?: error("destructive action missing")
                val actionName = opTitle.logLabel
                val uiState = state
                val confirmed = effect.confirmation
                    ?: throw IllegalStateException("destructive confirmation missing")
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
                                    val uri = uiState.backupUri
                                        ?: throw IllegalStateException("no GPT backup file selected")
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
                                    val imageUri = uiState.rescueImageUri
                                        ?: throw IllegalStateException("no rescue image selected")
                                    val mapUri = uiState.rescueMapUri
                                        ?: throw IllegalStateException("no rescue map selected")
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
                                    VentoyOperations.install(partitionDisk, assets, this)
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
                                    VentoyOperations.update(partitionDisk, assets, this)
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.VENTOY_DOWNLOAD_INSTALL -> {
                                    val released = VentoyAssetOperations.download(
                                        cacheDir = getApplication<Application>().cacheDir,
                                        operation = this,
                                        isActive = { isActive() },
                                    )
                                    bytesTotal = -1L
                                    VentoyOperations.install(partitionDisk, released.assets, this)
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
                                null -> throw IllegalArgumentException("no destructive action selected")
                            }
                            syncDestructiveDisk(disk)
                        } finally {
                            disk.close()
                        }
                    }
                }
                val isCooperative = usb.operation.OperationLifecyclePolicy.isCooperative(action)
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
                        val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                        val errorMsg = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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
            UiEffect.Kind.ReadScan ->
                submitReadOnlyDiskOperation(OperationTitle.ReadScan) { disk, operation, sink ->
                    val report = Diagnostics.readScan(disk, operation = operation)
                    sink.readScanResult = ReadScanOutcome.from(report, disk.geometry.sectorCount)
                    // UI formats from sink; empty resultMessage avoids frozen language string.
                    ""
                }

            UiEffect.Kind.RangeProbe -> {
                val start = effect.startLba
                val count = effect.sectorCount
                if (start != null && count != null) {
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
                                requestId = effect.probeRequestId ?: 0L,
                            )
                        onIntentDeferred(UiIntent.RangeProbeResulted(result))
                        "Range probe: ${result.finding}, " +
                            "${result.sampledSectors.size} sector(s) sampled"
                    }
                }
            }

            UiEffect.Kind.RescueBackup ->
                submitReadOnlyDiskOperation(OperationTitle.RescueBackup) { disk, operation, _ ->
                    val dir = backupDirectory()
                    val stamp = "${timestamp()}-${System.nanoTime()}"
                    val image = File(dir, "rescue-$stamp.img")
                    if (image.exists()) {
                        throw IllegalStateException("rescue image name collision: ${image.name}")
                    }
                    val mapFile = File(dir, "rescue-$stamp.map")
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
                    val identity = deviceId.identity
                    val need = usb.diagnostics.RescueRangePolicy.requireSignedBytes(
                        disk.geometry.sectorCount,
                        disk.geometry.sectorSize,
                    )
                    operation.bytesTotal = need
                    // usableSpace is an estimate, not a reservation.
                    val usable = dir.usableSpace
                    if (usable in 1 until need) {
                        throw java.io.IOException(
                            "not enough free space under ${dir.path}: usable=$usable, need≈$need (estimate)",
                        )
                    }
                    val copyMap =
                        RescueMap.forCopy(
                            disk.geometry.sectorCount,
                            sectorSize = sectorSize,
                            identity = identity,
                        )
                    RescueBackupOperations.copy(
                        disk = disk,
                        image = image,
                        mapFile = mapFile,
                        map = copyMap,
                        operation = operation,
                    )
                    val copied = copyMap.copied.sumOf { it.last - it.first + 1UL }
                    val unreadable = copyMap.failed.sumOf { it.last - it.first + 1UL }
                    val note =
                        if (copyMap.partial) {
                            " (partial/incomplete — map kept for diagnosis; in-app backup resume " +
                                "is not supported — re-run backup to finish NON_TRIED sectors)"
                        } else {
                            ""
                        }
                    "Rescue backup: image ${image.absolutePath}, map ${mapFile.absolutePath}: " +
                        "$copied copied, $unreadable unreadable of ${copyMap.sectorCount} sectors$note"
                }

            UiEffect.Kind.CaptureGptBackup ->
                submitReadOnlyDiskOperation(OperationTitle.GptCriticalBackup) { disk, operation, _ ->
                    val blob = GptBackup.backupCriticalSectors(disk)
                    val info = GptCriticalBackup.inspectCaptured(
                        blob = blob,
                        disk = disk,
                        primarySectors = GptBackup.primarySectors(disk.sectorSize),
                        backupSectors = GptBackup.backupSectors(disk.sectorSize),
                    )
                    val file = File(backupDirectory(), "gpt-critical-${timestamp()}.bin")
                    file.writeBytes(blob)
                    operation.bytesTotal = blob.size.toLong()
                    operation.report(blob.size.toLong())
                    "GPT critical backup saved: ${file.absolutePath} (${blob.size} bytes, " +
                        "${info.partitions} partitions, backup header at LBA " +
                        "${info.backup.currentLba})"
                }

            UiEffect.Kind.CancelOperation -> {
                activeHandle?.cancel()
            }
            UiEffect.Kind.LoadFiles -> {
                listDirectory(effect.path ?: state.currentPath)
            }
            UiEffect.Kind.LoadFilesystemUsage -> {
                val partitionId = effect.partitionId
                val usageKey = effect.usageKey
                val generation = effect.usageGeneration
                if (partitionId != null && usageKey != null && generation != null) {
                    measureFilesystemUsage(partitionId, usageKey, generation)
                }
            }
            UiEffect.Kind.CreateFolder -> {
                val name = effect.name
                val path = effect.path
                if (name != null && path != null) createFolder(path, name)
            }
            UiEffect.Kind.DeleteEntry -> {
                val name = effect.name
                val path = effect.path
                if (name != null && path != null) deleteEntry(path, name)
            }
            UiEffect.Kind.RenameEntry -> {
                val name = effect.name
                val newName = effect.newName
                val path = effect.path
                val binding = effect.binding
                if (name != null && newName != null && path != null && binding != null) {
                    renameEntry(path, name, newName, binding)
                }
            }
            UiEffect.Kind.UploadFile -> {
                val uri = effect.uri
                val path = effect.path
                if (uri != null && path != null) uploadFile(path, uri)
            }
            UiEffect.Kind.DownloadFile -> {
                val name = effect.name
                val uri = effect.uri
                val path = effect.path
                if (name != null && uri != null && path != null) downloadFile(path, name, uri)
            }
            UiEffect.Kind.StageUsbImageForRawWrite -> {
                val name = effect.name
                val path = effect.path
                val binding = effect.binding
                if (name != null && path != null && binding != null) {
                    stageUsbImageForRawWrite(path, name, binding)
                } else {
                    onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.CannotStage)))
                }
            }

            UiEffect.Kind.LoadRawSources -> {
                val binding = effect.binding
                val generation = effect.requestGeneration
                if (binding != null && generation != null) {
                    loadRawSourceCandidates(binding, generation)
                }
            }

            UiEffect.Kind.CancelRawSources -> {
                rawSourceHandle?.cancel()
                rawSourceHandle = null
            }

            UiEffect.Kind.LoadWindowsIsoCandidates -> {
                val generation = effect.requestGeneration
                if (generation != null) loadWindowsImageCandidates(generation)
            }

            UiEffect.Kind.InspectIsoImport -> {
                val uri = effect.uri
                val generation = effect.requestGeneration
                if (uri != null && generation != null) {
                    inspectImageImport(uri, generation, effect.importKind, effect.importDirectory)
                }
            }

            UiEffect.Kind.ImportIso -> {
                val proposal = effect.isoImport
                if (proposal != null) importImage(proposal, effect.overwrite)
            }

            UiEffect.Kind.LinkXml -> {
                val imagePaths =
                    effect.imagePaths.takeIf { it.isNotEmpty() }
                        ?: effect.isoName?.takeIf { it.isNotBlank() }?.let { listOf(it) }
                        ?: emptyList()
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
                val confirmed = effect.confirmation ?: throw IllegalStateException("Ventoy link confirmation missing")
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
                        val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                        val failure = (outcome as? usb.operation.OperationState.Failed)?.cause
                        val errorMsg = usb.operation.OperationCoordination.errorMessage(outcome)
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

            UiEffect.Kind.LoadWindowsLinks -> {
                val generation = effect.requestGeneration
                if (generation != null) loadWindowsImageLinks(generation)
            }

            UiEffect.Kind.UnlinkXml -> {
                if (effect.imagePaths.isNotEmpty()) unlinkWindowsImages(effect.imagePaths)
            }

            UiEffect.Kind.SaveWindowsControls -> saveWindowsControls()

            UiEffect.Kind.LoadAutounattend ->
                effect.xmlName?.let { loadAutounattend(it, effect.requestGeneration ?: 0L) }

            UiEffect.Kind.LoadAutounattendCandidates ->
                effect.xmlName?.let {
                    loadAutounattendCandidates(it, effect.requestGeneration ?: 0L)
                }

            UiEffect.Kind.GenerateAutounattend -> generateAutounattend(effect)

            UiEffect.Kind.LoadVentoyGlobalSettings -> loadVentoyGlobalSettings()

            UiEffect.Kind.LoadVentoyFolders -> loadVentoyFolders()

            UiEffect.Kind.SetVentoyGlobalSetting -> {
                val key = effect.settingKey
                if (key != null) setVentoyGlobalSetting(key, effect.settingValue)
            }

            UiEffect.Kind.BindAutounattend -> {
                val name = effect.xmlName
                if (name != null && effect.imagePaths.isNotEmpty()) {
                    bindAutounattend(
                        name,
                        effect.imagePaths,
                        effect.allowDestructiveTemplate,
                        effect.expectedTemplateSha256,
                    )
                }
            }

            UiEffect.Kind.UnbindAutounattend -> {
                val name = effect.xmlName
                if (name != null && effect.imagePaths.isNotEmpty()) {
                    unbindAutounattend(name, effect.imagePaths.first())
                }
            }
            UiEffect.Kind.RunSelfTest -> {
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
        val gen = if (sameDevice) attachGeneration else ++attachGeneration
        if (isNewDevice) {
            usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
            activeHandles.clear()
            activeHandle = null
            scope.launch(Dispatchers.IO) {
                if (attachGeneration == gen) {
                    closeFilesVolume()
                }
            }
        }
        attachedDevice = device
        DiskController.get().onConnected(key)
        if (manager != null) {
            usbManager = manager
        }
        val mgr = usbManager
        val identity = UsbDeviceIdentity.of(device)
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
                            } else {
                                listDirectory(this@MainViewModel.state.currentPath)
                            }
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
                                    device = state.device ?: UsbDeviceIdentity.of(dev),
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
        val gen = ++attachGeneration
        attachedDevice = null
        lastUsageKey = null
        usageInFlightKey = null
        usb.operation.OperationLifecyclePolicy.cancelAll(activeHandles)
        activeHandles.clear()
        activeHandle = null
        scope.launch(Dispatchers.IO) {
            if (attachGeneration == gen) {
                closeFilesVolume()
            }
        }
        DiskController.get().onDisconnected()
        onIntent(UiIntent.DeviceChanged(presence = DevicePresence.Disconnected))
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
    private fun openMountedFilesVolume(partitionId: String, writable: Boolean): MountedFilesVolume {
        val device = attachedDevice ?: throw DiskException.Disconnected()
        val manager = usbManager ?: throw DiskException.Disconnected()
        val disk = UsbDiskSession.open(manager, device)
        val volume = try {
            val index = partitionId.toIntOrNull()
                ?: throw IllegalArgumentException("invalid partition id $partitionId")
            val table = PartitionTable.read(disk)
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
                            val volume = filesSession.volumeForLocked(partitionId)
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
    fun listDirectory(path: String, status: UiMessage? = null) = withPartitionId { partitionId ->
        submitFilesOperation(
            title = OperationTitle.ListPath(path),
            failurePath = path,
            onSuccess = { status?.let { onIntent(UiIntent.Status(it)) } },
        ) {
            val entries = usb.files.FilesOperations.list(filesSession.volumeForLocked(partitionId), path)
            post(UiIntent.FilesListed(path, entries.map { it.toFileEntry() }))
        }
    }

    private fun createFolder(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            submitFilesOperation(
                title = OperationTitle.NewFolder(name),
                onSuccess = { listDirectory(path, status = UiMessage.Key(UiMessageKey.CreatedFolder, listOf(target))) },
            ) { operation ->
                filesSession.withWritableVolume(partitionId, operation) { volume ->
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
            submitFilesOperation(
                title = OperationTitle.DeleteEntry(name),
                onSuccess = { listDirectory(path, status = UiMessage.Key(UiMessageKey.DeletedPath, listOf(target))) },
            ) { operation ->
                filesSession.withWritableVolume(partitionId, operation) { volume ->
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
        val expectedGeneration = attachGeneration
        val expectedDeviceKey = deviceKey(attachedDevice)
        withWritablePartitionId { partitionId ->
            submitFilesOperation(
                title = OperationTitle.RenameEntry(from),
                onSuccess = {
                    listDirectory(
                        path,
                        status = UiMessage.Key(
                            UiMessageKey.RenamedPath,
                            listOf(from, to),
                        ),
                    )
                },
            ) { operation ->
                require(partitionId == binding.partitionId) { "rename target partition changed" }
                require(attachGeneration == expectedGeneration) { "USB attach generation changed" }
                require(deviceKey(attachedDevice) == expectedDeviceKey) { "USB device changed" }
                require(binding.matches(state)) { "rename target changed" }
                filesSession.withWritableVolume(partitionId, operation) { volume ->
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
            submitFilesOperation(
                title = OperationTitle.Upload,
                onSuccess = {
                    val name = uploaded
                    listDirectory(
                        path,
                        status = name?.let {
                            UiMessage.Key(UiMessageKey.UploadedFile, listOf(it, path))
                        },
                    )
                },
            ) { operation ->
                val document = Uri.parse(uri)
                val (reportedName, reportedSize) = contentInfo(resolver, document)
                val rawName = reportedName ?: document.lastPathSegment ?: uri
                val name = FilesOperationPolicy.requireChildName(rawName, uri)
                uploaded = name
                val target = fatChildPath(path, name)
                operation.bytesTotal = reportedSize ?: -1L

                filesSession.withWritableVolume(partitionId, operation) { volume ->
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
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
            require(attachGeneration == expectedAttach) { "USB attach generation changed" }
            require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
            require(binding.matches(state)) { "raw source target changed" }
            val target = fatChildPath(path, name)
            val volume = filesSession.volumeForLocked(binding.partitionId)
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
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val identity = state.device?.identity
        rawSourceHandle?.cancel()
        rawSourceHandle = submitFilesOperation(
            title = OperationTitle.ListRawSources,
            quiet = true,
        ) { operation ->
            require(attachGeneration == expectedAttach) { "USB attach generation changed" }
            require(deviceKey(attachedDevice) == expectedKey) { "USB device changed" }
            require(binding.matches(state)) { "raw source partition changed" }
            val volume = filesSession.volumeForLocked(binding.partitionId)
            val listing = usb.image.RawSourceChooser.list(
                volume = volume,
                root = "/",
                maxEntries = 1000,
                maxDepth = 4,
                isActive = { operation.isActive() },
            )
            if (attachGeneration == expectedAttach && state.device?.identity == identity) {
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
                    post(UiIntent.Status(UiMessage.Raw(message)))
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
    ) {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val identity = state.device?.identity
        val resolver = getApplication<Application>().contentResolver
        val document = Uri.parse(uri)
        scope.launch(Dispatchers.IO) {
            try {
                val (reportedName, reportedSize) = contentInfo(resolver, document)
                val rawName = reportedName ?: document.lastPathSegment
                    ?: throw IllegalArgumentException("picker returned no file name")
                val name = usb.ventoy.VentoyIsoLibrary.requireImportName(rawName)
                val directory =
                    destinationDirectory?.let {
                        usb.ventoy.VentoyIsoLibrary.normalizeDirectory(it)
                    } ?: if (kind == usb.ui.WindowsImportKind.XmlTemplate) {
                        "/ventoy"
                    } else {
                        "/ISO"
                    }
                val target = if (directory == "/") "/$name" else "$directory/$name"
                require(
                    !target.equals("/ventoy/ventoy.json", ignoreCase = true),
                ) { "refusing to overwrite the Ventoy config with a template" }
                val markers =
                    if (kind == usb.ui.WindowsImportKind.XmlTemplate) {
                        inspectPhoneTemplate(resolver, document)
                    } else {
                        emptyList()
                    }
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                val exists =
                    try {
                        usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
                            if (destinationDirectory != null && directory != "/") {
                                val chosen = volume.statOrNull(directory)
                                require(chosen != null && chosen.isDirectory) {
                                    "import folder \"$directory\" does not exist on the Ventoy partition"
                                }
                            }
                            volume.statOrNull(target) != null
                        }
                    } finally {
                        disk.close()
                    }
                if (attachGeneration == expectedAttach &&
                    deviceKey(attachedDevice) == expectedKey &&
                    state.device?.identity == identity
                ) {
                    post(
                        UiIntent.WindowsIsoImportInspected(
                            uri = uri,
                            name = name,
                            sizeBytes = reportedSize,
                            targetName = name,
                            exists = exists,
                            generation = generation,
                            kind = kind,
                            templateMarkers = markers,
                            directory = directory,
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
                } else {
                    // The stick changed under the picker: say so instead of dropping the result
                    // and leaving the user with no dialog and no message.
                    post(
                        UiIntent.Status(
                            UiMessage.Raw("USB attachment changed; re-select the file to import"),
                        ),
                    )
                }
            } catch (failure: Throwable) {
                val problem =
                    when (failure) {
                        is usb.ventoy.NotUnattendRootException ->
                            UnreadableProblem.NotUnattendRoot
                        is usb.ventoy.TemplateTooLargeException ->
                            UnreadableProblem.TooLarge
                        is usb.ventoy.EmptyTemplateException ->
                            UnreadableProblem.Empty
                        is usb.ventoy.InvalidTemplateException ->
                            UnreadableProblem.MalformedXml
                        is java.io.IOException -> UnreadableProblem.IoFailure
                        else -> UnreadableProblem.MalformedXml
                    }
                post(
                    UiIntent.WindowsIsoImportRejected(
                        problem = problem,
                        reason = failure.message ?: failure.javaClass.simpleName,
                    ),
                )
            }
        }
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
        val op = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val probe = usb.ventoy.VentoyLayout.probe(disk)
                        ?: throw usb.ventoy.IncompatibleVentoyException(
                            "No Ventoy data partition on this disk",
                        )
                    val sectorSize = disk.sectorSize.toULong()
                    val maxBytes =
                        if (probe.part1SectorCount <= Long.MAX_VALUE.toULong() / sectorSize) {
                            (probe.part1SectorCount * sectorSize).toLong()
                        } else {
                            Long.MAX_VALUE
                        }
                    bytesTotal = proposal.sizeBytes ?: -1L
                    val outcome = usb.ventoy.VentoySetup.withDataVolume(disk) { volume ->
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
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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
                    }
                    onIntent(UiIntent.LoadWindowsIsoCandidates)
                    onIntent(UiIntent.LoadWindowsImageLinks)
                } else if (message != null) {
                    onIntent(UiIntent.Status(UiMessage.Raw(message)))
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
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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

    private fun generateAutounattend(effect: UiEffect) {
        val xmlName = effect.xmlName ?: return
        val params = effect.autounattendParams ?: return
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
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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
                    onIntent(UiIntent.LoadAutounattend(xmlName))
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

    private fun loadVentoyGlobalSettings() {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val op = object : DiskOperation(OperationTitle.ReadVentoyGlobalSettings.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val settings = usb.ventoy.VentoySetup.readGlobalSettings(disk)
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
                        post(UiIntent.VentoyGlobalSettingsLoaded(settings))
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
                            VentoyGlobalSettings(emptyList(), message),
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun loadVentoyFolders() {
        val expectedAttach = attachGeneration
        val expectedKey = deviceKey(attachedDevice)
        val op = object : DiskOperation(OperationTitle.ListVentoyFolders.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val folders = usb.ventoy.VentoySetup.listDirectories(disk)
                    if (attachGeneration == expectedAttach && deviceKey(attachedDevice) == expectedKey) {
                        post(UiIntent.VentoyFoldersLoaded(folders))
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
                    post(
                        UiIntent.VentoyFoldersLoaded(
                            emptyList(),
                            outcome.cause.message ?: outcome.cause.javaClass.simpleName,
                        ),
                    )
                }
            } finally {
                activeHandles.remove(handle)
            }
        }
    }

    private fun setVentoyGlobalSetting(key: String, value: String?) {
        val op = object : DiskOperation(OperationTitle.SetVentoyGlobalSetting.logLabel) {
            override suspend fun run() {
                requireFilesVolumeClosed(closeFilesVolume(reportStatus = false))
                val dev = attachedDevice ?: throw DiskException.Disconnected()
                val mgr = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    usb.ventoy.VentoySetup.setGlobalSetting(
                        disk = disk,
                        key = key,
                        value = value,
                        onMutation = { mutationAttempted = true },
                    )
                    if (mutationAttempted) commitReached = true
                } finally {
                    disk.close()
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = OperationTitle.SetVentoyGlobalSetting,
                    cancelable = false,
                ),
            ),
        )
        val handle = DiskController.get().submit(op)
        activeHandles.add(handle)
        scope.launch {
            try {
                val outcome = handle.await()
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = mappedState,
                            title = OperationTitle.SetVentoyGlobalSetting,
                            errorMessage = message,
                            cancelable = false,
                        ),
                    ),
                )
                if (mappedState == OperationState.Completed) {
                    onIntent(UiIntent.LoadVentoyGlobalSettings)
                } else if (message != null) {
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
                val mappedState = usb.operation.OperationCoordination.uiState(outcome)
                val message = usb.operation.OperationCoordination.errorMessage(outcome, op.failureCause)
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
                    // LoadAutounattend now refreshes the document *and* the candidate list,
                    // which is the only source of the Bound flag and of the Unbind button.
                    onIntent(UiIntent.LoadAutounattend(xmlName))
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
            submitFilesOperation(
                title = OperationTitle.DownloadEntry(name),
                onSuccess = { onIntent(UiIntent.Status(UiMessage.Key(UiMessageKey.SavedFile, listOf(name)))) },
            ) { operation ->
                val target = fatChildPath(path, name)
                val destination = Uri.parse(uri)
                val volume = filesSession.volumeForLocked(partitionId)
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
     */
    private fun submitFilesOperation(
        title: OperationTitle,
        bytesTotal: Long = -1L,
        failurePath: String? = null,
        onSuccess: (() -> Unit)? = null,
        onTerminal: ((usb.operation.OperationState) -> Unit)? = null,
        quiet: Boolean = failurePath != null,
        body: (DiskOperation) -> Unit,
    ): OperationHandle {
        val label = title.logLabel
        val operation = object : DiskOperation(name = label, bytesTotal = bytesTotal) {
            override suspend fun run() {
                filesSession.withLock {
                    body(this)
                }
            }
        }
        if (!quiet) onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    title = title,
                    bytesTotal = bytesTotal,
                ),
            ),
        )
        val handle = DiskController.get().submit(operation)
        // A quiet listing must never become the CancelOperation target of a live write.
        if (!quiet) activeHandle = handle
        activeHandles.add(handle)
        scope.launch {
            val ticker = launch {
                var lastLogAt = android.os.SystemClock.elapsedRealtime()
                var lastLogged = -1L
                while (true) {
                    delay(PROGRESS_TICK_MS)
                    if (!quiet) onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Running,
                                title = title,
                                bytesDone = operation.bytesDone,
                                bytesTotal = operation.bytesTotal,
                            ),
                        ),
                    )
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
                when (outcome) {
                    is usb.operation.OperationState.Failed -> {
                        val message =
                            outcome.cause.message
                                ?: outcome.cause.javaClass.simpleName
                        if (failurePath != null) {
                            onIntent(UiIntent.FilesListFailed(failurePath, message))
                        } else {
                            onIntent(UiIntent.Status(UiMessage.Raw(message)))
                        }
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Failed,
                                    title = title,
                                    errorMessage = message,
                                ),
                            ),
                        )
                        // The USB claim may be gone; reopen for the next attempt.
                        scope.launch(Dispatchers.IO) { runCatching { closeFilesVolume(reportStatus = true) } }
                        onTerminal?.invoke(outcome)
                    }
                    is usb.operation.OperationState.Cancelled -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(state = OperationState.Cancelled, title = title),
                            ),
                        )
                        onTerminal?.invoke(outcome)
                    }
                    is usb.operation.OperationState.Partial -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Partial,
                                    title = title,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    errorMessage = operation.failureCause?.message
                                        ?: operation.failureCause?.javaClass?.simpleName
                                        ?: "Incomplete operation",
                                ),
                            ),
                        )
                        onTerminal?.invoke(outcome)
                    }
                    else -> {
                        if (!quiet) onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Completed,
                                    title = title,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                ),
                            ),
                        )
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
        viewModel.onIntent(UiIntent.SetBackupFile(uri.toString()))
    }

    private val rescueImagePicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onIntent(UiIntent.SetRescueImage(uri.toString()))
    }

    private val rescueMapPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onIntent(UiIntent.SetRescueMap(uri.toString()))
    }

    /** Kind and destination waiting for the SAF file chosen in [windowsImportLauncher]. */
    private var pendingWindowsImportKind = usb.ui.WindowsImportKind.Image
    private var pendingWindowsImportDirectory: String? = null

    /**
     * SAF document picked for a phone-to-stick import. The kind and the chosen existing
     * folder are captured before the picker opens, because the result arrives later. The
     * source document is only read: nothing on the phone is moved, renamed or deleted.
     */
    private val windowsImportLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri: Uri? ->
        val kind = pendingWindowsImportKind
        val directory = pendingWindowsImportDirectory
        pendingWindowsImportKind = usb.ui.WindowsImportKind.Image
        pendingWindowsImportDirectory = null
        if (uri == null) return@registerForActivityResult
        runCatching {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        viewModel.onIntent(UiIntent.RequestImportWindowsIso(uri.toString(), kind, directory))
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // A re-delivered CreateDocument result arrives after the activity is recreated
        // (rotation/process death); without this it would find no pending name and drop it.
        pendingDownloadName = savedInstanceState?.getString(STATE_PENDING_DOWNLOAD)
        // The document picker may recreate the activity; without these two the result would
        // arrive as an image bound for /ISO, so an XML chosen for /ventoy would be misfiled and
        // its marker scan skipped.
        savedInstanceState?.getString(STATE_PENDING_WINDOWS_KIND)?.let { stored ->
            pendingWindowsImportKind = runCatching {
                usb.ui.WindowsImportKind.valueOf(stored)
            }.getOrDefault(usb.ui.WindowsImportKind.Image)
        }
        pendingWindowsImportDirectory =
            savedInstanceState?.getString(STATE_PENDING_WINDOWS_DIRECTORY)
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
                    pendingWindowsImportKind = kind
                    pendingWindowsImportDirectory = directory
                    windowsImportLauncher.launch(arrayOf("*/*"))
                },
                onPickBackup = {
                    gptBackupPickerLauncher.launch(arrayOf("*/*"))
                },
                onPickRescueImage = {
                    rescueImagePicker.launch(arrayOf("*/*"))
                },
                onPickRescueMap = {
                    rescueMapPicker.launch(arrayOf("*/*"))
                },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_DOWNLOAD, pendingDownloadName)
        outState.putString(STATE_PENDING_WINDOWS_KIND, pendingWindowsImportKind.name)
        pendingWindowsImportDirectory?.let {
            outState.putString(STATE_PENDING_WINDOWS_DIRECTORY, it)
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
        private const val STATE_PENDING_WINDOWS_KIND = "usb.multitool.PENDING_WINDOWS_KIND"
        private const val STATE_PENDING_WINDOWS_DIRECTORY =
            "usb.multitool.PENDING_WINDOWS_DIRECTORY"
    }
}
