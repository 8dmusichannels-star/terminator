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

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import com.terminator.app.TerminatorApp
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.ui.theme.TerminatorTheme

/**
 * Settings root: category list, each opening its own sub-page.
 * Categories: Sessions, Appearance, Theme, Sound, Display, Keyboard, Storage/Permissions.
 */
class SettingsActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            // Was TerminatorTheme {} with no argument, which always
            // defaulted amoledBlack to false - Settings > Theme > AMOLED
            // Black could be switched on and MainActivity's terminal
            // screen would follow it correctly, but Settings itself (this
            // screen and everything under it: Sessions, Appearance, Theme,
            // Sound, Display, Keyboard, Storage) stayed on the plain
            // Material You scheme regardless, since nothing here ever read
            // the setting. Reading it the same way MainActivity and
            // ThemeSettingsScreen already do and passing it through makes
            // AMOLED Black an app-wide setting instead of one that only
            // visibly did anything on the terminal screen.
            val settingsRepo = remember {
                (applicationContext as TerminatorApp).settingsRepository
            }
            val amoledBlack by settingsRepo.flow(SettingsKeys.AMOLED_BLACK, false)
                .collectAsState(initial = false)
            TerminatorTheme(amoledBlack = amoledBlack) {
                SettingsRoot()
            }
        }
    }
}

private enum class SettingsCategory(val title: String) {
    SESSIONS("Sessions"),
    APPEARANCE("Appearance"),
    THEME("Theme"),
    SOUND("Sound"),
    DISPLAY("Display"),
    KEYBOARD("Keyboard"),
    STORAGE("Storage & Permissions")
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsRoot() {
    var openCategory by androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf<SettingsCategory?>(null)
    }

    when (openCategory) {
        SettingsCategory.SESSIONS -> SessionsSettingsScreen(onBack = { openCategory = null })
        SettingsCategory.APPEARANCE -> AppearanceSettingsScreen(onBack = { openCategory = null })
        SettingsCategory.THEME -> ThemeSettingsScreen(onBack = { openCategory = null })
        SettingsCategory.SOUND -> SoundSettingsScreen(onBack = { openCategory = null })
        SettingsCategory.DISPLAY -> DisplaySettingsScreen(onBack = { openCategory = null })
        SettingsCategory.KEYBOARD -> KeyboardSettingsScreen(onBack = { openCategory = null })
        SettingsCategory.STORAGE -> StorageSettingsScreen(onBack = { openCategory = null })
        null -> Scaffold(
            topBar = { TopAppBar(title = { Text("Settings") }) }
        ) { padding ->
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(SettingsCategory.values().toList()) { category ->
                    ListItem(
                        headlineContent = { Text(category.title) },
                        modifier = Modifier.clickable { openCategory = category }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}
