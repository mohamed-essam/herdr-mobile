package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.PendingMsg
import dev.herdr.mobile.data.PendingStatus
import dev.herdr.mobile.data.BOTTOM_OFFSET
import dev.herdr.mobile.data.entryScrollTarget
import dev.herdr.mobile.data.pendingLabel
import dev.herdr.mobile.data.taskNoticeIsError
import dev.herdr.mobile.data.taskNoticeLabel
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onTerminal: () -> Unit) {
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    var draft by rememberSaveable(pane.paneId) { mutableStateOf("") }
    var expanded by remember(pane.paneId) { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()

    DisposableEffect(pane.paneId) {
        vm.openChat(pane.paneId)
        onDispose { vm.closeChat(pane.paneId) }
    }
    LaunchedEffect(pane.paneId) {
        while (true) { delay(10_000); vm.expireChat() }
    }

    val results = remember(view.entries) {
        view.entries.mapNotNull { it.event as? ChatEvent.ToolResult }.associateBy { it.toolUseId }
    }
    val rows = remember(view.entries) { view.entries.filter { it.event !is ChatEvent.ToolResult } }
    val itemCount = rows.size + view.pending.size
    // Follow new output only while the user is already at the bottom.
    val atBottom by remember {
        derivedStateOf {
            val last = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1
            last >= listState.layoutInfo.totalItemsCount - 2
        }
    }
    // Entering the screen (or toggling back from the terminal) with a loaded
    // view opens at the newest item, once; later updates use the follow rule.
    var entryScrolled by remember(pane.paneId) { mutableStateOf(false) }
    LaunchedEffect(pane.paneId, view.loaded) {
        entryScrollTarget(view.loaded, itemCount, entryScrolled)?.let {
            listState.scrollToItem(it, BOTTOM_OFFSET)
            entryScrolled = true
        }
    }
    LaunchedEffect(itemCount) {
        if (itemCount > 0 && atBottom) listState.scrollToItem(itemCount - 1, BOTTOM_OFFSET)
    }

    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }
    val status = when {
        !connected -> "reconnecting…"
        view.state == "working" -> "working ${spinnerFrame()}"
        else -> "idle"
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surfaceContainer),
                navigationIcon = { IconButton(onClick = onExit) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") } },
                title = {
                    Column {
                        Text(title, style = MaterialTheme.typography.titleMedium)
                        Text(status, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = { IconButton(onClick = onTerminal) { Icon(Icons.Filled.Terminal, "terminal view") } },
            )
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().imePadding()) {
            if (!pane.chat) {
                Surface(color = MaterialTheme.colorScheme.errorContainer) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("chat unavailable — mod not reporting", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = onTerminal) { Text("Open terminal") }
                    }
                }
            }
            if (!view.loaded) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Text("loading…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { "e${view.epoch}-${it.seq}" }) { entry ->
                        when (val ev = entry.event) {
                            is ChatEvent.UserText -> UserBubble(ev.text)
                            is ChatEvent.AssistantText -> AssistantBlock(ev.text)
                            is ChatEvent.ToolUse -> ToolCard(
                                ev, results[ev.toolUseId], ev.toolUseId in expanded,
                            ) { expanded = if (ev.toolUseId in expanded) expanded - ev.toolUseId else expanded + ev.toolUseId }
                            is ChatEvent.TaskNotice -> TaskNoticeRow(ev)
                            is ChatEvent.ToolResult -> {}
                        }
                    }
                    items(view.pending, key = { it.id }) { p ->
                        UserBubble(
                            p.text,
                            pending = p,
                            label = pendingLabel(p, view.state),
                            onRetry = if (p.status != PendingStatus.Queued) ({ vm.retryChat(pane.paneId, p.id) }) else null,
                        )
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.Bottom) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Message Claude") },
                    maxLines = 5,
                )
                IconButton(
                    enabled = connected && pane.chat && draft.isNotBlank(),
                    onClick = { vm.sendChat(pane.paneId, draft); draft = "" },
                ) { Icon(Icons.AutoMirrored.Filled.Send, "send") }
            }
        }
    }
}

@Composable
private fun UserBubble(text: String, pending: PendingMsg? = null, label: String? = null, onRetry: (() -> Unit)? = null) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp).alpha(if (pending != null) 0.6f else 1f),
        ) {
            Text(text, Modifier.padding(horizontal = 12.dp, vertical = 8.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
        }
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun TaskNoticeRow(n: ChatEvent.TaskNotice) {
    Text(
        taskNoticeLabel(n),
        modifier = Modifier.fillMaxWidth().alpha(0.8f),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelSmall,
        color = if (taskNoticeIsError(n)) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun AssistantBlock(text: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (seg in splitFences(text)) {
            when (seg) {
                is TextSegment.Prose -> Text(seg.text, style = MaterialTheme.typography.bodyMedium)
                is TextSegment.Code -> Text(
                    seg.text,
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
                        .background(MaterialTheme.colorScheme.surfaceContainer)
                        .horizontalScroll(rememberScrollState()).padding(8.dp),
                    softWrap = false,
                )
            }
        }
    }
}

@Composable
private fun ToolCard(use: ChatEvent.ToolUse, result: ChatEvent.ToolResult?, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (result?.isError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .clickable(enabled = result != null, onClick = onToggle).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Text(
            (if (expanded) "▾ " else "▸ ") + use.summary,
            color = accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
        if (expanded && result != null) {
            Text(
                result.preview.ifBlank { "(no output)" },
                fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}
