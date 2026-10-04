package dev.herdr.mobile.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.MonoFamily
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph
import kotlinx.coroutines.launch

/** How long "Hold to close" must be held. */
private const val HOLD_TO_CLOSE_MS = 1_200

/**
 * The structural action sheet for a pane, tab or workspace: a header naming the
 * target with its full path, the common actions as tiles, then tab actions and
 * a set-apart Close. With [confirm] set it shows the close confirmation in place.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RowActionSheet(
    target: RowAction,
    header: ActionHeader,
    confirm: CloseSummary?,
    alsoCloses: List<String>,
    onNewTab: () -> Unit,
    onNewAgent: () -> Unit,
    onNewShell: () -> Unit,
    onSplit: (String) -> Unit,   // "right" | "down"
    onMove: () -> Unit,
    onRename: () -> Unit,
    onClose: () -> Unit,
    onTabActions: () -> Unit,
    onConfirmClose: () -> Unit,
    onCancelClose: () -> Unit,
    onDismiss: () -> Unit,
) {
    HerdrSheet(onDismiss) {
        if (confirm != null) {
            CloseConfirmation(target, confirm, alsoCloses, onConfirmClose, onCancelClose)
        } else {
            ActionSheetHeader(header)
            Spacer(Modifier.height(16.dp))
            val tiles = when (target.kind) {
                NodeKind.PANE -> listOf(
                    Tile("▯▯", "Split right", "new shell") { onSplit("right") },
                    Tile("▭", "Split down", "new shell") { onSplit("down") },
                    Tile("↗", "Move…", "to tab or workspace", onMove),
                    Tile("Aa", "Rename", target.label, onRename),
                )
                NodeKind.TAB -> listOf(
                    Tile("$", "New shell", "in this workspace", onNewShell),
                    Tile("❯", "New agent", "split down", onNewAgent),
                    Tile("Aa", "Rename", header.title, onRename),
                )
                NodeKind.WORKSPACE -> listOf(
                    Tile("+", "New tab", "in this workspace", onNewTab),
                    Tile("❯", "New agent", "in a new tab", onNewAgent),
                    Tile("Aa", "Rename", target.label, onRename),
                )
            }
            ActionTiles(tiles)
            Spacer(Modifier.height(16.dp))
            ListCard {
                if (target.mergedTab != null) {
                    ListRow(onClick = onTabActions) {
                        Text("Tab actions", style = HerdrType.body, color = Herdr.colors.text, modifier = Modifier.weight(1f))
                        Text("›", style = HerdrType.body, color = Herdr.colors.overlay0)
                    }
                    ListDivider()
                }
                val noun = target.kind.wire
                val sub = when (target.kind) {
                    NodeKind.PANE -> if (target.isAgent) "Terminates ${target.label}" else "Ends this shell"
                    NodeKind.TAB, NodeKind.WORKSPACE -> "Ends ${target.paneCount} pane" + if (target.paneCount == 1) "" else "s"
                }
                ListRow(onClick = onClose, height = 56.dp) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text("Close $noun", style = HerdrType.title, color = Herdr.colors.red)
                        Text(sub, style = HerdrType.caption, color = Herdr.colors.overlay2)
                    }
                }
            }
        }
    }
}

@Composable
private fun ActionSheetHeader(h: ActionHeader) {
    val dark = isSystemInDarkTheme()
    Row(verticalAlignment = Alignment.CenterVertically) {
        WorkspaceBadge(h.number, size = 40.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(h.title, style = HerdrType.title.copy(fontSize = HerdrType.wordmark.fontSize), color = Herdr.colors.text,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (h.path.isNotBlank()) {
                Text(h.path, style = HerdrType.meta, color = Herdr.colors.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        h.status?.let { s ->
            Spacer(Modifier.width(8.dp))
            val color = statusColor(s, dark)
            Text(
                s, style = HerdrType.meta, color = color,
                modifier = Modifier.clip(HerdrRadius.badge).background(color.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 4.dp),
            )
        }
    }
}

private class Tile(val glyph: String, val title: String, val subtitle: String, val onClick: () -> Unit)

@Composable
private fun ActionTiles(tiles: List<Tile>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (row in tiles.chunked(2)) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.height(IntrinsicSize.Min)) {
                for (t in row) ActionTile(t, Modifier.weight(1f).fillMaxHeight())
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun ActionTile(t: Tile, modifier: Modifier) {
    val c = Herdr.colors
    Column(
        modifier.clip(HerdrRadius.tile).background(c.surface0).clickable(onClick = t.onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(t.glyph, style = HerdrType.code.copy(fontSize = HerdrType.body.fontSize), color = c.mauve)
        Text(t.title, style = HerdrType.title, color = c.text)
        Text(t.subtitle, style = HerdrType.caption, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun CloseConfirmation(
    target: RowAction,
    summary: CloseSummary,
    alsoCloses: List<String>,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    val c = Herdr.colors
    val dark = isSystemInDarkTheme()
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel("close ${target.kind.wire}", c.red)
            Text(summary.headline, style = HerdrType.headline, color = c.text)
        }
        if (summary.panes.isNotEmpty()) {
            ListCard {
                summary.panes.forEachIndexed { i, p ->
                    if (i > 0) ListDivider()
                    ListRow(height = 48.dp) {
                        Text(statusGlyph(p.status), style = HerdrType.small, color = statusColor(p.status, dark))
                        Spacer(Modifier.width(12.dp))
                        Text(
                            p.name, style = HerdrType.body, color = if (p.status == "shell") c.overlay2 else c.text,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(p.tab, style = HerdrType.meta, color = c.overlay2)
                    }
                }
            }
        }
        if (alsoCloses.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().clip(HerdrRadius.tile).background(c.peach.copy(alpha = 0.08f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("⚠", style = HerdrType.small, color = c.peach)
                Text(
                    buildAnnotatedString {
                        append(if (alsoCloses.size == 1) "Also closes linked worktree workspace " else "Also closes linked worktree workspaces ")
                        alsoCloses.forEachIndexed { i, name ->
                            if (i > 0) append(", ")
                            withStyle(SpanStyle(fontFamily = MonoFamily, fontWeight = FontWeight.SemiBold)) { append(name) }
                        }
                    },
                    style = HerdrType.small, color = c.peach,
                )
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SecondaryButton("Cancel", onCancel, Modifier.width(112.dp), height = 52.dp, shape = HerdrRadius.card)
            HoldToCloseButton(onConfirm, Modifier.weight(1f))
        }
    }
}

/**
 * Press and hold for [HOLD_TO_CLOSE_MS] to confirm; the fill runs left to right
 * and an early release cancels. Accessibility services get a direct "Close" action.
 */
