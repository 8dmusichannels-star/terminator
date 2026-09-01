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

package com.terminator.emulator

import androidx.compose.animation.core.AnimationState
import androidx.compose.animation.core.animateDecay
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.util.VelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Runs one xterm mouse-reporting gesture (press -> drag/move -> release)
 * against [emit], with the sharpening this needs to feel right under both
 * a finger and a real USB/BT mouse:
 *
 *  - col/row are clamped into the live buffer size before ever reaching
 *    [emit] - previously only [TerminalEmulator.encodeMouseEvent] clamped,
 *    so a press/drag near the right/bottom edge (or a drag that leaves the
 *    view entirely) could walk the tracked (col, row) state negative or
 *    past the last column/row, which then round-trips through unclamped
 *    on the next comparison.
 *  - every historical pointer sample within a frame is walked and encoded
 *    in order, not just the latest one - Compose can coalesce several
 *    physical samples into a single [PointerEvent] on a fast drag, and
 *    reading only `changes.first().position` silently drops the cells in
 *    between (a fast drag from col 5 to col 8 previously never reported
 *    entering 6 or 7).
 *  - real mouse button (left/middle/right) and the ANY_EVENT (1003) hover
 *    MOVE case are both reported, using [PointerType] and [PointerButtons]
 *    rather than hardcoding button 0 for every event the way the two call
 *    sites used to.
 *
 * [bufferSize] is read fresh on every callback rather than captured once,
 * since a resize (rotation, split-pane drag) can happen mid-gesture.
 *
 * [runMouseReportGesture] takes an [AwaitPointerEventScope] receiver, NOT
 * [PointerInputScope] - awaitPointerEvent only exists on the former (the
 * restricted-suspension scope handed to the block passed to
 * awaitPointerEventScope { } / awaitEachGesture { }), so it must be called
 * from inside an existing `awaitEachGesture { ... }` block. [runMouseHoverGesture]
 * instead takes a plain [PointerInputScope] receiver and opens its own
 * `awaitEachGesture` internally, since it runs as an independent gesture
 * loop rather than being driven by an existing one.
 */
object MouseGestureTracker {

    /** 0=left, 1=middle, 2=right - matches the `button` xterm expects in
     *  [TerminalEmulator.encodeMouseEvent]. */
    private fun buttonOf(event: PointerEvent): Int {
        val buttons = event.buttons
        return when {
            buttons.isSecondaryPressed -> 2
            buttons.isTertiaryPressed -> 1
            else -> 0 // left, or a touch pointer (no button bitmask at all)
        }
    }

    private fun cellOf(offset: Offset, charWidth: Float, charHeight: Float, cols: Int, rows: Int): Pair<Int, Int> {
        val c = (offset.x / charWidth).toInt().coerceIn(0, max(0, cols - 1))
        val r = (offset.y / charHeight).toInt().coerceIn(0, max(0, rows - 1))
        return c to r
    }

