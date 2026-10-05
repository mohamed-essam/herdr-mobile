package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.BOTTOM_OFFSET
import dev.herdr.mobile.data.PendingMsg
import dev.herdr.mobile.data.PendingStatus
import dev.herdr.mobile.data.entryScrollTarget
import dev.herdr.mobile.data.followAfterScroll
import dev.herdr.mobile.data.formatTs
import dev.herdr.mobile.data.pendingLabel
import dev.herdr.mobile.data.taskNoticeIsError
import dev.herdr.mobile.data.taskNoticeLabel
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay
import java.time.ZoneId
import java.util.Locale

/** Minimum gap between chat_history requests from the paging trigger. */
private const val OLDER_RETRY_MS = 2_000L

/** The timeline's time gutter and the gap between it and the content. */
private val GUTTER = 40.dp
private val GUTTER_GAP = 12.dp

/**
 * A pane's chat as a timeline: times in a left gutter, "you"/agent labels at
 * the head of each block, tool calls folded onto a thin rail. The composer
 * (with Interrupt while the agent works) sits at the bottom; a pending
 * question replaces it as a pinned sheet.
 */
@Composable
fun ChatScreen(vm: DashboardViewModel, pane: Pane, onExit: () -> Unit, onTerminal: () -> Unit) {
    val c = Herdr.colors
    val view by remember(pane.paneId) { vm.chatView(pane.paneId) }.collectAsState()
    val connected by vm.connected.collectAsState()
    val repoTree by vm.repoTree.collectAsState()
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

    val ctx = remember(repoTree, pane) { paneContexts(repoTree)[pane.paneId] ?: fallbackContext(pane) }
    val agent = pane.agent?.takeIf { it.isNotBlank() } ?: "claude"
    val working = view.state == "working"
    val results = remember(view.entries) {
        view.entries.mapNotNull { it.event as? ChatEvent.ToolResult }.associateBy { it.toolUseId }
    }
    val items = remember(view.entries, view.epoch, view.pageStarts) { buildTimeline(view.entries, view.epoch, view.pageStarts) }
    val running = remember(items, results, working) { runningTools(items, results.keys, working) }
    val asking = remember(view.entries, view.answered) { pendingQuestion(view.entries, view.answered) }
    val status = chatStatus(connected, view.state, asking != null && asking.toolUseId !in view.answering)
    val statusColor = when (status.kind) {
        ChatStatusKind.Waiting -> c.red
        ChatStatusKind.Working -> c.yellow
        ChatStatusKind.Offline, ChatStatusKind.Idle -> c.overlay2
    }

    val itemCount = items.size + view.pending.size
    // The newest item, plus what can still grow under it: a tool group's calls,
    // results and images, a question's state.
    val lastKey = view.pending.lastOrNull()?.id ?: items.lastOrNull()?.let { item ->
        val grows = when (item) {
            is TimelineItem.Tools -> "${item.tools.size}/" + item.tools.sumOf { e ->
                (e.event as? ChatEvent.ToolUse)?.let { results[it.toolUseId] }?.let { 1 + it.images.size } ?: 0
            }
            is TimelineItem.Event -> (item.entry.event as? ChatEvent.Question)?.let {
                "${it.toolUseId in view.answering}${it.toolUseId in view.answered}"
            }
        }
        "${item.key}/$grows"
    }
    // Follow new output only while the user is at the very bottom, judged by
    // where their own scrolling leaves the list: a folded tool rail can be
    // taller than the screen, so "the last rows are visible" isn't "at bottom".
    var follow by remember(pane.paneId) { mutableStateOf(true) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress to listState.canScrollForward }
            .collect { (scrolling, canScrollForward) -> follow = followAfterScroll(follow, scrolling, canScrollForward) }
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
        if (itemCount > 0 && follow) listState.scrollToItem(itemCount - 1, BOTTOM_OFFSET)
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

    // "Today" moves on a minute ticker, so items only recompose when it ticks.
    val now by produceState(System.currentTimeMillis()) {
        while (true) { delay(60_000); value = System.currentTimeMillis() }
    }
    val ts: (Long?) -> String? = remember(now) {
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        ({ t -> t?.let { formatTs(it, now, zone, locale) } })
    }
    val toggle: (String) -> Unit = { id -> expanded = if (id in expanded) expanded - id else expanded + id }

    Box(Modifier.fillMaxSize().background(c.crust)) {
        Column(Modifier.fillMaxSize().imePadding()) {
            PaneHeader(
                ctx = ctx,
                breadcrumb = paneBreadcrumb(ctx),
                status = status.label,
                statusColor = statusColor,
                onBack = onExit,
                toggleLabel = ">_ terminal",
                onToggle = onTerminal,
            )
            if (!pane.chat) UnavailableBanner(onTerminal)
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val sheetMax = maxHeight * 0.8f
                Column(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxWidth().alpha(if (asking != null) 0.45f else 1f)) {
                        if (!view.loaded) {
                            Text("loading…", Modifier.align(Alignment.Center), style = HerdrType.meta, color = c.overlay2)
                        } else {
                            LazyColumn(
                                state = listState,
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 16.dp),
                            ) {
                                itemsIndexed(items, key = { _, it -> it.key }) { i, item ->
                                    val top = if (i == 0) 0.dp else if (item.head) 16.dp else 8.dp
                                    when (item) {
                                        is TimelineItem.Tools -> GutterRow(
                                            if (item.head) ts((item.tools.first().event as? ChatEvent.ToolUse)?.ts) else null, top,
                                        ) {
                                            if (item.head) SpeakerLabel(agent, c.blue)
                                            ToolRail(vm, pane.paneId, item, results, running, expanded, toggle)
                                        }
                                        is TimelineItem.Event -> EventRow(
                                            vm, pane, item, top, agent, ts, view.answering, view.answered,
                                            results, asking?.toolUseId,
                                        )
                                    }
                                }
                                itemsIndexed(view.pending, key = { _, p -> p.id }) { i, p ->
                                    val head = i == 0 && items.lastOrNull()?.speaker != Speaker.User
                                    GutterRow(null, if (items.isEmpty() && i == 0) 0.dp else if (head) 16.dp else 8.dp, spacing = 4.dp) {
                                        if (head) SpeakerLabel("you", c.mauve)
                                        PendingMessage(
                                            p,
                                            label = pendingLabel(p, view.state),
                                            onRetry = if (p.status != PendingStatus.Queued) ({ vm.retryChat(pane.paneId, p.id) }) else null,
                                        )
                                    }
                                }
                            }
                        }
                        // An overlay, not a list row: a row above the oldest item would
                        // become the scroll anchor and the prepended page would push the
                        // view to its top.
                        val pill = when {
                            view.loadingOlder -> "loading earlier…"
                            view.gap -> "resyncing…"
                            else -> null
                        }
                        if (pill != null) {
                            Text(
                                pill,
                                style = HerdrType.meta,
                                color = c.overlay2,
                                modifier = Modifier.align(Alignment.TopCenter).padding(top = 8.dp)
                                    .clip(HerdrRadius.field).background(c.base)
                                    .padding(horizontal = 12.dp, vertical = 4.dp),
                            )
                        }
                    }
                    if (asking != null) {
                        QuestionSheet(
                            asking,
                            agent = agent,
                            enabled = connected && pane.chat,
                            sending = asking.toolUseId in view.answering,
                            modifier = Modifier.heightIn(max = sheetMax),
                        ) { vm.answerChat(pane.paneId, asking.toolUseId, it) }
                    } else {
                        Composer(
                            draft = draft,
                            onDraft = { draft = it },
                            placeholder = if (working) "Queue a message…" else "Message $agent…",
                            canSend = connected && pane.chat && draft.isNotBlank(),
                            onSend = { vm.sendChat(pane.paneId, draft); draft = "" },
                            interrupt = if (working) ({ vm.interruptChat(pane.paneId) }) else null,
                            interruptEnabled = connected,
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().imePadding().padding(bottom = 72.dp),
        )
    }
}

@Composable
private fun EventRow(
    vm: DashboardViewModel,
    pane: Pane,
    item: TimelineItem.Event,
    top: Dp,
    agent: String,
    ts: (Long?) -> String?,
    answering: Set<String>,
    answered: Set<String>,
    results: Map<String, ChatEvent.ToolResult>,
    askingId: String?,
) {
    val c = Herdr.colors
    val time = { t: Long? -> if (item.head) ts(t) else null }
    when (val ev = item.entry.event) {
        is ChatEvent.UserText -> GutterRow(time(ev.ts), top, spacing = 4.dp) {
            if (item.head) SpeakerLabel("you", c.mauve)
            for (id in ev.images) ChatImage(vm, pane.paneId, id, ev.imageSizes[id])
            // A message that is only an image has no text.
            if (ev.text.isNotEmpty()) SelectionContainer { Text(ev.text, style = HerdrType.body, color = c.text) }
        }
        is ChatEvent.AssistantText -> GutterRow(time(ev.ts), top) {
            if (item.head) SpeakerLabel(agent, c.blue)
            SelectionContainer { ChatMarkdown(ev.text) }
        }
        is ChatEvent.Question -> GutterRow(time(ev.ts), top) {
            if (item.head) SpeakerLabel(agent, c.blue)
            QuestionHistory(
                ev,
                pending = ev.toolUseId == askingId,
                sending = ev.toolUseId in answering,
                answered = ev.toolUseId in answered,
                answer = results[ev.toolUseId]?.preview,
            )
        }
        is ChatEvent.TaskNotice -> GutterRow(ts(ev.ts), top) {
            Text(
                taskNoticeLabel(ev),
                style = HerdrType.meta,
                color = if (taskNoticeIsError(ev)) c.red else c.overlay2,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        is ChatEvent.ToolUse, is ChatEvent.ToolResult -> {}
    }
}

/** A timeline row: [time] in the 40dp gutter, the block's content beside it. */
@Composable
private fun GutterRow(time: String?, top: Dp, spacing: Dp = 8.dp, content: @Composable ColumnScope.() -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = top)) {
        Box(Modifier.width(GUTTER).padding(top = 1.dp)) {
            if (time != null) Text(time, style = HerdrType.meta, color = Herdr.colors.overlay0)
        }
        Spacer(Modifier.width(GUTTER_GAP))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing), content = content)
    }
}

