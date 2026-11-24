package usb.multitool

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong
import usb.device.MassStorageDiscovery
import usb.device.UsbPermissionPolicy

/**
 * Owns USB attach/detach receiver registration and permission request fencing.
 * [MainActivity] remains the [Listener] and still owns batch-picker clears on detach.
 */
class UsbAttachController(
    private val context: Context,
    private val listener: Listener,
) {
    interface Listener {
        fun onAttached(device: UsbDevice, hasPermission: Boolean, manager: UsbManager)

        fun onDetached(device: UsbDevice?)

        fun onPermissionResult(device: UsbDevice?, granted: Boolean, manager: UsbManager?)

        /** Fired when the selected device key is cleared by detach (before [onDetached]). */
        fun onSelectionCleared() {}
    }

    private var pendingUsbPermissionKey: String? = null
    private var pendingUsbPermissionToken: String? = null
    private var deniedUsbPermissionKey: String? = null
    /** Key that already used its single post-denial retry while still attached. */
    private var deniedUsbPermissionRetriedKey: String? = null
    private var selectedUsbDeviceKey: String? = null
    private var usbReceiverRegistered = false

    private val usbReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager
                val device =
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }

                when (intent.action) {
                    UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                        if (device != null &&
                            usbManager != null &&
                            MassStorageDiscovery.findBotInterface(device) != null
                        ) {
                            selectUsbDevice(device)
                            val hasPerm = usbManager.hasPermission(device)
                            listener.onAttached(device, hasPerm, usbManager)
                            if (!hasPerm) requestUsbPermission(usbManager, device)
                        }
                    }
                    UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                        if (device == null ||
                            selectedUsbDeviceKey ==
                            UsbPermissionPolicy.key(device.deviceId, device.deviceName)
                        ) {
                            forgetSelection()
                            listener.onSelectionCleared()
                        }
                        listener.onDetached(device)
                    }
                    ACTION_USB_PERMISSION -> {
                        val granted =
                            intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                        val callbackKey =
                            device?.let { UsbPermissionPolicy.key(it.deviceId, it.deviceName) }
                        val callbackToken = intent.getStringExtra(EXTRA_USB_PERMISSION_TOKEN)
                        val liveDevice =
                            UsbPermissionPolicy.currentMatch(
                                device,
                                usbManager?.deviceList?.values.orEmpty(),
                            )
                        if (liveDevice != null &&
                            UsbPermissionPolicy.accepts(
                                pendingUsbPermissionKey,
                                callbackKey,
                                selectedUsbDeviceKey,
                                pendingUsbPermissionToken,
                                callbackToken,
                            ) &&
                            MassStorageDiscovery.findBotInterface(liveDevice) != null
                        ) {
                            pendingUsbPermissionKey = null
                            pendingUsbPermissionToken = null
                            if (granted) {
                                deniedUsbPermissionKey = null
                                deniedUsbPermissionRetriedKey = null
                            } else {
                                deniedUsbPermissionKey = callbackKey
                            }
                            listener.onPermissionResult(liveDevice, granted, usbManager)
                        }
                    }
                }
            }
        }

    fun start() {
        if (usbReceiverRegistered) return
        val filter =
            IntentFilter().apply {
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
                addAction(ACTION_USB_PERMISSION)
            }
        // Only system USB attach/detach and our own PendingIntent broadcast arrive here,
        // so the receiver is not exported (ContextCompat keeps the 2-arg call below API 33).
        ContextCompat.registerReceiver(
            context,
            usbReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        usbReceiverRegistered = true
    }

    fun stop() {
        if (!usbReceiverRegistered) return
        runCatching { context.unregisterReceiver(usbReceiver) }
        usbReceiverRegistered = false
    }

    fun probeAttachedDevice(intent: Intent?): Boolean {
        val usbManager = context.getSystemService(Context.USB_SERVICE) as? UsbManager ?: return false
        val deviceFromIntent = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent?.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
        }.getOrNull()
        intent?.removeExtra(UsbManager.EXTRA_DEVICE)

        val currentDevices = usbManager.deviceList
        val validFromIntent =
            deviceFromIntent?.let { dev ->
                currentDevices[dev.deviceName]?.takeIf {
                    MassStorageDiscovery.findBotInterface(it) != null
                }
            }
        val device =
            validFromIntent
                ?: currentDevices.values.firstOrNull {
                    MassStorageDiscovery.findBotInterface(it) != null
                }
        if (device != null) {
            selectUsbDevice(device)
            val hasPerm = usbManager.hasPermission(device)
            listener.onAttached(device, hasPerm, usbManager)
            if (!hasPerm) {
                requestUsbPermission(usbManager, device)
            }
            return true
        }
        forgetSelection()
        return false
    }

    fun forgetSelection() {
        selectedUsbDeviceKey = null
        pendingUsbPermissionKey = null
        pendingUsbPermissionToken = null
        deniedUsbPermissionKey = null
        deniedUsbPermissionRetriedKey = null
    }

    private fun selectUsbDevice(device: UsbDevice, resetPermission: Boolean = false) {
        val key = UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        if (resetPermission || selectedUsbDeviceKey != key) {
            pendingUsbPermissionKey = null
            pendingUsbPermissionToken = null
            deniedUsbPermissionKey = null
            deniedUsbPermissionRetriedKey = null
        }
        selectedUsbDeviceKey = key
    }

    private fun requestUsbPermission(usbManager: UsbManager, device: UsbDevice) {
        val key = UsbPermissionPolicy.key(device.deviceId, device.deviceName)
        val decision =
            UsbPermissionRetryGate.prepareRequest(
                key,
                UsbPermissionRetryGate.State(
                    pendingKey = pendingUsbPermissionKey,
                    deniedKey = deniedUsbPermissionKey,
                    retriedKey = deniedUsbPermissionRetriedKey,
                ),
            )
        deniedUsbPermissionKey = decision.state.deniedKey
        deniedUsbPermissionRetriedKey = decision.state.retriedKey
        if (!decision.shouldRequest) return
        val requestCode = usbPermissionRequestCounter.incrementAndGet().toInt()
        val token = UUID.randomUUID().toString()
        pendingUsbPermissionKey = key
        pendingUsbPermissionToken = token
        val flags =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            } else {
                PendingIntent.FLAG_UPDATE_CURRENT
            }
        val permissionIntent =
            PendingIntent.getBroadcast(
                context,
                requestCode,
                Intent(ACTION_USB_PERMISSION)
                    .setPackage(context.packageName)
                    .putExtra(EXTRA_USB_PERMISSION_TOKEN, token),
                flags,
            )
        usbManager.requestPermission(device, permissionIntent)
    }

    companion object {
        const val ACTION_USB_PERMISSION = "usb.multitool.USB_PERMISSION"
        const val EXTRA_USB_PERMISSION_TOKEN = "usb.multitool.USB_PERMISSION_TOKEN"
        private val usbPermissionRequestCounter = AtomicLong()
    }
}