    /**
     * Drives the whole gesture. Call from inside an existing
     * `awaitEachGesture { ... }` block, after confirming the session wants
     * mouse events and consuming [down] - see the class doc above for why
     * the receiver has to be [AwaitPointerEventScope].
     *
     * [charSize] and [bufferSize] are lambdas (not plain values) so they're
     * re-read on every event instead of once at gesture start - a mid-drag
     * font-size change or pane resize would otherwise clamp against stale
     * dimensions for the rest of the gesture.
     *
     * [onMove] is fed every raw pixel sample alongside the col/row emits
     * above (down included) so a caller can drive its own [ScrollFling]
     * velocity tracking - Termux's onFling() keeps working even with mouse
     * tracking active (its doScroll() branches on isMouseTrackingActive()
     * internally), so releasing this gesture with residual velocity should
     * still be able to hand off to momentum, just reported as WHEEL_UP/
     * WHEEL_DOWN afterward instead of scrollOffset changes - see
     * ScrollFling.releaseAsWheelEvents.
     *
     * [edgeAutoScroll], if given, is invoked once per pointer-move frame
     * (after [onMove], before the per-cell DRAG walk) with that frame's raw
     * position and viewport height in px so the caller can drive an
     * [EdgeWheelAutoScroll] - a finger held down near the top/bottom edge
     * while mouse tracking is active gets fast, sharp WHEEL_UP/WHEEL_DOWN
     * autorepeat the same way the non-mouse-tracking edge-autoscroll blocks
     * already reveal scrollback near an edge, just as wheel notches instead
     * of direct scrollOffset changes since the terminal-side program owns
     * scrollback here. Left as a callback (not baked in here) so each call
     * site keeps deciding its own edge fraction/max-lines-per-frame the same
     * way the plain-scroll edge-autoscroll blocks already do inline.
     */
    suspend fun AwaitPointerEventScope.runMouseReportGesture(
        down: PointerInputChange,
        charSize: () -> Pair<Float, Float>,
        bufferSize: () -> Pair<Int, Int>,
        onMove: (uptimeMillis: Long, position: Offset) -> Unit = { _, _ -> },
        edgeAutoScroll: (uptimeMillis: Long, position: Offset, viewportHeightPx: Float, col: Int, row: Int) -> Unit = { _, _, _, _, _ -> },
        emit: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int, button: Int) -> Unit,
    ) {
        val (cw0, ch0) = charSize()
        if (cw0 <= 0f || ch0 <= 0f) return

        val (cols0, rows0) = bufferSize()
        var (col, row) = cellOf(down.position, cw0, ch0, cols0, rows0)
        var lastButton = 0
        emit(TerminalEmulator.MouseEventKind.PRESS, col, row, lastButton)

        while (true) {
            // Initial pass sees the raw event (with historical samples)
            // before later passes/other pointerInput blocks can consume
            // individual changes out of it.
            val event = awaitPointerEvent(PointerEventPass.Initial)
            val change = event.changes.firstOrNull() ?: break
            val (charWidth, charHeight) = charSize()
            val (cols, rows) = bufferSize()
            if (charWidth <= 0f || charHeight <= 0f) break

            lastButton = buttonOf(event)
            onMove(change.uptimeMillis, change.position)
            edgeAutoScroll(change.uptimeMillis, change.position, size.height.toFloat(), col, row)

            // Walk every historical sample plus the final position, in
            // order, so a fast drag can't skip cells between two frames.
            val positions = change.historical.map { it.position } + change.position
            for (pos in positions) {
                val (dCol, dRow) = cellOf(pos, charWidth, charHeight, cols, rows)
                if (dCol != col || dRow != row) {
                    col = dCol; row = dRow
                    emit(TerminalEmulator.MouseEventKind.DRAG, col, row, lastButton)
                }
            }

            // changedToUp() reads isConsumed internally (!isConsumed &&
            // previousPressed && !pressed) - it MUST be checked before
            // change.consume() runs, or consume() flips isConsumed and
            // changedToUp() then always reports false, silently eating
            // every RELEASE event and leaving the terminal-side program
            // thinking the button/finger never came up.
            val isUp = change.changedToUp()
            change.consume()

            if (isUp) {
                val (rCol, rRow) = cellOf(change.position, charWidth, charHeight, cols, rows)
                emit(TerminalEmulator.MouseEventKind.RELEASE, rCol, rRow, lastButton)
                break
            }
        }
    }

    /**
     * Reports hover MOVE events (xterm 1003/ANY_EVENT mode - used by
     * programs like htop/mc to highlight under the cursor with no button
     * held) for a real mouse. Only meaningful for [PointerType.Mouse]; a
     * finger has no concept of hovering, so touch never reaches this path.
     *
     * Unlike [runMouseReportGesture], this one owns its own gesture loop
     * (it calls [awaitEachGesture] itself) since hover-with-nothing-pressed
     * needs to keep restarting on every new "gesture" (pointer entering/
     * re-entering with nothing held) independent of whatever the
     * press/drag/release pointerInput block elsewhere is doing. Its
     * receiver is the plain [PointerInputScope] that
     * `Modifier.pointerInput { ... }` hands you - awaitEachGesture itself
     * is what steps down into [AwaitPointerEventScope] for the block body.
     */
    suspend fun PointerInputScope.runMouseHoverGesture(
        wantsHover: () -> Boolean,
        charSize: () -> Pair<Float, Float>,
        bufferSize: () -> Pair<Int, Int>,
        emit: (col: Int, row: Int) -> Unit,
    ) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
            if (down.type != PointerType.Mouse) return@awaitEachGesture
            var lastCol = -1
            var lastRow = -1
            while (true) {
                val event = awaitPointerEvent(PointerEventPass.Initial)
                val change = event.changes.firstOrNull() ?: break
                if (change.pressed || !wantsHover()) {
                    // A button went down mid-hover - the separate
                    // press/drag/release pointerInput block (which calls
                    // runMouseReportGesture) takes over from here.
                    break
                }
                val (charWidth, charHeight) = charSize()
                if (charWidth <= 0f || charHeight <= 0f) continue
                val (cols, rows) = bufferSize()
                val (col, row) = cellOf(change.position, charWidth, charHeight, cols, rows)
                if (col != lastCol || row != lastRow) {
                    lastCol = col; lastRow = row
                    emit(col, row)
                }
                if (change.changedToUp()) break
            }
        }
    }

    /**
     * Sharp, immediate edge-autoscroll for mouse-tracking sessions: unlike
     * [ScrollFling.releaseAsWheelEvents] (residual momentum after lift) or
     * the non-mouse-tracking edge-autoscroll blocks in MainActivity/
     * SplitTerminalPane/MultiPaneContainer (which move scrollOffset
     * directly, since those aren't talking to a mouse-reporting program),
     * this fires WHILE the finger is still down and near the top/bottom
     * edge, converting proximity directly into a steady stream of
     * WHEEL_UP/WHEEL_DOWN notches for as long as the finger stays in the
     * edge band - there's no decay curve here, this isn't momentum, it's a
     * held-position autorepeat the same way the plain-scroll edge-autoscroll
     * blocks already work, just emitted as wheel notches instead of
     * scrollOffset deltas because the terminal-side program owns scrollback
     * under mouse tracking.
     *
     * Call this once per pointer-move frame while mouse tracking is active
     * and the finger is down; it self-throttles via [minIntervalMillis] so a
     * 60fps+ pointer stream doesn't spam a notch per frame - real terminal
     * programs (mc/vim/htop) can only usefully consume a few scroll steps
     * per second before the eye can't track the movement anyway, so this
     * caps at a fast-but-readable cadence rather than trying to match
     * exactly one notch per animation frame the way
     * [ScrollFling.releaseAsWheelEvents]'s decay curve does.
     *
     * [strength] is 0..1, same convention as the other edge-autoscroll
     * blocks' own `strength` (0 at the edge boundary, 1 right at the
     * physical screen edge) - higher strength shortens the interval between
     * notches for a sharp acceleration curve near the very edge, rather than
     * firing at a flat rate regardless of how deep into the band the finger
     * is.
     *
     * Two more guards beyond the interval throttle, both aimed at the same
     * "glitch" failure mode a naive per-frame emit has - a burst of notches
     * landing on the PTY faster than the terminal-side program's own redraw
     * can keep up with reads as scroll tearing/flicker rather than a clean
     * scroll:
     *
     *  - [armDelayMillis]: a finger merely passing through the edge band for
     *    a frame or two (overshoot on a fast drag, a quick direction
     *    correction) shouldn't fire anything at all. [tick] requires the
     *    finger to have been continuously in the SAME edge (same
     *    [towardScrollback]) for this long before the very first notch of a
     *    dwell fires - same grace-window idea as this codebase's existing
     *    selection-drag edge-autoscroll (its own 200ms lastStartAtNanos
     *    check), just applied to "entered the edge band" instead of
     *    "selection just started".
     *  - direction-change reset: if the finger crosses from the top band to
     *    the bottom band (or leaves and re-enters) [reset]-equivalent
     *    rearming happens automatically via the internal edge-identity
     *    check in [tick], so a rapid top/bottom bounce can't keep the old
     *    throttle window alive and fire a notch in the NEW direction
     *    immediately off the old timer.
     */
    class EdgeWheelAutoScroll {
        private var lastEmitMillis = 0L
        private var armedForUp: Boolean? = null // null = not currently dwelling in any edge
        private var armStartMillis = 0L

        /** Call every frame the finger sits in the edge band ([strength] >
         *  0); call with strength == 0 (or don't call at all) the frame the
         *  finger leaves the band, which disarms the dwell timer so the next
         *  entry starts a fresh [armDelayMillis] wait rather than resuming a
         *  stale one. */
        fun tick(
            uptimeMillis: Long,
            strength: Float,
            towardScrollback: Boolean,
            minIntervalMillis: Long = 260L,
            maxIntervalMillis: Long = 90L,
            armDelayMillis: Long = 90L,
            col: Int,
            row: Int,
            emit: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit,
        ) {
            if (strength <= 0f) {
                armedForUp = null
                return
            }

            if (armedForUp != towardScrollback) {
                // Just entered this edge (or switched from the other one) -
                // start the dwell timer instead of emitting immediately.
                armedForUp = towardScrollback
                armStartMillis = uptimeMillis
                return
            }
            if (uptimeMillis - armStartMillis < armDelayMillis) return

            val interval = (minIntervalMillis - ((minIntervalMillis - maxIntervalMillis) * strength.coerceIn(0f, 1f))).toLong()
            if (uptimeMillis - lastEmitMillis < interval) return
            lastEmitMillis = uptimeMillis
            // Reversed to match ScrollFling.releaseAsWheelEvents' own flip -
            // both edge-hold and fling-release now use the same
            // physical-mouse-wheel sense instead of the touch-natural one.
            val kind = if (towardScrollback) TerminalEmulator.MouseEventKind.WHEEL_DOWN else TerminalEmulator.MouseEventKind.WHEEL_UP
            emit(kind, col, row)
        }

        /** Reset the throttle and dwell state - call on a fresh gesture
         *  down, mirroring [ScrollFling.reset]. */
        fun reset() {
            lastEmitMillis = 0L
            armedForUp = null
        }
    }
}

