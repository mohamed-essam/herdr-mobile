package dev.herdr.mobile.ui

import dev.herdr.mobile.net.ChatEntry
import dev.herdr.mobile.net.ChatEvent

/** Who a timeline row belongs to; a change of speaker starts a new block. */
enum class Speaker { User, Agent, Notice }

/**
 * One row of the chat timeline. [head] marks the first row of a speaker's
 * block: it carries the gutter timestamp and the "you"/"claude" label.
 */
sealed interface TimelineItem {
    val key: String
    val speaker: Speaker
    val head: Boolean

    /** A message, notice or question. */
    data class Event(override val key: String, val entry: ChatEntry, override val speaker: Speaker, override val head: Boolean) : TimelineItem

    /** Consecutive tool calls, folded onto one rail. */
    data class Tools(override val key: String, val tools: List<ChatEntry>, override val head: Boolean) : TimelineItem {
        override val speaker get() = Speaker.Agent
    }
}

private fun speakerOf(ev: ChatEvent): Speaker = when (ev) {
    is ChatEvent.UserText -> Speaker.User
    is ChatEvent.TaskNotice -> Speaker.Notice
    else -> Speaker.Agent
}

/**
 * The timeline rows for [entries]: tool results are dropped (they show under
 * their call), an AskUserQuestion's own tool call is dropped (its question
 * shows instead), and runs of tool calls fold into one [TimelineItem.Tools]
 * keyed by its first call, so appending calls keeps the row's key.
 */
fun buildTimeline(entries: List<ChatEntry>, epoch: Int): List<TimelineItem> {
    val questions = entries.mapNotNullTo(HashSet()) { (it.event as? ChatEvent.Question)?.toolUseId }
    val out = ArrayList<TimelineItem>()
    var run = ArrayList<ChatEntry>()
    var prev: Speaker? = null
    fun head(s: Speaker) = (s != prev || s == Speaker.Notice).also { prev = s }
    fun flush() {
        if (run.isEmpty()) return
        out += TimelineItem.Tools("e$epoch-${run.first().seq}", run, head(Speaker.Agent))
        run = ArrayList()
    }
    for (e in entries) {
        val ev = e.event
        if (ev is ChatEvent.ToolResult) continue
        if (ev is ChatEvent.ToolUse) {
            if (ev.toolUseId !in questions) run += e
            continue
        }
        flush()
        val s = speakerOf(ev)
        out += TimelineItem.Event("e$epoch-${e.seq}", e, s, head(s))
    }
    flush()
    return out
}

/**
 * The tool calls still running: those without a result in the newest row,
 * while the agent is working. Older calls without one were cut off.
 */
fun runningTools(items: List<TimelineItem>, resultIds: Set<String>, working: Boolean): Set<String> {
    if (!working) return emptySet()
    val last = items.lastOrNull() as? TimelineItem.Tools ?: return emptySet()
    return last.tools.mapNotNullTo(HashSet()) { e ->
        (e.event as? ChatEvent.ToolUse)?.toolUseId?.takeIf { it !in resultIds }
    }
}

private val FILE_TOOLS = setOf("Read", "Edit", "Write", "NotebookEdit", "MultiEdit")

/**
 * A tool call as one rail line: "Read: /home/me/app/ui/X.kt" becomes
 * "Read ui/X.kt" (file tools keep the last two path segments).
 */
fun toolLine(tool: String, summary: String): String {
    if (!summary.startsWith("$tool:")) return summary.ifBlank { tool }
    val detail = summary.removePrefix("$tool:").trim()
    if (tool !in FILE_TOOLS) return "$tool $detail"
    val parts = detail.split('/').filter { it.isNotEmpty() }
    return "$tool ${if (parts.size > 2) parts.takeLast(2).joinToString("/") else detail}"
}

/**
 * The question waiting on the user: the newest unanswered AskUserQuestion with
 * no message after it (a later message means the agent moved on).
 */
fun pendingQuestion(entries: List<ChatEntry>, answered: Set<String>): ChatEvent.Question? {
    for (e in entries.asReversed()) {
        when (val ev = e.event) {
            is ChatEvent.Question -> return ev.takeIf { it.toolUseId !in answered }
            is ChatEvent.UserText, is ChatEvent.AssistantText -> return null
            else -> {}
        }
    }
    return null
}

enum class ChatStatusKind { Offline, Waiting, Working, Idle }

/** The header's live status: [label] ends the breadcrumb, [kind] picks its color. */
data class ChatStatus(val label: String, val kind: ChatStatusKind)

fun chatStatus(connected: Boolean, state: String, questionPending: Boolean): ChatStatus = when {
    !connected -> ChatStatus("reconnecting…", ChatStatusKind.Offline)
    questionPending -> ChatStatus("waiting on you", ChatStatusKind.Waiting)
    state == "working" -> ChatStatus("working", ChatStatusKind.Working)
    else -> ChatStatus("idle", ChatStatusKind.Idle)
}
