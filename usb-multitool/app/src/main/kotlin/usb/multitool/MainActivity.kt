package usb.multitool

import android.Manifest
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
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
import androidx.lifecycle.ViewModel
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
import usb.partition.PartitionTab
import usb.partition.PartitionTable
import usb.partition.PartitionType
import usb.partition.VirtualPartition
import usb.ui.DestructiveAction

class MainViewModel : ViewModel() {
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
                val op = object : DiskOperation(name = actionName) {
                    override suspend fun run() {
                        val dev = attachedDevice
                        val mgr = usbManager
                        if (dev != null && mgr != null) {
                            val disk = UsbDiskSession.open(mgr, dev)
                            try {
                                val devIdentity = state.device?.identity ?: disk.geometry.capacityBytes.toString()
                                val diagConfirm = usb.diagnostics.DestructiveConfirmation(
                                    identity = devIdentity,
                                    capacityBytes = state.device?.capacityBytes ?: disk.geometry.capacityBytes,
                                )
                                when (action) {
                                    DestructiveAction.WIPE -> {
                                        Diagnostics.wipe(
                                            disk = disk,
                                            identity = devIdentity,
                                            confirmation = diagConfirm,
                                            operation = this,
                                        )
                                    }
                                    DestructiveAction.FAKE_CAPACITY -> {
                                        Diagnostics.fakeCapacity(disk, operation = this)
                                    }
                                    DestructiveAction.WRITE_BENCHMARK -> {
                                        val sectors = 2048UL.coerceAtMost(
                                            if (disk.geometry.sectorCount > 2048UL) disk.geometry.sectorCount - 2048UL else 1UL,
                                        )
                                        Diagnostics.writeBenchmark(
                                            disk = disk,
                                            identity = devIdentity,
                                            confirmation = diagConfirm,
                                            startLba = 2048UL,
                                            sectors = sectors,
                                            operation = this,
                                        )
                                    }
                                    DestructiveAction.GPT_REPAIR -> {
                                        val table = PartitionTable.read(disk)
                                        PartitionEditor.repairGptTail(table)
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.DELETE_PARTITION -> {
                                        val table = PartitionTable.read(disk)
                                        if (table.partitions.isNotEmpty()) {
                                            PartitionEditor.delete(table, table.partitions.size - 1)
                                        }
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.CREATE_PARTITION -> {
                                        val table = PartitionTable.read(disk)
                                        if (table.freeRegions.isNotEmpty()) {
                                            val free = table.freeRegions.first()
                                            PartitionEditor.create(
                                                table,
                                                size = free.sectorCount.coerceAtMost(2048UL),
                                                type = PartitionType.LINUX_DATA,
                                            )
                                        }
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.FORMAT_PARTITION -> {
                                        val partition = VirtualPartition(
                                            disk,
                                            startLba = 2048UL,
                                            sectorCount = 2048UL.coerceAtMost(disk.geometry.sectorCount),
                                        )
                                        Formatter.format(partition, FilesystemId.FAT)
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.BACKUP_RESTORE -> {
                                        val table = PartitionTable.read(disk)
                                        GptBackup.relocateBackup(disk, table.diskSectorCount)
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.VENTOY_INSTALL -> {
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    DestructiveAction.RAW_WRITE -> {
                                        report(disk.geometry.capacityBytes.toLong())
                                    }
                                    null -> Unit
                                }
                            } finally {
                                disk.close()
                            }
                        } else {
                            report(1024L)
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
        if (manager != null) {
            usbManager = manager
        }
        val identity = DeviceIdentity(
            label = device.productName ?: device.deviceName ?: "USB Storage",
            capacityBytes = 0uL,
        )
        val presence = if (hasPermission) DevicePresence.Ready else DevicePresence.PermissionRequired

        var partitions = emptyList<PartitionTab>()
        if (hasPermission && usbManager != null) {
            partitions = runCatching {
                val disk = UsbDiskSession.open(usbManager!!, device)
                try {
                    val table = PartitionTable.read(disk)
                    table.partitions.mapIndexed { idx, p ->
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
            }.getOrDefault(emptyList())
        }

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