/**
 * Compose-native counterpart to what Termux's `TerminalView.onFling()` does
 * with a plain Android [android.widget.Scroller]: when a one/two-finger
 * scrollback drag is released while still moving, keep scrolling under
 * decaying momentum instead of stopping dead the instant the finger lifts.
 *
 * The gesture code in MainActivity/SplitTerminalPane applies each frame's
 * pixel delta to scrollOffset immediately while the finger is DOWN - that
 * part already matches Termux's own onScroll() 1:1 finger tracking. What
 * was missing is everything Termux's onFling() does once the finger comes
 * UP with residual velocity: this object is that missing half, not a
 * replacement for the drag-tracking that already exists.
 *
 * Usage: feed every move sample to [track] while the finger is down (same
 * values already being used to drive scrollOffset - no extra bookkeeping),
 * then call [release] once on lift. [release] launches its own coroutine in
 * [scope] and returns immediately; it self-cancels if a new gesture starts
 * (call [cancel] from the next `awaitFirstDown`), exactly like Termux's
 * `mScroller.abortAnimation()` when a new touch interrupts an in-flight
 * fling.
 */
class ScrollFling(private val scope: CoroutineScope) {

    private val velocityTracker = VelocityTracker()
    private var flingJob: Job? = null

    /** Feed one move sample (screen px, not lines) while the finger is down. */
    fun track(uptimeMillis: Long, position: Offset) {
        velocityTracker.addPosition(uptimeMillis, position)
    }

