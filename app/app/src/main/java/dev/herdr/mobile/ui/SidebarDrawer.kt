package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LocalContentColor
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph

@Composable
fun SidebarDrawer(
    repos: List<RepoNode>,
    collapsed: Set<String>,
    focusedPaneId: String?,
    lastOpenedPaneId: String?,
    onToggle: (String) -> Unit,
    onSelectPane: (Pane) -> Unit,
    onRowAction: (RowAction) -> Unit,
    onNewWorkspace: () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val rows = flattenTree(repos, collapsed)
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
                Spacer(Modifier.weight(1f))
                Text(
                    "+",
                    color = MaterialTheme.colorScheme.primary,
                    style = MaterialTheme.typography.titleLarge,
                    modifier = Modifier
                        .clickable(onClick = onNewWorkspace)
                        .semantics { contentDescription = "new workspace" }
                        .padding(horizontal = 12.dp, vertical = 2.dp),
                )
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(rows, key = { treeRowKey(it) }) { row ->
                    when (row) {
                        is TreeRow.Repo -> RepoRow(row, onToggle)
                        is TreeRow.Ws -> WorkspaceRow(row, dark, onToggle, onRowAction)
                        is TreeRow.Tab -> TabRowView(row, dark, onToggle, onRowAction)
                        is TreeRow.PaneItem -> PaneTreeRow(row.pane, row.promoted, row.parentTab, dark, focusedPaneId, lastOpenedPaneId, onSelectPane, onRowAction)
                    }
                }
            }
        }
    }
}

@Composable
private fun RepoRow(row: TreeRow.Repo, onToggle: (String) -> Unit) {
    val node = row.node
    Row(
        Modifier.fillMaxWidth()
            .clickable { onToggle("repo:${node.repoKey}") }
            .padding(start = 12.dp, end = 12.dp).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(if (row.expanded) "▾" else "▸", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(8.dp))
        RepoAvatar(node.displayName)
        Spacer(Modifier.width(10.dp))
        Text(
            node.displayName,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text("${row.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceRow(row: TreeRow.Ws, dark: Boolean, onToggle: (String) -> Unit, onRowAction: (RowAction) -> Unit) {
    val ws = row.node.ws
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onToggle(ws.workspaceId) }, onLongClick = { onRowAction(wsAction(row.node)) })
            .padding(start = 28.dp, end = 12.dp).padding(vertical = 10.dp),
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
        if (ws.paneCount > 0) Text("${ws.paneCount}", color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
        RowActionDots("workspace actions") { onRowAction(wsAction(row.node)) }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun TabRowView(row: TreeRow.Tab, dark: Boolean, onToggle: (String) -> Unit, onRowAction: (RowAction) -> Unit) {
    val tab = row.node.tab
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onToggle(tab.tabId) }, onLongClick = { onRowAction(tabAction(row.node)) })
            .padding(start = 44.dp, end = 12.dp).padding(vertical = 8.dp),
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
    pane: Pane, promoted: Boolean, parentTab: TabNode?, dark: Boolean,
    focusedPaneId: String?, lastOpenedPaneId: String?, onSelectPane: (Pane) -> Unit,
    onRowAction: (RowAction) -> Unit,
) {
    val isAgent = pane.agent != null
    val marked = pane.focused || pane.paneId == focusedPaneId || pane.paneId == lastOpenedPaneId
    // A promoted pane carries its elided parent tab so its sheet can pivot to tab actions.
    val action = paneAction(pane, parentTab)
    // Shell panes are now attachable too (herdr terminal attach by terminal_id);
    // keep the dimmed styling as a cue but allow the tap. A pane with no
    // terminal_id is not attachable, so it stays non-clickable.
    val attachable = pane.terminalId.isNotBlank()
    val clickable = Modifier.fillMaxWidth()
        .let {
            if (attachable) {
                it.combinedClickable(onClick = { onSelectPane(pane) }, onLongClick = { onRowAction(action) })
            } else {
                it
            }
        }
    Row(
        clickable
            .then(if (marked) Modifier.background(MaterialTheme.colorScheme.surfaceVariant) else Modifier)
            // Promoted panes (elided tab) sit at 40dp — one step shallower than a
            // tab-nested pane (56dp) — reading as a workspace-direct child.
            .padding(start = if (promoted) 40.dp else 56.dp, end = 12.dp).padding(vertical = 8.dp),
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
        RowActionDots("pane actions") { onRowAction(action) }
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
    onNewTab: () -> Unit,
    onNewAgent: () -> Unit,
    onNewShell: () -> Unit,
    onSplit: (String) -> Unit,   // "right" | "down"
    onMove: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onTabActions: () -> Unit = {},
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
            Text(target.label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            when (target.kind) {
                NodeKind.WORKSPACE -> {
                    SheetItem("New tab", onNewTab)
                    SheetItem("New agent", onNewAgent)
                }
                NodeKind.TAB -> {
                    SheetItem("New shell", onNewShell)
                    SheetItem("New agent", onNewAgent)
                }
                NodeKind.PANE -> {
                    if (target.mergedTab != null) SheetItem("Tab actions…", onTabActions)
                    SheetItem("Split right", onClick = { onSplit("right") })
                    SheetItem("Split down", onClick = { onSplit("down") })
                    SheetItem("Move…", onMove)
                }
            }
            SheetItem("Rename", onRename)
            SheetItem("Close", onClose, color = statusColor("blocked", isSystemInDarkTheme()))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentPickerSheet(agents: List<String>, onPick: (String) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("New agent", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            agents.forEach { name -> SheetItem(name, { onPick(name) }) }
            SheetItem("Other…", onOther, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MoveDestinationSheet(
    tree: List<WorkspaceNode>,
    currentTabId: String,
    onExistingTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewWorkspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).navigationBarsPadding()) {
            Text("Move to…", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp))
            SheetItem("New tab", onNewTab)
            SheetItem("New workspace", onNewWorkspace)
            tree.forEach { w ->
                w.tabs.forEach { t ->
                    if (t.tab.tabId != currentTabId && t.tab.tabId.isNotBlank()) {
                        val wsLabel = w.ws.label.ifEmpty { "(unknown)" }
                        val tabLabel = t.tab.label.ifEmpty { t.tab.number.toString() }
                        SheetItem("$wsLabel / $tabLabel", { onExistingTab(t.tab.tabId) })
                    }
                }
            }
        }
    }
}

@Composable
private fun SheetItem(label: String, onClick: () -> Unit, color: androidx.compose.ui.graphics.Color = LocalContentColor.current) {
    Text(
        label,
        style = MaterialTheme.typography.bodyLarge,
        color = color,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 14.dp),
    )
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
    workspaceId = node.tab.workspaceId,
)

internal fun paneAction(pane: Pane, parentTab: TabNode? = null) = RowAction(
    kind = NodeKind.PANE,
    id = pane.paneId,
    label = pane.agent ?: "shell",
    isAgent = pane.agent != null,
    // A blank-id parent tab is the synthetic (unknown)-workspace orphan tab; no
    // pivot for it (its tab actions would dispatch with an empty id, bypassing
    // the isNotBlank() guard on onRowAction).
    mergedTab = parentTab?.takeIf { it.tab.tabId.isNotBlank() }?.let { tabAction(it) },
)
