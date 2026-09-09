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

package com.terminator.app.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "terminator_settings")

/**
 * All persisted keys for every settings screen. Kept in one place so every
 * screen reads/writes through the same [SettingsRepository] instead of
 * holding throwaway Compose state.
 */
object SettingsKeys {
    // Appearance
    val FONT_FAMILY = stringPreferencesKey("font_family") // "Monospace" | "Sans Mono" | "Serif Mono" | "Custom"
    val FONT_URI = stringPreferencesKey("font_uri") // used when FONT_FAMILY == "Custom"
    val TEXT_SIZE = floatPreferencesKey("text_size")
    val COLUMNS = floatPreferencesKey("columns")
    val BLUR_ALPHA = floatPreferencesKey("blur_alpha") // legacy key, kept for migration - see BACKGROUND_ALPHA
    // Appearance > wallpaper background: alpha and blur are now two
    // independent sliders instead of one value driving both. BACKGROUND_ALPHA
    // controls how transparent the wallpaper/terminal background is;
    // BACKGROUND_BLUR controls how blurred the wallpaper image itself is
    // (0 = sharp, higher = blurrier). Both default to BLUR_ALPHA's old
    // default (0.3) / no blur (0f) respectively so existing installs don't
    // visually jump on upgrade.
    val BACKGROUND_ALPHA = floatPreferencesKey("background_alpha")
    val BACKGROUND_BLUR = floatPreferencesKey("background_blur")
    val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")

    // Theme
    val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
    val COLOR_SCHEME_MODE = stringPreferencesKey("color_scheme_mode")
    val CUSTOM_FG = intPreferencesKey("custom_fg")
    val CUSTOM_BG = intPreferencesKey("custom_bg")

    // Theme > Material color override. Off (default): Material only fills
    // in fg/bg for colorless output, same as before - any ANSI palette the
    // running program sets (or CUSTOM_FG/CUSTOM_BG/Nord/imported themes)
    // is left alone. On: Material's dynamic palette is also mapped onto
    // the terminal's 16 ANSI slots, overriding whatever palette would
    // otherwise apply - see TerminalPalette.materialOverride().
    val MATERIAL_COLOR_OVERRIDE = booleanPreferencesKey("material_color_override")

    // Theme > separate error/status colors, independent of whichever
    // COLOR_SCHEME_MODE/MATERIAL_COLOR_OVERRIDE is active. When enabled,
    // ANSI red (1/9, conventionally "error") and yellow (3/11,
    // conventionally "warning/status") are pinned to these two RGB values
    // regardless of what the rest of the palette resolves to.
    val STATUS_COLORS_ENABLED = booleanPreferencesKey("status_colors_enabled")
    val STATUS_ERROR_COLOR = intPreferencesKey("status_error_color")
    val STATUS_WARNING_COLOR = intPreferencesKey("status_warning_color")

    // Theme > "Custom Palette" mode - a real 16-slot termcolor palette
    // (distinct from CUSTOM_FG/CUSTOM_BG above, which only ever override
    // the default text/background pair and leave the 16 ANSI accent colors
    // fixed). Stored as a JSON array of 16 ARGB ints, index 0-15 in
    // standard ANSI order (black, red, green, yellow, blue, magenta, cyan,
    // white, then the bright variants) - see PaletteThemes.kt for parsing/
    // encoding and the bundled Solarized/Gruvbox/Dracula presets.
    val CUSTOM_PALETTE_COLORS = stringPreferencesKey("custom_palette_colors")
    val CUSTOM_PALETTE_FG = intPreferencesKey("custom_palette_fg")
    val CUSTOM_PALETTE_BG = intPreferencesKey("custom_palette_bg")

    // Theme > "Import theme file" mode's own 16-slot palette storage -
    // deliberately separate from CUSTOM_PALETTE_COLORS/FG/BG above so
    // switching between "Custom Palette" and "Import theme file" doesn't
    // let one mode's data leak into (or get silently overwritten by) the
    // other. Same JSON-array encoding as CUSTOM_PALETTE_COLORS (see
    // PaletteThemes.kt); populated only when an imported file actually
    // defines the full ANSI palette, not just foreground/background - see
    // ThemeSettingsScreen's parseThemeFile().
    val IMPORTED_PALETTE_COLORS = stringPreferencesKey("imported_palette_colors")
    val IMPORTED_PALETTE_FG = intPreferencesKey("imported_palette_fg")
    val IMPORTED_PALETTE_BG = intPreferencesKey("imported_palette_bg")

    // Appearance > pinch-to-zoom on/off. On by default (existing
    // behaviour). Off disables the two-finger pinch gesture entirely -
    // font size then only changes via the Text Size slider.
    val ZOOM_ENABLED = booleanPreferencesKey("zoom_enabled")

