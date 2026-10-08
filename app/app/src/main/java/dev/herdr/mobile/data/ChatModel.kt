package dev.herdr.mobile.data

import dev.herdr.mobile.net.AgentSummary
import dev.herdr.mobile.net.BgTask
import dev.herdr.mobile.net.ChatEntry
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.ServerFrame
import dev.herdr.mobile.net.SlashCommand
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

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
    /** The companion has events older than [entries]' first one. */
    val hasMore: Boolean = false,
    /** A chat_history request is in flight. */
    val loadingOlder: Boolean = false,
    /** reqId of the chat_history request in flight; only its page is accepted. */
    val olderReqId: String? = null,
    /** toolUseIds of shown questions whose tool_result arrived (the card collapses). */
    val answered: Set<String> = emptySet(),
    /** toolUseIds with an answer sent from the phone and no tool_result yet. */
    val answering: Set<String> = emptySet(),
    /**
     * Seqs where a page of older history was joined on (each was the first
     * entry before that page landed). The timeline doesn't fold across them,
     * so rows already on screen keep their keys and heights.
     */
    val pageStarts: Set<Int> = emptySet(),
    /** The pane's subagents and workflows by agentId; kept up to date on the main view only. */
    val agents: Map<String, AgentSummary> = emptyMap(),
    /** The pane's background tasks (main view only). */
    val tasks: List<BgTask> = emptyList(),
    /** The session's slash commands (main view only); empty: no picker. */
    val commands: List<SlashCommand> = emptyList(),
    /** A thread's snapshot said the companion doesn't know that agent. */
    val missing: Boolean = false,
)

const val PENDING_TIMEOUT_MS = 120_000L
/** Matches the companion's ring, so paging back through it is never trimmed away. */
const val MAX_ENTRIES = 5000
const val HISTORY_PAGE_LIMIT = 300

sealed interface ImageState {
    data object Loading : ImageState
    class Ready(val bytes: ByteArray) : ImageState
    data object Missing : ImageState
}

