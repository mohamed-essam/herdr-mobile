package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
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
import dev.herdr.mobile.data.formatTs
import dev.herdr.mobile.data.pendingLabel
import dev.herdr.mobile.data.taskNoticeIsError
import dev.herdr.mobile.data.taskNoticeLabel
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/** Minimum gap between chat_history requests from the paging trigger. */
private const val OLDER_RETRY_MS = 2_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onTerminal: () -> Unit) {
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    var draft by rememberSaveable(pane.paneId) { mutableStateOf("") }
    var expanded by remember(pane.paneId) { mutableStateOf(setOf<String>()) }
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }

    DisposableEffect(pane.paneId) {
        vm.openChat(pane.paneId)
        onDispose { vm.closeChat(pane.paneId) }
    }
    LaunchedEffect(pane.paneId) {
        while (true) { delay(10_000); vm.expireChat() }
    }
    // The dashboard (and its snackbar) isn't composed while a pane is open.
    LaunchedEffect(Unit) {
        vm.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    val results = remember(view.entries) {
        view.entries.mapNotNull { it.event as? ChatEvent.ToolResult }.associateBy { it.toolUseId }
    }
    // An AskUserQuestion shows as its question card, not also as a tool card.
    val rows = remember(view.entries) {
        val questions = view.entries.mapNotNullTo(HashSet()) { (it.event as? ChatEvent.Question)?.toolUseId }
        view.entries.filter { e ->
            val ev = e.event
            ev !is ChatEvent.ToolResult && !(ev is ChatEvent.ToolUse && ev.toolUseId in questions)
        }
    }
    val itemCount = rows.size + view.pending.size
    // The newest item, plus what can still grow under it: a tool card's result
    // and images, a question card's state.
    val lastKey = view.pending.lastOrNull()?.id ?: rows.lastOrNull()?.let { e ->
        val grows = when (val ev = e.event) {
            is ChatEvent.ToolUse -> results[ev.toolUseId]?.let { "r${it.images.size}" }
            is ChatEvent.Question -> "${ev.toolUseId in view.answering}${ev.toolUseId in view.answered}"
            else -> null
        }
        "e${view.epoch}-${e.seq}/$grows"
    }
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
    // Keyed on the newest item, so a page of older history prepended above
    // doesn't count as new output.
    LaunchedEffect(lastKey) {
        if (itemCount > 0 && atBottom) listState.scrollToItem(itemCount - 1, BOTTOM_OFFSET)
    }
    // Page back once the oldest item is on screen. Items are keyed by seq, so
    // the list keeps the visible item where it is when the page is prepended.
    val atTop by remember { derivedStateOf { listState.firstVisibleItemIndex == 0 } }
    // A rejected page clears loadingOlder but leaves hasMore set; space the
    // requests out so sitting at the top doesn't re-request in a tight loop.
    var lastOlderAt by remember(pane.paneId) { mutableLongStateOf(0L) }
    LaunchedEffect(atTop, entryScrolled, view.hasMore, view.loadingOlder) {
        if (atTop && entryScrolled && view.hasMore && !view.loadingOlder) {
            val wait = lastOlderAt + OLDER_RETRY_MS - System.currentTimeMillis()
            if (wait > 0) delay(wait)
            lastOlderAt = System.currentTimeMillis()
            vm.loadOlderChat(pane.paneId)
        }
    }

    val title = pane.cwd.substringAfterLast('/').ifBlank { pane.workspaceId.ifBlank { pane.paneId } }
    val status = when {
        !connected -> "reconnecting…"
        view.state == "working" -> "working ${spinnerFrame()}"
        else -> "idle"
    }
    // "Today" moves on a minute ticker, so items only recompose when it ticks.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    val ts: (Long?) -> String? = remember(now) {
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        ({ t -> t?.let { formatTs(it, now, zone, locale) } })
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(snackbarHostState) },
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
            } else Box(Modifier.weight(1f).fillMaxWidth()) {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(rows, key = { "e${view.epoch}-${it.seq}" }) { entry ->
                        when (val ev = entry.event) {
                            is ChatEvent.UserText -> UserBubble(ev.text, time = ts(ev.ts)) {
                                for (id in ev.images) ChatImage(vm, pane.paneId, id)
                            }
                            is ChatEvent.AssistantText -> AssistantBlock(ev.text, ts(ev.ts))
                            is ChatEvent.ToolUse -> {
                                val result = results[ev.toolUseId]
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    ToolCard(ev, result, ev.toolUseId in expanded) {
                                        expanded = if (ev.toolUseId in expanded) expanded - ev.toolUseId else expanded + ev.toolUseId
                                    }
                                    result?.images?.forEach { ChatImage(vm, pane.paneId, it) }
                                }
                            }
                            is ChatEvent.TaskNotice -> TaskNoticeRow(ev)
                            is ChatEvent.ToolResult -> {}
                            is ChatEvent.Question -> QuestionCard(
                                ev,
                                enabled = connected && pane.chat,
                                sending = ev.toolUseId in view.answering,
                                answered = ev.toolUseId in view.answered,
                                answer = results[ev.toolUseId]?.preview,
                            ) { vm.answerChat(pane.paneId, ev.toolUseId, it) }
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
                // An overlay, not a list row: a row above the oldest item would
                // become the scroll anchor and the prepended page would push the
                // view to its top.
                if (view.loadingOlder) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp),
                    ) {
                        Text(
                            "loading earlier…", Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun UserBubble(
    text: String,
    pending: PendingMsg? = null,
    label: String? = null,
    time: String? = null,
    onRetry: (() -> Unit)? = null,
    images: @Composable ColumnScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.widthIn(max = 320.dp).alpha(if (pending != null) 0.6f else 1f),
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                images()
                // A message that is only an image has no text.
                if (text.isNotEmpty() || pending != null) {
                    SelectionContainer { Text(text, color = MaterialTheme.colorScheme.onPrimaryContainer) }
                }
            }
        }
        if (time != null) TimeLabel(time)
        if (label != null) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (onRetry != null) TextButton(onClick = onRetry) { Text("Retry") }
            }
        }
    }
}

@Composable
private fun TimeLabel(time: String) {
    Text(time, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f))
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
private fun AssistantBlock(text: String, time: String?) {
    Column(Modifier.fillMaxWidth()) {
        SelectionContainer { ChatMarkdown(text) }
        if (time != null) TimeLabel(time)
    }
}

@Composable
private fun ToolCard(use: ChatEvent.ToolUse, result: ChatEvent.ToolResult?, expanded: Boolean, onToggle: () -> Unit) {
    val accent = if (result?.isError == true) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(6.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        // Only the header toggles, so the expanded output below can be selected.
        Text(
            (if (expanded) "▾ " else "▸ ") + use.summary,
            color = accent, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.fillMaxWidth().clickable(enabled = result != null, onClick = onToggle)
                .padding(horizontal = 10.dp, vertical = 6.dp),
        )
        if (expanded && result != null) {
            SelectionContainer {
                Text(
                    result.preview.ifBlank { "(no output)" },
                    fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 10.dp, end = 10.dp, bottom = 6.dp),
                )
            }
        }
    }
}
