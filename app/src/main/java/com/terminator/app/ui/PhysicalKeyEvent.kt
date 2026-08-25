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
     */
    fun sequenceFor(event: KeyEvent): String? {
        val keyCode = event.keyCode
        val ctrl = event.isCtrlPressed
        val alt = event.isAltPressed
        val shift = event.isShiftPressed

        // Bare modifier press/release - nothing to send yet, but this IS
        // a key this app understands, so return "" rather than null (see
        // this function's own doc).
        when (keyCode) {
            KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_CTRL_RIGHT,
            KeyEvent.KEYCODE_ALT_LEFT, KeyEvent.KEYCODE_ALT_RIGHT,
            KeyEvent.KEYCODE_SHIFT_LEFT, KeyEvent.KEYCODE_SHIFT_RIGHT,
            KeyEvent.KEYCODE_META_LEFT, KeyEvent.KEYCODE_META_RIGHT,
            KeyEvent.KEYCODE_CAPS_LOCK, KeyEvent.KEYCODE_NUM_LOCK,
            KeyEvent.KEYCODE_SCROLL_LOCK -> return ""
            else -> {}
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
