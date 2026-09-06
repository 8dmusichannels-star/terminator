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

package com.terminator.app.ui

import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Turns a hardware/Bluetooth keyboard's [KeyEvent] into the same kind of
 * terminal escape sequence VirtualKeyBar's own onKeyPressed already
 * produces for its on-screen keys - CTRL/ALT/SHIFT modifiers, arrows,
 * HOME/END/PGUP/PGDN, function keys, and plain character keys (letters,
 * digits, punctuation) all funnel through here.
 *
 * Deliberately does NOT touch CapsLock/NumLock: those are handled by
 * Android/the connected keyboard's own firmware before this app's
 * KeyEvent ever arrives - KeyEvent.getUnicodeChar(metaState) already
 * reflects CapsLock's effect on letter case, and Android's own IME
 * framework handles NumLock for numpad-equipped external keyboards. This
 * file only needs to stay stable across keyboard connect/disconnect and
 * app (re)launch - not reimplement lock-key state Android already owns.
 */
object PhysicalKeyEvent {

    /**
     * True only for a genuine hardware/Bluetooth keyboard - not the
     * on-screen IME, which on some OEM keyboards (e.g. Samsung, some
     * Xiaomi builds) also reports SOURCE_KEYBOARD. InputDevice's own
     * isVirtual flag (true for the synthetic devices Android uses for
     * IME/assist injection, false for anything with a real backing
     * hardware device - Bluetooth or USB) is the reliable discriminator;
     * checking KeyEvent.getDevice() rather than getSource() avoids
     * misclassifying those OEM soft-keyboards as physical.
     */
    fun isFromPhysicalKeyboard(event: KeyEvent): Boolean {
        val device = event.device ?: return false
        if (device.isVirtual) return false
        return (device.sources and android.view.InputDevice.SOURCE_KEYBOARD) == android.view.InputDevice.SOURCE_KEYBOARD
    }

    /**
     * The exact bytes to write to the PTY for this key-down, or null if
     * this key isn't one this app maps at all (e.g. a media key, a
     * launcher-reserved key) - callers should let those fall through to
     * the platform's normal dispatch rather than swallowing them.
     * Returns "" (not null) for a modifier-only press (bare CTRL, ALT,
     * SHIFT, META with no other key yet) - those legitimately produce no
     * output on their own but the caller still needs to know this was a
     * key this app recognizes, not an unhandled one.
     *
     * [kittyFlags] is the active session's current kitty keyboard protocol
     * flags (TerminalEmulator.kittyKeyboardFlags - 0 means the protocol
     * isn't engaged). When non-zero, this bypasses the legacy named/
     * resolvedChar encoding below entirely and defers to
     * [kittySequenceFor] instead - see that function's own doc for why a
     * program that opted into the protocol needs a structurally different
     * encoding, not just tweaks to the legacy one.
     */
    fun sequenceFor(event: KeyEvent, kittyFlags: Int = 0): String? {
        val keyCode = event.keyCode
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        // Bare modifier press/release - normally nothing to send (see
        // this function's own doc for why "" rather than null), UNLESS
        // the active kitty flags include bit 3 ("report all keys" /
        // value 8), which per spec means bare Ctrl/Alt/Shift/Meta/
        // CapsLock/NumLock presses ARE reported as their own key events
        // too - checked here, before the general kittyFlags dispatch
        // below, since kittySequenceFor's own `when (keyCode)` has no
        // case for these at all otherwise and would fall through to the
        // plain-character-key path and mis-resolve them as garbage
        // characters via KeyCharacterMap.
        if (kittyFlags and 8 != 0) {
            kittySequenceFor(event, kittyFlags, isRelease = false)?.let { return it }
        }
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK -> return ""
            else -> {}
        }

        if (kittyFlags != 0) {
            kittySequenceFor(event, kittyFlags, isRelease = false)?.let { return it }
            // Falls through to legacy encoding below only if kittySequenceFor
            // declined (returned null) - e.g. a key it doesn't have a kitty
            // functional-key codepoint for. See that function's own doc.
        }

