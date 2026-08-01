package com.terminator.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Appears above the terminal once a long-press has started a text
 * selection (see the gesture loop in MainActivity). Deliberately a plain
 * Compose row rather than the native Android ActionMode/floating toolbar -
 * the terminal is a single Canvas with no real text layout underneath it
 * for ActionMode to anchor to, so this is the equivalent affordance built
 * directly on top of the (row, col) selection range MainActivity already
 * tracks.
 */
@Composable
fun SelectionToolbar(
    onCopy: () -> Unit,
    onPaste: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF262626)),
    ) {
        ToolbarAction("Copy", onCopy)
        ToolbarAction("Paste", onPaste)
        ToolbarAction("Cancel", onCancel)
    }
}

@Composable
private fun ToolbarAction(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
