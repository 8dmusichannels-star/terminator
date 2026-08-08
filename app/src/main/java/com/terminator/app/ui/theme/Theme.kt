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

package com.terminator.app.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// TERMINATOR base palette: flat black, no gradients/tint. A thin accent
// color is used for selection/highlights only - everything else is black.
private val AccentBlue = Color(0xFF7EC8FF)
private val FlatBlack = Color(0xFF000000)
private val FlatBlackSurface = Color(0xFF0A0A0A) // barely-there separation for rows/dividers

private val TerminatorFlatScheme = darkColorScheme(
    primary = AccentBlue,
    onPrimary = Color.Black,
    background = FlatBlack,
    onBackground = Color(0xFFE6E6E6),
    surface = FlatBlack,
    onSurface = Color(0xFFE6E6E6),
    surfaceVariant = FlatBlackSurface,
    onSurfaceVariant = Color(0xFFB0B0B0)
)

// Used when AMOLED Black is off on API < 31, where dynamic/Material You
// colors aren't available - a plain (non-pure-black) dark scheme rather
// than silently falling back to the same AMOLED look.
private val TerminatorDarkFallbackScheme = darkColorScheme(primary = AccentBlue)

@Composable
fun TerminatorTheme(
    amoledBlack: Boolean = false,
    content: @Composable () -> Unit
) {
    // Previously both branches were identical flat black, so this toggle
    // did nothing regardless of what Settings > Theme > AMOLED Black was
    // set to. Now: ON keeps the pure-black AMOLED scheme; OFF follows the
    // device's Material You wallpaper-derived colors (Android 12+), with a
    // plain dark scheme as the fallback on older versions.
    val context = LocalContext.current
    val colorScheme = when {
        amoledBlack -> TerminatorFlatScheme
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicDarkColorScheme(context)
        else -> TerminatorDarkFallbackScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
