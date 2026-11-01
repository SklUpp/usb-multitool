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
 */
class DiskService : Service(), ServiceControl {
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
        DiskController.get().bind(wakeLock = lock, serviceControl = this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundInternal()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun startForeground() {
        val intent = Intent(this, DiskService::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            startForegroundService(intent)
        } else {
            @Suppress("DEPRECATION")
            startService(intent)
        }
        startForegroundInternal()
    }

    override fun stopWhenIdle() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        // Idle teardown must leave DiskController / OperationExecutor alive.
        wakeLock?.let { if (it.isHeld) it.release() }
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
    }
}
