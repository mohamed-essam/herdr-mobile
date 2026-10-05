package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.AgentSummary
import dev.herdr.mobile.net.BgTask
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.MonoFamily

/** "12s", "4m", "2h": how long something ran, from [from] to [to]. */
private fun elapsedLabel(from: Long, to: Long): String {
    val s = ((to - from) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m"
        else -> "${s / 3600}h"
    }
}

/** A workflow card's title: its task's label (the workflow's name), else "Workflow". */
fun workflowTitle(call: ChatEvent.ToolUse, tasks: List<BgTask>): String =
    tasks.firstOrNull { it.kind == "workflow" && it.toolUseId == call.toolUseId }?.label?.takeIf { it.isNotBlank() } ?: "Workflow"

private val LAUNCHED = listOf("async_launched", "Async agent launched", "launched in background")

/**
 * A card's status while no agent summary exists, from its call's result:
 * "failed" (an error result), "launched" (a background launch, still going
 * on its own), "done" (any other result); with no result, "running" while
 * the pane works, else "unknown".
 */
fun fallbackStatus(result: ChatEvent.ToolResult?, working: Boolean): String = when {
    result == null -> if (working) "running" else "unknown"
    result.isError -> "failed"
    LAUNCHED.any { result.preview.contains(it) } -> "launched"
    else -> "done"
}

/**
 * A status glyph: the working spinner, ✓ in green, ✗ in red, or a dim ○ for a
 * launched or unknown one (mono, like the dashboard's).
 */
@Composable
private fun StatusGlyph(status: String) {
    val c = Herdr.colors
    val (text, color) = when (status) {
        "done" -> "✓" to c.green
        "failed" -> "✗" to c.red
        "launched", "unknown" -> "○" to c.overlay0
        else -> spinnerFrame() to c.yellow
    }
    Text(text, style = HerdrType.meta.copy(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold), color = color)
}

@Composable
private fun CardSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier.fillMaxWidth().clip(HerdrRadius.badge).background(Herdr.colors.surface0).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
        content = content,
    )
}

/** The activity line of a card with no summary, by its [fallbackStatus]. */
private fun fallbackActivity(status: String): String = when (status) {
    "launched" -> "running in background"
    "running" -> "starting…"
    else -> ""
}

/**
 * A subagent launched by [call]: label, type and elapsed time on the first
 * line, its current activity under it. Tapping opens its thread, once known.
 * With no summary yet, its status comes from the call's [result] (see
 * [fallbackStatus]).
 */
@Composable
fun SubagentCard(call: ChatEvent.ToolUse, agent: AgentSummary?, now: Long, onOpen: (String) -> Unit, result: ChatEvent.ToolResult? = null, working: Boolean = false) {
    val c = Herdr.colors
    val status = agent?.status ?: fallbackStatus(result, working)
    val label = agent?.label?.takeIf { it.isNotBlank() } ?: toolLine(call.tool, call.summary)
    val start = call.ts ?: agent?.ts ?: now
    val end = if (status == "running" || status == "launched") now else agent?.ts ?: result?.ts ?: start
    val elapsed = elapsedLabel(start, end)
    CardSurface(if (agent != null) Modifier.clickable { onOpen(agent.agentId) } else Modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusGlyph(status)
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(label, Modifier.weight(1f, fill = false), style = HerdrType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                agent?.type?.takeIf { it.isNotBlank() }?.let { Text(it, Modifier.weight(0.6f, fill = false), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            Text(elapsed, style = HerdrType.meta, color = c.overlay0)
        }
        Text(
            (if (agent == null) fallbackActivity(status) else activityLine(agent.activity)) ?: "",
            style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 16.dp),
        )
    }
}

/**
 * A workflow launched by [call]: name and overall status, then its agents
 * under their phase headings as compact rows. Past [WORKFLOW_ROWS] rows the
 * rest fold into "+N more", which expands in place. With no agents yet, its
 * status comes from the call's [result] (see [fallbackStatus]).
 */
@Composable
fun WorkflowCard(
    call: ChatEvent.ToolUse, agents: List<AgentSummary>, now: Long, onOpen: (String) -> Unit, tasks: List<BgTask> = emptyList(),
    result: ChatEvent.ToolResult? = null, working: Boolean = false,
) {
    val c = Herdr.colors
    var all by rememberSaveable(call.toolUseId) { mutableStateOf(false) }
    val overall = when {
        agents.isEmpty() -> fallbackStatus(result, working)
        agents.any { it.status == "running" } -> "running"
        agents.any { it.status == "failed" } -> "failed"
        else -> "done"
    }
    val shown = if (all) agents else agents.take(WORKFLOW_ROWS)
    val hidden = agents.size - shown.size
    val start = call.ts ?: agents.firstOrNull()?.ts ?: now
    val end = if (overall == "running" || overall == "launched") now else agents.maxOfOrNull { it.ts } ?: result?.ts ?: start
    CardSurface {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusGlyph(overall)
            Text(
                workflowTitle(call, tasks), Modifier.weight(1f),
                style = HerdrType.meta.copy(fontWeight = FontWeight.SemiBold), color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            Text(elapsedLabel(start, end), style = HerdrType.meta, color = c.overlay0)
        }
        if (agents.isEmpty()) fallbackActivity(overall).takeIf { it.isNotEmpty() }?.let {
            Text(it, Modifier.padding(start = 16.dp), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        for ((phase, rows) in workflowGroups(shown)) {
            if (phase != null) Text(phase, Modifier.padding(top = 4.dp), style = HerdrType.section, color = c.overlay2)
            for (a in rows) AgentRow(a, onOpen)
        }
        if (hidden > 0) {
            Text(
                "+$hidden more", style = HerdrType.meta, color = c.mauve,
                modifier = Modifier.clip(HerdrRadius.badge).clickable { all = true }.padding(vertical = 4.dp),
            )
        }
    }
}

@Composable
private fun AgentRow(a: AgentSummary, onOpen: (String) -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().clickable { onOpen(a.agentId) }.padding(start = 8.dp, top = 2.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        StatusGlyph(a.status)
        Text(a.label, style = HerdrType.meta, color = c.subtext1, maxLines = 1, overflow = TextOverflow.Ellipsis)
        activityLine(a.activity)?.let {
            Text(it, Modifier.weight(1f), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/**
 * The launch call's card: a workflow's rows, or one subagent. [result] is the
 * call's tool_result, if any, and [working] whether the pane is working: they
 * decide the status while no summary exists.
 */
@Composable
fun AgentCardBody(
    call: ChatEvent.ToolUse, agents: Map<String, AgentSummary>, now: Long, onOpen: (String) -> Unit, tasks: List<BgTask> = emptyList(),
    result: ChatEvent.ToolResult? = null, working: Boolean = false,
) {
    val mine = remember(call.toolUseId, agents) { agentsFor(call, agents) }
    if (call.tool == "Workflow") WorkflowCard(call, mine, now, onOpen, tasks, result, working)
    else SubagentCard(call, mine.firstOrNull(), now, onOpen, result, working)
}
