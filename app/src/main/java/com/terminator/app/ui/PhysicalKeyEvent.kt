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

import com.terminator.emulator.TermProfile
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
    // Keys whose xterm modified form ("CSI 1;m X" / "CSI n;m ~") already encodes Alt.
    private val MODIFIABLE_KEYS = setOf(
        KeyEvent.KEYCODE_FORWARD_DEL, KeyEvent.KEYCODE_MOVE_HOME, KeyEvent.KEYCODE_MOVE_END,
        KeyEvent.KEYCODE_PAGE_UP, KeyEvent.KEYCODE_PAGE_DOWN, KeyEvent.KEYCODE_INSERT,
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_LEFT,
        KeyEvent.KEYCODE_F1, KeyEvent.KEYCODE_F2, KeyEvent.KEYCODE_F3, KeyEvent.KEYCODE_F4,
        KeyEvent.KEYCODE_F5, KeyEvent.KEYCODE_F6, KeyEvent.KEYCODE_F7, KeyEvent.KEYCODE_F8,
        KeyEvent.KEYCODE_F9, KeyEvent.KEYCODE_F10, KeyEvent.KEYCODE_F11, KeyEvent.KEYCODE_F12
    )

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
    fun sequenceFor(
        event: KeyEvent,
        kittyFlags: Int = 0,
        // Per-$TERM key encodings (Home/End/F1-F4/Backspace/modified keys), the
        // session's DECCKM state and its DECBKM override - see TermProfile.
        profile: TermProfile = TermProfile.GENERIC,
        applicationCursorKeys: Boolean = false,
        backarrowMode: Boolean? = null,
        // XTMODKEYS level the running program asked for (TerminalEmulator.modifyOtherKeysLevel).
        modifyOtherKeys: Int = 0,
        // DECKPAM ("ESC =" / DECSET 66): numpad keys send SS3 codes instead of characters.
        applicationKeypad: Boolean = false
    ): String? {
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

        // Modifier parameter of the xterm "modified key" forms: 1 + shift(1) +
        // alt(2) + ctrl(4). Applies only to profiles whose terminfo has
        // modified keys (TermProfile.modifiedKeys).
        val mod = (if (shift) 1 else 0) + (if (alt) 2 else 0) + (if (ctrl) 4 else 0)
        val modParam = mod + 1
        val useMods = profile.modifiedKeys && mod != 0

        // modifyOtherKeys (CSI > 4 ; Pv m): Enter/Tab/Backspace/Escape combined
        // with Ctrl (or Shift, except Shift+Tab which keeps "CSI Z") have no
        // legacy encoding, so a program that asked for it gets
        // "CSI 27 ; mods ; code ~" instead.
        if (modifyOtherKeys > 0 && (ctrl || (shift && keyCode != KeyEvent.KEYCODE_TAB))) {
            val mokCode = when (keyCode) {
                KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> 13
                KeyEvent.KEYCODE_TAB -> 9
                KeyEvent.KEYCODE_DEL -> 127
                KeyEvent.KEYCODE_ESCAPE -> 27
                else -> 0
            }
            if (mokCode != 0) return "\u001B[27;$modParam;$mokCode~"
        }

        fun letterKey(letter: Char, plain: String): String =
            if (useMods) "\u001B[1;$modParam$letter" else plain
        fun tildeKey(n: Int): String =
            if (useMods) "\u001B[$n;$modParam~" else "\u001B[$n~"

        // Application keypad (DECKPAM): numpad keys become "ESC O <letter>".
        if (applicationKeypad && !ctrl) {
            val k = when (keyCode) {
                KeyEvent.KEYCODE_NUMPAD_0 -> 'p'
                KeyEvent.KEYCODE_NUMPAD_1 -> 'q'
                KeyEvent.KEYCODE_NUMPAD_2 -> 'r'
                KeyEvent.KEYCODE_NUMPAD_3 -> 's'
                KeyEvent.KEYCODE_NUMPAD_4 -> 't'
                KeyEvent.KEYCODE_NUMPAD_5 -> 'u'
                KeyEvent.KEYCODE_NUMPAD_6 -> 'v'
                KeyEvent.KEYCODE_NUMPAD_7 -> 'w'
                KeyEvent.KEYCODE_NUMPAD_8 -> 'x'
                KeyEvent.KEYCODE_NUMPAD_9 -> 'y'
                KeyEvent.KEYCODE_NUMPAD_MULTIPLY -> 'j'
                KeyEvent.KEYCODE_NUMPAD_ADD -> 'k'
                KeyEvent.KEYCODE_NUMPAD_COMMA -> 'l'
                KeyEvent.KEYCODE_NUMPAD_SUBTRACT -> 'm'
                KeyEvent.KEYCODE_NUMPAD_DOT -> 'n'
                KeyEvent.KEYCODE_NUMPAD_DIVIDE -> 'o'
                KeyEvent.KEYCODE_NUMPAD_ENTER -> 'M'
                KeyEvent.KEYCODE_NUMPAD_EQUALS -> 'X'
                else -> null
            }
            if (k != null) return "\u001BO$k"
        }

        // Named/control keys. Arrows/Home/End/F1-F4 follow the profile and
        // DECCKM, so a physical key and the on-screen key bar agree.
        val named = when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> "\u001B"
            KeyEvent.KEYCODE_TAB -> if (shift) "\u001B[Z" else "\t"
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> "\r"
            KeyEvent.KEYCODE_DEL -> profile.backspaceFor(backarrowMode) // Backspace
            KeyEvent.KEYCODE_FORWARD_DEL -> tildeKey(3) // Delete
            KeyEvent.KEYCODE_MOVE_HOME -> letterKey('H', profile.home(applicationCursorKeys))
            KeyEvent.KEYCODE_MOVE_END -> letterKey('F', profile.end(applicationCursorKeys))
            KeyEvent.KEYCODE_PAGE_UP -> tildeKey(5)
            KeyEvent.KEYCODE_PAGE_DOWN -> tildeKey(6)
            KeyEvent.KEYCODE_INSERT -> tildeKey(2)
            KeyEvent.KEYCODE_DPAD_UP -> letterKey('A', profile.cursorKey('A', applicationCursorKeys))
            KeyEvent.KEYCODE_DPAD_DOWN -> letterKey('B', profile.cursorKey('B', applicationCursorKeys))
            KeyEvent.KEYCODE_DPAD_RIGHT -> letterKey('C', profile.cursorKey('C', applicationCursorKeys))
            KeyEvent.KEYCODE_DPAD_LEFT -> letterKey('D', profile.cursorKey('D', applicationCursorKeys))
            KeyEvent.KEYCODE_F1 -> letterKey('P', profile.functionKey1to4(1))
            KeyEvent.KEYCODE_F2 -> letterKey('Q', profile.functionKey1to4(2))
            KeyEvent.KEYCODE_F3 -> letterKey('R', profile.functionKey1to4(3))
            KeyEvent.KEYCODE_F4 -> letterKey('S', profile.functionKey1to4(4))
            KeyEvent.KEYCODE_F5 -> tildeKey(15)
            KeyEvent.KEYCODE_F6 -> tildeKey(17)
            KeyEvent.KEYCODE_F7 -> tildeKey(18)
            KeyEvent.KEYCODE_F8 -> tildeKey(19)
            KeyEvent.KEYCODE_F9 -> tildeKey(20)
            KeyEvent.KEYCODE_F10 -> tildeKey(21)
            KeyEvent.KEYCODE_F11 -> tildeKey(23)
            KeyEvent.KEYCODE_F12 -> tildeKey(24)
            else -> null
        }
        if (named != null) {
            // The modified forms already carry Alt in their parameter; the
            // other keys (and profiles without modified keys) keep the
            // classic ESC prefix for Alt.
            val carriesAlt = useMods && keyCode in MODIFIABLE_KEYS
            return if (alt && !carriesAlt) "\u001B$named" else named
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

        // modifyOtherKeys for character keys: level 2 reports every Ctrl/Alt
        // combination as "CSI 27 ; mods ; code ~", level 1 only the Ctrl ones
        // that would otherwise be ambiguous or impossible (Ctrl+Shift+letter,
        // Ctrl+digit, ...). The code is the key's character with Shift applied
        // but Ctrl/Alt ignored.
        if (modifyOtherKeys > 0 && (ctrl || alt)) {
            val legacyCtrlExists = resolvedChar.uppercaseChar() in 'A'..'Z' || resolvedChar in "[\\]^_?"
            val report = if (modifyOtherKeys >= 2) true else (ctrl && (shift || !legacyCtrlExists))
            if (report) {
                val shiftMeta = if (shift) KeyEvent.META_SHIFT_ON else 0
                val baseMap = event.device?.keyCharacterMap ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
                val code = baseMap.get(keyCode, shiftMeta).takeIf { it != 0 } ?: resolvedChar.code
                return "\u001B[27;$modParam;$code~"
            }
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
        // Android marks auto-repeat as ACTION_DOWN with repeatCount > 0.
        val eventType = if (isRelease) 3 else if (event.repeatCount > 0) 2 else 1
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

        // Progressive-enhancement flags (kitty keyboard protocol):
        //  1 disambiguate - Esc and any Ctrl/Alt/Meta-modified key use CSI u;
        //    plain and Shift-only text, and unmodified Enter/Tab/Backspace, stay
        //    legacy; modified Enter/Tab/Backspace and functional keys use their
        //    modifier forms.
        //  2 report event types (repeat / release), 8 report all keys as CSI u.
        // Returning null means "leave this key to the legacy encoding", which
        // is also what keeps plain typing, DECCKM and the TERM profile working.
        val disambiguate = kittyFlags and 1 != 0
        val reportAll = kittyFlags and 8 != 0
        val nonShiftMods = modBits and 1.inv() != 0 // Ctrl / Alt / Meta
        val eventReported = reportEventTypes && eventType != 1 // a repeat or release

        fun functional(form: String): String? =
            if (reportAll || modBits != 0 || eventReported) form else null

        fun enterTabBackspace(code: Int): String? = when {
            reportAll -> uForm(code)
            disambiguate && modBits != 0 && !isRelease -> uForm(code)
            else -> null
        }

        when (keyCode) {
            KeyEvent.KEYCODE_ESCAPE -> return if (reportAll || disambiguate) uForm(27) else null
            KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> return enterTabBackspace(13)
            KeyEvent.KEYCODE_TAB -> return enterTabBackspace(9)
            KeyEvent.KEYCODE_DEL -> return enterTabBackspace(127)

            KeyEvent.KEYCODE_DPAD_UP -> return functional(legacyLetterForm('A'))
            KeyEvent.KEYCODE_DPAD_DOWN -> return functional(legacyLetterForm('B'))
            KeyEvent.KEYCODE_DPAD_RIGHT -> return functional(legacyLetterForm('C'))
            KeyEvent.KEYCODE_DPAD_LEFT -> return functional(legacyLetterForm('D'))
            KeyEvent.KEYCODE_MOVE_HOME -> return functional(legacyLetterForm('H'))
            KeyEvent.KEYCODE_MOVE_END -> return functional(legacyLetterForm('F'))
            KeyEvent.KEYCODE_F1 -> return functional(legacyLetterForm('P'))
            KeyEvent.KEYCODE_F2 -> return functional(legacyLetterForm('Q'))
            KeyEvent.KEYCODE_F4 -> return functional(legacyLetterForm('S'))

            KeyEvent.KEYCODE_INSERT -> return functional(legacyTildeForm(2))
            KeyEvent.KEYCODE_FORWARD_DEL -> return functional(legacyTildeForm(3))
            KeyEvent.KEYCODE_PAGE_UP -> return functional(legacyTildeForm(5))
            KeyEvent.KEYCODE_PAGE_DOWN -> return functional(legacyTildeForm(6))
            KeyEvent.KEYCODE_F3 -> return functional(legacyTildeForm(13)) // spec removed the CSI R form (clashes with CPR)
            KeyEvent.KEYCODE_F5 -> return functional(legacyTildeForm(15))
            KeyEvent.KEYCODE_F6 -> return functional(legacyTildeForm(17))
            KeyEvent.KEYCODE_F7 -> return functional(legacyTildeForm(18))
            KeyEvent.KEYCODE_F8 -> return functional(legacyTildeForm(19))
            KeyEvent.KEYCODE_F9 -> return functional(legacyTildeForm(20))
            KeyEvent.KEYCODE_F10 -> return functional(legacyTildeForm(21))
            KeyEvent.KEYCODE_F11 -> return functional(legacyTildeForm(23))
            KeyEvent.KEYCODE_F12 -> return functional(legacyTildeForm(24))

            // Bare modifier / lock keys are only ever reported under "report all keys".
            KeyEvent.KEYCODE_SHIFT_LEFT -> return if (reportAll) uForm(57441) else null
            KeyEvent.KEYCODE_SHIFT_RIGHT -> return if (reportAll) uForm(57447) else null
            KeyEvent.KEYCODE_CTRL_LEFT -> return if (reportAll) uForm(57442) else null
            KeyEvent.KEYCODE_CTRL_RIGHT -> return if (reportAll) uForm(57448) else null
            KeyEvent.KEYCODE_ALT_LEFT -> return if (reportAll) uForm(57443) else null
            KeyEvent.KEYCODE_ALT_RIGHT -> return if (reportAll) uForm(57449) else null
            KeyEvent.KEYCODE_META_LEFT -> return if (reportAll) uForm(57444) else null
            KeyEvent.KEYCODE_META_RIGHT -> return if (reportAll) uForm(57450) else null
            KeyEvent.KEYCODE_CAPS_LOCK -> return if (reportAll) uForm(57358) else null
            KeyEvent.KEYCODE_NUM_LOCK -> return if (reportAll) uForm(57360) else null
            KeyEvent.KEYCODE_SCROLL_LOCK -> return if (reportAll) uForm(57359) else null

            else -> {}
        }

        // Text keys: the key code is the unshifted character. Plain and
        // Shift-only typing stays legacy (the user types text) unless "report
        // all keys" is on; Ctrl/Alt/Meta combinations go out as CSI u under
        // "disambiguate". Release events exist only under "report all keys".
        val send = when {
            reportAll -> true
            disambiguate && nonShiftMods && !isRelease -> true
            else -> false
        }
        if (!send) return null
        val fallback = event.device?.keyCharacterMap
            ?: KeyCharacterMap.load(KeyCharacterMap.VIRTUAL_KEYBOARD)
        val unshifted = fallback.get(keyCode, 0)
        val codepoint = if (unshifted != 0) unshifted else return null
        val associatedText = if (!isRelease && reportAssociatedText && !nonShiftMods) {
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
    val toggleSplitFocus: (() -> Unit)? = null,
    // Physical-keyboard font-size zoom (Ctrl +/-/0), mirrored down from
    // MainActivity's own stepZoom/zoomTargetId the same way toggleSplitFocus
    // is - see MainActivity's LaunchedEffect that constructs this. Null in
    // the default/no-op instance, same "nothing to do yet" contract as
    // toggleSplitFocus above; AppAction.execute treats a null callback as a
    // no-op for ZOOM_IN/ZOOM_OUT/ZOOM_RESET.
    val zoomIn: (() -> Unit)? = null,
    val zoomOut: (() -> Unit)? = null,
    val zoomReset: (() -> Unit)? = null
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
