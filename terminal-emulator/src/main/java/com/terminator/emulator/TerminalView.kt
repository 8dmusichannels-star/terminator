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
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.text.selection.SelectionState
import androidx.compose.foundation.text.selection.rememberSelectionState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.sp
import android.graphics.Paint
import android.graphics.Typeface

/**
 * Renders a TerminalBuffer to a Compose Canvas using a monospace font.
 * Colors are resolved through a [TerminalPalette] so themes (Material,
 * custom RGB, imported schemes such as Nord) can be swapped without
 * touching the renderer.
 */
class TerminalPalette(
    val ansiColors: IntArray, // 16 base colors, index -> ARGB int
    val defaultForeground: Int,
    val defaultBackground: Int,
    // Independent of ansiColors/defaultForeground/defaultBackground above -
    // see Settings > Theme > "Separate error/status colors". When non-null,
    // these pin ANSI red (indices 1 and 9) and yellow (indices 3 and 11)
    // to a fixed RGB regardless of what the rest of the palette resolves
    // to (Material, Nord, custom RGB, imported theme...), so a program's
    // own red/yellow SGR codes always read as "error"/"status" the same
    // way even if the surrounding palette changes.
    val statusErrorColor: Int? = null,
    val statusWarningColor: Int? = null
) {
    fun resolve(index: Int): Int = when {
        // Index 15 doubles as "default foreground" (see TerminalBuffer.
        // DEFAULT_FOREGROUND) and untouched/reset text is the overwhelming
        // majority of what's on screen. Without this branch every default
        // cell rendered through ansiColors[15] - a fixed accent white baked
        // into the palette - instead of whatever foreground the user
        // actually picked in Settings > Theme (Custom RGB / imported file /
        // Material), which is exactly why changing that color appeared to
        // do nothing. Mirrors the same special-casing already done for
        // DEFAULT_BACKGROUND (index 0) in drawTerminal() below.
        index == TerminalBuffer.DEFAULT_FOREGROUND -> defaultForeground
        // Checked before the general ansiColors branch below so a pinned
        // status color always wins over whatever red/yellow the active
        // palette (Material override included) would otherwise resolve to.
        statusErrorColor != null && (index == 1 || index == 9) -> statusErrorColor
        statusWarningColor != null && (index == 3 || index == 11) -> statusWarningColor
        index in ansiColors.indices -> ansiColors[index]
        else -> defaultForeground
    }

    /** Returns a copy with the same ansiColors/fg/bg but the given status
     *  override colors applied (or cleared, if either is null) - lets the
     *  caller layer Settings > Theme > "Separate error/status colors" on
     *  top of whatever base palette (Material, Nord, Custom RGB, Material
     *  override...) was already chosen, without duplicating that base
     *  palette's own construction logic. */
    fun withStatusColors(errorColor: Int?, warningColor: Int?): TerminalPalette =
        TerminalPalette(ansiColors, defaultForeground, defaultBackground, errorColor, warningColor)

    companion object {
        /** A Nord-inspired default palette as a sane out-of-the-box theme. */
        fun nord(): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, 0xFFD8DEE9.toInt(), 0xFF2E3440.toInt())
        }

        /** Flat black background, same accent colors as [nord] - the app default now. */
        fun flatBlack(): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, 0xFFE6E6E6.toInt(), 0xFF000000.toInt())
        }

        /**
         * A palette built from just a foreground/background pair, keeping the
         * same 16 ANSI accent colors as [flatBlack] for readability. Used for
         * both the "Custom RGB" theme option and the "Material" option (where
         * the caller passes colors derived from the current MaterialTheme),
         * so the terminal screen actually reflects whatever the user picked
         * in Settings > Theme instead of always rendering [flatBlack].
         */
        fun custom(foreground: Int, background: Int): TerminalPalette {
            val colors = intArrayOf(
                0xFF3B4252.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF88C0D0.toInt(), 0xFFE5E9F0.toInt(),
                0xFF4C566A.toInt(), 0xFFBF616A.toInt(), 0xFFA3BE8C.toInt(), 0xFFEBCB8B.toInt(),
                0xFF81A1C1.toInt(), 0xFFB48EAD.toInt(), 0xFF8FBCBB.toInt(), 0xFFECEFF4.toInt()
            )
            return TerminalPalette(colors, foreground, background)
        }

        /**
         * Settings > Theme > "Custom Palette" mode - all 16 ANSI slots plus
         * fg/bg, either hand-picked by the user or seeded from one of
         * PalettePresets (Solarized, Gruvbox, Dracula, Nord...). Distinct
         * from [custom], which only ever varies fg/bg and keeps a fixed
         * accent set; this is a real termcolor-style palette where every
         * slot is independently defined.
         */
        fun fromPalette(colors: IntArray, foreground: Int, background: Int): TerminalPalette {
            require(colors.size == 16) { "fromPalette requires exactly 16 colors, got ${colors.size}" }
            return TerminalPalette(colors.copyOf(), foreground, background)
        }

        /**
         * Settings > Theme > "Material color override" toggle, ON state.
         * Unlike [custom] (which only ever touches defaultForeground/
         * defaultBackground and leaves the 16 ANSI accent colors fixed),
         * this maps Material's own dynamic scheme onto all 16 ANSI slots -
         * so a program's own SGR color codes (red, green, blue...) render
         * in Material-derived hues too, instead of the fixed Nord-style
         * accents every other mode keeps for readability. Whether this or
         * [custom] is used for "Material" mode is decided by the caller
         * reading MATERIAL_COLOR_OVERRIDE - this function only builds the
         * palette, it doesn't read settings itself.
         *
         * Standard ANSI ordering: 0 black, 1 red, 2 green, 3 yellow,
         * 4 blue, 5 magenta, 6 cyan, 7 white, 8-15 the bright variants.
         * Material's tonal roles don't map 1:1 onto 8 hues, so this picks
         * the closest-fitting role for each slot and derives the bright
         * variant by leaning on the "inverse"/"container" counterpart
         * Material already computes, rather than inventing new colors.
         */
        fun materialOverride(
            primary: Int,
            error: Int,
            tertiary: Int,
            secondary: Int,
            onBackground: Int,
            background: Int,
            primaryContainer: Int,
            errorContainer: Int,
            tertiaryContainer: Int,
            secondaryContainer: Int
        ): TerminalPalette {
            val colors = intArrayOf(
                background,          // 0 black
                error,                // 1 red
                tertiary,             // 2 green (closest "positive" role Material exposes)
                secondary,            // 3 yellow/status
                primary,              // 4 blue
                secondary,            // 5 magenta
                tertiary,             // 6 cyan
                onBackground,         // 7 white
                background,           // 8 bright black
                errorContainer,       // 9 bright red
                tertiaryContainer,    // 10 bright green
                secondaryContainer,   // 11 bright yellow
                primaryContainer,     // 12 bright blue
                secondaryContainer,   // 13 bright magenta
                tertiaryContainer,    // 14 bright cyan
                onBackground          // 15 bright white / default foreground
            )
            // Also seed statusErrorColor/statusWarningColor with the same
            // Material error/secondary this palette already used for ANSI
            // slots 1/9 and 3/11 above. Without this, "Override ANSI colors
            // too" only touched the general 16-slot palette - a program's
            // own SGR red/yellow still landed on index 1/3 and looked
            // identical to before, because Material's error red is close
            // enough to the existing Nord-style red that the change wasn't
            // visible. Status colors are checked first in resolve(), so
            // seeding them here (rather than leaving them null) is what
            // actually makes the override read as "error/warning now follow
            // Material" instead of doing nothing. MainActivity's separate
            // "Separate error/status colors" + RGB picker layer still wins
            // when the user turns that on explicitly - see withStatusColors().
            return TerminalPalette(
                colors, onBackground, background,
                statusErrorColor = error,
                statusWarningColor = secondary
            )
        }
    }
}