object ChatReducer {
    /**
     * Applies a frame. [now] stamps a working -> idle transition. A chat_event
     * in the current epoch whose seq skips ahead (the companion dropped
     * updates for a full subscriber), or that belongs to a newer epoch (its
     * snapshot was dropped), sets [ChatView.gap]; later events are
     * ignored until a snapshot heals the view. The caller re-opens the pane.
     */
    fun onFrame(v: ChatView, f: ServerFrame, now: Long): ChatView = when (f) {
        is ServerFrame.ChatSnapshot -> {
            val all = dedupQuestions(f.entries)
            val entries = all.takeLast(MAX_ENTRIES)
            val answered = answeredIds(entries)
            withState(v, f.state, now).copy(
                loaded = true,
                epoch = f.epoch,
                entries = entries,
                lastSeq = maxOf(f.entries.maxOfOrNull { it.seq } ?: 0, f.maxSeq),
                pending = confirm(v.pending, snapshotCandidates(v, f)),
                gap = false,
                hasMore = f.hasMore || entries.size < all.size,
                loadingOlder = false,
                olderReqId = null,
                answered = answered,
                pageStarts = emptySet(),
                // A thread's snapshot sends neither: keep what the view has.
                agents = f.agents?.associateBy { it.agentId } ?: v.agents,
                tasks = f.tasks ?: v.tasks,
                commands = f.commands ?: v.commands,
                missing = f.missing,
                // A new epoch (companion restart / new session) dropped any held answer.
                answering = if (v.loaded && f.epoch == v.epoch) v.answering - answeredResults(entries) else emptySet(),
            )
        }
        is ServerFrame.ChatEventFrame -> {
            val e = f.entry
            when {
                !v.loaded || v.gap -> v
                // The new epoch's snapshot was dropped: heal like a seq gap.
                f.epoch > v.epoch -> v.copy(gap = true)
                f.epoch != v.epoch || f.seq <= v.lastSeq -> v
                f.seq != v.lastSeq + 1 -> v.copy(gap = true)
                // An event of a type this app doesn't know: not shown, but
                // its seq counts, so the next event is no gap.
                e == null -> v.copy(lastSeq = f.seq)
                else -> append(v, e)
            }
        }
        is ServerFrame.ChatHistoryPage -> {
            val first = v.entries.firstOrNull()?.seq ?: (v.lastSeq + 1)
            when {
                // Only the reply to the request in flight counts: a snapshot
                // since then replaced the entries the page was meant to extend.
                !v.loaded || !v.loadingOlder || f.reqId != v.olderReqId -> v
                f.stale || f.epoch != v.epoch -> v.copy(loadingOlder = false, olderReqId = null)
                // A page must end right before the first entry, or it would leave a hole.
                f.entries.isNotEmpty() && f.entries.maxOf { it.seq } != first - 1 ->
                    v.copy(loadingOlder = false, olderReqId = null)
                else -> {
                    val entries = dedupQuestions(f.entries.filter { it.seq < first }.sortedBy { it.seq } + v.entries)
                    v.copy(
                        entries = entries,
                        hasMore = f.hasMore,
                        loadingOlder = false,
                        olderReqId = null,
                        answered = answeredIds(entries),
                        answering = v.answering - answeredResults(entries),
                        pageStarts = if (f.entries.isNotEmpty() && v.entries.isNotEmpty()) v.pageStarts + first else v.pageStarts,
                    )
                }
            }
        }
        is ServerFrame.ChatState -> withState(v, f.state, now)
        is ServerFrame.ChatAgent -> when {
            f.agent != null -> v.copy(agents = v.agents + (f.agent.agentId to f.agent))
            f.removedId != null -> v.copy(agents = v.agents - f.removedId)
            else -> v
        }
        is ServerFrame.ChatTasks -> v.copy(tasks = f.tasks)
        is ServerFrame.ChatCommands -> v.copy(commands = f.commands)
        else -> v
    }

    private fun append(v: ChatView, e: ChatEntry): ChatView {
        val ev = e.event
        // A question repeated after a resync is shown once, where it first appeared.
        if (ev is ChatEvent.Question && v.entries.any { (it.event as? ChatEvent.Question)?.toolUseId == ev.toolUseId })
            return v.copy(lastSeq = e.seq)
        val all = v.entries + e
        val entries = all.takeLast(MAX_ENTRIES)
        val id = when (ev) {
            is ChatEvent.ToolResult -> ev.toolUseId.takeIf { id -> entries.any { (it.event as? ChatEvent.Question)?.toolUseId == id } }
            is ChatEvent.Question -> ev.toolUseId.takeIf { id -> entries.any { (it.event as? ChatEvent.ToolResult)?.toolUseId == id } }
            else -> null
        }
        return v.copy(
            entries = entries,
            lastSeq = e.seq,
            pending = confirm(v.pending, listOfNotNull(userText(e))),
            hasMore = v.hasMore || entries.size < all.size,
            answered = if (id != null) v.answered + id else v.answered,
            answering = if (ev is ChatEvent.ToolResult) v.answering - ev.toolUseId else v.answering,
        )
    }

    fun startLoadingOlder(v: ChatView, reqId: String): ChatView = v.copy(loadingOlder = true, olderReqId = reqId)

    /** The page for [reqId] never came: allow paging again (a later reply is ignored). */
    fun olderTimedOut(v: ChatView, reqId: String): ChatView =
        if (v.loadingOlder && v.olderReqId == reqId) v.copy(loadingOlder = false, olderReqId = null) else v

    fun markAnswering(v: ChatView, toolUseId: String): ChatView = v.copy(answering = v.answering + toolUseId)

    fun clearAnswering(v: ChatView, toolUseId: String): ChatView = v.copy(answering = v.answering - toolUseId)

