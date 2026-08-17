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

    // Keyboard
    val SOFT_KEYBOARD = booleanPreferencesKey("soft_keyboard")
    val VIRTUAL_KEYS = booleanPreferencesKey("virtual_keys")
    val INPUT_MODE = stringPreferencesKey("input_mode")
    val SECCOMP_ENABLED = booleanPreferencesKey("seccomp_enabled")
    val KEYMAPS = stringPreferencesKey("keymaps_json")

    // Terminal compatibility - which TERM value child processes see.
    // "xterm-256color" (default) gives full color/feature support. It used
    // to need a device-provided terminfo entry to be fully recognized by
    // ncurses apps; the app now bundles its own compiled entry (see
    // TerminatorApp.extractBundledTerminfo / TerminalSession's TERMINFO env
    // var) so this works even on devices with no terminfo db at all.
    // "vt100"/"ansi" remain available since ncurses ships hardcoded
    // fallback definitions for those very common names too.
    val TERM_TYPE = stringPreferencesKey("term_type") // "xterm-256color" | "vt100" | "ansi"

    // Terminal > Behaviour
    // When true: `clear` (CSI 2J + cursor-home) also discards the entire
    // scrollback buffer, so there's truly nothing left above the screen.
    // When false (default): `clear` just moves existing content off-screen
    // the way a normal terminal does - you can still scroll up to see it.
    val CLEAR_ALWAYS_PTY = booleanPreferencesKey("clear_always_pty")
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
