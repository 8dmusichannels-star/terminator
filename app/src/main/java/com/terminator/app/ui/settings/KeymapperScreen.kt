package com.terminator.app.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/** A single saved shortcut: a free-form name plus the terminal key actions it triggers. */
data class KeymapEntry(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val keys: List<String> // VirtualKey.name values, e.g. "ESC", "CTRL", "UP"
)

private val ALL_KEY_OPTIONS = listOf(
    "ESC", "TAB", "CTRL", "ALT", "SLASH", "DASH",
    "HOME", "END", "PGUP", "PGDN", "UP", "DOWN", "LEFT", "RIGHT"
)

fun decodeKeymaps(json: String): List<KeymapEntry> {
    if (json.isBlank()) return emptyList()
    return runCatching {
        val arr = JSONArray(json)
        (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            val keysArr = o.getJSONArray("keys")
            KeymapEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                keys = (0 until keysArr.length()).map { keysArr.getString(it) }
            )
        }
    }.getOrDefault(emptyList())
}

private fun encodeKeymaps(list: List<KeymapEntry>): String {
    val arr = JSONArray()
    list.forEach { entry ->
        val o = JSONObject()
        o.put("id", entry.id)
        o.put("name", entry.name)
        o.put("keys", JSONArray(entry.keys))
        arr.put(o)
    }
    return arr.toString()
}

/**
 * Opened from Settings > Keyboard > "Keyboard shortcuts & keymapper".
 * Lists saved named shortcuts; '+' opens an editor to create a new one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeymapperScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()
    val keymapsJson by repo.flow(SettingsKeys.KEYMAPS, "").collectAsState(initial = "")
    val keymaps = remember(keymapsJson) { decodeKeymaps(keymapsJson) }

    var editing: KeymapEntry? by remember { mutableStateOf(null) }
    var showEditor by remember { mutableStateOf(false) }

    if (showEditor) {
        KeymapEditor(
            initial = editing,
            onClose = { showEditor = false; editing = null },
            onSave = { entry ->
                val updated = keymaps.filterNot { it.id == entry.id } + entry
                scope.launch { repo.set(SettingsKeys.KEYMAPS, encodeKeymaps(updated)) }
                showEditor = false
                editing = null
            }
        )
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard shortcuts") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { editing = null; showEditor = true }) {
                Text("+")
            }
        }
    ) { padding ->
        if (keymaps.isEmpty()) {
            Column(
                modifier = Modifier.padding(padding).fillMaxSize().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    "No shortcuts yet. Tap + to create one.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        } else {
            LazyColumn(modifier = Modifier.padding(padding).fillMaxSize()) {
                items(keymaps, key = { it.id }) { entry ->
                    ListItem(
                        headlineContent = { Text(entry.name) },
                        supportingContent = { Text(entry.keys.joinToString(" + ")) },
                        trailingContent = {
                            IconButton(onClick = {
                                val updated = keymaps.filterNot { it.id == entry.id }
                                scope.launch { repo.set(SettingsKeys.KEYMAPS, encodeKeymaps(updated)) }
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete shortcut")
                            }
                        },
                        modifier = Modifier.clickable {
                            editing = entry
                            showEditor = true
                        }
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

/**
 * '+' destination: a name field ("name kullanıcı ne isterse yazar") and,
 * below it, the key-shortcut area - a multi-select grid of terminal key
 * actions this shortcut triggers. Close discards, Save persists.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun KeymapEditor(
    initial: KeymapEntry?,
    onClose: () -> Unit,
    onSave: (KeymapEntry) -> Unit
) {
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var selectedKeys by remember { mutableStateOf(initial?.keys?.toSet() ?: emptySet()) }

    Scaffold(
        topBar = {
            TopAppBar(title = { Text(if (initial == null) "New shortcut" else "Edit shortcut") })
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                OutlinedButton(onClick = onClose, modifier = Modifier.weight(1f)) {
                    Text("Close")
                }
                Button(
                    onClick = {
                        if (name.isNotBlank() && selectedKeys.isNotEmpty()) {
                            onSave(
                                KeymapEntry(
                                    id = initial?.id ?: UUID.randomUUID().toString(),
                                    name = name.trim(),
                                    keys = selectedKeys.toList()
                                )
                            )
                        }
                    },
                    enabled = name.isNotBlank() && selectedKeys.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) {
                    Text("Save")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )

            Spacer(modifier = Modifier.height(20.dp))
            Text("Key shortcut", style = MaterialTheme.typography.labelLarge)
            Text(
                "Pick the keys this shortcut sends to the terminal.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(modifier = Modifier.height(8.dp))

            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ALL_KEY_OPTIONS.forEach { key ->
                    FilterChip(
                        selected = key in selectedKeys,
                        onClick = {
                            selectedKeys = if (key in selectedKeys) {
                                selectedKeys - key
                            } else {
                                selectedKeys + key
                            }
                        },
                        label = { Text(key) }
                    )
                }
            }
        }
    }
}
