package dev.herdr.mobile.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
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
private val PUSH_ENABLED_KEY = booleanPreferencesKey("push_enabled")
private val SERVERS_KEY = stringPreferencesKey("servers")
private val ACTIVE_SERVER_KEY = stringPreferencesKey("active_server")
private fun endpointKey(serverId: String) = stringPreferencesKey("push_endpoint:$serverId")

/**
 * Whether UnifiedPush should be registered. Installs from before the opt-in
 * toggle never stored a choice: they keep push if they already had a
 * companion, so an upgrade doesn't silently stop notifications.
 */
fun pushEnabled(stored: Boolean?, hasCompanionUrl: Boolean): Boolean = stored ?: hasCompanionUrl

/** MRU update: most-recent first, de-duplicated, capped. */
fun updatedMru(current: List<String>, picked: String, cap: Int = 5): List<String> =
    (listOf(picked) + current.filterNot { it == picked }).take(cap)

class Settings(private val context: Context) {
    val companionUrl: Flow<String?> = context.dataStore.data.map { it[URL_KEY] }
    suspend fun setCompanionUrl(url: String) { context.dataStore.edit { it[URL_KEY] = url } }

    val pushEndpoint: Flow<String?> = context.dataStore.data.map { it[PUSH_ENDPOINT_KEY] }
    suspend fun setPushEndpoint(endpoint: String) { context.dataStore.edit { it[PUSH_ENDPOINT_KEY] = endpoint } }

    val servers: Flow<List<Server>> = context.dataStore.data.map { decodeServers(it[SERVERS_KEY]) }
    val activeServerId: Flow<String?> = context.dataStore.data.map { it[ACTIVE_SERVER_KEY] }

    suspend fun setServers(servers: List<Server>, activeId: String?) {
        context.dataStore.edit {
            it[SERVERS_KEY] = encodeServers(servers)
            if (activeId == null) it.remove(ACTIVE_SERVER_KEY) else it[ACTIVE_SERVER_KEY] = activeId
        }
    }

    suspend fun setActiveServer(id: String) { context.dataStore.edit { it[ACTIVE_SERVER_KEY] = id } }

    /** Appends [server] and makes it active. */
    suspend fun addServer(server: Server) {
        context.dataStore.edit {
            it[SERVERS_KEY] = encodeServers(decodeServers(it[SERVERS_KEY]) + server)
            it[ACTIVE_SERVER_KEY] = server.id
        }
    }

    fun pushEndpoint(serverId: String): Flow<String?> = context.dataStore.data.map { it[endpointKey(serverId)] }
    suspend fun setPushEndpoint(serverId: String, endpoint: String) {
        context.dataStore.edit { it[endpointKey(serverId)] = endpoint }
    }
    suspend fun removePushEndpoint(serverId: String) { context.dataStore.edit { it.remove(endpointKey(serverId)) } }

    /**
     * Moves the single-companion prefs to the server list, once: a no-op when
     * `servers` exists. Both the activity and the push receiver call it, since
     * a push can arrive before an upgraded app is opened.
     */
    suspend fun migrate() {
        context.dataStore.edit { prefs ->
            if (prefs[SERVERS_KEY] != null) return@edit
            val m = migrateLegacy(prefs[URL_KEY], prefs[PUSH_ENDPOINT_KEY])
            prefs[SERVERS_KEY] = encodeServers(m.servers)
            m.activeId?.let { prefs[ACTIVE_SERVER_KEY] = it }
            m.endpoint?.let { prefs[endpointKey(LEGACY_SERVER_ID)] = it }
            prefs.remove(URL_KEY)
            prefs.remove(PUSH_ENDPOINT_KEY)
        }
    }

    /** The raw opt-in; null when never chosen. Resolve with [pushEnabled]. */
    val pushEnabledChoice: Flow<Boolean?> = context.dataStore.data.map { it[PUSH_ENABLED_KEY] }
    suspend fun setPushEnabled(enabled: Boolean) { context.dataStore.edit { it[PUSH_ENABLED_KEY] = enabled } }

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
