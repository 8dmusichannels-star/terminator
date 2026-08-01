package com.terminator.emulator

import android.os.ParcelFileDescriptor
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException

/**
 * Defines what a session executes.
 *
 * COMMAND_ARG: a single executable path, e.g. "/system/bin/sh" or "/system/bin/su".
 * FILE_BASE: a directory (path) + filename combined into one executable, e.g.
 *            path=/sdcard/Terminator, filename=session.sh -> /sdcard/Terminator/session.sh
 */
sealed class SessionSpec(val displayName: String) {
    class CommandArg(name: String, val commandPath: String) : SessionSpec(name)
    class FileBase(name: String, val path: String, val filename: String) : SessionSpec(name) {
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
    private val useRoot: Boolean = false
) {
    private var masterFd: Int = -1
    private var pid: Int = -1

    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null
    private var masterPfd: ParcelFileDescriptor? = null

    private var reader: Thread? = null
    @Volatile private var alive = false
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
        val cwd = historyFile.parentFile?.absolutePath
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
                cols = columns
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
                alive = false
                markExited()
            }
        }
        reader!!.isDaemon = true
        reader!!.start()
    }

    /**
     * Minimal, predictable environment for the child - PATH so a bare "su"
     * resolves, HOME pointed at the session's own history directory, and a
     * capable TERM so full-screen apps render correctly.
     */
    private fun buildEnvironment(cwd: String?): Array<String> {
        val path = System.getenv("PATH") ?: "/system/bin:/system/xbin"
        return arrayOf(
            "PATH=$path",
            "HOME=${cwd ?: "/sdcard"}",
            "TERM=xterm-256color",
            "TMPDIR=${cwd ?: "/sdcard"}"
        )
    }

    fun write(data: String) {
        try {
            outputStream?.write(data.toByteArray(Charsets.UTF_8))
            outputStream?.flush()
        } catch (_: IOException) {
            // pty closed underneath us; nothing to do
        }
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
        if (pid > 0) {
            NativePty.sendSignal(pid, SIGTERM)
        }
        reader?.interrupt()
        try {
            masterPfd?.close()
        } catch (_: IOException) {
            // already closed
        }
        alive = false
        markExited()
    }

    /**
     * Hard kill, unconditionally - used by the trash-can icon in the
     * session drawer and by Ctrl+D inside the terminal (per spec, Ctrl+D
     * always sends SIGKILL rather than a graceful EOF). SIGKILL can't be
     * caught or ignored by the child, so this is immediate.
     */
    fun kill() {
        if (pid > 0) {
            NativePty.sendSignal(pid, SIGKILL)
        }
        reader?.interrupt()
        try {
            masterPfd?.close()
        } catch (_: IOException) {
            // already closed
        }
        alive = false
        markExited()
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
