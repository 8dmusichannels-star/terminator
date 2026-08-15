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

import org.json.JSONArray

/**
 * A full 16-slot ANSI termcolor palette (standard order: black, red, green,
 * yellow, blue, magenta, cyan, white, then the 8 bright variants), plus its
 * own default foreground/background - the "Custom Palette" theme option's
 * data model. Distinct from the plain CUSTOM_FG/CUSTOM_BG pair used by
 * "Custom fg/bg" mode, which never touches the 16 ANSI slots.
 */
data class NamedPalette(
    val name: String,
    val colors: IntArray,
    val foreground: Int,
    val background: Int
) {
    init {
        require(colors.size == 16) { "NamedPalette requires exactly 16 colors, got ${colors.size}" }
    }
}

/** Encodes a 16-int ANSI color array as a JSON array string for CUSTOM_PALETTE_COLORS. */
fun encodePaletteColors(colors: IntArray): String {
    val arr = JSONArray()
    colors.forEach { arr.put(it) }
    return arr.toString()
}

/** Decodes CUSTOM_PALETTE_COLORS back into a 16-int array, falling back to
 *  [PalettePresets.default]'s colors if the stored value is missing/malformed
 *  (e.g. first run, or a value from a future/incompatible app version). */
fun decodePaletteColors(json: String?): IntArray {
    if (json.isNullOrBlank()) return PalettePresets.default.colors.copyOf()
    return try {
        val arr = JSONArray(json)
        if (arr.length() != 16) return PalettePresets.default.colors.copyOf()
        IntArray(16) { arr.getInt(it) }
    } catch (_: Exception) {
        PalettePresets.default.colors.copyOf()
    }
}

/**
 * Bundled termcolor palette presets, offered as one-tap starting points for
 * "Custom Palette" mode instead of making every user hand-pick 16 colors
 * from nothing. Users can still edit any slot after applying one - applying
 * a preset just seeds CUSTOM_PALETTE_COLORS/FG/BG, it isn't a separate mode.
 */
object PalettePresets {
    val default = NamedPalette(
        name = "Default",
        colors = intArrayOf(
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
        ),
        foreground = 0xFFE6E6E6.toInt(),
        background = 0xFF000000.toInt()
    )

    val solarizedDark = NamedPalette(
        name = "Solarized Dark",
        colors = intArrayOf(
            0xFF073642.toInt(), 0xFFDC322F.toInt(), 0xFF859900.toInt(), 0xFFB58900.toInt(),
            0xFF268BD2.toInt(), 0xFFD33682.toInt(), 0xFF2AA198.toInt(), 0xFFEEE8D5.toInt(),
            0xFF002B36.toInt(), 0xFFCB4B16.toInt(), 0xFF586E75.toInt(), 0xFF657B83.toInt(),
            0xFF839496.toInt(), 0xFF6C71C4.toInt(), 0xFF93A1A1.toInt(), 0xFFFDF6E3.toInt()
        ),
        foreground = 0xFF839496.toInt(),
        background = 0xFF002B36.toInt()
    )

    val gruvboxDark = NamedPalette(
        name = "Gruvbox Dark",
        colors = intArrayOf(
            0xFF282828.toInt(), 0xFFCC241D.toInt(), 0xFF98971A.toInt(), 0xFFD79921.toInt(),
            0xFF458588.toInt(), 0xFFB16286.toInt(), 0xFF689D6A.toInt(), 0xFFA89984.toInt(),
            0xFF928374.toInt(), 0xFFFB4934.toInt(), 0xFFB8BB26.toInt(), 0xFFFABD2F.toInt(),
            0xFF83A598.toInt(), 0xFFD3869B.toInt(), 0xFF8EC07C.toInt(), 0xFFEBDBB2.toInt()
        ),
        foreground = 0xFFEBDBB2.toInt(),
        background = 0xFF282828.toInt()
    )

    val dracula = NamedPalette(
        name = "Dracula",
        colors = intArrayOf(
            0xFF21222C.toInt(), 0xFFFF5555.toInt(), 0xFF50FA7B.toInt(), 0xFFF1FA8C.toInt(),
            0xFFBD93F9.toInt(), 0xFFFF79C6.toInt(), 0xFF8BE9FD.toInt(), 0xFFF8F8F2.toInt(),
            0xFF6272A4.toInt(), 0xFFFF6E6E.toInt(), 0xFF69FF94.toInt(), 0xFFFFFFA5.toInt(),
            0xFFD6ACFF.toInt(), 0xFFFF92DF.toInt(), 0xFFA4FFFF.toInt(), 0xFFFFFFFF.toInt()
        ),
        foreground = 0xFFF8F8F2.toInt(),
        background = 0xFF282A36.toInt()
    )

    val nord = NamedPalette(
        name = "Nord",
        colors = intArrayOf(
            0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
            0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
            0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
        ),
        foreground = 0xFFD8DEE9.toInt(),
        background = 0xFF2E3440.toInt()
    )

    val all: List<NamedPalette> = listOf(default, solarizedDark, gruvboxDark, dracula, nord)
}

/** ANSI slot names in index order, for labeling each row in the palette editor. */
val ANSI_SLOT_NAMES = listOf(
    "Black", "Red", "Green", "Yellow", "Blue", "Magenta", "Cyan", "White",
    "Bright Black", "Bright Red", "Bright Green", "Bright Yellow",
    "Bright Blue", "Bright Magenta", "Bright Cyan", "Bright White"
)
