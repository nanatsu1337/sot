package com.example.screenofftimer

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat

class TimerService : Service() {

    companion object {
        const val ACTION_START = "com.example.screenofftimer.START"
        const val ACTION_STOP = "com.example.screenofftimer.STOP"
        const val EXTRA_MIN = "minutes"

        const val PREFS = "timer"
        const val KEY_END = "end"
        const val KEY_TOTAL = "total"

        private const val CHANNEL_ID = "timer"
        private const val NOTIF_ID = 1

        fun start(ctx: Context, minutes: Int) {
            val i = Intent(ctx, TimerService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_MIN, minutes)
            ContextCompat.startForegroundService(ctx, i)
        }

        fun stop(ctx: Context) {
            ctx.startService(Intent(ctx, TimerService::class.java).setAction(ACTION_STOP))
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null
    private var endTime = 0L

    private val tick = object : Runnable {
        override fun run() {
            if (System.currentTimeMillis() >= endTime) {
                fire()
            } else {
                handler.postDelayed(this, 1000)
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)

        when (intent?.action) {
            ACTION_STOP -> {
                cancelAll()
                return START_NOT_STICKY
            }
            ACTION_START -> {
                val minutes = intent.getIntExtra(EXTRA_MIN, 1).coerceAtLeast(1)
                endTime = System.currentTimeMillis() + minutes * 60_000L
                prefs.edit().putLong(KEY_END, endTime).putInt(KEY_TOTAL, minutes).apply()
            }
            else -> {
                // service direstart sistem: lanjutkan timer yang tersimpan
                endTime = prefs.getLong(KEY_END, 0L)
                if (endTime <= System.currentTimeMillis()) {
                    prefs.edit().clear().apply()
                    stopSelf()
                    return START_NOT_STICKY
                }
            }
        }

        createChannel()
        ServiceCompat.startForeground(
            this, NOTIF_ID, buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        )
        acquireWakeLock()
        handler.removeCallbacks(tick)
        handler.post(tick)
        return START_STICKY
    }

    private fun fire() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, AdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        }
        cancelAll()
    }

    private fun cancelAll() {
        handler.removeCallbacks(tick)
        releaseWakeLock()
        getSharedPreferences(PREFS, MODE_PRIVATE).edit().clear().apply()
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun acquireWakeLock() {
        releaseWakeLock()
        val pm = getSystemService(PowerManager::class.java)
        val ms = (endTime - System.currentTimeMillis()).coerceAtLeast(1000L) + 60_000L
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "screenofftimer:timer")
            .also { it.acquire(ms) }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val ch = NotificationChannel(CHANNEL_ID, "Screen Off Timer", NotificationManager.IMPORTANCE_LOW)
            getSystemService(NotificationManager::class.java).createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val flags = PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        val openPi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), flags
        )
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, TimerService::class.java).setAction(ACTION_STOP), flags
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_lock_idle_lock)
            .setContentTitle("Layar akan dimatikan")
            .setContentText("Hitung mundur berjalan")
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(true)
            .setWhen(endTime)
            .setUsesChronometer(true)
            .setChronometerCountDown(true)
            .setContentIntent(openPi)
            .addAction(0, "Batal", stopPi)
            .build()
    }

    override fun onDestroy() {
        handler.removeCallbacks(tick)
        releaseWakeLock()
        super.onDestroy()
    }
}
