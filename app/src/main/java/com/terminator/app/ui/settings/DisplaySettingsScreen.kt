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
 * Show/hide statusbar (single toggle, consistent in both portrait and
 * landscape - no separate horizontal-mode setting needed), show/hide
 * titlebar, and enable/disable horizontal (landscape) mode itself.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DisplaySettingsScreen(onBack: () -> Unit) {
    val repo = rememberSettingsRepository()
    val scope = rememberCoroutineScope()

    val showStatusbar by repo.flow(SettingsKeys.SHOW_STATUSBAR, false).collectAsState(initial = false)
    val showTitlebar by repo.flow(SettingsKeys.SHOW_TITLEBAR, true).collectAsState(initial = true)
    val horizontalModeEnabled by repo.flow(SettingsKeys.HORIZONTAL_MODE, true).collectAsState(initial = true)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Display") },
                navigationIcon = {
                    IconButton(onClick = onBack) { Icon(Icons.Filled.ArrowBack, contentDescription = "Back") }
                }
            )
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding).padding(16.dp).fillMaxSize()) {
            SwitchRow("Show statusbar", showStatusbar) {
                scope.launch { repo.set(SettingsKeys.SHOW_STATUSBAR, it) }
            }
            Text(
                "Applies consistently in both portrait and landscape.",
                style = MaterialTheme.typography.bodySmall
            )

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Show titlebar", showTitlebar) {
                scope.launch { repo.set(SettingsKeys.SHOW_TITLEBAR, it) }
            }

            Spacer(modifier = Modifier.height(16.dp))
            SwitchRow("Horizontal (landscape) mode", horizontalModeEnabled) {
                scope.launch { repo.set(SettingsKeys.HORIZONTAL_MODE, it) }
            }
        }
    }
}
