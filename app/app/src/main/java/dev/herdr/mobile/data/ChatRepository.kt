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
/** chat_image requests in flight at once; each reply can be up to ~3.5 MB. */
const val MAX_IMAGE_REQUESTS = 3
/** An image can reach the companion after its event: a `missing` is retried once after this. */
const val IMAGE_RETRY_MS = 3_000L
/** A chat_image / chat_history reply not seen by then is treated as lost. */
const val REPLY_TIMEOUT_MS = 15_000L

/** A chat stream: a pane's main one ([agentId] null) or one of its agent threads. */
data class ChatKey(val paneId: String, val agentId: String? = null)

/**
 * Per-pane (and per-thread) chat state. Opened panes are re-subscribed after a reconnect; the
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
    private val views = ConcurrentHashMap<ChatKey, MutableStateFlow<ChatView>>()
    private val opened: MutableSet<ChatKey> = ConcurrentHashMap.newKeySet()
    private val ids = AtomicInteger(0)

    private fun flow(key: ChatKey) = views.computeIfAbsent(key) { MutableStateFlow(ChatView()) }

    fun view(key: ChatKey): StateFlow<ChatView> = flow(key)
    fun view(paneId: String): StateFlow<ChatView> = view(ChatKey(paneId))

    fun onFrame(f: ServerFrame) {
        val key = when (f) {
            is ServerFrame.ChatSnapshot -> ChatKey(f.paneId, f.agentId)
            is ServerFrame.ChatEventFrame -> ChatKey(f.paneId, f.agentId)
            is ServerFrame.ChatState -> ChatKey(f.paneId)
            is ServerFrame.ChatHistoryPage -> ChatKey(f.paneId, f.agentId)
            is ServerFrame.ChatAgent -> ChatKey(f.paneId)
            is ServerFrame.ChatTasks -> ChatKey(f.paneId)
            is ServerFrame.ChatCommands -> ChatKey(f.paneId)
            is ServerFrame.ChatImageData -> return onImage(f)
            else -> return
        }
        val t = now()
        var gapped = false
        var newEpoch = false
        flow(key).update { v ->
            ChatReducer.onFrame(v, f, t).also {
                gapped = !v.gap && it.gap
                newEpoch = v.loaded && it.epoch != v.epoch
            }
        }
        // A seq gap: re-open once; the companion answers with a fresh snapshot.
        if (gapped && key in opened) sendRaw(ClientMsg.chatOpen(key.paneId, key.agentId))
        if (key.agentId == null) reopenThreads(key.paneId, f, newEpoch)
        // The companion may hold images now that it lacked before (a resync
        // re-sends the history's newest): give this pane's missing ones another go.
        if (f is ServerFrame.ChatSnapshot) {
            val toSend = synchronized(imgLock) {
                resetMissingLocked(key.paneId).forEach { if (it !in imgQueue && it !in imgInFlight) imgQueue.addLast(it) }
                pumpLocked()
            }
            sendImages(toSend)
        }
    }

    /**
     * The companion closes a pane's threads at a main resync and evicts old
     * ones; an open thread would then freeze. A new main epoch re-opens every
     * opened thread of the pane; an agent's upsert re-opens its thread when
     * that is opened and was answered missing.
     */
    private fun reopenThreads(paneId: String, f: ServerFrame, newEpoch: Boolean) {
        val again = when {
            f is ServerFrame.ChatSnapshot && newEpoch -> opened.filter { it.paneId == paneId && it.agentId != null }
            f is ServerFrame.ChatAgent && f.agent != null -> listOf(ChatKey(paneId, f.agent.agentId))
                .filter { it in opened && views[it]?.value?.missing == true }
            else -> emptyList()
        }
        again.forEach { sendRaw(ClientMsg.chatOpen(it.paneId, it.agentId)) }
    }

    fun open(key: ChatKey) {
        opened += key
        sendRaw(ClientMsg.chatOpen(key.paneId, key.agentId))
    }
    fun open(paneId: String) = open(ChatKey(paneId))

    fun close(key: ChatKey) {
        opened -= key
        sendRaw(ClientMsg.chatClose(key.paneId, key.agentId))
    }
    fun close(paneId: String) = close(ChatKey(paneId))

    fun onReconnected() {
        opened.forEach { sendRaw(ClientMsg.chatOpen(it.paneId, it.agentId)) }
        // Replies to requests sent on the old socket will never come.
        val toSend = synchronized(imgLock) {
            imgInFlight.clear()
            imgQueue.clear()
            resetMissingLocked(null)
            images.forEach { (k, e) -> if (e.flow.value == ImageState.Loading) imgQueue.addLast(k) }
            pumpLocked()
        }
        sendImages(toSend)
    }

    /**
     * Requests the page before the oldest loaded entry. A no-op unless the view
     * is loaded, the companion has more and no page is already in flight. A
     * reply that doesn't come within [REPLY_TIMEOUT_MS] (or a snapshot) clears
     * the request so paging can resume.
     */
    fun loadOlder(paneId: String) = loadOlder(ChatKey(paneId))

    fun loadOlder(key: ChatKey) {
        val reqId = "h${ids.incrementAndGet()}"
        var msg: String? = null
        flow(key).update { v ->
            if (!v.loaded || !v.hasMore || v.loadingOlder || v.gap) {
                msg = null
                v
            } else {
                val before = v.entries.firstOrNull()?.seq ?: (v.lastSeq + 1)
                msg = ClientMsg.chatHistory(reqId, key.paneId, v.epoch, before, HISTORY_PAGE_LIMIT, key.agentId)
                ChatReducer.startLoadingOlder(v, reqId)
            }
        }
        val m = msg ?: return
        sendRaw(m)
        scope.launch {
            delay(REPLY_TIMEOUT_MS)
            flow(key).update { ChatReducer.olderTimedOut(it, reqId) }
        }
    }

    suspend fun send(paneId: String, text: String) {
        val t = text.trim()
        if (t.isEmpty()) return
        val id = "p${ids.incrementAndGet()}"
        flow(ChatKey(paneId)).update { ChatReducer.addPending(it, id, t, now()) }
        try {
            sendChat(paneId, t)
        } catch (e: TimeoutCancellationException) {
            // The message may still have been queued; leave it Queued so it can
            // be confirmed by a late delivery or expire to NotDelivered.
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            flow(ChatKey(paneId)).update { ChatReducer.failPending(it, id, e.message ?: "send failed") }
        }
    }

    suspend fun retry(paneId: String, pendingId: String) {
        var text: String? = null
        flow(ChatKey(paneId)).update { v ->
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
        flow(ChatKey(paneId)).update { v ->
            go = toolUseId !in v.answering && toolUseId !in v.answered
            if (go) ChatReducer.markAnswering(v, toolUseId) else v
        }
        if (!go) return
        try {
            sendAnswer(paneId, toolUseId, answers)
        } catch (e: Throwable) {
            flow(ChatKey(paneId)).update { ChatReducer.clearAnswering(it, toolUseId) }
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
    /** In-flight requests, each with a token so a stale timeout can't touch a newer request. */
    private val imgInFlight = HashMap<ImgKey, Long>()
    private var imgTokens = 0L

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

    /**
     * Gives the Missing images of [paneId] (every pane when null) another
     * chance: one on screen goes back to Loading, with the retry-once rule
     * afresh, and is returned for the caller to request; any other is dropped
     * from the cache, so its next [imageState] requests it.
     */
    private fun resetMissingLocked(paneId: String?): List<ImgKey> {
        val again = mutableListOf<ImgKey>()
        // Iterating the entries is no access in the access-ordered LRU.
        val it = images.entries.iterator()
        while (it.hasNext()) {
            val (k, e) = it.next()
            if ((paneId != null && k.paneId != paneId) || e.flow.value != ImageState.Missing) continue
            if (e.flow.subscriptionCount.value > 0) {
                e.retried = false
                e.flow.value = ImageState.Loading
                again += k
            } else {
                it.remove()
            }
        }
        return again
    }

    private fun decode(data: String): ImageState =
        try { ImageState.Ready(Base64.getDecoder().decode(data)) } catch (e: IllegalArgumentException) { ImageState.Missing }

    /** Moves queued ids into free request slots; returns what to send (outside the lock). */
    private fun pumpLocked(): List<Pair<ImgKey, Long>> {
        val out = mutableListOf<Pair<ImgKey, Long>>()
        while (imgInFlight.size < MAX_IMAGE_REQUESTS && imgQueue.isNotEmpty()) {
            val k = imgQueue.removeFirst()
            // Not images[k]: a lookup would count as a use in the access-ordered LRU.
            if (k in imgInFlight || !images.containsKey(k)) continue
            val token = ++imgTokens
            imgInFlight[k] = token
            out += k to token
        }
        return out
    }

    /** Sends the requests; one unanswered within [REPLY_TIMEOUT_MS] counts as a `missing` reply. */
    private fun sendImages(reqs: List<Pair<ImgKey, Long>>) = reqs.forEach { (k, token) ->
        sendRaw(ClientMsg.chatImage(k.paneId, k.id))
        scope.launch {
            delay(REPLY_TIMEOUT_MS)
            val lost = synchronized(imgLock) { imgInFlight[k] == token }
            if (lost) onImage(ServerFrame.ChatImageData(k.paneId, k.id, null, null, missing = true))
        }
    }
}
