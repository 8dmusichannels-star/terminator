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

package com.terminator.app.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * Material theme toggle, optional AMOLED Black, and terminal color scheme
 * selection: Material-derived, custom RGB picker, or an imported theme
 * (e.g. Nord-style) via a CSS/bash-based theme file.
 *
 * Whatever is picked here is what the terminal screen actually renders with
 * (see MainActivity, which reads COLOR_SCHEME_MODE / CUSTOM_FG / CUSTOM_BG).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ThemeSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val amoledBlack by repo.flow(SettingsKeys.AMOLED_BLACK, false).collectAsState(initial = false)
    val colorSchemeMode by repo.flow(SettingsKeys.COLOR_SCHEME_MODE, "Material")
        .collectAsState(initial = "Material")
    val customFg by repo.flow(SettingsKeys.CUSTOM_FG, DEFAULT_CUSTOM_FG).collectAsState(initial = DEFAULT_CUSTOM_FG)
    val customBg by repo.flow(SettingsKeys.CUSTOM_BG, DEFAULT_CUSTOM_BG).collectAsState(initial = DEFAULT_CUSTOM_BG)
    var importError by remember { mutableStateOf<String?>(null) }
    var importedFileName by remember { mutableStateOf<String?>(null) }

    // "Custom Palette" mode - a real 16-slot termcolor palette, distinct
    // from the plain CUSTOM_FG/CUSTOM_BG pair above. See PaletteThemes.kt.
    val customPaletteColorsJson by repo.flow(SettingsKeys.CUSTOM_PALETTE_COLORS, "")
        .collectAsState(initial = "")
    val customPaletteFg by repo.flow(SettingsKeys.CUSTOM_PALETTE_FG, PalettePresets.default.foreground)
        .collectAsState(initial = PalettePresets.default.foreground)
    val customPaletteBg by repo.flow(SettingsKeys.CUSTOM_PALETTE_BG, PalettePresets.default.background)
        .collectAsState(initial = PalettePresets.default.background)
    val customPaletteColors = remember(customPaletteColorsJson) { decodePaletteColors(customPaletteColorsJson) }

    // "Material color override" - see TerminalPalette.materialOverride()'s
    // doc. Only meaningful while colorSchemeMode == "Material"; shown
    // regardless so the setting isn't silently lost if the user switches
    // modes and back.
    val materialColorOverride by repo.flow(SettingsKeys.MATERIAL_COLOR_OVERRIDE, false)
        .collectAsState(initial = false)

    // Separate error/status colors - independent of colorSchemeMode /
    // materialColorOverride, see TerminalPalette.withStatusColors()'s doc.
    val statusColorsEnabled by repo.flow(SettingsKeys.STATUS_COLORS_ENABLED, false)
        .collectAsState(initial = false)
    val statusErrorColor by repo.flow(SettingsKeys.STATUS_ERROR_COLOR, DEFAULT_STATUS_ERROR)
        .collectAsState(initial = DEFAULT_STATUS_ERROR)
    val statusWarningColor by repo.flow(SettingsKeys.STATUS_WARNING_COLOR, DEFAULT_STATUS_WARNING)
        .collectAsState(initial = DEFAULT_STATUS_WARNING)

    // Accepts either:
    //  - simple "key=value" lines, e.g. foreground=#E6E6E6 / background=#000000
    //  - or a small JSON object, e.g. {"foreground":"#E6E6E6","background":"#000000"}
    // (also accepts fg/bg as short aliases). Whatever is parsed is written
    // straight into CUSTOM_FG/CUSTOM_BG, which MainActivity now actually
    // reads for the "Import theme file" mode.
    val themeFilePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val text = runCatching {
            context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() }
        }.getOrNull()
        if (text.isNullOrBlank()) {
            importError = "Could not read that file"
            return@rememberLauncherForActivityResult
        }
        val parsed = parseThemeFile(text)
        if (parsed == null) {
            importError = "Unrecognized theme file format"
        } else {
            val (fg, bg) = parsed
            scope.launch {
                repo.set(SettingsKeys.CUSTOM_FG, fg)
                repo.set(SettingsKeys.CUSTOM_BG, bg)
                repo.set(SettingsKeys.COLOR_SCHEME_MODE, "Import theme file")
            }
            importError = null
            importedFileName = uri.lastPathSegment ?: "theme file"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Theme") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        // Custom Palette mode alone renders 16 ANSI color rows plus presets
        // and fg/bg fields - taller than the screen on most phones. The
        // Column here previously had no scroll modifier at all, so once
        // Custom Palette was selected the bottom of the list (Green and
        // everything after it) was simply unreachable - fillMaxSize() just
        // clipped it instead of allowing a scroll.
        Column(
            modifier = Modifier
                .padding(padding)
                .padding(16.dp)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
        ) {
            SwitchRow("AMOLED Black", amoledBlack) {
                scope.launch { repo.set(SettingsKeys.AMOLED_BLACK, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Terminal color scheme", style = MaterialTheme.typography.labelLarge)
            // "Nord" used to also be a standalone radio option here, but
            // it had no dedicated behavior of its own - selecting it did
            // nothing beyond the radio button changing, since it's really
            // just one of the presets inside Custom Palette (see
            // PaletteThemes.kt) and belongs there.
            listOf("Material", "No color", "Custom fg/bg", "Custom Palette", "Import theme file").forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = colorSchemeMode == option,
                        onClick = { scope.launch { repo.set(SettingsKeys.COLOR_SCHEME_MODE, option) } }
                    )
                    Text(option)
                }
            }

            if (colorSchemeMode == "No color") {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Terminal keeps its own stable built-in colors - no " +
                        "Material, status, or custom override is applied.",
                    style = MaterialTheme.typography.bodySmall
                )
            }

            if (colorSchemeMode == "Custom fg/bg") {
                Spacer(modifier = Modifier.height(8.dp))
                CustomRgbEditor(
                    foregroundHex = "#%06X".format(customFg and 0xFFFFFF),
                    backgroundHex = "#%06X".format(customBg and 0xFFFFFF),
                    onForegroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_FG, it) } }
                    },
                    onBackgroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_BG, it) } }
                    }
                )
            }

            if (colorSchemeMode == "Custom Palette") {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "All 16 ANSI colors, defined individually - start from a " +
                        "preset below or edit any slot directly.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(modifier = Modifier.height(8.dp))
                PalettePresetRow(
                    onPresetSelected = { preset ->
                        scope.launch {
                            repo.set(SettingsKeys.CUSTOM_PALETTE_COLORS, encodePaletteColors(preset.colors))
                            repo.set(SettingsKeys.CUSTOM_PALETTE_FG, preset.foreground)
                            repo.set(SettingsKeys.CUSTOM_PALETTE_BG, preset.background)
                        }
                    }
                )
                Spacer(modifier = Modifier.height(12.dp))
                CustomPaletteEditor(
                    colors = customPaletteColors,
                    foregroundHex = "#%06X".format(customPaletteFg and 0xFFFFFF),
                    backgroundHex = "#%06X".format(customPaletteBg and 0xFFFFFF),
                    onColorChange = { index, argb ->
                        val next = customPaletteColors.copyOf()
                        next[index] = argb
                        scope.launch { repo.set(SettingsKeys.CUSTOM_PALETTE_COLORS, encodePaletteColors(next)) }
                    },
                    onForegroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_PALETTE_FG, it) } }
                    },
                    onBackgroundChange = { hex ->
                        parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.CUSTOM_PALETTE_BG, it) } }
                    }
                )
            }

            if (colorSchemeMode == "Import theme file") {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = {
                    themeFilePicker.launch(arrayOf("text/*", "application/json", "*/*"))
                }) {
                    Text("Import theme file")
                }
                importedFileName?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Imported: $it", style = MaterialTheme.typography.bodySmall)
                }
                importError?.let {
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            if (colorSchemeMode == "Material") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Override ANSI colors too", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off: Material only fills in text/background for " +
                                "colorless output - a program's own red/green/blue " +
                                "etc. still render as-is. On: Material's palette is " +
                                "also mapped onto every ANSI color, so program output " +
                                "picks up Material's hues too.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = materialColorOverride,
                        onCheckedChange = { scope.launch { repo.set(SettingsKeys.MATERIAL_COLOR_OVERRIDE, it) } }
                    )
                }
            }

            // "No color" means "leave the terminal's stable built-in colors
            // completely untouched" - showing the status-color override
            // section underneath would contradict that, so it's hidden
            // (not just disabled) for this mode rather than letting the
            // two settings quietly fight each other.
            if (colorSchemeMode != "No color") {
                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider()
                Spacer(modifier = Modifier.height(16.dp))
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Separate error/status colors", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Independent of the color scheme above. When on, ANSI " +
                                "red is pinned to the error color and ANSI yellow to " +
                                "the status color below, no matter which theme is active.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = statusColorsEnabled,
                        onCheckedChange = { scope.launch { repo.set(SettingsKeys.STATUS_COLORS_ENABLED, it) } }
                    )
                }
                if (statusColorsEnabled) {
                    Spacer(modifier = Modifier.height(8.dp))
                    StatusColorEditor(
                        errorHex = "#%06X".format(statusErrorColor and 0xFFFFFF),
                        warningHex = "#%06X".format(statusWarningColor and 0xFFFFFF),
                        onErrorChange = { hex ->
                            parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.STATUS_ERROR_COLOR, it) } }
                        },
                        onWarningChange = { hex ->
                            parseHexColor(hex)?.let { scope.launch { repo.set(SettingsKeys.STATUS_WARNING_COLOR, it) } }
                        }
                    )
                }
            }
        }
    }
}

