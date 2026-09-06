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
        // OSC 52 clipboard-set ("OSC 52 ; c ; <base64> ST") - fired with
        // the raw, still-base64-encoded payload exactly as it arrived (see
        // finishOsc's own doc for why decoding isn't done in this module).
        // The caller (an Android-side listener) decodes it and writes it to
        // the system clipboard.
        fun onClipboardSet(base64Data: String)
        // OSC 52 clipboard-GET ("OSC 52 ; c ; ?") - fired when a program
        // asks to read the system clipboard back through the pty. `selection`
        // is the raw selection-buffer letter from the sequence ('c', 'p',
        // 's', ...) exactly as sent, unvalidated - see finishOsc's own doc
        // for why answering this at all is a real info-leak surface (any
        // command in this shell, a remote ssh session, or another tmux pane
        // could otherwise silently exfiltrate clipboard contents). The
        // caller is expected to gate this behind an explicit user opt-in
        // (Settings > Terminal Behaviour > Allow OSC 52 clipboard reads,
        // default off - same posture as xterm's own allowWindowOps) and,
        // only if that's on, read the system clipboard, base64-encode it,
        // and write the full "OSC 52 ; c ; <base64> ST" reply back via
        // onRespond - this module doesn't build that reply itself since
        // doing so would mean either always answering (the very leak this
        // exists to prevent) or this module reaching into a
        // platform-specific settings store to decide, which breaks its
        // no-android-imports rule same as onClipboardSet's decoding does.
        // When the setting is off, the caller simply does nothing and the
        // request silently times out on the requesting program's side,
        // identical to today's blanket-ignore behavior.
        fun onClipboardGet(selection: String)
        // OSC 133 shell-integration mark - fired once per A/B/C/D marker a
        // shell-integration-aware prompt (starship, fish, some zsh setups)
        // sends. `marker` is 'A'/'B'/'C'/'D', `row` is where it landed,
        // `exitCode` is only non-null for a 'D' mark that included one
        // (";<code>"). Purely informational from this module's side - it
        // hands the mark over without assuming what, if anything, the UI
        // layer does with it (e.g. jump-to-previous-command navigation).
        fun onShellIntegrationMark(marker: Char, row: Int, exitCode: Int?)

        // OSC 4/10/11/12 QUERY ("OSC 4 ; <slot> ; ? ST" / "OSC 10 ; ? ST" /
        // etc.) - a program (vim/neovim detecting a light-vs-dark
        // background, tmux, some prompt themes) asking "what color IS
        // this right now?" rather than setting one. [slot] is the OSC 4
        // palette index (0-255) for a code=4 query, or one of
        // TerminalBuffer.DYNAMIC_COLOR_FOREGROUND/BACKGROUND/CURSOR for a
        // 10/11/12 query (mirrors how TerminalBuffer's own override maps
        // are keyed - see that class's own doc for why 4 and 10/11/12
        // deliberately use separate map/key spaces despite both being
        // "a color slot number" conceptually). [isDynamic] disambiguates
        // which of the two maps [slot] indexes into, since a raw Int
        // alone can't (OSC 4's slot 10 and OSC 10's dynamic-foreground
        // slot both happen to be the number 10, entirely coincidentally,
        // per TerminalBuffer's DYNAMIC_COLOR_FOREGROUND = 10 choice).
        //
        // This module can't answer the query itself - see TerminalBuffer.
        // paletteOverrides' own doc on why the LIVE, currently-active
        // palette (Settings > Theme, Material dynamic colors, etc.) only
        // exists on the Compose/UI side, not in this no-android-imports
        // module. The callee is expected to: first check
        // TerminalBuffer.getPaletteOverride/getDynamicColorOverride for
        // [slot] (a prior OSC 4/10/11/12 SET always wins over the theme
        // default, matching real xterm), and if that's null, resolve
        // [slot] against whatever TerminalPalette is currently active and
        // reply over the pty via onRespond with the spec's own
        // "OSC <code> ; rgb:RRRR/GGGG/BBBB ST" format (note: 4-hex-digit
        // per-channel, not 2 - see xterm's ctlseqs doc; a plain 2-digit
        // 0-255 value RR is scaled up, e.g. 0xFF -> "ffff", by repeating
        // the byte, same as xterm's own convention for reporting 8-bit
        // channels at this 16-bit-per-channel wire format).
        fun onQueryDynamicColor(code: Int, slot: Int, isDynamic: Boolean)

        // OSC 7 ("OSC 7 ; file://<host>/<path> ST") - a shell-integration-
        // aware prompt (same family as OSC 133 - see onShellIntegrationMark)
        // reporting the shell's current working directory. [path] is the
        // decoded filesystem path only (the "file://<host>" prefix and any
        // percent-encoding are already stripped/decoded - see finishOsc's
        // own "7" branch for why parsing happens in this module rather
        // than pushing raw OSC text out to the listener the way OSC 52's
        // still-encoded base64 payload does: unlike OSC 52's payload,
        // which the caller must decode ANYWAY to act on it and might want
        // the exact original bytes of, OSC 7's only ever-useful form to a
        // caller IS the plain path, so there's no reason to make every
        // listener re-implement the same URI unwrapping). Purely
        // informational, same posture as onShellIntegrationMark - a UI
        // layer CAN use this for "open new session/pane in the same
        // directory" without this module needing any opinion on that
        // feature.
        fun onWorkingDirectoryChanged(path: String)

        // DECCOLM ("CSI ?3h"/"CSI ?3l") - switch the terminal's column
        // count to 132 ([columns]=132) or back to 80 ([columns]=80), per
        // spec. Only ever fired with one of those two values, matching
        // DECCOLM's own fixed two-mode nature (unlike an arbitrary resize,
        // there's no "DECCOLM to N columns" - it's always exactly 80 or
        // 132). Routed through the listener rather than done directly
        // against [buffer] here because an actual column-count change has
        // to reach the PTY itself (TIOCSWINSZ/SIGWINCH so the running
        // program's own idea of the terminal size stays in sync - see
        // TerminalSession.resize's own doc) and TerminalEmulator has no
        // reference to the TerminalSession that owns that ioctl, only to
        // [buffer] and this listener - same "the thing that needs doing
        // lives on the other side of a boundary this module doesn't cross"
        // reasoning as onRespond/onClipboardSet above. The callee is
        // expected to call its own TerminalSession.resize(columns, rows)
        // with the CURRENT row count unchanged (DECCOLM only ever affects
        // width) - on a phone-sized layout where 132 columns would just
        // render illegibly tiny or get silently clamped by the view's own
        // layout math, the callee is free to ignore this (or clamp it) per
        // Settings/screen-size, same tolerance this codebase already
        // extends to other "the terminal asked for something the host UI
        // can't honor" cases (e.g. kittyAnchor's own pixel-size fallback).
        fun onDeccolmChanged(columns: Int)

        // OSC 1 ("OSC 1 ; <icon name> ST") - sets the window/task "icon
        // name" distinctly from OSC 0/2's window TITLE (onTitleChanged
        // above). Real xterm keeps these as two separate strings (icon
        // name historically labelled a minimized/iconified window, title
        // labelled the full window chrome) - some programs (screen/tmux
        // outside of this app, vim's own title-setting, a few prompt
        // frameworks) set one without the other, or set them to
        // deliberately different text, so collapsing this into
        // onTitleChanged would silently lose whichever one arrives second.
        // Purely informational, same posture as onWorkingDirectoryChanged -
        // this module has no opinion on whether/where a mobile UI surfaces
        // an "icon name" (there's no taskbar icon to relabel here), it just
        // hands the string over.
        fun onIconNameChanged(name: String)

        // OSC 1337 ("OSC 1337 ; File=[key=value;...]:<base64> ST") -
        // iTerm2's inline image protocol. Only the "display it in the
        // stream, right where the cursor is" case (inline=1) is
        // surfaced here at all - see finishOsc's own doc on why a
        // File= WITHOUT inline=1 (a plain "offer this as a downloadable
        // attachment" request, iTerm2's original use before inline
        // display existed) is silently dropped rather than reaching
        // this callback: there's no download-manager surface on a
        // mobile terminal to hand it to. [base64Data] is the still-
        // encoded payload exactly as it arrived on the wire (same
        // "decoding needs android.graphics, which this platform-
        // agnostic module can't import" boundary as onClipboardSet's
        // own base64Data) - actual PNG/JPEG/GIF decoding happens on the
        // caller's side, which is then expected to call back into
        // [placeDecodedInlineImage] with the decoded pixels once it has
        // them - NOT to reach into [buffer].placeImage directly, see
        // that function's own doc for why. [widthSpec]/[heightSpec] are
        // the raw, unparsed width=/height= values from the control
        // string (e.g. "auto", "50%", "10", or null if the key was
        // absent) - iTerm2 defines these as accepting a bare cell
        // count, a "Npx" pixel count, an "N%" percentage of the
        // viewport, or "auto"; resolving that into an actual cell-grid
        // size needs the caller's own character-cell metrics (same
        // reason kittyAnchor's pixel-size fallback lives on the caller
        // side too), which this module has no access to - accepted here
        // purely so a future caller CAN act on them, not because
        // [placeDecodedInlineImage] itself does anything with them today
        // (it places at native pixel size, same as plain Sixel).
        fun onInlineImageData(base64Data: String, widthSpec: String?, heightSpec: String?)

        // OSC 9 ("OSC 9 ; <message> ST", iTerm2/Growl-style) and OSC 777
        // ("OSC 777 ; notify ; <title> ; <body> ST", rxvt-unicode/urxvt's
        // own convention) - both are "pop a system notification" requests
        // a long-running background command (a build, a download, `notify-
        // send`-alike wrappers some shells alias onto) sends so the user
        // finds out it finished without having to keep the terminal in the
        // foreground. Normalized to one callback shape here despite the
        // two wire formats being shaped differently (OSC 9 has no separate
        // title - only a body; OSC 777 always has both) so the caller only
        // ever has to handle one thing: [title] is null for a bare OSC 9
        // (the caller is expected to fall back to something like the
        // session's own tab name in that case, same way a real desktop
        // notification needs SOME title), [body] is always the actual
        // message text. Purely informational/request-shaped exactly like
        // onClipboardGet's own posture - this module has no idea whether
        // the user has actually granted notification permission or wants
        // to be interrupted at all, so the caller is expected to gate this
        // behind its own explicit opt-in (Settings > Notifications) before
        // ever posting a real system notification, rather than this module
        // assuming every OSC 9/777 sender should always get through.
        fun onNotification(title: String?, body: String)
    }

    /**
     * One OSC 133 mark, captured with enough coordinate context to still be
     * locatable after arbitrarily more output has scrolled the line it sits
     * on into (and eventually out of) scrollback - a raw live-grid row alone
     * would go stale the moment a single further line printed.
     *
     * `scrollbackSizeAtMark` is `buffer.scrollback.size` at the instant this
     * mark fired, and `row` is the live-grid row it landed on then. Together
     * they pin the line's absolute position in the ever-growing scrollback
     * stream as `scrollbackSizeAtMark + row` - the same "N lines back from
     * the bottom" addressing TerminalBuffer.lineAt already uses (see its own
     * doc), just expressed as an absolute offset instead of a live-relative
     * one. [scrollOffsetFor] turns that back into a scrollOffset against
     * whatever the buffer's scrollback.size has grown to by the time the
     * user actually jumps to it - naturally returning a clamped/stale result
     * once the line has fallen out of the capped scrollback entirely,
     * exactly the same "can't jump to what's no longer kept" behavior
     * MAX_SCROLLBACK_LINES already imposes on manual scrolling.
     */
    data class ShellIntegrationMark(
        val marker: Char,
        val row: Int,
        val scrollbackSizeAtMark: Int,
        val exitCode: Int?
    ) {
        /** The scrollOffset that puts this mark's line at the same on-screen
         *  position it had when it fired, against the buffer's CURRENT
         *  scrollback size - or null if the line has already scrolled out of
         *  the capped scrollback and is no longer reachable at all. */
        fun scrollOffsetFor(buffer: TerminalBuffer): Int? {
            val absolutePos = scrollbackSizeAtMark + row
            val currentScrollbackSize = buffer.scrollback.size
            val offset = currentScrollbackSize - absolutePos
            if (offset > buffer.maxScrollOffset) return null // evicted from scrollback
            return offset.coerceAtLeast(0)
        }
    }

    // Every OSC 133 'A' (prompt-start) mark seen so far, oldest first -
    // these are what jump-to-previous/next-command navigation steps
    // between. Only 'A' marks are kept (not B/C/D) since "jump to the
    // previous command" means "jump to the previous prompt", matching what
    // shells with integration (starship, fish, zsh) actually emit one of
    // per command line. Capped at MAX_PROMPT_MARKS (see the other
    // companion object below) - a mark whose line has already scrolled out
    // of scrollback is unreachable anyway (see scrollOffsetFor), so keeping
    // unbounded marks around forever would just leak memory for entries
    // jump navigation can never actually use.
    private val promptMarks = ArrayDeque<ShellIntegrationMark>()

    /** Read-only snapshot of every prompt-start mark still tracked, oldest
     *  first - used by jump-to-previous/next-command navigation to find the
     *  mark nearest a given scrollOffset. */
    fun promptMarksSnapshot(): List<ShellIntegrationMark> = promptMarks.toList()

    /**
     * The scrollOffset to jump to for "previous command" / "next command"
     * navigation from the CURRENT scrollOffset, or null if there is no mark
     * in that direction (already at the oldest/newest prompt, or no marks
     * at all yet).
     *
     * "Previous" means further back in history (a larger scrollOffset);
     * "next" means further forward toward live (a smaller one) - matching
     * how scrollOffset itself is defined (0 = live screen, see its own doc
     * on MainUiState). Marks whose line has since scrolled out of the
     * capped scrollback (scrollOffsetFor returning null) are skipped rather
     * than stopping the walk - one evicted prompt shouldn't make every
     * older-still prompt beyond it unreachable too.
     */
    fun findAdjacentPromptMark(buffer: TerminalBuffer, currentScrollOffset: Int, forward: Boolean): Int? {
        val candidates = if (forward) promptMarks.asReversed() else promptMarks
        for (mark in candidates) {
            val offset = mark.scrollOffsetFor(buffer) ?: continue
            if (forward) {
                if (offset < currentScrollOffset) return offset
            } else {
                if (offset > currentScrollOffset) return offset
            }
        }
        return null
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

    // DECKPAM ("ESC =") / DECKPNM ("ESC >") - the numeric-keypad analog of
    // DECCKM just above: while true, a real terminal's numpad keys send
    // application-mode SS3 sequences (\EOM for Enter, \EOj for '*', etc.)
    // instead of their plain digit/operator characters, the same
    // "structurally different encoding while a full-screen program has
    // asked for it" split DECCKM already models for arrow keys. Tracked
    // here purely as parser state - like applicationCursorKeys, the actual
    // key-ENCODING side belongs to the UI layer reading this flag, not to
    // this receive-only escape-sequence parser (see this class's own
    // kittySequenceFor-adjacent doc on why key-input encoding stays out of
    // this file entirely). vi/vim toggle this via ncurses' keypad(TRUE)/
    // (FALSE) as part of the same terminal-mode setup DECCKM already
    // covers; previously ESC =/> both fell to handleEscape's generic
    // unsupported-byte `else` (silently dropped, state reset to NORMAL) -
    // harmless on its own since nothing consumed the flag either way, but
    // left no state here for a UI layer to ever read if it later wanted to
    // encode numpad keys correctly the way arrow keys already are.
    var applicationKeypadMode: Boolean = false
        private set

    /**
     * Kitty keyboard protocol progressive-enhancement flags, as a bitmask:
     * bit 0 (1) = disambiguate escape codes, bit 1 (2) = report event types
     * (press/repeat/release), bit 2 (4) = report alternate keys, bit 3 (8)
     * = report all keys as escape codes, bit 4 (16) = report associated
     * text. See handleKittyKeyboardProtocol's own doc for how push/pop/set
     * mutate this via the CSI-u control forms, and PhysicalKeyEvent/
     * VirtualKeyBar's own key-encoding for how a non-zero value here
     * changes what a keypress actually sends.
     */
    var kittyKeyboardFlags: Int = 0
        private set

    // Stack kitty PUSH ("CSI > flags u")/POP ("CSI < count u") push onto
    // and pop from - a program that enables its own enhancements (e.g. a
    // full-screen editor wanting disambiguated escape codes) is expected to
    // push its flags on entry and pop back on exit, restoring whatever the
    // shell/previous program had rather than clobbering it outright. Kept
    // separate from kittyKeyboardFlags itself (the ACTIVE value) the same
    // way a real terminal keeps the two separate - kittyKeyboardFlags is
    // always what's currently in effect; this is only consulted on POP.
    private val kittyFlagStack = ArrayDeque<Int>()

    // XTWINOPS ("CSI Ps ; Ps ; Ps t") window-title push/pop stack - see
    // handleCsi's own 't' case doc. TerminalEmulator itself only ever
    // FIRES onTitleChanged/onIconNameChanged forward (see those callbacks'
    // own doc) rather than keeping the current title/icon as state of its
    // own - correct for the plain OSC 0/1/2 SET case, which never needs to
    // read a title back - but PUSH (Ps=22) needs exactly that: something
    // to push. currentTitle/currentIconName mirror whatever was last SET
    // via OSC purely so PUSH has a value to save; POP (Ps=23) restores
    // from here the same way kittyFlagStack's own POP restores
    // kittyKeyboardFlags. Same 64-deep bound as kittyFlagStack for the
    // same reason (a program that pushes without ever popping - e.g.
    // crashes mid-session - shouldn't grow this unboundedly over a long-
    // lived session).
    private var currentTitle: String = ""
    private var currentIconName: String = ""
    private val titleStack = ArrayDeque<Pair<String, String>>() // (icon, title)

    // DECSET/DECRST 2004 (bracketed paste). When true, the running program
    // has asked to be told which input came from a paste vs. real typing -
    // the UI layer (TerminalSession.writePaste) wraps pasted text in
    // ESC[200~...ESC[201~ only while this is true, exactly mirroring what a
    // real xterm/VTE does. Off by default and reset on RIS (see reset()),
    // same as every other DECSET-driven mode here.
    var bracketedPasteMode: Boolean = false
        private set

    // DECSET/DECRST 7 (DECAWM, auto-wrap mode). True (xterm's own default)
    // means writeChar's existing "hit right margin -> wrap to next line"
    // behavior applies as before; false means a program that explicitly
    // asked NOT to wrap (some progress bars/status lines use `tput rmam` /
    // ESC[?7l specifically so a too-long line overwrites itself in place
    // instead of pushing the display down a line) gets that instead -
    // writeChar simply stops advancing past the last column rather than
    // wrapping. Previously this DECSET code fell through to the generic
    // "unsupported private mode - ignore" else branch, so auto-wrap was
    // unconditionally forced on regardless of what the program asked for.
    var autoWrapMode: Boolean = true
        private set

    // DECSET/DECRST 3 (DECCOLM, 80/132-column mode). Tracked purely so
    // DECRQM ("CSI ?3$p") can answer truthfully what the last DECCOLM
    // request set - the ACTUAL resize (see onDeccolmChanged's own doc)
    // happens on the listener/TerminalSession side, which this module has
    // no read-back access to, so this flag is this module's own
    // best-effort memory of what it last asked for rather than a query of
    // the real current column count.
    var deccolm132Mode: Boolean = false
        private set

    // DECSET/DECRST 1004 (focus reporting). When true, the running program
    // (vim's autoread-on-focus, some fzf/tmux integrations) has asked to be
    // told when this terminal gains/loses input focus, reported as
    // "ESC[I" (focus in) / "ESC[O" (focus out) via the same onRespond
    // channel DSR/CPR already uses. The UI layer (TerminatorApp/MainActivity
    // lifecycle callbacks) is expected to call [reportFocusChange] on real
    // focus transitions; this module only tracks whether the running
    // program actually wants those reports, same as every other DECSET-
    // driven mode here.
    var focusReportingMode: Boolean = false
        private set

    // DECSET/DECRST 2026 (synchronized output, https://gist.github.com/christianparpart/d8a62cc1ab659194337d73e63790c46e).
    // A modern multiplexer/editor (tmux >=3.3, neovim, kitty, helix) wraps a
    // full-screen redraw in "ESC[?2026h ... ESC[?2026l" so a renderer CAN
    // defer painting until the matching 'l' arrives instead of drawing every
    // intermediate frame - without this, a big redraw can flash partially-
    // drawn content for one composition frame ("tearing"). Purely advisory:
    // this module just tracks the flag and exposes it via
    // [inSynchronizedUpdate] so the UI layer's own render loop can choose to
    // skip a repaint while true; nothing here withholds buffer writes based
    // on it; a program that never asked for this keeps rendering exactly as
    // before (default false = always paint immediately, same as today).
    var inSynchronizedUpdate: Boolean = false
        private set

    // G0/G1 designated character sets (SCS, "ESC ( X" / "ESC ) X") and
    // which of the two is currently invoked (SI/SO, \u000F/\u000E) - see
    // writeChar's own DEC-special-graphics translation for why this
    // matters. true = DEC special graphics (line-drawing: q/x/l/k/m/j/etc.
    // map to box-drawing Unicode instead of printing literally), false =
    // plain ASCII/UK (no translation - the overwhelming common case).
    // Defaults false/false (G0=ASCII, G1=ASCII) matching a freshly-reset
    // real terminal; RIS (see reset()) puts both back here too. Previously
    // the designator byte following '('/')' was consumed and unconditionally
    // dropped with no effect at all - a program relying on the classic
    // VT100 line-drawing charset (rather than writing UTF-8 box-drawing
    // characters directly, which most modern TUIs do instead) rendered its
    // borders as literal "qqqx" text instead of ─│┐ etc.
    private var g0IsSpecialGraphics = false
    private var g1IsSpecialGraphics = false
    // G2/G3 designated character sets (SCS, "ESC * X" / "ESC + X") - the
    // other half of the four-way G0/G1/G2/G3 designation set VT220 and
    // later terminals support, alongside G0/G1 above. Real-world use is
    // rare (G0/G1 with SI/SO cover the overwhelming majority of classic
    // line-drawing programs) but genuinely exists: LS2/LS3 (ESC n / ESC o,
    // see lockingGSet's own doc) invoke these instead of G0/G1 for the
    // remainder of a session, and SS2/SS3 (ESC N / ESC O) invoke one of
    // them for just the single next character. Same true/false meaning as
    // g0.../g1IsSpecialGraphics (true = DEC special graphics line-drawing,
    // false = plain ASCII/UK) and same "only '0' is modeled as special
    // graphics" simplification - see State.CHARSET's own doc.
    private var g2IsSpecialGraphics = false
    private var g3IsSpecialGraphics = false
    // Which designator ('(' G0 / ')' G1 / '*' G2 / '+' G3) the CHARSET
    // state is currently waiting on the follow-up byte for - set the
    // instant one of those four bytes is seen (see handleEscape's own
    // '(' /')' /'*' /'+' cases), read the instant State.CHARSET consumes
    // the follow-up byte (see processChar's own CHARSET branch).
    private enum class CharsetSlot { G0, G1, G2, G3 }
    private var pendingCharsetSlot = CharsetSlot.G0

    // Which of g0/g1/g2/g3IsSpecialGraphics above is LOCKED-shift invoked
    // (stays in effect until the next SI/SO/LS2/LS3, as opposed to SS2/
    // SS3's own one-character-only invocation - see singleShiftGSet's own
    // doc for that) - SI (\u000F) locks in G0, SO (\u000E) locks in G1,
    // LS2 (ESC n) locks in G2, LS3 (ESC o) locks in G3. Most shells/
    // programs only ever touch G0 and never send any of these at all, so
    // this stays G0 forever for them - the others only matter for the
    // minority of classic ncurses builds that alternate between charsets
    // for line-drawing.
    private var lockingGSet = CharsetSlot.G0
    // SS2/SS3 (ESC N / ESC O) - invoke G2 or G3 for JUST the next single
    // character, then automatically revert to whatever lockingGSet was
    // already selected (unlike LS2/LS3 above, which change lockingGSet
    // itself). Null when no single-shift is pending (the overwhelming
    // common case - real senders that use G2/G3 at all almost always do
    // so via the locking LS2/LS3 form instead). Consumed and cleared by
    // writeChar's own charset-translation lookup the instant the next
    // character is written, matching a real terminal's "shift applies to
    // exactly one graphic character" semantics.
    private var singleShiftGSet: CharsetSlot? = null

    // Current SGR (graphic rendition) state, applied to newly written cells
    private var curFg = TerminalBuffer.DEFAULT_FOREGROUND
    private var curBg = TerminalBuffer.DEFAULT_BACKGROUND
    private var curBold = false
    private var curUnderline = false
    private var curInverse = false
    private var curItalic = false
    private var curDim = false
    private var curBlink = false
    private var curStrikethrough = false
    // SGR 8 (conceal) / 28 (reveal, off). Real terminals still "write" the
    // character (selection/copy still yields the real text - see
    // TerminalView's conceal rendering, which hides the glyph by matching
    // its color to the background rather than skipping the drawText call
    // outright), only the visible glyph is suppressed.
    private var curConceal = false
    // SGR 53 (overline) / 55 (off) - a line drawn above the glyph rather
    // than below it (underline) or through it (strikethrough). Rare outside
    // spell-checkers/diff tools that use it to mark a distinct third kind
    // of "decorated" text, but xterm/VTE both support it and some
    // TUIs (e.g. certain vim colorschemes) rely on it being real rather
    // than silently downgrading to no decoration at all.
    private var curOverline = false
    // SGR 4:3 (curly/"undercurl") - VS Code/kitty/iTerm2's extension for
    // spell-check-style squiggly underlines, distinct from the plain
    // straight line SGR 4 (or bare "4:1") draws. Only the colon SUB-
    // parameter form distinguishes this from a plain underline (see
    // handleCsi's `underlineSubStyle` extraction, since the generic params
    // list flattens "4:3" down to a bare 4 otherwise) - modeled as a
    // separate flag rather than an enum since curUnderline already tracks
    // "is there a decoration at all" and this only ever refines its shape.
    private var curUnderlineCurly = false
    // SGR 58 (set underline color, 58;5;N or 58;2;R;G;B) / 59 (reset to
    // match the text's own foreground, the historical default every
    // terminal used before this extension existed). Null means "no
    // explicit underline color set - use the glyph's own resolved
    // foreground", matching curHyperlink's own null-means-default
    // convention elsewhere in this class. Packed the same way curFg/curBg
    // are (a plain 0-255 palette index, or TerminalPalette.TRUECOLOR_MARKER-
    // tagged RGB) so TerminalView can resolve it through the exact same
    // palette.resolve() path.
    private var curUnderlineColor: Int? = null
    // OSC 8 hyperlink URI currently "open" - set by an "OSC 8 ; ; <uri> ST"
    // and attached to every cell written until the matching "OSC 8 ; ; ST"
    // (empty URI) closes it, exactly mirroring how curFg/curBold/etc. above
    // apply to every subsequent writeChar() until their own SGR changes
    // them. Real programs (ls --hyperlink, git log, eza, fd) wrap the
    // *visible text* of a link between these two OSC 8s, not just the URI
    // itself - the visible text has no special marking of its own, it's
    // just plain characters written while this happens to be non-null.
    private var curHyperlink: String? = null

    // The last actual glyph writeChar drew (post DEC-special-graphics
    // translation, i.e. exactly what landed in the cell - see writeChar's
    // own `effectiveText`), for REP ("CSI Pn b") to repeat - see handleCsi's
    // 'b' case. xterm-256color's own compiled terminfo entry (this app's
    // own assets/terminfo/x/xterm-256color) advertises `rep`, so ncurses-
    // based full-screen programs (vim, tmux, htop, mc, less) actively rely
    // on the terminal implementing this rather than falling back to writing
    // the run out longhand - before this was tracked, "CSI Pn b" silently
    // matched the generic unsupported-final-byte `else` case, so every
    // repeated-character run one of those programs sent this way (a
    // horizontal rule, a status-bar border, a progress bar's fill) simply
    // never appeared: not drawn wrong, just entirely missing from the
    // screen. Null until the first ordinary character is written, and
    // reset back to null on a full reset (see reset()'s own doc) - a
    // REP with nothing preceding it (or right after RIS wiped the screen)
    // is a no-op rather than repeating stale content from before the reset.
    private var lastGraphicChar: String? = null

    // Scrolling region (DECSTBM, CSI r) - top/bottom are 0-indexed and
    // inclusive. Defaults to the full screen. TUI apps commonly pin a
    // status/title line outside this region and scroll only the rest.
    private var scrollTop = 0
    private var scrollBottom = buffer.rows - 1

    // DECSLRM (left/right margins, "CSI Pl ; Pr s") - the horizontal
    // counterpart to scrollTop/scrollBottom above. Only settable while
    // DECLRMM ("CSI ?69h") is enabled (see declrmmEnabled's own doc) -
    // when it's off, the exact same "CSI ... s" final byte is instead the
    // long-standing ANSI.SYS save-cursor sequence (see handleCsi's 's'
    // case), which is why this can't simply always parse Pl;Pr the way
    // DECSTBM's 'r' unconditionally does for scrollTop/scrollBottom.
    // 0-indexed and inclusive, same convention as scrollTop/scrollBottom.
    private var scrollLeft = 0
    private var scrollRight = buffer.columns - 1
    // DECLRMM ("CSI ?69h"/"CSI ?69l") - whether DECSLRM's margins are even
    // settable right now. Off by default (real xterm's own default too):
    // most programs never touch this, and a stray/malformed "CSI ... s"
    // from something else entirely must keep meaning save-cursor, not
    // silently start being reinterpreted as a margin-set once some
    // unrelated prior program happened to leave DECLRMM on.
    private var declrmmEnabled = false

    // DECOM (Origin Mode, "CSI ?6 h/l") - see handlePrivateMode's own case
    // and the 'H'/'f' CUP case's own doc for how this actually changes
    // cursor addressing. Off by default (xterm's own default) - CUP is
    // absolute-to-the-whole-screen until a program explicitly opts into
    // margin-relative addressing.
    private var originMode = false

    // IRM (Insert Mode, ANSI "CSI 4 h/l" - NOT a DEC-private mode, so this
    // is one of the few non-private SM/RM sequences this emulator actually
    // tracks rather than folding into the blanket "non-private mode set/
    // reset we don't track" ignore). While true, writeChar shifts the rest
    // of the row one column right before placing the new glyph instead of
    // overwriting whatever was already at the cursor - real terminals'
    // "insert" behavior, as opposed to the default "replace" (REPLACE
    // MODE) behavior every other write in this file already does. Off by
    // default, matching xterm.
    private var insertMode = false

    // LNM (Line Feed/New Line Mode, ANSI "CSI 20 h/l") - whether a bare
    // LF/VT/FF (see lineFeed's own call sites: '\n', '\u000B', '\u000C' in
    // handleNormal) ALSO returns the cursor to column 0, the same as if a
    // literal '\r' had preceded it. Off (the default, matching xterm) is
    // the overwhelmingly common real-world state: virtually every modern
    // program/shell/PTY layer already sends its own explicit "\r\n" and
    // relies on the terminal NOT adding a second implicit CR - turning
    // this on would double-indent every line for them. On (some legacy
    // DOS-descended or ancient Unix tools toggle it) makes a bare LF alone
    // behave like the DOS/CRLF convention. See lineFeed's own use of this
    // flag for where the actual column reset happens.
    private var lineFeedNewLineMode = false

    // SRM (Send/Receive Mode a.k.a. "local echo", ANSI "CSI 12 h/l") -
    // when ON (enable=true, i.e. "local echo IS active"), a program has
    // asked the terminal to display every character locally as it's typed
    // instead of waiting for the host to echo it back over the pty. This
    // emulator has no local keystroke-echo path at all (the pty's own
    // line discipline / the remote program already handles echo, the same
    // as every other terminal-emulator-over-a-real-pty setup) - there is
    // nothing to actually toggle, but the mode is still tracked (rather
    // than silently folded into the blanket ignore) purely so DECRQM can
    // truthfully answer "is SRM set?" instead of claiming "not modeled"
    // for a mode this emulator quite reasonably has no visible behavior
    // for either way. Per spec's own naming this is inverted from what it
    // sounds like: SRM "set" (CSI 12h) means echo is OFF (host does it),
    // SRM "reset" (CSI 12l) means echo is ON (terminal does it) - xterm's
    // own default is reset (local echo off, matching every real remote-
    // shell setup), so this starts false to match.
    private var sendReceiveMode = false

    // DECARM (Auto Repeat Mode, "CSI ?8 h/l") - whether a physically-held
    // key should auto-repeat. This emulator receives already-composed
    // key events from Android's own IME/hardware-keyboard stack, which
    // has its own OS-level key-repeat handling entirely outside this
    // terminal's control - there is no "held key" concept at this layer
    // to suppress repeats on, the same reasoning as sendReceiveMode just
    // above. Tracked anyway so DECRQM can answer truthfully rather than
    // claiming "not modeled". On by default (xterm's own default - most
    // real terminals ship with auto-repeat enabled out of the box).
    private var autoRepeatMode = true

    // DECSC/DECRC and ANSI.SYS-style CSI s/u cursor save-restore state.
    private var savedCursorRow = 0
    private var savedCursorCol = 0

    // Cursor visibility (CSI ?25h/l) - surfaced for the UI layer to hide the
    // caret while a program has explicitly turned it off (e.g. during a
    // full-screen redraw).
    var cursorVisible = true
        private set(value) { field = value; buffer.cursorVisible = value }

    enum class CursorStyle { BLOCK, UNDERLINE, BAR }

    // Cursor shape (DECSCUSR, "CSI Ps SP q") - vim's insert-mode thin "bar"
    // caret, tmux/kitty's underline mode, etc. Mirrors cursorVisible's own
    // pattern (own field here, pushed onto buffer's copy on every write) so
    // TerminalView's draw pass can read it from the same locked
    // cursorSnapshot() it already reads position/visibility from, rather
    // than a second unsynchronized field access.
    var cursorStyle = CursorStyle.BLOCK
        private set(value) { field = value; buffer.cursorStyle = value }

    // Custom tab stops (HTS / ESC H adds one at the cursor column; CSI g /
    // TBC removes them). Starts null, meaning "no program has touched tab
    // stops yet - use the plain every-8th-column default this emulator
    // always had". The first HTS or TBC call materializes the full default
    // set into this field (via ensureTabStops()) so that adding one custom
    // stop keeps every other default stop intact, and clearing a program's
    // own stops (TBC Ps=3) doesn't silently resurrect the defaults on the
    // next tab. Kept as a set rather than a fixed-size array so it never
    // needs reallocating on resize - see onBufferResized()'s trim below.
    private var tabStops: MutableSet<Int>? = null

    // LINE tab stops (TBC Ps=1 clears the one at the current line, Ps=2
    // clears all of them) - the vertical-tabbing analogue of `tabStops`
    // above, for programs that use VT/FF-driven line tabulation rather
    // than horizontal character tabulation. No SET counterpart is modeled
    // (real VT420s expose one via VTS/DECST8C-family sequences that no
    // shell/CLI tool in ordinary use actually sends - unlike HTS, which
    // ncurses-based full-screen apps use constantly), so this stays null
    // (meaning "nothing to clear") unless something else ever populates
    // it; TBC 1/2 at least stop silently discarding the request instead of
    // falling into the "not modeled" `else` case every other Ps value
    // still does.
    private var lineTabStops: MutableSet<Int>? = null

    private fun defaultTabStops(): MutableSet<Int> =
        (0 until buffer.columns step 8).toMutableSet()

    private fun ensureTabStops(): MutableSet<Int> {
        if (tabStops == null) tabStops = defaultTabStops()
        return tabStops!!
    }

    /** Column the cursor lands on after a horizontal-tab (\t) from [from],
     *  honoring any custom stops a program has set/cleared via HTS/TBC. */
    private fun nextTabStop(from: Int): Int {
        val stops = tabStops
        val next = if (stops == null) {
            // Untouched by HTS/TBC - the original fixed every-8 behavior.
            ((from / 8) + 1) * 8
        } else {
            // No stop past the cursor (either past the last default, or
            // TBC cleared everything to its right) - real terminals stop
            // at the right margin in that case rather than wrapping.
            stops.filter { it > from }.minOrNull() ?: buffer.columns
        }
        return next.coerceAtMost((buffer.columns - 1).coerceAtLeast(0))
    }

    /** Mirror of [nextTabStop] for CBT ("CSI Pn Z") - the column the
     *  cursor lands on after tabbing BACKWARD one stop from [from],
     *  honoring the same custom HTS/TBC layout [nextTabStop] does. Column
     *  0 is always an implicit leftmost stop (real terminals never let
     *  CBT walk the cursor past the left edge), so this never needs a
     *  buffer.columns-style "ran off the end" fallback the way
     *  [nextTabStop] does for the right edge. */
    private fun previousTabStop(from: Int): Int {
        val stops = tabStops
        val prev = if (stops == null) {
            // Untouched by HTS/TBC - mirror of the fixed every-8 default.
            ((from - 1) / 8) * 8
        } else {
            stops.filter { it < from }.maxOrNull() ?: 0
        }
        return prev.coerceIn(0, (buffer.columns - 1).coerceAtLeast(0))
    }

    // Parser state
    private enum class State { NORMAL, ESCAPE, CSI, OSC, CHARSET, DCS, APC, DECALN_CHECK }
    private var state = State.NORMAL
    private val paramBuffer = StringBuilder()
    private val oscBuffer = StringBuilder()
    // Raw payload of the DCS (Device Control String) currently being
    // collected - e.g. everything between "ESC P q" and the terminating ST
    // in a Sixel image ("ESC P q <sixel data> ESC \\"), or between
    // "ESC P > |" and ST for our own XTVERSION reply (which this emulator
    // only ever SENDS, never receives, but shares the same DCS envelope).
    // Sixel payloads are legitimately large - real-world images from
    // img2sixel/chafa/mpv routinely run tens to low hundreds of KB of
    // ASCII-encoded pixel data - so this needs a much bigger cap than
    // oscBuffer's 8192 (see dcsBuffer's overflow guard in handleDcs).
    private val dcsBuffer = StringBuilder()
    // Set the instant a DCS sequence's introducer/params have been read and
    // its *kind* has been identified as Sixel - i.e. as soon as the 'q'
    // final byte of "ESC P <params> q" arrives. Everything from that point
    // until the terminator is raw Sixel body, not "DCS payload text" in
    // the OSC-string sense, so handleDcs branches on this rather than
    // trying to sniff the content of dcsBuffer after the fact.
    private var dcsIsSixel = false

    // Raw payload of the APC (Application Program Command) sequence
    // currently being collected - "ESC _ <data> ST" - used exclusively for
    // the Kitty graphics protocol (see handleEscape's '_' case). Unlike
    // dcsBuffer this can legitimately need to hold a FULL base64-encoded
    // image chunk-by-chunk across many separate APC sequences before the
    // image is complete (Kitty's m=1/m=0 chunking - see kittyChunkBuffer
    // below for why that's tracked separately rather than just appending
    // every chunk onto one another here), so this buffer itself is
    // cleared and reused per INDIVIDUAL APC sequence, not per whole
    // multi-chunk transmission.
    private val apcBuffer = StringBuilder()
    // Same "ESC seen, waiting for the '\' that completes ST" pattern as
    // dcsPendingSt/oscPendingSt - kept as its own field since APC/DCS/OSC
    // are never simultaneously active but each needs its own flag rather
    // than sharing one (a DCS-then-APC or APC-then-OSC back-to-back
    // sequence, while unusual, must not have one's leftover pending-ST
    // flag bleed into the other's parsing).
    private var apcPendingSt = false
    // Accumulates base64 payload bytes across a MULTI-CHUNK Kitty
    // transmission (m=1 on every chunk but the last, m=0 or omitted on
    // the final one - see the kitty graphics protocol spec's "chunked
    // data" section). Kept separate from apcBuffer (which only ever holds
    // ONE APC sequence's worth of data at a time) because each chunk
    // arrives as its OWN complete "ESC _ G<...>,m=1;<chunk> ST" sequence -
    // apcBuffer is cleared between them by handleEscape's '_' case, so
    // the payload has to be stitched together somewhere that survives
    // that. Only the control keys from the FIRST chunk are kept
    // (kittyChunkedCommand) since the spec requires the control-data keys
    // to appear only on that first chunk (continuation chunks are bare
    // "m=1;<data>" or "m=0;<data>" with no other keys) - re-parsing later
    // chunks' keys would find nothing there anyway.
    private val kittyChunkBuffer = StringBuilder()
    // The parsed key=value command from a multi-chunk transmission's
    // FIRST chunk (m=1 present), held here until the LAST chunk (m=0/
    // absent) arrives and the accumulated kittyChunkBuffer payload can
    // finally be decoded against it. Null whenever no chunked
    // transmission is in progress - a null here is exactly how
    // finishApc/handleKittyCommand tell "this chunk continues a prior
    // one" apart from "this chunk starts a new/is a complete standalone
    // command".
    private var kittyChunkedCommand: Map<String, String>? = null
    // Guards against a single runaway/malicious chunked transmission
    // growing kittyChunkBuffer without bound if a program never sends a
    // final m=0 chunk - same "cap it generously above any real use case,
    // bail out rather than hang forever" reasoning as dcsBuffer's own
    // 2_000_000 guard, sized larger still since Kitty images are
    // routinely full-resolution RGBA screenshots (a single 1920x1080 RGBA
    // frame is already ~8 MB raw, ~11 MB base64-encoded) rather than the
    // heavily-quantized/RLE'd palette data Sixel typically carries.
    private var kittyImageIdCounter = 0
    // Assigns synthetic image ids to Kitty commands that upload pixel data
    // (a=t/a=T) without specifying their own i= (client image id) or q=
    // (quiet image id) - the spec allows this for a "fire and forget,
    // immediately place, never reference again" transmit+place in one
    // step (a=T with no explicit id). Counting down from a high value
    // rather than up from 1 keeps synthetic ids from ever colliding with
    // a real client-chosen id in practice (clients conventionally pick
    // small sequential or PID-derived numbers), without this module
    // needing to actually cross-check every synthetic id against every id
    // a program has used so far.
    // The single intermediate byte (0x20-0x2F, e.g. the ' ' in DECSCUSR's
    // "CSI Ps SP q") seen since the current CSI sequence started, if any.
    // Only one is ever meaningful for the sequences this emulator dispatches
    // on, so a single nullable Char (rather than a buffer) is enough to
    // disambiguate final bytes that mean different things with vs. without
    // one - e.g. "CSI Ps SP q" (DECSCUSR, cursor shape) vs. "CSI > 0 q"
    // (XTVERSION) both end in 'q'. Reset whenever a fresh CSI sequence
    // starts and once the current one finishes dispatching.
    private var csiIntermediate: Char? = null

    companion object {
        // U+200D ZERO WIDTH JOINER - glues two otherwise-independent emoji
        // codepoints into one displayed glyph (family/couple/profession
        // sequences etc: "man" + ZWJ + "woman" + ZWJ + "girl" + ZWJ + "boy"
        // renders as a single family emoji, not four side-by-side ones).
        private const val ZWJ = '\u200D'
        // Kitty graphics protocol Unicode placeholder codepoint - see its
        // own doc further down this file (writeChar's detection branch)
        // for what it's for. Lives here rather than there because Kotlin
        // only allows `const val` at top level or inside a named/
        // companion object, never directly in a class body.
        private const val KITTY_PLACEHOLDER_CHAR = 0x10EEEE
        // VT100 DEC Special Graphics charset ("ESC(0"), mapping the ASCII
        // range xterm/real VT100s use for this set onto the Unicode
        // box-drawing/symbol glyphs they actually represent - see
        // writeChar's own translation for where this is applied. Only
        // covers the ASCII code points this charset actually redefines
        // (0x60..0x7E, per DEC's own assignment); anything outside that
        // range passes through unchanged even while special graphics is
        // active, matching how a real VT100 leaves those bytes alone too.
        // Source: https://vt100.net/docs/vt220-rm/table2-4.html
        private val DEC_SPECIAL_GRAPHICS = mapOf(
            '`' to '◆', 'a' to '▒', 'b' to '␉', 'c' to '␌', 'd' to '␍', 'e' to '␊',
            'f' to '°', 'g' to '±', 'h' to '␤', 'i' to '␋', 'j' to '┘', 'k' to '┐',
            'l' to '┌', 'm' to '└', 'n' to '┼', 'o' to '⎺', 'p' to '⎻', 'q' to '─',
            'r' to '⎼', 's' to '⎽', 't' to '├', 'u' to '┤', 'v' to '┴', 'w' to '┬',
            'x' to '│', 'y' to '≤', 'z' to '≥', '{' to 'π', '|' to '≠', '}' to '£',
            '~' to '·'
        )
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

        // Ranges a real terminal renders at DOUBLE the width of an
        // ordinary ASCII cell - the full set of Unicode East_Asian_Width
        // "W" (Wide) and "F" (Fullwidth) ranges (Unicode 15.0's
        // EastAsianWidth.txt - https://www.unicode.org/Public/15.0.0/ucd/EastAsianWidth.txt,
        // the same table every terminal emulator's wcwidth() is built
        // from) plus the emoji-presentation ranges real terminals also
        // render double-wide even though Unicode itself classifies most
        // of them as East_Asian_Width=Neutral rather than W/F (xterm,
        // kitty, iTerm2, and every other terminal that renders emoji at
        // all follow this same "emoji block is wide regardless of its
        // formal EAW property" convention, since a single-cell emoji
        // glyph would be squashed illegibly). Astral-plane ranges are
        // listed directly in codepoint terms (not surrogate pairs) since
        // graphemeDisplayWidth below works from codePointAt, not UTF-16
        // code units.
        private val WIDE_RANGES = listOf(
            0x1100..0x115F,   // Hangul Jamo (leading consonants)
            0x231A..0x231B,   // Watch, Hourglass (emoji-presentation)
            0x2329..0x232A,   // Angle brackets (legacy wide punctuation)
            0x23E9..0x23EC,   // Black right/left-pointing double triangle (emoji)
            0x23F0..0x23F0,   // Alarm clock (emoji)
            0x23F3..0x23F3,   // Hourglass with flowing sand (emoji)
            0x25FD..0x25FE,   // White/black medium-small square (emoji)
            0x2614..0x2615,   // Umbrella with rain drops, hot beverage (emoji)
            0x2648..0x2653,   // Zodiac signs (emoji)
            0x267F..0x267F,   // Wheelchair symbol (emoji)
            0x2693..0x2693,   // Anchor (emoji)
            0x26A1..0x26A1,   // High voltage sign (emoji)
            0x26AA..0x26AB,   // White/black circle (emoji)
            0x26BD..0x26BE,   // Soccer ball, baseball (emoji)
            0x26C4..0x26C5,   // Snowman without snow, sun behind cloud (emoji)
            0x26CE..0x26CE,   // Ophiuchus (emoji)
            0x26D4..0x26D4,   // No entry (emoji)
            0x26EA..0x26EA,   // Church (emoji)
            0x26F2..0x26F3,   // Fountain, flag in hole (emoji)
            0x26F5..0x26F5,   // Sailboat (emoji)
            0x26FA..0x26FA,   // Tent (emoji)
            0x26FD..0x26FD,   // Fuel pump (emoji)
            0x2705..0x2705,   // White heavy check mark (emoji)
            0x270A..0x270B,   // Raised fist, raised hand (emoji)
            0x2728..0x2728,   // Sparkles (emoji)
            0x274C..0x274C,   // Cross mark (emoji)
            0x274E..0x274E,   // Negative squared cross mark (emoji)
            0x2753..0x2755,   // Question/exclamation mark ornaments (emoji)
            0x2757..0x2757,   // Heavy exclamation mark symbol (emoji)
            0x2795..0x2797,   // Heavy plus/minus/division sign (emoji)
            0x27B0..0x27B0,   // Curly loop (emoji)
            0x27BF..0x27BF,   // Double curly loop (emoji)
            0x2B1B..0x2B1C,   // Black/white large square (emoji)
            0x2B50..0x2B50,   // White medium star (emoji)
            0x2B55..0x2B55,   // Heavy large circle (emoji)
            0x2E80..0x303E,   // CJK Radicals, Kangxi Radicals, CJK symbols/punctuation
            0x3041..0x33FF,   // Hiragana, Katakana, Bopomofo, Hangul Compat Jamo, CJK strokes/enclosed/compat
            0x3400..0x4DBF,   // CJK Unified Ideographs Extension A
            0x4E00..0x9FFF,   // CJK Unified Ideographs
            0xA000..0xA4CF,   // Yi Syllables/Radicals
            0xA960..0xA97F,   // Hangul Jamo Extended-A
            0xAC00..0xD7A3,   // Hangul Syllables
            0xF900..0xFAFF,   // CJK Compatibility Ideographs
            0xFE30..0xFE4F,   // CJK Compatibility Forms
            0xFE54..0xFE66,   // Small Form Variants (partial - the W-classified subset)
            0xFE68..0xFE6B,   // Small Form Variants (partial - the W-classified subset)
            0xFF00..0xFF60,   // Fullwidth Forms (fullwidth ASCII/punctuation)
            0xFFE0..0xFFE6,   // Fullwidth signs
            0x16FE0..0x16FFF, // Ideographic symbols/Tangut punctuation
            0x17000..0x18AFF, // Tangut, Tangut Components
            0x18B00..0x18CFF, // Khitan Small Script
            0x18D00..0x18D08, // Tangut Supplement
            0x1AFF0..0x1B16F, // Kana extensions/supplements
            0x1B170..0x1B2FF, // Nushu
            0x1F004..0x1F004, // Mahjong tile red dragon (emoji-presentation)
            0x1F0CF..0x1F0CF, // Playing card black joker (emoji-presentation)
            0x1F18E..0x1F19A, // Squared emoji symbols (AB, CL, etc.)
            0x1F1E6..0x1F1FF, // Regional indicator symbols (flag letter pairs) -
                               // rendered as a single wide flag glyph when two
                               // combine via ZWJ-less adjacency; each individual
                               // indicator is itself wide per Unicode's own
                               // emoji-presentation default even standalone.
            0x1F200..0x1F2FF, // Enclosed Ideographic Supplement
            0x1F300..0x1FAFF, // The bulk of Unicode emoji: pictographs, transport,
                               // symbols, supplemental symbols/pictographs, emoji-extended-A
            0x1FB00..0x1FBFF, // Symbols for Legacy Computing (block-mosaic/sextant glyphs)
            0x20000..0x3FFFD  // CJK Unified Ideographs Extension B and beyond,
                               // including Extension C/D/E/F/G/H and the CJK
                               // Compatibility Ideographs Supplement, all wide
        )

        // Codepoints/ranges that occupy ZERO columns - combining marks
        // (Unicode General Category Mn/Me and a handful of Mc that are
        // conventionally rendered zero-width by every terminal despite
        // formally being "spacing combining" per the Unicode category
        // name) plus the invisible format characters a terminal is
        // expected to never advance the cursor for. Distinct from - and
        // checked BEFORE - [WIDE_RANGES] in [graphemeDisplayWidth]: a
        // codepoint here always contributes 0 columns regardless of
        // whether it also happens to fall in a block that's wide for
        // OTHER codepoints (none currently do, but the check order keeps
        // that true even if a future addition were sloppy about it).
        // This list is the actually-common subset real terminal output
        // exercises (combining diacritics on Latin/Cyrillic/Hebrew/Arabic
        // text, Indic vowel signs, ZWJ/ZWNJ, variation selectors,
        // Fitzpatrick skin-tone modifiers) rather than the full ~2,300-
        // entry Unicode Mn/Me/Cf table - the same "cover what real output
        // contains, not the entire data file" scope WIDE_RANGES' own doc
        // already applies to the wide side.
        private val ZERO_WIDTH_RANGES = listOf(
            0x0300..0x036F,   // Combining Diacritical Marks
            0x0483..0x0489,   // Combining Cyrillic marks
            0x0591..0x05BD,   // Hebrew points
            0x05BF..0x05BF,
            0x05C1..0x05C2,
            0x05C4..0x05C5,
            0x05C7..0x05C7,
            0x0610..0x061A,   // Arabic marks
            0x064B..0x065F,   // Arabic combining marks
            0x0670..0x0670,
            0x06D6..0x06DC,
            0x06DF..0x06E4,
            0x06E7..0x06E8,
            0x06EA..0x06ED,
            0x0711..0x0711,   // Syriac
            0x0730..0x074A,
            0x07A6..0x07B0,   // Thaana
            0x07EB..0x07F3,   // NKo
            0x0816..0x0819,   // Samaritan
            0x081B..0x0823,
            0x0825..0x0827,
            0x0829..0x082D,
            0x0859..0x085B,   // Mandaic
            0x08E3..0x0902,   // Arabic Extended-A, Devanagari signs
            0x093A..0x093A,
            0x093C..0x093C,
            0x0941..0x0948,
            0x094D..0x094D,
            0x0951..0x0957,
            0x0962..0x0963,
            0x0981..0x0981,   // Bengali
            0x09BC..0x09BC,
            0x09C1..0x09C4,
            0x09CD..0x09CD,
            0x09E2..0x09E3,
            0x0A01..0x0A02,   // Gurmukhi
            0x0A3C..0x0A3C,
            0x0A41..0x0A51,
            0x0A70..0x0A71,
            0x0A75..0x0A75,
            0x0AC1..0x0AC8,   // Gujarati
            0x0ACD..0x0ACD,
            0x0AE2..0x0AE3,
            0x0B01..0x0B01,   // Oriya
            0x0B3C..0x0B3C,
            0x0B3F..0x0B3F,
            0x0B41..0x0B44,
            0x0B4D..0x0B4D,
            0x0B62..0x0B63,
            0x0B82..0x0B82,   // Tamil
            0x0BC0..0x0BC0,
            0x0BCD..0x0BCD,
            0x0C00..0x0C00,   // Telugu
            0x0C3E..0x0C40,
            0x0C46..0x0C56,
            0x0CBC..0x0CBC,   // Kannada
            0x0CCC..0x0CCD,
            0x0D00..0x0D01,   // Malayalam
            0x0D3B..0x0D3C,
            0x0D41..0x0D44,
            0x0D4D..0x0D4D,
            0x0DCA..0x0DCA,   // Sinhala
            0x0DD2..0x0DD6,
            0x0E31..0x0E31,   // Thai
            0x0E34..0x0E3A,
            0x0E47..0x0E4E,
            0x0EB1..0x0EB1,   // Lao
            0x0EB4..0x0EBC,
            0x0EC8..0x0ECD,
            0x0F18..0x0F19,   // Tibetan
            0x0F35..0x0F35,
            0x0F37..0x0F37,
            0x0F39..0x0F39,
            0x0F71..0x0F7E,
            0x0F80..0x0F84,
            0x0F86..0x0F87,
            0x0F8D..0x0F97,
            0x0F99..0x0FBC,
            0x0FC6..0x0FC6,
            0x102D..0x1030,   // Myanmar
            0x1032..0x1037,
            0x1039..0x103A,
            0x103D..0x103E,
            0x1058..0x1059,
            0x105E..0x1060,
            0x1071..0x1074,
            0x1082..0x1082,
            0x1085..0x1086,
            0x108D..0x108D,
            0x135D..0x135F,   // Ethiopic combining marks
            0x1712..0x1714,   // Tagalog
            0x1732..0x1734,   // Hanunoo
            0x1752..0x1753,   // Buhid
            0x1772..0x1773,   // Tagbanwa
            0x17B4..0x17B5,   // Khmer
            0x17B7..0x17BD,
            0x17C6..0x17C6,
            0x17C9..0x17D3,
            0x17DD..0x17DD,
            0x180B..0x180D,   // Mongolian, incl. Free Variation Selectors 1-3
            0x1885..0x1886,
            0x18A9..0x18A9,
            0x1920..0x1922,   // Limbu
            0x1927..0x1928,
            0x1932..0x1932,
            0x1939..0x193B,
            0x1A17..0x1A18,   // Buginese
            0x1A1B..0x1A1B,
            0x1A56..0x1A56,   // Tai Tham
            0x1A58..0x1A5E,
            0x1A60..0x1A60,
            0x1A62..0x1A62,
            0x1A65..0x1A6C,
            0x1A73..0x1A7C,
            0x1A7F..0x1A7F,
            0x1AB0..0x1ACE,   // Combining Diacritical Marks Extended/Supplement
            0x1B00..0x1B03,   // Balinese
            0x1B34..0x1B34,
            0x1B36..0x1B3A,
            0x1B3C..0x1B3C,
            0x1B42..0x1B42,
            0x1B6B..0x1B73,
            0x1B80..0x1B81,   // Sundanese
            0x1BA2..0x1BA5,
            0x1BA8..0x1BA9,
            0x1BAB..0x1BAD,
            0x1BE6..0x1BE6,   // Batak
            0x1BE8..0x1BE9,
            0x1BED..0x1BED,
            0x1BEF..0x1BF1,
            0x1C2C..0x1C33,   // Lepcha
            0x1C36..0x1C37,
            0x1CD0..0x1CD2,   // Vedic Extensions
            0x1CD4..0x1CE0,
            0x1CE2..0x1CE8,
            0x1CED..0x1CED,
            0x1CF4..0x1CF4,
            0x1CF8..0x1CF9,
            0x1DC0..0x1DFF,   // Combining Diacritical Marks Supplement/for Symbols
            0x200B..0x200F,   // Zero Width Space/Non-Joiner/Joiner + directional marks
            0x202A..0x202E,   // Directional formatting (LRE/RLE/PDF/LRO/RLO)
            0x2060..0x2064,   // Word Joiner + invisible math operators
            0x2066..0x206F,   // Directional isolates + deprecated format chars
            0x20D0..0x20FF,   // Combining Diacritical Marks for Symbols
            0x2CEF..0x2CF1,   // Coptic combining marks
            0x2D7F..0x2D7F,   // Tifinagh
            0x2DE0..0x2DFF,   // Cyrillic Extended-A combining
            0x302A..0x302D,   // CJK tone marks
            0x3099..0x309A,   // Combining Katakana-Hiragana Voiced/Semi-Voiced Sound Marks
            0xA66F..0xA672,   // Cyrillic Extended-B combining
            0xA674..0xA67D,
            0xA69E..0xA69F,
            0xA6F0..0xA6F1,   // Bamum
            0xA802..0xA802,   // Syloti Nagri
            0xA806..0xA806,
            0xA80B..0xA80B,
            0xA825..0xA826,
            0xA82C..0xA82C,
            0xA8C4..0xA8C5,   // Saurashtra
            0xA8E0..0xA8F1,   // Devanagari Extended
            0xA8FF..0xA8FF,
            0xA926..0xA92D,   // Kayah Li
            0xA947..0xA951,   // Rejang
            0xA980..0xA982,   // Javanese
            0xA9B3..0xA9B3,
            0xA9B6..0xA9B9,
            0xA9BC..0xA9BD,
            0xA9E5..0xA9E5,   // Myanmar Extended-B
            0xAA29..0xAA2E,   // Cham
            0xAA31..0xAA32,
            0xAA35..0xAA36,
            0xAA43..0xAA43,
            0xAA4C..0xAA4C,
            0xAA7C..0xAA7C,   // Myanmar Extended-A
            0xAAB0..0xAAB0,   // Tai Viet
            0xAAB2..0xAAB4,
            0xAAB7..0xAAB8,
            0xAABE..0xAABF,
            0xAAC1..0xAAC1,
            0xAAEC..0xAAED,   // Meetei Mayek Extensions
            0xAAF6..0xAAF6,
            0xABE5..0xABE5,   // Meetei Mayek
            0xABE8..0xABE8,
            0xABED..0xABED,
            0xFB1E..0xFB1E,   // Hebrew Point Judeo-Spanish Varika
            0xFE00..0xFE0F,   // Variation Selectors 1-16 (includes VS15/VS16 already
                               // named as constants above - kept in this range too
                               // for a uniform lookup rather than a special case)
            0xFE20..0xFE2F,   // Combining Half Marks
            0xFEFF..0xFEFF,   // Zero Width No-Break Space / BOM
            0x101FD..0x101FD, // Phaistos Disc sign combining obelos
            0x102E0..0x102E0, // Coptic Epact combining mark
            0x10376..0x1037A, // Combining Old Permic letters
            0x10A01..0x10A03, // Kharoshthi
            0x10A05..0x10A06,
            0x10A0C..0x10A0F,
            0x10A38..0x10A3A,
            0x10A3F..0x10A3F,
            0x10AE5..0x10AE6, // Manichaean
            0x10D24..0x10D27, // Hanifi Rohingya
            0x10EAB..0x10EAC, // Yezidi
            0x10F46..0x10F50, // Sogdian
            0x11000..0x11002, // Brahmi (Bindu/Visarga are spacing, kept for coverage)
            0x11038..0x11046,
            0x1107F..0x11082, // Kaithi
            0x110B0..0x110BA,
            0x11100..0x11102, // Chakma
            0x11127..0x11134,
            0x11173..0x11173, // Mahajani
            0x11180..0x11182, // Sharada
            0x111B6..0x111BE,
            0x111C9..0x111CC,
            0x1122F..0x11231, // Khojki
            0x11234..0x11234,
            0x11236..0x11237,
            0x1123E..0x1123E,
            0x112DF..0x112EA, // Khudawadi
            0x11300..0x11301, // Grantha
            0x1133B..0x1133C,
            0x11340..0x11340,
            0x11366..0x1136C,
            0x11370..0x11374,
            0x11438..0x1143F, // Newa
            0x11442..0x11444,
            0x11446..0x11446,
            0x114B3..0x114B8, // Tirhuta
            0x114BA..0x114BA,
            0x114BF..0x114C0,
            0x114C2..0x114C3,
            0x115B2..0x115B5, // Siddham
            0x115BC..0x115BD,
            0x115BF..0x115C0,
            0x115DC..0x115DD,
            0x11633..0x1163A, // Modi
            0x1163D..0x1163D,
            0x1163F..0x11640,
            0x116AB..0x116AB, // Takri
            0x116AD..0x116AD,
            0x116B0..0x116B5,
            0x116B7..0x116B7,
            0x1171D..0x1171F, // Ahom
            0x11722..0x11725,
            0x11727..0x1172B,
            0x1182F..0x11837, // Dogra
            0x11839..0x1183A,
            0x119D4..0x119D7, // Nandinagari
            0x119DA..0x119DB,
            0x119E0..0x119E0,
            0x11A01..0x11A0A, // Zanabazar Square
            0x11A33..0x11A38,
            0x11A3B..0x11A3E,
            0x11A47..0x11A47,
            0x11A51..0x11A56, // Soyombo
            0x11A59..0x11A5B,
            0x11A8A..0x11A96,
            0x11A98..0x11A99,
            0x11C30..0x11C36, // Bhaiksuki
            0x11C38..0x11C3D,
            0x11C3F..0x11C3F,
            0x11C92..0x11CA7, // Marchen
            0x11CAA..0x11CB0,
            0x11CB2..0x11CB3,
            0x11CB5..0x11CB6,
            0x11D31..0x11D36, // Masaram Gondi
            0x11D3A..0x11D3A,
            0x11D3C..0x11D3D,
            0x11D3F..0x11D45,
            0x11D47..0x11D47,
            0x11D90..0x11D91, // Gunjala Gondi
            0x11D95..0x11D95,
            0x11D97..0x11D97,
            0x11EF3..0x11EF4, // Makasar
            0x16AF0..0x16AF4, // Bassa Vah
            0x16B30..0x16B36, // Pahawh Hmong
            0x16F4F..0x16F4F,  // Miao
            0x16F8F..0x16F92,
            0x16FE4..0x16FE4,  // Tangut tone marks
            0x1BC9D..0x1BC9E,  // Duployan
            0x1D165..0x1D169,  // Musical Symbols combining
            0x1D16D..0x1D172,
            0x1D17B..0x1D182,
            0x1D185..0x1D18B,
            0x1D1AA..0x1D1AD,
            0x1D242..0x1D244,  // Ancient Greek Musical Notation combining
            0x1DA00..0x1DA36,  // Sign Writing combining
            0x1DA3B..0x1DA6C,
            0x1DA75..0x1DA75,
            0x1DA84..0x1DA84,
            0x1DA9B..0x1DA9F,
            0x1DAA1..0x1DAAF,
            0x1E000..0x1E006,  // Glagolitic Supplement combining
            0x1E008..0x1E018,
            0x1E01B..0x1E021,
            0x1E023..0x1E024,
            0x1E026..0x1E02A,
            0x1E130..0x1E136,  // Nyiakeng Puachue Hmong
            0x1E2AE..0x1E2AE,  // Toto
            0x1E2EC..0x1E2EF,  // Wancho
            0x1E8D0..0x1E8D6,  // Mende Kikakui
            0x1E944..0x1E94A,  // Adlam
            0xE0100..0xE01EF   // Variation Selectors Supplement 17-256
        )

        /** True if [codePoint] is one of the zero-column combining marks/
         *  format characters listed in [ZERO_WIDTH_RANGES] - checked by
         *  [graphemeDisplayWidth] before the wide-range check, separately
         *  by [scanGraphemeCluster]'s own clustering logic (a zero-width
         *  mark always joins the PRECEDING grapheme cluster rather than
         *  starting a new one, same as ZWJ/skin-tone modifiers already do
         *  there), and by [handleNormal] to route a standalone BMP mark
         *  (one that arrives as a plain Char rather than as part of a
         *  surrogate-pair-started cluster - scanGraphemeCluster's own scan
         *  never sees these at all) to [appendCombiningMark] instead of
         *  [writeChar]. */
        private fun isZeroWidth(codePoint: Int) = ZERO_WIDTH_RANGES.any { codePoint in it }


        /**
         * Display width, in terminal columns, of the codepoint a grapheme
         * cluster STARTS with (a trailing ZWJ/variation-selector/skin-tone
         * modifier never changes the width a compound emoji sequence
         * occupies - real terminals render the whole joined sequence at
         * the same 2-column cell the base emoji alone would take, not
         * wider per additional joined codepoint). Returns 0 for a cluster
         * that starts with a combining mark/format character in
         * [ZERO_WIDTH_RANGES] - real terminal output practically never
         * hits this branch on its own (scanGraphemeCluster's own doc:
         * such a codepoint normally gets folded into the PRECEDING
         * cluster before this is ever called on it standalone), but it's
         * the semantically correct answer if it ever is, e.g. a
         * combining mark arriving as the very first character of a
         * fresh write with nothing preceding it to attach to. Returns 2
         * for [WIDE_RANGES], 1 for everything else - matching a plain
         * ASCII/Latin/etc. cell's existing single-column behavior
         * exactly, so ordinary text is unaffected by either table's
         * existence.
         */
        fun graphemeDisplayWidth(cluster: String): Int {
            if (cluster.isEmpty()) return 1
            val codePoint = cluster.codePointAt(0)
            return when {
                isZeroWidth(codePoint) -> 0
                WIDE_RANGES.any { codePoint in it } -> 2
                else -> 1
            }
        }

        // Cap on promptMarks (see its own doc) - generous enough that a
        // single session would need tens of thousands of individual shell
        // prompts before the oldest ones start getting dropped, while still
        // bounding memory for a long-lived session left open for days.
        private const val MAX_PROMPT_MARKS = 10_000
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
            State.DCS -> handleDcs(ch)
            State.APC -> handleApc(ch)
            State.DECALN_CHECK -> handleDecAlnCheck(ch)
            State.CHARSET -> {
                // The designator byte itself (e.g. 'B' in ESC(B, '0' in
                // ESC(0) - only '0' (DEC special graphics) is modeled as
                // true; every other designator (B=US-ASCII, A=UK, etc.)
                // is treated as "plain, no translation" the same as this
                // module already only ever produces ASCII/Unicode text
                // otherwise. See g0/g1/g2/g3IsSpecialGraphics's own doc.
                val isSpecialGraphics = ch == '0'
                when (pendingCharsetSlot) {
                    CharsetSlot.G0 -> g0IsSpecialGraphics = isSpecialGraphics
                    CharsetSlot.G1 -> g1IsSpecialGraphics = isSpecialGraphics
                    CharsetSlot.G2 -> g2IsSpecialGraphics = isSpecialGraphics
                    CharsetSlot.G3 -> g3IsSpecialGraphics = isSpecialGraphics
                }
                state = State.NORMAL
            }
        }
    }

    private fun handleNormal(ch: Char) {
        when (ch) {
            '\u001B' -> { state = State.ESCAPE }
            '\u0007' -> listener.onBell() // BEL
            // LF, VT (\u000B) and FF (\u000C) are all treated identically -
            // a plain "move down one line, scrolling at the bottom margin"
            // - matching real xterm/VTE: none of the three ever reset
            // cursorCol (that's CR's job alone), and VT/FF's own historical
            // "advance the paper" meaning on real hardware collapses to
            // exactly the same on-screen effect as LF on every terminal
            // emulator in practice. Previously only '\n' was handled here,
            // so a program that emitted \v/\f directly (rare, but e.g. some
            // legacy `tput`/troff-descended tools and a handful of DOS-
            // ported CLI utilities do) fell to the `else` branch below and
            // had those bytes written as literal (invisible/control)
            // characters into the grid instead of moving the cursor.
            '\n', '\u000B', '\u000C' -> lineFeed()
            '\r' -> cursorCol = 0
            '\b' -> if (cursorCol > 0) cursorCol--
            '\t' -> cursorCol = nextTabStop(cursorCol)
            // SO (Shift Out) / SI (Shift In) - lock in G1/G0 respectively
            // (see lockingGSet's own doc). Classic ncurses line-drawing
            // builds alternate SO...text...SI around box-drawing runs
            // instead of always writing UTF-8 box-drawing characters
            // directly; without these, such a program's line-drawing text
            // printed as literal ASCII (q/x/l/k/etc.) since neither byte
            // was consumed here before - they fell to the `else` branch
            // below and were written as literal (invisible/control)
            // characters into the grid.
            '\u000E' -> lockingGSet = CharsetSlot.G1
            '\u000F' -> lockingGSet = CharsetSlot.G0
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
            // A BMP zero-width combining mark/format character (see
            // isZeroWidth's own doc) never enters scanGraphemeCluster's
            // clustering path - that scan only ever triggers for a run
            // STARTING with a surrogate pair - so a plain decomposed
            // accent (e.g. 'e' + COMBINING ACUTE ACCENT rather than the
            // single precomposed 'é'), genuinely common in real terminal
            // output rather than just an emoji-pipeline edge case, used
            // to fall straight through to the plain writeChar call below:
            // it consumed a whole column of its own and visually
            // detached the accent from the letter it decorates instead
            // of stacking onto it, exactly the "independent zero-width
            // character" case graphemeDisplayWidth's own doc warns
            // writeChar's `isWide` check doesn't handle correctly. Routed
            // to appendCombiningMark instead so it merges onto the
            // PRECEDING cell without moving the cursor, matching every
            // real terminal's rendering of a trailing combining mark.
            else -> if (!ch.isSurrogate()) {
                if (isZeroWidth(ch.code)) appendCombiningMark(ch) else writeChar(ch.toString())
            }
        }
    }

    /**
     * Merges a standalone zero-width combining mark (see handleNormal's
     * own call site doc) into whichever cell currently sits immediately
     * to the LEFT of the cursor, without moving the cursor itself - a
     * combining mark always decorates the glyph it follows rather than
     * occupying a column of its own. Walks one further column left when
     * that immediate left neighbor is itself the reserved right-half
     * cell of a wide (CJK/fullwidth/emoji) glyph (see Cell.
     * isWideContinuation's own doc), so the mark lands on the actual
     * glyph-holding cell rather than on that placeholder. If the cursor
     * is sitting at column 0 (a combining mark as the literal first
     * character written to a fresh row - genuinely rare, but possible on
     * a redraw or right after a cursor reposition), there's nothing to
     * attach to at all, so it's dropped rather than given a bogus
     * standalone cell.
     */
    private fun appendCombiningMark(mark: Char) {
        if (cursorCol <= 0) return
        var targetCol = cursorCol - 1
        if (buffer.cellAt(cursorRow, targetCol).isWideContinuation && targetCol > 0) {
            targetCol--
        }
        val target = buffer.cellAt(cursorRow, targetCol)
        buffer.setCell(cursorRow, targetCol, target.copy(text = target.text + mark))
    }

    private fun handleEscape(ch: Char) {
        when (ch) {
            '[' -> { state = State.CSI; paramBuffer.clear(); csiIntermediate = null }
            ']' -> { state = State.OSC; oscBuffer.clear() }
            // DCS (Device Control String) introducer - "ESC P <params> <final> <body> ST".
            // Only consumed on the RECEIVE side for Sixel graphics ("ESC P
            // <params> q <sixel data> ST"); this emulator only ever SENDS
            // (never parses) the other DCS reply it emits itself
            // (XTVERSION's "ESC P > | ... ST"). Previously fell to the
            // `else` branch below and was silently dropped from here on -
            // which didn't just lose the image, it left the parser sitting
            // in ESCAPE state at the 'P', so the very next byte (the first
            // param/final byte of the DCS the program actually sent) got
            // reinterpreted as a *new*, unrelated escape sequence,
            // corrupting whatever followed the dropped image in the
            // stream. dcsBuffer collects raw params+body from here; the
            // 'q' final byte flips dcsIsSixel once we know which kind of
            // DCS this is (see handleDcs).
            'P' -> { state = State.DCS; dcsBuffer.clear(); dcsIsSixel = false }
            // APC (Application Program Command) introducer - "ESC _ <data>
            // ST". The Kitty graphics protocol rides entirely inside APC
            // (never DCS - Sixel and Kitty graphics are two unrelated
            // envelopes that happen to both carry images), with `data`
            // always shaped as "G<key>=<value>,<key>=<value>,...;<payload>"
            // per the kitty spec: a 'G' marker, comma-separated key=value
            // control fields, then a ';' separator, then the (usually
            // base64) payload. Same ESC-butted-up-against-terminator
            // recovery as DCS/OSC apply here (see handleApc's own
            // apcPendingSt), and same rationale for why previously falling
            // to `else` below would have been actively harmful rather than
            // just inert: it would leave the parser stuck re-interpreting
            // the Kitty command's own bytes as top-level escape sequences.
            '_' -> { state = State.APC; apcBuffer.clear() }
            'c' -> { reset(); state = State.NORMAL }
            // SCS - designate G0/G1/G2/G3 character set (e.g. ESC(B =
            // US-ASCII, ESC(0 = DEC special graphics). nano and other
            // full-screen apps send these routinely around their status-
            // bar drawing. This is a two-byte sequence: the designator
            // that follows ('(' G0, ')' G1, '*' G2, '+' G3) still needs
            // one more byte consumed (the actual set, like 'B' or '0').
            // Previously that byte fell through to the `else` branch
            // below, which reset state to NORMAL without consuming it -
            // so the next processChar() call treated it as plain text and
            // wrote it straight into the buffer. That's exactly what put
            // stray "B" characters into nano's screen. pendingCharsetSlot
            // records WHICH designator this was (see State.CHARSET's own
            // doc) so the follow-up byte writes into the right one of
            // g0/g1/g2/g3IsSpecialGraphics instead of always assuming G0.
            '(' -> { state = State.CHARSET; pendingCharsetSlot = CharsetSlot.G0 }
            ')' -> { state = State.CHARSET; pendingCharsetSlot = CharsetSlot.G1 }
            '*' -> { state = State.CHARSET; pendingCharsetSlot = CharsetSlot.G2 }
            '+' -> { state = State.CHARSET; pendingCharsetSlot = CharsetSlot.G3 }
            // LS2/LS3 (Locking Shift 2/3, "ESC n"/"ESC o") - the G2/G3
            // counterparts of SI/SO (which lock G0/G1 instead - see
            // lockingGSet's own doc). Stays in effect for every
            // subsequent character until the next SI/SO/LS2/LS3.
            'n' -> lockingGSet = CharsetSlot.G2
            'o' -> lockingGSet = CharsetSlot.G3
            // SS2/SS3 (Single Shift 2/3, "ESC N"/"ESC O") - invoke G2/G3
            // for exactly the ONE next graphic character, then
            // automatically fall back to whatever lockingGSet already
            // was - see singleShiftGSet's own doc. writeChar consumes and
            // clears this the instant that one character is written.
            'N' -> singleShiftGSet = CharsetSlot.G2
            'O' -> singleShiftGSet = CharsetSlot.G3
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
            // HTS (Horizontal Tab Set) - plants a custom tab stop at the
            // current cursor column. Never handled before, so it fell to
            // the catch-all `else` below (silently dropped, harmlessly -
            // nothing was leaking to the screen), but any program relying
            // on its own tab stops (rather than the fixed every-8 default)
            // - column-aligned CLI tables, some TUI status lines - tabbed
            // to the wrong place because the stop it thought it had set
            // was simply never recorded.
            'H' -> { ensureTabStops().add(cursorCol); state = State.NORMAL }
            // DECKPAM/DECKPNM - see applicationKeypadMode's own doc.
            '=' -> { applicationKeypadMode = true; state = State.NORMAL }
            '>' -> { applicationKeypadMode = false; state = State.NORMAL }
            // '#' introduces a handful of two-byte VT100 test/line-size
            // sequences (DECDHL top/bottom half, DECSWL, DECDWL - none of
            // which this single-size-cell renderer can meaningfully act
            // on) with DECALN ("ESC # 8") the one genuinely useful member:
            // fills the whole screen with 'E' at the default SGR, real
            // terminals' own "screen alignment test" used to visually
            // check margins/geometry - some terminal capability test
            // suites and a few retro/BBS-style programs still send it on
            // startup as a crude "does this thing even render a full
            // screen" probe. Routed to its own CHARSET-like one-byte
            // lookahead state rather than handled inline here since (like
            // SCS's own '('/')' two-byte form just above) the actual
            // effect depends on the SECOND byte, not just recognizing '#'
            // - falling to the generic `else` below previously reset state
            // to NORMAL without consuming that follow-up byte, leaking it
            // as literal text the exact same way an unconsumed SCS
            // designator used to (see '('/')' cases' own doc).
            '#' -> { state = State.DECALN_CHECK }
            else -> state = State.NORMAL
        }
    }

    /** One-byte lookahead after "ESC #" (see handleEscape's own '#' case
     *  doc) - only Ps=8 (DECALN) is acted on; every other DECDHL/DECSWL/
     *  DECDWL variant is consumed (so it doesn't leak as literal text) but
     *  otherwise a no-op, since none of them have a meaningful effect on a
     *  single-cell-size renderer like this one. */
    private fun handleDecAlnCheck(ch: Char) {
        if (ch == '8') {
            for (row in 0 until buffer.rows) {
                for (col in 0 until buffer.columns) {
                    buffer.setCell(row, col, TerminalBuffer.Cell(text = "E"))
                }
            }
            cursorRow = 0
            cursorCol = 0
        }
        state = State.NORMAL
    }

    private fun handleCsi(ch: Char) {
        if (ch.isDigit() || ch == ';' || ch == '?' || ch == '>' || ch == '=' || ch == '<' || ch == ':') {
            // '?' is the DEC-private-mode prefix (CSI ? Ps h/l, handled by
            // handlePrivateMode) and also the kitty keyboard-protocol QUERY
            // prefix ("CSI ? u", see handleKittyKeyboardProtocol). '>' and
            // '=' are two more CSI prefix bytes real programs send - '>'
            // for secondary-DA/xterm queries like XTVERSION ("CSI > 0 q",
            // "CSI > c") and kitty's PUSH ("CSI > <flags> u"), '=' for
            // tertiary-DA and kitty's SET ("CSI = <flags> ; <mode> u").
            // '<' is kitty's own POP ("CSI < <count> u") - distinct from
            // the '<' xterm SGR mouse-tracking uses, which only ever
            // appears in this app's own OUTPUT (encodeMouseEvent), never
            // something this parser has to read back in. Before '>'/'='
            // were recognized here, the prefix byte itself fell straight
            // through to the "final byte reached" dispatch below with an
            // empty paramBuffer: it got treated AS the final byte, silently
            // matched no case, and state reset to NORMAL - right as the
            // sequence's *real* parameters and final byte (e.g. the "0",
            // " ", "q" of "CSI > 0 q") were still incoming. Those then
            // arrived one at a time in NORMAL state and got printed as
            // literal text - exactly the "0q" garbage seen on fish/
            // starship startup (both send "CSI > 0 q" as a terminal-
            // capability probe); '<' was never added at all, so a kitty POP
            // query hit the exact same bug until now. ':' is kitty's own
            // key-report sub-parameter separator ("CSI 97:99 u" - base
            // codepoint colon shifted-codepoint, "CSI 1;1:2 u" - modifiers
            // colon event-type); this parser only ever needs to recognize
            // and pass through kitty query/push/pop/set forms (see
            // handleKittyKeyboardProtocol's own doc on why full in/out
            // parity isn't attempted), but a real kitty-aware program could
            // still legitimately echo a colon-bearing CSI u sequence back
            // at this terminal (e.g. over ssh, a remote kitty-protocol-
            // aware shell reflecting terminal capability strings), and
            // without ':' accepted here that would hit the same "prefix
            // byte treated as final byte, rest leaks as text" bug as '<'
            // just did. Folding every one of these into paramBuffer here,
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
            // Recorded (rather than just consumed) so the final-byte
            // dispatch below can tell apart sequences that only differ by
            // which intermediate byte preceded the same final letter - see
            // csiIntermediate's own doc.
            csiIntermediate = ch
            return
        }
        // Final byte reached - dispatch
        val raw = paramBuffer.toString()
        val private = raw.startsWith("?")
        // Distinct from `private` (the '?' DEC-private-mode prefix) - '>'
        // marks a secondary-DA/XTVERSION/kitty-keyboard query specifically
        // (e.g. "CSI > c" for DA2, "CSI > 0 q" for XTVERSION). Needed below
        // so 'c' can tell a DA2 query ("CSI > c", answered with the
        // Pp;Pv;Pc-style secondary-DA reply xterm/tmux/ssh expect) apart
        // from a DA1 query ("CSI c", answered with the VT100 "\u001B[?1;2c"
        // primary-DA reply) - before this they were indistinguishable once
        // the prefix was stripped below, so a DA2 query would have gotten
        // DA1's answer, a reply shape the querying side doesn't recognize
        // as a valid DA2 response and wasn't going to accept as unblocking
        // its wait either.
        val secondaryDA = raw.startsWith(">")
        // '>' (secondary-DA/XTVERSION/kitty-keyboard queries, e.g. the
        // "CSI > 0 q" fish/starship send on startup) and '=' (tertiary-DA)
        // sequences aren't otherwise handled below - stripping the prefix
        // the same way '?' is stripped means they still hit a real case
        // (none) and fall through to the unsupported-final-byte `else`,
        // silently and completely ignored, instead of the leftover prefix
        // character corrupting the parsed param list.
        val params = raw.removePrefix("?").removePrefix(">").removePrefix("=").removePrefix("<")
            .split(';')
            // Kitty sub-params (e.g. the "99" in a "97:99" base:shifted
            // pair) are colon-joined onto the same field; only the part
            // before the first colon is ever dispatched on below (see
            // handleKittyKeyboardProtocol's own doc on why this parser
            // only needs the flags/mode/count value, not a full sub-param
            // decode), so splitting on ':' and keeping the first piece
            // here means a colon-bearing field parses the same as a plain
            // one instead of failing toIntOrNull() below and silently
            // vanishing via mapNotNull.
            .map { it.substringBefore(':') }
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

        // Underline-style colon-subparameter (e.g. the "3" in "4:3", which
        // requests a CURLY/"undercurl" underline rather than a plain
        // straight one) - extracted separately from `params` above because
        // that list's own colon-handling (see its own doc just above)
        // deliberately keeps only the part BEFORE the first colon for every
        // field, which is exactly right for the fields that use colons as a
        // kitty-keyboard base:shifted pairing but would silently swallow
        // this one field's only meaningful information. Found by prefix
        // match rather than by index into `params` since a field that
        // fails toIntOrNull() (an empty field from "1;;2", for instance)
        // gets dropped by params' own mapNotNull, which would desync any
        // by-index lookup between the two lists - a plain string search
        // over the still-intact raw fields has no such alignment hazard.
        // Only consulted by applySgr (ch == 'm'), and only meaningful when
        // present - see curUnderlineCurly's own doc for how a null here
        // (the overwhelmingly common case: SGR 4 with no colon at all)
        // leaves the plain single-underline behavior untouched.
        val underlineSubStyle: Int? = if (ch == 'm') {
            raw.split(';').firstOrNull { it.startsWith("4:") }
                ?.substringAfter(':')?.toIntOrNull()
        } else null

        // Kitty keyboard protocol ("CSI ? u" query, "CSI > ... u" push,
        // "CSI < ... u" pop, "CSI = ... u" set) - checked BEFORE the
        // generic `private`/secondaryDA branches below so it doesn't get
        // swallowed by handlePrivateMode's empty-params no-op loop (a bare
        // "CSI ? u" has no digits between '?' and 'u', so private's own
        // params list is empty and its for-loop does nothing) or by
        // secondaryDA's own handling further down, which doesn't know
        // about 'u' at all. Plain "CSI u" (no prefix, ANSI.SYS cursor-
        // restore, handled in the main `when` below) and "CSI < cb;c;r M"
        // (SGR mouse - a different final byte, 'M'/'m', not 'u') are both
        // untouched by this since neither matches prefix=='<' with
        // finalByte=='u'.
        if (ch == 'u' && (raw.startsWith("?") || raw.startsWith(">") || raw.startsWith("<") || raw.startsWith("="))) {
            handleKittyKeyboardProtocol(raw, params)
            state = State.NORMAL
            csiIntermediate = null
            return
        }

        // DECRQM (Request Mode), "CSI ? Ps $ p" (DEC-private) or
        // "CSI Ps $ p" (ANSI mode) - "is mode Ps currently set?". Answered
        // with "CSI ? Ps ; Pm $ y" (DEC-private) / "CSI Ps ; Pm $ y" (ANSI),
        // Pm being 0 (not recognized), 1 (set), 2 (reset), 3 (permanently
        // set) or 4 (permanently reset) per the spec. Checked BEFORE the
        // `private` branch below since a bare '?' prefix would otherwise
        // route this into handlePrivateMode(params, 'p') - which only knows
        // 'h'/'l' final bytes and would silently no-op the whole query,
        // leaving the requesting program to sit out its own timeout the
        // same way DSR/DA1/XTVERSION used to before those got answered.
        // Only the handful of modes this emulator actually models are
        // reported set/reset; anything else answers "not recognized" (0)
        // rather than guessing, matching how xterm itself answers a mode it
        // doesn't implement.
        if (ch == 'p' && csiIntermediate == '$') {
            val queriedPs = params.getOrElse(0) { 0 }
            val reportedValue = if (private) {
                when (queriedPs) {
                    1 -> if (applicationCursorKeys) 1 else 2
                    3 -> if (deccolm132Mode) 1 else 2
                    5 -> if (buffer.reverseVideoMode) 1 else 2
                    6 -> if (originMode) 1 else 2
                    7 -> if (autoWrapMode) 1 else 2
                    8 -> if (autoRepeatMode) 1 else 2
                    9 -> if (mouseMode == MouseMode.X10) 1 else 2
                    25 -> if (cursorVisible) 1 else 2
                    47, 1049 -> if (buffer.inAlternateScreen) 1 else 2
                    1000 -> if (mouseMode == MouseMode.NORMAL) 1 else 2
                    1002 -> if (mouseMode == MouseMode.BUTTON_EVENT) 1 else 2
                    1003 -> if (mouseMode == MouseMode.ANY_EVENT) 1 else 2
                    1004 -> if (focusReportingMode) 1 else 2
                    1006 -> if (mouseSgrMode) 1 else 2
                    2004 -> if (bracketedPasteMode) 1 else 2
                    2026 -> if (inSynchronizedUpdate) 1 else 2
                    else -> 0
                }
            } else {
                // IRM/SRM/LNM (ANSI "CSI 4/12/20 h/l") are the three
                // non-DEC-private modes this emulator actually tracks
                // (see insertMode/sendReceiveMode/lineFeedNewLineMode's
                // own docs) - reportable here the same way every
                // DEC-private mode above is, rather than falling into the
                // blanket "not modeled" 0 every other ANSI mode still
                // does.
                when (queriedPs) {
                    4 -> if (insertMode) 1 else 2
                    12 -> if (sendReceiveMode) 1 else 2
                    20 -> if (lineFeedNewLineMode) 1 else 2
                    else -> 0
                }
            }
            val prefix = if (private) "?" else ""
            listener.onRespond("\u001B[$prefix$queriedPs;$reportedValue\$y")
            state = State.NORMAL
            csiIntermediate = null
            return
        }

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
            // HPR/VPR - CUF/CUD's own relative-move behavior under a
            // different final byte (part of the same ECMA-48 "positioning"
            // family as CHA/VPA below, just relative instead of absolute).
            // Real senders are rare (most emit 'C'/'B' directly), but a
            // handful of DECPrivate-adjacent programs and some serial-
            // console firmware banners do use these - previously fell to
            // the generic unsupported-final-byte `else`, silently dropping
            // the move.
            'a' -> cursorCol = (cursorCol + (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtMost(buffer.columns - 1)
            'e' -> cursorRow = (cursorRow + (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtMost(buffer.rows - 1)
            // CNL/CPL - move down/up N lines AND home the column to 0 in
            // one shot (CR + CUD/CUU combined) - distinct from a plain 'B'/
            // 'A' move, which leaves the column untouched. Used by a few
            // TUI frameworks (and some install-script/progress-bar output)
            // to start each new status line from column 0 without a
            // separate '\r'. Previously silently dropped like 'a'/'e' above.
            'E' -> { cursorRow = (cursorRow + (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtMost(buffer.rows - 1); cursorCol = 0 }
            'F' -> { cursorRow = (cursorRow - (params.getOrElse(0) { 1 }).coerceAtLeast(1)).coerceAtLeast(0); cursorCol = 0 }
            // CUP/HVP - see originMode's own doc for why this branches on
            // it. Off (the common case): row/col 1 is always the screen's
            // absolute top-left, same behavior this had before DECOM
            // existed. On: row/col 1 means the scroll region's own top-
            // left (scrollTop/scrollLeft) instead, and the result is
            // clamped to STAY inside the region rather than the whole
            // screen - a program that's opted into origin-relative
            // addressing is explicitly asking to never address outside
            // its own region, the same way it can't scroll outside it.
            'H', 'f' -> if (originMode) {
                cursorRow = (scrollTop + (params.getOrElse(0) { 1 }) - 1).coerceIn(scrollTop, scrollBottom)
                cursorCol = (scrollLeft + (params.getOrElse(1) { 1 }) - 1).coerceIn(scrollLeft, scrollRight)
            } else {
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
            // CHT (Cursor Horizontal Tab) - advance N tab stops forward,
            // same stop layout \t/nextTabStop already honors (custom HTS/
            // TBC stops included). CBT (Cursor Backward Tab) is the mirror
            // going left via previousTabStop. Both terminfo-advertised
            // ('cht'/'cbt' in this app's own xterm-256color entry) and
            // used by column-aligned CLI output (e.g. `column -t`,
            // multi-column `ls`) and some pagers/status lines for cheap
            // fixed-width alignment without walking the cursor one column
            // at a time - previously silently dropped by the generic
            // unsupported-final-byte `else`, which left such output
            // starting every field from wherever the cursor happened to
            // already be instead of the intended stop.
            'I' -> repeat(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns)) { cursorCol = nextTabStop(cursorCol) }
            'Z' -> repeat(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns)) { cursorCol = previousTabStop(cursorCol) }
            // REP (Repeat) - redraws whatever [lastGraphicChar] holds N
            // more times, advancing the cursor (with normal autowrap) the
            // same as if that same character/grapheme had actually been
            // sent N times over the wire. See lastGraphicChar's own doc
            // for why this matters beyond spec completeness - this app's
            // own bundled xterm-256color terminfo advertises `rep`, so
            // real ncurses full-screen programs actively depend on the
            // terminal implementing it rather than writing the run out
            // literally. A bare REP with nothing written yet this session
            // (lastGraphicChar still null) is a no-op, matching real
            // terminals' behavior for a REP with no preceding graphic
            // character. Clamped to buffer.columns like the other repeat-
            // count cases just above - a REP can't usefully redraw more
            // copies than a single row could ever hold anyway.
            'b' -> lastGraphicChar?.let { glyph ->
                repeat(params.getOrElse(0) { 1 }.coerceIn(1, buffer.columns)) { writeChar(glyph) }
            }
            // Plain "CSI s" (no params, DECLRMM off) is the long-standing
            // ANSI.SYS-style save-cursor (distinct escape form of the same
            // DECSC/DECRC behavior handled in handleEscape). "CSI Pl;Pr s"
            // while DECLRMM IS enabled is instead DECSLRM (set left/right
            // margins, 1-indexed inclusive) - see scrollLeft/scrollRight
            // and declrmmEnabled's own docs for why the two long-standing-
            // vs-DECSLRM meanings of this one final byte have to stay
            // gated on that mode rather than being disambiguated by
            // params alone (a real program could conceivably send a
            // no-op "CSI 1;80 s" expecting margin-set behavior, which
            // would be indistinguishable from an unusual save-cursor call
            // otherwise). No params (or an out-of-order Pl>=Pr) resets to
            // the full width, same "invalid range = full extent" fallback
            // DECSTBM's own 'r' case uses for rows.
            's' -> if (declrmmEnabled) {
                val left = (params.getOrElse(0) { 1 } - 1).coerceIn(0, buffer.columns - 1)
                val right = (params.getOrElse(1) { buffer.columns } - 1).coerceIn(0, buffer.columns - 1)
                if (left < right) {
                    scrollLeft = left
                    scrollRight = right
                } else {
                    scrollLeft = 0
                    scrollRight = buffer.columns - 1
                }
                // Per spec, DECSLRM homes the cursor to the new region's
                // top-left corner, mirroring DECSTBM's own 'r' case just
                // above (which homes to (scrollTop, 0)) - just also
                // respecting the new left margin on the column axis.
                cursorRow = scrollTop
                cursorCol = scrollLeft
            } else {
                savedCursorRow = cursorRow; savedCursorCol = cursorCol
            }
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
            'm' -> applySgr(params, underlineSubStyle)
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
            // Primary Device Attributes (DA1), `CSI c` or `CSI 0 c` - "what
            // kind of terminal are you?" Distinct from the ESC-c (RIS, full
            // reset) branch above this whenClause's caller dispatches on -
            // this is the CSI form, params-based like DSR just above it.
            // Never answered before, which is the exact same class of bug
            // DSR/CPR's own doc describes for starship: any remote-side
            // shell, multiplexer, or prompt framework (bash/zsh completion
            // probing terminal capabilities, tmux/screen sanity-checking
            // the terminal on attach, starship/powerlevel10k's own startup
            // probes, ssh's terminal-type negotiation on some servers) that
            // sends this and blocks waiting for a reply sees nothing come
            // back and has to sit out its own internal timeout before
            // falling back - which is what read as PTY/SSH startup and
            // per-keystroke lag, not a rendering delay: nothing was slow to
            // DRAW, the remote side was stuck waiting on a reply this
            // emulator was silently never going to send. DA1 ("CSI c")
            // replies with the standard VT100-with-AVO identification
            // (matches what xterm/most terminfo "xterm-256color" entries
            // expect a DA1-querying peer to receive); DA2 ("CSI > c",
            // secondaryDA above) is a DIFFERENT query asking for
            // terminal-version info, not terminal-class info, and expects
            // the "Pp;Pv;Pc" shaped reply below instead - answering it
            // with DA1's reply is a malformed response the querying side
            // won't recognize as a valid DA2 answer, so it still sits out
            // its timeout. "64" here is an arbitrary but plausible xterm
            // patch-level; "0" is the (unused) ROM cartridge param.
            'c' -> if (secondaryDA) {
                listener.onRespond("\u001B[>0;64;0c")
            } else if (params.getOrElse(0) { 0 } == 0) {
                listener.onRespond("\u001B[?1;2c")
            }
            // TBC (Tab Clear), "CSI g" / "CSI 0 g" clears the stop at the
            // cursor column, "CSI 3 g" clears every stop. Same silent-drop
            // history as HTS above - a program that explicitly clears its
            // tab layout (before laying out its own) kept tabbing into the
            // untouched default stops instead, which is the "tabs land in
            // the wrong column after this program redraws" symptom.
            'g' -> when (params.getOrElse(0) { 0 }) {
                0 -> ensureTabStops().remove(cursorCol)
                3 -> tabStops = mutableSetOf()
                // Ps=1/2 clear LINE tab stops rather than column ones - see
                // lineTabStops' own doc. Ps=1 clears just the current line;
                // a null lineTabStops (nothing ever set one - the common
                // case, since no SET sequence is modeled) means there's
                // nothing to remove, so the `?.` is a plain no-op rather
                // than materializing an empty set the way ensureTabStops()
                // would for the column case.
                1 -> lineTabStops?.remove(cursorRow)
                2 -> lineTabStops = mutableSetOf()
                else -> { /* unrecognized Ps - ignore */ }
            }
            // DECSCUSR ("CSI Ps SP q", intermediate space) sets the cursor's
            // on-screen shape; XTVERSION ("CSI > 0 q", secondaryDA prefix,
            // no intermediate) asks for the terminal's name/version -
            // same final byte, disambiguated via csiIntermediate (see its
            // own doc). Both used to be indistinguishable once the
            // intermediate byte was discarded, so DECSCUSR always fell into
            // the unsupported-final-byte `else` below: vim/nvim's
            // insert-mode thin "bar" caret (a common visual cue for "you're
            // in insert mode") was never rendered, always drawing the solid
            // block regardless of mode. XTVERSION's own history is
            // separate: fish/starship send it on startup alongside DA1/DA2/
            // DSR as another capability probe and, same as those, sit out
            // their own timeout waiting for a reply that never came before
            // this - a silent contributor to the same startup-lag class of
            // bug those fix. Ps 0/1/2 are block (blinking/blinking/steady),
            // 3/4 underline, 5/6 bar - blink vs. steady isn't modeled
            // separately (no cursor-blink timer exists here, unlike SGR 5's
            // text-blink timer), so both collapse to the same shape.
            // XTVERSION's reply shape is xterm's own convention: DCS > |
            // <name>(<version>) ST; the exact text is cosmetic, a querying
            // program only needs a well-formed reply to stop waiting.
            'q' -> if (csiIntermediate == ' ') {
                cursorStyle = when (params.getOrElse(0) { 1 }) {
                    3, 4 -> CursorStyle.UNDERLINE
                    5, 6 -> CursorStyle.BAR
                    else -> CursorStyle.BLOCK
                }
            } else if (secondaryDA && params.getOrElse(0) { 0 } == 0) {
                listener.onRespond("\u001BP>|Terminator(1.0)\u001B\\")
            }
            // IRM (Insert Mode, "CSI 4 h/l") - see insertMode's own doc.
            // Alongside SRM (CSI 12 h/l, see sendReceiveMode's own doc)
            // and LNM (CSI 20 h/l, see lineFeedNewLineMode's own doc),
            // these are the only three non-DEC-private modes this
            // emulator tracks; every other ANSI SM/RM mode (KAM/2, etc.)
            // has no real-world sender worth modeling and stays in the
            // blanket ignore below. A single "CSI 4;12;20 h" can set more
            // than one of these at once per spec, so each is checked
            // independently via params.contains rather than an exclusive
            // when/else.
            'h', 'l' -> {
                val on = ch == 'h'
                if (params.contains(4)) insertMode = on
                if (params.contains(12)) sendReceiveMode = on
                if (params.contains(20)) lineFeedNewLineMode = on
            }
            // XTWINOPS ("CSI Ps ; Ps ; Ps t") - xterm's window-manipulation
            // family. The overwhelming majority of Ps values (1/2/3/4/5/6/
            // 7/9/10/11/13/20/21 - de/iconify, move, resize in pixels,
            // maximize, get window position, etc.) describe operations a
            // single full-screen mobile app window has no meaningful
            // equivalent for, so those fall to the same unsupported-`else`
            // silence every other unmodeled Ps already does below - not
            // regressions, genuinely inapplicable here. Three sub-cases
            // ARE meaningful and implemented:
            // - Ps=18 (report text-area size, characters) / Ps=19 (report
            //   screen size, characters) - vim and tmux both query one or
            //   both of these on startup to size themselves independent of
            //   the LINES/COLUMNS environment (which can be stale after a
            //   resize a program hasn't been told about yet); answered
            //   identically since this app has no separate "screen larger
            //   than the text area" concept the way a windowed desktop
            //   terminal might (scrollbars, tab bars eating rows). Reply
            //   shape is "CSI 8 ; rows ; cols t" / "CSI 9 ; rows ; cols t"
            //   per spec. Ps=14 (report size in PIXELS) is deliberately
            //   left unanswered rather than guessed at - TerminalEmulator
            //   itself is never told the cell/window pixel dimensions
            //   (that lives one layer up, in TerminalSession's own
            //   ioctl(TIOCSWINSZ) pixel plumbing - see NativePty), and a
            //   wrong guess is worse than a query a caller simply times
            //   out on the same as it already tolerates for anything else
            //   this terminal doesn't answer.
            // - Ps=22/23 (push/pop window title) - see titleStack's own
            //   doc. Sub-param (second Ps, default 0) selects icon+title
            //   (0), icon only (1), or title only (2) - vim's own
            //   `t_ts`/`t_fs`/'&title' handling is the most common real
            //   sender, saving the shell's title on entry and restoring it
            //   on exit so quitting vim doesn't leave the terminal
            //   permanently retitled to whatever vim's own status line
            //   said. Previously both silently fell to the generic
            //   unsupported-final-byte `else`, so title restore on exit
            //   from such a program simply never happened.
            't' -> when (params.getOrElse(0) { 0 }) {
                18 -> listener.onRespond("\u001B[8;${buffer.rows};${buffer.columns}t")
                19 -> listener.onRespond("\u001B[9;${buffer.rows};${buffer.columns}t")
                22 -> {
                    if (titleStack.size >= 64) titleStack.removeFirst()
                    titleStack.addLast(currentIconName to currentTitle)
                }
                23 -> {
                    val (savedIcon, savedTitle) = titleStack.removeLastOrNull() ?: ("" to "")
                    when (params.getOrElse(1) { 0 }) {
                        1 -> { currentIconName = savedIcon; listener.onIconNameChanged(savedIcon) }
                        2 -> { currentTitle = savedTitle; listener.onTitleChanged(savedTitle) }
                        else -> {
                            currentIconName = savedIcon; listener.onIconNameChanged(savedIcon)
                            currentTitle = savedTitle; listener.onTitleChanged(savedTitle)
                        }
                    }
                }
                else -> { /* window position/resize/(de)iconify etc - not applicable, ignore */ }
            }
            else -> { /* unsupported final byte - ignore */ }
        }
        listener.onCursorMoved(cursorRow, cursorCol)
        state = State.NORMAL
        csiIntermediate = null
    }

    private fun handlePrivateMode(params: List<Int>, finalByte: Char) {
        val enable = finalByte == 'h'
        for (p in params) {
            when (p) {
                25 -> cursorVisible = enable
                // DECARM (Auto Repeat Mode, "CSI ?8 h/l") - see
                // autoRepeatMode's own doc for why this has no actual
                // behavior to toggle on this platform; tracked purely so
                // DECRQM (below) can answer truthfully.
                8 -> autoRepeatMode = enable
                // DECOM (Origin Mode) - see originMode's own doc and the
                // 'H'/'f' CUP case's own doc for what this actually
                // changes. Per spec, toggling it ALSO homes the cursor
                // immediately - to the region's own top-left (scrollTop,
                // scrollLeft) if now on, or the screen's absolute (0,0) if
                // now off - same "mode switch homes the cursor" pattern
                // DECCOLM's own case 3 above already follows, since
                // whatever the cursor was addressing under the OLD
                // interpretation is meaningless under the new one.
                6 -> {
                    originMode = enable
                    cursorRow = if (enable) scrollTop else 0
                    cursorCol = if (enable) scrollLeft else 0
                }
                // DECSCNM (Reverse Video) - see TerminalBuffer.
                // reverseVideoMode's own doc for why this lives on the
                // buffer (the renderer's only handle) rather than as a
                // plain field here the way most other DEC-private modes
                // in this function are.
                5 -> buffer.reverseVideoMode = enable
                // DECCOLM - switch between 80 and 132 columns. Genuinely
                // useful on a tablet-sized layout with room to actually
                // show 132 columns legibly (see onDeccolmChanged's own
                // doc on why a phone-sized screen can reasonably clamp/
                // ignore this instead) - vim/htop/mc and other full-
                // screen TUIs query/set this when a user explicitly asks
                // for a wide layout. Per spec DECCOLM also implies: erase
                // the whole display, reset the scrolling region to the
                // full screen, and home the cursor - modeled here the
                // same way 1049's own enable branch already does the
                // equivalent "mode switch implies a clean redraw" reset,
                // since a running full-screen app's existing content was
                // laid out for the OLD width and is meaningless at the
                // new one.
                3 -> {
                    deccolm132Mode = enable
                    listener.onDeccolmChanged(if (enable) 132 else 80)
                    eraseInDisplay(2)
                    scrollTop = 0
                    scrollBottom = buffer.rows - 1
                    cursorRow = 0
                    cursorCol = 0
                }
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
                // DECAWM - see autoWrapMode's own doc. Defaults to true
                // (xterm's own default) via the property initializer above,
                // so this only needs to react to an explicit h/l for it.
                7 -> autoWrapMode = enable
                // Focus reporting - see focusReportingMode's own doc. Only
                // tracks the flag; actual "ESC[I"/"ESC[O" emission happens
                // in reportFocusChange(), called by the UI layer on real
                // focus transitions.
                1004 -> focusReportingMode = enable
                // Synchronized output - see inSynchronizedUpdate's own doc.
                // Purely advisory (this module doesn't defer any writes on
                // its own), so simply mirrors the h/l straight into the
                // flag the UI layer's render loop can check.
                2026 -> inSynchronizedUpdate = enable
                // Bracketed paste (see bracketedPasteMode's own doc) - was
                // previously matched by the generic `else` branch below
                // (silently ignored), which is why pasted multi-line text
                // was never distinguishable from typed input on the shell
                // side: without the ESC[200~/201~ wrapper a paste containing
                // newlines reads to the shell exactly like the user pressing
                // Enter after every line, so each line ran as its own
                // command instead of landing as one editable blob.
                2004 -> bracketedPasteMode = enable
                // DECLRMM - gates whether "CSI Pl;Pr s" means DECSLRM
                // (set left/right margins) or the older ANSI.SYS
                // save-cursor - see declrmmEnabled's own doc. Per spec,
                // turning it OFF also resets the margins back to the full
                // screen width immediately (a program that disables
                // DECLRMM expects to get plain full-width behavior back,
                // not to have some earlier margin silently keep applying
                // to writes/wraps that can no longer even change it).
                69 -> {
                    declrmmEnabled = enable
                    if (!enable) {
                        scrollLeft = 0
                        scrollRight = buffer.columns - 1
                    }
                }
                else -> { /* unsupported private mode - ignore */ }
            }
        }
    }

    /**
     * Kitty keyboard protocol (https://sw.kovidgoyal.net/kitty/keyboard-protocol/)
     * control sequences - the four ways a program manages the progressive-
     * enhancement flag stack, all sharing the 'u' final byte and
     * disambiguated by the prefix byte already stripped into [raw]/params
     * by the caller (see this file's own doc at the "CSI ? u" dispatch
     * site above for why this runs before the generic private/secondaryDA
     * branches):
     *
     * - QUERY  "CSI ? u"            -> replies "CSI ? <flags> u" with the
     *   currently active flags (0 if the protocol was never engaged).
     *   Vim, kakoune, and other kitty-aware editors send this on startup to
     *   detect support before turning any enhancement on; a terminal that
     *   never replies leaves them assuming legacy-only input, exactly the
     *   same "sits out its own timeout" class of bug DSR/DA1/XTVERSION's
     *   own docs above describe for their own probes - except here the
     *   consequence isn't a hang, it's the editor silently never offering
     *   the more precise key handling (disambiguated Ctrl/Alt/Shift
     *   combinations, distinct press/release events) it otherwise would.
     * - PUSH   "CSI > <flags> u"    -> pushes the CURRENT flags onto
     *   kittyFlagStack, then makes <flags> (default 1 if omitted) active.
     *   This is the enable form: a program pushes its own desired flags on
     *   entry, expecting a matching POP to restore whatever was active
     *   before it started (typically 0, the shell's own legacy-only
     *   default) on exit - the same push/pop discipline DECSC/DECRC-style
     *   save/restore already follows elsewhere in this file, just for this
     *   protocol's own state instead of cursor position.
     * - POP    "CSI < <count> u"    -> pops <count> (default 1) entries off
     *   kittyFlagStack, making the top of what's left active (0 - fully
     *   legacy - if the stack empties out). A pop past an empty stack is
     *   harmless: it just leaves flags at 0, same as if PUSH had never been
     *   called, rather than underflowing.
     * - SET    "CSI = <flags> ; <mode> u" -> sets flags directly, WITHOUT
     *   touching the stack at all (distinct from PUSH). <mode> (default 1)
     *   controls how <flags> combines with whatever's already active: 1 =
     *   replace outright, 2 = OR in (add bits), 3 = AND-NOT (clear bits).
     *   Real programs use this far less than PUSH/POP, but some (notably
     *   ones that want to add ONE specific bit - e.g. just "report event
     *   types" - without assuming what else might already be enabled by an
     *   outer program) rely on mode 2/3 specifically for that.
     *
     * Deliberately doesn't attempt the OUTPUT half of the protocol (the
     * actual "CSI <code>[...] u"-shaped key reports a program expects to
     * RECEIVE once it's enabled disambiguation) here - that's a key-input-
     * encoding concern, symmetric with how DECCKM's applicationCursorKeys
     * flag is exposed as a plain var for the UI layer (PhysicalKeyEvent /
     * VirtualKeyBar) to read and encode against, rather than this parser
     * (which only ever sees PTY OUTPUT, never raw key input) trying to
     * synthesize key-report bytes itself.
     */
    private fun handleKittyKeyboardProtocol(raw: String, params: List<Int>) {
        when {
            raw.startsWith("?") -> {
                listener.onRespond("\u001B[?${kittyKeyboardFlags}u")
            }
            raw.startsWith(">") -> {
                kittyFlagStack.addLast(kittyKeyboardFlags)
                // Same runaway-growth guard as paramBuffer/oscBuffer above -
                // a program that pushes without ever popping (buggy, or
                // just exits without cleanup and relies on RIS/reset()
                // instead) shouldn't be able to grow this without bound
                // across a long-lived session with many such programs run
                // in sequence.
                if (kittyFlagStack.size > 64) kittyFlagStack.removeFirst()
                kittyKeyboardFlags = params.getOrElse(0) { 1 }.coerceIn(0, 31)
            }
            raw.startsWith("<") -> {
                val count = params.getOrElse(0) { 1 }.coerceIn(1, 4096)
                repeat(count) { if (kittyFlagStack.isNotEmpty()) kittyFlagStack.removeLast() }
                kittyKeyboardFlags = kittyFlagStack.lastOrNull() ?: 0
            }
            raw.startsWith("=") -> {
                val flags = params.getOrElse(0) { 0 }.coerceIn(0, 31)
                val mode = params.getOrElse(1) { 1 }
                kittyKeyboardFlags = when (mode) {
                    2 -> kittyKeyboardFlags or flags
                    3 -> kittyKeyboardFlags and flags.inv()
                    else -> flags
                }.coerceIn(0, 31)
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
            //
            // Sized to comfortably fit an OSC 1337 inline image (see
            // onInlineImageData's own doc) rather than the old flat 8192 -
            // base64 runs ~4/3 the size of the source bytes, so this
            // still leaves headroom under Kitty's own 4 MiB single-chunk
            // APC guard (apcBuffer below) for a same-size image sent the
            // "one big OSC 1337" way instead of Kitty's chunked APC way.
            // Every OTHER OSC code this emulator handles (title, OSC 8
            // URL, OSC 52 clipboard, OSC 9/777 notification text) is
            // legitimately tiny and hits its own terminator long before
            // this ceiling - raising it only changes how long a garbled/
            // truncated stream on one of THOSE codes takes to give up,
            // not what any of them can legitimately contain.
            if (oscBuffer.length >= 6_000_000) {
                state = State.NORMAL
                return
            }
            oscBuffer.append(ch)
        }
    }

    private fun finishOsc() {
        val content = oscBuffer.toString()
        val sepIdx = content.indexOf(';')
        if (sepIdx < 0) return
        val code = content.substring(0, sepIdx)
        val rest = content.substring(sepIdx + 1)
        when (code) {
            "0", "2" -> { currentTitle = rest; listener.onTitleChanged(rest) }
            // OSC 1: icon name - see onIconNameChanged's own doc for why
            // this is kept distinct from onTitleChanged rather than folded
            // into the same callback.
            "1" -> { currentIconName = rest; listener.onIconNameChanged(rest) }
            // OSC 9 (iTerm2/Growl-style notification): "9 ; <message>" -
            // the entire `rest` (already split off the "9" code above by
            // the top-level `sepIdx`) IS the message body, with no title
            // field at all - see onNotification's own doc for why [title]
            // is passed as null here rather than inventing one.
            "9" -> listener.onNotification(title = null, body = rest)
            // OSC 777 (rxvt-unicode/urxvt notification): "777 ; notify ;
            // <title> ; <body>". The literal "notify" subcommand is the
            // only one this emulator acts on - urxvt defines a handful of
            // other OSC 777 subcommands (e.g. its own clipboard variants)
            // that aren't notifications at all and would be actively wrong
            // to surface through onNotification, so anything else in that
            // first field is silently ignored rather than guessed at.
            "777" -> {
                val parts = rest.split(';', limit = 3)
                if (parts.getOrNull(0) == "notify") {
                    listener.onNotification(
                        title = parts.getOrNull(1)?.ifEmpty { null },
                        body = parts.getOrNull(2) ?: ""
                    )
                }
            }
            // OSC 1337 File= (iTerm2 inline images): "1337 ; File=
            // [key=value;...]:<base64>". The control-string portion
            // (before the ':') is a ';'-separated key=value list -
            // "name" (base64'd filename, cosmetic only, nothing here
            // saves to a filesystem), "size" (byte count, purely
            // advisory), "width"/"height" (see onInlineImageData's own
            // doc), "preserveAspectRatio" (0/1, not modeled - this
            // decoder always preserves aspect ratio, there's no
            // stretch-to-fill path to opt out of), and "inline" (0/1) -
            // which is the field that actually decides whether this
            // fires onInlineImageData at all: inline=0 (or the key
            // absent entirely, iTerm2's own default) means "offer this
            // as a downloadable attachment", not "show it in the
            // stream" - there's no download-manager/Files-app handoff
            // surface this module could route that through, so it's
            // silently dropped rather than mis-rendered inline against
            // the sender's actual intent.
            "1337" -> {
                val colonIdx = rest.indexOf(':')
                if (colonIdx >= 0) {
                    val controlString = rest.substring(0, colonIdx)
                    val base64Data = rest.substring(colonIdx + 1)
                    val fields = controlString.split(';').mapNotNull { field ->
                        val eqIdx = field.indexOf('=')
                        if (eqIdx < 0) null else field.substring(0, eqIdx) to field.substring(eqIdx + 1)
                    }.toMap()
                    if (fields["inline"] == "1" && base64Data.isNotEmpty()) {
                        // Try decoding entirely in-module first via the
                        // same PNG decoder the Kitty graphics path
                        // already has (decodePng, below) - PNG is by far
                        // the most common OSC 1337 payload (screenshot/
                        // plot tools overwhelmingly emit it), and this
                        // avoids a round-trip through the caller's
                        // android.graphics.BitmapFactory entirely for
                        // that common case. Only genuinely non-PNG
                        // payloads (JPEG/GIF/WebP, or a PNG this
                        // decoder's own subset doesn't cover - see
                        // decodePng's own doc on the bit-depth/interlace/
                        // color-type restrictions) fall through to
                        // onInlineImageData for the caller to decode via
                        // whatever platform image codecs it has.
                        val decodedInline = try {
                            java.util.Base64.getDecoder().decode(base64Data)
                        } catch (e: IllegalArgumentException) {
                            null
                        }
                        val png = decodedInline?.let { decodePng(it, imageId = 0) }
                        if (png != null) {
                            placeDecodedInlineImage(png.pixels, png.width, png.height)
                        } else {
                            listener.onInlineImageData(base64Data, fields["width"], fields["height"])
                        }
                    }
                }
            }
            // OSC 8 hyperlink: "8 ; params ; uri". params (e.g. an "id=xxx"
            // some programs use to group multiple non-contiguous text runs
            // under one link) isn't acted on here - nothing downstream
            // needs the grouping, only the URI text gets attached to
            // cells - so it's parsed past and discarded. An empty uri is
            // how a program CLOSES the link that's currently open (see
            // curHyperlink's own doc); ifEmpty{null} is what makes that
            // closing form turn curHyperlink back off instead of attaching
            // the literal empty string to every subsequent cell as a
            // "link" that then does nothing when tapped.
            "8" -> {
                val uriSepIdx = rest.indexOf(';')
                val uri = if (uriSepIdx >= 0) rest.substring(uriSepIdx + 1) else ""
                curHyperlink = uri.ifEmpty { null }
            }
            // OSC 52 clipboard: "52 ; selection ; base64-or-?". SET
            // ("...;<base64>") is surfaced as a raw base64 string - left
            // un-decoded here since decoding needs nothing this module has
            // access to, only to keep this module platform-agnostic (no
            // android.* imports anywhere in it); the base64 itself is
            // exactly what came off the wire either way. GET ("...;?", a
            // program asking to READ BACK the system clipboard's actual
            // contents through the pty) is now surfaced too, via the
            // separate onClipboardGet callback - see that callback's own
            // doc for why this module still refuses to decide whether to
            // actually answer it (that's the caller's job, gated on an
            // explicit opt-in setting) and just hands the selection letter
            // over unconditionally either way.
            "52" -> {
                val dataSepIdx = rest.indexOf(';')
                if (dataSepIdx >= 0) {
                    val selection = rest.substring(0, dataSepIdx)
                    val payload = rest.substring(dataSepIdx + 1)
                    if (payload == "?") {
                        listener.onClipboardGet(selection)
                    } else if (payload.isNotEmpty()) {
                        listener.onClipboardSet(payload)
                    }
                }
            }
            // OSC 133 shell-integration marks (fish/starship/zsh's own
            // integration, and others): A = prompt start, B = end of
            // prompt/start of what the user types, C = start of the
            // command's output, D[;exitcode] = command finished. Comment
            // above previously noted these were parsed-past but never
            // acted on ("starship sends this"); this surfaces each mark
            // (with the row it landed on and, for D, the exit code if one
            // was sent) to the listener so a UI layer CAN build on top of
            // it (e.g. jump-to-previous-command navigation) without this
            // module needing to know anything about that UI itself - it
            // only hands over the raw mark data.
            "133" -> {
                val marker = rest.getOrNull(0)
                if (marker != null) {
                    val exitCode = if (marker == 'D') {
                        rest.substringAfter(';', "").toIntOrNull()
                    } else null
                    // Only 'A' (prompt-start) marks feed jump navigation -
                    // see promptMarks' own doc for why B/C/D aren't kept
                    // here too. Captured with buffer.scrollback.size at
                    // THIS instant (before whatever the shell prints next
                    // has a chance to push more lines into it) so the mark
                    // pins the exact line the prompt started on - see
                    // ShellIntegrationMark's own doc for the addressing.
                    if (marker == 'A') {
                        if (promptMarks.size >= MAX_PROMPT_MARKS) promptMarks.removeFirst()
                        promptMarks.addLast(
                            ShellIntegrationMark(marker, cursorRow, buffer.scrollback.size, exitCode)
                        )
                    }
                    listener.onShellIntegrationMark(marker, cursorRow, exitCode)
                }
            }
            // OSC 4: palette-entry set/query - "4 ; <index> ; <spec>",
            // repeatable ("4;1;#ff0000;3;#ffff00" sets slots 1 AND 3 in one
            // sequence, per spec). <spec> is either "?" (query) or an
            // X11/xterm color spec - only the "#RGB"/"#RRGGBB"/"#RRRGGGBBB"/
            // "#RRRRGGGGBBBB" hex forms and "rgb:R/G/B" (any 1-4 hex digits
            // per channel) are handled (see parseXtermColorSpec below) -
            // named X11 colors ("rgb:red", bare "red") are NOT resolved
            // (would need a ~600-entry X11 color name table for something
            // real Kitty-protocol senders never actually emit for OSC 4,
            // unlike OSC 4's hex/rgb: forms which theme scripts use
            // constantly - see e.g. base16-shell's whole approach of
            // blasting all 16 slots via OSC 4 on shell startup).
            "4" -> {
                val parts = rest.split(';')
                var i = 0
                while (i + 1 < parts.size) {
                    val index = parts[i].toIntOrNull()
                    val spec = parts[i + 1]
                    if (index != null) {
                        if (spec == "?") {
                            listener.onQueryDynamicColor(4, index, isDynamic = false)
                        } else {
                            parseXtermColorSpec(spec)?.let { buffer.setPaletteOverride(index, it) }
                        }
                    }
                    i += 2
                }
            }
            // OSC 10/11/12: dynamic foreground/background/cursor color
            // set/query - "10 ; <spec>" (also repeatable/multi-target per
            // spec, e.g. "10;#fff;11;#000" in one sequence, same shape as
            // OSC 4 above - xterm documents OSC 10 itself as accepting a
            // SECOND ;spec for 11 chained on, though in practice almost
            // every real sender just fires 10/11/12 as separate OSCs; the
            // same index/spec pairing loop below handles either shape
            // uniformly).
            "10", "11", "12" -> {
                val dynamicSlot = code.toInt()
                val parts = rest.split(';')
                for (spec in parts) {
                    if (spec == "?") {
                        listener.onQueryDynamicColor(dynamicSlot, dynamicSlot, isDynamic = true)
                    } else {
                        parseXtermColorSpec(spec)?.let { buffer.setDynamicColorOverride(dynamicSlot, it) }
                    }
                }
            }
            // OSC 104/110/111/112: reset palette entry / dynamic
            // foreground / background / cursor color back to the theme
            // default - the other half of OSC 4/10/11/12 SET above. OSC
            // 104 with no args at all (bare "104" - rest is empty since
            // finishOsc's own sepIdx<0 early-return only fires when
            // there's no ';' whatsoever, but "104;" with a trailing empty
            // segment is exactly how "reset ALL slots" is spelled per
            // spec) resets every palette override; "104;1;3" resets only
            // slots 1 and 3.
            "104" -> {
                val indices = rest.split(';').mapNotNull { it.toIntOrNull() }
                if (indices.isEmpty()) buffer.resetPaletteOverride(null) else indices.forEach { buffer.resetPaletteOverride(it) }
            }
            "110" -> buffer.resetDynamicColorOverride(TerminalBuffer.DYNAMIC_COLOR_FOREGROUND)
            "111" -> buffer.resetDynamicColorOverride(TerminalBuffer.DYNAMIC_COLOR_BACKGROUND)
            "112" -> buffer.resetDynamicColorOverride(TerminalBuffer.DYNAMIC_COLOR_CURSOR)
            // OSC 7: current working directory report - "7 ; file://<host>/
            // <path>". Only the path component is surfaced to the listener
            // (see Listener.onWorkingDirectoryChanged's own doc for why);
            // <host> itself is discarded (it's almost always either empty
            // or the machine's own hostname, per spec's own "file URL"
            // convention - not meaningfully actionable for a caller that
            // just wants "what directory is this shell in"). Percent-
            // decoded since a path containing a space/non-ASCII byte
            // arrives percent-encoded per the file:// URI scheme (e.g. a
            // directory literally named "My Files" arrives as
            // "My%20Files").
            "7" -> {
                val withoutScheme = rest.removePrefix("file://")
                val pathStart = withoutScheme.indexOf('/')
                if (pathStart >= 0) {
                    val rawPath = withoutScheme.substring(pathStart)
                    val decoded = try {
                        java.net.URLDecoder.decode(rawPath, "UTF-8")
                    } catch (e: Exception) {
                        rawPath
                    }
                    listener.onWorkingDirectoryChanged(decoded)
                }
            }
        }
    }

    // Parses an xterm color spec as used by OSC 4/10/11/12's SET form
    // (see spec's own "ctlseqs" ColorSpec grammar) into this codebase's
    // packed ARGB Int convention. Handles the two forms real senders
    // actually emit: "#RGB"/"#RRGGBB"/"#RRRGGGBBB"/"#RRRRGGGGBBBB" (1-4
    // hex digits per channel, MSB-truncated down to 8 bits per channel
    // the same way xterm's own doc describes - e.g. "#1" for a channel
    // means 0x1 scaled as if it were the high nibble, i.e. 0x11) and
    // "rgb:R/G/B" (1-4 hex digits per channel, '/'-separated, no length
    // requirement that all three channels match each other). Named X11
    // colors are NOT handled (see the OSC 4 case's own doc on why) -
    // returns null for those, same as for any other unparseable spec, so
    // callers simply skip the SET rather than corrupting a slot with a
    // wrong color from a partial parse.
    private fun parseXtermColorSpec(spec: String): Int? {
        fun scaleChannel(hex: String): Int? {
            if (hex.isEmpty() || hex.length > 4 || hex.any { !it.isDigit() && it !in 'a'..'f' && it !in 'A'..'F' }) return null
            val value = hex.toIntOrNull(16) ?: return null
            val maxForLen = (1 shl (hex.length * 4)) - 1
            // Scale whatever bit-depth was given down to 8 bits by taking
            // the high 8 bits of the fully-extended value - matches
            // xterm's own "the value is scaled" wording (e.g. a 4-hex-
            // digit "ffff" channel is full-intensity == 0xFF, a 1-hex-
            // digit "f" is ALSO full-intensity == 0xFF, not 0x0F).
            return ((value.toLong() * 255) / maxForLen).toInt().coerceIn(0, 255)
        }
        if (spec.startsWith("#")) {
            val hex = spec.substring(1)
            if (hex.length % 3 != 0 || hex.isEmpty()) return null
            val chanLen = hex.length / 3
            val r = scaleChannel(hex.substring(0, chanLen)) ?: return null
            val g = scaleChannel(hex.substring(chanLen, chanLen * 2)) ?: return null
            val b = scaleChannel(hex.substring(chanLen * 2, chanLen * 3)) ?: return null
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        if (spec.startsWith("rgb:")) {
            val channels = spec.substring(4).split('/')
            if (channels.size != 3) return null
            val r = scaleChannel(channels[0]) ?: return null
            val g = scaleChannel(channels[1]) ?: return null
            val b = scaleChannel(channels[2]) ?: return null
            return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
        return null
    }

    // Set when the DCS terminator's ESC byte has been seen but its
    // required follow-up '\' (forming ST, ESC \) hasn't arrived yet - same
    // shape as oscPendingSt, kept separate since the two states (DCS vs
    // OSC) are never simultaneously active.
    private var dcsPendingSt = false

    private fun handleDcs(ch: Char) {
        if (dcsPendingSt) {
            dcsPendingSt = false
            state = State.NORMAL
            finishDcs()
            if (ch == '\\') {
                return
            }
            // Same "no real ST, a fresh escape butted up against us"
            // recovery as handleOsc's own equivalent branch - see its doc.
            state = State.ESCAPE
            handleEscape(ch)
            return
        }
        if (ch == '\u001B') {
            dcsPendingSt = true
            return
        }
        // Some programs (rare, but xterm itself accepts it) terminate a
        // DCS with a bare BEL instead of ST - accept both like handleOsc
        // does, rather than leaving the parser stuck waiting for an ST
        // that never comes.
        if (ch == '\u0007') {
            state = State.NORMAL
            finishDcs()
            return
        }
        // Only the SIXEL introducer is recognized/decoded on the receive
        // side (see dcsIsSixel's own doc) - anything else (a DECRQSS
        // query, a program probing for termcap-string support, etc.) is
        // collected into dcsBuffer as harmless inert bytes and simply
        // discarded whole once the terminator arrives, same as this
        // emulator already silently no-ops any CSI/OSC code it doesn't
        // model. The 'q' final byte of "ESC P <params> q" is what commits
        // this DCS to being a Sixel image - see the check below.
        if (!dcsIsSixel && dcsBuffer.isEmpty() && (ch == 'q' || (ch.isDigit() || ch == ';'))) {
            // Still inside "ESC P <params>" - params are plain digits/';'
            // (e.g. Sixel's optional "ESC P 0 ; 0 ; 8 q" macro/background/
            // grid-size prefix). Once 'q' itself arrives, this DCS is a
            // Sixel image and everything AFTER 'q' is raw sixel body, not
            // more params - so 'q' both sets the flag and is NOT appended
            // to dcsBuffer (dcsBuffer from here on is body-only, which is
            // exactly what decodeSixel expects).
            if (ch == 'q') {
                dcsIsSixel = true
            }
            // Digits/';' before 'q' are params this emulator doesn't act on
            // (Sixel macro id / background-fill mode / horizontal grid
            // size - none of which this simplified decoder implements);
            // dropped rather than buffered since they're never re-read.
            return
        }
        // Runaway-growth guard, sized far larger than OSC's 8192 - Sixel
        // bodies are legitimately large (see dcsBuffer's own doc). 2 MiB of
        // ASCII sixel data decodes to a multi-hundred-KB image, comfortably
        // past anything a terminal-width image actually needs; a stream
        // that blows through this without ever reaching ST is almost
        // certainly garbled, not a real large image.
        if (dcsBuffer.length >= 2_000_000) {
            state = State.NORMAL
            dcsIsSixel = false
            return
        }
        dcsBuffer.append(ch)
    }

    private fun finishDcs() {
        if (dcsIsSixel) {
            decodeAndPlaceSixel(dcsBuffer.toString())
        }
        dcsIsSixel = false
    }

    private fun handleApc(ch: Char) {
        if (apcPendingSt) {
            apcPendingSt = false
            state = State.NORMAL
            finishApc()
            if (ch == '\\') {
                return
            }
            // Same "no real ST, a fresh escape butted up against us"
            // recovery as handleOsc/handleDcs's own equivalent branches.
            state = State.ESCAPE
            handleEscape(ch)
            return
        }
        if (ch == '\u001B') {
            apcPendingSt = true
            return
        }
        // Some programs terminate with a bare BEL instead of ST - accept
        // both, same as handleOsc/handleDcs do.
        if (ch == '\u0007') {
            state = State.NORMAL
            finishApc()
            return
        }
        // Runaway-growth guard for a single APC sequence's own buffer -
        // deliberately much smaller than kittyChunkBuffer's cap (this is
        // one chunk of a possibly-multi-chunk transmission, not the whole
        // image; the kitty spec itself recommends clients keep individual
        // chunks around 4096 bytes of base64, though this doesn't enforce
        // that upper bound strictly - it only guards against a single
        // chunk that never terminates at all). 4 MiB comfortably covers
        // any real single chunk plus its key=value control prefix with
        // large margin.
        if (apcBuffer.length >= 4_000_000) {
            state = State.NORMAL
            apcBuffer.clear()
            return
        }
        apcBuffer.append(ch)
    }

    private fun finishApc() {
        val raw = apcBuffer.toString()
        apcBuffer.clear()
        // Only Kitty graphics ('G' marker) is understood on the receive
        // side - any other APC (some programs use APC for private
        // scratch signaling this emulator has no business acting on) is
        // silently discarded whole, same "ignore what we don't model"
        // policy as unrecognized DCS/OSC/CSI codes elsewhere in this file.
        if (raw.isEmpty() || raw[0] != 'G') return
        val semi = raw.indexOf(';')
        val controlPart = if (semi >= 0) raw.substring(1, semi) else raw.substring(1)
        val payloadPart = if (semi >= 0) raw.substring(semi + 1) else ""
        val fields = parseKittyControlFields(controlPart)

        val moreChunks = fields["m"] == "1"
        val inProgress = kittyChunkedCommand
        if (inProgress != null) {
            // Continuation chunk of an already-started multi-chunk
            // transmission - per spec this chunk's own key=value fields
            // (if any were even sent - continuation chunks are usually
            // bare) are ignored in favor of the first chunk's, and only
            // its payload matters.
            kittyChunkBuffer.append(payloadPart)
            if (!moreChunks) {
                val command = inProgress
                kittyChunkedCommand = null
                val fullPayload = kittyChunkBuffer.toString()
                kittyChunkBuffer.clear()
                handleKittyCommand(command, fullPayload)
            } else if (kittyChunkBuffer.length >= 64_000_000) {
                // Guard against a transmission that never sends its final
                // m=0 chunk - 64 MiB of accumulated base64 is already far
                // past any legitimate single-image payload (see
                // kittyImageIdCounter's neighboring doc for real-world
                // image sizes), so bail out rather than let this grow
                // without bound.
                kittyChunkedCommand = null
                kittyChunkBuffer.clear()
            }
            return
        }
        if (moreChunks) {
            // First chunk of a new multi-chunk transmission - stash the
            // control fields (see kittyChunkedCommand's own doc for why
            // only the first chunk's fields are kept) and start
            // accumulating payload.
            kittyChunkedCommand = fields
            kittyChunkBuffer.clear()
            kittyChunkBuffer.append(payloadPart)
            return
        }
        // Complete, unchunked command.
        handleKittyCommand(fields, payloadPart)
    }

    // Parses Kitty's "key=value,key=value,..." control-field syntax into a
    // plain map. Deliberately tolerant of unknown keys (simply kept in the
    // map, ignored by whichever handleKittyCommand branch doesn't look for
    // them) since the protocol is still evolving and a future key this
    // emulator doesn't yet act on shouldn't corrupt parsing of the keys it
    // does. Values are always plain ASCII digits/letters in the real
    // protocol (numbers, or single-letter action/delete-mode codes) - no
    // quoting/escaping to worry about, unlike OSC's title-string handling.
    private fun parseKittyControlFields(controlPart: String): Map<String, String> {
        if (controlPart.isEmpty()) return emptyMap()
        val out = HashMap<String, String>(8)
        for (pair in controlPart.split(',')) {
            val eq = pair.indexOf('=')
            if (eq <= 0) continue
            out[pair.substring(0, eq)] = pair.substring(eq + 1)
        }
        return out
    }

    /**
     * Decodes a Sixel image body (everything after the "ESC P <params> q"
     * introducer's 'q', up to but not including the terminating ST) into a
     * plain ARGB pixel raster, then hands it to [buffer] to be anchored at
     * the current cursor position - see [TerminalBuffer.placeImage] for how
     * that anchoring/scroll-following works.
     *
     * Sixel encodes an image column-major in bands of 6 vertical pixels at
     * a time ("a sixel"): each data byte in the range 0x3F..0x7E ('?'..'~')
     * packs 6 one-bit-per-pixel values for the CURRENT color, for 6 stacked
     * pixel rows starting at the band's current y-offset, at the next x
     * column. Bit 0 (value 1) of the byte is the TOPMOST of the 6 rows;
     * see the bitIndex loop below. A full image is built by repeating this
     * across a band's width, then moving to the next 6-row band with '-'
     * (newline) or back to column 0 of the SAME band with '$' (carriage
     * return, used to overlay a second color into pixels already drawn by
     * an earlier color in this band without disturbing its y-offset).
     *
     * Only the commands real-world encoders (img2sixel, chafa, mpv, xterm
     * itself) actually emit are implemented: '#' (color select/define),
     * '!' (run-length repeat), '$'/'-' (band control), and the sixel data
     * bytes themselves. Raster attributes ('"', pixel aspect ratio/size
     * hint) are accepted and skipped since this decoder derives the
     * image's actual dimensions from the data it draws rather than
     * trusting a declared size - a mismatched/lying raster header (not
     * uncommon from hand-rolled encoders) would otherwise clip or
     * misplace the real pixels.
     */
    private fun decodeAndPlaceSixel(body: String) {
        // Sixel's own default 16-color VT340 palette (indices 0-15) -
        // present so a stream that never bothers defining its own colors
        // (rare in practice, but valid per spec) still renders something
        // recognizable rather than an all-black image. Real encoders
        // almost always emit explicit '#'-definitions for every index they
        // use, which simply overwrite these defaults in the loop below.
        val palette = HashMap<Int, Int>(64).apply {
            // 0=black 1=blue 2=red 3=green 4=magenta 5=cyan 6=yellow 7=white
            // (dim/standard tones), 8-15 the "bright" counterparts - values
            // taken from the VT340 reference palette xterm itself ships.
            this[0] = argb(0, 0, 0); this[1] = argb(20, 20, 80); this[2] = argb(80, 13, 13)
            this[3] = argb(20, 80, 20); this[4] = argb(80, 20, 80); this[5] = argb(20, 80, 80)
            this[6] = argb(80, 80, 20); this[7] = argb(53, 53, 53); this[8] = argb(26, 26, 26)
            this[9] = argb(33, 33, 60); this[10] = argb(60, 26, 26); this[11] = argb(33, 60, 33)
            this[12] = argb(60, 33, 60); this[13] = argb(33, 60, 60); this[14] = argb(60, 60, 33)
            this[15] = argb(100, 100, 100)
        }
        var currentColor = palette[0]!!
        var x = 0
        var maxX = 0
        var y = 0
        var maxY = 0
        // Sparse pixel storage keyed by (row * tentativeWidth + col) would
        // need a width up front, which Sixel doesn't declare reliably (see
        // this function's own doc) - a row-major list of IntArrays grown
        // on demand as y advances avoids needing one. 6 is the band
        // height; rows are only materialized as a '-'/data byte actually
        // reaches them.
        val rows = ArrayList<IntArray?>()
        fun ensureRow(r: Int): IntArray {
            while (rows.size <= r) rows.add(null)
            var row = rows[r]
            if (row == null) {
                row = IntArray(1) { 0 } // grown lazily below as x advances
                rows[r] = row
            }
            return row
        }
        fun setPixel(px: Int, py: Int, color: Int) {
            if (px < 0 || py < 0) return
            var row = ensureRow(py)
            if (px >= row.size) {
                val grown = IntArray((px + 1).coerceAtLeast(row.size * 2))
                row.copyInto(grown)
                rows[py] = grown
                row = grown
            }
            row[px] = color
            if (px > maxX) maxX = px
            if (py > maxY) maxY = py
        }

        var i = 0
        val n = body.length
        // Repeat count set by a preceding '!' ("DECGRI", e.g. "!255~" draws
        // the same sixel byte 255 times in a row) - the single most common
        // real-world Sixel command after plain data bytes, since it's how
        // encoders compress large flat-colored runs instead of emitting
        // one byte per pixel column.
        var repeatCount = 1
        while (i < n) {
            val c = body[i]
            when {
                c == '#' -> {
                    // Color introducer: "# Pc" (select existing index Pc)
                    // or "# Pc ; Pu ; Px ; Py ; Pz" (DEFINE index Pc, Pu=2
                    // meaning Px/Py/Pz are 0-100 RGB percentages - the only
                    // Pu this decoder supports, which is also the only one
                    // any modern encoder actually emits; Pu=1 HLS is
                    // vanishingly rare and left as an unrecognized-select,
                    // i.e. falls back to whatever palette[Pc] already was).
                    i++
                    val numStart = i
                    while (i < n && body[i].isDigit()) i++
                    val pc = body.substring(numStart, i).toIntOrNull() ?: 0
                    if (i < n && body[i] == ';') {
                        // Definition form - collect the remaining up to 4
                        // ';'-separated numeric fields.
                        val fields = ArrayList<Int>(4)
                        while (i < n && body[i] == ';') {
                            i++
                            val fs = i
                            while (i < n && body[i].isDigit()) i++
                            fields.add(body.substring(fs, i).toIntOrNull() ?: 0)
                        }
                        if (fields.size >= 4 && fields[0] == 2) {
                            val r = (fields[1].coerceIn(0, 100) * 255) / 100
                            val g = (fields[2].coerceIn(0, 100) * 255) / 100
                            val b = (fields[3].coerceIn(0, 100) * 255) / 100
                            palette[pc] = argb(r, g, b)
                        }
                    }
                    currentColor = palette.getOrPut(pc) { palette[0]!! }
                }
                c == '!' -> {
                    // Repeat count: "! Pn" applies to the NEXT single sixel
                    // data byte only (reset to 1 after it's consumed, in
                    // the data-byte branch below).
                    i++
                    val numStart = i
                    while (i < n && body[i].isDigit()) i++
                    repeatCount = body.substring(numStart, i).toIntOrNull()?.coerceAtLeast(1) ?: 1
                }
                c == '$' -> {
                    // Graphics Carriage Return - back to column 0 of the
                    // SAME 6-row band (y unchanged), used to overlay
                    // another color's pixels into a band already partly
                    // drawn by a previous color.
                    x = 0
                    i++
                }
                c == '-' -> {
                    // Graphics New Line - next 6-row band, column 0.
                    x = 0
                    y += 6
                    i++
                }
                c == '"' -> {
                    // Raster attributes ("\" Pan;Pad;Ph;Pv") - aspect ratio
                    // and a declared width/height this decoder deliberately
                    // ignores (see this function's own doc); skip past its
                    // up-to-4 numeric fields without acting on them.
                    i++
                    var fieldsLeft = 4
                    while (fieldsLeft > 0 && i < n) {
                        while (i < n && body[i].isDigit()) i++
                        if (i < n && body[i] == ';') { i++; fieldsLeft-- } else break
                    }
                }
                c in '\u003F'..'\u007E' -> {
                    // A real sixel data byte - 6 vertically-stacked pixels
                    // for the current x column, current color, current
                    // band. Subtracting '?' (0x3F) recovers the raw 6-bit
                    // value; bit 0 is the TOP row of the band (per DEC's
                    // own bit-to-scanline mapping), hence py = y + bitIndex
                    // rather than y + (5 - bitIndex).
                    val bits = c.code - '?'.code
                    repeat(repeatCount) {
                        for (bitIndex in 0 until 6) {
                            if ((bits shr bitIndex) and 1 == 1) {
                                setPixel(x, y + bitIndex, currentColor)
                            }
                        }
                        x++
                    }
                    repeatCount = 1
                    i++
                }
                else -> i++ // Whitespace/newlines some encoders pad with; ignore.
            }
        }

        val width = maxX + 1
        val height = maxY + 6 // last band's full 6-row height, even if only partly drawn
        if (width <= 0 || height <= 0 || rows.isEmpty()) return
        val pixels = IntArray(width * height)
        for (r in 0 until height) {
            val row = rows.getOrNull(r) ?: continue
            val copyLen = row.size.coerceAtMost(width)
            System.arraycopy(row, 0, pixels, r * width, copyLen)
        }
        buffer.placeImage(TerminalBuffer.SixelImage(width, height, pixels), cursorRow, cursorCol)
        // Real terminals leave the cursor positioned after the image
        // rather than on top of it, so text a program prints right after
        // ("Image loaded." on the line below, a common pattern) doesn't
        // land ON TOP of what was just drawn - and so a following prompt
        // redraw's CSI J/K doesn't clearRow() a row that's still visually
        // part of the image (see imageRowSpan's own doc: that was exactly
        // what made an image vanish right after the user pressed Enter).
        // advanceCursorRows (see its own doc) caps this at the rows
        // actually available below the image instead of scrolling it
        // straight off the top for anything taller than the screen.
        advanceCursorRows(imageRowSpan(height), anchorRow = cursorRow)
    }

    private fun argb(r: Int, g: Int, b: Int): Int =
        (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

    // ---------------------------------------------------------------
    // Kitty graphics protocol - command dispatch
    // ---------------------------------------------------------------
    //
    // Entry point once finishApc has a COMPLETE command: either a single
    // unchunked APC, or a chunked transmission's stitched-together
    // payload (see finishApc's own doc). `fields` is the parsed key=value
    // control data (from the FIRST chunk if this was chunked); `payload`
    // is the still-base64-encoded data string (empty for e.g. a bare a=d
    // delete, which carries no pixel payload at all).
    //
    // Per-key meanings this emulator acts on (unknown keys/values are
    // silently ignored, matching parseKittyControlFields's own
    // tolerance):
    //   a = action: t (transmit only), T (transmit+place), p (place an
    //       already-transmitted image), d (delete), q (query - "can you
    //       do this?", answered without actually storing anything)
    //   f = pixel format: 32 (RGBA8888 raw), 24 (RGB888 raw), 100 (PNG,
    //       any of the above once decoded), default 32 if t/T with no
    //       explicit f=
    //   t = transmission medium: d (direct, payload IS the data,
    //       default), f (payload is a path to a plain file), t (payload
    //       is a path to a temp file the client deletes after this
    //       terminal reads it - same read, no different handling needed
    //       here since this terminal never re-reads it later anyway)
    //   s,v = declared pixel width/height (required for f=24/32 raw
    //       data, since raw pixels carry no self-describing dimensions
    //       the way PNG does)
    //   i = client image id, q = "quiet" image id (same slot, lower
    //       verbosity - treated identically here since this emulator's
    //       ack/error responses are already minimal)
    //   p = placement id (default 0)
    //   C = cursor-movement-after-place suppression flag (1 = don't
    //       move cursor) - honored below alongside the existing
    //       Sixel-style "advance past the image" behavior
    //   z = z-index (default 0)
    //   d = delete selector letter (a/A, i/I, p/P, q/Q, x/X, y/Y, z/Z -
    //       see deleteSelectorFor below)
    //   q (on the CONTROL side, distinct from the q= image-id key above
    //       when a='q') = suppress-response flag; not separately
    //       tracked since this emulator only ever responds to a=t/a=T/
    //       a=p and a=q anyway, never to a=d.
    private fun handleKittyCommand(fields: Map<String, String>, payload: String) {
        val action = fields["a"] ?: "t"
        when (action) {
            "d" -> kittyDelete(fields)
            "p" -> kittyPlace(fields)
            "q" -> kittyQuery(fields)
            "t", "T" -> kittyTransmit(fields, payload, alsoPlace = action == "T")
            "f" -> kittyTransmitFrame(fields, payload)
            "a" -> kittyAnimationControl(fields)
            "c" -> kittyComposeFrames(fields)
            else -> { /* unrecognized action - ignore, same policy as unrecognized OSC/DCS */ }
        }
    }

    // Resolves the `i=`/`I=` pair the spec defines for every Kitty
    // command that identifies an image: `i=` is a real, client-managed
    // image id; `I=` is a "number" the client uses instead when it
    // doesn't want to manage ids itself, which this emulator resolves
    // to whichever real id was most recently stored under that number
    // (see TerminalBuffer.newestImageIdForNumber's own doc). Per spec
    // "Specifying both i and I keys in any command is an error" -
    // modeled here as `i=` simply taking priority when both are
    // present, rather than a hard EINVAL, since silently preferring the
    // more specific key is a safer default than refusing the whole
    // command over a client mistake this emulator can still act on
    // sensibly. Returns null if neither key resolves to anything (no
    // `i=`/`I=` at all, or an `I=` number nothing was ever stored
    // under) - callers already have their own "no id at all" fallback
    // (usually assigning a fresh id via kittyImageIdCounter) for the
    // plain-transmit case where that's valid; commands that instead
    // need an EXISTING image (place, delete, animate) treat null as
    // "not found".
    private fun resolveKittyImageId(fields: Map<String, String>): Int? {
        fields["i"]?.toIntOrNull()?.let { return it }
        val number = fields["I"]?.toIntOrNull() ?: return null
        return buffer.newestImageIdForNumber(number)
    }

    // Reads the raw (still base64/binary, pre-decompress) bytes for a
    // transmit-family command (a=t/a=T/a=f) per its `t=` transmission
    // medium - shared by kittyTransmit and kittyTransmitFrame since the
    // medium handling is identical for both (only what's done with the
    // resulting bytes afterward differs). `t=s` (shared memory) reads
    // from the same POSIX shared-memory-object namespace `shm_open`
    // resolves to on Linux, which the kernel exposes as ordinary files
    // under /dev/shm - this emulator only ever runs on Android/Linux
    // (see this class's header doc), so a plain java.io.File read
    // against /dev/shm/<name> is a faithful implementation of "read
    // from the named shared memory object" without needing JNI/a real
    // shm_open binding. Per spec the terminal must unlink (delete) a
    // POSIX shared memory object after reading it - done here via
    // File.delete() right after the read succeeds, same "best-effort,
    // don't fail the transmit over cleanup" tolerance as everything
    // else in this decode path. `S=`/`O=` (size/offset) restrict a
    // `t=f`/`t=t`/`t=s` read to a sub-range of the underlying file/shm
    // object, per spec's own "read only part of the specified file"
    // description of those two keys - applied uniformly to all three
    // file-backed mediums since the spec doesn't scope them to any one
    // of the three.
    private fun readKittyTransmitBytes(fields: Map<String, String>, payload: String): ByteArray {
        val medium = fields["t"] ?: "d"
        val size = fields["S"]?.toIntOrNull()
        val offset = fields["O"]?.toIntOrNull() ?: 0
        return when (medium) {
            "f", "t" -> {
                val file = java.io.File(payload)
                val bytes = if (size != null) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset.toLong())
                        val buf = ByteArray(size)
                        raf.readFully(buf)
                        buf
                    }
                } else {
                    file.readBytes()
                }
                if (medium == "t") file.delete()
                bytes
            }
            "s" -> {
                // POSIX shm_open names are conventionally "/name" and
                // land at /dev/shm/name (the leading slash stripped) -
                // strip it the same way if the client included it, but
                // tolerate a name given without one too.
                val shmName = payload.removePrefix("/")
                val file = java.io.File("/dev/shm", shmName)
                val bytes = if (size != null) {
                    java.io.RandomAccessFile(file, "r").use { raf ->
                        raf.seek(offset.toLong())
                        val buf = ByteArray(size)
                        raf.readFully(buf)
                        buf
                    }
                } else {
                    file.readBytes()
                }
                // Per spec: "terminal emulator must read the data from
                // the memory object and then unlink and close it on
                // POSIX" - File.delete() is the unlink; there's no
                // separate close since this was never held open beyond
                // the read above.
                file.delete()
                bytes
            }
            else -> java.util.Base64.getDecoder().decode(payload)
        }
    }

    // a=t/a=T: decode the pixel payload (per f=) and store it under its
    // image id, then (a=T only) immediately anchor a placement the same
    // way a=p would. Always ack/nak via onRespond unless q= (quiet)
    // level 2 was requested (level 1 still wants failure notices; only
    // level 2 is fully silent per spec - see kittyRespond).
    private fun kittyTransmit(fields: Map<String, String>, payload: String, alsoPlace: Boolean) {
        val imageNumber = fields["I"]?.toIntOrNull()
        val imageId = fields["i"]?.toIntOrNull() ?: (++kittyImageIdCounter)
        val format = fields["f"]?.toIntOrNull() ?: 32

        val rawBytes = try {
            val encoded = readKittyTransmitBytes(fields, payload)
            if (fields["o"] == "z") inflateZlib(encoded) else encoded
        } catch (e: Exception) {
            kittyRespond(fields, imageId, "ENOENT:could not read image data")
            return
        }

        val image = try {
            when (format) {
                100 -> decodePng(rawBytes, imageId)
                24 -> decodeRawRgb(rawBytes, fields, imageId, hasAlpha = false)
                32 -> decodeRawRgb(rawBytes, fields, imageId, hasAlpha = true)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (image == null) {
            kittyRespond(fields, imageId, "EBADF:could not decode image data")
            return
        }

        buffer.storeKittyImage(image, imageNumber)
        // Per spec, when both i= and I= are given the terminal replies
        // with the id under `i` AND echoes the client's own number back
        // under `I` (see resolveKittyImageId's own doc on the i=/I=
        // relationship) - only relevant when the client actually used
        // I=, so the plain i=-only ack path (the overwhelmingly common
        // case) is untouched.
        if (imageNumber != null) kittyRespond(fields, imageId, "OK", imageNumber) else kittyRespond(fields, imageId, "OK")

        if (alsoPlace) {
            if (fields["U"] == "1") {
                val placementId = fields["p"]?.toIntOrNull() ?: 0
                val cols = fields["c"]?.toIntOrNull() ?: 1
                val rows = fields["r"]?.toIntOrNull() ?: 1
                buffer.markKittyVirtualPlacement(imageId, placementId, cols, rows)
            } else {
                kittyAnchor(fields, imageId, image)
            }
        }
    }

    // a=f: transmits animation frame data. Per spec's own "Keys for
    // animation frame loading" table: `r=` is the 1-based frame number
    // being edited (root/frame 1 if unspecified data still needs a
    // brand-new frame allocated - handled below by defaulting to
    // "append a new frame" when r= is absent, same as this function's
    // previous behavior's default of 2 for a first a=f, generalized to
    // "next never-used frame number" for subsequent ones so repeated
    // a=f calls without r= keep appending rather than colliding on the
    // same frame 2 every time); `c=` is the 1-based frame number whose
    // pixels serve as the BACKGROUND CANVAS this transmitted data is
    // composited onto (spec: "by default the base data is black, fully
    // transparent pixels" when c= is absent - modeled as compositing
    // onto an all-zero IntArray of the same dimensions, which is
    // exactly transparent-black in ARGB8888); `x,y,s,v` (present) mean
    // this transmission only covers a PARTIAL rectangle of the frame
    // (top-left at x,y, size s×v) rather than the full image - the
    // decoded payload is composited into that sub-rectangle of the
    // canvas rather than treated as a full-size frame; `X=` selects
    // replace (1) vs the default full alpha blend for that
    // compositing; `Y=` is a background RGBA color used to pre-fill the
    // canvas instead of transparent-black when no `c=` source frame
    // was given either.
    private fun kittyTransmitFrame(fields: Map<String, String>, payload: String) {
        val imageId = resolveKittyImageId(fields) ?: return
        val requestedFrameNumber = fields["r"]?.toIntOrNull()
        val gapMs = fields["z"]?.toIntOrNull() ?: 0
        val format = fields["f"]?.toIntOrNull() ?: 32
        val baseFrameNumber = fields["c"]?.toIntOrNull()
        val partialX = fields["x"]?.toIntOrNull() ?: 0
        val partialY = fields["y"]?.toIntOrNull() ?: 0
        val partialW = fields["s"]?.toIntOrNull()
        val partialH = fields["v"]?.toIntOrNull()
        val overwrite = fields["X"]?.toIntOrNull() == 1
        val bgColor = fields["Y"]?.toLongOrNull()?.let { rgbaLongToArgbInt(it) } ?: 0

        val rawBytes = try {
            val encoded = readKittyTransmitBytes(fields, payload)
            if (fields["o"] == "z") inflateZlib(encoded) else encoded
        } catch (e: Exception) {
            kittyRespond(fields, imageId, "ENOENT:could not read frame data")
            return
        }

        val decoded = try {
            when (format) {
                100 -> decodePng(rawBytes, imageId)
                24 -> decodeRawRgb(rawBytes, fields, imageId, hasAlpha = false)
                32 -> decodeRawRgb(rawBytes, fields, imageId, hasAlpha = true)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
        if (decoded == null) {
            kittyRespond(fields, imageId, "EBADF:could not decode frame data")
            return
        }

        val root = buffer.getKittyImage(imageId) ?: return
        // The full-size canvas this transmission's (possibly partial)
        // data gets composited onto - either an existing frame's
        // pixels (c=), or a fresh bgColor-filled (or transparent-black)
        // canvas the same size as the root image, per spec's "by
        // default the base data is black, fully transparent pixels".
        val canvasW = root.width
        val canvasH = root.height
        val canvas = if (baseFrameNumber != null) {
            kittyFramePixelsByNumber(imageId, root, baseFrameNumber)?.copyOf() ?: IntArray(canvasW * canvasH) { bgColor }
        } else {
            IntArray(canvasW * canvasH) { bgColor }
        }

        val destX = if (partialW != null) partialX else 0
        val destY = if (partialH != null) partialY else 0
        compositeKittyRect(
            dest = canvas, destW = canvasW, destH = canvasH, destX = destX, destY = destY,
            src = decoded.pixels, srcW = decoded.width, srcH = decoded.height,
            overwrite = overwrite
        )

        val frameNumber = requestedFrameNumber ?: (nextFreeKittyFrameNumber(imageId))
        buffer.addKittyFrame(
            imageId,
            TerminalBuffer.KittyFrame(frameNumber, canvasW, canvasH, canvas, gapMs, composeMode = if (overwrite) 1 else 0)
        )
        kittyRespond(fields, imageId, "OK")
    }

    // a=c: explicit frame-to-frame composition (spec's own dedicated
    // action, distinct from the compositing a=f ALSO does inline as
    // part of transmitting new frame data above) - composes a
    // rectangle from one already-existing frame onto another
    // already-existing frame, with no new pixel payload transmitted at
    // all. `r=` source frame, `c=` destination frame, `w,h` rectangle
    // size (defaults to the full image when absent), `x,y` source
    // rectangle offset, `X,Y` destination rectangle offset, `C=1`
    // overwrite vs the default alpha blend - all per spec's "Keys for
    // animation frame composition" table. Responds ENOENT if either
    // frame doesn't exist, matching the spec's documented error for
    // this action (the EINVAL/ENOSPC cases the spec also documents for
    // out-of-bounds rectangles and storage exhaustion aren't modeled
    // here - out-of-bounds is instead clamped defensively by
    // compositeKittyRect itself, same tolerance as the rest of this
    // Kitty layer's malformed-command handling).
    private fun kittyComposeFrames(fields: Map<String, String>) {
        val imageId = resolveKittyImageId(fields) ?: return
        val root = buffer.getKittyImage(imageId) ?: return
        val srcFrameNumber = fields["r"]?.toIntOrNull() ?: return
        val destFrameNumber = fields["c"]?.toIntOrNull() ?: return
        val srcPixels = kittyFramePixelsByNumber(imageId, root, srcFrameNumber)
        val destCanvas = kittyFramePixelsByNumber(imageId, root, destFrameNumber)?.copyOf()
        if (srcPixels == null || destCanvas == null) {
            kittyRespond(fields, imageId, "ENOENT:no such frame")
            return
        }
        val w = fields["w"]?.toIntOrNull() ?: root.width
        val h = fields["h"]?.toIntOrNull() ?: root.height
        val srcX = fields["x"]?.toIntOrNull() ?: 0
        val srcY = fields["y"]?.toIntOrNull() ?: 0
        val destX = fields["X"]?.toIntOrNull() ?: 0
        val destY = fields["Y"]?.toIntOrNull() ?: 0
        val overwrite = fields["C"]?.toIntOrNull() == 1

        // Crop the source rectangle out of srcPixels first (it may be a
        // differently-sized frame than the destination canvas), then
        // composite that crop onto destCanvas at (destX, destY) -
        // compositeKittyRect itself already clamps against the
        // destination's own bounds.
        val srcFrameW = root.width // every stored frame shares the root's canvas dimensions (see kittyTransmitFrame)
        val cropped = IntArray(w * h)
        for (row in 0 until h) {
            val srcRow = srcY + row
            if (srcRow !in 0 until root.height) continue
            for (col in 0 until w) {
                val srcCol = srcX + col
                if (srcCol !in 0 until srcFrameW) continue
                cropped[row * w + col] = srcPixels[srcRow * srcFrameW + srcCol]
            }
        }
        compositeKittyRect(
            dest = destCanvas, destW = root.width, destH = root.height, destX = destX, destY = destY,
            src = cropped, srcW = w, srcH = h, overwrite = overwrite
        )
        buffer.addKittyFrame(
            imageId,
            TerminalBuffer.KittyFrame(destFrameNumber, root.width, root.height, destCanvas, gapMs = 0, composeMode = if (overwrite) 1 else 0)
        )
        kittyRespond(fields, imageId, "OK")
    }

    // Resolves frame number [n] (1-based, 1 = root) to its pixel
    // IntArray for compositing purposes - shared by kittyTransmitFrame
    // (c= base canvas) and kittyComposeFrames (both r= source and c=
    // destination), since both need the same "1 means the root image,
    // otherwise look it up in kittyFrames" resolution.
    private fun kittyFramePixelsByNumber(imageId: Int, root: TerminalBuffer.KittyImage, n: Int): IntArray? {
        if (n <= 1) return root.pixels
        return buffer.getKittyFramePixels(imageId, n)
    }

    // Returns the smallest frame number >= 2 not already used by an
    // existing frame for [imageId] - used when a=f omits r= entirely,
    // so repeated no-r= transmissions append new frames (2, 3, 4...)
    // instead of all colliding on the same hardcoded frame 2.
    private fun nextFreeKittyFrameNumber(imageId: Int): Int {
        val used = buffer.kittyFrameNumbers(imageId)
        var n = 2
        while (n in used) n++
        return n
    }

    // Composites src (srcW x srcH) onto dest (destW x destH, modified
    // in place) at top-left (destX, destY), clamped so any part of src
    // falling outside dest's bounds is simply skipped rather than
    // throwing - out-of-bounds compositing requests are a malformed-
    // command case this Kitty layer generally tolerates rather than
    // erroring on (see e.g. deleteKittyPlacements' own "ignore what
    // doesn't have a sensible target" policy). [overwrite]=true is a
    // flat copy (spec's `C=1`/`X=1` "simple replacement"); false does a
    // standard "over" alpha blend using src's own alpha channel, per
    // spec's default "full alpha blend" compositing mode.
    private fun compositeKittyRect(
        dest: IntArray, destW: Int, destH: Int, destX: Int, destY: Int,
        src: IntArray, srcW: Int, srcH: Int,
        overwrite: Boolean
    ) {
        for (row in 0 until srcH) {
            val dy = destY + row
            if (dy !in 0 until destH) continue
            for (col in 0 until srcW) {
                val dx = destX + col
                if (dx !in 0 until destW) continue
                val srcPixel = src[row * srcW + col]
                val destIdx = dy * destW + dx
                if (overwrite) {
                    dest[destIdx] = srcPixel
                    continue
                }
                val srcAlpha = (srcPixel ushr 24) and 0xFF
                if (srcAlpha == 0) continue // fully transparent - leaves dest untouched
                if (srcAlpha == 255) {
                    dest[destIdx] = srcPixel
                    continue
                }
                val destPixel = dest[destIdx]
                val invAlpha = 255 - srcAlpha
                val dstAlpha = (destPixel ushr 24) and 0xFF
                val outAlpha = srcAlpha + (dstAlpha * invAlpha) / 255
                fun blendChannel(shift: Int): Int {
                    val s = (srcPixel ushr shift) and 0xFF
                    val d = (destPixel ushr shift) and 0xFF
                    return (s * srcAlpha + d * dstAlpha * invAlpha / 255) / outAlpha.coerceAtLeast(1)
                }
                dest[destIdx] = (outAlpha shl 24) or (blendChannel(16) shl 16) or (blendChannel(8) shl 8) or blendChannel(0)
            }
        }
    }

    // Converts a spec `Y=` value (a 32-bit RGBA integer, e.g.
    // "4278190335 # 0xff0000ff opaque red") to this codebase's own
    // ARGB-packed Int pixel convention (same shl-24/16/8/0 ARGB layout
    // decodeRawRgb/decodePng both already produce) - the two byte
    // orders differ (spec: R,G,B,A high-to-low bytes; this codebase:
    // A,R,G,B), so a straight reinterpret-cast would swap channels.
    private fun rgbaLongToArgbInt(rgba: Long): Int {
        val r = (rgba ushr 24) and 0xFF
        val g = (rgba ushr 16) and 0xFF
        val b = (rgba ushr 8) and 0xFF
        val a = rgba and 0xFF
        return ((a.toInt()) shl 24) or ((r.toInt()) shl 16) or ((g.toInt()) shl 8) or b.toInt()
    }

    // a=a: animation playback control. Per spec's own "Keys for
    // animation control" table (distinct from the transmit-time key
    // meanings the SAME letters have under a=f - see kittyTransmitFrame
    // for that separate table): `c=` is the 1-based frame number to
    // make current (client-driven stepping: "<ESC>_Ga=a,i=3,c=7" makes
    // frame 7 current), `s=` is play/stop state (1 stop, 2 run-but-
    // wait-for-more-frames-at-the-end/"loading" mode, 3 run-and-loop),
    // `r=` (separately from `c=`) retargets a SPECIFIC frame's gap
    // without touching current-frame/play-state at all, and `v=` is the
    // loop count (0 ignored/unset, 1 infinite, N>1 means N-1 loops -
    // modeled here directly against TerminalBuffer.KittyAnimationState.
    // loopCount's own "0 = infinite" convention, so v=1→0 and v>1→v-1).
    // s=2's "loading" mode has no real distinct terminal-side behavior
    // from s=3 in this simplified model (both just mean "keep playing"
    // - the difference is purely about what the terminal does once it
    // RUNS OUT of frames, which this emulator's advanceKittyAnimations
    // already handles by just holding on the last frame if there's
    // nowhere further to advance to, matching s=2's own "wait for more
    // frames" intent for free).
    private fun kittyAnimationControl(fields: Map<String, String>) {
        val imageId = resolveKittyImageId(fields) ?: return
        val gotoFrame = fields["c"]?.toIntOrNull()
        val retargetFrame = fields["r"]?.toIntOrNull()
        val retargetGap = fields["z"]?.toIntOrNull()
        val loopLimit = fields["v"]?.toIntOrNull()
        val playing = when (fields["s"]?.toIntOrNull()) {
            1 -> false
            2, 3 -> true
            else -> null
        }
        val loopCount = when (loopLimit) {
            null, 0 -> null // 0/unset - "ignored" per spec, leave unchanged
            1 -> 0 // spec's v=1 ("loop infinitely") maps to KittyAnimationState's own 0="infinite"
            else -> loopLimit - 1 // spec's v=N ("loop N-1 times") for any N>1
        }
        if (retargetFrame != null && retargetGap != null) {
            buffer.retargetKittyFrameGap(imageId, retargetFrame, retargetGap)
        }
        buffer.controlKittyAnimation(imageId, gotoFrame = gotoFrame, setPlaying = playing, setLoopCount = loopCount)
    }

    private fun kittyPlace(fields: Map<String, String>) {
        val imageId = resolveKittyImageId(fields) ?: return
        val image = buffer.getKittyImage(imageId)
        if (image == null) {
            kittyRespond(fields, imageId, "ENOENT:no such image")
            return
        }
        if (fields["U"] == "1") {
            // Unicode-placeholder virtual placement (see
            // TerminalBuffer.markKittyVirtualPlacement's own doc): no
            // fixed grid position is anchored at all - the client is
            // expected to separately write U+10EEEE placeholder
            // characters (see writeChar's placeholder-detection branch)
            // wherever the image should actually appear, so kittyAnchor
            // (which anchors a REAL, fixed-position placement and moves
            // the cursor) is deliberately skipped entirely here.
            val placementId = fields["p"]?.toIntOrNull() ?: 0
            val cols = fields["c"]?.toIntOrNull() ?: 1
            val rows = fields["r"]?.toIntOrNull() ?: 1
            buffer.markKittyVirtualPlacement(imageId, placementId, cols, rows)
            kittyRespond(fields, imageId, "OK", placementId = placementId.takeIf { fields["p"] != null })
            return
        }
        val placementId = kittyAnchor(fields, imageId, image)
        kittyRespond(fields, imageId, "OK", placementId = placementId.takeIf { fields["p"] != null })
    }

    // Shared by kittyTransmit(alsoPlace=true)/kittyPlace: anchors `image`
    // at the current cursor position as placement p= (default 0), z=
    // (default 0), then advances the cursor past it the same way
    // decodeAndPlaceSixel does - unless C=1 asked for the cursor to stay
    // put (Kitty-specific; Sixel has no equivalent flag). Also parses
    // the full display-layout key set the spec defines for a=p/a=T:
    // `x,y,w,h` (source-rectangle crop, in image pixel coordinates -
    // TerminalBuffer.KittyPlacement's own srcX/srcY/srcW/srcH), `X,Y`
    // (pixel offset within the anchor cell - cellOffsetX/cellOffsetY),
    // and `c,r` (display size in whole cells, image scaled to fit -
    // displayCols/displayRows). All default to 0 ("unspecified") the
    // same way TerminalBuffer.placeKittyImage's own defaults do,
    // matching the protocol's control-data-reference table defaults
    // for every one of these keys. Returns the resolved placementId so
    // callers can include it in their own ack (see kittyPlace's call
    // site) without re-deriving fields["p"] themselves.
    private fun kittyAnchor(fields: Map<String, String>, imageId: Int, image: TerminalBuffer.KittyImage): Int {
        val placementId = fields["p"]?.toIntOrNull() ?: 0
        val z = fields["z"]?.toIntOrNull() ?: 0
        val srcX = fields["x"]?.toIntOrNull() ?: 0
        val srcY = fields["y"]?.toIntOrNull() ?: 0
        val srcW = fields["w"]?.toIntOrNull() ?: 0
        val srcH = fields["h"]?.toIntOrNull() ?: 0
        val cellOffsetX = fields["X"]?.toIntOrNull() ?: 0
        val cellOffsetY = fields["Y"]?.toIntOrNull() ?: 0
        val displayCols = fields["c"]?.toIntOrNull() ?: 0
        val displayRows = fields["r"]?.toIntOrNull() ?: 0
        buffer.placeKittyImage(
            imageId, placementId, cursorRow, cursorCol, z, image,
            srcX, srcY, srcW, srcH, cellOffsetX, cellOffsetY, displayCols, displayRows
        )
        if (fields["C"] != "1") {
            // Per spec the cursor should advance by the placement's own
            // on-screen size in cells. When the client DID give explicit
            // c=/r= (a size this function already knows exactly, no pixel
            // math needed), honor it precisely rather than falling back,
            // since that's the common real-world case (icat/chafa almost
            // always pass explicit c=/r=) and costs nothing extra to get
            // exactly right. Row advance either way goes through
            // advanceCursorRows (see its own doc) rather than a raw
            // repeat(n){lineFeed()}: a tall placement's own auto-advance
            // used to scroll it straight back off the top before the user
            // even typed anything, since every lineFeed() past the bottom
            // margin calls buffer.scrollUp(), which shifts (and eventually
            // drops) every placed image including the one just placed.
            val anchorRow = cursorRow
            if (displayCols > 0 || displayRows > 0) {
                val advanceCols = displayCols.takeIf { it > 0 } ?: 1
                val advanceRows = displayRows.takeIf { it > 0 } ?: 1
                val targetCol = (cursorCol + advanceCols).coerceAtMost(buffer.columns - 1)
                advanceCursorRows(advanceRows, anchorRow)
                cursorCol = targetCol
            } else {
                // No explicit c=/r= - natural size, so the on-screen row
                // span has to come from the image's own pixel height (see
                // imageRowSpan's own doc). Previously always a single
                // lineFeed() regardless of how tall the image actually
                // rendered, which left the cursor sitting on one of the
                // image's OWN rows for anything taller than one cell - the
                // next prompt redraw's CSI J/K would then clearRow() that
                // row and wipe the placement out from under the image that
                // was still fully visible on screen (icat's default,
                // no-size-flags mode hits this constantly, matching
                // "image render olduktan sonra enter basinca yok oluyor").
                advanceCursorRows(imageRowSpan(image.height), anchorRow)
            }
        }
        return placementId
    }

    // a=d: maps the delete selector letter (d= key) onto
    // TerminalBuffer.deleteKittyPlacements's filter params, per the
    // spec's full "Keys for deleting images" table (a/A, i/I, n/N, c/C,
    // f/F, p/P, q/Q, r/R, x/X, y/Y, z/Z). Uppercase letters additionally
    // free the underlying image data once unreferenced (alsoFreeData) -
    // see that function's own doc. `x`/`y` are reused across several
    // selectors with different meanings per spec (plain cell column/
    // row for x/X and y/Y, but the CORNER of a cell for p/P/q/Q) -
    // resolved per-branch below rather than hoisted to shared locals to
    // avoid implying they always mean the same thing.
    private fun kittyDelete(fields: Map<String, String>) {
        val selector = fields["d"] ?: "a"
        val letter = selector.firstOrNull() ?: 'a'
        val alsoFree = letter.isUpperCase()
        val imageId = resolveKittyImageId(fields)
        val placementId = fields["p"]?.toIntOrNull()
        when (letter.lowercaseChar()) {
            'a' -> buffer.deleteKittyPlacements(alsoFreeData = alsoFree)
            // i/I: by image id (+ optional placement id).
            'i' -> buffer.deleteKittyPlacements(imageId = imageId, placementId = placementId, alsoFreeData = alsoFree)
            // n/N: by image NUMBER (the `I=` key here, not `i=` -
            // spec: "newest image with the specified number, specified
            // using the I key") resolved to its real id the same way
            // every other by-number reference is (resolveKittyImageId
            // already prefers i= when both are given, but n/N's own
            // spec wording specifically means I=, so resolve directly
            // against I= here rather than via the shared i=-priority
            // helper).
            'n' -> {
                val byNumber = fields["I"]?.toIntOrNull()?.let { buffer.newestImageIdForNumber(it) }
                buffer.deleteKittyPlacements(imageId = byNumber, placementId = placementId, alsoFreeData = alsoFree)
            }
            // r/R: by image id RANGE (x=min, y=max) - NOT the same x/y
            // as p/P's cell coordinates below, per spec's own "The
            // values of the x and y keys are the same as cursor
            // positions" note applying only to the OTHER selectors
            // that use them for position.
            'r' -> {
                val idMin = fields["x"]?.toIntOrNull()
                val idMax = fields["y"]?.toIntOrNull()
                buffer.deleteKittyPlacements(idRangeMin = idMin, idRangeMax = idMax, alsoFreeData = alsoFree)
            }
            // c/C: placements intersecting the CURRENT cursor position -
            // no x=/y=/z= keys involved at all, unlike every other
            // positional selector here.
            'c' -> buffer.deleteKittyPlacements(cursorRow = cursorRow, cursorCol = cursorCol, alsoFreeData = alsoFree)
            // f/F: animation frames only, not placements - see
            // TerminalBuffer.deleteKittyFrames' own doc for why this is
            // a completely separate call rather than another
            // deleteKittyPlacements filter combination.
            'f' -> buffer.deleteKittyFrames(imageId)
            // p/P: placements intersecting the specific cell (x=col, y=row).
            'p' -> {
                val col = fields["x"]?.toIntOrNull()
                val row = fields["y"]?.toIntOrNull()
                buffer.deleteKittyPlacements(row = row, col = col, alsoFreeData = alsoFree)
            }
            // q/Q: placements intersecting a specific cell AT a specific z-index.
            'q' -> {
                val col = fields["x"]?.toIntOrNull()
                val row = fields["y"]?.toIntOrNull()
                val z = fields["z"]?.toIntOrNull()
                buffer.deleteKittyPlacements(row = row, col = col, z = z, alsoFreeData = alsoFree)
            }
            'x' -> buffer.deleteKittyPlacements(col = fields["x"]?.toIntOrNull(), alsoFreeData = alsoFree)
            'y' -> buffer.deleteKittyPlacements(row = fields["y"]?.toIntOrNull(), alsoFreeData = alsoFree)
            'z' -> buffer.deleteKittyPlacements(z = fields["z"]?.toIntOrNull(), alsoFreeData = alsoFree)
            else -> { /* unrecognized selector letter - ignore */ }
        }
    }

    // a=q: "can you handle this?" capability probe - a program sends a
    // real transmit command with a=q instead of a=t to test support
    // without it actually being stored/displayed. Since this emulator
    // DOES support f=24/32/100 unconditionally, just decode-validate and
    // ack/nak exactly like a=t would, but skip storeKittyImage entirely.
    private fun kittyQuery(fields: Map<String, String>) {
        val imageId = fields["i"]?.toIntOrNull() ?: (++kittyImageIdCounter)
        kittyRespond(fields, imageId, "OK")
    }

    // Sends the Kitty graphics protocol's own response format back over
    // the pty: "ESC _ G i=<id>,I=<id>;<message> ESC \". Suppressed
    // entirely when q=2 (quiet level 2) was requested - q=1 still wants
    // failure notices, matching the spec's "even quiet mode reports
    // errors unless quiet=2" rule; both are treated identically here
    // since every kittyRespond call site already only calls this once
    // per command with either "OK" or a specific error message.
    // [imageNumber] (spec's `I=` echoed back - see kittyTransmit's own
    // call site for why: "the value of I is the same as was sent in
    // the creation command") and [placementId] (spec's `p=`, added to
    // the ack "When you specify a placement id") are both optional and
    // appended to the response's key list only when actually given -
    // the plain `i=<id>;OK` shape (neither supplied) is unaffected and
    // remains every other call site's default.
    private fun kittyRespond(fields: Map<String, String>, imageId: Int, message: String, imageNumber: Int? = null, placementId: Int? = null) {
        val quiet = fields["q"]?.toIntOrNull() ?: 0
        if (quiet >= 1 && message == "OK") return
        if (quiet >= 2) return
        val keys = StringBuilder("i=$imageId")
        if (imageNumber != null) keys.append(",I=").append(imageNumber)
        if (placementId != null) keys.append(",p=").append(placementId)
        listener.onRespond("\u001B_G$keys;$message\u001B\\")
    }

    // ---------------------------------------------------------------
    // Kitty graphics protocol - pixel decoding
    // ---------------------------------------------------------------

    // Raw RGB(A) payload (f=24/32): no header at all, just tightly
    // packed pixel bytes in row-major order - s=/v= (width/height) are
    // REQUIRED per spec for this format since there's nothing
    // self-describing to derive them from, unlike PNG.
    private fun decodeRawRgb(bytes: ByteArray, fields: Map<String, String>, imageId: Int, hasAlpha: Boolean): TerminalBuffer.KittyImage? {
        val width = fields["s"]?.toIntOrNull() ?: return null
        val height = fields["v"]?.toIntOrNull() ?: return null
        if (width <= 0 || height <= 0) return null
        val bytesPerPixel = if (hasAlpha) 4 else 3
        val expected = width.toLong() * height.toLong() * bytesPerPixel
        if (bytes.size < expected) return null
        val pixels = IntArray(width * height)
        var src = 0
        for (i in pixels.indices) {
            val r = bytes[src].toInt() and 0xFF
            val g = bytes[src + 1].toInt() and 0xFF
            val b = bytes[src + 2].toInt() and 0xFF
            val a = if (hasAlpha) bytes[src + 3].toInt() and 0xFF else 0xFF
            pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            src += bytesPerPixel
        }
        return TerminalBuffer.KittyImage(imageId, width, height, pixels)
    }

    // Decompresses a `o=z` payload: RFC 1950 zlib-wrapped DEFLATE data,
    // per spec applied to the WHOLE transmit payload (after t=/S=/O=
    // medium reading, before f=24/32/100 pixel-format interpretation) -
    // distinct from decodePng's own internal Inflater use just below,
    // which unwraps PNG's IDAT chunks specifically and is unaffected by
    // whether the OUTER payload itself was also o=z-compressed (a PNG
    // sent with o=z is zlib-compressed PNG BYTES, which once inflated
    // here is a normal PNG file that decodePng then parses/inflates its
    // own IDAT data from same as any uncompressed PNG). Grows the
    // output buffer as needed rather than needing a known-in-advance
    // decompressed size, since unlike decodePng's fixed width*height*
    // channels target, a raw RGB(A) payload's decompressed size is only
    // knowable from s=/v= AFTER this function returns.
    private fun inflateZlib(compressed: ByteArray): ByteArray {
        val inflater = java.util.zip.Inflater()
        inflater.setInput(compressed)
        val out = java.io.ByteArrayOutputStream(compressed.size * 3)
        val chunk = ByteArray(65536)
        while (!inflater.finished()) {
            val n = inflater.inflate(chunk)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
                continue
            }
            out.write(chunk, 0, n)
        }
        inflater.end()
        return out.toByteArray()
    }

    // Minimal PNG decoder covering exactly what real-world Kitty senders
    // (icat, chafa --format=kitty, wezterm's imgcat-alikes) actually
    // produce: 8-bit-depth, non-interlaced IHDR; color types 2 (RGB), 6
    // (RGBA), 0 (grayscale) and 3 (palette, with optional tRNS alpha).
    // Deliberately does NOT handle 16-bit depth or Adam7 interlacing -
    // no observed Kitty-protocol encoder emits either, and supporting
    // them would roughly double this decoder's size for a case that
    // doesn't arise in practice.
    //
    // Uses only java.util.zip.Inflater (plain JDK, ships on every
    // Android runtime as part of core-libart, NOT an android.* package)
    // for the DEFLATE decompression PNG's IDAT chunks are always
    // encoded with - this keeps the "no android.*/Compose imports in
    // TerminalEmulator.kt" boundary intact (see this class's header
    // doc); actual android.graphics.Bitmap creation from the resulting
    // IntArray happens later, in TerminalView's bitmap cache, exactly
    // the same place Sixel's own decoded pixels get turned into a
    // Bitmap (see TerminalView.kt's sixelBitmapCache/bitmapFor).
    private fun decodePng(bytes: ByteArray, imageId: Int): TerminalBuffer.KittyImage? {
        val sig = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        if (bytes.size < 8 || !sig.indices.all { bytes[it] == sig[it] }) return null

        var width = 0
        var height = 0
        var bitDepth = 0
        var colorType = 0
        var interlace = 0
        var palette: ByteArray? = null
        var trns: ByteArray? = null
        val idat = java.io.ByteArrayOutputStream()

        var pos = 8
        while (pos + 8 <= bytes.size) {
            val len = ((bytes[pos].toInt() and 0xFF) shl 24) or ((bytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((bytes[pos + 2].toInt() and 0xFF) shl 8) or (bytes[pos + 3].toInt() and 0xFF)
            val type = String(bytes, pos + 4, 4, Charsets.US_ASCII)
            val dataStart = pos + 8
            if (dataStart + len > bytes.size) break
            when (type) {
                "IHDR" -> {
                    width = ((bytes[dataStart].toInt() and 0xFF) shl 24) or ((bytes[dataStart + 1].toInt() and 0xFF) shl 16) or
                        ((bytes[dataStart + 2].toInt() and 0xFF) shl 8) or (bytes[dataStart + 3].toInt() and 0xFF)
                    height = ((bytes[dataStart + 4].toInt() and 0xFF) shl 24) or ((bytes[dataStart + 5].toInt() and 0xFF) shl 16) or
                        ((bytes[dataStart + 6].toInt() and 0xFF) shl 8) or (bytes[dataStart + 7].toInt() and 0xFF)
                    bitDepth = bytes[dataStart + 8].toInt() and 0xFF
                    colorType = bytes[dataStart + 9].toInt() and 0xFF
                    interlace = bytes[dataStart + 12].toInt() and 0xFF
                }
                "PLTE" -> palette = bytes.copyOfRange(dataStart, dataStart + len)
                "tRNS" -> trns = bytes.copyOfRange(dataStart, dataStart + len)
                "IDAT" -> idat.write(bytes, dataStart, len)
                "IEND" -> { pos = bytes.size; continue }
            }
            pos = dataStart + len + 4 // skip 4-byte CRC
        }

        if (width <= 0 || height <= 0 || bitDepth != 8 || interlace != 0) return null
        if (colorType !in intArrayOf(0, 2, 3, 6)) return null

        val channels = when (colorType) {
            0 -> 1 // grayscale
            2 -> 3 // RGB
            3 -> 1 // palette index
            6 -> 4 // RGBA
            else -> return null
        }

        val inflater = java.util.zip.Inflater()
        inflater.setInput(idat.toByteArray())
        val stride = width * channels
        val raw = ByteArray((stride + 1) * height) // +1 per row for the filter-type byte
        var written = 0
        val chunk = ByteArray(65536)
        while (!inflater.finished() && written < raw.size) {
            val n = inflater.inflate(chunk)
            if (n == 0) {
                if (inflater.needsInput() || inflater.needsDictionary()) break
                continue
            }
            val toCopy = n.coerceAtMost(raw.size - written)
            System.arraycopy(chunk, 0, raw, written, toCopy)
            written += toCopy
        }
        inflater.end()
        if (written < raw.size) return null

        // Reverse PNG's per-row filtering (None/Sub/Up/Average/Paeth) to
        // recover the plain unfiltered scanlines - see PNG spec section
        // 9. Each row is `stride` bytes of pixel data plus a 1-byte
        // filter-type prefix.
        val bpp = channels // bytes-per-pixel at 8-bit depth == channel count
        val unfiltered = ByteArray(stride * height)
        var rawPos = 0
        for (y in 0 until height) {
            val filterType = raw[rawPos].toInt() and 0xFF
            rawPos++
            val rowStart = y * stride
            val prevRowStart = (y - 1) * stride
            for (x in 0 until stride) {
                val rawByte = raw[rawPos + x].toInt() and 0xFF
                val a = if (x >= bpp) unfiltered[rowStart + x - bpp].toInt() and 0xFF else 0
                val b = if (y > 0) unfiltered[prevRowStart + x].toInt() and 0xFF else 0
                val c = if (y > 0 && x >= bpp) unfiltered[prevRowStart + x - bpp].toInt() and 0xFF else 0
                val recon = when (filterType) {
                    0 -> rawByte
                    1 -> rawByte + a
                    2 -> rawByte + b
                    3 -> rawByte + (a + b) / 2
                    4 -> rawByte + paethPredictor(a, b, c)
                    else -> rawByte
                }
                unfiltered[rowStart + x] = recon.toByte()
            }
            rawPos += stride
        }

        val pixels = IntArray(width * height)
        when (colorType) {
            2 -> for (i in 0 until width * height) {
                val o = i * 3
                pixels[i] = argb(unfiltered[o].toInt() and 0xFF, unfiltered[o + 1].toInt() and 0xFF, unfiltered[o + 2].toInt() and 0xFF)
            }
            6 -> for (i in 0 until width * height) {
                val o = i * 4
                val r = unfiltered[o].toInt() and 0xFF
                val g = unfiltered[o + 1].toInt() and 0xFF
                val b = unfiltered[o + 2].toInt() and 0xFF
                val a = unfiltered[o + 3].toInt() and 0xFF
                pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
            }
            0 -> for (i in 0 until width * height) {
                val gray = unfiltered[i].toInt() and 0xFF
                pixels[i] = argb(gray, gray, gray)
            }
            3 -> {
                val pal = palette ?: return null
                for (i in 0 until width * height) {
                    val idx = unfiltered[i].toInt() and 0xFF
                    val po = idx * 3
                    if (po + 2 >= pal.size) { pixels[i] = 0; continue }
                    val r = pal[po].toInt() and 0xFF
                    val g = pal[po + 1].toInt() and 0xFF
                    val b = pal[po + 2].toInt() and 0xFF
                    val a = trns?.getOrNull(idx)?.toInt()?.and(0xFF) ?: 0xFF
                    pixels[i] = (a shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
        }
        return TerminalBuffer.KittyImage(imageId, width, height, pixels)
    }

    // PNG's Paeth predictor (spec section 9.2) - picks whichever of the
    // left/above/upper-left reconstructed neighbor is numerically
    // closest to a simple linear predictor of the three.
    private fun paethPredictor(a: Int, b: Int, c: Int): Int {
        val p = a + b - c
        val pa = kotlin.math.abs(p - a)
        val pb = kotlin.math.abs(p - b)
        val pc = kotlin.math.abs(p - c)
        return if (pa <= pb && pa <= pc) a else if (pb <= pc) b else c
    }

    // ---------------------------------------------------------------
    // Kitty graphics protocol - Unicode placeholder mode (U+10EEEE)
    //
    // The placeholder character itself - a Private Use Area codepoint
    // that carries no inherent meaning beyond "a Kitty-aware terminal
    // should render an image tile here instead of this glyph". Written
    // to the grid as perfectly ordinary text by a client that knows
    // nothing about terminal graphics (vim, tmux) - only the terminal
    // itself (writeChar's own detection branch below, and TerminalView's
    // paint pass) treats it specially. Defined in the companion object
    // (moved from a bare class-body `const val`, which Kotlin only
    // permits at top level or inside a named/companion object) alongside
    // this file's other `const val`s like ZWJ, for the same reason those
    // live there.

    // Row/column diacritic table, in exactly the order kitty's own
    // rowcolumn-diacritics.txt lists them (combining-class-230 marks
    // from Unicode 6.0 with fusion-prone ones excluded) - index 0 is
    // U+0305 (per the spec's own worked example: "U+305 is the diacritic
    // corresponding to the number 0"), index 1 is U+030D, and so on
    // through all 296 entries. A cell's row index is encoded by the
    // FIRST combining diacritic to follow the placeholder character,
    // its column index by the SECOND - see decodeKittyPlaceholderIndex
    // for how a run of combining chars after U+10EEEE is split into
    // (row, col, [image-id-extension]) using this same table for all
    // three. Kept as a flat IntArray of codepoints (not Char, since
    // several entries are astral-plane - e.g. 0x10A0F, 0x1D185 - and
    // don't fit in one UTF-16 Char) rather than a generated/parsed
    // rowcolumn-diacritics.txt at runtime, since the table is fixed by
    // spec and never changes.
    private val KITTY_ROWCOLUMN_DIACRITICS = intArrayOf(
        0x0305, 0x030D, 0x030E, 0x0310, 0x0312, 0x033D, 0x033E, 0x033F,
        0x0346, 0x034A, 0x034B, 0x034C, 0x0350, 0x0351, 0x0352, 0x0357,
        0x035B, 0x0363, 0x0364, 0x0365, 0x0366, 0x0367, 0x0368, 0x0369,
        0x036A, 0x036B, 0x036C, 0x036D, 0x036E, 0x036F, 0x0483, 0x0484,
        0x0485, 0x0486, 0x0487, 0x0592, 0x0593, 0x0594, 0x0595, 0x0597,
        0x0598, 0x0599, 0x059C, 0x059D, 0x059E, 0x059F, 0x05A0, 0x05A1,
        0x05A8, 0x05A9, 0x05AB, 0x05AC, 0x05AF, 0x05C4, 0x0610, 0x0611,
        0x0612, 0x0613, 0x0614, 0x0615, 0x0616, 0x0617, 0x0657, 0x0658,
        0x0659, 0x065A, 0x065B, 0x065D, 0x065E, 0x06D6, 0x06D7, 0x06D8,
        0x06D9, 0x06DA, 0x06DB, 0x06DC, 0x06DF, 0x06E0, 0x06E1, 0x06E2,
        0x06E4, 0x06E7, 0x06E8, 0x06EB, 0x06EC, 0x0730, 0x0732, 0x0733,
        0x0735, 0x0736, 0x073A, 0x073D, 0x073F, 0x0740, 0x0741, 0x0743,
        0x0745, 0x0747, 0x0749, 0x074A, 0x07EB, 0x07EC, 0x07ED, 0x07EE,
        0x07EF, 0x07F0, 0x07F1, 0x07F3, 0x0816, 0x0817, 0x0818, 0x0819,
        0x081B, 0x081C, 0x081D, 0x081E, 0x081F, 0x0820, 0x0821, 0x0822,
        0x0823, 0x0825, 0x0826, 0x0827, 0x0829, 0x082A, 0x082B, 0x082C,
        0x082D, 0x0951, 0x0953, 0x0954, 0x0F82, 0x0F83, 0x0F86, 0x0F87,
        0x135D, 0x135E, 0x135F, 0x17DD, 0x193A, 0x1A17, 0x1A75, 0x1A76,
        0x1A77, 0x1A78, 0x1A79, 0x1A7A, 0x1A7B, 0x1A7C, 0x1B6B, 0x1B6D,
        0x1B6E, 0x1B6F, 0x1B70, 0x1B71, 0x1B72, 0x1B73, 0x1CD0, 0x1CD1,
        0x1CD2, 0x1CDA, 0x1CDB, 0x1CE0, 0x1DC0, 0x1DC1, 0x1DC3, 0x1DC4,
        0x1DC5, 0x1DC6, 0x1DC7, 0x1DC8, 0x1DC9, 0x1DCB, 0x1DCC, 0x1DD1,
        0x1DD2, 0x1DD3, 0x1DD4, 0x1DD5, 0x1DD6, 0x1DD7, 0x1DD8, 0x1DD9,
        0x1DDA, 0x1DDB, 0x1DDC, 0x1DDD, 0x1DDE, 0x1DDF, 0x1DE0, 0x1DE1,
        0x1DE2, 0x1DE3, 0x1DE4, 0x1DE5, 0x1DE6, 0x1DFE, 0x20D0, 0x20D1,
        0x20D4, 0x20D5, 0x20D6, 0x20D7, 0x20DB, 0x20DC, 0x20E1, 0x20E7,
        0x20E9, 0x20F0, 0x2CEF, 0x2CF0, 0x2CF1, 0x2DE0, 0x2DE1, 0x2DE2,
        0x2DE3, 0x2DE4, 0x2DE5, 0x2DE6, 0x2DE7, 0x2DE8, 0x2DE9, 0x2DEA,
        0x2DEB, 0x2DEC, 0x2DED, 0x2DEE, 0x2DEF, 0x2DF0, 0x2DF1, 0x2DF2,
        0x2DF3, 0x2DF4, 0x2DF5, 0x2DF6, 0x2DF7, 0x2DF8, 0x2DF9, 0x2DFA,
        0x2DFB, 0x2DFC, 0x2DFD, 0x2DFE, 0x2DFF, 0xA66F, 0xA67C, 0xA67D,
        0xA6F0, 0xA6F1, 0xA8E0, 0xA8E1, 0xA8E2, 0xA8E3, 0xA8E4, 0xA8E5,
        0xA8E6, 0xA8E7, 0xA8E8, 0xA8E9, 0xA8EA, 0xA8EB, 0xA8EC, 0xA8ED,
        0xA8EE, 0xA8EF, 0xA8F0, 0xA8F1, 0xAAB0, 0xAAB2, 0xAAB3, 0xAAB7,
        0xAAB8, 0xAABE, 0xAABF, 0xAAC1, 0xFE20, 0xFE21, 0xFE22, 0xFE23,
        0xFE24, 0xFE25, 0xFE26, 0x10A0F, 0x10A38, 0x1D185, 0x1D186, 0x1D187,
        0x1D188, 0x1D189, 0x1D1AA, 0x1D1AB, 0x1D1AC, 0x1D1AD, 0x1D242, 0x1D243,
        0x1D244
    )

    // Reverse lookup (codepoint -> table index) built once, since
    // writeChar needs to test "is this combining char one of the
    // row/column diacritics, and if so which index" on every single
    // character written while a placeholder run is active - a linear
    // scan of KITTY_ROWCOLUMN_DIACRITICS per character would be fine at
    // this table's size (296 entries) but a map lookup is both clearer
    // and avoids re-scanning on every keystroke of a densely
    // placeholder-packed image (hundreds of cells for a large sixel-
    // replacement image).
    private val kittyDiacriticIndex: Map<Int, Int> by lazy {
        val m = HashMap<Int, Int>(KITTY_ROWCOLUMN_DIACRITICS.size * 2)
        for (idx in KITTY_ROWCOLUMN_DIACRITICS.indices) m[KITTY_ROWCOLUMN_DIACRITICS[idx]] = idx
        m
    }

    // Last COMPLETE Unicode placeholder cell's resolved reference,
    // carried forward so a subsequent bare U+10EEEE with NO diacritics
    // at all (the spec's own "rules are applied left-to-right...allows
    // specifying only row diacritics of the first column" shorthand -
    // see this field's use in decodeKittyPlaceholderRun) can inherit the
    // previous cell's row and simply increment the column, rather than
    // this emulator having to fail closed on every placeholder past the
    // first one in a row. Reset to null whenever a NON-placeholder
    // character is written (writeChar's own branch), same "run breaks
    // the inheritance chain" rule the spec describes.
    private var lastKittyPlaceholderRef: TerminalBuffer.KittyPlaceholderRef? = null

    // Decodes one grapheme cluster that STARTS with U+10EEEE plus
    // whatever combining diacritics append() already clustered onto it
    // (see append()'s own grapheme-clustering doc - combining marks
    // following a base character land in the SAME Cell.text string this
    // function receives, exactly like an emoji + variation selector
    // would) into a resolved (imageId, placementId, tileRow, tileCol).
    // Returns null if `text` isn't a placeholder run at all (the normal
    // case for every other character ever written), in which case
    // writeChar proceeds exactly as before.
    //
    // Diacritic assignment, per spec: the placeholder's foreground
    // color (curFg) IS the image id - a plain 0-255 SGR 256-color index
    // for an 8-bit id (the common case), or a truecolor RGB value
    // (curFg with TerminalPalette.TRUECOLOR_MARKER set) providing a
    // 24-bit id via three combining diacritics (row, col, and a THIRD
    // diacritic for the id's high bits) instead of the usual two. This
    // emulator always treats a 2-diacritic run as row+col with id taken
    // straight from curFg's low bits, and a 3-diacritic run as row+col+
    // id-extension - covering both the common 8-bit-id case and the
    // extended 24-bit-id case real encoders (tupimage, kittytgp) emit.
    private fun decodeKittyPlaceholderRun(text: String): TerminalBuffer.KittyPlaceholderRef? {
        val codepoints = text.codePoints().toArray()
        if (codepoints.isEmpty() || codepoints[0] != KITTY_PLACEHOLDER_CHAR) return null

        val diacriticIndices = ArrayList<Int>(3)
        for (i in 1 until codepoints.size) {
            val idx = kittyDiacriticIndex[codepoints[i]] ?: continue
            diacriticIndices.add(idx)
        }

        // Base image id from the foreground color - low 24 bits whether
        // it's a plain palette index (0-255, TRUECOLOR_MARKER unset) or
        // a packed truecolor value (TRUECOLOR_MARKER set, RGB in the low
        // 24 bits) - either way the SAME bits the spec says to read the
        // id from, since this emulator packs both into curFg identically
        // apart from that one marker bit (see applySgr's truecolor
        // branch and its own doc).
        var imageId = curFg and 0x00FFFFFF

        val row: Int
        val col: Int
        when (diacriticIndices.size) {
            0 -> {
                // Bare placeholder, no diacritics at all - only valid as
                // a continuation of the immediately preceding placeholder
                // cell (spec's left-to-right inheritance shorthand): row
                // inherited unchanged, column advances by one. Fails
                // closed (returns null - and null means "no placeholder
                // detected", so writeChar simply writes literal, WRONG-
                // looking placeholder text) if there's no prior cell to
                // inherit from, exactly the ungainly-but-spec-compliant
                // outcome the spec itself calls out as the terminal's
                // prerogative to handle heuristically or not.
                val prev = lastKittyPlaceholderRef ?: return null
                row = prev.tileRow
                col = prev.tileCol + 1
            }
            1 -> {
                // Spec's OWN worked shorthand: "allows specifying only
                // row diacritics of the first column" - one diacritic
                // present means ROW only, column continues the previous
                // cell's row's own left-to-right count (0 if this is a
                // fresh row, i.e. no previous ref OR the previous ref's
                // row differs from this one's).
                row = diacriticIndices[0]
                val prev = lastKittyPlaceholderRef
                col = if (prev != null && prev.tileRow == row) prev.tileCol + 1 else 0
            }
            else -> {
                // 2 diacritics: row, col. 3+: row, col, and a THIRD
                // diacritic extending the image id into its high bits
                // for the 24-bit-id case (id = low 8 bits from curFg,
                // shifted-in high bits from this third diacritic's own
                // table index) - see this function's header doc.
                row = diacriticIndices[0]
                col = diacriticIndices[1]
                if (diacriticIndices.size >= 3) {
                    imageId = (imageId and 0xFF) or (diacriticIndices[2] shl 8)
                }
            }
        }
        // Placement id, per spec, comes from underline color rather than
        // foreground - NOT tracked as separate state in this emulator
        // (see this class's own header note on underline-color scope),
        // so every placeholder-mode image is treated as placement id 0,
        // matching how the overwhelming majority of real usage (a
        // single virtual placement per image, exactly the case the
        // original discussion/PR that introduced this feature explicitly
        // called out as the only one initially supported) never sets a
        // second placement anyway.
        val ref = TerminalBuffer.KittyPlaceholderRef(imageId, 0, row, col)
        lastKittyPlaceholderRef = ref
        return ref
    }

    private fun writeChar(text: String) {
        // DECSLRM-aware effective edges - see scrollLeft/scrollRight's own
        // doc. marginsActive is false for the overwhelming common case
        // (DECSLRM never used), which collapses rightEdge/leftEdge back to
        // exactly buffer.columns/0 - i.e. bit-for-bit the same wrap
        // behavior this function always had - so this is purely additive
        // for programs that never touch DECSLRM at all.
        val marginsActive = scrollLeft > 0 || scrollRight < buffer.columns - 1
        val rightEdge = if (marginsActive) scrollRight + 1 else buffer.columns
        val leftEdge = if (marginsActive) scrollLeft else 0
        if (cursorCol >= rightEdge) {
            if (autoWrapMode) {
                cursorCol = leftEdge
                lineFeed()
            } else {
                // DECAWM off: real terminals stop advancing past the last
                // column and keep overwriting that same cell instead of
                // wrapping - matches xterm's own "single-shot" behavior for
                // ESC[?7l (e.g. a progress bar redrawing the same bottom-
                // right cell every tick without pushing the screen up).
                cursorCol = rightEdge - 1
            }
        }
        // DEC special graphics translation (see DEC_SPECIAL_GRAPHICS's own
        // doc) - only for a single-Char cell (a multi-Char `text` is always
        // a grapheme cluster/emoji sequence from append()'s own clustering,
        // never a plain ASCII byte this charset could redefine, so leaving
        // those untouched is correct either way). Applies to whichever
        // G-set is currently INVOKED (see lockingGSet's own doc), not
        // always G0 - matches how a real terminal keeps all four
        // independently designated/invoked instead of only ever
        // consulting G0. A pending single-shift (singleShiftGSet, SS2/
        // SS3) overrides the locking shift for just this one character
        // and is consumed here - cleared unconditionally right after the
        // lookup so it never leaks into the NEXT character even when this
        // one wasn't itself a special-graphics designator.
        val activeGSet = singleShiftGSet ?: lockingGSet
        singleShiftGSet = null
        val activeIsSpecialGraphics = when (activeGSet) {
            CharsetSlot.G0 -> g0IsSpecialGraphics
            CharsetSlot.G1 -> g1IsSpecialGraphics
            CharsetSlot.G2 -> g2IsSpecialGraphics
            CharsetSlot.G3 -> g3IsSpecialGraphics
        }
        val effectiveText = if (activeIsSpecialGraphics && text.length == 1) {
            DEC_SPECIAL_GRAPHICS[text[0]]?.toString() ?: text
        } else {
            text
        }
        // Wide-glyph handling (CJK/fullwidth/emoji - see
        // graphemeDisplayWidth's own doc for the exact ranges). A wide
        // cluster that would land with its reserved second column past
        // the right edge can't be drawn without clipping into a column
        // that doesn't exist, so - same "doesn't fit, wrap or clamp"
        // posture as the plain single-column overflow check above - it
        // wraps to a fresh line first when autowrap is on (matching real
        // xterm's own wide-char wrap behavior: the leftover single column
        // is left blank rather than splitting the glyph across the wrap
        // boundary), or simply renders single-width-clamped into the
        // final column when DECAWM is off, consistent with the plain
        // overflow branch above already doing the same clamp-not-wrap for
        // narrow glyphs in that mode.
        val isWide = activeIsSpecialGraphics.not() && graphemeDisplayWidth(effectiveText) == 2
        if (isWide && autoWrapMode && cursorCol == rightEdge - 1) {
            cursorCol = leftEdge
            lineFeed()
        }
        val canFitWide = isWide && cursorCol < rightEdge - 1
        if (insertMode) {
            // IRM (Insert Mode) - see insertMode's own doc. Shifts the
            // rest of the row right by however many columns this glyph
            // will occupy (1, or 2 for a wide grapheme) rather than
            // overwriting whatever was already at the cursor - the same
            // buffer.insertChars a program-issued '@' (insertChars(),
            // just above this function) already uses, so this gets
            // identical row-shifting/right-edge-drop behavior for free.
            // Runs INSTEAD of the overwrite-consistency cleanup below
            // (that block is specifically for the REPLACE-mode case where
            // this write destroys whatever was at cursorCol - here
            // nothing is destroyed, the old content is shifted along
            // with everything to its right, wide-glyph pairs included).
            buffer.insertChars(cursorRow, cursorCol, if (canFitWide) 2 else 1, curBg)
        } else {
            // Overwrite consistency: this write is about to land on
            // cursorRow/cursorCol directly (a mid-line edit, redraw, or
            // cursor reposition - not necessarily the sequential
            // left-to-right append the rest of this function otherwise
            // assumes), so if whatever WAS there is one half of a
            // wide-glyph pair, its other half needs clearing too or it'd
            // be left as an orphaned "wide" flag with no glyph (the cell
            // this write just replaced) or an orphaned continuation cell
            // with nothing to its left to continue (the mirror case) -
            // either one would make the renderer stretch/skip a column
            // that no longer has a real wide glyph backing it.
            val existing = buffer.cellAt(cursorRow, cursorCol)
            if (existing.wide && cursorCol + 1 < buffer.columns) {
                buffer.setCell(cursorRow, cursorCol + 1, TerminalBuffer.Cell(bg = existing.bg))
            } else if (existing.isWideContinuation && cursorCol - 1 >= 0) {
                buffer.setCell(cursorRow, cursorCol - 1, TerminalBuffer.Cell(bg = existing.bg))
            }
        }
        // Kitty Unicode-placeholder detection (see
        // decodeKittyPlaceholderRun's own doc) - only ever attempted for
        // a grapheme starting with the placeholder codepoint itself, so
        // the overwhelming majority of writeChar calls (plain text) pay
        // only for the single codepoint-array-construction + first-
        // element comparison this needs, not a full diacritic-table
        // lookup per character written. A non-placeholder run explicitly
        // clears lastKittyPlaceholderRef (see that field's own doc on
        // why a run break must reset the left-to-right inheritance
        // chain) rather than leaving it stale for some LATER, unrelated
        // placeholder run to accidentally inherit from.
        val placeholderRef = decodeKittyPlaceholderRun(effectiveText)
        if (placeholderRef == null) lastKittyPlaceholderRef = null
        // See lastGraphicChar's own doc - recorded here (not in the CSI
        // 'b' REP handler itself) so REP always repeats whatever glyph
        // ACTUALLY landed on screen last, including DEC-special-graphics
        // translation and Kitty-placeholder runs, exactly like a real
        // terminal's REP does.
        lastGraphicChar = effectiveText
        buffer.setCell(
            cursorRow, cursorCol,
            TerminalBuffer.Cell(
                text = effectiveText, fg = curFg, bg = curBg,
                bold = curBold, underline = curUnderline,
                inverse = curInverse, italic = curItalic,
                dim = curDim, blink = curBlink, strikethrough = curStrikethrough,
                conceal = curConceal, overline = curOverline,
                underlineCurly = curUnderlineCurly, underlineColor = curUnderlineColor,
                hyperlink = curHyperlink,
                kittyPlaceholder = placeholderRef,
                wide = canFitWide
            )
        )
        cursorCol++
        // Reserve the second column a wide glyph actually occupies on
        // screen - see Cell.isWideContinuation's own doc for why this has
        // to be a real (if blank) Cell in the grid rather than just a
        // rendering-time skip: selection ranges, resize/reflow, and
        // scrollback copies all walk the grid column-by-column, and
        // without a real placeholder here they'd see the wide glyph's
        // right half as an ordinary empty cell belonging to whatever gets
        // written there next, rather than as still-part-of-the-glyph-to-
        // its-left space.
        if (canFitWide) {
            buffer.setCell(
                cursorRow, cursorCol,
                TerminalBuffer.Cell(bg = curBg, isWideContinuation = true)
            )
            cursorCol++
        }
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
        // LNM (Line Feed/New Line Mode) - see lineFeedNewLineMode's own
        // doc. When on, every bare LF/VT/FF ALSO does what a separate '\r'
        // normally does, in addition to the line movement just above.
        if (lineFeedNewLineMode) cursorCol = 0
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

    /**
     * Local-echo-only backspace: steps the cursor back one column and
     * clears that cell, the way a real terminal's line-editing visually
     * erases a character. Used exclusively by TerminalSession.write()'s
     * forceLocalEcho path for a raw '\u007F'/'\b' key press - those bytes
     * normally travel to the remote side to be interpreted there, with
     * the visual erase arriving back over the pty as its own explicit
     * "\b \b" (or an equivalent redraw), not as a side effect of the lone
     * backspace byte itself. Deliberately bypasses the ordinary
     * append()/handleNormal escape-sequence path entirely (unlike routing
     * a synthesized "\b \b" through append(), which relies on
     * handleNormal's own '\b' case and its own cursorCol<=0 handling) so
     * this stays a single, predictable buffer-level operation - in
     * particular, at cursorCol == 0 this is a guaranteed no-op rather
     * than however handleNormal's own '\b' case happens to treat the
     * left edge.
     */
    fun localEchoBackspace() {
        if (cursorCol <= 0) return
        cursorCol--
        buffer.setCell(cursorRow, cursorCol, TerminalBuffer.Cell(bg = curBg))
        listener.onContentChanged()
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

    // Average on-screen cell height in pixels, kept in sync by the caller
    // (see TerminalSession.resize's own pixelHeight doc) purely so
    // image-placement cursor-advance below can convert a natural-size
    // (no explicit c=/r=) image's pixel height into an actual row count,
    // instead of the single-lineFeed guess this used to always fall back
    // to. 0 (the default, and whatever a caller that never wires this up
    // leaves it at) means "unknown" and keeps the old single-line
    // behavior - never a crash, just the same imprecision as before for
    // callers that don't supply it.
    var cellHeightPx: Int = 0

    // How many on-screen ROWS an image of pixel height [heightPx] actually
    // covers once drawn, starting at its own anchor row - i.e. how many
    // lines the cursor needs to clear before it's genuinely below the
    // image rather than sitting on top of one of its own rows (see
    // decodeAndPlaceSixel's and kittyAnchor's own docs for why landing
    // cursor mid-image is exactly what let a later prompt redraw's
    // clearRow/eraseInLine wipe the image out from under the user).
    // cellHeightPx == 0 (never wired up, or not yet known at spawn time)
    // keeps the historical single-row estimate rather than guessing at a
    // ratio - same "safe under-estimate, never corrupts state" posture
    // the single-lineFeed fallback already had.
    private fun imageRowSpan(heightPx: Int): Int {
        if (cellHeightPx <= 0 || heightPx <= 0) return 1
        return kotlin.math.ceil(heightPx.toFloat() / cellHeightPx).toInt().coerceAtLeast(1)
    }

    // Advances the cursor `rows` lines down (column reset to 0), the same
    // as `repeat(rows) { lineFeed() }` - EXCEPT it never calls lineFeed()
    // for a row that would scroll the grid while `anchorRow` (the image
    // placement that was just anchored there) is still on-screen. A tall
    // image (anchorRow near the top, height spanning most/all of the
    // visible rows) previously had its own cursor-advance immediately
    // scroll it back off the top the instant it was placed - each
    // lineFeed() past the bottom margin calls buffer.scrollUp(), which
    // shifts every placed image's row up by one and drops it once that
    // goes negative (see TerminalBuffer.shiftImageRows's own doc), so
    // "auto-advance past a big image" and "the image scrolling off-
    // screen" were literally the same buffer.scrollUp() call. This is
    // what made a big image vanish after only 2-3 real Enters afterward
    // ("buyuk fotograflari render etdiyinde 3 defa enter basinca
    // siliniyor") - almost all of its own row budget had already been
    // silently spent by its own placement before the user typed anything.
    // Capping at `buffer.rows - 1 - anchorRow` (the number of rows
    // actually still below the anchor without scrolling) means the
    // cursor parks at the last visible row instead of pushing further -
    // an image taller than the visible screen will still get clipped at
    // the bottom edge by TerminalView's own draw-time bounds (same as
    // any oversized image), but at least it stays fully on-screen and
    // reachable instead of being discarded by its own placement.
    private fun advanceCursorRows(rows: Int, anchorRow: Int) {
        val safeRows = (buffer.rows - 1 - anchorRow).coerceAtLeast(0)
        val actualRows = rows.coerceAtMost(safeRows)
        cursorCol = 0
        repeat(actualRows) { lineFeed() }
        cursorRow = (anchorRow + actualRows).coerceAtMost(buffer.rows - 1)
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

    private fun applySgr(params: List<Int>, underlineSubStyle: Int? = null) {
        if (params.isEmpty()) { resetSgr(); return }
        var i = 0
        while (i < params.size) {
            when (val p = params[i]) {
                0 -> resetSgr()
                1 -> curBold = true
                2 -> curDim = true
                3 -> curItalic = true
                // Plain "4" (no colon) is always a straight single
                // underline, same as before. "4:N" additionally carries a
                // style in underlineSubStyle (extracted once per whole CSI
                // dispatch - see its own doc at the call site): 0 turns the
                // decoration off entirely (equivalent to 24), 3 selects the
                // curly/"undercurl" shape, anything else (1/2/4/5, or no
                // colon at all) falls back to the plain straight line -
                // double/dotted/dashed aren't modeled as their own distinct
                // shapes, but degrading them to a plain underline is a far
                // less jarring miss than dropping the decoration entirely.
                4 -> when (underlineSubStyle) {
                    0 -> { curUnderline = false; curUnderlineCurly = false }
                    3 -> { curUnderline = true; curUnderlineCurly = true }
                    else -> { curUnderline = true; curUnderlineCurly = false }
                }
                5, 6 -> curBlink = true
                7 -> curInverse = true
                // SGR 8 (conceal/"hidden") - see curConceal's own doc.
                8 -> curConceal = true
                9 -> curStrikethrough = true
                // 22 is "normal intensity" - real terminals clear BOTH bold
                // and dim here (they're the two ends of one "intensity"
                // attribute, only one active at a time), not just bold -
                // previously this left curDim untouched, so `tput dim;
                // tput sgr0`-style sequences (or any app sending 1 then 2
                // then 22) could leave text stuck dim after a reset that
                // should have cleared it.
                22 -> { curBold = false; curDim = false }
                23 -> curItalic = false
                24 -> { curUnderline = false; curUnderlineCurly = false }
                25 -> curBlink = false
                27 -> curInverse = false
                28 -> curConceal = false
                29 -> curStrikethrough = false
                // SGR 53 (overline) / 55 (off) - see curOverline's own doc.
                53 -> curOverline = true
                55 -> curOverline = false
                in 30..37 -> curFg = p - 30
                in 40..47 -> curBg = p - 40
                in 90..97 -> curFg = p - 90 + 8
                in 100..107 -> curBg = p - 100 + 8
                38, 48 -> {
                    // Extended color: 38;5;N (256-color) or 38;2;R;G;B (truecolor)
                    if (i + 1 < params.size && params[i + 1] == 5 && i + 2 < params.size) {
                        val colorIdx = params[i + 2]
                        if (p == 38) curFg = colorIdx else curBg = colorIdx
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2 && i + 4 < params.size) {
                        // Packed into the same Int as a plain 0-255 ANSI
                        // index using TerminalPalette.TRUECOLOR_MARKER (see
                        // its own doc) - previously the R/G/B params were
                        // read past (i += 4) but never actually stored
                        // anywhere, so curFg/curBg were silently left
                        // unchanged and every truecolor SGR was a no-op:
                        // programs relying on 24-bit color (bat, delta,
                        // neovim themes, modern ls/fzf themes) rendered in
                        // whatever color happened to be active before the
                        // truecolor escape, not the color actually
                        // requested. Params clamped to 0..255 same as any
                        // other untrusted CSI param (see the repeat-count
                        // clamp above) - a malformed/out-of-range R/G/B
                        // shouldn't be able to set stray high bits that
                        // collide with TRUECOLOR_MARKER itself or wrap
                        // negative.
                        val r = params[i + 2].coerceIn(0, 255)
                        val g = params[i + 3].coerceIn(0, 255)
                        val b = params[i + 4].coerceIn(0, 255)
                        val packed = TerminalPalette.TRUECOLOR_MARKER or (r shl 16) or (g shl 8) or b
                        if (p == 38) curFg = packed else curBg = packed
                        i += 4
                    }
                }
                39 -> curFg = TerminalBuffer.DEFAULT_FOREGROUND
                49 -> curBg = TerminalBuffer.DEFAULT_BACKGROUND
                // SGR 58 (set underline color) - same 5/2 sub-forms and same
                // semicolon-joined convention this class already uses for
                // 38/48 above (see that branch's own doc on packing
                // truecolor via TerminalPalette.TRUECOLOR_MARKER); kept
                // consistent with 38/48 rather than switching to the
                // colon-joined form the spec technically prefers for 58,
                // since real-world senders of this sequence (kitty, VS
                // Code's terminal, iTerm2) commonly emit the same
                // semicolon shape for it that this parser already handles.
                58 -> {
                    if (i + 1 < params.size && params[i + 1] == 5 && i + 2 < params.size) {
                        curUnderlineColor = params[i + 2]
                        i += 2
                    } else if (i + 1 < params.size && params[i + 1] == 2 && i + 4 < params.size) {
                        val r = params[i + 2].coerceIn(0, 255)
                        val g = params[i + 3].coerceIn(0, 255)
                        val b = params[i + 4].coerceIn(0, 255)
                        curUnderlineColor = TerminalPalette.TRUECOLOR_MARKER or (r shl 16) or (g shl 8) or b
                        i += 4
                    }
                }
                // SGR 59 - reset underline color back to "follow the
                // glyph's own foreground", the pre-58 default (see
                // curUnderlineColor's own doc on why null means that rather
                // than a stored color value).
                59 -> curUnderlineColor = null
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
        curDim = false
        curBlink = false
        curStrikethrough = false
        curConceal = false
        curOverline = false
        curUnderlineCurly = false
        curUnderlineColor = null
        // Deliberately NOT resetting curHyperlink here: xterm and every
        // real terminal treat OSC 8's link state as independent of SGR -
        // only another OSC 8 (with an empty URI) closes a link, and colored
        // hyperlink text routinely does "OSC 8;;url ST <colored text via
        // its own SGR resets in between> OSC 8;; ST" without the link
        // dropping partway through. Clearing it here would end the link
        // the moment the *color* got reset (a plain "ESC[0m", which
        // resetSgr() also handles - not just the RIS full-reset case) even
        // though no OSC 8 closer had actually arrived yet.
    }

    private fun reset() {
        buffer.clearAll()
        // RIS puts OSC 4/10/11/12 color overrides back to their real
        // theme defaults too - see clearAllColorOverrides' own doc for
        // why this is deliberately NOT also done on alternate-screen
        // entry/exit (unlike clearAllKittyImages just above/below it).
        buffer.clearAllColorOverrides()
        cursorRow = 0
        cursorCol = 0
        resetSgr()
        scrollTop = 0
        scrollBottom = buffer.rows - 1
        scrollLeft = 0
        scrollRight = buffer.columns - 1
        declrmmEnabled = false
        cursorVisible = true
        cursorStyle = CursorStyle.BLOCK
        curHyperlink = null
        lastGraphicChar = null
        applicationCursorKeys = false
        applicationKeypadMode = false
        bracketedPasteMode = false
        autoWrapMode = true
        deccolm132Mode = false
        focusReportingMode = false
        inSynchronizedUpdate = false
        // RIS puts every DEC-private/ANSI mode this emulator tracks back
        // to its power-on default, the same as autoWrapMode/
        // focusReportingMode/etc. just above - DECOM and IRM off (xterm's
        // own defaults - see each field's own doc), DECSCNM off. Origin
        // mode being cleared here also means the cursor-home just above
        // (cursorRow/cursorCol = 0) is already correct under the new
        // (now-absolute) addressing - no extra scrollTop/scrollLeft homing
        // needed the way handlePrivateMode's own case 6 does when a
        // program toggles DECOM mid-session instead of via a full reset.
        originMode = false
        insertMode = false
        buffer.reverseVideoMode = false
        // RIS puts LNM, SRM, and DECARM back to their own power-on
        // defaults too, same as every other mode reset just above - see
        // each field's own doc for why lineFeedNewLineMode/
        // sendReceiveMode default off and autoRepeatMode defaults on.
        lineFeedNewLineMode = false
        sendReceiveMode = false
        autoRepeatMode = true
        g0IsSpecialGraphics = false
        g1IsSpecialGraphics = false
        g2IsSpecialGraphics = false
        g3IsSpecialGraphics = false
        lockingGSet = CharsetSlot.G0
        singleShiftGSet = null
        oscPendingSt = false
        dcsPendingSt = false
        dcsIsSixel = false
        dcsBuffer.clear()
        // RIS drops back to fully legacy key encoding - a program that
        // pushed its own enhancement flags and then crashed/was killed
        // without a matching POP would otherwise leave the NEXT program
        // (typically the shell itself) silently stuck receiving
        // disambiguated key reports it never asked for and doesn't parse -
        // exactly the kind of stuck-state a full reset exists to clear.
        kittyKeyboardFlags = 0
        kittyFlagStack.clear()
        titleStack.clear()
        state = State.NORMAL
        // A full reset (e.g. `clear` invoked with a program restart, or the
        // shell re-execing) means every prompt mark recorded so far refers
        // to a screen that's about to be wiped out from under it - stale
        // navigation targets are worse than none, so start the list fresh.
        promptMarks.clear()
        // RIS (full reset) puts a real terminal back to its default every-
        // 8th-column tab layout, discarding anything a program set/cleared
        // via HTS/TBC - back to null so nextTabStop() falls back to the
        // plain default again instead of replaying a stale custom set from
        // whatever program was running before the reset.
        tabStops = null
        lineTabStops = null
    }

    fun getCursorRow() = cursorRow
    fun getCursorCol() = cursorCol

    /**
     * Anchors an already-decoded image (PNG/JPEG/GIF pixels the caller
     * got via android.graphics.BitmapFactory, per onInlineImageData's
     * own doc on why that decode can't happen inside this module) at the
     * CURRENT cursor position, then advances past it - the exact same
     * "placeImage at (cursorRow, cursorCol), reset column, line-feed"
     * shape decodeAndPlaceSixel uses for the plain-Sixel path just above
     * it in this file, reproduced here as its own public entry point
     * since decodeAndPlaceSixel itself is only ever reached from this
     * module's own DCS state machine, not from an external callback.
     *
     * Deliberately requires the caller to hand back pixels (rather than
     * exposing cursorRow/cursorCol as public vars the caller could poke
     * at directly and call buffer.placeImage itself) so this stays the
     * ONE place that couples "an image just got placed" to "the cursor
     * moves past it" - letting a caller place an image without also
     * going through this would silently reproduce decodeAndPlaceSixel's
     * cursor-advance logic a second time, or skip it entirely.
     *
     * Must be called on the same thread that drives [append] (the pty
     * reader thread - see TerminalSession's own reader-thread doc) even
     * though this itself isn't invoked from inside an [append] call the
     * way decodeAndPlaceSixel is: onInlineImageData fires synchronously
     * from within [finishOsc], but decoding the base64 into pixels
     * (BitmapFactory) is comparatively slow, so a caller is expected to
     * capture the base64 string out of that callback and do the actual
     * decode + this call afterward - by which point [append] may already
     * be mid-way through a LATER chunk of output, and cursorRow/cursorCol
     * would have moved on. Calling this from any other thread risks
     * exactly the same cross-thread races buffer's own lock protects
     * against for every other mutation - see TerminalBuffer.placeImage's
     * own withLock.
     */
    fun placeDecodedInlineImage(pixels: IntArray, width: Int, height: Int) {
        if (width <= 0 || height <= 0 || pixels.size < width * height) return
        buffer.placeImage(TerminalBuffer.SixelImage(width, height, pixels), cursorRow, cursorCol)
        // Same advanceCursorRows reasoning as decodeAndPlaceSixel's own
        // cursor advance just above - caps at what's actually available
        // below the image instead of scrolling it off via its own placement.
        advanceCursorRows(imageRowSpan(height), anchorRow = cursorRow)
    }

    /** Call on a real focus transition (Activity onResume/onPause, or a
     *  multi-window/split-screen focus change) so a program that requested
     *  focus reporting (see [focusReportingMode]'s own doc) actually
     *  receives the "ESC[I"/"ESC[O" it asked for. No-ops if the running
     *  program never enabled DECSET 1004 - same "don't report noise nobody
     *  asked for" posture as onWantsMouseEvents/mouseMode elsewhere in this
     *  class. */
    fun reportFocusChange(focused: Boolean) {
        if (!focusReportingMode) return
        listener.onRespond(if (focused) "\u001B[I" else "\u001B[O")
    }

    /** Called when the underlying buffer is resized (e.g. on rotation) so
     *  the scrolling region doesn't keep referencing the old row count. */
    fun onBufferResized() {
        val wasFullScreen = scrollTop == 0 && scrollBottom >= 0
        scrollBottom = (buffer.rows - 1).coerceAtLeast(0)
        if (wasFullScreen) scrollTop = 0
        scrollTop = scrollTop.coerceIn(0, scrollBottom)
        // buffer.resize() (called just before this, by TerminalSession.
        // resize()) already moved buffer.cursorRow to the correct row for
        // the new grid - shifting it by rowOffset/totalTopPadding as
        // content scrolled into/out of scrollback (see its own doc: the
        // "beyaz cursor ekranin ortasinda asili kaliyor" bug). Reading this
        // class's OWN cursorRow field here instead - which the resize call
        // never touched, so it's still the PRE-resize row number - and
        // merely coercing it back into buffer.rows was wrong on every
        // grow/shrink that actually shifted content: the coerce alone
        // doesn't reproduce that shift, so the field's stale value went
        // straight back into buffer.cursorRow via this class's own setter
        // below, silently undoing the correct shifted value resize() had
        // just computed - which is exactly why that fix kept appearing to
        // not take: this ran a moment later on every single resize and
        // clobbered it back to the wrong row every time. Reading FROM
        // buffer.cursorRow instead (the value resize() just got right) and
        // only coercing THAT keeps this class's own field in sync with the
        // buffer's already-correct post-resize cursor instead of
        // overwriting it.
        cursorRow = buffer.cursorRow.coerceIn(0, buffer.rows - 1)
        // Same clobbering hazard as cursorRow above, just for the column
        // axis: buffer.resize() already coerced buffer.cursorCol against
        // the new column count. Reading FROM this class's own (stale,
        // pre-resize) cursorCol field here - as this used to - and
        // writing it back through the setter would silently overwrite
        // that already-correct value with the old one on every column
        // resize, the exact same bug the cursorRow fix above addresses.
        cursorCol = buffer.cursorCol.coerceIn(0, buffer.columns - 1)
        // DECSLRM margins were computed against the PRE-resize column
        // count too (same staleness hazard tabStops' own doc describes) -
        // clamp scrollRight into the new width and, if that collapses the
        // region to nothing meaningful (left >= right), just drop back to
        // the full new width rather than leaving an inverted/degenerate
        // margin pair in place.
        scrollRight = scrollRight.coerceIn(0, buffer.columns - 1)
        scrollLeft = scrollLeft.coerceIn(0, buffer.columns - 1)
        if (scrollLeft >= scrollRight) {
            scrollLeft = 0
            scrollRight = buffer.columns - 1
        }
        // A custom tab-stop set was built against the PRE-resize column
        // count (defaultTabStops() reads buffer.columns at the moment HTS/
        // TBC first materializes it). If the grid just shrank, stale stops
        // past the new right edge would otherwise sit in the set forever -
        // harmless for nextTabStop() (which already coerces its result
        // into range) but only by accident, and worth trimming so the set
        // doesn't silently drift out of sync with what TBC Ps=3 or a fresh
        // HTS would produce against the current width.
        tabStops?.let { stops -> stops.removeAll { it >= buffer.columns } }
        // Same stale-entry trim as tabStops just above, mirrored for the
        // row axis: a line tab stop past the new (shrunk) row count can
        // never be cleared by a future in-range TBC Ps=1 again otherwise.
        lineTabStops?.let { stops -> stops.removeAll { it >= buffer.rows } }
    }
}
