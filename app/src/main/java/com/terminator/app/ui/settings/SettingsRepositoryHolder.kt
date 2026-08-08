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

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.terminator.app.TerminatorApp
import com.terminator.app.settings.SettingsRepository

/** Grabs the app-wide [SettingsRepository] the same way MainViewModel grabs SessionRepository. */
@Composable
fun rememberSettingsRepository(): SettingsRepository {
    val context = LocalContext.current
    return remember {
        (context.applicationContext as TerminatorApp).settingsRepository
    }
}
