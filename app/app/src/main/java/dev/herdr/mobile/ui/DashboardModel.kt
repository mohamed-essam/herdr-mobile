package dev.herdr.mobile.ui

import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.PaneActivity
import dev.herdr.mobile.net.PaneAsk
import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.net.QuestionKind

/*
 * Pure logic behind the attention-first dashboard, the workspace switcher and
 * the action sheets: section ordering, the inline-answer state machine, search,
 * close summaries and display formatters.
 */

/** The dashboard's sections; every pane lands in exactly one, except a pane
 *  answered from the dashboard, which leaves [needsYou] while its "resumed" row shows. */
data class DashboardSections(
    val needsYou: List<Pane>,
    val working: List<Pane>,
    val done: List<Pane>,
    val idle: List<Pane>,
)

private fun Pane.activityTs(): Long = activity?.ts ?: 0L

/**
 * Splits [panes] by status. Needs-you is oldest-waiting first, working and done
 * most-recent first; panes without a known timestamp sort last. Idle holds idle
 * agents, then shells.
 */
fun dashboardSections(panes: List<Pane>, answers: InlineAnswers = emptyMap()): DashboardSections {
    val knownFirst = compareBy<Pane> { if (it.activityTs() > 0) 0 else 1 }
    val blocked = panes.filter { it.agent != null && it.agentStatus == "blocked" && !resumedHides(it, answers) }
        .sortedWith(knownFirst.thenBy { it.activityTs() }.thenBy { it.paneId })
    val recentFirst = knownFirst.thenByDescending<Pane> { it.activityTs() }.thenBy { it.paneId }
    val working = panes.filter { it.agent != null && it.agentStatus == "working" }.sortedWith(recentFirst)
    val done = panes.filter { it.agent != null && it.agentStatus == "done" }.sortedWith(recentFirst)
    val placed = (blocked + working + done).map { it.paneId }.toSet()
    val hidden = panes.filter { it.agent != null && it.agentStatus == "blocked" && resumedHides(it, answers) }
        .map { it.paneId }.toSet()
    val idle = panes.filter { it.paneId !in placed && it.paneId !in hidden }
        .sortedWith(compareBy<Pane> { if (it.agent != null) 0 else 1 }.thenBy { it.paneId })
    return DashboardSections(blocked, working, done, idle)
}

/** A blocked pane whose question was just answered here hides behind its "resumed" row,
 *  unless a new question has arrived since. */
private fun resumedHides(p: Pane, answers: InlineAnswers): Boolean {
    val a = answers[p.paneId] ?: return false
    return a.phase == AnswerPhase.Sent && (p.ask == null || p.ask.toolUseId == a.toolUseId)
}

enum class AnswerPhase { Sending, Sent }

/** One dashboard answer in flight or just confirmed. [at] is when it entered [phase]. */
data class InlineAnswer(
    val paneId: String,
    val toolUseId: String,
    val label: String,
    val phase: AnswerPhase,
    val at: Long,
)

/** Inline answers by paneId. A pane absent from the map is idle (its chips are live). */
typealias InlineAnswers = Map<String, InlineAnswer>

/** How long the "resumed" confirmation stays up. */
const val RESUMED_TTL_MS = 4_000L

/** Idle → Sending; null when that pane already has an answer sending. */
fun startAnswer(m: InlineAnswers, paneId: String, toolUseId: String, label: String, now: Long): InlineAnswers? {
    if (m[paneId]?.phase == AnswerPhase.Sending) return null
    return m + (paneId to InlineAnswer(paneId, toolUseId, label, AnswerPhase.Sending, now))
}

/** Sending → Sent. */
fun answerSent(m: InlineAnswers, paneId: String, now: Long): InlineAnswers {
    val a = m[paneId] ?: return m
    return m + (paneId to a.copy(phase = AnswerPhase.Sent, at = now))
}

/** Sending → Failed, which is idle again: the chips re-enable (the error shows as a snackbar). */
fun answerFailed(m: InlineAnswers, paneId: String): InlineAnswers = m - paneId

/** Drops Sent entries older than [ttl]. */
fun expireAnswers(m: InlineAnswers, now: Long, ttl: Long = RESUMED_TTL_MS): InlineAnswers =
    m.filterValues { it.phase != AnswerPhase.Sent || now - it.at < ttl }

