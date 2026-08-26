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

package com.terminator.app.ui.settings

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.foundation.focusable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.ui.AppAction
import com.terminator.app.ui.AppShortcutEntry
import com.terminator.app.ui.decodeAppShortcuts
import com.terminator.app.ui.encodeAppShortcuts
import com.terminator.app.ui.formatShortcutCombo
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A single saved shortcut: a free-form name plus the terminal key actions
 *  it triggers. [repeatOnHold] is per-entry and user-chosen (in the editor,
 *  not a fixed list of "these keys always repeat") - some shortcuts make
 *  sense to hold (e.g. a repeated arrow-based combo for scrolling), others
 *  don't (e.g. a one-shot Ctrl+C), and only the user creating the shortcut
 *  really knows which is which. Defaults to false so every keymap saved
 *  before this field existed decodes as "tap only", unchanged from today. */
data class KeymapEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keys: List<String>, // VirtualKey.name values, e.g. "ESC", "CTRL", "UP"
    val repeatOnHold: Boolean = false
)

private val ALL_KEY_OPTIONS = listOf(
    "ESC", "TAB", "CTRL", "ALT", "SLASH", "DASH",
    "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT", "INSERT"
)

fun decodeKeymaps(json: String): List<KeymapEntry> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val keysArr = o.getJSONArray("keys")
            KeymapEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                keys = (0 until keysArr.length()).map { keysArr.getString(it) },
                // optBoolean defaults to false for any entry saved before
                // this field existed, so old keymaps keep their old
                // tap-only behavior unchanged.
                repeatOnHold = o.optBoolean("repeatOnHold", false)
            )
        }
    }.getOrDefault(emptyList())
}

private fun encodeKeymaps(list: List<KeymapEntry>): String {
    val arr = JSONArray()
    list.forEach { entry ->
        val o = JSONObject()
        o.put("id", entry.id)
        o.put("name", entry.name)
        o.put("keys", JSONArray(entry.keys))
        o.put("repeatOnHold", entry.repeatOnHold)
        arr.put(o)
    }
    return arr.toString()
}

