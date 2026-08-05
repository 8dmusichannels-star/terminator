package com.terminator.app

import android.app.Application
import com.terminator.app.session.SessionRepository
import com.terminator.app.settings.SettingsRepository
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/** One running session, as far as the notification needs to know about it -
 *  just enough to build a per-session action row. Kept separate from
 *  MainViewModel's RunningSession so the session package doesn't need to
 *  depend on the ui package. */
data class NotificationSessionInfo(
    val runtimeId: String,
    val label: String
)

/** A "Close" tap on one of the notification's per-session rows. The
 *  Service has no access to the live TerminalSession/ViewModel (those only
 *  exist while an Activity is around), so it can't kill the session itself -
 *  it just posts the request here and MainViewModel, which does hold the
 *  live sessions, actually performs the kill. */
data class NotificationCloseRequest(val runtimeId: String)

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

    override fun onCreate() {
        super.onCreate()
        sessionRepository = SessionRepository(applicationContext)
        settingsRepository = SettingsRepository(applicationContext)
        terminfoDir = extractBundledTerminfo().absolutePath
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
