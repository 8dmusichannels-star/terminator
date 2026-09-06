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

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/**
 * Soft keyboard / virtual key bar toggles, Input Mode (Termux-style 3-way
 * choice for IME compatibility), keyboard shortcuts + keymapper (custom
 * key-combo -> action mapping), and the SECCOMP workaround toggle for
 * "Operation not permitted" errors caused by kernel syscall filters.
 *
 * "Soft keyboard" here just enables/disables the tap-to-toggle behavior on
 * the main terminal screen - the actual show/hide happens per-tap there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val softKeyboard by repo.flow(SettingsKeys.SOFT_KEYBOARD, true).collectAsState(initial = true)
    val virtualKeys by repo.flow(SettingsKeys.VIRTUAL_KEYS, true).collectAsState(initial = true)
    val keymapperEnabled by repo.flow(SettingsKeys.KEYMAPPER_ENABLED, true).collectAsState(initial = true)
    val physicalKeyRepeatEnabled by repo.flow(SettingsKeys.PHYSICAL_KEY_REPEAT_ENABLED, true).collectAsState(initial = true)
    val inputMode by repo.flow(SettingsKeys.INPUT_MODE, "Default").collectAsState(initial = "Default")
    val seccompEnabled by repo.flow(SettingsKeys.SECCOMP_ENABLED, false).collectAsState(initial = false)
    // Default is NONE (see TERM_TYPE_OPTIONS' own doc below, and
    // buildEnvironment's own doc in TerminalSession, for why that means
    // "don't set $TERM" rather than literally "TERM=NONE"). The
    // stringPreferencesKey default only ever applies to a key that was
    // never written, so this doesn't change what an existing user who
    // already picked xterm-256color/vt100/ansi sees.
    val termType by repo.flow(SettingsKeys.TERM_TYPE, "NONE").collectAsState(initial = "NONE")
    var showKeymapper by remember { mutableStateOf(false) }
    var showTermTypeDialog by remember { mutableStateOf(false) }

    if (showKeymapper) {
        KeymapperScreen(onBack = { showKeymapper = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard") },
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
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SwitchRow("Soft keyboard (tap terminal to open/close)", softKeyboard) {
                scope.launch { repo.set(SettingsKeys.SOFT_KEYBOARD, it) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Virtual keys (key bar)", virtualKeys) {
                scope.launch { repo.set(SettingsKeys.VIRTUAL_KEYS, it) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Independent of "Virtual keys" above - the keymap row used to be
            // tied to the key bar's own visibility, so there was no way to
            // keep keyboard shortcuts working while hiding the bar itself.
            SwitchRow("Keyboard shortcuts & keymapper", keymapperEnabled) {
                scope.launch { repo.set(SettingsKeys.KEYMAPPER_ENABLED, it) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            // Local override only - a running program's own DECARM state
            // (CSI ?8 h/l) is untouched by this; see
            // SettingsKeys.PHYSICAL_KEY_REPEAT_ENABLED's own doc.
            SwitchRow("Physical keyboard key repeat", physicalKeyRepeatEnabled) {
                scope.launch { repo.set(SettingsKeys.PHYSICAL_KEY_REPEAT_ENABLED, it) }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Input Mode", style = MaterialTheme.typography.labelLarge)

            InputModeOption(
                title = "Default (Recommended)",
                description = "Compatible with all IMEs including CJK input",
                selected = inputMode == "Default"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Default") } }

            InputModeOption(
                title = "Strict Terminal",
                description = "Semantically correct but may break some IMEs",
                selected = inputMode == "Strict"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Strict") } }

            InputModeOption(
                title = "Legacy Workaround",
                description = "Fixes Samsung keyboard echo; may break Gboard CJK input",
                selected = inputMode == "Legacy"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Legacy") } }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Terminal Type (TERM)", style = MaterialTheme.typography.labelLarge)
            Text(
                "What full-screen apps (nano, vim, htop...) see as \$TERM. " +
                    "Only takes effect for sessions started after changing it.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(
                onClick = { showTermTypeDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (termType == "NONE") "NONE (don't set \$TERM)" else termType)
            }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { showKeymapper = true }) {
                Text("Keyboard shortcuts & keymapper")
            }

            Spacer(modifier = Modifier.height(24.dp))
            SwitchRow("SECCOMP", seccompEnabled) {
                scope.launch { repo.set(SettingsKeys.SECCOMP_ENABLED, it) }
            }
            Text(
                "Fix \"operation not permitted\" error",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    if (showTermTypeDialog) {
        TermTypeDialog(
            selected = termType,
            onSelect = {
                scope.launch { repo.set(SettingsKeys.TERM_TYPE, it) }
                showTermTypeDialog = false
            },
            onDismiss = { showTermTypeDialog = false }
        )
    }
}

// Full $TERM picker list - every entry a program is realistically going to
// check for/recognize via terminfo, roughly ordered by how likely a user is
// to want it: NONE (don't set $TERM - the default, see TerminalSession.
// buildEnvironment's own doc for why that's different from literally
// setting "TERM=NONE"), the xterm family (plain/color/256color/kitty),
// screen/tmux (+ their 256color variants, for the common "TERM already set
// by an outer multiplexer" case), then the older vt220/vt100/ANSI entries
// for programs or devices with no modern terminfo available at all.
private val TERM_TYPE_OPTIONS = listOf(
    "NONE", "xterm", "xterm-color", "xterm-256color", "screen",
    "screen-256color", "tmux-256color", "xterm-kitty", "tmux", "vt220",
    "vt100", "ANSI"
)

/**
 * Popup (was previously an inline radio list directly on this screen) so
 * the growing 3->12-entry $TERM list doesn't push every other Keyboard
 * setting further down the page. Dismissible via scrim tap/back without
 * picking anything, same as [AlertDialog]'s default onDismissRequest
 * behavior - the screen's own current [selected] value is left untouched
 * either way.
 */
@Composable
private fun TermTypeDialog(
    selected: String,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Terminal Type (TERM)") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TERM_TYPE_OPTIONS.forEach { option ->
                    InputModeOption(
                        title = if (option == "NONE") "NONE (don't set \$TERM)" else option,
                        description = termTypeDescription(option),
                        selected = selected == option
                    ) { onSelect(option) }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        }
    )
}

// Short per-entry blurb shown under each TERM_TYPE_OPTIONS row - kept as
// its own function rather than zipped inline into the list above so the
// option identifiers themselves (what actually gets written to
// SettingsKeys.TERM_TYPE / passed as $TERM) stay a plain, easily-diffable
// list of strings.
private fun termTypeDescription(option: String): String = when (option) {
    "NONE" -> "Leave \$TERM unset - whatever the shell/exec environment would otherwise provide"
    "xterm" -> "Base xterm entry, no 256-color extension"
    "xterm-color" -> "xterm with basic (16) color support"
    "xterm-256color" -> "Full color and feature support; uses this app's bundled terminfo entry"
    "screen" -> "For running inside GNU screen, no 256-color extension"
    "screen-256color" -> "GNU screen with 256-color support"
    "tmux-256color" -> "tmux with 256-color support"
    "xterm-kitty" -> "For kitty-protocol-aware programs (kitty graphics/keyboard protocol)"
    "tmux" -> "For running inside tmux, no 256-color extension"
    "vt220" -> "Older DEC VT220 compatibility"
    "vt100" -> "Minimal, near-universal - works even without a terminfo entry"
    "ANSI" -> "Basic ANSI/DOS-style compatibility, no color extensions"
    else -> ""
}

@Composable
private fun InputModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
