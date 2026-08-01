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

    /** Feed a chunk of decoded output text from the child process into the emulator. */
    fun append(text: CharSequence) {
        for (ch in text) {
            processChar(ch)
        }
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
            else -> writeChar(ch)
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
            else -> state = State.NORMAL
        }
    }

    private fun handleCsi(ch: Char) {
        if (ch.isDigit() || ch == ';' || ch == '?') {
            paramBuffer.append(ch)
            return
        }
        // Final byte reached - dispatch
        val raw = paramBuffer.toString()
        val private = raw.startsWith("?")
        val params = raw.removePrefix("?")
            .split(';')
            .mapNotNull { it.toIntOrNull() }

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
            'L' -> insertLines(params.getOrElse(0) { 1 }.coerceAtLeast(1))
            'M' -> deleteLines(params.getOrElse(0) { 1 }.coerceAtLeast(1))
            'P' -> deleteChars(params.getOrElse(0) { 1 }.coerceAtLeast(1))
            '@' -> insertChars(params.getOrElse(0) { 1 }.coerceAtLeast(1))
            // CHA/HPA - absolute column. VPA - absolute row. ECH - erase N
            // chars in place without shifting anything (unlike 'P'/'@').
            // These three are used constantly by nano and other ncurses
            // programs for cheap partial redraws; without them the cursor
            // just stayed wherever the last relative move left it, which is
            // what made those screens look garbled/misaligned.
            'G', '`' -> cursorCol = ((params.getOrElse(0) { 1 }) - 1).coerceIn(0, buffer.columns - 1)
            'd' -> cursorRow = ((params.getOrElse(0) { 1 }) - 1).coerceIn(0, buffer.rows - 1)
            'X' -> eraseChars(params.getOrElse(0) { 1 }.coerceAtLeast(1))
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
            'm' -> applySgr(params)
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
                // 1: application cursor keys, 2004: bracketed paste - not
                // implemented, but explicitly ignored now rather than
                // falling through to the generic (and wrong) non-private
                // h/l no-op.
                else -> { /* unsupported private mode - ignore */ }
            }
        }
    }

    /**
     * Touch/pointer event kinds a UI layer can report. Mirrors the subset
     * of xterm mouse-tracking button semantics that DECSET modes 1000/
     * 1002/1003 distinguish between.
     */
    enum class MouseEventKind { PRESS, RELEASE, DRAG, MOVE }

    /**
     * Encodes a touch at 0-indexed (col, row) into the escape sequence the
     * currently-running program expects, given whatever mouse mode it last
     * requested via DECSET - or returns null if nothing should be sent
     * (mouse reporting is off, or this event kind isn't reported under the
     * active mode - e.g. plain hover MOVE only goes out under 1003).
     *
     * button: 0=left, 1=middle, 2=right - only meaningful for PRESS/DRAG.
     */
    fun encodeMouseEvent(kind: MouseEventKind, col: Int, row: Int, button: Int = 0): String? {
        if (mouseMode == MouseMode.NONE) return null
        if (kind == MouseEventKind.MOVE && mouseMode != MouseMode.ANY_EVENT) return null
        if (kind == MouseEventKind.DRAG && mouseMode != MouseMode.BUTTON_EVENT && mouseMode != MouseMode.ANY_EVENT) return null
        if (kind == MouseEventKind.RELEASE && mouseMode == MouseMode.X10) return null // X10 never reports release

        // xterm mouse coordinates are 1-indexed from the top-left.
        val c = (col + 1).coerceIn(1, buffer.columns)
        val r = (row + 1).coerceIn(1, buffer.rows)

        val cb = when (kind) {
            MouseEventKind.PRESS -> button
            MouseEventKind.DRAG -> button or 32   // motion-while-pressed flag
            MouseEventKind.MOVE -> 3 or 32         // no button + motion flag
            MouseEventKind.RELEASE -> if (mouseSgrMode) button else 3 // legacy encoding has no distinct release button id
        }

        return if (mouseSgrMode) {
            // SGR (1006) extended encoding: CSI < cb ; col ; row M/m - the
            // final byte itself (M press/drag/move, m release) carries the
            // press/release distinction, so cb doesn't need the legacy
            // "release = 3" placeholder above.
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

    private fun handleOsc(ch: Char) {
        if (ch == '\u0007' || ch == '\u001B') {
            // OSC terminator (BEL or ST). Format we care about: "0;title" or "2;title"
            val content = oscBuffer.toString()
            val sepIdx = content.indexOf(';')
            if (sepIdx >= 0) {
                val code = content.substring(0, sepIdx)
                if (code == "0" || code == "2") {
                    listener.onTitleChanged(content.substring(sepIdx + 1))
                }
            }
            state = State.NORMAL
        } else {
            oscBuffer.append(ch)
        }
    }

    private fun writeChar(ch: Char) {
        if (cursorCol >= buffer.columns) {
            cursorCol = 0
            lineFeed()
        }
        buffer.setCell(
            cursorRow, cursorCol,
            TerminalBuffer.Cell(
                char = ch, fg = curFg, bg = curBg,
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
            2, 3 -> buffer.clearAll(curBg)
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
