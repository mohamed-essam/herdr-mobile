package dev.herdr.mobile.data

import dev.herdr.mobile.net.ChatEntry
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.ServerFrame

enum class PendingStatus { Queued, Failed, NotDelivered }

data class PendingMsg(
    val id: String,
    val text: String,
    val sentAt: Long,
    val status: PendingStatus = PendingStatus.Queued,
    val error: String? = null,
)

data class ChatView(
    val loaded: Boolean = false,
    val epoch: Int = 0,
    val state: String = "idle",
    val entries: List<ChatEntry> = emptyList(),
    val lastSeq: Int = 0,
    val pending: List<PendingMsg> = emptyList(),
)

const val PENDING_TIMEOUT_MS = 120_000L
const val MAX_ENTRIES = 500

object ChatReducer {
    fun onFrame(v: ChatView, f: ServerFrame): ChatView = when (f) {
        is ServerFrame.ChatSnapshot -> v.copy(
            loaded = true,
            epoch = f.epoch,
            state = f.state,
            entries = f.entries.takeLast(MAX_ENTRIES),
            lastSeq = f.entries.maxOfOrNull { it.seq } ?: 0,
            pending = confirm(v.pending, snapshotCandidates(v, f)),
        )
        is ServerFrame.ChatEventFrame -> {
            val e = f.entry
            if (!v.loaded || f.epoch != v.epoch || e == null || e.seq <= v.lastSeq) v
            else v.copy(
                entries = (v.entries + e).takeLast(MAX_ENTRIES),
                lastSeq = e.seq,
                pending = confirm(v.pending, listOfNotNull(userText(e))),
            )
        }
        is ServerFrame.ChatState -> v.copy(state = f.state)
        else -> v
    }

    fun addPending(v: ChatView, id: String, text: String, now: Long): ChatView =
        v.copy(pending = v.pending + PendingMsg(id, text.trim(), now))

    fun failPending(v: ChatView, id: String, error: String): ChatView =
        v.copy(pending = v.pending.map { if (it.id == id) it.copy(status = PendingStatus.Failed, error = error) else it })

    fun removePending(v: ChatView, id: String): ChatView =
        v.copy(pending = v.pending.filterNot { it.id == id })

    fun expire(v: ChatView, now: Long): ChatView {
        if (v.pending.none { it.status == PendingStatus.Queued && now - it.sentAt >= PENDING_TIMEOUT_MS }) return v
        return v.copy(pending = v.pending.map {
            if (it.status == PendingStatus.Queued && now - it.sentAt >= PENDING_TIMEOUT_MS) it.copy(status = PendingStatus.NotDelivered) else it
        })
    }

    // Same epoch: only entries newer than what we already had can be new
    // deliveries. New epoch / first load: only the trailing user_texts (one per
    // outstanding bubble) can be ours; older history must not confirm anything.
    private fun snapshotCandidates(v: ChatView, f: ServerFrame.ChatSnapshot): List<String> =
        if (v.loaded && f.epoch == v.epoch) f.entries.filter { it.seq > v.lastSeq }.mapNotNull { userText(it) }
        else f.entries.mapNotNull { userText(it) }.takeLast(v.pending.count { it.status != PendingStatus.Failed })

    private fun userText(e: ChatEntry): String? = (e.event as? ChatEvent.UserText)?.text?.trim()

    // Each delivered text confirms the oldest unconfirmed pending bubble with
    // the same text, so sending "yes" twice needs two deliveries.
    private fun confirm(pending: List<PendingMsg>, texts: List<String>): List<PendingMsg> {
        if (pending.isEmpty() || texts.isEmpty()) return pending
        val out = pending.toMutableList()
        for (t in texts) {
            val i = out.indexOfFirst { it.status != PendingStatus.Failed && it.text == t }
            if (i >= 0) out.removeAt(i)
        }
        return out
    }
}

fun pendingLabel(p: PendingMsg, state: String): String = when (p.status) {
    PendingStatus.Queued -> if (state == "working") "queued — Claude is busy" else "sending…"
    PendingStatus.Failed -> "failed: ${p.error ?: "error"}"
    PendingStatus.NotDelivered -> "not delivered"
}