/** Ms until the next Sent entry expires, or null when none is pending. */
fun nextAnswerExpiry(m: InlineAnswers, now: Long, ttl: Long = RESUMED_TTL_MS): Long? =
    m.values.filter { it.phase == AnswerPhase.Sent }.minOfOrNull { (it.at + ttl - now).coerceAtLeast(0) }

/**
 * The one question the dashboard can answer in place: a lone single-select
 * choice with options. Anything else (several questions, multi-select, text,
 * number) opens the pane.
 */
fun inlineChoice(ask: PaneAsk?): QuestionItem? {
    val items = ask?.items ?: return null
    val q = items.singleOrNull() ?: return null
    return q.takeIf { it.kind == QuestionKind.Choice && !it.multiSelect && it.options.isNotEmpty() }
}

/** The answers map for tapping [label], exactly like QuestionCard's tap-submit. */
fun inlineAnswerMap(item: QuestionItem, label: String): Map<String, String> = mapOf(item.question to label)

/** Panes of a workspace, in tab order. */
fun workspacePanes(node: WorkspaceNode): List<Pane> = node.tabs.flatMap { it.panes }

/** A workspace's headline status from its agents: blocked > working > done > idle. */
fun workspaceStatus(node: WorkspaceNode): String {
    val statuses = workspacePanes(node).filter { it.agent != null }.map { it.agentStatus }.toSet()
    return listOf("blocked", "working", "done").firstOrNull { it in statuses } ?: "idle"
}

/** Where tapping a workspace tile goes: a blocked pane, else a working one, else the first. */
fun mostRelevantPane(node: WorkspaceNode): Pane? {
    val panes = workspacePanes(node)
    return panes.firstOrNull { it.agent != null && it.agentStatus == "blocked" }
        ?: panes.firstOrNull { it.agent != null && it.agentStatus == "working" }
        ?: panes.firstOrNull()
}

/**
 * Repos narrowed to workspaces matching [query] (case-insensitive) on the
 * workspace label, the repo name, or any pane's agent or cwd. Repos left
 * without workspaces drop out; a blank query returns [repos] unchanged.
 */
fun filterRepos(repos: List<RepoNode>, query: String): List<RepoNode> {
    val q = query.trim()
    if (q.isEmpty()) return repos
    fun hit(s: String?) = s != null && s.contains(q, ignoreCase = true)
    return repos.mapNotNull { r ->
        val ws = if (hit(r.displayName)) r.workspaces else r.workspaces.filter { w ->
            hit(w.ws.label) || workspacePanes(w).any { hit(it.agent) || hit(it.cwd) }
        }
        if (ws.isEmpty()) null else r.copy(workspaces = ws)
    }
}

/** One pane a close will end, for the confirmation list. */
data class ClosingPane(val name: String, val status: String?, val tab: String)

data class CloseSummary(val headline: String, val panes: List<ClosingPane>)

private fun count(n: Int, noun: String) = "$n $noun" + if (n == 1) "" else "s"

private fun tabName(t: TabNode): String = when {
    t.tab.number > 0 -> "tab ${t.tab.number}"
    t.tab.label.isNotBlank() -> "tab ${t.tab.label}"
    else -> ""
}

private fun closing(p: Pane, t: TabNode) = ClosingPane(p.agent ?: "shell", paneStatusLabel(p), tabName(t))

/**
 * What closing [target] ends: "chat-view ends 3 panes across 2 tabs" and the
 * panes themselves. Falls back to the target's own counts when [tree] doesn't
 * (yet) hold it.
 */
