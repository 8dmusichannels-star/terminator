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
        var char: Char = ' ',
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

    fun cellAt(row: Int, col: Int): Cell = grid[row][col]

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

    /** How many lines are available to scroll back through right now. */
    val maxScrollOffset: Int get() = scrollback.size

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
        savedGrid = savedGrid?.let { resized(it) }
        columns = newColumns
        rows = newRows
        cursorRow = cursorRow.coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
    }

    fun rowText(row: Int): String {
        if (row !in 0 until rows) return ""
        return grid[row].joinToString(separator = "") { it.char.toString() }
    }
}
