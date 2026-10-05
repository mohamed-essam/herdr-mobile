package dev.herdr.mobile.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.net.PaneAsk
import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.MonoFamily
import dev.herdr.mobile.ui.theme.SansFamily
import dev.herdr.mobile.ui.theme.statusColor
import dev.herdr.mobile.ui.theme.statusGlyph
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Alpha of the last-known state while reconnecting. */
private const val STALE_ALPHA = 0.45f

private val PillShape = RoundedCornerShape(26.dp)

/** A confirmed dashboard answer, offered as "Answer sent to <label>  Open". */
private data class AnswerToast(val paneId: String, val label: String)

/**
 * The attention-first dashboard: what needs you (answerable in place), what's
 * working, then everything else, with the workspace switcher and "+" at the
 * bottom. Opening a pane replaces it with [PaneScreen]. [openRequest] is a pane
 * to open (from a notification), acknowledged with [onOpenRequestHandled].
 * [companionUrl] is shown on the empty state.
 */
@Composable
fun DashboardScreen(
    vm: DashboardViewModel,
    openRequest: String?,
    onOpenRequestHandled: () -> Unit,
    companionUrl: String? = null,
) {
    val panes by vm.panes.collectAsState()
    val connected by vm.connected.collectAsState()
    var selected by remember { mutableStateOf<Pane?>(null) }
    // Kept above the pane screen's early return so an answer in flight (and its
    // confirmation) survives opening a pane.
    val scope = rememberCoroutineScope()
    var answers by remember { mutableStateOf<InlineAnswers>(emptyMap()) }
    var toast by remember { mutableStateOf<AnswerToast?>(null) }

    // A tapped notification opens its pane once, over whatever is showing; it
    // must not reopen it on later updates after the user has gone back.
    LaunchedEffect(openRequest, panes) {
        val id = openRequest ?: return@LaunchedEffect
        when (val r = resolveOpenRequest(id, panes)) {
            OpenRequest.Wait -> return@LaunchedEffect
            is OpenRequest.Open -> selected = r.pane
            OpenRequest.Drop -> {}
        }
        onOpenRequestHandled()
    }

    // Pop back to the dashboard when the pane we're viewing disappears (closed
    // here, or taken over / closed elsewhere).
    LaunchedEffect(panes) {
        val open = selected
        if (open != null && panes.none { it.paneId == open.paneId }) selected = null
    }

    var pendingOpenTerminalId by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { vm.autoOpen.collect { pendingOpenTerminalId = it } }
    LaunchedEffect(panes, pendingOpenTerminalId) {
        val tid = pendingOpenTerminalId ?: return@LaunchedEffect
        panes.firstOrNull { it.terminalId == tid }?.let { selected = it; pendingOpenTerminalId = null }
    }

    LaunchedEffect(answers) {
        val wait = nextAnswerExpiry(answers, System.currentTimeMillis()) ?: return@LaunchedEffect
        delay(wait)
        answers = expireAnswers(answers, System.currentTimeMillis())
    }
    LaunchedEffect(toast) {
        if (toast != null) { delay(RESUMED_TTL_MS); toast = null }
    }

    selected?.let { pane ->
        // `selected` is a snapshot from when it was tapped; read the live pane
        // so the chat flag (and the chat-unavailable banner) stays current.
        val live = panes.firstOrNull { it.paneId == pane.paneId } ?: pane
        BackHandler { selected = null }   // system back returns here, like the header's ←
        PaneScreen(vm, live) { selected = null }
        return   // the pane screen replaces the dashboard while open
    }

    val repoTree by vm.repoTree.collectAsState()
    val tree by vm.tree.collectAsState()
    val lastOpened by vm.lastOpenedPaneId.collectAsState()
    val disconnectedSince by vm.disconnectedSince.collectAsState()
    val now by produceState(System.currentTimeMillis(), connected) {
        while (true) {
            value = System.currentTimeMillis()
            delay(if (connected) 30_000 else 1_000)
        }
    }

    var actionTarget by remember { mutableStateOf<RowAction?>(null) }      // action sheet open for
    var confirmSummary by remember { mutableStateOf<CloseSummary?>(null) } // set: the sheet confirms a close
    var alsoCloses by remember { mutableStateOf<List<String>>(emptyList()) }
    var renameTarget by remember { mutableStateOf<RowAction?>(null) }
    var agentPickerFor by remember { mutableStateOf<RowAction?>(null) }
    var moveTargetPaneId by remember { mutableStateOf<String?>(null) }
    var showOtherDialog by remember { mutableStateOf<RowAction?>(null) }
    var showSwitcher by remember { mutableStateOf(false) }
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    fun openSheet(a: RowAction) {
        if (a.id.isBlank() || !connected) return
        confirmSummary = null
        alsoCloses = emptyList()
        actionTarget = a
    }
    fun dismissSheet() { actionTarget = null; confirmSummary = null; alsoCloses = emptyList() }
    fun open(p: Pane) { if (canOpen(p)) selected = p }
    fun answer(pane: Pane, ask: PaneAsk, item: QuestionItem, label: String, wsLabel: String) {
        answers = startAnswer(answers, pane.paneId, ask.toolUseId, label, System.currentTimeMillis()) ?: return
        scope.launch {
            val r = vm.answerChatResult(pane.paneId, ask.toolUseId, inlineAnswerMap(item, label))
            if (r.isSuccess) {
                answers = answerSent(answers, pane.paneId, System.currentTimeMillis())
                toast = AnswerToast(pane.paneId, wsLabel)
            } else {
                answers = answerFailed(answers, pane.paneId)
            }
        }
    }

    val contexts = remember(repoTree) { paneContexts(repoTree) }
    fun ctxOf(p: Pane) = contexts[p.paneId]?.copy(pane = p) ?: fallbackContext(p)
    val c = Herdr.colors

    Box(Modifier.fillMaxSize().background(c.crust)) {
        when {
            panes.isEmpty() && connected -> EmptyState(companionUrl) { vm.createNode(what = "workspace") }
            panes.isEmpty() -> ConnectingState(companionUrl)
            else -> {
                val sections = dashboardSections(panes, answers)
                val resumed = answers.values.filter { it.phase == AnswerPhase.Sent }
                val counts = statusCounts(panes)
                val dim = Modifier.alpha(if (connected) 1f else STALE_ALPHA)
                val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                LazyColumn(
                    Modifier.fillMaxSize().statusBarsPadding(),
                    contentPadding = PaddingValues(bottom = navBottom + 96.dp),
                ) {
                    item(key = "top") { TopBar(connected, panes.size) }
                    if (!connected) item(key = "reconnect") {
                        ReconnectBanner(staleLabel(disconnectedSince ?: now, now))
                    }
                    item(key = "stats") { StatTiles(counts, dim) }
                    if (sections.needsYou.isNotEmpty() || resumed.isNotEmpty()) {
                        item(key = "h:needs") {
                            SectionHeader("● needs you · ${sections.needsYou.size}", c.red, dim)
                        }
                        items(sections.needsYou, key = { "n:" + it.paneId }) { p ->
                            val ctx = ctxOf(p)
                            NeedsYouCard(
                                ctx, now,
                                answer = answers[p.paneId]?.takeIf { it.phase == AnswerPhase.Sending },
                                enabled = connected,
                                onOpen = { open(p) },
                                onLongPress = { openSheet(paneAction(p, tabOf(p.paneId, tree))) },
                                onAnswer = { ask, item, label -> answer(p, ask, item, label, ctx.workspaceLabel) },
                                modifier = dim.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                        items(resumed, key = { "r:" + it.paneId }) { a ->
                            val ctx = panes.firstOrNull { it.paneId == a.paneId }?.let(::ctxOf)
                            ResumedRow(
                                ctx?.workspaceLabel ?: "pane", ctx?.workspaceNumber ?: 0, a.label,
                                dim.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                    paneGroup("working", sections.working.map(::ctxOf), null, c.yellow, true, now, dim,
                        onOpen = ::open, onLongPress = { p -> openSheet(paneAction(p, tabOf(p.paneId, tree))) })
                    paneGroup("done", sections.done.map(::ctxOf), "✓", c.green, false, now, dim,
                        onOpen = ::open, onLongPress = { p -> openSheet(paneAction(p, tabOf(p.paneId, tree))) })
                    paneGroup("idle", sections.idle.map(::ctxOf), "○", c.overlay2, false, now, dim,
                        onOpen = ::open, onLongPress = { p -> openSheet(paneAction(p, tabOf(p.paneId, tree))) })
                }

                Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().navigationBarsPadding()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                ) {
                    val t = toast
                    if (t != null) {
                        HerdrToast("Answer sent to ${t.label}", actionLabel = "Open", onAction = {
                            toast = null
                            panes.firstOrNull { it.paneId == t.paneId }?.let(::open)
                        })
                    } else {
                        BottomBar(
                            workspaces = repoTree.flatMap { it.workspaces },
                            fabEnabled = connected,
                            onSwitcher = { showSwitcher = true },
                            onNew = { vm.createNode(what = "workspace") },
                        )
                    }
                }
            }
        }
        SnackbarHost(
            snackbarHostState,
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 76.dp),
        )
    }

    if (showSwitcher) {
        val highlighted = (panes.firstOrNull { it.paneId == lastOpened } ?: panes.firstOrNull { it.focused })?.workspaceId
        WorkspaceSwitcher(
            repos = repoTree,
            highlightedWorkspaceId = highlighted,
            actionsEnabled = connected,
            onOpen = { p -> showSwitcher = false; open(p) },
            onActions = { a -> openSheet(a) },
            onNew = { showSwitcher = false; vm.createNode(what = "workspace") },
            onDismiss = { showSwitcher = false },
        )
    }

    actionTarget?.let { target ->
        RowActionSheet(
            target = target,
            header = actionHeader(target, repoTree),
            confirm = confirmSummary,
            alsoCloses = alsoCloses,
            onNewTab = { vm.createNode(what = "tab", workspaceId = target.id); dismissSheet() },
            onNewAgent = { agentPickerFor = target; dismissSheet() },
            onNewShell = { vm.createNode(what = "shell", workspaceId = target.workspaceId); dismissSheet() },
            onSplit = { dir -> vm.createNode(what = "shell", paneId = target.id, direction = dir); dismissSheet() },
            onMove = { moveTargetPaneId = target.id; dismissSheet() },
            onRename = { renameTarget = target; dismissSheet() },
            onClose = {
                if (needsCloseConfirm(target)) {
                    if (target.kind == NodeKind.WORKSPACE) {
                        scope.launch {
                            val impact = vm.closeImpact(target.id)
                            if (actionTarget == target) {
                                alsoCloses = impact
                                confirmSummary = closeSummary(target, tree)
                            }
                        }
                    } else {
                        alsoCloses = emptyList()
                        confirmSummary = closeSummary(target, tree)
                    }
                } else {
                    vm.closeNode(target.kind.wire, target.id)
                    dismissSheet()
                }
            },
            onTabActions = { target.mergedTab?.let(::openSheet) },
            onConfirmClose = { vm.closeNode(target.kind.wire, target.id); dismissSheet() },
            onCancelClose = { dismissSheet() },
            onDismiss = { dismissSheet() },
        )
    }

    renameTarget?.let { target ->
        RenameDialog(
            target = target,
            onConfirm = { newLabel ->
                vm.renameNode(target.kind.wire, target.id, newLabel)
                renameTarget = null
            },
            onDismiss = { renameTarget = null },
        )
    }

    agentPickerFor?.let { target ->
        LaunchedEffect(target) { vm.refreshAgents() }
        val agents by vm.agents.collectAsState()
        val recent by vm.recentAgents.collectAsState()
        AgentPickerSheet(
            agents = agents,
            recent = recent,
            onPick = { name ->
                vm.recordRecentAgent(name)
                if (target.kind == NodeKind.TAB) {
                    vm.createNode(what = "agent", tabId = target.id, direction = "down", agentName = name, argv = listOf(name))
                } else {
                    vm.createNode(what = "agent", workspaceId = target.id, agentName = name, argv = listOf(name))
                }
                agentPickerFor = null
            },
            onOther = { showOtherDialog = target; agentPickerFor = null },
            onDismiss = { agentPickerFor = null },
        )
    }

    showOtherDialog?.let { target ->
        OtherAgentDialog(
            onConfirm = { input ->
                val cmd = parseAgentCommand(input)
                if (cmd.argv.isNotEmpty()) {
                    if (cmd.name.isNotBlank()) vm.recordRecentAgent(cmd.name)
                    if (target.kind == NodeKind.TAB) {
                        vm.createNode(what = "agent", tabId = target.id, direction = "down", agentName = cmd.name, argv = cmd.argv)
                    } else {
                        vm.createNode(what = "agent", workspaceId = target.id, agentName = cmd.name, argv = cmd.argv)
                    }
                }
                showOtherDialog = null
            },
            onDismiss = { showOtherDialog = null },
        )
    }

    moveTargetPaneId?.let { paneId ->
        val currentTab = panes.firstOrNull { it.paneId == paneId }?.tabId ?: ""
        MoveDestinationSheet(
            tree = tree,
            currentTabId = currentTab,
            onExistingTab = { tabId -> vm.moveNode(paneId, "tab", tabId = tabId, direction = "down"); moveTargetPaneId = null },
            onNewTab = { vm.moveNode(paneId, "new_tab"); moveTargetPaneId = null },
            onNewWorkspace = { vm.moveNode(paneId, "new_workspace"); moveTargetPaneId = null },
            onDismiss = { moveTargetPaneId = null },
        )
    }
}

@Composable
private fun TopBar(connected: Boolean, count: Int) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 16.dp).heightIn(min = 30.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Wordmark()
        Spacer(Modifier.weight(1f))
        if (connected) CountPill(if (count == 1) "1 pane" else "$count panes", Herdr.colors.green)
    }
}

@Composable
private fun ReconnectBanner(stale: String) {
    val c = Herdr.colors
    Row(
        Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp).fillMaxWidth()
            .clip(HerdrRadius.tile)
            .background(c.yellow.copy(alpha = 0.10f))
            .border(1.dp, c.yellow.copy(alpha = 0.20f), HerdrRadius.tile)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(spinnerFrame(), style = HerdrType.wordmark.copy(fontSize = 15.sp), color = c.yellow)
        Spacer(Modifier.width(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("Reconnecting…", style = HerdrType.title, color = c.yellow)
            Text("Showing state from $stale", style = HerdrType.caption, color = c.subtext1)
        }
    }
}

@Composable
private fun StatTiles(counts: StatusCounts, modifier: Modifier) {
    val c = Herdr.colors
    val dark = isSystemInDarkTheme()
    Row(
        modifier.padding(start = 16.dp, end = 16.dp, bottom = 24.dp).fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for ((n, status) in listOf(counts.blocked to "blocked", counts.working to "working", counts.done to "done")) {
            Column(
                Modifier.weight(1f).clip(HerdrRadius.tile).background(c.base).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Text("$n", style = HerdrType.stat, color = statusColor(status, dark))
                Text(status, style = HerdrType.caption, color = c.overlay2)
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String, color: Color, modifier: Modifier = Modifier) {
    SectionLabel(text, color, modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 8.dp))
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun NeedsYouCard(
    ctx: PaneContext,
    now: Long,
    answer: InlineAnswer?,
    enabled: Boolean,
    onOpen: () -> Unit,
    onLongPress: () -> Unit,
    onAnswer: (PaneAsk, QuestionItem, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val c = Herdr.colors
    val pane = ctx.pane
    val ask = pane.ask
    val choice = inlineChoice(ask)
    Column(
        modifier.fillMaxWidth()
            .clip(HerdrRadius.card)
            .background(c.base)
            .border(1.dp, c.red.copy(alpha = 0.22f), HerdrRadius.card)
            .combinedClickable(onClick = onOpen, onLongClick = if (enabled) onLongPress else null)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            WorkspaceBadge(ctx.workspaceNumber)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(ctx.workspaceLabel, style = HerdrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(paneMeta(ctx), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Text(relativeAge(pane.activity?.ts ?: 0, now), style = HerdrType.meta, color = c.overlay2)
        }
        when {
            ask != null && choice != null -> {
                Text(choice.question, style = HerdrType.body, color = c.text)
                val sending = answer?.takeIf { it.toolUseId == ask.toolUseId }
                if (sending != null) SendingOptions(choice, sending.label)
                else ChoiceChips(choice, enabled, onPick = { onAnswer(ask, choice, it) }, onOther = onOpen)
            }
            ask != null -> {
                val q = ask.items.firstOrNull()?.question?.takeIf { it.isNotBlank() } ?: "Claude has a question"
                Text(q, style = HerdrType.body, color = c.text)
                GhostButton("Answer in chat ›", onOpen, Modifier.fillMaxWidth(), height = 40.dp)
            }
            else -> Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    activityLine(pane.activity) ?: "Waiting for you",
                    style = HerdrType.body, color = c.subtext1, maxLines = 3, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                GhostButton("›", onOpen, Modifier.width(48.dp).semantics { contentDescription = "Open pane" }, height = 40.dp)
            }
        }
        bgBadge(pane)?.let { BgChip(it, start = 0.dp) }
    }
}

/** A small meta-style chip: the pane's background tasks still running. */
@Composable
private fun BgChip(text: String, start: Dp = 8.dp) {
    val c = Herdr.colors
    Text(
        text,
        style = HerdrType.meta,
        color = c.yellow,
        modifier = Modifier.padding(start = start).clip(HerdrRadius.badge).background(c.yellow.copy(alpha = 0.12f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

@Composable
private fun ChoiceChips(item: QuestionItem, enabled: Boolean, onPick: (String) -> Unit, onOther: () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in item.options) SecondaryButton(o.label, { onPick(o.label) }, enabled = enabled, height = 36.dp)
        GhostButton("Other…", onOther, height = 36.dp)
    }
}

/** The options while an answer sends: the picked one mauve with "sending…", the rest dimmed. */
@Composable
private fun SendingOptions(item: QuestionItem, picked: String) {
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (o in item.options) {
            val mine = o.label == picked
            Row(
                Modifier.fillMaxWidth().height(44.dp).clip(HerdrRadius.field)
                    .background(if (mine) c.mauve else c.surface0)
                    .alpha(if (mine) 1f else 0.5f)
                    .padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    o.label, style = if (mine) HerdrType.title else HerdrType.body, color = if (mine) c.crust else c.text,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
                )
                if (mine) Text("sending…", style = HerdrType.meta, color = c.crust)
            }
        }
    }
}

/** "✓ chat-view resumed / You answered Compact row" after an inline answer lands. */
@Composable
private fun ResumedRow(label: String, number: Int, answer: String, modifier: Modifier) {
    val c = Herdr.colors
    Row(
        modifier.fillMaxWidth()
            .clip(HerdrRadius.card)
            .background(c.base)
            .border(1.dp, c.green.copy(alpha = 0.25f), HerdrRadius.card)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.size(28.dp).shadow(8.dp, CircleShape, ambientColor = c.green, spotColor = c.green)
                .clip(CircleShape).background(c.green),
            contentAlignment = Alignment.Center,
        ) { Text("✓", style = HerdrType.title.copy(fontFamily = MonoFamily), color = c.crust) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("$label resumed", style = HerdrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("You answered $answer", style = HerdrType.small, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(12.dp))
        WorkspaceBadge(number, size = 20.dp)
    }
}

/**
 * A grouped card of panes under a "<glyph> NAME · n" label; a null [glyph] is
 * the working spinner. [accentAge] colors the age in the section color.
 */
private fun LazyListScope.paneGroup(
    name: String,
    ctxs: List<PaneContext>,
    glyph: String?,
    color: Color,
    accentAge: Boolean,
    now: Long,
    dim: Modifier,
    onOpen: (Pane) -> Unit,
    onLongPress: (Pane) -> Unit,
) {
    if (ctxs.isEmpty()) return
    item(key = "h:$name") {
        val g = glyph ?: spinnerFrame()
        SectionHeader("$g $name · ${ctxs.size}", color, dim.padding(top = 8.dp))
    }
    item(key = "g:$name") {
        Column(dim.padding(horizontal = 16.dp).fillMaxWidth().clip(HerdrRadius.card).background(Herdr.colors.base)) {
            ctxs.forEachIndexed { i, ctx ->
                if (i > 0) ListDivider()
                PaneListRow(ctx, if (accentAge) color else Herdr.colors.overlay2, now, onOpen, onLongPress)
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PaneListRow(ctx: PaneContext, ageColor: Color, now: Long, onOpen: (Pane) -> Unit, onLongPress: (Pane) -> Unit) {
    val c = Herdr.colors
    val pane = ctx.pane
    Row(
        Modifier.fillMaxWidth()
            .combinedClickable(onClick = { onOpen(pane) }, onLongClick = { onLongPress(pane) })
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        WorkspaceBadge(ctx.workspaceNumber)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                buildAnnotatedString {
                    append(ctx.workspaceLabel)
                    if (ctx.repoName.isNotBlank() && ctx.repoName != ctx.workspaceLabel) {
                        withStyle(SpanStyle(fontFamily = SansFamily, fontWeight = FontWeight.Normal, fontSize = 13.sp, color = c.overlay2)) {
                            append(" · ${ctx.repoName}")
                        }
                    }
                },
                style = HerdrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val sub = activityLine(pane.activity)
                ?: listOf(pane.agent ?: "shell", tabCrumb(ctx)).filter { it.isNotBlank() }.joinToString(" · ")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(sub, style = HerdrType.meta, color = c.subtext1, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                bgBadge(pane)?.let { BgChip(it) }
            }
        }
        val age = relativeAge(pane.activity?.ts ?: 0, now)
        if (age.isNotEmpty()) {
            Spacer(Modifier.width(8.dp))
            Text(age, style = HerdrType.meta, color = ageColor)
        }
    }
}

@Composable
private fun BottomBar(workspaces: List<WorkspaceNode>, fabEnabled: Boolean, onSwitcher: () -> Unit, onNew: () -> Unit) {
    val c = Herdr.colors
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        Row(
            Modifier.weight(1f).height(52.dp)
                .shadow(16.dp, PillShape)
                .clip(PillShape)
                .background(c.surface0)
                .clickable(onClick = onSwitcher)
                .padding(horizontal = 20.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val shown = workspaces.take(4)
            if (shown.isNotEmpty()) {
                Box(Modifier.width((20 + 14 * (shown.size - 1)).dp).height(24.dp)) {
                    shown.forEachIndexed { i, w ->
                        Box(
                            Modifier.padding(start = (14 * i).dp).align(Alignment.CenterStart)
                                .clip(RoundedCornerShape(7.dp))
                                .background(c.surface0).padding(1.dp),
                        ) { WorkspaceBadge(w.ws.number, size = 20.dp) }
                    }
                }
                Spacer(Modifier.width(12.dp))
            }
            Text("Workspaces", style = HerdrType.body, color = c.text, modifier = Modifier.weight(1f))
            Text("${workspaces.size}", style = HerdrType.meta, color = c.overlay2)
        }
        Box(
            Modifier.size(52.dp)
                .then(if (fabEnabled) Modifier.shadow(12.dp, CircleShape, ambientColor = c.mauve, spotColor = c.mauve) else Modifier)
                .clip(CircleShape)
                .background(if (fabEnabled) c.mauve else c.surface0)
                .clickable(enabled = fabEnabled, onClick = onNew)
                .semantics { contentDescription = "New workspace" },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = HerdrType.body.copy(fontSize = 24.sp), color = if (fabEnabled) c.crust else c.overlay0)
        }
    }
}

@Composable
private fun EmptyState(companionUrl: String?, onNew: () -> Unit) {
    val c = Herdr.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(connected = true, count = 0)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    Box(Modifier.size(12.dp).drawBehind {
                        drawCircle(c.surface2, radius = size.minDimension / 2 - 0.75.dp.toPx(), style = Stroke(1.5.dp.toPx()))
                    })
                }
                GlowDot(c.mauve, size = 12.dp)
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("The herd is resting", style = HerdrType.display, color = c.text)
                Text(
                    "Start an agent in herdr on your desktop and it appears here, or start one from your phone.",
                    style = HerdrType.body.copy(lineHeight = 22.sp), color = c.subtext1,
                )
            }
            Row(
                Modifier.fillMaxWidth().clip(HerdrRadius.field).background(c.base).padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text("❯", style = HerdrType.code.copy(fontSize = 13.sp), color = c.mauve)
                Text(" herdr", style = HerdrType.code.copy(fontSize = 13.sp), color = c.subtext1)
            }
        }
        Column(
            Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            PrimaryButton("+ New workspace", onNew, Modifier.fillMaxWidth())
            companionUrl?.let { Text("connected to ${hostPort(it)}", style = HerdrType.meta, color = c.overlay0) }
        }
    }
}

@Composable
private fun ConnectingState(companionUrl: String?) {
    val c = Herdr.colors
    Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        TopBar(connected = false, count = 0)
        Column(
            Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(spinnerFrame(), style = HerdrType.stat, color = c.yellow)
            Text("Connecting…", style = HerdrType.title, color = c.text)
            companionUrl?.let { Text(hostPort(it), style = HerdrType.meta, color = c.overlay0) }
        }
    }
}
