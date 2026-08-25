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

import android.view.KeyEvent
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * Every app-level command a physical-keyboard shortcut can be bound to.
 * Deliberately separate from the terminal-byte keymapper (KeymapperScreen's
 * KeymapEntry): these don't write to the PTY at all, they call straight
 * into MainViewModel the same way a toolbar button or More-menu row would.
 *
 * Grouped by area for the editor's picker UI. [label] is what's shown in
 * that picker and in the saved-shortcut list; keep it short and in the
 * same "action, not description" voice as the app's existing button/menu
 * text (e.g. "New split", not "Opens a new split pane").
 */
enum class AppAction(val label: String, val group: String) {
    // Sessions
    NEW_SESSION("Duplicate active session", "Sessions"),
    CLOSE_ACTIVE_SESSION("Close active session (hard kill)", "Sessions"),
    CLEAR_ALL_SESSIONS("Clear all sessions", "Sessions"),
    DISMISS_EXITED_SESSION("Dismiss exited session banner", "Sessions"),
    TOGGLE_WAKE_LOCK("Toggle wake lock for focused session", "Sessions"),

    // Split screen
    SPLIT_DUPLICATE_INTO_SPLIT("Duplicate active session into split", "Split screen"),
    SPLIT_CLOSE("Close split (return to single pane)", "Split screen"),
    SPLIT_TOGGLE_ORIENTATION("Toggle split orientation", "Split screen"),
    SPLIT_SWAP_FOCUS("Switch focus between split panes", "Split screen"),
    SPLIT_TOGGLE_BROADCAST("Toggle broadcast input to both panes", "Split screen"),

    // Multi-pane
    PANE_SPAWN_NEW("Spawn new pane", "Multi-pane"),
    PANE_CLONE_FOCUSED("Clone focused pane", "Multi-pane"),
    PANE_CLOSE_FOCUSED("Close focused pane", "Multi-pane"),
    PANE_BRING_FOCUSED_TO_FRONT("Bring focused pane to front", "Multi-pane"),
    PANE_EXIT_MULTI_PANE("Exit multi-pane mode", "Multi-pane"),
    PANE_CYCLE_FOCUS_NEXT("Focus next pane", "Multi-pane"),
    PANE_CYCLE_FOCUS_PREV("Focus previous pane", "Multi-pane"),

    // Navigation / UI
    TOGGLE_SESSION_DRAWER("Toggle session drawer", "Navigation"),
    SCROLL_UP_PAGE("Scroll up one page", "Navigation"),
    SCROLL_DOWN_PAGE("Scroll down one page", "Navigation"),
    SCROLL_TO_LIVE("Jump to live output (scroll to bottom)", "Navigation");

    companion object {
        fun groupsInOrder(): List<String> =
            entries.map { it.group }.distinct()
    }
}

/**
 * One saved binding: a physical key combo (keycode + modifier bitmask,
 * captured straight from a real KeyEvent so it round-trips exactly what
 * the keyboard sent - see AppShortcutCapture) mapped to an [AppAction].
 * Modeled after KeymapperScreen's KeymapEntry but intentionally a distinct
 * type/table (see SettingsKeys.APP_SHORTCUTS's own doc) since these two
 * shortcut systems dispatch completely differently.
 */
data class AppShortcutEntry(
    val id: String = UUID.randomUUID().toString(),
    val keyCode: Int,
    val metaState: Int, // KeyEvent.META_CTRL_ON / META_ALT_ON / META_SHIFT_ON / META_META_ON, OR'd together
    val action: AppAction
) {
    /** Only the modifier bits this app's capture UI ever records, so a
     *  device-specific stray meta flag (e.g. META_CAPS_LOCK_ON riding
     *  along in the raw event) never breaks matching. */
    val normalizedMeta: Int
        get() = metaState and RELEVANT_META_MASK

    companion object {
        const val RELEVANT_META_MASK =
            KeyEvent.META_CTRL_ON or KeyEvent.META_ALT_ON or
            KeyEvent.META_SHIFT_ON or KeyEvent.META_META_ON
    }
}

/** Human-readable combo text for the shortcut list/editor, e.g.
 *  "Ctrl + Shift + T". Modifier order is fixed so the same combo always
 *  renders identically regardless of press order. */
fun formatShortcutCombo(keyCode: Int, metaState: Int): String {
    val parts = mutableListOf<String>()
    val meta = metaState and AppShortcutEntry.RELEVANT_META_MASK
    if (meta and KeyEvent.META_CTRL_ON != 0) parts += "Ctrl"
    if (meta and KeyEvent.META_ALT_ON != 0) parts += "Alt"
    if (meta and KeyEvent.META_SHIFT_ON != 0) parts += "Shift"
    if (meta and KeyEvent.META_META_ON != 0) parts += "Meta"
    val keyLabel = KeyEvent.keyCodeToString(keyCode)
        .removePrefix("KEYCODE_")
        .replace('_', ' ')
    parts += keyLabel
    return parts.joinToString(" + ")
}

