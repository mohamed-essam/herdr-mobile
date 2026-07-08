package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph

/** One flattened, renderable row of the tree. */
private sealed interface Row {
    data class Ws(val node: WorkspaceNode, val expanded: Boolean) : Row
    data class TabRow(val node: TabNode, val expanded: Boolean) : Row
    data class PaneRowItem(val pane: Pane) : Row
}

/** A node is expanded unless its id is in [collapsed]. */
private fun flatten(tree: List<WorkspaceNode>, collapsed: Set<String>): List<Row> {
    val rows = mutableListOf<Row>()
    for (w in tree) {
        val wOpen = w.ws.workspaceId !in collapsed
        rows.add(Row.Ws(w, wOpen))
        if (!wOpen) continue
        for (t in w.tabs) {
            val tOpen = t.tab.tabId !in collapsed
            rows.add(Row.TabRow(t, tOpen))
            if (!tOpen) continue
            t.panes.forEach { rows.add(Row.PaneRowItem(it)) }
        }
    }
    return rows
}

@Composable
fun SidebarDrawer(
    tree: List<WorkspaceNode>,
    collapsed: Set<String>,
    focusedPaneId: String?,
    lastOpenedPaneId: String?,
    onToggle: (String) -> Unit,
    onSelectPane: (Pane) -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val rows = flatten(tree, collapsed)
    ModalDrawerSheet(
        drawerContainerColor = MaterialTheme.colorScheme.surfaceContainerLow,
    ) {
        Column(Modifier.fillMaxSize()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("herdr", style = MaterialTheme.typography.titleMedium)
                Text("  ❯", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { rowKey(it) }) { row ->
                    when (row) {
                        is Row.Ws -> WorkspaceRow(row, dark, onToggle)
                        is Row.TabRow -> TabRowView(row, dark, onToggle)
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, dark, focusedPaneId, lastOpenedPaneId, onSelectPane)
                    }
                }
            }
        }
    }
}

private fun rowKey(r: Row): String = when (r) {
    is Row.Ws -> "w:" + r.node.ws.workspaceId
    is Row.TabRow -> "t:" + r.node.tab.tabId
    is Row.PaneRowItem -> "p:" + r.pane.paneId
}

@Composable
private fun WorkspaceRow(row: Row.Ws, dark: Boolean, onToggle: (String) -> Unit) {
    val ws = row.node.ws
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(ws.workspaceId) }.padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        StatusGlyph(ws.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(ws.label.ifEmpty { "(unknown)" }, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
        if (ws.number > 0) {
            Spacer(Modifier.width(6.dp))
            Text("#${ws.number}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        }
        Spacer(Modifier.weight(1f))
        ws.worktree?.repoName?.let { repo ->
            Text("⑂ $repo", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(0.8f))
            Spacer(Modifier.width(8.dp))
        }
        if (ws.paneCount > 0) Text("${ws.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun TabRowView(row: Row.TabRow, dark: Boolean, onToggle: (String) -> Unit) {
    val tab = row.node.tab
    Row(
        Modifier.fillMaxWidth().clickable { onToggle(tab.tabId) }.padding(start = 32.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        StatusGlyph(tab.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(if (tab.label.isEmpty()) "—" else "tab ${tab.label}", style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun PaneTreeRow(
    pane: Pane, dark: Boolean, focusedPaneId: String?, lastOpenedPaneId: String?, onSelectPane: (Pane) -> Unit,
) {
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    val base = Modifier.fillMaxWidth()
    val clickable = if (isAgent) base.clickable { onSelectPane(pane) } else base
    Row(
        clickable
            .then(if (marked) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            .padding(start = 52.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (marked) {
            Text("▎", color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.width(4.dp))
        }
        StatusGlyph(pane.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        val label = pane.agent ?: "shell"
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.alpha(if (isAgent) 1f else 0.5f),
        )
        val base = pane.cwd.substringAfterLast('/')
        if (base.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(base, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall, modifier = Modifier.alpha(if (isAgent) 0.8f else 0.4f))
        }
    }
}

@Composable
private fun StatusGlyph(status: String?, dark: Boolean) {
    val glyph = if (status == "working") spinnerFrame() else statusGlyph(status)
    Text(glyph, color = statusColor(status, dark), style = MaterialTheme.typography.bodyMedium)
}
