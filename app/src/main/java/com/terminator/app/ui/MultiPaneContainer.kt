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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
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
    modifier: Modifier = Modifier
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
                    onResizeSessionPty = onResizeSessionPty
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
                    onResizeSessionPty = onResizeSessionPty
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
    onResizeSessionPty: (String, Int, Int) -> Unit
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
    onResizeSessionPty: (String, Int, Int) -> Unit
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
    onDragStart: () -> Unit = {},
    onHeaderDrag: (Offset) -> Unit = {},
    onResizeDrag: (Offset) -> Unit = {},
    modifier: Modifier = Modifier
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
                        // Single gesture loop per pane, same "one reader of
                        // the touch stream" discipline as the primary pane -
                        // tap focuses + types directly (no separate input
                        // box, per the "type here kaldir" requirement), drag
                        // pans scrollback, pinch zooms this pane's own text
                        // size. Deliberately without the primary pane's
                        // mouse-reporting/text-selection/edge-auto-scroll
                        // layers - see this file's header doc.
                        .pointerInput(runtimeId) {
                            detectTapGestures(onTap = { onFocus() })
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
                    // OS's own selection handles+toolbar work per-pane
                    // exactly as they do on the primary pane, with no extra
                    // wiring needed here since TerminalView owns the native
                    // selection UI internally now.
                    val paneSelectionState = androidx.compose.foundation.text.selection.rememberSelectionState()
                    androidx.compose.runtime.LaunchedEffect(runtimeId) { paneSelectionState.clear() }
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
                    // Typing always jumps back to the live screen, matching
                    // the primary pane's own sendInput behavior - handled by
                    // the caller-supplied onInput wrapper resetting scroll
                    // via a side effect would be more surprising here than
                    // just resetting locally right before forwarding.
                    HiddenPaneInputField(
                        active = isFocused,
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
 * to stay alive/focused while THIS pane is the focused one - it's fine for
 * it to fully reset to the placeholder on every own edit, unlike the
 * primary pane's field which has to survive being the ONLY hidden field
 * for the entire activity across every session switch.
 */
@Composable
internal fun HiddenPaneInputField(active: Boolean, onText: (String) -> Unit) {
    val placeholder = "\u200B"
    var value by remember { mutableStateOf(TextFieldValue(placeholder, selection = TextRange(placeholder.length))) }
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(active) {
        if (active) {
            focusRequester.requestFocus()
        }
    }

    BasicTextField(
        value = value,
        onValueChange = { new ->
            val newText = new.text
            when {
                newText.length > placeholder.length && newText.startsWith(placeholder) -> {
                    onText(newText.removePrefix(placeholder))
                }
                newText.length < placeholder.length -> {
                    // Backspace past the placeholder itself - single DEL,
                    // matches a real terminal's backspace-with-nothing-to-
                    // delete-locally behavior (still forwarded, the running
                    // program decides what to do with it).
                    onText("\u007F")
                }
                !newText.startsWith(placeholder) -> {
                    // Autocorrect/predictive-text replaced the whole field -
                    // forward as-is rather than silently dropping it, same
                    // fallback the primary pane's field uses.
                    onText(newText)
                }
            }
            value = TextFieldValue(placeholder, selection = TextRange(placeholder.length))
        },
        modifier = Modifier
            .size(1.dp)
            .alpha(0f)
            .focusRequester(focusRequester),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Send),
        keyboardActions = androidx.compose.foundation.text.KeyboardActions(
            onSend = { onText("\r") }
        )
    )
}