    private fun dedupQuestions(entries: List<ChatEntry>): List<ChatEntry> {
        val seen = HashSet<String>()
        return entries.filter { e -> (e.event as? ChatEvent.Question)?.let { seen.add(it.toolUseId) } ?: true }
    }

    private fun answeredResults(entries: List<ChatEntry>): Set<String> =
        entries.mapNotNullTo(HashSet()) { (it.event as? ChatEvent.ToolResult)?.toolUseId }

    private fun answeredIds(entries: List<ChatEntry>): Set<String> {
        val results = answeredResults(entries)
        return entries.mapNotNullTo(HashSet()) { e ->
            (e.event as? ChatEvent.Question)?.toolUseId?.takeIf { it in results }
        }
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

    // A slash command sent from the phone comes back as its output row.
    private fun userText(e: ChatEntry): String? = when (val ev = e.event) {
        is ChatEvent.UserText -> ev.text.trim()
        is ChatEvent.CommandOutput -> ev.command.trim()
        else -> null
    }

    // A command comes back as `/name args`, however the space after its name was typed.
    private fun sameText(a: String, b: String): Boolean =
        a == b || (a.startsWith("/") && b.startsWith("/") && a.replaceFirst(Regex("\\s+"), " ") == b.replaceFirst(Regex("\\s+"), " "))

    // Each delivered text confirms the oldest unconfirmed pending bubble with
    // the same text, so sending "yes" twice needs two deliveries.
    private fun confirm(pending: List<PendingMsg>, texts: List<String>): List<PendingMsg> {
        if (pending.isEmpty() || texts.isEmpty()) return pending
        val out = pending.toMutableList()
        for (t in texts) {
            val i = out.indexOfFirst { it.status != PendingStatus.Failed && sameText(it.text, t) }
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

/**
 * scrollToItem offset that lands on the END of an item (Compose clamps it), so a long
 * last message shows its tail rather than its top.
 */
const val BOTTOM_OFFSET = Int.MAX_VALUE

/** The item to jump to when a chat screen is entered, or null (not ready / already done). */
fun entryScrollTarget(loaded: Boolean, itemCount: Int, done: Boolean): Int? =
    if (loaded && itemCount > 0 && !done) itemCount - 1 else null

/**
 * Whether the chat keeps following new output: at the very end it does;
 * scrolling away from the end stops it; new output growing below the end
 * while the list is at rest keeps the current choice.
 */
fun followAfterScroll(follow: Boolean, scrolling: Boolean, canScrollForward: Boolean): Boolean = when {
    !canScrollForward -> true
    scrolling -> false
    else -> follow
}

fun taskNoticeLabel(n: ChatEvent.TaskNotice): String = when {
    n.summary.isNotBlank() -> "⚙ ${n.summary.trim()}"
    n.status.isNotBlank() -> "⚙ background task ${n.status.trim()}"
    else -> "⚙ background task"
}

fun taskNoticeIsError(n: ChatEvent.TaskNotice): Boolean = n.status == "failed" || n.status == "killed"

/** A multi-select answer: the chosen labels joined the way AskUserQuestion expects. */
fun joinAnswerLabels(labels: List<String>): String = labels.joinToString(", ")

fun answerErrorLabel(error: String?): String = when (error) {
    "no_mod" -> "Claude isn't connected"
    "no_question" -> "the question is no longer waiting"
    "empty" -> "empty answer"
    else -> "answer failed: ${error ?: "error"}"
}

/** `HH:mm` when [ts] falls on [now]'s day in [zone], `MMM d HH:mm` otherwise. */
fun formatTs(ts: Long, now: Long, zone: ZoneId, locale: Locale): String {
    val t = Instant.ofEpochMilli(ts).atZone(zone)
    val sameDay = t.toLocalDate() == Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    return DateTimeFormatter.ofPattern(if (sameDay) "HH:mm" else "MMM d HH:mm", locale).format(t)
}