    /** Call once per gesture, right before the first [track] call. */
    fun reset() {
        velocityTracker.resetTracking()
        cancel()
    }

    /** Abort any in-progress fling - call when a new gesture starts touching
     *  down, the same moment Termux calls `mScroller.abortAnimation()`. */
    fun cancel() {
        flingJob?.cancel()
        flingJob = null
    }

    /**
     * Finger lifted. If there's residual vertical velocity, spend it down
     * via an exponential-decay animation (the same decay curve
     * `android.widget.OverScroller`/`Scroller` uses internally), calling
     * [applyDeltaLines] every animation frame with that frame's slice of
     * movement converted to terminal rows - mirroring how Termux's
     * onFling() runnable calls `doScroll()` every frame with `newY - mLastY`.
     *
     * [charHeightPx] converts the decaying px/s velocity into rows the same
     * way [applyDeltaLines] callers already convert drag delta (dy /
     * charHeight) - kept as a lambda, not a captured value, so a mid-fling
     * font size change (same reasoning as MouseGestureTracker's charSize
     * lambdas above) doesn't clamp against a stale cell height.
     *
     * [minVelocityPxPerSec] is a small deadzone (Android's
     * ViewConfiguration.getScaledMinimumFlingVelocity() equivalent) so an
     * essentially-stationary lift doesn't spawn a fling that immediately
     * decays to a single stray row nudge.
     */
    fun release(
        charHeightPx: () -> Float,
        minVelocityPxPerSec: Float = 50f,
        applyDeltaLines: (Float) -> Unit,
    ) {
        val velocity = velocityTracker.calculateVelocity().y
        cancel()
        if (abs(velocity) < minVelocityPxPerSec) return

        flingJob = scope.launch {
            var lastValue = 0f
            AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(
                exponentialDecay(frictionMultiplier = 1.5f)
            ) {
                val ch = charHeightPx()
                if (ch <= 0f) {
                    // Can't convert px -> rows right now (view mid-layout) -
                    // stop this frame's contribution but let the animation
                    // keep running; the next frame retries with fresh
                    // metrics rather than the whole fling silently dying.
                    return@animateDecay
                }
                val deltaPx = this.value - lastValue
                lastValue = this.value
                // Sign convention matches the drag path: Termux/our own
                // onScroll feeds `dy / charHeight` straight into
                // adjustScrollOffset, so the fling frame delta does the same.
                applyDeltaLines(deltaPx / ch)
            }
        }
    }

