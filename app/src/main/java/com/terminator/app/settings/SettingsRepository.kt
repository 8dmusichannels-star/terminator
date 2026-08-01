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
