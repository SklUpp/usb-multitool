package usb.multitool

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
import usb.service.DiskController
import usb.service.DiskOperation
import usb.service.OperationHandle
import usb.service.DiskService
import usb.ui.AppShell
import usb.ui.AppUiState
import usb.ui.DeviceIdentity
import usb.ui.DevicePresence
import usb.ui.FileEntry
import usb.ui.OperationProgress
import usb.ui.OperationState
import usb.ui.UiEffect
import usb.ui.UiIntent
import usb.ui.UiReducer
import usb.ui.displayName
import usb.device.UsbDiskSession
import usb.diagnostics.Diagnostics
import usb.diagnostics.Rescue
import usb.diagnostics.RescueMap
import usb.diagnostics.RescueSink
import usb.fs.FatDirEntry
import usb.fs.FatFsException
import usb.fs.FatVolume
import usb.fs.FilesystemId
import usb.fs.Formatter
import usb.fs.PartitionVolumeSession
import usb.ui.fatChildPath
import usb.partition.GptBackup
import usb.partition.PartitionEditor
import usb.ui.PartitionTab
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.PartitionWriter
import usb.partition.VirtualPartition
import usb.image.ImageSource
import usb.image.RawWriter
import usb.ventoy.VentoyAssets
import usb.ventoy.VentoyDownloader
import usb.ventoy.VentoyInstaller
import usb.ventoy.VentoySetup
import java.io.File
import java.io.RandomAccessFile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import usb.ui.DestructiveAction

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(AppUiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeHandle: OperationHandle? = null
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

    init {
        DiskService.initialize(application)
    }
    /**
     * Owns the mounted FAT volume of the selected partition. Guarded by [filesLock]:
     * every files operation runs inside it, so a close can never race a mounted read.
     * The ceiling of this coarse lock is that detach/onCleared can wait for one files
     * operation to finish; upgrade only if a single FAT call ever becomes unbounded.
     */
    private val filesLock = Any()
    private var filesVolume: MountedFilesVolume? = null

    private class MountedFilesVolume(
        val partitionId: String,
        private val disk: UsbDisk,
        val volume: FatVolume,
    ) : AutoCloseable {
        override fun close() {
            runCatching { volume.close() }
            runCatching { disk.close() }
        }
    }

    override fun onCleared() {
        closeFilesVolume()
        super.onCleared()
        scope.cancel()
    }

    fun onIntent(intent: UiIntent) {
        val result = UiReducer.reduce(state, intent)
        state = result.state
        result.effects.forEach { effect ->
            handleEffect(effect)
        }
    }

    fun handleEffect(effect: UiEffect) {
        when (effect.kind) {
            UiEffect.Kind.SubmitDestructive -> {
                val action = effect.action
                val actionName = action?.displayName() ?: "destructive"
                val uiState = state
                val op = object : DiskOperation(name = actionName) {
                    /** Concrete outcome surfaced instead of the bare "Success" label. */
                    @Volatile var result: String? = null

                    override suspend fun run() {
                        // Release the FAT session and its USB claim before raw/partition work.
                        closeFilesVolume()
                        val dev = attachedDevice ?: throw usb.device.DiskException.Disconnected()
                        val mgr = usbManager ?: throw usb.device.DiskException.Disconnected()
                        val disk = UsbDiskSession.open(mgr, dev)
                        try {
                            val identity = uiState.device?.label ?: dev.productName ?: "USB Storage"
                            val confirmation = usb.diagnostics.DestructiveConfirmation(
                                identity = identity,
                                capacityBytes = uiState.device?.capacityBytes ?: disk.geometry.capacityBytes,
                            )
                            val table = runCatching { PartitionTable.read(disk) }.getOrNull()
                            val selected = table?.let { t ->
                                val index = uiState.selectedPartitionId?.toIntOrNull() ?: 1
                                t.partitions.getOrNull(index - 1)
                            }
                            when (action) {
                                DestructiveAction.WIPE -> {
                                    Diagnostics.wipe(
                                        disk = disk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        operation = this,
                                    )
                                }
                                DestructiveAction.WRITE_BENCHMARK -> {
                                    val startLba = 2048UL.coerceAtMost(disk.geometry.sectorCount - 1UL)
                                    val sectors = 2048UL.coerceAtMost(disk.geometry.sectorCount - startLba)
                                    Diagnostics.writeBenchmark(
                                        disk = disk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        startLba = startLba,
                                        sectors = sectors,
                                        operation = this,
                                    )
                                }
                                DestructiveAction.GPT_REPAIR -> {
                                    // Moves the backup entries + header to the current disk end,
                                    // fixes backupLba/lastUsable and recomputes both CRCs.
                                    val repaired = GptBackup.relocateBackup(disk, disk.geometry.sectorCount)
                                    result = "GPT backup relocated to LBA ${disk.geometry.sectorCount - 1UL}: " +
                                        "${repaired.partitions.size} partitions kept"
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.DELETE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    val partId = uiState.selectedPartitionId
                                        ?: throw IllegalStateException("no partition selected for deletion")
                                    val index = (partId.toIntOrNull()
                                        ?: throw IllegalStateException("invalid partition id: $partId")) - 1
                                    if (current.partitions.getOrNull(index) == null) {
                                        throw IllegalStateException("no partition selected for deletion")
                                    }
                                    PartitionWriter.commit(disk, PartitionEditor.delete(current, index))
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.CREATE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    val align = PartitionTable.alignmentSectors(current.sectorSize)
                                    val region = current.freeRegions.firstOrNull {
                                        PartitionEditor.alignedCapacity(it, align) > 0UL
                                    } ?: throw usb.partition.NoFreeSpaceException("no free space")
                                    val size = PartitionEditor.alignedCapacity(region, align)
                                    PartitionWriter.commit(
                                        disk,
                                        PartitionEditor.create(current, size = size, type = PartitionType.BASIC),
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.FORMAT_PARTITION -> {
                                    val partId = uiState.selectedPartitionId
                                        ?: throw IllegalStateException("no partition selected to format")
                                    val index = (partId.toIntOrNull()
                                        ?: throw IllegalStateException("invalid partition id: $partId")) - 1
                                    val target = table?.partitions?.getOrNull(index)
                                        ?: throw IllegalStateException("no partition selected to format")
                                    val partition = VirtualPartition(
                                        startLba = target.startLba,
                                        sectorCount = target.sectorCount,
                                        disk = disk,
                                    )
                                    Formatter.format(partition, FilesystemId.FAT)
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
                                    )
                                    report(blob.size.toLong())
                                    result = "Restored ${restored.sectorsRestored} GPT critical " +
                                        "sectors from ${uri.substringAfterLast('/')}: " +
                                        "${restored.partitions} partitions, backup at LBA " +
                                        "${restored.backup.currentLba} verified"
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
                                    VentoyInstaller.install(
                                        disk = disk,
                                        assets = assets,
                                        isActive = { isActive() },
                                        onProgress = { done, total ->
                                            bytesTotal = total
                                            report(done)
                                        },
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
                                    VentoyInstaller.update(
                                        disk = disk,
                                        assets = assets,
                                        isActive = { isActive() },
                                        onProgress = { done, total ->
                                            bytesTotal = total
                                            report(done)
                                        },
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.VENTOY_DOWNLOAD_INSTALL -> {
                                    val released = VentoyDownloader.downloadRelease(
                                        cacheDir = getApplication<Application>().cacheDir,
                                        release = VentoyAssets.RELEASE_1_1_05,
                                        onProgress = { done, total ->
                                            if (total > 0) bytesTotal = total
                                            report(done)
                                        },
                                        isActive = { isActive() },
                                    )
                                    bytesTotal = -1L
                                    VentoyInstaller.install(
                                        disk = disk,
                                        assets = released.assets,
                                        isActive = { isActive() },
                                        onProgress = { done, total ->
                                            bytesTotal = total
                                            report(done)
                                        },
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.RAW_WRITE -> {
                                    val uri = uiState.imageUri
                                        ?: throw IllegalStateException("no image selected for raw write")
                                    RawWriter.write(
                                        source = ImageSource.fromContentUri(
                                            Uri.parse(uri),
                                            getApplication<Application>().contentResolver,
                                        ),
                                        disk = disk,
                                        identity = identity,
                                        confirmation = confirmation,
                                        overwrite = true,
                                        operation = this,
                                    )
                                }
                                null -> throw IllegalArgumentException("no destructive action selected")
                            }
                            disk.sync()
                        } finally {
                            disk.close()
                        }
                    }
                }
                val isCooperative = action == DestructiveAction.RAW_WRITE ||
                    action == DestructiveAction.VENTOY_DOWNLOAD_INSTALL
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            label = actionName,
                            cancelable = isCooperative,
                        ),
                    ),
                )
                val handle = DiskController.get().submit(op)
                if (isCooperative) activeHandle = handle
                activeHandles.add(handle)
                scope.launch {
                    try {
                        val outcome = handle.await()
                        val mappedState = when (outcome) {
                            is usb.service.OperationState.Completed -> OperationState.Completed
                            is usb.service.OperationState.Cancelled -> OperationState.Cancelled
                            is usb.service.OperationState.Partial -> OperationState.Partial
                            is usb.service.OperationState.Failed -> OperationState.Failed
                            else -> OperationState.Completed
                        }
                        val errorMsg = (outcome as? usb.service.OperationState.Failed)?.cause?.message
                            ?: op.failureCause?.message
                            ?: (outcome as? usb.service.OperationState.Failed)?.cause?.javaClass?.simpleName
                            ?: op.failureCause?.javaClass?.simpleName
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = mappedState,
                                    label = actionName,
                                    errorMessage = errorMsg,
                                    resultMessage = op.result,
                                    cancelable = isCooperative,
                                ),
                            ),
                        )
                        when (action) {
                            DestructiveAction.DELETE_PARTITION,
                            DestructiveAction.CREATE_PARTITION,
                            DestructiveAction.FORMAT_PARTITION,
                            DestructiveAction.GPT_REPAIR,
                            DestructiveAction.GPT_RESTORE,
                            DestructiveAction.VENTOY_INSTALL,
                            DestructiveAction.VENTOY_UPDATE,
                            DestructiveAction.VENTOY_DOWNLOAD_INSTALL,
                            DestructiveAction.WIPE,
                            DestructiveAction.RAW_WRITE -> refreshPartitions()
                            else -> Unit
                        }
                    } finally {
                        if (activeHandle === handle) activeHandle = null
                        activeHandles.remove(handle)
                    }
                }
            }
            UiEffect.Kind.ReadScan ->
                submitReadOnlyDiskOperation("Read scan") { disk, operation ->
                    val report = Diagnostics.readScan(disk, operation = operation)
                    val unreadable = report.failed.sumOf { it.last - it.first + 1UL }
                    "Read scan: ${report.sectorsRead} of ${disk.geometry.sectorCount} " +
                        "sectors read, $unreadable unreadable in ${report.failed.size} range(s)"
                }

            UiEffect.Kind.RescueBackup ->
                submitReadOnlyDiskOperation("Rescue backup") { disk, operation ->
                    val stamp = timestamp()
                    val image = File(backupDirectory(), "rescue-$stamp.img")
                    val mapFile = File(backupDirectory(), "rescue-$stamp.map")
                    val sectorSize = disk.geometry.sectorSize
                    operation.bytesTotal = disk.geometry.capacityBytes.toLong()
                    // Seek per sector: an unreadable sector must leave a map-marked gap,
                    // never shift every later sector to the wrong offset.
                    val map = RandomAccessFile(image, "rw").use { file ->
                        Rescue.copy(
                            disk = disk,
                            destination = RescueSink { lba, sector ->
                                file.seek((lba * sectorSize.toULong()).toLong())
                                file.write(sector)
                            },
                            map = RescueMap.whole(disk.geometry.sectorCount),
                            operation = operation,
                        )
                    }
                    mapFile.writeText(map.encode())
                    val copied = map.copied.sumOf { it.last - it.first + 1UL }
                    val unreadable = map.failed.sumOf { it.last - it.first + 1UL }
                    val state = if (map.partial) " (partial map kept for resume)" else ""
                    "Rescue backup: image ${image.absolutePath}, map ${mapFile.absolutePath}: " +
                        "$copied copied, $unreadable unreadable of ${map.sectorCount} sectors$state"
                }

            UiEffect.Kind.CaptureGptBackup ->
                submitReadOnlyDiskOperation("GPT critical backup") { disk, operation ->
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
            // Ventoy/raw-writer routing is owned by the other tabs.
            UiEffect.Kind.LinkXml -> {
                val isoName = effect.isoName?.substringAfterLast('/')
                if (isoName.isNullOrBlank()) {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                label = "autounattend.xml link",
                                errorMessage = "No ISO selected for the Windows setup link",
                            ),
                        ),
                    )
                    return
                }
                val tpmBypass = state.vtoyBypassTpm
                val op = object : DiskOperation(name = "autounattend.xml link") {
                    override suspend fun run() {
                        // The browse volume still holds the USB interface claim; release it
                        // (as the destructive path does) or UsbDiskSession.open() hits -EBUSY.
                        // Remount is lazy: volumeForLocked() reopens on the next files operation.
                        closeFilesVolume()
                        val dev = attachedDevice ?: throw usb.device.DiskException.Disconnected()
                        val mgr = usbManager ?: throw usb.device.DiskException.Disconnected()
                        val disk = UsbDiskSession.open(mgr, dev)
                        try {
                            VentoySetup.linkXml(
                                disk = disk,
                                isoName = isoName,
                                options = usb.ventoy.SetupOptions(tpmBypass = tpmBypass),
                                overwrite = true,
                            )
                        } finally {
                            disk.close()
                        }
                    }
                }
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            label = "autounattend.xml link",
                            cancelable = false,
                        ),
                    ),
                )
                val handle = DiskController.get().submit(op)
                activeHandles.add(handle)
                scope.launch {
                    try {
                        val outcome = handle.await()
                        val mappedState = when (outcome) {
                            is usb.service.OperationState.Completed -> OperationState.Completed
                            is usb.service.OperationState.Cancelled -> OperationState.Cancelled
                            is usb.service.OperationState.Partial -> OperationState.Partial
                            is usb.service.OperationState.Failed -> OperationState.Failed
                            else -> OperationState.Completed
                        }
                        val errorMsg = (outcome as? usb.service.OperationState.Failed)?.cause?.message
                            ?: (outcome as? usb.service.OperationState.Failed)?.cause?.javaClass?.simpleName
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = mappedState,
                                    label = "autounattend.xml link",
                                    errorMessage = errorMsg,
                                    cancelable = false,
                                ),
                            ),
                        )
                    } finally {
                        activeHandles.remove(handle)
                    }
                }
            }
            UiEffect.Kind.SendToRawWriter -> Unit
            UiEffect.Kind.RunSelfTest -> {
                runSelfTest()
            }
        }
    }
    private fun runSelfTest() {
        val dev = attachedDevice
        val mgr = usbManager
        if (dev != null && mgr != null && state.presence == DevicePresence.Ready) {
            submitReadOnlyDiskOperation("Self-test", cancelable = false) { disk, _ ->
                val report = usb.diagnostics.SelfTestRunner.runAll(disk)
                check(report.allPassed) { report.summary() }
                report.summary()
            }
        } else {
            scope.launch {
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(state = OperationState.Running, label = "Self-test: running...", cancelable = false),
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
                                label = "Self-test",
                                resultMessage = if (report.allPassed) summary else null,
                                errorMessage = if (!report.allPassed) summary else null,
                                cancelable = false,
                            ),
                        ),
                    )
                    onIntent(UiIntent.Status(summary))
                } catch (t: Throwable) {
                    onIntent(
                        UiIntent.OperationUpdated(
                            OperationProgress(
                                state = OperationState.Failed,
                                label = "Self-test",
                                errorMessage = t.message ?: "Self-test failed",
                                cancelable = false,
                            ),
                        ),
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
        val gen = ++attachGeneration
        if (isNewDevice) {
            activeHandles.forEach { it.cancel() }
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
        val identity = DeviceIdentity(
            label = device.productName ?: device.deviceName ?: "USB Storage",
            capacityBytes = 0uL,
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
        onIntent(UiIntent.Status("Probing USB device..."))

        val op = object : DiskOperation(name = "Probe device") {
            override suspend fun run() {
                closeFilesVolume()
                val disk = UsbDiskSession.open(mgr, device)
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.partitions?.mapIndexed { idx, p ->
                        val isEfi = p.name.contains("VTOYEFI", ignoreCase = true) ||
                            p.type.contains("EFI", ignoreCase = true)
                        PartitionTab(
                            id = (idx + 1).toString(),
                            name = p.name.ifBlank { "Partition ${idx + 1}" },
                            readOnly = isEfi,
                            isVtoyEfi = isEfi,
                        )
                    } ?: emptyList()
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration != gen || attachedDevice?.deviceId != device.deviceId) {
                            return@withContext
                        }
                        val probedIdentity = DeviceIdentity(
                            label = device.productName ?: device.deviceName ?: "USB Storage",
                            capacityBytes = capacity,
                        )
                        onIntent(
                            UiIntent.DeviceChanged(
                                presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                device = if (isTransport) identity else probedIdentity,
                                partitions = partitions,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                            ),
                        )
                        tableResult.onSuccess {
                            if (partitions.isEmpty()) {
                                onIntent(UiIntent.Status("No partitions found on this device"))
                            } else {
                                listDirectory(this@MainViewModel.state.currentPath)
                            }
                        }.onFailure { err ->
                            val prefix = if (isTransport) "Cannot read USB device" else "Cannot read partition table"
                            onIntent(UiIntent.Status("$prefix: ${err.message ?: err.javaClass.simpleName}"))
                        }
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
                when (val outcome = handle.await()) {
                    is usb.service.OperationState.Failed -> {
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
                                val errorMsg = "Cannot open USB device: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}"
                                onIntent(UiIntent.Status(errorMsg))
                            }
                        }
                    }
                    is usb.service.OperationState.Cancelled -> {
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
                                onIntent(UiIntent.Status("USB probe cancelled"))
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
        val op = object : DiskOperation(name = "Refresh partitions") {
            override suspend fun run() {
                closeFilesVolume()
                val disk = UsbDiskSession.open(mgr, dev)
                try {
                    val capacity = disk.geometry.capacityBytes
                    val tableResult = runCatching { PartitionTable.read(disk) }
                    val failure = tableResult.exceptionOrNull()
                    val isTransport = failure is DiskException || failure?.cause is DiskException
                    val partitions = tableResult.getOrNull()?.partitions?.mapIndexed { idx, p ->
                        val isEfi = p.name.contains("VTOYEFI", ignoreCase = true) ||
                            p.type.contains("EFI", ignoreCase = true)
                        PartitionTab(
                            id = (idx + 1).toString(),
                            name = p.name.ifBlank { "Partition ${idx + 1}" },
                            readOnly = isEfi,
                            isVtoyEfi = isEfi,
                        )
                    } ?: emptyList()
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration != gen || attachedDevice?.deviceId != dev.deviceId) {
                            return@withContext
                        }
                        val identity = DeviceIdentity(
                            label = dev.productName ?: dev.deviceName ?: "USB Storage",
                            capacityBytes = capacity,
                        )
                        onIntent(
                            UiIntent.DeviceChanged(
                                presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                device = identity,
                                partitions = partitions,
                                files = emptyList(),
                                tableValid = !isTransport && tableResult.isSuccess,
                            ),
                        )
                        tableResult.onFailure { err ->
                            val prefix = if (isTransport) "Cannot read USB device" else "Cannot read partition table"
                            onIntent(UiIntent.Status("$prefix: ${err.message ?: err.javaClass.simpleName}"))
                        }
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
                if (outcome is usb.service.OperationState.Failed) {
                    withContext(Dispatchers.Main.immediate) {
                        if (attachGeneration == gen && attachedDevice?.deviceId == dev.deviceId) {
                            val isTransport = outcome.cause is DiskException
                            onIntent(
                                UiIntent.DeviceChanged(
                                    presence = if (isTransport) DevicePresence.Disconnected else DevicePresence.Ready,
                                    device = state.device ?: DeviceIdentity(
                                        label = dev.productName ?: dev.deviceName ?: "USB Storage",
                                        capacityBytes = 0uL,
                                    ),
                                    partitions = emptyList(),
                                    files = emptyList(),
                                    tableValid = false,
                                ),
                            )
                            val prefix = if (isTransport) "Cannot read USB device" else "Cannot read partition table"
                            onIntent(UiIntent.Status("$prefix: ${outcome.cause.message ?: outcome.cause.javaClass.simpleName}"))
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
        activeHandles.forEach { it.cancel() }
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
    private fun closeFilesVolume() {
        synchronized(filesLock) {
            filesVolume?.close()
            filesVolume = null
        }
    }

    /**
     * Mounted volume of [partitionId], opened on first use. Must be called from inside
     * a files operation (holding [filesLock]).
     */
    private fun volumeForLocked(partitionId: String): FatVolume {
        val current = filesVolume
        if (current != null && current.partitionId == partitionId) return current.volume
        current?.close()
        filesVolume = null

        val device = attachedDevice ?: throw DiskException.Disconnected()
        val manager = usbManager ?: throw DiskException.Disconnected()
        val disk = UsbDiskSession.open(manager, device)
        val volume = try {
            val index = partitionId.toIntOrNull()
                ?: throw IllegalArgumentException("invalid partition id $partitionId")
            val table = PartitionTable.read(disk)
            val partition = table.partitions.getOrNull(index - 1)
                ?: throw IllegalArgumentException("partition $partitionId no longer exists")
            PartitionVolumeSession.open(disk, partition.startLba, partition.sectorCount)
        } catch (failure: Throwable) {
            // PartitionVolumeSession already closes on mount failure; close() is idempotent.
            runCatching { disk.close() }
            throw failure
        }
        filesVolume = MountedFilesVolume(partitionId, disk, volume)
        return volume
    }

    /** Runs [action] with the partition selected at submit time, or reports why not. */
    private inline fun withPartitionId(action: (String) -> Unit) {
        val partitionId = state.selectedPartitionId
        if (partitionId == null) {
            onIntent(UiIntent.Status("Select a partition first"))
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

    /** Re-list the displayed directory from the real volume. */
    fun listDirectory(path: String, status: String? = null) = withPartitionId { partitionId ->
        submitFilesOperation(
            label = "List $path",
            failurePath = path,
            onSuccess = { status?.let { onIntent(UiIntent.Status(it)) } },
        ) {
            val entries = volumeForLocked(partitionId).list(path)
            post(UiIntent.FilesListed(path, entries.map { it.toFileEntry() }))
        }
    }

    private fun createFolder(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            submitFilesOperation(
                label = "New folder $name",
                onSuccess = { listDirectory(path, status = "Created folder $target") },
            ) {
                val volume = volumeForLocked(partitionId)
                volume.mkdir(target)
                volume.sync()
            }
        }
    }

    private fun deleteEntry(path: String, name: String) {
        val target = fatChildPath(path, name)
        withWritablePartitionId { partitionId ->
            submitFilesOperation(
                label = "Delete $name",
                onSuccess = { listDirectory(path, status = "Deleted $target") },
            ) {
                val volume = volumeForLocked(partitionId)
                volume.delete(target)
                volume.sync()
            }
        }
    }

    /** Stream a SAF document into [path] in fixed-size chunks, reporting real bytes. */
    private fun uploadFile(path: String, uri: String) {
        val resolver = getApplication<Application>().contentResolver
        var uploaded: String? = null
        withWritablePartitionId { partitionId ->
            submitFilesOperation(
                label = "Upload",
                onSuccess = { listDirectory(path, status = "Uploaded $uploaded to $path") },
            ) { operation ->
                val document = Uri.parse(uri)
                val (reportedName, reportedSize) = contentInfo(resolver, document)
                val rawName = reportedName ?: document.lastPathSegment ?: uri
                val name = rawName.substringAfterLast('/').trim()
                require(name.isNotEmpty() && name != "." && name != "..") {
                    "invalid file name \"$rawName\" for $uri"
                }
                uploaded = name
                val target = fatChildPath(path, name)
                operation.bytesTotal = reportedSize ?: -1L

                val volume = volumeForLocked(partitionId)
                // create = true maps to FA_CREATE_ALWAYS, so an existing same-named file
                // would be truncated before the new copy is complete. Refuse instead.
                if (fatEntryExists(volume, target)) {
                    throw IllegalStateException(
                        "$target already exists: delete it first (delete asks for confirmation)",
                    )
                }
                val input = resolver.openInputStream(document)
                    ?: throw IllegalStateException("cannot open $uri for reading")
                input.use { stream ->
                    val buffer = ByteArray(COPY_CHUNK_BYTES)
                    var offset = 0L
                    var createAttempted = false
                    try {
                        while (true) {
                            if (!operation.isActive()) {
                                throw kotlinx.coroutines.CancellationException("Upload cancelled")
                            }
                            val read = stream.read(buffer)
                            if (read <= 0) break
                            val chunk = if (read == buffer.size) buffer else buffer.copyOf(read)
                            val isFirstWrite = !createAttempted
                            if (isFirstWrite) {
                                createAttempted = true
                            }
                            volume.write(target, chunk, offset = offset, create = isFirstWrite)
                            offset += read
                            operation.report(offset)
                        }
                        if (!createAttempted) {
                            createAttempted = true
                            volume.write(target, ByteArray(0), offset = 0L, create = true)
                        }
                        volume.sync()
                    } catch (t: Throwable) {
                        if (createAttempted) {
                            try {
                                volume.delete(target)
                                volume.sync()
                            } catch (cleanupError: Throwable) {
                                val isNoFile = cleanupError is FatFsException &&
                                    cleanupError.message.orEmpty().endsWith("delete failed: $FR_NO_FILE")
                                if (!isNoFile) {
                                    t.addSuppressed(cleanupError)
                                    post(
                                        UiIntent.Status(
                                            "Failed to clean up incomplete upload $name: ${cleanupError.message ?: cleanupError.javaClass.simpleName}",
                                        ),
                                    )
                                }
                            }
                        }
                        throw t
                    }
                }
            }
        }
    }

    /**
     * Whether [path] exists on [volume]. FatFs reports a missing entry as FR_NO_FILE (4)
     * through the JNI bridge; any other stat failure is re-thrown, because treating an
     * unreadable directory as "absent" would let a write truncate a file unseen.
     */
    private fun fatEntryExists(volume: FatVolume, path: String): Boolean =
        try {
            volume.stat(path)
            true
        } catch (failure: FatFsException) {
            if (failure.message.orEmpty().endsWith("stat failed: $FR_NO_FILE")) {
                false
            } else {
                throw IllegalStateException(
                    "cannot check whether $path exists: ${failure.message ?: "stat failed"}",
                    failure,
                )
            }
        }

    /** Copy a volume file into a SAF document chosen by the activity. */
    private fun downloadFile(path: String, name: String, uri: String) {
        val resolver = getApplication<Application>().contentResolver
        withPartitionId { partitionId ->
            submitFilesOperation(
                label = "Download $name",
                onSuccess = { onIntent(UiIntent.Status("Saved $name")) },
            ) { operation ->
                val target = fatChildPath(path, name)
                val volume = volumeForLocked(partitionId)
                val size = volume.stat(target).size
                operation.bytesTotal = size

                val destination = Uri.parse(uri)
                val output = resolver.openOutputStream(destination, "wt")
                    ?: throw IllegalStateException("cannot open $uri for writing")
                output.use { stream ->
                    var offset = 0L
                    try {
                        while (offset < size) {
                            if (!operation.isActive()) {
                                throw kotlinx.coroutines.CancellationException("Download cancelled")
                            }
                            val want = minOf(COPY_CHUNK_BYTES.toLong(), size - offset).toInt()
                            val data = volume.read(target, offset, want)
                            if (data.isEmpty()) {
                                throw IllegalStateException("short read at $offset of $size bytes")
                            }
                            stream.write(data)
                            offset += data.size
                            operation.report(offset)
                        }
                        stream.flush()
                    } catch (t: Throwable) {
                        operation.state = usb.service.OperationState.Partial
                        val detail = if (offset > 0L) {
                            "$offset of $size bytes written"
                        } else {
                            "0 bytes written, document truncated"
                        }
                        val baseMsg = t.message ?: t.javaClass.simpleName
                        val warning = if (t is kotlinx.coroutines.CancellationException) {
                            "Download cancelled: destination document was truncated ($detail)"
                        } else {
                            "Download failed ($baseMsg): destination document was truncated ($detail)"
                        }
                        operation.failureCause = DiskException.IoFailure(warning, t)
                        throw t
                    }
                }
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
     * the volume side of a mutation are serialized by [filesLock]; [onSuccess] re-lists
     * the affected directory after a mutation so the UI never shows invented entries.
     */
    private fun submitFilesOperation(
        label: String,
        bytesTotal: Long = -1L,
        failurePath: String? = null,
        onSuccess: (() -> Unit)? = null,
        body: (DiskOperation) -> Unit,
    ) {
        val operation = object : DiskOperation(name = label, bytesTotal = bytesTotal) {
            override suspend fun run() {
                synchronized(filesLock) {
                    body(this)
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(
                    state = OperationState.Running,
                    label = label,
                    bytesTotal = bytesTotal,
                ),
            ),
        )
        val handle = DiskController.get().submit(operation)
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
                                label = label,
                                bytesDone = operation.bytesDone,
                                bytesTotal = operation.bytesTotal,
                            ),
                        ),
                    )
                }
            }
            try {
                when (val outcome = handle.await()) {
                    is usb.service.OperationState.Failed -> {
                        val message =
                            outcome.cause.message
                                ?: outcome.cause.javaClass.simpleName
                        if (failurePath != null) {
                            onIntent(UiIntent.FilesListFailed(failurePath, message))
                        } else {
                            onIntent(UiIntent.Status(message))
                        }
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Failed,
                                    label = label,
                                    errorMessage = message,
                                ),
                            ),
                        )
                        // The USB claim may be gone; reopen for the next attempt.
                        scope.launch(Dispatchers.IO) { closeFilesVolume() }
                    }
                    is usb.service.OperationState.Cancelled -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(state = OperationState.Cancelled, label = label),
                            ),
                        )
                    }
                    is usb.service.OperationState.Partial -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Partial,
                                    label = label,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    errorMessage = operation.failureCause?.message
                                        ?: operation.failureCause?.javaClass?.simpleName
                                        ?: "Incomplete operation",
                                ),
                            ),
                        )
                    }
                    else -> {
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Completed,
                                    label = label,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                ),
                            ),
                        )
                        onSuccess?.invoke()
                    }
                }
            } finally {
                ticker.cancel()
                if (activeHandle === handle) activeHandle = null
                activeHandles.remove(handle)
            }
        }
    }

    /**
     * Read-only disk work. The mounted FAT volume is closed first because it holds the
     * USB claim; [body] then runs on the shared disk thread and returns the summary shown
     * to the user. Nothing here writes to the stick.
     */
    private fun submitReadOnlyDiskOperation(
        label: String,
        cancelable: Boolean = true,
        body: (UsbDisk, DiskOperation) -> String,
    ) {
        val operation = object : DiskOperation(name = label) {
            @Volatile var result: String? = null

            override suspend fun run() {
                closeFilesVolume()
                val device = attachedDevice ?: throw DiskException.Disconnected()
                val manager = usbManager ?: throw DiskException.Disconnected()
                val disk = UsbDiskSession.open(manager, device)
                try {
                    result = body(disk, this)
                } finally {
                    disk.close()
                }
            }
        }
        onIntent(
            UiIntent.OperationUpdated(
                OperationProgress(state = OperationState.Running, label = label, cancelable = cancelable),
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
                                label = label,
                                bytesDone = operation.bytesDone,
                                bytesTotal = operation.bytesTotal,
                                cancelable = cancelable,
                            ),
                        ),
                    )
                }
            }
            try {
                when (val outcome = handle.await()) {
                    is usb.service.OperationState.Failed -> {
                        val message =
                            outcome.cause.message ?: outcome.cause.javaClass.simpleName
                        onIntent(UiIntent.Status(message))
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Failed,
                                    label = label,
                                    errorMessage = message,
                                    cancelable = cancelable,
                                ),
                            ),
                        )
                        // The USB claim may be gone; reopen for the next attempt.
                        scope.launch(Dispatchers.IO) { closeFilesVolume() }
                    }
                    is usb.service.OperationState.Cancelled ->
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(state = OperationState.Cancelled, label = label, cancelable = cancelable),
                            ),
                        )
                    is usb.service.OperationState.Partial ->
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Partial,
                                    label = label,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    resultMessage = operation.result,
                                    errorMessage = operation.failureCause?.message ?: operation.failureCause?.javaClass?.simpleName,
                                    cancelable = cancelable,
                                ),
                            ),
                        )
                    else ->
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = OperationState.Completed,
                                    label = label,
                                    bytesDone = operation.bytesDone,
                                    bytesTotal = operation.bytesTotal,
                                    resultMessage = operation.result,
                                    cancelable = cancelable,
                                ),
                            ),
                        )
                }
            } finally {
                ticker.cancel()
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

    /** Disk thread -> main thread hop for result intents. */
    private fun post(intent: UiIntent) {
        scope.launch { onIntent(intent) }
    }

    private fun FatDirEntry.toFileEntry(): FileEntry =
        FileEntry(
            name = name,
            isDirectory = isDirectory,
            sizeBytes = size.coerceAtLeast(0L).toULong(),
        )

    private companion object {
        const val COPY_CHUNK_BYTES = 64 * 1024
        const val PROGRESS_TICK_MS = 120L

        /** FatFs R0.16 FR_NO_FILE: fat_engine_stat() maps a missing path to this code. */
        const val FR_NO_FILE = 4
    }
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
        viewModel.onIntent(UiIntent.SetImage(uri.toString()))
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

    /** Entry name waiting for the SAF destination chosen in [downloadLauncher]. */
    private var pendingDownloadName: String? = null

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
                        val hasPerm = usbManager.hasPermission(device)
                        viewModel.onUsbDeviceAttached(device, hasPerm, usbManager)
                        if (!hasPerm) {
                            requestUsbPermission(usbManager, device)
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    viewModel.onUsbDeviceDetached(device)
                }
                ACTION_USB_PERMISSION -> {
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    if (device != null && MassStorageDiscovery.findBotInterface(device) != null) {
                        viewModel.onUsbDeviceAttached(device, granted, usbManager)
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
        requestPermissionsIfNeeded()
        setContent {
            AppShell(
                state = viewModel.state,
                onIntent = { intent ->
                    viewModel.onIntent(intent)
                },
                onPickImage = {
                    imagePickerLauncher.launch(
                        arrayOf(
                            "application/octet-stream",
                            "application/x-iso9660-image",
                            "application/gzip",
                            "application/x-xz",
                        ),
                    )
                },
                onRequestDownload = { name ->
                    pendingDownloadName = name
                    downloadLauncher.launch(name)
                },
                onPickBackup = {
                    gptBackupPickerLauncher.launch(arrayOf("*/*"))
                },
            )
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_PENDING_DOWNLOAD, pendingDownloadName)
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
            val hasPerm = usbManager.hasPermission(device)
            viewModel.onUsbDeviceAttached(device, hasPerm, usbManager)
            if (!hasPerm) {
                requestUsbPermission(usbManager, device)
            }
        } else {
            viewModel.onUsbDeviceDetached()
            viewModel.onIntent(UiIntent.DeviceChanged(DevicePresence.Absent))
        }
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
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
    }
}
