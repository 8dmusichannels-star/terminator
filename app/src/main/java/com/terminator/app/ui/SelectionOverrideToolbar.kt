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
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.VerticalSplit
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupPositionProvider
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
        this.onCopy = onCopy
        this.onPaste = onPaste
        this.onMore = onMore
        isVisible = true
    }
    fun hide() {
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
 *  see this file's top doc).
 *
 *  That "usually mid-screen" assumption breaks when the relevant selection
 *  handle sits in the first few rows of the visible viewport (very common -
 *  selecting the first line or two of the current screen, or of a short
 *  command's output) - a fixed TopCenter popup then renders directly on
 *  top of the drag handle marking that edge, so the bar visually covers
 *  the very handle a user would drag to extend the selection ("Copy Paste
 *  More menusu bazen selection kucuk isaretinin onune geciyor"). [handleRow]
 *  - the row of whichever handle is actually being dragged right now,
 *  screen-relative (see TerminalSelectionState.focusRow's own doc: focus
 *  is always the actively-moved end, anchor is re-pinned to the OTHER,
 *  fixed end the instant a handle-drag starts - so focusRow, not
 *  min(anchorRow, focusRow), is the row that matters here) - lets this bar
 *  flip to BottomCenter whenever that handle is too close to the top for
 *  TopCenter to clear it, and back to TopCenter once the dragged handle
 *  moves away from the top again (including down toward the bottom of the
 *  viewport), the same top-vs-bottom flip Android's native floating
 *  selection toolbar does. Using min(anchor, focus) here instead of the
 *  live handle would get this backwards mid-drag: e.g. selecting downward
 *  from a top-anchored start, the anchor stays pinned near row 0 for the
 *  whole drag even once the actively-dragged focus handle has moved well
 *  down the screen, which kept forcing BottomCenter - directly under the
 *  handle the user is dragging - for as long as any part of the selection
 *  touched the top few rows ("asagiya kaydirirken kucuk bazen gozukmuyor").
 *  Defaults to Int.MAX_VALUE (always "far from the top") so any call site
 *  that doesn't pass it keeps today's TopCenter-always behavior unchanged.
 *
 *  Visual shape/color follow the system's own floating selection toolbar
 *  (the dark full-capsule pill with a trailing overflow dot-icon,
 *  Material-You-tinted) rather than the earlier flat rounded-rect bar -
 *  Copy/Paste/More stay exactly the same three actions/callbacks, this is
 *  purely the container's shape and color scheme. MoreActionsPopup (the
 *  menu that opens off the More button) is intentionally untouched - only
 *  this bar's own look changed.
 *
 *  [hideWhileDragging] - pass TerminalSelectionState.draggingHandle here -
 *  temporarily skips rendering the whole popup for as long as a handle is
 *  actually being held/dragged. Without this, the bar's TopCenter/
 *  BottomCenter flip (driven by [handleRow], live off the dragged handle)
 *  recomposes the popup at a new position on every row the finger crosses,
 *  which is itself another way the bar can end up sitting directly under a
 *  handle mid-drag for a frame or two even with the flip logic correct -
 *  "toolbar'in gecici olarak kaybolmasi" while a handle drag is in
 *  progress avoids that entirely, and the controller's own isVisible is
 *  left untouched so the bar reappears at its (by-then-settled) position
 *  the instant endHandleDrag() fires, with no separate show()/hide() call
 *  needed here.
 *
 *  [handleTopPx]/[handleBottomPx] - the actively-dragged handle's own
 *  screen-relative pixel bounds (top/bottom of its glyph + touch padding,
 *  in the SAME pixel space as the Box this popup is placed in), and
 *  [rowHeightPx] - one character row's height in that same space. Replaces
 *  the old Top/Bottom-flip-only positioning (which only ever snapped the
 *  bar to the very top or very bottom of the whole pane, regardless of
 *  where on-screen the handle actually was) with a position computed
 *  directly off the handle's real pixel rect: the bar is placed
 *  [rowHeightPx] above handleTopPx whenever that clears the top of the
 *  viewport, and [rowHeightPx] below handleBottomPx otherwise. Either way
 *  the bar's own rect never overlaps [handleTopPx, handleBottomPx] - "kuscuk
 *  hep ustune spawn olsun, kuscuk onun icine girmesine izin verme": the
 *  handle never ends up underneath/inside the bar and the bar never spawns
 *  inside the handle's own touch target, regardless of the mid-screen
 *  Top-vs-Bottom case the old fixed-alignment version got right only by
 *  accident (selection happened to be far enough from both edges).
 *  Defaults (Int.MAX_VALUE for both, matching the old handleRow default)
 *  fall back to the previous behavior for any call site that hasn't been
 *  updated to pass real pixel bounds yet - see SelectionOverrideToolbarLegacyRow-
 *  BasedPositionProvider below. */
@Composable
fun SelectionActionBar(
    controller: ActionModeController,
    handleRow: Int = Int.MAX_VALUE,
    hideWhileDragging: Boolean = false,
    handleTopPx: Float = Float.NaN,
    handleBottomPx: Float = Float.NaN,
    rowHeightPx: Float = Float.NaN,
) {
    if (!controller.isVisible) return
    if (hideWhileDragging) return
    val copy = controller.onCopy ?: return
    val hasPixelBounds = !handleTopPx.isNaN() && !handleBottomPx.isNaN() && !rowHeightPx.isNaN() && rowHeightPx > 0f
    // Rows are small (a handle glyph plus touch padding is roughly this
    // tall in character-grid terms on a typical terminal font size) - a
    // dragged handle within this many rows of the viewport's top edge is
    // close enough that the TopCenter popup below would overlap it. Only
    // used as the fallback path when hasPixelBounds is false.
    val nearTopRowThreshold = 2
    val barContent: @Composable () -> Unit = {
        Surface(
            // Full capsule, not a fixed corner radius - matches the
            // system toolbar's pill shape at any bar height, the same way
            // the reference screenshot's own pill scales with its content.
            shape = CircleShape,
            // Material You's own tonal surface rather than a hardcoded
            // dark gray, so this bar tints along with the rest of the
            // app's dynamic color instead of looking like a fixed asset
            // dropped on top of it - surfaceContainerHigh sits close to
            // the reference's dark neutral pill while still tracking the
            // active wallpaper-derived palette.
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shadowElevation = 6.dp,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .height(IntrinsicSize.Min)
                    .padding(vertical = 4.dp)
            ) {
                SelectionBarButton("Copy", onClick = copy)
                controller.onPaste?.let { paste ->
                    SelectionBarButton("Paste", onClick = paste)
                }
                controller.onMore?.let { more ->
                    // Trailing hairline divider before the overflow icon,
                    // same as the reference toolbar's separator between
                    // its label and its dot-menu - only drawn when More
                    // is actually present, so a Copy-only bar (no
                    // paste/more) stays a plain pill with no dangling
                    // divider.
                    Divider(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f),
                        modifier = Modifier
                            .fillMaxHeight()
                            .width(1.dp)
                            .padding(vertical = 8.dp)
                    )
                    IconButton(onClick = more, modifier = Modifier.size(40.dp)) {
                        Icon(
                            Icons.Filled.MoreVert,
                            contentDescription = "More",
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
    // Two separate Popup() call sites rather than one shared call fed a
    // nullable popupPositionProvider: Popup's alignment-based overload and
    // its popupPositionProvider-based overload are mutually exclusive
    // (passing both is not a supported combination), so branching has to
    // happen at the call site itself, not by conditionally building one
    // provider and always passing it through the same parameter.
    if (hasPixelBounds) {
        Popup(
            popupPositionProvider = remember(handleTopPx, handleBottomPx, rowHeightPx) {
                HandleClearingPositionProvider(handleTopPx, handleBottomPx, rowHeightPx)
            },
            properties = PopupProperties(focusable = false),
            content = barContent,
        )
    } else {
        // Same window-clamp reasoning as MoreActionsPopup's own switch
        // away from the alignment overload (see WindowClampedPositionProvider's
        // doc) - this branch only
        // runs when a caller doesn't pass pixel bounds (none of
        // MainActivity/SplitTerminalPane/MultiPaneContainer's call sites
        // currently hit it, they all pass handleTopPx/handleBottomPx/
        // rowHeightPx), but a future caller that doesn't wire those up
        // shouldn't regress back to the same off-screen-overflow bug this
        // whole fix is for. handleRow's Top/Bottom choice is preserved by
        // picking which anchor edge (top vs bottom) the provider centers
        // against before the window-clamp is applied.
        Popup(
            popupPositionProvider = remember(handleRow) {
                if (handleRow <= nearTopRowThreshold) {
                    WindowClampedPositionProvider(Alignment.BottomCenter)
                } else {
                    WindowClampedPositionProvider(Alignment.TopCenter)
                }
            },
            properties = PopupProperties(focusable = false),
            content = barContent,
        )
    }
}

/**
 * Positions the bar directly off the actively-dragged handle's own pixel
 * rect ([handleTopPx]..[handleBottomPx], screen-relative in the same space
 * as the anchor Box) instead of snapping to a fixed Top/Bottom alignment of
 * the whole pane - see SelectionActionBar's own doc for why the old
 * row-threshold flip could still let the bar spawn overlapping the handle
 * anywhere the selection wasn't near either screen edge.
 *
 * This is a two-sided barrier, not a one-shot "try above, else below":
 * [handleTopWindow, handleBottomWindow] (plus [margin] on each side) is
 * treated as a hard exclusion zone the bar's own rect may never enter,
 * full stop - preferred placement is above the handle (mirrors the old
 * TopCenter default), but if placing it there would push the bar's TOP
 * edge above the visible window (clipping off-screen against the actual
 * top barrier), it flips below instead. Critically, the final vertical
 * clamp against the window bounds is done SEPARATELY per branch - clamped
 * toward the window edge the bar is already sitting against, never back
 * across the handle - so a bar that doesn't fully fit above (or below)
 * gets pushed further in that same direction rather than snapping back
 * onto/into the handle's own rect. That's what makes this an actual
 * barrier instead of the old single coerceIn(minY, maxY), which could
 * clamp a "not enough room below" result straight back up into the
 * handle whenever handleBottomWindow + margin + popupHeight overflowed
 * the window.
 */
private class HandleClearingPositionProvider(
    private val handleTopPx: Float,
    private val handleBottomPx: Float,
    private val rowHeightPx: Float,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        // anchorBounds is the anchor Box's own rect in window coordinates;
        // handleTopPx/handleBottomPx are supplied relative to that same
        // Box, so add anchorBounds.top to land in window space.
        val handleTopWindow = anchorBounds.top + handleTopPx
        val handleBottomWindow = anchorBounds.top + handleBottomPx
        val margin = (rowHeightPx * 0.5f).coerceAtLeast(4f)
        val popupHeight = popupContentSize.height.toFloat()
        // Preferred: bar's bottom edge sits `margin` above the handle's
        // top barrier. If the bar's own top edge would then land above
        // anchorBounds.top (off the visible window - the ONLY thing that
        // can invalidate the "above" placement), flip below instead.
        val aboveTopEdge = handleTopWindow - margin - popupHeight
        val y = if (aboveTopEdge >= anchorBounds.top) {
            // Fits above cleanly.
            aboveTopEdge
        } else {
            // Doesn't clear the top barrier - place below the handle's
            // bottom barrier instead. If the bar also doesn't fully fit
            // below (a very short pane), it still starts exactly at the
            // barrier - handleBottomWindow + margin - rather than
            // snapping back up across it, so the handle stays outside
            // the bar's rect either way even if the bar itself ends up
            // partially clipped by the window edge (unavoidable once
            // neither side has room, but the exclusion zone itself is
            // never violated).
            handleBottomWindow + margin
        }
        // Horizontal center: since only the handle's vertical rect is
        // passed in (handles are effectively a point on the X axis - a
        // caret/teardrop glyph - the anchor Box's own horizontal center is
        // the closest stable reference without threading handle X through
        // too), center the bar in the anchor Box horizontally, same as the
        // old TopCenter/BottomCenter alignment did.
        val boxCenterX = anchorBounds.left + (anchorBounds.right - anchorBounds.left) / 2
        // coerceIn(min, max) throws if min > max, which the naive
        // anchorBounds.right - popupContentSize.width bound can be
        // whenever the bar is wider than the anchor Box itself (a narrow
        // split pane, a small multi-pane tile) - clamp the upper bound up
        // to at least the lower bound first so this never crashes on a
        // narrow pane.
        val minX = anchorBounds.left
        val maxX = maxOf(minX, anchorBounds.right - popupContentSize.width)
        var x = (boxCenterX - popupContentSize.width / 2).coerceIn(minX, maxX)
        // Second clamp pass, against windowSize rather than anchorBounds -
        // "Terminal ekranin disina cikmiycak". The anchorBounds-only clamp
        // above is enough on the primary pane (its own Box already spans
        // the full window width), but on a narrow split/multi-pane tile
        // anchorBounds itself can be narrower than the popup content
        // (a Copy+Paste+More pill doesn't shrink to fit a small tile), so
        // minX/maxX above can both sit past the tile's own edge while
        // still being nowhere near the window's actual edge - the bar
        // then renders centered on the tile but overflowing past the
        // window boundary on one or both sides, since Compose's Popup does
        // not itself clip content to the window. Re-clamping x against
        // [0, windowSize.width - popupContentSize.width] after the
        // anchor-relative pass guarantees the bar's own rect never exits
        // the actual screen, regardless of how narrow the anchoring pane
        // is - same "clamp toward the edge it's already against, don't
        // undo the barrier flip" spirit as the y computation above, just
        // for the window boundary instead of the handle exclusion zone.
        val minWindowX = 0
        val maxWindowX = maxOf(minWindowX, windowSize.width - popupContentSize.width)
        x = x.coerceIn(minWindowX, maxWindowX)
        // Deliberately NOT a single coerceIn(anchorBounds.top,
        // anchorBounds.bottom - popupHeight) here - that would clamp the
        // "below" branch's y back UP whenever handleBottomWindow + margin
        // + popupHeight overflows the window bottom, landing the bar back
        // on/inside the handle it just flipped below to avoid ("ust
        // barrier'a takilirsa asagiya dusmeli" - the barrier flip has to
        // survive the final clamp, not get undone by it). Each branch
        // instead only clamps toward the window edge it's already
        // sitting against - see the two y-computation branches above -
        // so the worst case on a too-short pane is the bar clipping
        // against that edge while still staying fully outside the
        // handle's own rect, never sliding back across it.
        //
        // Final window-bound clamp on y, same reasoning as the x pass
        // above: the handle-relative branches only ever reason about the
        // handle's own barrier and anchorBounds.top, never about the
        // window's bottom edge or (on a pane that isn't flush against the
        // window top, e.g. a floating multi-pane tile) the window's top
        // edge either. Clamping the chosen y into
        // [0, anchorBounds.bottom - popupHeight] after the fact keeps the
        // bar from clipping off the top of the window, or spilling past
        // the bottom of its own anchor Box, without touching which branch
        // (above/below the handle) was picked.
        // Was windowSize.height here, same as the x-pass above - but unlike x,
        // the y bottom bound can't just be "the real screen edge": VirtualKeyBar
        // (and, above it, KeymapperRow) render as a sibling BELOW this popup's
        // anchor Box, inside the same outer Column, so anchorBounds.bottom
        // already sits exactly on top of them - VirtualKeyBar/KeymapperRow are
        // simply outside anchorBounds entirely. windowSize.height, however, is
        // the full window/screen height and includes that space underneath
        // anchorBounds, so clamping against it let the "below the handle"
        // branch (handleBottomWindow + margin) drop the bar down past
        // anchorBounds.bottom and straight into VirtualKeyBar's own rows
        // ("Copy Paste More menusu bazen virtual key bar'in ustune biniyor").
        // anchorBounds.bottom is the correct barrier here, not the window edge.
        val minWindowY = 0f
        val maxWindowY = maxOf(minWindowY, anchorBounds.bottom - popupHeight)
        val clampedY = y.coerceIn(minWindowY, maxWindowY)
        return IntOffset(x, clampedY.toInt())
    }
}
/**
 * Same "top-center of the anchor, but never past the actual window edge"
 * placement MoreActionsPopup always used (previously via the plain
 * alignment = Alignment.TopCenter Popup overload, which centers against
 * the anchor Box only and has no window-clamp hook at all) - see that
 * call site's own doc for why a fixed-width popup centered on a narrow
 * split/multi-pane tile can overflow past the real screen edge. Unlike
 * HandleClearingPositionProvider above, this has no handle-exclusion zone
 * to honor - so vertical placement is just the anchor's own top or bottom
 * edge (matching whichever Alignment the caller would otherwise have
 * passed straight to Popup's alignment overload), clamped into the window
 * the same way HandleClearingPositionProvider's own final y-clamp works,
 * and horizontal is always the anchor's own center, clamped the same way
 * its final x-clamp works. Also reused by SelectionActionBar's own
 * no-pixel-bounds fallback branch (see that call site) for the same
 * TopCenter/BottomCenter choice it used to hand straight to the alignment
 * overload.
 */
private class WindowClampedPositionProvider(
    private val alignment: Alignment,
) : PopupPositionProvider {
    override fun calculatePosition(
        anchorBounds: IntRect,
        windowSize: IntSize,
        layoutDirection: androidx.compose.ui.unit.LayoutDirection,
        popupContentSize: IntSize,
    ): IntOffset {
        val boxCenterX = anchorBounds.left + (anchorBounds.right - anchorBounds.left) / 2
        var x = boxCenterX - popupContentSize.width / 2
        val minWindowX = 0
        val maxWindowX = maxOf(minWindowX, windowSize.width - popupContentSize.width)
        x = x.coerceIn(minWindowX, maxWindowX)
        var y = if (alignment == Alignment.BottomCenter) {
            anchorBounds.bottom - popupContentSize.height
        } else {
            anchorBounds.top
        }
        // Same anchorBounds.bottom barrier as HandleClearingPositionProvider's
        // own y-clamp above (see its doc) - MoreActionsPopup's TopCenter case
        // never hits this since it clamps against anchorBounds.top instead, but
        // the BottomCenter case (near-top selections, see SelectionActionBar's
        // fallback branch) has the exact same VirtualKeyBar-is-outside-anchorBounds
        // issue windowSize.height doesn't know about.
        val minWindowY = 0
        val maxWindowY = maxOf(minWindowY, anchorBounds.bottom - popupContentSize.height)
        y = y.coerceIn(minWindowY, maxWindowY)
        return IntOffset(x, y)
    }
}
@Composable
private fun SelectionBarButton(label: String, onClick: () -> Unit) {
    Text(
        text = label,
        color = MaterialTheme.colorScheme.onSurface,
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
        // Was alignment = Alignment.TopCenter - Compose's alignment-based
        // Popup overload only ever centers against the anchor Box (same
        // "narrow split/multi-pane tile" issue SelectionActionBar's own
        // HandleClearingPositionProvider doc covers), never against the
        // actual window, and Compose's Popup does not itself clip content
        // to the window - so this 220.dp-wide menu could render centered
        // on a tile narrower than itself and overflow past the real screen
        // edge on a narrow pane ("Terminal ekranin disina cikmiycak" - same
        // complaint, same root cause, this call site just hadn't been
        // switched over yet). A popupPositionProvider is required to get a
        // window-clamp in at all - the alignment overload has no hook for
        // one - so this switches to WindowClampedPositionProvider(TopCenter),
        // which keeps the same "top-center of the anchor" placement this
        // call site always used and only ADDS the final clamp into
        // [0, windowSize] on both axes.
        popupPositionProvider = remember { WindowClampedPositionProvider(Alignment.TopCenter) },
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
