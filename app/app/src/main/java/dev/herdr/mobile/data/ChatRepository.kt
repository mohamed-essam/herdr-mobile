package dev.herdr.mobile.data

import dev.herdr.mobile.net.ClientMsg
import dev.herdr.mobile.net.ServerFrame
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/** Decoded images kept in memory (LRU), across panes. */
const val IMAGE_CACHE_SIZE = 30
/** chat_image requests in flight at once; each reply can be ~5 MB. */
const val MAX_IMAGE_REQUESTS = 3
/** An image can reach the companion after its event: a `missing` is retried once after this. */
const val IMAGE_RETRY_MS = 3_000L

/**
 * Per-pane chat state. Opened panes are re-subscribed after a reconnect; the
 * companion answers every chat_open with a fresh snapshot.
 */
class ChatRepository(
    private val sendRaw: (String) -> Unit,
    private val sendChat: suspend (paneId: String, text: String) -> Unit,
    private val now: () -> Long = System::currentTimeMillis,
    private val sendAnswer: suspend (paneId: String, toolUseId: String, answers: Map<String, String>) -> Unit = { _, _, _ -> },
    /** Runs the delayed image retries. */
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
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
            is ServerFrame.ChatHistoryPage -> f.paneId
            is ServerFrame.ChatImageData -> return onImage(f)
            else -> return
        }
        val t = now()
        var gapped = false
        flow(paneId).update { v -> ChatReducer.onFrame(v, f, t).also { gapped = !v.gap && it.gap } }
        // A seq gap: re-open once; the companion answers with a fresh snapshot.
        if (gapped && paneId in opened) sendRaw(ClientMsg.chatOpen(paneId))
    }

    fun open(paneId: String) {
        opened += paneId
        sendRaw(ClientMsg.chatOpen(paneId))
    }

    fun close(paneId: String) {
        opened -= paneId
        sendRaw(ClientMsg.chatClose(paneId))
    }

    fun onReconnected() {
        opened.forEach { sendRaw(ClientMsg.chatOpen(it)) }
        // Replies to requests sent on the old socket will never come.
        val toSend = synchronized(imgLock) {
            imgInFlight.clear()
            imgQueue.clear()
            images.forEach { (k, e) -> if (e.flow.value == ImageState.Loading) imgQueue.addLast(k) }
            pumpLocked()
        }
        sendImages(toSend)
    }

    /**
     * Requests the page before the oldest loaded entry. A no-op unless the view
     * is loaded, the companion has more and no page is already in flight; a
     * lost reply is healed by the next snapshot (which clears loadingOlder).
     */
    fun loadOlder(paneId: String) {
        val reqId = "h${ids.incrementAndGet()}"
        var msg: String? = null
        flow(paneId).update { v ->
            if (!v.loaded || !v.hasMore || v.loadingOlder || v.gap) {
                msg = null
                v
            } else {
                val before = v.entries.firstOrNull()?.seq ?: (v.lastSeq + 1)
                msg = ClientMsg.chatHistory(reqId, paneId, v.epoch, before, HISTORY_PAGE_LIMIT)
                ChatReducer.startLoadingOlder(v)
            }
        }
        msg?.let(sendRaw)
    }

    suspend fun send(paneId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = "p${ids.incrementAndGet()}"
        flow(paneId).update { ChatReducer.addPending(it, id, t, now()) }
        try {
            sendChat(paneId, t)
        } catch (e: TimeoutCancellationException) {
            // The message may still have been queued; leave it Queued so it can
            // be confirmed by a late delivery or expire to NotDelivered.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow(paneId).update { ChatReducer.failPending(it, id, e.message ?: "send failed") }
        }
    }

    suspend fun retry(paneId: String, pendingId: String) {
        var text: String? = null
        flow(paneId).update { v ->
            val p = v.pending.firstOrNull { it.id == pendingId && it.status != PendingStatus.Queued }
            text = p?.text
            if (p == null) v else ChatReducer.removePending(v, pendingId)
        }
        text?.let { send(paneId, it) }
    }

    fun expirePending() {
        val t = now()
        views.values.forEach { f -> f.update { ChatReducer.expire(it, t) } }
    }

    /**
     * Answers an AskUserQuestion (question text -> answer). The question stays
     * marked answering until its tool_result arrives; a failure clears the mark
     * and throws with the companion's error code (see [answerErrorLabel]).
     * Ignored while an answer is in flight or after the question was answered.
     */
    suspend fun answer(paneId: String, toolUseId: String, answers: Map<String, String>) {
        var go = false
        flow(paneId).update { v ->
            go = toolUseId !in v.answering && toolUseId !in v.answered
            if (go) ChatReducer.markAnswering(v, toolUseId) else v
        }
        if (!go) return
        try {
            sendAnswer(paneId, toolUseId, answers)
        } catch (e: Throwable) {
            flow(paneId).update { ChatReducer.clearAnswering(it, toolUseId) }
            if (e is TimeoutCancellationException) throw RuntimeException("timed out", e)
            throw e
        }
    }

    // ---- images ----

    private data class ImgKey(val paneId: String, val id: String)
    private class ImgEntry {
        val flow = MutableStateFlow<ImageState>(ImageState.Loading)
        var retried = false
    }

    private val imgLock = Any()
    private val images = object : LinkedHashMap<ImgKey, ImgEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<ImgKey, ImgEntry>?) = size > IMAGE_CACHE_SIZE
    }
    private val imgQueue = ArrayDeque<ImgKey>()
    private val imgInFlight = HashSet<ImgKey>()

    /** The image's state; the first call for an id (or after eviction) requests it. */
    fun imageState(paneId: String, id: String): StateFlow<ImageState> {
        val key = ImgKey(paneId, id)
        val entry: ImgEntry
        val toSend = synchronized(imgLock) {
            images[key]?.let { return it.flow }
            entry = ImgEntry()
            images[key] = entry
            imgQueue.addLast(key)
            pumpLocked()
        }
        sendImages(toSend)
        return entry.flow
    }

    private fun onImage(f: ServerFrame.ChatImageData) {
        val key = ImgKey(f.paneId, f.id)
        var retry: ImgEntry? = null
        val toSend = synchronized(imgLock) {
            imgInFlight.remove(key)
            val e = images[key]
            if (e != null && e.flow.value == ImageState.Loading) {
                when {
                    f.missing && !e.retried -> { e.retried = true; retry = e }
                    f.missing || f.data == null -> e.flow.value = ImageState.Missing
                    else -> e.flow.value = decode(f.data)
                }
            }
            pumpLocked()
        }
        sendImages(toSend)
        val e = retry ?: return
        scope.launch {
            delay(IMAGE_RETRY_MS)
            val again = synchronized(imgLock) {
                if (images.containsKey(key) && e.flow.value == ImageState.Loading && key !in imgQueue) imgQueue.addLast(key)
                pumpLocked()
            }
            sendImages(again)
        }
    }

    private fun decode(data: String): ImageState =
        try { ImageState.Ready(Base64.getDecoder().decode(data)) } catch (e: IllegalArgumentException) { ImageState.Missing }

    /** Moves queued ids into free request slots; returns what to send (outside the lock). */
    private fun pumpLocked(): List<ImgKey> {
        val out = mutableListOf<ImgKey>()
        while (imgInFlight.size < MAX_IMAGE_REQUESTS && imgQueue.isNotEmpty()) {
            val k = imgQueue.removeFirst()
            // Not images[k]: a lookup would count as a use in the access-ordered LRU.
            if (k in imgInFlight || !images.containsKey(k)) continue
            imgInFlight += k
            out += k
        }
        return out
    }

    private fun sendImages(keys: List<ImgKey>) = keys.forEach { sendRaw(ClientMsg.chatImage(it.paneId, it.id)) }
}
