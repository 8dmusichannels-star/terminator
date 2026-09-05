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
        var italic: Boolean = false,
        // SGR 2 (dim/faint) / 22 (normal intensity, off). 22 already
        // doubles as "bold off" (see TerminalEmulator.applySgr) - real
        // terminals treat 22 as "reset BOTH bold and dim", since bold and
        // dim are the two ends of the same "intensity" attribute and only
        // one can be active at a time. Rendered as the resolved foreground
        // blended toward the background (see drawTerminal), the standard
        // way terminals distinguish dim from a plain color change.
        var dim: Boolean = false,
        // SGR 5 (blink) / 25 (steady, off). Rendering is timer-driven (see
        // TerminalView's blinkPhase) rather than a static paint flag, since
        // blink is genuinely an animated attribute, not a fixed glyph style
        // like bold/italic/underline.
        var blink: Boolean = false,
        // SGR 9 (strikethrough/crossed-out) / 29 (off).
        var strikethrough: Boolean = false
    )

    // Current cursor position, kept in sync by TerminalEmulator on every
    // move/write so the renderer knows where to draw the "waiting for
    // input" caret.
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    // Whether the cursor should be drawn at all (CSI ?25h/l) - full-screen
    // TUI apps commonly hide it during a redraw pass.
    var cursorVisible: Boolean = true

    /**
     * Immutable snapshot of everything TerminalView's draw pass needs to
     * decide where (and whether) to paint the block cursor, all read
     * together under [lock] in one atomic step - see [cursorSnapshot]'s own
     * doc for why reading cursorRow/cursorCol/rows/columns/cursorVisible as
     * five separate unsynchronized field reads (which is what TerminalView
     * used to do directly) was never actually safe.
     */
    data class CursorSnapshot(
        val row: Int,
        val col: Int,
        val rows: Int,
        val columns: Int,
        val visible: Boolean
    )

    /**
     * Every one of cursorRow/cursorCol/rows/columns is a plain public var,
     * and every WRITER of any of them (setCell, resize, scrollUp, and
     * every other mutator in this class) correctly goes through
     * `lock.withLock` - so writers never race each other. But
     * TerminalView's draw pass (running on the Compose UI thread, not the
     * PTY reader thread that drives most of those writes) used to read
     * cursorRow, buffer.rows, cursorCol and buffer.columns as four
     * separate, completely unsynchronized field accesses:
     *
     *   if (!suppressCursor && ... buffer.cursorRow in 0 until buffer.rows
     *       && buffer.cursorCol in 0 until buffer.columns) { ... }
     *
     * Locked writers with an unlocked reader still races: the JVM/Kotlin
     * memory model gives no visibility or ordering guarantee for a read
     * that never itself synchronizes, even if every write it might
     * observe was individually made under a lock. Concretely, resize()
     * (called from the main thread whenever the keyboard opens/closes,
     * rotates, or a pane is resized) reassigns `rows`/`columns` AND
     * shifts `cursorRow` inside one `lock.withLock` block - but a draw
     * pass reading those four fields one at a time, with no lock of its
     * own, could observe `rows` already shrunk to its new (smaller) value
     * while `cursorRow` still held the OLD (larger, now out-of-range in
     * the new grid but still in-range against the stale bounds check)
     * value, or any other torn combination of before/after values across
     * the four fields - all while the PTY reader thread might
     * SIMULTANEOUSLY be moving the cursor via a totally unrelated
     * `setCell`/escape-sequence write that also takes the same lock a
     * moment later. The result was a genuinely torn snapshot presented to
     * the renderer: a cursor position that could transiently point
     * outside the real current grid, or land on a row that had just been
     * reshuffled by the resize's own scrollback-shift math, painting a
     * stray white block in the wrong place or over content that had
     * already moved - exactly the "beyaz imleç uçuyor, siyah boşluklar
     * beliriyor" reported when tapping the terminal (which focuses the
     * hidden field and opens the keyboard, i.e. triggers exactly this
     * resize) while the shell was also actively producing output on the
     * reader thread. suppressCursor's own gating (pinch-zoom preview,
     * manual pane resize, split-divider drag) only ever addressed the
     * SEPARATE "stale coordinates vs. new charWidth/charHeight" issue -
     * it does nothing for this thread-race, since the numbers it reads
     * are exactly as unsynchronized as before.
     *
     * This single method takes the lock once and reads all four fields
     * (plus cursorVisible, same hazard) inside that one critical section,
     * so the renderer always sees one mutually-consistent instant - the
     * pre-resize state, or the fully-post-resize state, never a mix of
     * the two - matching how every other cross-field read in this class
     * (cellAt, rowText, etc.) already behaves.
     */
    fun cursorSnapshot(): CursorSnapshot = lock.withLock {
        CursorSnapshot(cursorRow, cursorCol, rows, columns, cursorVisible)
    }

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

    // Counts rows resize() has pushed into scrollback (rowOffset, the same
    // value folded into pendingScrollLines above) since the last
    // consumePendingResizeScrollLines() call - kept as its OWN separate
    // counter rather than reusing pendingScrollLines, because the two need
    // different consumers reacting differently:
    //
    // - pendingScrollLines (shared with plain scrollUp() output) drives
    //   shiftRows()/clear() on an ACTIVE SELECTION only - see its own doc.
    //   A selection that isn't there has nothing to shift either way.
    //
    // - This counter drives keeping scrollOffset itself pointing at the
    //   same actual content when a resize (not ordinary output) is what
    //   moved it. A resize's rowOffset push is fundamentally different
    //   from scrollUp()'s: scrollUp() adds ONE new line the user hasn't
    //   seen yet (so leaving scrollOffset numerically unchanged, i.e.
    //   "keep looking at the same N-lines-back-from-live-bottom", is a
    //   reasonable, common terminal-emulator default - the user stays
    //   anchored by row count, and can scroll-follow to the bottom if they
    //   want the new line). A resize's rowOffset instead RELOCATES
    //   `rowOffset` rows of content the user may already be looking at
    //   straight from the live grid into scrollback, out from under a
    //   scrollOffset that never changed. If the user was scrolled up
    //   (scrollOffset > 0) at that exact moment - e.g. tapping the
    //   terminal to open the keyboard, which is a real shrink - lineAt()'s
    //   scrollback/live-grid split (see its own doc) now resolves every
    //   row against a DIFFERENT scrollback.size and a DIFFERENT (smaller)
    //   rows than when that scrollOffset value was chosen, producing a
    //   visibly incoherent screen: a stray line of genuinely live content
    //   at one edge, a wall of blank freshly-grown rows, and old
    //   scrollback history reappearing somewhere else entirely - not a
    //   loss of data (the underlying grid/scrollback content itself is
    //   fine, as verified separately), just an increasingly stale window
    //   into it every time a resize fires while scrolled up. Shifting
    //   scrollOffset by this same rowOffset keeps it pointing at the exact
    //   same content across the resize, the same "compensate the fixed
    //   (row, scrollOffset) pair" principle pendingScrollLines already
    //   applies to selections - just applied to the view's own scroll
    //   position instead.
    private var pendingResizeScrollLines: Int = 0

    /** Returns and clears the number of lines resize() has pushed into
     *  scrollback (rowOffset) since the last call - lets a caller
     *  (MainActivity/SplitTerminalPane, once per content-change tick)
     *  compensate scrollOffset so a user scrolled up into history doesn't
     *  have their view silently desync from the content it was pointing
     *  at when the keyboard opens/closes or a pane resizes. See this
     *  counter's own doc above for why it's kept separate from
     *  pendingScrollLines rather than folded into it. */
    fun consumePendingResizeScrollLines(): Int {
        val n = pendingResizeScrollLines
        pendingResizeScrollLines = 0
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
        // of the grid. The window kept must be anchored to the CURSOR, not
        // blindly to "the last newRows rows of the old grid": a terminal
        // that's only lightly used (cursor sitting a few rows down, most of
        // a tall screen still blank below it) has its real content nowhere
        // near the bottom of the OLD grid - unconditionally keeping the
        // bottom newRows rows discarded the cursor's own row (and every
        // real line above it) into scrollback while keeping a chunk of
        // still-blank trailing rows as the "new" screen, which is exactly
        // what showed up as the terminal's visible content vanishing
        // (permanently - grow never reclaims scrollback, see below) the
        // instant the keyboard opened and shrank the viewport
        // ("bosluk spawn oluyor, dokununca"). The correct amount to push
        // into scrollback is only however many rows overflow PAST the
        // cursor's own row once the viewport shrinks - if the cursor
        // already fits inside newRows, nothing needs to move at all.
        val rowOffset = if (altGrid == null && newRows < rows) {
            (cursorRow + 1 - newRows).coerceAtLeast(0)
        } else 0
        if (rowOffset > 0) {
            // Only worth remembering in scrollback if at least one of the
            // departing rows actually has real content. Android settles
            // layout in (at least) two passes - an early one before
            // insets/keyboard/measured-height are final, then a corrected
            // one a moment later - so a brand-new session (nothing typed
            // yet, grid still all Cell()s) can see a content-free SHRINK
            // fire first (the early, too-small measurement) followed
            // immediately by a GROW to the real size. Pushing those blank
            // rows into scrollback here made the grow branch below
            // faithfully "reclaim" them via growOffset - shifting
            // cursorRow down to make room for content that never
            // existed, i.e. the exact "cursor floats on a random row on
            // first launch" bug this whole resize() rewrite was for,
            // just reached through a shrink-then-grow pair instead of a
            // single grow. Skipping the push when the departing rows are
            // all blank means an empty scrollback stays empty, so the
            // later grow's growOffset/growShortfall genuinely stays 0 -
            // no reclaim, no phantom cursor shift - regardless of how
            // that first layout pass happened to measure.
            //
            // NOTE: an earlier version of this comment/fix tried to also
            // catch "rapid pinch-zoom oscillation" (real content shrinking
            // and growing back within ~150-200ms) by suppressing the
            // scrollback push whenever this shrink landed within a fixed
            // time window of the last settle. That was wrong: it can't
            // tell a brief shrink-then-grow blip apart from a genuinely
            // continuous, deliberate shrink (e.g. dragging a floating
            // pane's corner handle steadily smaller, which - see
            // MultiPaneContainer's onSizeChanged - commits a new real
            // shrink roughly every 32ms the whole time the finger moves).
            // Both look identical from "how long since the last settle"
            // alone, so the time-window version silently discarded real,
            // on-screen content during any ordinary manual floating-pane
            // shrink instead of archiving it ("floating resize olunca
            // veriler yutuluyor"). Fixed for real below.
            val blankCell = Cell()
            val hasRealContent = (0 until rowOffset).any { i -> grid[i].any { it != blankCell } }
            if (hasRealContent) {
                for (i in 0 until rowOffset) {
                    scrollback.addLast(grid[i])
                }
                while (scrollback.size > MAX_SCROLLBACK_LINES) {
                    scrollback.removeFirst()
                }
            }
        }
        // On a grow (pinch-zoom out, keyboard closing, pane resize), newRows
        // > rows means we need MORE rows than the old grid had. This used to
        // try to "reclaim" up to growCount lines back out of `scrollback` -
        // on the theory that a shrink moments earlier had just pushed
        // exactly those rows there, so pulling them back out would restore
        // them instead of leaving the new rows blank.
        //
        // That reclaim was unsound and is the actual root cause of the
        // "beyaz cursor uçuyor, siyah boşluklar beliriyor" bug reported
        // when simply tapping the terminal (which focuses the hidden field
        // and opens the soft keyboard - a real shrink - and closing it
        // later is a real grow): `scrollback` is ONE shared deque that
        // scrollUp() ALSO pushes onto for completely ordinary reasons -
        // every newline the shell prints while the keyboard happens to be
        // open calls scrollUp(), which addLast()s onto the exact same tail
        // this reclaim popped from via removeLast(). There is no tag,
        // timestamp, or separate queue distinguishing "rows THIS resize's
        // shrink just pushed a moment ago" from "unrelated newer shell
        // output that scrolled by in between" - by the time a grow (the
        // keyboard closing) ran, the tail of scrollback was whatever the
        // shell had most recently printed, not the original pre-shrink
        // screen content. Reclaiming that as "the top of the screen" and
        // shifting cursorRow down by however many lines were pulled back
        // (see the old totalTopPadding-based cursorRow adjustment this
        // replaced) inserted rows that had nothing to do with where the
        // cursor actually was, and shoved the cursor down over them - a
        // real, visible jump/glitch on every single keyboard open/close,
        // i.e. on every tap. It only ever looked correct in the narrow
        // case the fix was originally written for (a rapid, isolated
        // shrink-then-grow with no PTY output at all in between, like two
        // back-to-back layout-settling passes on first launch) - anything
        // that actually printed output during the shrunk period (typing,
        // command output - the completely normal case) reclaimed garbage.
        //
        // Growing rows are simply left blank at the top instead - exactly
        // what the original (pre-scrollback-reclaim) version of this
        // function did, and it never exhibited this bug. `resized()` below
        // maps old content starting right at row 0 of the new grid (no top
        // padding), so existing content and the cursor both stay exactly
        // where they were, and the newly-available rows appear blank at
        // the bottom - which is also how a real terminal growing past any
        // size it has ever been behaves. The one-time shrink→scrollback
        // push above is left in place (it's harmless and gives a floating
        // pane shrink real scrollback history to scroll back INTO with a
        // drag, exactly as intended) - only the unsafe pull-back-out on
        // grow is removed.
        val totalTopPadding = 0
        // Reclaimed lines were stored at the OLD column width, which may
        // differ from newColumns (the same pinch/drag tick can change both
        // rows and columns at once - a floating pane's corner-handle drag
        // always does, since dragging diagonally changes both dimensions
        // every single frame). Re-map them through the same column-width
        // adjustment the rest of the grid gets below, instead of copying
        // the raw array directly - otherwise a width change on the same
        // tick would either truncate silently (array too long for the new
        // row) or crash with an index-out-of-bounds (array too short).
        //
        // Critically, "truncate" here means allocate a row at least
        // maxOf(newColumns, line.size) wide and copy every existing cell
        // into it - NEVER drop a cell just because it's past newColumns.
        // An earlier version allocated exactly Array(newColumns), which
        // silently discarded any column beyond the new (narrower) width -
        // fine for a ONE-TIME width change, but a floating pane's corner
        // drag fires this on nearly every frame while the finger moves,
        // and a diagonal drag shrinks columns on most of those frames.
        // Real cells the user had already typed - to the right of
        // wherever the shrinking edge landed that frame - were erased for
        // good right there, with no way for a later grow (widening the
        // pane back out) to recover them, since nothing preserved them
        // anywhere. That is exactly the reported "floating resize olunca
        // veriler yutuluyor": not a display artifact, real content
        // permanently gone after a shrink-then-grow drag. Keeping each
        // row's backing array at its widest-ever size and letting
        // `columns` alone govern what's currently VISIBLE (every read in
        // this class already bounds-checks against `columns`, never
        // against the row array's own .size - see cellAt/setCell/clearRow
        // above) means a column shrink only hides the trailing cells, and
        // a subsequent grow reveals the real characters that were there
        // the whole time instead of blank padding.
        fun resized(g: Array<Array<Cell>>): Array<Array<Cell>> = Array(newRows) { r ->
            // totalTopPadding is always 0 now (see its own doc above) - the
            // `r < totalTopPadding` reclaimed-scrollback branch this used
            // to have is gone entirely, so every new row maps straight
            // through to the old grid via rowOffset.
            val srcRow = r + rowOffset
            if (srcRow < rows) {
                // Widen (or leave alone) rather than allocate exactly
                // newColumns-wide and drop anything past it. `columns`
                // (not the row array's .size) is what every other
                // method in this class already treats as the visible
                // width, so carrying the old row's full backing array
                // forward - padded up to newColumns only if it was
                // narrower - loses nothing: a later column-widen finds
                // the real characters still sitting there rather than
                // blank Cells.
                val src = g[srcRow]
                val width = maxOf(newColumns, src.size)
                Array(width) { c -> if (c < src.size) src[c] else Cell() }
            } else {
                Array(newColumns) { Cell() }
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
        // enterAlternateScreen/exitAlternateScreen). It has its own
        // rowOffset-only resize helper (no top-padding/reclaim concept -
        // that mechanism is gone from resized() above too, see
        // totalTopPadding's own doc) rather than reusing the closure
        // above, so it never accidentally double-dips into rows already
        // handed to the primary grid. Same widen-don't-truncate principle
        // as resized() above: a column shrink while an alt-screen app
        // (vim/htop) is running must not silently erase the primary
        // screen's real content that's sitting dormant in savedGrid - the
        // user only sees it again once they quit back out, by which point
        // a naive truncate would already have thrown it away with no way
        // to notice or recover it.
        fun resizedPlain(g: Array<Array<Cell>>): Array<Array<Cell>> = Array(newRows) { r ->
            val srcRow = r + rowOffset
            if (srcRow < rows) {
                val src = g[srcRow]
                val width = maxOf(newColumns, src.size)
                Array(width) { c -> if (c < src.size) src[c] else Cell() }
            } else {
                Array(newColumns) { Cell() }
            }
        }
        savedGrid = savedGrid?.let { resizedPlain(it) }
        columns = newColumns
        rows = newRows
        // Content moved up by rowOffset rows above (bottom of the old grid
        // is now the new grid), so cursorRow/savedCursorRow have to move
        // with it - otherwise the cursor (and a later DECRC restoring
        // savedCursorRow) would land on the wrong, now-shifted row.
        // totalTopPadding is always 0 now (see its own doc above - grow no
        // longer reclaims anything from scrollback to insert above the old
        // content), so this is just `cursorRow - rowOffset` in practice;
        // kept as `+ totalTopPadding` so a future legitimate use of top
        // padding (if one is ever added back deliberately) doesn't have to
        // rediscover this adjustment.
        cursorRow = (cursorRow + totalTopPadding - rowOffset).coerceIn(0, rows - 1)
        cursorCol = cursorCol.coerceIn(0, columns - 1)
        if (rowOffset > 0) {
            savedCursorRow = (savedCursorRow - rowOffset).coerceIn(0, rows - 1)
        }
        // Same "content moved under a fixed (row, scrollOffset) pair"
        // situation consumePendingScrollLines()'s doc describes for
        // scrollUp() - a resize shifts every row's content up by
        // rowOffset (shrink) without touching scrollOffset, so an active
        // selection anchored to old row indices silently points at
        // different content afterward: the handles/highlight visually
        // "stick" to whatever now occupies those same row numbers instead
        // of the text the user actually selected - which is exactly what
        // made a selection appear over unselected text after zooming. Net
        // delta matches scrollUp()'s sign convention (positive = pushed
        // into scrollback / content moved up); totalTopPadding is always
        // 0 now (see its own doc above), so this reduces to `rowOffset`.
        // TerminalView's LaunchedEffect(bufferVersion) picks this up the
        // same tick it consumes any scrollUp()-driven lines.
        if (rowOffset != totalTopPadding) {
            pendingScrollLines += (rowOffset - totalTopPadding)
        }
        // See pendingResizeScrollLines' own doc for why this needs its own
        // separate counter rather than reusing pendingScrollLines above -
        // this one exists specifically so a caller can compensate
        // scrollOffset itself (not just an active selection) when a
        // resize relocates content the user might already be scrolled up
        // into. Only rowOffset (rows actually pushed into scrollback)
        // counts - totalTopPadding is always 0 now (see its own doc
        // above), same net value as pendingScrollLines' delta, kept as an
        // explicit separate add here (rather than aliasing the two
        // counters) so a future reintroduction of top-padding-style logic
        // doesn't have to remember two counters need the same fix.
        if (rowOffset > 0) {
            pendingResizeScrollLines += rowOffset
        }
        if (newColumns != oldColumns) {
            pendingColumnsChanged = true
        }
    }

    fun rowText(row: Int): String = lock.withLock {
        if (row !in 0 until rows) return@withLock ""
        // Bounded to `columns`, NOT grid[row].size: a row's backing array
        // can now be wider than the currently-visible column count (see
        // resize()'s resized - a column shrink widens-not-truncates so a
        // later grow can recover the real characters instead of blank
        // padding). Joining the raw array here would leak those
        // intentionally-hidden off-screen cells into the returned text,
        // which every other reader in this class already avoids by going
        // through `columns` rather than the array's own .size.
        (0 until columns).joinToString(separator = "") { c -> grid[row][c].text }
    }
}
