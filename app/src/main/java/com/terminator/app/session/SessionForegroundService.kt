/*
 * Modern terminal for Terminator android
 * Copyright (C) 2026 Zaman Huseyinli
 *
 * SPDX-License-Identifier: GPL-3.0-or-later
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

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
import androidx.core.content.ContextCompat
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
 * normal conditions. If the user has marked at least one session "awake"
 * (see TerminatorApp.requestToggleWakeUp), the wake lock is held
 * indefinitely rather than opportunistically, as a priority hint that
 * lowers - but doesn't eliminate - the odds of the OS reclaiming that
 * session's process under memory pressure.
 *
 * The notification always shows a fixed number of actions regardless of how
 * many sessions are running: with exactly one session it offers that
 * session's own Close action directly; with more than one, tapping the
 * notification's body (or its single "Manage sessions" action) opens the
 * app straight into the session drawer, where every session already has
 * its own open/close/wake-up controls. Earlier this instead added a
 * separate Open+Close action PER session, which both blew past the ~3
 * actions most launchers render before overflowing into a "more" menu and,
 * even where it fit, buried the notification under an unreadable wall of
 * buttons the moment a third session was opened.
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
        private const val ACTION_TOGGLE_WAKEUP = "com.terminator.app.action.TOGGLE_WAKEUP"
        // Read by MainActivity.handleNotificationIntent to know which
        // session a notification tap (content tap or the single-session
        // Close action) was for, and by this service internally for
        // EXTRA_RUNTIME_ID on the close broadcast.
        const val EXTRA_RUNTIME_ID = "com.terminator.app.extra.RUNTIME_ID"
        // Read by MainActivity to open straight into the session drawer -
        // used by the "Manage sessions" action so the user lands on the
        // picker instead of whatever session happened to be active, since
        // with 2+ sessions running the notification can no longer say
        // which one they meant.
        const val EXTRA_OPEN_DRAWER = "com.terminator.app.extra.OPEN_DRAWER"

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
                // TerminatorApp.observeSessionServiceLifecycle only ever
                // calls start() once runningSessions is already non-empty,
                // so .value here is never actually the empty list in
                // practice - but startForeground() itself requires a
                // notification immediately, before this service has had a
                // chance to collect anything from that flow. Building the
                // very first notification from the real current value
                // instead of a hardcoded emptyList() is what stops the
                // "A session is running" 0-session text (see
                // buildNotification's `0 ->` branch) from being guaranteed
                // to flash for a frame on every single start - previously
                // it always would, however briefly, since the true list
                // only arrived on the next emission via observeRunningSessions.
                val app = application as TerminatorApp
                val initialSessions = app.runningSessions.value
                startForeground(NOTIFICATION_ID, buildNotification(initialSessions))
                acquireWakeLock(anyAwake = initialSessions.any { it.wakeUp })
                observeRunningSessions()
            }
        }
        return START_STICKY
    }

    /** Rebuilds and re-posts the notification every time TerminatorApp's
     *  running-session list changes, so the count and per-session rows stay
     *  live without the user needing to reopen the app. Also re-derives the
     *  wake lock's priority from whether any session is currently marked
     *  awake. */
    private fun observeRunningSessions() {
        if (serviceScope != null) return // already observing from a previous onStartCommand
        val scope = CoroutineScope(SupervisorJob())
        serviceScope = scope
        val app = application as TerminatorApp
        app.runningSessions
            .onEach { sessions ->
                val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                manager.notify(NOTIFICATION_ID, buildNotification(sessions))
                acquireWakeLock(anyAwake = sessions.any { it.wakeUp })
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
                when (intent.action) {
                    // The toggle plumbing itself (WakeUpToggleRequest /
                    // requestToggleWakeUp) already existed - what was
                    // actually missing, matching the "toggle hiç görünmüyor"
                    // report, was any notification action ever wired to
                    // fire it. The single-session notification (by far the
                    // common case) only ever had a Close action; the 2+
                    // session case deliberately defers all per-session
                    // controls, wake-up included, to the drawer instead of
                    // growing an action per session per session - so this
                    // button only needs to exist for the single-session
                    // notification.
                    ACTION_TOGGLE_WAKEUP -> (application as TerminatorApp).requestToggleWakeUp(runtimeId)
                    else -> (application as TerminatorApp).requestCloseSession(runtimeId)
                }
            }
        }
        closeActionReceiver = receiver
        val filter = IntentFilter().apply {
            addAction(ACTION_CLOSE_SESSION)
            addAction(ACTION_TOGGLE_WAKEUP)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED) 
        } else {
            @Suppress("UnspecifiedRegisterReceiverFlag")
            registerReceiver(receiver, filter)
        }
    }

    /**
     * (Re)acquires the wake lock at a priority reflecting whether any
     * session is currently marked "awake". PARTIAL_WAKE_LOCK itself is the
     * same lock type either way - Android has no notion of a "more
     * important" partial wake lock - so what actually changes is how
     * eagerly this service holds and re-acquires it: with no session
     * awake, a normal-priority hold is enough (the OS's usual scheduling
     * applies); with at least one session awake, the lock is (re)acquired
     * immediately on every running-session update rather than left to
     * possibly lapse, keeping the CPU from suspending under this process
     * for as long as any session wants to stay awake. This is a priority
     * hint, not a kill-proof guarantee - the OS can still reclaim the
     * process under severe memory pressure regardless.
     */
    private fun acquireWakeLock(anyAwake: Boolean) {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (wakeLock?.isHeld != true) {
            wakeLock = pm.newWakeLock(
                PowerManager.PARTIAL_WAKE_LOCK,
                if (anyAwake) "TERMINATOR::SessionWakeUpLock" else "TERMINATOR::SessionWakeLock"
            )
            wakeLock?.setReferenceCounted(false)
        }
        wakeLock?.acquire(/* no timeout - held until session ends or user disables */)
    }

    private fun releaseWakeLock() {
        if (wakeLock?.isHeld == true) wakeLock?.release()
        wakeLock = null
    }

    /** Content intent: opens the app, routed (via EXTRA_RUNTIME_ID) at a
     *  specific session when one is given, or the app's default entry
     *  point when null. */
    private fun openAppIntent(runtimeId: String?, openDrawer: Boolean = false): PendingIntent {
        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            if (runtimeId != null) putExtra(EXTRA_RUNTIME_ID, runtimeId)
            if (openDrawer) putExtra(EXTRA_OPEN_DRAWER, true)
        }
        // requestCode has to be distinct per runtimeId/openDrawer combo (not
        // just per action) or Android reuses/overwrites one PendingIntent's
        // extras with another's.
        val requestCode = (runtimeId?.hashCode() ?: 0) * 31 + if (openDrawer) 1 else 0
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

    private fun toggleWakeUpIntent(runtimeId: String): PendingIntent {
        val intent = Intent(ACTION_TOGGLE_WAKEUP).apply {
            setPackage(packageName)
            putExtra(EXTRA_RUNTIME_ID, runtimeId)
        }
        // Distinct requestCode from closeSessionIntent's (same runtimeId,
        // different action) - runtimeId.hashCode() alone would let Android
        // treat this and the Close action for the same session as the same
        // PendingIntent and silently overwrite one with the other, the same
        // hazard openAppIntent's own comment already calls out above.
        return PendingIntent.getBroadcast(
            this, runtimeId.hashCode() * 31 + 1, intent,
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
            .setOngoing(true)

        when (sessions.size) {
            0 -> {
                builder.setContentIntent(openAppIntent(null))
            }
            1 -> {
                // Single session: tapping the body opens it directly. Close
                // and the wake-up toggle are both offered as actions here -
                // wake-up used to be readable-only (the "★" suffix a few
                // lines below, still used for the 2+ session InboxStyle
                // list) but had no actual button in the single-session case,
                // which is the common one, so the toggle effectively never
                // appeared in normal use even though the request plumbing
                // for it already existed end-to-end.
                builder.setContentIntent(openAppIntent(sessions[0].runtimeId))
                builder.addAction(
                    0, getString(R.string.notification_action_close_generic),
                    closeSessionIntent(sessions[0].runtimeId)
                )
                builder.addAction(
                    0,
                    getString(
                        if (sessions[0].wakeUp) R.string.notification_action_wakeup_off
                        else R.string.notification_action_wakeup_on
                    ),
                    toggleWakeUpIntent(sessions[0].runtimeId)
                )
            }
            else -> {
                // 2+ sessions: the notification can no longer say which one
                // a body tap means, so both the body and the single action
                // route to the drawer - every session's own open/close/
                // wake-up controls live there instead of the notification
                // growing an action per session. Session names are still
                // listed (read-only) in the expanded view so the count
                // alone isn't the only information available at a glance.
                builder.setContentIntent(openAppIntent(null, openDrawer = true))
                val style = NotificationCompat.InboxStyle()
                    .setBigContentTitle(
                        resources.getQuantityString(
                            R.plurals.notification_session_count, sessions.size, sessions.size
                        )
                    )
                sessions.forEach { session ->
                    style.addLine(if (session.wakeUp) "${session.label} ★" else session.label)
                }
                builder.setStyle(style)
                builder.addAction(
                    0, getString(R.string.notification_action_manage_sessions),
                    openAppIntent(null, openDrawer = true)
                )
            }
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
