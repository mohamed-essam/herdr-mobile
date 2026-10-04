package dev.herdr.mobile.data

import dev.herdr.mobile.net.ClientMsg
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-pane chat state. Opened panes are re-subscribed after a reconnect; the
 * companion answers every chat_open with a fresh snapshot.
 */
class ChatRepository(
    private val sendRaw: (String) -> Unit,
    private val sendChat: suspend (paneId: String, text: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val views = ConcurrentHashMap<String, MutableStateFlow<ChatView>>()
    private val opened: MutableSet<String> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicInteger(0)

    private fun flow(paneId: String) = views.computeIfAbsent(paneId) { MutableStateFlow(ChatView()) }

    fun view(paneId: String): StateFlow<ChatView> = flow(paneId)

    fun onFrame(f: ServerFrame) {
        val paneId = when (f) {
            is ServerFrame.ChatSnapshot -> f.paneId
            is ServerFrame.ChatEventFrame -> f.paneId
            is ServerFrame.ChatState -> f.paneId
            else -> return
        }
        flow(paneId).update { ChatReducer.onFrame(it, f) }
    }

    fun open(paneId: String) {
        opened += paneId
        sendRaw(ClientMsg.chatOpen(paneId))
    }

    fun close(paneId: String) {
        opened -= paneId
        sendRaw(ClientMsg.chatClose(paneId))
    }

    fun onReconnected() = opened.forEach { sendRaw(ClientMsg.chatOpen(it)) }

    suspend fun send(paneId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = "p${ids.incrementAndGet()}"
        flow(paneId).update { ChatReducer.addPending(it, id, t, now()) }
        runCatching { sendChat(paneId, t) }
            .onFailure { e -> flow(paneId).update { ChatReducer.failPending(it, id, e.message ?: "send failed") } }
    }

    suspend fun retry(paneId: String, pendingId: String) {
        val p = flow(paneId).value.pending.firstOrNull { it.id == pendingId } ?: return
        flow(paneId).update { ChatReducer.removePending(it, pendingId) }
        send(paneId, p.text)
    }

    fun expirePending() {
        val t = now()
        views.values.forEach { f -> f.update { ChatReducer.expire(it, t) } }
    }
}
