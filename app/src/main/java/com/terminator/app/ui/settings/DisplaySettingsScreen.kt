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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.datastore.preferences.core.Preferences
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.settings.SettingsRepository
import kotlinx.coroutines.launch

/**
 * One row's worth of a Display toggle: the persisted key, its default,
 * label, and optional helper text. Adding a new on/off Display setting
 * means adding one entry here - not copy-pasting a Switch+Text+Spacer
 * block like this screen used to require for every single toggle.
 */
private data class DisplayToggleItem(
    val key: Preferences.Key<Boolean>,
    val default: Boolean,
    val label: String,
    val description: String? = null
)

private val DISPLAY_TOGGLES = listOf(
    DisplayToggleItem(
        key = SettingsKeys.SHOW_STATUSBAR,
        default = false,
        label = "Show statusbar",
        description = "Applies consistently in both portrait and landscape."
    ),
    DisplayToggleItem(
        key = SettingsKeys.SHOW_TITLEBAR,
        default = true,
        label = "Show titlebar"
    ),
    DisplayToggleItem(
        key = SettingsKeys.HORIZONTAL_MODE,
        default = true,
        label = "Horizontal (landscape) mode"
    ),
    DisplayToggleItem(
        key = SettingsKeys.SHOW_RUNNER_TOOLBAR_SAVE,
        default = true,
        label = "Show runner toolbar save button",
        description = "Shows/hides the Save (export) icon on each running session's row in the drawer."
    ),
    DisplayToggleItem(
        key = SettingsKeys.SPLIT_SCREEN_VISIBLE,
        default = true,
        label = "Split screen visibility",
        description = "Shows/hides the split-screen button. Only affects the button itself - " +
            "an already-open split stays open even if you turn this off."
    ),
    DisplayToggleItem(
        key = SettingsKeys.BROADCAST_ALL_PANES,
        default = false,
        label = "Broadcast to all panes",
        description = "When multiple panes are open (multi-pane mode), typing reaches every visible pane at once. " +
            "When off, typing only reaches whichever pane you last tapped to focus."
    ),
    DisplayToggleItem(
        key = SettingsKeys.SHOW_CLEAR_ALL_SESSIONS_BUTTON,
        default = false,
        label = "Show \"All clear session\" button",
        description = "Shows a button in the session area that kills every running session at once. " +
            "Saved session profiles aren't affected - only what's currently running."
    )
)

/**
 * Every toggle here is driven off [DISPLAY_TOGGLES] and rendered by
 * [DisplayToggleRow] - the list is the single source of truth for both
 * order and content, so adding/reordering a Display setting is a one-line
 * change instead of touching layout code.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display") },
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
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(DISPLAY_TOGGLES, key = { it.label }) { item ->
                DisplayToggleRow(item = item, repo = repo, scope = scope)
                Spacer(modifier = Modifier.height(16.dp))
            }
        }
    }
}

@Composable
private fun DisplayToggleRow(
    item: DisplayToggleItem,
    repo: SettingsRepository,
    scope: kotlinx.coroutines.CoroutineScope
) {
    val checked by repo.flow(item.key, item.default).collectAsState(initial = item.default)

    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(modifier = Modifier.weight(1f)) {
            Text(item.label, style = MaterialTheme.typography.bodyLarge)
            item.description?.let {
                Text(it, style = MaterialTheme.typography.bodySmall)
            }
        }
        Switch(
            checked = checked,
            onCheckedChange = { newValue -> scope.launch { repo.set(item.key, newValue) } }
        )
    }
}
