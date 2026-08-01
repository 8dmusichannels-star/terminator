package com.terminator.app

import android.app.Application
import com.terminator.app.session.SessionRepository
import com.terminator.app.settings.SettingsRepository
import java.io.File

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