/**
 * Opened from Settings > Keyboard > "Keyboard shortcuts & keymapper".
 * Two tabs: "Terminal Keys" (the original named keymap list above, sends
 * terminal byte sequences) and "App Actions" (see AppShortcuts.kt - a
 * physical key combo bound straight to a MainViewModel action instead).
 * Kept as one screen/one entry point since both are "keyboard shortcuts"
 * from the user's point of view even though they dispatch completely
 * differently under the hood.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeymapperScreen(onBack: () -> Unit) {
    var tab by remember { mutableIntStateOf(0) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard shortcuts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Terminal Keys") })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("App Actions") })
            }
            when (tab) {
                0 -> TerminalKeysTab()
                else -> AppActionsTab()
            }
        }
    }
}

/**
 * Tab 1: the original keymap list/editor, unchanged in behavior - only
 * extracted out of the old top-level KeymapperScreen body so it can sit
 * alongside AppActionsTab under the new TabRow. Owns its own '+' FAB
 * (Scaffold-in-Scaffold is fine here since this one has no topBar of its
 * own) since the parent Scaffold's FAB slot can't switch per-tab cleanly.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TerminalKeysTab() {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val keymapsJson by repo.flow(SettingsKeys.KEYMAPS, "").collectAsState(initial = "")
    val keymaps = remember(keymapsJson) { decodeKeymaps(keymapsJson) }

    var editing: KeymapEntry? by remember { mutableStateOf(null) }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        KeymapEditor(
            initial = editing,
            onClose = { showEditor = false; editing = null },
            onSave = { entry ->
                val updated = keymaps.filterNot { it.id == entry.id } + entry
                scope.launch { repo.set(SettingsKeys.KEYMAPS, encodeKeymaps(updated)) }
                showEditor = false
                editing = null
            }
        )
        return
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Text("+")
            }
        }
    ) { padding ->
        if (keymaps.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No shortcuts yet. Tap + to create one.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(keymaps, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = {
                            Text(
                                entry.keys.joinToString(" + ") +
                                    if (entry.repeatOnHold) "  •  hold to repeat" else ""
                            )
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                val updated = keymaps.filterNot { it.id == entry.id }
                                scope.launch { repo.set(SettingsKeys.KEYMAPS, encodeKeymaps(updated)) }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete shortcut")
                            }
                        },
                        modifier = Modifier.clickable {
                            editing = entry
                            showEditor = true
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * '+' destination: a name field ("name kullanıcı ne isterse yazar") and,
 * below it, the key-shortcut area - a multi-select grid of terminal key
 * actions this shortcut triggers. Close discards, Save persists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KeymapEditor(
    initial: KeymapEntry?,
    onClose: () -> Unit,
    onSave: (KeymapEntry) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var selectedKeys by remember { mutableStateOf(initial?.keys?.toSet() ?: emptySet()) }
    var repeatOnHold by remember { mutableStateOf(initial?.repeatOnHold ?: false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (initial == null) "New shortcut" else "Edit shortcut") },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text("Close")
                }
                Button(
                    onClick = {
                        if (name.isNotBlank() && selectedKeys.isNotEmpty()) {
                            onSave(
                                KeymapEntry(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    keys = selectedKeys.toList(),
                                    repeatOnHold = repeatOnHold
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && selectedKeys.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("Key shortcut", style = MaterialTheme.typography.labelLarge)
            Text(
                "Pick the keys this shortcut sends to the terminal.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ALL_KEY_OPTIONS.forEach { key ->
                    FilterChip(
                        selected = key in selectedKeys,
                        onClick = {
                            selectedKeys = if (key in selectedKeys) {
                                selectedKeys - key
                            } else {
                                selectedKeys + key
                            }
                        },
                        label = { Text(key) }
                    )
                }
            }

            Spacer(modifier = Modifier.height(20.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Hold to repeat", style = MaterialTheme.typography.labelLarge)
                    Text(
                        "When on, holding this shortcut's button keeps sending it, like a held key on a physical keyboard. Off sends it once per tap, same as before.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(checked = repeatOnHold, onCheckedChange = { repeatOnHold = it })
            }
        }
    }
}

/**
 * Tab 2: shortcuts bound to app-level actions (new split, clear all
 * sessions, kill focused pane, etc. - see AppShortcuts.kt's AppAction
 * enum) rather than to terminal byte sequences. '+' opens a capture
 * dialog: the user presses the real combo on their physical/Bluetooth
 * keyboard, it's shown back to them live, then they pick which action it
 * should trigger. Stored completely separately from KEYMAPS (its own
 * SettingsKeys.APP_SHORTCUTS entry) since these two tables are read by
 * different dispatch paths - see MainActivity's dispatchKeyEvent.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppActionsTab() {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val shortcutsJson by repo.flow(SettingsKeys.APP_SHORTCUTS, "").collectAsState(initial = "")
    val shortcuts = remember(shortcutsJson) { decodeAppShortcuts(shortcutsJson) }

    var showCapture by remember { mutableStateOf(false) }

    fun persist(updated: List<AppShortcutEntry>) {
        scope.launch { repo.set(SettingsKeys.APP_SHORTCUTS, encodeAppShortcuts(updated)) }
    }

    if (showCapture) {
        AppShortcutCaptureDialog(
            existing = shortcuts,
            onDismiss = { showCapture = false },
            onSave = { entry ->
                persist(shortcuts.filterNot { it.id == entry.id } + entry)
                showCapture = false
            }
        )
    }

    Scaffold(
        floatingActionButton = {
            FloatingActionButton(onClick = { showCapture = true }) {
                Text("+")
            }
        }
    ) { padding ->
        if (shortcuts.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No app shortcuts yet. Tap + and press a key combo on your keyboard to bind it to an action.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(shortcuts, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.action.label) },
                        supportingContent = {
                            Text(formatShortcutCombo(entry.keyCode, entry.metaState) + "  •  " + entry.action.group)
                        },
                        trailingContent = {
                            IconButton(onClick = {
                                persist(shortcuts.filterNot { it.id == entry.id })
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete shortcut")
                            }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * Full-screen "press keys..." capture flow: an invisible-but-focused
 * surface intercepts the next physical KeyEvent via Compose's own
 * onKeyEvent (works here because this dialog, unlike the running
 * terminal, has no PTY that also wants the keystroke - no
 * dispatchKeyEvent-level interception needed). Once a non-modifier key
 * lands, the combo is frozen and shown back to the user with the action
 * picker below it; re-tapping "Press keys..." lets them redo the capture
 * before saving. Warns (but doesn't block) if the combo is already bound
 * to something else, since silently overwriting a forgotten shortcut is
 * worse than one extra line of text.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppShortcutCaptureDialog(
    existing: List<AppShortcutEntry>,
    onDismiss: () -> Unit,
    onSave: (AppShortcutEntry) -> Unit
) {
    var capturedKeyCode by remember { mutableStateOf<Int?>(null) }
    var capturedMeta by remember { mutableStateOf(0) }
    var selectedAction by remember { mutableStateOf<AppAction?>(null) }
    var capturing by remember { mutableStateOf(true) }
    val focusRequester = remember { FocusRequester() }

    val conflict = capturedKeyCode?.let { code ->
        existing.find { it.keyCode == code && it.normalizedMeta == (capturedMeta and AppShortcutEntry.RELEVANT_META_MASK) }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New app shortcut") },
        text = {
            Column {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                        .focusable()
                        .onKeyEvent { keyEvent ->
                            if (!capturing) return@onKeyEvent false
                            val native = keyEvent.nativeKeyEvent
                            if (native.action != KeyEvent.ACTION_DOWN) return@onKeyEvent false
                            // Ignore a bare modifier press - wait for the
                            // real key so e.g. holding Ctrl alone doesn't
                            // get captured as the whole shortcut.
                            when (native.keyCode) {
                                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
                                KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
                                KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
                                KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT ->
                                    return@onKeyEvent true
                                else -> {}
                            }
                            capturedKeyCode = native.keyCode
                            capturedMeta = native.metaState
                            capturing = false
                            true
                        }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        when {
                            capturing -> "Press a key combo…"
                            else -> formatShortcutCombo(capturedKeyCode!!, capturedMeta)
                        },
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                if (!capturing) {
                    TextButton(onClick = {
                        capturedKeyCode = null
                        capturedMeta = 0
                        capturing = true
                    }) {
                        Text("Capture again")
                    }
                    if (conflict != null) {
                        Text(
                            "Already bound to \"${conflict.action.label}\" - saving will replace it.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))
                    Text("Action", style = MaterialTheme.typography.labelLarge)
                    Spacer(modifier = Modifier.height(4.dp))

                    Column(modifier = Modifier.heightIn(max = 280.dp).verticalScrollWorkaround()) {
                        AppAction.groupsInOrder().forEach { group ->
                            Text(
                                group,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 8.dp, bottom = 2.dp)
                            )
                            AppAction.entries.filter { it.group == group }.forEach { action ->
                                ListItem(
                                    headlineContent = { Text(action.label) },
                                    leadingContent = {
                                        RadioButton(
                                            selected = selectedAction == action,
                                            onClick = { selectedAction = action }
                                        )
                                    },
                                    modifier = Modifier.clickable { selectedAction = action }
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    val code = capturedKeyCode
                    val action = selectedAction
                    if (code != null && action != null) {
                        val id = conflict?.id ?: UUID.randomUUID().toString()
                        onSave(AppShortcutEntry(id = id, keyCode = code, metaState = capturedMeta, action = action))
                    }
                },
                enabled = capturedKeyCode != null && selectedAction != null
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }
}

/** Small helper so the action picker list scrolls within its capped
 *  height inside the AlertDialog instead of pushing the dialog itself
 *  off-screen on smaller devices - a plain verticalScroll import kept
 *  local to this file since nothing else here needs it. */
@Composable
private fun Modifier.verticalScrollWorkaround(): Modifier {
    val scrollState = rememberScrollState()
    return this.then(Modifier.verticalScroll(scrollState))
}

