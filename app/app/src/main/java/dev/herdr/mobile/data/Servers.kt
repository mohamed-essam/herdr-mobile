package dev.herdr.mobile.data

import dev.herdr.mobile.net.json
import dev.herdr.mobile.ui.hostPort
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer

/** A saved companion. [id] is also its UnifiedPush instance name. */
@Serializable
data class Server(val id: String, val name: String, val url: String)

/**
 * The UnifiedPush connector's default instance. The one companion of an
 * install from before multiple servers gets it, so its push registration and
 * endpoint carry over without registering again.
 */
const val LEGACY_SERVER_ID = "default"

private val serverList = ListSerializer(Server.serializer())

fun encodeServers(servers: List<Server>): String = json.encodeToString(serverList, servers)

/** A missing or corrupt value reads as no servers, never a crash at launch. */
fun decodeServers(raw: String?): List<Server> =
    if (raw.isNullOrBlank()) emptyList()
    else runCatching { json.decodeFromString(serverList, raw) }.getOrDefault(emptyList())

/** The name a server has when the user gives none: its host:port. */
fun defaultServerName(url: String): String = hostPort(url)

/** What the single-companion prefs become: the servers, the active id, and the endpoint to move. */
data class Migration(val servers: List<Server>, val activeId: String?, val endpoint: String?)

/** Which endpoint the `default` instance keeps: one already stored there is newer than the legacy one. */
fun winningEndpoint(stored: String?, legacy: String?): String? = stored ?: legacy

fun migrateLegacy(url: String?, endpoint: String?): Migration =
    if (url.isNullOrBlank()) Migration(emptyList(), null, null)
    else Migration(listOf(Server(LEGACY_SERVER_ID, defaultServerName(url), url)), LEGACY_SERVER_ID, endpoint)

/** Renames [id]; a blank name falls back to the host. */
fun List<Server>.withRenamed(id: String, name: String): List<Server> =
    map { if (it.id == id) it.copy(name = name.trim().ifBlank { defaultServerName(it.url) }) else it }

/** The servers left after a removal, and which is active: unchanged, or the first left when it was removed. */
data class Removal(val servers: List<Server>, val activeId: String?)

fun List<Server>.withRemoved(id: String, activeId: String?): Removal {
    val left = filterNot { it.id == id }
    val active = if (left.any { it.id == activeId }) activeId else left.firstOrNull()?.id
    return Removal(left, active)
}

/** The server already saved at [url]'s host:port (case-insensitive), so one companion isn't saved twice. */
fun List<Server>.savedAt(url: String): Server? =
    firstOrNull { hostPort(it.url).equals(hostPort(url), ignoreCase = true) }

/** What tapping a notification from a server does. */
sealed interface ServerOpen {
    /** The active server, or a notification posted before servers had ids. */
    data object Stay : ServerOpen
    data class Switch(val server: Server) : ServerOpen
    /** Removed since: just open the app. */
    data object Unknown : ServerOpen
}

fun resolveServerOpen(serverId: String?, activeId: String?, servers: List<Server>): ServerOpen = when {
    serverId == null || serverId == activeId -> ServerOpen.Stay
    else -> servers.firstOrNull { it.id == serverId }?.let { ServerOpen.Switch(it) } ?: ServerOpen.Unknown
}
