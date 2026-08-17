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

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import com.terminator.app.ui.BUILT_IN_FONTS
import com.terminator.app.ui.cachedCustomFontFile
import kotlinx.coroutines.launch
import java.io.FileOutputStream

/**
 * Font chooser (built-in monospace-style options or an imported .ttf/.otf),
 * text size, terminal width (columns), background blur + alpha (applied
 * together), wallpaper picker from storage. Every value here is persisted
 * via SettingsRepository and applied live behind the terminal on the main
 * screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppearanceSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val fontFamily by repo.flow(SettingsKeys.FONT_FAMILY, "Monospace").collectAsState(initial = "Monospace")
    // Was written by the font picker below but never read back anywhere -
    // this is what lets the screen actually show which file is imported
    // instead of just a generic "Custom" label.
    val fontUri by repo.flow(SettingsKeys.FONT_URI, "").collectAsState(initial = "")
    val importedFontName = remember(fontUri) {
        if (fontUri.isBlank()) null
        else runCatching {
            context.contentResolver.query(android.net.Uri.parse(fontUri), null, null, null, null)
                ?.use { cursor ->
                    val nameIndex = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (cursor.moveToFirst() && nameIndex >= 0) cursor.getString(nameIndex) else null
                }
        }.getOrNull()
    }
    val textSize by repo.flow(SettingsKeys.TEXT_SIZE, 14f).collectAsState(initial = 14f)
    val columns by repo.flow(SettingsKeys.COLUMNS, 80f).collectAsState(initial = 80f)
    val zoomEnabled by repo.flow(SettingsKeys.ZOOM_ENABLED, true).collectAsState(initial = true)
    // Migrated from the old combined BLUR_ALPHA - both default to its old
    // value/behaviour (0.3 alpha, no blur) so existing installs don't jump.
    val backgroundAlpha by repo.flow(SettingsKeys.BACKGROUND_ALPHA, 0.3f).collectAsState(initial = 0.3f)
    val backgroundBlur by repo.flow(SettingsKeys.BACKGROUND_BLUR, 0f).collectAsState(initial = 0f)
    val wallpaperUri by repo.flow(SettingsKeys.WALLPAPER_URI, "").collectAsState(initial = "")

    val wallpaperPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions - the
                // Uri is still usable for this session, just won't survive reboot.
            }
            scope.launch { repo.set(SettingsKeys.WALLPAPER_URI, uri.toString()) }
        }
    }

    val fontPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(cachedCustomFontFile(context)).use { output -> input.copyTo(output) }
                }
            }
            scope.launch {
                repo.set(SettingsKeys.FONT_URI, uri.toString())
                repo.set(SettingsKeys.FONT_FAMILY, "Custom")
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Appearance") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            Text("Font", style = MaterialTheme.typography.labelLarge)
            BUILT_IN_FONTS.forEach { option ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    RadioButton(
                        selected = fontFamily == option,
                        onClick = {
                            if (option == "Custom") {
                                fontPicker.launch(arrayOf("font/ttf", "font/otf", "application/x-font-ttf", "*/*"))
                            } else {
                                scope.launch { repo.set(SettingsKeys.FONT_FAMILY, option) }
                            }
                        }
                    )
                    Text(
                        if (option == "Custom") {
                            if (fontFamily == "Custom" && importedFontName != null) {
                                "Custom (imported: $importedFontName)"
                            } else {
                                "Custom (import .ttf/.otf)"
                            }
                        } else option
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Text size: ${textSize.toInt()}sp")
            Slider(
                value = textSize,
                onValueChange = { scope.launch { repo.set(SettingsKeys.TEXT_SIZE, it) } },
                valueRange = 8f..28f
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Terminal width: ${columns.toInt()} columns")
            Slider(
                value = columns,
                onValueChange = { scope.launch { repo.set(SettingsKeys.COLUMNS, it) } },
                valueRange = 40f..200f
            )

            Spacer(modifier = Modifier.height(16.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Pinch to zoom", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Two-finger pinch resizes the terminal text live. " +
                            "Turn off to only change size via the slider above.",
                        style = MaterialTheme.typography.bodySmall
                    )
                }
                Switch(
                    checked = zoomEnabled,
                    onCheckedChange = { scope.launch { repo.set(SettingsKeys.ZOOM_ENABLED, it) } }
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text("Background alpha: ${(backgroundAlpha * 100).toInt()}%")
            Slider(
                value = backgroundAlpha,
                onValueChange = { scope.launch { repo.set(SettingsKeys.BACKGROUND_ALPHA, it) } },
                valueRange = 0f..1f
            )

            Spacer(modifier = Modifier.height(16.dp))
            Text("Background blur: ${(backgroundBlur * 100).toInt()}%")
            Slider(
                value = backgroundBlur,
                onValueChange = { scope.launch { repo.set(SettingsKeys.BACKGROUND_BLUR, it) } },
                valueRange = 0f..1f
            )

            Spacer(modifier = Modifier.height(16.dp))
            OutlinedButton(onClick = { wallpaperPicker.launch(arrayOf("image/*")) }) {
                Text(if (wallpaperUri.isBlank()) "Choose wallpaper from storage" else "Change wallpaper")
            }
            if (wallpaperUri.isNotBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                TextButton(onClick = { scope.launch { repo.set(SettingsKeys.WALLPAPER_URI, "") } }) {
                    Text("Remove wallpaper")
                }
            }
        }
    }
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, modifier = Modifier.align(androidx.compose.ui.Alignment.CenterVertically))
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
