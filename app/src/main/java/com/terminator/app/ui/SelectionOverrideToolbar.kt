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
package com.terminator.app.ui
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
/**
 * Why this is a Compose-drawn popup and not `android.view.ActionMode`
 * (the approach this file used before - see git history): on this app's
 * Compose Foundation version (1.12, `compose-bom:2026.08.00`),
 * SelectionContainer's default Copy/Paste bubble on selection-finalize
 * (finger up) is drawn directly by Compose and never reaches
 * `View.startActionMode()` at all - so an ActionMode-based intercept
 * (gating `View.startActionMode()`, or `Activity.
 * onWindowStartingActionMode()`) has nothing to catch on that path. The
 * only mechanism that's guaranteed to run for every selection change,
 * regardless of which internal path a given compose-foundation version
 * uses, is composition itself: [NoOpTextToolbarProvider] (below) blocks
 * Compose's own default menu at the source by intercepting
 * `LocalTextToolbar`, and this popup - driven straight off
 * `selectionState.selectedTexts`, not off any toolbar callback - is what
 * replaces it. That sidesteps the "which internal path does this
 * Foundation version use" question entirely: nothing native is ever
 * asked to show anything, so there's nothing to race or fall back to.
 *
 * ActionModeController keeps its original name/shape (`show`/`hide`,
 * same three-lambda signature) so MainActivity's call site - the
 * LaunchedEffect on selectionState.selectedTexts, and the onCopy/onPaste/
 * onMore bodies themselves - didn't need to change at all, only what
 * happens inside show()/hide().
 */
class ActionModeController {
    var isVisible by mutableStateOf(false)
        private set
    var onCopy: (() -> Unit)? = null
        private set
    var onPaste: (() -> Unit)? = null
        private set
    var onMore: (() -> Unit)? = null
        private set
    /** Shows (or refreshes) the Copy/Paste/More popup. onPaste/onMore
     *  nullable exactly as before - null hides that button rather than
     *  greying it out, so e.g. an empty clipboard shows Copy only. */
    fun show(onCopy: () -> Unit, onPaste: (() -> Unit)?, onMore: (() -> Unit)?) {
        android.util.Log.d("ToolbarDebug", "ActionModeController@${System.identityHashCode(this)}.show called")
        this.onCopy = onCopy
        this.onPaste = onPaste
        this.onMore = onMore
        isVisible = true
    }
    fun hide() {
        android.util.Log.d("ToolbarDebug", "ActionModeController@${System.identityHashCode(this)}.hide called")
        isVisible = false
        onCopy = null
        onPaste = null
        onMore = null
    }
}
@Composable
fun rememberActionModeController(): ActionModeController {
    return remember { ActionModeController() }
}
/** The actual Copy/Paste/More bar, replacing the native bubble. Renders
 *  near the top-center of whatever Box it's placed in (call site anchors
 *  it inside the same Box that hosts TerminalView, same as
 *  MoreActionsPopup below it) - selection is usually mid-screen on a
 *  terminal, so a fixed top-center placement reliably clears the
 *  selection itself and both edges, without needing the selection's own
 *  rect (which native ActionMode/TextToolbar positioning relied on and
 *  which doesn't reliably reach this app's SelectionContainer version -
 *  see this file's top doc). */
@Composable
fun SelectionActionBar(controller: ActionModeController) {
    if (!controller.isVisible) return
    val copy = controller.onCopy ?: return
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = false),
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1F1F1F),
            shadowElevation = 8.dp,
        ) {
            Row(modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)) {
                SelectionBarButton("Copy", onClick = copy)
                controller.onPaste?.let { paste ->
                    SelectionBarButton("Paste", onClick = paste)
                }
                controller.onMore?.let { more ->
                    SelectionBarButton("More", onClick = more)
                }
            }
        }
    }
}
@Composable
private fun SelectionBarButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = Color.White,
        style = MaterialTheme.typography.labelLarge,
        modifier = Modifier
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 16.dp, vertical = 10.dp)
    )
}
/**
 * Blocks Compose's own default Copy/Paste menu at the source, regardless
 * of which internal path (ActionMode or a Compose-drawn popup) a given
 * compose-foundation version routes it through - see this file's top doc
 * for why that internal path can't be reliably intercepted after the
 * fact. showMenu() simply never does anything, so SelectionContainer's
 * request to show a menu is acknowledged and dropped; SelectionActionBar
 * (driven off selectionState directly, not off this callback) is the
 * only selection UI that actually appears.
 *
 * status is always Hidden since we never let anything ask this toolbar to
 * show - SelectionActionBar/MoreActionsPopup are the only selection UI
 * that should ever appear, both driven manually via selectionState.
 */
