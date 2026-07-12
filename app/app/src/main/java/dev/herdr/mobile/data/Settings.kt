package dev.herdr.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore("settings")
private val URL_KEY = stringPreferencesKey("companion_url")
private val PUSH_ENDPOINT_KEY = stringPreferencesKey("push_endpoint")
private val FONT_SIZE_KEY = intPreferencesKey("terminal_font_size")
private val RECENT_AGENTS_KEY = stringPreferencesKey("recent_agents")

/** MRU update: most-recent first, de-duplicated, capped. */
fun updatedMru(current: List<String>, picked: String, cap: Int = 5): List<String> =
    (listOf(picked) + current.filterNot { it == picked }).take(cap)

class Settings(private val context: Context) {
    val companionUrl: Flow<String?> = context.dataStore.data.map { it[URL_KEY] }
    suspend fun setCompanionUrl(url: String) { context.dataStore.edit { it[URL_KEY] = url } }

    val pushEndpoint: Flow<String?> = context.dataStore.data.map { it[PUSH_ENDPOINT_KEY] }
    suspend fun setPushEndpoint(endpoint: String) { context.dataStore.edit { it[PUSH_ENDPOINT_KEY] = endpoint } }

    val terminalFontSize: Flow<Int?> = context.dataStore.data.map { it[FONT_SIZE_KEY] }
    suspend fun setTerminalFontSize(px: Int) { context.dataStore.edit { it[FONT_SIZE_KEY] = px } }

    val recentAgents: Flow<List<String>> =
        context.dataStore.data.map { it[RECENT_AGENTS_KEY]?.split("\n")?.filter { s -> s.isNotBlank() } ?: emptyList() }

    suspend fun addRecentAgent(name: String) {
        context.dataStore.edit { prefs ->
            val cur = prefs[RECENT_AGENTS_KEY]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
            prefs[RECENT_AGENTS_KEY] = updatedMru(cur, name).joinToString("\n")
        }
    }
}