@Composable
fun TerminalView(
    buffer: TerminalBuffer,
    palette: TerminalPalette,
    fontFamily: Typeface = Typeface.MONOSPACE,
    fontSizeSp: Float = 14f,
    bufferVersion: Int = 0,
    // 1f = fully opaque background (normal, no-wallpaper look). When a
    // wallpaper is active behind the terminal, the caller passes something
    // < 1f here so the base canvas fill lets the wallpaper show through -
    // previously this rect was always fully opaque and hid any wallpaper
    // completely regardless of the Appearance > blur/alpha slider.
    backgroundAlpha: Float = 1f,
    // 0 = showing the live screen (normal). >0 = the user has dragged the
    // terminal down to look at scrollback history, this many lines back.
    scrollOffset: Int = 0,
    // Hoisted by the caller (MainActivity) so its own Copy/Paste/Close
    // toolbar can read selectionState.selectedTexts and call .clear() -
    // see that file's SelectionToolbar wiring. Callers that don't need to
    // observe/drive selection themselves (SplitTerminalPane's panes) can
    // just leave the default, which still gets full native long-press/
    // drag selection - they just don't read anything back out of it.
    selectionState: SelectionState = rememberSelectionState(),
    modifier: Modifier = Modifier
) {
    val density = LocalDensity.current
    // Same px math drawTerminal uses below (sp -> px via density * fontScale,
    // then Paint's own font metrics) computed once here too, so the invisible
    // selection overlay's row height/column width in dp lines up with the
    // actual glyph grid the Canvas paints. Recomputed only when one of the
    // inputs that could change it actually changes, not on every frame.
    val (charWidthPx, charHeightPx) = remember(fontFamily, fontSizeSp, density.density, density.fontScale) {
        val measuringPaint = Paint().apply {
            typeface = fontFamily
            textSize = fontSizeSp * density.density * density.fontScale
        }
        measuringPaint.measureText("M") to measuringPaint.fontSpacing
    }

    Box(modifier = modifier) {
        // bufferVersion is bumped by the caller's ViewModel on every
        // TerminalEmulator.Listener callback (cursor move / content change).
        // Reading it here (even though drawTerminal reads straight from
        // `buffer`) is what makes Compose actually recompose on new output.
        Canvas(modifier = Modifier.fillMaxSize()) {
            @Suppress("UNUSED_EXPRESSION")
            bufferVersion
            drawTerminal(buffer, palette, fontFamily, fontSizeSp, backgroundAlpha, scrollOffset)
        }

        // Native text-selection overlay (requires Compose BOM 2026.08.00 /
        // Compose Foundation 1.12+, which added edge auto-scroll-while-
        // selecting to SelectionContainer - see build.gradle.kts). This is
        // an invisible, real-text row stack positioned exactly over the
        // Canvas above it. Long-press-to-select, drag-to-extend, the
        // system's own selection handles, auto-scroll past the viewport
        // edge, and the floating Copy/Select All toolbar are now all
        // Android's job via SelectionContainer - none of it is hand-rolled
        // pointerInput math anymore (see MainActivity's gesture loop,
        // which no longer starts a selection on long-press for exactly
        // this reason). The Canvas above still owns everything actually
        // painted (ANSI colors, bold/italic, the block cursor); this layer
        // only has to carry the right characters in the right on-screen
        // positions for selection/copy to work correctly - text color is
        // fully transparent so it never visually doubles the glyphs
        // Canvas already drew. It intentionally uses FontFamily.Monospace
        // rather than the caller's own `fontFamily` Typeface: Compose's
        // font APIs don't take an android.graphics.Typeface directly, and
        // since this layer is invisible its glyph shapes don't matter -
        // only that it stays reasonably monospaced so column positions
        // (and therefore where a drag lands / where handles appear) stay
        // close to the Canvas grid underneath. bufferVersion is read via
        // the enclosing composable's own recomposition (see the Canvas
        // comment above) rather than a second explicit read here.
        SelectionContainer(state = selectionState) {
            androidx.compose.runtime.LaunchedEffect(selectionState.selectedTexts) {
                android.util.Log.d("SelDebug", "TerminalView: selectedTexts changed, count=${selectionState.selectedTexts.size}")
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .onGloballyPositioned { coords ->
                        android.util.Log.d("SelDebug", "TerminalView: overlay Column size=${coords.size}")
                    }
            ) {
                val rowHeightDp = with(density) { charHeightPx.toDp() }
                val rowWidthDp = with(density) { (charWidthPx * buffer.columns).toDp() }
                for (row in 0 until buffer.rows) {
                    BasicText(
                        text = buffer.rowPlainText(row, scrollOffset),
                        style = TextStyle(
                            color = Color.Transparent,
                            fontSize = fontSizeSp.sp,
                            fontFamily = FontFamily.Monospace
                        ),
                        softWrap = false,
                        modifier = Modifier
                            .height(rowHeightDp)
                            .width(rowWidthDp)
                    )
                }
            }
        }
    }
}

