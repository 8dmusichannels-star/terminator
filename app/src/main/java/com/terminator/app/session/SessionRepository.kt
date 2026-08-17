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

package com.terminator.app.session

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject

private val Context.sessionDataStore by preferencesDataStore(name = "terminator_sessions")

/**
 * Persists session definitions (default + custom, Command Arg / File Base)
 * across app restarts. Enforces the rule that at least one session must
 * always be marked default.
 */
class SessionRepository(private val context: Context) {

    private val sessionsKey = stringPreferencesKey("sessions_json")

    val sessions: Flow<List<SessionEntry>> = context.sessionDataStore.data.map { prefs ->
        decodeOrDefault(prefs[sessionsKey])
    }

    suspend fun currentSessions(): List<SessionEntry> = sessions.first()

    suspend fun save(entry: SessionEntry) = mutate { current ->
        val idx = current.indexOfFirst { it.id == entry.id }
        if (idx >= 0) current.toMutableList().apply { this[idx] = entry } else current + entry
    }

    suspend fun delete(id: String) = mutate { current ->
        val remaining = current.filterNot { it.id == id }.toMutableList()
        // Enforce: at least one default session must always remain.
        if (remaining.none { it.isDefault }) {
            if (remaining.isEmpty()) {
                remaining.add(SessionEntry.defaultAndroidShell())
            } else {
                remaining[0] = remaining[0].copy(isDefault = true)
            }
        }
        remaining
    }

    suspend fun setDefault(id: String) = mutate { current ->
        current.map { it.copy(isDefault = it.id == id) }
    }

    suspend fun setFavorite(id: String, favorite: Boolean) = mutate { current ->
        current.map { if (it.id == id) it.copy(isFavorite = favorite) else it }
    }

    /**
     * Reads, transforms and writes the session list in ONE atomic DataStore
     * transaction instead of a separate currentSessions() read followed by
     * a separate persist() write. The old two-step version was a classic
     * read-modify-write race: any two calls landing close together (a fast
     * double-tap on the star, the drawer's live collector and the settings
     * screen's own collector both triggering around the same time, etc.)
     * could each read the same pre-change snapshot and then write back over
     * each other, silently dropping whichever one wrote second - which from
     * the outside looks exactly like "toggling the star never actually
     * takes effect". DataStore's edit {} block is already a single atomic
     * transaction; doing the read (via prefs[sessionsKey], captured at
     * transaction time, not beforehand) AND the transform inside that same
     * block closes the gap entirely - there's no longer a window for
     * another write to land in between.
     */
    private suspend fun mutate(transform: (List<SessionEntry>) -> List<SessionEntry>) {
        context.sessionDataStore.edit { prefs ->
            val current = decodeOrDefault(prefs[sessionsKey])
            val transformed = transform(current)
            prefs[sessionsKey] = encode(transformed)
        }
    }

    private fun decodeOrDefault(json: String?): List<SessionEntry> =
        if (json.isNullOrBlank()) listOf(SessionEntry.defaultAndroidShell()) else decode(json)

    private fun encode(list: List<SessionEntry>): String {
        val arr = JSONArray()
        list.forEach { e ->
            val o = JSONObject()
            o.put("id", e.id)
            o.put("name", e.name)
            o.put("type", e.type.name)
            o.put("commandPath", e.commandPath ?: JSONObject.NULL)
            o.put("filePath", e.filePath ?: JSONObject.NULL)
            o.put("fileName", e.fileName ?: JSONObject.NULL)
            o.put("workingDirectory", e.workingDirectory ?: JSONObject.NULL)
            o.put("useRoot", e.useRoot)
            o.put("isFavorite", e.isFavorite)
            o.put("isDefault", e.isDefault)
            o.put("allowMultipleInstances", e.allowMultipleInstances)
            o.put("imageUri", e.imageUri ?: JSONObject.NULL)
            arr.put(o)
        }
        return arr.toString()
    }

    private fun decode(json: String): List<SessionEntry> {
        val arr = JSONArray(json)
        return (0 until arr.length()).map { i ->
            val o = arr.getJSONObject(i)
            SessionEntry(
                id = o.getString("id"),
                name = o.getString("name"),
                type = SessionType.valueOf(o.getString("type")),
                commandPath = o.optString("commandPath").takeIf { o.get("commandPath") != JSONObject.NULL },
                filePath = o.optString("filePath").takeIf { o.get("filePath") != JSONObject.NULL },
                fileName = o.optString("fileName").takeIf { o.get("fileName") != JSONObject.NULL },
                workingDirectory = if (o.has("workingDirectory"))
                    o.optString("workingDirectory").takeIf { o.get("workingDirectory") != JSONObject.NULL }
                else null,
                useRoot = o.optBoolean("useRoot", false),
                isFavorite = o.optBoolean("isFavorite", false),
                isDefault = o.optBoolean("isDefault", false),
                allowMultipleInstances = o.optBoolean("allowMultipleInstances", false),
                imageUri = if (o.has("imageUri"))
                    o.optString("imageUri").takeIf { o.get("imageUri") != JSONObject.NULL }
                else null
            )
        }
    }
}
