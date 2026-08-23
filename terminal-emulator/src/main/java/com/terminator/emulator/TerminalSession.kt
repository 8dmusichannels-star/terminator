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
import android.os.ParcelFileDescriptor
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
    // xterm-256color for full feature support; vt100/ansi are there for
    // devices/binaries where full-screen apps don't recognize
    // xterm-256color for lack of a matching terminfo entry.
    private val termType: String = "xterm-256color",
    // Settings > Keyboard > SECCOMP. See NativePty.createSubprocess for what
    // this actually changes at the syscall level.
    private val seccompWorkaround: Boolean = false,
    // Root of the app-bundled terminfo database (see
    // TerminatorApp.extractBundledTerminfo), or null to leave $TERMINFO
    // unset and rely on whatever the device itself provides (or ncurses'
    // hardcoded vt100/ansi fallbacks).
    private val terminfoDir: String? = null
) {
    private var masterFd: Int = -1
    private var pid: Int = -1

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var masterPfd: ParcelFileDescriptor? = null

    private var reader: Thread? = null
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

    val buffer = TerminalBuffer(columns = 80, rows = 24)
    lateinit var emulator: TerminalEmulator
        private set

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

        val argv = if (useRoot) arrayOf("su", "-c", executablePath) else arrayOf(executablePath)
        // A configured working directory (Settings > Sessions > entry path)
        // takes priority; otherwise fall back to this session's own
        // per-session history directory, same as before this field existed.
        val cwd = spec.workingDirectory?.takeIf { it.isNotBlank() } ?: historyFile.parentFile?.absolutePath
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
                seccompWorkaround = seccompWorkaround
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
                    emulator.append(chunk)
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
                alive = false
                markExited()
            }
        }
        reader!!.isDaemon = true
        reader!!.start()

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
            "TERM=$termType",
            "TMPDIR=${cwd ?: Environment.getExternalStorageDirectory().path}"
        )
        if (!terminfoDir.isNullOrBlank()) {
            base += "TERMINFO=$terminfoDir"
        }
        return base.toTypedArray()
    }

    fun write(data: String) {
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

    fun resize(columns: Int, rows: Int) {
        buffer.resize(columns, rows)
        if (::emulator.isInitialized) {
            emulator.onBufferResized()
        }
        if (masterFd >= 0) {
            NativePty.setWindowSize(masterFd, rows, columns)
        }
    }

    private fun appendHistory(chunk: String) {
        try {
            historyFile.appendText(chunk)
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
        exitListener?.invoke()
    }

    private companion object {
        const val SIGTERM = 15
        const val SIGKILL = 9
    }
}
