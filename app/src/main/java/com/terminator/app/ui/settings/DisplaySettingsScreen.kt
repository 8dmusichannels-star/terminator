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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/**
 * Show/hide statusbar (single toggle, consistent in both portrait and
 * landscape - no separate horizontal-mode setting needed), show/hide
 * titlebar, and enable/disable horizontal (landscape) mode itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val showStatusbar by repo.flow(SettingsKeys.SHOW_STATUSBAR, false).collectAsState(initial = false)
    val showTitlebar by repo.flow(SettingsKeys.SHOW_TITLEBAR, true).collectAsState(initial = true)
    val horizontalModeEnabled by repo.flow(SettingsKeys.HORIZONTAL_MODE, true).collectAsState(initial = true)
    val showRunnerToolbarSave by repo.flow(SettingsKeys.SHOW_RUNNER_TOOLBAR_SAVE, true).collectAsState(initial = true)
    val splitScreenVisible by repo.flow(SettingsKeys.SPLIT_SCREEN_VISIBLE, true).collectAsState(initial = true)
    val broadcastAllPanes by repo.flow(SettingsKeys.BROADCAST_ALL_PANES, false).collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SwitchRow("Show statusbar", showStatusbar) {
                scope.launch { repo.set(SettingsKeys.SHOW_STATUSBAR, it) }
            }
            Text(
                "Applies consistently in both portrait and landscape.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Show titlebar", showTitlebar) {
                scope.launch { repo.set(SettingsKeys.SHOW_TITLEBAR, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Horizontal (landscape) mode", horizontalModeEnabled) {
                scope.launch { repo.set(SettingsKeys.HORIZONTAL_MODE, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Show runner toolbar save button", showRunnerToolbarSave) {
                scope.launch { repo.set(SettingsKeys.SHOW_RUNNER_TOOLBAR_SAVE, it) }
            }
            Text(
                "Shows/hides the Save (export) icon on each running session's row in the drawer.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Split screen visibility", splitScreenVisible) {
                scope.launch { repo.set(SettingsKeys.SPLIT_SCREEN_VISIBLE, it) }
            }
            Text(
                "Shows/hides the split-screen button. Only affects the button itself - " +
                    "an already-open split stays open even if you turn this off.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Broadcast to all panes", broadcastAllPanes) {
                scope.launch { repo.set(SettingsKeys.BROADCAST_ALL_PANES, it) }
            }
            Text(
                "When multiple panes are open (multi-pane mode), typing reaches every visible pane at once. " +
                    "When off, typing only reaches whichever pane you last tapped to focus.",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