private object NoOpTextToolbar : androidx.compose.ui.platform.TextToolbar {
    override val status: androidx.compose.ui.platform.TextToolbarStatus
        get() = androidx.compose.ui.platform.TextToolbarStatus.Hidden
    override fun showMenu(
        rect: androidx.compose.ui.geometry.Rect,
        onCopyRequested: (() -> Unit)?,
        onPasteRequested: (() -> Unit)?,
        onCutRequested: (() -> Unit)?,
        onSelectAllRequested: (() -> Unit)?
    ) {
        android.util.Log.d("ToolbarDebug", "NoOpTextToolbar.showMenu blocked (would have shown Compose's own menu)")
        // Intentionally empty - never show Compose's own menu.
    }
    override fun hide() {}
}
/**
 * Wrap TerminalView's SelectionContainer subtree with this so that
 * whichever internal path (ActionMode-based or Compose-popup-based) a
 * given compose-foundation version uses to show its default Copy/Paste
 * menu, it's suppressed at the composition level rather than relying
 * solely on the Activity-level ActionMode intercept. Use together with
 * MainActivity.onWindowStartingActionMode, not instead of it - some
 * compose-foundation versions still go through real ActionMode during an
 * active drag, and that path needs the Activity-level block too.
 */
@Composable
fun NoOpTextToolbarProvider(content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(
        androidx.compose.ui.platform.LocalTextToolbar provides NoOpTextToolbar,
        content = content
    )
}
/** Actions the "More" popup can trigger - MainActivity supplies real
 *  implementations for whichever running session the selection belongs to
 *  (the active pane, or the split partner - see MainActivity's call
 *  site). Save mirrors SessionDrawer's per-row Save icon exactly (same
 *  exportSessionOutput call), it's just reachable from the selection bar
 *  too now rather than only from the drawer. */
data class MoreMenuActions(
    val onCloneSession: () -> Unit,
    val onToggleWakeUp: () -> Unit,
    val wakeUpActive: Boolean,
    // Null hides the split-screen row entirely (Settings > Display >
    // "Split screen visibility" off) rather than showing a disabled row -
    // same "hidden not disabled" treatment used everywhere else a
    // setting gates one of these buttons.
    val onToggleSplitScreen: (() -> Unit)?,
    val splitScreenActive: Boolean,
    val onSave: () -> Unit
)
/**
 * The Compose popup rendered when SelectionActionBar's "More" button is
 * tapped: clone this session, toggle its wake-up lock, toggle split
 * screen with it, or save (export) its output. visible controls whether
 * the popup shows at all - MainActivity toggles it on the More button's
 * click and passes it back down here.
 */
@Composable
fun MoreActionsPopup(
    visible: Boolean,
    actions: MoreMenuActions,
    onDismiss: () -> Unit
) {
    if (!visible) return
    Popup(
        alignment = Alignment.TopCenter,
        properties = PopupProperties(focusable = true),
        onDismissRequest = onDismiss
    ) {
        Surface(
            shape = RoundedCornerShape(10.dp),
            color = Color(0xFF1F1F1F),
            shadowElevation = 8.dp,
            modifier = Modifier.width(220.dp)
        ) {
            androidx.compose.foundation.layout.Column(modifier = Modifier.padding(vertical = 4.dp)) {
                MoreMenuRow(
                    icon = Icons.Filled.Add,
                    label = "Clone session",
                    onClick = { actions.onCloneSession(); onDismiss() }
                )
                MoreMenuRow(
                    icon = if (actions.wakeUpActive) Icons.Filled.Star else Icons.Outlined.StarOutline,
                    label = if (actions.wakeUpActive) "Wake lock: on" else "Wake lock: off",
                    tint = if (actions.wakeUpActive) Color(0xFFEBCB8B) else Color.White,
                    onClick = { actions.onToggleWakeUp(); onDismiss() }
                )
                if (actions.onToggleSplitScreen != null) {
                    MoreMenuRow(
                        icon = Icons.Filled.VerticalSplit,
                        label = if (actions.splitScreenActive) "Close split screen" else "Open split screen",
                        tint = if (actions.splitScreenActive) MaterialTheme.colorScheme.primary else Color.White,
                        onClick = { actions.onToggleSplitScreen.invoke(); onDismiss() }
                    )
                }
                Divider(color = Color.White.copy(alpha = 0.08f))
                MoreMenuRow(
                    icon = Icons.Filled.Save,
                    label = "Save session output",
                    onClick = { actions.onSave(); onDismiss() }
                )
            }
        }
    }
}
@Composable
private fun MoreMenuRow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    tint: Color = Color.White,
    onClick: () -> Unit
) {
    androidx.compose.foundation.layout.Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.width(20.dp))
        androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(14.dp))
        Text(label, color = Color.White, style = MaterialTheme.typography.bodyMedium)
    }
}
