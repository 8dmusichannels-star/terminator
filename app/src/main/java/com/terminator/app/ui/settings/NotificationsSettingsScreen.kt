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

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.core.content.ContextCompat
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/**
 * Settings > Notifications - the "separate window" for OSC 9/777 terminal-
 * requested notifications the user asked for: a single opt-in switch (see
 * SettingsKeys.OSC_NOTIFICATIONS_ENABLED's own doc for why this defaults
 * off, same posture as Allow OSC 52 clipboard reads under Sessions) plus,
 * on API 33+, a shortcut into the runtime POST_NOTIFICATIONS permission
 * prompt - turning the in-app switch on is meaningless if Android itself
 * would silently drop the notification for lack of that permission, so
 * this surfaces the gap right where the user just asked to enable it
 * rather than leaving them to discover a notification never arrived.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationsSettingsScreen(onBack: () -> Unit) {
    val settingsRepo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val oscNotificationsEnabled by settingsRepo.flow(SettingsKeys.OSC_NOTIFICATIONS_ENABLED, false)
        .collectAsState(initial = false)

    // Only meaningful on API 33+ (Android 13's runtime notification
    // permission) - below that, POST_NOTIFICATIONS doesn't exist and
    // notifications just work once a channel is created. Re-checked on
    // every recomposition (rather than cached once) so returning from the
    // system permission dialog immediately reflects the new state without
    // needing its own callback plumbing.
    val hasNotificationPermission = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* result reflected on next recomposition via hasNotificationPermission above */ }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notifications") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                    scrolledContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Terminal notifications", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("OSC 9 / OSC 777 notifications", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off: a running command's \"OSC 9\"/\"OSC 777\" " +
                                "notification request (e.g. a shell script " +
                                "alerting you when a long build finishes) is " +
                                "silently ignored. On: it's posted as a real " +
                                "system notification.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = oscNotificationsEnabled,
                        onCheckedChange = { checked ->
                            scope.launch { settingsRepo.set(SettingsKeys.OSC_NOTIFICATIONS_ENABLED, checked) }
                        }
                    )
                }
                if (oscNotificationsEnabled && !hasNotificationPermission) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Card {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Text(
                                "Notification permission not granted",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                "Android also requires its own notification " +
                                    "permission - without it, terminal " +
                                    "notifications still won't appear even " +
                                    "with the switch above on.",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Button(onClick = { permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS) }) {
                                Text("Grant permission")
                            }
                        }
                    }
                }
            }
        }
    }
}
