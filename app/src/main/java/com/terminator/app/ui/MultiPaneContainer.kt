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
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.OpenWith
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.terminator.emulator.TerminalBuffer
import com.terminator.emulator.TerminalEmulator
import com.terminator.emulator.MouseGestureTracker
import com.terminator.emulator.ScrollFling
import com.terminator.emulator.TerminalPalette
import com.terminator.emulator.TerminalView
import kotlin.math.roundToInt

/** Minimum pane size (both axes) in the tiling grid, and the minimum any
 *  floating pane can be resized down to - keeps a pane from being dragged
 *  or squeezed down to an unusably tiny sliver in either mode. */
private val MIN_PANE_WIDTH = 160.dp
private val MIN_PANE_HEIGHT = 160.dp

/**
 * Top-level entry point for multi-pane mode - MainActivity swaps to this
 * (instead of its own single/split-pane Column) whenever [panes] is
 * non-empty. Deliberately does NOT reuse the primary pane's ~450-line
 * gesture stack (mouse reporting, long-press text selection with edge
 * auto-scroll, pinch-zoom) - see MainActivity's own comment on
 * SplitTerminalPane for why hoisting that out to serve an arbitrary number
 * of independent panes would be its own large, risky rewrite. Each pane
 * here gets a smaller but still real gesture set: tap directly on the
 * terminal to focus it and bring up the keyboard (no separate "type here"
 * box), drag to pan scrollback, pinch to zoom text size, plus (Floating
 * mode only) drag the header to move the window and drag the corner handle
 * to resize it.
 *
 * Every pane is self-contained: it resizes its OWN session's pty against
 * its OWN measured pixel size (not the container's), so two panes at
 * different sizes each get a column/row count that actually matches what
 * they're individually showing - unlike a single shared column/row count
 * that only ever matched a single full-screen pane.
 */
