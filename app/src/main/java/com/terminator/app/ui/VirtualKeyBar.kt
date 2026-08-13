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

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Termux-style 2-row virtual key bar.
 * Row 1: ESC, /, —, HOME, ↑, END, PGUP
 * Row 2: TAB(⇆), CTRL, ALT, ←, ↓, →, PGDN
 * Horizontally scrollable if it doesn't fit the screen width.
 *
 * The bar has exactly one of two pages visible at a time, never both:
 *  - Key rows page (default).
 *  - Long-text entry page, for typing/pasting a longer string in one shot.
 * Swiping right past the end of the key rows swaps to the text page;
 * swiping left on the text page swaps back. Purely gesture-driven, same as
 * flipping a card - no visible toggle button.
 */
enum class VirtualKey(val label: String, val sendSequence: String) {
    ESC("ESC", "\u001B"),
    SLASH("/", "/"),
    DASH("—", "-"),
    HOME("HOME", "\u001B[H"),
    UP("↑", "\u001B[A"),
    END("END", "\u001B[F"),
    PGUP("PGUP", "\u001B[5~"),
    TAB("⇆", "\t"),
    CTRL("CTRL", ""), // modifier - handled as toggle state, not a direct sequence
    ALT("ALT", ""),   // modifier - handled as toggle state
    LEFT("←", "\u001B[D"),
    DOWN("↓", "\u001B[B"),
    RIGHT("→", "\u001B[C"),
    PGDN("PGDN", "\u001B[6~")
}

private val row1 = listOf(
    VirtualKey.ESC, VirtualKey.SLASH, VirtualKey.DASH, VirtualKey.HOME,
    VirtualKey.UP, VirtualKey.END, VirtualKey.PGUP
)
private val row2 = listOf(
    VirtualKey.TAB, VirtualKey.CTRL, VirtualKey.ALT, VirtualKey.LEFT,
    VirtualKey.DOWN, VirtualKey.RIGHT, VirtualKey.PGDN
)