fun decodeAppShortcuts(json: String): List<AppShortcutEntry> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).mapNotNull { i ->
            val o = arr.getJSONObject(i)
            val actionName = o.getString("action")
            val action = AppAction.entries.find { it.name == actionName } ?: return@mapNotNull null
            AppShortcutEntry(
                id = o.getString("id"),
                keyCode = o.getInt("keyCode"),
                metaState = o.getInt("metaState"),
                action = action
            )
        }
    }.getOrDefault(emptyList())
}

fun encodeAppShortcuts(list: List<AppShortcutEntry>): String {
    val arr = JSONArray()
    list.forEach { entry ->
        val o = JSONObject()
        o.put("id", entry.id)
        o.put("keyCode", entry.keyCode)
        o.put("metaState", entry.metaState)
        o.put("action", entry.action.name)
        arr.put(o)
    }
    return arr.toString()
}

/**
 * Finds the saved shortcut (if any) matching a physical KeyEvent's
 * keycode + relevant modifier bits. Both sides are normalized through
 * RELEVANT_META_MASK so an incidental extra meta flag on the incoming
 * event never causes a false miss.
 */
fun List<AppShortcutEntry>.findMatch(event: KeyEvent): AppShortcutEntry? {
    val eventMeta = event.metaState and AppShortcutEntry.RELEVANT_META_MASK
    return find { it.keyCode == event.keyCode && it.normalizedMeta == eventMeta }
}

/**
 * Executes an [AppAction] against the current UI state, resolving "which
 * session/pane" the action applies to the same way physical-keyboard
 * terminal input already does (see PhysicalKeyboardRouting): the focused
 * multi-pane tile if multi-pane is active, else the split's secondary pane
 * if split is focused, else the plain active session. Actions with no
 * natural target (drawer toggle, clear-all, spawn) ignore focus entirely.
 *
 * Lives as an extension on MainViewModel rather than a method on it so
 * this file stays the single place that knows about the app-shortcut
 * action set, mirroring how AppShortcuts.kt already owns the rest of this
 * feature's logic.
 */
fun AppAction.execute(viewModel: MainViewModel, routing: PhysicalKeyboardRouting) {
    val state = viewModel.uiState.value
    val focusedPaneId: String? = state.focusedPaneRuntimeId ?: state.activeSessionId
    val splitTargetId: String? = state.splitRuntimeId

    when (this) {
        AppAction.NEW_SESSION -> viewModel.duplicateActiveSession()
        AppAction.CLOSE_ACTIVE_SESSION -> viewModel.killActiveSessionHard()
        AppAction.CLEAR_ALL_SESSIONS -> viewModel.clearAllSessions()
        AppAction.DISMISS_EXITED_SESSION -> { viewModel.dismissExitedActiveSession() }
        AppAction.TOGGLE_WAKE_LOCK -> {
            val target = if (routing.isMultiPane) focusedPaneId
                else if (routing.splitPaneFocused) splitTargetId
                else state.activeSessionId
            target?.let { viewModel.toggleWakeUp(it) }
        }

        AppAction.SPLIT_DUPLICATE_INTO_SPLIT -> viewModel.duplicateActiveSessionIntoSplit()
        AppAction.SPLIT_CLOSE -> viewModel.setSplitSession(null)
        AppAction.SPLIT_TOGGLE_ORIENTATION -> {
            val next = if (state.splitOrientation == SplitOrientation.Horizontal)
                SplitOrientation.Vertical
            else SplitOrientation.Horizontal
            viewModel.setSplitOrientation(next)
        }
        AppAction.SPLIT_SWAP_FOCUS -> routing.toggleSplitFocus?.invoke()
        AppAction.SPLIT_TOGGLE_BROADCAST -> viewModel.setBroadcastInput(!state.broadcastInput)

        AppAction.PANE_SPAWN_NEW -> viewModel.spawnAndAddPane()
        AppAction.PANE_CLONE_FOCUSED -> focusedPaneId?.let { viewModel.clonePaneSession(it) }
        AppAction.PANE_CLOSE_FOCUSED -> focusedPaneId?.let { viewModel.removePane(it) }
        AppAction.PANE_BRING_FOCUSED_TO_FRONT -> focusedPaneId?.let { viewModel.bringPaneToFront(it) }
        AppAction.PANE_EXIT_MULTI_PANE -> viewModel.exitMultiPaneMode()
        AppAction.PANE_CYCLE_FOCUS_NEXT -> viewModel.cyclePaneFocus(1)
        AppAction.PANE_CYCLE_FOCUS_PREV -> viewModel.cyclePaneFocus(-1)

        AppAction.TOGGLE_SESSION_DRAWER -> viewModel.setDrawerOpen(!state.drawerOpen)
        AppAction.SCROLL_UP_PAGE -> {
            if (routing.splitPaneFocused) viewModel.adjustSplitScrollOffset(20f)
            else viewModel.adjustScrollOffset(20f)
        }
        AppAction.SCROLL_DOWN_PAGE -> {
            if (routing.splitPaneFocused) viewModel.adjustSplitScrollOffset(-20f)
            else viewModel.adjustScrollOffset(-20f)
        }
        AppAction.SCROLL_TO_LIVE -> {
            if (routing.splitPaneFocused) viewModel.adjustSplitScrollOffset(-1_000_000f)
            else viewModel.adjustScrollOffset(-1_000_000f)
        }
    }
}
