package com.terminator.app.session


import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.terminator.app.NotificationSessionInfo
import com.terminator.app.R
import com.terminator.app.TerminatorApp
import com.terminator.app.ui.MainActivity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Keeps active sessions alive in the background via a foreground service +
 * partial wake lock, and exposes a persistent notification for quick access
 * back into a session. Resource usage is left to the OS scheduler under
 * normal conditions; when the user has root enabled the service requests a
 * higher-priority wake lock so a lightweight/non-critical session process is
 * not needlessly killed - this is a priority hint, not an unkillable
 * guarantee. A "no-op" mode (see Settings > Sessions) disables this
 * measurement/throttling entirely.
 *
 * The notification itself shows how many sessions are running and lists
 * each one with its own Open action (tapping the row does the same thing);
 * one Close action per row lets the user kill a session without opening the
 * app at all. Both are wired through TerminatorApp's app-wide state/flows
 * rather than anything Activity-owned, since a Service can outlive the
 * Activity entirely.
 */
class SessionForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null
    private var serviceScope: CoroutineScope? = null
    private var closeActionReceiver: BroadcastReceiver? = null

    companion object {
        const val CHANNEL_ID = "terminator_sessions"
        const val NOTIFICATION_ID = 1001
        const val ACTION_START = "com.terminator.app.action.START_FOREGROUND"
        const val ACTION_STOP = "com.terminator.app.action.STOP_FOREGROUND"
        // Internal broadcast the notification's per-session Close button
        // sends to this service - not exported, only this app can send it
        // (see the RECEIVER_NOT_EXPORTED registration below).
        private const val ACTION_CLOSE_SESSION = "com.terminator.app.action.CLOSE_SESSION"
        // Read by MainActivity.handleNotificationIntent to know which
        // session a notification tap (content tap or an "Open" action) was
        // for, and by this service internally for EXTRA_RUNTIME_ID on the
        // close broadcast.
        const val EXTRA_RUNTIME_ID = "com.terminator.app.extra.RUNTIME_ID"

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
        registerCloseActionReceiver()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                releaseWakeLock()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> {
                startForeground(NOTIFICATION_ID, buildNotification(emptyList()))
                acquireWakeLock()
                observeRunningSessions()
            }
        }
        return START_STICKY
    }

    /** Rebuilds and re-posts the notification every time TerminatorApp's
     *  running-session list changes, so the count and per-session rows stay
     *  live without the user needing to reopen the app. */
    private fun observeRunningSessions() {
        if (serviceScope != null) return // already observing from a previous onStartCommand
        val scope = CoroutineScope(SupervisorJob())
        serviceScope = scope
        val app = application as TerminatorApp
        app.runningSessions
            .onEach { sessions ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(sessions))
            }
            .launchIn(scope)
    }

    /** Listens for the per-session Close action's broadcast and forwards it
     *  to TerminatorApp.requestCloseSession - this service has no access to
     *  the live TerminalSession itself (that only exists inside
     *  MainViewModel, which may not even be running right now), so it can
     *  only ask whichever ViewModel instance is listening to do the kill. */
    private fun registerCloseActionReceiver() {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val runtimeId = intent.getStringExtra(EXTRA_RUNTIME_ID) ?: return
                (application as TerminatorApp).requestCloseSession(runtimeId)
            }
        }
        closeActionReceiver = receiver
        val filter = IntentFilter(ACTION_CLOSE_SESSION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
           registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
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

    /** Content intent: opens the app, routed (via EXTRA_RUNTIME_ID) at a
     *  specific session when one is given, or the app's default entry
     *  point when null (used for the top-level notification tap when no
     *  sessions are running yet, and as a base for per-session intents). */
    private fun openAppIntent(runtimeId: String?): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (runtimeId != null) putExtra(EXTRA_RUNTIME_ID, runtimeId)
        }
        // requestCode has to be distinct per runtimeId (not just per
        // action) or Android reuses/overwrites one PendingIntent's extras
        // with another's - every session's "Open" row would silently open
        // whichever session's PendingIntent got built last otherwise.
        val requestCode = runtimeId?.hashCode() ?: 0
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun closeSessionIntent(runtimeId: String): PendingIntent {
        val intent = Intent(ACTION_CLOSE_SESSION).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNTIME_ID, runtimeId)
        }
        return PendingIntent.getBroadcast(
            this, runtimeId.hashCode(), intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun buildNotification(sessions: List<NotificationSessionInfo>): Notification {
        val contentText = when (sessions.size) {
            0 -> getString(R.string.notification_session_running)
            1 -> sessions[0].label
            else -> resources.getQuantityString(
                R.plurals.notification_session_count, sessions.size, sessions.size
            )
        }
        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_notification_terminal)
            .setContentIntent(openAppIntent(sessions.singleOrNull()?.runtimeId))
            .setOngoing(true)

        if (sessions.size > 1) {
            // One expanded row per session, each independently tappable to
            // open straight into that session, plus its own Close button -
            // matches how notification-based multi-session controls work
            // in Termux-style apps rather than only ever landing on
            // whichever session happened to be active when the app was
            // last open.
            val style = NotificationCompat.InboxStyle()
                .setBigContentTitle(
                    resources.getQuantityString(
                        R.plurals.notification_session_count, sessions.size, sessions.size
                    )
                )
            sessions.forEach { style.addLine(it.label) }
            builder.setStyle(style)
            sessions.take(3).forEach { session ->
                // Notification actions cap out around 3 visible without
                // overflowing into a "more" menu on most launchers/OEM
                // skins, so only the first few sessions get an inline Open
                // action - every session is still reachable by tapping its
                // line in the expanded InboxStyle view above, which routes
                // through the same per-session PendingIntent.
                builder.addAction(
                    0, getString(R.string.notification_action_open, session.label),
                    openAppIntent(session.runtimeId)
                )
                builder.addAction(
                    0, getString(R.string.notification_action_close, session.label),
                    closeSessionIntent(session.runtimeId)
                )
            }
        } else if (sessions.size == 1) {
            builder.addAction(
                0, getString(R.string.notification_action_close_generic),
                closeSessionIntent(sessions[0].runtimeId)
            )
        }

        return builder.build()
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
        serviceScope?.cancel()
        serviceScope = null
        closeActionReceiver?.let { runCatching { unregisterReceiver(it) } }
        closeActionReceiver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
