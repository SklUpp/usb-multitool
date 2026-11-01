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

class MainViewModel : ViewModel() {
    var state by mutableStateOf(AppUiState())
        private set

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var activeHandle: OperationHandle? = null

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
                        // Execution routed via DiskController executor
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
                }
            }
            UiEffect.Kind.CancelOperation -> {
                activeHandle?.cancel()
            }
            else -> Unit
        }
    }

    fun onUsbDeviceAttached(device: UsbDevice?, hasPermission: Boolean) {
        if (device == null) return
        val identity = DeviceIdentity(
            label = device.productName ?: device.deviceName ?: "USB Storage",
            capacityBytes = 0uL,
            vendorId = device.vendorId,
            productId = device.productId,
            serialNumber = runCatching { device.serialNumber ?: "" }.getOrDefault(""),
        )
        val presence = if (hasPermission) DevicePresence.Ready else DevicePresence.PermissionRequired
        onIntent(UiIntent.DeviceChanged(presence = presence, device = identity))
    }

    fun onUsbDeviceDetached(device: UsbDevice? = null) {
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
            )
        } else {
            state.files.ifEmpty {
                listOf(
                    FileEntry("ventoy", isDirectory = true, sizeBytes = 0uL),
                    FileEntry("ISO", isDirectory = true, sizeBytes = 0uL),
                )
            }
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
                        viewModel.onUsbDeviceAttached(device, hasPerm)
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
                        viewModel.onUsbDeviceAttached(device, granted)
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
            viewModel.onUsbDeviceAttached(device, hasPerm)
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