        // Named/control keys - same escape sequences VirtualKey's own
        // sendSequence table already uses, so a physical arrow key or
        // Ctrl+arrow behaves identically to tapping the on-screen bar.
        val named = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001B[Z" else "\t"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> "\u007F" // Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> "\u001B[3~" // Delete
            KeyEvent.KEYCODE_MOVE_HOME -> "\u001B[H"
            KeyEvent.KEYCODE_MOVE_END -> "\u001B[F"
            KeyEvent.KEYCODE_PAGE_UP -> "\u001B[5~"
            KeyEvent.KEYCODE_PAGE_DOWN -> "\u001B[6~"
            KeyEvent.KEYCODE_INSERT -> "\u001B[2~"
            KeyEvent.KEYCODE_DPAD_UP -> if (ctrl) "\u001B[1;5A" else "\u001B[A"
            KeyEvent.KEYCODE_DPAD_DOWN -> if (ctrl) "\u001B[1;5B" else "\u001B[B"
            KeyEvent.KEYCODE_DPAD_RIGHT -> if (ctrl) "\u001B[1;5C" else "\u001B[C"
            KeyEvent.KEYCODE_DPAD_LEFT -> if (ctrl) "\u001B[1;5D" else "\u001B[D"
            KeyEvent.KEYCODE_F1 -> "\u001BOP"
            KeyEvent.KEYCODE_F2 -> "\u001BOQ"
            KeyEvent.KEYCODE_F3 -> "\u001BOR"
            KeyEvent.KEYCODE_F4 -> "\u001BOS"
            KeyEvent.KEYCODE_F5 -> "\u001B[15~"
            KeyEvent.KEYCODE_F6 -> "\u001B[17~"
            KeyEvent.KEYCODE_F7 -> "\u001B[18~"
            KeyEvent.KEYCODE_F8 -> "\u001B[19~"
            KeyEvent.KEYCODE_F9 -> "\u001B[20~"
            KeyEvent.KEYCODE_F10 -> "\u001B[21~"
            KeyEvent.KEYCODE_F11 -> "\u001B[23~"
            KeyEvent.KEYCODE_F12 -> "\u001B[24~"
            else -> null
        }
        if (named != null) {
            return if (alt) "\u001B$named" else named
        }

        // Everything else (letters, digits, punctuation, numpad digits):
        // let the platform's own KeyCharacterMap resolve the actual
        // character for this keyCode+metaState - this is what already
        // accounts for CapsLock/Shift producing the right case, and
        // NumLock producing a digit vs. a nav function on numpad keys
        // (see this file's own top doc), rather than this app
        // reimplementing either. Ctrl+letter still needs its own
        // mapping afterward since terminals expect a control code
        // (0x01-0x1A), not the literal letter, when Ctrl is held.
        val unicodeChar = event.unicodeChar
        val resolvedChar = if (unicodeChar != 0) {
            unicodeChar.toChar()
        } else {
            // unicodeChar is 0 for some layouts/devices when metaState
            // includes CTRL (Android suppresses the char precisely
            // because Ctrl is meant to be a control combo, not a literal
            // character) - re-resolve with just the SHIFT bit so Ctrl+C
            // still maps from 'c'/'C', not from nothing.
            val plainMeta = event.metaState and (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_RIGHT_ON)
            val fallback = event.device?.keyCharacterMap
                ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
            val ch = fallback.get(keyCode, plainMeta)
            if (ch != 0) ch.toChar() else return null
        }

        return if (ctrl) {
            applyPhysicalCtrl(resolvedChar)
        } else if (alt) {
            "\u001B$resolvedChar"
        } else {
            resolvedChar.toString()
        }
    }

    /**
     * The bytes to write to the PTY for this key-UP, under the kitty
     * keyboard protocol's "report event types" enhancement (bit 1 / value
     * 2) - or null if that bit isn't set (legacy encoding never reports key-
     * up at all, matching dispatchKeyEvent's existing "swallow ACTION_UP"
     * behavior) or this key has no kitty encoding (see kittySequenceFor).
     * Deliberately NOT folded into [sequenceFor] itself - callers need to
     * tell "no sequence, legacy behavior" (null, swallow silently) apart
     * from "protocol wants this key-up reported", which sequenceFor's
     * existing null-means-unhandled contract can't express without a
     * second signal.
     */
    fun releaseSequenceFor(event: KeyEvent, kittyFlags: Int): String? {
        if (kittyFlags and 2 == 0) return null // report-event-types bit not set
        // Bare modifier RELEASE - only reported at all under bit 3
        // ("report all keys", same gate sequenceFor's own press-side
        // check above uses), never under bit 1 alone. Checked before the
        // blanket exclusion below so bit-3-enabled terminals still get
        // release events for these, mirroring sequenceFor's press-side
        // carve-out.
        val isBareModifier = when (event.keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK -> true
            else -> false
        }
        if (isBareModifier) {
            return if (kittyFlags and 8 != 0) kittySequenceFor(event, kittyFlags, isRelease = true) else null
        }
        return kittySequenceFor(event, kittyFlags, isRelease = true)
    }