fun closeSummary(target: RowAction, tree: List<WorkspaceNode>): CloseSummary = when (target.kind) {
    NodeKind.WORKSPACE -> {
        val w = tree.firstOrNull { it.ws.workspaceId == target.id }
        val panes = w?.tabs?.flatMap { t -> t.panes.map { closing(it, t) } } ?: emptyList()
        val paneCount = if (w != null) panes.size else target.paneCount
        val tabCount = w?.tabs?.count { it.panes.isNotEmpty() } ?: target.tabCount
        val across = if (tabCount > 1) " across ${count(tabCount, "tab")}" else ""
        CloseSummary("${target.label} ends ${count(paneCount, "pane")}$across", panes)
    }
    NodeKind.TAB -> {
        val t = tree.flatMap { it.tabs }.firstOrNull { it.tab.tabId == target.id }
        val panes = t?.panes?.map { closing(it, t) } ?: emptyList()
        val name = t?.let(::tabName)?.ifEmpty { null } ?: "tab ${target.label}"
        CloseSummary("$name ends ${count(if (t != null) panes.size else target.paneCount, "pane")}", panes)
    }
    NodeKind.PANE -> {
        val hit = tree.flatMap { it.tabs }.firstNotNullOfOrNull { t ->
            t.panes.firstOrNull { it.paneId == target.id }?.let { it to t }
        }
        val panes = hit?.let { listOf(closing(it.first, it.second)) } ?: emptyList()
        val what = if (target.isAgent) "terminates ${target.label}" else "ends this shell"
        CloseSummary("Closing this pane $what", panes)
    }
}

/** An action sheet's header: badge number, title, path and status. */
data class ActionHeader(val number: Int, val title: String, val path: String, val status: String?)

/** The header for [target]: a pane names its agent with "repo › workspace › tab N". */
fun actionHeader(target: RowAction, repos: List<RepoNode>): ActionHeader {
    for (r in repos) for (w in r.workspaces) {
        val wsLabel = w.ws.label.ifEmpty { r.displayName }
        if (target.kind == NodeKind.WORKSPACE && w.ws.workspaceId == target.id) {
            return ActionHeader(w.ws.number, wsLabel, r.displayName, workspaceStatus(w))
        }
        for (t in w.tabs) {
            if (target.kind == NodeKind.TAB && t.tab.tabId == target.id) {
                val status = if (t.panes.any { it.agent != null }) workspaceStatus(WorkspaceNode(w.ws, listOf(t))) else null
                return ActionHeader(w.ws.number, tabName(t).ifEmpty { "tab" }, "${r.displayName} › $wsLabel", status)
            }
            if (target.kind == NodeKind.PANE) t.panes.firstOrNull { it.paneId == target.id }?.let { p ->
                val path = listOf(r.displayName, wsLabel, tabName(t)).filter { it.isNotBlank() }.joinToString(" › ")
                return ActionHeader(w.ws.number, p.agent ?: "shell", path, paneStatusLabel(p))
            }
        }
    }
    return ActionHeader(0, target.label, "", null)
}

/** The tab a pane sits in, for its sheet's "Tab actions" pivot. */
fun tabOf(paneId: String, tree: List<WorkspaceNode>): TabNode? =
    tree.flatMap { it.tabs }.firstOrNull { t -> t.panes.any { it.paneId == paneId } }

/** "▸ Bash npm run build" for a tool call, else the activity's first text line; null when empty. */
fun activityLine(a: PaneActivity?): String? {
    if (a == null) return null
    val text = a.text.lineSequence().map { it.trim() }.firstOrNull { it.isNotEmpty() } ?: ""
    val line = if (a.kind == "tool") listOf("▸", a.tool.orEmpty(), text).filter { it.isNotBlank() }.joinToString(" ")
    else text
    return line.takeIf { it.isNotBlank() && it != "▸" }
}

/** "32s ago", "5m ago", "2h ago" for how long ago [since] was at [now]. */
fun staleLabel(since: Long, now: Long): String {
    val s = ((now - since) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s ago"
        s < 3600 -> "${s / 60}m ago"
        s < 86_400 -> "${s / 3600}h ago"
        else -> "${s / 86_400}d ago"
    }
}

/** "100.101.7.12:8787" from "ws://100.101.7.12:8787/ws?token=…". */
fun hostPort(url: String): String =
    url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore('#')
        .substringAfterLast('@')

/** Counts behind the dashboard's stat tiles. */
data class StatusCounts(val blocked: Int, val working: Int, val done: Int)

fun statusCounts(panes: List<Pane>): StatusCounts {
    val agents = panes.filter { it.agent != null }
    return StatusCounts(
        agents.count { it.agentStatus == "blocked" },
        agents.count { it.agentStatus == "working" },
        agents.count { it.agentStatus == "done" },
    )
}