    // Sound
    val BELL_ENABLED = booleanPreferencesKey("bell_enabled")
    val USE_CUSTOM_SOUND = booleanPreferencesKey("use_custom_sound")
    val CUSTOM_SOUND_URI = stringPreferencesKey("custom_sound_uri")

    // Display
    val SHOW_STATUSBAR = booleanPreferencesKey("show_statusbar")
    val SHOW_TITLEBAR = booleanPreferencesKey("show_titlebar")
    val HORIZONTAL_MODE = booleanPreferencesKey("horizontal_mode")
    // Display > "Show runner toolbar save button". Controls whether the
    // per-session Save/export icon is rendered on each running-session row
    // in SessionDrawer (see SessionDrawer.kt's onSaveRunningSession doc).
    // Default true so existing installs see the Save icon appear the same
    // way a new feature normally would. The same Save action remains
    // reachable via the long-press selection toolbar's own save icon
    // regardless, unaffected by this.
    val SHOW_RUNNER_TOOLBAR_SAVE = booleanPreferencesKey("show_runner_toolbar_save")

    // Display > "Split screen visibility". Purely a UI-chrome toggle -
    // controls whether the standalone split-screen button (in the
    // selection bar's More popup and anywhere else a dedicated split
    // button is offered) is shown at all. Independent of whether a split
    // is actually open right now (MainUiState.splitRuntimeId): turning
    // this off just hides the button that would let you open/close one,
    // it doesn't close an already-open split. Default true, same
    // "existing installs see it exactly as before" reasoning as the
    // runner toolbar save toggle above.
    val SPLIT_SCREEN_VISIBLE = booleanPreferencesKey("split_screen_visible")

    // Display > Multi-pane > "Broadcast to all panes". Off (default):
    // typed input goes only to whichever pane is currently focused (tap a
    // pane to focus it) - see MainViewModel.sendPaneInput's doc. On: every
    // keystroke is mirrored to every visible pane at once, same idea as the
    // classic split's own broadcastInput toggle but covering the whole
    // pane group instead of just one partner.
    val BROADCAST_ALL_PANES = booleanPreferencesKey("broadcast_all_panes")

    // Controls whether the "All clear session" button (kills every running
    // session at once, see MainViewModel.clearAllSessions) is shown in the
    // session area at all. Off by default - it's a destructive one-tap
    // action, so it should be an opt-in reveal rather than always visible.
    val SHOW_CLEAR_ALL_SESSIONS_BUTTON = booleanPreferencesKey("show_clear_all_sessions_button")

    // Keyboard
    val SOFT_KEYBOARD = booleanPreferencesKey("soft_keyboard")
    val VIRTUAL_KEYS = booleanPreferencesKey("virtual_keys")
    // Whether VirtualKeyBar's own keymap row (the user's saved keyboard
    // shortcuts, shown as tappable chips inside the bar) is enabled.
    // Previously there was no separate flag for this at all - the keymap
    // row's visibility was entirely tied to VIRTUAL_KEYS, so turning the
    // virtual key bar off also silently killed keymap shortcuts, and there
    // was no way to have one without the other. Independent of VIRTUAL_KEYS
    // and defaults to true so existing keymap users see no behavior change.
    val KEYMAPPER_ENABLED = booleanPreferencesKey("keymapper_enabled")
    // Local, client-side override for physical-keyboard key-repeat, fully
    // separate from DECARM's own protocol state (TerminalEmulator's
    // autoRepeatMode, toggled by a program via "CSI ?8 h/l"). On
    // (default): repeat KeyEvents from a real hardware/Bluetooth keyboard
    // (event.repeatCount > 0, see PhysicalKeyEvent.isFromPhysicalKeyboard)
    // are written to the PTY same as any other key, matching every other
    // real terminal's default. Off: MainActivity.dispatchKeyEvent drops
    // those repeat events before they ever reach the PTY - a purely local
    // input-filtering decision that always wins over whatever a running
    // program has set DECARM to, since it's about whether this app
    // chooses to forward an event it already received, not about
    // reporting the protocol's own state (DECRQM still truthfully answers
    // with the program's own CSI ?8h/l regardless of this setting).
    val PHYSICAL_KEY_REPEAT_ENABLED = booleanPreferencesKey("physical_key_repeat_enabled")
    val INPUT_MODE = stringPreferencesKey("input_mode")
    val SECCOMP_ENABLED = booleanPreferencesKey("seccomp_enabled")
    val KEYMAPS = stringPreferencesKey("keymaps_json")
    // Physical-keyboard shortcuts bound to app-level actions (new split,
    // clear all sessions, kill focused pane, etc.) rather than to terminal
    // byte sequences - see AppShortcuts.kt. Fully separate table from
    // KEYMAPS above: KEYMAPS are user-named combos of VirtualKey taps sent
    // to the PTY, these are physical KeyEvent combos routed to a
    // MainViewModel action instead. Independent JSON array, own key, so
    // clearing/importing one never touches the other.
    val APP_SHORTCUTS = stringPreferencesKey("app_shortcuts_json")

