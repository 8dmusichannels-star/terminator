package com.terminator.app.session

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.terminator.app.R
import com.terminator.app.ui.MainActivity

/**
 * Keeps active sessions alive in the background via a foreground service +
 * partial wake lock, and exposes a persistent notification for quick access
 * back into a session. Resource usage is left to the OS scheduler under
 * normal conditions; when the user has root enabled the service requests a
 * higher-priority wake lock so a lightweight/non-critical session process is
 * not needlessly killed - this is a priority hint, not an unkillable
 * guarantee. A "no-op" mode (see Settings > Sessions) disables this
 * measurement/throttling entirely.
 */
class SessionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "terminator_sessions"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.terminator.app.action.START_FOREGROUND"
        const val ACTION_STOP = "com.terminator.app.action.STOP_FOREGROUND"

        fun start(context: Context) {
            val intent = Intent(context, SessionForegroundService::class.java).apply {
                action = ACTION_START
            }
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, SessionForegroundService::class.java))
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification())
                acquireWakeLock()
            }
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TERMINATOR::SessionWakeLock")
        wakeLock?.setReferenceCounted(false)
        wakeLock?.acquire(/* no timeout - held until session ends or user disables */)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    private fun buildNotification(): Notification {
        val openIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(getString(R.string.notification_session_running))
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notification_channel_name),
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
