package com.terminator.emulator
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.text.font.FontFamily
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
    val defaultBackground: Int
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
        index in ansiColors.indices -> ansiColors[index]
        else -> defaultForeground
    }

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
    // Long-press-to-select range, in (row, col) screen space - null when
    // nothing is selected. Order doesn't matter (start can be after end,
    // e.g. dragging upward); the highlight and the copied text both
    // normalize it the same way.
    selectionStart: Pair<Int, Int>? = null,
    selectionEnd: Pair<Int, Int>? = null,
    modifier: Modifier = Modifier
) {
    // bufferVersion is bumped by the caller's ViewModel on every
    // TerminalEmulator.Listener callback (cursor move / content change).
    // Reading it here (even though drawTerminal reads straight from
    // `buffer`) is what makes Compose actually recompose on new output.
    Canvas(modifier = modifier.fillMaxSize()) {
        @Suppress("UNUSED_EXPRESSION")
        bufferVersion
        drawTerminal(buffer, palette, fontFamily, fontSizeSp, backgroundAlpha, scrollOffset, selectionStart, selectionEnd)
    }
}

private fun DrawScope.drawTerminal(
    buffer: TerminalBuffer,
    palette: TerminalPalette,
    fontFamily: Typeface,
    fontSizeSp: Float,
    backgroundAlpha: Float = 1f,
    scrollOffset: Int = 0,
    selectionStart: Pair<Int, Int>? = null,
    selectionEnd: Pair<Int, Int>? = null
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

    // Selection highlight, drawn before glyphs so text stays legible on top
    // of it. Normalized the same way TerminalBuffer.selectedText() does -
    // start/end can be given in either order (dragging up vs down) - so the
    // two stay visually and textually consistent with each other.
    if (selectionStart != null && selectionEnd != null) {
        var (r1, c1) = selectionStart
        var (r2, c2) = selectionEnd
        if (r1 > r2 || (r1 == r2 && c1 > c2)) {
            val tr = r1; val tc = c1
            r1 = r2; c1 = c2
            r2 = tr; c2 = tc
        }
        val highlightColor = Color(0xFF4A90D9).copy(alpha = 0.45f)
        for (row in r1..r2) {
            if (row !in 0 until buffer.rows) continue
            val fromCol = (if (row == r1) c1 else 0).coerceIn(0, buffer.columns - 1)
            val rawToCol = (if (row == r2) c2 else buffer.columns - 1).coerceIn(0, buffer.columns - 1)

            // Trailing blank cells on a row (columns never written to, or
            // cleared back to a space) aren't real content - selectedText()
            // already trims them from what actually gets copied. The
            // highlight used to always paint out to the row's raw toCol
            // (the far edge of the screen for every row except the last),
            // so dragging a selection down past a couple lines of real
            // text into the blank rows below it painted a solid full-width
            // bar across all that empty space - looking like one giant
            // block instead of just the two or three lines actually
            // selected. Clamping to the row's last non-space character
            // keeps the highlight's shape matching what Copy will actually
            // produce.
            val lastNonBlankCol = buffer.lastNonBlankColumn(row, scrollOffset)
            // Only clamp the *end* of the row's highlighted range - the
            // drag's start column (fromCol, on r1) is always where the
            // user actually put their finger, so it's left untouched even
            // if that lands past the last real character.
            val toCol = if (lastNonBlankCol != null) rawToCol.coerceAtMost(lastNonBlankCol) else -1
            if (toCol < fromCol) continue

            val x = fromCol * charWidth
            val y = (row + 1) * charHeight
            val width = (toCol - fromCol + 1) * charWidth
            drawRect(
                color = highlightColor,
                topLeft = Offset(x, y - charHeight),
                size = androidx.compose.ui.geometry.Size(width, charHeight)
            )
        }
    }

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
                if (cell.char != ' ' || cell.underline) {
                    canvas.nativeCanvas.drawText(cell.char.toString(), x, y, paint)
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
            canvas.nativeCanvas.drawText(cursorCell.char.toString(), cursorX, cursorY, paint)
        }
    }
}
