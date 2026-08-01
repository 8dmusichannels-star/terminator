package com.terminator.app.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.terminator.app.settings.SettingsKeys
import kotlinx.coroutines.launch

/**
 * Soft keyboard / virtual key bar toggles, Input Mode (Termux-style 3-way
 * choice for IME compatibility), keyboard shortcuts + keymapper (custom
 * key-combo -> action mapping), and the SECCOMP workaround toggle for
 * "Operation not permitted" errors caused by kernel syscall filters.
 *
 * "Soft keyboard" here just enables/disables the tap-to-toggle behavior on
 * the main terminal screen - the actual show/hide happens per-tap there.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KeyboardSettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val softKeyboard by repo.flow(SettingsKeys.SOFT_KEYBOARD, true).collectAsState(initial = true)
    val virtualKeys by repo.flow(SettingsKeys.VIRTUAL_KEYS, true).collectAsState(initial = true)
    val inputMode by repo.flow(SettingsKeys.INPUT_MODE, "Default").collectAsState(initial = "Default")
    val seccompEnabled by repo.flow(SettingsKeys.SECCOMP_ENABLED, false).collectAsState(initial = false)
    var showKeymapper by remember { mutableStateOf(false) }

    if (showKeymapper) {
        KeymapperScreen(onBack = { showKeymapper = false })
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Keyboard") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SwitchRow("Soft keyboard (tap terminal to open/close)", softKeyboard) {
                scope.launch { repo.set(SettingsKeys.SOFT_KEYBOARD, it) }
            }
            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Virtual keys (key bar)", virtualKeys) {
                scope.launch { repo.set(SettingsKeys.VIRTUAL_KEYS, it) }
            }

            Spacer(modifier = Modifier.height(24.dp))
            Text("Input Mode", style = MaterialTheme.typography.labelLarge)

            InputModeOption(
                title = "Default (Recommended)",
                description = "Compatible with all IMEs including CJK input",
                selected = inputMode == "Default"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Default") } }

            InputModeOption(
                title = "Strict Terminal",
                description = "Semantically correct but may break some IMEs",
                selected = inputMode == "Strict"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Strict") } }

            InputModeOption(
                title = "Legacy Workaround",
                description = "Fixes Samsung keyboard echo; may break Gboard CJK input",
                selected = inputMode == "Legacy"
            ) { scope.launch { repo.set(SettingsKeys.INPUT_MODE, "Legacy") } }

            Spacer(modifier = Modifier.height(24.dp))
            OutlinedButton(onClick = { showKeymapper = true }) {
                Text("Keyboard shortcuts & keymapper")
            }

            Spacer(modifier = Modifier.height(24.dp))
            SwitchRow("SECCOMP", seccompEnabled) {
                scope.launch { repo.set(SettingsKeys.SECCOMP_ENABLED, it) }
            }
            Text(
                "Fix \"operation not permitted\" error",
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

@Composable
private fun InputModeOption(
    title: String,
    description: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(description, style = MaterialTheme.typography.bodySmall)
        }
    }
}
