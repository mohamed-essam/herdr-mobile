package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.AgentSummary
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType

/**
 * A subagent's thread, read-only: the pane's header with the agent's
 * "label · type" breadcrumb and status, then its timeline. Nothing here
 * sends to the agent. [agents] is the main view's map (the companion keeps
 * every summary pane-wide), so nested cards resolve and [onOpen] stacks them.
 */
@Composable
fun ThreadScreen(
    vm: DashboardViewModel,
    pane: Pane,
    agentId: String,
    agents: Map<String, AgentSummary>,
    onOpen: (String) -> Unit,
    onBack: () -> Unit,
) {
    val c = Herdr.colors
    val view by remember(pane.paneId, agentId) { vm.chatView(pane.paneId, agentId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    val limits by vm.limits.collectAsState()
    val now = rememberNow()
    val repoTree by vm.repoTree.collectAsState()
    val listState = rememberSaveable(agentId, saver = LazyListState.Saver) { LazyListState() }

    // One thread subscription at a time: pushing or popping closes this one first.
    DisposableEffect(agentId) {
        vm.openChat(pane.paneId, agentId)
        onDispose { vm.closeChat(pane.paneId, agentId) }
    }

    val ctx = remember(repoTree, pane) { paneContexts(repoTree)[pane.paneId] ?: fallbackContext(pane) }
    val summary = agents[agentId]
    val type = summary?.type?.takeIf { it.isNotBlank() }
    val label = summary?.label?.takeIf { it.isNotBlank() } ?: type ?: "subagent"
    val (status, statusColor) = when {
        !connected -> "offline" to c.overlay2
        summary == null -> "" to c.overlay2
        summary.status == "running" -> "running" to c.yellow
        summary.status == "failed" -> "failed" to c.red
        else -> summary.status to c.green
    }

    Column(Modifier.fillMaxSize().background(c.crust)) {
        PaneHeader(
            ctx = ctx,
            breadcrumb = listOfNotNull(label, type?.takeIf { it != label }).joinToString(" · "),
            status = status,
            statusColor = statusColor,
            onBack = onBack,
            toggleLabel = null,
            onToggle = {},
            usage = pane.context, limits = limits, now = now,
        )
        if (view.missing) {
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("This thread is no longer available", style = HerdrType.body, color = c.overlay2)
                Spacer(Modifier.height(16.dp))
                SecondaryButton("Back", onClick = onBack)
            }
        } else {
            ChatTimelineList(
                vm = vm,
                paneId = pane.paneId,
                agentId = agentId,
                view = view,
                agents = agents,
                agentLabel = type ?: "agent",
                listState = listState,
                readOnly = true,
                onOpenThread = onOpen,
                modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding(),
            )
        }
    }
}