    /**
     * Encodes one key event as a kitty keyboard protocol report, following
     * the "Legacy functional keys" table from the spec
     * (https://sw.kovidgoyal.net/kitty/keyboard-protocol/#legacy-functional-keys)
     * for keys that have a legacy-compatible form, and the PUA "Functional
     * key definitions" table for the handful that don't - or null to fall
     * back to the plain legacy encoding in [sequenceFor] (a plain
     * character key with no modifiers held round-trips fine as itself
     * either way; this only needs to intervene where the legacy encoding
     * this app already had is ambiguous, which is exactly the
     * disambiguation this protocol exists for).
     *
     * Two wire shapes are used, matching the spec exactly rather than
     * inventing a single one for everything:
     * - "CSI number ; modifiers u" for plain character keys and the small
     *   set of keys the spec has no legacy form for at all (Escape, Enter,
     *   Tab, Backspace - fixed C0/DEL codes per the spec's own
     *   "C0 controls" table, sent unconditionally so `reset` still works
     *   at a shell prompt if a program that set this mode crashes without
     *   clearing it, exactly as the spec requires).
     * - "CSI 1 ; modifiers <letter>" for arrows/Home/End/F1-F4, and
     *   "CSI <n> ; modifiers ~" for Insert/Delete/PageUp/PageDown/F5-F12 -
     *   the spec's own "legacy functional keys" forms, which every
     *   kitty-protocol-aware program is specified to still recognize
     *   (disambiguation only changes how modifiers are attached to these,
     *   not their base shape) - this is what real kitty terminals emit
     *   for these keys even with the protocol engaged.
     *
     * The modifier field itself (1 + bitmask; 1=shift, 2=alt, 4=ctrl,
     * 8=super) and the event-type sub-field (only under "report event
     * types", bit 1) are shared by both shapes and applied identically -
     * see the spec's own "Modifiers"/"Event types" sections.
     *
     * Bit 3 ("report all keys", value 8) and bit 4 (associated-text
     * reporting, value 16) are both implemented - see sequenceFor's own
     * bare-modifier check and this function's own bare-modifier `when`
     * cases for bit 3, and the plain-character-key call site near this
     * function's end for bit 4's associatedText parameter to [uForm].
     *
     * Deliberately does NOT implement: alternate-key reporting (bit 2,
     * shifted-key/base-layout-key sub-parameters) - this genuinely needs
     * information this app's key layer has no way to compute at all, not
     * just a missing plumbing path the way bits 3/4 were: the spec
     * defines the "shifted key" as what THIS SAME physical key would
     * produce under the OPPOSITE shift state at the SAME layout, and the
     * "base layout key" as what it produces under the user's base
     * (non-alternate) keyboard layout - both require querying
     * KeyCharacterMap a second/third time with a hypothetically-different
     * meta state and/or a different active layout than the one that
     * actually fired this event, which Android's KeyEvent/
     * KeyCharacterMap API has no supported way to do for a layout that
     * isn't the currently active one. A kitty-aware program that also
     * asked for bit 2 still gets a strictly-better-than-legacy report
     * from every other enhancement this function implements, just
     * without that one sub-parameter, which every kitty-protocol-aware
     * program is specified to tolerate (the enhancements are
     * independently negotiable, not all-or-nothing).
     */
    private fun kittySequenceFor(event: KeyEvent, kittyFlags: Int, isRelease: Boolean): String? {
        val keyCode = event.keyCode
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed
        val meta = event.isMetaPressed

        var modBits = 0
        if (shift) modBits = modBits or 1
        if (alt) modBits = modBits or 2
        if (ctrl) modBits = modBits or 4
        if (meta) modBits = modBits or 8
        val modValue = modBits + 1 // spec's own "1 + bitmask" convention

        val reportEventTypes = kittyFlags and 2 != 0
        // Associated-text reporting (bit 4, value 16) - see the plain-
        // character-key call site near this function's end for where
        // this actually gets used; checked once up here alongside the
        // other flag reads for consistency with reportEventTypes/
        // reportAllKeys.
        val reportAssociatedText = kittyFlags and 16 != 0
        val eventType = if (isRelease) 3 else 1 // repeat (2) isn't distinguishable from a fresh Android ACTION_DOWN here
        // The trailing ":<event-type>" sub-field, or "" for a plain press
        // under a terminal that hasn't asked for release/repeat reporting -
        // omitted entirely rather than sent as ":1", matching the spec's
        // own "press event type has value 1 and is the default if no event
        // type sub field is present" - CSI key-code;modifier (no colon) IS
        // a press event already, sending ":1" explicitly would just be
        // longer for no semantic difference, but some strict parsers only
        // tolerate the documented forms.
        val eventSuffix = if (reportEventTypes && eventType != 1) ":$eventType" else ""

        // "CSI 1 ; modifiers <letter>" form (arrows/Home/End/F1-F4) - the
        // "1" is omitted when modifiers are also absent, per the spec's own
        // note under the functional-key table.
        fun legacyLetterForm(letter: Char): String = buildString {
            append("\u001B[")
            if (modBits != 0 || eventSuffix.isNotEmpty()) {
                append("1;")
                append(modValue)
                append(eventSuffix)
            }
            append(letter)
        }

        // "CSI <n> ; modifiers ~" form (Insert/Delete/PageUp/PageDown/
        // F5-F12) - <n> is always present (unlike the letter form's "1"),
        // only the modifier field is conditional.
        fun legacyTildeForm(n: Int): String = buildString {
            append("\u001B[")
            append(n)
            if (modBits != 0 || eventSuffix.isNotEmpty()) {
                append(';')
                append(modValue)
                append(eventSuffix)
            }
            append('~')
        }

        // "CSI <code> ; modifiers[:<event-type>][;text-as-codepoints] u"
        // form - plain character keys, and the handful of PUA-only
        // functional keys this app maps (CapsLock/NumLock/ScrollLock are
        // excluded upstream in sequenceFor/releaseSequenceFor before this
        // is ever reached). [associatedText], when non-null (only ever
        // passed by the plain-character-key call site below, under bit 4 -
        // see this function's own updated doc), is appended as the
        // spec's third ';'-separated sub-parameter: the actual UTF-8 text
        // this keystroke produces, encoded as its Unicode codepoint(s)
        // written in decimal and joined with ':' (almost always exactly
        // one codepoint for a physical key, but the field is defined as a
        // list so a single key that legitimately produces a multi-
        // codepoint grapheme isn't truncated). Per spec this third field
        // requires the modifier field to be present even when modifiers
        // are otherwise absent (unlike the plain 2-field form, which
        // omits ";modifiers" entirely when there's nothing to report) -
        // so modValue is written unconditionally whenever text is
        // attached, using "1" (no bits set) rather than skipping the
        // field the way the modifier-less case above does.
        fun uForm(code: Int, associatedText: String? = null): String = buildString {
            append("\u001B[")
            append(code)
            if (modBits != 0 || eventSuffix.isNotEmpty() || associatedText != null) {
                append(';')
                append(modValue)
                append(eventSuffix)
            }
            if (associatedText != null) {
                append(';')
                append(associatedText.codePoints().toArray().joinToString(":"))
            }
            append('u')
        }

        when (keyCode) {
            // Enter/Tab/Backspace/Escape keep their fixed legacy bytes on
            // PRESS unconditionally (spec's own "C0 controls" table) so
            // `reset` still works at a shell prompt if a program that
            // engaged this mode crashes without popping back out of it -
            // genuinely ignores modifiers/event-type here on purpose,
            // matching every other kitty-protocol terminal's behavior for
            // these four keys specifically (this is the one place
            // disambiguation deliberately does NOT disambiguate). On
            // RELEASE, though, the spec is explicit these four do NOT get
            // release events at all unless "report all keys" (bit 3) is
            // also set - report-all-keys implies full disambiguation for
            // every key including these, which this function doesn't
            // implement (see this function's own doc on the bit-3 scope
            // cut), so a release is never sent for them: returning null
            // here means releaseSequenceFor sends nothing, rather than
            // duplicating the press byte and risking a shell seeing two
            // Enters (double command execution) for one keystroke.
            KeyEvent.KEYCODE_ESCAPE -> return if (isRelease) null else "\u001B"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return if (isRelease) null else "\r"
            KeyEvent.KEYCODE_TAB -> return if (isRelease) null else "\t"
            KeyEvent.KEYCODE_DEL -> return if (isRelease) null else "\u007F"

            KeyEvent.KEYCODE_DPAD_UP -> return legacyLetterForm('A')
            KeyEvent.KEYCODE_DPAD_DOWN -> return legacyLetterForm('B')
            KeyEvent.KEYCODE_DPAD_RIGHT -> return legacyLetterForm('C')
            KeyEvent.KEYCODE_DPAD_LEFT -> return legacyLetterForm('D')
            KeyEvent.KEYCODE_MOVE_HOME -> return legacyLetterForm('H')
            KeyEvent.KEYCODE_MOVE_END -> return legacyLetterForm('F')
            KeyEvent.KEYCODE_F1 -> return legacyLetterForm('P')
            KeyEvent.KEYCODE_F2 -> return legacyLetterForm('Q')
            KeyEvent.KEYCODE_F4 -> return legacyLetterForm('S')

            KeyEvent.KEYCODE_INSERT -> return legacyTildeForm(2)
            KeyEvent.KEYCODE_FORWARD_DEL -> return legacyTildeForm(3)
            KeyEvent.KEYCODE_PAGE_UP -> return legacyTildeForm(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> return legacyTildeForm(6)
            KeyEvent.KEYCODE_F3 -> return legacyTildeForm(13) // spec removed the CSI R form (clashes with CPR) - ~ only
            KeyEvent.KEYCODE_F5 -> return legacyTildeForm(15)
            KeyEvent.KEYCODE_F6 -> return legacyTildeForm(17)
            KeyEvent.KEYCODE_F7 -> return legacyTildeForm(18)
            KeyEvent.KEYCODE_F8 -> return legacyTildeForm(19)
            KeyEvent.KEYCODE_F9 -> return legacyTildeForm(20)
            KeyEvent.KEYCODE_F10 -> return legacyTildeForm(21)
            KeyEvent.KEYCODE_F11 -> return legacyTildeForm(23)
            KeyEvent.KEYCODE_F12 -> return legacyTildeForm(24)

            // Bare modifier keys - only ever reached from sequenceFor/
            // releaseSequenceFor when bit 3 ("report all keys") is set;
            // both call sites gate on that bit before routing these
            // keycodes in here at all, so no additional flag check is
            // needed at this level. PUA codepoints per the spec's own
            // "Functional key definitions" table
            // (https://sw.kovidgoyal.net/kitty/keyboard-protocol/#functional-key-definitions) -
            // these have no legacy-compatible form at all (unlike the
            // arrows/Home/End/etc. above), so they always go through
            // uForm's plain "CSI code;modifiers u" shape. Left/right
            // variants of the same physical modifier get distinct
            // codepoints (spec's own table splits them), which is also
            // why this can't just check event.isCtrlPressed/etc. the way
            // the rest of this app's modifier handling does - those
            // booleans can't tell LEFT from RIGHT, only this per-keycode
            // dispatch can.
            KeyEvent.KEYCODE_SHIFT_LEFT -> return uForm(57441)
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return uForm(57447)
            KeyEvent.KEYCODE_CTRL_LEFT -> return uForm(57442)
            KeyEvent.KEYCODE_CTRL_RIGHT -> return uForm(57448)
            KeyEvent.KEYCODE_ALT_LEFT -> return uForm(57443)
            KeyEvent.KEYCODE_ALT_RIGHT -> return uForm(57449)
            KeyEvent.KEYCODE_META_LEFT -> return uForm(57444)
            KeyEvent.KEYCODE_META_RIGHT -> return uForm(57450)
            KeyEvent.KEYCODE_CAPS_LOCK -> return uForm(57358)
            KeyEvent.KEYCODE_NUM_LOCK -> return uForm(57360)
            KeyEvent.KEYCODE_SCROLL_LOCK -> return uForm(57359)

            else -> {}
        }

        // Plain character key - resolve the same way the legacy path does
        // (see sequenceFor's own doc on why CTRL suppresses unicodeChar on
        // some layouts), but WITHOUT applying Ctrl's control-code folding:
        // kitty reports the base codepoint plus modifiers separately
        // rather than xterm's legacy "Ctrl+letter becomes byte 1-26"
        // collapsing, which is exactly the ambiguity (was that really
        // Ctrl+I, or a literal Tab byte?) disambiguation exists to
        // resolve. The spec requires the UN-shifted codepoint here (shift
        // is reported purely via the modifier bit, then applied on the
        // receiving end) - resolving with metaState 0 below (no SHIFT, no
        // CTRL) is exactly that: KeyCharacterMap's own base/unshifted
        // mapping for this physical key, unlike the legacy path's own
        // fallback further up which needs shift baked into the resolved
        // char since legacy has no separate modifier field for text keys.
        val fallback = event.device?.keyCharacterMap
            ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val unshifted = fallback.get(keyCode, 0)
        val codepoint = if (unshifted != 0) unshifted else return null
        // Associated-text reporting (bit 4, value 16) - the ACTUAL text
        // this keystroke produces (shift/altgr/dead-key composition and
        // all), as opposed to codepoint above which is deliberately the
        // UNshifted base key. Only meaningful on press (the spec defines
        // this sub-parameter for key-down text production; a release
        // carries no new text) and only when the resolved char is an
        // actual printable/composed character - event.unicodeChar with
        // the REAL (not zeroed) metaState is exactly what sequenceFor's
        // own legacy path already resolves for the non-kitty case, reused
        // here rather than re-deriving it a third way.
        val associatedText = if (!isRelease && reportAssociatedText) {
            val real = event.unicodeChar
            if (real != 0) real.toChar().toString() else null
        } else null
        return uForm(codepoint, associatedText)
    }

    /** Same mapping as MainActivity's own private applyCtrl (Ctrl+letter
     *  -> control code 1-26, a handful of punctuation keys to their own
     *  well-known codes) - duplicated rather than shared because that one
     *  is private to MainActivity.kt and this file intentionally has no
     *  dependency on it, only on the standard terminal Ctrl mapping both
     *  independently implement the same way. */
    private fun applyPhysicalCtrl(c: Char): String {
        val upper = c.uppercaseChar()
        return when {
            upper in 'A'..'Z' -> ((upper.code - 'A'.code + 1)).toChar().toString()
            c == '[' -> "\u001B"
            c == '\\' -> "\u001C"
            c == ']' -> "\u001D"
            c == '^' -> "\u001E"
            c == '_' -> "\u001F"
            c == '?' -> "\u007F"
            else -> c.toString()
        }
    }
}

/**
 * Snapshot of "which pane should a physical key press go to right now",
 * mirrored from Compose state each time it changes (see MainActivity's
 * LaunchedEffect that constructs this) into a plain field on the
 * Activity, since dispatchKeyEvent runs outside Compose and can't
 * collectAsState() anything itself. Read fresh on every key press, same
 * as VirtualKeyBar's onKeyPressed already reads splitPaneFocused/
 * focusedPaneRuntimeId at click time rather than caching them.
 */
data class PhysicalKeyboardRouting(
    val isMultiPane: Boolean = false,
    val broadcastAllPanes: Boolean = false,
    val splitPaneFocused: Boolean = false,
    val splitRuntimeId: String? = null,
    // Null in the default/no-op instance (e.g. before MainActivity's
    // LaunchedEffect first runs) - AppAction.execute treats a null
    // callback as "nothing to do" for SPLIT_SWAP_FOCUS rather than
    // crashing, same as every other target-less action there. Set from
    // MainActivity's own splitPaneFocused Compose state setter, mirrored
    // down for the same reason the rest of this class already is - see
    // this class's own top doc.
    val toggleSplitFocus: (() -> Unit)? = null
) {
    /** Same three-way routing VirtualKeyBar's onKeyPressed/onKeymapTriggered
     *  callbacks already do by hand at each of MainActivity's two
     *  VirtualKeyBar call sites - multi-pane goes through sendPaneInput
     *  (which reads MainUiState.focusedPaneRuntimeId itself, see its own
     *  doc), split-screen's secondary pane through sendInputTo when it's
     *  the focused one, everything else through the plain primary
     *  sendInput. */
    fun dispatch(viewModel: MainViewModel, sequence: String) {
        when {
            isMultiPane -> viewModel.sendPaneInput(sequence, broadcastAllPanes)
            splitPaneFocused && splitRuntimeId != null -> viewModel.sendInputTo(splitRuntimeId, sequence)
            else -> viewModel.sendInput(sequence)
        }
    }
}
