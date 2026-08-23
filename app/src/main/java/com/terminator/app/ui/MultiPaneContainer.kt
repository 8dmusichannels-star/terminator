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
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.terminator.emulator.TerminalBuffer
import com.terminator.emulator.TerminalEmulator
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
    onInput: (runtimeId: String, text: String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onMovePane: (runtimeId: String, offset: Offset) -> Unit,
    onResizePane: (runtimeId: String, size: Size) -> Unit,
    onResizeSessionPty: (runtimeId: String, columns: Int, rows: Int) -> Unit,
    onSetMode: (PaneMode) -> Unit,
    onAddPaneRequested: () -> Unit,
    onExitMultiPane: () -> Unit,
    modifier: Modifier = Modifier,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit = { _, _, _, _ -> },
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
    focusRequestSignal: Int = 0
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
                    onInput = onInput,
                    onFocusPane = onFocusPane,
                    onClosePane = onClosePane,
                    onResizeSessionPty = onResizeSessionPty,
                    onWantsMouseEvents = onWantsMouseEvents,
                    onMouseEvent = onMouseEvent,
                    onCloneSession = onCloneSession,
                    onToggleWakeUp = onToggleWakeUp,
                    wakeUpActiveFor = wakeUpActiveFor,
                    onSaveSession = onSaveSession,
                    focusRequestSignal = focusRequestSignal
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
                    onInput = onInput,
                    onFocusPane = onFocusPane,
                    onClosePane = onClosePane,
                    onMovePane = onMovePane,
                    onResizePane = onResizePane,
                    onResizeSessionPty = onResizeSessionPty,
                    onWantsMouseEvents = onWantsMouseEvents,
                    onMouseEvent = onMouseEvent,
                    onCloneSession = onCloneSession,
                    onToggleWakeUp = onToggleWakeUp,
                    wakeUpActiveFor = wakeUpActiveFor,
                    onSaveSession = onSaveSession
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
    onInput: (String, String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onResizeSessionPty: (String, Int, Int) -> Unit,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit = { _, _, _, _ -> },
    // "More" actions for each tile's own selection bar - see PaneContent's
    // identical params for why these all default to null/false (existing
    // callers keep the "no More button" behavior they always had).
    onCloneSession: ((String) -> Unit)? = null,
    onToggleWakeUp: ((String) -> Unit)? = null,
    wakeUpActiveFor: (String) -> Boolean = { false },
    onSaveSession: ((String) -> Unit)? = null,
    focusRequestSignal: Int = 0
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
                                    onInput = { text -> onInput(pane.runtimeId, text) },
                                    onFocus = { onFocusPane(pane.runtimeId) },
                                    onClose = { onClosePane(pane.runtimeId) },
                                    onMeasuredSize = { cols, rws -> onResizeSessionPty(pane.runtimeId, cols, rws) },
                                    fontDensity = density,
                                    showDragHandle = false,
                                    onWantsMouseEvents = { onWantsMouseEvents(pane.runtimeId) },
                                    onMouseEvent = { kind, col, row -> onMouseEvent(pane.runtimeId, kind, col, row) },
                                    onCloneSession = onCloneSession?.let { { it(pane.runtimeId) } },
                                    onToggleWakeUp = onToggleWakeUp?.let { { it(pane.runtimeId) } },
                                    wakeUpActive = wakeUpActiveFor(pane.runtimeId),
                                    onSaveSession = onSaveSession?.let { { it(pane.runtimeId) } },
                                    focusRequestSignal = focusRequestSignal,
                                    modifier = Modifier.fillMaxSize()
                                )
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
    onInput: (String, String) -> Unit,
    onFocusPane: (String) -> Unit,
    onClosePane: (String) -> Unit,
    onMovePane: (runtimeId: String, offset: Offset) -> Unit,
    onResizePane: (runtimeId: String, size: Size) -> Unit,
    onResizeSessionPty: (String, Int, Int) -> Unit,
    onWantsMouseEvents: (String) -> Boolean = { false },
    onMouseEvent: (runtimeId: String, kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit = { _, _, _, _ -> },
    onCloneSession: ((String) -> Unit)? = null,
    onToggleWakeUp: ((String) -> Unit)? = null,
    wakeUpActiveFor: (String) -> Boolean = { false },
    onSaveSession: ((String) -> Unit)? = null,
    focusRequestSignal: Int = 0
) {
    BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
        val density = LocalDensity.current.density
        val containerWidthDp = constraints.maxWidth / density
        val containerHeightDp = constraints.maxHeight / density

        // Sorted by zIndex so later (higher-stacked) panes draw on top,
        // matching what bringPaneToFront just did to the underlying state.
        panes.sortedBy { it.zIndex }.forEach { pane ->
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
                    onInput = { text -> onInput(pane.runtimeId, text) },
                    onFocus = { onFocusPane(pane.runtimeId) },
                    onClose = { onClosePane(pane.runtimeId) },
                    onMeasuredSize = { cols, rws -> onResizeSessionPty(pane.runtimeId, cols, rws) },
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
                    onMouseEvent = { kind, col, row -> onMouseEvent(pane.runtimeId, kind, col, row) },
                    onCloneSession = onCloneSession?.let { { it(pane.runtimeId) } },
                    onToggleWakeUp = onToggleWakeUp?.let { { it(pane.runtimeId) } },
                    wakeUpActive = wakeUpActiveFor(pane.runtimeId),
                    onSaveSession = onSaveSession?.let { { it(pane.runtimeId) } },
                    focusRequestSignal = focusRequestSignal,
                    modifier = Modifier.fillMaxSize()
                )
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
    onInput: (String) -> Unit,
    onFocus: () -> Unit,
    onClose: () -> Unit,
    onMeasuredSize: (columns: Int, rows: Int) -> Unit,
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
    onMouseEvent: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit = { _, _, _ -> },
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
    focusRequestSignal: Int = 0
) {
    // Per-pane pinch-zoom override, local to this composable only (not
    // persisted) - matches the lightweight-vs-primary-pane tradeoff this
    // whole file documents up top. Resets to the global size whenever the
    // pane's runtimeId changes (a different session took this slot).
    var zoomSizeSp by remember(runtimeId) { androidx.compose.runtime.mutableStateOf<Float?>(null) }
    val effectiveFontSizeSp = zoomSizeSp ?: fontSizeSp

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

    // Local scroll offset into this pane's own scrollback - independent of
    // every other pane's, and of the classic single-pane view's scrollOffset.
    var scrollOffset by remember(runtimeId) { androidx.compose.runtime.mutableIntStateOf(0) }

    // Same focusToken pattern SplitTerminalPane uses: bumped on every real
    // tap into this pane so HiddenPaneInputField's LaunchedEffect fires even
    // when isFocused was already true (a same-value write is a no-op for
    // LaunchedEffect keyed on a boolean). Without this, tapping back into a
    // pane that was already focused left the keyboard silently closed with
    // no field to show it for — the "keyboard bazen açılmıyor" bug in
    // multi-pane mode.
    var focusToken by remember(runtimeId) { androidx.compose.runtime.mutableIntStateOf(0) }

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

        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
            if (buffer != null) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .onSizeChanged { sizePx ->
                            val (charWidth, charHeight) = charMetrics
                            if (charWidth > 0f && charHeight > 0f) {
                                val cols = (sizePx.width / charWidth).toInt().coerceAtLeast(1)
                                val rws = (sizePx.height / charHeight).toInt().coerceAtLeast(1)
                                onMeasuredSize(cols, rws)
                            }
                        }
                        // Single gesture loop per pane. On tap: focus the pane
                        // AND bump focusToken so HiddenPaneInputField's IME
                        // show re-fires even when isFocused was already true —
                        // see focusToken's doc above. Mouse reporting path
                        // mirrors SplitTerminalPane: when the session has
                        // enabled xterm mouse mode (DECSET 1000/1002/1003),
                        // press/drag/release become mouse escape sequences
                        // instead of plain tap-to-focus, so ncurses programs
                        // (mc, vim, htop) running in a multi-pane tile
                        // actually receive mouse input.
                        .pointerInput(runtimeId) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                onFocus()
                                focusToken++
                                val mouseWanted = onWantsMouseEvents()
                                if (!mouseWanted || charMetrics.first <= 0f || charMetrics.second <= 0f) {
                                    return@awaitEachGesture
                                }
                                down.consume()
                                fun cellOf(offset: androidx.compose.ui.geometry.Offset) =
                                    (offset.x / charMetrics.first).toInt() to (offset.y / charMetrics.second).toInt()
                                var (col, row) = cellOf(down.position)
                                onMouseEvent(TerminalEmulator.MouseEventKind.PRESS, col, row)
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull() ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        val (rCol, rRow) = cellOf(change.position)
                                        onMouseEvent(TerminalEmulator.MouseEventKind.RELEASE, rCol, rRow)
                                        break
                                    }
                                    val (dCol, dRow) = cellOf(change.position)
                                    if (dCol != col || dRow != row) {
                                        col = dCol; row = dRow
                                        onMouseEvent(TerminalEmulator.MouseEventKind.DRAG, col, row)
                                    }
                                }
                            }
                        }
                        .pointerInput(runtimeId) {
                            detectTransformGestures { _, pan, zoom, _ ->
                                if (zoom != 1f) {
                                    val newSize = (effectiveFontSizeSp * zoom).coerceIn(8f, 32f)
                                    zoomSizeSp = newSize
                                }
                                if (pan.y != 0f) {
                                    val (_, charHeight) = charMetrics
                                    if (charHeight > 0f) {
                                        val deltaLines = -(pan.y / charHeight)
                                        val maxOffset = buffer.maxScrollOffset
                                        scrollOffset = (scrollOffset + deltaLines.roundToInt()).coerceIn(0, maxOffset)
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
                    val paneSelectionState = androidx.compose.foundation.text.selection.rememberSelectionState()
                    androidx.compose.runtime.LaunchedEffect(runtimeId) { paneSelectionState.clear() }
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
                                    val text = paneSelectionState.selectedTexts.joinToString("\n") { it.text }
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
                        modifier = Modifier.fillMaxSize()
                    )
                    // Anchored in this same Box as TerminalView (top-center
                    // popup, see SelectionActionBar's own doc) so it renders
                    // directly over this pane's own tile, not some shared
                    // coordinate space that could land it over a different
                    // pane entirely.
                    SelectionActionBar(actionModeController)
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
                    HiddenPaneInputField(
                        active = isFocused,
                        activationKey = focusToken,
                        onText = { text ->
                            scrollOffset = 0
                            onInput(text)
                        }
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
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    accumulated += dragAmount
                                    onResizeDrag(accumulated)
                                }
                            )
                        }
                ) {
                    Icon(
                        Icons.Filled.OpenWith,
                        contentDescription = "Drag to resize",
                        tint = Color.White.copy(alpha = 0.35f),
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
internal fun HiddenPaneInputField(active: Boolean, onText: (String) -> Unit, activationKey: Any = active) {
    val placeholder = "\u200B"
    var value by remember { mutableStateOf(TextFieldValue(placeholder, selection = TextRange(placeholder.length))) }
    var consumedBaseline by remember { mutableStateOf(placeholder) }
    val focusRequester = remember { FocusRequester() }
    val focusManager = LocalFocusManager.current
    val view = androidx.compose.ui.platform.LocalView.current
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
        if (active) {
            // Was routed through view.post (queues to the next Choreographer
            // frame) - see the old comment below for why that existed for
            // the very FIRST composition. But this LaunchedEffect only runs
            // on a real activationKey CHANGE, which means Compose has
            // already committed a recomposition by the time this coroutine
            // resumes - the layout that owns this FocusRequester is already
            // in the tree, so the "hasn't been committed yet" concern
            // view.post was guarding against doesn't apply here. Routing
            // through post was adding a full extra frame (sometimes more,
            // if the Looper queue was busy) on top of whatever gap already
            // exists between the outgoing field's clearFocus/hide and this
            // field's requestFocus/show - part of what was reading as the
            // keyboard staying fully closed for the better part of a second
            // on swipe-back in split screen. Calling both directly,
            // synchronously, closes that extra frame of delay.
            focusRequester.requestFocus()
            insetsController?.show(androidx.core.view.WindowInsetsCompat.Type.ime())
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
            insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
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
                insetsController?.hide(androidx.core.view.WindowInsetsCompat.Type.ime())
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
            value = new.copy(
                text = if (consumedBaseline == placeholder) placeholder else newText,
                selection = if (consumedBaseline == placeholder)
                    TextRange(placeholder.length) else new.selection
            )
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
