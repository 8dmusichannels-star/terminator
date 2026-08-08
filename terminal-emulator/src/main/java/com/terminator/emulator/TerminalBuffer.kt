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

/**
 * TERMINATOR terminal-emulator module.
 *
 * Screen buffer holding characters, foreground/background color indices and
 * text attributes (bold, underline, inverse) for a fixed-size grid, plus an
 * unlimited scrollback log persisted by TerminalSession to a .history file.
 *
 * Architecture is inspired by the VT100 model used by TermOne Plus
 * (Apache-2.0, gitlab.com/termapps/termoneplus), itself derived from
 * jackpal/Android-Terminal-Emulator. This is an independent Kotlin
 * implementation written for TERMINATOR, not a copy of that source.
 */
class TerminalBuffer(
    var columns: Int,
    var rows: Int
) {
    companion object {
        const val DEFAULT_FOREGROUND = 15 // ANSI bright white
        const val DEFAULT_BACKGROUND = 0  // ANSI black
    }

    data class Cell(
        // A cell's on-screen content as a String rather than a single Char.
        // Most cells are exactly one UTF-16 code unit ("a", "1", " "), but
        // astral-plane characters - emoji among them, e.g. U+1F310 GLOBE
        // WITH MERIDIANS - encode as a UTF-16 *surrogate pair*: two Chars
        // that only mean something together. Iterating a CharSequence one
        // Char at a time (as TerminalEmulator.append did) split that pair
        // across two separate cells, each holding one half of the pair on
        // its own - neither of which is a valid character - so the emoji
        // rendered as two adjacent bogus glyphs instead of one. Storing the
        // full grapheme as a String lets TerminalEmulator hand over both
        // surrogates already joined, so a single Cell always holds one
        // complete, renderable unit.
        var text: String = " ",
        var fg: Int = DEFAULT_FOREGROUND,
        var bg: Int = DEFAULT_BACKGROUND,
        var bold: Boolean = false,
        var underline: Boolean = false,
        var inverse: Boolean = false,
        var italic: Boolean = false
    )

    // Current cursor position, kept in sync by TerminalEmulator on every
    // move/write so the renderer knows where to draw the "waiting for
    // input" caret.
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    // Whether the cursor should be drawn at all (CSI ?25h/l) - full-screen
    // TUI apps commonly hide it during a redraw pass.
    var cursorVisible: Boolean = true

    // Visible screen grid: rows x columns
    private var grid: Array<Array<Cell>> = Array(rows) { Array(columns) { Cell() } }

    // Alternate screen buffer (CSI ?1049h / ?47h), used by full-screen TUI
    // apps like nano/vim/less/htop. Swapping to a separate grid - instead of
    // drawing directly into the primary grid/scrollback - means their
    // redraws don't get interleaved with shell scrollback and the original
    // screen content is intact when they exit.
    private var altGrid: Array<Array<Cell>>? = null
    private var savedGrid: Array<Array<Cell>>? = null
    private var savedCursorRow = 0
    private var savedCursorCol = 0
    val inAlternateScreen: Boolean get() = altGrid != null

    fun enterAlternateScreen() {
        if (altGrid != null) return
        savedGrid = grid
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        grid = Array(rows) { Array(columns) { Cell() } }
        altGrid = grid
    }

    fun exitAlternateScreen() {
        val original = savedGrid ?: return
        grid = original
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
        altGrid = null
        savedGrid = null
    }

    // Unlimited scrollback - lines pushed off the top of the visible grid.
    // Persisted incrementally to disk by TerminalSession (.history file).
    val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    fun cellAt(row: Int, col: Int): Cell =
        if (row in grid.indices && col in 0 until columns) grid[row][col] else Cell()

    /**
     * Reads a cell at a given scroll offset above the live grid - offset 0
     * is the normal (live) screen, offset 1 is one line scrolled back into
     * history, etc. Used by the renderer when the user has dragged the
     * terminal down to look at old output instead of always showing
     * whatever's currently at the bottom.
     *
     * scrollback stores lines oldest-first via addLast (see scrollUp
     * below), so "N lines back from the bottom of scrollback" is
     * `scrollback[scrollback.size - offset]`.
     */
    fun lineAt(row: Int, col: Int, scrollOffset: Int): Cell {
        if (scrollOffset <= 0) return cellAt(row, col)
        val totalScrollback = scrollback.size
        // The visible window is `rows` lines tall. At scrollOffset, the
        // first `scrollOffset` visible rows come from the tail of
        // scrollback and the rest from the top of the live grid.
        val scrollbackRowsShown = scrollOffset.coerceAtMost(totalScrollback)
        return if (row < scrollbackRowsShown) {
            val idx = totalScrollback - scrollbackRowsShown + row
            scrollback.getOrNull(idx)?.getOrNull(col) ?: Cell()
        } else {
            cellAt(row - scrollbackRowsShown, col)
        }
    }

    /**
     * Last column on [row] that has real (non-space) content, respecting
     * [scrollOffset] the same way [lineAt] does - or null if the whole row
     * is blank. Used to snap a long-press/drag that landed on empty
     * terminal space (very common with only a couple of lines of output -
     * most of the screen below the prompt is blank) onto the nearest real
     * text instead of silently selecting/copying nothing.
     */
    fun lastNonBlankColumn(row: Int, scrollOffset: Int): Int? =
        (0 until columns).lastOrNull { col -> lineAt(row, col, scrollOffset).text != " " }

    /** How many lines are available to scroll back through right now. */
    val maxScrollOffset: Int get() = scrollback.size

    /**
     * Plain text between two screen positions (row, col), as currently
     * rendered - i.e. respecting [scrollOffset] the same way [lineAt] does,
     * so selecting into scrollback and copying grabs what's actually on
     * screen rather than the live grid underneath it. The two endpoints can
     * be given in either order (drag-up or drag-down selection); this
     * normalizes them internally. Each line's trailing spaces are trimmed
     * (the common terminal-copy convention - unwritten cells are blank
     * padding, not real content) but a run of spaces in the *middle* of a
     * line is preserved untouched. Multi-row selections are newline-joined.
     */
    fun selectedText(startRow: Int, startCol: Int, endRow: Int, endCol: Int, scrollOffset: Int): String {
        var r1 = startRow; var c1 = startCol
        var r2 = endRow; var c2 = endCol
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            val tr = r1; val tc = c1
            r1 = r2; c1 = c2
            r2 = tr; c2 = tc
        }
        val lines = mutableListOf<String>()
        for (row in r1..r2) {
            if (row !in 0 until rows) continue
            val fromCol = (if (row == r1) c1 else 0).coerceIn(0, columns - 1)
            val toCol = (if (row == r2) c2 else columns - 1).coerceIn(0, columns - 1)
            val sb = StringBuilder()
            for (col in fromCol..toCol) {
                sb.append(lineAt(row, col, scrollOffset).text)
            }
            lines.add(sb.toString().trimEnd(' '))
        }
        return lines.joinToString("\n")
    }

    fun setCell(row: Int, col: Int, cell: Cell) {
        if (row in 0 until rows && col in 0 until columns) {
            grid[row][col] = cell
        }
    }

    fun clearRow(row: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (row !in 0 until rows) return
        for (c in 0 until columns) {
            grid[row][c] = Cell(bg = bg)
        }
    }

    fun clearAll(bg: Int = DEFAULT_BACKGROUND) {
        for (r in 0 until rows) clearRow(r, bg)
    }

    /** Discards scrollback history entirely - used by CSI 3J ("clear
     *  scrollback") and by plain CSI 2J when Settings > Terminal >
     *  "Clear always purges scrollback" is on. Leaves the live grid alone;
     *  callers that want a full clear call this alongside clearAll(). */
    fun clearScrollback() {
        scrollback.clear()
    }

    /** Scrolls the grid up by one line, pushing the top line into scrollback
     *  (unless we're in the alternate screen, where scrolled-off content is
     *  throwaway rather than shell history). */
    fun scrollUp() {
        if (altGrid == null) {
            scrollback.addLast(grid[0])
        }
        for (r in 0 until rows - 1) {
            grid[r] = grid[r + 1]
        }
        grid[rows - 1] = Array(columns) { Cell() }
    }

    /** Scrolls the region [top, bottom] (inclusive) up by one line without
     *  touching scrollback - used for scrolling-region-aware line feeds. */
    fun scrollRegionUp(top: Int, bottom: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (top >= bottom || top !in 0 until rows || bottom !in 0 until rows) return
        for (r in top until bottom) {
            grid[r] = grid[r + 1]
        }
        grid[bottom] = Array(columns) { Cell(bg = bg) }
    }

    /** Scrolls the whole grid down by one line (Reverse Index at the top
     *  margin) - bottom line is dropped, a blank line appears at the top.
     *  Never touches scrollback: RI only re-reveals a blank row, never
     *  "new" content, so there's nothing worth persisting. */
    fun scrollDown(bg: Int = DEFAULT_BACKGROUND) {
        for (r in rows - 1 downTo 1) {
            grid[r] = grid[r - 1]
        }
        grid[0] = Array(columns) { Cell(bg = bg) }
    }

    /** Scrolls the region [top, bottom] (inclusive) down by one line -
     *  the scrolling-region-aware counterpart of [scrollRegionUp], used for
     *  Reverse Index when a custom scroll region (DECSTBM) is active. */
    fun scrollRegionDown(top: Int, bottom: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (top >= bottom || top !in 0 until rows || bottom !in 0 until rows) return
        for (r in bottom downTo top + 1) {
            grid[r] = grid[r - 1]
        }
        grid[top] = Array(columns) { Cell(bg = bg) }
    }

    /** Inserts `count` blank lines at `row`, pushing lines down within
     *  [row, bottom] and dropping any that fall off the bottom. */
    fun insertLines(row: Int, bottom: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (row !in 0 until rows || bottom !in row until rows) return
        var r = bottom
        while (r - count >= row) {
            grid[r] = grid[r - count]
            r--
        }
        while (r >= row) {
            grid[r] = Array(columns) { Cell(bg = bg) }
            r--
        }
    }

    /** Deletes `count` lines at `row`, pulling lines up within [row, bottom]
     *  and filling the vacated bottom rows with blanks. */
    fun deleteLines(row: Int, bottom: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (row !in 0 until rows || bottom !in row until rows) return
        var r = row
        while (r + count <= bottom) {
            grid[r] = grid[r + count]
            r++
        }
        while (r <= bottom) {
            grid[r] = Array(columns) { Cell(bg = bg) }
            r++
        }
    }

    /** Deletes `count` cells at (row, col), shifting the rest of the line
     *  left and filling the vacated right edge with blanks. */
    fun deleteChars(row: Int, col: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (row !in 0 until rows) return
        val line = grid[row]
        var c = col
        while (c + count < columns) {
            line[c] = line[c + count]
            c++
        }
        while (c < columns) {
            line[c] = Cell(bg = bg)
            c++
        }
    }

    /** Inserts `count` blank cells at (row, col), shifting the rest of the
     *  line right and dropping any that fall off the right edge. */
    fun insertChars(row: Int, col: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) {
        if (row !in 0 until rows) return
        val line = grid[row]
        var c = columns - 1
        while (c - count >= col) {
            line[c] = line[c - count]
            c--
        }
        while (c >= col) {
            line[c] = Cell(bg = bg)
            c--
        }
    }

    /** Resizes the grid, preserving existing content where possible. */
    fun resize(newColumns: Int, newRows: Int) {
        fun resized(g: Array<Array<Cell>>): Array<Array<Cell>> = Array(newRows) { r ->
            Array(newColumns) { c ->
                if (r < rows && c < columns) g[r][c] else Cell()
            }
        }
        grid = resized(grid)
        // When alternate screen is active, `grid` above just became a
        // freshly-resized array - but altGrid, which is meant to be the
        // SAME array as grid while alternate screen is active (see
        // enterAlternateScreen()), was never updated to match, so it kept
        // pointing at the old pre-resize array instead. inAlternateScreen
        // still read true (altGrid was non-null, just stale) and rendering
        // itself was unaffected since TerminalView reads through grid/
        // cellAt() rather than altGrid directly - but exitAlternateScreen()
        // followed by another enterAlternateScreen() before this session
        // resized again would have silently resumed writing into that
        // stale, wrong-sized array. Re-pointing it here keeps the "altGrid
        // is grid, while active" invariant intact across a resize.
        if (altGrid != null) altGrid = grid
        savedGrid = savedGrid?.let { resized(it) }
        columns = newColumns
        rows = newRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
    }

    fun rowText(row: Int): String {
        if (row !in 0 until rows) return ""
        return grid[row].joinToString(separator = "") { it.text }
    }
}
