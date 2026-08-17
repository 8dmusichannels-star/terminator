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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import org.json.JSONObject

private val Context.paneGeometryDataStore by preferencesDataStore(name = "terminator_pane_geometry")

/**
 * Persists floating-mode pane position/size across app restarts, keyed by
 * *session entryId* (the saved session definition) rather than runtimeId (a
 * fresh, throwaway value every time that session is (re)launched - see
 * PaneState's doc). This is what makes "kullanici panel sinirini belirlesin,
 * son birakildigi yerde hatirlansin" actually durable: close the app,
 * reopen the same session as a floating pane days later, it comes back
 * exactly where it was left.
 *
 * A single JSON blob (one key, small map) rather than one DataStore key per
 * session - the number of distinct sessions a person defines is always
 * small (this is a personal terminal app's session list, not a database),
 * so there's no pagination/size concern that would justify per-entry keys,
 * and one key means one atomic read-modify-write per save with no risk of
 * two saves for two different panes racing each other the way per-key
 * writes could.
 */
object PaneGeometryStore {
    private val geometryKey = stringPreferencesKey("pane_geometry_json")

    private val defaultOffset = Offset(24f, 24f)
    private val defaultSize = Size(360f, 480f)

    /** Returns (offset, size) for the given entryId, or sane defaults if
     *  this session has never been floated before (entryId == null covers
     *  the "session's live entry already went away" edge case - callers
     *  pass liveEntries[runtimeId]?.id straight through). */
    suspend fun geometryFor(context: Context, entryId: String?): Pair<Offset, Size> {
        if (entryId == null) return defaultOffset to defaultSize
        return try {
            val prefs = context.paneGeometryDataStore.data.first()
            val json = prefs[geometryKey] ?: return defaultOffset to defaultSize
            val root = JSONObject(json)
            val entry = root.optJSONObject(entryId) ?: return defaultOffset to defaultSize
            val offset = Offset(entry.optDouble("x", defaultOffset.x.toDouble()).toFloat(), entry.optDouble("y", defaultOffset.y.toDouble()).toFloat())
            val size = Size(entry.optDouble("w", defaultSize.width.toDouble()).toFloat(), entry.optDouble("h", defaultSize.height.toDouble()).toFloat())
            offset to size
        } catch (_: Exception) {
            // Corrupt/unexpected JSON should never crash pane creation -
            // worst case the pane opens at the default spot instead of its
            // remembered one.
            defaultOffset to defaultSize
        }
    }

    suspend fun saveGeometry(context: Context, entryId: String, offset: Offset, size: Size) {
        try {
            context.paneGeometryDataStore.edit { prefs ->
                val root = prefs[geometryKey]?.let { JSONObject(it) } ?: JSONObject()
                val entry = JSONObject()
                entry.put("x", offset.x.toDouble())
                entry.put("y", offset.y.toDouble())
                entry.put("w", size.width.toDouble())
                entry.put("h", size.height.toDouble())
                root.put(entryId, entry)
                prefs[geometryKey] = root.toString()
            }
        } catch (_: Exception) {
            // Best-effort persistence, same reasoning as exportSessionOutput's
            // catch - losing a geometry save is a minor annoyance (window
            // reopens at the default spot next time), never worth crashing
            // a drag/resize gesture over.
        }
    }
}