@Composable
fun VirtualKeyBar(
    onKeyPressed: (VirtualKey) -> Unit,
    onTextSubmitted: (String) -> Unit = {},
    ctrlActive: Boolean = false,
    altActive: Boolean = false,
    // The hidden terminal input field loses focus the moment the user taps
    // into this bar's own long-text OutlinedTextField (a different IME
    // connection takes over). Focus here doesn't drive MainActivity's "is
    // the keyboard open" state directly anymore (that's WindowInsets.ime
    // now - see its doc), but the toolbar's Copy/Paste/Cancel callbacks
    // still need to know which field to send focus back to, so this
    // callback keeps MainActivity informed of this field's focus for that
    // purpose.
    onTextFieldFocusChanged: (Boolean) -> Unit = {},
    // Mirrors the open-path's textFieldFocusRequester.requestFocus() call
    // below, for the close path. Swiping back to the key-rows page removes
    // the long-text OutlinedTextField (the only focused element) from
    // composition without anything else claiming focus, so the real IME
    // starts hiding on its own with no field to reopen it for - which is
    // what surfaced as the keyboard flickering shut and reopening by
    // itself right after a swipe-back ("kendi kendine kaybolup geri
    // geliyor"). The caller uses this to hand focus straight back to the
    // hidden terminal input field instead of leaving it unclaimed.
    onTextEntryClosed: () -> Unit = {},
    // Settings > Keyboard > "Keyboard shortcuts & keymapper" entries. Each
    // one was previously saved to disk and never surfaced anywhere - tapping
    // its chip here is what actually sends its key combo to the terminal.
    keymaps: List<com.terminator.app.ui.settings.KeymapEntry> = emptyList(),
    onKeymapTriggered: (com.terminator.app.ui.settings.KeymapEntry) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var textEntryOpen by remember { mutableStateOf(false) }
    var textEntryValue by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val revealThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    // Requests focus on the text-entry field every time the page opens -
    // see the LaunchedEffect below for why this exists.
    val textFieldFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // Opening the text page via swipe never itself focused the field it
    // just revealed - only an explicit tap on the OutlinedTextField did.
    // Without an input connection, the system has no reason to show the
    // IME, so MainActivity's keyboardOpen (now driven by the real
    // WindowInsets.ime inset rather than focus tracking - see its own doc)
    // correctly read false right after a swipe-reopen, and this whole
    // bar's visibility (keyed on keyboardOpen) hid the page it had just
    // shown - the "sola cevirib tekrar geri dondugunde keyboard kendi
    // kendine kayboluyor" bug. Explicitly requesting focus on every open
    // gives the field an input connection immediately, so the system
    // actually has something to show a keyboard for.
    LaunchedEffect(textEntryOpen) {
        if (textEntryOpen) {
            textFieldFocusRequester.requestFocus()
        } else {
            onTextEntryClosed()
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
    ) {
        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))

        // Was a hard if/else cut before - AnimatedContent gives the page
        // swap an actual card-flip slide instead of an instant swap, and
        // slides in the same direction as the drag gesture that triggered
        // it (right-drag reveals the text page sliding in from the right;
        // left-drag reveals the key rows sliding back in from the left).
        AnimatedContent(
            targetState = textEntryOpen,
            transitionSpec = {
                // Was 220ms - felt sluggish for something that's supposed to
                // be a quick, reflexive flip. 120ms with a snappier easing
                // reads as immediate without being jarring.
                val forward = targetState // true = opening the text page
                if (forward) {
                    (slideInHorizontally(tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { width -> width } + fadeIn(tween(120))) togetherWith
                        (slideOutHorizontally(tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { width -> -width } + fadeOut(tween(120)))
                } else {
                    (slideInHorizontally(tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { width -> -width } + fadeIn(tween(120))) togetherWith
                        (slideOutHorizontally(tween(120, easing = androidx.compose.animation.core.FastOutSlowInEasing)) { width -> width } + fadeOut(tween(120)))
                }
            },
            label = "virtualKeyBarPageSwap"
        ) { showTextPage ->
            if (!showTextPage) {
                // Key rows page. Once scrolled all the way to the right edge,
                // continuing to drag right swaps the whole bar to the text page.
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .pointerInput(Unit) {
                            // Manual pan instead of Modifier.horizontalScroll: once
                            // the row is at its scroll boundary, extra drag in that
                            // direction accumulates as "overscroll" instead of being
                            // silently absorbed - that overscroll is what swaps to
                            // the text page. Normal scrolling and the swap gesture
                            // share the same continuous drag.
                            var overscroll = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { overscroll = 0f },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    // dispatchRawDelta(-delta) is what makes a
                                    // right-finger-drag (delta > 0) reveal keys
                                    // further to the LEFT of the row and a
                                    // left-finger-drag (delta < 0) reveal keys
                                    // further to the RIGHT - the normal
                                    // "drag content under your finger" mapping
                                    // any horizontalScroll-backed row uses. Once
                                    // already scrolled all the way to that right
                                    // edge (atEnd), continuing to drag further in
                                    // that same reveal-more-to-the-right
                                    // direction - i.e. still dragging LEFT,
                                    // delta < 0 - has nothing left to scroll to,
                                    // so that's the natural point to treat it as
                                    // "swipe past the end" and accumulate
                                    // overscroll toward opening the text page.
                                    // This used to check delta > 0 (a
                                    // right-finger-drag) instead, which is the
                                    // opposite direction from the one that
                                    // actually runs out of row to scroll -
                                    // dragging right at atEnd just re-scrolls
                                    // back toward the start (always available,
                                    // so overscroll could never accumulate),
                                    // while the genuine "nothing left to scroll"
                                    // drag (left) fell into the plain-scroll
                                    // else branch and silently did nothing. It
                                    // also made the open gesture point the
                                    // opposite way from the text page's own
                                    // close gesture below (drag < 0 to close),
                                    // which is the inconsistent-feeling half of
                                    // this bug.
                                    val atEnd = scrollState.value >= scrollState.maxValue
                                    if (atEnd && delta < 0) {
                                        overscroll += -delta
                                    } else {
                                        overscroll = 0f
                                        scrollState.dispatchRawDelta(-delta)
                                    }
                                },
                                onDragEnd = {
                                    if (overscroll > revealThresholdPx) {
                                        textEntryOpen = true
                                    }
                                    overscroll = 0f
                                }
                            )
                        }
                ) {
                    VirtualKeyRow(row1, onKeyPressed, ctrlActive, altActive, scrollState)
                    VirtualKeyRow(row2, onKeyPressed, ctrlActive, altActive, scrollState)
                    if (keymaps.isNotEmpty()) {
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            keymaps.forEach { entry ->
                                TextButton(onClick = { onKeymapTriggered(entry) }) {
                                    Text(
                                        entry.name,
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.secondary
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                // Long-text entry page - replaces the key rows entirely while
                // open, never rendered alongside them.
                //
                // The swipe-to-close gesture used to be attached to this
                // entire Row, including the OutlinedTextField itself. Tapping
                // into the field to type opens the IME, which resizes/shifts
                // this row's layout mid-touch - the still-active drag
                // detector picked that layout shift up as a large horizontal
                // drag and immediately closed the page out from under the
                // user's finger (the "kayboluyor" bug). The fix: the gesture
                // now lives only on a dedicated leading handle, never on the
                // text field's own touch area, so focusing/typing in the
                // field can never be misread as a swipe.
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(
                        onClick = { textEntryOpen = false },
                        modifier = Modifier
                            .pointerInput(Unit) {
                                var drag = 0f
                                detectHorizontalDragGestures(
                                    onDragStart = { drag = 0f },
                                    onHorizontalDrag = { change, delta ->
                                        change.consume()
                                        drag += delta
                                    },
                                    onDragEnd = {
                                        if (drag < -revealThresholdPx / 2) {
                                            textEntryOpen = false
                                        }
                                        drag = 0f
                                    }
                                )
                            }
                    ) {
                        Icon(
                            Icons.Filled.KeyboardArrowLeft,
                            contentDescription = "Back to keys",
                            tint = MaterialTheme.colorScheme.primary
                        )
                    }
                    OutlinedTextField(
                        value = textEntryValue,
                        onValueChange = { textEntryValue = it },
                        modifier = Modifier
                            .weight(1f)
                            .focusRequester(textFieldFocusRequester)
                            .onFocusChanged { onTextFieldFocusChanged(it.isFocused) },
                        placeholder = { Text("Type or paste text…") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                        keyboardActions = KeyboardActions(onSend = {
                            if (textEntryValue.isNotEmpty()) {
                                onTextSubmitted(textEntryValue)
                                textEntryValue = ""
                            }
                        })
                    )
                    IconButton(onClick = {
                        if (textEntryValue.isNotEmpty()) {
                            onTextSubmitted(textEntryValue)
                            textEntryValue = ""
                        }
                    }) {
                        Icon(Icons.Filled.Send, contentDescription = "Send text")
                    }
                }
            }
        }
    }
}

@Composable
private fun VirtualKeyRow(
    keys: List<VirtualKey>,
    onKeyPressed: (VirtualKey) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    scrollState: androidx.compose.foundation.ScrollState
) {
    // In landscape there's a lot less vertical room (titlebar + 2 key rows +
    // IME can easily eat most of the screen), so the two rows shrink down
    // instead of keeping the same tall portrait-sized buttons.
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val verticalPadding = if (isLandscape) 0.dp else 8.dp
    val fontSize = if (isLandscape) 12.sp else MaterialTheme.typography.bodyMedium.fontSize

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState, enabled = false),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        keys.forEach { key ->
            // CTRL/ALT are one-shot toggles (tap CTRL, then tap a regular
            // key on the real keyboard) - highlight them while "held" so
            // there's visible feedback that the modifier is armed.
            val isActive = (key == VirtualKey.CTRL && ctrlActive) || (key == VirtualKey.ALT && altActive)
            TextButton(
                onClick = { onKeyPressed(key) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 10.dp, vertical = verticalPadding
                ),
                modifier = Modifier.background(
                    if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f) else Color.Transparent
                )
            ) {
                Text(
                    key.label,
                    fontSize = fontSize,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}
