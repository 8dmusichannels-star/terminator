package com.terminator.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminator.app.session.SessionEntry
import com.terminator.app.session.SessionRepository
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.settings.SettingsRepository
import com.terminator.emulator.TerminalBuffer
import com.terminator.emulator.TerminalEmulator
import com.terminator.emulator.TerminalSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * One live, running instance of a [SessionEntry]. Several of these can
 * point at the same entry at once (e.g. two shells opened via the "+"
 * button) - runtimeId is what's unique, entryId is just where it came from.
 */
data class RunningSession(
    val runtimeId: String,
    val entryId: String,
    val label: String,
    val exited: Boolean = false
)

data class MainUiState(
    val sessions: List<SessionEntry> = emptyList(),
    val runningSessions: List<RunningSession> = emptyList(),
    val activeSessionId: String? = null,
    val drawerOpen: Boolean = false,
    val bufferVersion: Int = 0, // bumped on content change to trigger recomposition
    val bellTick: Int = 0, // incremented on every BEL so back-to-back bells always retrigger playback
    // Per-runtimeId pinch-to-zoom text size override, in sp. Absent entries
    // fall back to the global Settings > Appearance > Text Size. Cleared
    // automatically when that runtime session ends.
    val sessionTextSizes: Map<String, Float> = emptyMap(),
    // How many lines back into scrollback the active session's view is
    // dragged (0 = live screen). Reset to 0 whenever new output arrives or
    // the active session changes - see bumpVersion()/setActiveSession-style
    // call sites - so the user is never silently stuck looking at history
    // while missing new output with no indication why nothing's updating.
    val scrollOffset: Int = 0
)

