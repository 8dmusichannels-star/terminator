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

import android.os.Environment
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
    val scrollOffset: Int = 0,
    // Same idea as scrollOffset above, but for the split-screen secondary
    // pane's own scrollback position (see splitRuntimeId below) - kept
    // separate so scrolling one pane never moves the other. Reset to 0
    // whenever the split session changes - see setSplitSession.
    val splitScrollOffset: Int = 0,
    // Split-screen (see SplitScreenContainer). Null splitRuntimeId means
    // split is off - the terminal renders as a single pane exactly as
    // before. When set, it names a second RunningSession (distinct from
    // activeSessionId) shown alongside the primary pane. orientation/ratio
    // are purely presentational (which axis, how much space each pane
    // gets) and have no effect on either PTY. broadcastInput, when true,
    // sends typed input to BOTH panes' sessions instead of just the
    // primary - entirely opt-in, off by default, see sendInput's doc.
    val splitRuntimeId: String? = null,
    val splitOrientation: SplitOrientation = SplitOrientation.Horizontal,
    val splitRatio: Float = 0.5f,
    val broadcastInput: Boolean = false,
    // Multi-pane mode (see MultiPaneContainer.kt). Empty means "off" - the
    // classic single-pane (optionally split-screen, above) rendering path
    // is used untouched. Non-empty means MainActivity switches over to
    // MultiPaneContainer instead: an arbitrary number of independent panes,
    // each bound to its own running session, either auto-arranged in a
    // grid (Tiling) or freely dragged/resized by the user (Floating).
    // Deliberately a fully separate system from splitRuntimeId rather than
    // trying to unify the two - splitRuntimeId's 2-pane path is small,
    // well-tested and used by most people who only ever want a single
    // side-by-side pane; multi-pane is opt-in for people who explicitly
    // ask for more than that, reached only via enterMultiPaneMode().
    val panes: List<PaneState> = emptyList(),
    val paneMode: PaneMode = PaneMode.Tiling,
    // Which pane currently receives typed input when broadcastAllPanes
    // (Settings > Display) is off. Tapping a pane's terminal focuses it -
    // see MultiPaneContainer's tap handling. Null only when panes is empty.
    val focusedPaneRuntimeId: String? = null
)

enum class SplitOrientation { Horizontal, Vertical }

/** Tiling: panes auto-arranged in a grid, sized only by dragging the
 *  dividers between them (each pane's own [PaneState.floatOffset]/
 *  [PaneState.floatSize] are ignored in this mode). Floating: every pane is
 *  a free-floating window at its own [PaneState.floatOffset]/[floatSize],
 *  draggable anywhere and independently resizable, panes may overlap. */
enum class PaneMode { Tiling, Floating }

/**
 * One pane in multi-pane mode - a running session plus its own floating
 * geometry. floatOffset/floatSize are only meaningful in [PaneMode.Floating]
 * (ignored by the tiling grid layout) but are kept on every pane
 * regardless of the currently active mode, so switching Tiling -> Floating
 * -> Tiling doesn't lose a manually-placed window's position. Per the "user
 * sınırı belirlesin, session bazlı hatırlansın" requirement, floatOffset/
 * floatSize are seeded from (and persisted back to) SessionRepository's
 * per-entryId floating-geometry store - see PaneGeometryStore - keyed by
 * entryId (the saved session definition) rather than runtimeId (a fresh
 * value every relaunch), so a session's floating window really does come
 * back where it was last left, across relaunches and even across app
 * restarts.
 */