private fun DrawScope.drawTerminal(
    buffer: TerminalBuffer,
    palette: TerminalPalette,
    fontFamily: Typeface,
    fontSizeSp: Float,
    backgroundAlpha: Float = 1f,
    scrollOffset: Int = 0
) {
    // DrawScope implements Density, so both `density` and `fontScale` are
    // available directly here. Real Android sp->px conversion is
    // `px = sp * density * fontScale` - this used to multiply by `density`
    // alone, silently ignoring the user's accessibility font-scale setting.
    // Whenever that scale isn't exactly 1.0, the glyphs actually painted
    // here came out a different size than what MainActivity used to compute
    // the pty's column/row count, which is what made full-screen apps like
    // nano/vim render misaligned or garbled at non-default font scales.
    val paint = Paint().apply {
        typeface = fontFamily
        textSize = fontSizeSp * density * fontScale
        isAntiAlias = true
    }
    // Reused for every cell/cursor background fill instead of allocating a
    // fresh Paint per cell. On an 80x24 screen that's up to ~1920 Paint()
    // allocations per single redraw (and htop/top can trigger several
    // redraws a second) - all of that garbage is what made the terminal
    // feel sluggish/janky, especially right as the IME animates in or out
    // and a resize forces a full repaint on top of the GC churn.
    val bgPaint = Paint()
    val charWidth = paint.measureText("M")
    val charHeight = paint.fontSpacing

    drawRect(color = Color(palette.defaultBackground).copy(alpha = backgroundAlpha.coerceIn(0f, 1f)), size = size)

    // Selection highlight used to be drawn here by hand (blue rect per
    // row, normalized/clamped against buffer.lastNonBlankColumn) - removed
    // now that selection is owned by TerminalView's native
    // SelectionContainer overlay, which draws its own highlight behind the
    // (invisible) text it manages. That overlay sits directly on top of
    // this Canvas, so nothing here needs to paint a highlight anymore.

    drawIntoCanvas { canvas ->
        for (row in 0 until buffer.rows) {
            for (col in 0 until buffer.columns) {
                val cell = buffer.lineAt(row, col, scrollOffset)
                val x = col * charWidth
                val y = (row + 1) * charHeight

                val fg = if (cell.inverse) palette.resolve(cell.bg) else palette.resolve(cell.fg)
                // Compare the RAW ansi index, not the resolved color. Index 0
                // ("default black") is deliberately resolved to a lighter
                // slate for readable black-on-black TEXT, but that same
                // lighter shade must never be painted as a BACKGROUND rect -
                // otherwise every default-background cell (i.e. almost the
                // entire screen, since that's what any program gets after a
                // plain SGR reset) gets covered in a visible blue-gray slab
                // instead of blending into the true-black canvas fill below.
                val bgIndex = if (cell.inverse) cell.fg else cell.bg
                if (bgIndex != TerminalBuffer.DEFAULT_BACKGROUND) {
                    val bg = palette.resolve(bgIndex)
                    bgPaint.color = bg
                    canvas.nativeCanvas.drawRect(x, y - charHeight, x + charWidth, y, bgPaint)
                }

                paint.color = fg
                paint.isFakeBoldText = cell.bold
                paint.isUnderlineText = cell.underline
                paint.textSkewX = if (cell.italic) -0.25f else 0f

                // A plain space draws nothing visible - skipping the
                // drawText call for it (the common case: blank lines,
                // cleared regions, right-padding after short output) cuts
                // a meaningful fraction of the ~1920 drawText calls a full
                // 80x24 redraw would otherwise make. Underlined spaces
                // still need to draw (the underline itself is visible).
                if (cell.text != " " || cell.underline) {
                    canvas.nativeCanvas.drawText(cell.text, x, y, paint)
                }
            }
        }

        // Block cursor: solid white rectangle at the current input position,
        // with that cell's character redrawn in black on top so it stays
        // readable. This is what shows where the next keystroke will land.
        // Only drawn on the live screen - while scrolled back into history
        // (scrollOffset > 0) the cursor's actual row/col don't correspond
        // to what's currently being displayed, so drawing it would just
        // put a stray white block over unrelated scrollback text.
        if (scrollOffset == 0 && buffer.cursorVisible && buffer.cursorRow in 0 until buffer.rows && buffer.cursorCol in 0 until buffer.columns) {
            val cursorX = buffer.cursorCol * charWidth
            val cursorY = (buffer.cursorRow + 1) * charHeight
            bgPaint.color = android.graphics.Color.WHITE
            canvas.nativeCanvas.drawRect(cursorX, cursorY - charHeight, cursorX + charWidth, cursorY, bgPaint)
            val cursorCell = buffer.cellAt(buffer.cursorRow, buffer.cursorCol)
            paint.color = android.graphics.Color.BLACK
            paint.isFakeBoldText = cursorCell.bold
            canvas.nativeCanvas.drawText(cursorCell.text, cursorX, cursorY, paint)
        }
    }
}