@Composable
private fun HoldToCloseButton(onConfirm: () -> Unit, modifier: Modifier = Modifier) {
    val c = Herdr.colors
    val confirm by rememberUpdatedState(onConfirm)
    val progress = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    var fired by remember { mutableStateOf(false) }
    fun fire() { if (!fired) { fired = true; confirm() } }
    Box(
        modifier
            .height(52.dp)
            .clip(HerdrRadius.card)
            .background(c.red.copy(alpha = 0.15f))
            .pointerInput(Unit) {
                detectTapGestures(onPress = {
                    val job = scope.launch {
                        val left = ((1f - progress.value) * HOLD_TO_CLOSE_MS).toInt()
                        progress.animateTo(1f, tween(left, easing = LinearEasing))
                        fire()
                    }
                    tryAwaitRelease()
                    if (!fired) {
                        job.cancel()
                        scope.launch { progress.animateTo(0f, tween(150)) }
                    }
                })
            }
            .semantics {
                role = Role.Button
                contentDescription = "Hold to close"
                customActions = listOf(CustomAccessibilityAction("Close") { fire(); true })
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.align(Alignment.CenterStart).fillMaxHeight().fillMaxWidth(progress.value).background(c.red))
        Text("Hold to close", style = HerdrType.title, color = if (progress.value > 0.5f) c.crust else c.red)
    }
}

/** A bottom sheet in the spec's chrome: base surface, 24dp top radius, grab handle. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HerdrSheet(
    onDismiss: () -> Unit,
    container: Color = Herdr.colors.base,
    scrollable: Boolean = true,
    content: @Composable ColumnScope.() -> Unit,
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = container,
        shape = HerdrRadius.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth()
                .then(if (scrollable) Modifier.verticalScroll(rememberScrollState()) else Modifier)
                .padding(horizontal = 16.dp)
                .padding(bottom = 24.dp)
                .navigationBarsPadding(),
            content = content,
        )
    }
}

/** A grouped list on mantle, r14, rows split by surface0 hairlines. */
@Composable
fun ListCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.fillMaxWidth().clip(HerdrRadius.tile).background(Herdr.colors.mantle), content = content)
}

@Composable
fun ListDivider() = HorizontalDivider(thickness = 1.dp, color = Herdr.colors.surface0)

@Composable
fun ListRow(
    onClick: (() -> Unit)? = null,
    height: androidx.compose.ui.unit.Dp = 52.dp,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = height)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** The spec's search field: base, r12, 44dp, a "⌕" glyph. */
@Composable
fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    container: Color = Herdr.colors.base,
) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth().height(44.dp).clip(HerdrRadius.field).background(container).padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("⌕", style = HerdrType.code.copy(fontSize = HerdrType.small.fontSize), color = c.overlay2)
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) Text(placeholder, style = HerdrType.small, color = c.overlay2, maxLines = 1)
            BasicTextField(
                value = value, onValueChange = onValueChange, singleLine = true,
                textStyle = HerdrType.small.copy(color = c.text),
                cursorBrush = SolidColor(c.mauve),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

@Composable
fun SheetTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, style = HerdrType.headline, color = Herdr.colors.text, modifier = modifier.padding(bottom = 16.dp))
}