@Composable
private fun SpeakerLabel(text: String, color: Color) {
    Text(text, style = HerdrType.meta.copy(fontWeight = FontWeight.SemiBold), color = color)
}

@Composable
private fun PendingMessage(p: PendingMsg, label: String, onRetry: (() -> Unit)?) {
    val c = Herdr.colors
    SelectionContainer { Text(p.text, style = HerdrType.body, color = c.text, modifier = Modifier.alpha(0.6f)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = HerdrType.meta, color = if (p.status == PendingStatus.Queued) c.overlay2 else c.red)
        if (onRetry != null) {
            Text(
                "Retry",
                style = HerdrType.button,
                color = c.mauve,
                modifier = Modifier.padding(start = 4.dp).clip(HerdrRadius.badge).clickable(onClick = onRetry)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * Consecutive tool calls on a thin rail, one mono line each ("$ Read ui/X.kt").
 * A running call is a yellow spinner line on a yellow rail; a failed one is
 * red. Tapping a finished call shows its result preview under it.
 */
@Composable
private fun ToolRail(
    vm: DashboardViewModel,
    paneId: String,
    group: TimelineItem.Tools,
    results: Map<String, ChatEvent.ToolResult>,
    running: Set<String>,
    expanded: Set<String>,
    onToggle: (String) -> Unit,
) {
    val c = Herdr.colors
    val live = group.tools.any { (it.event as? ChatEvent.ToolUse)?.toolUseId in running }
    val rail = if (live) c.yellow else c.surface1
    Column(
        Modifier.fillMaxWidth()
            .drawBehind { drawLine(rail, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx()) }
            .padding(start = 12.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        for (e in group.tools) {
            val use = e.event as? ChatEvent.ToolUse ?: continue
            val result = results[use.toolUseId]
            val isRunning = use.toolUseId in running
            val open = use.toolUseId in expanded && result != null
            val color = when {
                isRunning -> c.yellow
                result?.isError == true -> c.red
                else -> c.subtext1
            }
            val mark = if (isRunning) spinnerFrame() else if (open) "▾" else "$"
            // Only the line toggles, so the expanded output below can be selected.
            Text(
                "$mark ${toolLine(use.tool, use.summary)}",
                style = HerdrType.meta,
                color = color,
                maxLines = if (open) 4 else 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth().clickable(enabled = result != null) { onToggle(use.toolUseId) },
            )
            if (open && result != null) {
                SelectionContainer {
                    Text(
                        result.preview.ifBlank { "(no output)" },
                        style = HerdrType.meta,
                        color = c.overlay2,
                        modifier = Modifier.padding(start = 16.dp),
                    )
                }
            }
            result?.let { r -> r.images.forEach { ChatImage(vm, paneId, it, r.imageSizes[it]) } }
        }
    }
}

@Composable
private fun UnavailableBanner(onTerminal: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().background(c.red.copy(alpha = 0.12f)).padding(start = 20.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("chat unavailable — mod not reporting", Modifier.weight(1f), style = HerdrType.small, color = c.red)
        Text(
            "Open terminal",
            style = HerdrType.button,
            color = c.mauve,
            modifier = Modifier.clip(HerdrRadius.badge).clickable(onClick = onTerminal).padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

/**
 * The bottom row: the message field, a send button once there's text, and
 * Interrupt (esc to the pane) while the agent works. With a draft, Interrupt
 * shrinks to its keycap to leave the field room.
 */
@Composable
private fun Composer(
    draft: String,
    onDraft: (String) -> Unit,
    placeholder: String,
    canSend: Boolean,
    onSend: () -> Unit,
    interrupt: (() -> Unit)?,
    interruptEnabled: Boolean,
) {
    val c = Herdr.colors
    Row(
        Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        BasicTextField(
            value = draft,
            onValueChange = onDraft,
            maxLines = 5,
            textStyle = HerdrType.body.copy(color = c.text),
            cursorBrush = SolidColor(c.mauve),
            modifier = Modifier.weight(1f),
            decorationBox = { inner ->
                Box(
                    Modifier.fillMaxWidth().heightIn(min = 48.dp).clip(HerdrRadius.tile).background(c.base)
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    contentAlignment = Alignment.CenterStart,
                ) {
                    if (draft.isEmpty()) Text(placeholder, style = HerdrType.body, color = c.overlay0, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    inner()
                }
            },
        )
        if (draft.isNotBlank()) {
            Box(
                Modifier.size(48.dp).clip(HerdrRadius.tile).background(if (canSend) c.mauve else c.surface0)
                    .clickable(enabled = canSend, onClick = onSend),
                contentAlignment = Alignment.Center,
            ) { Text("↑", style = HerdrType.title, color = if (canSend) c.crust else c.overlay0) }
        }
        if (interrupt != null) {
            val ink = if (interruptEnabled) c.red else c.overlay0
            Row(
                Modifier.height(48.dp).clip(HerdrRadius.tile).background(c.red.copy(alpha = 0.12f))
                    .clickable(enabled = interruptEnabled, onClick = interrupt)
                    .padding(horizontal = if (draft.isNotBlank()) 12.dp else 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "esc",
                    style = HerdrType.meta,
                    color = ink,
                    modifier = Modifier.border(1.dp, ink.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 4.dp, vertical = 2.dp),
                )
                if (draft.isBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text("Interrupt", style = HerdrType.button, color = ink)
                }
            }
        }
    }
}
