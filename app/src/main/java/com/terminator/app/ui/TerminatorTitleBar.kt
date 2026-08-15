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

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp

/**
 * Typical titlebar: hamburger (opens the session drawer, same as swipe
 * gesture) on the left, "TERMINATOR" centered, quick session-select + on
 * the right. Shown only when Settings > Display > Show Titlebar is enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminatorTitleBar(
    onMenuClicked: () -> Unit,
    onQuickAddClicked: () -> Unit,
    // Optional picture from the active session's SessionEntry.imageUri -
    // see that field's doc. Purely cosmetic: when null/blank, the titlebar
    // renders exactly as before (just the hamburger + centered title).
    // Reflecting it here is opt-in in the sense that it only shows up at
    // all if the user bothered to set a picture on the session in
    // Settings > Sessions - nothing forces every session to have one.
    activeSessionImageUri: String? = null
) {
    TopAppBar(
        title = {
            if (!activeSessionImageUri.isNullOrBlank()) {
                val context = LocalContext.current
                val bitmap = remember(activeSessionImageUri) {
                    runCatching {
                        context.contentResolver.openInputStream(android.net.Uri.parse(activeSessionImageUri))
                            ?.use { android.graphics.BitmapFactory.decodeStream(it) }
                    }.getOrNull()
                }
                if (bitmap != null) {
                    androidx.compose.foundation.layout.Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.size(28.dp).clip(CircleShape)
                        )
                        androidx.compose.foundation.layout.Spacer(modifier = Modifier.size(8.dp))
                        Text("TERMINATOR")
                    }
                } else {
                    Text("TERMINATOR")
                }
            } else {
                Text("TERMINATOR")
            }
        },
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
        // Kept flat black - see the comment on `colors` below for why the
        // container itself stays pinned. Text/icon color, however, now
        // follows the active Material color scheme (onBackground) instead
        // of a hardcoded Color.White, so it stays readable against
        // whatever theme (including AMOLED/light variants of Material)
        // the user has picked in Settings > Theme rather than assuming
        // dark-on-black always applies.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            scrolledContainerColor = Color.Black,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
            navigationIconContentColor = MaterialTheme.colorScheme.onBackground,
            actionIconContentColor = MaterialTheme.colorScheme.onBackground
        )
    )
}