@Composable
private fun StatusColorEditor(
    errorHex: String,
    warningHex: String,
    onErrorChange: (String) -> Unit,
    onWarningChange: (String) -> Unit
) {
    var errorText by remember(errorHex) { mutableStateOf(errorHex) }
    var warningText by remember(warningHex) { mutableStateOf(warningHex) }

    Column {
        OutlinedTextField(
            value = errorText,
            onValueChange = {
                errorText = it
                onErrorChange(it)
            },
            label = { Text("Error color (hex, e.g. #FF5555)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = warningText,
            onValueChange = {
                warningText = it
                onWarningChange(it)
            },
            label = { Text("Status/warning color (hex, e.g. #F1C40F)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** ARGB int for #FF5555 - a readable default error red. */
const val DEFAULT_STATUS_ERROR = 0xFFFF5555.toInt()

/** ARGB int for #F1C40F - a readable default status/warning yellow. */
const val DEFAULT_STATUS_WARNING = 0xFFF1C40F.toInt()

@Composable
private fun CustomRgbEditor(
    foregroundHex: String,
    backgroundHex: String,
    onForegroundChange: (String) -> Unit,
    onBackgroundChange: (String) -> Unit
) {
    var fgText by remember(foregroundHex) { mutableStateOf(foregroundHex) }
    var bgText by remember(backgroundHex) { mutableStateOf(backgroundHex) }

    Column {
        OutlinedTextField(
            value = fgText,
            onValueChange = {
                fgText = it
                onForegroundChange(it)
            },
            label = { Text("Text color (hex, e.g. #E6E6E6)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = bgText,
            onValueChange = {
                bgText = it
                onBackgroundChange(it)
            },
            label = { Text("Background color (hex, e.g. #000000)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

/** ARGB int for #E6E6E6 - matches the previous flatBlack() default foreground. */
const val DEFAULT_CUSTOM_FG = 0xFFE6E6E6.toInt()

/** ARGB int for #000000 - matches the previous flatBlack() default background. */
const val DEFAULT_CUSTOM_BG = 0xFF000000.toInt()

@Composable
private fun PalettePresetRow(onPresetSelected: (com.terminator.app.ui.settings.NamedPalette) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(androidx.compose.foundation.rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        PalettePresets.all.forEach { preset ->
            OutlinedButton(onClick = { onPresetSelected(preset) }) {
                Text(preset.name, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

@Composable
private fun CustomPaletteEditor(
    colors: IntArray,
    foregroundHex: String,
    backgroundHex: String,
    onColorChange: (index: Int, argb: Int) -> Unit,
    onForegroundChange: (String) -> Unit,
    onBackgroundChange: (String) -> Unit
) {
    Column {
        CustomRgbEditor(
            foregroundHex = foregroundHex,
            backgroundHex = backgroundHex,
            onForegroundChange = onForegroundChange,
            onBackgroundChange = onBackgroundChange
        )
        Spacer(modifier = Modifier.height(12.dp))
        HorizontalDivider()
        Spacer(modifier = Modifier.height(8.dp))
        ANSI_SLOT_NAMES.forEachIndexed { index, label ->
            var hexText by remember(colors[index]) {
                mutableStateOf("#%06X".format(colors.getOrElse(index) { 0 } and 0xFFFFFF))
            }
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                androidx.compose.foundation.layout.Box(
                    modifier = Modifier
                        .size(24.dp)
                        .background(
                            androidx.compose.ui.graphics.Color(colors.getOrElse(index) { 0 }),
                            androidx.compose.foundation.shape.RoundedCornerShape(4.dp)
                        )
                )
                Spacer(modifier = Modifier.width(12.dp))
                Text(label, modifier = Modifier.width(110.dp), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(
                    value = hexText,
                    onValueChange = { text ->
                        hexText = text
                        parseHexColor(text)?.let { onColorChange(index, it) }
                    },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

/**
 * Parses a small theme file into (foreground, background) ARGB ints.
 * Supports "key=value" lines and a flat JSON object; returns null if
 * neither foreground nor background could be found.
 */
private fun parseThemeFile(text: String): Pair<Int, Int>? {
    var fg: Int? = null
    var bg: Int? = null

    val trimmed = text.trim()
    if (trimmed.startsWith("{")) {
        runCatching {
            val obj = JSONObject(trimmed)
            fg = firstColorKey(obj, "foreground", "fg", "text")
            bg = firstColorKey(obj, "background", "bg")
        }
    } else {
        trimmed.lineSequence().forEach { line ->
            val cleaned = line.trim()
            if (cleaned.isBlank() || !cleaned.contains('=')) return@forEach
            val (key, value) = cleaned.split('=', limit = 2).map { it.trim() }
            val color = parseHexColor(value) ?: return@forEach
            when (key.lowercase()) {
                "foreground", "fg", "text" -> fg = color
                "background", "bg" -> bg = color
            }
        }
    }

    val resolvedFg = fg ?: DEFAULT_CUSTOM_FG
    val resolvedBg = bg ?: DEFAULT_CUSTOM_BG
    return if (fg == null && bg == null) null else resolvedFg to resolvedBg
}

private fun firstColorKey(obj: JSONObject, vararg keys: String): Int? {
    for (key in keys) {
        if (obj.has(key)) {
            parseHexColor(obj.optString(key))?.let { return it }
        }
    }
    return null
}

private fun parseHexColor(hex: String): Int? {
    return try {
        android.graphics.Color.parseColor(if (hex.startsWith("#")) hex else "#$hex")
    } catch (_: IllegalArgumentException) {
        null
    }
}
