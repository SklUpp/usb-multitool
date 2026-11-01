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
 * Foreground `connectedDevice` owner for serialized USB disk work.
 *
 * [onStartCommand] promotes to foreground synchronously. The bound
 * [ServiceControl.requestStart] only refreshes the notification — it never
 * calls [Context.startForegroundService], which would recurse from a service
 * callback. External starters use [ContextServiceControl] / [start].
 */
class DiskService : Service() {
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "usb.multitool:disk").apply {
            setReferenceCounted(false)
        }
        val lock = object : OperationWakeLock {
            override fun acquire() {
                wakeLock?.acquire(60 * 60 * 1000L)
            }

            override fun release() {
                wakeLock?.let { if (it.isHeld) it.release() }
            }
        }
        // In-service control: never call startForegroundService from here.
        DiskController.get().bind(
            wakeLock = lock,
            serviceControl = object : ServiceControl {
                override fun requestStart() {
                    startForegroundInternal()
                }

                override fun stopWhenIdle() {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            },
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        // Idle teardown must leave DiskController / OperationExecutor alive.
        // Rebind external starter so later submits can startForegroundService again.
        DiskController.get().bind(
            wakeLock = NoOpWakeLock,
            serviceControl = ContextServiceControl(applicationContext),
        )
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
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

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = getSystemService(NotificationManager::class.java)
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

        /** External entry — safe to call from Activity/Application, not from Service callbacks. */
        fun start(context: Context) {
            val intent = Intent(context, DiskService::class.java)
            if (Build.VERSION.SDK_INT >= 26) {
                context.startForegroundService(intent)
            } else {
                @Suppress("DEPRECATION")
                context.startService(intent)
            }
        }
    }
}

/** Application-context [ServiceControl] that starts [DiskService] without recursion. */
class ContextServiceControl(
    context: Context,
) : ServiceControl {
    private val appContext = context.applicationContext

    override fun requestStart() {
        DiskService.start(appContext)
    }

    override fun stopWhenIdle() {
        appContext.stopService(Intent(appContext, DiskService::class.java))
    }
}
