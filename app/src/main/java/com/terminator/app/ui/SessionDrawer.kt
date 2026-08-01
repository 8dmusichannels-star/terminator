package com.terminator.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterExitState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.terminator.app.session.SessionEntry
import kotlin.math.roundToInt

/**
 * Left session drawer: shown via the titlebar hamburger. Slides in/out with
 * a subtle flat scrim behind it. Closeable by:
 *  - tapping the scrim (anywhere outside the drawer itself), or
 *  - dragging the drawer to the left (swipe-to-dismiss).
 * The titlebar's + button spawns a new running copy of the active session;
 * every such copy shows up here under "Running", separate from the saved
 * session profiles below, so switching between several open instances of
 * the same session (or back to a plain profile to start a new one) both
 * work from the same list. Favorited profiles are surfaced first among the
 * profiles. Long-press a profile row to set it as default.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun SessionDrawer(
    visible: Boolean,
    sessions: List<SessionEntry>,
    runningSessions: List<RunningSession> = emptyList(),
    activeSessionId: String? = null,
    onSessionSelected: (SessionEntry) -> Unit,
    onRunningSessionSelected: (String) -> Unit = {},
    onKillRunningSession: (String) -> Unit = {},
    onSettingsClicked: () -> Unit,
    onToggleFavorite: (SessionEntry) -> Unit,
    onSetDefault: (SessionEntry) -> Unit,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn(tween(220)),
        exit = fadeOut(tween(220))
    ) {
        // Both the scrim fade (above) and the drawer's horizontal slide
        // (below) are driven off THIS SAME transition clock, instead of a
        // second nested AnimatedVisibility with its own timeline. Two
        // independently-triggered transitions on the same `visible` state is
        // what made the open/close look like it animated twice; deriving the
        // slide fraction from `transition` keeps everything as one motion.
        val slideOutFraction by transition.animateFloat(
            transitionSpec = { tween(220) },
            label = "drawerSlide"
        ) { state -> if (state == EnterExitState.Visible) 0f else 1f }

        // Scrim: fills the screen behind the drawer. Tapping anywhere on it
        // closes the drawer - the close gesture is deliberately NOT confined
        // to the drawer's own rows.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.45f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest
                )
        ) {
            var dragOffset by remember { mutableStateOf(0f) }
            val drawerWidth = 300.dp
            val drawerWidthPx = with(LocalDensity.current) { drawerWidth.toPx() }

            Column(
                modifier = modifier
                    .align(Alignment.CenterStart)
                    .fillMaxHeight()
                    .width(drawerWidth)
                    .offset {
                        IntOffset(
                            x = ((-slideOutFraction * drawerWidthPx) + dragOffset.coerceAtMost(0f)).roundToInt(),
                            y = 0
                        )
                    }
                    .background(Color.Black)
                    // Swallow taps so they don't fall through to the scrim.
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                if (dragOffset < -80f) {
                                    onDismissRequest()
                                }
                                dragOffset = 0f
                            },
                            onHorizontalDrag = { _, delta ->
                                dragOffset = (dragOffset + delta).coerceAtMost(0f)
                            }
                        )
                    }
            ) {
                LazyColumn(modifier = Modifier.weight(1f)) {
                    if (runningSessions.isNotEmpty()) {
                        item(key = "running-header") {
                            Text(
                                "RUNNING",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                        items(
                            items = runningSessions,
                            key = { "running-${it.runtimeId}" }
                        ) { running ->
                            RunningSessionRow(
                                running = running,
                                isActive = running.runtimeId == activeSessionId,
                                onClick = { onRunningSessionSelected(running.runtimeId) },
                                onKillClick = { onKillRunningSession(running.runtimeId) }
                            )
                        }
                        item(key = "running-divider") {
                            HorizontalDivider(color = Color.White.copy(alpha = 0.08f))
                            Text(
                                "SESSIONS",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color.White.copy(alpha = 0.5f),
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                            )
                        }
                    }
                    items(
                        items = sessions.sortedByDescending { it.isFavorite },
                        key = { it.id }
                    ) { session ->
                        // Re-look up the entry by id from the *current* sessions list on every
                        // recomposition instead of trusting the `session` this lambda captured
                        // when the item was first placed. LazyColumn keeps a composable slot
                        // alive across recompositions via `key`, but since this list is
                        // re-sorted (sortedByDescending isFavorite) on every call, the slot's
                        // captured `session` could still reflect the pre-toggle snapshot for a
                        // frame - which is what made the star icon look like it wasn't
                        // updating/disappearing even though the DataStore write had already
                        // completed underneath it.
                        val current = sessions.firstOrNull { it.id == session.id } ?: session
                        SessionRow(
                            session = current,
                            onClick = { onSessionSelected(current) },
                            onLongClick = { onSetDefault(current) },
                            onToggleFavorite = { onToggleFavorite(current) }
                        )
                    }
                }

                HorizontalDivider()

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(onClick = onSettingsClicked)
                        .padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Filled.Settings, contentDescription = null)
                    Spacer(modifier = Modifier.width(12.dp))
                    Text("Settings", style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
    }
}

@Composable
private fun RunningSessionRow(
    running: RunningSession,
    isActive: Boolean,
    onClick: () -> Unit,
    onKillClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isActive) Color.White.copy(alpha = 0.08f) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                running.label,
                style = MaterialTheme.typography.bodyLarge,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.White
            )
            // Shown once the process is confirmed gone (natural exit,
            // SIGTERM, or the trash-can/Ctrl+D SIGKILL below) - the row
            // stays visible instead of vanishing, so it's clear the
            // process is dead rather than just idle.
            if (running.exited) {
                Text(
                    "Session exited",
                    style = MaterialTheme.typography.labelSmall,
                    color = Color(0xFFBF616A)
                )
            }
        }
        // Kill button: always available here, independent of Ctrl+D inside
        // the terminal - the two methods the spec asked for. Both send an
        // unconditional SIGKILL.
        IconButton(onClick = onKillClick) {
            Icon(
                Icons.Filled.Delete,
                contentDescription = "Kill session",
                tint = Color(0xFFBF616A)
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionRow(
    session: SessionEntry,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    onToggleFavorite: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // combinedClickable lives ONLY on this column now, not on the
        // whole row. It was on the outer Row before, which meant its
        // press-gesture handling covered the star IconButton's area too -
        // combinedClickable's long-press detection swallows the initial
        // press before the nested IconButton's own clickable ever sees it,
        // so taps on the star did nothing. Scoping it to just the
        // name/default text leaves the star fully outside that gesture
        // area, so it gets presses normally again.
        Column(
            modifier = Modifier
                .weight(1f)
                .combinedClickable(onClick = onClick, onLongClick = onLongClick)
        ) {
            Text(session.name, style = MaterialTheme.typography.bodyLarge)
            if (session.isDefault) {
                Text(
                    "Default",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
        IconButton(onClick = onToggleFavorite) {
            Icon(
                Icons.Filled.Star,
                contentDescription = "Toggle favorite",
                tint = if (session.isFavorite) {
                    MaterialTheme.colorScheme.primary
                } else {
                    Color.White.copy(alpha = 0.35f)
                }
            )
        }
    }
}
