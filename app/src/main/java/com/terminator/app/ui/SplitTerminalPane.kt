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
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.terminator.emulator.TerminalBuffer
import com.terminator.emulator.TerminalPalette
import com.terminator.emulator.TerminalView

/**
 * The bar between the primary and secondary split panes. A thin drag
 * target reporting raw pixel deltas + the container's measured height, so
 * the caller (MainActivity) can convert to a ratio itself using the same
 * measured size it already tracks for the primary pane, rather than this
 * composable trying to independently measure or own the split geometry.
 */
@Composable
fun SplitDragHandle(onDrag: (deltaPx: Float, containerHeightPx: Float) -> Unit) {
    var containerHeightPx by remember { mutableStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .background(Color(0xFF1A1A1A))
            .pointerInput(Unit) {
                containerHeightPx = size.height.toFloat()
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    // Use this pointerInput's own full-screen-ish parent height as
                    // the ratio denominator - approximate but stable, and avoids
                    // needing a second onSizeChanged just for the handle itself.
                    onDrag(dragAmount, containerHeightPx.takeIf { it > 0f } ?: 1000f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .size(width = 36.dp, height = 4.dp)
                .background(Color.White.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(2.dp))
        )
    }
}

/**
 * Split-screen's secondary pane: its own TerminalView bound to a different
 * running session's buffer, a small header (session label, broadcast-input
 * toggle, close button) and a plain text field for input - see this
 * file's header doc on why this is deliberately simpler than the primary
 * pane's gesture stack rather than sharing it.
 */
@Composable
fun SplitTerminalPane(
    modifier: Modifier = Modifier,
    runtimeId: String,
    buffer: TerminalBuffer?,
    bufferVersion: Int,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    broadcastInput: Boolean,
    onToggleBroadcast: () -> Unit,
    onInput: (String) -> Unit,
    onClose: () -> Unit
) {
    var inputText by remember(runtimeId) { mutableStateOf("") }

    Box(modifier = modifier.background(Color.Black)) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            // Header: session id (short), broadcast toggle, close.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(
                    "Split pane",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Broadcast toggle lives here (not in Settings) because
                    // it's meaningful only while a split is actually open -
                    // see MainUiState.broadcastInput's doc. Highlighted
                    // when on so it's obvious typing now reaches both panes.
                    IconButton(onClick = onToggleBroadcast, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.SyncAlt,
                            contentDescription = if (broadcastInput) {
                                "Broadcast input: on - typing reaches both panes"
                            } else {
                                "Broadcast input: off - typing reaches only the primary pane"
                            },
                            tint = if (broadcastInput) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close split",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                if (buffer != null) {
                    TerminalView(
                        buffer = buffer,
                        palette = palette,
                        fontFamily = fontFamily,
                        fontSizeSp = fontSizeSp,
                        bufferVersion = bufferVersion,
                        backgroundAlpha = 1f,
                        scrollOffset = 0,
                        selectionStart = null,
                        selectionEnd = null,
                        modifier = Modifier.fillMaxWidth().fillMaxHeight()
                    )
                } else {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Session ended",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }

            // Plain text input row - intentionally not the primary pane's
            // hidden-field + soft-keyboard-tracking setup (see this file's
            // header doc). Enter sends the line plus a newline, matching
            // what pressing Enter in a real terminal does.
            androidx.compose.material3.OutlinedTextField(
                value = inputText,
                onValueChange = { inputText = it },
                modifier = Modifier.fillMaxWidth().padding(4.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp, color = Color.White),
                placeholder = { Text("Type here...", style = MaterialTheme.typography.bodySmall) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(
                    onSend = {
                        onInput(inputText + "\n")
                        inputText = ""
                    }
                )
            )
        }
    }
}
