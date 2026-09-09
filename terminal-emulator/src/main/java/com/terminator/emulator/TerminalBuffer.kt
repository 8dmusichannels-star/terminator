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

        // OSC 4/10/11/12 override "slot" numbers, used as the keys into
        // [dynamicColorOverrides] below - kept as named constants rather
        // than raw 10/11/12 magic numbers at every call site since they
        // deliberately alias the real OSC codes that set them (10=fg,
        // 11=bg, 12=cursor), matching how xterm's own control sequence
        // table names them.
        const val DYNAMIC_COLOR_FOREGROUND = 10
        const val DYNAMIC_COLOR_BACKGROUND = 11
        const val DYNAMIC_COLOR_CURSOR = 12

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
        var strikethrough: Boolean = false,
        // SGR 8 (conceal) / 28 (reveal). The glyph is still stored here
        // exactly as written (selection/copy still yields the real text -
        // concealing is a purely visual effect), only rendering hides it -
        // see TerminalView's drawTerminal, which matches the paint color to
        // the resolved background for a concealed cell instead of skipping
        // its drawText call outright.
        var conceal: Boolean = false,
        // SGR 53 (overline) / 55 (off) - a decoration line drawn above the
        // glyph, the mirror image of `underline` below it. Android's Paint
        // has no built-in "overline" flag the way it has isUnderlineText/
        // isStrikeThruText, so this is drawn manually in TerminalView.
        var overline: Boolean = false,
        // SGR 4:3 ("undercurl") - refines `underline` (still true whenever
        // this is) into a wavy/squiggly line instead of a straight one, the
        // same visual convention spell-checkers and kitty/VS Code/iTerm2's
        // diagnostic underlines use. Meaningless (ignored) when `underline`
        // itself is false.
        var underlineCurly: Boolean = false,
        // SGR 58 (set underline color) / 59 (reset). Null means "use this
        // cell's own resolved foreground for the underline/undercurl line",
        // the behavior every terminal had before this extension existed -
        // packed the same way `fg`/`bg` are (plain 0-255 index, or a
        // TerminalPalette.TRUECOLOR_MARKER-tagged RGB) so it resolves
        // through the exact same palette.resolve() call those do.
        var underlineColor: Int? = null,
        // OSC 8 hyperlink URI active when this cell was written, if any
        // (e.g. `ls --hyperlink`, `git log`'s clickable commit links, eza/
        // fd). Null for plain text. Stored per-cell rather than as a
        // separate row-range table since cells already get copied whole on
        // scroll/resize/scrollback push - piggybacking on that means a
        // link's clickable area automatically follows its text through all
        // of that instead of needing its own bookkeeping to stay in sync.
        var hyperlink: String? = null,
        // Kitty Unicode-placeholder tile reference (see
        // TerminalEmulator.writeChar's placeholder-detection branch and
        // TerminalBuffer.isKittyVirtualPlacement) - non-null exactly
        // when `text` is the U+10EEEE placeholder character written as
        // part of a Kitty virtual-placement image. Kept as a single
        // small data class rather than four separate nullable Int
        // fields on Cell itself so a plain non-placeholder cell (the
        // overwhelming majority) pays for one null reference, not four
        // separate always-present Int fields defaulting to 0 that would
        // be indistinguishable from a legitimately-zero row/col/id.
        // Follows the cell through scroll/resize/scrollback exactly like
        // `text`/`hyperlink` do - which is the entire point of the
        // Unicode-placeholder mechanism (see kittyVirtualPlacements' own
        // doc): the image tile moves with ordinary text reflow with zero
        // extra bookkeeping here.
        var kittyPlaceholder: KittyPlaceholderRef? = null,
        // True when this cell's `text` is a double-width grapheme (an East
        // Asian Wide/Fullwidth codepoint, or an emoji sequence - see
        // TerminalEmulator.graphemeDisplayWidth's own doc for the exact
        // classification) that visually spans TWO columns instead of one.
        // The renderer draws such a cell's glyph stretched across its own
        // column plus the one immediately to its right (see TerminalView's
        // drawTerminal, the `cell.wide` branch), and writeChar reserves
        // that second column as a WIDE_CONTINUATION cell (see that
        // constant's own doc) rather than leaving it as an ordinary blank -
        // without this flag, TerminalBuffer had no way to tell a renderer
        // "this glyph is wider than its cell", so wide emoji/CJK glyphs
        // rendered at a fixed single-column width bled over the next
        // cell's own independently-drawn background/glyph instead of
        // cleanly occupying two reserved columns (the "emoji spawns
        // artifacts next to it" bug).
        var wide: Boolean = false,
        // True for the (blank, otherwise-ordinary) cell immediately to the
        // right of a `wide` cell - reserved by writeChar so cursor
        // movement, selection, and resize/scrollback reflow all treat a
        // wide glyph as occupying two real columns instead of one. Never
        // set together with `wide` on the same Cell (a cell is either the
        // wide glyph itself, its continuation, or neither - never both).
        // The renderer skips drawing this cell's own (empty) text entirely
        // - see TerminalView's drawTerminal `cell.isWideContinuation`
        // branch - since its background is already covered by the wide
        // glyph's own stretched-across-two-columns draw call.
        var isWideContinuation: Boolean = false

    )

    /** One placeholder cell's resolved (image id, placement id, tile
     *  row, tile column) - see [Cell.kittyPlaceholder]'s own doc for why
     *  this is a nested class rather than inline fields. [tileRow]/
     *  [tileCol] are the ROW/COLUMN INDEX WITHIN THE IMAGE this
     *  particular cell should display (decoded from the diacritics that
     *  followed the U+10EEEE character - see
     *  TerminalEmulator.decodeKittyPlaceholderDiacritics), not a grid
     *  position - the grid position is simply wherever this Cell itself
     *  lives, same as any other character. */
    data class KittyPlaceholderRef(val imageId: Int, val placementId: Int, val tileRow: Int, val tileCol: Int)

    // Current cursor position, kept in sync by TerminalEmulator on every
    // move/write so the renderer knows where to draw the "waiting for
    // input" caret.
    var cursorRow: Int = 0
    var cursorCol: Int = 0
    // Whether the cursor should be drawn at all (CSI ?25h/l) - full-screen
    // TUI apps commonly hide it during a redraw pass.
    var cursorVisible: Boolean = true
    // Cursor on-screen shape (DECSCUSR) - mirrors cursorVisible: a plain
    // field kept in sync by TerminalEmulator on every SGR-shape change,
    // read back out through cursorSnapshot() under the same lock as
    // row/col/visible so the renderer never sees a torn combination.
    var cursorStyle: TerminalEmulator.CursorStyle = TerminalEmulator.CursorStyle.BLOCK

    // DECSCNM (Reverse Video, "CSI ?5 h/l") - whole-screen fg/bg swap,
    // independent of any per-cell SGR 7 (Cell.inverse). Lives here rather
    // than as a plain field on TerminalEmulator because TerminalView (the
    // only thing that needs to read it, once per draw pass) is only ever
    // handed a TerminalBuffer, never the TerminalEmulator instance that
    // owns the escape-sequence parsing - same reasoning as cursorVisible/
    // cursorStyle just above, which exist here for the identical reason.
    // A plain var (not lock-guarded like cursorSnapshot()'s fields) is
    // deliberate: TerminalView's own draw loop already reads
    // buffer.rows/buffer.columns as raw unsynchronized field accesses on
    // every frame, so gating this one additional boolean behind the lock
    // would be inconsistent with - and no safer than - what that loop
    // already does for the two fields it can't function without at all.
    // A stray torn read here is also far lower-stakes than the cursor-
    // position tearing cursorSnapshot() exists to prevent: worst case one
    // frame briefly paints some cells in the pre-toggle mode before the
    // very next frame (a handful of milliseconds later) catches up -
    // never a crash or an out-of-bounds draw the way a torn row/col could
    // cause.
    var reverseVideoMode: Boolean = false

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
        val visible: Boolean,
        val style: TerminalEmulator.CursorStyle
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
        CursorSnapshot(cursorRow, cursorCol, rows, columns, cursorVisible, cursorStyle)
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
        // Images belong to the grid they were drawn on (see images' own
        // doc) - the primary screen's images have nothing to do with
        // whatever a full-screen TUI app about to take over the alternate
        // screen will draw, so they're cleared here rather than left to
        // render underneath/behind the alt screen's own content.
        images.clear()
        clearAllKittyImages()
    }

    fun exitAlternateScreen() = lock.withLock {
        val original = savedGrid ?: return@withLock
        grid = original
        cursorRow = savedCursorRow
        cursorCol = savedCursorCol
        altGrid = null
        savedGrid = null
        // Same reasoning as enterAlternateScreen's own clear: whatever the
        // alt-screen app drew (Sixel or otherwise) doesn't belong on the
        // primary screen being restored here.
        images.clear()
        clearAllKittyImages()
    }

    // Scrollback, capped at MAX_SCROLLBACK_LINES - lines pushed off the top
    // of the visible grid. Persisted incrementally and *without* this cap
    // to disk by TerminalSession (.history file), so nothing is actually
    // lost - only how much of it this class keeps as live objects.
    val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()

    /** A decoded Sixel image: [width]x[height] pixels, row-major, one
     *  packed ARGB Int per pixel (see TerminalEmulator.argb) - deliberately
     *  a plain data holder with no android.graphics/Compose type anywhere
     *  in terminal-emulator (see this module's existing OSC 52 doc for why
     *  that boundary matters); TerminalView converts this to an
     *  ImageBitmap only at draw time. */
    data class SixelImage(val width: Int, val height: Int, val pixels: IntArray)

    /** One placed image, anchored to the GRID row it was drawn at when
     *  received (never a scrollback row directly - see [placeImage]'s own
     *  doc for why anchoring only ever happens against live grid rows).
     *  [col] is the starting column, in cell units, that the image's left
     *  edge was placed at - needed since Sixel images don't necessarily
     *  start at column 0 (e.g. an image printed after some prompt text on
     *  the same line). Purely additive rendering data: nothing here
     *  affects text layout, selection, or copy - a placed image is
     *  invisible to everything in this class except [images] and the
     *  scroll/resize bookkeeping that keeps it (approximately) attached to
     *  the right on-screen rows as content moves. */
    data class PlacedImage(var row: Int, val col: Int, val image: SixelImage)

    // Every image currently placed in the LIVE grid (never scrollback -
    // once an image's row scrolls off the top, it's simply dropped rather
    // than migrated into a parallel scrollback-image list, same
    // simplification real terminals like xterm/mlterm make: Sixel output
    // that's scrolled out of view is gone, not preserved for later
    // scrollback replay). Kept as a plain mutable list rather than
    // per-Cell storage since an image spans many cells/rows at once and
    // Cell is otherwise a small, cheaply-copied value type - attaching a
    // multi-hundred-KB IntArray reference to every cell under it would
    // multiply-store the same pixels instead of once.
    private val images = ArrayList<PlacedImage>()

    /** Returns a snapshot of every image currently anchored to a row still
     *  within the live grid (i.e. not yet scrolled off) - for TerminalView
     *  to paint after the text grid each frame. Row numbers are relative
     *  to THIS instant's grid layout, matching how cellAt/lineAt address
     *  rows elsewhere in this class. */
    fun placedImages(): List<PlacedImage> = lock.withLock { images.toList() }

    /** Anchors a freshly-decoded Sixel image at (row, col) in the LIVE
     *  grid. Only ever called with the cursor's OWN current row/col (see
     *  TerminalEmulator.decodeAndPlaceSixel) - i.e. always a live-grid
     *  position, never scrollback - which is why [PlacedImage.row] only
     *  ever needs to track live-grid movement (scrollUp/scrollRegionUp/
     *  insertLines/deleteLines below) and not scrollback migration: the
     *  image starts, and is dropped, entirely within the grid's own
     *  lifetime. A real terminal's cursor is always somewhere in the live
     *  grid when a program prints anything (including a Sixel image), so
     *  this covers every case Sixel output can actually arrive in. */
    fun placeImage(image: SixelImage, row: Int, col: Int) = lock.withLock {
        if (row !in 0 until rows) return@withLock
        images.add(PlacedImage(row, col, image))
    }

    // ---------------------------------------------------------------
    // Kitty graphics protocol
    //
    // Deliberately a SEPARATE model from SixelImage/PlacedImage above
    // rather than shoehorned into it, because Kitty's data model is
    // genuinely different in ways that matter for storage:
    //  - Sixel is "decode body, slam pixels at the cursor, forget it" -
    //    there's no id, no way to reference the same pixels twice, no
    //    way to delete it short of overwriting the cells it covers.
    //  - Kitty images are named (client id, and/or server-assigned id),
    //    persist independently of any one placement, can be PLACED
    //    more than once (same decoded pixels shown at several grid
    //    positions - "placement id" is a second, per-placement key),
    //    carry an explicit z-index for stacking order against other
    //    placements AND against text, and are explicitly deletable by
    //    id/placement id/row/column/z-index (a=d) rather than only
    //    ever falling off the grid via scroll/clear like Sixel's are.
    // Keeping two parallel id->object maps (images by image id,
    // placements by (image id, placement id)) models this directly
    // instead of forcing an awkward translation into PlacedImage's
    // single-use-per-draw shape.
    // ---------------------------------------------------------------

    /** A decoded Kitty image's pixels - same plain ARGB IntArray shape as
     *  [SixelImage] and for the same reason (no android.graphics/Compose
     *  type in this module - TerminalView converts to a Bitmap at draw
     *  time only). [id] is the image id the client/server negotiated for
     *  this image (see TerminalEmulator's Kitty command parsing for how
     *  client-only ids get one assigned) - kept here too, not just as a
     *  map key, so a KittyImage can be identified after being looked up
     *  via a KittyPlacement without a second map lookup. */
    data class KittyImage(val id: Int, val width: Int, val height: Int, val pixels: IntArray)

    // ---------------------------------------------------------------
    // Kitty graphics protocol - animation (a=f transmit frame / a=a
    // animation control)
    //
    // A root KittyImage (above) is always frame 1 (implicit, created by
    // the original a=t/a=T that established the image id). Additional
    // frames are transmitted with a=f against that same image id, each
    // becoming a further KittyFrame in kittyFrames[imageId] - kept as a
    // SEPARATE parallel list rather than folded into KittyImage itself
    // so KittyImage can stay the simple, immutable, identity-cacheable
    // shape TerminalView's bitmap cache already relies on (see
    // TerminalView's kittyBitmapCache doc) - only the CURRENT frame's
    // pixels need to be identity-stable moment to moment, not the
    // static root image.
    //
    // gapMs=0 (a common default when a client never bothers setting
    // z= gap timing) is treated as "hold indefinitely" per spec (a
    // frame with no gap is a static reference frame, not part of the
    // animation loop) - see advanceKittyAnimations' own doc.
    data class KittyFrame(
        val frameNumber: Int,
        val width: Int,
        val height: Int,
        val pixels: IntArray,
        val gapMs: Int,
        // Composition mode against the frame it was declared to compose
        // over (Kitty's c= key on a=f: 0/omitted = full replace, other
        // values are alpha-blend variants) - only replace is actually
        // implemented (see composeKittyFrame's own doc for why blending
        // beyond that is out of scope), but the raw value is kept here
        // in case a future revision wants it.
        val composeMode: Int
    )

    // frame 1 is always the root KittyImage itself and is NOT duplicated
    // into this list - kittyFrames[imageId] holds only frames 2+ (a=f's
    // own frame numbering starts at 2, since 1 is implicitly the image
    // transmitted by a=t/a=T). Cleared alongside kittyImages on delete/
    // reset (see clearAllKittyImages/deleteKittyPlacements).
    private val kittyFrames = HashMap<Int, MutableList<KittyFrame>>()

    // Per-image animation playback state - only present for image ids
    // that have received at least one a=f frame (a plain static image
    // never gets an entry here, so the common non-animated case pays
    // no bookkeeping cost). currentFrame is 1-based, matching the
    // protocol's own frame numbering (1 = root image).
    data class KittyAnimationState(
        var currentFrame: Int = 1,
        var loopCount: Int = 0, // 0 = infinite, per spec's v= on a=a
        var playing: Boolean = true,
        var msSinceLastAdvance: Long = 0L
    )
    private val kittyAnimState = HashMap<Int, KittyAnimationState>()

    /** Appends a new frame (a=f) to image [imageId]'s frame list, or
     *  replaces an existing frame with the same [KittyFrame.frameNumber]
     *  (a client re-sending frame N with new pixels - same "replace in
     *  place" tolerance as [storeKittyImage] itself allows for the root
     *  image). Silently does nothing if [imageId] was never transmitted
     *  at all (no root KittyImage to animate) - matches this class's
     *  general "ignore what doesn't have a sensible target" policy for
     *  malformed/out-of-order Kitty commands. */
    fun addKittyFrame(imageId: Int, frame: KittyFrame) = lock.withLock {
        if (!kittyImages.containsKey(imageId)) return@withLock
        val frames = kittyFrames.getOrPut(imageId) { ArrayList() }
        val existingIdx = frames.indexOfFirst { it.frameNumber == frame.frameNumber }
        if (existingIdx >= 0) frames[existingIdx] = frame else frames.add(frame)
        kittyAnimState.getOrPut(imageId) { KittyAnimationState() }
    }

    /** Returns the pixels that should currently be displayed for
     *  [imageId]: the root image's own pixels if it has no animation
     *  state (or is on frame 1), otherwise whichever frame
     *  [KittyAnimationState.currentFrame] points at. Returns null only
     *  if [imageId] itself was never transmitted (nothing to show at
     *  all) - a request for a frame number that doesn't exist falls
     *  back to the root image rather than showing nothing, since a
     *  client referencing a not-yet-transmitted frame is a timing bug
     *  on the SENDING side that shouldn't blank an otherwise-valid
     *  placement. */
    /** Returns the raw pixel [IntArray] stored for a SPECIFIC 1-based
     *  frame number of [imageId] (2+ only - frame 1/the root has no
     *  entry of its own here, see [kittyFrames]' own doc; callers that
     *  need frame 1's pixels too should check the root [KittyImage]
     *  directly, which [TerminalEmulator.kittyFramePixelsByNumber]
     *  already does), or null if that frame was never transmitted.
     *  Distinct from [currentKittyPixels] - this always returns the
     *  EXACT frame asked for regardless of playback state, for
     *  compositing operations (a=f's own c= base-frame, a=c's r=/c=
     *  source/destination) that need a specific frame's data rather
     *  than "whatever's currently showing". */
    fun getKittyFramePixels(imageId: Int, frameNumber: Int): IntArray? = lock.withLock {
        kittyFrames[imageId]?.firstOrNull { it.frameNumber == frameNumber }?.pixels
    }

    /** Returns every frame number (2+) currently stored for [imageId] -
     *  used by [TerminalEmulator.nextFreeKittyFrameNumber] to find the
     *  next unused frame number when a=f omits r= (spec: a new frame is
     *  created when r= isn't given), so repeated no-r= transmissions
     *  append distinct frames instead of colliding on a single
     *  hardcoded number. */
    fun kittyFrameNumbers(imageId: Int): Set<Int> = lock.withLock {
        kittyFrames[imageId]?.map { it.frameNumber }?.toSet() ?: emptySet()
    }

    fun currentKittyPixels(imageId: Int): KittyImage? = lock.withLock {
        val root = kittyImages[imageId] ?: return@withLock null
        val state = kittyAnimState[imageId] ?: return@withLock root
        if (state.currentFrame <= 1) return@withLock root
        val frame = kittyFrames[imageId]?.firstOrNull { it.frameNumber == state.currentFrame }
            ?: return@withLock root
        KittyImage(root.id, frame.width, frame.height, frame.pixels)
    }

    /** Implements Kitty's a=a animation control: sets the current frame
     *  ([gotoFrame], 1-based, per the c= wait-no-actually-it's-s= key
     *  naming the spec uses inconsistently across versions - this
     *  emulator's TerminalEmulator side normalizes whichever key the
     *  client sent into this single [gotoFrame] parameter before
     *  calling here), and/or starts/stops autoplay ([setPlaying]) and/or
     *  sets the loop count ([setLoopCount]). Any null parameter leaves
     *  that piece of state unchanged - mirrors how a=a's own key=value
     *  fields are each independently optional per command. */
    fun controlKittyAnimation(imageId: Int, gotoFrame: Int? = null, setPlaying: Boolean? = null, setLoopCount: Int? = null) = lock.withLock {
        if (!kittyImages.containsKey(imageId)) return@withLock
        val state = kittyAnimState.getOrPut(imageId) { KittyAnimationState() }
        if (gotoFrame != null) {
            val maxFrame = (kittyFrames[imageId]?.maxOfOrNull { it.frameNumber }) ?: 1
            state.currentFrame = gotoFrame.coerceIn(1, maxFrame.coerceAtLeast(1))
            state.msSinceLastAdvance = 0L
        }
        if (setPlaying != null) state.playing = setPlaying
        if (setLoopCount != null) state.loopCount = setLoopCount
    }

    /** Implements a=a's `r=`+`z=` combination (spec: "the gap for
     *  frames can be set... separately using the animation control
     *  escape code"): retargets frame [frameNumber]'s gap to [gapMs]
     *  milliseconds without touching play/stop state or the current
     *  frame at all - a completely separate operation from
     *  [controlKittyAnimation]'s own `c=`/`s=`/`v=` handling, called
     *  alongside it (not instead of it) when a=a supplies both r= and
     *  z= together. [frameNumber]=1 (the root/base frame, which has no
     *  [KittyFrame] entry of its own per [kittyFrames]' own doc) is
     *  handled by synthesizing a zero-duration/zero-composeMode
     *  KittyFrame entry for it if one doesn't already exist purely to
     *  carry the gap value - [currentKittyPixels]/[advanceKittyAnimations]
     *  both already tolerate a frame-1 KittyFrame entry existing
     *  alongside the root KittyImage (they only ever read its gapMs/
     *  pixels are never substituted for the root's own). Silently
     *  no-ops if [imageId] or the target [frameNumber] doesn't exist at
     *  all, same tolerance as every other Kitty animation call here. */
    fun retargetKittyFrameGap(imageId: Int, frameNumber: Int, gapMs: Int) = lock.withLock {
        val root = kittyImages[imageId] ?: return@withLock
        val frames = kittyFrames.getOrPut(imageId) { ArrayList() }
        val idx = frames.indexOfFirst { it.frameNumber == frameNumber }
        if (idx >= 0) {
            frames[idx] = frames[idx].copy(gapMs = gapMs)
        } else if (frameNumber == 1) {
            frames.add(KittyFrame(1, root.width, root.height, root.pixels, gapMs, composeMode = 0))
        }
        // else: retargeting a frame number that was never transmitted -
        // nothing sensible to attach the gap to, ignore per this
        // class's general malformed-command tolerance.
    }

    /** Advances every image's animation state by [deltaMs] of wall-clock
     *  time, looping through frames per each frame's own gapMs, called
     *  once per repaint tick from TerminalView (see its own animation-
     *  driver doc) rather than on a dedicated timer thread, since
     *  repaints already happen on every frame the UI actually draws -
     *  a separate ticking thread would just be advancing state nobody's
     *  about to look at between repaints. A frame with gapMs<=0 is a
     *  "hold here, don't auto-advance" frame per spec (typically the
     *  LAST frame of a non-looping animation, or a single reference
     *  frame with no timing at all) - advancement simply skips images
     *  currently sitting on one. Returns true if any image's displayed
     *  frame actually changed, so the caller knows whether a repaint is
     *  actually warranted purely from animation (as opposed to new
     *  program output). */
    fun advanceKittyAnimations(deltaMs: Long): Boolean = lock.withLock {
        var changed = false
        for ((imageId, state) in kittyAnimState) {
            if (!state.playing) continue
            val frames = kittyFrames[imageId] ?: continue
            if (frames.isEmpty()) continue
            val currentGap = if (state.currentFrame <= 1) {
                // Root/frame-1 normally has no KittyFrame entry of its
                // own (see kittyFrames' own doc) - UNLESS a=a's r=1,z=
                // combination explicitly retargeted it via
                // retargetKittyFrameGap (see that function's own doc on
                // why it synthesizes a frame-1 entry purely to carry a
                // gap), in which case that explicit gap takes priority.
                // Falling back to frame 2's gap when no explicit frame-1
                // entry exists preserves this function's original
                // behavior for every animation that never bothered
                // calling a=a's gap-retarget at all (frame 2's gap is
                // still the best implicit guess for how long the root
                // should show before advancing).
                frames.firstOrNull { it.frameNumber == 1 }?.gapMs
                    ?: frames.firstOrNull { it.frameNumber == 2 }?.gapMs ?: 0
            } else {
                frames.firstOrNull { it.frameNumber == state.currentFrame }?.gapMs ?: 0
            }
            if (currentGap <= 0) continue
            state.msSinceLastAdvance += deltaMs
            if (state.msSinceLastAdvance < currentGap) continue
            state.msSinceLastAdvance = 0L
            val maxFrame = frames.maxOf { it.frameNumber }
            val next = state.currentFrame + 1
            state.currentFrame = if (next > maxFrame) {
                if (state.loopCount == 0) {
                    1 // infinite loop - back to root/frame 1
                } else {
                    state.loopCount--
                    if (state.loopCount <= 0) { state.playing = false; maxFrame } else 1
                }
            } else {
                next
            }
            changed = true
        }
        changed
    }

    /** One placement of a [KittyImage] onto the live grid. Unlike
     *  [PlacedImage] (Sixel, always exactly one placement per image,
     *  never separately addressable) a KittyImage can have several of
     *  these alive at once - re-transmitting is not the only way a
     *  program shows the same pixels twice; a=p with a new placement id
     *  against an already-uploaded image id does it without resending
     *  any data. [placementId] is 0 for the anonymous/default placement
     *  (the common case: most programs never set p=), matching how the
     *  spec treats a missing/zero p= as "the placement", so lookups by
     *  (imageId, placementId) work uniformly whether or not the sender
     *  ever specified one. [row]/[col] follow the exact same live-grid-
     *  only, mutate-in-place-on-scroll contract as [PlacedImage.row] -
     *  see that field's doc; nothing here changes that reasoning. [z] is
     *  the placement's z-index (Kitty's z= key, default 0): negative
     *  values draw BEHIND text, zero or positive draw in front of it -
     *  see TerminalView's paint order for how that split is honored,
     *  since it's the one real semantic difference from Sixel's always-
     *  in-front placements.
     *
     *  [srcX]/[srcY]/[srcW]/[srcH] are the optional source-rectangle
     *  crop (spec's `x,y,w,h` keys on a=p/a=T): the sub-rectangle of
     *  the FULL image's own pixels to display, in image pixel
     *  coordinates. 0/0 (both offsets) with 0/0 (both sizes) means "no
     *  crop, use the whole image" - srcW/srcH of 0 is what the spec's
     *  own "by default, the entire width/height is used" default
     *  means, so 0 is treated as a sentinel for "unspecified" at
     *  render time (see TerminalView's placement-paint doc) rather
     *  than a real zero-size crop, which would just be invisible
     *  anyway.
     *
     *  [cellOffsetX]/[cellOffsetY] are the `X,Y` keys - a pixel offset
     *  WITHIN the anchor cell at which the (possibly cropped) image
     *  starts painting, letting an image be positioned off the cell's
     *  top-left corner without moving the placement's actual (row,
     *  col) anchor.
     *
     *  [displayCols]/[displayRows] are the `c,r` keys - the placement's
     *  requested on-screen size in WHOLE CELLS (not pixels): the
     *  (cropped) image is scaled to exactly fill this many columns/
     *  rows rather than being shown at its natural pixel size divided
     *  by cell size. 0 for either means "use the image's own aspect-
     *  ratio-preserving natural size" (spec: "If only one of either r
     *  or c is specified, the other one is computed based on the
     *  source image aspect ratio" - only the fully-unspecified 0/0
     *  case is handled distinctly here as "natural size", since a
     *  single-axis-only aspect computation needs the actual pixel
     *  dimensions TerminalView has at paint time, not this data
     *  class). */
    data class KittyPlacement(
        val imageId: Int,
        val placementId: Int,
        var row: Int,
        val col: Int,
        val z: Int,
        val image: KittyImage,
        val srcX: Int = 0,
        val srcY: Int = 0,
        val srcW: Int = 0,
        val srcH: Int = 0,
        val cellOffsetX: Int = 0,
        val cellOffsetY: Int = 0,
        val displayCols: Int = 0,
        val displayRows: Int = 0
    )

    // Every Kitty image ever uploaded this session, keyed by image id -
    // kept independently of placements/liveness (see KittyPlacement's own
    // doc: a=p can place an already-uploaded image again with no new
    // pixel data at all, so the pixels must outlive any one placement's
    // presence on the grid). Only ever removed by an explicit a=d delete
    // targeting the image id itself (see deleteKittyImages) or session
    // reset - NOT by scroll/clear, unlike kittyPlacements below, matching
    // the real protocol's "images are a client-managed pool, placements
    // are what's actually visible" split.
    private val kittyImages = HashMap<Int, KittyImage>()

    // Every LIVE placement currently anchored to the grid - the Kitty
    // equivalent of `images` (Sixel) above, but keyed so a specific
    // placement can be looked up/deleted directly by (imageId,
    // placementId) rather than only ever iterated linearly. Subject to
    // the exact same scroll/resize/clear bookkeeping as Sixel's `images`
    // (see shiftImageRows/shiftImageRowsInRange call sites below) since
    // once placed, a placement's on-screen row moves with grid content
    // the same way regardless of which protocol drew it.
    private val kittyPlacements = LinkedHashMap<Long, KittyPlacement>()

    // Packs (imageId, placementId) into one Long key for kittyPlacements -
    // both are non-negative 32-bit values in the protocol (image ids and
    // placement ids are both "1 to 4294967295" per spec, but this
    // implementation stores them as Int/treats them as unsigned-ish small
    // numbers same as everywhere else in this file uses Int for such
    // ids), so packing avoids a Pair allocation per lookup on this
    // decode-time-hot path.
    private fun placementKey(imageId: Int, placementId: Int): Long =
        (imageId.toLong() shl 32) or (placementId.toLong() and 0xFFFFFFFFL)

    /** Stores or replaces a Kitty image's decoded pixels under its own id
     *  (a=t transmit, or the transmit half of a=T). Replacing an existing
     *  id's pixels (a client re-sending under an id it already used) is
     *  intentional passthrough of client behavior, not validated against
     *  here - the spec allows re-use and existing placements referencing
     *  the old pixels simply start showing the new ones, same as a real
     *  Kitty-compatible terminal.
     *
     *  [imageNumber] is the spec's `I=` "image number" - a client-chosen,
     *  NOT-necessarily-unique tag (unlike `i=`/id, which the protocol
     *  requires be unique) that lets a client that doesn't want to
     *  manage real image ids itself refer back to "whichever image I
     *  most recently created with number N" (see
     *  [newestImageIdForNumber]). Optional/null when the client only
     *  ever used `i=`, which is the common case and costs this map
     *  nothing extra to support alongside it. */
    fun storeKittyImage(image: KittyImage, imageNumber: Int? = null) = lock.withLock {
        kittyImages[image.id] = image
        if (imageNumber != null) kittyImageNumbers[imageNumber] = image.id
    }

    /** Looks up a previously-stored Kitty image by id, e.g. so a=p (place,
     *  no new pixel data) can resolve which pixels to attach to a fresh
     *  placement. */
    fun getKittyImage(id: Int): KittyImage? = lock.withLock { kittyImages[id] }

    // Maps a client's own `I=` "image number" (spec: "not unique...even
    // if an existing image has the same number a new one is created")
    // to whichever real image id was MOST RECENTLY stored under that
    // number - overwritten (not appended to) by every storeKittyImage
    // call that supplies a number, which is exactly the "newest wins"
    // resolution the spec requires for every later command that
    // addresses an image by number rather than id.
    private val kittyImageNumbers = HashMap<Int, Int>()

    /** Resolves a client-supplied `I=` image number to the real image id
     *  of the NEWEST image ever stored under that number (spec: "act on
     *  only the newest image with that number"), or null if no image
     *  was ever transmitted with that number at all. */
    fun newestImageIdForNumber(imageNumber: Int): Int? = lock.withLock { kittyImageNumbers[imageNumber] }

    /** Anchors a Kitty image (already stored via [storeKittyImage], or the
     *  transmit-and-place a=T shortcut which calls both together) at
     *  (row, col) in the LIVE grid as placement [placementId]. Replaces
     *  any existing placement under the same (imageId, placementId) pair
     *  in place - re-placing with the same ids is how a program moves/
     *  redraws a placement without a separate explicit delete-then-place
     *  round trip. The crop/offset/scale parameters are passed straight
     *  through to the [KittyPlacement] fields of the same name - see
     *  their own doc for what each means at render time; all default to
     *  0 ("unspecified", matching the protocol's own key defaults) so
     *  existing call sites that only care about position keep working
     *  unchanged. */
    fun placeKittyImage(
        imageId: Int,
        placementId: Int,
        row: Int,
        col: Int,
        z: Int,
        image: KittyImage,
        srcX: Int = 0,
        srcY: Int = 0,
        srcW: Int = 0,
        srcH: Int = 0,
        cellOffsetX: Int = 0,
        cellOffsetY: Int = 0,
        displayCols: Int = 0,
        displayRows: Int = 0
    ) = lock.withLock {
        if (row !in 0 until rows) return@withLock
        kittyPlacements[placementKey(imageId, placementId)] = KittyPlacement(
            imageId, placementId, row, col, z, image,
            srcX, srcY, srcW, srcH, cellOffsetX, cellOffsetY, displayCols, displayRows
        )
    }

    /** Returns a snapshot of every Kitty placement currently anchored to a
     *  row still within the live grid, for TerminalView to paint - same
     *  contract as [placedImages] (row numbers relative to THIS instant's
     *  grid layout). Not sorted by z-index here: TerminalView sorts at
     *  paint time since it also needs to interleave these against the
     *  text glyph layer by z-index sign (see [KittyPlacement.z]'s own
     *  doc), which this module has no glyph-layer concept of. */
    fun kittyPlacements(): List<KittyPlacement> = lock.withLock { kittyPlacements.values.toList() }

    // ---------------------------------------------------------------
    // Kitty graphics protocol - virtual placements (Unicode placeholder
    // mode, U=1 on a=p/a=T)
    //
    // A "virtual placement" is deliberately NOT stored in kittyPlacements
    // above - it has no (row, col) of its own at all. Per spec it exists
    // purely as a prototype: "this image id/placement id COULD be shown
    // via Unicode placeholder characters, if any ever appear in the
    // grid text". The actual on-screen location is wherever the CLIENT
    // separately writes U+10EEEE placeholder characters as ordinary
    // text (see TerminalEmulator.writeChar's own placeholder-detection
    // branch) - this module never anchors a virtual placement to a
    // fixed cell, unlike every real KittyPlacement above.
    // Keyed the same (imageId, placementId) way as kittyPlacements so a
    // later real a=p/a=d against the same ids resolves consistently.
    private val kittyVirtualPlacements = HashMap<Long, Pair<Int, Int>>()

    /** Marks (imageId, placementId) as a virtual placement (U=1) -
     *  called instead of [placeKittyImage] when the client asked for
     *  Unicode-placeholder mode rather than a fixed-position placement.
     *  [cols]/[rows] are the c=/r= tile-grid dimensions the client
     *  declared for how the image should be sliced across however many
     *  placeholder cells eventually reference it (see
     *  [KittyPlaceholderRef]'s own doc) - defaulted to 1x1 (the whole
     *  image in a single cell) if the client never specified them,
     *  matching how a single-cell image is the common case for small
     *  icons/sixel-replacement use. Requires [imageId] to already be a
     *  stored image; silently no-ops otherwise, same tolerance as
     *  [addKittyFrame]. */
    fun markKittyVirtualPlacement(imageId: Int, placementId: Int, cols: Int = 1, rows: Int = 1) = lock.withLock {
        if (!kittyImages.containsKey(imageId)) return@withLock
        kittyVirtualPlacements[placementKey(imageId, placementId)] = (cols.coerceAtLeast(1) to rows.coerceAtLeast(1))
    }

    /** True if (imageId, placementId) was registered via
     *  [markKittyVirtualPlacement] - consulted by TerminalView when it
     *  encounters a placeholder-tagged [Cell] to confirm the id pair the
     *  cell's diacritics decoded to actually refers to a real virtual
     *  placement, rather than rendering stale/made-up ids as if they
     *  were valid (a placeholder character can easily outlive the
     *  placement that registered it, e.g. after an a=d - see this
     *  function's call site in TerminalView for how that's handled). */
    fun isKittyVirtualPlacement(imageId: Int, placementId: Int): Boolean =
        lock.withLock { kittyVirtualPlacements.containsKey(placementKey(imageId, placementId)) }

    /** Returns the (cols, rows) tile-grid size registered for a virtual
     *  placement via [markKittyVirtualPlacement], or null if
     *  (imageId, placementId) isn't a registered virtual placement at
     *  all - see [isKittyVirtualPlacement] for the same check without
     *  needing the dimensions themselves. */
    fun kittyVirtualPlacementGrid(imageId: Int, placementId: Int): Pair<Int, Int>? =
        lock.withLock { kittyVirtualPlacements[placementKey(imageId, placementId)] }

    /** Implements Kitty's a=d delete action. All of the selector
     *  combinations the spec defines as meaningful for a receiving
     *  terminal are supported: by placement (imageId[+placementId]), by
     *  row, by column, by z-index, or all placements - each mapped from
     *  the single-letter `d=` value TerminalEmulator's Kitty command
     *  parser already decoded into these nullable filters (null = "this
     *  filter doesn't apply", matching d='a'/'A' deleting everything).
     *  [alsoFreeData] mirrors the spec's lowercase-vs-uppercase d= distinction
     *  (e.g. d='i' vs d='I'): lowercase only removes the on-screen
     *  placement(s), uppercase ALSO frees the underlying image data
     *  (kittyImages entry) once no placement anywhere still references
     *  it - checked per-image after the placement removal below rather
     *  than blindly deleting kittyImages[imageId], since another still-
     *  live placement (different placementId, same image) legitimately
     *  keeps sharing those pixels. */
    fun deleteKittyPlacements(
        imageId: Int? = null,
        placementId: Int? = null,
        row: Int? = null,
        col: Int? = null,
        z: Int? = null,
        // r/R selector (spec: "id greater than or equal to x and less
        // than or equal to y") - an inclusive [idRangeMin, idRangeMax]
        // filter on the placement's OWN imageId, independent of (and
        // combinable with, though the spec never actually combines it
        // with anything else) the other filters above.
        idRangeMin: Int? = null,
        idRangeMax: Int? = null,
        // c/C selector (spec: "placements that intersect with the
        // current cursor position") - when both given, only placements
        // whose on-screen footprint covers this exact (row, col) match.
        // Deliberately a SEPARATE pair from row/col above rather than
        // reusing them: row/col alone (x/y or p/P selectors) mean
        // "anywhere in this row" / "anywhere in this column" / "this
        // exact cell", while cursorRow/cursorCol here means "the
        // footprint check", which also has to account for a
        // multi-cell-wide/tall placement's displayCols/displayRows
        // rather than just placed.row/placed.col equality.
        cursorRow: Int? = null,
        cursorCol: Int? = null,
        alsoFreeData: Boolean = false
    ) = lock.withLock {
        val it = kittyPlacements.entries.iterator()
        val touchedImageIds = HashSet<Int>()
        while (it.hasNext()) {
            val placed = it.next().value
            if (imageId != null && placed.imageId != imageId) continue
            if (placementId != null && placed.placementId != placementId) continue
            if (row != null && placed.row != row) continue
            if (col != null && placed.col != col) continue
            if (z != null && placed.z != z) continue
            if (idRangeMin != null && placed.imageId < idRangeMin) continue
            if (idRangeMax != null && placed.imageId > idRangeMax) continue
            if (cursorRow != null && cursorCol != null) {
                val spanCols = placed.displayCols.coerceAtLeast(1)
                val spanRows = placed.displayRows.coerceAtLeast(1)
                val within = cursorRow in placed.row until (placed.row + spanRows) &&
                    cursorCol in placed.col until (placed.col + spanCols)
                if (!within) continue
            }
            touchedImageIds.add(placed.imageId)
            it.remove()
        }
        if (alsoFreeData) {
            for (id in touchedImageIds) {
                val stillReferenced = kittyPlacements.values.any { it.imageId == id }
                if (!stillReferenced) {
                    kittyImages.remove(id)
                    kittyFrames.remove(id)
                    kittyAnimState.remove(id)
                }
            }
        }
        // Per spec, virtual placements (see kittyVirtualPlacements' own
        // doc) are only ever touched by an i/I/p/P/n/N/r/R-keyed delete,
        // never by a/c/p/q/x/y/z selectors that target real on-screen
        // placements (they have no row/col/z to match against in the
        // first place) - this function only receives calls for the
        // by-image/by-placement-id/by-id-range selector already (see
        // kittyDelete's own 'i'/'r' branches), so removing by the same
        // filters here is always in-scope regardless of which delete
        // letter dispatched here. cursorRow/cursorCol (the c/C
        // selector) is deliberately excluded from this check even
        // though it's passed through above - virtual placements have
        // no on-screen footprint to intersect the cursor against, so a
        // c/C delete should never touch them, matching the spec's own
        // exclusion list.
        if (imageId != null || placementId != null || idRangeMin != null || idRangeMax != null) {
            val it2 = kittyVirtualPlacements.keys.iterator()
            while (it2.hasNext()) {
                val key = it2.next()
                val storedImageId = (key ushr 32).toInt()
                val storedPlacementId = key.toInt()
                val matches = (imageId == null || storedImageId == imageId) &&
                    (placementId == null || storedPlacementId == placementId) &&
                    (idRangeMin == null || storedImageId >= idRangeMin) &&
                    (idRangeMax == null || storedImageId <= idRangeMax)
                if (matches) it2.remove()
            }
        }
    }

    /** Implements the f/F delete selector: removes animation frames
     *  rather than placements. Per spec "Delete animation frames" - no
     *  further per-frame filter is defined beyond which image, so this
     *  clears every non-root frame ([kittyFrames]) and resets playback
     *  ([kittyAnimState]) for [imageId], leaving the root KittyImage
     *  itself (frame 1) and any real/virtual placements untouched -
     *  only the ANIMATION on top of the image goes away, same as how
     *  the other delete selectors leave [kittyImages] alone unless
     *  [alsoFreeData]/uppercase was requested. A null [imageId] (the
     *  bare `d=f` form with no `i=`/`I=` at all) is a no-op: unlike
     *  a/A's "everything visible" default, the spec ties f/F to
     *  whichever image was identified the same way every other
     *  animation command requires (see kittyAnimationControl's own
     *  i=/I= handling), so there's no sensible "all animations
     *  everywhere" behavior to fall back to here. */
    fun deleteKittyFrames(imageId: Int?) = lock.withLock {
        if (imageId == null) return@withLock
        kittyFrames.remove(imageId)
        kittyAnimState.remove(imageId)
    }

    /** Clears every Kitty image and placement - used by [reset] (RIS) and
     *  alternate-screen entry/exit (same "images don't survive a screen-
     *  identity change" simplification as [images]'s own doc for Sixel;
     *  see [enterAlternateScreen]/[exitAlternateScreen]). */
    fun clearAllKittyImages() = lock.withLock {
        kittyPlacements.clear()
        kittyImages.clear()
        kittyFrames.clear()
        kittyAnimState.clear()
        kittyVirtualPlacements.clear()
        kittyImageNumbers.clear()
    }

    // ---------------------------------------------------------------
    // Dynamic color overrides (OSC 4 palette-entry set, OSC 10/11/12
    // foreground/background/cursor set) and their palette-entry
    // counterpart (OSC 4 only - 10/11/12 have no "entry number", they ARE
    // the color).
    //
    // Both live here (buffer-owned, lock-guarded) rather than as plain
    // TerminalEmulator fields for the same reason curFg/curBg/etc. don't:
    // TerminalEmulator.kt has a hard "no android.*/Compose imports" rule
    // (see this class's own header doc and Kitty's decodePng/decodeRawRgb
    // for the same constraint already documented there), and the actual
    // palette a program's OSC 10/11/12 QUERY needs to answer against
    // lives entirely on the Compose side (TerminalView.kt's own
    // TerminalPalette, built from Settings > Theme) - TerminalEmulator
    // itself has no idea what color index 15 currently resolves to, only
    // that it's "index 15". Storing the override here instead of trying
    // to thread a TerminalPalette reference down into TerminalEmulator
    // keeps that boundary intact: the emulator just records "the program
    // asked for slot N to become this ARGB", and it's up to
    // TerminalPalette.resolve() (see its own doc) to consult this map
    // before falling back to its own static table, and up to whichever
    // listener answers an OSC 10/11/12 QUERY (see TerminalEmulator.
    // Listener.onQueryDynamicColor's own doc) to read the CURRENT
    // resolved value - which for an un-overridden slot means asking the
    // UI layer's own live palette, not this map.
    //
    // Keyed by ARGB Int (0xAARRGGBB, same packed format as everywhere
    // else in this codebase - TerminalPalette.resolve's own return type,
    // Kitty's pixel IntArrays, etc.) so no new color representation is
    // introduced. OSC 4 keys by the 0-255 palette slot number; OSC
    // 10/11/12 key by the DYNAMIC_COLOR_* constants above, in a SEPARATE
    // map from the 0-255 OSC 4 slots - despite xterm documenting OSC
    // 10/11/12 as themselves reachable via 4;10/4;11/4;12 in some
    // implementations, keeping them apart means a program that (rarely,
    // legitimately) uses OSC 4 to redefine palette slot 10 doesn't
    // collide with an unrelated program's OSC 10 dynamic-foreground
    // request, or vice versa - this codebase's TerminalPalette has no
    // slot 10/11/12 concept in the first place (defaultForeground/
    // defaultBackground are separate fields, not ansiColors[10/11]), so
    // conflating the two would be actively wrong here, not just
    // over-cautious.
    private val paletteOverrides = HashMap<Int, Int>()
    private val dynamicColorOverrides = HashMap<Int, Int>()

    /** OSC 4 SET: overrides palette slot [index] (0-255) to [argb]. */
    fun setPaletteOverride(index: Int, argb: Int) = lock.withLock {
        paletteOverrides[index] = argb
    }

    /** OSC 4 QUERY / resolve-time lookup: the overridden ARGB for palette
     *  slot [index], or null if the program never overrode it (caller
     *  falls back to its own static/theme table - see this section's own
     *  header doc). */
    fun getPaletteOverride(index: Int): Int? = lock.withLock { paletteOverrides[index] }

    /** OSC 104 (reset one palette slot to its theme default, no args
     *  means "reset all") - [index] null clears every override. */
    fun resetPaletteOverride(index: Int?) = lock.withLock {
        if (index == null) paletteOverrides.clear() else paletteOverrides.remove(index)
    }

    /** OSC 10/11/12 SET: overrides the dynamic color [slot]
     *  (DYNAMIC_COLOR_FOREGROUND/BACKGROUND/CURSOR) to [argb]. */
    fun setDynamicColorOverride(slot: Int, argb: Int) = lock.withLock {
        dynamicColorOverrides[slot] = argb
    }

    /** The overridden ARGB for dynamic color [slot], or null if never
     *  overridden (caller falls back to the live theme's own default/
     *  cursor color - see this section's own header doc on why that
     *  fallback can't happen here). */
    fun getDynamicColorOverride(slot: Int): Int? = lock.withLock { dynamicColorOverrides[slot] }

    /** OSC 110/111/112 (reset one dynamic color to its theme default). */
    fun resetDynamicColorOverride(slot: Int) = lock.withLock {
        dynamicColorOverrides.remove(slot)
    }

    /** Clears every OSC 4/10/11/12 override - used by [reset] (RIS), same
     *  "a full terminal reset puts colors back to their real defaults"
     *  contract as a real xterm's own RIS behavior for these OSCs. Unlike
     *  [clearAllKittyImages], deliberately NOT called on alternate-screen
     *  entry/exit - a real terminal's OSC 10/11/12 overrides are a
     *  terminal-wide setting (the running program's chosen background,
     *  say), not tied to which screen buffer happens to be active, so a
     *  full-screen app (vim) switching to the alt screen must not lose a
     *  color the shell set on the primary screen before launching it. */
    fun clearAllColorOverrides() = lock.withLock {
        paletteOverrides.clear()
        dynamicColorOverrides.clear()
    }

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
        images.removeAll { it.row == row }
        kittyPlacements.entries.removeAll { it.value.row == row }
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
        shiftImageRows(-1)
        shiftKittyPlacementRows(-1)
    }

    // Applies `delta` to every placed image's row (see PlacedImage's own
    // doc for why images only ever track LIVE grid movement), dropping any
    // that scroll off the top (row < 0) or past the bottom (row >= rows) -
    // same "gone once off-screen" simplification as scrollUp's own
    // scrollback handling for images specifically (see images' own doc).
    // Shared by every grid-row-shifting mutator below instead of each one
    // reimplementing this bookkeeping separately.
    private fun shiftImageRows(delta: Int) {
        if (images.isEmpty()) return
        val it = images.iterator()
        while (it.hasNext()) {
            val placed = it.next()
            placed.row += delta
            if (placed.row !in 0 until rows) it.remove()
        }
    }

    // Kitty counterpart of shiftImageRows - same delta-and-drop-if-off-
    // grid bookkeeping, kept as its own function (rather than folding
    // into shiftImageRows) since kittyPlacements is a LinkedHashMap keyed
    // by (imageId, placementId), not a plain ArrayList, so entries have
    // to be removed by key rather than via ArrayList iterator.remove().
    // Deliberately does NOT touch kittyImages - a placement scrolling off
    // the live grid is exactly the "on-screen placement gone, pixels kept
    // in case another placement still wants them" case KittyPlacement's
    // own doc describes, not a delete-the-data event (only an explicit
    // uppercase a=d, see deleteKittyPlacements, ever frees kittyImages).
    private fun shiftKittyPlacementRows(delta: Int) {
        if (kittyPlacements.isEmpty()) return
        val it = kittyPlacements.entries.iterator()
        while (it.hasNext()) {
            val placed = it.next().value
            placed.row += delta
            if (placed.row !in 0 until rows) it.remove()
        }
    }

    // Region-scoped counterpart of shiftKittyPlacementRows, mirroring
    // shiftImageRowsInRange's own reasoning for why region-bounded
    // mutators (scrollRegionUp/Down, insertLines/deleteLines) need a
    // separate function from the whole-grid shift above.
    private fun shiftKittyPlacementRowsInRange(top: Int, bottom: Int, delta: Int) {
        if (kittyPlacements.isEmpty()) return
        val it = kittyPlacements.entries.iterator()
        while (it.hasNext()) {
            val placed = it.next().value
            if (placed.row !in top..bottom) continue
            placed.row += delta
            if (placed.row !in top..bottom) it.remove()
        }
    }

    /** Scrolls the region [top, bottom] (inclusive) up by one line without
     *  touching scrollback - used for scrolling-region-aware line feeds. */
    fun scrollRegionUp(top: Int, bottom: Int, bg: Int = DEFAULT_BACKGROUND) = lock.withLock {
        if (top >= bottom || top !in 0 until rows || bottom !in 0 until rows) return@withLock
        for (r in top until bottom) {
            grid[r] = grid[r + 1]
        }
        grid[bottom] = Array(columns) { Cell(bg = bg) }
        shiftImageRowsInRange(top, bottom, -1)
        shiftKittyPlacementRowsInRange(top, bottom, -1)
    }

    // Region-scoped counterpart of shiftImageRows: only images anchored
    // within [top, bottom] move/drop, matching how scrollRegionUp/Down and
    // insertLines/deleteLines only ever touch rows inside their own
    // [row, bottom] window and leave everything outside it untouched.
    private fun shiftImageRowsInRange(top: Int, bottom: Int, delta: Int) {
        if (images.isEmpty()) return
        val it = images.iterator()
        while (it.hasNext()) {
            val placed = it.next()
            if (placed.row !in top..bottom) continue
            placed.row += delta
            if (placed.row !in top..bottom) it.remove()
        }
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
        shiftImageRows(1)
        shiftKittyPlacementRows(1)
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
        shiftImageRowsInRange(top, bottom, 1)
        shiftKittyPlacementRowsInRange(top, bottom, 1)
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
        shiftImageRowsInRange(row, bottom, count)
        shiftKittyPlacementRowsInRange(row, bottom, count)
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
        shiftImageRowsInRange(row, bottom, -count)
        shiftKittyPlacementRowsInRange(row, bottom, -count)
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
        // A COLUMN change genuinely invalidates a placed image's anchor:
        // text reflows around it differently, and there's no meaningful
        // "shift" to apply to its (row, col) - real terminals (xterm
        // among them) drop Sixel/image content across a reflow for
        // exactly this reason, and this still does too, below.
        //
        // A pure ROW change with columns UNCHANGED is a completely
        // different case, though, and is by far the most common resize
        // on Android: opening/closing the soft keyboard, or a pinch/
        // pane-drag that only changes height. Nothing horizontally
        // reflows here - the same row-shift already computed for the
        // text grid (rowOffset, just below) applies to an image's
        // anchored row exactly the same way it applies to any other
        // row's content. Previously this cleared images unconditionally
        // on EVERY resize, including this common keyboard-only case -
        // so simply tapping the terminal (which focuses the hidden field
        // and opens the keyboard) or dismissing it silently wiped out
        // any Sixel/Kitty image the user had just rendered, even though
        // nothing about the image's own footprint had actually become
        // invalid. That's the "IME unfocus olunca resim kayboluyor" bug -
        // shifting/clipping instead of clearing here fixes it while still
        // preserving the real xterm-matching behavior for an actual
        // column reflow.
        val columnsChanged = newColumns != oldColumns
        if (columnsChanged) {
            images.clear()
            clearAllKittyImages()
        }
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
        // Row-only resize (columnsChanged == false, see its own doc up top):
        // apply the exact same rowOffset shift to placed images that the
        // text grid itself just got via resized()/resizedPlain() above,
        // instead of the unconditional images.clear() this used to do on
        // every resize. shiftImageRows/shiftKittyPlacementRows already
        // exist for this (scrollUp and friends use them too) and already
        // drop anything that lands outside the new `rows` after shifting -
        // exactly "an image scrolled off the top/bottom of the live grid
        // during this resize is gone", the same simplification scrollUp
        // itself applies, just reached via a resize instead of a newline.
        // Must run AFTER rows = newRows above, since both shift helpers
        // bounds-check the shifted row against the CURRENT `rows`.
        if (!columnsChanged) {
            shiftImageRows(-rowOffset)
            shiftKittyPlacementRows(-rowOffset)
        }
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
