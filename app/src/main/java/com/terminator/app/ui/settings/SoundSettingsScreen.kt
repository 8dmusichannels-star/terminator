package com.terminator.app.ui.settings

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/**
 * Bell sound fires on error. User picks the default notification sound
 * or a custom sound file.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SoundSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val bellEnabled by repo.flow(SettingsKeys.BELL_ENABLED, true).collectAsState(initial = true)
    val useCustomSound by repo.flow(SettingsKeys.USE_CUSTOM_SOUND, false).collectAsState(initial = false)
    val customSoundUri by repo.flow(SettingsKeys.CUSTOM_SOUND_URI, "").collectAsState(initial = "")

    val soundPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
            } catch (_: SecurityException) {
                // Some providers don't support persistable permissions.
            }
            scope.launch { repo.set(SettingsKeys.CUSTOM_SOUND_URI, uri.toString()) }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sound") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SwitchRow("Bell sound on error", bellEnabled) {
                scope.launch { repo.set(SettingsKeys.BELL_ENABLED, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = !useCustomSound,
                    onClick = { scope.launch { repo.set(SettingsKeys.USE_CUSTOM_SOUND, false) } }
                )
                Text("Default notification sound")
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
            ) {
                RadioButton(
                    selected = useCustomSound,
                    onClick = { scope.launch { repo.set(SettingsKeys.USE_CUSTOM_SOUND, true) } }
                )
                Text("Custom sound file")
            }
            if (useCustomSound) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = { soundPicker.launch(arrayOf("audio/*")) }) {
                    Text(if (customSoundUri.isBlank()) "Choose sound file" else "Change sound file")
                }
            }
        }
    }
}
