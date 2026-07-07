package dev.herdr.mobile.push

import dev.herdr.mobile.net.json
import kotlinx.serialization.Serializable

@Serializable
data class PushPayload(
    val kind: String,
    val paneId: String = "",
    val workspaceId: String = "",
    val title: String = "",
    val body: String = "",
)

fun parsePush(bytes: ByteArray): PushPayload? = try {
    json.decodeFromString(PushPayload.serializer(), String(bytes))
} catch (e: Exception) { null }
