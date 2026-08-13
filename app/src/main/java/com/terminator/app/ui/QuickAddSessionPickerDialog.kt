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

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Popup shown when the titlebar "+" is tapped, listing every currently
 * RUNNING session (as opposed to the saved session profiles in the drawer)
 * so the user can pick exactly which live instance to clone.
 *
 * Previously "+" just called duplicateActiveSession(), which silently
 * cloned whatever the active session happened to be with no confirmation -
 * if the user had several sessions open and meant to duplicate a different
 * one than whichever was on-screen at the moment, tapping "+" cloned the
 * wrong one instead. This dialog surfaces the actual choice: it lists every
 * running session (centered popup, per Material3's default AlertDialog
 * placement) and only clones the one the user taps. Exited sessions are
 * excluded - there's nothing live left in them to clone. If nothing is
 * running yet, the caller falls back to spawning the default session
 * directly instead of showing an empty, useless list (see MainActivity's
 * onQuickAddClicked wiring).
 */
@Composable
fun QuickAddSessionPickerDialog(
    runningSessions: List<RunningSession>,
    onSessionPicked: (String) -> Unit,
    onDismissRequest: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismissRequest,
        title = { Text("Clone which session?") },
        text = {
            // Fixed max height would be nicer for a very long list, but
            // AlertDialog already caps its own content height and scrolls -
            // LazyColumn here just keeps a long running-session list itself
            // scrollable within that.
            LazyColumn {
                items(
                    items = runningSessions,
                    key = { it.runtimeId }
                ) { running ->
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSessionPicked(running.runtimeId) }
                            .padding(horizontal = 8.dp, vertical = 12.dp)
                    ) {
                        Text(running.label, style = MaterialTheme.typography.bodyLarge)
                        if (running.exited) {
                            Text(
                                "Session exited",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFBF616A)
                            )
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismissRequest) {
                Text("Cancel")
            }
        }
    )
}
