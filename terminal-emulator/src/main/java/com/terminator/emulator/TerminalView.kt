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

package com.terminator.emulator
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import java.util.WeakHashMap

// Caches the decoded android.graphics.Bitmap for each TerminalBuffer.SixelImage
// so drawTerminal (called on every recomposition/repaint - see bgPaint's own
// doc for how often that is on a busy terminal) doesn't re-decode the same
// pixel IntArray into a fresh Bitmap dozens of times a second for an image
// that hasn't changed at all. Keyed by IDENTITY (WeakHashMap, default
// equals/hashCode) rather than SixelImage's own structural data-class
// equals - deliberately: TerminalEmulator.decodeAndPlaceSixel only ever
// creates ONE SixelImage instance per placed image and never mutates or
// recreates it afterwards (see PlacedImage's own doc - only `row` changes,
// on the same instance), so identity IS the right notion of "same image"
// here and skips comparing potentially large pixel arrays on every lookup.
// Weak-keyed so an image dropped from TerminalBuffer.images (scrolled off,
// cleared, alt-screen swap - see those call sites) lets its cached Bitmap
// be GC'd too instead of leaking for the life of the process.
private val sixelBitmapCache = WeakHashMap<TerminalBuffer.SixelImage, Bitmap>()

private fun bitmapFor(image: TerminalBuffer.SixelImage): Bitmap =
    sixelBitmapCache.getOrPut(image) {
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

// Kitty counterpart of sixelBitmapCache - same identity-keyed, weak-
// referenced caching rationale (TerminalEmulator only ever creates ONE
// KittyImage instance per transmitted image id and never mutates its
// pixels afterwards; re-transmitting under the same id via
// storeKittyImage produces a genuinely NEW instance, which is exactly
// what should invalidate the cached Bitmap here - identity equality
// does that for free without an explicit invalidation call).
private val kittyBitmapCache = WeakHashMap<TerminalBuffer.KittyImage, Bitmap>()

private fun bitmapFor(image: TerminalBuffer.KittyImage): Bitmap =
    kittyBitmapCache.getOrPut(image) {
        Bitmap.createBitmap(image.pixels, image.width, image.height, Bitmap.Config.ARGB_8888)
    }

/** Per-(source-image-identity, tileRow, tileCol, gridCols, gridRows)
 *  cache of the small sliced [Bitmap] a single Unicode-placeholder cell
 *  (see [TerminalBuffer.Cell.kittyPlaceholder]'s own doc) paints. A
 *  drawKittyPlaceholderTile call happens once per placeholder CELL per
 *  repaint - for an image spanning many cells that's many small slices
 *  of the same source bitmap every frame, so this avoids re-cropping
 *  identical sub-rects repeatedly. Keyed on the outer map by the
 *  source [TerminalBuffer.KittyImage] identity (same rationale as
 *  [kittyBitmapCache]: currentKittyPixels returns a fresh KittyImage
 *  per animation frame, so identity naturally invalidates stale tiles
 *  when the displayed frame changes) via a WeakHashMap so tiles for a
 *  frame that's no longer current/referenced can be GC'd; the inner
 *  map is a plain HashMap keyed by the (tileRow, tileCol, cols, rows)
 *  tuple since a grid's own tile count is always small. */
private val kittyTileBitmapCache = WeakHashMap<TerminalBuffer.KittyImage, HashMap<List<Int>, Bitmap>>()

/** Slices [source] into a [cols]x[rows] grid and returns the bitmap for
 *  tile ([tileCol], [tileRow]), or null if that tile index falls
 *  outside the declared grid (a stale/malformed placeholder reference)
 *  or the source image has zero width/height. Each tile's pixel rect
 *  is computed independently off integer division of the full
 *  width/height by cols/rows - the same "last tile absorbs the
 *  rounding remainder" approach as a real Kitty terminal, so a e.g.
 *  10px-wide image sliced into 3 columns gives tiles of width
 *  3,3,4 rather than crashing or leaving a 1px gap. */
private fun kittyPlaceholderTileBitmap(
    source: TerminalBuffer.KittyImage,
    tileCol: Int,
    tileRow: Int,
    cols: Int,
    rows: Int
): Bitmap? {
    if (tileCol !in 0 until cols || tileRow !in 0 until rows) return null
    if (source.width <= 0 || source.height <= 0) return null
    val perTileTable = kittyTileBitmapCache.getOrPut(source) { HashMap() }
    val key = listOf(tileCol, tileRow, cols, rows)
    perTileTable[key]?.let { return it }

    val baseW = source.width / cols
    val baseH = source.height / rows
    if (baseW <= 0 || baseH <= 0) return null
    val left = tileCol * baseW
    val top = tileRow * baseH
    val w = if (tileCol == cols - 1) source.width - left else baseW
    val h = if (tileRow == rows - 1) source.height - top else baseH
    if (w <= 0 || h <= 0) return null

    val full = bitmapFor(source)
    val tile = Bitmap.createBitmap(full, left, top, w, h)
    perTileTable[key] = tile
    return tile
}

/** Paints the on-screen tile for a single Unicode-placeholder [Cell]
 *  (see [TerminalBuffer.Cell.kittyPlaceholder]'s own doc for how a
 *  cell ends up tagged this way, and the call site above for why this
 *  is checked before falling back to the raw glyph). Returns false -
 *  telling the caller to fall back to drawing the placeholder's raw
 *  glyph instead - whenever the reference has gone stale: the
 *  (imageId, placementId) is no longer a registered virtual placement
 *  (an a=d since this text was written), the underlying image itself
 *  is gone, or the cell's tagged (tileRow, tileCol) falls outside the
 *  grid the placement actually declared (e.g. text got copy/pasted or
 *  the c=/r= grid shrank after the fact). [x]/[y] are the same
 *  glyph-baseline coordinates the caller already computed for
 *  drawText, so the destination rect is derived the same way as
 *  everywhere else in this file: (x, y - charHeight) is the cell's
 *  top-left corner. */
private fun drawKittyPlaceholderTile(
    canvas: androidx.compose.ui.graphics.drawscope.DrawScope,
    buffer: TerminalBuffer,
    ref: TerminalBuffer.KittyPlaceholderRef,
    x: Float,
    y: Float,
    charWidth: Float,
    charHeight: Float
): Boolean {
    val (cols, rows) = buffer.kittyVirtualPlacementGrid(ref.imageId, ref.placementId) ?: return false
    val source = buffer.currentKittyPixels(ref.imageId) ?: return false
    val tile = kittyPlaceholderTileBitmap(source, ref.tileCol, ref.tileRow, cols, rows) ?: return false
    // DrawScope itself has no nativeCanvas - that's a property of the
    // underlying androidx.compose.ui.graphics.Canvas, only reachable via
    // drawIntoCanvas's own callback (same pattern the two other
    // drawIntoCanvas call sites in this file already use to reach
    // android.graphics.Canvas.drawBitmap/drawRect/drawText).
    canvas.drawIntoCanvas { nativeCanvasHolder ->
        nativeCanvasHolder.nativeCanvas.drawBitmap(
            tile,
            null,
            android.graphics.RectF(x, y - charHeight, x + charWidth, y),
            null
        )
    }
    return true
}

/**
 * Replaces androidx.compose.foundation.text.selection.SelectionState now
 * that TerminalView owns its own long-press/drag/handle selection instead
 * of delegating to Compose's native SelectionContainer (see TerminalView's
 * own doc for why - the native handle drawables don't track this app's
 * actual char grid across zoom/font-scale, which read as the handle
 * "jumping" away from the finger).
 *
 * Deliberately keeps the same two-member surface SelectionState had -
 * `selectedTexts: List<String>` and `clear()` - because MainActivity has
 * ~40 call sites reading selectionState.selectedTexts.isEmpty()/
 * isNotEmpty()/joinToString and calling selectionState.clear() (edge
 * auto-scroll guard, the Copy toolbar's LaunchedEffect, the toolbar's
 * onCopy body, session-switch resets...). Swapping this in for the old
 * SelectionState as a drop-in - same shape, different implementation -
 * meant none of those call sites needed to change at all.
 *
 * Internally this is anchor/focus row+col pairs (character offsets into
 * TerminalBuffer's grid) rather than a text-layout selection - TerminalView
 * turns those into per-row substrings via TerminalBuffer.rowPlainText()
 * whenever selectedTexts is read (see the private rowTexts() below),
 * instead of Compose's own text-node-based tracking.
 */
class TerminalSelectionState {
    var active by mutableStateOf(false)
        private set

    // Row/col are character-grid coordinates (buffer.rows x buffer.columns),
    // NOT pixels - converting pixel->cell happens once, at the point a
    // pointerInput callback reads a raw touch Offset (see startAt/
    // updateFocusAt/updateAnchorAt below and TerminalView's gesture block).
    var anchorRow by mutableStateOf(0)
        private set
    var anchorCol by mutableStateOf(0)
        private set
    var focusRow by mutableStateOf(0)
        private set
    var focusCol by mutableStateOf(0)
        private set

    // Backing list for selectedTexts - a SnapshotStateList so Compose
    // recomposes anywhere selectedTexts is read (LaunchedEffect keys,
    // selectedRows further down) exactly like the old SelectionState's own
    // reactive list did. Rebuilt in full on every anchor/focus change via
    // recomputeFrom() rather than mutated cell-by-cell, since a drag can
    // jump several rows in one pointer event and there's no cheaper partial
    // update that stays correct in every direction (dragging up vs down,
    // shrinking vs growing).
    private val _selectedTexts = SnapshotStateList<String>()
    val selectedTexts: List<String> get() = _selectedTexts

    // Timestamp (System.nanoTime()) of the most recent startAt() call - lets
    // a caller distinguish "a selection was just created by this long-press,
    // finger hasn't actually moved yet" from "a selection has been alive for
    // a while and the finger is now genuinely dragging". MainActivity's edge-
    // auto-scroll observer (PointerEventPass.Initial) reads this: without it,
    // a long-press that happened to land in the bottom ~15% of the visible
    // terminal (very common - people select the last few lines of output,
    // which sit near the bottom of the screen) made selectedTexts go from
    // empty to non-empty while the finger was ALREADY resting inside the
    // auto-scroll band, so the very next Initial-pass tick auto-scrolled
    // immediately - before the user had dragged anywhere - which both moved
    // the freshly-created one-cell selection to point at different buffer
    // content (shiftRows compensates for the offset change, but the visual
    // effect is still the selection appearing to "drop" to a new spot the
    // instant it's created) and only reproduced intermittently, exactly
    // matching "bazen küçücük selection aşağıya düşüyor... sık olmuyor ama
    // bazen oluyor" - it only happened when the long-press itself landed in
    // that bottom band, not on every selection.
    var lastStartAtNanos: Long = 0L
        private set

    // True only for the duration a selection HANDLE is actually being
    // held/dragged (the grabbedStart/grabbedEnd branch in TerminalView's
    // gesture block, between down.consume() and the drag loop ending) -
    // NOT true merely because `active`/`selectedTexts` is non-empty.
    // MainActivity's edge-auto-scroll observer (PointerEventPass.Initial,
    // watching every pointer event regardless of consumption) used to gate
    // only on selectedTexts.isEmpty()+position, which meant ANY press in
    // the top/bottom 15% band while a selection existed elsewhere on
    // screen - a plain long-press on empty space, or even an ordinary tap
    // held a beat too long - auto-scrolled the scrollback on its own. That
    // read as "selection seçip duraksadıktan sonra long press ile
    // scrollback kendi kendine scroll oluyor" and stepped on ordinary
    // short-press/tap scroll interactions in that same band. Edge-auto-
    // scroll should only ever fire while the user is actually holding a
    // handle to extend the selection into scrollback - this flag is what
    // lets that block tell the difference.
    var draggingHandle by mutableStateOf(false)
        private set

    fun beginHandleDrag() {
        draggingHandle = true
    }

    fun endHandleDrag() {
        draggingHandle = false
    }

    fun clear() {
        active = false
        _selectedTexts.clear()
        // Defensive: if something external (session switch, etc.) clears
        // the selection out from under an in-progress handle drag, don't
        // leave edge-auto-scroll permanently gated open afterward.
        draggingHandle = false
    }

    /** Begins a new selection anchored at (row, col) - called once, from
     *  the long-press timeout in TerminalView's gesture block. */
    fun startAt(row: Int, col: Int) {
        anchorRow = row; anchorCol = col
        focusRow = row; focusCol = col
        active = true
        lastStartAtNanos = System.nanoTime()
    }

    /** Moves the FOCUS (the end being dragged) to (row, col), keeping the
     *  anchor fixed - called on every drag frame once a selection is
     *  active. Anchor/focus can be in either order (focus above or below
     *  anchor); recomputeFrom normalizes that when building row text. */
    fun updateFocusAt(row: Int, col: Int) {
        focusRow = row; focusCol = col
    }

    /** Shifts both anchorRow and focusRow by [deltaRows] - call whenever
     *  scrollOffset itself just changed (edge-auto-scroll while dragging a
     *  handle) so the selection keeps pointing at the same BUFFER content
     *  instead of silently sliding to whatever now occupies those same
     *  screen-relative row numbers.
     *
     *  anchorRow/focusRow are screen-relative (0..buffer.rows-1), the same
     *  coordinate space TerminalView's cellOf() and TerminalBuffer's own
     *  row/scrollOffset addressing use - they say nothing on their own
     *  about WHICH buffer content they point at without also knowing
     *  scrollOffset at the moment they were set (see TerminalBuffer.lineAt's
     *  own doc: row N at scrollOffset S and row N at scrollOffset S+1 are
     *  two different lines of actual text). Edge-auto-scroll changes
     *  scrollOffset out from under an in-progress drag specifically so the
     *  user can extend a selection into scrollback by holding a handle at
     *  the screen edge - but changing scrollOffset alone, with nothing
     *  adjusting anchorRow/focusRow to match, left both of them pointing at
     *  the SAME row numbers as before against a screen that had just
     *  scrolled past them. That's what made a selection appear to get
     *  dragged/slide along with the scroll instead of staying anchored to
     *  the text it started on and simply growing into the newly-revealed
     *  rows: every tick moved the content under the selection without
     *  moving the selection's own row bookkeeping to compensate, in either
     *  scroll direction (up or down) equally - this fixes both.
     *
     *  Call this BEFORE recomputeFrom() for the same scroll tick so
     *  recomputeFrom reads already-corrected rows against the new
     *  scrollOffset, not the stale ones against a scrollOffset that no
     *  longer matches them.
     */
    fun shiftRows(deltaRows: Int) {
        if (deltaRows == 0 || !active) return
        anchorRow += deltaRows
        focusRow += deltaRows
    }

    /** Recomputes selectedTexts from the current anchor/focus against
     *  [buffer] at [scrollOffset] - call after startAt/updateFocusAt
     *  whenever the caller wants selectedTexts to reflect the latest
     *  drag position (TerminalView does this once per gesture-loop frame,
     *  not on every intermediate pointer event, to avoid rebuilding up to
     *  buffer.rows row strings more often than the screen can actually
     *  redraw). No-op (leaves selectedTexts as-is) when `active` is
     *  false - clear() already emptied the list in that case. */
    fun recomputeFrom(buffer: TerminalBuffer, scrollOffset: Int) {
        if (!active) return
        val (startRow, startCol, endRow, endCol) = normalized()
        val rows = mutableListOf<String>()
        for (row in startRow..endRow) {
            val line = buffer.rowPlainText(row, scrollOffset)
            val fromCol = if (row == startRow) startCol else 0
            // line.length can be shorter than buffer.columns would
            // suggest if the caller ever changes that invariant - coerce
            // defensively rather than throwing on a stale/racy read.
            val toColExclusive = if (row == endRow) (endCol + 1).coerceAtMost(line.length) else line.length
            rows += if (fromCol < toColExclusive) line.substring(fromCol.coerceIn(0, line.length), toColExclusive) else ""
        }
        _selectedTexts.clear()
        _selectedTexts.addAll(rows)
    }

    /** Anchor/focus in top-to-bottom, left-to-right order regardless of
     *  which one the user actually dragged - a drag that moves the focus
     *  ABOVE the anchor (selecting upward) still needs startRow <= endRow
     *  for recomputeFrom's row loop and for TerminalView's handle
     *  placement (the "start" handle is always the visually-earlier one,
     *  not always the anchor). */
    fun normalized(): SelectionRange {
        return if (anchorRow < focusRow || (anchorRow == focusRow && anchorCol <= focusCol)) {
            SelectionRange(anchorRow, anchorCol, focusRow, focusCol)
        } else {
            SelectionRange(focusRow, focusCol, anchorRow, anchorCol)
        }
    }
}

/** Normalized (start <= end) selection bounds in buffer row/col
 *  coordinates - see [TerminalSelectionState.normalized]. */
data class SelectionRange(val startRow: Int, val startCol: Int, val endRow: Int, val endCol: Int)

@Composable
fun rememberTerminalSelectionState(): TerminalSelectionState = remember { TerminalSelectionState() }

/**
 * Renders a TerminalBuffer to a Compose Canvas using a monospace font.
 * Colors are resolved through a [TerminalPalette] so themes (Material,
 * custom RGB, imported schemes such as Nord) can be swapped without
 * touching the renderer.
 */
class TerminalPalette(
    val ansiColors: IntArray, // 16 base colors, index -> ARGB int
    val defaultForeground: Int,
    val defaultBackground: Int,
    // Independent of ansiColors/defaultForeground/defaultBackground above -
    // see Settings > Theme > "Separate error/status colors". When non-null,
    // these pin ANSI red (indices 1 and 9) and yellow (indices 3 and 11)
    // to a fixed RGB regardless of what the rest of the palette resolves
    // to (Material, Nord, custom RGB, imported theme...), so a program's
    // own red/yellow SGR codes always read as "error"/"status" the same
    // way even if the surrounding palette changes.
    val statusErrorColor: Int? = null,
    val statusWarningColor: Int? = null
) {
    fun resolve(index: Int): Int = when {
        // Index 15 doubles as "default foreground" (see TerminalBuffer.
        // DEFAULT_FOREGROUND) and untouched/reset text is the overwhelming
        // majority of what's on screen. Without this branch every default
        // cell rendered through ansiColors[15] - a fixed accent white baked
        // into the palette - instead of whatever foreground the user
        // actually picked in Settings > Theme (Custom RGB / imported file /
        // Material), which is exactly why changing that color appeared to
        // do nothing. Mirrors the same special-casing already done for
        // DEFAULT_BACKGROUND (index 0) in drawTerminal() below.
        index == TerminalBuffer.DEFAULT_FOREGROUND -> defaultForeground
        // Checked before the general ansiColors branch below so a pinned
        // status color always wins over whatever red/yellow the active
        // palette (Material override included) would otherwise resolve to.
        statusErrorColor != null && (index == 1 || index == 9) -> statusErrorColor
        statusWarningColor != null && (index == 3 || index == 11) -> statusWarningColor
        index in ansiColors.indices -> ansiColors[index]
        // Truecolor marker (see TerminalEmulator.applySgr's 38;2/48;2
        // handling): index has TRUECOLOR_MARKER set in its high bits and
        // the R/G/B bytes packed into the low 24 bits. Programs using
        // 24-bit SGR (bat, delta, neovim themes, modern ls/fzf themes)
        // send colors nowhere near the 0-255 ANSI index range at all - this
        // used to silently fall through to `else -> defaultForeground`
        // below, which is why truecolor output always rendered as one flat
        // color instead of the actual RGB the program asked for.
        (index and TRUECOLOR_MARKER) == TRUECOLOR_MARKER ->
            (0xFF shl 24) or (index and 0x00FFFFFF)
        // Standard ANSI 256-color palette, indices 16-255 (the 16 base
        // colors above only cover 0-15). 16-231 is the 6x6x6 color cube
        // xterm defines; 232-255 is a 24-step grayscale ramp. Before this,
        // any SGR 38;5;N/48;5;N with N >= 16 (i.e. almost the entire
        // 256-color range - most themes/tools pick from the cube or the
        // grayscale ramp, not the base 16) fell through to
        // `else -> defaultForeground` below and rendered as one flat color
        // regardless of which of the 240 possible colors was requested.
        index in 16..231 -> {
            val i = index - 16
            val r = i / 36
            val g = (i % 36) / 6
            val b = i % 6
            // xterm's cube uses 0 or 55+40*n per step (0,95,135,175,215,255),
            // not a plain evenly-spaced 0-255 - matching that exactly (vs.
            // a naive r*51) is what makes 256-color output match what the
            // same escape sequence looks like in a real xterm.
            fun step(n: Int) = if (n == 0) 0 else 55 + 40 * n
            (0xFF shl 24) or (step(r) shl 16) or (step(g) shl 8) or step(b)
        }
        index in 232..255 -> {
            val level = 8 + (index - 232) * 10
            (0xFF shl 24) or (level shl 16) or (level shl 8) or level
        }
        else -> defaultForeground
    }

    /** Returns a copy with the same ansiColors/fg/bg but the given status
     *  override colors applied (or cleared, if either is null) - lets the
     *  caller layer Settings > Theme > "Separate error/status colors" on
     *  top of whatever base palette (Material, Nord, Custom RGB, Material
     *  override...) was already chosen, without duplicating that base
     *  palette's own construction logic. */
    fun withStatusColors(errorColor: Int?, warningColor: Int?): TerminalPalette =
        TerminalPalette(ansiColors, defaultForeground, defaultBackground, errorColor, warningColor)

    companion object {
        // Marker bit distinguishing a packed truecolor RGB value (see
        // resolve()'s truecolor branch and TerminalEmulator.applySgr's
        // 38;2/48;2 handling) from a plain 0-255 ANSI palette index in the
        // same Int-typed Cell.fg/bg field. Real ANSI indices only ever run
        // 0-255, so any bit at or above 1 shl 24 is unambiguously never a
        // valid index - safe to repurpose as "the low 24 bits are a packed
        // RRGGBB value, not a palette lookup".
        const val TRUECOLOR_MARKER = 1 shl 24

        /** A Nord-inspired default palette as a sane out-of-the-box theme. */
        fun nord(): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, 0xFFD8DEE9.toInt(), 0xFF2E3440.toInt())
        }

        /** Flat black background, same accent colors as [nord] - the app default now. */
        fun flatBlack(): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, 0xFFE6E6E6.toInt(), 0xFF000000.toInt())
        }

        /**
         * A palette built from just a foreground/background pair, keeping the
         * same 16 ANSI accent colors as [flatBlack] for readability. Used for
         * both the "Custom RGB" theme option and the "Material" option (where
         * the caller passes colors derived from the current MaterialTheme),
         * so the terminal screen actually reflects whatever the user picked
         * in Settings > Theme instead of always rendering [flatBlack].
         */
        fun custom(foreground: Int, background: Int): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, foreground, background)
        }

        /**
         * Settings > Theme > "Custom Palette" mode - all 16 ANSI slots plus
         * fg/bg, either hand-picked by the user or seeded from one of
         * PalettePresets (Solarized, Gruvbox, Dracula, Nord...). Distinct
         * from [custom], which only ever varies fg/bg and keeps a fixed
         * accent set; this is a real termcolor-style palette where every
         * slot is independently defined.
         */
        fun fromPalette(colors: IntArray, foreground: Int, background: Int): TerminalPalette {
            require(colors.size == 16) { "fromPalette requires exactly 16 colors, got ${colors.size}" }
            return TerminalPalette(colors.copyOf(), foreground, background)
        }

        /**
         * Settings > Theme > "Material color override" toggle, ON state.
         * Unlike [custom] (which only ever touches defaultForeground/
         * defaultBackground and leaves the 16 ANSI accent colors fixed),
         * this maps Material's own dynamic scheme onto all 16 ANSI slots -
         * so a program's own SGR color codes (red, green, blue...) render
         * in Material-derived hues too, instead of the fixed Nord-style
         * accents every other mode keeps for readability. Whether this or
         * [custom] is used for "Material" mode is decided by the caller
         * reading MATERIAL_COLOR_OVERRIDE - this function only builds the
         * palette, it doesn't read settings itself.
         *
         * Standard ANSI ordering: 0 black, 1 red, 2 green, 3 yellow,
         * 4 blue, 5 magenta, 6 cyan, 7 white, 8-15 the bright variants.
         * Material's tonal roles don't map 1:1 onto 8 hues, so this picks
         * the closest-fitting role for each slot and derives the bright
         * variant by leaning on the "inverse"/"container" counterpart
         * Material already computes, rather than inventing new colors.
         */
        fun materialOverride(
            primary: Int,
            error: Int,
            tertiary: Int,
            secondary: Int,
            onBackground: Int,
            background: Int,
            primaryContainer: Int,
            errorContainer: Int,
            tertiaryContainer: Int,
            secondaryContainer: Int
        ): TerminalPalette {
            val colors = intArrayOf(
                background,          // 0 black
                error,                // 1 red
                tertiary,             // 2 green (closest "positive" role Material exposes)
                secondary,            // 3 yellow/status
                primary,              // 4 blue
                secondary,            // 5 magenta
                tertiary,             // 6 cyan
                onBackground,         // 7 white
                background,           // 8 bright black
                errorContainer,       // 9 bright red
                tertiaryContainer,    // 10 bright green
                secondaryContainer,   // 11 bright yellow
                primaryContainer,     // 12 bright blue
                secondaryContainer,   // 13 bright magenta
                tertiaryContainer,    // 14 bright cyan
                onBackground          // 15 bright white / default foreground
            )
            // Also seed statusErrorColor/statusWarningColor with the same
            // Material error/secondary this palette already used for ANSI
            // slots 1/9 and 3/11 above. Without this, "Override ANSI colors
            // too" only touched the general 16-slot palette - a program's
            // own SGR red/yellow still landed on index 1/3 and looked
            // identical to before, because Material's error red is close
            // enough to the existing Nord-style red that the change wasn't
            // visible. Status colors are checked first in resolve(), so
            // seeding them here (rather than leaving them null) is what
            // actually makes the override read as "error/warning now follow
            // Material" instead of doing nothing. MainActivity's separate
            // "Separate error/status colors" + RGB picker layer still wins
            // when the user turns that on explicitly - see withStatusColors().
            return TerminalPalette(
                colors, onBackground, background,
                statusErrorColor = error,
                statusWarningColor = secondary
            )
        }
    }
}

