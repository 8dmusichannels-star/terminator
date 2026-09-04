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
 * VT100/ANSI escape sequence state machine.
 *
 * Feeds raw process output bytes/chars in, updates a TerminalBuffer, and
 * exposes cursor position + title (OSC 0/2 sequences) for the UI layer.
 *
 * Supports: cursor movement (CUU/CUD/CUF/CUB/CUP/HVP), erase in
 * display/line (ED/EL), SGR (colors 16/256, bold/underline/inverse/italic,
 * reset), scrolling region basics, bell (BEL), and window title OSC.
 * This is a pragmatic subset covering common shell/CLI usage - it is not a
 * full xterm clone.
 */
class TerminalEmulator(
    val buffer: TerminalBuffer,
    private val listener: Listener
) {
    interface Listener {
        fun onBell()
        fun onTitleChanged(title: String)
        fun onCursorMoved(row: Int, col: Int)
        fun onContentChanged()
        // Fired when the emulator itself needs to write bytes back to the
        // pty in response to something the running program asked for -
        // currently just DSR/CPR (CSI 6n, "where's the cursor?"). The
        // caller (TerminalSession) wires this straight to its own write().
        fun onRespond(data: String)
    }

    // Mirrored onto buffer.cursorRow/cursorCol on every write so the
    // renderer always knows where to draw the "waiting for input" caret,
    // without having to touch every call site that moves the cursor.
    private var cursorRow = 0
        set(value) { field = value; buffer.cursorRow = value }
    private var cursorCol = 0
        set(value) { field = value; buffer.cursorCol = value }

    /**
     * Which xterm mouse-tracking mode the running program (mc, vim, htop,
     * etc.) last asked for via DECSET, if any. NONE means the app hasn't
     * requested mouse reporting - touches on the terminal should fall back
     * to normal scroll/select/zoom gestures instead of being turned into
     * mouse escape sequences the app never asked for and wouldn't parse.
     */
    enum class MouseMode { NONE, X10, NORMAL, BUTTON_EVENT, ANY_EVENT }
    var mouseMode: MouseMode = MouseMode.NONE
        private set
    // SGR (1006) extended coordinates vs. the legacy fixed-width encoding.
    // Nearly everything modern (including mc, vim, htop) requests 1006
    // because the legacy encoding breaks past column/row 223 - but both are
    // supported here since some older curses builds only ask for the plain
    // 1000/1002/1003 modes.
    var mouseSgrMode: Boolean = false
        private set

    // DECCKM (CSI ?1h/l) - "application cursor keys" mode. ncurses turns
    // this on at startup for any full-screen program (nano included - see
    // its smkx/rmkx terminfo capability) and from then on expects arrow/
    // home/end keys to arrive as SS3 sequences (\EOA..\EOD, \EOH, \EOF)
    // instead of the normal CSI form (\E[A.. etc). Previously silently
    // ignored, which meant the app always sent the CSI form regardless -
    // wrong whenever a full-screen program had switched modes. The UI
    // layer (VirtualKeyBar/MainActivity) reads this to decide which form
    // to actually send for arrow/home/end key presses.
    var applicationCursorKeys: Boolean = false
        private set

    // Current SGR (graphic rendition) state, applied to newly written cells
    private var curFg = TerminalBuffer.DEFAULT_FOREGROUND
    private var curBg = TerminalBuffer.DEFAULT_BACKGROUND
    private var curBold = false
    private var curUnderline = false
    private var curInverse = false
    private var curItalic = false

    // Scrolling region (DECSTBM, CSI r) - top/bottom are 0-indexed and
    // inclusive. Defaults to the full screen. TUI apps commonly pin a
    // status/title line outside this region and scroll only the rest.
    private var scrollTop = 0
    private var scrollBottom = buffer.rows - 1

    // DECSC/DECRC and ANSI.SYS-style CSI s/u cursor save-restore state.
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    // Cursor visibility (CSI ?25h/l) - surfaced for the UI layer to hide the
    // caret while a program has explicitly turned it off (e.g. during a
    // full-screen redraw).
    var cursorVisible = true
        private set(value) { field = value; buffer.cursorVisible = value }

    // Parser state
    private enum class State { NORMAL, ESCAPE, CSI, OSC, CHARSET }
    private var state = State.NORMAL
    private val paramBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()

    companion object {
        // U+200D ZERO WIDTH JOINER - glues two otherwise-independent emoji
        // codepoints into one displayed glyph (family/couple/profession
        // sequences etc: "man" + ZWJ + "woman" + ZWJ + "girl" + ZWJ + "boy"
        // renders as a single family emoji, not four side-by-side ones).
        private const val ZWJ = '\u200D'
        // U+FE0E/FE0F variation selectors - pick the text-style vs
        // emoji-style presentation of the preceding codepoint (e.g. "❤" as
        // plain glyph vs "❤️" as a colored heart). No width of their own;
        // they only modify what came right before them.
        private const val VS15_TEXT = '\uFE0E'
        private const val VS16_EMOJI = '\uFE0F'
        // U+1F3FB..U+1F3FF EMOJI MODIFIER FITZPATRICK TYPE-1-2..TYPE-6 -
        // skin-tone modifiers, astral-plane so they arrive as surrogate
        // pairs like any other emoji codepoint.
        private const val SKIN_TONE_MODIFIER_LOW = 0x1F3FB
        private const val SKIN_TONE_MODIFIER_HIGH = 0x1F3FF

        private fun isSkinToneModifier(codePoint: Int) =
            codePoint in SKIN_TONE_MODIFIER_LOW..SKIN_TONE_MODIFIER_HIGH
    }

    // Holds a grapheme cluster (emoji + trailing ZWJ/variation-selector/
    // skin-tone bytes) that was still "open" - i.e. could plausibly keep
    // extending - when a previous append() call ran out of chars. The pty
    // reader (see TerminalSession.start) decodes UTF-8 into a fixed 4096-
    // char buffer per InputStreamReader.read() and hands each read straight
    // to append() as its own chunk; a compound emoji sequence (family/
    // couple/flag sequences run well past 10 UTF-16 chars once several ZWJs
    // are involved) landing across that 4096-char boundary used to get torn
    // in half - the first half rendered immediately as its own (wrong,
    // partial) glyph by the old code below, and the second half arrived in
    // the *next* append() call with nothing to tell it those chars were
    // actually a continuation rather than a new cluster. That's the split-
    // across-reads case scanGraphemeCluster's own forward-only scan could
    // never catch, since it only ever looked within the single CharSequence
    // it was given. Carrying the trailing open cluster over here - instead
    // of writing it - and prepending it to whatever text the next append()
    // call brings in lets the scan see the whole sequence as one contiguous
    // run again, the same as if the pty read had never split it.
    private val pendingCluster = StringBuilder()

    /** Feed a chunk of decoded output text from the child process into the emulator. */
    fun append(text: CharSequence) {
        // Re-attach whatever cluster was left hanging off the end of the
        // previous chunk (see pendingCluster's doc) before scanning this
        // one, so a ZWJ sequence split across two pty reads is seen here as
        // a single contiguous run rather than two unrelated fragments.
        val effectiveText: CharSequence = if (pendingCluster.isEmpty()) {
            text
        } else {
            val combined = pendingCluster.toString() + text
            pendingCluster.clear()
            combined
        }
        var i = 0
        while (i < effectiveText.length) {
            val ch = effectiveText[i]
            // Only NORMAL-state printable characters can ever start a
            // grapheme cluster - escape/CSI/OSC sequences are pure ASCII,
            // so this is only attempted when we're about to hand a
            // printable character to writeChar (handleNormal's `else`
            // branch would otherwise take it one Char at a time).
            if (state == State.NORMAL && ch.isHighSurrogate() && i + 1 < effectiveText.length && effectiveText[i + 1].isLowSurrogate()) {
                val (end, stillOpen) = scanGraphemeCluster(effectiveText, i)
                if (stillOpen) {
                    // Ran off the end of this chunk mid-cluster (e.g. right
                    // after a ZWJ, waiting on the codepoint it joins to) -
                    // hold the whole thing back rather than rendering a
                    // truncated glyph now. It'll be completed (or, in the
                    // rare case the process output genuinely ends there,
                    // flushed as-is) the next time append() runs.
                    pendingCluster.append(effectiveText.subSequence(i, end))
                    i = end
                    continue
                }
                writeChar(effectiveText.subSequence(i, end).toString())
                i = end
                continue
            }
            processChar(ch)
            i++
        }
        listener.onContentChanged()
    }

    /**
     * Starting from a confirmed surrogate pair at [start], greedily extends
     * the range forward to absorb whatever keeps it one visual glyph rather
     * than several: a ZWJ followed by another codepoint (joins two emoji
     * into a compound one - families, couples, profession sequences, the
     * rainbow/trans/etc pride flags), a trailing variation selector, or a
     * skin-tone modifier. Returns the exclusive end index of the whole
     * cluster, plus whether the scan stopped because it ran out of chars
     * while still in a state that could extend further (true) versus
     * stopping because it found a definitive non-continuing character
     * (false) - see append()'s pendingCluster handling, which only holds
     * a cluster back in the first case. Without the cluster-extension scan
     * itself, TerminalBuffer.Cell (see its own doc) still only ever held a
     * single codepoint's surrogate pair, so a ZWJ sequence rendered as
     * several adjacent glyphs (e.g. an adult, a joiner glyph, and a child,
     * instead of one family emoji) rather than the intended single
     * grapheme - Cell.text being a String already made it capable of
     * holding the rest once this scan feeds it in.
     */
    private fun scanGraphemeCluster(text: CharSequence, start: Int): Pair<Int, Boolean> {
        var i = start + 2 // past the initial confirmed surrogate pair
        while (i < text.length) {
            val ch = text[i]
            when {
                // Variation selector: consumes just the one BMP char, then
                // keep scanning - a ZWJ can still follow it.
                ch == VS15_TEXT || ch == VS16_EMOJI -> i += 1
                // ZWJ must be followed by another full codepoint (either a
                // surrogate pair or a plain BMP one, e.g. some flag
                // sequences join BMP regional-indicator-adjacent symbols)
                // to mean anything. If the chunk ends exactly on the ZWJ (or
                // one char past it, with no way yet to tell whether that
                // lone char is the whole joined codepoint or just the high
                // surrogate of a pair still arriving), that's the open,
                // held-back case - a dangling ZWJ with genuinely nothing
                // ever coming after it (chunk ends, process exits) still
                // eventually gets flushed by the pendingCluster fallback
                // below, same as before.
                ch == ZWJ && i + 1 >= text.length -> return i + 1 to true
                ch == ZWJ -> {
                    val next = text[i + 1]
                    if (next.isHighSurrogate() && i + 2 >= text.length) {
                        // High surrogate with its low half not arrived yet -
                        // stay open rather than guessing.
                        return i + 2 to true
                    }
                    i += if (next.isHighSurrogate() && i + 2 < text.length && text[i + 2].isLowSurrogate()) {
                        3 // ZWJ + surrogate pair
                    } else {
                        2 // ZWJ + one BMP codepoint
                    }
                }
                // Skin-tone modifier directly following the base emoji (no
                // ZWJ needed for this one per the Unicode emoji spec). A
                // lone trailing high surrogate here (pair not complete yet)
                // is also held open rather than treated as "no modifier".
                ch.isHighSurrogate() && i + 1 >= text.length -> return i + 1 to true
                ch.isHighSurrogate() && text[i + 1].isLowSurrogate() &&
                    isSkinToneModifier(Character.toCodePoint(ch, text[i + 1])) -> i += 2
                else -> return i to false
            }
        }
        return i to false
    }

    /**
     * Flushes any grapheme cluster still held open by [pendingCluster] -
     * called when a session ends (see TerminalSession's exit handling) so a
     * dangling ZWJ/partial sequence that was genuinely the last output the
     * process ever produced (chunk ended, pty then closed - not just
     * "next read is still coming") still renders whatever it has rather
     * than silently vanishing.
     */
    fun flushPendingCluster() {
        if (pendingCluster.isEmpty()) return
        val text = pendingCluster.toString()
        pendingCluster.clear()
        writeChar(text)
        listener.onContentChanged()
    }

    private fun processChar(ch: Char) {
        when (state) {
            State.NORMAL -> handleNormal(ch)
            State.ESCAPE -> handleEscape(ch)
            State.CSI -> handleCsi(ch)
            State.OSC -> handleOsc(ch)
            State.CHARSET -> { state = State.NORMAL } // consume+drop the designator byte (e.g. the 'B' in ESC(B)
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch) {
            '\u001B' -> { state = State.ESCAPE }
            '\u0007' -> listener.onBell() // BEL
            '\n' -> lineFeed()
            '\r' -> cursorCol = 0
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> cursorCol = ((cursorCol / 8) + 1) * 8
            // A lone surrogate (the pairing check in append() didn't fire -
            // e.g. a high surrogate arrived as literally the last char of
            // one append() call, with its low-surrogate other half not yet
            // read off the pty) has no valid single-Char rendering. Rather
            // than writeChar-ing an invalid half-codepoint into a cell,
            // drop it silently; the common real-world case (split across a
            // pty read boundary) is naturally rare since pty reads are
            // usually larger than a handful of bytes, and even when it
            // happens this just costs one glyph rather than corrupting the
            // grid with an unpaired surrogate.
            else -> if (!ch.isSurrogate()) writeChar(ch.toString())
        }
    }

    private fun handleEscape(ch: Char) {
        when (ch) {
            '[' -> { state = State.CSI; paramBuffer.clear() }
            ']' -> { state = State.OSC; oscBuffer.clear() }
            'c' -> { reset(); state = State.NORMAL }
            // SCS - designate G0/G1 character set (e.g. ESC(B = US-ASCII,
            // ESC(0 = DEC special graphics). nano and other full-screen
            // apps send these routinely around their status-bar drawing.
            // This is a two-byte sequence: the designator that follows
            // ('(' or ')') still needs one more byte consumed (the actual
            // set, like 'B' or '0'). Previously that byte fell through to
            // the `else` branch below, which reset state to NORMAL without
            // consuming it - so the next processChar() call treated it as
            // plain text and wrote it straight into the buffer. That's
            // exactly what put stray "B" characters into nano's screen.
            '(', ')' -> { state = State.CHARSET }
            // DECSC / DECRC (save/restore cursor position) - used by some
            // full-screen programs for scratch redraws. Previously silently
            // swallowed (fell to the `else` branch below), which lost the
            // saved position entirely and could leave the cursor - and
            // anything drawn relative to it - in the wrong place.
            '7' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol; state = State.NORMAL }
            '8' -> {
                cursorRow = savedCursorRow.coerceIn(0, buffer.rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, buffer.columns - 1)
                state = State.NORMAL
            }
            // IND (Index) - move down one line, scrolling the region if
            // already at the bottom margin. Previously fell through to the
            // `else` below and was silently dropped, which is exactly why
            // full-screen apps that scroll via IND (nano among them, when
            // it redraws the edit window at the bottom of the screen)
            // looked "stuck": the scroll they asked for never happened.
            'D' -> { lineFeed(); state = State.NORMAL }
            // NEL (Next Line) - CR+LF in one shot. Same scroll-on-bottom-
            // margin behavior as IND, plus a carriage return.
            'E' -> { cursorCol = 0; lineFeed(); state = State.NORMAL }
            // RI (Reverse Index) - move up one line, scrolling the region
            // down if already at the top margin. Was silently dropped too;
            // without it, scrolling upward through a scroll-region (e.g.
            // Page Up inside nano) never worked.
            'M' -> { reverseLineFeed(); state = State.NORMAL }
            else -> state = State.NORMAL
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.isDigit() || ch == ';' || ch == '?' || ch == '>' || ch == '=') {
            // '?' is the DEC-private-mode prefix (CSI ? Ps h/l, handled by
            // handlePrivateMode). '>' and '=' are two more CSI prefix bytes
            // real programs send - '>' for secondary-DA/xterm queries like
            // XTVERSION ("CSI > 0 q", "CSI > c") and kitty's keyboard-
            // protocol sequences ("CSI > 1 u"), '=' for tertiary-DA. Before
            // this, neither was recognized here, so the prefix byte itself
            // fell straight through to the "final byte reached" dispatch
            // below with an empty paramBuffer: it got treated AS the final
            // byte, silently matched no case, and state reset to NORMAL -
            // right as the sequence's *real* parameters and final byte
            // (e.g. the "0", " ", "q" of "CSI > 0 q") were still incoming.
            // Those then arrived one at a time in NORMAL state and got
            // printed as literal text - exactly the "0q" garbage seen on
            // fish/starship startup (both send "CSI > 0 q" as a terminal-
            // capability probe). Folding the prefix into paramBuffer here,
            // same as '?', keeps the parser in CSI state until the actual
            // final byte shows up, so the whole sequence dispatches (and
            // gets silently ignored, correctly) instead of leaking half of
            // itself onto the screen.
            if (paramBuffer.length >= 64) {
                state = State.NORMAL
                return
            }
            paramBuffer.append(ch)
            return
        }
        if (ch.code in 0x20..0x2F) {
            // Intermediate byte (e.g. the '$' in DECRQM's "CSI ? Ps $ p",
            // or ' ' in some DECSCUSR cursor-style sequences) - part of the
            // sequence but not itself the final byte. Previously this fell
            // straight through to the dispatch logic below, which treated
            // it AS the final byte: the sequence got dispatched early on
            // the wrong byte (silently ignored, since '$'/' ' etc. don't
            // match any known final byte), state reset to NORMAL, and the
            // *real* final byte that followed (e.g. 'p') then arrived as
            // plain text in NORMAL state and got printed literally. That's
            // the garbled-character pattern seen with prompts/programs that
            // use two-byte-final CSI sequences. Consuming it here and
            // continuing to wait for the actual final byte fixes that.
            return
        }
        // Final byte reached - dispatch
        val raw = paramBuffer.toString()
        val private = raw.startsWith("?")
        // '>' (secondary-DA/XTVERSION/kitty-keyboard queries, e.g. the
        // "CSI > 0 q" fish/starship send on startup) and '=' (tertiary-DA)
        // sequences aren't otherwise handled below - stripping the prefix
        // the same way '?' is stripped means they still hit a real case
        // (none) and fall through to the unsupported-final-byte `else`,
        // silently and completely ignored, instead of the leftover prefix
        // character corrupting the parsed param list.
        val params = raw.removePrefix("?").removePrefix(">").removePrefix("=")
            .split(';')
            .mapNotNull { it.toIntOrNull() }
            // Numeric CSI params (repeat counts for 'L'/'M'/'P'/'@'/'X'/'S'/'T',
            // cursor-move distances, etc.) are meant to be small - real
            // terminals send counts in the tens at most. But nothing here
            // stopped a huge or malformed count (e.g. a truncated/garbled
            // sequence whose digits ran into the next chunk) from reaching
            // repeat()/coerceAtLeast() and looping millions of times before
            // returning control to the UI thread, which is exactly what an
            // app hang looks like from the outside even though the loop
            // does eventually terminate. Clamping here caps every dispatch
            // below at a bounded amount of work regardless of what a
            // misbehaving/garbled sequence claims.
            .map { it.coerceIn(-4096, 4096) }

        if (private) {
            handlePrivateMode(params, ch)
            listener.onCursorMoved(cursorRow, cursorCol)
            state = State.NORMAL
            return
        }

        when (ch) {
            'A' -> cursorRow = (cursorRow - (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtLeast(0)
            'B' -> cursorRow = (cursorRow + (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtMost(buffer.rows - 1)
            'C' -> cursorCol = (cursorCol + (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtMost(buffer.columns - 1)
            'D' -> cursorCol = (cursorCol - (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtLeast(0)
            'H', 'f' -> {
                cursorRow = ((params.getOrElse(0) { 1 }) - 1).coerceIn(0, buffer.rows - 1)
                cursorCol = ((params.getOrElse(1) { 1 }) - 1).coerceIn(0, buffer.columns - 1)
            }
            'J' -> eraseInDisplay(params.getOrElse(0) { 0 })
            'K' -> eraseInLine(params.getOrElse(0) { 0 })
            // Repeat counts additionally clamped to the buffer's own
            // dimensions - inserting/deleting more lines or chars than the
            // screen actually has is never meaningful, so there's no reason
            // to let a large-but-under-4096 count do that much pointless
            // work on every keystroke's worth of output.
            'L' -> insertLines(params.getOrElse(0) { 1 }.coerceIn(1, buffer.rows))
            'M' -> deleteLines(params.getOrElse(0) { 1 }.coerceIn(1, buffer.rows))
            'P' -> deleteChars(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns))
            '@' -> insertChars(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns))
            // CHA/HPA - absolute column. VPA - absolute row. ECH - erase N
            // chars in place without shifting anything (unlike 'P'/'@').
            // These three are used constantly by nano and other ncurses
            // programs for cheap partial redraws; without them the cursor
            // just stayed wherever the last relative move left it, which is
            // what made those screens look garbled/misaligned.
            'G', '`' -> cursorCol = ((params.getOrElse(0) { 1 }) - 1).coerceIn(0, buffer.columns - 1)
            'd' -> cursorRow = ((params.getOrElse(0) { 1 }) - 1).coerceIn(0, buffer.rows - 1)
            'X' -> eraseChars(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns))
            // ANSI.SYS-style save/restore cursor (distinct escape form of
            // the same DECSC/DECRC behavior handled in handleEscape).
            's' -> { savedCursorRow = cursorRow; savedCursorCol = cursorCol }
            'u' -> {
                cursorRow = savedCursorRow.coerceIn(0, buffer.rows - 1)
                cursorCol = savedCursorCol.coerceIn(0, buffer.columns - 1)
            }
            'r' -> {
                // DECSTBM: set scrolling region (1-indexed, inclusive). No
                // params resets to the full screen.
                val top = (params.getOrElse(0) { 1 } - 1).coerceIn(0, buffer.rows - 1)
                val bottom = (params.getOrElse(1) { buffer.rows } - 1).coerceIn(0, buffer.rows - 1)
                if (top < bottom) {
                    scrollTop = top
                    scrollBottom = bottom
                } else {
                    scrollTop = 0
                    scrollBottom = buffer.rows - 1
                }
                cursorRow = scrollTop
                cursorCol = 0
            }
            // SU (Scroll Up) / SD (Scroll Down) - scroll the region by N
            // lines without touching the cursor position at all. Distinct
            // from IND/RI (which move the cursor and only scroll as a side
            // effect of hitting the margin); some redraws issue these
            // directly instead. Previously unhandled, silently ignored.
            'S' -> {
                val savedRow = cursorRow
                cursorRow = scrollBottom // so each lineFeed() call actually scrolls
                repeat(params.getOrElse(0) { 1 }.coerceIn(1, buffer.rows)) { lineFeed() }
                cursorRow = savedRow
            }
            'T' -> {
                val savedRow = cursorRow
                cursorRow = scrollTop // so each reverseLineFeed() call actually scrolls
                repeat(params.getOrElse(0) { 1 }.coerceIn(1, buffer.rows)) { reverseLineFeed() }
                cursorRow = savedRow
            }
            'm' -> applySgr(params)
            // DSR (Device Status Report). Ps=6 is CPR - "where's the
            // cursor?" - and the caller is expected to block waiting for
            // an answer on the pty. starship (and other prompts/programs
            // that probe terminal state on startup) send this and will
            // hang indefinitely - exactly the "connects but then freezes
            // until Ctrl+C" symptom - if nothing ever answers it. Reply
            // with CSI row;col R, 1-indexed per the spec, using the
            // emulator's own cursor position. Ps=5 ("are you OK?") is
            // answered with a fixed "OK" status report; some scripts probe
            // it before deciding whether to enable fancier prompt features.
            'n' -> when (params.getOrElse(0) { 0 }) {
                6 -> listener.onRespond("\u001B[${cursorRow + 1};${cursorCol + 1}R")
                5 -> listener.onRespond("\u001B[0n")
            }
            'h', 'l' -> { /* non-private mode set/reset we don't track - ignore */ }
            else -> { /* unsupported final byte - ignore */ }
        }
        listener.onCursorMoved(cursorRow, cursorCol)
        state = State.NORMAL
    }

    private fun handlePrivateMode(params: List<Int>, finalByte: Char) {
        val enable = finalByte == 'h'
        for (p in params) {
            when (p) {
                25 -> cursorVisible = enable
                1049, 47 -> {
                    // Alternate screen buffer, used by nano/vim/less/htop
                    // etc. Without this their full-screen redraws land
                    // directly in the primary grid/scrollback instead of a
                    // clean separate buffer, which is what made them look
                    // garbled. 1049 also implies save/restore cursor.
                    if (enable) {
                        buffer.enterAlternateScreen()
                        eraseInDisplay(2)
                    } else {
                        buffer.exitAlternateScreen()
                    }
                    cursorRow = buffer.cursorRow
                    cursorCol = buffer.cursorCol
                    scrollTop = 0
                    scrollBottom = buffer.rows - 1
                }
                // X10 (click only), Normal (1000: click+release), Button-
                // event (1002: click+release+drag while a button is held),
                // Any-event (1003: also reports plain hover motion). These
                // are mutually exclusive in real xterm - the app enables
                // whichever one matches how much motion detail it wants, so
                // the last one set wins here too.
                9 -> mouseMode = if (enable) MouseMode.X10 else MouseMode.NONE
                1000 -> mouseMode = if (enable) MouseMode.NORMAL else MouseMode.NONE
                1002 -> mouseMode = if (enable) MouseMode.BUTTON_EVENT else MouseMode.NONE
                1003 -> mouseMode = if (enable) MouseMode.ANY_EVENT else MouseMode.NONE
                1006 -> mouseSgrMode = enable
                1 -> applicationCursorKeys = enable
                // 2004: bracketed paste - not implemented, but explicitly
                // ignored now rather than falling through to the generic
                // (and wrong) non-private h/l no-op.
                else -> { /* unsupported private mode - ignore */ }
            }
        }
    }

    /**
     * Touch/pointer event kinds a UI layer can report. Mirrors the subset
     * of xterm mouse-tracking button semantics that DECSET modes 1000/
     * 1002/1003 distinguish between.
     *
     * WHEEL_UP/WHEEL_DOWN are separate from PRESS/DRAG/RELEASE: xterm wheel
     * buttons (4 and 5, encoded as button-id 64/65 - see encodeMouseEvent)
     * are a single self-contained "click" with no matching release, exactly
     * like a real scroll wheel notch. They're what a released-but-still-
     * moving fling should keep sending once the finger is no longer
     * physically down to drive PRESS/DRAG/RELEASE - see ScrollFling below,
     * which is the Compose-side equivalent of what Termux's TerminalView
     * does in doScroll() when mEmulator.isMouseTrackingActive() is true
     * during an onFling() callback.
     */
    enum class MouseEventKind { PRESS, RELEASE, DRAG, MOVE, WHEEL_UP, WHEEL_DOWN }

    /**
     * Encodes a touch at 0-indexed (col, row) into the escape sequence the
     * currently-running program expects, given whatever mouse mode it last
     * requested via DECSET - or returns null if nothing should be sent
     * (mouse reporting is off, or this event kind isn't reported under the
     * active mode - e.g. plain hover MOVE only goes out under 1003).
     *
     * button: 0=left, 1=middle, 2=right - only meaningful for PRESS/DRAG.
     * Ignored for WHEEL_UP/WHEEL_DOWN, which always encode as xterm button
     * id 64/65 regardless of what's passed.
     */
    fun encodeMouseEvent(kind: MouseEventKind, col: Int, row: Int, button: Int = 0): String? {
        if (mouseMode == MouseMode.NONE) return null
        if (kind == MouseEventKind.MOVE && mouseMode != MouseMode.ANY_EVENT) return null
        if (kind == MouseEventKind.DRAG && mouseMode != MouseMode.BUTTON_EVENT && mouseMode != MouseMode.ANY_EVENT) return null
        if (kind == MouseEventKind.RELEASE && mouseMode == MouseMode.X10) return null // X10 never reports release
        // Wheel notches are reported under every mode that reports PRESS at
        // all (X10 included) - real xterm does the same, a wheel click is
        // just another button-4/5 press with no release, same as X10's
        // ordinary button clicks having no release either.

        // xterm mouse coordinates are 1-indexed from the top-left.
        val c = (col + 1).coerceIn(1, buffer.columns)
        val r = (row + 1).coerceIn(1, buffer.rows)

        val cb = when (kind) {
            MouseEventKind.PRESS -> button
            MouseEventKind.DRAG -> button or 32   // motion-while-pressed flag
            MouseEventKind.MOVE -> 3 or 32         // no button + motion flag
            MouseEventKind.RELEASE -> if (mouseSgrMode) button else 3 // legacy encoding has no distinct release button id
            MouseEventKind.WHEEL_UP -> 64
            MouseEventKind.WHEEL_DOWN -> 65
        }

        return if (mouseSgrMode) {
            // SGR (1006) extended encoding: CSI < cb ; col ; row M/m - the
            // final byte itself (M press/drag/move, m release) carries the
            // press/release distinction, so cb doesn't need the legacy
            // "release = 3" placeholder above. Wheel notches always use the
            // 'M' (press) final byte - xterm never sends a matching 'm' for
            // a wheel click, same as the legacy branch below never sends a
            // release byte for one.
            val finalByte = if (kind == MouseEventKind.RELEASE) 'm' else 'M'
            "\u001B[<$cb;$c;$r$finalByte"
        } else {
            // Legacy X10/1000/1002 encoding: CSI M then three raw bytes
            // (button+32, col+32, row+32). Breaks past col/row 223 - real
            // xterm has the same limitation in this mode, that's why 1006
            // exists and everything modern asks for it too.
            val btnByte = (cb + 32).coerceIn(32, 255).toChar()
            val colByte = (c + 32).coerceIn(32, 255).toChar()
            val rowByte = (r + 32).coerceIn(32, 255).toChar()
            "\u001B[M$btnByte$colByte$rowByte"
        }
    }

    // Set when the OSC terminator's ESC byte has been seen but its
    // required follow-up '\' (forming the two-byte ST, ESC \) hasn't
    // arrived yet.
    private var oscPendingSt = false

    private fun handleOsc(ch: Char) {
        if (oscPendingSt) {
            oscPendingSt = false
            state = State.NORMAL
            finishOsc()
            if (ch == '\\') {
                // The expected second byte of ST (ESC \\) - consumed as
                // part of the terminator, nothing left to do with it.
                return
            }
            // Not actually ST - the ESC we saw was the start of a *new*
            // escape sequence butting up against this OSC with no proper
            // terminator (very common with prompts like starship, which
            // chain an OSC 133 marker straight into an SGR sequence with
            // no ST in between). ch here is the byte *after* that second
            // ESC, e.g. '[' in "...ESC \\ ESC [ 4;1m" - not the ESC itself,
            // which was already consumed when oscPendingSt was set. Route
            // through handleEscape (not processChar/handleNormal) so that
            // byte is interpreted as the start of the new sequence instead
            // of being printed literally - which is exactly what produced
            // the "4;1m" garbage with the leading ESC and '[' missing.
            state = State.ESCAPE
            handleEscape(ch)
            return
        }
        if (ch == '\u0007') {
            state = State.NORMAL
            finishOsc()
        } else if (ch == '\u001B') {
            // Could be the start of ST (ESC \\) - wait for the next byte
            // before finishing, instead of ending the OSC right here and
            // leaking the '\\' into NORMAL state as printable text.
            oscPendingSt = true
        } else {
            // Same runaway-growth guard as paramBuffer above, sized larger
            // since real OSC payloads (window titles, OSC 8 hyperlink URLs)
            // are legitimately longer than a CSI parameter list. A
            // terminator/BEL that never arrives (garbled stream, torn
            // chunk) would otherwise grow this without bound instead of
            // ever reaching handleOsc's terminator branch.
            if (oscBuffer.length >= 8192) {
                state = State.NORMAL
                return
            }
            oscBuffer.append(ch)
        }
    }

    private fun finishOsc() {
        // Format we care about: "0;title" or "2;title"
        val content = oscBuffer.toString()
        val sepIdx = content.indexOf(';')
        if (sepIdx >= 0) {
            val code = content.substring(0, sepIdx)
            if (code == "0" || code == "2") {
                listener.onTitleChanged(content.substring(sepIdx + 1))
            }
        }
    }

    private fun writeChar(text: String) {
        if (cursorCol >= buffer.columns) {
            cursorCol = 0
            lineFeed()
        }
        buffer.setCell(
            cursorRow, cursorCol,
            TerminalBuffer.Cell(
                text = text, fg = curFg, bg = curBg,
                bold = curBold, underline = curUnderline,
                inverse = curInverse, italic = curItalic
            )
        )
        cursorCol++
    }

    private fun lineFeed() {
        if (cursorRow == scrollBottom) {
            if (scrollTop == 0 && scrollBottom == buffer.rows - 1) {
                buffer.scrollUp()
            } else {
                buffer.scrollRegionUp(scrollTop, scrollBottom, curBg)
            }
        } else if (cursorRow < buffer.rows - 1) {
            cursorRow++
        }
    }

    /** Reverse Index (ESC M) - the upward counterpart of [lineFeed]: moves
     *  the cursor up one line, or scrolls the region down if the cursor is
     *  already sitting at the top margin. */
    private fun reverseLineFeed() {
        if (cursorRow == scrollTop) {
            if (scrollTop == 0 && scrollBottom == buffer.rows - 1) {
                buffer.scrollDown(curBg)
            } else {
                buffer.scrollRegionDown(scrollTop, scrollBottom, curBg)
            }
        } else if (cursorRow > 0) {
            cursorRow--
        }
    }

    private fun insertLines(count: Int) {
        buffer.insertLines(cursorRow, scrollBottom, count, curBg)
    }

    private fun deleteLines(count: Int) {
        buffer.deleteLines(cursorRow, scrollBottom, count, curBg)
    }

    private fun deleteChars(count: Int) {
        buffer.deleteChars(cursorRow, cursorCol, count, curBg)
    }

    private fun insertChars(count: Int) {
        buffer.insertChars(cursorRow, cursorCol, count, curBg)
    }

    /** ECH (CSI X) - blank out `count` cells starting at the cursor, in
     *  place. Unlike deleteChars('P'), nothing to the right shifts left. */
    private fun eraseChars(count: Int) {
        val end = (cursorCol + count).coerceAtMost(buffer.columns)
        for (c in cursorCol until end) {
            buffer.setCell(cursorRow, c, TerminalBuffer.Cell(bg = curBg))
        }
    }

    // Settings > Terminal > "Clear always purges scrollback" (CLEAR_ALWAYS_PTY).
    // Off by default: `clear`/CSI 2J behaves like a normal terminal (content
    // just scrolls out of view, still reachable by scrolling up). On: CSI 2J
    // also wipes scrollback, so `clear` leaves truly nothing above the
    // screen - this is what "pty kalintilari" (leftover scrollback residue
    // still visible above a `clear`) was actually asking for: not a bug in
    // the erase logic itself, but `clear`'s CSI 2J never being wired to
    // scrollback at all. CSI 3J (explicit "clear scrollback", e.g. from
    // `clear -x` / tmux's clear-history) always purges scrollback regardless
    // of this setting, since that sequence's whole purpose is exactly that.
    var clearAlwaysPurgesScrollback: Boolean = false

    private fun eraseInDisplay(mode: Int) {
        when (mode) {
            0 -> { // cursor to end of screen
                eraseInLine(0)
                for (r in cursorRow + 1 until buffer.rows) buffer.clearRow(r, curBg)
            }
            1 -> { // start of screen to cursor
                for (r in 0 until cursorRow) buffer.clearRow(r, curBg)
                eraseInLine(1)
            }
            2 -> {
                buffer.clearAll(curBg)
                if (clearAlwaysPurgesScrollback) buffer.clearScrollback()
            }
            3 -> {
                // Real xterm semantics: 3J clears scrollback ONLY, leaving
                // the live screen's content untouched. Previously grouped
                // with mode 2 (full clearAll), which meant a `clear -x`/
                // tmux clear-history erased visible on-screen content it
                // has no business touching.
                //
                // Only honored while on the PRIMARY screen (not
                // buffer.inAlternateScreen) - there's no way to tell "the
                // user ran `clear`" apart from "an app fired 3J on its own"
                // from the byte stream alone, since both are the exact same
                // escape sequence over the same pty. But in practice a
                // user-typed `clear`/`clear -x` always runs in the shell,
                // i.e. on the primary screen. Full-screen programs (htop,
                // mc, some vim/tmux terminfo setups) that fire an
                // unsolicited 3J on their own do so from INSIDE alternate
                // screen (right around their own 1049h/l transition) -
                // which is exactly what was silently wiping all prior
                // shell scrollback just from opening one of those apps
                // ("eski terminal geçmişi siliniyor"). Restricting to the
                // primary screen filters that case out while still letting
                // a real `clear -x` typed at the shell prompt purge
                // scrollback (subject to clearAlwaysPurgesScrollback below,
                // same opt-in as CSI 2J).
                if (!buffer.inAlternateScreen && clearAlwaysPurgesScrollback) buffer.clearScrollback()
            }
        }
    }

    private fun eraseInLine(mode: Int) {
        when (mode) {
            0 -> for (c in cursorCol until buffer.columns) buffer.setCell(cursorRow, c, TerminalBuffer.Cell(bg = curBg))
            1 -> for (c in 0..cursorCol) buffer.setCell(cursorRow, c, TerminalBuffer.Cell(bg = curBg))
            2 -> buffer.clearRow(cursorRow, curBg)
        }
    }

    private fun applySgr(params: List<Int>) {
        if (params.isEmpty()) { resetSgr(); return }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetSgr()
                1 -> curBold = true
                3 -> curItalic = true
                4 -> curUnderline = true
                7 -> curInverse = true
                22 -> curBold = false
                23 -> curItalic = false
                24 -> curUnderline = false
                27 -> curInverse = false
                in 30..37 -> curFg = p - 30
                in 40..47 -> curBg = p - 40
                in 90..97 -> curFg = p - 90 + 8
                in 100..107 -> curBg = p - 100 + 8
                38, 48 -> {
                    // Extended color: 38;5;N (256-color) or 38;2;R;G;B (truecolor index-mapped)
                    if (i + 1 < params.size && params[i + 1] == 5 && i + 2 < params.size) {
                        val colorIdx = params[i + 2]
                        if (p == 38) curFg = colorIdx else curBg = colorIdx
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2 && i + 4 < params.size) {
                        // truecolor - stored as-is; UI layer maps to RGB directly via a side table
                        i += 4
                    }
                }
                39 -> curFg = TerminalBuffer.DEFAULT_FOREGROUND
                49 -> curBg = TerminalBuffer.DEFAULT_BACKGROUND
            }
            i++
        }
    }

    private fun resetSgr() {
        curFg = TerminalBuffer.DEFAULT_FOREGROUND
        curBg = TerminalBuffer.DEFAULT_BACKGROUND
        curBold = false
        curUnderline = false
        curInverse = false
        curItalic = false
    }

    private fun reset() {
        buffer.clearAll()
        cursorRow = 0
        cursorCol = 0
        resetSgr()
        scrollTop = 0
        scrollBottom = buffer.rows - 1
        cursorVisible = true
        applicationCursorKeys = false
        oscPendingSt = false
        state = State.NORMAL
    }

    fun getCursorRow() = cursorRow
    fun getCursorCol() = cursorCol

    /** Called when the underlying buffer is resized (e.g. on rotation) so
     *  the scrolling region doesn't keep referencing the old row count. */
    fun onBufferResized() {
        val wasFullScreen = scrollTop == 0 && scrollBottom >= 0
        scrollBottom = (buffer.rows - 1).coerceAtLeast(0)
        if (wasFullScreen) scrollTop = 0
        scrollTop = scrollTop.coerceIn(0, scrollBottom)
        cursorRow = cursorRow.coerceIn(0, buffer.rows - 1)
        cursorCol = cursorCol.coerceIn(0, buffer.columns - 1)
    }
}
