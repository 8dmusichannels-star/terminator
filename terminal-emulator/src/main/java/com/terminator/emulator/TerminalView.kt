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
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import android.graphics.Paint
import android.graphics.Typeface
import kotlinx.coroutines.withTimeoutOrNull

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
                            while (true) {
                                val event = awaitPointerEvent()
                                val change = event.changes.firstOrNull { it.id == down.id } ?: break
                                change.consume()
                                if (!change.pressed) {
                                    selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                    break
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

                        while (true) {
                            val event = awaitPointerEvent()
                            val change = event.changes.firstOrNull { it.id == down.id } ?: break
                            change.consume()
                            if (!change.pressed) {
                                selectionState.recomputeFrom(buffer, latestScrollOffset.value)
                                break
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
            drawTerminal(buffer, palette, fontFamily, fontSizeSp, backgroundAlpha, scrollOffset, selectedColumnRanges, highlightColor, suppressCursor)
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
    suppressCursor: Boolean = false
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

    drawIntoCanvas { canvas ->
        for (row in 0 until buffer.rows) {
            for (col in 0 until buffer.columns) {
                val cell = buffer.lineAt(row, col, scrollOffset)
                val x = col * charWidth
                val y = (row + 1) * charHeight

                val fg = if (cell.inverse) palette.resolve(cell.bg) else palette.resolve(cell.fg)
                // Compare the RAW ansi index, not the resolved color. Index 0
                // ("default black") is deliberately resolved to a lighter
                // slate for readable black-on-black TEXT, but that same
                // lighter shade must never be painted as a BACKGROUND rect -
                // otherwise every default-background cell (i.e. almost the
                // entire screen, since that's what any program gets after a
                // plain SGR reset) gets covered in a visible blue-gray slab
                // instead of blending into the true-black canvas fill below.
                val bgIndex = if (cell.inverse) cell.fg else cell.bg
                if (bgIndex != TerminalBuffer.DEFAULT_BACKGROUND) {
                    val bg = palette.resolve(bgIndex)
                    bgPaint.color = bg
                    canvas.nativeCanvas.drawRect(x, y - charHeight, x + charWidth, y, bgPaint)
                }

                paint.color = fg
                paint.isFakeBoldText = cell.bold
                paint.isUnderlineText = cell.underline
                paint.textSkewX = if (cell.italic) -0.25f else 0f

                // A plain space draws nothing visible - skipping the
                // drawText call for it (the common case: blank lines,
                // cleared regions, right-padding after short output) cuts
                // a meaningful fraction of the ~1920 drawText calls a full
                // 80x24 redraw would otherwise make. Underlined spaces
                // still need to draw (the underline itself is visible).
                if (cell.text != " " || cell.underline) {
                    canvas.nativeCanvas.drawText(cell.text, x, y, paint)
                }
            }
        }

        // Block cursor: solid white rectangle at the current input position,
        // with that cell's character redrawn in black on top so it stays
        // readable. This is what shows where the next keystroke will land.
        // Only drawn on the live screen - while scrolled back into history
        // (scrollOffset > 0) the cursor's actual row/col don't correspond
        // to what's currently being displayed, so drawing it would just
        // put a stray white block over unrelated scrollback text.
        if (!suppressCursor && scrollOffset == 0 && buffer.cursorVisible && buffer.cursorRow in 0 until buffer.rows && buffer.cursorCol in 0 until buffer.columns) {
            val cursorX = buffer.cursorCol * charWidth
            val cursorY = (buffer.cursorRow + 1) * charHeight
            bgPaint.color = android.graphics.Color.WHITE
            canvas.nativeCanvas.drawRect(cursorX, cursorY - charHeight, cursorX + charWidth, cursorY, bgPaint)
            val cursorCell = buffer.cellAt(buffer.cursorRow, buffer.cursorCol)
            paint.color = android.graphics.Color.BLACK
            paint.isFakeBoldText = cursorCell.bold
            canvas.nativeCanvas.drawText(cursorCell.text, cursorX, cursorY, paint)
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
