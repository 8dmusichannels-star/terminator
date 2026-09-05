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

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.SyncAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.AnnotatedString
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import androidx.compose.ui.unit.dp
import com.terminator.emulator.TerminalBuffer
import com.terminator.emulator.TerminalEmulator
import com.terminator.emulator.MouseGestureTracker
import com.terminator.emulator.ScrollFling
import com.terminator.emulator.TerminalPalette
import com.terminator.emulator.TerminalView

/**
 * Runs a two-finger pinch-to-zoom gesture for [SplitTerminalPane] to
 * completion, starting from the pointer event that first reported a second
 * finger down. Mirrors MainActivity's own primary-pane pinch branch
 * (distance-ratio zoom, 8f..40f clamp, 150ms debounced commit so a fast
 * pinch doesn't push a ViewModel write - and therefore a full-screen
 * recomposition, see MainActivity's own liveZoomSize doc - on every single
 * frame) but deliberately does NOT reproduce that branch's midpoint-row
 * scrollback-anchoring: MainActivity's version needs it because it also
 * resizes the live pty grid (updateTerminalSize) out from under the
 * fingers, whereas this pane's column/row count is driven entirely by
 * MainActivity's own onResizeSessionPty-less bufferVersion/onSizeChanged
 * path elsewhere - adding a second, independent pty-resize call from in
 * here would race that path rather than cooperate with it. Font size is
 * still fully live (effectivePaneFontSize) during the pinch, exactly like
 * the primary pane, just without also reflowing the pty mid-gesture.
 *
 * Left as a plain suspend function (not inlined into the two call sites
 * above) since the exact same sequence - read distance ratio, clamp,
 * publish liveZoomSize, debounce-commit - is needed from both the
 * long-press-vs-scroll decision loop (second finger arrives before slop is
 * exceeded) and the confirmed-scroll loop (second finger arrives after);
 * duplicating this inline at both would double the surface area for the
 * two loops to drift out of sync with each other.
 */
private suspend fun androidx.compose.ui.input.pointer.AwaitPointerEventScope.runSplitPinchZoom(
    initialEvent: androidx.compose.ui.input.pointer.PointerEvent,
    latestFontSize: androidx.compose.runtime.State<Float>,
    coroutineScope: kotlinx.coroutines.CoroutineScope,
    onLiveZoom: (Float?) -> Unit,
    onCommitZoom: ((Float) -> Unit)?,
    setZoomCommitJob: (Job?) -> Unit,
    cancelPendingZoomCommit: () -> Unit
) {
    // Runs directly on the AwaitPointerEventScope the caller (an
    // awaitEachGesture block) is already inside, rather than opening a
    // second, nested awaitPointerEventScope { } of its own. awaitEachGesture
    // provides a *restricted* suspend scope (RestrictsSuspension) - Kotlin
    // rejects a restricted-suspend receiver calling into a NEW instance of
    // that same restricted scope from inside itself ("Restricted suspending
    // functions can invoke member or extension suspending functions only on
    // their restricted coroutine scope"), which is exactly what this
    // function used to do by wrapping its whole body in its own
    // awaitPointerEventScope { }. Being an extension ON
    // AwaitPointerEventScope directly - with no such wrapper - means every
    // awaitPointerEvent() call below runs on the SAME restricted scope the
    // two call sites are already suspended within, which is allowed.
    var lastEvent = initialEvent
    // Nanos of the last REAL commit (onCommitZoom actually invoked), not
    // just the last liveZoomSize preview update - see throttleElapsed
    // below for why this exists. Local to one continuous 2-finger gesture,
    // same as lastEvent; a gesture that drops to one finger and pinches
    // again later starts a fresh call to this function with this reset to
    // 0, which just means that resumed gesture's first frame waits out the
    // normal debounce once more - a negligible edge case next to the
    // problem this fixes.
    var lastCommitNanos = 0L
    while (true) {
        val changes = lastEvent.changes.filter { it.pressed }
        if (changes.size < 2) break // back down to one finger (or zero) - pinch is over
        val p1 = changes.getOrNull(0)
        val p2 = changes.getOrNull(1)
        if (p1 != null && p2 != null) {
            val prevDist = (p1.previousPosition - p2.previousPosition).getDistance()
            val curDist = (p1.position - p2.position).getDistance()
            if (prevDist > 0f) {
                val zoom = curDist / prevDist
                if (zoom != 1f) {
                    val newSize = (latestFontSize.value * zoom).coerceIn(8f, 40f)
                    onLiveZoom(newSize)
                    cancelPendingZoomCommit()
                    // Same fix as MainActivity's own primary-pane pinch
                    // branch (see its inline doc): cancelling and
                    // restarting this 150ms debounce on EVERY pinch frame
                    // meant a slow, continuous pinch never let it reach
                    // zero until the gesture paused/ended, so onCommitZoom
                    // - which is what ultimately reflows this pane's
                    // column/row count - never fired for the whole
                    // gesture. Font size kept shrinking live while the
                    // grid stayed exactly as big as it was before the
                    // pinch started, leaving a growing unfilled (near-
                    // black) strip in this pane the entire time it was
                    // still being pinched. Throttling to a real commit at
                    // most every 150ms during an ongoing gesture (skip the
                    // wait once that long has actually elapsed since the
                    // last real commit) keeps it roughly in sync
                    // throughout; the trailing debounced commit (delay(150)
                    // below, still reset every frame) still guarantees the
                    // exact final size once the gesture settles.
                    val throttleElapsed = System.nanoTime() - lastCommitNanos >= 150_000_000L
                    setZoomCommitJob(
                        coroutineScope.launch {
                            if (!throttleElapsed) delay(150)
                            lastCommitNanos = System.nanoTime()
                            onCommitZoom?.invoke(newSize)
                            onLiveZoom(null)
                        }
                    )
                }
            }
        }
        val event = awaitPointerEvent()
        event.changes.forEach { it.consume() }
        lastEvent = event
    }
}

/**
 * The bar between the primary and secondary split panes. A thin drag
 * target reporting raw pixel deltas + the container's measured height, so
 * the caller (MainActivity) can convert to a ratio itself using the same
 * measured size it already tracks for the primary pane, rather than this
 * composable trying to independently measure or own the split geometry.
 *
 * Visually this is just a plain straight divider line - no filled block.
 * It used to render as a solid 20dp-tall Color(0xFF1A1A1A) rectangle plus a
 * separate little rounded "grip" pill centered on top of it, which read as
 * a chunky black bar sitting between the two panes rather than a clean
 * seam. The touch target still needs real height to stay comfortably
 * draggable with a finger (a true 1px-tall hit area would be unusable), so
 * the height is kept but made transparent - only a hairline Divider drawn
 * through its vertical center is actually visible, matching how a desktop
 * split-pane divider typically looks (a line you can grab, not a block).
 */
