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
// Used only as the pre-Android-12 fallback, where there's no Material You
// wallpaper-derived palette to AMOLED-ify (see TerminatorTheme below).
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
    // AMOLED Black is an *override* on top of Material You, not a
    // replacement for it: it was previously a completely separate
    // hardcoded palette (TerminatorFlatScheme), so turning it on threw
    // away the device's wallpaper-derived colors entirely - accents,
    // tints, everything went flat blue-on-black regardless of wallpaper.
    //
    // OFF = the dynamic scheme completely untouched, exactly as the
    // system provides it (normal Material You).
    //
    // ON = every neutral/surface slot pushed to pure black - not just
    // background/surface/surfaceVariant like before, which left
    // surfaceContainer/surfaceContainerHigh/surfaceContainerHighest (cards,
    // dialogs, menus, the settings list's own row backgrounds) and
    // inverseSurface sitting at Material You's dark-gray tones instead of
    // true black, so "AMOLED Black" only affected part of the screen and
    // the rest stayed visibly dark-gray. Every hue slot (primary/
    // secondary/tertiary/error and their on-/container pairs, all of
    // which Material You actually derives from the wallpaper) is left
    // alone either way - AMOLED Black only ever touches neutrals, never
    // the accent colors that make it "Material You" in the first place.
    //
    // Pre-Android-12 has no dynamic palette to work from, so both states
    // fall back to the old hardcoded schemes there.
    val context = LocalContext.current
    val colorScheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val dynamic = dynamicDarkColorScheme(context)
            if (amoledBlack) {
                dynamic.copy(
                    background = FlatBlack,
                    surface = FlatBlack,
                    surfaceVariant = FlatBlackSurface,
                    surfaceBright = FlatBlackSurface,
                    surfaceDim = FlatBlack,
                    surfaceContainer = FlatBlackSurface,
                    surfaceContainerLow = FlatBlack,
                    surfaceContainerLowest = FlatBlack,
                    surfaceContainerHigh = FlatBlackSurface,
                    surfaceContainerHighest = FlatBlackSurface,
                    inverseSurface = Color(0xFFE6E6E6),
                    inverseOnSurface = FlatBlack,
                    scrim = Color.Black
                )
            } else {
                dynamic
            }
        }
        amoledBlack -> TerminatorFlatScheme
        else -> TerminatorDarkFallbackScheme
    }
    MaterialTheme(
        colorScheme = colorScheme,
        typography = MaterialTheme.typography,
        content = content
    )
}
