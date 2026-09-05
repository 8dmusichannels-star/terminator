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

import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * TERMINATOR terminal-emulator module.
 *
 * Screen buffer holding characters, foreground/background color indices and
 * text attributes (bold, underline, inverse) for a fixed-size grid, plus a
 * capped in-memory scrollback log (see MAX_SCROLLBACK_LINES) that's also
 * persisted, uncapped, by TerminalSession to a .history file.
 *
 * Architecture is inspired by the VT100 model used by TermOne Plus
 * (Apache-2.0, gitlab.com/termapps/termoneplus), itself derived from
 * jackpal/Android-Terminal-Emulator. This is an independent Kotlin
 * implementation written for TERMINATOR, not a copy of that source.
 *
 * Thread safety: TerminalEmulator.append() mutates this buffer from the
 * pty's dedicated reader thread (see TerminalSession.start), while
 * TerminalView/Compose reads it from the UI thread on every recomposition
 * (draw pass, selection, copy). Both sides go through this class's public
 * methods, so a single lock here - rather than in either caller - is the
 * one place that can cover every access. Without it, a fast-producing
 * command (the classic repro is the `yes` command, which floods the pty
 * with output as quickly as the shell can write it) drives the reader
 * thread to mutate `grid`/`scrollback` many times a second while the UI
 * thread is concurrently iterating those same arrays/deque to draw a
 * frame - a race that surfaces as ArrayIndexOutOfBoundsException or
 * ConcurrentModificationException and crashes the app. Ordinary keyboard
 * typing almost never produces output fast enough to hit this window,
 * which is why it reads as a `yes`-specific crash rather than a general
 * one. The lock is a plain (non-fair) ReentrantLock: contention is brief
 * (each method call is O(rows*columns) at worst, no I/O), and reentrancy
 * matters because some of these methods call each other (e.g. lineAt ->
 * cellAt, selectedText -> lineAt).
 */
