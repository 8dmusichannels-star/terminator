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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Appears above the terminal once a long-press has started a text
 * selection (see the gesture loop in MainActivity). Deliberately a plain
 * Compose row rather than the native Android ActionMode/floating toolbar -
 * the terminal is a single Canvas with no real text layout underneath it
 * for ActionMode to anchor to, so this is the equivalent affordance built
 * directly on top of the (row, col) selection range MainActivity already
 * tracks.
 */
@Composable
fun SelectionToolbar(
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    // Optional "+" placed in front of Copy/Paste/Cancel. Unlike the
    // titlebar's "+" (which opens QuickAddSessionPickerDialog so the user
    // picks which running session to clone), this one is a fast path: no
    // popup, tapping it clones whichever session is active right now. Null
    // hides the button entirely, keeping every other caller of this
    // toolbar unaffected.
    onCloneClicked: (() -> Unit)? = null,
    // Save/export icon for the active session's full terminal output
    // (screen + scrollback) - see MainViewModel.exportSessionOutput's doc.
    // Nullable so other/future callers of this toolbar can still hide it,
    // but MainActivity always passes a non-null handler.
    onSaveHistoryClicked: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF262626)),
    ) {
        if (onCloneClicked != null) {
            ToolbarAction("+", onCloneClicked)
        }
        ToolbarAction("Copy", onCopy)
        ToolbarAction("Paste", onPaste)
        ToolbarAction("Cancel", onCancel)
        if (onSaveHistoryClicked != null) {
            Icon(
                Icons.Filled.Save,
                contentDescription = "Export terminal output",
                tint = Color.White,
                modifier = Modifier
                    .clickable(onClick = onSaveHistoryClicked)
                    .padding(horizontal = 14.dp, vertical = 10.dp)
                    .size(20.dp)
            )
        }
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
