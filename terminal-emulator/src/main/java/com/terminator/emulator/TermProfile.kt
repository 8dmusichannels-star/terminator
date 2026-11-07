/*
 * Terminator - Modern terminal for Android
 *
 * Per-$TERM behaviour profile. The emulator itself is xterm-class whatever
 * $TERM says; this profile only decides the places where the terminfo entry
 * the program is reading *differs* from what a plain xterm would send or
 * answer - key encodings (Home/End/F1-F4/Backspace/modified arrows) and the
 * identity replies (DA1/DA2/DECID) - so that what the user types and what
 * the program was told to expect agree.
 *
 * The same profile is used by the physical-keyboard path
 * (PhysicalKeyEvent) and the on-screen key bar (VirtualKeyBar/MainActivity),
 * and both honour DECCKM (application cursor keys) the same way.
 */
package com.terminator.emulator

enum class TermProfile(
    /** What the Backspace key sends unless the program overrode it with DECBKM. */
    val backspace: String,
    /** Home/End in normal and in DECCKM (application) cursor mode. */
    val homeNormal: String,
    val homeApp: String,
    val endNormal: String,
    val endApp: String,
    /** True: F1-F4 are SS3 P..S ("\u001BOP"); false: CSI 11~..14~ (xterm-color). */
    val f1to4UseSs3: Boolean,
    /** Whether Ctrl/Shift/Alt-modified cursor and function keys use "CSI 1;m X". */
    val modifiedKeys: Boolean,
    /** Primary / secondary device attributes replies. */
    val da1: String,
    val da2: String,
    /** DEC conformance level 1..4 this terminal type starts at (VT100=1, VT220=2). */
    val defaultConformanceLevel: Int
) {
    /**
     * Terminal Type = NONE (and any $TERM this app has no specific profile
     * for): no terminfo entry is assumed, so this is the plain, widely
     * compatible baseline - DECCKM-aware cursor keys, DEL for Backspace,
     * SS3 F1-F4, xterm-style modified keys and Home/End, and the stock
     * identity replies. It deliberately is not tied to any $TERM value.
     */
    GENERIC(
        backspace = "\u007F",
        homeNormal = "\u001B[H", homeApp = "\u001BOH",
        endNormal = "\u001B[F", endApp = "\u001BOF",
        f1to4UseSs3 = true, modifiedKeys = true,
        da1 = "\u001B[?1;2;4c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 4
    ),

    XTERM(
        backspace = "\u007F",
        homeNormal = "\u001B[H", homeApp = "\u001BOH",
        endNormal = "\u001B[F", endApp = "\u001BOF",
        f1to4UseSs3 = true, modifiedKeys = true,
        da1 = "\u001B[?1;2;4c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 4
    ),

    /** terminfo "xterm-color": 8 colours, F1-F4 as CSI 11~..14~, no Home/End capabilities. */
    XTERM_COLOR(
        backspace = "\u007F",
        homeNormal = "\u001B[H", homeApp = "\u001BOH",
        endNormal = "\u001B[F", endApp = "\u001BOF",
        f1to4UseSs3 = false, modifiedKeys = true,
        da1 = "\u001B[?1;2;4c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 4
    ),

    /** screen, screen-256color, tmux, tmux-256color: khome=\E[1~, kend=\E[4~. */
    SCREEN_TMUX(
        backspace = "\u007F",
        homeNormal = "\u001B[1~", homeApp = "\u001B[1~",
        endNormal = "\u001B[4~", endApp = "\u001B[4~",
        f1to4UseSs3 = true, modifiedKeys = true,
        da1 = "\u001B[?1;2;4c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 4
    ),

    /** DEC VT220: Find/Select act as Home/End, kbs=^H, no modified keys, VT220 identity. */
    VT220(
        backspace = "\b",
        homeNormal = "\u001B[1~", homeApp = "\u001B[1~",
        endNormal = "\u001B[4~", endApp = "\u001B[4~",
        f1to4UseSs3 = true, modifiedKeys = false,
        da1 = "\u001B[?62;1;2;6;7;8;9c", da2 = "\u001B[>1;10;0c",
        defaultConformanceLevel = 2
    ),

    /**
     * DEC VT100. The real keyboard has no Home/End; xterm's codes are kept
     * as a deliberate convenience (readline binds them regardless of
     * terminfo), everything else follows terminfo: kbs=^H, no modifiers.
     */
    VT100(
        backspace = "\b",
        homeNormal = "\u001B[H", homeApp = "\u001BOH",
        endNormal = "\u001B[F", endApp = "\u001BOF",
        f1to4UseSs3 = true, modifiedKeys = false,
        da1 = "\u001B[?1;2c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 1
    ),

    /** terminfo "ansi": khome=\E[H, kbs=^H, no modified keys. */
    ANSI(
        backspace = "\b",
        homeNormal = "\u001B[H", homeApp = "\u001B[H",
        endNormal = "\u001B[F", endApp = "\u001B[F",
        f1to4UseSs3 = true, modifiedKeys = false,
        da1 = "\u001B[?1;2c", da2 = "\u001B[>0;64;0c",
        defaultConformanceLevel = 1
    );

    /** "CSI X" in normal mode, "SS3 X" in DECCKM mode (arrows). */
    fun cursorKey(letter: Char, applicationMode: Boolean): String =
        if (applicationMode) "\u001BO$letter" else "\u001B[$letter"

    fun home(applicationMode: Boolean): String = if (applicationMode) homeApp else homeNormal

    fun end(applicationMode: Boolean): String = if (applicationMode) endApp else endNormal

    /** F1..F4 (n = 1..4). */
    fun functionKey1to4(n: Int): String =
        if (f1to4UseSs3) "\u001BO${'P' + (n - 1)}" else "\u001B[${10 + n}~"

    /**
     * The Backspace byte(s): DECBKM ("CSI ? 67 h" = BS, "l" = DEL) wins when
     * the program set it, otherwise the profile's terminfo default.
     */
    fun backspaceFor(decbkm: Boolean?): String = when (decbkm) {
        true -> "\b"
        false -> "\u007F"
        null -> backspace
    }

    companion object {
        /** Maps the value stored in Settings > Keyboard > Terminal Type to a profile. */
        fun fromTermType(termType: String?): TermProfile {
            val t = termType?.trim()?.lowercase().orEmpty()
            return when {
                t.isEmpty() || t == "none" -> GENERIC
                t.startsWith("xterm-color") -> XTERM_COLOR
                t.startsWith("screen") || t.startsWith("tmux") -> SCREEN_TMUX
                t == "vt220" -> VT220
                t == "vt100" -> VT100
                t == "ansi" -> ANSI
                t == "xterm" || t == "xterm-256color" || t == "xterm-kitty" -> XTERM
                else -> GENERIC // anything this app has no specific profile for
            }
        }
    }
}