@Composable
fun MultiPaneContainer(
    panes: List<PaneState>,
    mode: PaneMode,
    focusedRuntimeId: String?,
    bufferVersion: Int,
    bufferFor: (String) -> TerminalBuffer?,
    labelFor: (String) -> String,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    // Settings > Appearance > Pinch to zoom. Threaded down through
    // TilingLayout/FloatingLayout to PaneContent's own gesture loop, which
    // previously ignored this setting entirely (it has its own local,
    // unrelated-to-MainActivity pinch-zoom implementation - see this file's
    // top-level doc) - toggling it off did nothing in split/multi-pane
    // mode. Defaults to true so any other existing caller keeps today's
    // behavior with no wiring needed.
    zoomEnabled: Boolean = true,
    // Settings > Soft keyboard toggle - threaded down to each tile's own
    // PaneContent (see that composable's identically-named param for the
    // full doc on why only the wantsKeyboard toggle is gated, not the
    // focus-tracking bookkeeping). Defaults to true so any other existing
    // caller of this composable keeps today's behavior unchanged.
    softKeyboardEnabled: Boolean = true,
    onInput: (runtimeId: String, text: String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onMovePane: (runtimeId: String, offset: Offset) -> Unit,
    onResizePane: (runtimeId: String, size: Size) -> Unit,
    onResizeSessionPty: (runtimeId: String, columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) -> Unit,
    onSetMode: (PaneMode) -> Unit,
    onAddPaneRequested: () -> Unit,
    onExitMultiPane: () -> Unit,
    modifier: Modifier = Modifier,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onWantsMouseMoveEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit = { _, _, _, _, _ -> },
    // "More" actions (clone/wake-lock/save) for each tile's own Copy/Paste
    // selection bar - see PaneContent's identical params for the full doc.
    // All default to null/false so every existing caller of this composable
    // keeps getting exactly the old behavior (no More button on any tile)
    // without needing to change anything.
    onCloneSession: ((String) -> Unit)? = null,
    onToggleWakeUp: ((String) -> Unit)? = null,
    wakeUpActiveFor: (String) -> Boolean = { false },
    onSaveSession: ((String) -> Unit)? = null,
    // "Reclaim the focused pane's IME focus" signal - see PaneContent's own
    // doc on the identically-named param this threads down to. Bump on any
    // change (e.g. a counter incremented by the caller); 0 is the inert
    // default so existing callers need no wiring to keep today's behavior.
    focusRequestSignal: Int = 0,
    // Routes every tile's HiddenPaneInputField show()/hide() through
    // MainActivity's single insetsController - see PaneContent's own doc on
    // these params (threaded down through TilingLayout/FloatingLayout to
    // there). Null (the default) keeps any other caller's behavior
    // unchanged.
    onImeRequestShow: (() -> Unit)? = null,
    onImeRequestHide: (() -> Unit)? = null
) {
    Column(modifier = modifier.fillMaxSize()) {
        MultiPaneToolbar(
            mode = mode,
            paneCount = panes.size,
            onSetMode = onSetMode,
            onAddPaneRequested = onAddPaneRequested,
            onExitMultiPane = onExitMultiPane
        )
        Box(modifier = Modifier.weight(1f).fillMaxWidth().background(Color.Black)) {
            when (mode) {
                PaneMode.Tiling -> TilingLayout(
                    panes = panes,
                    focusedRuntimeId = focusedRuntimeId,
                    bufferVersion = bufferVersion,
                    bufferFor = bufferFor,
                    labelFor = labelFor,
                    palette = palette,
                    fontFamily = fontFamily,
                    fontSizeSp = fontSizeSp,
                    zoomEnabled = zoomEnabled,
                    softKeyboardEnabled = softKeyboardEnabled,
                    onInput = onInput,
                    onFocusPane = onFocusPane,
                    onClosePane = onClosePane,
                    onResizeSessionPty = onResizeSessionPty,
                    onWantsMouseEvents = onWantsMouseEvents,
                    onWantsMouseMoveEvents = onWantsMouseMoveEvents,
                    onMouseEvent = onMouseEvent,
                    onCloneSession = onCloneSession,
                    onToggleWakeUp = onToggleWakeUp,
                    wakeUpActiveFor = wakeUpActiveFor,
                    onSaveSession = onSaveSession,
                    focusRequestSignal = focusRequestSignal,
                    onImeRequestShow = onImeRequestShow,
                    onImeRequestHide = onImeRequestHide
                )
                PaneMode.Floating -> FloatingLayout(
                    panes = panes,
                    focusedRuntimeId = focusedRuntimeId,
                    bufferVersion = bufferVersion,
                    bufferFor = bufferFor,
                    labelFor = labelFor,
                    palette = palette,
                    fontFamily = fontFamily,
                    fontSizeSp = fontSizeSp,
                    zoomEnabled = zoomEnabled,
                    softKeyboardEnabled = softKeyboardEnabled,
                    onInput = onInput,
                    onFocusPane = onFocusPane,
                    onClosePane = onClosePane,
                    onMovePane = onMovePane,
                    onResizePane = onResizePane,
                    onResizeSessionPty = onResizeSessionPty,
                    onWantsMouseEvents = onWantsMouseEvents,
                    onWantsMouseMoveEvents = onWantsMouseMoveEvents,
                    onMouseEvent = onMouseEvent,
                    onCloneSession = onCloneSession,
                    onToggleWakeUp = onToggleWakeUp,
                    wakeUpActiveFor = wakeUpActiveFor,
                    onSaveSession = onSaveSession,
                    // Was missing entirely - FloatingLayout's own
                    // focusRequestSignal param silently defaulted to 0
                    // forever, so VirtualKeyBar's long-text swipe-back
                    // signal (bumped by MainActivity's onTextEntryClosed,
                    // same signal TilingLayout above already gets) never
                    // reached a floating pane's PaneContent at all - its
                    // LaunchedEffect(focusRequestSignal) never saw a
                    // nonzero value to react to. Same
                    // "IME kendi kendine kapanıyor" swipe bug already
                    // fixed for split screen and tiling multi-pane, just
                    // never wired up for Floating mode when this dispatch
                    // was written.
                    focusRequestSignal = focusRequestSignal,
                    onImeRequestShow = onImeRequestShow,
                    onImeRequestHide = onImeRequestHide
                )
            }
        }
    }
}

/** Mode toggle (Tiling/Floating), pane count, "+" to add another running
 *  session as a pane, and a way back to the classic single-pane view.
 *  Kept as a compact single row for the same reason RunnerToolbar used to
 *  be kept small - this sits permanently above every pane. */
@Composable
private fun MultiPaneToolbar(
    mode: PaneMode,
    paneCount: Int,
    onSetMode: (PaneMode) -> Unit,
    onAddPaneRequested: () -> Unit,
    onExitMultiPane: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(start = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "$paneCount pane" + if (paneCount == 1) "" else "s",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
            modifier = Modifier.weight(1f)
        )
        IconButton(onClick = { onSetMode(if (mode == PaneMode.Tiling) PaneMode.Floating else PaneMode.Tiling) }) {
            Icon(
                if (mode == PaneMode.Tiling) Icons.Filled.PictureInPicture else Icons.Filled.GridView,
                contentDescription = if (mode == PaneMode.Tiling) "Switch to floating windows" else "Switch to tiling grid",
                modifier = Modifier.size(20.dp)
            )
        }
        IconButton(onClick = onAddPaneRequested) {
            Icon(Icons.Filled.Add, contentDescription = "Add pane", modifier = Modifier.size(20.dp))
        }
        IconButton(onClick = onExitMultiPane) {
            Icon(Icons.Filled.Close, contentDescription = "Exit multi-pane mode", modifier = Modifier.size(20.dp))
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
}

// ---------------------------------------------------------------------
// Tiling: an auto-computed grid (as square as possible for the pane
// count), each cell separated by a draggable divider. Cell rectangles are
// derived fresh every composition from paneCount + container size - panes
// don't carry their own tiling geometry (floatOffset/floatSize are a
// Floating-mode-only concept, see PaneState's doc), so there is no stale
// layout to reconcile when a pane is added/removed; the grid just
// recomputes for the new count.
// ---------------------------------------------------------------------

@Composable
private fun TilingLayout(
    panes: List<PaneState>,
    focusedRuntimeId: String?,
    bufferVersion: Int,
    bufferFor: (String) -> TerminalBuffer?,
    labelFor: (String) -> String,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    zoomEnabled: Boolean = true,
    softKeyboardEnabled: Boolean = true,
    onInput: (String, String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onResizeSessionPty: (String, Int, Int, Int, Int) -> Unit,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onWantsMouseMoveEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit = { _, _, _, _, _ -> },
    // "More" actions for each tile's own selection bar - see PaneContent's
    // identical params for why these all default to null/false (existing
    // callers keep the "no More button" behavior they always had).
    onCloneSession: ((String) -> Unit)? = null,
    onToggleWakeUp: ((String) -> Unit)? = null,
    wakeUpActiveFor: (String) -> Boolean = { false },
    onSaveSession: ((String) -> Unit)? = null,
    focusRequestSignal: Int = 0,
    onImeRequestShow: (() -> Unit)? = null,
    onImeRequestHide: (() -> Unit)? = null
) {
    if (panes.isEmpty()) return
    // Grid shape: as close to square as possible, favoring one extra
    // column over one extra row (matches how most tiling WMs lay out a
    // non-perfect-square pane count - e.g. 3 panes -> 2 cols x 2 rows with
    // the last cell empty/last row's pane widened, not 1x3 or 3x1).
    val columns = kotlin.math.ceil(kotlin.math.sqrt(panes.size.toDouble())).toInt().coerceAtLeast(1)
    val rows = kotlin.math.ceil(panes.size.toDouble() / columns).toInt().coerceAtLeast(1)

    // Per-row/per-column size fractions, individually adjustable by
    // dragging the dividers between them - remember{} keyed on the grid
    // shape (columns/rows) so resizing the pane count resets to even splits
    // rather than trying to remap old fractions onto a different-shaped
    // grid, which has no single sensible mapping.
    val rowFractions = remember(rows) { mutableFloatListOfEven(rows) }
    val colFractions = remember(columns) { mutableFloatListOfEven(columns) }

    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val totalWidth = constraints.maxWidth.toFloat()
        val totalHeight = constraints.maxHeight.toFloat()
        val density = LocalDensity.current.density
        val minColFraction = (MIN_PANE_WIDTH.value * density) / totalWidth.coerceAtLeast(1f)
        val minRowFraction = (MIN_PANE_HEIGHT.value * density) / totalHeight.coerceAtLeast(1f)

        Column(modifier = Modifier.fillMaxSize()) {
            for (rowIndex in 0 until rows) {
                val rowWeight = rowFractions.getOrElse(rowIndex) { 1f / rows }
                Row(modifier = Modifier.weight(rowWeight).fillMaxWidth()) {
                    for (colIndex in 0 until columns) {
                        val paneIndex = rowIndex * columns + colIndex
                        val pane = panes.getOrNull(paneIndex)
                        val colWeight = colFractions.getOrElse(colIndex) { 1f / columns }
                        if (pane != null) {
                            // Keyed on the pane's own runtimeId, not just
                            // positional call order: without this, Compose
                            // reuses the composable AT THIS GRID SLOT across
                            // recompositions purely by call-site position -
                            // so closing an earlier pane (shifting every
                            // later pane's paneIndex down by one) silently
                            // handed each remaining PaneContent instance's
                            // remembered state (its own zoom level via
                            // zoomSizeSp, its own text selection via
                            // rememberSelectionState(), its own scrollOffset)
                            // to whichever DIFFERENT pane's runtimeId now
                            // landed on that slot - a stale, wrong-buffer
                            // selection rectangle (or a zoom level that
                            // belonged to a different, now-closed session)
                            // could show up superimposed on the pane that
                            // took its place. key() forces Compose to treat
                            // a slot whose runtimeId changed as a brand-new
                            // composable instance instead, so each pane's
                            // own remembered state only ever follows that
                            // pane, however the list reorders or shrinks.
                            androidx.compose.runtime.key(pane.runtimeId) {
                            Box(modifier = Modifier.weight(colWeight).fillMaxHeight()) {
                                PaneContent(
                                    runtimeId = pane.runtimeId,
                                    label = labelFor(pane.runtimeId),
                                    isFocused = pane.runtimeId == focusedRuntimeId,
                                    buffer = bufferFor(pane.runtimeId),
                                    bufferVersion = bufferVersion,
                                    palette = palette,
                                    fontFamily = fontFamily,
                                    fontSizeSp = fontSizeSp,
                                    zoomEnabled = zoomEnabled,
                                    softKeyboardEnabled = softKeyboardEnabled,
                                    onInput = { text -> onInput(pane.runtimeId, text) },
                                    onFocus = { onFocusPane(pane.runtimeId) },
                                    onClose = { onClosePane(pane.runtimeId) },
                                    onMeasuredSize = { cols, rws, pxW, pxH -> onResizeSessionPty(pane.runtimeId, cols, rws, pxW, pxH) },
                                    fontDensity = density,
                                    showDragHandle = false,
                                    onWantsMouseEvents = { onWantsMouseEvents(pane.runtimeId) },
                                    onWantsMouseMoveEvents = { onWantsMouseMoveEvents(pane.runtimeId) },
                                    onMouseEvent = { kind, col, row, button -> onMouseEvent(pane.runtimeId, kind, col, row, button) },
                                    onCloneSession = onCloneSession?.let { { it(pane.runtimeId) } },
                                    onToggleWakeUp = onToggleWakeUp?.let { { it(pane.runtimeId) } },
                                    wakeUpActive = wakeUpActiveFor(pane.runtimeId),
                                    onSaveSession = onSaveSession?.let { { it(pane.runtimeId) } },
                                    focusRequestSignal = focusRequestSignal,
                                    onImeRequestShow = onImeRequestShow,
                                    onImeRequestHide = onImeRequestHide,
                                    modifier = Modifier.fillMaxSize()
                                )
                            }
                            }
                            // Vertical divider between columns (not after the
                            // last one) - drags redistribute width between
                            // this column and the next.
                            if (colIndex < columns - 1) {
                                TilingDivider(
                                    vertical = true,
                                    onDrag = { deltaPx ->
                                        adjustAdjacentFractions(colFractions, colIndex, deltaPx / totalWidth, minColFraction)
                                    }
                                )
                            }
                        } else {
                            // Fewer panes than grid cells (e.g. 3 panes in a
                            // 2x2 grid) - leave the trailing cell(s) blank
                            // rather than crashing on a null pane.
                            Box(modifier = Modifier.weight(colWeight).fillMaxHeight())
                        }
                    }
                }
                if (rowIndex < rows - 1) {
                    TilingDivider(
                        vertical = false,
                        onDrag = { deltaPx ->
                            adjustAdjacentFractions(rowFractions, rowIndex, deltaPx / totalHeight, minRowFraction)
                        }
                    )
                }
            }
        }
    }
}

/** Even-width mutable fraction list (each 1/n) that PaneContent's dividers
 *  mutate in place via Compose's SnapshotStateList semantics through a
 *  plain MutableList<Float> wrapped in mutableStateListOf-like access -
 *  using a simple androidx.compose.runtime.mutableStateListOf under the
 *  hood so drags trigger recomposition. */
private fun mutableFloatListOfEven(n: Int): androidx.compose.runtime.snapshots.SnapshotStateList<Float> {
    val even = 1f / n.coerceAtLeast(1)
    val list = androidx.compose.runtime.mutableStateListOf<Float>()
    repeat(n) { list.add(even) }
    return list
}

/** Redistributes delta (a fraction of total container extent) between
 *  index and index+1, clamped so neither side can shrink below minFraction -
 *  same "keep every pane usable" guarantee as the classic split's
 *  setSplitRatio clamp. */
private fun adjustAdjacentFractions(
    fractions: androidx.compose.runtime.snapshots.SnapshotStateList<Float>,
    index: Int,
    delta: Float,
    minFraction: Float
) {
    if (index + 1 >= fractions.size) return
    val a = fractions[index]
    val b = fractions[index + 1]
    val safeMin = minFraction.coerceIn(0.05f, 0.4f)
    var newA = (a + delta).coerceAtLeast(safeMin)
    var newB = (a + b) - newA
    if (newB < safeMin) {
        newB = safeMin
        newA = (a + b) - newB
    }
    fractions[index] = newA
    fractions[index + 1] = newB
}

@Composable
private fun TilingDivider(vertical: Boolean, onDrag: (deltaPx: Float) -> Unit) {
    val thickness = 6.dp
    Box(
        modifier = (if (vertical) Modifier.width(thickness).fillMaxHeight() else Modifier.height(thickness).fillMaxWidth())
            .background(Color.White.copy(alpha = 0.06f))
            .pointerInput(vertical) {
                detectDragGestures { change, dragAmount ->
                    change.consume()
                    onDrag(if (vertical) dragAmount.x else dragAmount.y)
                }
            }
    )
}

// ---------------------------------------------------------------------
// Floating: every pane is a free window at PaneState.floatOffset/floatSize,
// draggable by its header and resizable by a corner handle, may overlap,
// tap anywhere on a pane raises it and focuses it.
// ---------------------------------------------------------------------

@Composable
private fun FloatingLayout(
    panes: List<PaneState>,
    focusedRuntimeId: String?,
    bufferVersion: Int,
    bufferFor: (String) -> TerminalBuffer?,
    labelFor: (String) -> String,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    zoomEnabled: Boolean = true,
    softKeyboardEnabled: Boolean = true,
    onInput: (String, String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onMovePane: (runtimeId: String, offset: Offset) -> Unit,
    onResizePane: (runtimeId: String, size: Size) -> Unit,
    onResizeSessionPty: (String, Int, Int, Int, Int) -> Unit,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onWantsMouseMoveEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit = { _, _, _, _, _ -> },
    onCloneSession: ((String) -> Unit)? = null,
    onToggleWakeUp: ((String) -> Unit)? = null,
    wakeUpActiveFor: (String) -> Boolean = { false },
    onSaveSession: ((String) -> Unit)? = null,
    focusRequestSignal: Int = 0,
    onImeRequestShow: (() -> Unit)? = null,
    onImeRequestHide: (() -> Unit)? = null
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val containerWidthDp = constraints.maxWidth / density
        val containerHeightDp = constraints.maxHeight / density

        // Sorted by zIndex so later (higher-stacked) panes draw on top,
        // matching what bringPaneToFront just did to the underlying state.
        panes.sortedBy { it.zIndex }.forEach { pane ->
            // Keyed on the pane's own runtimeId: this list is sorted by
            // zIndex, which changes on every bringPaneToFront() -
            // including the one PaneContent's own gesture loop fires via
            // onFocus() on every single touch, even the initial down of a
            // pinch. Without an explicit key(), Compose identifies each
            // iteration's composable by its POSITION in this forEach, not
            // by which pane it's showing - so the instant a touch
            // reordered `panes` here, the composable sitting at a given
            // position silently started rendering a DIFFERENT pane's
            // content while still holding onto whatever remembered state
            // (this pane's own zoomSizeSp, its own rememberSelectionState())
            // belonged to whichever pane used to occupy that slot. That's
            // what made pinch-zoom "geri tepiyor" (a touch reorders the
            // stack the moment it lands, so the zoom level the user was
            // mid-gesture on could get silently swapped for a different
            // pane's remembered zoom) and could just as easily surface a
            // stale, wrong-buffer text selection rectangle left over from
            // whatever pane previously occupied that slot. key() forces a
            // slot whose runtimeId changed to be torn down and recomposed
            // as a genuinely new instance instead, so all of this state
            // only ever follows its own pane regardless of stacking order.
            androidx.compose.runtime.key(pane.runtimeId) {
            // Clamp so a window dragged/restored from a larger screen still
            // has at least its header reachable on a smaller one, rather
            // than becoming a permanently off-screen, unrecoverable pane.
            val clampedX = pane.floatOffset.x.coerceIn(-pane.floatSize.width + 48f, containerWidthDp - 48f)
            val clampedY = pane.floatOffset.y.coerceIn(0f, containerHeightDp - 32f)

            // Fixed reference point for the CURRENT drag/resize gesture -
            // captured once when a gesture starts (see PaneContent's
            // cumulative-delta doc) rather than read fresh from `pane` on
            // every frame. `pane` itself only reflects the latest
            // committed state, which lags a frame or more behind the
            // in-progress gesture once movePane/resizePane round-trips
            // through the ViewModel and back - computing "start + running
            // total" against a value captured once avoids compounding that
            // lag into visible jitter.
            var dragStartOffset by remember(pane.runtimeId) { androidx.compose.runtime.mutableStateOf(pane.floatOffset) }
            var resizeStartSize by remember(pane.runtimeId) { androidx.compose.runtime.mutableStateOf(pane.floatSize) }

            Box(
                modifier = Modifier
                    .offset { IntOffset((clampedX * density).roundToInt(), (clampedY * density).roundToInt()) }
                    .size(width = pane.floatSize.width.dp, height = pane.floatSize.height.dp)
            ) {
                PaneContent(
                    runtimeId = pane.runtimeId,
                    label = labelFor(pane.runtimeId),
                    isFocused = pane.runtimeId == focusedRuntimeId,
                    buffer = bufferFor(pane.runtimeId),
                    bufferVersion = bufferVersion,
                    palette = palette,
                    fontFamily = fontFamily,
                    fontSizeSp = fontSizeSp,
                    zoomEnabled = zoomEnabled,
                    softKeyboardEnabled = softKeyboardEnabled,
                    onInput = { text -> onInput(pane.runtimeId, text) },
                    onFocus = { onFocusPane(pane.runtimeId) },
                    onClose = { onClosePane(pane.runtimeId) },
                    onMeasuredSize = { cols, rws, pxW, pxH -> onResizeSessionPty(pane.runtimeId, cols, rws, pxW, pxH) },
                    fontDensity = density,
                    showDragHandle = true,
                    onDragStart = {
                        dragStartOffset = pane.floatOffset
                        resizeStartSize = pane.floatSize
                    },
                    onHeaderDrag = { cumulativeDeltaPx ->
                        onFocusPane(pane.runtimeId)
                        val newOffset = Offset(
                            (dragStartOffset.x + cumulativeDeltaPx.x / density)
                                .coerceIn(-pane.floatSize.width + 48f, containerWidthDp - 48f),
                            (dragStartOffset.y + cumulativeDeltaPx.y / density)
                                .coerceIn(0f, containerHeightDp - 32f)
                        )
                        onMovePane(pane.runtimeId, newOffset)
                    },
                    onResizeDrag = { cumulativeDeltaPx ->
                        val newSize = Size(
                            (resizeStartSize.width + cumulativeDeltaPx.x / density).coerceAtLeast(MIN_PANE_WIDTH.value),
                            (resizeStartSize.height + cumulativeDeltaPx.y / density).coerceAtLeast(MIN_PANE_HEIGHT.value)
                        )
                        onResizePane(pane.runtimeId, newSize)
                    },
                    onWantsMouseEvents = { onWantsMouseEvents(pane.runtimeId) },
                    onWantsMouseMoveEvents = { onWantsMouseMoveEvents(pane.runtimeId) },
                    onMouseEvent = { kind, col, row, button -> onMouseEvent(pane.runtimeId, kind, col, row, button) },
                    onCloneSession = onCloneSession?.let { { it(pane.runtimeId) } },
                    onToggleWakeUp = onToggleWakeUp?.let { { it(pane.runtimeId) } },
                    wakeUpActive = wakeUpActiveFor(pane.runtimeId),
                    onSaveSession = onSaveSession?.let { { it(pane.runtimeId) } },
                    focusRequestSignal = focusRequestSignal,
                    onImeRequestShow = onImeRequestShow,
                    onImeRequestHide = onImeRequestHide,
                    modifier = Modifier.fillMaxSize()
                )
            }
            }
        }
    }
}

// ---------------------------------------------------------------------
// Shared pane chrome: header (label + close), terminal body with its own
// tap-to-focus/type, drag-to-scroll-history and pinch-to-zoom, and (when
// showDragHandle is true, i.e. Floating mode) a header drag area plus a
// corner resize handle.
// ---------------------------------------------------------------------

@Composable
private fun PaneContent(
    runtimeId: String,
    label: String,
    isFocused: Boolean,
    buffer: TerminalBuffer?,
    bufferVersion: Int,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    // See MultiPaneContainer's own doc on this param - gates only the
    // zoomSizeSp write below (the pinch/pan loop's scroll handling stays
    // active either way, same as the primary pane's identical zoomEnabled
    // gate in MainActivity).
    zoomEnabled: Boolean = true,
    onInput: (String) -> Unit,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    onMeasuredSize: (columns: Int, rows: Int, pixelWidth: Int, pixelHeight: Int) -> Unit,
    fontDensity: Float,
    showDragHandle: Boolean,
    modifier: Modifier = Modifier,
    onDragStart: () -> Unit = {},
    onHeaderDrag: (Offset) -> Unit = {},
    onResizeDrag: (Offset) -> Unit = {},
    // Mouse reporting for this pane's own session (mc/vim/htop xterm-mouse-
    // mode). Defaults to permanently-off/no-op so callers that don't wire
    // mouse reporting keep working exactly as before.
    onWantsMouseEvents: () -> Boolean = { false },
    onWantsMouseMoveEvents: () -> Boolean = { false },
    onMouseEvent: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit = { _, _, _, _ -> },
    // "More" actions for this pane's own Copy/Paste/More selection bar -
    // same MoreMenuActions shape the primary pane and SplitTerminalPane
    // use, just with onToggleSplitScreen always null (split-screen mode
    // and multi-pane mode are separate features - see this file's own
    // MultiPaneContainer doc - so there's no single "the split partner"
    // concept a multi-pane tile could offer here). Null hides the More
    // button entirely (same "hidden not disabled" treatment as elsewhere),
    // which is what every existing caller gets by default since this
    // param didn't exist before - no wiring needed unless a caller
    // actually wants the button shown.
    onCloneSession: (() -> Unit)? = null,
    onToggleWakeUp: (() -> Unit)? = null,
    wakeUpActive: Boolean = false,
    onSaveSession: (() -> Unit)? = null,
    // Settings > Soft keyboard toggle, same flag SplitTerminalPane now takes
    // (see that composable's own doc) - guards only this tile's
    // wantsKeyboard toggle below, not the onFocus()/focusToken bookkeeping,
    // which must keep running unconditionally so VirtualKeyBar/keymapper
    // routing still follows which tile was tapped even when the soft
    // keyboard is turned off. Defaults to true so any other existing caller
    // of this composable keeps today's behavior unchanged.
    softKeyboardEnabled: Boolean = true,
    // Same "reclaim this pane's IME focus" signal SplitTerminalPane's own
    // focusRequestSignal is (see its doc): MainActivity bumps this after
    // VirtualKeyBar's long-text page swipes closed. Without a per-pane
    // equivalent here, multi-pane mode's onTextEntryClosed had nothing to
    // bump - the outgoing OutlinedTextField left composition with no field
    // left focused, the real IME started closing on its own with nothing to
    // reopen it for, and MainActivity's only fallback was a bare
    // insetsController.show() with no input connection behind it - the
    // "IMEİ kendi kendine kapanıyor" swipe bug, multi-pane-only for exactly
    // the same reason SplitTerminalPane's own doc gives (the primary pane's
    // field is requested directly, one hop; every pane in here goes through
    // this extra signal -> focusToken bump hop instead). Defaults to 0 so
    // every existing caller keeps its old behavior with no wiring needed.
    focusRequestSignal: Int = 0,
    // Routes this tile's HiddenPaneInputField show()/hide() through
    // MainActivity's single insetsController + WindowInsetsAnimationCompat
    // ground-truth instead of HiddenPaneInputField deriving its own local
    // controller - same fix and same reasoning as SplitTerminalPane's own
    // onImeRequestShow/onImeRequestHide (see HiddenPaneInputField's doc on
    // those params). Multi-pane mode showed the identical swipe-glitch
    // symptom split screen did, for the identical reason: each tile's
    // HiddenPaneInputField was deriving its own separate
    // WindowInsetsControllerCompat from LocalView, a second controller
    // instance able to issue show()/hide() against the same window
    // independently of the one MainActivity's own ground-truth callback is
    // attached to. Null (the default) keeps any other caller's behavior
    // unchanged.
    onImeRequestShow: (() -> Unit)? = null,
    onImeRequestHide: (() -> Unit)? = null
) {
    // Per-pane pinch-zoom override, local to this composable only (not
    // persisted) - matches the lightweight-vs-primary-pane tradeoff this
    // whole file documents up top. Resets to the global size whenever the
    // pane's runtimeId changes (a different session took this slot).
    var zoomSizeSp by remember(runtimeId) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val effectiveFontSizeSp = zoomSizeSp ?: fontSizeSp
    // The pinch/pan gesture loop below lives inside a long-running
    // awaitEachGesture { } coroutine launched once per pointerInput(runtimeId)
    // key change - the plain `effectiveFontSizeSp` val above is only the
    // value captured at that launch moment, not a live read. Every pinch
    // frame recomputes zoomSizeSp from `effectiveFontSizeSp * frameZoom`
    // (see the loop below), so once the loop's closure was holding a stale
    // captured size, each frame's zoom ratio kept compounding against that
    // same stale base instead of the size the previous frame just wrote -
    // the result either ran away in one direction until it hit the
    // coerceIn(8f, 32f) clamp and stopped dead ("takılıp kalıyor"), or the
    // clamp let a large single-frame jump through and snapped back once a
    // later frame recomputed against the real (much larger/smaller) live
    // size - both read as "geri tepiyor". rememberUpdatedState mirrors
    // MainActivity's own latestEffectiveTextSize fix for the identical
    // primary-pane bug: reading .value inside the gesture loop always sees
    // the size as of the most recent recomposition, so each frame's zoom
    // compounds against the real current size instead of a stale snapshot.
    val latestEffectiveFontSizeSp = rememberUpdatedState(effectiveFontSizeSp)

    // Same char-metric formula MainActivity's primary pane uses, scoped to
    // this pane's own current font size so its own resize computation is
    // correct even mid-pinch.
    val charMetrics = remember(fontFamily, effectiveFontSizeSp, fontDensity) {
        val metricsPaint = android.graphics.Paint().apply {
            typeface = fontFamily
            this.textSize = effectiveFontSizeSp * fontDensity
        }
        metricsPaint.measureText("M") to metricsPaint.fontSpacing
    }

    // Debounces this pane's own onSizeChanged the same way MainActivity's
    // primary-pane Box already does (see its own onSizeChanged doc) -
    // imePadding()-driven resizes report a new size on every frame of the
    // IME's ~250-300ms show/hide animation, not just once at the end.
    // MultiPaneContainer's per-tile onSizeChanged used to forward every one
    // of those straight to onMeasuredSize -> onResizeSessionPty ->
    // TerminalSession.resize(), so full-screen ncurses programs (vim, mc,
    // htop) running in a tile got a burst of SIGWINCH + full-redraw cycles
    // against transient mid-animation sizes on every keyboard open/close -
    // each redraw against a size that was already stale a frame later is
    // what showed up as a brief blackout/flicker right as the IME closed.
    // The classic single/split-pane view never had this because its own
    // resize goes through updateTerminalSize(), which every live session
    // (including the split partner) shares - and that path already sits
    // behind MainActivity's 120ms debounce. Multi-pane tiles use the
    // separate per-runtime updateTerminalSizeFor() path instead (see its
    // own doc: "each multi-pane pane has its own independent on-screen
    // size"), which had no debounce of its own until now - this mirrors
    // that same fix at this call site instead.
    val paneResizeScope = rememberCoroutineScope()
    // Momentum + sharp edge-autoscroll for this tile's mouse-tracking
    // gestures - same pair as MainActivity's primary pane and
    // SplitTerminalPane (see ScrollFling/EdgeWheelAutoScroll's own docs in
    // MouseGestureTracker.kt). This tile's mouse-report block previously
    // called runMouseReportGesture with neither wired up.
    val scrollFling = remember(runtimeId) { ScrollFling(paneResizeScope) }
    val edgeWheelAutoScroll = remember(runtimeId) { MouseGestureTracker.EdgeWheelAutoScroll() }
    var paneResizeDebounceJob by remember(runtimeId) {
        mutableStateOf<Job?>(null)
    }
    var latestPaneSizePx by remember(runtimeId) {
        mutableStateOf<IntSize?>(null)
    }
    val latestOnMeasuredSize = rememberUpdatedState(onMeasuredSize)
    val latestCharMetrics = rememberUpdatedState(charMetrics)
    // Set true for the duration of a manual corner-handle resize drag (see
    // onDragStart/onDragEnd below) so onSizeChanged's own debounce can tell
    // "the IME is animating past this size" apart from "the user is
    // actively dragging the resize handle and wants to see the terminal
    // grid follow their finger". Both paths land in the same onSizeChanged
    // callback (dragging the handle changes pane.floatSize, which resizes
    // this Box, which is exactly what onSizeChanged already observes for
    // the IME case) - previously both were debounced by the same fixed
    // 120ms, which is short enough to be invisible for a one-shot IME
    // animation but reads as a visible lag ("boyutlandirirken metinler gec
    // boyutlaniyor") for a drag that keeps reporting a new size every
    // frame: cancelling and restarting a 120ms timer on every single frame
    // of a multi-second drag means the pty is never actually resized until
    // the finger stops moving for a full 120ms, so the Canvas box visibly
    // grows/shrinks under the user's finger while the character grid
    // inside it stays pinned at its old column/row count the entire time.
    var isManuallyResizing by remember(runtimeId) { mutableStateOf(false) }
    val latestIsManuallyResizing = rememberUpdatedState(isManuallyResizing)
    // Wall-clock time (System.currentTimeMillis()) of the last commit
    // actually pushed to the pty during a manual corner-handle drag - see
    // onSizeChanged's own doc below for why this turns the 32ms manual
    // window into a genuine THROTTLE instead of a debounce.
    var lastManualResizeCommitMs by remember(runtimeId) { mutableStateOf(0L) }

    // Local scroll offset into this pane's own scrollback - independent of
    // every other pane's, and of the classic single-pane view's scrollOffset.
    var scrollOffset by remember(runtimeId) { androidx.compose.runtime.mutableIntStateOf(0) }
    // Last known pointer position for a real mouse - see MainActivity's own
    // lastMousePosition doc for why a wheel notch (a delta, not a
    // position) needs this.
    var lastMousePosition by remember(runtimeId) { mutableStateOf(Offset.Zero) }

    // Mirrors MainViewModel.lastScrollWasEdgeAutoScroll / the primary pane's
    // LaunchedEffect(state.scrollOffset) guard, and SplitTerminalPane's
    // wasLastScrollEdgeAutoScroll param - but local to this tile, since each
    // pane's scrollOffset above is already local rather than routed through
    // the shared ViewModel. Without this, EVERY scrollOffset change here
    // unconditionally cleared paneSelectionState (see the LaunchedEffect
    // right below the pointerInput block), so as soon as a drag moved even
    // one line while a selection was active in a multi-pane/grid tile, the
    // selection vanished instantly - "scrollback ile selection ile metin
    // secme ozelligi yok" in multi-pane/split-grid modes specifically,
    // while the classic single-pane view (which does have this guard) and
    // split-screen mode (via wasLastScrollEdgeAutoScroll) worked fine.
    var lastScrollWasEdgeAutoScroll by remember(runtimeId) { mutableStateOf(false) }

    // Declared here (rather than down where it's read by TerminalView)
    // specifically so the pointerInput gesture loop below - and the
    // scrollOffset LaunchedEffect guard right after it - can both see it.
    // Same instance is reused for TerminalView/SelectionActionBar/etc.
    // further down in this composable.
    val paneSelectionState = com.terminator.emulator.rememberTerminalSelectionState()
    androidx.compose.runtime.LaunchedEffect(runtimeId) { paneSelectionState.clear() }

    // Same reasoning as MainActivity's own LaunchedEffect(state.scrollOffset):
    // a scrollOffset change clears the active selection UNLESS that specific
    // scroll was flagged as an edge-auto-scroll-while-selecting (or, below,
    // an ordinary drag that started with a non-empty selection) - otherwise
    // scrolling scrollback out from under a selection (via drag or the
    // auto-scroll-past-viewport-edge case TerminalView's SelectionContainer
    // itself drives) invalidates the frozen row text's identity and the
    // selection collapses to empty on the very next recomposition.
    androidx.compose.runtime.LaunchedEffect(scrollOffset) {
        if (!lastScrollWasEdgeAutoScroll) {
            paneSelectionState.clear()
        }
    }

    // Same focusToken pattern SplitTerminalPane uses: bumped on every real
    // tap into this pane so HiddenPaneInputField's LaunchedEffect fires even
    // when isFocused was already true (a same-value write is a no-op for
    // LaunchedEffect keyed on a boolean). Without this, tapping back into a
    // pane that was already focused left the keyboard silently closed with
    // no field to show it for — the "keyboard bazen açılmıyor" bug in
    // multi-pane mode.
    var focusToken by remember(runtimeId) { androidx.compose.runtime.mutableIntStateOf(0) }
    // Local, independent of isFocused/focusedRuntimeId (which just tracks
    // WHICH tile owns input focus, not whether ITS keyboard should be up).
    // Was missing entirely, so the tap gesture below only ever bumped
    // focusToken and re-ran HiddenPaneInputField's show() branch - a tap on
    // an already-focused, already-open tile had no way to close the
    // keyboard, unlike the primary pane's own tap-to-toggle. Starts true so
    // a freshly-focused tile still opens the keyboard immediately, same as
    // before this fix.
    var wantsKeyboard by remember(runtimeId) { mutableStateOf(true) }
    // Live per-frame IME-visible read - same pattern as MainActivity's own
    // primary-pane `keyboardOpen` (see its doc: assigned into a
    // mutableStateOf on every composition, not read as a plain val), NOT
    // rememberUpdatedState(val). The two look equivalent but aren't: a
    // plain `val keyboardOpenNow = WindowInsets.ime.getBottom(...)` wrapped
    // in rememberUpdatedState only refreshes when THIS composable itself
    // recomposes, and Compose has no obligation to recompose this exact
    // scope just because the inset changed several composables away from
    // where the inset is actually read - depending on where recomposition
    // scopes land, the gesture loop below could keep reading a frozen
    // "IME closed" or "IME open" snapshot indefinitely, which reads as the
    // toggle being permanently inverted rather than merely occasionally
    // stale. A mutableStateOf that's WRITTEN on every composition (like
    // MainActivity's keyboardOpen) doesn't have that gap: the write itself
    // is what keeps a State's snapshot current, independent of whether any
    // particular downstream reader's scope was the one that recomposed.
    var keyboardOpenNow by remember(runtimeId) { mutableStateOf(false) }
    keyboardOpenNow = WindowInsets.ime
        .getBottom(androidx.compose.ui.platform.LocalDensity.current) > 0
    // Same live-mirror pattern as keyboardOpenNow directly above, for the
    // same reason: a plain captured `isFocused` parameter read inside the
    // long-running pointerInput(runtimeId){ awaitEachGesture{} } coroutine
    // below would freeze at whatever value this composable held when that
    // coroutine was first launched (see the staleness note right below
    // this block) and never see later focus changes - so it's mirrored
    // into a State here, reassigned on every recomposition, so a read
    // inside the gesture coroutine gets this tile's true focus state as of
    // the last completed recomposition. Captured into a local val at the
    // very top of each gesture (see the down-handling block below) so
    // this tile's OWN onFocus() call, made moments later in the same
    // gesture, can't retroactively corrupt what "was I already focused
    // BEFORE this tap" meant for that gesture's toggle decision.
    var focusedBeforeGesture by remember(runtimeId) { mutableStateOf(false) }
    focusedBeforeGesture = isFocused
    // isFocused (the constructor param above) is a plain Boolean, not a
    // Compose State - read directly inside the tap gesture's
    // pointerInput(runtimeId){ awaitEachGesture { ... } } block below, it
    // would capture whatever value isFocused happened to hold the moment
    // that coroutine was originally launched and never see it change
    // again: pointerInput's key is runtimeId alone, which never changes
    // for a tile's whole lifetime, so Compose never restarts that
    // coroutine (and therefore never re-captures the closure) just
    // because focus moved to/from this tile. This no longer matters for
    // the tap-to-toggle decision itself (see that block's own doc - it
    // reads keyboardOpenNow directly now, not isFocused), but is left
    // here as the general staleness note for this gesture loop.

    // Consumes focusRequestSignal - only for whichever pane is actually
    // focused, same guard SplitTerminalPane's splitPaneFocused check gives
    // it implicitly (MainActivity there only has one split pane to target;
    // here there are N tiles, so this composable has to check isFocused
    // itself instead). No artificial delay needed anymore: VirtualKeyBar's
    // own LaunchedEffect(textEntryOpen) now force-clears the outgoing
    // long-text field's focus (focusManager.clearFocus(force = true))
    // synchronously before firing onTextEntryClosed(), so by the time this
    // signal bumps the old field has already let go - requesting focus here
    // immediately no longer loses the race, and the real IME never sees a
    // focus-less gap to visibly close/reopen for. Skips signal 0 so the
    // initial composition (default value) never fires this on its own.
    androidx.compose.runtime.LaunchedEffect(focusRequestSignal) {
        if (focusRequestSignal != 0 && isFocused) {
            wantsKeyboard = true
            focusToken++
        }
    }

    Column(
        modifier = modifier
            .background(if (isFocused) MaterialTheme.colorScheme.primary.copy(alpha = 0.4f) else Color.White.copy(alpha = 0.12f))
            .padding(1.dp) // Focus ring: the outer background peeks through as a 1dp border.
            .background(Color.Black)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xFF141414))
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .let { base ->
                    if (showDragHandle) {
                        base.pointerInput(runtimeId) {
                            // Accumulates the running total of the drag
                            // gesture, reset at the start of every new drag -
                            // NOT the single per-frame dragAmount. Compose's
                            // pointer-input coroutine runs independently of
                            // recomposition, so a callback fed only the raw
                            // per-frame delta and adding it on top of a
                            // composable-scope value (pane.floatOffset, which
                            // only updates once recomposition catches up)
                            // was applying that same tiny delta to a stale
                            // base repeatedly - the visible symptom being the
                            // pane jittering in place instead of following
                            // the finger. Sending the cumulative total lets
                            // the caller compute newOffset = dragStartOffset
                            // + total once per frame, which is stable
                            // regardless of how many recompositions land
                            // mid-gesture.
                            var accumulated = Offset.Zero
                            detectDragGestures(
                                onDragStart = {
                                    accumulated = Offset.Zero
                                    onDragStart()
                                    onFocus()
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    onHeaderDrag(accumulated)
                                }
                            )
                        }
                    } else {
                        base.pointerInput(runtimeId) {
                            detectTapGestures(onTap = { onFocus() })
                        }
                    }
                },
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            if (showDragHandle) {
                Icon(
                    Icons.Filled.OpenWith,
                    contentDescription = "Drag to move",
                    tint = Color.White.copy(alpha = 0.4f),
                    modifier = Modifier.size(14.dp)
                )
                androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onClose, modifier = Modifier.size(24.dp)) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Close pane",
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // clipToBounds: TerminalView's own selection-handle circles are
        // drawn slightly BELOW the row they represent (see TerminalView's
        // drawSelectionHandle doc: rowTop + rowHeight + radius*0.7f) - when
        // a selection's end row is the LAST visible row, that circle can
        // extend past this Box's own bottom edge. Without clipping here,
        // Compose has nothing stopping that overdraw from painting over
        // whatever sits above this pane in the shared Z-plane - in a
        // multi-pane grid tile that's this SAME pane's own header row
        // (the label/close-button Row just above, sharing this Column),
        // and in a cramped tile it can reach further still. That's what
        // showed up as a selection handle circle bleeding up into/through
        // the pane header UI ("UI dışına sızıyor... her modda kuşçuk").
        // clipToBounds() constrains all drawing (including this handle
        // overdraw) to this Box's own layout bounds, same as any other
        // scrollable/clipped content area.
        Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
            if (buffer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { sizePx ->
                            // Debounced - see this pane's own resize-debounce
                            // doc above. Only the size that's still current
                            // 120ms after the LAST onSizeChanged call actually
                            // gets pushed to the pty; every transient size
                            // reported mid-IME-animation gets superseded
                            // before its own delay fires.
                            //
                            // While a manual corner-handle drag is in
                            // progress (isManuallyResizing, see its own doc
                            // just above), this used to fall to a shorter
                            // ~2-frame (32ms) DEBOUNCE instead of the full
                            // 120ms - but a debounce restarts its delay on
                            // every single call, and a continuous drag
                            // delivers a new onSizeChanged practically every
                            // frame, so the 32ms timer kept getting cancelled
                            // and re-armed the whole time the finger was
                            // moving - it only ever actually fired in the
                            // brief gaps where movement paused for a beat,
                            // which is what still read as laggy ("Floating
                            // boyutlandirmasi sonrasi... biraz gecikmeli").
                            // Fixed by throttling instead: while manually
                            // resizing, if at least ~32ms have passed since
                            // the last real commit, apply this size
                            // IMMEDIATELY (no delay at all) rather than
                            // scheduling another timer - so the pty/Canvas
                            // grid updates at a steady ~30fps-ish cadence
                            // that keeps pace with the finger throughout the
                            // whole drag, not just once it stops.
                            latestPaneSizePx = sizePx
                            if (latestIsManuallyResizing.value) {
                                val now = System.currentTimeMillis()
                                if (now - lastManualResizeCommitMs >= 32L) {
                                    lastManualResizeCommitMs = now
                                    paneResizeDebounceJob?.cancel()
                                    val (charWidth, charHeight) = latestCharMetrics.value
                                    if (charWidth > 0f && charHeight > 0f) {
                                        val cols = (sizePx.width / charWidth).toInt().coerceAtLeast(1)
                                        val rws = (sizePx.height / charHeight).toInt().coerceAtLeast(1)
                                        latestOnMeasuredSize.value(cols, rws, sizePx.width, sizePx.height)
                                    }
                                }
                                return@onSizeChanged
                            }
                            paneResizeDebounceJob?.cancel()
                            paneResizeDebounceJob = paneResizeScope.launch {
                                delay(120L)
                                val (charWidth, charHeight) = latestCharMetrics.value
                                val finalSize = latestPaneSizePx
                                if (charWidth > 0f && charHeight > 0f && finalSize != null) {
                                    val cols = (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                    val rws = (finalSize.height / charHeight).toInt().coerceAtLeast(1)
                                    latestOnMeasuredSize.value(cols, rws, finalSize.width, finalSize.height)
                                }
                            }
                        }
                        // Edge-scroll while selecting - same feature/mechanism as
                        // SplitTerminalPane's own block and MainActivity's primary-
                        // pane one: observe pointer position at
                        // PointerEventPass.Initial (before SelectionContainer or the
                        // gesture loop below consume anything) and, while
                        // paneSelectionState has an active selection, keep revealing
                        // scrollback near the top/bottom edge so the selection can be
                        // extended into history by holding a finger near the edge.
                        // Never consumes - purely a side-effecting observer.
                        //
                        // This tile never had this modifier at all (unlike
                        // SplitTerminalPane), which is a separate gap from the
                        // drag-to-scroll-while-selecting fix in the gesture loop
                        // below: that fix only keeps a selection alive when the
                        // USER's own finger-drag changes scrollOffset, but
                        // SelectionContainer's own long-press-drag-to-extend can
                        // itself need scrollback revealed near an edge without any
                        // separate pan gesture ever starting - nothing here was
                        // driving that reveal at all, so a selection that grew
                        // toward the top/bottom of the visible pane simply couldn't
                        // reach anything above/below the initial viewport.
                        // lastScrollWasEdgeAutoScroll is set true here (matching the
                        // gesture loop's own sites) so the LaunchedEffect(scrollOffset)
                        // guard doesn't clear the very selection this scroll exists
                        // to extend.
                        .pointerInput(runtimeId) {
                            val edgeFraction = 0.15f
                            val maxLinesPerFrame = 1.5f
                            awaitPointerEventScope {
                                while (true) {
                                    val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                    if (paneSelectionState.selectedTexts.isEmpty()) continue
                                    // Grace window right after a selection is (re)created -
                                    // see TerminalSelectionState.lastStartAtNanos' own doc
                                    // (same fix as the primary pane's identical block).
                                    val sinceStartMs = (System.nanoTime() - paneSelectionState.lastStartAtNanos) / 1_000_000L
                                    if (sinceStartMs < 200L) continue
                                    val pointer = event.changes.firstOrNull() ?: continue
                                    if (!pointer.pressed) continue
                                    val h = size.height.toFloat()
                                    if (h <= 0f) continue
                                    val y = pointer.position.y
                                    val edgePx = h * edgeFraction
                                    // latestCharMetrics.value, not the plain charMetrics -
                                    // this whole edge-auto-scroll loop lives inside
                                    // pointerInput(runtimeId), a coroutine that (like
                                    // MainActivity's primary-pane gesture loop) is only
                                    // relaunched when runtimeId itself changes, not on
                                    // every font-size/zoom change. See MainActivity's own
                                    // latestCharMetrics.value fix for the full doc - same
                                    // stale-closure bug, same fix, just in this tile's
                                    // copy of the gesture stack.
                                    val (_, charHeight) = latestCharMetrics.value
                                    if (charHeight <= 0f) continue
                                    val maxOffset = buffer.maxScrollOffset
                                    when {
                                        y < edgePx -> {
                                            val strength = ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                                            lastScrollWasEdgeAutoScroll = true
                                            val prevOffset = scrollOffset
                                            scrollOffset = (scrollOffset + (strength * maxLinesPerFrame).roundToInt()).coerceIn(0, maxOffset)
                                            // Keep anchor/focus pointing at the same buffer
                                            // content now that scrollOffset just moved under
                                            // them - see TerminalSelectionState.shiftRows' own
                                            // doc. Without this the selection stayed alive
                                            // (lastScrollWasEdgeAutoScroll above already
                                            // prevents the clear) but silently slid/came out
                                            // incomplete against the newly-scrolled buffer.
                                            val applied = scrollOffset - prevOffset
                                            if (applied != 0) {
                                                paneSelectionState.shiftRows(applied)
                                                paneSelectionState.recomputeFrom(buffer, scrollOffset)
                                            }
                                        }
                                        y > h - edgePx -> {
                                            val strength = ((y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                                            lastScrollWasEdgeAutoScroll = true
                                            val prevOffset = scrollOffset
                                            scrollOffset = (scrollOffset - (strength * maxLinesPerFrame).roundToInt()).coerceIn(0, maxOffset)
                                            val applied = scrollOffset - prevOffset
                                            if (applied != 0) {
                                                paneSelectionState.shiftRows(applied)
                                                paneSelectionState.recomputeFrom(buffer, scrollOffset)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                        // Single gesture loop per pane, merging what used to be
                        // two independent, stacked pointerInput blocks (one
                        // awaitEachGesture for tap-focus/mouse-reporting, one
                        // detectTransformGestures for pinch-zoom/pan). Two
                        // sibling pointerInput modifiers both read the SAME
                        // raw pointer stream - Compose doesn't merge their
                        // interpretation of it, so the first block's loop
                        // (which starts consuming/awaiting on every down)
                        // regularly finished or claimed the gesture before
                        // detectTransformGestures ever saw a coherent second
                        // pointer, which is why pinch-to-zoom (and, less
                        // consistently, pan) never worked reliably here even
                        // though the identical zoom math worked fine on the
                        // primary pane. This is the exact hazard the primary
                        // pane's own gesture loop in MainActivity documents
                        // (see its "give the child first, uncontested crack
                        // at every down" comment) - merging into one loop
                        // here applies that same fix at the tile level: one
                        // reader of the pointer stream, one place that
                        // decides mouse vs. pinch vs. pan vs. tap, and (by
                        // waiting rather than eagerly consuming single-finger
                        // movement) an uncontested window for TerminalView's
                        // own internal long-press-to-select detector to
                        // claim the gesture first, same as the primary pane.
                        .pointerInput(runtimeId) {
                            // Hover-only MOVE reporting (xterm 1003/ANY_EVENT)
                            // for a real mouse with no button held - same as
                            // MainActivity's own hover block, previously
                            // missing here entirely.
                            with(MouseGestureTracker) {
                                runMouseHoverGesture(
                                    wantsHover = onWantsMouseMoveEvents,
                                    // latestCharMetrics.value - see the edge-auto-scroll
                                    // block above's doc, same stale-closure fix.
                                    charSize = { latestCharMetrics.value },
                                    bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                ) { col, row ->
                                    val (cw, ch) = latestCharMetrics.value
                                    lastMousePosition = Offset(col * cw, row * ch)
                                    onMouseEvent(TerminalEmulator.MouseEventKind.MOVE, col, row, 0)
                                }
                            }
                        }
                        .pointerInput(runtimeId) {
                            // Physical mouse/trackpad scroll wheel - same gap
                            // and same fix as MainActivity's own primary-pane
                            // wheel block; see runMouseWheelGesture's doc for
                            // the full rationale.
                            with(MouseGestureTracker) {
                                runMouseWheelGesture(
                                    wantsWheelReporting = onWantsMouseEvents,
                                    emitWheelToApp = { kind, col, row -> onMouseEvent(kind, col, row, 0) },
                                    // latestCharMetrics.value - same stale-closure fix as
                                    // the hover/edge-scroll blocks above.
                                    charSize = { latestCharMetrics.value },
                                    bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                    lastPointerPosition = { lastMousePosition },
                                ) { deltaLines ->
                                    val buf = buffer ?: return@runMouseWheelGesture
                                    val maxOffset = buf.maxScrollOffset
                                    val prevOffset = scrollOffset
                                    scrollOffset = (scrollOffset + deltaLines.roundToInt()).coerceIn(0, maxOffset)
                                    val applied = scrollOffset - prevOffset
                                    if (applied != 0) {
                                        paneSelectionState.shiftRows(applied)
                                        paneSelectionState.recomputeFrom(buf, scrollOffset)
                                    }
                                }
                            }
                        }
                        .pointerInput(runtimeId) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                // Captured BEFORE onFocus() below can change this
                                // tile's focus state - see focusedBeforeGesture's own
                                // doc above. This is "was THIS tile already the
                                // focused one right before this exact tap landed",
                                // used only by the plain-tap keyboard-toggle branch
                                // further down.
                                val wasFocusedBeforeThisTap = focusedBeforeGesture
                                // onFocus()/focusToken fire unconditionally on every
                                // down (routes VirtualKeyBar/keymapper to this tile the
                                // instant a finger lands, even if the gesture turns out
                                // to be a scroll/pinch/selection rather than a tap).
                                //
                                // wantsKeyboard is deliberately NOT touched here anymore
                                // - it used to be set right on this same down, before
                                // this gesture is known to be a plain tap at all. Since
                                // this whole pointerInput block was merged into one
                                // shared reader of the raw pointer stream (see this
                                // modifier's own doc above, "single gesture loop per
                                // pane"), EVERY down here also covers starting a
                                // scrollback drag, a pinch, or a long-press-to-select -
                                // toggling the IME's wanted state that early meant
                                // starting to scroll or select in a tile with the
                                // keyboard closed silently reopened it (and vice versa
                                // while it was open), regardless of what the gesture
                                // turned out to be - "IME acılıyor kapalı olsada". The
                                // toggle now only runs once this gesture is actually
                                // confirmed to be a plain tap - see the "Lifted before
                                // the timeout - plain tap" branch below, mirroring
                                // MainActivity's own primary-pane gesture loop, which
                                // makes this same decision only after its own
                                // moved/stolenByTerminalView classification, not on the
                                // initial down either.
                                onFocus()
                                focusToken++
                                // New touch landing: abort any in-flight fling from a
                                // previous release and start tracking velocity fresh,
                                // unconditionally (a fling from a previous mouse-tracking
                                // gesture could still be in-flight when a new one starts) -
                                // same as the primary pane and SplitTerminalPane.
                                scrollFling.reset()
                                scrollFling.track(down.uptimeMillis, down.position)
                                edgeWheelAutoScroll.reset()

                                if (onWantsMouseEvents() && latestCharMetrics.value.first > 0f && latestCharMetrics.value.second > 0f) {
                                    // Mouse reporting owns the whole gesture,
                                    // same as before: press/drag/release become
                                    // xterm mouse escape sequences instead of
                                    // tap-to-focus/pinch/pan. Sharpened via the
                                    // shared MouseGestureTracker - see
                                    // MainActivity's own mouse-report block for
                                    // what this fixes over the old inline
                                    // version (edge clamping, historical-sample
                                    // coalescing, real button id).
                                    down.consume()
                                    var lastCol = 0
                                    var lastRow = 0
                                    with(MouseGestureTracker) {
                                        runMouseReportGesture(
                                            down = down,
                                            // latestCharMetrics.value - same stale-closure
                                            // fix as this block's siblings above.
                                            charSize = { latestCharMetrics.value },
                                            bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                            onMove = { uptimeMillis, position ->
                                                scrollFling.track(uptimeMillis, position)
                                            },
                                            edgeAutoScroll = { uptimeMillis, position, viewportHeightPx, ecol, erow ->
                                                if (viewportHeightPx > 0f) {
                                                    val edgeFraction = 0.12f
                                                    val edgePx = viewportHeightPx * edgeFraction
                                                    val y = position.y
                                                    when {
                                                        y < edgePx -> {
                                                            val strength = ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = strength,
                                                                towardScrollback = true,
                                                                col = ecol, row = erow,
                                                            ) { kind, c, r -> onMouseEvent(kind, c, r, 0) }
                                                        }
                                                        y > viewportHeightPx - edgePx -> {
                                                            val strength = ((y - (viewportHeightPx - edgePx)) / edgePx).coerceIn(0f, 1f)
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = strength,
                                                                towardScrollback = false,
                                                                col = ecol, row = erow,
                                                            ) { kind, c, r -> onMouseEvent(kind, c, r, 0) }
                                                        }
                                                        else -> {
                                                            // Outside both edge bands - disarm the dwell
                                                            // timer so re-entering either edge starts a
                                                            // fresh armDelayMillis wait.
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = 0f,
                                                                towardScrollback = true,
                                                                col = ecol, row = erow,
                                                            ) { _, _, _ -> }
                                                        }
                                                    }
                                                }
                                            },
                                        ) { kind, col, row, button ->
                                            lastCol = col; lastRow = row
                                            onMouseEvent(kind, col, row, button)
                                        }
                                    }
                                    scrollFling.releaseAsWheelEvents(
                                        // latestCharMetrics.value - same stale-closure fix.
                                        charHeightPx = { latestCharMetrics.value.second },
                                        col = lastCol,
                                        row = lastRow,
                                    ) { kind, col, row ->
                                        onMouseEvent(kind, col, row, 0)
                                    }
                                    return@awaitEachGesture
                                }

                                // No mouse reporting: give TerminalView's own
                                // long-press-select detector first crack at a
                                // stationary single finger, exactly like the
                                // primary pane. Watch without consuming until
                                // either a second finger lands (pinch), the
                                // finger moves past touch slop (pan), the
                                // system long-press timeout elapses (hand off
                                // to selection entirely), or the finger lifts
                                // (plain tap - nothing left to do here).
                                val longPressDeadline = System.nanoTime() +
                                    viewConfiguration.longPressTimeoutMillis * 1_000_000L
                                var longPressCandidate = true
                                var pinchStartMidY: Float? = null
                                while (longPressCandidate) {
                                    val remainingMillis = (longPressDeadline - System.nanoTime()) / 1_000_000L
                                    if (remainingMillis <= 0L) break
                                    val event = withTimeoutOrNull(remainingMillis) { awaitPointerEvent() } ?: break
                                    val changes = event.changes
                                    val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                                    if (primary == null || !changes.any { it.pressed }) {
                                        // Lifted before the timeout - plain tap. This is
                                        // the one place in this gesture loop that's
                                        // actually confirmed NOT to be a scroll/pinch/
                                        // long-press, so this is where the IME toggle
                                        // belongs (see the down-handling block above's
                                        // doc for why it was moved out of awaitFirstDown).
                                        //
                                        // Only actually TOGGLES when this tile was
                                        // already the focused one before this tap
                                        // (wasFocusedBeforeThisTap) - a real re-tap on
                                        // the tile you're already typing into. A tap
                                        // that's switching focus IN from elsewhere
                                        // leaves wantsKeyboard untouched, so whatever
                                        // this tile's own remembered
                                        // open/closed preference was (from the last
                                        // time IT was focused) simply takes effect
                                        // as-is once `active = isFocused && wantsKeyboard`
                                        // recomposes true for it.
                                        //
                                        // Previously this read the GLOBAL
                                        // `keyboardOpenNow` (WindowInsets.ime, one
                                        // signal per WINDOW, not per tile) for every
                                        // tap, switch-in included. That meant tapping
                                        // INTO a tile always inverted whatever the
                                        // PREVIOUSLY-focused tile's keyboard state
                                        // happened to be, regardless of what this
                                        // tile's own state was: e.g. tile A open,
                                        // switch to tile B which you'd deliberately
                                        // left closed earlier - keyboardOpenNow read
                                        // true (from A), so `!keyboardOpenNow` forced
                                        // wantsKeyboard = false for B even though B's
                                        // own remembered preference had nothing to do
                                        // with A. Or the reverse - switching to a tile
                                        // you'd left closed while the CURRENT tile
                                        // is/was also closed forced it back OPEN. This
                                        // is the actual "IME açılıyor kapalı olsada"
                                        // bug: it was never about focus-loss not
                                        // hiding the field (that's the separate
                                        // activationKey/primaryPaneFocused fix already
                                        // made) - it's that the toggle decision itself
                                        // read a window-global signal to make a
                                        // per-tile decision.
                                        if (softKeyboardEnabled && wasFocusedBeforeThisTap) {
                                            wantsKeyboard = !keyboardOpenNow
                                            focusToken++
                                        }
                                        return@awaitEachGesture
                                    }
                                    val pressedNow = changes.filter { it.pressed }
                                    if (pressedNow.size >= 2) {
                                        // Second finger landed - pinch, not a
                                        // long-press. Hand off to the merged
                                        // pinch/pan loop below, seeded with
                                        // this event's own midpoint so the
                                        // very first pan delta is against a
                                        // real previous position, not null.
                                        val p1 = pressedNow[0]
                                        val p2 = pressedNow[1]
                                        pinchStartMidY = (p1.position.y + p2.position.y) / 2f
                                        longPressCandidate = false
                                        break
                                    }
                                    val totalDx = primary.position.x - down.position.x
                                    val totalDy = primary.position.y - down.position.y
                                    if (kotlin.math.sqrt(totalDx * totalDx + totalDy * totalDy) > viewConfiguration.touchSlop) {
                                        // Real single-finger movement - this is
                                        // a pan, not a long-press. Apply this
                                        // event's own delta right away (same
                                        // fix as the primary pane: otherwise
                                        // the bit of motion that crossed touch
                                        // slop is silently dropped) and fall
                                        // through to the pan/pinch loop below.
                                        primary.consume()
                                        // latestCharMetrics.value - same stale-closure fix
                                        // as this tile's other gesture blocks above.
                                        val (_, charHeight) = latestCharMetrics.value
                                        if (charHeight > 0f) {
                                            val deltaLines = -(totalDy / charHeight)
                                            val maxOffset = buffer.maxScrollOffset
                                            // Flag before writing scrollOffset - see
                                            // lastScrollWasEdgeAutoScroll's doc above.
                                            // A drag that starts with an active
                                            // selection should extend/preserve it
                                            // instead of wiping it on this first
                                            // motion, same as the primary pane's
                                            // draggingWithSelection handling.
                                            lastScrollWasEdgeAutoScroll = paneSelectionState.selectedTexts.isNotEmpty()
                                            val prevOffset = scrollOffset
                                            scrollOffset = (scrollOffset + deltaLines.roundToInt()).coerceIn(0, maxOffset)
                                            // Same shiftRows/recomputeFrom compensation as the
                                            // edge-auto-scroll block above - a plain drag
                                            // anywhere in the pane can also move scrollOffset
                                            // while a selection stays alive, so it needs the
                                            // identical fix or the selection slides/comes out
                                            // incomplete just like the edge case did.
                                            if (lastScrollWasEdgeAutoScroll) {
                                                val applied = scrollOffset - prevOffset
                                                if (applied != 0) {
                                                    paneSelectionState.shiftRows(applied)
                                                    paneSelectionState.recomputeFrom(buffer, scrollOffset)
                                                }
                                            }
                                        }
                                        longPressCandidate = false
                                        break
                                    }
                                    // Still down, stationary, timeout not yet
                                    // reached - keep waiting without consuming.
                                }
                                if (longPressCandidate) {
                                    // Timeout reached, finger still down and
                                    // stationary: this is a long-press. Don't
                                    // read the pointer stream again - hand the
                                    // rest of the gesture to TerminalView's own
                                    // selection detector uncontested.
                                    return@awaitEachGesture
                                }

                                var lastMidY: Float? = pinchStartMidY

                                while (true) {
                                    val event = awaitPointerEvent()
                                    val changes = event.changes
                                    val pressed = changes.filter { it.pressed }
                                    if (pressed.isEmpty()) break
                                    if (pressed.size >= 2) {
                                        val p1 = pressed[0]
                                        val p2 = pressed[1]
                                        p1.consume(); p2.consume()
                                        val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
                                        val curDist = (p1.position - p2.position).getDistance()
                                        if (prevDist > 0f && zoomEnabled) {
                                            val zoom = curDist / prevDist
                                            if (zoom != 1f) {
                                                val newSize = (latestEffectiveFontSizeSp.value * zoom).coerceIn(8f, 32f)
                                                zoomSizeSp = newSize
                                            }
                                        }
                                        val midY = (p1.position.y + p2.position.y) / 2f
                                        val prevMidY = lastMidY
                                        if (prevMidY != null) {
                                            // latestCharMetrics.value - the actual root
                                            // cause of the same "imleç/ekran yukarı kayıyor"
                                            // bug in this tile: this pinch/pan loop lives
                                            // inside pointerInput(runtimeId), which only
                                            // relaunches when runtimeId changes, so the
                                            // plain charMetrics closed over here was frozen
                                            // at whatever font size was active before this
                                            // gesture (or session) started - scrolling
                                            // WHILE pinching kept computing deltaLines
                                            // against that stale row height instead of the
                                            // live one, drifting content/cursor position
                                            // further off with every such frame.
                                            val (_, charHeight) = latestCharMetrics.value
                                            if (charHeight > 0f) {
                                                val deltaLines = -((midY - prevMidY) / charHeight)
                                                if (deltaLines != 0f) {
                                                    val maxOffset = buffer.maxScrollOffset
                                                    lastScrollWasEdgeAutoScroll = paneSelectionState.selectedTexts.isNotEmpty()
                                                    val prevOffset = scrollOffset
                                                    scrollOffset = (scrollOffset + deltaLines.roundToInt()).coerceIn(0, maxOffset)
                                                    if (lastScrollWasEdgeAutoScroll) {
                                                        val applied = scrollOffset - prevOffset
                                                        if (applied != 0) {
                                                            paneSelectionState.shiftRows(applied)
                                                            paneSelectionState.recomputeFrom(buffer, scrollOffset)
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        lastMidY = midY
                                    } else {
                                        lastMidY = null
                                        val change = pressed.first()
                                        change.consume()
                                        val dy = change.position.y - change.previousPosition.y
                                        if (dy != 0f) {
                                            // latestCharMetrics.value - same stale-closure
                                            // fix as the two-finger branch just above.
                                            val (_, charHeight) = latestCharMetrics.value
                                            if (charHeight > 0f) {
                                                val deltaLines = -(dy / charHeight)
                                                val maxOffset = buffer.maxScrollOffset
                                                lastScrollWasEdgeAutoScroll = paneSelectionState.selectedTexts.isNotEmpty()
                                                val prevOffset = scrollOffset
                                                scrollOffset = (scrollOffset + deltaLines.roundToInt()).coerceIn(0, maxOffset)
                                                if (lastScrollWasEdgeAutoScroll) {
                                                    val applied = scrollOffset - prevOffset
                                                    if (applied != 0) {
                                                        paneSelectionState.shiftRows(applied)
                                                        paneSelectionState.recomputeFrom(buffer, scrollOffset)
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                ) {
                    // Each pane gets its own independent native selection
                    // state (see TerminalView's selectionState param doc,
                    // added by the copy/paste API migration this file was
                    // written before) - long-press/drag selection and the
                    // OS's own selection handles work per-pane exactly as
                    // they do on the primary pane, since TerminalView owns
                    // the native selection UI internally.
                    //
                    // The Copy/Paste toolbar itself is a different story
                    // from what the comment above used to claim: TerminalView
                    // deliberately SUPPRESSES the platform's own selection
                    // bubble (NoOpTextToolbar / NoOpTextContextMenuProvider -
                    // see TerminalView.kt's doc) because on this Compose
                    // Foundation version that bubble raced visibly against
                    // this app's own bar. The primary pane and
                    // SplitTerminalPane both replace it with their own
                    // ActionModeController-driven SelectionActionBar; this
                    // file never got that wiring added, which is why a
                    // multi-pane tile could show the blue selection
                    // highlight (SelectionContainer's own handles) but never
                    // any Copy/Paste/More bar - nothing was left to show one
                    // once the native fallback was suppressed.
                    // paneSelectionState/its clear-on-runtimeId effect are
                    // declared up above this Box now, alongside
                    // lastScrollWasEdgeAutoScroll, so the gesture loop can
                    // read them - see that declaration's doc.
                    val actionModeController = rememberActionModeController()
                    val clipboardManager = LocalClipboardManager.current
                    var moreVisible by remember(runtimeId) { androidx.compose.runtime.mutableStateOf(false) }
                    // Keyed on the full selectedTexts list, not isNotEmpty() -
                    // same reasoning as the primary pane / SplitTerminalPane's
                    // identical effect: isNotEmpty() is a Boolean and only
                    // re-fires on a false<->true edge, so a selection that
                    // re-anchors (e.g. while dragging a handle) without ever
                    // fully emptying would leave this effect stale.
                    androidx.compose.runtime.LaunchedEffect(paneSelectionState.selectedTexts.toList()) {
                        if (paneSelectionState.selectedTexts.isEmpty()) {
                            actionModeController.hide()
                        } else {
                            actionModeController.show(
                                onCopy = {
                                    val text = paneSelectionState.selectedTexts.joinToString("\n")
                                    if (text.isNotEmpty()) clipboardManager.setText(AnnotatedString(text))
                                    paneSelectionState.clear()
                                    actionModeController.hide()
                                },
                                onPaste = {
                                    // Always offered (button never hidden for an
                                    // empty clipboard) - read the clipboard at
                                    // TAP time, not at LaunchedEffect-fire time.
                                    // The old `clipboardManager.getText()?.text
                                    // ?.let { pasted -> {...} }` pattern read the
                                    // clipboard once, synchronously, right when
                                    // this LaunchedEffect fired (i.e. the instant
                                    // the selection changed) - if the clipboard
                                    // was empty at that exact moment, onPaste
                                    // became null and the Paste button vanished
                                    // from the bar entirely for the rest of that
                                    // selection, even if something got copied a
                                    // second later. Matches the primary pane's
                                    // own onPaste (MainActivity), which is a
                                    // plain always-present lambda for the same
                                    // reason.
                                    clipboardManager.getText()?.text?.let { pasted ->
                                        if (pasted.isNotEmpty()) {
                                            // Same CR/LF fixup as the primary pane's
                                            // onPaste - real terminals want CR for a
                                            // line break, not the LF a multi-line
                                            // clipboard selection naturally contains.
                                            scrollOffset = 0
                                            onInput(pasted.replace('\n', '\r'))
                                        }
                                    }
                                    paneSelectionState.clear()
                                    actionModeController.hide()
                                },
                                // Only offered when the caller actually wired
                                // at least one action - see onCloneSession/
                                // onToggleWakeUp/onSaveSession's own doc on
                                // this function's signature. Every existing
                                // caller of MultiPaneContainer left these
                                // null before this param existed, so this
                                // stays a no-op (More button hidden) unless
                                // MainActivity opts a specific tile in.
                                onMore = if (onCloneSession != null || onToggleWakeUp != null || onSaveSession != null) {
                                    { moreVisible = true }
                                } else {
                                    null
                                }
                            )
                        }
                    }
                    TerminalView(
                        buffer = buffer,
                        palette = palette,
                        fontFamily = fontFamily,
                        fontSizeSp = effectiveFontSizeSp,
                        bufferVersion = bufferVersion,
                        backgroundAlpha = 1f,
                        scrollOffset = scrollOffset,
                        selectionState = paneSelectionState,
                        // Same Material primary @ ~25% alpha as the single-pane and
                        // split-pane terminals - consistent selection highlight across
                        // every multi-pane tile regardless of that tile's own palette.
                        highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f).toArgb(),
                        handleColor = MaterialTheme.colorScheme.primary.toArgb(),
                        // zoomSizeSp != null: this tile is mid pinch-zoom, rendering at
                        // a live/preview effectiveFontSizeSp that hasn't reached
                        // TerminalSession.resize() yet (that only happens once
                        // onSizeChanged's own 120ms - or 32ms while manually
                        // resizing - debounce above actually fires). Exactly the same
                        // gap MainActivity/SplitTerminalPane's own suppressCursor doc
                        // describes: until that commit lands, buffer.cursorRow/
                        // cursorCol are still the OLD grid coordinates, and drawTerminal
                        // would multiply them by the NEW live charWidth/charHeight,
                        // detaching the block cursor from the actual glyph grid for the
                        // whole gesture - a stray white block drifting away from (often
                        // upward of) where the cursor actually is. This tile never got
                        // that suppressCursor wiring when the fix landed on the other
                        // two panes, which is why "imleç zoom yaparken yukarı kayıyor"
                        // kept reproducing specifically in multi-pane/floating mode.
                        suppressCursor = zoomSizeSp != null,
                        modifier = Modifier.fillMaxSize()
                    )
                    // Anchored in this same Box as TerminalView (top-center
                    // popup, see SelectionActionBar's own doc) so it renders
                    // directly over this pane's own tile, not some shared
                    // coordinate space that could land it over a different
                    // pane entirely.
                    SelectionActionBar(
                        actionModeController,
                        // See MainActivity's identical call site: focusRow is
                        // always the actively-dragged handle, so it - not
                        // minOf(anchor, focus) - is what should drive the flip.
                        handleRow = paneSelectionState.focusRow,
                        // Same reasoning as MainActivity's call site: hide the
                        // bar while a handle is actually being dragged.
                        hideWhileDragging = paneSelectionState.draggingHandle,
                        // Real pixel bounds, same fix as MainActivity's call
                        // site - see HandleClearingPositionProvider's doc.
                        handleTopPx = paneSelectionState.focusRow * charMetrics.second,
                        handleBottomPx = (paneSelectionState.focusRow + 1) * charMetrics.second,
                        rowHeightPx = charMetrics.second,
                    )
                    // Same MoreActionsPopup the primary pane and
                    // SplitTerminalPane use - onToggleSplitScreen is always
                    // null here (see this function's doc), so that row
                    // never renders for a multi-pane tile regardless of
                    // splitScreenActive's value.
                    if (onCloneSession != null || onToggleWakeUp != null || onSaveSession != null) {
                        MoreActionsPopup(
                            visible = moreVisible,
                            actions = MoreMenuActions(
                                onCloneSession = onCloneSession ?: {},
                                onToggleWakeUp = onToggleWakeUp ?: {},
                                wakeUpActive = wakeUpActive,
                                onToggleSplitScreen = null,
                                splitScreenActive = false,
                                onSave = onSaveSession ?: {}
                            ),
                            onDismiss = {
                                moreVisible = false
                                paneSelectionState.clear()
                                actionModeController.hide()
                            }
                        )
                    }
                    // Typing always jumps back to the live screen, matching
                    // the primary pane's own sendInput behavior - handled by
                    // the caller-supplied onInput wrapper resetting scroll
                    // via a side effect would be more surprising here than
                    // just resetting locally right before forwarding.
                    //
                    // wantsKeyboardOn factors in softKeyboardEnabled, which
                    // `active` was missing entirely: MainActivity's own
                    // primary pane never even calls requestFocus()/show() when
                    // this setting is off (see its onTap branch, gated
                    // `... && softKeyboardEnabled`), but this tile's `active`
                    // was just `isFocused && wantsKeyboard` - wantsKeyboard
                    // defaults true and the tap handler that could ever set it
                    // false is ITSELF gated behind softKeyboardEnabled (so it
                    // never runs while the setting is off), meaning
                    // wantsKeyboard stayed permanently true and `active` was
                    // driven by isFocused alone. Any tile that gained focus
                    // therefore still called requestFocus() + show(ime())
                    // below regardless of the setting - the real IME popped
                    // up over split/multi-pane tiles even with "disable soft
                    // keyboard" on, while the primary pane correctly never did.
                    val wantsKeyboardOn = isFocused && wantsKeyboard && softKeyboardEnabled
                    HiddenPaneInputField(
                        // wantsKeyboard added so a tap toggle-close (see the
                        // tap gesture above) can hide the IME without giving
                        // up this tile's input focus - isFocused alone can't
                        // represent "focused but keyboard dismissed".
                        active = wantsKeyboardOn,
                        // Was `focusToken` alone. isFocused here is DERIVED
                        // (pane.runtimeId == focusedRuntimeId, recomposed
                        // automatically by Compose whenever ANOTHER tile is
                        // tapped), unlike focusToken which is local state
                        // this tile only ever bumps on a tap into ITSELF.
                        // So when a different tile was tapped, this tile's
                        // isFocused correctly flipped to false on
                        // recomposition, but activationKey never changed -
                        // LaunchedEffect(activationKey) never re-ran, hide()
                        // was never called, and this tile's IME stayed open
                        // over the newly-focused tile. Pairing in the
                        // derived active value means a focus-loss with no
                        // local focusToken bump still changes the key, so
                        // the effect re-fires and reads the now-current
                        // latestActive.value (false) - same
                        // "outgoing pane never hides" bug as
                        // SplitTerminalPane's own isFocused/primaryPaneFocused
                        // fix, different mechanism because this tile's
                        // isFocused is derived rather than locally owned.
                        // Keyed on wantsKeyboardOn itself (not a narrower
                        // isFocused/wantsKeyboard pair) so flipping
                        // softKeyboardEnabled off mid-session, while a tile's
                        // real IME is showing, also re-fires this and hides
                        // it - not just focus/wantsKeyboard changes.
                        activationKey = focusToken to wantsKeyboardOn,
                        onText = { text ->
                            scrollOffset = 0
                            onInput(text)
                        },
                        onRequestShow = onImeRequestShow,
                        onRequestHide = onImeRequestHide
                    )
                }
            } else {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Session ended", color = Color.White.copy(alpha = 0.5f), style = MaterialTheme.typography.bodyMedium)
                }
            }

            if (showDragHandle) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .size(24.dp)
                        .pointerInput(runtimeId) {
                            // Same cumulative-total fix as the header drag
                            // above - see its comment for why per-frame
                            // dragAmount alone caused jitter instead of a
                            // smooth resize.
                            var accumulated = Offset.Zero
                            detectDragGestures(
                                onDragStart = {
                                    accumulated = Offset.Zero
                                    onDragStart()
                                    // See isManuallyResizing's own doc above -
                                    // switches onSizeChanged's debounce down
                                    // to a short throttle for the duration of
                                    // this drag so the terminal grid actually
                                    // follows the finger instead of only
                                    // catching up once it lifts.
                                    isManuallyResizing = true
                                    // Reset so the very first onSizeChanged of
                                    // this new drag commits immediately
                                    // instead of waiting out the 32ms window
                                    // left over from whenever the previous
                                    // drag's last commit happened to land.
                                    lastManualResizeCommitMs = 0L
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    onResizeDrag(accumulated)
                                },
                                onDragEnd = {
                                    // Drop straight back to the normal 120ms
                                    // debounce, AND fire one final immediate
                                    // commit of whatever size the last
                                    // onSizeChanged reported - without this,
                                    // if the finger lifts before the last
                                    // in-flight 32ms job fires, the very last
                                    // few pixels of the drag could otherwise
                                    // wait out a full 120ms (the next
                                    // onSizeChanged's debounce, now back to
                                    // its normal length) before the pty
                                    // caught up, reintroducing exactly the
                                    // lag this change exists to remove right
                                    // at the moment the drag ends.
                                    isManuallyResizing = false
                                    paneResizeDebounceJob?.cancel()
                                    val (charWidth, charHeight) = latestCharMetrics.value
                                    val finalSize = latestPaneSizePx
                                    if (charWidth > 0f && charHeight > 0f && finalSize != null) {
                                        val cols = (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                        val rws = (finalSize.height / charHeight).toInt().coerceAtLeast(1)
                                        latestOnMeasuredSize.value(cols, rws, finalSize.width, finalSize.height)
                                    }
                                },
                                onDragCancel = {
                                    // Same immediate-commit reasoning as
                                    // onDragEnd - a cancelled gesture still
                                    // leaves pane.floatSize at wherever the
                                    // drag last moved it, so the pty still
                                    // needs to catch up to that final size
                                    // rather than being left waiting on a
                                    // debounce that a lifted/cancelled finger
                                    // will never trigger another onSizeChanged
                                    // to restart.
                                    isManuallyResizing = false
                                    paneResizeDebounceJob?.cancel()
                                    val (charWidth, charHeight) = latestCharMetrics.value
                                    val finalSize = latestPaneSizePx
                                    if (charWidth > 0f && charHeight > 0f && finalSize != null) {
                                        val cols = (finalSize.width / charWidth).toInt().coerceAtLeast(1)
                                        val rws = (finalSize.height / charHeight).toInt().coerceAtLeast(1)
                                        latestOnMeasuredSize.value(cols, rws, finalSize.width, finalSize.height)
                                    }
                                }
                            )
                        }
                ) {
                    // Full opacity while actively being dragged (0.35f the
                    // rest of the time, same as before) - a static low alpha
                    // made the handle hard to track under the finger during
                    // the resize itself, exactly when the user needs the
                    // clearest visual feedback ("Floating boyutlandirirken
                    // parmak opakligi okadar iyi sayilmaz"). isFocused isn't
                    // involved here on purpose: this is about the drag
                    // gesture's own state, not which pane has input focus.
                    Icon(
                        Icons.Filled.OpenWith,
                        contentDescription = "Drag to resize",
                        tint = Color.White.copy(alpha = if (isManuallyResizing) 0.9f else 0.35f),
                        modifier = Modifier.size(16.dp).align(Alignment.BottomEnd)
                    )
                }
            }
        }
    }
}

/**
 * Per-pane invisible text field that forwards typed characters straight to
 * [onText] - this is what "type here kaldir, kullanici dogrudan terminala
 * yazsin" means in multi-pane mode: tapping a pane's terminal (see
 * PaneContent's tap gesture, which calls onFocus -> isFocused -> [active])
 * requests the soft keyboard directly against the terminal area, no
 * separate OutlinedTextField/"Type here..." box the way the old
 * SplitTerminalPane had one.
 *
 * Simpler than MainActivity's single shared hidden field (no
 * consumedBaseline growth-cap dance) because each pane's field only needs
 * to stay alive/focused while THIS pane is the focused one. Now uses the
 * same append-baseline architecture as the primary pane's hidden field
 * (see HiddenPaneInputField's own doc below for why the old reset-every-
 * keystroke approach broke Enter specifically in split screen).
 */
@Composable
internal fun HiddenPaneInputField(
    active: Boolean,
    onText: (String) -> Unit,
    activationKey: Any = active,
    // Split-screen-only override: lets SplitTerminalPane route show()/hide()
    // through MainActivity's single insetsController + WindowInsetsAnimation
    // ground-truth instead of this field deriving its own local
    // WindowInsetsControllerCompat below. Null (the default, and the only
    // thing MultiPaneContainer's own tiling callers ever pass) keeps this
    // field's original self-contained behavior exactly as-is - multi-pane
    // mode has no glitch and isn't part of this change. Only SplitTerminalPane
    // supplies these, collapsing what used to be a 3rd independent
    // insetsController (this field's own, derived from LocalView here) down
    // to zero for the split-screen path - every IME show/hide call for split
    // screen now originates from the same single controller MainActivity's
    // own WindowInsetsAnimationCompat.Callback is already attached to, so
    // there's no longer a second, unsynchronized controller instance that
    // could issue a show()/hide() the platform's own animation callback
    // doesn't know about.
    onRequestShow: (() -> Unit)? = null,
    onRequestHide: (() -> Unit)? = null
) {
    val placeholder = "\u200B"
    var value by remember { mutableStateOf(TextFieldValue(placeholder, selection = TextRange(placeholder.length))) }
    var consumedBaseline by remember { mutableStateOf(placeholder) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
    // Only actually derived/used when the caller didn't supply
    // onRequestShow/onRequestHide (i.e. every MultiPaneContainer tiling
    // caller) - see the show()/hide() call sites below.
    val insetsController = remember(view) {
        val activity = view.context as? android.app.Activity
        activity?.window?.let { window ->
            androidx.core.view.WindowInsetsControllerCompat(window, view)
        }
    }
    // onText arrives as a new lambda on every recomposition (it closes over
    // state from SplitTerminalPane/MainActivity that changes). Without
    // rememberUpdatedState the onValueChange closure below captures whatever
    // lambda was current at the last remember{} call - a stale version that
    // sees old state (e.g. splitExited=false right after a kill). This is
    // exactly why Enter did nothing: by the time the user pressed Enter,
    // onText had already been captured in the closure before exited=true was
    // propagated, and no recomposition had run to refresh it.
    val latestOnText = rememberUpdatedState(onText)
    val latestActive = rememberUpdatedState(active)

    LaunchedEffect(activationKey) {
        // Reads latestActive.value, NOT the raw `active` parameter: this
        // coroutine restarts the instant activationKey (focusToken) changes,
        // which the tap gesture bumps in the SAME write batch as the
        // onFocus() call that flips the PARENT's focusedRuntimeId (and
        // therefore this composable's own `isFocused` -> `active` param)
        // - but onFocus() only updates parent state, it doesn't recompose
        // this composable synchronously. A newly-focused tile's very first
        // tap therefore had this coroutine relaunch on the new focusToken
        // while still closing over the OLD `active` (false, from before
        // isFocused flipped), so requestFocus()/show() were silently
        // skipped - focus visibly moved (the tile highlighted, isFocused
        // eventually flipped) but the IME never appeared. This is the same
        // "focusing oluyor ama IME gorunmuyor" gap the post{} below already
        // fixes for the Compose-recomposition-vs-platform-callback race;
        // this fixes the analogous gap one step earlier, between the parent
        // focus write and this coroutine's read of it. Only affects
        // non-primary panes - the primary pane in MainActivity doesn't use
        // HiddenPaneInputField at all.
        if (latestActive.value) {
            // requestFocus() itself stays synchronous - Compose has already
            // committed the recomposition that puts this FocusRequester's
            // target in the tree by the time a real activationKey CHANGE
            // resumes this coroutine, so there's nothing to gain waiting on
            // that front.
            //
            // But show() still has to go through view.post (next
            // Choreographer frame), same as MainActivity's own hidden field
            // (see its insetsController doc: "a synchronous show() right
            // after requestFocus() races the focus-driven show request the
            // platform already fires on its own"). That's a DIFFERENT gap
            // than the Compose-recomposition one above: requestFocus() only
            // marks Compose's own focus state immediately - the underlying
            // platform View actually receiving focus and standing up a real
            // InputConnection for the IME to attach to happens over a
            // separate callback chain that hasn't necessarily run yet in
            // the same tick. Calling show() before that lands is what let
            // this call site silently no-op: focus visibly moved (fields
            // recomposed, isFocused flipped) but the IME never actually
            // appeared - "focusing oluyor ama IME gorunmuyor" - while every
            // other show() call site in this app already routes through
            // post{} and doesn't see it. A previous pass here read that
            // Compose-recomposition reasoning as covering this too and
            // dropped post{} for both calls; it only ever covered
            // requestFocus(), so only that one stays synchronous.
            focusRequester.requestFocus()
            view.post {
                if (onRequestShow != null) {
                    onRequestShow()
                } else {
                    insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }
        } else {
            // Mirrors the primary pane's own tap-to-close path in
            // MainActivity (focusManager.clearFocus() + a synchronous
            // insetsController.hide(ime()), see its onTap/onCopy/onPaste
            // "else" branches) - that pairing is what makes the primary
            // pane's keyboard open/close cleanly with no animation glitch.
            // This field previously only handled active == true: leaving a
            // pane (split partner losing focus, a multi-pane tile losing
            // focus, or the split/pane closing outright) never told the IME
            // to hide and never released this field's own Compose focus, so
            // the keyboard was left to whatever state it happened to be in -
            // sometimes staying open over a pane that no longer wants it,
            // sometimes only partially animating closed. Calling both here,
            // synchronously and unconditionally whenever activationKey
            // flips to inactive, gives split-screen and multi-pane the same
            // clean full open/close animation the primary pane already has.
            focusManager.clearFocus()
            if (onRequestHide != null) {
                onRequestHide()
            } else {
                insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
            }
        }
    }

    // Covers the case LaunchedEffect(activationKey) above can't: this whole
    // field being torn out of composition while still active == true, e.g.
    // closing the split entirely (SplitTerminalPane's parent `if
    // (splitRuntimeId != null)` block in MainActivity stops composing it)
    // or a multi-pane tile being removed outright rather than merely losing
    // focus. Neither path flips activationKey first - the field just
    // disappears - so without this the keyboard could stay shown over
    // whatever pane happens to be left, anchored to a focus target that no
    // longer exists.
    DisposableEffect(Unit) {
        onDispose {
            if (latestActive.value) {
                focusManager.clearFocus()
                if (onRequestHide != null) {
                    onRequestHide()
                } else {
                    insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
                }
            }
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            val newText = new.text
            when {
                newText.length > consumedBaseline.length && newText.startsWith(consumedBaseline) -> {
                    latestOnText.value(newText.substring(consumedBaseline.length))
                    consumedBaseline = newText
                    if (newText.length > 256 && new.composition == null) {
                        consumedBaseline = placeholder
                    }
                }
                newText.length <= consumedBaseline.length -> {
                    val removedCount = (consumedBaseline.length - newText.length).coerceAtLeast(1)
                    latestOnText.value("\u007F".repeat(removedCount))
                    consumedBaseline = placeholder
                }
                else -> {
                    latestOnText.value(newText)
                    consumedBaseline = placeholder
                }
            }
            // Explicitly construct a fresh TextFieldValue rather than
            // new.copy(...) whenever resetting to the bare placeholder
            // (consumedBaseline == placeholder here). new.copy() carries
            // forward new.composition - the IME's own active composing
            // region - even though the visible/committed text is being
            // yanked back down to the placeholder underneath it. That left
            // Compose's side of the field reset while the IME's own
            // InputConnection still believed it had an open composing
            // region over text that no longer exists on this side (most
            // reachable via the 256-char growth cap above, which can land
            // mid-composition on a fast typing burst or right as an emoji
            // panel commit lands) - the IME's NEXT edit (often exactly an
            // emoji-panel commit, which many keyboards send as "finish
            // composing region" + "commit text" in two separate calls) was
            // then computed against its own stale composing state and
            // landed as something other than the emoji itself once
            // reconciled against this field's already-reset text, which is
            // what surfaced as an emoji tap silently turning into a space.
            // TextFieldValue(text, selection) with no composition argument
            // defaults composition to null, telling Compose (and, via
            // restartInput, the IME) there is no composing region at all -
            // matching reality once the field's been forced back to the
            // placeholder.
            value = if (consumedBaseline == placeholder) {
                TextFieldValue(placeholder, selection = TextRange(placeholder.length))
            } else {
                new.copy(text = newText, selection = new.selection)
            }
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester)
            .onFocusChanged {
                if (it.isFocused && consumedBaseline != placeholder) {
                    consumedBaseline = placeholder
                    value = TextFieldValue(placeholder, selection = TextRange(placeholder.length))
                }
            },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            capitalization = KeyboardCapitalization.None,
            autoCorrect = false
        )
    )
}