@Composable
fun AgentPickerSheet(
    agents: List<String>, recent: List<String>,
    onPick: (String) -> Unit, onOther: () -> Unit, onDismiss: () -> Unit,
) {
    val c = Herdr.colors
    HerdrSheet(onDismiss) {
        var query by rememberSaveable { mutableStateOf("") }
        SheetTitle("New agent")
        SearchField(query, { query = it }, "Search agents", container = c.mantle)
        val recentShown = if (query.isBlank()) recent.filter { it in agents } else emptyList()
        if (recentShown.isNotEmpty()) {
            SectionLabel("recent", c.overlay0, Modifier.padding(top = 16.dp, bottom = 8.dp))
            AgentList(recentShown, onPick)
            SectionLabel("all", c.overlay0, Modifier.padding(top = 16.dp, bottom = 8.dp))
        } else Spacer(Modifier.height(16.dp))
        AgentList(filterAgents(agents, query).filter { it !in recentShown }, onPick)
        Spacer(Modifier.height(8.dp))
        GhostButton("Other…", onOther, Modifier.fillMaxWidth(), height = 44.dp)
    }
}

@Composable
private fun AgentList(names: List<String>, onPick: (String) -> Unit) {
    if (names.isEmpty()) return
    val c = Herdr.colors
    ListCard {
        names.forEachIndexed { i, name ->
            if (i > 0) ListDivider()
            ListRow(onClick = { onPick(name) }) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(name, style = HerdrType.title, color = c.text)
                    describeAgent(name)?.let { Text(it, style = HerdrType.caption, color = c.overlay2) }
                }
            }
        }
    }
}

@Composable
fun MoveDestinationSheet(
    tree: List<WorkspaceNode>,
    currentTabId: String,
    onExistingTab: (String) -> Unit,
    onNewTab: () -> Unit,
    onNewWorkspace: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Herdr.colors
    HerdrSheet(onDismiss) {
        SheetTitle("Move to…")
        ListCard {
            ListRow(onClick = onNewTab) { Text("+ New tab", style = HerdrType.title, color = c.mauve) }
            ListDivider()
            ListRow(onClick = onNewWorkspace) { Text("+ New workspace", style = HerdrType.title, color = c.mauve) }
        }
        val dests = tree.flatMap { w ->
            w.tabs.filter { it.tab.tabId != currentTabId && it.tab.tabId.isNotBlank() }.map { w to it }
        }
        if (dests.isNotEmpty()) {
            SectionLabel("existing tab", c.overlay0, Modifier.padding(top = 16.dp, bottom = 8.dp))
            ListCard {
                dests.forEachIndexed { i, (w, t) ->
                    if (i > 0) ListDivider()
                    ListRow(onClick = { onExistingTab(t.tab.tabId) }) {
                        WorkspaceBadge(w.ws.number, size = 20.dp)
                        Spacer(Modifier.width(12.dp))
                        Text(w.ws.label.ifEmpty { "(unknown)" }, style = HerdrType.body, color = c.text,
                            modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text("tab ${t.tab.label.ifEmpty { t.tab.number.toString() }}", style = HerdrType.meta, color = c.overlay2)
                    }
                }
            }
        }
    }
}

@Composable
fun RenameDialog(target: RowAction, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(target.id) { mutableStateOf(target.label) }
    HerdrDialog(
        title = "Rename",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = text.isNotBlank() && text.trim() != target.label,
        onConfirm = { onConfirm(text.trim()) },
    ) {
        OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true, shape = HerdrRadius.field)
    }
}

@Composable
fun OtherAgentDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    HerdrDialog(
        title = "Run command",
        onDismiss = onDismiss,
        confirmLabel = "Start",
        confirmEnabled = text.isNotBlank(),
        onConfirm = { onConfirm(text) },
    ) {
        OutlinedTextField(
            value = text, onValueChange = { text = it }, singleLine = true, shape = HerdrRadius.field,
            placeholder = { Text("e.g. claude --model opus", style = HerdrType.code) },
            textStyle = HerdrType.code.copy(fontSize = HerdrType.small.fontSize, color = Herdr.colors.text),
        )
    }
}

@Composable
private fun HerdrDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmLabel: String,
    confirmEnabled: Boolean,
    onConfirm: () -> Unit,
    content: @Composable () -> Unit,
) {
    val c = Herdr.colors
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = c.base,
        shape = RoundedCornerShape(24.dp),
        title = { Text(title, style = HerdrType.headline, color = c.text) },
        text = content,
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = confirmEnabled) {
                Text(confirmLabel, style = HerdrType.button, color = if (confirmEnabled) c.mauve else c.overlay0)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", style = HerdrType.button, color = c.subtext1) }
        },
    )
}

internal fun wsAction(node: WorkspaceNode) = RowAction(
    kind = NodeKind.WORKSPACE,
    id = node.ws.workspaceId,
    label = node.ws.label.ifEmpty { "(unknown)" },
    paneCount = workspacePanes(node).size,
    tabCount = node.tabs.size,
)

internal fun tabAction(node: TabNode) = RowAction(
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
    // pivot for it (its tab actions would dispatch with an empty id).
    mergedTab = parentTab?.takeIf { it.tab.tabId.isNotBlank() }?.let { tabAction(it) },
)
