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

package com.terminator.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.terminator.app.NotificationSessionInfo
import com.terminator.app.TerminatorApp
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
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    // Display label, possibly disambiguated by relabel() below (e.g.
    // "Android Shell (2)") - what the drawer/notification actually show.
    val label: String,
    // The session entry's own name, never touched by relabel(). Kept
    // separate from `label` specifically so relabel() always has a clean
    // starting point to derive from - see its doc for why applying it to
    // `label` directly caused the "(2) (3) (4)..." runaway suffix bug.
    val baseLabel: String = label,
    val exited: Boolean = false,
    // User-requested wake-up state - see TerminatorApp.requestToggleWakeUp's
    // doc. Purely a priority hint communicated to SessionForegroundService,
    // not a guarantee the OS won't still reclaim the process.
    val wakeUp: Boolean = false
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
    private val app: TerminatorApp,
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

    // Latest value of Settings > Terminal > "Clear always purges scrollback"
    // (CLEAR_ALWAYS_PTY). Same "plain field kept fresh via a collector"
    // pattern as termType/seccompEnabled - pushed onto every live session's
    // emulator immediately below AND applied to newly spawned sessions, so
    // toggling it in Settings takes effect on already-open sessions too
    // (unlike termType/seccomp, which only matter at spawn time).
    private var clearAlwaysPurgesScrollback: Boolean = false

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
        viewModelScope.launch {
            settingsRepository.flow(SettingsKeys.CLEAR_ALWAYS_PTY, false).collect { value ->
                clearAlwaysPurgesScrollback = value
                // Unlike termType/seccomp (spawn-time only), this needs to
                // reach sessions that are already running - the whole point
                // is the user flipping it mid-session and immediately
                // seeing `clear` behave differently, not just on their next
                // new tab.
                liveSessions.values.forEach { it.emulator.clearAlwaysPurgesScrollback = value }
            }
        }
        // Keeps the app-wide session list (read by SessionForegroundService
        // to build the notification's session count + per-session Open/
        // Close actions) in sync with the drawer's own runningSessions.
        // distinctUntilChanged so a bufferVersion/scrollOffset-only state
        // update (which happens on essentially every keystroke/line of
        // output) doesn't re-post the notification's RemoteViews dozens of
        // times a second - only an actual session list change does.
        viewModelScope.launch {
            _uiState
                .map { it.runningSessions }
                .distinctUntilChanged()
                .collect { running ->
                    app.updateRunningSessions(
                        running.map { NotificationSessionInfo(it.runtimeId, it.label, it.wakeUp) }
                    )
                }
        }
        // Wake-up toggle from either the notification's "..." menu or the
        // in-app drawer control - see TerminatorApp.requestToggleWakeUp.
        viewModelScope.launch {
            app.wakeUpToggleRequests.collect { request ->
                toggleWakeUp(request.runtimeId)
            }
        }
        // "Close" tap on one of the notification's per-session rows -
        // SessionForegroundService can't kill a session itself (it has no
        // access to the live TerminalSession map, which only exists here),
        // so it posts the request through TerminatorApp and this is what
        // actually performs it.
        viewModelScope.launch {
            app.closeRequests.collect { request ->
                killSession(request.runtimeId)
            }
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

    /**
     * Backs QuickAddSessionPickerDialog: spawns a fresh copy of whichever
     * *specific* running session the user picked from that popup (by
     * runtimeId), rather than always whatever happens to be active - see
     * duplicateActiveSession's doc for the silent-clone-the-wrong-one bug
     * this replaces "+" with. Falls back to duplicateActiveSession's normal
     * default-session behavior if the given runtimeId isn't a live session
     * anymore (e.g. it exited between the popup opening and the tap).
     */
    fun duplicateSession(runtimeId: String) {
        val entry = liveEntries[runtimeId]
        if (entry == null) {
            duplicateActiveSession()
            return
        }
        launchLiveSession(runtimeId = newRuntimeId(entry.id), entry = entry)
    }

    /** Switches to an already-running instance without spawning anything new. */
    fun openRunningSession(runtimeId: String) {
        if (liveSessions[runtimeId]?.isAlive() == true) {
            _uiState.value = _uiState.value.copy(activeSessionId = runtimeId, drawerOpen = false, scrollOffset = 0)
        }
    }

    private fun newRuntimeId(entryId: String): String = "$entryId#${System.currentTimeMillis()}"

    private fun launchLiveSession(runtimeId: String, entry: SessionEntry) {
        // One history file per *session definition* (entryId), not per
        // launch (runtimeId) - runtimeId is "$entryId#$timestamp" (see
        // newRuntimeId), fresh every single time a session is opened, so
        // keying the file by it meant relaunching the same session over and
        // over left behind a new, never-cleaned-up .history file each time
        // instead of resuming the one scrollback that session already had.
        // TerminalSession opens this in append mode, so reusing the same
        // path across relaunches naturally accumulates one continuous
        // history rather than overwriting it.
        // The one case entryId alone doesn't handle is Multiple Mode: two
        // *simultaneously live* instances of the same entry writing to one
        // path would interleave their output into a single file. Falling
        // back to the old per-runtimeId name only for that concurrent case
        // keeps the common "close and reopen this session later" path down
        // to one file while still avoiding cross-talk between instances
        // that are genuinely running at the same time.
        val hasLiveSiblingInstance = liveEntries.any { (otherRuntimeId, otherEntry) ->
            otherEntry.id == entry.id &&
                liveSessions[otherRuntimeId]?.isAlive() == true
        }
        val historyFile = if (hasLiveSiblingInstance) {
            File(historyDir, "${runtimeId}.history")
        } else {
            File(historyDir, "${entry.id}.history")
        }
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
            // DSR/CPR replies (CSI 6n/5n) - the emulator computed the
            // answer, this just has to get those bytes back onto the pty.
            // Without this, anything that probes cursor position on
            // startup (starship among them) blocks waiting for a reply
            // that never comes - the "connects fine, then freezes until
            // Ctrl+C" symptom, since Ctrl+C's SIGINT is what was actually
            // breaking it out of that wait, not any real recovery.
            override fun onRespond(data: String) {
                session.write(data)
            }
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
        session.emulator.clearAlwaysPurgesScrollback = clearAlwaysPurgesScrollback
        liveSessions[runtimeId] = session
        liveEntries[runtimeId] = entry

        val running = _uiState.value.runningSessions + RunningSession(
            runtimeId = runtimeId,
            entryId = entry.id,
            label = entry.name,
            baseLabel = entry.name
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

    /** Flips one session's wakeUp flag - see TerminatorApp.requestToggleWakeUp
     *  for what "awake" actually changes. Symmetric: calling this again on
     *  an already-awake session turns it back off, which is what lets both
     *  the notification's "..." menu and the drawer's own control use the
     *  exact same request for "wake up" and "let it sleep again". */
    fun toggleWakeUp(runtimeId: String) {
        val running = _uiState.value.runningSessions
        val target = running.firstOrNull { it.runtimeId == runtimeId } ?: return
        _uiState.value = _uiState.value.copy(
            runningSessions = running.map {
                if (it.runtimeId == runtimeId) it.copy(wakeUp = !target.wakeUp) else it
            }
        )
    }

    /**
     * Ctrl+D inside the terminal. Delegates to TerminalSession.sendCtrlDOrKill -
     * SIGKILL only when the shell itself is in the foreground (nothing else
     * running), plain EOT (0x04) when some other program has taken the
     * foreground - see that method's doc for the reasoning. Previously this
     * always SIGKILLed the whole session unconditionally, which is what
     * made Ctrl+D forcibly kill vim/any other foreground program.
     */
    fun killActiveSessionHard() {
        val activeId = _uiState.value.activeSessionId ?: return
        liveSessions[activeId]?.sendCtrlDOrKill()
    }

    /** Pinch-to-zoom: per-session text size override in sp, cleared when
     *  that runtime session ends. Does not touch the global Settings value. */
    fun setSessionTextSize(runtimeId: String, sizeSp: Float) {
        _uiState.value = _uiState.value.copy(
            sessionTextSizes = _uiState.value.sessionTextSizes + (runtimeId to sizeSp)
        )
    }

    /** "Android Shell", "Android Shell (2)", "Android Shell (3)", ... in
     *  launch order. Always derives from baseLabel (the session's own,
     *  untouched name) rather than the previous `label` - relabel() runs
     *  again every time the running-session list changes (e.g. opening a
     *  third tab re-labels the first two as well), and appending onto an
     *  already-suffixed label is what previously produced runaway labels
     *  like "Android Shell (2) (3) (4)" instead of stable ones. */
    private fun relabel(running: List<RunningSession>): List<RunningSession> =
        running.groupBy { it.entryId }.values.flatMap { group ->
            if (group.size == 1) {
                group.map { it.copy(label = it.baseLabel) }
            } else {
                group.mapIndexed { i, r -> r.copy(label = "${r.baseLabel} (${i + 1})") }
            }
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
    // Carries the fractional part of adjustScrollOffset's deltaLines across
    // calls (see that method's doc for why this exists).
    private var scrollFractionCarry: Float = 0f

    /**
     * Applies a drag-scroll delta, in fractional lines (pixels / charHeight
     * - see the caller in MainActivity). Small or slow finger movements
     * routinely produce a delta under 1.0 - e.g. a 10px move on a ~40px
     * line height is 0.25 lines - and simply truncating that straight to
     * Int, as this used to do, drops it entirely: nothing scrolls at all
     * until a single frame's movement happens to cross a whole line on its
     * own. That's what made vertical scrolling feel sluggish/laggy - most
     * of a slow, deliberate drag's motion was being silently discarded
     * rather than accumulating into an eventual scroll. Carrying the
     * truncated fractional remainder into the next call means that same
     * slow drag still adds up to the right number of lines over the course
     * of the gesture, it just spreads the actual scroll-offset changes out
     * more evenly instead of only firing on the frames with big jumps.
     */
    /** Returns the actual whole-line change applied (post-clamp, post-
     *  accumulator) - callers that track scroll-relative row coordinates
     *  (selection start/end while edge-auto-scrolling) need this to shift
     *  those coordinates by the same amount scrollOffset just moved, or
     *  their on-screen row numbers silently point at different content
     *  than the instant before the scroll happened. See the edge-auto-
     *  scroll call site in MainActivity for why that matters. */
    fun adjustScrollOffset(deltaLines: Float): Int {
        val buffer = activeBuffer() ?: return 0
        val current = _uiState.value.scrollOffset
        val combined = deltaLines + scrollFractionCarry
        val wholeLines = combined.toInt() // truncates toward zero, same sign as combined
        scrollFractionCarry = combined - wholeLines
        val next = (current + wholeLines).coerceIn(0, buffer.maxScrollOffset)
        if (next != current) {
            _uiState.value = _uiState.value.copy(scrollOffset = next)
        }
        return next - current
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
        // Every session just got killed above, but nothing else will ever
        // tell TerminatorApp that - the collector that normally calls
        // updateRunningSessions on every session-list change (see the
        // viewModelScope.launch near this class's init) dies right along
        // with this ViewModel, since it's viewModelScope-bound. Without
        // this, TerminatorApp.runningSessions stayed frozen at whatever it
        // last was (usually non-empty), which meant SessionForegroundService
        // never got the signal to stop - so its "A session is running"
        // notification kept sitting there indefinitely with zero sessions
        // actually left alive behind it. Clearing it explicitly here, at
        // the one point that's guaranteed to run whenever this ViewModel
        // (and therefore every session it owned) goes away, keeps the
        // notification honest even though nothing else observes session
        // state once the Activity is gone.
        app.updateRunningSessions(emptyList())
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
