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
    val BLUR_ALPHA = floatPreferencesKey("blur_alpha")
    val WALLPAPER_URI = stringPreferencesKey("wallpaper_uri")

    // Theme
    val AMOLED_BLACK = booleanPreferencesKey("amoled_black")
    val COLOR_SCHEME_MODE = stringPreferencesKey("color_scheme_mode")
    val CUSTOM_FG = intPreferencesKey("custom_fg")
    val CUSTOM_BG = intPreferencesKey("custom_bg")

    // Sound
    val BELL_ENABLED = booleanPreferencesKey("bell_enabled")
    val USE_CUSTOM_SOUND = booleanPreferencesKey("use_custom_sound")
    val CUSTOM_SOUND_URI = stringPreferencesKey("custom_sound_uri")

    // Display
    val SHOW_STATUSBAR = booleanPreferencesKey("show_statusbar")
    val SHOW_TITLEBAR = booleanPreferencesKey("show_titlebar")
    val HORIZONTAL_MODE = booleanPreferencesKey("horizontal_mode")

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
