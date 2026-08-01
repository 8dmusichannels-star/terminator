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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
    // connection takes over). MainActivity's "is the keyboard open" state
    // was previously driven only by that hidden field's focus, so this
    // exact tap made it flip to false and hid this entire bar mid-typing -
    // the "basınca kayboluyor" bug. This callback lets the parent also
    // treat focus on the text-entry page's own field as "keyboard open".
    onTextFieldFocusChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier
) {
    var textEntryOpen by remember { mutableStateOf(false) }
    var textEntryValue by remember { mutableStateOf("") }
    val scrollState = rememberScrollState()
    val revealThresholdPx = with(androidx.compose.ui.platform.LocalDensity.current) { 56.dp.toPx() }

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
                                    val atEnd = scrollState.value >= scrollState.maxValue
                                    if (atEnd && delta > 0) {
                                        overscroll += delta
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
