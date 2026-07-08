package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
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
    onRowAction: (RowAction) -> Unit,
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
                        is Row.Ws -> WorkspaceRow(row, dark, onToggle, onRowAction)
                        is Row.TabRow -> TabRowView(row, dark, onToggle, onRowAction)
                        is Row.PaneRowItem -> PaneTreeRow(row.pane, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(row: Row.Ws, dark: Boolean, onToggle: (String) -> Unit, onRowAction: (RowAction) -> Unit) {
    val ws = row.node.ws
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onToggle(ws.workspaceId) }, onLongClick = { onRowAction(wsAction(row.node)) })
            .padding(horizontal = 12.dp, vertical = 10.dp),
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
        RowActionDots("workspace actions") { onRowAction(wsAction(row.node)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRowView(row: Row.TabRow, dark: Boolean, onToggle: (String) -> Unit, onRowAction: (RowAction) -> Unit) {
    val tab = row.node.tab
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onToggle(tab.tabId) }, onLongClick = { onRowAction(tabAction(row.node)) })
            .padding(start = 32.dp, end = 12.dp).padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        StatusGlyph(tab.agentStatus, dark)
        Spacer(Modifier.width(8.dp))
        Text(if (tab.label.isEmpty()) "—" else "tab ${tab.label}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.weight(1f))
        RowActionDots("tab actions") { onRowAction(tabAction(row.node)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneTreeRow(
    pane: Pane, dark: Boolean, focusedPaneId: String?, lastOpenedPaneId: String?, onSelectPane: (Pane) -> Unit,
    onRowAction: (RowAction) -> Unit,
) {
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    // Shell panes are now attachable too (herdr terminal attach by terminal_id);
    // keep the dimmed styling as a cue but allow the tap. A pane with no
    // terminal_id is not attachable, so it stays non-clickable.
    val attachable = pane.terminalId.isNotBlank()
    val clickable = Modifier.fillMaxWidth()
        .let {
            if (attachable) {
                it.combinedClickable(onClick = { onSelectPane(pane) }, onLongClick = { onRowAction(paneAction(pane)) })
            } else {
                it
            }
        }
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
        Spacer(Modifier.weight(1f))
        RowActionDots("pane actions") { onRowAction(paneAction(pane)) }
    }
}

/** Compact "⋯" affordance — a small clickable glyph, not a 48dp IconButton,
 *  so it doesn't inflate row height. Long-press on the row is the alternate. */
@Composable
private fun RowActionDots(contentDescription: String, onClick: () -> Unit) {
    Text(
        "⋯",
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier
            .clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription }
            .padding(horizontal = 10.dp, vertical = 2.dp),
    )
}

@Composable
private fun StatusGlyph(status: String?, dark: Boolean) {
    val glyph = if (status == "working") spinnerFrame() else statusGlyph(status)
    Text(glyph, color = statusColor(status, dark), style = MaterialTheme.typography.bodyMedium)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    target: RowAction,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(
                target.label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            )
            Text(
                "Rename",
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth().clickable { onRename() }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
            Text(
                "Close",
                style = MaterialTheme.typography.bodyLarge,
                color = statusColor("blocked", isSystemInDarkTheme()),
                modifier = Modifier.fillMaxWidth().clickable { onClose() }.padding(horizontal = 20.dp, vertical = 14.dp),
            )
        }
    }
}

private fun wsAction(node: WorkspaceNode) = RowAction(
    kind = NodeKind.WORKSPACE,
    id = node.ws.workspaceId,
    label = node.ws.label.ifEmpty { "(unknown)" },
    paneCount = node.ws.paneCount,
    tabCount = node.ws.tabCount,
)

private fun tabAction(node: TabNode) = RowAction(
    kind = NodeKind.TAB,
    id = node.tab.tabId,
    label = node.tab.label.ifEmpty { node.tab.number.toString() },
    paneCount = node.panes.size,
    hasAgent = node.panes.any { it.agent != null },
)

private fun paneAction(pane: Pane) = RowAction(
    kind = NodeKind.PANE,
    id = pane.paneId,
    label = pane.agent ?: "shell",
    isAgent = pane.agent != null,
)
