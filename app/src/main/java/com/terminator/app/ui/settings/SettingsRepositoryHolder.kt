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
