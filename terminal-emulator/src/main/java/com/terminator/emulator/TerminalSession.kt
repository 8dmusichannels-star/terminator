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

package com.terminator.emulator

import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue

/**
 * Defines what a session executes.
 *
 * COMMAND_ARG: a single executable path, e.g. "/system/bin/sh" or "/system/bin/su".
 * FILE_BASE: a directory (path) + filename combined into one executable, e.g.
 *            path=/sdcard/Terminator, filename=session.sh -> /sdcard/Terminator/session.sh
 */
sealed class SessionSpec(val displayName: String, val workingDirectory: String?) {
    class CommandArg(name: String, val commandPath: String, workingDirectory: String? = null) :
        SessionSpec(name, workingDirectory)
    class FileBase(name: String, val path: String, val filename: String, workingDirectory: String? = null) :
        SessionSpec(name, workingDirectory) {
        fun resolvedPath(): String = File(path, filename).absolutePath
    }
}

/**
 * Wraps a running shell/process for one terminal session.
 *
 * The child is spawned attached to a real pseudo-terminal (via [NativePty],
 * a small JNI shim over /dev/ptmx + fork/exec) rather than a plain
 * ProcessBuilder pipe. That gives it a proper controlling TTY: job control,
 * SIGWINCH on resize, correct isatty()/ioctl(TIOCGWINSZ) results, working
 * Ctrl-C, and sane behavior from full-screen programs (vim, top, less, ...).
 */
