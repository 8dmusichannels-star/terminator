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

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.caverock.androidsvg.SVG
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Every session-picture display site (titlebar, drawer rows, the Settings >
 * Sessions edit screen) used to decode SessionEntry.imageUri with a bare
 * BitmapFactory.decodeStream call - which only understands the raster
 * formats Android's system codecs ship (PNG/JPEG/WEBP/etc) and silently
 * returns null for an SVG file, since there's no built-in SVG-to-Bitmap
 * decoder on the platform. That made picking an SVG as a session's picture
 * look identical to picking a corrupt file: the picker would accept it, but
 * the picture would just never render anywhere.
 *
 * This is the single shared decode path all of those sites now go through
 * instead of duplicating a raster/SVG branch five times. It sniffs the
 * first bytes for the two ways an SVG can start (a raw "<svg" root
 * element, or an XML prolog like "<?xml ... ?>" ahead of the "<svg" root -
 * both are common depending on which app/editor produced the file) rather
 * than trusting the content:// Uri's reported MIME type, since SAF pickers
 * on some devices/providers report a generic "application/octet-stream" or
 * similar for SVGs rather than "image/svg+xml". On an SVG match, renders
 * through AndroidSVG at a fixed square size appropriate for the small
 * circular picture slots these are always shown in (see callers); anything
 * else falls back to the exact same BitmapFactory path this replaced, so
 * every previously-working format keeps working unchanged.
 */
private const val SESSION_IMAGE_RENDER_PX = 128

/** Process-lifetime cache keyed by uri string - see rememberSessionImage's doc. */
private val sessionImageCache = java.util.concurrent.ConcurrentHashMap<String, Bitmap>()

private fun sniffIsSvg(bytes: ByteArray): Boolean {
    // Only need to look at a small header window - "<svg" (or an XML
    // prolog leading up to it) always appears within the first couple
    // hundred bytes of a real SVG file, well before any path/style data.
    val header = String(bytes, 0, minOf(bytes.size, 512), Charsets.UTF_8)
        .trimStart()
    return header.startsWith("<svg", ignoreCase = true) ||
        (header.startsWith("<?xml", ignoreCase = true) && header.contains("<svg", ignoreCase = true))
}

private fun decodeSvgToBitmap(bytes: ByteArray): Bitmap? = runCatching {
    val svg = SVG.getFromString(String(bytes, Charsets.UTF_8))
    // Session pictures don't carry meaningful intrinsic size semantics
    // (they're always displayed in a fixed small circular slot - see
    // TerminatorTitleBar/SessionDrawer/SessionsSettingsScreen), so render
    // at one fixed square resolution regardless of the SVG's own
    // width/height or viewBox, rather than trying to preserve an aspect
    // ratio that wouldn't be honored by the circular crop anyway.
    svg.documentWidth = SESSION_IMAGE_RENDER_PX.toFloat()
    svg.documentHeight = SESSION_IMAGE_RENDER_PX.toFloat()
    val bitmap = Bitmap.createBitmap(SESSION_IMAGE_RENDER_PX, SESSION_IMAGE_RENDER_PX, Bitmap.Config.ARGB_8888)
    svg.renderToCanvas(Canvas(bitmap))
    bitmap
}.getOrNull()

/**
 * Reads whatever [uri] points to and decodes it to a Bitmap, trying SVG
 * first (see sniffIsSvg) and falling back to BitmapFactory for every other
 * format. Returns null on any failure (missing permission, corrupt file,
 * unsupported format) exactly like the BitmapFactory-only code this
 * replaced did - every call site already handles a null bitmap by simply
 * not showing a picture.
 */
fun decodeSessionImage(context: Context, uri: String): Bitmap? = runCatching {
    context.contentResolver.openInputStream(Uri.parse(uri))?.use { stream ->
        val bytes = stream.readBytes()
        if (sniffIsSvg(bytes)) {
            decodeSvgToBitmap(bytes) ?: BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        } else {
            BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
        }
    }
}.getOrNull()

/**
 * Compose-friendly wrapper: decodes once per distinct [uri] using the
 * current LocalContext. Returns null for a null/blank uri without
 * attempting a decode.
 *
 * Decode runs on Dispatchers.IO inside a LaunchedEffect keyed on uri, not
 * synchronously inside remember(uri) { ... } - the old version did the
 * decode (file read + SVG parse/render) directly on the composition/main
 * thread, and every session row in the drawer/settings list calls this
 * once, so any recomposition re-ran that decode chain on the main thread
 * for every visible row - the stutter/freeze reported after adding a
 * session photo. A process-lifetime cache also avoids re-decoding the same
 * uri across drawer re-opens.
 */
@Composable
fun rememberSessionImage(uri: String?): Bitmap? {
    val context = LocalContext.current
    var bitmap by remember(uri) { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(uri) {
        val target = uri?.takeIf { it.isNotBlank() }
        bitmap = when {
            target == null -> null
            else -> sessionImageCache[target] ?: withContext(Dispatchers.IO) {
                decodeSessionImage(context, target)
            }?.also { sessionImageCache[target] = it }
        }
    }
    return bitmap
}
