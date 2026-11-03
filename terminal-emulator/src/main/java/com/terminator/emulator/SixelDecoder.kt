package com.terminator.emulator

/**
 * Incremental (streaming) Sixel decoder.
 *
 * Feed it the Sixel body (everything after the `ESC P <params> q` introducer)
 * in arbitrary pieces with [feed]; call [finish] once at the terminator to get
 * the raster. There is no input-size limit: memory is bounded by the decoded
 * image size, which is clamped to [MAX_WIDTH] x [MAX_HEIGHT] and
 * [MAX_PIXELS] total (anything beyond is cropped, never an OOM).
 *
 * Not thread-safe; use from one thread at a time (the emulator hands it to a
 * single background worker, see TerminalEmulator's sixel pipeline).
 *
 * Supported: `#` colour select/define (RGB and HLS), `!` repeat, `$` carriage
 * return, `-` new line, `"` raster attributes (skipped: dimensions are derived
 * from the data drawn, since hand-rolled encoders often lie in the header).
 * Unpainted pixels stay transparent (alpha 0).
 */
internal class SixelDecoder {
    companion object {
        const val MAX_WIDTH = 8192
        const val MAX_HEIGHT = 8192
        const val MAX_PIXELS = 16 * 1024 * 1024
        private const val PALETTE_SIZE = 1024

        private fun argb(r: Int, g: Int, b: Int): Int =
            (0xFF shl 24) or ((r and 0xFF) shl 16) or ((g and 0xFF) shl 8) or (b and 0xFF)

        // VT340 default palette (percent values), indices 0..15.
        private val DEFAULT_PALETTE = intArrayOf(
            argb(0, 0, 0), argb(51, 51, 204), argb(204, 33, 33), argb(51, 204, 51),
            argb(204, 51, 204), argb(51, 204, 204), argb(204, 204, 51), argb(135, 135, 135),
            argb(66, 66, 66), argb(84, 84, 153), argb(153, 66, 66), argb(84, 153, 84),
            argb(153, 84, 153), argb(84, 153, 153), argb(153, 153, 84), argb(204, 204, 204)
        )

        // DEC HLS (hue 0 = blue) -> packed ARGB.
        private fun hls(h: Int, l: Int, s: Int): Int {
            val hh = ((h % 360) + 360) % 360
            val ll = l.coerceIn(0, 100) / 100f
            val ss = s.coerceIn(0, 100) / 100f
            // DEC puts blue at 0 degrees; standard HLS puts red at 0.
            val hue = ((hh + 240) % 360) / 360f
            if (ss == 0f) {
                val v = (ll * 255).toInt()
                return argb(v, v, v)
            }
            val q = if (ll < 0.5f) ll * (1 + ss) else ll + ss - ll * ss
            val p = 2 * ll - q
            fun ch(t0: Float): Int {
                var t = t0
                if (t < 0) t += 1f
                if (t > 1) t -= 1f
                val v = when {
                    t < 1f / 6 -> p + (q - p) * 6 * t
                    t < 0.5f -> q
                    t < 2f / 3 -> p + (q - p) * (2f / 3 - t) * 6
                    else -> p
                }
                return (v * 255 + 0.5f).toInt().coerceIn(0, 255)
            }
            return argb(ch(hue + 1f / 3), ch(hue), ch(hue - 1f / 3))
        }
    }

    private val palette = IntArray(PALETTE_SIZE) { if (it < 16) DEFAULT_PALETTE[it] else DEFAULT_PALETTE[0] }
    private var currentColor = palette[0]

    private var x = 0
    private var y = 0
    private var maxX = -1
    private var maxY = -1
    private var repeatCount = 1

    // Row-major list of rows, each grown on demand as x advances.
    private val rows = ArrayList<IntArray?>()

    // Numeric-argument parser for the multi-character commands (# ! ").
    private enum class Cmd { NONE, COLOR, REPEAT, RASTER }
    private var cmd = Cmd.NONE
    private val fields = IntArray(8)
    private var fieldCount = 0
    private var cur = -1