// OSC 8 hyperlinks come straight off the pty - meaning a hyperlink's URI
// text can be whatever the running program (or, over ssh, a remote host;
// or a `cat`ed file the user didn't write) chose to emit, with nothing
// checking it before it reaches ACTION_VIEW. Restricting to schemes the
// terminal actually intends to support closes off e.g. a malicious "OSC 8
// ;; content://some.other.app/private/data ST" or an "intent:" payload
// being tapped and handed straight to startActivity with no confirmation.
// http(s)/file are handled with extra care just below this list's use;
// mailto/tel/sms/geo/ftp/ssh/smb are common in real command-line output
// (git commit trailers, contact export tools, location-tagged logs, ssh
// config dumps, network share paths); ipfs/ipns cover distributed-web
// content addresses tools like ipfs/kubo print for pinned content. Always
// allowed regardless of the "Allow custom app schemes" setting below -
// none of these can trigger an app-specific deep-linked action the way an
// arbitrary custom scheme could.
private val DEFAULT_ALLOWED_HYPERLINK_SCHEMES = setOf(
    "http", "https", "mailto", "tel", "sms", "geo",
    "ftp", "ssh", "smb", "file", "ipfs", "ipns"
)

@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    palette: TerminalPalette,
    fontFamily: Typeface = Typeface.MONOSPACE,
    fontSizeSp: Float = 14f,
    bufferVersion: Int = 0,
    // 1f = fully opaque background (normal, no-wallpaper look). When a
    // wallpaper is active behind the terminal, the caller passes something
    // < 1f here so the base canvas fill lets the wallpaper show through -
    // previously this rect was always fully opaque and hid any wallpaper
    // completely regardless of the Appearance > blur/alpha slider.
    backgroundAlpha: Float = 1f,
    // 0 = showing the live screen (normal). >0 = the user has dragged the
    // terminal down to look at scrollback history, this many lines back.
    scrollOffset: Int = 0,
    // Hoisted by the caller (MainActivity) so its own Copy/Paste/Close
    // toolbar can read selectionState.selectedTexts and call .clear().
    // Callers that don't need to observe/drive selection themselves
    // (SplitTerminalPane's panes) can just leave the default - they still
    // get full long-press/drag selection, they just don't read anything
    // back out of it.
    selectionState: TerminalSelectionState = rememberTerminalSelectionState(),
    // Full-row selection highlight color (ARGB int, alpha already baked
    // in by the caller - MainActivity passes Material's primary at ~25%
    // alpha, see its own comment there). Painted as a whole-row block
    // behind the glyphs, text/whitespace alike, for every row that has
    // any selected text on it - not a per-character highlight. Stays
    // visible for exactly as long as selectionState.selectedTexts is
    // non-empty for that row, which is what makes it disappear the
    // instant selectionState.clear() runs (tapping empty space) - see
    // drawTerminal below for where it's actually painted.
    highlightColor: Int = 0x407EC8FF.toInt(),
    // Selection handle color (ARGB int) - the two draggable teardrop
    // markers at the start/end of an active selection. Defaults to the
    // same blue as highlightColor's base hue but fully opaque (handles
    // need to stay visible/grabbable, unlike the translucent row fill).
    handleColor: Int = 0xFF7EC8FF.toInt(),
    // Settings > Terminal > Behaviour > "Allow custom app schemes". Off
    // (default): only DEFAULT_ALLOWED_HYPERLINK_SCHEMES are opened when a
    // hyperlink is tapped (see that set's own doc) - anything else (a
    // custom app deep link like "spotify:", "market:", "whatsapp:", or an
    // "intent:" payload) is treated as a dead link. On: any scheme is
    // handed to startActivity, restoring the old unrestricted behavior -
    // an explicit opt-in since a tapped hyperlink's URI is untrusted
    // program output, not something the user typed themselves.
    allowCustomHyperlinkSchemes: Boolean = false,
    modifier: Modifier = Modifier,
    // Debug-only tag prefixed onto this instance's SelDebug/ToolbarDebug
    // logcat lines so a log spanning both the primary pane's TerminalView
    // and the split pane's TerminalView (both log under the same tags)
    // can actually be told apart. "primary" is MainActivity's default;
    // SplitTerminalPane passes "split" explicitly.
    debugLabel: String = "primary",
    // True while the caller is mid pinch-to-zoom, i.e. rendering at a
    // live/preview fontSizeSp that hasn't been committed to buffer.resize()
    // yet (see MainActivity/SplitTerminalPane's own liveZoomSize doc: the
    // real buffer/pty resize is throttled to at most once per ~150ms during
    // an active pinch, but every pinch frame still re-renders immediately
    // at the new live font size for a smooth preview). The glyph grid below
    // handles that fine - it just draws buffer.rows/columns worth of cells
    // at whatever charWidth/charHeight the live font size produces. The
    // block cursor doesn't: its position is buffer.cursorRow/cursorCol (the
    // OLD, not-yet-committed grid coordinates) multiplied by the NEW live
    // charWidth/charHeight, which visibly detaches it from the actual
    // character grid for the entire pinch gesture - a stray white block
    // sitting wherever that stale row/col happens to land at the new scale,
    // only snapping back to the real cursor position once the throttled
    // commit finally fires. That's the "zoom edince imleç beyaz kalıyor,
    // yeri değişiyor" bug. Simplest correct fix: just don't draw the block
    // cursor for the handful of frames where its coordinates are known to
    // be stale - it reappears the instant the commit lands and bufferVersion
    // bumps this composable's recomposition.
    suppressCursor: Boolean = false
) {
    val density = LocalDensity.current
    val context = LocalContext.current
    val viewConfiguration = LocalViewConfiguration.current
    // Same px math drawTerminal uses below (sp -> px via density * fontScale,
    // then Paint's own font metrics) computed once here too, so handle
    // placement and hit-testing in dp/px line up with the actual glyph grid
    // the Canvas paints - this is the actual fix for the handle "jumping"
    // away from the finger the native SelectionContainer handles used to
    // do: those were positioned via Compose's own text-layout bounds, which
    // didn't always agree with this exact charWidth/charHeight math,
    // especially right after a pinch-zoom font-size change. Recomputed only
    // when one of the inputs that could change it actually changes, not on
    // every frame.
    val (charWidthPx, charHeightPx) = remember(fontFamily, fontSizeSp, density.density, density.fontScale) {
        val measuringPaint = Paint().apply {
            typeface = fontFamily
            textSize = fontSizeSp * density.density * density.fontScale
        }
        measuringPaint.measureText("M") to measuringPaint.fontSpacing
    }

    // New PTY output (scrollUp() pushing lines into scrollback) shifts
    // what every row/scrollOffset pair addresses just as much as the user
    // dragging scrollOffset does - but nothing about it touches
    // scrollOffset itself, so none of the shiftRows()/recomputeFrom() call
    // sites in MainActivity (all gated on scrollOffset changing) ever see
    // it. A selection left active while a flooding command keeps printing
    // - or simply a long selection that takes a while to drag out and
    // lift - could silently go stale and copy the wrong (or, once the
    // exact scrollback lines it pointed into got evicted, blank) rows.
    // Runs once per content-change tick (bufferVersion), consuming
    // whatever scrolled since the last tick:
    // - mid-drag (draggingHandle), TerminalView's own gesture loop already
    //   calls recomputeFrom every frame against the CURRENT scrollOffset,
    //   so compensating here too would double-shift; just drop the count
    //   without acting, same as scrollOffset-driven shiftRows callers skip
    //   when applied == 0.
    // - idle with an active selection, shift anchor/focus to keep pointing
    //   at the same buffer content and recompute, exactly like the
    //   user-driven edge-auto-scroll path does for a scrollOffset change.
    LaunchedEffect(bufferVersion) {
        val scrolled = buffer.consumePendingScrollLines()
        val columnsChanged = buffer.consumePendingColumnsChanged()
        if (columnsChanged && selectionState.active && !selectionState.draggingHandle) {
            // A resize that changed the column count invalidates
            // anchorCol/focusCol outright (see
            // TerminalBuffer.consumePendingColumnsChanged's doc) - there's
            // no row shift that fixes a selection whose column bounds no
            // longer mean the same thing, so drop it instead of trying to
            // shiftRows() it like a pure scroll. This also covers the
            // rowOffset/growOffset case below for the same tick: no point
            // shiftRows()-ing a selection this same resize is about to
            // clear anyway.
            selectionState.clear()
        } else if (scrolled != 0 && selectionState.active && !selectionState.draggingHandle) {
            // scrollUp() moves live content UP by `scrolled` lines while
            // scrollOffset itself stays put - the same net effect on what
            // a fixed (row, scrollOffset) pair addresses as the user
            // DECREASING scrollOffset by that many lines would have (see
            // TerminalBuffer.lineAt's doc: sliding the window toward the
            // live screen). shiftRows' sign convention matches
            // adjustScrollOffset's returned delta (positive = scrollOffset
            // increased), so this is the negated line count, not +scrolled.
            // The same delta also carries a pinch-zoom resize's row shift
            // (rowOffset - growOffset, folded into pendingScrollLines by
            // resize() itself) alongside any scrollUp()-driven lines from
            // this same tick, so a resize's row-only shift (column count
            // unchanged) gets shiftRows()-compensated exactly like normal
            // PTY-output scrolling instead of losing the selection.
            selectionState.shiftRows(-scrolled)
            selectionState.recomputeFrom(buffer, scrollOffset)
        }
    }

    Box(modifier = modifier) {
        // Watchdog for the suppressCursor param: the caller (MainActivity/
        // SplitTerminalPane/MultiPaneContainer) is only ever supposed to
        // hold this true for the brief window between a resize/zoom/drag
        // starting and its own debounced commit landing (~120-150ms by
        // every caller's own doc) - it's a "the grid is mid-transition,
        // don't paint a cursor at coordinates that might not match it yet"
        // signal, never meant to be a durable "hide the cursor" switch.
        // But suppressCursor is driven entirely by caller-side state
        // (pendingResize/liveZoomSize/isDraggingSplit flags, each flipped
        // back to false by that caller's own commit path) that this
        // composable has no visibility into and no way to verify - if any
        // one of those call sites' own reset ever fails to run (a
        // cancelled coroutine, a skipped branch, a future caller bug),
        // suppressCursor stays wedged true with nothing on this side ever
        // clearing it, silently hiding the cursor forever until something
        // else (unrelated) happens to flip the underlying flag back. A
        // renderer-side self-heal - the actual fix here - means a caller
        // bug degrades to "the cursor blinks back after a fraction of a
        // second longer than usual" instead of "gone until the user
        // stumbles onto whatever unrelated action clears it", without
        // this composable needing to know anything about WHY suppression
        // was requested.
        var suppressCursorTimedOut by remember { mutableStateOf(false) }
        LaunchedEffect(suppressCursor) {
            if (suppressCursor) {
                // Comfortably longer than the longest legitimate
                // suppression window any caller documents (MainActivity's
                // resize debounce: 120ms + delay; MultiPaneContainer's
                // manual-resize throttle: 32ms steps; a pinch's
                // zoomCommitJob: 150ms) - long enough that a real, still-
                // in-progress transition never trips it, short enough that
                // a wedged flag only costs a brief extra delay before the
                // cursor reappears on its own instead of staying hidden
                // indefinitely.
                delay(500)
                suppressCursorTimedOut = true
            } else {
                suppressCursorTimedOut = false
            }
        }
        val effectiveSuppressCursor = suppressCursor && !suppressCursorTimedOut
        // Drives SGR 5/25 (blink) text - a real attribute cells carry (see
        // TerminalBuffer.Cell.blink / TerminalEmulator's curBlink), not
        // something drawTerminal can express with a static Paint flag the
        // way bold/italic/underline are. 530ms on/off matches the
        // conventional terminal blink rate (xterm/VTE both default close
        // to this) - toggling a single shared phase here rather than a
        // per-cell timer means every blinking cell on screen (there can be
        // many, e.g. a whole `tput blink`-styled status line) flips in
        // lockstep off one Compose recomposition instead of each cell
        // drifting out of sync with its own clock.
        var blinkPhaseOn by remember { mutableStateOf(true) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(530)
                blinkPhaseOn = !blinkPhaseOn
            }
        }
        // Drives Kitty a=f/a=a animated images (see
        // TerminalBuffer.advanceKittyAnimations' own doc) the same way
        // blinkPhaseOn above drives SGR 5 blink: a dedicated ticking
        // LaunchedEffect rather than hooking into bufferVersion, since an
        // animated image needs to keep advancing frames even while the
        // program driving the terminal is sitting idle and producing no
        // new output/bufferVersion bumps at all. 33ms (~30fps) is fine-
        // grained enough that advanceKittyAnimations' own per-frame gapMs
        // math (arbitrary millisecond values from the transmitting
        // client) lands on the right frame within a tick or two rather
        // than visibly stepping; it only actually triggers a recompose
        // (via animTick) on ticks where advanceKittyAnimations reports a
        // frame genuinely changed, so a terminal with no animated images
        // at all (the common case) pays this loop's cost but never
        // recomposes from it.
        var animTick by remember { mutableStateOf(0) }
        LaunchedEffect(Unit) {
            while (true) {
                delay(33)
                if (buffer.advanceKittyAnimations(33)) animTick++
            }
        }
        // bufferVersion is bumped by the caller's ViewModel on every
        // TerminalEmulator.Listener callback (cursor move / content change).
        // Reading it here (even though drawTerminal reads straight from
        // `buffer`) is what makes Compose actually recompose on new output.
        // Which on-screen rows currently carry any selected text, read
        // fresh on every recomposition. selectionState.selectedTexts is a
        // SnapshotStateList - its CONTENTS change (via recomputeFrom's
        // clear()+addAll()) but the list object itself never does, so
        // remember(selectedTexts) here would key off a reference that
        // never changes and permanently cache the very first (empty,
        // pre-selection) result forever - that was the actual bug behind
        // "highlight never shows up at all": selectedRows silently stayed
        // the empty set from the first composition onward regardless of
        // how many rows actually became selected afterward. Recomputing
        // plainly on every recomposition (no remember) is correct here:
        // reading selectedTexts's contents is itself what subscribes this
        // composable to the SnapshotStateList's structural changes, so it
        // reruns exactly when the selection actually changes - the same
        // mechanism that already makes plain (non-remembered) reads of
        // other Compose State work everywhere else in this file.
        val selectedTexts = selectionState.selectedTexts
        // Handle positions, in buffer row/col coordinates - only
        // meaningful while selectionState.active is true. Read here (not
        // inside the gesture block) so the Canvas draw call below
        // recomposes on every anchor/focus change, same as selectedRows.
        // Read BEFORE selectedRows below: selectedTexts[i] corresponds to
        // buffer row range.startRow + i (recomputeFrom builds one string
        // per row starting at normalized() 's startRow, not per absolute
        // buffer row - see its own doc), so mapping a plain list index
        // straight to a buffer row number is only correct when
        // startRow == 0. That held by coincidence for a downward drag
        // started at the very top of the visible screen (the common
        // manual test), which is exactly why an upward drag - or any
        // selection that doesn't start at row 0 - highlighted/handled the
        // wrong rows: e.g. a 3-row selection from row 5 to row 7 produced
        // selectedTexts of size 3 at indices 0/1/2, which this used to
        // read directly as rows 0/1/2 instead of offsetting by startRow.
        val range = if (selectionState.active) selectionState.normalized() else null
        // Per-row [fromCol, toColExclusive) span actually selected on that
        // row - NOT a whole-row flag. Mirrors recomputeFrom's own column
        // math (fromCol is startCol only on the first row, 0 on every row
        // after; toColExclusive is endCol+1 only on the last row, the full
        // line length on every row before it) so the highlight painted
        // below covers exactly the characters recomputeFrom put in
        // selectedTexts/what Copy would actually grab - not the previous
        // whole-row-regardless-of-column rect, which painted every row
        // touched by the selection edge-to-edge (blank trailing space
        // included) even though only part of that row - often just a
        // single word - was actually selected. That's what read as
        // "seçmediğim yer de seçili görünüyor": the highlight was telling
        // the truth about which ROWS were touched, but not about which
        // COLUMNS within them actually were.
        val selectedColumnRanges = if (range != null) {
            selectedTexts.withIndex()
                .filter { (_, text) -> text.isNotEmpty() }
                .associate { (index, text) ->
                    val row = range.startRow + index
                    val fromCol = if (row == range.startRow) range.startCol else 0
                    // text here is recomputeFrom's ALREADY-TRIMMED substring
                    // for this row (line.substring(fromCol, toColExclusive)),
                    // not the row's full text - so its length alone is only
                    // the right toColExclusive when fromCol is 0. On the
                    // selection's start row, fromCol is startCol (non-zero
                    // whenever the selection doesn't begin at column 0), so
                    // text.length there is line.length - fromCol, not
                    // line.length - using it bare left the highlight ending
                    // `fromCol` columns short of where the actual selected
                    // (and copyable) text ends on that row. Every row AFTER
                    // the first has fromCol == 0, where fromCol + text.length
                    // and text.length happen to be the same number - which
                    // is exactly why this only ever showed up as a gap on
                    // the FIRST row of a multi-row selection (colored
                    // backgrounds - ls output, prompts, grep matches -
                    // made the missing tail visible; plain text on the
                    // default background just looked like ordinary
                    // unhighlighted blank space, which is what read as
                    // "renkli kısımlar bazen tam seçmiyor, boşluklar
                    // oluşuyor").
                    val toColExclusive = if (row == range.endRow) (range.endCol + 1) else (fromCol + text.length)
                    row to (fromCol until toColExclusive)
                }
        } else {
            emptyMap()
        }

        // The gesture block below is long-lived (its pointerInput key list
        // deliberately does NOT include scrollOffset - restarting mid-drag
        // on every edge-auto-scroll tick would cancel the drag itself).
        // That means the block's own closure can't just capture
        // `scrollOffset` by value; it needs to read whatever the CURRENT
        // scrollOffset is on every drag frame so recomputeFrom() selects
        // against the right rows as the user scrolls into history mid-
        // selection - see MainActivity's own edge-auto-scroll doc for why
        // that has to keep working while a selection is active. Without
        // this, every recomputeFrom() call during a drag used whatever
        // scrollOffset happened to be in effect when the gesture started,
        // so dragging a handle toward the edge scrolled the view but the
        // selection itself stayed pinned to the pre-scroll rows - the
        // "scrollback yapinca genislemiyor" bug.
        val latestScrollOffset = androidx.compose.runtime.rememberUpdatedState(scrollOffset)

        Canvas(
            modifier = Modifier
                .fillMaxSize()
                // Long-press-to-select + drag-to-extend, replacing
                // Compose Foundation's native SelectionContainer detector
                // (see TerminalSelectionState's own doc for why: the
                // native handle drawables didn't track this app's actual
                // char grid across zoom/font-scale changes). Runs at the
                // default (Main) pass, same as any ordinary tap/drag
                // handler - MainActivity's own gesture pointerInput
                // no longer needs to race this one for the first crack
                // at a down event, since there's no separate native
                // detector left to contend with; this block and
                // MainActivity's tap/pinch/pan block simply run as two
                // independent pointerInput modifiers on the same Box,
                // and Compose delivers every event to both. Cancelling
                // out of this block on ordinary taps/short drags (see
                // the wasLongPress check below) leaves those events
                // fully unconsumed for MainActivity's own block to
                // handle exactly as before.
                .pointerInput(debugLabel, buffer.rows, buffer.columns) {
                    fun cellOf(x: Float, y: Float): Pair<Int, Int> {
                        val rawCol = (x / charWidthPx).toInt().coerceIn(0, buffer.columns - 1)
                        val rawRow = (y / charHeightPx).toInt().coerceIn(0, buffer.rows - 1)
                        return rawRow to rawCol
                    }
                    // Snaps a touch that landed past the end of real
                    // content on its row onto the last non-blank column
                    // instead - mirrors the old SelectionContainer-era
                    // lastNonBlankColumn doc: most of a terminal screen
                    // below the prompt is blank, and selecting/copying
                    // nothing from a tap on obviously-empty space read as
                    // broken.
                    fun snappedCellOf(x: Float, y: Float): Pair<Int, Int> {
                        val (row, col) = cellOf(x, y)
                        val lastCol = buffer.lastNonBlankColumn(row, latestScrollOffset.value)
                        return if (lastCol != null && col > lastCol) row to lastCol else row to col
                    }

                    // Word-select-on-double-tap. `lastTapUp*` remembers the
                    // position/time of the most recent short (non-long-press,
                    // non-handle-grab) tap-UP across `awaitEachGesture`
                    // iterations of this SAME pointerInput instance, so the
                    // very next down can be recognized as its pair. Reset to
                    // "no recent tap" (nanos = 0) once consumed as either half
                    // of a double-tap, so a third quick tap doesn't chain into
                    // treating taps 2+3 as another pair.
                    var lastTapUpNanos = 0L
                    var lastTapUpX = 0f
                    var lastTapUpY = 0f
                    // Deliberately narrow: letters/digits/underscore. Matches
                    // what most users mean by "a word" (a flag like -rf or a
                    // path segment stays a separate word each side of the
                    // punctuation) - this seeds the initial double-tap
                    // selection only, dragging a handle afterward can still
                    // extend it across punctuation/the rest of the line, so
                    // narrow-by-default here doesn't block selecting more.
                    fun isWordChar(c: Char): Boolean = c.isLetterOrDigit() || c == '_'
                    // Argument/flag punctuation that commonly sits directly
                    // against a word char with no space between - "-rf",
                    // "--force", "/etc/passwd", "a.txt", "user@host". A
                    // double-tap landing exactly on one of these used to
                    // return null from wordRangeAt (isWordChar(line[col])
                    // false), which fell all the way through to the plain
                    // long-press path below and started a brand-new
                    // degenerate one-cell selection instead - that's what
                    // read as "hep tüm seçme modu aktif oluyor" (double-tap
                    // silently degrading into the whole-line/long-press
                    // selection behavior) whenever the tap happened to land
                    // on the dash of a flag or a slash in a path rather than
                    // a letter. Treated as its own word-like run here -
                    // adjacent characters from this SAME set extend the
                    // selection, same as isWordChar's letters/digits/
                    // underscore run does - rather than merging with
                    // isWordChar (which would make "-rf" and "foo" one word
                    // if they ever sat next to each other) or being left to
                    // fail outright.
                    fun isArgPunctChar(c: Char): Boolean = c in "-./_@"
                    fun wordRangeAt(row: Int, col: Int): Pair<Int, Int>? {
                        val line = buffer.rowPlainText(row, latestScrollOffset.value)
                        if (col !in line.indices) return null
                        val tapped = line[col]
                        val matches: (Char) -> Boolean = when {
                            isWordChar(tapped) -> ::isWordChar
                            isArgPunctChar(tapped) -> ::isArgPunctChar
                            else -> return null
                        }
                        var start = col
                        while (start > 0 && matches(line[start - 1])) start--
                        var end = col
                        while (end < line.length - 1 && matches(line[end + 1])) end++
                        return start to end
                    }

                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)

                        // If a selection is already active, a fresh down
                        // ON one of its own handles re-grabs that handle
                        // for dragging instead of starting a brand new
                        // long-press cycle - without this, touching the
                        // handle you just placed would wait out another
                        // full long-press timeout before doing anything.
                        //
                        // Two DIFFERENT radii on purpose now: visualRadius
                        // must match drawSelectionHandle's own formula
                        // exactly (0.22f / 10dp floor) so the CENTER of
                        // the hit-test region lines up with the center of
                        // the circle actually drawn (cy depends on the
                        // handle's own radius - see drawSelectionHandle's
                        // doc). hitRadius is kept separately larger (real
                        // 24dp/48dp-diameter touch-target territory) so
                        // shrinking the visible circle - the "kuşçuklar
                        // dana boyda" complaint - doesn't also shrink how
                        // easy the handle is to grab. Coupling these two
                        // into one value (as before) meant every attempt
                        // to make the circle look smaller made it
                        // proportionally harder to hit, which is why the
                        // radius kept getting bumped back up instead of
                        // actually shrinking.
                        val visualRadius = (charHeightPx * 0.22f).coerceAtLeast(with(density) { 10.dp.toPx() })
                        val hitRadius = (charHeightPx * 0.45f).coerceAtLeast(with(density) { 24.dp.toPx() })
                        // hy must match the CIRCLE's actual drawn center, not
                        // the row's bottom edge/bar line. drawSelectionHandle
                        // draws the bar spanning [rowTop, rowTop+rowHeight]
                        // and the circle centered BELOW that, at
                        // rowTop + rowHeight + radius*0.7f using ITS OWN
                        // (visual) radius - so this offset must use
                        // visualRadius, not hitRadius, even though the
                        // surrounding box you're allowed to tap within
                        // uses the larger hitRadius.
                        // Only offer handle re-grab for a selection that's
                        // actually visible (non-empty selectedTexts) - not
                        // merely selectionState.active, which stays true for
                        // a one-cell degenerate selection too (the instant
                        // after startAt(), before any drag). Guarding on
                        // active alone meant a fresh long-press landing near
                        // where an old selection's handle used to sit could
                        // silently re-grab that stale handle instead of
                        // starting an independent new selection - the new
                        // touch then re-anchored from the OLD selection's far
                        // end straight to wherever this new touch is, which
                        // reads as "I started selecting near blank space and
                        // it immediately jumped/dropped somewhere else"
                        // rather than growing naturally from the new touch
                        // point.
                        val existingRange = if (selectionState.active && selectionState.selectedTexts.isNotEmpty()) {
                            selectionState.normalized()
                        } else null
                        val grabbedStart = existingRange != null && run {
                            val hx = existingRange.startCol * charWidthPx
                            val hy = (existingRange.startRow + 1) * charHeightPx + visualRadius * 0.7f
                            kotlin.math.abs(down.position.x - hx) < hitRadius && kotlin.math.abs(down.position.y - hy) < hitRadius * 1.5f
                        }
                        val grabbedEnd = !grabbedStart && existingRange != null && run {
                            val hx = (existingRange.endCol + 1) * charWidthPx
                            val hy = (existingRange.endRow + 1) * charHeightPx + visualRadius * 0.7f
                            kotlin.math.abs(down.position.x - hx) < hitRadius && kotlin.math.abs(down.position.y - hy) < hitRadius * 1.5f
                        }

                        if (grabbedStart || grabbedEnd) {
                            down.consume()
                            // The finger's raw y at grab time is NOT over
                            // the row the handle represents - it's over
                            // the circle, which drawSelectionHandle draws
                            // visualRadius*0.7f BELOW that row's bottom
                            // edge (see its own doc, and the hy formula
                            // just above this block, which accounts for
                            // that same offset for hit-testing the grab
                            // itself). Every subsequent drag frame below
                            // used to feed the finger's raw y straight
                            // into cellOf(), which does a plain
                            // (y / charHeightPx) row division with no
                            // knowledge of that offset - so the row it
                            // computed was consistently the finger's
                            // ACTUAL row, not the row the handle visually
                            // sat on when first grabbed, off by however
                            // many pixels the circle hangs below the bar.
                            // Dragging down mostly hid this (the error
                            // pointed the same direction as the drag), but
                            // dragging the start handle UP to shrink/grow
                            // the selection consistently landed the new
                            // boundary a row lower than the finger really
                            // was - the selected block visibly failing to
                            // keep up with an upward drag, ending up
                            // "left behind" below where the finger
                            // actually stopped. Recording the gap between
                            // the raw grab point and the row's own
                            // coordinate here, then subtracting it from
                            // every later position, makes the drag track
                            // the finger's MOVEMENT from where it actually
                            // grabbed rather than re-deriving an absolute
                            // row from a touch point that was never on the
                            // row to begin with - the same "grab offset"
                            // approach ordinary drag handles use.
                            val grabRow = if (grabbedStart) existingRange!!.startRow else existingRange!!.endRow
                            val grabRowCenterY = (grabRow + 1) * charHeightPx + visualRadius * 0.7f
                            val verticalGrabOffset = down.position.y - grabRowCenterY
                            // Dragging the START handle: keep the OTHER
                            // end (endRow/endCol) fixed as the anchor and
                            // move this handle as the focus - but since
                            // TerminalSelectionState always stores
                            // anchor/focus (not start/end), re-anchor at
                            // the fixed end first so updateFocusAt below
                            // moves the right one.
                            val fixed = existingRange!!
                            // Re-anchor at the fixed (non-grabbed) end, but
                            // ALSO seed the focus at the grabbed handle's
                            // own current position (not left equal to the
                            // anchor) - startAt() always sets focus==anchor,
                            // which for one tick makes the selection a
                            // single collapsed point at the fixed end. If
                            // the finger pauses right here (down, then no
                            // move before lifting - the "duraksayıp tekrar
                            // selection seçmeye çalıştığında" case) and lifts
                            // without ever generating a move event, the loop
                            // below never runs updateFocusAt at all, so the
                            // selection was left collapsed at that single
                            // point - the two handles visibly "yaklaşıyor"
                            // (snap together) onto the fixed end instead of
                            // staying where they were. Restoring the grabbed
                            // end's own row/col as the initial focus means a
                            // zero-movement grab reproduces the ORIGINAL
                            // range exactly (nothing to snap together), and
                            // a real drag still calls updateFocusAt from
                            // that same correct starting point as before.
                            if (grabbedStart) {
                                selectionState.startAt(fixed.endRow, fixed.endCol)
                                selectionState.updateFocusAt(fixed.startRow, fixed.startCol)
                            } else {
                                selectionState.startAt(fixed.startRow, fixed.startCol)
                                selectionState.updateFocusAt(fixed.endRow, fixed.endCol)
                            }
                            selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                            // Marks this as a genuine handle drag for the
                            // whole lifetime of the loop below - this is
                            // what MainActivity's edge-auto-scroll observer
                            // gates on now, instead of just "a selection
                            // exists somewhere" (see draggingHandle's own
                            // doc). try/finally so it's cleared on every
                            // exit path (release, or the pointer's id
                            // disappearing from the event stream) and never
                            // gets stuck true if this loop exits abnormally.
                            selectionState.beginHandleDrag()
                            try {
                                while (true) {
                                    val event = awaitPointerEvent()
                                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                    change.consume()
                                    if (!change.pressed) {
                                        selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                        break
                                    }
                                    val (row, col) = cellOf(change.position.x, change.position.y - verticalGrabOffset)
                                    selectionState.updateFocusAt(row, col)
                                    selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                }
                            } finally {
                                selectionState.endHandleDrag()
                            }
                            return@awaitEachGesture
                        }

                        // Double-tap-to-select-word: this down landed close
                        // in time+space to the previous short tap's lift (set
                        // at the bottom of the `aborted` branch below) and
                        // over a word character - select that whole word
                        // immediately (anchor at its start, focus at its
                        // end) and drop straight into the same drag-extend
                        // loop long-press-confirmed selections use below, so
                        // the user can still drag a handle afterward to grow
                        // the selection across the rest of the line/argument
                        // ("satırın tamamını da seçsin ama kelime seçmek te
                        // mümkün olsun"). Only considered when there's no
                        // existing selection to protect, same as the
                        // long-press path just below - existingRange came
                        // back null here already (grabbedStart/grabbedEnd
                        // both false with existingRange non-null would have
                        // returned above).
                        val (tapRow, tapCol) = snappedCellOf(down.position.x, down.position.y)
                        val isDoubleTap = existingRange == null && lastTapUpNanos != 0L &&
                            (System.nanoTime() - lastTapUpNanos) < viewConfiguration.doubleTapTimeoutMillis * 1_000_000L &&
                            kotlin.math.abs(down.position.x - lastTapUpX) < charWidthPx * 2f &&
                            kotlin.math.abs(down.position.y - lastTapUpY) < charHeightPx * 2f
                        val wordRange = if (isDoubleTap) wordRangeAt(tapRow, tapCol) else null
                        if (wordRange != null) {
                            lastTapUpNanos = 0L
                            down.consume()
                            val (wordStart, wordEnd) = wordRange
                            selectionState.startAt(tapRow, wordStart)
                            selectionState.updateFocusAt(tapRow, wordEnd)
                            selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                            // Same dead-zone as the long-press drag loop below
                            // (see its own doc) - a double-tap word-select is
                            // often the smallest possible selection, so it's
                            // the case most visibly affected by finger wobble
                            // flipping the focus cell right after selection.
                            var pastDeadZone = false
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                    break
                                }
                                if (!pastDeadZone) {
                                    val dx = change.position.x - down.position.x
                                    val dy = change.position.y - down.position.y
                                    if (kotlin.math.sqrt(dx * dx + dy * dy) <= viewConfiguration.touchSlop) {
                                        continue
                                    }
                                    pastDeadZone = true
                                }
                                val (row, col) = cellOf(change.position.x, change.position.y)
                                selectionState.updateFocusAt(row, col)
                                selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                            }
                            return@awaitEachGesture
                        }

                        // Not on a handle: wait out the long-press
                        // timeout, watching for movement/lift/a second
                        // finger exactly like MainActivity's own
                        // long-press-candidate window used to (back when
                        // it had to yield to a native detector) - the
                        // difference is this loop no longer has anything
                        // else to defer to, it IS the detector now.
                        //
                        // BUT only when there's no existing selection to
                        // protect. If a selection is already active and
                        // this down landed away from both its handles
                        // (existingRange != null, grabbedStart/grabbedEnd
                        // both false), consuming the long-press here to
                        // start a BRAND NEW one-cell selection at the touch
                        // point silently overwrote/replaced the selection
                        // the user already had - the classic case being
                        // "select everything, pause, then long-press empty
                        // space intending to scroll scrollback up/down" -
                        // the long-press timeout fires here first, plants a
                        // fresh degenerate selection wherever the finger
                        // happened to land, and the drag that follows
                        // extends THAT new selection instead of ever
                        // reaching MainActivity's own scroll/pan handling,
                        // which is what actually drives scrollOffset. The
                        // result read as "scrollback ileri geri yaparken
                        // geri tepiyor" - the existing selection appearing
                        // to snap/reset the instant the long-press timeout
                        // elapsed, because it effectively had been replaced
                        // by a new one that then got dragged around instead
                        // of scrolling anything. Falling through
                        // unconsumed here for this case hands the whole
                        // gesture to MainActivity's own pointerInput block,
                        // exactly like an ordinary tap/pan on empty space
                        // with no selection at all - which already knows
                        // how to scroll scrollback while preserving an
                        // active selection (see its own draggingWithSelection
                        // handling). A tap on empty space still needs to be
                        // able to DISMISS an existing selection (tapping
                        // away from it is the normal way to clear a
                        // selection) - that path is unaffected, since a
                        // short tap here still won't be consumed by this
                        // block either way and MainActivity's own tap
                        // handling has no selection-awareness of its own to
                        // clear it, so TerminalView still needs to do that;
                        // ordinary taps are handled by the `aborted` branch
                        // below exactly as before. Only the LONG-PRESS path
                        // (below, past the timeout) is skipped when there's
                        // an existing selection - and that skip has to
                        // happen AFTER the wait loop, not before it: an
                        // early return here (as this used to do) bailed out
                        // of the whole block before the wait loop ever ran,
                        // which meant the `aborted` branch below - the one
                        // that actually clears the selection on a short tap
                        // - never got a chance to execute either. The net
                        // effect was a plain tap on empty space no longer
                        // dismissing an active selection at all, since the
                        // code path that does that dismissal is downstream
                        // of this point. So: always run the wait loop and
                        // let `aborted` handling do its job; only the
                        // long-press-confirmed branch further below checks
                        // existingRange to decide whether to plant a new
                        // selection.
                        val longPressDeadline = System.nanoTime() + viewConfiguration.longPressTimeoutMillis * 1_000_000L
                        var aborted = false
                        // Distinguishes WHY the long-press wait was aborted:
                        // a plain tap (lifted, or a second finger landed)
                        // should dismiss an active selection same as before,
                        // but movement past touch slop should NOT - that's
                        // the start of a scroll/pan gesture, and MainActivity's
                        // own block (which reads this same down afterward)
                        // already knows to treat a drag while a selection is
                        // active as scroll-while-selecting (draggingWithSelection)
                        // and keep the selection alive via isEdgeAutoScroll.
                        // Clearing it here first - as this used to do
                        // unconditionally on ANY abort reason - raced that
                        // logic and won: the selection was gone by the time
                        // MainActivity's block ever got to check it, so any
                        // one-finger scroll attempt while text was selected
                        // (typically starting with the finger somewhere in
                        // ordinary space, not on a handle - "boşluğa yakın
                        // yerde" and "scrollback yaparken kapanıyor") silently
                        // dismissed the very selection the user was trying to
                        // extend, before the drag had scrolled anything at all.
                        var abortedByMovement = false
                        while (true) {
                            val remainingMillis = (longPressDeadline - System.nanoTime()) / 1_000_000L
                            if (remainingMillis <= 0L) break
                            val event = withTimeoutOrNull(remainingMillis) { awaitPointerEvent(PointerEventPass.Initial) }
                            if (event == null) break
                            val changes = event.changes
                            val primary = changes.firstOrNull { it.id == down.id } ?: changes.firstOrNull()
                            if (primary == null || !changes.any { it.pressed }) { aborted = true; break }
                            if (changes.count { it.pressed } >= 2) { aborted = true; break }
                            val dx = primary.position.x - down.position.x
                            val dy = primary.position.y - down.position.y
                            if (kotlin.math.sqrt(dx * dx + dy * dy) > viewConfiguration.touchSlop) {
                                aborted = true; abortedByMovement = true; break
                            }
                        }
                        if (aborted) {
                            // OSC 8 hyperlink: a short (non-movement) tap on
                            // a cell carrying a link opens it like a
                            // hyperlink tap anywhere else on Android - a
                            // plain ACTION_VIEW Intent, so the SYSTEM picks
                            // whichever installed app actually handles that
                            // URL/mime type (a browser for http(s), a PDF
                            // viewer for a file:// .pdf, the package
                            // installer for an .apk link, etc.) rather than
                            // this emulator hardcoding one specific target -
                            // exactly the classic-Android-hyperlink behavior
                            // asked for. lineAt (not cellAt) so this also
                            // works on a link sitting in scrollback, not
                            // just the live screen. Checked first, before
                            // the selection-dismiss/double-tap bookkeeping
                            // below, so tapping a link doesn't ALSO clear an
                            // unrelated active selection or arm a double-tap
                            // word-select on the next tap; consumed so the
                            // gesture ends here instead of falling through
                            // to MainActivity's tap-to-toggle-keyboard
                            // handler - there's nothing to type into once
                            // you've switched away to a browser/viewer.
                            if (!abortedByMovement) {
                                val (tapRow, tapCol) = cellOf(down.position.x, down.position.y)
                                val link = buffer.lineAt(tapRow, tapCol, latestScrollOffset.value).hyperlink
                                if (link != null) {
                                    try {
                                        val uri = android.net.Uri.parse(link)
                                        if (uri.scheme?.lowercase() !in DEFAULT_ALLOWED_HYPERLINK_SCHEMES && !allowCustomHyperlinkSchemes) {
                                            // Not a scheme this emulator
                                            // opens by default (see
                                            // DEFAULT_ALLOWED_HYPERLINK_SCHEMES's
                                            // doc) and "Allow custom app
                                            // schemes" isn't turned on -
                                            // treat exactly like a dead
                                            // link rather than handing an
                                            // untrusted, program-chosen URI
                                            // straight to startActivity.
                                            down.consume()
                                            return@awaitEachGesture
                                        }
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW)
                                        if (uri.scheme.equals("file", ignoreCase = true) && uri.path != null) {
                                            // A raw file:// Uri handed to
                                            // another app's Intent throws
                                            // FileUriExposedException on
                                            // Android 7.0+ (this app targets
                                            // 37) - an uncaught
                                            // RuntimeException, not an
                                            // ActivityNotFoundException,
                                            // that used to crash the whole
                                            // app instead of just failing
                                            // the link (TerminatorApp now
                                            // disables that death penalty
                                            // via disableDeathOnFileUriExposure,
                                            // needed below for directories -
                                            // see that branch). For regular
                                            // files, re-expose the path as a
                                            // content:// Uri via FileProvider
                                            // instead, which is both safe to
                                            // share and actually openable by
                                            // the receiving app (a PDF
                                            // viewer, image viewer, etc.).
                                            val file = java.io.File(uri.path!!)
                                            if (!file.exists()) {
                                                // Dead link - the path the
                                                // hyperlink points at is
                                                // gone (deleted, moved, or
                                                // was never real to begin
                                                // with). FileProvider itself
                                                // wouldn't have caught this
                                                // - it only validates the
                                                // path is under a declared
                                                // root, not that the file is
                                                // actually there - so without
                                                // this check tapping it would
                                                // still launch a viewer app
                                                // just to show a "file not
                                                // found" error there instead
                                                // of here. Same as any other
                                                // dead hyperlink: do nothing.
                                                down.consume()
                                                return@awaitEachGesture
                                            }
                                            if (file.isDirectory) {
                                                // A content:// Uri from
                                                // FileProvider only exposes
                                                // a single readable stream -
                                                // file managers can't browse
                                                // into it as a folder, so a
                                                // link to a directory
                                                // (`ls --hyperlink` on a
                                                // folder, cd targets, etc.)
                                                // silently did nothing when
                                                // tapped even though it
                                                // wasn't a dead link. File
                                                // managers instead look for
                                                // the raw file:// path typed
                                                // "resource/folder" to open
                                                // a folder browser - so use
                                                // that shape here instead of
                                                // going through FileProvider.
                                                // Safe from the
                                                // FileUriExposedException
                                                // that used to crash this
                                                // (see TerminatorApp's
                                                // disableDeathOnFileUriExposure
                                                // call).
                                                intent.setDataAndType(uri, "resource/folder")
                                            } else {
                                                val contentUri = androidx.core.content.FileProvider.getUriForFile(
                                                    context,
                                                    "${context.packageName}.fileprovider",
                                                    file
                                                )
                                                val extension = android.webkit.MimeTypeMap.getFileExtensionFromUrl(file.name)
                                                val mimeType = android.webkit.MimeTypeMap.getSingleton()
                                                    .getMimeTypeFromExtension(extension) ?: "*/*"
                                                intent.setDataAndType(contentUri, mimeType)
                                            }
                                            intent.addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                        } else {
                                            intent.data = uri
                                        }
                                        intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        // No app on the device handles this
                                        // link, the path doesn't exist, or
                                        // the system otherwise refused the
                                        // Intent (ActivityNotFoundException,
                                        // FileUriExposedException,
                                        // SecurityException, a malformed
                                        // link causing IllegalArgumentException,
                                        // etc.) - nothing to do, same as any
                                        // other dead hyperlink. This should
                                        // never crash the app over a tap on
                                        // terminal text.
                                    }
                                    down.consume()
                                    return@awaitEachGesture
                                }
                            }
                            // A short tap (lifted before the long-press
                            // timeout, and not on either handle - the
                            // grabbedStart/grabbedEnd check above already
                            // returned early for those) on empty terminal
                            // space while a selection is active should
                            // dismiss that selection, the same way tapping
                            // empty space always has for text selection
                            // elsewhere in Android. Previously this block
                            // only ever STARTED a selection (on long-press)
                            // and never had a path that ENDED one - a plain
                            // tap fell all the way through, unconsumed, to
                            // MainActivity's own tap-to-toggle-keyboard
                            // handler, which knows nothing about selection
                            // state at all. That's what made an active
                            // selection stick around forever (highlight,
                            // handles, the Copy/Paste toolbar) until the
                            // user happened to long-press again or switched
                            // sessions - tapping away from it, the obvious
                            // way to dismiss it, silently did nothing. Only
                            // clearing (not consuming) here: the tap should
                            // still fall through and toggle the keyboard
                            // exactly as it did before, this just
                            // additionally drops the selection first.
                            //
                            // Skipped entirely when the abort reason was
                            // movement (abortedByMovement) - see that flag's
                            // own doc above.
                            if (selectionState.active && !abortedByMovement) {
                                selectionState.clear()
                            }
                            // Remember this short tap's lift so the NEXT
                            // down, if it lands close enough in time/space
                            // (checked above, at the top of the next
                            // awaitEachGesture iteration), gets recognized
                            // as the second half of a double-tap and
                            // word-selects instead of starting another
                            // long-press wait. Only for a genuine short tap,
                            // not a movement-abort (that's a scroll/pan
                            // starting, not a tap at all).
                            if (!abortedByMovement) {
                                lastTapUpNanos = System.nanoTime()
                                lastTapUpX = down.position.x
                                lastTapUpY = down.position.y
                            }
                            return@awaitEachGesture
                        }

                        // Long-press confirmed. If there's already an
                        // active selection and this down landed away from
                        // both its handles (existingRange != null - the
                        // grabbedStart/grabbedEnd cases returned earlier,
                        // above this whole block), do NOT plant a brand
                        // new one-cell selection here: that used to
                        // silently overwrite the user's existing selection
                        // the instant the long-press timeout elapsed (see
                        // the long doc comment above). Fall through
                        // unconsumed instead, exactly like an ordinary
                        // long-press on empty space with no selection at
                        // all, so MainActivity's own pointerInput block
                        // handles it (e.g. scroll/pan while preserving the
                        // active selection).
                        if (existingRange != null) {
                            return@awaitEachGesture
                        }

                        // Start a selection at the touch point (snapped
                        // away from trailing blank space) and consume
                        // every event for the rest of this gesture so
                        // MainActivity's own tap/pan block never sees it
                        // as a tap-to-toggle-keyboard or a pan.
                        down.consume()
                        val (startRow, startCol) = snappedCellOf(down.position.x, down.position.y)
                        selectionState.startAt(startRow, startCol)
                        selectionState.recomputeFrom(buffer, latestScrollOffset.value)

                        // A character cell is often only 20-30px wide/tall on
                        // a phone screen - well inside the amount a finger
                        // naturally wobbles while just resting in place, with
                        // no actual intent to drag. Reacting to every raw
                        // pixel here (as this used to) meant that wobble
                        // alone could flip `col`/`row` across a cell boundary
                        // and move the focus cell right after a selection was
                        // created, which read as a small (single-word or
                        // shorter) selection visibly jittering/growing by a
                        // cell on its own the moment the long-press
                        // confirmed - "selection... fazla oynuyor" especially
                        // noticeable on short selections where a one-cell
                        // wobble is a large fraction of the whole thing.
                        // touchSlop is already the platform's own answer to
                        // "how much movement counts as intentional" (used
                        // above to decide whether the long-press itself gets
                        // cancelled) - gating focus updates behind that same
                        // threshold, measured from the down point, means the
                        // focus only ever moves once the finger has genuinely
                        // left the start point rather than on every sub-pixel
                        // tremor. Only gates the FIRST move away from the
                        // start cell; once the finger has moved past the
                        // threshold once, every subsequent frame updates
                        // normally (checked via a flag, not by re-measuring
                        // from `down` every time, since the user may then
                        // legitimately drag back near the start column - that
                        // should still track, not get stuck ungated again).
                        var pastDeadZone = false
                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                break
                            }
                            if (!pastDeadZone) {
                                val dx = change.position.x - down.position.x
                                val dy = change.position.y - down.position.y
                                if (kotlin.math.sqrt(dx * dx + dy * dy) <= viewConfiguration.touchSlop) {
                                    continue
                                }
                                pastDeadZone = true
                            }
                            val (row, col) = cellOf(change.position.x, change.position.y)
                            selectionState.updateFocusAt(row, col)
                            selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                        }
                    }
                }
        ) {
            @Suppress("UNUSED_EXPRESSION")
            bufferVersion
            @Suppress("UNUSED_EXPRESSION")
            animTick
            drawTerminal(buffer, palette, fontFamily, fontSizeSp, backgroundAlpha, scrollOffset, selectedColumnRanges, highlightColor, effectiveSuppressCursor, blinkPhaseOn)
            // Custom selection handles - two small teardrop markers at the
            // normalized start/end of the active selection, drawn directly
            // against the same charWidthPx/charHeightPx grid the gesture
            // block above hit-tests against (see its own doc: this is the
            // actual fix for handles not tracking the finger/zoom level,
            // since there's now only ONE source of truth for cell<->pixel
            // math instead of a separate native text-layout computing its
            // own). Drawn after drawTerminal so they sit on top of the
            // glyphs/highlight, not under them.
            if (range != null) {
                drawSelectionHandle(range.startCol * charWidthPx, (range.startRow) * charHeightPx, charHeightPx, handleColor, leading = true)
                drawSelectionHandle((range.endCol + 1) * charWidthPx, (range.endRow) * charHeightPx, charHeightPx, handleColor, leading = false)
            }
        }
    }
}

