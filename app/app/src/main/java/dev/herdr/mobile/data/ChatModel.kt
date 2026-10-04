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
    /** When the view last went working -> idle; pending timeouts count from here. */
    val idleSince: Long = 0L,
    /** A seq gap was seen in this epoch; events wait for the fresh snapshot. */
    val gap: Boolean = false,
)

const val PENDING_TIMEOUT_MS = 120_000L
const val MAX_ENTRIES = 500

object ChatReducer {
    /**
     * Applies a frame. [now] stamps a working -> idle transition. A chat_event
     * in the current epoch whose seq skips ahead (the companion dropped
     * updates for a full subscriber), or that belongs to a newer epoch (its
     * snapshot was dropped), sets [ChatView.gap]; later events are
     * ignored until a snapshot heals the view. The caller re-opens the pane.
     */
    fun onFrame(v: ChatView, f: ServerFrame, now: Long): ChatView = when (f) {
        is ServerFrame.ChatSnapshot -> withState(v, f.state, now).copy(
            loaded = true,
            epoch = f.epoch,
            entries = f.entries.takeLast(MAX_ENTRIES),
            lastSeq = f.entries.maxOfOrNull { it.seq } ?: 0,
            pending = confirm(v.pending, snapshotCandidates(v, f)),
            gap = false,
        )
        is ServerFrame.ChatEventFrame -> {
            val e = f.entry
            when {
                !v.loaded || v.gap -> v
                // The new epoch's snapshot was dropped: heal like a seq gap.
                f.epoch > v.epoch -> v.copy(gap = true)
                f.epoch != v.epoch || e == null || e.seq <= v.lastSeq -> v
                e.seq != v.lastSeq + 1 -> v.copy(gap = true)
                else -> v.copy(
                    entries = (v.entries + e).takeLast(MAX_ENTRIES),
                    lastSeq = e.seq,
                    pending = confirm(v.pending, listOfNotNull(userText(e))),
                )
            }
        }
        is ServerFrame.ChatState -> withState(v, f.state, now)
        else -> v
    }

    private fun withState(v: ChatView, state: String, now: Long): ChatView =
        if (v.state == "working" && state != "working") v.copy(state = state, idleSince = now)
        else v.copy(state = state)

    fun addPending(v: ChatView, id: String, text: String, now: Long): ChatView =
        v.copy(pending = v.pending + PendingMsg(id, text.trim(), now))

    fun failPending(v: ChatView, id: String, error: String): ChatView =
        v.copy(pending = v.pending.map { if (it.id == id) it.copy(status = PendingStatus.Failed, error = error) else it })

    fun removePending(v: ChatView, id: String): ChatView =
        v.copy(pending = v.pending.filterNot { it.id == id })

    // A message is held by the mod until Claude goes idle, so nothing expires
    // while working and the clock restarts when the view goes idle again.
    fun expire(v: ChatView, now: Long): ChatView {
        if (v.state == "working") return v
        fun overdue(p: PendingMsg) =
            p.status == PendingStatus.Queued && now - maxOf(p.sentAt, v.idleSince) >= PENDING_TIMEOUT_MS
        if (v.pending.none(::overdue)) return v
        return v.copy(pending = v.pending.map { if (overdue(it)) it.copy(status = PendingStatus.NotDelivered) else it })
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
    PendingStatus.Failed -> when (p.error) {
        "no_mod" -> "Claude isn't connected"
        "outbox_full" -> "too many queued messages"
        "empty" -> "empty message"
        else -> "failed: ${p.error ?: "error"}"
    }
    PendingStatus.NotDelivered -> "not delivered"
}

/** The item to jump to when a chat screen is entered, or null (not ready / already done). */
fun entryScrollTarget(loaded: Boolean, itemCount: Int, done: Boolean): Int? =
    if (loaded && itemCount > 0 && !done) itemCount - 1 else null

fun taskNoticeLabel(n: ChatEvent.TaskNotice): String = when {
    n.summary.isNotBlank() -> "⚙ ${n.summary.trim()}"
    n.status.isNotBlank() -> "⚙ background task ${n.status.trim()}"
    else -> "⚙ background task"
}

fun taskNoticeIsError(n: ChatEvent.TaskNotice): Boolean = n.status == "failed" || n.status == "killed"