    /**
     * Same idea as [release], but for when mouse reporting is active: the
     * terminal-side program owns scrollback, not us, so residual velocity
     * has to keep arriving as xterm wheel notches (button 64/65) instead of
     * moving scrollOffset directly. This is the direct counterpart of what
     * Termux's doScroll() does when `mEmulator.isMouseTrackingActive()` is
     * true inside its onFling() runnable - one WHEELUP/WHEELDOWN
     * sendMouseEventCode() call per row of decayed movement, same coordinate
     * (mMouseScrollStartX/Y) held fixed for the whole fling the way Termux's
     * mMouseStartDownTime check does, rather than following the now-absent
     * finger position.
     *
     * [col]/[row] are captured once at release time (the last known finger
     * position) and reused for every notch - there's no finger position to
     * re-sample once it's lifted, and xterm doesn't expect wheel coordinates
     * to move superimposed on their own momentum anyway.
     *
     * Two glitch guards on top of the plain decay-to-notches conversion:
     *
     *  - [minNotchIntervalMillis] rate-limits actual [emit] calls
     *    independent of the animation frame rate. animateDecay ticks at the
     *    display's frame rate (90/120Hz on many phones), and early in a
     *    fast fling `combined` can be several rows per frame - without a
     *    wall-clock floor between notches, a burst of WHEEL_UP/WHEEL_DOWN
     *    lands on the PTY faster than mc/vim/htop's own redraw can keep up
     *    with, which is what reads as scroll "glitching"/tearing rather
     *    than a smooth coast. Any rows that arrive faster than this floor
     *    are folded into [rowCarry] instead of dropped, so the fling still
     *    covers the same total distance, just paced out.
     *  - [maxNotchesPerTick] caps how many notches a single callback can
     *    burst-emit even once the interval floor above allows one through -
     *    guards the case where the app was backgrounded/GC-paused mid-fling
     *    and animateDecay's next tick reports a huge `deltaPx` all at once;
     *    the remainder is folded back into [rowCarry] and drains over
     *    subsequent ticks instead of slamming the terminal in one frame.
     */
    fun releaseAsWheelEvents(
        charHeightPx: () -> Float,
        col: Int,
        row: Int,
        minVelocityPxPerSec: Float = 50f,
        minNotchIntervalMillis: Long = 16L,
        maxNotchesPerTick: Int = 3,
        emit: (kind: TerminalEmulator.MouseEventKind, col: Int, row: Int) -> Unit,
    ) {
        val velocity = velocityTracker.calculateVelocity().y
        cancel()
        if (abs(velocity) < minVelocityPxPerSec) return

        flingJob = scope.launch {
            var lastValue = 0f
            // Carries the fractional row remainder across frames the same
            // way MainViewModel.adjustScrollOffset's own scrollFractionCarry
            // does for the non-mouse-tracking path - a wheel notch is a
            // whole-row event, there's no fractional xterm wheel message,
            // so a slow decay tail can't just emit fractional notches the
            // way applyDeltaLines(Float) does for scrollOffset. Also
            // absorbs whatever the two guards above hold back from this
            // tick, so no distance is ever lost - only re-paced.
            var rowCarry = 0f
            var lastEmitMillis = 0L
            AnimationState(initialValue = 0f, initialVelocity = velocity).animateDecay(
                exponentialDecay(frictionMultiplier = 1.5f)
            ) {
                val ch = charHeightPx()
                if (ch <= 0f) return@animateDecay
                val deltaPx = this.value - lastValue
                lastValue = this.value
                val combined = deltaPx / ch + rowCarry
                val wholeRows = combined.toInt()
                rowCarry = combined - wholeRows
                if (wholeRows == 0) return@animateDecay

                val nowMillis = System.currentTimeMillis()
                if (nowMillis - lastEmitMillis < minNotchIntervalMillis) {
                    // Too soon since the last notch - park these rows in
                    // rowCarry (added back on top of int truncation loss)
                    // rather than firing now; a later tick that clears the
                    // interval floor will pick them up.
                    rowCarry += wholeRows
                    return@animateDecay
                }

                // Reversed from the "natural/touch scroll" mapping this
                // function used before: deltaPx negative (finger moving up
                // the screen at release) now sends WHEEL_DOWN instead of
                // WHEEL_UP, and vice versa - i.e. this now follows the
                // physical-mouse-wheel convention (xterm notch 64 = "wheel
                // rolled away from the user" = scroll up) rather than the
                // touchscreen-fling convention. Flip this back if the touch
                // feel is wrong again - see the sign check just below.
                val kind = if (wholeRows < 0) TerminalEmulator.MouseEventKind.WHEEL_DOWN else TerminalEmulator.MouseEventKind.WHEEL_UP
                val notches = min(abs(wholeRows), maxNotchesPerTick)
                repeat(notches) { emit(kind, col, row) }
                lastEmitMillis = nowMillis
                // Whatever this tick couldn't send (rate-capped by
                // maxNotchesPerTick) goes back into rowCarry so the fling's
                // total travel is preserved, just spread over more ticks.
                val leftover = abs(wholeRows) - notches
                if (leftover > 0) rowCarry += if (wholeRows < 0) -leftover else leftover
            }
        }
    }

}
