package dev.herdr.mobile.ui

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph

/**
 * The full-height workspace switcher: workspaces as tiles grouped under their
 * repo, each with a per-pane status strip. Tapping a tile opens its most
 * relevant pane; a long press opens the workspace's action sheet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WorkspaceSwitcher(
    repos: List<RepoNode>,
    highlightedWorkspaceId: String?,
    actionsEnabled: Boolean,
    onOpen: (Pane) -> Unit,
    onActions: (RowAction) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Herdr.colors
    var query by rememberSaveable { mutableStateOf("") }
    val shown = filterRepos(repos, query)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.mantle,
        shape = HerdrRadius.sheet,
        dragHandle = { SheetHandle() },
    ) {
        LazyColumn(
            Modifier.fillMaxSize().padding(horizontal = 16.dp),
            contentPadding = WindowInsets.navigationBars.asPaddingValues(),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item(key = "head") {
                Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Workspaces", style = HerdrType.headline, color = c.text, modifier = Modifier.weight(1f))
                    GhostButton("+ New", onNew, enabled = actionsEnabled)
                }
            }
            item(key = "search") { SearchField(query, { query = it }, "Search workspace, agent, path") }
            if (shown.isEmpty()) item(key = "none") {
                Text(
                    if (repos.isEmpty()) "No workspaces yet" else "Nothing matches “${query.trim()}”",
                    style = HerdrType.small, color = c.overlay2, modifier = Modifier.padding(vertical = 16.dp),
                )
            }
            for (r in shown) {
                item(key = "repo:${r.repoKey}") { RepoHeader(r) }
                items(r.workspaces.chunked(2), key = { row -> "ws:" + row.joinToString { it.ws.workspaceId } }) { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                        for (w in row) {
                            WorkspaceTile(
                                w,
                                highlighted = w.ws.workspaceId == highlightedWorkspaceId,
                                onClick = { mostRelevantPane(w)?.let(onOpen) },
                                onLongClick = if (actionsEnabled && w.ws.workspaceId.isNotBlank()) ({ onActions(wsAction(w)) }) else null,
                                modifier = Modifier.weight(1f).fillMaxHeight(),
                            )
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
            item(key = "end") { Spacer(Modifier.height(16.dp)) }
        }
    }
}

@Composable
private fun RepoHeader(r: RepoNode) {
    val c = Herdr.colors
    Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        RepoAvatar(r.displayName, size = 16.dp)
        Spacer(Modifier.width(8.dp))
        SectionLabel(r.displayName, c.overlay0, Modifier.weight(1f))
        Text("${r.workspaces.size}", style = HerdrType.meta, color = c.overlay0)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WorkspaceTile(
    w: WorkspaceNode,
    highlighted: Boolean,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)?,
    modifier: Modifier,
) {
    val c = Herdr.colors
    val dark = isSystemInDarkTheme()
    val status = workspaceStatus(w)
    val color = statusColor(status, dark)
    Column(
        modifier
            .clip(HerdrRadius.card)
            .background(c.base)
            .then(if (highlighted) Modifier.border(2.dp, c.mauve, HerdrRadius.card) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkspaceBadge(w.ws.number)
            Spacer(Modifier.weight(1f))
            val glyph = if (status == "working") spinnerFrame() else statusGlyph(status)
            Text("$glyph $status", style = HerdrType.meta, color = color)
        }
        Text(
            w.ws.label.ifEmpty { "(unknown)" }, style = HerdrType.title, color = c.text,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        StatusStrip(workspacePanes(w))
    }
}

/** One 6dp segment per pane, colored by status; shells and idle panes are neutral. */
@Composable
private fun StatusStrip(panes: List<Pane>) {
    val c = Herdr.colors
    val dark = isSystemInDarkTheme()
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        val segs = panes.ifEmpty { listOf(null) }
        for (p in segs) {
            val s = p?.takeIf { it.agent != null }?.agentStatus
            val color = if (s in setOf("blocked", "working", "done")) statusColor(s, dark) else c.surface1
            Box(Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(3.dp)).background(color))
        }
    }
}
