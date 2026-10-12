package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.BgTask
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay

/*
 * The chat's background tasks (shells, agents, workflows, monitors): the
 * one-line strip under the header, the sheet it opens, and the pure formatters
 * behind them and the dashboard's badge.
 */

data class TaskCounts(val running: Int, val done: Int, val failed: Int)

fun taskCounts(tasks: List<BgTask>): TaskCounts = TaskCounts(
    running = tasks.count { it.status == "running" },
    done = tasks.count { it.status == "done" },
    failed = tasks.count { it.status == "failed" },
)

/** "⟳ 2 running · 1 done · 1 failed", zero parts omitted; null when all are zero. */
fun stripLabel(c: TaskCounts): String? {
    val parts = listOfNotNull(
        c.running.takeIf { it > 0 }?.let { "$it running" },
        c.done.takeIf { it > 0 }?.let { "$it done" },
        c.failed.takeIf { it > 0 }?.let { "$it failed" },
    )
    return if (parts.isEmpty()) null else "⟳ " + parts.joinToString(" · ")
}

/** "4s", "2m 05s", "1h 03m". A running task (or one with no end time) counts to [now]. */
fun taskDuration(t: BgTask, now: Long): String {
    val end = if (t.status == "running") now else t.endedAt ?: now
    val s = ((end - t.startedAt) / 1000).coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${(s % 60).toString().padStart(2, '0')}s"
        else -> "${s / 3600}h ${((s % 3600) / 60).toString().padStart(2, '0')}m"
    }
}

fun taskGlyph(kind: String): String = when (kind) {
    "shell" -> "$"
    "subagent" -> "◆"
    "workflow" -> "⧉"
    "monitor" -> "◉"
    else -> "•"
}

/** The dashboard chip: how many background tasks the pane has running; null when none. */
fun bgBadge(p: Pane): String? = if (p.bgRunning > 0) "⟳ ${p.bgRunning}" else null

/** The task's whole text: the full command when the mod sent it, else its label. */
fun taskFullText(t: BgTask): String = t.detail ?: t.label

/** Subagent and workflow tasks have a card in the chat to jump to. */
private fun BgTask.hasCard() = kind == "subagent" || kind == "workflow"

/** One line under the header summarising the tasks; absent when there are none. */
@Composable
fun TasksStrip(tasks: List<BgTask>, onClick: () -> Unit) {
    val c = Herdr.colors
    val counts = remember(tasks) { taskCounts(tasks) }
    val label = stripLabel(counts) ?: return
    Text(
        label,
        style = HerdrType.meta,
        color = if (counts.failed > 0 && counts.running == 0) c.red else c.subtext1,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.fillMaxWidth().background(c.mantle).clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 8.dp),
    )
}

/**
 * The tasks as rows: glyph, label, status and duration. The duration ticks
 * every second only while the sheet is shown and a task is running. A subagent
 * or workflow row jumps to its card; a shell or monitor row expands to show
 * its description and whole command.
 */
@Composable
fun TasksSheet(tasks: List<BgTask>, now: Long, onPick: (BgTask) -> Unit, onDismiss: () -> Unit) {
    val c = Herdr.colors
    val anyRunning = tasks.any { it.status == "running" }
    val clock by produceState(now, anyRunning) {
        value = System.currentTimeMillis()
        while (anyRunning) { delay(1_000); value = System.currentTimeMillis() }
    }
    var expanded by remember { mutableStateOf(emptySet<String>()) }
    HerdrSheet(onDismiss) {
        Text("Background tasks", style = HerdrType.title, color = c.text, modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
        ListCard {
            tasks.forEachIndexed { i, t ->
                if (i > 0) ListDivider()
                val color = when (t.status) {
                    "running" -> c.yellow
                    "done" -> c.green
                    else -> c.red
                }
                val open = t.id in expanded
                Column(
                    Modifier.fillMaxWidth()
                        .clickable { if (t.hasCard()) onPick(t) else expanded = if (open) expanded - t.id else expanded + t.id }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(taskGlyph(t.kind), style = HerdrType.title, color = c.overlay2, modifier = Modifier.width(24.dp))
                        Text(
                            t.label.ifBlank { t.kind },
                            style = HerdrType.body, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(t.status, style = HerdrType.meta, color = color)
                        Spacer(Modifier.width(8.dp))
                        Text(taskDuration(t, clock), style = HerdrType.meta, color = c.overlay2)
                    }
                    if (open) {
                        t.description?.let {
                            Text(it, style = HerdrType.body, color = c.subtext1, modifier = Modifier.padding(start = 24.dp, top = 8.dp))
                        }
                        SelectionContainer(Modifier.padding(start = 24.dp, top = 8.dp)) {
                            Text(
                                taskFullText(t), style = HerdrType.code, color = c.text,
                                modifier = Modifier.fillMaxWidth().background(c.mantle).padding(horizontal = 10.dp, vertical = 8.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