class TerminalBuffer(
    var columns: Int,
    var rows: Int
) {
    companion object {
        const val DEFAULT_FOREGROUND = 15 // ANSI bright white
        const val DEFAULT_BACKGROUND = 0  // ANSI black

        // In-memory scrollback cap, in lines. The full history still lands
        // on disk uncapped via TerminalSession's .history file - this only
        // bounds what's kept live as Cell objects here. Without a cap, a
        // fast-producing command (`yes` is the textbook repro: it floods
        // the pty with a line at a time as fast as the shell can write,
        // easily thousands of lines a second) pushes one more
        // Array(columns){Cell()} onto `scrollback` per line, forever, for
        // as long as the command keeps running - each ArrayDeque add is
        // O(1) and the ReentrantLock below keeps it thread-safe, so
        // nothing ever throws or blocks on that side, but the heap grows
        // without bound until the process is OOM-killed. That's what
        // actually crashes the app on `yes` even with the reader/UI race
        // fixed: the race produced an exception on the spot, this produces
        // an OutOfMemoryError a few seconds to a couple minutes in
        // (depending on device memory) - long enough to read as "the
        // terminal just crashes" rather than obviously being a leak.
        // 10,000 lines is generous scrollback (Termux and most desktop
        // terminals default in the low thousands) while keeping worst-case
        // memory bounded regardless of how long a flooding command runs.
        const val MAX_SCROLLBACK_LINES = 10_000
    }

    private val lock = ReentrantLock()

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
    val inAlternateScreen: Boolean get() = lock.withLock { altGrid != null }

    fun enterAlternateScreen() = lock.withLock {
        if (altGrid != null) return@withLock
        savedGrid = grid
        savedCursorRow = cursorRow
        savedCursorCol = cursorCol
        grid = Array(rows) { Array(columns) { Cell() } }
        altGrid = grid
    }

    fun exitAlternateScreen() = lock.withLock {
        val original = savedGrid ?: return@withLock
        grid = original
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
        altGrid = null
        savedGrid = null
    }

    // Scrollback, capped at MAX_SCROLLBACK_LINES - lines pushed off the top
    // of the visible grid. Persisted incrementally and *without* this cap
    // to disk by TerminalSession (.history file), so nothing is actually
    // lost - only how much of it this class keeps as live objects.
    val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    // Counts lines pushed into scrollback by scrollUp() since the last
    // consumePendingScrollLines() call. A running program's own output -
    // completely separate from the user dragging scrollOffset - also
    // shifts what every row/scrollOffset pair addresses, but nothing
    // previously told an in-progress text selection that had happened:
    // shiftRows()/recomputeFrom() only ever ran from the user-scroll call
    // sites (edge-auto-scroll, drag-while-selecting), all of which change
    // scrollOffset itself. New PTY output changes nothing about
    // scrollOffset - it mutates scrollback/grid directly - so a selection
    // sitting idle (or a long one still being read before lift) while a
    // flooding command like `yes` or `cat` keeps printing silently went
    // stale: the same (row, scrollOffset) pair it was anchored to now
    // refers to different, newer content, so Copy could grab the wrong
    // lines or - once enough output evicted the exact scrollback lines a
    // selection pointed into - some rows resolved to blank Cells instead.
    // Longer-lived selections (long drags, or a pause before lifting the
    // handle) simply have a wider window for this to happen in, which is
    // why it reads as "bazen oluyor, uzun seçimlerde" rather than always.
    private var pendingScrollLines: Int = 0

    /** Returns and clears the number of lines scrollUp() has pushed into
     *  scrollback since the last call - lets a caller (TerminalView, once
     *  per content-change tick) detect PTY-output-driven scrolling that
     *  scrollOffset-based tracking never sees, and shiftRows()/clear() an
     *  active selection to match. Not under `lock`: reads/writes of a
     *  single Int are already atomic enough here, and wrapping this in
     *  the same lock scrollUp() takes would risk a self-deadlock if a
     *  future caller ever consumed it from inside another locked call. */
    fun consumePendingScrollLines(): Int {
        val n = pendingScrollLines
        pendingScrollLines = 0
        return n
    }

    // Set by resize() whenever newColumns != columns. Row-level shifting
    // (pendingScrollLines) is enough to keep a selection's row indices
    // pointing at the right LINE after a resize, but a column-count change
    // invalidates the selection's anchorCol/focusCol outright - "column 40"
    // meant something different on an 80-wide grid than it does on a
    // 47-wide one, and there's no shift that fixes that, only re-selecting.
    // A caller (TerminalView) should treat this the same as consuming
    // pendingScrollLines: clear() the selection instead of shiftRows()-ing
    // it whenever this reads true.
    private var pendingColumnsChanged: Boolean = false

    /** Returns and clears whether resize() changed the column count since
     *  the last call - see [pendingColumnsChanged]'s doc for why this means
     *  "clear the selection", not "shift it". */
    fun consumePendingColumnsChanged(): Boolean {
        val v = pendingColumnsChanged
        pendingColumnsChanged = false
        return v
    }

    fun cellAt(row: Int, col: Int): Cell = lock.withLock {
        if (row in grid.indices && col in 0 until columns) grid[row][col] else Cell()
    }

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
    fun lineAt(row: Int, col: Int, scrollOffset: Int): Cell = lock.withLock {
        if (scrollOffset <= 0) return@withLock cellAt(row, col)
        val totalScrollback = scrollback.size
        // The visible window is `rows` lines tall. At scrollOffset, the
        // first `scrollOffset` visible rows come from the tail of
        // scrollback and the rest from the top of the live grid.
        val scrollbackRowsShown = scrollOffset.coerceAtMost(totalScrollback)
        if (row < scrollbackRowsShown) {
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
    fun lastNonBlankColumn(row: Int, scrollOffset: Int): Int? = lock.withLock {
        (0 until columns).lastOrNull { col -> lineAt(row, col, scrollOffset).text != " " }
    }

    /** How many lines are available to scroll back through right now. */
    val maxScrollOffset: Int get() = lock.withLock { scrollback.size }

    /**
     * [row]'s on-screen text, respecting [scrollOffset] the same way
     * [lineAt] does - but, unlike [selectedText] and [fullText],
     * deliberately NOT trimmed of trailing spaces. This backs
     * TerminalView's native-selection overlay: an invisible row of real
     * text stacked directly on top of the Canvas-painted glyph grid, used
     * so Android's own SelectionContainer can own long-press/drag
     * selection and Copy instead of the app hand-tracking (row, col)
     * pairs. Every character in that overlay row has to land at the same
     * col*charWidth X position the Canvas below it painted that column
     * at - trimming here would shorten some rows more than others and
     * throw that alignment off for every column after the trim point.
     */
    fun rowPlainText(row: Int, scrollOffset: Int): String = lock.withLock {
        val sb = StringBuilder(columns)
        for (col in 0 until columns) {
            sb.append(lineAt(row, col, scrollOffset).text)
        }
        sb.toString()
    }

    /**
     * The session's entire visible output as plain text: everything still
     * in scrollback (oldest first) followed by the current on-screen grid,
     * each row trimmed of trailing padding the same way [selectedText]
     * trims a selection. Used by the runner toolbar's save/export button -
     * "everything the terminal has shown", not just what got selected and
     * copied by hand. Bounded by however much scrollback is actually kept
     * (MAX_SCROLLBACK_LINES) - older output that already scrolled out
     * isn't recoverable here, same limit selectedText()/lineAt() already
     * have.
     */
    fun fullText(): String = lock.withLock {
        val lines = mutableListOf<String>()
        val totalScrollback = scrollback.size
        // lineAt(row, col, scrollOffset) only resolves scrollback rows when
        // scrollOffset > 0 (offset 0 is always just the live grid - see its
        // own doc). To read scrollback line `idx` (0 = oldest), the
        // equivalent view is "row 0 at scrollOffset = totalScrollback - idx"
        // - i.e. walk scrollOffset down from its max toward 0 as idx
        // increases, which lands on row 0 of that offset's window each
        // time rather than trying to address scrollback with a negative
        // row number (which cellAt() - what scrollOffset=0 falls through
        // to - doesn't support; it just returns a blank Cell for anything
        // outside the visible [0, rows) range.
        for (idx in 0 until totalScrollback) {
            val sb = StringBuilder()
            val offset = totalScrollback - idx
            for (col in 0 until columns) {
                sb.append(lineAt(0, col, scrollOffset = offset).text)
            }
            lines.add(sb.toString().trimEnd(' '))
        }
        for (row in 0 until rows) {
            val sb = StringBuilder()
            for (col in 0 until columns) {
                sb.append(lineAt(row, col, scrollOffset = 0).text)
            }
            lines.add(sb.toString().trimEnd(' '))
        }
        lines.joinToString("\n")
    }

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
     *
     * Trailing fully-blank rows are dropped from the result (but never
     * leading ones - see below). The drag-to-extend-selection gesture has
     * no equivalent of the long-press start point's snap-to-last-real-
     * content behavior (MainActivity's lastNonBlankColumn call, used only
     * when a selection is first created): every frame it just floors the
     * raw finger position to a (row, col), so a drag that runs past the
     * last line of real output into the blank terminal space below the
     * prompt - extremely easy to do, since that blank space is most of the
     * screen after only a couple lines of output - extended the selection
     * across those empty rows too. Each contributed its own empty string,
     * still joined by "\n" like any other row, so Copy produced trailing
     * blank lines the user never meant to grab ("kopyalama yaparken
     * boşluklar oluşuyor bazen"). Only trimming from the end (not the
     * start) matters here: a selection's start point already went through
     * that snap-to-content logic when it was first placed, so a genuinely
     * blank *first* row only happens if the user deliberately long-pressed
     * on empty space with no real content anywhere on that row - in which
     * case leaving it alone is correct, there's nothing to snap to.
     */
    fun selectedText(startRow: Int, startCol: Int, endRow: Int, endCol: Int, scrollOffset: Int): String = lock.withLock {
        var r1 = startRow; var c1 = startCol
        var r2 = endRow; var c2 = endCol
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            val tr = r1; val tc = c1
            r1 = r2; c1 = c2
            r2 = tr; c2 = tc
        }
        val lines = mutableListOf<String>()
        for (row in r1..r2) {
            // NOT `if (row !in 0 until rows) continue`. r1/r2 are
            // screen-space rows meant to be resolved together with
            // scrollOffset via lineAt() - same contract lineAt's own doc
            // describes. A selection's stationary endpoint can end up
            // outside [0, rows) by design: MainActivity shifts it by the
            // scroll delta every time auto-scroll-while-dragging changes
            // scrollOffset underneath it, specifically so the selection
            // keeps tracking the same *text* rather than silently
            // relabeling itself to whatever now sits at the old row
            // number - see that call site's own doc for why. A row
            // outside [0, rows) here isn't invalid, it means "further
            // into scrollback than the current viewport's top" (negative)
            // or "further toward live output than the viewport's bottom"
            // (>= rows) - both real, resolvable positions. Skipping them
            // silently dropped exactly those rows from both what got
            // highlighted AND what Copy actually produced, which is why
            // dragging a selection past the top or bottom of the visible
            // screen truncated the copied text at the edge instead of
            // continuing to follow the finger. lineAt (and the cellAt it
            // falls back to) already bounds-check internally and return a
            // blank Cell for anything before scrollback's start or after
            // the live grid's end, so calling it with an out-of-window
            // row is always safe - there was never a need for this loop
            // to pre-filter what lineAt can already handle.
            val fromCol = (if (row == r1) c1 else 0).coerceIn(0, columns - 1)
            val toCol = (if (row == r2) c2 else columns - 1).coerceIn(0, columns - 1)
            val sb = StringBuilder()
            for (col in fromCol..toCol) {
                sb.append(lineAt(row, col, scrollOffset).text)
            }
            lines.add(sb.toString().trimEnd(' '))
        }
        // Drop trailing blank rows picked up by an over-drag past the last
        // real line - see this function's doc. Keeps at least one line so
        // a selection that is genuinely all blank (single row, or the user
        // really did drag across nothing but empty space) still copies as
        // an empty string rather than throwing an index exception here.
        while (lines.size > 1 && lines.last().isEmpty()) {
            lines.removeAt(lines.size - 1)
        }
        lines.joinToString("\n")
    }

    fun setCell(row: Int, col: Int, cell: Cell) = lock.withLock {
        if (row in 0 until rows && col in 0 until columns) {
            grid[row][col] = cell
        }
    }

    fun clearRow(row: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (row !in 0 until rows) return@withLock
        for (c in 0 until columns) {
            grid[row][c] = Cell(bg = bg)
        }
    }

    fun clearAll(bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        for (r in 0 until rows) clearRow(r, bg)
    }

    /** Discards scrollback history entirely - used by CSI 3J ("clear
     *  scrollback") and by plain CSI 2J when Settings > Terminal >
     *  "Clear always purges scrollback" is on. Leaves the live grid alone;
     *  callers that want a full clear call this alongside clearAll(). */
    fun clearScrollback() = lock.withLock {
        scrollback.clear()
    }

    /** Scrolls the grid up by one line, pushing the top line into scrollback
     *  (unless we're in the alternate screen, where scrolled-off content is
     *  throwaway rather than shell history). */
    fun scrollUp() = lock.withLock {
        if (altGrid == null) {
            scrollback.addLast(grid[0])
            // Drop from the front (oldest) once over the cap, same as any
            // ring-buffer-style scrollback - see MAX_SCROLLBACK_LINES doc
            // for why this exists at all. removeFirst() is O(1) on
            // ArrayDeque, so this stays cheap even called once per line
            // under a flooding command like `yes`.
            while (scrollback.size > MAX_SCROLLBACK_LINES) {
                scrollback.removeFirst()
            }
            // Alternate-screen scrolling (TUI apps) has no scrollback
            // semantics at all - see the altGrid==null guard just above -
            // so a selection can't be pointing into it via scrollOffset in
            // the first place; only count real, primary-screen scrolling
            // here. See consumePendingScrollLines()'s doc for why this
            // exists.
            pendingScrollLines++
        }
        for (r in 0 until rows - 1) {
            grid[r] = grid[r + 1]
        }
        grid[rows - 1] = Array(columns) { Cell() }
    }

    /** Scrolls the region [top, bottom] (inclusive) up by one line without
     *  touching scrollback - used for scrolling-region-aware line feeds. */
    fun scrollRegionUp(top: Int, bottom: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (top >= bottom || top !in 0 until rows || bottom !in 0 until rows) return@withLock
        for (r in top until bottom) {
            grid[r] = grid[r + 1]
        }
        grid[bottom] = Array(columns) { Cell(bg = bg) }
    }

    /** Scrolls the whole grid down by one line (Reverse Index at the top
     *  margin) - bottom line is dropped, a blank line appears at the top.
     *  Never touches scrollback: RI only re-reveals a blank row, never
     *  "new" content, so there's nothing worth persisting. */
    fun scrollDown(bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        for (r in rows - 1 downTo 1) {
            grid[r] = grid[r - 1]
        }
        grid[0] = Array(columns) { Cell(bg = bg) }
    }

    /** Scrolls the region [top, bottom] (inclusive) down by one line -
     *  the scrolling-region-aware counterpart of [scrollRegionUp], used for
     *  Reverse Index when a custom scroll region (DECSTBM) is active. */
    fun scrollRegionDown(top: Int, bottom: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (top >= bottom || top !in 0 until rows || bottom !in 0 until rows) return@withLock
        for (r in bottom downTo top + 1) {
            grid[r] = grid[r - 1]
        }
        grid[top] = Array(columns) { Cell(bg = bg) }
    }

    /** Inserts `count` blank lines at `row`, pushing lines down within
     *  [row, bottom] and dropping any that fall off the bottom. */
    fun insertLines(row: Int, bottom: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (row !in 0 until rows || bottom !in row until rows) return@withLock
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
    fun deleteLines(row: Int, bottom: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (row !in 0 until rows || bottom !in row until rows) return@withLock
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
    fun deleteChars(row: Int, col: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (row !in 0 until rows) return@withLock
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
    fun insertChars(row: Int, col: Int, count: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (row !in 0 until rows) return@withLock
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
    fun resize(newColumns: Int, newRows: Int) = lock.withLock {
        val oldColumns = columns
        // On a shrink, rows above `rowOffset` are about to fall off the top
        // of the grid. Previously they were just discarded outright - the
        // helper below always kept rows 0 until newRows of the OLD grid,
        // i.e. the TOP of the pre-resize screen, and threw away everything
        // below that, including whatever was near the cursor. That's what
        // made a floating-pane shrink both destroy scrollback AND make
        // stale old-top-of-screen content resurface once new output landed
        // on top of it ("eski komutlar yeni pencereye yansıyor").
        // On the primary screen (alternate-screen apps like htop/vim have
        // no scrollback semantics - same altGrid-null guard used for CSI 3J
        // elsewhere in this class) push those overflowing rows into
        // scrollback, oldest-first, and keep the BOTTOM newRows rows -
        // where the cursor and most recent output actually are - as the
        // new grid, mirroring how a real terminal shrink behaves.
        val rowOffset = if (altGrid == null && newRows < rows) rows - newRows else 0
        if (rowOffset > 0) {
            for (i in 0 until rowOffset) {
                scrollback.addLast(grid[i])
            }
            while (scrollback.size > MAX_SCROLLBACK_LINES) {
                scrollback.removeFirst()
            }
        }
        // On a grow (pinch-zoom out, or an immediate grow right after a
        // shrink from the same gesture), newRows > rows means we need MORE
        // rows than the old grid had. The naive approach - just pad the
        // extra rows with blank Cell() - is what was happening before, and
        // it's wrong whenever the primary screen (altGrid == null, same
        // scrollback-eligible guard as above) just pushed rows into
        // scrollback moments earlier: those rows are still the most recent
        // reality of the top of the screen, and the user watching a rapid
        // pinch (shrink then grow, tick after tick) would see them get
        // silently erased instead of reappearing, which is exactly the
        // "growing patches of blank rows eating the screen" symptom.
        // Pull back up to `growCount` lines from the tail of scrollback
        // (oldest-appended-last, so the tail is what was most recently
        // pushed) to seed the newly-added rows at the top instead of
        // leaving them blank.
        val growCount = if (altGrid == null && newRows > rows) newRows - rows else 0
        val reclaimed: List<Array<Cell>> = if (growCount > 0) {
            val take = minOf(growCount, scrollback.size)
            val lines = ArrayList<Array<Cell>>(take)
            repeat(take) { lines.add(0, scrollback.removeLast()) }
            lines
        } else {
            emptyList()
        }
        val growOffset = reclaimed.size
        // scrollback didn't have enough lines to fill the whole grow (the
        // common case right after a session starts, before enough output
        // has accumulated to push anything into scrollback yet - exactly
        // when a floating pane's keyboard-close or corner-drag grow first
        // happens). The old code only ever inserted `reclaimed.size` blank/
        // reclaimed rows at the top and left the remaining
        // growCount - growOffset shortfall for `resized()`'s row-count math
        // to implicitly pad at the BOTTOM instead (newRows rows total, only
        // growOffset of them accounted for above the shifted-down old
        // content) - which put a growing gap of dead blank rows under the
        // live screen and left the actual content (and the cursor sitting
        // in it) stranded near the TOP of the now-taller grid instead of
        // anchored to the bottom where a real terminal keeps it. That's the
        // "beyaz cursor ekranin ortasinda asili kaliyor" bug: cursorRow's
        // own coerceIn below never moved it, because rowOffset/growOffset
        // (the only terms that shift it) didn't reflect this shortfall at
        // all. Padding the shortfall onto growOffset itself - as genuinely
        // blank rows, same shape as a reclaimed line - fixes both the
        // padding side (blanks now land above the content, not below) and
        // the cursor math below (which reads growOffset) in one place.
        val growShortfall = growCount - growOffset
        val totalTopPadding = growOffset + growShortfall
        // Reclaimed lines were stored at the OLD column width, which may
        // differ from newColumns (the same pinch tick can change both rows
        // and columns at once as font size changes). Re-map them through
        // the same column-width adjustment the rest of the grid gets below,
        // instead of copying the raw array directly - otherwise a width
        // change on the same tick would either truncate silently (array too
        // long for the new row) or crash with an index-out-of-bounds
        // (array too short).
        fun resizeRow(line: Array<Cell>): Array<Cell> = Array(newColumns) { c ->
            if (c < line.size) line[c] else Cell()
        }
        fun resized(g: Array<Array<Cell>>): Array<Array<Cell>> = Array(newRows) { r ->
            if (r < totalTopPadding) {
                // First growOffset rows are real reclaimed scrollback;
                // anything beyond that (the growShortfall) is genuinely
                // blank - there was no more scrollback left to pull from -
                // but still belongs at the TOP alongside the reclaimed
                // rows, not implicitly at the bottom.
                if (r < growOffset) resizeRow(reclaimed[r]) else Array(newColumns) { Cell() }
            } else {
                val srcRow = r - totalTopPadding + rowOffset
                Array(newColumns) { c ->
                    if (srcRow < rows && c < columns) g[srcRow][c] else Cell()
                }
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
        // savedGrid only exists while altGrid != null (see
        // enterAlternateScreen/exitAlternateScreen), and growCount above is
        // gated on altGrid == null - so whenever savedGrid is non-null,
        // growOffset is guaranteed to be 0 and `reclaimed` is empty. Resize
        // it with its own rowOffset-only helper (no reclaimed rows) rather
        // than reusing the closure above, so it never accidentally
        // double-dips into rows already handed to the primary grid.
        fun resizedPlain(g: Array<Array<Cell>>): Array<Array<Cell>> = Array(newRows) { r ->
            val srcRow = r + rowOffset
            Array(newColumns) { c ->
                if (srcRow < rows && c < columns) g[srcRow][c] else Cell()
            }
        }
        savedGrid = savedGrid?.let { resizedPlain(it) }
        columns = newColumns
        rows = newRows
        // Content moved up by rowOffset rows above (bottom of the old grid
        // is now the new grid), so cursorRow/savedCursorRow have to move
        // with it - otherwise the cursor (and a later DECRC restoring
        // savedCursorRow) would land on the wrong, now-shifted row.
        // Conversely, totalTopPadding rows were just inserted ABOVE the old
        // content (growOffset reclaimed from scrollback, growShortfall
        // genuinely blank - see totalTopPadding's own doc), so the cursor
        // has to move DOWN by that same total or it'll end up sitting that
        // many rows too high, floating over content that isn't actually
        // where the cursor last was.
        cursorRow = (cursorRow + totalTopPadding - rowOffset).coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
        if (rowOffset > 0) {
            savedCursorRow = (savedCursorRow - rowOffset).coerceIn(0, rows - 1)
        }
        // Same "content moved under a fixed (row, scrollOffset) pair"
        // situation consumePendingScrollLines()'s doc describes for
        // scrollUp() - a pinch-zoom resize shifts every row's content up
        // by rowOffset (shrink) or down by growOffset (grow) without
        // touching scrollOffset, so an active selection anchored to old
        // row indices silently points at different content afterward: the
        // handles/highlight visually "stick" to whatever now occupies
        // those same row numbers instead of the text the user actually
        // selected - which is exactly what made a selection appear over
        // unselected text after zooming. Net delta matches scrollUp()'s
        // sign convention (positive = pushed into scrollback / content
        // moved up), so it's rowOffset - totalTopPadding (not just
        // growOffset - see totalTopPadding's own doc: old content actually
        // shifts down by growOffset PLUS growShortfall, not growOffset
        // alone, whenever scrollback ran out before the grow was fully
        // covered); TerminalView's LaunchedEffect(bufferVersion) picks this
        // up the same tick it consumes any scrollUp()-driven lines.
        if (rowOffset != totalTopPadding) {
            pendingScrollLines += (rowOffset - totalTopPadding)
        }
        if (newColumns != oldColumns) {
            pendingColumnsChanged = true
        }
    }

    fun rowText(row: Int): String = lock.withLock {
        if (row !in 0 until rows) return@withLock ""
        grid[row].joinToString(separator = "") { it.text }
    }
}