class TerminalSession(
    val id: String,
    val spec: SessionSpec,
    private val historyFile: File,
    private val useRoot: Boolean = false,
    // User-selectable via Settings > Keyboard > Terminal Type. Defaults to
    // "NONE" (don't inject a TERM env var at all - see buildEnvironment's
    // own doc); the other choices cover the common terminfo entries a
    // full-screen program might expect (xterm-256color for full feature
    // support, vt100/ANSI for devices/binaries with no matching terminfo
    // entry for anything fancier, screen/tmux variants for running inside
    // a multiplexer, xterm-kitty for kitty-protocol-aware programs, ...).
    private val termType: String = "NONE",
    // Settings > Keyboard > SECCOMP. See NativePty.createSubprocess for what
    // this actually changes at the syscall level.
    private val seccompWorkaround: Boolean = false,
    // Root of the app-bundled terminfo database (see
    // TerminatorApp.extractBundledTerminfo), or null to leave $TERMINFO
    // unset and rely on whatever the device itself provides (or ncurses'
    // hardcoded vt100/ansi fallbacks).
    private val terminfoDir: String? = null,
    // Settings > Sessions > "Force local echo". See SettingsKeys.
    // FORCE_LOCAL_ECHO's own doc for the full reasoning - default false
    // because every session runs over a real pty that already echoes on
    // its own; this exists only for raw connections that don't. A `var`,
    // not `val`: MainViewModel pushes every settings-flow update onto
    // this field directly on the live session (liveSessions.values.forEach),
    // not just at construction time, so flipping the toggle takes effect
    // on a session already open - the user doesn't have to close and
    // reopen the tab to see a blank/dead connection start echoing.
    var forceLocalEcho: Boolean = false
) {
    private var masterFd: Int = -1
    private var pid: Int = -1

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var masterPfd: ParcelFileDescriptor? = null
    // Opened once and kept for the life of the session, instead of
    // historyFile.appendText(chunk) (Kotlin stdlib: open, write, close on
    // EVERY call) which appendHistory used before. That per-chunk
    // open+write+close syscall round-trip ran synchronously on the reader
    // thread, between one isr.read() and the next - so it directly delayed
    // how soon the next chunk of PTY output (cursor moves, redraws, any
    // ANSI escape sequence) got parsed and rendered. Local shells rarely
    // send output often enough for that per-chunk cost to be visible, but
    // an SSH session - frequent small reads from mouse-reporting TUIs,
    // remote shell redraws, etc. - hits this path far more often per
    // second, which is what read as "SSH/ANSI escape gecikmesi" (escape
    // sequences visibly lagging behind, worse over SSH specifically).
    // A single stream held open for the session's whole lifetime turns
    // each appendHistory call back into a plain buffered write(), with the
    // real fd churn paid only once at session start/end instead of on
    // every read.
    //
    // IMPORTANT: an earlier version of this fix called stream.flush()
    // on every single chunk "to match appendText's per-call durability".
    // flush() on a FileOutputStream-backed stream is its own syscall
    // (effectively a write() of whatever's buffered) - forcing one on
    // EVERY chunk reintroduced almost the same per-chunk syscall cost
    // this fix exists to remove, and made it WORSE specifically for SSH:
    // SSH's frequent small reads (mouse reporting, remote redraws) call
    // appendHistory far more often per second than a local shell does, so
    // a mandatory flush per chunk multiplies exactly where it hurts most
    // - measured as SSH sessions opening slower and lagging harder than
    // before this file's history fix existed at all.
    //
    // Fixed by decoupling durability from the reader thread entirely: a
    // dedicated flusher thread (below, same pattern as the writer thread's
    // own doc for why a separate thread beats inlining) wakes up on a
    // fixed interval and flushes ONLY IF something was actually written
    // since its last pass - not on every appendHistory call, and never
    // blocking the reader. This keeps data loss on a hard crash bounded to
    // a fraction of a second of scrollback, same order of magnitude as a
    // real-time guarantee, without paying a flush syscall per PTY chunk.
    private var historyStream: BufferedOutputStream? = null
    // Set (not incremented - a plain flag is all the flusher needs) by
    // appendHistory after every write, cleared by the flusher right before
    // it flushes. @Volatile since these two threads only ever communicate
    // through this one field - no other shared state, so a full lock
    // would be pure overhead for a single boolean handoff.
    @Volatile private var historyDirty: Boolean = false
    private var historyFlusher: Thread? = null

    private var reader: Thread? = null

    // emulator.append() was always only ever called from one place - the
    // reader thread's loop below - so it (and the pendingCluster/buffer
    // state it mutates) was never made thread-safe. "Force local echo"
    // (see write()'s own doc) is the first caller of append() from
    // somewhere other than the reader thread - typically the UI thread,
    // since that's what invokes write(). Without this lock, a keystroke's
    // local-echo append() and a concurrent pty-output append() could
    // interleave mid-mutation on two different threads - a real data
    // race, not a hypothetical one. Both call sites synchronize on this
    // same object so at most one is ever inside append() at a time.
    private val emulatorAppendLock = Any()
    // All writes to the pty - user keystrokes, mouse events, and (critically)
    // DSR/CPR auto-replies from TerminalEmulator.Listener.onRespond - go
    // through this queue instead of a direct outputStream.write() call. The
    // reader thread invokes onRespond() synchronously from inside
    // emulator.append() (it has to: the reply has to reflect the cursor
    // position at that exact point in the stream), so if write() itself did
    // the blocking I/O, a write that stalls - e.g. the pty's write buffer is
    // full because the remote/ssh side hasn't drained it yet - freezes the
    // *reader* thread right along with it. Nothing else can then read
    // incoming output either, which is exactly the "connects, then freezes
    // until Ctrl+C" symptom: Ctrl+C's SIGINT is what unblocks the write (by
    // interrupting whatever the far end was doing), not any real recovery.
    // A dedicated writer thread means the reader only ever does a cheap,
    // non-blocking queue.put() and immediately continues reading, no matter
    // how long the actual write() ends up taking.
    private val writeQueue = LinkedBlockingQueue<ByteArray>()
    private var writer: Thread? = null
    @Volatile private var alive = false
    // Guards masterPfd.close() so it only ever actually runs once. destroy(),
    // kill(), and the reader thread's EOF path (finally -> markExited()) can
    // all race to tear the fd down for the same exit; without this guard two
    // of them can each call ParcelFileDescriptor.close() on the same fd,
    // and the second close hits an fd fdsan has already marked closed/
    // unowned - "attempted to close file descriptor X, expected to be
    // unowned, actually owned by unique_fd/Parcel". This is a compare-and-set
    // rather than a plain boolean check so two threads calling close() at
    // the same instant can't both pass the check before either sets it.
    private val pfdClosed = java.util.concurrent.atomic.AtomicBoolean(false)
    // True once the child process is confirmed gone (EOF on the pty read
    // side, or an explicit kill/destroy). Exposed so the UI can render an
    // "exited" state on the session that just went away.
    @Volatile private var exited = false
    private var exitListener: (() -> Unit)? = null
    // markExited() itself can run on the pty reader thread (natural EOF,
    // via the finally block in start()'s reader Thread) OR synchronously
    // on whatever thread called kill()/destroy() - the UI thread, in
    // practice. exitListener ultimately mutates MainViewModel's
    // liveSessions/liveEntries maps (plain, non-thread-safe mutableMapOf)
    // and _uiState. If session A's reader thread fires this at the same
    // moment the UI thread is inside launchLiveSession() for a brand new
    // session B - Ctrl+D dismissing A and immediately opening a new
    // session, or tapping Clone on a row while some OTHER session's
    // process happens to be dying in the background - both threads are
    // mutating those same maps concurrently, corrupting them and crashing
    // (this is the "new session after Ctrl+D" and "Clone crashes if
    // another session was killed" bugs). Posting to the main looper
    // serializes every exitListener invocation with all of
    // MainViewModel's own map/state mutations, which only ever happen on
    // the UI thread.
    private val mainHandler = Handler(Looper.getMainLooper())

    val buffer = TerminalBuffer(columns = 80, rows = 24)
    lateinit var emulator: TerminalEmulator
        private set

    // Last cols/rows actually pushed to the pty via ioctl(TIOCSWINSZ).
    // resize() below skips the native call (and the SIGWINCH it triggers)
    // when the new size matches this exactly - e.g. a recomposition that
    // re-reports the same measured size, or two resize sources agreeing on
    // the same target. buffer.resize()/onBufferResized() still always run
    // so the in-app grid stays correct either way; this guard only saves
    // the redundant kernel round-trip + full-screen-program redraw.
    private var lastAppliedColumns = -1
    private var lastAppliedRows = -1

    /** Registers a callback fired exactly once, when the session's process
     *  is first detected as no longer running (natural exit or kill). */
    fun setOnExited(callback: () -> Unit) {
        exitListener = callback
    }

    fun start(listener: TerminalEmulator.Listener, columns: Int, rows: Int) {
        buffer.resize(columns, rows)
        emulator = TerminalEmulator(buffer, listener)

        val executablePath = when (spec) {
            is SessionSpec.CommandArg -> spec.commandPath
            is SessionSpec.FileBase -> spec.resolvedPath()
        }

        // This session's own per-session history directory is the fallback;
        // "Settings > Sessions > entry path" (workingDirectory), when set,
        // takes priority. Applies to both COMMAND_ARG and FILE_BASE.
        //
        // HOST-SIDE ONLY: this feeds NativePty.createSubprocess's chdir(cwd)
        // (see pty.c), which runs before exec - i.e. before the command has
        // done anything of its own. For a plain Android shell that's exactly
        // right, since the configured path is a real host directory. It does
        // NOT work for a path that's only meaningful INSIDE a proot/chroot
        // guest the command hasn't entered yet (that chdir() silently no-ops
        // there - see pty.c's own best-effort handling). Wrapping the whole
        // command line in an extra shell to smuggle the cd through used to
        // exist here for that case, but it added its own writes to the pty
        // stream ahead of the real program's, producing a transient
        // garbled/black-screen flash - removed for good. proot/chroot users
        // should point their own tool's --cwd/-w (or the guest script's own
        // cd) at the guest path instead; this field only ever controls the
        // HOST cwd the command starts from.
        val cwd = spec.workingDirectory?.takeIf { it.isNotBlank() } ?: historyFile.parentFile?.absolutePath

        val argv = if (useRoot) arrayOf("su", "-c", executablePath) else arrayOf(executablePath)
        val envp = buildEnvironment(cwd)
        val pidOut = IntArray(1)

        masterFd = try {
            NativePty.createSubprocess(
                cmd = argv[0],
                cwd = cwd,
                argv = argv,
                envp = envp,
                pidOut = pidOut,
                rows = rows,
                cols = columns,
                seccompWorkaround = seccompWorkaround,
                // Pixel size isn't known yet at spawn time (the view hasn't
                // been measured before the session starts) - the first real
                // resize() call right after layout fills ws_xpixel/ws_ypixel
                // in properly.
                pixelWidth = 0,
                pixelHeight = 0
            )
        } catch (e: IOException) {
            throw IOException("Unable to start session: $executablePath", e)
        }
        pid = pidOut[0]
        alive = true

        val pfd = ParcelFileDescriptor.adoptFd(masterFd)
        masterPfd = pfd
        inputStream = FileInputStream(pfd.fileDescriptor)
        outputStream = FileOutputStream(pfd.fileDescriptor)

        // Append persistent, unlimited scrollback to .history as output arrives.
        reader = Thread {
            try {
                val isr = java.io.InputStreamReader(inputStream, Charsets.UTF_8)
                val buf = CharArray(4096)
                while (true) {
                    val n = isr.read(buf)
                    if (n < 0) break
                    val chunk = String(buf, 0, n)
                    synchronized(emulatorAppendLock) { emulator.append(chunk) }
                    appendHistory(chunk)
                }
            } catch (_: IOException) {
                // process ended / pty closed
            } finally {
                // Flush any grapheme cluster append() was still holding
                // open (see TerminalEmulator.pendingCluster's doc) - the
                // pty has genuinely closed at this point, so "wait for the
                // next chunk to see if this cluster keeps extending" no
                // longer applies; render whatever was captured instead of
                // silently dropping the last partial sequence.
                emulator.flushPendingCluster()
                // Stop the periodic flusher before closing the stream it
                // flushes - interrupt() breaks it out of its sleep loop
                // (see historyFlusher's own doc), then close() below does
                // one final flush of anything written since its last pass.
                historyFlusher?.interrupt()
                // Close the persistent history stream opened by
                // appendHistory (see historyStream's own doc) - closeable
                // even if it was never opened (a session with no output).
                try {
                    historyStream?.close()
                } catch (_: IOException) {
                    // best-effort, same as appendHistory's own write failures
                }
                alive = false
                markExited()
            }
        }
        reader!!.isDaemon = true
        reader!!.start()

        // Periodic history-durability flusher - see historyStream's own
        // doc for why appendHistory itself no longer flushes per chunk.
        // Sleeps almost all the time; only touches the stream (a flush()
        // syscall) on ticks where historyDirty shows real writes happened
        // since the last one, so an idle session costs nothing here at
        // all. 200ms bounds how much scrollback a hard crash could lose
        // to well under a second, while staying far below the frequency
        // SSH's own chunk rate would hit if every chunk flushed itself.
        historyFlusher = Thread {
            try {
                while (true) {
                    Thread.sleep(200)
                    if (historyDirty) {
                        historyDirty = false
                        try {
                            historyStream?.flush()
                        } catch (_: IOException) {
                            // best-effort, same as appendHistory's own write failures
                        }
                    }
                }
            } catch (_: InterruptedException) {
                // reader thread's finally block shutting this down at session end
            }
        }
        historyFlusher!!.isDaemon = true
        historyFlusher!!.start()

        // Dedicated writer thread - see writeQueue's doc comment above for
        // why this can't just be outputStream.write() called inline from
        // wherever write() is invoked (the reader thread, for DSR replies;
        // the UI thread, for keystrokes). take() blocks only this thread
        // when the queue is empty, and the actual write() blocking on slow
        // I/O only ever stalls this thread too - never the reader, never
        // the UI.
        writer = Thread {
            try {
                while (true) {
                    val data = writeQueue.take()
                    outputStream?.write(data)
                    outputStream?.flush()
                }
            } catch (_: InterruptedException) {
                // destroy()/kill() interrupting this thread to shut it down
            } catch (_: IOException) {
                // pty closed underneath us; nothing more to write
            }
        }
        writer!!.isDaemon = true
        writer!!.start()
    }

    /**
     * Minimal, predictable environment for the child - PATH so a bare "su"
     * resolves, HOME pointed at the session's own history directory, TERM
     * set to whatever the user picked in Settings > Keyboard > Terminal
     * Type, and (when available) TERMINFO pointed at the app's bundled
     * terminfo db so xterm-256color actually resolves via ncurses'
     * standard TERMINFO env-var lookup, same mechanism real terminfo
     * installs use - not just a hardcoded guess this app makes up.
     */
    private fun buildEnvironment(cwd: String?): Array<String> {
        val path = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        val base = mutableListOf(
            "PATH=$path",
            "HOME=${cwd ?: Environment.getExternalStorageDirectory().path}",
            "TMPDIR=${cwd ?: Environment.getExternalStorageDirectory().path}"
        )
        // "NONE" (Settings > Keyboard > Terminal Type's own default) means
        // don't inject a TERM at all - leave whatever the shell/exec
        // environment would otherwise provide alone, rather than actually
        // setting the literal string "TERM=NONE" (which is itself a
        // recognized-but-nearly-featureless terminfo entry, not "unset").
        if (termType != "NONE") {
            // The Keyboard settings popup shows "ANSI" (matching the other
            // display labels' capitalization), but the only terminfo entry
            // that actually exists - ncurses' own hardcoded fallback and
            // this app's bundled one alike - is the lowercase "ansi".
            // ncurses' TERM/TERMINFO lookup is case-sensitive, so passing
            // the label through as-is would set TERM=ANSI, which resolves
            // to nothing and silently downgrades full-screen apps to a
            // dumb terminal. Every other entry in TERM_TYPE_OPTIONS is
            // already lowercase and needs no such mapping.
            val envTermType = if (termType == "ANSI") "ansi" else termType
            base += "TERM=$envTermType"
        }
        if (!terminfoDir.isNullOrBlank()) {
            base += "TERMINFO=$terminfoDir"
        }
        return base.toTypedArray()
    }

    fun write(data: String) {
        if (forceLocalEcho && ::emulator.isInitialized) {
            localEchoAppend(data)
        }
        writeRaw(data)
    }

    /**
     * The actual, unwrapped queue put - shared by [write] and [writePaste]
     * so paste's bracketed-paste wrapper never goes through write()'s own
     * forceLocalEcho echo path a second time (see [writePaste]'s own doc
     * on why it echoes the plain [data] itself instead of calling this
     * via [write]).
     */
    private fun writeRaw(data: String) {
        // Non-blocking: hands the bytes to writeQueue and returns
        // immediately. The writer thread (started in start()) does the
        // actual, potentially-blocking outputStream.write() - see
        // writeQueue's doc comment for why that separation matters.
        // put() only ever blocks on a *bounded* queue when full; this one
        // is unbounded, so it can't stall the caller (reader or UI thread)
        // waiting for space - but it still declares InterruptedException,
        // so a caller thread that's mid-interrupt (e.g. during app
        // teardown) doesn't crash on an uncaught exception here.
        try {
            writeQueue.put(data.toByteArray(Charsets.UTF_8))
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    /**
     * "Force local echo" override (Settings > Sessions) - shared by
     * [write] and [writePaste] so the exact same DEL/BS/CR handling
     * applies to both instead of being duplicated at each call site.
     * Feeds [data] straight into the emulator's own append(), the same
     * entry point the pty reader thread uses for incoming output (see
     * start()'s reader loop). This is deliberately a plain, unconditional
     * append with no de-dup against whatever the pty itself might echo
     * back a moment later - that's the documented trade-off users are
     * opting into by turning this on for a connection that doesn't echo
     * on its own; see SettingsKeys.FORCE_LOCAL_ECHO's doc.
     *
     * Raw DEL (0x7F - what this app's own key mapping sends for
     * Backspace, see PhysicalKeyEvent.kt) has no erase effect in this
     * emulator: handleNormal only treats literal BS (\b) as "move cursor
     * left one column" and has no case for DEL at all, so an echoed DEL
     * falls to its `else` branch and gets written into the grid as an
     * actual glyph - cursor moves the WRONG way and nothing gets erased.
     * That never showed up before this setting existed because the real
     * erase effect always came from the REMOTE side's own line-editing
     * echo, which answers a DEL with a proper "\b \b" (backspace, space,
     * backspace) - never a bare DEL byte. Rather than synthesizing that
     * "\b \b" substitution as text and routing it back through
     * append()/handleNormal (which would make the erase depend on
     * handleNormal's own '\b' case and however IT happens to treat the
     * left edge), DEL/BS goes straight to emulator.localEchoBackspace() -
     * a single, predictable buffer-level cursor-back-and-clear that's a
     * guaranteed no-op at cursorCol == 0 rather than however a
     * synthesized "\b \b" would land there. See localEchoBackspace's own
     * doc for the full reasoning.
     *
     * Same DEL/BS reasoning doesn't apply to Enter, which this app's own
     * key mapping sends as a bare CR (\r, see PhysicalKeyEvent.kt):
     * handleNormal's '\r' case only resets cursorCol to 0, it never
     * advances a line - on a real remote, the shell's own canonical-mode
     * echo answers a typed CR with "\r\n", not a bare CR. Echoing a lone
     * \r locally would just return the cursor to the start of the
     * CURRENT line instead of moving to a new one, so every line typed
     * after the first would overwrite the previous one instead of
     * stacking below it - CR is substituted with "\r\n" and still goes
     * through the normal append() path, just split into runs around the
     * DEL/BS bytes handled separately above.
     */
    private fun localEchoAppend(data: String) {
        synchronized(emulatorAppendLock) {
            var runStart = 0
            var i = 0
            while (i < data.length) {
                val ch = data[i]
                if (ch == '\u007F' || ch == '\b') {
                    if (i > runStart) emulator.append(data.substring(runStart, i))
                    emulator.localEchoBackspace()
                    runStart = i + 1
                } else if (ch == '\r') {
                    if (i > runStart) emulator.append(data.substring(runStart, i))
                    emulator.append("\r\n")
                    runStart = i + 1
                }
                i++
            }
            if (runStart < data.length) emulator.append(data.substring(runStart))
        }
    }

    /**
     * Like [write], but for text arriving from a clipboard paste
     * specifically (as opposed to real keystrokes) - the one distinction
     * bracketed paste (DECSET 2004) exists to preserve. When the running
     * program has requested it (emulator.bracketedPasteMode), the text is
     * wrapped in ESC[200~ / ESC[201~ so the program can tell "this whole
     * blob arrived from a paste" and treat embedded newlines as literal
     * text rather than as Enter being pressed after each line - which is
     * exactly what shells with bracketed-paste support (bash/zsh/fish with
     * a recent readline/line-editor) use it for: a multi-line paste lands
     * as one editable block instead of executing line-by-line. When the
     * program hasn't asked for it, this is identical to a plain write() -
     * unwrapped, matching a real terminal's behavior toward programs that
     * never opted in.
     */
    fun writePaste(data: String) {
        val wrapped = if (::emulator.isInitialized && emulator.bracketedPasteMode) {
            "\u001B[200~$data\u001B[201~"
        } else {
            data
        }
        // Echoes the plain [data] itself, not [wrapped] - feeding the
        // ESC[200~/201~ bracketed-paste markers into emulator.append would
        // have the emulator parse them as real escape sequences rather than
        // display them, corrupting the echoed text. Goes through writeRaw
        // (not write()) so this doesn't ALSO trigger write()'s own
        // forceLocalEcho echo of the wrapped string underneath.
        if (forceLocalEcho && ::emulator.isInitialized) {
            localEchoAppend(data)
        }
        writeRaw(wrapped)
    }

    /**
     * Reports a touch as an xterm mouse-tracking escape sequence, if (and
     * only if) the running program has actually asked for mouse reporting
     * via DECSET - see TerminalEmulator.encodeMouseEvent. col/row are
     * 0-indexed terminal cell coordinates, not pixels; the caller (the
     * touch-handling UI code) is responsible for that pixel->cell mapping.
     */
    fun sendMouseEvent(kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int = 0) {
        val seq = emulator.encodeMouseEvent(kind, col, row, button) ?: return
        write(seq)
    }

    /**
     * [pixelWidth]/[pixelHeight] are the terminal view's on-screen size in
     * pixels (ws_xpixel/ws_ypixel) - optional, default 0/0 for callers that
     * only have cols/rows. Programs that trust the kernel's pixel size over
     * cols*font-width (mouse-pixel reporting, sixel/image output, some
     * ncurses builds) need these to be non-zero to lay out correctly;
     * everything else ignores them.
     *
     * [deferIoctl]: true means "still mid-gesture" - buffer.resize() (and
     * the whole onBufferResized()/cellHeightPx follow-up) still runs, so
     * the visible grid and drawn font size stay in sync frame-to-frame
     * (this is what keeps a continuous pinch-zoom from showing a growing
     * black gap - see MainActivity's applyResize/zoomCommitJob docs), but
     * the actual ioctl(TIOCSWINSZ) call - and the SIGWINCH it raises in
     * whatever's running - is skipped. A pinch that changes columns/rows
     * on nearly every ~150ms throttled tick was previously raising
     * SIGWINCH that same number of times per second for the ENTIRE
     * gesture; ncurses full-screen apps (btop chief among them) redraw
     * their whole screen from scratch on every SIGWINCH, so a several-
     * second pinch fired several dozen full btop redraws back to back -
     * "zoom bug'ı tum screenlerde ... btop gibi uygulamalar glitch
     * oluyor" was this SIGWINCH storm, not a rendering bug in btop
     * itself or in this app's own grid math (both already correct).
     * lastAppliedColumns/Rows is intentionally left UNCHANGED while
     * deferred, so the final post-gesture resize() call (deferIoctl =
     * false, from the pinch's own trailing commit or any other caller)
     * still sees a genuine size change against the last value the PTY
     * itself was actually told about, and fires the real ioctl exactly
     * once for the whole gesture.
     */
    fun resize(columns: Int, rows: Int, pixelWidth: Int = 0, pixelHeight: Int = 0, deferIoctl: Boolean = false) {
        buffer.resize(columns, rows)
        if (::emulator.isInitialized) {
            emulator.onBufferResized()
            // Keeps emulator.cellHeightPx (used only to size a natural-size
            // Sixel/Kitty image's cursor-advance in rows - see its own doc)
            // in sync with the view's real layout. rows > 0 guard avoids a
            // divide-by-zero during the brief window before the first real
            // layout pass; pixelHeight == 0 (caller hasn't measured yet, or
            // never wires pixel size in at all) leaves cellHeightPx at 0,
            // which imageRowSpan already treats as "unknown, use the old
            // single-line fallback" - so this is never worse than before.
            if (pixelHeight > 0 && rows > 0) {
                emulator.cellHeightPx = pixelHeight / rows
            }
        }
        // Skip the ioctl (and the SIGWINCH + full redraw it triggers in
        // whatever's running) when cols/rows haven't actually changed - see
        // lastAppliedColumns/Rows's own doc. Pixel size alone changing with
        // the same cols/rows (e.g. font metrics settling a frame later)
        // isn't worth a second kernel round-trip either; the next real
        // cols/rows change will carry the corrected pixel size along.
        // deferIoctl (see this function's own doc) skips it unconditionally
        // while a gesture is still in flight, regardless of whether cols/
        // rows actually changed this tick.
        if (!deferIoctl && masterFd >= 0 && (columns != lastAppliedColumns || rows != lastAppliedRows)) {
            NativePty.setWindowSize(masterFd, rows, columns, pixelWidth, pixelHeight)
            lastAppliedColumns = columns
            lastAppliedRows = rows
        }
    }

    private fun appendHistory(chunk: String) {
        try {
            // Lazily opened on the first chunk (not in start(), so a
            // session that never produces output never touches the file
            // at all - same as appendText's old behavior). FileOutputStream(file,
            // append = true) matches appendText's own open mode; wrapped in
            // BufferedOutputStream so a burst of small chunks (the common
            // case - PTY reads are rarely large) doesn't turn back into a
            // write() syscall per chunk, just per buffer-full/flush.
            val stream = historyStream ?: BufferedOutputStream(
                FileOutputStream(historyFile, /* append = */ true)
            ).also { historyStream = it }
            stream.write(chunk.toByteArray(Charsets.UTF_8))
            // Not flushed here - see historyStream's own doc for why a
            // per-chunk flush() was tried and reverted. historyFlusher
            // picks this up on its own short interval instead; just mark
            // that there's something worth flushing next time it wakes.
            historyDirty = true
        } catch (_: IOException) {
            // best-effort persistence; do not interrupt the session on write failure
        }
    }

    /** Graceful teardown - used when a session profile is deleted, or the
     *  app itself is going away. Gives the child a chance to clean up. */
    fun destroy() {
        // Guard against sending a signal to a pid that has already exited
        // and been reused by an unrelated process (destroy()/kill() can be
        // called after the reader thread's EOF path already tore this
        // session down, e.g. Enter on an already-exited session).
        if (pid > 0 && alive) {
            NativePty.sendSignal(pid, SIGTERM)
        }
        reader?.interrupt()
        writer?.interrupt()
        // historyFlusher is normally torn down from the reader thread's own
        // finally block (natural EOF) - but destroy()/kill() can run first,
        // closing the fd out from under a reader that hasn't reached EOF
        // yet. Interrupting it here too means it's never left running past
        // whichever teardown path gets there first.
        historyFlusher?.interrupt()
        closePfdOnce()
        alive = false
        markExited()
    }

    /**
     * Hard kill, unconditionally - used by the trash-can icon in the
     * session drawer, which always means "get rid of this session now"
     * regardless of what's running in it. SIGKILL can't be caught or
     * ignored by the child, so this is immediate. Ctrl+D inside the
     * terminal goes through [sendCtrlDOrKill] instead - see its doc for why
     * this method isn't the right one for that anymore.
     */
    fun kill() {
        if (pid > 0 && alive) {
            NativePty.sendSignal(pid, SIGKILL)
        }
        reader?.interrupt()
        writer?.interrupt()
        // See destroy()'s identical comment just above.
        historyFlusher?.interrupt()
        closePfdOnce()
        alive = false
        markExited()
    }

    /**
     * Ctrl+D from the terminal keyboard. Only force-kills (SIGKILL) when
     * the shell itself is what's currently in the foreground - i.e. no
     * other program has been launched and taken over. When something else
     * (vim, a build, top, ...) is in the foreground, this sends a plain
     * EOT (0x04) into the pty instead, exactly like a real terminal does,
     * so Ctrl+D behaves as "let the foreground program handle EOF its own
     * way" rather than killing it and any unsaved work out from under the
     * user. Previously this always SIGKILLed unconditionally, which is
     * what made Ctrl+D nuke vim/foreground jobs instead of just, say,
     * closing vim's own EOF-triggered dialog or exiting a REPL cleanly.
     *
     * "Shell is in the foreground" is detected as tcgetpgrp(masterFd)
     * matching this session's own pid: a freshly-spawned shell is its own
     * process group leader (pgid == pid), and job control hands foreground
     * status to a *different* pgid the moment the shell launches anything
     * else. If the pgrp can't be determined (getForegroundPgrp returns -1),
     * this conservatively treats that as "something's running" and just
     * sends EOT rather than risking a kill of unknown work.
     */
    fun sendCtrlDOrKill() {
        if (masterFd < 0 || pid <= 0) return
        val fgPgrp = NativePty.getForegroundPgrp(masterFd)
        if (fgPgrp == pid) {
            kill()
        } else {
            write("\u0004")
        }
    }

    /** Closes [masterPfd] at most once across destroy()/kill()/any future
     *  caller, no matter how many of them race to tear this session down. */
    private fun closePfdOnce() {
        if (!pfdClosed.compareAndSet(false, true)) return
        try {
            masterPfd?.close()
        } catch (_: IOException) {
            // already closed
        }
    }

    fun isAlive(): Boolean = alive

    /** True once this session's process is confirmed gone. */
    fun hasExited(): Boolean = exited

    private fun markExited() {
        // Guard against firing twice - destroy()/kill() and the reader
        // thread's EOF path can both race to call this for the same exit.
        if (exited) return
        exited = true
        // Printed straight into this session's own buffer/scrollback so it
        // shows up like real terminal output, whether the process exited on
        // its own, was SIGTERM'd (destroy()), or hard-killed via Ctrl+D /
        // the drawer trash icon (kill(), which is a SIGKILL per spec).
        if (::emulator.isInitialized) {
            val message = "\r\n[Process completed - press Enter]\r\n"
            emulator.append(message)
            appendHistory(message)
        }
        // See mainHandler's doc above - this MUST NOT invoke exitListener
        // directly from whatever thread markExited() itself is running on.
        // If already on the main thread (kill()/destroy() called from UI
        // code), Handler.post still defers to the next looper iteration
        // rather than running inline - that's fine and in fact necessary:
        // it keeps this always-async, so callers on the UI thread can't
        // accidentally come to depend on the listener having already run
        // by the time markExited() returns.
        val listener = exitListener
        if (listener != null) {
            mainHandler.post { listener.invoke() }
        }
    }

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
