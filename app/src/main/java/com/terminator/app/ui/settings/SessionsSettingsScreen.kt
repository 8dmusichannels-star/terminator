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
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminator.app.TerminatorApp
import com.terminator.app.session.SessionEntry
import com.terminator.app.session.SessionRepository
import com.terminator.app.session.SessionType
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/** Grabs the app-wide SessionRepository the same way MainViewModel does. */
@Composable
private fun rememberSessionRepository(): SessionRepository {
    val context = LocalContext.current
    return remember { (context.applicationContext as TerminatorApp).sessionRepository }
}

/**
 * Add/edit/delete custom sessions here. Two types:
 * - Command Arg Session: a single executable path (e.g. /system/bin/su)
 * - File Base Session: a directory path + filename, combined to execute
 * Sessions themselves are only *selected* from the home screen drawer.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionsSettingsScreen(onBack: () -> Unit) {
    val repo = rememberSessionRepository()
    val settingsRepo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingSession by remember { mutableStateOf<SessionEntry?>(null) }
    val sessions by repo.sessions.collectAsState(initial = emptyList())
    val clearAlwaysPty by settingsRepo.flow(SettingsKeys.CLEAR_ALWAYS_PTY, false).collectAsState(initial = false)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sessions") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Text("+")
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize()) {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text("Terminal Behaviour", style = MaterialTheme.typography.labelLarge)
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Clear always purges scrollback", style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "Off: `clear` behaves like a normal terminal - old " +
                                "output just scrolls out of view, still reachable by " +
                                "scrolling up. On: `clear` also wipes scrollback " +
                                "history, so nothing is left above the screen.",
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                    Switch(
                        checked = clearAlwaysPty,
                        onCheckedChange = { scope.launch { settingsRepo.set(SettingsKeys.CLEAR_ALWAYS_PTY, it) } }
                    )
                }
            }
            HorizontalDivider()

            Text(
                "Multiple Mode allows opening the same session as more than one simultaneous instance.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall
            )

            // Every saved session shows up here, including the built-in
            // default Android shell - previously this screen never listed
            // any sessions at all, so the default one looked "missing" and
            // there was no way to remove it.
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(sessions, key = { it.id }) { session ->
                    SessionSettingsRow(
                        session = session,
                        onClick = { editingSession = session },
                        onToggleFavorite = {
                            scope.launch { repo.setFavorite(session.id, !session.isFavorite) }
                        },
                        onSetDefault = {
                            scope.launch { repo.setDefault(session.id) }
                        },
                        onDelete = {
                            scope.launch { repo.delete(session.id) }
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (showAddDialog) {
        AddSessionDialog(
            existing = null,
            onDismiss = { showAddDialog = false },
            onSave = { entry ->
                scope.launch { repo.save(entry) }
                showAddDialog = false
            }
        )
    }

    editingSession?.let { session ->
        AddSessionDialog(
            existing = session,
            onDismiss = { editingSession = null },
            onSave = { entry ->
                scope.launch { repo.save(entry) }
                editingSession = null
            }
        )
    }
}

@Composable
private fun SessionSettingsRow(
    session: SessionEntry,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onSetDefault: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Only renders when the session actually has a picture set - no
        // placeholder/initial circle is shown for sessions without one.
        // See SessionImage.kt's doc - adds SVG support alongside the
        // raster formats BitmapFactory already handled.
        val bitmap = com.terminator.app.ui.rememberSessionImage(session.imageUri)
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(36.dp)
                    .clip(CircleShape)
            )
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
        ) {
            Text(session.name, style = MaterialTheme.typography.bodyLarge)
            if (session.isDefault) {
                Text(
                    "Default",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            } else {
                TextButton(onClick = onSetDefault, contentPadding = PaddingValues(0.dp)) {
                    Text("Set as default", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Toggle favorite",
                tint = if (session.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    LocalContentColor.current.copy(alpha = 0.35f)
                }
            )
        }
        // Deletable even if it's the current default - SessionRepository
        // automatically promotes another session to default (or recreates
        // the built-in shell if the list would otherwise be empty), so
        // there's always at least one valid default left afterwards.
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = "Delete session")
        }
    }
}

@Composable
private fun AddSessionDialog(
    existing: SessionEntry?,
    onDismiss: () -> Unit,
    onSave: (SessionEntry) -> Unit
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var type by remember { mutableStateOf(existing?.type ?: SessionType.COMMAND_ARG) }
    var commandPath by remember { mutableStateOf(existing?.commandPath ?: "") }
    var filePath by remember { mutableStateOf(existing?.filePath ?: "/sdcard/Terminator") }
    var fileName by remember { mutableStateOf(existing?.fileName ?: "session.sh") }
    // Entry path: the directory the spawned process starts in (its cwd /
    // $HOME). Blank means "no override" - TerminalSession falls back to
    // the session's own history directory, same as before this existed.
    var workingDirectory by remember { mutableStateOf(existing?.workingDirectory ?: "") }
    var useRoot by remember { mutableStateOf(existing?.useRoot ?: false) }
    // Optional session picture - see SessionEntry.imageUri's doc. Purely
    // cosmetic, never required to save a session.
    var imageUri by remember { mutableStateOf(existing?.imageUri ?: "") }
    val context = LocalContext.current

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Same as AppearanceSettingsScreen's wallpaper picker - some
                // providers don't support persistable permissions, the Uri
                // still works for this session, just won't survive reboot.
            }
            imageUri = uri.toString()
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (existing != null) "Edit session" else "Add session") },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Session name") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))

                Text("Session picture (optional)", style = MaterialTheme.typography.labelLarge)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (imageUri.isNotBlank()) {
                        // See SessionImage.kt's doc - adds SVG support
                        // alongside the raster formats BitmapFactory
                        // already handled.
                        val bitmap = com.terminator.app.ui.rememberSessionImage(imageUri)
                        bitmap?.let {
                            Image(
                                bitmap = it.asImageBitmap(),
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                    }
                    TextButton(onClick = {
                        // "image/*" alone doesn't reliably surface .svg
                        // files in every device's SAF picker - some
                        // content providers index SVG under a generic/
                        // octet-stream MIME rather than image/svg+xml, so
                        // an image/*-only filter can hide them entirely.
                        // Listing both explicitly ensures SVG files show up
                        // now that decodeSessionImage() can actually render
                        // them (see SessionImage.kt).
                        imagePicker.launch(arrayOf("image/*", "image/svg+xml"))
                    }) {
                        Text(if (imageUri.isBlank()) "Choose picture" else "Change")
                    }
                    if (imageUri.isNotBlank()) {
                        TextButton(onClick = { imageUri = "" }) { Text("Remove") }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))

                Text("Session type", style = MaterialTheme.typography.labelLarge)
                Row {
                    FilterChip(
                        selected = type == SessionType.COMMAND_ARG,
                        onClick = { type = SessionType.COMMAND_ARG },
                        label = { Text("Command Arg Session") }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    FilterChip(
                        selected = type == SessionType.FILE_BASE,
                        onClick = { type = SessionType.FILE_BASE },
                        label = { Text("File Base Session") }
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))

                when (type) {
                    SessionType.COMMAND_ARG -> OutlinedTextField(
                        value = commandPath,
                        onValueChange = { commandPath = it },
                        label = { Text("Command path (e.g. /system/bin/su)") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    SessionType.FILE_BASE -> {
                        OutlinedTextField(
                            value = fileName,
                            onValueChange = { fileName = it },
                            label = { Text("Filename (e.g. session.sh)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        OutlinedTextField(
                            value = filePath,
                            onValueChange = { filePath = it },
                            label = { Text("Path (e.g. /sdcard/Terminator)") },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = workingDirectory,
                    onValueChange = { workingDirectory = it },
                    label = { Text("Entry path / spawn directory (optional)") },
                    placeholder = { Text("e.g. /sdcard, /data/local/tmp") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useRoot, onCheckedChange = { useRoot = it })
                    Text("Use root (su)")
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val entry = (existing ?: SessionEntry(name = "", type = type)).copy(
                    name = name.ifBlank { "Session" },
                    type = type,
                    commandPath = commandPath.ifBlank { null },
                    filePath = filePath.ifBlank { null },
                    fileName = fileName.ifBlank { null },
                    workingDirectory = workingDirectory.ifBlank { null },
                    useRoot = useRoot,
                    imageUri = imageUri.ifBlank { null }
                )
                onSave(entry)
            }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        }
    )
}
