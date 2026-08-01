package com.terminator.app.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

/**
 * Typical titlebar: hamburger (opens the session drawer, same as swipe
 * gesture) on the left, "TERMINATOR" centered, quick session-select + on
 * the right. Shown only when Settings > Display > Show Titlebar is enabled.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminatorTitleBar(
    onMenuClicked: () -> Unit,
    onQuickAddClicked: () -> Unit
) {
    TopAppBar(
        title = { Text("TERMINATOR") },
        navigationIcon = {
            IconButton(onClick = onMenuClicked) {
                Icon(Icons.Filled.Menu, contentDescription = "Open sessions")
            }
        },
        actions = {
            IconButton(onClick = onQuickAddClicked) {
                Icon(Icons.Filled.Add, contentDescription = "Quick session select")
            }
        },
        // Material3's default TopAppBar tints its container with a translucent
        // primary-color overlay ("tonal elevation") once the surface scrolls
        // under it - that's the unwanted blue wash at the top of the window.
        // Pin every state to flat black so the titlebar always matches the
        // rest of the flat-black theme.
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = Color.Black,
            scrolledContainerColor = Color.Black,
            titleContentColor = Color.White,
            navigationIconContentColor = Color.White,
            actionIconContentColor = Color.White
        )
    )
}
