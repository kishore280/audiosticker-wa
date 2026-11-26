package com.kish.wastickers.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * Keeps the app process running (not frozen by Android) while the engine links or sends.
 * Android freezes background apps after a few seconds; during linking the user is in WhatsApp,
 * so without this service the engine cannot finish the pairing and WhatsApp stays on "Logging in…".
 */
class EngineService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private val stopper = Runnable { stopSelf() }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val text = intent?.getStringExtra(EXTRA_TEXT) ?: "Working…"
        val timeoutMs = intent?.getLongExtra(EXTRA_TIMEOUT, 180_000L) ?: 180_000L
        val n = notification(this, text)
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(ID, n)
        }
        handler.removeCallbacks(stopper)
        handler.postDelayed(stopper, timeoutMs)
        return START_NOT_STICKY
    }

    override fun onTimeout(startId: Int, fgsType: Int) { stopSelf() }

    override fun onDestroy() {
        handler.removeCallbacks(stopper)
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "engine"
        private const val ID = 7
        private const val EXTRA_TEXT = "text"
        private const val EXTRA_TIMEOUT = "timeout"

        fun start(ctx: Context, text: String, timeoutMs: Long) {
            val i = Intent(ctx, EngineService::class.java).putExtra(EXTRA_TEXT, text).putExtra(EXTRA_TIMEOUT, timeoutMs)
            try { ContextCompat.startForegroundService(ctx, i) } catch (_: Exception) {}
        }

        fun stop(ctx: Context) { ctx.stopService(Intent(ctx, EngineService::class.java)) }

        private fun notification(ctx: Context, text: String): Notification {
            if (Build.VERSION.SDK_INT >= 26) {
                val nm = ctx.getSystemService(NotificationManager::class.java)
                if (nm.getNotificationChannel(CHANNEL) == null) {
                    nm.createNotificationChannel(NotificationChannel(CHANNEL, "WhatsApp connection", NotificationManager.IMPORTANCE_LOW))
                }
            }
            val open = ctx.packageManager.getLaunchIntentForPackage(ctx.packageName)
            val pi = android.app.PendingIntent.getActivity(ctx, 0, open, android.app.PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Builder(ctx, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Sticker Studio")
                .setContentText(text)
                .setContentIntent(pi)
                .setOngoing(true)
                .build()
        }
    }
}