    fun feed(chunk: CharSequence) {
        for (i in 0 until chunk.length) feed(chunk[i])
    }

    fun feed(c: Char) {
        if (cmd != Cmd.NONE) {
            if (c in '0'..'9') {
                val d = c - '0'
                cur = if (cur < 0) d else if (cur > 100_000) cur else cur * 10 + d
                return
            }
            if (c == ';') {
                pushField()
                return
            }
            endCommand()
        }
        when {
            c == '#' -> startCommand(Cmd.COLOR)
            c == '!' -> startCommand(Cmd.REPEAT)
            c == '"' -> startCommand(Cmd.RASTER)
            c == '$' -> x = 0
            c == '-' -> { x = 0; y += 6 }
            c in '?'..'~' -> data(c.code - '?'.code)
            else -> { /* whitespace/newlines some encoders pad with */ }
        }
    }

    private fun startCommand(k: Cmd) {
        cmd = k
        fieldCount = 0
        cur = -1
    }

    private fun pushField() {
        if (fieldCount < fields.size) fields[fieldCount++] = if (cur < 0) 0 else cur
        cur = -1
    }

    private fun endCommand() {
        if (cur >= 0 || (cmd == Cmd.COLOR && fieldCount == 0)) pushField()
        when (cmd) {
            Cmd.COLOR -> {
                val pc = fields[0].coerceIn(0, PALETTE_SIZE - 1)
                if (fieldCount >= 5) {
                    val a = fields[2]; val b = fields[3]; val d = fields[4]
                    when (fields[1]) {
                        2 -> palette[pc] = argb(
                            a.coerceIn(0, 100) * 255 / 100,
                            b.coerceIn(0, 100) * 255 / 100,
                            d.coerceIn(0, 100) * 255 / 100
                        )
                        1 -> palette[pc] = hls(a, b, d)
                    }
                }
                currentColor = palette[pc]
            }
            Cmd.REPEAT -> repeatCount = if (fieldCount > 0) fields[0].coerceAtLeast(1) else 1
            else -> {}
        }
        cmd = Cmd.NONE
    }

    private fun data(bits: Int) {
        val n = repeatCount
        repeatCount = 1
        if (bits == 0 || y >= MAX_HEIGHT) {
            x += n
            return
        }
        val endX = minOf(x + n, MAX_WIDTH)
        for (px in x until endX) {
            for (bit in 0 until 6) {
                if ((bits shr bit) and 1 == 1) setPixel(px, y + bit, currentColor)
            }
        }
        x += n
    }

    private fun setPixel(px: Int, py: Int, color: Int) {
        if (py >= MAX_HEIGHT) return
        while (rows.size <= py) rows.add(null)
        var row = rows[py]
        if (row == null) {
            row = IntArray(maxOf(64, px + 1))
            rows[py] = row
        } else if (px >= row.size) {
            row = row.copyOf(minOf(MAX_WIDTH, maxOf(px + 1, row.size * 2)))
            rows[py] = row
        }
        row[px] = color
        if (px > maxX) maxX = px
        if (py > maxY) maxY = py
    }

    /** Flushes any pending command and returns the decoded image, or null if nothing was drawn. */
    fun finish(): TerminalBuffer.SixelImage? {
        if (cmd != Cmd.NONE) endCommand()
        if (maxX < 0 || maxY < 0) return null
        val width = maxX + 1
        // The last band's full 6-row height, even if only partly drawn.
        var height = minOf(maxY + 1 + ((6 - (maxY + 1) % 6) % 6), MAX_HEIGHT)
        if (width.toLong() * height > MAX_PIXELS) height = MAX_PIXELS / width
        if (height <= 0) return null
        val pixels = IntArray(width * height)
        for (r in 0 until minOf(height, rows.size)) {
            val row = rows[r] ?: continue
            System.arraycopy(row, 0, pixels, r * width, minOf(row.size, width))
            rows[r] = null // release as we go to keep the peak low
        }
        return TerminalBuffer.SixelImage(width, height, pixels)
    }
}