/**
 * Note on why there's no NoOpTextToolbar/NoOpTextContextMenuProvider here
 * anymore: those existed solely to suppress Compose Foundation's
 * SelectionContainer from popping its own native Copy/Paste bubble
 * alongside ActionModeController's own bar (see SelectionOverrideToolbar.kt).
 * Now that TerminalView owns selection directly via its own pointerInput
 * gesture block instead of SelectionContainer, there's no native bubble
 * left to race - ActionModeController/SelectionActionBar remains the only
 * Copy/Paste UI, same as before, just without needing to suppress a
 * competing native path.
 */

private fun DrawScope.drawTerminal(
    buffer: TerminalBuffer,
    palette: TerminalPalette,
    fontFamily: Typeface,
    fontSizeSp: Float,
    backgroundAlpha: Float = 1f,
    scrollOffset: Int = 0,
    selectedColumnRanges: Map<Int, IntRange> = emptyMap(),
    highlightColor: Int = 0x407EC8FF.toInt(),
    // See TerminalView's own suppressCursor doc - true while buffer.cursorRow/
    // cursorCol are known stale relative to the live (not-yet-committed)
    // charWidth/charHeight this exact draw call is about to compute below.
    suppressCursor: Boolean = false,
    // Shared blink phase from TerminalView's own 530ms ticker - a cell with
    // blink=true (SGR 5) only actually paints its glyph while this is true,
    // same on/off rhythm every blinking cell on screen shares (see
    // blinkPhaseOn's own doc for why it's one shared clock, not per-cell).
    blinkPhaseOn: Boolean = true
) {
    // DrawScope implements Density, so both `density` and `fontScale` are
    // available directly here. Real Android sp->px conversion is
    // `px = sp * density * fontScale` - this used to multiply by `density`
    // alone, silently ignoring the user's accessibility font-scale setting.
    // Whenever that scale isn't exactly 1.0, the glyphs actually painted
    // here came out a different size than what MainActivity used to compute
    // the pty's column/row count, which is what made full-screen apps like
    // nano/vim render misaligned or garbled at non-default font scales.
    val paint = Paint().apply {
        typeface = fontFamily
        textSize = fontSizeSp * density * fontScale
        isAntiAlias = true
    }
    // Reused for every cell/cursor background fill instead of allocating a
    // fresh Paint per cell. On an 80x24 screen that's up to ~1920 Paint()
    // allocations per single redraw (and htop/top can trigger several
    // redraws a second) - all of that garbage is what made the terminal
    // feel sluggish/janky, especially right as the IME animates in or out
    // and a resize forces a full repaint on top of the GC churn.
    val bgPaint = Paint()
    val charWidth = paint.measureText("M")
    val charHeight = paint.fontSpacing
    // Reused for the manually-drawn decoration lines (overline, and
    // underline/undercurl whenever a custom SGR 58 color or the curly
    // shape means Paint's own built-in isUnderlineText can't be used - see
    // its own call site below) - same "one Paint, not one per cell" reuse
    // rationale as bgPaint above. STROKE style (rather than bgPaint's
    // implicit FILL) is what makes strokeWidth actually control the drawn
    // line's thickness for drawLine/drawPath calls.
    val decorPaint = Paint().apply {
        isAntiAlias = true
        style = Paint.Style.STROKE
        strokeWidth = (charHeight * 0.08f).coerceAtLeast(1.5f)
    }

    drawRect(color = Color(palette.defaultBackground).copy(alpha = backgroundAlpha.coerceIn(0f, 1f)), size = size)

    // Selection highlight: painted by hand again (was removed when
    // selection moved to TerminalView's native SelectionContainer
    // overlay, which drew its own highlight - but that highlight came
    // from Compose's default text-selection color, not the app's
    // Material palette, and didn't reliably cover blank/whitespace
    // columns). Drawn here, under the glyph loop below, as one rect PER
    // SELECTED COLUMN SPAN in `selectedColumnRanges` - only the actual
    // [fromCol, toColExclusive) run TerminalView's caller computed for
    // that row (recomputeFrom's own column math, mirrored there) gets
    // painted, not the row's entire width. This used to paint every row
    // touched by the selection edge-to-edge regardless of which columns
    // were actually selected on it - correct for the FULL interior rows
    // of a multi-row selection (those genuinely are selected end to end),
    // but wrong for the first/last row of the selection, where only part
    // of the row (often just the one word actually long-pressed/dragged
    // over) was selected - the rest of that row's width got the same
    // highlight anyway, which is what read as "seçmediğim yer de seçili
    // görünüyor". Must happen before the glyph/cursor drawing loop so the
    // highlight sits behind the text instead of covering it.
    if (selectedColumnRanges.isNotEmpty()) {
        val hlPaint = Paint().apply { color = highlightColor }
        val rowCharHeight = Paint().apply {
            typeface = fontFamily
            textSize = fontSizeSp * density * fontScale
        }.fontSpacing
        drawIntoCanvas { hlCanvas ->
            for ((row, colRange) in selectedColumnRanges) {
                if (row !in 0 until buffer.rows) continue
                if (colRange.isEmpty()) continue
                val top = row * rowCharHeight
                val left = colRange.first * charWidth
                // colRange.last is inclusive (IntRange) - +1 to get the
                // exclusive right edge in px, same "up to but not
                // including" convention recomputeFrom's own toColExclusive
                // uses.
                val right = (colRange.last + 1) * charWidth
                hlCanvas.nativeCanvas.drawRect(left, top, right, top + rowCharHeight, hlPaint)
            }
        }
    }

    // DECSCNM (Reverse Video) - see TerminalBuffer.reverseVideoMode's own
    // doc for why this is a raw unsynchronized read taken once here,
    // rather than per-cell inside the loop below: it's XORed against each
    // cell's own SGR-7 `inverse` flag (screen-wide swap ON TOP OF, not
    // instead of, whatever per-cell inverse video a program already set -
    // matching real xterm/VTE, where the two are independent and both
    // apply), so every one of the four `cell.inverse` reads below becomes
    // `cell.inverse xor reverseVideo` instead.
    val reverseVideo = buffer.reverseVideoMode
    drawIntoCanvas { canvas ->
        for (row in 0 until buffer.rows) {
            for (col in 0 until buffer.columns) {
                val cell = buffer.lineAt(row, col, scrollOffset)
                val x = col * charWidth
                val y = (row + 1) * charHeight

                var fg = if (cell.inverse xor reverseVideo) palette.resolve(cell.bg) else palette.resolve(cell.fg)
                if (cell.dim) {
                    // Standard terminal treatment of SGR 2: blend the
                    // resolved foreground 50% toward the background rather
                    // than toward a fixed gray, so dim text stays legible
                    // (and on-theme) against light AND dark backgrounds
                    // alike, same approach xterm/VTE use.
                    val bgForBlend = if (cell.inverse xor reverseVideo) palette.resolve(cell.fg) else palette.resolve(cell.bg)
                    val a = ((android.graphics.Color.alpha(fg) + android.graphics.Color.alpha(bgForBlend)) / 2)
                    val r = ((android.graphics.Color.red(fg) + android.graphics.Color.red(bgForBlend)) / 2)
                    val g = ((android.graphics.Color.green(fg) + android.graphics.Color.green(bgForBlend)) / 2)
                    val b = ((android.graphics.Color.blue(fg) + android.graphics.Color.blue(bgForBlend)) / 2)
                    fg = android.graphics.Color.argb(a, r, g, b)
                }
                if (cell.conceal) {
                    // SGR 8: hide the glyph by painting it (and, since
                    // paint.color also drives isUnderlineText/
                    // isStrikeThruText below, any decoration line too) the
                    // same color as the cell's own actual background -
                    // matching xterm/VTE's own conceal behavior of making
                    // the text visually disappear rather than skipping the
                    // draw call outright (which would misreport as an
                    // empty cell to anything measuring layout). Recomputed
                    // independently of the `dim` blend above rather than
                    // reusing bgForBlend (only in scope inside that `if`)
                    // since conceal and dim can't both meaningfully apply -
                    // conceal wins by running after.
                    fg = if (cell.inverse xor reverseVideo) palette.resolve(cell.fg) else palette.resolve(cell.bg)
                }
                // Compare the RAW ansi index, not the resolved color. Index 0
                // ("default black") is deliberately resolved to a lighter
                // slate for readable black-on-black TEXT, but that same
                // lighter shade must never be painted as a BACKGROUND rect -
                // otherwise every default-background cell (i.e. almost the
                // entire screen, since that's what any program gets after a
                // plain SGR reset) gets covered in a visible blue-gray slab
                // instead of blending into the true-black canvas fill below.
                val bgIndex = if (cell.inverse xor reverseVideo) cell.fg else cell.bg
                // A wide-continuation cell's own background is never drawn
                // separately - see the `cell.wide` branch below, which
                // already paints ITS background stretched across both this
                // column and the wide cell's own, so painting it again here
                // would just be redundant (same color, same rect, drawn
                // twice) rather than actually wrong - skipped purely to
                // avoid the wasted draw call.
                if (bgIndex != TerminalBuffer.DEFAULT_BACKGROUND && !cell.isWideContinuation) {
                    val bg = palette.resolve(bgIndex)
                    bgPaint.color = bg
                    // Wide cells (CJK/fullwidth/emoji - see
                    // TerminalBuffer.Cell.wide's own doc) reserve a real
                    // second column via a following isWideContinuation
                    // cell, so painting only `charWidth` here would leave
                    // that reserved column showing the DEFAULT_BACKGROUND
                    // canvas fill instead of this cell's actual background -
                    // visible as a one-column-wide "notch" of the wrong
                    // color immediately after every colored-background wide
                    // glyph. Stretching this same rect across both columns
                    // is the direct fix; the continuation cell's own
                    // (skipped, see above) background draw would have
                    // painted the identical color into the identical pixels
                    // anyway, so this isn't double-covering anything new.
                    val bgRight = if (cell.wide) x + charWidth * 2 else x + charWidth
                    canvas.nativeCanvas.drawRect(x, y - charHeight, bgRight, y, bgPaint)
                }


                paint.color = fg
                paint.isFakeBoldText = cell.bold
                // Paint's own isUnderlineText always draws a plain straight
                // line in the TEXT's own color - it can't do the wavy
                // undercurl shape, nor an underline color independent of
                // the glyph color (SGR 58). Whenever either applies, this
                // is left off here and drawn by hand instead (below, after
                // drawText) so the built-in decoration doesn't paint a
                // second, wrong-shaped/wrong-colored line underneath it.
                val hasCustomUnderline = cell.underline && (cell.underlineCurly || cell.underlineColor != null)
                paint.isUnderlineText = cell.underline && !hasCustomUnderline
                paint.isStrikeThruText = cell.strikethrough
                paint.textSkewX = if (cell.italic) -0.25f else 0f

                // A plain space draws nothing visible - skipping the
                // drawText call for it (the common case: blank lines,
                // cleared regions, right-padding after short output) cuts
                // a meaningful fraction of the ~1920 drawText calls a full
                // 80x24 redraw would otherwise make. Underlined/struck
                // spaces still need to draw (the line itself is visible
                // even with no glyph). A blinking cell (cell.blink) simply
                // skips its own drawText call entirely during the "off"
                // half of blinkPhaseOn - background/underline/strikethrough
                // rects, which aren't gated by this condition, keep
                // painting normally, so only the glyph itself blinks, same
                // as a real terminal.
                if (cell.blink && !blinkPhaseOn) {
                    // glyph hidden this phase - nothing to draw
                } else if (cell.isWideContinuation) {
                    // Reserved second half of a wide glyph (see
                    // TerminalBuffer.Cell.isWideContinuation's own doc) -
                    // always text=" " with nothing of its own to draw; the
                    // actual glyph was already painted by the wide cell one
                    // column to the left, and this cell's background was
                    // already covered by that same wide cell's stretched-
                    // across-two-columns background rect above. Drawing
                    // its own (blank) text here would do nothing visible
                    // anyway, but skipping it explicitly avoids paying for
                    // a drawText call on every single wide-glyph's trailing
                    // column across a full redraw.
                } else if (cell.kittyPlaceholder != null) {
                    // Kitty Unicode-placeholder cell (see
                    // TerminalBuffer.Cell.kittyPlaceholder's own doc) -
                    // painted here, INSTEAD of the placeholder glyph
                    // itself (which is a PUA codepoint with no sensible
                    // visual glyph anyway), by slicing the referenced
                    // image into a grid per its registered virtual-
                    // placement c=/r= dimensions and drawing just the
                    // (tileRow, tileCol) tile this cell was tagged with.
                    // Falls through to drawing the raw glyph (below,
                    // same as any other character) only if the
                    // placement/image reference turns out stale (an
                    // a=d since this text was written, or a tile index
                    // outside the registered grid) - see
                    // drawKittyPlaceholderTile's own doc.
                    val drew = drawKittyPlaceholderTile(this@drawTerminal, buffer, cell.kittyPlaceholder!!, x, y, charWidth, charHeight)
                    if (!drew && (cell.text != " " || cell.underline || cell.strikethrough)) {
                        canvas.nativeCanvas.drawText(cell.text, x, y, paint)
                    }
                } else if (cell.text != " " || cell.underline || cell.strikethrough) {
                    canvas.nativeCanvas.drawText(cell.text, x, y, paint)
                }

                // Manually-drawn decorations Android's Paint can't express
                // as a flag: overline (no isOverlineText equivalent exists
                // at all) and any underline that needs the curly/undercurl
                // shape or its own independent color (see hasCustomUnderline
                // above). Skipped for a wide glyph's reserved right-hand
                // continuation column (its own decoration was already drawn
                // stretched across both columns by the wide cell itself,
                // one iteration ago - same "don't double-paint" reasoning
                // as the background-rect skip above) and during the "off"
                // half of a blinking cell's cycle, matching how the glyph's
                // own drawText call is skipped in that phase just above.
                if (!cell.isWideContinuation && !(cell.blink && !blinkPhaseOn)) {
                    val decorWidth = if (cell.wide) charWidth * 2 else charWidth
                    if (cell.overline) {
                        decorPaint.color = fg
                        val overlineY = y - charHeight + decorPaint.strokeWidth
                        canvas.nativeCanvas.drawLine(x, overlineY, x + decorWidth, overlineY, decorPaint)
                    }
                    if (hasCustomUnderline) {
                        decorPaint.color = cell.underlineColor?.let { palette.resolve(it) } ?: fg
                        val underlineY = y + charHeight * 0.06f
                        if (cell.underlineCurly) {
                            // Wavy "undercurl" line: a handful of short
                            // up/down segments approximating a sine wave
                            // across the cell's width, matching the spell-
                            // check-squiggle convention kitty/VS Code/
                            // iTerm2 use for SGR 4:3. Segment count scales
                            // with width so a wide (CJK/emoji) cell gets a
                            // proportionally longer squiggle instead of the
                            // same fixed ripple stretched thin across it.
                            val segments = (decorWidth / (charHeight * 0.5f)).toInt().coerceAtLeast(2)
                            val segWidth = decorWidth / segments
                            val amplitude = charHeight * 0.05f
                            val path = android.graphics.Path()
                            path.moveTo(x, underlineY)
                            for (s in 0 until segments) {
                                val segX = x + segWidth * (s + 1)
                                val segY = if (s % 2 == 0) underlineY + amplitude else underlineY - amplitude
                                path.lineTo(segX, segY)
                            }
                            canvas.nativeCanvas.drawPath(path, decorPaint)
                        } else {
                            canvas.nativeCanvas.drawLine(x, underlineY, x + decorWidth, underlineY, decorPaint)
                        }
                    }
                }
            }
        }

        // Placed Sixel images (see TerminalBuffer.PlacedImage's own doc) -
        // painted after the glyph loop above so an image visually covers
        // whatever text/background was in its footprint, same as a real
        // terminal's Sixel output does, and before the cursor block below
        // so the cursor still shows up on top of an image if it happens to
        // land there. PlacedImage.row is a LIVE-grid row index (see
        // placedImages()'s own doc) - once scrollOffset > 0, the visible
        // row a given live-grid row paints at shifts DOWN by however many
        // scrollback lines are currently showing above it, exactly the
        // same translation TerminalBuffer.lineAt applies per-cell for
        // text (scrollbackRowsShown = scrollOffset clamped to how much
        // scrollback actually exists). Previously this skipped drawing
        // images entirely for any scrollOffset != 0 at all - correct only
        // for an image that had ALREADY scrolled off the live grid, but
        // also hid one that's still fully anchored within it (rows 0..
        // buffer.rows-1) just because the user scrolled up a little to
        // see scrollback ABOVE it. A tall image spanning most of the
        // screen made this trivial to hit with a single scroll gesture -
        // "fotograf scroll etmek olmuyor" was this guard blanking the
        // image out the instant scrollOffset left zero, not a real
        // rendering limitation.
        run {
            val scrollbackRowsShown = scrollOffset.coerceAtMost(buffer.maxScrollOffset)
            for (placed in buffer.placedImages()) {
                val visibleRow = placed.row + scrollbackRowsShown
                if (visibleRow !in 0 until buffer.rows) continue
                val bitmap = bitmapFor(placed.image)
                val left = placed.col * charWidth
                val top = visibleRow * charHeight
                // Sixel pixels have their own native resolution, generally
                // NOT a clean multiple of one cell's charWidth/charHeight -
                // scaling the source rect to exactly the image's own pixel
                // size while destination-sizing it in cell units is what
                // drawBitmap(src, dst, paint) does natively, avoiding a
                // separate manual bitmap-scaling step.
                val destWidthCells = (bitmap.width / charWidth).let {
                    if (it <= 0f) 1 else kotlin.math.ceil(it).toInt()
                }.coerceAtMost(buffer.columns - placed.col).coerceAtLeast(1)
                val destHeightPx = bitmap.height.toFloat()
                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    null,
                    android.graphics.RectF(left, top, left + destWidthCells * charWidth, top + destHeightPx),
                    null
                )
            }
        }

        // Placed Kitty images (see TerminalBuffer.KittyPlacement's own doc) -
        // same post-glyph/pre-cursor paint order and live-grid-row-index
        // shift as the Sixel loop just above (see its own doc for why this
        // now shifts by scrollbackRowsShown instead of skipping entirely
        // once scrollOffset != 0), but sorted by z-index first: unlike
        // Sixel (which has no z-index concept and paints in whatever order
        // placedImages() happens to return), Kitty placements with a
        // negative z sit BELOW the text glyph layer and positive/zero z sit
        // ABOVE it - since this loop runs entirely after the glyph loop
        // already finished, only the relative order AMONG kitty placements
        // themselves is actually controllable here (a negative-z image
        // still paints after, hence visually "on top of", cell text that
        // was already drawn - a known simplification; see kittyPlacements()
        // itself for why TerminalBuffer punts this same interleaving
        // decision to this call site). Sorting ascending at least keeps
        // higher z-index placements layered correctly relative to EACH
        // OTHER when several overlap.
        run {
            val scrollbackRowsShown = scrollOffset.coerceAtMost(buffer.maxScrollOffset)
            for (placed in buffer.kittyPlacements().sortedBy { it.z }) {
                val visibleRow = placed.row + scrollbackRowsShown
                if (visibleRow !in 0 until buffer.rows) continue
                val bitmap = bitmapFor(placed.image)

                // Source rectangle (spec's x,y,w,h crop - see
                // TerminalBuffer.KittyPlacement's own doc): srcW/srcH
                // of 0 is the class's own "unspecified, use the whole
                // image" sentinel, so only a genuinely non-zero
                // width/height crops the source rect at all. Clamped
                // against the bitmap's own bounds so a malformed/out-
                // of-range crop (e.g. a client requesting a rect
                // partially outside the image) degrades to whatever
                // portion is actually valid rather than crashing
                // drawBitmap with an invalid src Rect.
                val srcLeft = placed.srcX.coerceIn(0, bitmap.width)
                val srcTop = placed.srcY.coerceIn(0, bitmap.height)
                val srcRight = if (placed.srcW > 0) (srcLeft + placed.srcW).coerceAtMost(bitmap.width) else bitmap.width
                val srcBottom = if (placed.srcH > 0) (srcTop + placed.srcH).coerceAtMost(bitmap.height) else bitmap.height
                val srcRect = android.graphics.Rect(srcLeft, srcTop, srcRight.coerceAtLeast(srcLeft + 1), srcBottom.coerceAtLeast(srcTop + 1))
                val croppedW = srcRect.width()
                val croppedH = srcRect.height()

                // Destination top-left: the anchor cell's own pixel
                // corner, shifted by the X,Y cell-offset keys (clamped
                // to stay within one cell's width/height, per spec's
                // "the offsets must be smaller than the size of the
                // cell"). top uses visibleRow (not placed.row), so the
                // whole placement moves down on screen along with the
                // rest of the viewport while scrolled.
                val left = placed.col * charWidth + placed.cellOffsetX.coerceIn(0, charWidth.toInt() - 1)
                val top = visibleRow * charHeight + placed.cellOffsetY.coerceIn(0, charHeight.toInt() - 1)

                // Destination size: c=/r= (displayCols/displayRows)
                // request an EXACT on-screen size in whole cells that
                // the (cropped) source gets scaled to fit, scaling
                // disproportionately if only one axis was actually
                // requested is avoided by falling back to the natural
                // pixel size on whichever axis wasn't specified (0),
                // matching the spec's own "the other one is computed
                // based on the source image aspect ratio" - approximated
                // here via the cropped rect's own aspect ratio rather
                // than a further explicit computation, since scaling
                // the unspecified axis by the SAME ratio as the
                // specified one already preserves aspect ratio exactly.
                val destWidthPx: Float
                val destHeightPx: Float
                if (placed.displayCols > 0 && placed.displayRows > 0) {
                    destWidthPx = placed.displayCols * charWidth
                    destHeightPx = placed.displayRows * charHeight
                } else if (placed.displayCols > 0) {
                    destWidthPx = placed.displayCols * charWidth
                    destHeightPx = croppedH * (destWidthPx / croppedW.coerceAtLeast(1))
                } else if (placed.displayRows > 0) {
                    destHeightPx = placed.displayRows * charHeight
                    destWidthPx = croppedW * (destHeightPx / croppedH.coerceAtLeast(1))
                } else {
                    // Natural size, clipped to the available columns to
                    // the right of the anchor the same way this loop
                    // always has (a wide image shouldn't paint past the
                    // right edge of the grid).
                    val destWidthCells = (croppedW / charWidth).let {
                        if (it <= 0f) 1 else kotlin.math.ceil(it).toInt()
                    }.coerceAtMost(buffer.columns - placed.col).coerceAtLeast(1)
                    destWidthPx = destWidthCells * charWidth
                    destHeightPx = croppedH.toFloat()
                }

                canvas.nativeCanvas.drawBitmap(
                    bitmap,
                    srcRect,
                    android.graphics.RectF(left, top, left + destWidthPx, top + destHeightPx),
                    null
                )
            }
        }

        // Block cursor: solid white rectangle at the current input position,
        // with that cell's character redrawn in black on top so it stays
        // readable. This is what shows where the next keystroke will land.
        // Only drawn on the live screen - while scrolled back into history
        // (scrollOffset > 0) the cursor's actual row/col don't correspond
        // to what's currently being displayed, so drawing it would just
        // put a stray white block over unrelated scrollback text.
        //
        // Reads buffer.cursorSnapshot() - a single locked read of
        // cursorRow/cursorCol/rows/columns/cursorVisible together - rather
        // than five separate unsynchronized field accesses (buffer.cursorRow,
        // buffer.rows, buffer.cursorCol, buffer.columns, buffer.cursorVisible
        // one at a time, as this used to). See cursorSnapshot's own doc for
        // why that used to be a real cross-thread race: the PTY reader
        // thread (moving the cursor on every escape sequence) and a resize()
        // call from the main thread (keyboard open/close, rotation, pane
        // resize) each individually lock their own writes, but reading the
        // four numbers back one at a time with no lock of its own could
        // still observe a torn mix of pre- and post-resize values - a stray
        // white block landing outside the real grid or on the wrong,
        // just-shifted row, exactly the "beyaz imleç uçuyor, siyah
        // boşluklar beliriyor" symptom that showed up specifically when
        // tapping the terminal (which focuses the hidden field and opens
        // the keyboard - i.e. triggers a resize) while the shell was also
        // actively producing output.
        val cursor = buffer.cursorSnapshot()
        if (!suppressCursor && scrollOffset == 0 && cursor.visible && cursor.row in 0 until cursor.rows && cursor.col in 0 until cursor.columns) {
            val cursorX = cursor.col * charWidth
            val cursorY = (cursor.row + 1) * charHeight
            bgPaint.color = android.graphics.Color.WHITE
            // DECSCUSR shape (see TerminalEmulator.CursorStyle's own doc).
            // BLOCK keeps the original full-cell treatment: solid rect with
            // the glyph redrawn inverted on top, since a translucent glyph
            // over a solid block would be unreadable either way. UNDERLINE/
            // BAR are the "just a caret, don't obscure what's underneath"
            // styles real terminals use for them (vim/nvim's insert-mode
            // thin bar being the common case) - the glyph the main draw
            // loop above already painted for this cell is left alone, and
            // only a thin strip is drawn on top of it.
            when (cursor.style) {
                TerminalEmulator.CursorStyle.BLOCK -> {
                    canvas.nativeCanvas.drawRect(cursorX, cursorY - charHeight, cursorX + charWidth, cursorY, bgPaint)
                    val cursorCell = buffer.cellAt(cursor.row, cursor.col)
                    paint.color = android.graphics.Color.BLACK
                    paint.isFakeBoldText = cursorCell.bold
                    canvas.nativeCanvas.drawText(cursorCell.text, cursorX, cursorY, paint)
                }
                TerminalEmulator.CursorStyle.UNDERLINE -> {
                    val thickness = (charHeight * 0.12f).coerceAtLeast(2f)
                    canvas.nativeCanvas.drawRect(cursorX, cursorY - thickness, cursorX + charWidth, cursorY, bgPaint)
                }
                TerminalEmulator.CursorStyle.BAR -> {
                    val thickness = (charWidth * 0.15f).coerceAtLeast(2f)
                    canvas.nativeCanvas.drawRect(cursorX, cursorY - charHeight, cursorX + thickness, cursorY, bgPaint)
                }
            }
        }
    }
}

