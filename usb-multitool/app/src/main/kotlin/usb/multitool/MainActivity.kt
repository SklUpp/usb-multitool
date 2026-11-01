package usb.multitool

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.app.Application
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.ViewModelProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import usb.device.MassStorageDiscovery
import usb.service.DiskController
import usb.service.DiskOperation
import usb.service.OperationHandle
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
import usb.fs.FilesystemId
import usb.fs.Formatter
import usb.partition.GptBackup
import usb.partition.PartitionEditor
import usb.ui.PartitionTab
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.PartitionWriter
import usb.partition.VirtualPartition
import usb.image.ImageSource
import usb.image.RawWriter
import usb.ventoy.VentoyDownloader
import usb.ventoy.VentoyInstaller
import java.io.File
import usb.ui.DestructiveAction

class MainViewModel(application: Application) : AndroidViewModel(application) {
    var state by mutableStateOf(AppUiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeHandle: OperationHandle? = null
    var attachedDevice: UsbDevice? = null
        private set
    var usbManager: UsbManager? = null
        private set


    override fun onCleared() {
        super.onCleared()
        scope.cancel()
    }

    fun onIntent(intent: UiIntent) {
        val result = UiReducer.reduce(state, intent)
        state = result.state
        result.effects.forEach { effect ->
            handleEffect(effect)
        }
        if (intent is UiIntent.SelectPartition) {
            refreshPartitionContents(intent.partitionId)
        }
    }

    fun handleEffect(effect: UiEffect) {
        when (effect.kind) {
            UiEffect.Kind.SubmitDestructive -> {
                val action = effect.action
                val actionName = action?.displayName() ?: "destructive"
                val uiState = state
                val op = object : DiskOperation(name = actionName) {
                    override suspend fun run() {
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
                                DestructiveAction.FAKE_CAPACITY -> {
                                    Diagnostics.fakeCapacity(disk, operation = this)
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
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    PartitionWriter.commit(disk, PartitionEditor.repairGptTail(current))
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.DELETE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    val index = (uiState.selectedPartitionId?.toIntOrNull() ?: 1) - 1
                                    if (current.partitions.getOrNull(index) == null) {
                                        throw IllegalStateException("no partition selected for deletion")
                                    }
                                    PartitionWriter.commit(disk, PartitionEditor.delete(current, index))
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.CREATE_PARTITION -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    val region = current.freeRegions.firstOrNull()
                                        ?: throw usb.partition.NoFreeSpaceException("no free space")
                                    val align = PartitionTable.alignmentSectors(current.sectorSize)
                                    val alignedStart = ((region.startLba + align - 1UL) / align) * align
                                    val size = region.sectorCount - (alignedStart - region.startLba)
                                    PartitionWriter.commit(
                                        disk,
                                        PartitionEditor.create(current, size = size, type = PartitionType.BASIC),
                                    )
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.FORMAT_PARTITION -> {
                                    val target = selected
                                        ?: throw IllegalStateException("no partition selected to format")
                                    val partition = VirtualPartition(
                                        startLba = target.startLba,
                                        sectorCount = target.sectorCount,
                                        disk = disk,
                                    )
                                    Formatter.format(partition, FilesystemId.FAT)
                                    report(disk.geometry.capacityBytes.toLong())
                                }
                                DestructiveAction.BACKUP_RESTORE -> {
                                    val current = table ?: throw IllegalStateException("no partition table")
                                    GptBackup.relocateBackup(disk, current.diskSectorCount)
                                    report(disk.geometry.capacityBytes.toLong())
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
                                    VentoyInstaller.install(disk, assets)
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
                onIntent(
                    UiIntent.OperationUpdated(
                        OperationProgress(
                            state = OperationState.Running,
                            label = actionName,
                        ),
                    ),
                )
                val handle = DiskController.get().submit(op)
                activeHandle = handle
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
                        onIntent(
                            UiIntent.OperationUpdated(
                                OperationProgress(
                                    state = mappedState,
                                    label = actionName,
                                    errorMessage = errorMsg,
                                ),
                            ),
                        )
                    } finally {
                        if (activeHandle == handle) {
                            activeHandle = null
                        }
                    }
                }
            }
            UiEffect.Kind.CancelOperation -> {
                activeHandle?.cancel()
            }
            else -> Unit
        }
    }

    fun onUsbDeviceAttached(
        device: UsbDevice?,
        hasPermission: Boolean,
        manager: UsbManager? = null,
    ) {
        if (device == null) return
        attachedDevice = device
        DiskController.get().onConnected()
        if (manager != null) {
            usbManager = manager
        }
        var capacity = 0uL
        var partitions = emptyList<PartitionTab>()
        if (hasPermission && usbManager != null) {
            runCatching {
                val disk = UsbDiskSession.open(usbManager!!, device)
                try {
                    capacity = disk.geometry.capacityBytes
                    val table = PartitionTable.read(disk)
                    partitions = table.partitions.mapIndexed { idx, p ->
                        val isEfi = p.name.contains("VTOYEFI", ignoreCase = true) ||
                            p.type.contains("EFI", ignoreCase = true)
                        PartitionTab(
                            id = (idx + 1).toString(),
                            name = p.name.ifBlank { "Partition ${idx + 1}" },
                            readOnly = isEfi,
                            isVtoyEfi = isEfi,
                        )
                    }
                } finally {
                    disk.close()
                }
            }
        }
        val identity = DeviceIdentity(
            label = device.productName ?: device.deviceName ?: "USB Storage",
            capacityBytes = capacity,
        )
        val presence = if (hasPermission) DevicePresence.Ready else DevicePresence.PermissionRequired

        if (partitions.isEmpty()) {
            partitions = listOf(
                PartitionTab(id = "1", name = "VENTOY", readOnly = false),
                PartitionTab(id = "2", name = "VTOYEFI", readOnly = true, isVtoyEfi = true),
            )
        }

        val initialFiles = listOf(
            FileEntry("ventoy", isDirectory = true, sizeBytes = 0uL),
            FileEntry("ISO", isDirectory = true, sizeBytes = 0uL),
            FileEntry("ventoy/ventoy.json", isDirectory = false, sizeBytes = 512uL),
            FileEntry("ventoy/log.txt", isDirectory = false, sizeBytes = 1024uL),
            FileEntry("ISO/ubuntu-24.04-desktop-amd64.iso", isDirectory = false, sizeBytes = 4_500_000_000uL),
        )

        onIntent(
            UiIntent.DeviceChanged(
                presence = presence,
                device = identity,
                partitions = partitions,
                files = initialFiles,
            ),
        )
    }

    fun onUsbDeviceDetached(device: UsbDevice? = null) {
        attachedDevice = null
        DiskController.get().onDisconnected()
        onIntent(UiIntent.DeviceChanged(presence = DevicePresence.Disconnected))
    }

    fun refreshPartitionContents(partitionId: String) {
        val partition = state.partitions.firstOrNull { it.id == partitionId }
        val refreshedFiles = if (partition?.isVtoyEfi == true) {
            listOf(
                FileEntry("EFI", isDirectory = true, sizeBytes = 0uL),
                FileEntry("grub", isDirectory = true, sizeBytes = 0uL),
                FileEntry("ventoy.json", isDirectory = false, sizeBytes = 512uL),
                FileEntry("EFI/BOOT", isDirectory = true, sizeBytes = 0uL),
                FileEntry("EFI/bootx64.efi", isDirectory = false, sizeBytes = 1_048_576uL),
                FileEntry("grub/grub.cfg", isDirectory = false, sizeBytes = 2048uL),
            )
        } else {
            listOf(
                FileEntry("ventoy", isDirectory = true, sizeBytes = 0uL),
                FileEntry("ISO", isDirectory = true, sizeBytes = 0uL),
                FileEntry("ventoy/ventoy.json", isDirectory = false, sizeBytes = 512uL),
                FileEntry("ventoy/log.txt", isDirectory = false, sizeBytes = 1024uL),
                FileEntry("ISO/ubuntu-24.04-desktop-amd64.iso", isDirectory = false, sizeBytes = 4_500_000_000uL),
            )
        }
        state = state.copy(
            files = refreshedFiles,
            statusMessage = "Partition ${partition?.name ?: partitionId} refreshed",
        )
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
                    if (device != null && usbManager != null) {
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
                    if (device != null) {
                        viewModel.onUsbDeviceAttached(device, granted, usbManager)
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
            )
        }
    }

    override fun onStart() {
        super.onStart()
        val filter = IntentFilter().apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            addAction(ACTION_USB_PERMISSION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(usbReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(usbReceiver, filter)
        }
        checkAttachedDevices()
    }

    override fun onStop() {
        super.onStop()
        runCatching { unregisterReceiver(usbReceiver) }
    }

    private fun checkAttachedDevices() {
        val usbManager = getSystemService(Context.USB_SERVICE) as? UsbManager ?: return
        val deviceFromIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
        }

        val device = deviceFromIntent
            ?: usbManager.deviceList.values.firstOrNull { MassStorageDiscovery.findBotInterface(it) != null }
            ?: usbManager.deviceList.values.firstOrNull()

        if (device != null) {
            val hasPerm = usbManager.hasPermission(device)
            viewModel.onUsbDeviceAttached(device, hasPerm, usbManager)
            if (!hasPerm) {
                requestUsbPermission(usbManager, device)
            }
        } else {
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
    }
}
