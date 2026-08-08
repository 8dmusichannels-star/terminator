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

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Typical titlebar: hamburger (opens the session drawer, same as swipe
 * gesture) on the left, "TERMINATOR" centered, quick session-select + on
 * the right. Shown only when Settings > Display > Show Titlebar is enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminatorTitleBar(
    onMenuClicked: () -> Unit,
    onQuickAddClicked: () -> Unit
) {
    TopAppBar(
        title = { Text("TERMINATOR") },
        navigationIcon = {
            IconButton(onClick = onMenuClicked) {
                Icon(Icons.Filled.Menu, contentDescription = "Open sessions")
            }
        },
        actions = {
            IconButton(onClick = onQuickAddClicked) {
                Icon(Icons.Filled.Add, contentDescription = "Quick session select")
            }
        },
        // Material3's default TopAppBar tints its container with a translucent
        // primary-color overlay ("tonal elevation") once the surface scrolls
        // under it - that's the unwanted blue wash at the top of the window.
        // Pin every state to flat black so the titlebar always matches the
        // rest of the flat-black theme.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            scrolledContainerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}