@Composable
fun SplitDragHandle(onDrag: (deltaPx: Float, containerHeightPx: Float) -> Unit) {
    var containerHeightPx by remember { mutableFloatStateOf(0f) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(20.dp)
            .pointerInput(Unit) {
                containerHeightPx = size.height.toFloat()
                detectVerticalDragGestures { change, dragAmount ->
                    change.consume()
                    // Use this pointerInput's own full-screen-ish parent height as
                    // the ratio denominator - approximate but stable, and avoids
                    // needing a second onSizeChanged just for the handle itself.
                    onDrag(dragAmount, containerHeightPx.takeIf { it > 0f } ?: 1000f)
                }
            },
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.material3.HorizontalDivider(
            modifier = Modifier.fillMaxWidth(),
            thickness = 1.dp,
            color = Color.White.copy(alpha = 0.35f)
        )
    }
}

/**
 * Split-screen's secondary pane: its own TerminalView bound to a different
 * running session's buffer, a small header (session label, broadcast-input
 * toggle, close button) and a plain text field for input - see this
 * file's header doc on why this is deliberately simpler than the primary
 * pane's gesture stack rather than sharing it.
 */
@Composable
fun SplitTerminalPane(
    modifier: Modifier = Modifier,
    runtimeId: String,
    buffer: TerminalBuffer?,
    bufferVersion: Int,
    palette: TerminalPalette,
    fontFamily: android.graphics.Typeface,
    fontSizeSp: Float,
    broadcastInput: Boolean,
    onToggleBroadcast: () -> Unit,
    onInput: (String) -> Unit,
    onClose: () -> Unit,
    // Reports this pane's own isFocused (below) up to MainActivity so it
    // can route VirtualKeyBar's key presses / keymaps / long-text page to
    // this pane's session instead of unconditionally sending them to the
    // primary pane - see MainActivity's splitPaneFocused doc. Fired
    // whenever isFocused changes, both true (tapped into this pane) and
    // false (would only happen if something else steals focus, which
    // today only the primary pane's own hidden field does via its own
    // callback). Defaults to a no-op so nothing else needs to change.
    onFocusChanged: (Boolean) -> Unit = {},
    // External "reclaim focus" trigger - same shape as MultiPaneContainer's
    // activationKey pattern, but driven from OUTSIDE this pane (MainActivity's
    // shared VirtualKeyBar's onTextEntryClosed) rather than by this pane's
    // own tap gesture. Swiping the key bar back from its long-text page while
    // this pane was the focused one used to always hand focus back to the
    // PRIMARY pane's hidden field (the only thing MainActivity's
    // onTextEntryClosed knew how to do), leaving this pane's own
    // HiddenPaneInputField with no input connection at all - the real IME
    // then closed itself with nothing focused to reopen it for. Bumping this
    // (any change from the caller's last value) re-triggers the same
    // focusToken bump / IME re-show a real re-tap into this pane would.
    focusRequestSignal: Int = 0,
    // Ground truth for "did the PRIMARY pane's own hidden field just take
    // real focus" (MainActivity's hiddenFieldFocused, mirrored down here) -
    // the counterpart onFocusChanged above never had: this pane's own
    // isFocused only ever got set to true (see the doc above it), never
    // back to false, because nothing told it the primary pane had taken
    // over. MainActivity's onFocusChanged{splitPaneFocused=...} callback
    // only updates MainActivity's own mirror of this state - it doesn't
    // reach back into this composable's local isFocused var. The result:
    // tapping the primary pane while this pane was focused correctly moved
    // VirtualKeyBar routing away (splitPaneFocused flipped false in
    // MainActivity) but this pane's own isFocused stayed stuck true, so
    // `active = isFocused && wantsKeyboard` never went false and
    // HiddenPaneInputField's hide() below never ran - the split pane's IME
    // stayed open over the primary pane ("hala aynı sorun var" in split
    // screen). Defaults to false so any other caller keeps today's behavior.
    primaryPaneFocused: Boolean = false,
    // Mouse reporting for THIS pane's own session (mc/vim/htop's own
    // xterm-mouse-mode programs) - see MainViewModel.sessionWantsMouseEvents/
    // sendMouseEventTo's docs for why these need to be per-runtimeId rather
    // than reusing the primary pane's activeSessionWantsMouseEvents/
    // sendMouseEvent (which only ever look at the primary session). Both
    // default to permanently-off no-ops so any other caller of this
    // composable keeps working exactly as before without wiring anything.
    wantsMouseEvents: () -> Boolean = { false },
    wantsMouseMoveEvents: () -> Boolean = { false },
    onMouseEvent: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit = { _, _, _, _ -> },
    // How many lines back into this pane's own scrollback it's currently
    // showing (0 = live tail) - caller-owned (MainViewModel.splitScrollOffset)
    // same as the primary pane's own scrollOffset, just tracked separately
    // per SplitTerminalPane's doc above. This used to be hardcoded to 0
    // inside this composable with no way to change it, which is why the
    // split pane could never scroll back at all - onScroll below is what
    // now drives it.
    scrollOffset: Int = 0,
    // Fired with a fractional line delta (pixels / this pane's own char
    // height, same unit MainActivity's own drag-scroll uses) whenever a
    // one-finger vertical drag on this pane isn't mouse-reporting input -
    // caller is expected to clamp/accumulate via
    // MainViewModel.adjustSplitScrollOffset, same division of
    // responsibility as onMouseEvent above.
    onScroll: (deltaLines: Float) -> Unit = {},
    // Edge-auto-scroll-while-selecting for this pane, same feature and same
    // reasoning as MainActivity's primary-pane pointerInput(PointerEventPass.
    // Initial) block: while paneSelectionState has an active selection and
    // the finger nears the top/bottom edge of this pane, keep revealing
    // scrollback so the selection can be extended into history. Split
    // previously had no such mechanism at all (see this param's absence
    // before), so a selection near the pane's edge just stopped there with
    // no way to grow it further than what was on-screen. Separate from
    // onScroll above (which drives *manual* one-finger drag-to-pan when no
    // selection is active) - kept as two callbacks rather than folding
    // auto-scroll into onScroll so the caller can flag the MainViewModel
    // call as isEdgeAutoScroll=true and avoid wiping the very selection
    // this is trying to extend (see MainViewModel.lastSplitScrollWasEdge-
    // AutoScroll's doc for why that flag exists).
    //
    // Returns the actual whole-line delta MainViewModel.
    // adjustSplitScrollOffset just applied to splitScrollOffset (same
    // contract as the primary pane's adjustScrollOffset) - this pane needs
    // that value back to call paneSelectionState.shiftRows()/recomputeFrom()
    // itself right below, exactly like MainActivity does for the primary
    // pane's own selectionState after every adjustScrollOffset call. Without
    // this, the pane's selection stayed "active" (not cleared, since
    // isEdgeAutoScroll=true already prevents that) but its anchor/focus rows
    // kept pointing at the same screen-relative row numbers while the text
    // underneath scrolled - the exact "selection slides / comes out partial"
    // symptom shiftRows exists to fix, just never wired up on this pane.
    onEdgeAutoScroll: (deltaLines: Float) -> Int = { 0 },
    // "More" popup actions for this pane's own selection toolbar (clone,
    // wake lock, split toggle, save). Null hides the More button entirely
    // (same treatment as the primary pane when activeSessionId is null) -
    // caller supplies this when a split session is active. MoreActionsPopup
    // is rendered right inside this composable so it's anchored to the
    // pane's own Box and inherits its own Z-order, rather than floating
    // somewhere under the primary pane's coordinate space.
    moreMenuActions: MoreMenuActions? = null,
    // Routes this pane's HiddenPaneInputField show()/hide() calls through
    // MainActivity's single insetsController + WindowInsetsAnimationCompat
    // ground-truth instead of HiddenPaneInputField deriving its own local
    // controller. Split screen only - see HiddenPaneInputField's own doc on
    // onRequestShow/onRequestHide for why. Null (the default) falls back to
    // that field's original self-contained behavior, so any other caller of
    // this composable keeps working unchanged.
    onImeRequestShow: (() -> Unit)? = null,
    onImeRequestHide: (() -> Unit)? = null,
    // Settings > Appearance > Pinch to zoom, same flag MultiPaneContainer
    // already threads down to its own tiles (see its zoomEnabled doc) -
    // this pane previously ignored the setting entirely because it had no
    // pinch-zoom gesture at all. Defaults to true so any other existing
    // caller of this composable keeps today's behavior (which was: no zoom)
    // with no wiring needed - the gesture itself is only added below, this
    // flag alone doesn't change anything for a caller that never supplies
    // onZoomTextSize.
    zoomEnabled: Boolean = true,
    // Fired (debounced, once per pinch - not per frame, see the
    // zoomCommitJob doc inline below) with this pane's newly-committed text
    // size whenever the user pinch-zooms THIS split pane specifically -
    // mirrors MainActivity's own viewModel.setSessionTextSize(activeSessionId,
    // newSize) call for the primary pane, just handed back up as a callback
    // since this composable has no ViewModel reference of its own. Caller
    // is expected to store it per-runtimeId (MainViewModel.sessionTextSizes
    // already is a runtimeId-keyed map, so the split pane's own runtimeId
    // slots into the exact same mechanism the primary pane uses) and feed
    // the result back in as this composable's fontSizeSp param. Null (the
    // default) disables committing anything - the gesture still runs and
    // resizes the pty live, but nothing persists past the pinch, same as
    // supplying zoomEnabled = false.
    onZoomTextSize: ((Float) -> Unit)? = null,
    // Settings > Soft keyboard toggle - same flag MainActivity's own primary
    // pane guards its tap-to-toggle-IME branch with (see that file's
    // `if (!moved && !stolenByTerminalView && softKeyboardEnabled)` call
    // site). This pane previously had no awareness of the setting at all,
    // so a tap always flipped wantsKeyboard/opened the IME regardless of
    // whether the user had turned the soft keyboard off - inconsistent with
    // the primary pane, which skips the whole toggle when the setting is
    // off. Defaults to true so any other existing caller of this composable
    // keeps today's behavior unchanged.
    softKeyboardEnabled: Boolean = true
) {
    // Direct-tap-to-type, no separate "Type here..." input box - tapping
    // the terminal area itself focuses it and brings up the keyboard, same
    // mechanism MultiPaneContainer's panes use (see HiddenPaneInputField's
    // doc) rather than the primary pane's own always-focused hidden field,
    // since this secondary pane isn't always the one the user is typing
    // into. isFocused starts true so the split partner is immediately
    // typable the moment it opens, without requiring an extra tap first.
    var isFocused by remember(runtimeId) { mutableStateOf(true) }
    // Bumped on every real tap into this pane (see the awaitEachGesture
    // block below), independent of isFocused's own true/false value.
    // isFocused starts true and, once flipped away by the primary pane,
    // never mirrors back - a `isFocused = true` write on re-tap is a
    // same-value write React/Compose treats as a no-op for anything keyed
    // on isFocused alone. HiddenPaneInputField's IME-show effect is keyed
    // on this token instead of on the raw boolean, so every real tap
    // reliably re-requests focus and re-shows the keyboard - without it,
    // tapping back into the split pane left the virtual key bar (which
    // gates on the real IME/WindowInsets state) never reappearing.
    var focusToken by remember(runtimeId) { mutableIntStateOf(0) }
    // Local, independent of isFocused (which only tracks whether THIS pane
    // owns input focus, not whether its keyboard should be up). Was missing
    // entirely: this pane's onTap only ever set isFocused = true and bumped
    // focusToken, with no way to ever ask HiddenPaneInputField to hide -
    // unlike MultiPaneContainer's tiles (see that file's own wantsKeyboard
    // doc for the identical bug there), this pane had NO tap-to-toggle-close
    // path at all, only ever tap-to-open. Starts true so the split partner
    // is still immediately typable the moment it opens, same as before this
    // fix (matches isFocused's own default above).
    var wantsKeyboard by remember(runtimeId) { mutableStateOf(true) }
    // Live per-frame IME-visible read - same pattern as MainActivity's own
    // primary-pane `keyboardOpen` (see its doc: assigned into a
    // mutableStateOf on every composition, not read as a plain val), NOT
    // rememberUpdatedState(val). The two look equivalent but aren't: a
    // plain val wrapped in rememberUpdatedState only refreshes when THIS
    // composable itself recomposes, and Compose has no obligation to
    // recompose this exact scope just because the inset changed - the
    // gesture loop below could keep reading a frozen snapshot indefinitely,
    // which reads as the toggle being permanently inverted rather than
    // merely occasionally stale. A mutableStateOf that's WRITTEN on every
    // composition (like MainActivity's keyboardOpen) doesn't have that gap.
    var keyboardOpenNow by remember(runtimeId) { mutableStateOf(false) }
    keyboardOpenNow = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Consumes focusRequestSignal (see its own doc) - any change from
    // MainActivity means "reclaim this pane's IME focus", identical to what
    // a real re-tap into this pane does via the gesture blocks below.
    // Skips signal 0 so the very first composition (default value) doesn't
    // fire this - only an actual caller-driven bump should.
    //
    // No artificial delay needed anymore. VirtualKeyBar's own
    // LaunchedEffect(textEntryOpen) now force-clears the outgoing long-text
    // field's Compose focus (focusManager.clearFocus(force = true))
    // synchronously, right when the swipe-back starts, before it ever fires
    // onTextEntryClosed() - so by the time this signal bumps, the old field
    // has already let go of focus regardless of where its 120ms exit
    // animation still is. Requesting focus here immediately no longer loses
    // the race the old comment described, and the real IME never sees a
    // focus-less gap in between to visibly close and reopen for - which is
    // what used to surface as the keyboard flickering shut on every
    // swipe-back in split screen.
    LaunchedEffect(focusRequestSignal) {
        if (focusRequestSignal != 0) {
            isFocused = true
            wantsKeyboard = true
            focusToken++
        }
    }
    // Forces HiddenPaneInputField's LaunchedEffect(activationKey) to
    // re-evaluate `active` (see its own doc above) when this setting flips
    // - activationKey here is plain `focusToken`, which nothing else bumps
    // on a settings change, so without this, turning "disable soft
    // keyboard" on while this pane's real IME was already showing left it
    // showing until the next unrelated focus/tap event happened to bump
    // focusToken. skipIfInitial-style guard isn't needed the way
    // focusRequestSignal's != 0 check is - a same-value initial composition
    // read is a no-op focusToken bump, not a spurious show/hide.
    LaunchedEffect(softKeyboardEnabled) { focusToken++ }
    // Mirrors isFocused up to MainActivity on every change, including the
    // initial true (this pane is typable immediately on open, same as
    // isFocused's own doc above) - not just the later awaitEachGesture
    // tap that flips it. See onFocusChanged's own doc for why this exists.
    LaunchedEffect(isFocused) { onFocusChanged(isFocused) }
    // The missing other half of that mirror - see primaryPaneFocused's own
    // doc above. Only reacts to the primary pane's field actually GAINING
    // focus (not losing it - the primary pane's own onFocusChanged only
    // ever needs to hand routing away on gain, and a bare "lost focus"
    // there doesn't mean this pane gained it). Guarded on isFocused already
    // being true so this doesn't fight a fresh re-tap into this same pane
    // that happens to land in the same frame primaryPaneFocused settles.
    LaunchedEffect(primaryPaneFocused) {
        if (primaryPaneFocused && isFocused) {
            isFocused = false
            focusToken++
        }
    }

    Box(modifier = modifier.background(Color.Black)) {
        androidx.compose.foundation.layout.Column(modifier = Modifier.fillMaxWidth().fillMaxHeight()) {
            // Header: session id (short), broadcast toggle, close.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xFF141414))
                    .padding(horizontal = 8.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.SpaceBetween
            ) {
                Text(
                    "Split pane",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (isFocused) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f)
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // Broadcast toggle lives here (not in Settings) because
                    // it's meaningful only while a split is actually open -
                    // see MainUiState.broadcastInput's doc. Highlighted
                    // when on so it's obvious typing now reaches both panes.
                    IconButton(onClick = onToggleBroadcast, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.SyncAlt,
                            contentDescription = if (broadcastInput) {
                                "Broadcast input: on - typing reaches both panes"
                            } else {
                                "Broadcast input: off - typing reaches only the primary pane"
                            },
                            tint = if (broadcastInput) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.5f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                    IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = "Close split",
                            tint = Color.White.copy(alpha = 0.7f),
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
            }

            // clipToBounds: see MultiPaneContainer's identical fix for the
            // full reasoning - TerminalView's selection-handle circles are
            // drawn slightly below their row and can extend past this
            // Box's bottom edge when the selection's end row is the last
            // visible one. Without clipping, that overdraw could bleed up
            // into this pane's own header Row just above ("UI dışına
            // sızıyor... split panel her modda kuşçuk").
            Box(modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds()) {
                if (buffer != null) {
                    // Own native selection state (see TerminalView's
                    // selectionState param doc) - independent of the
                    // primary pane's, long-press/drag selection works on
                    // this pane using the OS's own selection UI same as
                    // the primary pane, no separate toolbar wiring needed.
                    val paneSelectionState = com.terminator.emulator.rememberTerminalSelectionState()
                    LaunchedEffect(runtimeId) { paneSelectionState.clear() }
                    // No LaunchedEffect(scrollOffset) here (unlike an
                    // earlier version of this fix) - this pane's own drag/
                    // edge-auto-scroll gesture (the pointerInput block
                    // further down) already decides SYNCHRONOUSLY, in the
                    // same iteration that changes scrollOffset, whether to
                    // preserve the selection (paneSelectionState.selectedTexts
                    // .isNotEmpty() -> onEdgeAutoScroll + shiftRows/
                    // recomputeFrom) or clear it (the plain onScroll branch,
                    // see its own call site's doc) - see that block's own
                    // doc just below (search "Route through onEdgeAutoScroll").
                    // A LaunchedEffect reacting to scrollOffset AFTER the
                    // fact, gated on a separately-read flag, is exactly the
                    // pattern that raced other scroll callers and lost a
                    // selection mid pause-then-resume drag on the primary
                    // pane - not repeating that here now that this pane's
                    // gesture handles both outcomes itself, inline.
                    // This pane's own native Copy/Paste bubble
                    // (ActionModeController, same mechanism the primary
                    // pane uses - see SelectionOverrideToolbar.kt). This
                    // was previously completely missing here: TerminalView
                    // already suppresses its own default-fallback bubble
                    // via NoOpTextToolbar (so nothing shows on its own),
                    // but with no ActionModeController ever calling
                    // startActionMode for THIS pane's View either,
                    // selecting text in the split pane showed no toolbar
                    // at all here - which is what actually surfaced as
                    // "the split pane still shows Android's own toolbar,
                    // ours never takes over" (this pane's TerminalView
                    // sits under the SAME Activity Window as the primary
                    // pane, so with no override of its own the only
                    // ActionMode left able to fire for a selection made
                    // here was the platform's default one, not this
                    // pane's plain fallback state). No "More" item here -
                    // clone/wake-lock/split-toggle/save are all
                    // primary-pane-only concepts (see MoreMenuActions),
                    // so this pane only ever offers Copy/Paste.
                    val actionModeController = rememberActionModeController()
                    val clipboardManager = LocalClipboardManager.current
                    var moreVisible by remember(runtimeId) { mutableStateOf(false) }
                    // Keyed on the full selectedTexts list (same as TerminalView's own
                    // internal LaunchedEffect on line 325 of TerminalView.kt) rather than
                    // just isNotEmpty(). isNotEmpty() only triggers on false→true — if
                    // the list clears and refills (e.g. selection drag re-anchors) the
                    // LaunchedEffect key doesn't change and the toolbar never refreshes.
                    LaunchedEffect(paneSelectionState.selectedTexts.toList()) {
                        if (paneSelectionState.selectedTexts.isEmpty()) {
                            actionModeController.hide()
                        } else {
                            actionModeController.show(
                                onCopy = {
                                    val text = paneSelectionState.selectedTexts.joinToString("\n")
                                    if (text.isNotEmpty()) clipboardManager.setText(AnnotatedString(text))
                                    paneSelectionState.clear()
                                    actionModeController.hide()
                                },
                                onPaste = {
                                    // Always offered (button never hidden for an
                                    // empty clipboard) - read the clipboard at TAP
                                    // time, not at LaunchedEffect-fire time. See
                                    // MultiPaneContainer's identical fix/doc: the
                                    // old `clipboardManager.getText()?.text?.let
                                    // { pasted -> {...} }` pattern read the
                                    // clipboard once, synchronously, the instant
                                    // this LaunchedEffect fired (selection
                                    // changed) - an empty clipboard at that exact
                                    // moment made onPaste null and vanished the
                                    // Paste button from the bar for the rest of
                                    // that selection. Matches the primary pane's
                                    // own onPaste (MainActivity).
                                    clipboardManager.getText()?.text?.let { pasted ->
                                        if (pasted.isNotEmpty()) {
                                            // Same CR/LF fixup as the primary
                                            // pane's onPaste.
                                            onInput(pasted.replace('\n', '\r'))
                                        }
                                    }
                                    paneSelectionState.clear()
                                    actionModeController.hide()
                                },
                                onMore = moreMenuActions?.let { _ -> { moreVisible = true } }
                            )
                        }
                    }
                    // Pinch-to-zoom text size for THIS split pane specifically -
                    // same liveZoomSize/zoomCommitJob split as MainActivity's
                    // primary-pane pinch gesture (see its own doc): a pinch
                    // fires on essentially every frame while fingers are
                    // moving, so resizing straight through onZoomTextSize (a
                    // ViewModel write, which recomposes the whole screen) on
                    // every single frame would make this feel stuttery.
                    // liveZoomSize is purely local Compose state read only by
                    // this pane's own TerminalView; onZoomTextSize is only
                    // actually invoked once, 150ms after the pinch settles.
                    // Declared before the char-metrics measurement below since
                    // that measurement needs to react to the SAME live-zoomed
                    // size the TerminalView itself is showing, not just the
                    // caller's static fontSizeSp - otherwise mouse-reporting
                    // touch-to-cell math would use stale metrics for the
                    // duration of a pinch.
                    var liveZoomSize by remember(runtimeId) { mutableStateOf<Float?>(null) }
                    var zoomCommitJob by remember { mutableStateOf<Job?>(null) }
                    val effectivePaneFontSize = liveZoomSize ?: fontSizeSp
                    // Gesture below is keyed only on runtimeId (not fontSizeSp/
                    // effectivePaneFontSize) so it doesn't restart mid-pinch -
                    // same rememberUpdatedState pattern as MainActivity's
                    // latestEffectiveTextSize for the identical reason.
                    val latestEffectivePaneFontSize = androidx.compose.runtime.rememberUpdatedState(effectivePaneFontSize)
                    // Character cell size for this pane, used only to turn
                    // a raw touch position into a (col, row) pair for mouse
                    // reporting below - same measuringPaint approach
                    // TerminalView itself uses to line its own selection
                    // overlay up with the Canvas grid.
                    val density = LocalDensity.current
                    val (charWidthPx, charHeightPx) = remember(fontFamily, effectivePaneFontSize, density.density, density.fontScale) {
                        val measuringPaint = android.graphics.Paint().apply {
                            typeface = fontFamily
                            textSize = effectivePaneFontSize * density.density * density.fontScale
                        }
                        measuringPaint.measureText("M") to measuringPaint.fontSpacing
                    }
                    // Hoisted for the drag-scroll frame-coalescing job below -
                    // see that pointerInput block's own doc for why raw
                    // per-pointer-event onScroll() calls needed batching down
                    // to once per frame. rememberCoroutineScope() (not a bare
                    // launch{} inside the gesture block itself) matches this
                    // codebase's own existing pattern for a job started from
                    // inside a pointerInput/awaitEachGesture block - see
                    // MainActivity's identically-shaped resizeDebounceJob/
                    // zoomCommitJob, both driven off a coroutineScope hoisted
                    // the same way.
                    val paneCoroutineScope = androidx.compose.runtime.rememberCoroutineScope()
                    // Momentum + sharp edge-autoscroll for mouse-tracking
                    // gestures in this pane - same pair MainActivity's
                    // primary pane uses (see ScrollFling/EdgeWheelAutoScroll's
                    // own docs in MouseGestureTracker.kt). This pane's
                    // mouse-report block previously called
                    // runMouseReportGesture with neither wired up at all, so
                    // a fast scrollback drag against an mc/vim/htop session
                    // running in the split partner just stopped dead on
                    // release instead of coasting, and holding near the
                    // top/bottom edge while dragging did nothing.
                    val scrollFling = remember(runtimeId) { ScrollFling(paneCoroutineScope) }
                    val edgeWheelAutoScroll = remember(runtimeId) { MouseGestureTracker.EdgeWheelAutoScroll() }
                    // Last known pointer position for a real mouse - see
                    // MainActivity's own lastMousePosition doc for why a wheel
                    // notch (a delta, not a position) needs this.
                    var lastMousePosition by remember(runtimeId) { mutableStateOf(Offset.Zero) }
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            // focusToken only bumped on real taps (not long-press / drag
                            // / mouse events). awaitFirstDown fires on every touch-down
                            // including the very first frame of a long-press-to-select,
                            // so bumping focusToken there triggers HiddenPaneInputField's
                            // LaunchedEffect(activationKey) → insetsController.show(ime())
                            // mid-selection, which resizes the layout under the active
                            // SelectionContainer and collapses the selection before the
                            // toolbar can appear. A separate detectTapGestures block only
                            // fires onTap after the gesture is confirmed NOT to be a
                            // long-press or drag, so IME re-show never races with an
                            // ongoing selection.
                            // Edge-scroll while selecting - same feature and mechanism
                            // as MainActivity's primary-pane block: observe pointer
                            // position at PointerEventPass.Initial (before
                            // SelectionContainer or the gesture block below consume
                            // anything) and, while paneSelectionState has an active
                            // selection, keep revealing scrollback near the top/bottom
                            // edge so the selection can be extended into history.
                            // Never consumes - purely a side-effecting observer, same
                            // as the primary pane's version.
                            .pointerInput(runtimeId) {
                                val edgeFraction = 0.15f
                                val maxLinesPerFrame = 1.5f
                                awaitPointerEventScope {
                                    while (true) {
                                        val event = awaitPointerEvent(androidx.compose.ui.input.pointer.PointerEventPass.Initial)
                                        if (paneSelectionState.selectedTexts.isEmpty()) continue
                                        // Grace window right after a selection is (re)created -
                                        // see TerminalSelectionState.lastStartAtNanos' own doc
                                        // (same fix as the primary pane's identical block).
                                        val sinceStartMs = (System.nanoTime() - paneSelectionState.lastStartAtNanos) / 1_000_000L
                                        if (sinceStartMs < 200L) continue
                                        val pointer = event.changes.firstOrNull() ?: continue
                                        if (!pointer.pressed) continue
                                        val h = size.height.toFloat()
                                        if (h <= 0f) continue
                                        val y = pointer.position.y
                                        val edgePx = h * edgeFraction
                                        if (charHeightPx <= 0f) continue
                                        when {
                                            y < edgePx -> {
                                                val strength = ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                                                onEdgeAutoScroll(strength * maxLinesPerFrame)
                                            }
                                            y > h - edgePx -> {
                                                val strength = ((y - (h - edgePx)) / edgePx).coerceIn(0f, 1f)
                                                onEdgeAutoScroll(-strength * maxLinesPerFrame)
                                            }
                                        }
                                    }
                                }
                            }
                            .pointerInput(runtimeId) {
                                detectTapGestures(
                                    onTap = {
                                        // Focus tracking (isFocused/focusToken/
                                        // onFocusChanged) always runs on tap, regardless
                                        // of softKeyboardEnabled - this is what routes
                                        // VirtualKeyBar/keymapper input to the right
                                        // pane, and switching which pane is focused is
                                        // independent of whether the soft keyboard is
                                        // allowed to open. Only the IME toggle itself
                                        // (wantsKeyboard) is gated below - same split as
                                        // MainActivity's own primary-pane tap branch,
                                        // which still runs its non-keyboard bookkeeping
                                        // even when softKeyboardEnabled is off and only
                                        // skips the show/hide call.
                                        // Only actually TOGGLES when this pane was
                                        // already the focused one before this tap -
                                        // isFocused here is still the PRE-tap value
                                        // (the `isFocused = true` write is two lines
                                        // below, and unlike MultiPaneContainer's
                                        // per-tile isFocused - a plain captured
                                        // parameter, genuinely stale inside a
                                        // long-running pointerInput coroutine - this
                                        // pane's isFocused is a local mutableStateOf,
                                        // so reading it here always gets its true
                                        // current value, no mirroring needed).
                                        //
                                        // A tap switching focus IN from the primary
                                        // pane leaves wantsKeyboard untouched, so
                                        // this pane's own remembered open/closed
                                        // preference (from the last time IT was
                                        // focused) simply takes effect once `active =
                                        // isFocused && wantsKeyboard` recomposes true.
                                        //
                                        // Previously this read the GLOBAL
                                        // keyboardOpenNow (WindowInsets.ime - one
                                        // signal per WINDOW, shared with the primary
                                        // pane) for every tap, switch-in included:
                                        // switching in while the PRIMARY pane's
                                        // keyboard happened to be open forced this
                                        // pane's wantsKeyboard closed regardless of
                                        // what this pane's own preference was, and
                                        // vice versa - the actual "IME açılıyor kapalı
                                        // olsada" bug, not the separate focus-loss/
                                        // primaryPaneFocused issue already fixed.
                                        if (softKeyboardEnabled && isFocused) {
                                            wantsKeyboard = !keyboardOpenNow
                                        }
                                        isFocused = true
                                        focusToken++
                                        onFocusChanged(true)
                                    }
                                )
                            }
                            .pointerInput(runtimeId) {
                                // Hover-only MOVE reporting (xterm 1003/ANY_EVENT)
                                // for a real mouse with no button held - same as
                                // MainActivity's own hover block, previously
                                // missing here entirely.
                                with(MouseGestureTracker) {
                                    runMouseHoverGesture(
                                        wantsHover = wantsMouseMoveEvents,
                                        charSize = { charWidthPx to charHeightPx },
                                        bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                    ) { col, row ->
                                        lastMousePosition = Offset(col * charWidthPx, row * charHeightPx)
                                        onMouseEvent(TerminalEmulator.MouseEventKind.MOVE, col, row, 0)
                                    }
                                }
                            }
                            .pointerInput(runtimeId) {
                                // Physical mouse/trackpad scroll wheel - same
                                // gap and same fix as MainActivity's own
                                // primary-pane wheel block; see
                                // runMouseWheelGesture's doc for the full
                                // rationale.
                                with(MouseGestureTracker) {
                                    runMouseWheelGesture(
                                        wantsWheelReporting = wantsMouseEvents,
                                        emitWheelToApp = { kind, col, row -> onMouseEvent(kind, col, row, 0) },
                                        charSize = { charWidthPx to charHeightPx },
                                        bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                        lastPointerPosition = { lastMousePosition },
                                        emitScrollback = onScroll,
                                    )
                                }
                            }
                            .pointerInput(runtimeId) {
                                // Single gesture loop for this pane: when the
                                // session has enabled mouse reporting (DECSET
                                // 1000/1002/1003 - the same check the primary
                                // pane makes via wantsMouseEvents), press/
                                // drag/release become xterm mouse escape
                                // sequences instead of the plain tap-to-focus
                                // this pane previously only ever did. This is
                                // what makes ncurses programs (mc, vim,
                                // htop...) running in the split partner
                                // actually receive mouse input, matching the
                                // primary pane's own support instead of
                                // silently doing nothing here.
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    // isFocused announced here too (without bumping focusToken)
                                    // so mouse-reporting sessions that tap to start a drag still
                                    // route the PRESS event to the right session. focusToken is
                                    // intentionally NOT bumped here — see the detectTapGestures
                                    // block above for why (long-press must not trigger IME show).
                                    //
                                    // onFocusChanged(true) must ALSO fire here, not just isFocused
                                    // itself: this awaitEachGesture loop runs on every touch-down,
                                    // ahead of the sibling detectTapGestures block (which only
                                    // fires onTap once a gesture resolves as a real tap, i.e. after
                                    // finger-lift). Any press that resolves as a drag, long-press,
                                    // or gets consumed as a mouse-report event never reaches that
                                    // onTap at all - so if this block only wrote the local
                                    // `isFocused` var (a same-value no-op once it's already true),
                                    // MainActivity's splitPaneFocused never learned this pane was
                                    // focused. That's what left VirtualKeyBar routing CTRL/ALT and
                                    // regular key presses to the primary session while the split
                                    // pane looked focused on screen: onKeyPressed's own debug log
                                    // showed splitPaneFocused=false even after tapping the split
                                    // pane, because only a clean tap (not a press that becomes a
                                    // drag/selection/mouse-event) ever reached the other block.
                                    isFocused = true
                                    onFocusChanged(true)
                                    val mouseWanted = wantsMouseEvents()
                                    // New touch landing: abort any in-flight fling from a
                                    // previous release and start tracking velocity fresh,
                                    // same moment MainActivity's primary pane does it -
                                    // see ScrollFling.reset's own doc for why this has to
                                    // happen unconditionally on every down, not just once
                                    // mouseWanted is confirmed true below (a fling from a
                                    // PREVIOUS mouse-tracking gesture could still be
                                    // in-flight when this new gesture starts).
                                    scrollFling.reset()
                                    scrollFling.track(down.uptimeMillis, down.position)
                                    edgeWheelAutoScroll.reset()

                                    if (!mouseWanted || charWidthPx <= 0f || charHeightPx <= 0f) {
                                        // No mouse reporting active for this session.
                                        // Distinguish a vertical scrollback drag from a
                                        // long-press-to-select the same way the primary
                                        // pane's own gesture stack does (see MainActivity) -
                                        // and, critically, using the SAME two-phase
                                        // approach that fix required, not the naive one
                                        // this block used to use.
                                        //
                                        // This used to call awaitPointerEvent() itself in a
                                        // tight loop for the whole long-press window,
                                        // "just watching" without consuming. That still
                                        // starves TerminalView's own SelectionContainer:
                                        // its long-press-to-select detector runs as a
                                        // coroutine on this SAME pointerInput subtree, and
                                        // whichever coroutine calls awaitPointerEvent()
                                        // first on a given frame "wins" that shared input
                                        // queue - so as long as this loop kept polling
                                        // every frame, the child's long-press timer
                                        // effectively never got an uncontested window to
                                        // elapse in, and selection (and therefore the
                                        // Copy/Paste/More toolbar) never started at all.
                                        // MainActivity hit this exact bug on the primary
                                        // pane first (see its own gesture block's doc) -
                                        // this pane never got the same fix.
                                        //
                                        // The fix: don't call awaitPointerEvent() in a
                                        // competing loop while deciding what this gesture
                                        // is. Wait, without reading the pointer stream
                                        // ourselves, for up to the long-press timeout. If
                                        // the finger hasn't moved past touch slop by then,
                                        // this is a long-press: return immediately and let
                                        // SelectionContainer's own detector - which has now
                                        // had an uncontested window - claim it. Only start
                                        // this pane's own awaitPointerEvent() loop (and
                                        // only then start consuming) once real movement
                                        // past slop is confirmed, i.e. once we're certain
                                        // this is a scroll and not a selection gesture.
                                        val slop = viewConfiguration.touchSlop
                                        val deadline = System.nanoTime() + viewConfiguration.longPressTimeoutMillis * 1_000_000L
                                        var scrollStartEvent: androidx.compose.ui.input.pointer.PointerInputChange? = null
                                        while (true) {
                                            val remainingMillis = (deadline - System.nanoTime()) / 1_000_000L
                                            if (remainingMillis <= 0L) return@awaitEachGesture // long-press window elapsed untouched
                                            val event = withTimeoutOrNull(remainingMillis) { awaitPointerEvent() }
                                                ?: return@awaitEachGesture // timed out untouched
                                            // Second finger landed: a long-press-to-select can only
                                            // ever be a one-finger gesture, so as soon as a second
                                            // pointer is down this is unambiguously a pinch, not a
                                            // selection attempt - break out of the slop-vs-long-press
                                            // decision immediately instead of waiting out the rest of
                                            // the long-press timeout window for nothing. Mirrors
                                            // MainActivity's own primary-pane pinch branch, just
                                            // entered from this pane's own long-press/scroll decision
                                            // loop rather than after an already-confirmed scroll.
                                            if (zoomEnabled && event.changes.count { it.pressed } >= 2) {
                                                event.changes.forEach { it.consume() }
                                                runSplitPinchZoom(
                                                    initialEvent = event,
                                                    latestFontSize = latestEffectivePaneFontSize,
                                                    coroutineScope = paneCoroutineScope,
                                                    onLiveZoom = { liveZoomSize = it },
                                                    onCommitZoom = onZoomTextSize,
                                                    setZoomCommitJob = { zoomCommitJob = it },
                                                    cancelPendingZoomCommit = { zoomCommitJob?.cancel() }
                                                )
                                                return@awaitEachGesture
                                            }
                                            val change = event.changes.firstOrNull { it.id == down.id }
                                                ?: event.changes.firstOrNull()
                                                ?: return@awaitEachGesture
                                            if (change.isConsumed) return@awaitEachGesture
                                            if (!change.pressed) return@awaitEachGesture // lifted before slop: plain tap, not our concern
                                            if (kotlin.math.abs(change.position.y - down.position.y) > slop) {
                                                scrollStartEvent = change
                                                break
                                            }
                                            // Still within slop - keep waiting without
                                            // consuming, same as the primary pane's fix.
                                        }
                                        // Confirmed scroll: consume this and every
                                        // subsequent event ourselves for the rest of the
                                        // drag, driving scrollback the same as before.
                                        //
                                        // Coalesced to at most one onScroll() call per
                                        // rendered frame, not one per raw pointer event.
                                        // A fast drag can deliver several pointer-move
                                        // events between two actual UI frames - each one
                                        // used to call onScroll() straight through,
                                        // which (via adjustSplitScrollOffset ->
                                        // _uiState.copy()) triggered its own
                                        // recomposition and a full 80x24-cell Canvas
                                        // repaint (drawTerminal allocates a fresh Paint
                                        // per redraw - see that function's own doc) EACH
                                        // time, several of which the compositor then
                                        // only had one frame's worth of time to actually
                                        // show. Accumulating dy here and flushing it
                                        // once via withFrameNanos - which suspends until
                                        // the next frame is about to be drawn - collapses
                                        // however many raw events landed in between into
                                        // a single onScroll()/repaint per frame instead,
                                        // with the split pane's own second live session
                                        // (and its own independent bufferVersion-driven
                                        // repaints) competing for the same frame budget
                                        // being exactly why this showed up here first.
                                        // The total scrolled distance is identical either
                                        // way - only how often it's applied changes.
                                        var pendingDy = 0f
                                        var lastY = down.position.y
                                        scrollStartEvent?.let { change ->
                                            change.consume()
                                            pendingDy += change.position.y - lastY
                                            lastY = change.position.y
                                        }
                                        val frameJob = paneCoroutineScope.launch {
                                            while (true) {
                                                withFrameNanos {}
                                                if (pendingDy != 0f && charHeightPx > 0f) {
                                                    // Route through onEdgeAutoScroll (not onScroll)
                                                    // whenever a selection is already active - not
                                                    // just near the top/bottom edge, which is what
                                                    // that callback was previously reserved for (see
                                                    // its own doc higher up). A plain one-finger drag
                                                    // ANYWHERE in the pane should scroll scrollback
                                                    // while keeping the selection intact, same as
                                                    // MainActivity's primary-pane fix for this same
                                                    // gap. onScroll's own caller
                                                    // (adjustSplitScrollOffset with the default
                                                    // isEdgeAutoScroll=false) is what fed
                                                    // paneSelectionState.clear() on every single
                                                    // scroll tick via this pane's own
                                                    // LaunchedEffect(scrollOffset) guard - so
                                                    // scrolling while a selection was active looked
                                                    // like it did nothing, since the selection
                                                    // vanished the instant the drag started.
                                                    if (paneSelectionState.selectedTexts.isNotEmpty()) {
                                                        val applied = onEdgeAutoScroll(pendingDy / charHeightPx)
                                                        // Keep anchor/focus pointing at the same
                                                        // buffer content now that splitScrollOffset
                                                        // just moved under them - see shiftRows' own
                                                        // doc and onEdgeAutoScroll's doc above.
                                                        if (applied != 0) {
                                                            paneSelectionState.shiftRows(applied)
                                                            buffer?.let { buf ->
                                                                paneSelectionState.recomputeFrom(buf, scrollOffset)
                                                            }
                                                        }
                                                    } else {
                                                        onScroll(pendingDy / charHeightPx)
                                                        // No selection to preserve - clear
                                                        // defensively here rather than via a
                                                        // scrollOffset-keyed LaunchedEffect (see
                                                        // this pane's own doc above for why that
                                                        // separate-effect version raced other
                                                        // scroll callers).
                                                        paneSelectionState.clear()
                                                    }
                                                    pendingDy = 0f
                                                }
                                            }
                                        }
                                        try {
                                            while (true) {
                                                val event = awaitPointerEvent()
                                                // A second finger can also land mid-scroll (drag
                                                // started with one finger, a second touches down
                                                // before the first lifts) - hand off to the same
                                                // pinch-zoom path rather than continuing to treat
                                                // this as a one-finger scroll.
                                                if (zoomEnabled && event.changes.count { it.pressed } >= 2) {
                                                    event.changes.forEach { it.consume() }
                                                    runSplitPinchZoom(
                                                        initialEvent = event,
                                                        latestFontSize = latestEffectivePaneFontSize,
                                                        coroutineScope = paneCoroutineScope,
                                                        onLiveZoom = { liveZoomSize = it },
                                                        onCommitZoom = onZoomTextSize,
                                                        setZoomCommitJob = { zoomCommitJob = it },
                                                        cancelPendingZoomCommit = { zoomCommitJob?.cancel() }
                                                    )
                                                    break
                                                }
                                                val change = event.changes.firstOrNull { it.id == down.id }
                                                    ?: event.changes.firstOrNull()
                                                    ?: break
                                                change.consume()
                                                if (!change.pressed) break
                                                pendingDy += change.position.y - lastY
                                                lastY = change.position.y
                                            }
                                        } finally {
                                            // Flush whatever moved since the last frame
                                            // tick fired, so the drag's very last bit of
                                            // motion (between the final frame tick and
                                            // the finger lifting) isn't silently dropped.
                                            frameJob.cancel()
                                            if (pendingDy != 0f && charHeightPx > 0f) {
                                                if (paneSelectionState.selectedTexts.isNotEmpty()) {
                                                    val applied = onEdgeAutoScroll(pendingDy / charHeightPx)
                                                    if (applied != 0) {
                                                        paneSelectionState.shiftRows(applied)
                                                        buffer?.let { buf ->
                                                            paneSelectionState.recomputeFrom(buf, scrollOffset)
                                                        }
                                                    }
                                                } else {
                                                    onScroll(pendingDy / charHeightPx)
                                                    paneSelectionState.clear()
                                                }
                                                pendingDy = 0f
                                            }
                                        }
                                        return@awaitEachGesture
                                    }

                                    down.consume()
                                    // Sharpened via the shared MouseGestureTracker - see
                                    // MainActivity's own mouse-report block for what this
                                    // fixes over the old inline version (edge clamping,
                                    // historical-sample coalescing, real button id).
                                    var lastCol = 0
                                    var lastRow = 0
                                    with(MouseGestureTracker) {
                                        runMouseReportGesture(
                                            down = down,
                                            charSize = { charWidthPx to charHeightPx },
                                            bufferSize = { (buffer?.columns ?: 0) to (buffer?.rows ?: 0) },
                                            onMove = { uptimeMillis, position ->
                                                scrollFling.track(uptimeMillis, position)
                                            },
                                            edgeAutoScroll = { uptimeMillis, position, viewportHeightPx, ecol, erow ->
                                                if (viewportHeightPx > 0f) {
                                                    val edgeFraction = 0.12f
                                                    val edgePx = viewportHeightPx * edgeFraction
                                                    val y = position.y
                                                    when {
                                                        y < edgePx -> {
                                                            val strength = ((edgePx - y) / edgePx).coerceIn(0f, 1f)
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = strength,
                                                                towardScrollback = true,
                                                                col = ecol, row = erow,
                                                            ) { kind, c, r -> onMouseEvent(kind, c, r, 0) }
                                                        }
                                                        y > viewportHeightPx - edgePx -> {
                                                            val strength = ((y - (viewportHeightPx - edgePx)) / edgePx).coerceIn(0f, 1f)
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = strength,
                                                                towardScrollback = false,
                                                                col = ecol, row = erow,
                                                            ) { kind, c, r -> onMouseEvent(kind, c, r, 0) }
                                                        }
                                                        else -> {
                                                            // Outside both edge bands - disarm the dwell
                                                            // timer so re-entering either edge starts a
                                                            // fresh armDelayMillis wait.
                                                            edgeWheelAutoScroll.tick(
                                                                uptimeMillis = uptimeMillis,
                                                                strength = 0f,
                                                                towardScrollback = true,
                                                                col = ecol, row = erow,
                                                            ) { _, _, _ -> }
                                                        }
                                                    }
                                                }
                                            },
                                        ) { kind, col, row, button ->
                                            lastCol = col; lastRow = row
                                            onMouseEvent(kind, col, row, button)
                                        }
                                    }
                                    // Finger came up with residual velocity - keep
                                    // scrolling under momentum via xterm wheel notches,
                                    // same as MainActivity's primary pane (see
                                    // ScrollFling.releaseAsWheelEvents' own doc).
                                    scrollFling.releaseAsWheelEvents(
                                        charHeightPx = { charHeightPx },
                                        col = lastCol,
                                        row = lastRow,
                                    ) { kind, col, row ->
                                        onMouseEvent(kind, col, row, 0)
                                    }
                                }
                            }
                    ) {
                        TerminalView(
                            buffer = buffer,
                            palette = palette,
                            fontFamily = fontFamily,
                            fontSizeSp = effectivePaneFontSize,
                            // liveZoomSize != null: this pane is mid pinch-zoom, rendering
                            // at a live preview size not yet committed via buffer.resize() -
                            // see TerminalView's own suppressCursor doc / MainActivity's
                            // identical wiring for why the block cursor has to sit out
                            // these frames (otherwise it detaches from the real grid at
                            // the new scale - "imleç beyaz kalıyor" bug).
                            suppressCursor = liveZoomSize != null,
                            bufferVersion = bufferVersion,
                            backgroundAlpha = 1f,
                            scrollOffset = scrollOffset,
                            selectionState = paneSelectionState,
                            // Same Material primary @ ~25% alpha as the single-pane
                            // terminal (MainActivity) - keeps the selection highlight
                            // visually consistent across split panes regardless of
                            // each pane's own terminal color scheme.
                            highlightColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.25f).toArgb(),
                            handleColor = MaterialTheme.colorScheme.primary.toArgb(),
                            modifier = Modifier.fillMaxWidth().fillMaxHeight(),
                            debugLabel = "split"
                        )
                        HiddenPaneInputField(
                            // wantsKeyboard added so a tap toggle-close (see the
                            // detectTapGestures block above) can actually hide this
                            // pane's keyboard - active used to be isFocused alone,
                            // which can only ever go true->true on a re-tap into an
                            // already-focused pane and so never had a way to ask for
                            // hide. Same fix as MultiPaneContainer's own tiles.
                            //
                            // softKeyboardEnabled factored in here too - same missing
                            // piece as MultiPaneContainer's own wantsKeyboardOn (see
                            // its doc): MainActivity's own primary pane never even
                            // calls requestFocus()/show() when this setting is off,
                            // but this pane's `active` was just `isFocused &&
                            // wantsKeyboard` - wantsKeyboard defaults true and the
                            // only place that could ever set it false (the tap
                            // handler above) is itself gated behind
                            // softKeyboardEnabled, so it never ran while the setting
                            // was off and wantsKeyboard stayed permanently true. This
                            // pane's real IME kept popping up on focus regardless of
                            // "disable soft keyboard" being on.
                            active = isFocused && wantsKeyboard && softKeyboardEnabled,
                            activationKey = focusToken,
                            onText = { text ->
                                onInput(text)
                            },
                            onRequestShow = onImeRequestShow,
                            onRequestHide = onImeRequestHide
                        )
                        // actionModeController.show()/hide() above only
                        // ever flip isVisible - nothing was reading that
                        // state back out to actually draw the bar in
                        // this file, so the black Copy/Paste popup never
                        // appeared for this pane no matter how correctly
                        // the controller itself was being driven. Same
                        // composable/call shape as the primary pane's
                        // SelectionActionBar(actionModeController) call
                        // in MainActivity, just anchored inside this
                        // pane's own Box instead.
                        SelectionActionBar(
                            actionModeController,
                            // See MainActivity's identical call site: focusRow is
                            // always the actively-dragged handle, so it - not
                            // minOf(anchor, focus) - is what should drive the flip.
                            handleRow = paneSelectionState.focusRow,
                            // Same reasoning as MainActivity's call site: hide the
                            // bar while a handle is actually being dragged.
                            hideWhileDragging = paneSelectionState.draggingHandle,
                            // Real pixel bounds, same fix as MainActivity's call
                            // site - see HandleClearingPositionProvider's doc.
                            handleTopPx = paneSelectionState.focusRow * charHeightPx,
                            handleBottomPx = (paneSelectionState.focusRow + 1) * charHeightPx,
                            rowHeightPx = charHeightPx,
                        )
                        moreMenuActions?.let { actions ->
                            MoreActionsPopup(
                                visible = moreVisible,
                                actions = actions,
                                onDismiss = { moreVisible = false }
                            )
                        }
                    }
                } else {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        Text(
                            "Session ended",
                            color = Color.White.copy(alpha = 0.5f),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }
}