    // Terminal compatibility - which TERM value child processes see.
    // "NONE" (default) means don't inject a TERM env var at all - see
    // TerminalSession.buildEnvironment's own doc for why that's different
    // from literally setting "TERM=NONE". "xterm-256color" gives full
    // color/feature support; the app bundles its own compiled terminfo
    // entry for it (see TerminatorApp.extractBundledTerminfo /
    // TerminalSession's TERMINFO env var) so it works even on devices with
    // no terminfo db of their own. See KeyboardSettingsScreen's
    // TERM_TYPE_OPTIONS for the full picker list this key's value comes
    // from - NONE, xterm, xterm-color, xterm-256color, screen,
    // screen-256color, tmux-256color, xterm-kitty, tmux, vt220, vt100, ANSI.
    val TERM_TYPE = stringPreferencesKey("term_type")

    // Terminal > Behaviour
    // When true: `clear` (CSI 2J + cursor-home) also discards the entire
    // scrollback buffer, so there's truly nothing left above the screen.
    // When false (default): `clear` just moves existing content off-screen
    // the way a normal terminal does - you can still scroll up to see it.
    val CLEAR_ALWAYS_PTY = booleanPreferencesKey("clear_always_pty")

    // Terminal > Behaviour > "Allow custom app schemes". Tapping an OSC 8
    // hyperlink hands its URI to ACTION_VIEW - but that URI is untrusted
    // program output (whatever the running command, or a remote ssh host,
    // chose to print), not something the user typed. Off (default): only
    // TerminalView's DEFAULT_ALLOWED_HYPERLINK_SCHEMES (http/https/mailto/
    // tel/sms/geo/ftp/ssh/smb/file/ipfs/ipns) are actually opened; anything
    // else (a custom app deep link like "spotify:", "market:", or an
    // "intent:" payload) is silently ignored, same as a dead link. On:
    // every scheme is allowed through, restoring the fully unrestricted
    // behavior this had before scheme filtering existed.
    val ALLOW_CUSTOM_HYPERLINK_SCHEMES = booleanPreferencesKey("allow_custom_hyperlink_schemes")

    // Settings > Terminal Behaviour > Allow OSC 52 clipboard reads. Off
    // (default): a program sending "OSC 52 ; c ; ?" to read the system
    // clipboard back through the pty is silently ignored, same as today -
    // see TerminalEmulator.Listener.onClipboardGet's own doc for why this
    // is a real info-leak surface (any command in this shell, a remote ssh
    // session, or another tmux pane could otherwise exfiltrate clipboard
    // contents). On: the actual system clipboard is read, base64-encoded,
    // and written back as "OSC 52 ; c ; <base64> ST" - same explicit
    // opt-in posture as xterm's own allowWindowOps.
    val ALLOW_OSC52_CLIPBOARD_READ = booleanPreferencesKey("allow_osc52_clipboard_read")

    // Settings > Notifications > OSC 9/777. Off (default): a program's
    // "OSC 9 ; <msg> ST" / "OSC 777 ; notify ; <title> ; <body> ST" request
    // is parsed by the emulator (see TerminalEmulator.Listener.
    // onNotification's own doc) but never actually surfaced as a real
    // system notification - same silent-no-op-until-opted-in posture as
    // ALLOW_OSC52_CLIPBOARD_READ just above, since this is also "untrusted
    // program output gets to trigger a real OS-level side effect" territory
    // (a remote ssh session or a compromised/malicious script in this
    // shell could otherwise spam notifications). On: MainViewModel posts a
    // real notification via NotificationCompat for every request that
    // arrives.
    val OSC_NOTIFICATIONS_ENABLED = booleanPreferencesKey("osc_notifications_enabled")
}

/**
 * Thin wrapper around a single Preferences DataStore so every setting
 * survives process death / app restarts. Mirrors the pattern already used
 * by SessionRepository.
 */
class SettingsRepository(private val context: Context) {

    fun <T> flow(key: Preferences.Key<T>, default: T): Flow<T> =
        context.settingsDataStore.data.map { prefs -> prefs[key] ?: default }

    suspend fun <T> set(key: Preferences.Key<T>, value: T) {
        context.settingsDataStore.edit { prefs -> prefs[key] = value }
    }
}
