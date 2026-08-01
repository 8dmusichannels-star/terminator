package com.terminator.app.ui

import android.content.Context
import android.graphics.Typeface
import java.io.File

/** Built-in monospace-ish choices, plus "Custom" for a user-imported font file. */
val BUILT_IN_FONTS = listOf("Monospace", "Sans Mono", "Serif Mono", "Custom")

/**
 * Resolves the persisted FONT_FAMILY setting to a real Typeface for
 * TerminalView. For "Custom" this reads the font file already copied into
 * app-private storage by AppearanceSettingsScreen's import picker (SAF
 * content:// Uris aren't directly usable by Typeface.createFromFile).
 * Falls back to Typeface.MONOSPACE for anything unrecognized or missing, so
 * the terminal never ends up with a null/broken typeface.
 */
fun resolveTerminalTypeface(context: Context, fontFamily: String): Typeface {
    return when (fontFamily) {
        "Sans Mono" -> Typeface.create("sans-serif", Typeface.NORMAL)
        "Serif Mono" -> Typeface.create(Typeface.SERIF, Typeface.NORMAL)
        "Custom" -> {
            val cached = cachedCustomFontFile(context)
            if (cached.exists()) {
                runCatching { Typeface.createFromFile(cached) }.getOrDefault(Typeface.MONOSPACE)
            } else {
                Typeface.MONOSPACE
            }
        }
        else -> Typeface.MONOSPACE
    }
}

/**
 * Custom font files are imported via SAF (content:// Uri), which
 * Typeface.createFromFile can't read directly - they're copied once into
 * app-private storage and reused from there.
 */
fun cachedCustomFontFile(context: Context): File = File(context.filesDir, "custom_terminal_font.ttf")
