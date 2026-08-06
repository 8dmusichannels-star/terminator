package com.terminator.app

import android.app.Application
import com.terminator.app.session.SessionForegroundService
import com.terminator.app.session.SessionRepository
import com.terminator.app.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import java.io.File

/** One running session, as far as the notification needs to know about it -
 *  just enough to build a per-session action row. Kept separate from
 *  MainViewModel's RunningSession so the session package doesn't need to
 *  depend on the ui package. */
data class NotificationSessionInfo(
    val runtimeId: String,
    val label: String,
    // Whether the user has marked this session "awake" - see
    // requestToggleWakeUp's doc for what that actually does.
    val wakeUp: Boolean = false
)

/** A "Close" tap on one of the notification's per-session rows. The
 *  Service has no access to the live TerminalSession/ViewModel (those only
 *  exist while an Activity is around), so it can't kill the session itself -
 *  it just posts the request here and MainViewModel, which does hold the
 *  live sessions, actually performs the kill. */
data class NotificationCloseRequest(val runtimeId: String)

/** A wake-up toggle, from either the notification's per-session "..." menu
 *  or the in-app session drawer - both funnel through the same request so
 *  the two surfaces can never disagree about which sessions are woken. */
data class WakeUpToggleRequest(val runtimeId: String)

class TerminatorApp : Application() {
    lateinit var sessionRepository: SessionRepository
        private set

    lateinit var settingsRepository: SettingsRepository
        private set

    // Root of the bundled terminfo database (extracted from assets/terminfo
    // below) - see extractBundledTerminfo() for why this exists. Sessions
    // point $TERMINFO at this directory so xterm-256color (which, unlike
    // vt100/ansi, has no hardcoded fallback baked into ncurses and needs a
    // real compiled entry) actually resolves on devices whose /system
    // doesn't ship one.
    lateinit var terminfoDir: String
        private set

    // Live list of running sessions, kept in sync by MainViewModel and
    // observed by SessionForegroundService to render the notification's
    // session count and per-session Open/Close actions. Application-scoped
    // (rather than ViewModel-scoped) specifically so the Service - which
    // has no access to the Activity's ViewModel - can see it too.
    private val _runningSessions = MutableStateFlow<List<NotificationSessionInfo>>(emptyList())
    val runningSessions: StateFlow<List<NotificationSessionInfo>> = _runningSessions.asStateFlow()

    fun updateRunningSessions(sessions: List<NotificationSessionInfo>) {
        _runningSessions.value = sessions
    }

    // Drives SessionForegroundService's own lifecycle off the *actual*
    // running-session count, application-scoped so it reacts correctly no
    // matter which (if any) Activity is currently alive. Previously
    // MainActivity.onCreate started the service unconditionally and nothing
    // ever stopped it - so the "session runner" notification appeared the
    // instant the app was merely opened, before any session existed, and
    // then persisted forever (surviving even after every session had ended
    // and the app was closed) since no code path ever called
    // SessionForegroundService.stop(). Observing here instead means the
    // service starts only once there's really something to keep alive, and
    // stops itself the moment that's no longer true.
    private fun observeSessionServiceLifecycle() {
        runningSessions
            .map { it.isNotEmpty() }
            .distinctUntilChanged()
            .onEach { hasRunningSessions ->
                if (hasRunningSessions) {
                    SessionForegroundService.start(this)
                } else {
                    SessionForegroundService.stop(this)
                }
            }
            .launchIn(CoroutineScope(SupervisorJob()))
    }

    // Extra buffer capacity of 8: a "Close" tap on the notification can
    // arrive before MainViewModel's collector (below) has started - e.g.
    // the app process was fully dead and the notification action is what's
    // relaunching it - so this needs to hold onto the request rather than
    // drop it, the way a plain MutableSharedFlow(0) would for any
    // subscriber that isn't listening yet at emission time.
    private val _closeRequests = MutableSharedFlow<NotificationCloseRequest>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val closeRequests: SharedFlow<NotificationCloseRequest> = _closeRequests

    fun requestCloseSession(runtimeId: String) {
        _closeRequests.tryEmit(NotificationCloseRequest(runtimeId))
    }

    // Same buffered-SharedFlow shape as closeRequests above, and for the
    // same reason: a toggle tapped from the notification's "..." menu can
    // arrive before MainViewModel has started collecting.
    private val _wakeUpToggleRequests = MutableSharedFlow<WakeUpToggleRequest>(
        replay = 0,
        extraBufferCapacity = 8
    )
    val wakeUpToggleRequests: SharedFlow<WakeUpToggleRequest> = _wakeUpToggleRequests

    /**
     * Toggles a session's "awake" state - on first tap, marks it awake
     * (raises SessionForegroundService's wake lock priority while at least
     * one session is awake, protecting it from being killed under memory
     * pressure); tapping again on an already-awake session undoes it. Same
     * request either way; MainViewModel (which owns the actual state) flips
     * it based on the session's current wakeUp value, so the notification's
     * "..." menu and the in-app session drawer's own wake-up control always
     * agree on what a tap does regardless of which one the user used.
     */
    fun requestToggleWakeUp(runtimeId: String) {
        _wakeUpToggleRequests.tryEmit(WakeUpToggleRequest(runtimeId))
    }

    override fun onCreate() {
        super.onCreate()
        sessionRepository = SessionRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        terminfoDir = extractBundledTerminfo().absolutePath
        observeSessionServiceLifecycle()
    }

    /**
     * Settings > Keyboard > Terminal Type offers xterm-256color, vt100 and
     * ansi. vt100/ansi work everywhere because ncurses ships hardcoded
     * fallback definitions for those exact names - no terminfo file needed.
     * xterm-256color has no such fallback: without a matching compiled
     * terminfo entry somewhere ncurses can find it, full-screen apps
     * (nano/vim/htop) either misrender or silently downgrade, and most
     * stock Android /system images don't carry one.
     *
     * The app bundles pre-compiled entries for all three (see
     * assets/terminfo/<first-letter>/<name>, copied straight from a real
     * ncurses install - terminfo's binary format is architecture-neutral so
     * these work as-is on bionic) and copies them into app-private storage
     * once per install/update. TerminalSession then points the child
     * process's $TERMINFO env var at this directory, so ncurses looks here
     * first regardless of what (if anything) the device itself provides.
     */
    private fun extractBundledTerminfo(): File {
        val root = File(filesDir, "terminfo")
        val entries = listOf("x/xterm-256color", "v/vt100", "a/ansi")
        entries.forEach { relativePath ->
            val dest = File(root, relativePath)
            // Re-copied on every startup (files are a few KB each) rather
            // than gated on dest.exists(), so an app update that changes
            // the bundled entry doesn't leave a stale one behind forever.
            dest.parentFile?.mkdirs()
            runCatching {
                assets.open("terminfo/$relativePath").use { input ->
                    dest.outputStream().use { output -> input.copyTo(output) }
                }
            }
        }
        return root
    }
}
