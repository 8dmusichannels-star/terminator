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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalMinimumInteractiveComponentEnforcement
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
import androidx.compose.ui.platform.LocalFocusManager
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
    PGDN("PGDN", "\u001B[6~"),
    // Not on any mobile IME (only CTRL/ALT/DEL share that gap) - VT
    // sequence for Insert, same tilde-terminated family as PGUP/PGDN.
    INSERT("INS", "\u001B[2~")
}

private val row1 = listOf(
    VirtualKey.ESC, VirtualKey.SLASH, VirtualKey.DASH, VirtualKey.HOME,
    VirtualKey.UP, VirtualKey.END, VirtualKey.PGUP
)
private val row2 = listOf(
    VirtualKey.TAB, VirtualKey.INSERT, VirtualKey.CTRL, VirtualKey.ALT, VirtualKey.LEFT,
    VirtualKey.DOWN, VirtualKey.RIGHT, VirtualKey.PGDN
)

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun VirtualKeyBar(
    onKeyPressed: (VirtualKey) -> Unit,
    onTextSubmitted: (String) -> Unit = {},
    modifier: Modifier = Modifier,
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
    // Opens the same session drawer as the titlebar hamburger / right-drag
    // gesture (see MainActivity's onMenuClicked wiring). Placed right next
    // to ESC on the key-rows page so the drawer is reachable without
    // needing the titlebar to be visible or reaching across for the edge
    // swipe - purely an extra entry point into the existing drawer, the
    // drawer's own contents/behavior are untouched.
    onMenuClicked: () -> Unit = {}
) {
    var textEntryOpen by remember { mutableStateOf(false) }
    var textEntryValue by remember { mutableStateOf("") }
    val revealThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }
    // Requests focus on the text-entry field every time the page opens -
    // see the LaunchedEffect below for why this exists.
    val textFieldFocusRequester = remember { androidx.compose.ui.focus.FocusRequester() }
    // See the LaunchedEffect(textEntryOpen) close branch below for why
    // this is needed - clearing this field's own Compose focus explicitly,
    // synchronously, right when the swipe-back starts, rather than passively
    // waiting for it to leave composition at the end of the exit animation.
    val focusManager = LocalFocusManager.current
    // Guards the close-branch logic below so it only ever runs after a
    // REAL open->close transition, never on the very first composition.
    // LaunchedEffect(textEntryOpen) fires on mount too, with textEntryOpen
    // at its default (false) - that "close" branch used to run unconditionally,
    // which meant every time this whole bar entered composition (it's
    // conditionally shown/hidden on keyboardOpen at the call site - see
    // MainActivity), it force-cleared focus from whatever field the user
    // had just tapped into a moment earlier, before the real IME had even
    // finished opening for it. That's what turned into "primaryde IME hiç
    // açılmıyor, dokununca kendi kendine kapanıyor" - a tap opened the
    // keyboard -> this bar mounted -> its very first LaunchedEffect run
    // yanked focus straight back off, closing what had just opened.
    var everOpened by remember { mutableStateOf(false) }
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
            everOpened = true
            textFieldFocusRequester.requestFocus()
        } else if (everOpened) {
            // Force this field to give up Compose focus right now, instead
            // of leaving it focused for the rest of the exit animation and
            // making the caller's replacement field wait it out with an
            // artificial delay (see PaneContent's/SplitTerminalPane's own
            // focusRequestSignal doc for that old workaround). A still-
            // focused outgoing field is exactly what was making the real
            // IME visibly close and reopen on its own after a swipe-back -
            // force=true here so it lets go even while still composed and
            // mid-animation, so whichever field the caller focuses next
            // (from onTextEntryClosed(), fired immediately after) can claim
            // the window's one IME connection with no focus-less gap in
            // between for the system to animate a close/reopen for.
            focusManager.clearFocus(force = true)
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
                            // Both key rows now always fit the full screen
                            // width (see FirstKeyRowWithMenuButton's doc -
                            // every key gets an equal weight() share of the
                            // row instead of its own intrinsic width), so
                            // there's no more "scrolled to the edge" state
                            // to detect: there's nothing left to scroll to
                            // reveal. Any leftward drag on this page is
                            // already at that edge, so it goes straight
                            // into accumulating overscroll toward opening
                            // the text page - rightward drag just resets
                            // it, same as before.
                            var overscroll = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { overscroll = 0f },
                                onHorizontalDrag = { change, delta ->
                                    change.consume()
                                    if (delta < 0) {
                                        overscroll += -delta
                                    } else {
                                        overscroll = 0f
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
                    // Material3's TextButton enforces its own ~40dp minimum
                    // touch target regardless of contentPadding, silently
                    // re-padding a button back up to that floor even when
                    // FirstKeyRowWithMenuButton/VirtualKeyRow ask for much
                    // less - that's what made this whole bar stay bulky no
                    // matter how far their own padding/font sizes were
                    // shrunk. Disabling the enforcement for just this key
                    // section lets those smaller sizes actually take
                    // effect; every key here is still comfortably tappable,
                    // just no longer padded out to a minimum meant for
                    // sparser touch targets like a toolbar's icon buttons.
                    androidx.compose.runtime.CompositionLocalProvider(
                        LocalMinimumInteractiveComponentEnforcement provides false
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                        // Row1 has 8 columns (7 keys + MENU inlined between
                        // ESC and SLASH). Row2 has 7 real keys but TAB
                        // carries weight(2f) instead of 1f - so TAB alone
                        // spans the same width as ESC+MENU combined, and
                        // every key after it (CTRL, ALT, LEFT, DOWN, RIGHT,
                        // PGDN) still lines up under its row1 counterpart
                        // (SLASH, DASH, HOME, UP, END, PGUP) with zero
                        // drift - without adding an invisible spacer
                        // column or a separate menu row.
                        FirstKeyRowWithMenuButton(
                            keys = row1,
                            onKeyPressed = onKeyPressed,
                            ctrlActive = ctrlActive,
                            altActive = altActive,
                            onMenuClicked = onMenuClicked
                        )
                        VirtualKeyRow(row2, onKeyPressed, ctrlActive, altActive)
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

/**
 * Row 1 (ESC, /, —, HOME, ↑, END, PGUP) with a menu button inlined right
 * between ESC and SLASH, sized and styled like the surrounding keys. 8
 * equal-weight columns total. See [VirtualKeyRow]'s doc for how row2
 * mirrors this width with only 7 real keys, no spacer, no extra row.
 */
@Composable
private fun FirstKeyRowWithMenuButton(
    keys: List<VirtualKey>,
    onKeyPressed: (VirtualKey) -> Unit,
    ctrlActive: Boolean,
    altActive: Boolean,
    onMenuClicked: () -> Unit
) {
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val verticalPadding = if (isLandscape) 0.dp else 4.dp
    val fontSize = if (isLandscape) 11.sp else 13.sp

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        keys.forEach { key ->
            val isActive = (key == VirtualKey.CTRL && ctrlActive) || (key == VirtualKey.ALT && altActive)
            TextButton(
                onClick = { onKeyPressed(key) },
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 4.dp, vertical = verticalPadding
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f) else Color.Transparent
                    )
            ) {
                Text(
                    key.label,
                    fontSize = fontSize,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
            // Inlined right after ESC (before SLASH) - same weight/padding
            // as the surrounding keys so it reads as "one of the keys",
            // not a bolted-on extra.
            if (key == VirtualKey.ESC) {
                TextButton(
                    onClick = onMenuClicked,
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(
                        horizontal = 4.dp, vertical = verticalPadding
                    ),
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        Icons.Filled.Menu,
                        contentDescription = "Open sessions",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(if (isLandscape) 13.dp else 15.dp)
                    )
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
    altActive: Boolean
) {
    // In landscape there's a lot less vertical room (titlebar + 2 key rows +
    // IME can easily eat most of the screen), so the two rows shrink down
    // instead of keeping the same tall portrait-sized buttons.
    val isLandscape = androidx.compose.ui.platform.LocalConfiguration.current.orientation ==
        android.content.res.Configuration.ORIENTATION_LANDSCAPE
    // Matches FirstKeyRowWithMenuButton's sizing - see that composable's
    // doc for why these shrank from 8.dp/bodyMedium.
    val verticalPadding = if (isLandscape) 0.dp else 4.dp
    val fontSize = if (isLandscape) 11.sp else 13.sp

    // Row1 has 8 weight(1f) columns (7 keys + MENU inlined after ESC).
    // This row also has 8 real keys now (7 original + INSERT filling the
    // slot that used to sit empty next to TAB), all weight(1f) too - equal
    // column counts on both rows is what makes SpaceEvenly divide them
    // into identical-width columns: TAB lands under ESC, INSERT under
    // MENU, then CTRL/ALT/LEFT/DOWN/RIGHT/PGDN each land directly under
    // SLASH/DASH/HOME/UP/END/PGUP with zero drift.
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
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
                    horizontal = 4.dp, vertical = verticalPadding
                ),
                modifier = Modifier
                    .weight(1f)
                    .background(
                        if (isActive) MaterialTheme.colorScheme.onSurface.copy(alpha = 0.18f) else Color.Transparent
                    )
            ) {
                Text(
                    key.label,
                    fontSize = fontSize,
                    color = if (isActive) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.primary,
                    maxLines = 1
                )
            }
        }
    }
}