class MainViewModel(
    private val repository: SessionRepository,
    private val historyDir: File,
    private val settingsRepository: SettingsRepository,
    // Root of the bundled terminfo db extracted by TerminatorApp.onCreate -
    // see its extractBundledTerminfo() doc for why xterm-256color needs it.
    private val terminfoDir: String
) : ViewModel() {

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    // Live sessions kept alive tab-style; keyed by runtime id.
    private val liveSessions = mutableMapOf<String, TerminalSession>()
    // Which entry each runtime id was spawned from, so "+" can duplicate
    // whatever is currently active and the drawer can label running rows.
    private val liveEntries = mutableMapOf<String, SessionEntry>()

    // Latest value of Settings > Keyboard > Terminal Type. Kept as a plain
    // field (rather than read fresh per-launch) so a newly spawned session
    // always uses whatever the user currently has selected, without every
    // call site needing to be a suspend function just to read one
    // preference. Sessions already running keep whatever TERM they started
    // with - this only affects new ones from this point on.
    private var termType: String = "xterm-256color"

    // Latest value of Settings > Keyboard > SECCOMP. Same "plain field kept
    // fresh via a collector" pattern as termType above - applies to every
    // session launched from this point on.
    private var seccompEnabled: Boolean = false

    // Last measured terminal viewport, in character columns/rows. Updated by
    // MainActivity whenever the actual drawing area changes size (rotation,
    // IME opening/closing, virtual key bar toggling) so every session -
    // including ones not yet launched - gets a size that matches what's
    // really on screen instead of a fixed guess.
    private var columns = 80
    private var rows = 24

    init {
        viewModelScope.launch {
            repository.sessions.collect { list ->
                _uiState.value = _uiState.value.copy(sessions = list)
                if (_uiState.value.activeSessionId == null) {
                    list.firstOrNull { it.isDefault }?.let { openSession(it) }
                }
            }
        }
        viewModelScope.launch {
            settingsRepository.flow(SettingsKeys.TERM_TYPE, "xterm-256color").collect { termType = it }
        }
        viewModelScope.launch {
            settingsRepository.flow(SettingsKeys.SECCOMP_ENABLED, false).collect { seccompEnabled = it }
        }
    }

    fun openSession(entry: SessionEntry) {
        // Single-instance (default) sessions: reuse the live one if it's
        // still alive, just like before.
        if (!entry.allowMultipleInstances) {
            val existing = liveSessions[entry.id]
            if (existing != null && existing.isAlive()) {
                _uiState.value = _uiState.value.copy(activeSessionId = entry.id, drawerOpen = false, scrollOffset = 0)
                return
            }
            launchLiveSession(runtimeId = entry.id, entry = entry)
            return
        }

        // Multi-instance sessions: every tap spawns a brand new live
        // session under its own runtime id, instead of reusing/refocusing
        // whatever is already running under entry.id.
        launchLiveSession(runtimeId = newRuntimeId(entry.id), entry = entry)
    }

    /**
     * Backs the titlebar "+" button: spawns a fresh copy of whichever
     * session is currently active (falling back to the default session if
     * nothing is active yet) and switches to it. This is what lets the user
     * open several independent instances of the same session side by side,
     * listed separately in the drawer's Running section.
     */
    fun duplicateActiveSession() {
        val activeEntry = _uiState.value.activeSessionId?.let { liveEntries[it] }
            ?: _uiState.value.sessions.firstOrNull { it.isDefault }
            ?: _uiState.value.sessions.firstOrNull()
            ?: return
        launchLiveSession(runtimeId = newRuntimeId(activeEntry.id), entry = activeEntry)
    }

    /** Switches to an already-running instance without spawning anything new. */
    fun openRunningSession(runtimeId: String) {
        if (liveSessions[runtimeId]?.isAlive() == true) {
            _uiState.value = _uiState.value.copy(activeSessionId = runtimeId, drawerOpen = false, scrollOffset = 0)
        }
    }

    private fun newRuntimeId(entryId: String): String = "$entryId#${System.currentTimeMillis()}"

    private fun launchLiveSession(runtimeId: String, entry: SessionEntry) {
        val historyFile = File(historyDir, "${runtimeId}.history")
        val session = TerminalSession(
            id = runtimeId,
            spec = entry.toSpec(),
            historyFile = historyFile,
            useRoot = entry.useRoot,
            termType = termType,
            seccompWorkaround = seccompEnabled,
            terminfoDir = terminfoDir
        )
        val listener = object : TerminalEmulator.Listener {
            override fun onBell() {
                _uiState.value = _uiState.value.copy(bellTick = _uiState.value.bellTick + 1)
            }
            override fun onTitleChanged(title: String) { /* surfaced via titlebar if desired */ }
            override fun onCursorMoved(row: Int, col: Int) { bumpVersion() }
            override fun onContentChanged() { bumpVersion() }
        }
        // Fires once, off the pty reader thread, the moment this process is
        // confirmed gone (natural exit, SIGTERM, or SIGKILL). Flips this
        // runtime's row in the drawer to an "exited" state instead of
        // silently leaving a dead session looking alive.
        session.setOnExited {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(
                    runningSessions = _uiState.value.runningSessions.map {
                        if (it.runtimeId == runtimeId) it.copy(exited = true) else it
                    }
                )
                bumpVersion()
            }
        }

        session.start(listener, columns = columns, rows = rows)
        liveSessions[runtimeId] = session
        liveEntries[runtimeId] = entry

        val running = _uiState.value.runningSessions + RunningSession(
            runtimeId = runtimeId,
            entryId = entry.id,
            label = entry.name
        )
        _uiState.value = _uiState.value.copy(
            runningSessions = relabel(running),
            activeSessionId = runtimeId,
            drawerOpen = false,
            scrollOffset = 0
        )
    }

    /**
     * Called when the user presses Enter on a session whose process has
     * already exited (e.g. after Ctrl+D's hard kill) - the frozen last
     * screen otherwise just sits there forever since there's no process
     * left to send input to. Removes that dead runtime entirely and moves
     * on: to another still-running session if one exists (in list order),
     * or - if this was the last one - signals the caller to close the app.
     *
     * Returns true if the app should now exit (no sessions left at all).
     */
    fun dismissExitedActiveSession(): Boolean {
        val activeId = _uiState.value.activeSessionId ?: return true
        liveSessions.remove(activeId)?.kill()
        liveEntries.remove(activeId)
        val remaining = _uiState.value.runningSessions.filterNot { it.runtimeId == activeId }
        val nextActiveId = remaining.firstOrNull()?.runtimeId
        _uiState.value = _uiState.value.copy(
            runningSessions = remaining,
            activeSessionId = nextActiveId,
            sessionTextSizes = _uiState.value.sessionTextSizes - activeId,
            scrollOffset = 0
        )
        bumpVersion()
        return nextActiveId == null
    }

    /**
     * Hard-kills a running session (the trash-can icon next to a "Running"
     * row in the drawer). Unlike [deleteSession] this doesn't touch the
     * saved session profile - only the live process and its runtime
     * bookkeeping go away.
     */
    fun killSession(runtimeId: String) {
        liveSessions.remove(runtimeId)?.kill()
        liveEntries.remove(runtimeId)
        _uiState.value = _uiState.value.copy(
            runningSessions = _uiState.value.runningSessions.filterNot { it.runtimeId == runtimeId },
            sessionTextSizes = _uiState.value.sessionTextSizes - runtimeId
        )
    }

    /**
     * Ctrl+D inside the terminal always sends SIGKILL on the active runtime
     * session (per spec - a harder stop than the usual EOF), rather than
     * writing 0x04 to the pty and waiting for the shell to notice.
     */
    fun killActiveSessionHard() {
        val activeId = _uiState.value.activeSessionId ?: return
        liveSessions[activeId]?.kill()
    }

    /** Pinch-to-zoom: per-session text size override in sp, cleared when
     *  that runtime session ends. Does not touch the global Settings value. */
    fun setSessionTextSize(runtimeId: String, sizeSp: Float) {
        _uiState.value = _uiState.value.copy(
            sessionTextSizes = _uiState.value.sessionTextSizes + (runtimeId to sizeSp)
        )
    }

    /** "Android Shell", "Android Shell (2)", "Android Shell (3)", ... in launch order. */
    private fun relabel(running: List<RunningSession>): List<RunningSession> =
        running.groupBy { it.entryId }.values.flatMap { group ->
            if (group.size == 1) group else group.mapIndexed { i, r -> r.copy(label = "${r.label} (${i + 1})") }
        }.sortedBy { r -> running.indexOfFirst { it.runtimeId == r.runtimeId } }

    fun activeBuffer(): TerminalBuffer? = liveSessions[_uiState.value.activeSessionId]?.buffer

    fun sendInput(text: String) {
        liveSessions[_uiState.value.activeSessionId]?.write(text)
        // Typing always jumps back to the live screen, same as a real
        // terminal/tmux - otherwise keystrokes would land while the user is
        // looking at scrollback and they'd never see what they just typed.
        if (_uiState.value.scrollOffset != 0) {
            _uiState.value = _uiState.value.copy(scrollOffset = 0)
        }
    }

    /** True while the active session's running program has switched to the
     *  alternate screen buffer (nano/vim/htop/less, CSI ?1049h/?47h) - its
     *  scrollback stays empty the whole time (see TerminalBuffer.scrollUp),
     *  so dragging to "scroll back" would have nothing to show and should
     *  just be disabled instead of silently doing nothing. */
    fun activeSessionInAlternateScreen(): Boolean =
        liveSessions[_uiState.value.activeSessionId]?.buffer?.inAlternateScreen == true

    /** Applied by the terminal's pan gesture: positive deltaLines (dragging
     *  down) reveals older scrollback, negative (dragging up) moves back
     *  toward the live screen. Clamped to [0, maxScrollOffset] so it can
     *  never scroll past what's actually available or go negative. */
    fun adjustScrollOffset(deltaLines: Float) {
        val buffer = activeBuffer() ?: return
        val current = _uiState.value.scrollOffset
        val next = (current + deltaLines.toInt()).coerceIn(0, buffer.maxScrollOffset)
        if (next != current) {
            _uiState.value = _uiState.value.copy(scrollOffset = next)
        }
    }

    /** True only while the active session's program has actually enabled
     *  mouse reporting (DECSET 1000/1002/1003) - lets the UI decide whether
     *  a touch on the terminal should become a mouse escape sequence or
     *  fall through to the normal scroll/select/pinch-zoom gestures. */
    fun activeSessionWantsMouseEvents(): Boolean =
        liveSessions[_uiState.value.activeSessionId]?.emulator?.mouseMode != TerminalEmulator.MouseMode.NONE

    /** Whether the running program has switched the terminal into
     *  "application cursor keys" mode (DECCKM, CSI ?1h) - e.g. nano/vim via
     *  ncurses' keypad(TRUE). While active, arrow/home/end keys need to be
     *  sent as SS3 (\EO..) sequences instead of the normal CSI (\E[..) form
     *  or the running program won't recognize them at all. */
    fun activeSessionApplicationCursorKeys(): Boolean =
        liveSessions[_uiState.value.activeSessionId]?.emulator?.applicationCursorKeys == true

    fun sendMouseEvent(kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int = 0) {
        liveSessions[_uiState.value.activeSessionId]?.sendMouseEvent(kind, col, row, button)
    }

    /**
     * Called whenever the actual on-screen terminal area changes size (in
     * character columns/rows, already converted from pixels by the caller
     * using the current font metrics). Resizes every live session's pty so
     * full-screen programs (nano, vim, top...) redraw against the real
     * viewport instead of a stale fixed 80x24 assumption - this is what
     * keeps them legible when the IME or the virtual key bar covers part of
     * the screen and shrinks the visible terminal area.
     */
    fun updateTerminalSize(newColumns: Int, newRows: Int) {
        if (newColumns <= 0 || newRows <= 0) return
        if (newColumns == columns && newRows == rows) return
        columns = newColumns
        rows = newRows
        liveSessions.values.forEach { it.resize(newColumns, newRows) }
        bumpVersion()
    }

    fun setDrawerOpen(open: Boolean) {
        _uiState.value = _uiState.value.copy(drawerOpen = open)
    }

    fun toggleFavorite(entry: SessionEntry) = viewModelScope.launch {
        repository.setFavorite(entry.id, !entry.isFavorite)
    }

    fun setDefault(entry: SessionEntry) = viewModelScope.launch {
        repository.setDefault(entry.id)
    }

    fun deleteSession(entry: SessionEntry) = viewModelScope.launch {
        val runtimeIds = liveEntries.filterValues { it.id == entry.id }.keys.toList()
        runtimeIds.forEach { runtimeId ->
            liveSessions.remove(runtimeId)?.destroy()
            liveEntries.remove(runtimeId)
        }
        _uiState.value = _uiState.value.copy(
            runningSessions = _uiState.value.runningSessions.filterNot { it.entryId == entry.id },
            sessionTextSizes = _uiState.value.sessionTextSizes - runtimeIds.toSet()
        )
        repository.delete(entry.id)
    }

    private fun bumpVersion() {
        _uiState.value = _uiState.value.copy(bufferVersion = _uiState.value.bufferVersion + 1)
    }

    override fun onCleared() {
        liveSessions.values.forEach { it.destroy() }
        super.onCleared()
    }
}

private fun SessionEntry.toSpec(): com.terminator.emulator.SessionSpec = when (type) {
    com.terminator.app.session.SessionType.COMMAND_ARG ->
        com.terminator.emulator.SessionSpec.CommandArg(name, commandPath ?: "/system/bin/sh", workingDirectory)
    com.terminator.app.session.SessionType.FILE_BASE ->
        com.terminator.emulator.SessionSpec.FileBase(
            name, filePath ?: "/sdcard/Terminator", fileName ?: "session.sh", workingDirectory
        )
}