data class PaneState(
    val runtimeId: String,
    // Top-left corner, in dp, relative to the multi-pane container's own
    // origin. Clamped on every drag/restore to keep at least a corner of
    // the pane reachable on screen - see MultiPaneContainer's clamping.
    val floatOffset: androidx.compose.ui.geometry.Offset = androidx.compose.ui.geometry.Offset(24f, 24f),
    val floatSize: androidx.compose.ui.geometry.Size = androidx.compose.ui.geometry.Size(360f, 480f),
    // Floating-mode stacking order - higher draws on top and is what a tap
    // brings to front, same as any desktop floating window manager.
    val zIndex: Float = 0f
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

    // Persists/restores each session's last floating-mode pane geometry -
    // see PaneGeometryStore's own doc and PaneState's doc for why this is
    // keyed by entryId rather than runtimeId.
    private val paneGeometryStore = PaneGeometryStore

    // Per-pane last-applied size, so multi-pane's per-runtimeId resize
    // below can skip a no-op resize() call the same way updateTerminalSize
    // does for the classic shared-size path - each pane's own size,
    // independent of the shared columns/rows pair above and of every other
    // pane's size.
    private val paneColumnsRows = mutableMapOf<String, Pair<Int, Int>>()

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
                // If the chosen session is currently the split partner,
                // closing the split and making it the sole primary is the
                // cleanest outcome — showing the same session in both panes
                // would be confusing, and the user explicitly tapped it to
                // bring it front. We still clear splitRuntimeId so the
                // split closes gracefully.
                val wasSplitPartner = _uiState.value.splitRuntimeId == entry.id
                _uiState.value = _uiState.value.copy(
                    activeSessionId = entry.id,
                    drawerOpen = false,
                    scrollOffset = 0,
                    splitRuntimeId = if (wasSplitPartner) null else _uiState.value.splitRuntimeId,
                    broadcastInput = if (wasSplitPartner) false else _uiState.value.broadcastInput
                )
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
     * Same as [duplicateActiveSession], but for the More menu's split
     * toggle when nothing else is running to split against yet (see that
     * call site's doc) - clones the active session and immediately opens
     * the clone as the split partner, instead of just switching the
     * classic view over to it. launchLiveSession already sets the new
     * clone as activeSessionId, so the ORIGINAL session's id has to be
     * captured before calling it, or there would be nothing left to
     * distinguish "primary" from "split partner" (setSplitSession refuses
     * a session paired with itself, same guard that caused the bug this
     * exists to work around).
     */
    fun duplicateActiveSessionIntoSplit() {
        val activeId = _uiState.value.activeSessionId
        val activeEntry = activeId?.let { liveEntries[it] }
            ?: _uiState.value.sessions.firstOrNull { it.isDefault }
            ?: _uiState.value.sessions.firstOrNull()
            ?: return
        val originalActiveId = activeId
        val newId = newRuntimeId(activeEntry.id)
        launchLiveSession(runtimeId = newId, entry = activeEntry)
        // launchLiveSession just made `newId` the active session - put the
        // ORIGINAL session back as active (so it stays the primary pane,
        // matching what the user was already looking at) and the new
        // clone becomes the split partner alongside it.
        if (originalActiveId != null) {
            _uiState.value = _uiState.value.copy(activeSessionId = originalActiveId)
            setSplitSession(newId)
        }
    }

    /**
     * Backs the split pane's own "Clone session" More-menu row. Unlike
     * [duplicateSession] (which activates the clone, replacing whatever
     * the primary pane was showing - correct for the primary pane's own
     * More menu, wrong here), this keeps the primary pane exactly as it
     * was and swaps a fresh clone of the SPLIT session in as the new
     * split partner - "clone session" in split's own More menu reads as
     * "open a new split with a copy of this session", not "replace what
     * I'm looking at". Mirrors [duplicateActiveSessionIntoSplit]'s own
     * restore-then-set-split pattern, just cloning the split runtimeId
     * given here instead of the active one.
     */
    fun duplicateSplitSessionIntoNewSplit(splitRuntimeId: String) {
        val entry = liveEntries[splitRuntimeId] ?: return
        val originalActiveId = _uiState.value.activeSessionId
        val newId = newRuntimeId(entry.id)
        launchLiveSession(runtimeId = newId, entry = entry)
        // Same reasoning as duplicateActiveSessionIntoSplit: launchLiveSession
        // just made `newId` the active session, which would have silently
        // replaced the primary pane's content. Restore the original primary
        // and put the fresh clone in as the split partner instead.
        if (originalActiveId != null) {
            _uiState.value = _uiState.value.copy(activeSessionId = originalActiveId)
        }
        setSplitSession(newId)
    }

    /**
     * Exports the given session's full terminal output (current screen +
     * everything still in scrollback, up to TerminalBuffer.
     * MAX_SCROLLBACK_LINES) as plain text to the given destination Uri (a
     * CreateDocument launcher in MainActivity - the user picks where to
     * save it, per the feature request, rather than a fixed app-private
     * path). This replaced a separate clipboard-history log: exporting
     * everything the session has actually printed is strictly more useful
     * than a log of only what was manually selected and copied, and avoids
     * maintaining a second persisted history nobody asked to keep.
     * Defaults to the active session when no runtimeId is given, so the
     * primary pane's save button doesn't need to know its own runtimeId.
     */
    fun exportSessionOutput(destination: android.net.Uri, runtimeId: String? = null) {
        val targetId = runtimeId ?: _uiState.value.activeSessionId
        val buffer = liveSessions[targetId]?.buffer ?: return
        viewModelScope.launch {
            val text = buffer.fullText()
            try {
                app.contentResolver.openOutputStream(destination)?.use { out ->
                    out.write(text.toByteArray())
                }
            } catch (_: Exception) {
                // Best-effort: if the destination Uri became invalid (e.g.
                // the user cancelled a slow-loading storage provider), there's
                // nothing meaningful to recover here - the buffer itself is
                // untouched either way, the user can just retry Save.
            }
        }
    }

    /**
     * Backs MultiPaneContainer's per-tile "Clone" More-menu row. Unlike
     * [duplicateSession] (which spawns the clone as the classic view's
     * activeSessionId - correct for the primary pane's own More menu, but
     * invisible here since multi-pane mode renders from panes, not
     * activeSessionId), this adds the fresh clone as a NEW PANE right in
     * the same pane group instead. Without this, tapping Clone on a
     * multi-pane tile silently spawned a background session with no tile
     * of its own - it existed in runningSessions/liveSessions and showed
     * up in the drawer, but nothing on screen changed, reading as "Clone
     * does nothing" in multi-pane mode specifically.
     *
     * Falls back to [spawnAndAddPane] (clone of the focused/active
     * session) if the given runtimeId's entry isn't resolvable anymore -
     * same "clone whichever one is still actually here" fallback
     * [duplicateSession] already uses for the classic view's own picker.
     */
    fun clonePaneSession(runtimeId: String) {
        val entry = liveEntries[runtimeId]
        if (entry == null) {
            spawnAndAddPane()
            return
        }
        val newId = newRuntimeId(entry.id)
        launchLiveSession(runtimeId = newId, entry = entry)
        addPaneSession(newId)
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

    /**
     * Backs MultiPaneContainer's toolbar "+" when there is no already-
     * running session left to offer (every running session is already a
     * pane, or nothing is running at all) - QuickAddSessionPickerDialog
     * would otherwise pop up with an empty list and nothing to tap, same
     * "don't show a useless empty popup" reasoning as
     * duplicateActiveSession's own fallback for the titlebar "+". Spawns a
     * fresh copy of the active session (or the default session if nothing
     * is active) and adds THAT new instance straight to the pane group,
     * skipping the picker entirely since there was nothing to pick between.
     */
    fun spawnAndAddPane() {
        val activeEntry = _uiState.value.activeSessionId?.let { liveEntries[it] }
            ?: _uiState.value.sessions.firstOrNull { it.isDefault }
            ?: _uiState.value.sessions.firstOrNull()
            ?: return
        val newId = newRuntimeId(activeEntry.id)
        launchLiveSession(runtimeId = newId, entry = activeEntry)
        addPaneSession(newId)
    }

    /** Switches to an already-running instance without spawning anything new. */
    fun openRunningSession(runtimeId: String) {
        if (liveSessions[runtimeId]?.isAlive() == true) {
            _uiState.value = _uiState.value.copy(
                activeSessionId = runtimeId,
                drawerOpen = false,
                scrollOffset = 0,
                // Switching the active pane onto whatever was the split
                // partner would leave both panes showing the same session -
                // not a crash, just a confusing dead-end (nothing left to
                // show alongside it). Closing the split here is the same
                // "no longer distinct sessions" cleanup killSession() does
                // when the split partner dies outright.
                splitRuntimeId = _uiState.value.splitRuntimeId?.takeUnless { it == runtimeId },
                broadcastInput = if (_uiState.value.splitRuntimeId == runtimeId) false else _uiState.value.broadcastInput
            )
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
        // Fires once, either off the pty reader thread (natural EOF) or
        // synchronously on the calling thread (kill()/destroy(), which call
        // markExited() inline - see TerminalSession). Flips this runtime's
        // row in the drawer to an "exited" state instead of silently
        // leaving a dead session looking alive.
        //
        // MUST update _uiState.value directly here, not via
        // viewModelScope.launch { ... }. Launching a coroutine defers the
        // update by at least one dispatch, and that deferral is exactly
        // what caused "Ctrl+D kills the session, then Enter does nothing":
        // killActiveSessionHard()/killSessionHard() call sendCtrlDOrKill()
        // -> kill() -> markExited() -> this callback, all on the UI thread
        // when the shell itself is in the foreground - so by the time
        // markExited() returns, the exit is already real, but with
        // launch{} _uiState.value hadn't been published yet. If the
        // user's next keystroke (Enter) landed before that queued
        // coroutine got to run, the activeIsExited/splitExited guards in
        // MainActivity read a stale exited=false and Enter's \r got
        // written into a pty whose fd kill() had already closed - silently
        // swallowed, looked like the app just stopped responding.
        // NOTE: this callback is ALWAYS invoked on the main thread -
        // TerminalSession.markExited() posts it via a main-Looper Handler
        // rather than calling it inline, specifically so this closure
        // (and the plain, non-thread-safe liveSessions/liveEntries maps
        // below) never runs concurrently with the UI thread's own
        // mutation of that same state in launchLiveSession()/killSession()
        // etc. It used to be invoked directly from whatever thread
        // markExited() itself ran on (including the pty reader thread on
        // natural EOF), which is what let one session's background exit
        // race with the UI thread spawning/cloning another session and
        // corrupt these maps - see TerminalSession.markExited()'s doc.
        // Handler.post from the UI thread still defers to the next looper
        // iteration rather than running inline, so the "no coroutine
        // dispatch" ordering guarantee above still holds: this runs before
        // any later UI-thread work, including the next keystroke.
        session.setOnExited {
            _uiState.value = _uiState.value.copy(
                runningSessions = _uiState.value.runningSessions.map {
                    if (it.runtimeId == runtimeId) it.copy(exited = true) else it
                }
            )
            bumpVersion()
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
        paneColumnsRows.remove(runtimeId)
        val wasPaned = _uiState.value.panes.any { it.runtimeId == runtimeId }
        _uiState.value = _uiState.value.copy(
            runningSessions = _uiState.value.runningSessions.filterNot { it.runtimeId == runtimeId },
            sessionTextSizes = _uiState.value.sessionTextSizes - runtimeId,
            // If the session that just died was the split partner, close
            // the split entirely rather than leaving splitRuntimeId pointing
            // at a session that no longer exists - bufferFor() would just
            // return null forever and the secondary pane would be stuck
            // showing an empty/dead screen with no way back to single-pane
            // view short of the user finding the split toggle again.
            splitRuntimeId = _uiState.value.splitRuntimeId?.takeUnless { it == runtimeId },
            broadcastInput = if (_uiState.value.splitRuntimeId == runtimeId) false else _uiState.value.broadcastInput
        )
        // Same cleanup as splitRuntimeId above, but for multi-pane mode -
        // a dead session's pane is removed rather than left showing a
        // permanently frozen/empty screen. Goes through removePane so the
        // "last pane closed -> exit multi-pane mode" and focus-reassignment
        // logic there applies here too, not just to user-initiated closes.
        if (wasPaned) {
            removePane(runtimeId)
        }
    }

    /**
     * "All clear session" - kills every currently RUNNING session (classic
     * view, split partner, and every multi-pane instance alike) in one go,
     * then relaunches a single fresh default session so the user never
     * lands on an empty screen. Deliberately only touches runtime state:
     * saved session profiles in [MainUiState.sessions] (Settings > Sessions)
     * are never read from or written to here - "running" and "saved" are
     * kept as separate concerns the same way [killSession] already treats
     * them for a single session.
     *
     * Reimplements killSession's per-runtime cleanup as one batched pass
     * instead of calling killSession(id) in a loop, so a large pane group
     * collapses via a single _uiState update/recomposition rather than one
     * per running session.
     */
    fun clearAllSessions() {
        val runtimeIds = _uiState.value.runningSessions.map { it.runtimeId }
        if (runtimeIds.isEmpty()) return

        runtimeIds.forEach { id ->
            liveSessions.remove(id)?.kill()
            liveEntries.remove(id)
            paneColumnsRows.remove(id)
        }

        _uiState.value = _uiState.value.copy(
            runningSessions = emptyList(),
            activeSessionId = null,
            sessionTextSizes = emptyMap(),
            scrollOffset = 0,
            splitScrollOffset = 0,
            splitRuntimeId = null,
            broadcastInput = false,
            panes = emptyList(),
            focusedPaneRuntimeId = null
        )

        // Land on a fresh default session rather than an empty screen -
        // same fallback chain openSession/duplicateActiveSession already
        // use elsewhere: the flagged default, or just the first saved
        // session if none is flagged.
        val defaultEntry = _uiState.value.sessions.firstOrNull { it.isDefault }
            ?: _uiState.value.sessions.firstOrNull()
        if (defaultEntry != null) {
            launchLiveSession(runtimeId = defaultEntry.id, entry = defaultEntry)
        }
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

    /**
     * Same foreground-aware Ctrl+D behaviour as [killActiveSessionHard] but
     * targets an arbitrary runtime by id rather than the active session.
     * Used by the split pane's onInput so Ctrl+D there gets the same
     * treatment as the primary pane: SIGKILL when the shell itself is in the
     * foreground (exit is instantaneous → exited flag propagates immediately
     * → the next Enter correctly sees splitExited=true and tears the runtime
     * down), or plain EOT when something else has the foreground (vim, top,
     * etc.) so that program can handle EOF its own way.
     *
     * Without this the split pane was sending a raw 0x04 byte via
     * sendInputTo, which is correct when a foreground program should receive
     * EOF - but when the shell itself is in the foreground, the shell exits
     * asynchronously (pty write → shell reads → shell calls exit()) rather
     * than synchronously via kill(), so there is a timing window where the
     * very next keystroke (Enter) arrives before the exited flag has been
     * set in UI state - splitExited reads false and the Enter is forwarded
     * into a dead pty instead of dismissing the runtime.
     */
    fun killSessionHard(runtimeId: String) {
        liveSessions[runtimeId]?.sendCtrlDOrKill()
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

    /** Buffer for an arbitrary runtimeId, not just the active one - what the
     *  split-screen secondary pane renders. Same underlying liveSessions map
     *  as activeBuffer(); a runtimeId not currently live returns null so the
     *  pane can show "session ended" instead of crashing on a stale buffer. */
    fun bufferFor(runtimeId: String): TerminalBuffer? = liveSessions[runtimeId]?.buffer

    fun sendInput(text: String) {
        val state = _uiState.value
        liveSessions[state.activeSessionId]?.write(text)
        // Split-screen "broadcast input" (Settings-less, per-split toggle -
        // see MainUiState.broadcastInput's doc): mirrors the same keystroke
        // to the split partner's PTY too. Fire-and-forget on both, neither
        // write waits on the other - they're independent PTYs and one being
        // slow to drain shouldn't delay input reaching the other.
        if (state.broadcastInput) {
            state.splitRuntimeId?.let { liveSessions[it]?.write(text) }
        }
        // Typing always jumps back to the live screen, same as a real
        // terminal/tmux - otherwise keystrokes would land while the user is
        // looking at scrollback and they'd never see what they just typed.
        if (_uiState.value.scrollOffset != 0) {
            _uiState.value = _uiState.value.copy(scrollOffset = 0)
        }
    }

    /** Sends input to one specific pane's session, bypassing broadcast -
     *  used by the split-screen secondary pane's own keyboard/paste so
     *  typing directly into pane 2 never doubles into pane 1 regardless of
     *  the broadcast toggle (broadcast only mirrors FROM the primary pane's
     *  input path, sendInput() above - it was never meant to loop pane 2's
     *  own typing back into itself). */
    fun sendInputTo(runtimeId: String, text: String) {
        liveSessions[runtimeId]?.write(text)
    }

    /**
     * Turns split-screen on for the given runtimeId (must be a currently
     * running session, distinct from the active one - the drawer's "Split"
     * action / SelectionToolbar entry point is expected to enforce this by
     * only offering other running sessions as candidates) or off when
     * [runtimeId] is null. Resets ratio to 0.5 and orientation to Horizontal
     * each time split is turned ON from an off state, so re-enabling it
     * after closing always starts from a predictable 50/50 split rather
     * than resuming whatever ratio a previous, unrelated split left behind.
     */
    fun setSplitSession(runtimeId: String?) {
        // Multi-pane mode and split-screen are two independent rendering
        // paths (see MainActivity's top-level `if (state.panes.isNotEmpty())`
        // branch) - that branch checks panes BEFORE splitRuntimeId, so
        // setting splitRuntimeId while panes is still non-empty left the UI
        // stuck on the multi-pane branch forever: the state genuinely
        // changed (splitRuntimeId became non-null) but nothing on screen
        // reflected it, reading as "the split button does nothing" from the
        // drawer's per-row split icon. Closing multi-pane mode first -
        // exactly like tapping its own exit control would - keeps the
        // invariant "at most one of {panes, splitRuntimeId} is active at a
        // time" true instead of letting both linger together, which is
        // what actually broke here. The pane the user was focused on
        // becomes the new primary/active session, same as exitMultiPaneMode
        // on its own already does, so this doesn't silently drop whichever
        // pane they were looking at.
        if (_uiState.value.panes.isNotEmpty()) {
            exitMultiPaneMode()
        }

        // Guard against the active session being asked to split against
        // itself (the drawer already hides the split button on the active
        // row, but this keeps the invariant enforced at the state layer
        // too, not just in one UI entry point). Re-read activeSessionId
        // AFTER the exitMultiPaneMode() call above, since that call can
        // itself change activeSessionId (to whichever pane was focused) -
        // checking against the pre-exit value here would let a stale
        // comparison through.
        val safeRuntimeId = runtimeId?.takeUnless { it == _uiState.value.activeSessionId }
        _uiState.value = _uiState.value.copy(
            splitRuntimeId = safeRuntimeId,
            splitRatio = if (safeRuntimeId != null && _uiState.value.splitRuntimeId == null) 0.5f else _uiState.value.splitRatio,
            splitOrientation = if (safeRuntimeId != null && _uiState.value.splitRuntimeId == null) SplitOrientation.Horizontal else _uiState.value.splitOrientation,
            broadcastInput = if (safeRuntimeId == null) false else _uiState.value.broadcastInput,
            // New (or closed) split session means its scrollback position
            // means nothing anymore - same "never silently stuck looking
            // at stale history" reasoning as scrollOffset's own doc.
            splitScrollOffset = 0
        )
    }

    fun setSplitOrientation(orientation: SplitOrientation) {
        _uiState.value = _uiState.value.copy(splitOrientation = orientation)
    }

    // ---- Multi-pane mode (Tiling / Floating) ----------------------------
    // See MainUiState.panes's doc for why this is a fully separate system
    // from splitRuntimeId above rather than unifying the two.

    /**
     * Turns multi-pane mode on, seeded with the currently active session as
     * the first pane, entering the given mode (default Tiling - the safer
     * of the two to land in, since Floating panes can end up wherever they
     * were last dragged including potentially off the visible area on a
     * different-sized screen). No-op if multi-pane mode is already on.
     */
    fun enterMultiPaneMode(mode: PaneMode = PaneMode.Tiling) {
        if (_uiState.value.panes.isNotEmpty()) return
        val activeId = _uiState.value.activeSessionId ?: return
        viewModelScope.launch {
            val geometry = paneGeometryStore.geometryFor(app, liveEntries[activeId]?.id)
            _uiState.value = _uiState.value.copy(
                panes = listOf(PaneState(runtimeId = activeId, floatOffset = geometry.first, floatSize = geometry.second)),
                paneMode = mode,
                focusedPaneRuntimeId = activeId,
                // The classic split (if one was open) and multi-pane are
                // mutually exclusive rendering paths - close the former so
                // MainActivity's "which container do I draw" check (panes
                // empty vs non-empty) is unambiguous.
                splitRuntimeId = null,
                broadcastInput = false
            )
        }
    }

    /** Leaves multi-pane mode entirely, returning to the classic single-
     *  pane (optionally split-screen) rendering path. The session that was
     *  focused becomes the classic view's active session. Panes' floating
     *  geometry stays persisted (per entryId) for next time.
     *
     *  Guards against landing on a dead runtime: this is called from
     *  removePane() once the last tile is gone (see its own doc), at which
     *  point focusedPaneRuntimeId has already been cleared to null by that
     *  same removePane update - so the fallback below was activeSessionId,
     *  a value multi-pane mode never actually keeps current (see this
     *  file's activeSessionId assignments - the only one inside multi-pane
     *  mode is this function's own, right below). If the tile that just
     *  died was ALSO whatever activeSessionId happened to be frozen at
     *  from before multi-pane mode was entered, that fallback pointed
     *  activeSessionId at a runtime with nothing left in liveSessions for
     *  it - the classic view then rendered a black screen with no process
     *  to send input to, and nothing anywhere spawned a replacement.
     *  Falling back through the still-alive runtime list first, and
     *  spawning a fresh default session only when truly none are left
     *  (same fallback chain clearAllSessions() already uses), means
     *  leaving multi-pane mode always lands on an actual live session. */
    fun exitMultiPaneMode() {
        val state = _uiState.value
        val focused = state.focusedPaneRuntimeId ?: state.activeSessionId
        val focusedIsAlive = focused != null && liveSessions[focused]?.isAlive() == true
        if (focusedIsAlive) {
            _uiState.value = state.copy(
                panes = emptyList(),
                focusedPaneRuntimeId = null,
                activeSessionId = focused
            )
            return
        }
        // Neither the focused pane nor the frozen activeSessionId is still
        // alive - fall back to any other still-running session first.
        val fallbackAlive = state.runningSessions.firstOrNull {
            !it.exited && liveSessions[it.runtimeId]?.isAlive() == true
        }?.runtimeId
        if (fallbackAlive != null) {
            _uiState.value = state.copy(
                panes = emptyList(),
                focusedPaneRuntimeId = null,
                activeSessionId = fallbackAlive
            )
            return
        }
        // Truly nothing left running - same "never land on an empty
        // screen" fallback clearAllSessions() uses: launch a fresh default
        // session rather than leaving activeSessionId pointed at a dead
        // runtime with no process to spawn a replacement for it.
        _uiState.value = state.copy(
            panes = emptyList(),
            focusedPaneRuntimeId = null,
            activeSessionId = null
        )
        val defaultEntry = _uiState.value.sessions.firstOrNull { it.isDefault }
            ?: _uiState.value.sessions.firstOrNull()
        if (defaultEntry != null) {
            launchLiveSession(runtimeId = newRuntimeId(defaultEntry.id), entry = defaultEntry)
        }
    }

    /**
     * Adds a running session as a new pane. If it's already a pane, this
     * only focuses/raises it (no duplicate panes for the same runtimeId -
     * there is only ever at most one pane per running session). Auto-enters
     * multi-pane mode if it wasn't already on, so this single function
     * covers both "start a multi-pane session" and "add one more" call
     * sites (drawer row action either way).
     */
    fun addPaneSession(runtimeId: String) {
        if (liveSessions[runtimeId]?.isAlive() != true) return
        val state = _uiState.value
        // No pane group yet: seeded below (inside the launch block) with
        // BOTH the current active session and the one just requested,
        // rather than dropping the active session on the floor - opening
        // pane mode from the drawer's "add to panes" action on a second
        // session should read as "show these two together".
        if (state.panes.any { it.runtimeId == runtimeId }) {
            bringPaneToFront(runtimeId)
            return
        }
        viewModelScope.launch {
            val entryId = liveEntries[runtimeId]?.id
            val geometry = paneGeometryStore.geometryFor(app, entryId)
            val current = _uiState.value
            val basePanes = if (current.panes.isEmpty()) {
                val activeId = current.activeSessionId
                if (activeId != null && activeId != runtimeId && liveSessions[activeId]?.isAlive() == true) {
                    val activeGeometry = paneGeometryStore.geometryFor(app, liveEntries[activeId]?.id)
                    listOf(PaneState(runtimeId = activeId, floatOffset = activeGeometry.first, floatSize = activeGeometry.second))
                } else {
                    emptyList()
                }
            } else {
                current.panes
            }
            val nextZ = (basePanes.maxOfOrNull { it.zIndex } ?: 0f) + 1f
            val newPane = PaneState(
                runtimeId = runtimeId,
                floatOffset = geometry.first,
                floatSize = geometry.second,
                zIndex = nextZ
            )
            _uiState.value = current.copy(
                panes = basePanes + newPane,
                focusedPaneRuntimeId = runtimeId,
                splitRuntimeId = null,
                broadcastInput = false
            )
        }
    }

    /** Removes one pane (its "x" close button) without killing the
     *  underlying session - it keeps running and reappears in the drawer's
     *  Running list, same as closing a split used to just close the split.
     *  Leaving the last pane closes multi-pane mode entirely rather than
     *  leaving a permanently-empty pane container on screen. */
    /**
     * Closes one multi-pane tile (the tile's own × button, via onClosePane).
     *
     * Previously this ONLY dropped the runtimeId out of the `panes` UI list
     * - it never touched liveSessions/liveEntries/runningSessions, unlike
     * every other "a session is going away" path in this file (killSession,
     * dismissExitedActiveSession, deleteSession all call
     * liveSessions.remove(...)?.kill() and clean up the same three places).
     * That's why closing a tile in multi-pane mode "removed" it from the
     * screen but the underlying pty process, its liveEntries bookkeeping,
     * and its row in the drawer's running-sessions list all silently kept
     * living forever - a real resource/process leak, and a session that
     * looked gone but was still fully alive in the background.
     */
    fun removePane(runtimeId: String) {
        liveSessions.remove(runtimeId)?.kill()
        liveEntries.remove(runtimeId)
        paneColumnsRows.remove(runtimeId)
        val state = _uiState.value
        val remaining = state.panes.filterNot { it.runtimeId == runtimeId }
        val nextFocused = if (state.focusedPaneRuntimeId == runtimeId) {
            remaining.maxByOrNull { it.zIndex }?.runtimeId
        } else {
            state.focusedPaneRuntimeId
        }
        _uiState.value = state.copy(
            panes = remaining,
            focusedPaneRuntimeId = nextFocused,
            runningSessions = state.runningSessions.filterNot { it.runtimeId == runtimeId },
            sessionTextSizes = state.sessionTextSizes - runtimeId
        )
        if (remaining.isEmpty()) {
            exitMultiPaneMode()
        }
    }

    /**
     * Unlike [removePane] (a tile's own "X" - closes the tile AND kills its
     * session, since that's what closing a pane's window should do), this
     * only detaches [runtimeId] from the pane group - the session keeps
     * running in the background, same as any other running session not
     * currently shown as a pane. Backs the drawer's per-row "add to panes"
     * icon (see SessionDrawer's onAddPaneSession/isPaneMember), which needs
     * to be a genuine toggle: tapping a session already in the pane group
     * should take it back OUT of the group, not kill it outright - that
     * icon has no way to distinguish "close this tile's window" intent
     * (removePane's job) from "stop showing this alongside the others, but
     * keep it running" (this function's job), and killing a background
     * session just because the user tapped its "remove from panes" icon
     * would silently destroy work with no warning.
     */
    fun detachPaneKeepAlive(runtimeId: String) {
        val state = _uiState.value
        val remaining = state.panes.filterNot { it.runtimeId == runtimeId }
        val nextFocused = if (state.focusedPaneRuntimeId == runtimeId) {
            remaining.maxByOrNull { it.zIndex }?.runtimeId
        } else {
            state.focusedPaneRuntimeId
        }
        _uiState.value = state.copy(
            panes = remaining,
            focusedPaneRuntimeId = nextFocused
        )
        if (remaining.isEmpty()) {
            exitMultiPaneMode()
        }
    }

    /** Tap-to-focus: makes this pane the target of typed input (when the
     *  broadcast-all-panes setting is off) and, in Floating mode, raises it
     *  to the top of the stack - same as clicking any desktop window. */
    fun bringPaneToFront(runtimeId: String) {
        val state = _uiState.value
        if (state.panes.none { it.runtimeId == runtimeId }) return
        val topZ = (state.panes.maxOfOrNull { it.zIndex } ?: 0f)
        _uiState.value = state.copy(
            panes = state.panes.map {
                if (it.runtimeId == runtimeId) it.copy(zIndex = topZ + 1f) else it
            },
            focusedPaneRuntimeId = runtimeId
        )
    }

    /**
     * Moves keyboard focus to the next/previous pane in [MainUiState.panes]
     * order (wrapping both ways), relative to whichever pane is currently
     * focused - falls back to the first pane if none is focused yet.
     * Backs AppAction.PANE_CYCLE_FOCUS_NEXT/PREV (see AppShortcuts.kt):
     * physical-keyboard-only navigation between multi-pane tiles without
     * needing a tap, same underlying focus field [bringPaneToFront]
     * already sets so scroll/input routing picks the new target up for
     * free. No-op outside multi-pane mode (empty panes list).
     */
    fun cyclePaneFocus(direction: Int) {
        val state = _uiState.value
        if (state.panes.isEmpty()) return
        val ids = state.panes.map { it.runtimeId }
        val currentIndex = ids.indexOf(state.focusedPaneRuntimeId).let { if (it == -1) 0 else it }
        val nextIndex = ((currentIndex + direction) % ids.size + ids.size) % ids.size
        _uiState.value = state.copy(focusedPaneRuntimeId = ids[nextIndex])
    }

    /** Floating-mode drag: updates one pane's on-screen offset (dp), then
     *  persists it (per entryId, not runtimeId - see PaneState's doc) so it
     *  comes back in the same place next time this session is floated. */
    fun movePane(runtimeId: String, offset: androidx.compose.ui.geometry.Offset) {
        val state = _uiState.value
        val pane = state.panes.firstOrNull { it.runtimeId == runtimeId } ?: return
        _uiState.value = state.copy(
            panes = state.panes.map { if (it.runtimeId == runtimeId) it.copy(floatOffset = offset) else it }
        )
        val entryId = liveEntries[runtimeId]?.id ?: return
        viewModelScope.launch {
            paneGeometryStore.saveGeometry(app, entryId, offset, pane.floatSize)
        }
    }

    /** Floating-mode resize handle: updates one pane's size (dp), clamped
     *  by the caller before this is invoked so a drag can never shrink a
     *  pane down to an unusable sliver - persisted the same way movePane
     *  persists position. */
    fun resizePane(runtimeId: String, size: androidx.compose.ui.geometry.Size) {
        val state = _uiState.value
        val pane = state.panes.firstOrNull { it.runtimeId == runtimeId } ?: return
        _uiState.value = state.copy(
            panes = state.panes.map { if (it.runtimeId == runtimeId) it.copy(floatSize = size) else it }
        )
        val entryId = liveEntries[runtimeId]?.id ?: return
        viewModelScope.launch {
            paneGeometryStore.saveGeometry(app, entryId, pane.floatOffset, size)
        }
    }

    /** Tiling <-> Floating toggle. Per spec: switching INTO Floating always
     *  resets every current pane to a fresh cascade/grid-derived starting
     *  layout (rather than carrying over tiling's grid rects) - switching
     *  back to Tiling is a no-op on geometry since tiling computes its own
     *  rects from scratch every time regardless of floatOffset/floatSize. */
    fun setPaneMode(mode: PaneMode) {
        val state = _uiState.value
        if (mode == state.paneMode) return
        if (mode == PaneMode.Floating) {
            val cascadeStepDp = 32f
            val baseSize = androidx.compose.ui.geometry.Size(360f, 480f)
            val relaid = state.panes.mapIndexed { index, pane ->
                pane.copy(
                    floatOffset = androidx.compose.ui.geometry.Offset(
                        24f + (index % 5) * cascadeStepDp,
                        24f + (index % 5) * cascadeStepDp
                    ),
                    floatSize = baseSize,
                    zIndex = index.toFloat()
                )
            }
            _uiState.value = state.copy(panes = relaid, paneMode = mode)
            relaid.forEach { pane ->
                val entryId = liveEntries[pane.runtimeId]?.id ?: return@forEach
                viewModelScope.launch {
                    paneGeometryStore.saveGeometry(app, entryId, pane.floatOffset, pane.floatSize)
                }
            }
        } else {
            _uiState.value = state.copy(paneMode = mode)
        }
    }

    /**
     * Routes typed input while in multi-pane mode: to every visible pane at
     * once when Settings > Display > "Broadcast to all panes"
     * (broadcastAllPanes) is on, or only to [MainUiState.focusedPaneRuntimeId]
     * when it's off - matching "bu ayar aktifse hepsine yazabilsin,
     * değilse basarak focus etmek gerekir". Fire-and-forget on every
     * target, same reasoning as sendInput()'s classic-split broadcast: none
     * of these are meant to block on each other. Reuses sendInputTo() above
     * as the single-target primitive rather than writing to liveSessions
     * directly here.
     *
     * Mirrors the classic split pane's onInput fix in MainActivity (see its
     * splitExited/killSessionHard doc): this was the one call site that
     * fix never reached. Every multi-pane input path - typed IME text, the
     * VirtualKeyBar's key/keymap presses, sendPaneInput was and still is
     * the single primitive they all funnel through - so the same two gaps
     * existed here as in split screen before that fix: a literal EOT byte
     * (Ctrl+D) went to sendInputTo() and only ever hit the shell
     * asynchronously (pty write -> shell reads -> shell exit()), so the
     * "exited" flag wasn't set yet by the time the next Enter arrived, and
     * that Enter then wrote \r into an already-dead pty with nothing left
     * to read it or close the tile - matching "CTRL+D kill ettikten sonra
     * enter basıyorum ama tepki vermiyor, session temizlenmiyor". Handling
     * both here, once, means the typed-text path, the keymap path and the
     * raw key path in MainActivity all get the fix for free instead of
     * needing it duplicated at each of those three call sites.
     */
    fun sendPaneInput(text: String, broadcastAllPanes: Boolean) {
        val state = _uiState.value
        if (state.panes.isEmpty()) return
        val targets = if (broadcastAllPanes) {
            state.panes.map { it.runtimeId }
        } else {
            listOfNotNull(state.focusedPaneRuntimeId)
        }
        targets.forEach { target ->
            val exited = state.runningSessions.firstOrNull { it.runtimeId == target }?.exited == true
            when {
                text == "\u0004" -> killSessionHard(target)
                exited && text.contains('\r') -> removePane(target)
                else -> sendInputTo(target, text)
            }
        }
    }

    /** Clamped well away from 0/1 so neither pane can be dragged down to an
     *  unusable sliver - matches the drag handle's own min-size guard in
     *  SplitScreenContainer, kept here too since this is also reachable
     *  from anywhere else that might set the ratio directly. */
    fun setSplitRatio(ratio: Float) {
        _uiState.value = _uiState.value.copy(splitRatio = ratio.coerceIn(0.15f, 0.85f))
    }

    fun setBroadcastInput(enabled: Boolean) {
        _uiState.value = _uiState.value.copy(broadcastInput = enabled)
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
    /** Set true for the instant of an adjustScrollOffset() call that came
     *  from the edge-auto-scroll-while-selecting gesture (MainActivity),
     *  false for every other caller (free drag-to-pan, pinch-zoom, etc).
     *  MainActivity's LaunchedEffect(state.scrollOffset) reads this right
     *  after the state update to decide whether to clear selectionState -
     *  edge-auto-scroll exists specifically to extend a selection into
     *  scrollback, so wiping the selection the instant it moves scrollOffset
     *  defeated the feature entirely (every auto-scroll tick deleted the
     *  selection it was trying to grow). Free dragging still clears the
     *  selection as before, since in that case the row slots genuinely get
     *  handed different text with no continuity to preserve - see that
     *  LaunchedEffect's own doc. Plain var, not part of MainUiState: it's a
     *  same-frame signal read once immediately after the state write, not
     *  something that should survive recomposition or be part of equality
     *  checks on the UI state.
     */
    /** Returns the actual whole-line change applied (post-clamp, post-
     *  accumulator) - callers that track scroll-relative row coordinates
     *  (selection start/end while edge-auto-scrolling) need this to shift
     *  those coordinates by the same amount scrollOffset just moved, or
     *  their on-screen row numbers silently point at different content
     *  than the instant before the scroll happened. See the edge-auto-
     *  scroll call site in MainActivity for why that matters.
     *
     *  isEdgeAutoScroll no longer feeds a shared state field read later by
     *  a LaunchedEffect - every call site now decides synchronously, right
     *  after calling this, whether to preserve or clear the selection (see
     *  MainActivity's own doc where selectionState is declared for why a
     *  shared-flag-plus-LaunchedEffect version raced other callers of this
     *  function and lost a selection specifically across a pause-then-
     *  resume drag). The parameter is kept only because shiftRows/
     *  recomputeFrom callers still want to distinguish their own tick from
     *  a plain scroll for the return value's sake - it has no other
     *  effect on state anymore. */
    fun adjustScrollOffset(deltaLines: Float, isEdgeAutoScroll: Boolean = false): Int {
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

    // Carries the fractional part of adjustSplitScrollOffset's deltaLines
    // across calls - same reasoning as scrollFractionCarry above, just a
    // separate accumulator so a slow drag in one pane doesn't borrow
    // leftover fraction from a slow drag that happened in the other.
    private var splitScrollFractionCarry: Float = 0f

    /** Same as [adjustScrollOffset], but for the split-screen secondary
     *  pane's own scrollback (splitScrollOffset) against its own buffer,
     *  rather than the active session's. See SplitTerminalPane's drag
     *  gesture for the call site - same synchronous preserve-or-clear
     *  reasoning as adjustScrollOffset's own doc. */
    fun adjustSplitScrollOffset(deltaLines: Float, isEdgeAutoScroll: Boolean = false): Int {
        val runtimeId = _uiState.value.splitRuntimeId ?: return 0
        val buffer = liveSessions[runtimeId]?.buffer ?: return 0
        val current = _uiState.value.splitScrollOffset
        val combined = deltaLines + splitScrollFractionCarry
        val wholeLines = combined.toInt()
        splitScrollFractionCarry = combined - wholeLines
        val next = (current + wholeLines).coerceIn(0, buffer.maxScrollOffset)
        if (next != current) {
            _uiState.value = _uiState.value.copy(splitScrollOffset = next)
        }
        return next - current
    }

    /** True only while the active session's program has actually enabled
     *  mouse reporting (DECSET 1000/1002/1003) - lets the UI decide whether
     *  a touch on the terminal should become a mouse escape sequence or
     *  fall through to the normal scroll/select/pinch-zoom gestures. */
    fun activeSessionWantsMouseEvents(): Boolean =
        liveSessions[_uiState.value.activeSessionId]?.emulator?.mouseMode != TerminalEmulator.MouseMode.NONE

    /** True only when the active session has requested ANY_EVENT (DECSET
     *  1003) - the one mouse mode that reports plain hover MOVE with no
     *  button held (used by htop/mc to highlight under the cursor). Kept
     *  separate from [activeSessionWantsMouseEvents] because 1000/1002
     *  want press/drag/release but explicitly do NOT want a MOVE flood
     *  from a real mouse just sitting still and being nudged around. */
    fun activeSessionWantsMouseMoveEvents(): Boolean =
        liveSessions[_uiState.value.activeSessionId]?.emulator?.mouseMode == TerminalEmulator.MouseMode.ANY_EVENT

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

    /** Same as [activeSessionWantsMouseEvents], but for an arbitrary
     *  runtimeId rather than only the active session - needed so the
     *  split-screen secondary pane (SplitTerminalPane) can support mouse
     *  reporting for its OWN session too, not just whichever session
     *  happens to be primary/active. Previously only the primary pane's
     *  gesture loop ever consulted mouse mode at all, which is why
     *  ncurses programs (mc, vim, htop...) running in the split partner
     *  never got working mouse support - the split pane had no way to
     *  even ask whether its session wanted mouse events. */
    fun sessionWantsMouseEvents(runtimeId: String): Boolean =
        liveSessions[runtimeId]?.emulator?.mouseMode != TerminalEmulator.MouseMode.NONE

    /** Same as [activeSessionWantsMouseMoveEvents], but for an arbitrary
     *  runtimeId - see [sessionWantsMouseEvents]'s doc for why the split
     *  pane needs its own per-runtimeId variant. */
    fun sessionWantsMouseMoveEvents(runtimeId: String): Boolean =
        liveSessions[runtimeId]?.emulator?.mouseMode == TerminalEmulator.MouseMode.ANY_EVENT

    /** Same as [sendMouseEvent], but targets an arbitrary runtimeId - see
     *  [sessionWantsMouseEvents]'s doc for why the split pane needs this
     *  rather than always going through the active session. */
    fun sendMouseEventTo(runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int = 0) {
        liveSessions[runtimeId]?.sendMouseEvent(kind, col, row, button)
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
    fun updateTerminalSize(newColumns: Int, newRows: Int, pixelWidth: Int = 0, pixelHeight: Int = 0) {
        if (newColumns <= 0 || newRows <= 0) return
        if (newColumns == columns && newRows == rows) return
        columns = newColumns
        rows = newRows
        liveSessions.values.forEach { it.resize(newColumns, newRows, pixelWidth, pixelHeight) }
        bumpVersion()
    }

    /**
     * Multi-pane's own per-session resize - unlike [updateTerminalSize]
     * above (which resizes EVERY live session to one shared size, correct
     * for the classic single/split-pane view where at most one full-size
     * pane and one split partner are ever on screen), each multi-pane pane
     * has its own independent on-screen size, so it needs its own
     * independent pty size instead of inheriting the classic path's shared
     * columns/rows. Never touches the classic columns/rows fields or any
     * other session's size - switching back to the classic single-pane
     * view (exitMultiPaneMode) re-applies updateTerminalSize's shared size
     * to the now-single active session on its next measured layout pass,
     * same as it always has.
     */
    fun updateTerminalSizeFor(runtimeId: String, newColumns: Int, newRows: Int, pixelWidth: Int = 0, pixelHeight: Int = 0) {
        if (newColumns <= 0 || newRows <= 0) return
        val current = paneColumnsRows[runtimeId]
        if (current != null && current.first == newColumns && current.second == newRows) return
        paneColumnsRows[runtimeId] = newColumns to newRows
        liveSessions[runtimeId]?.resize(newColumns, newRows, pixelWidth, pixelHeight)
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
        // Same "don't leave a pane pointing at a dead session" cleanup as
        // killSession - a deleted session's own runtime instances (if any
        // were showing as panes) need to go too.
        runtimeIds.forEach { runtimeId ->
            if (_uiState.value.panes.any { it.runtimeId == runtimeId }) {
                removePane(runtimeId)
            }
        }
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
            name, filePath ?: "${Environment.getExternalStorageDirectory().path}/Terminator", fileName ?: "session.sh", workingDirectory
        )
}
