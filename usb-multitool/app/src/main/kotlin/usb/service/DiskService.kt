package usb.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager

/**
 * Foreground connectedDevice owner for serialized USB disk work.
 * Starts on first queued operation; stops when the executor goes idle.
 * Idle stop does not cancel [DiskController] — later submits still run.
 *
 * [ServiceControl.startForeground] only requests service start once.
 * [onStartCommand] promotes the notification synchronously via [Service.startForeground].
 */
class DiskService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel(this)
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usb.multitool:disk").apply {
            setReferenceCounted(false)
        }
        activeWakeLock = wakeLock
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val controller = DiskController.get()
        if (intent?.action == ACTION_STOP) {
            synchronized(Companion) {
                if (controller.executor.hasOutstandingWork()) {
                    return START_NOT_STICKY
                }
                stopForeground(STOP_FOREGROUND_REMOVE)
                if (controller.executor.hasOutstandingWork()) {
                    startForegroundInternal()
                    isForeground = true
                    return START_NOT_STICKY
                }
                isForeground = false
                stopSelfResult(startId)
            }
            return START_NOT_STICKY
        }
        // Synchronous promotion — never call startForegroundService from here.
        startForegroundInternal()
        synchronized(Companion) {
            isForeground = true
            serviceReady.complete(Unit)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        wakeLock?.let { if (it.isHeld) it.release() }
        synchronized(Companion) {
            if (activeWakeLock === wakeLock) {
                activeWakeLock = null
            }
            isForeground = false
            if (serviceReady.isCompleted) {
                serviceReady = kotlinx.coroutines.CompletableDeferred()
            }
        }
        super.onDestroy()
    }


    private fun startForegroundInternal() {
        val notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("USB Multitool")
            .setContentText("Disk operation in progress")
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .build()
        if (Build.VERSION.SDK_INT >= 34) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Disk operations",
            NotificationManager.IMPORTANCE_LOW,
        )
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL_ID = "usb.disk"
        private const val NOTIFICATION_ID = 42
        const val ACTION_STOP = "usb.service.DiskService.STOP"

        @Volatile
        private var serviceReady = kotlinx.coroutines.CompletableDeferred<Unit>()
        @Volatile
        private var activeWakeLock: PowerManager.WakeLock? = null

        fun initialize(context: Context) {
            val app = context.applicationContext
            ensureChannelStatic(app)
            setupServiceControl(app)
        }

        private fun setupServiceControl(context: Context) {
            val controller = DiskController.get()
            val lock = object : OperationWakeLock {
                override fun acquire() {
                    val wl = activeWakeLock
                        ?: throw IllegalStateException("Wake lock is not bound by active DiskService")
                    wl.acquire()
                }

                override fun release() {
                    activeWakeLock?.let { if (it.isHeld) it.release() }
                }
            }
            val policy = DiskServiceLifecycle(
                requestStart = { requestStart(context) },
                stopService = {
                    synchronized(Companion) {
                        if (!isForeground && activeWakeLock == null) return@synchronized
                        val intent = Intent(context, DiskService::class.java).apply {
                            action = ACTION_STOP
                        }
                        try {
                            context.startService(intent)
                        } catch (_: Exception) {}
                    }
                },
                hasOutstandingWork = { controller.executor.hasOutstandingWork() },
                isServiceForeground = { isForeground && activeWakeLock != null },
            )
            controller.bind(wakeLock = lock, serviceControl = policy)
        }
        @Volatile
        private var isForeground = false

        fun requestStart(context: Context) {
            val ready = synchronized(Companion) {
                if (!serviceReady.isCompleted) {
                    serviceReady
                } else {
                    serviceReady = kotlinx.coroutines.CompletableDeferred()
                    serviceReady
                }
            }
            val intent = Intent(context, DiskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
            kotlinx.coroutines.runBlocking {
                kotlinx.coroutines.withTimeout(5_000L) {
                    ready.await()
                }
            }
        }

        private fun ensureChannelStatic(context: Context) {
            if (Build.VERSION.SDK_INT < 26) return
            val manager = context.getSystemService(NotificationManager::class.java) ?: return
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Disk operations",
                NotificationManager.IMPORTANCE_LOW,
            )
            manager.createNotificationChannel(channel)
        }
    }
}
