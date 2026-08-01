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
        val json = prefs[sessionsKey]
        if (json.isNullOrBlank()) {
            listOf(SessionEntry.defaultAndroidShell())
        } else {
            decode(json)
        }
    }

    suspend fun currentSessions(): List<SessionEntry> = sessions.first()

    suspend fun save(entry: SessionEntry) {
        val current = currentSessions().toMutableList()
        val idx = current.indexOfFirst { it.id == entry.id }
        if (idx >= 0) current[idx] = entry else current.add(entry)
        persist(current)
    }

    suspend fun delete(id: String) {
        val current = currentSessions().toMutableList()
        current.removeAll { it.id == id }
        // Enforce: at least one default session must always remain.
        if (current.none { it.isDefault }) {
            if (current.isEmpty()) {
                current.add(SessionEntry.defaultAndroidShell())
            } else {
                val first = current[0]
                current[0] = first.copy(isDefault = true)
            }
        }
        persist(current)
    }

    suspend fun setDefault(id: String) {
        val current = currentSessions().map { it.copy(isDefault = it.id == id) }
        persist(current)
    }

    suspend fun setFavorite(id: String, favorite: Boolean) {
        val current = currentSessions().map {
            if (it.id == id) it.copy(isFavorite = favorite) else it
        }
        persist(current)
    }

    private suspend fun persist(list: List<SessionEntry>) {
        context.sessionDataStore.edit { prefs ->
            prefs[sessionsKey] = encode(list)
        }
    }

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
                allowMultipleInstances = o.optBoolean("allowMultipleInstances", false)
            )
        }
    }
}