/**
 * Draws one custom selection handle: a small filled circle sitting at the
 * text baseline (y = row's bottom edge, matching where SelectionActionBar/
 * the old native handles anchored) with a teardrop "tail" pointing up into
 * the row it marks, plus a thin vertical bar spanning that row's full
 * height so the exact column boundary stays visible even when the finger
 * is covering the circle itself.
 *
 * [x] is the column boundary in px (left edge of the first selected
 * column for the leading/start handle, right edge of the last selected
 * column for the trailing/end handle - see TerminalView's two call sites).
 * [rowTop] is that row's top edge in px; [rowHeight] is charHeightPx.
 * [leading] only affects which side of the vertical bar the circle center
 * sits on (start handle hangs to the left of its column boundary, end
 * handle to the right) - purely cosmetic, doesn't affect hit-testing
 * (TerminalView's gesture block hit-tests both handles with the same
 * generous radius regardless of this offset).
 */
private fun DrawScope.drawSelectionHandle(x: Float, rowTop: Float, rowHeight: Float, color: Int, leading: Boolean) {
    val handlePaint = Paint().apply {
        this.color = color
        isAntiAlias = true
    }
    // Computed outside drawIntoCanvas so `this` unambiguously refers to
    // the outer DrawScope (needed for the .dp.toPx() density conversion)
    // rather than relying on drawIntoCanvas's lambda not shadowing it.
    // Purely the VISUAL size now - the grabbable hit-test area is
    // computed separately in TerminalView's gesture block (see
    // hitRadius there) and is deliberately kept bigger than this so the
    // circle can look small while still being easy to grab with a real
    // finger. Earlier this same value drove both the drawn size AND the
    // hit-test radius, which meant shrinking one always shrank the
    // other - every attempt to make the handle look less oversized also
    // made it harder to actually grab, so it kept getting bumped back up.
    // 0.22f/10dp draws a clearly smaller circle at ordinary terminal
    // font sizes (charHeightPx ~30-40px -> roughly 7-9px radius, close
    // to a real text-cursor handle) without needing a large touch-target
    // floor here at all, since that floor now lives on the separate
    // hit-test radius instead.
    val radius = (rowHeight * 0.22f).coerceAtLeast(10.dp.toPx())
    drawIntoCanvas { canvas ->
        // Vertical bar spanning the row, thin enough not to obscure the
        // glyphs it's marking the edge of.
        val barHalfWidth = rowHeight * 0.08f
        canvas.nativeCanvas.drawRect(x - barHalfWidth, rowTop, x + barHalfWidth, rowTop + rowHeight, handlePaint)

        // Teardrop circle below the row (thumb-sized touch target).
        // Offset left/right of the bar by its own radius so the two
        // handles' circles hang away from the selection rather than
        // overlapping the selected text between them.
        val cx = if (leading) x - radius * 0.3f else x + radius * 0.3f
        val cy = rowTop + rowHeight + radius * 0.7f
        canvas.nativeCanvas.drawCircle(cx, cy, radius, handlePaint)
    }
}
