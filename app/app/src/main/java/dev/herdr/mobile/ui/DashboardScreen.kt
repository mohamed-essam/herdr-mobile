package dev.herdr.mobile.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import dev.herdr.mobile.ui.theme.statusColor
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, initialPaneId: String?) {
    val panes by vm.panes.collectAsState()
    val connected by vm.connected.collectAsState()
    var selected by remember { mutableStateOf<Pane?>(null) }

    LaunchedEffect(initialPaneId, panes) {
        if (initialPaneId != null && selected == null) {
            panes.firstOrNull { it.paneId == initialPaneId && it.agent != null }?.let { selected = it }
        }
    }

    // Pop back to the dashboard when the pane we're viewing disappears (closed
    // from the sidebar, or taken over / closed elsewhere).
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

    selected?.let { pane ->
        TerminalScreen(vm, pane) { selected = null }
        return   // full-screen terminal replaces the dashboard while open
    }

    val tree by vm.tree.collectAsState()
    val collapsed by vm.collapsed.collectAsState()
    val lastOpened by vm.lastOpenedPaneId.collectAsState()
    val focusedPaneId = panes.firstOrNull { it.focused }?.paneId
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var actionTarget by remember { mutableStateOf<RowAction?>(null) }   // action sheet open for
    var renameTarget by remember { mutableStateOf<RowAction?>(null) }   // rename dialog open for
    var confirmTarget by remember { mutableStateOf<RowAction?>(null) }  // close-confirm open for
    var agentPickerFor by remember { mutableStateOf<RowAction?>(null) } // agent picker open for (Task 5)
    var moveTargetPaneId by remember { mutableStateOf<String?>(null) }  // move sheet open for (Task 5)
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(Unit) {
        vm.actionErrors.collect { snackbarHostState.showSnackbar(it) }
    }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            SidebarDrawer(
                tree = tree,
                collapsed = collapsed,
                focusedPaneId = focusedPaneId,
                lastOpenedPaneId = lastOpened,
                onToggle = vm::toggleExpanded,
                onSelectPane = { p ->
                    scope.launch { drawerState.close() }
                    selected = p
                },
                onRowAction = { a -> if (a.id.isNotBlank()) actionTarget = a },
                onNewWorkspace = { vm.createNode(what = "workspace") },
            )
        },
    ) {
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            topBar = { HerdrTopBar(connected, panes.size) { scope.launch { drawerState.open() } } },
            snackbarHost = { SnackbarHost(snackbarHostState) },
        ) { pad ->
            Column(Modifier.padding(pad).fillMaxSize()) {
                if (!connected) ReconnectingBanner()
                if (panes.isEmpty()) {
                    EmptyState(connected)
                } else {
                    LazyColumn(contentPadding = PaddingValues(vertical = 8.dp), modifier = Modifier.fillMaxSize()) {
                        items(panes, key = { it.paneId }) { pane ->
                            PaneRow(pane) { p -> if (p.agent != null) selected = p }
                        }
                    }
                }
            }
        }

        actionTarget?.let { target ->
            RowActionSheet(
                target = target,
                onNewTab = { vm.createNode(what = "tab", workspaceId = target.id); actionTarget = null },
                onNewAgent = { agentPickerFor = target; actionTarget = null },
                onNewShell = { vm.createNode(what = "shell", workspaceId = target.workspaceId); actionTarget = null },
                onSplit = { dir -> vm.createNode(what = "shell", paneId = target.id, direction = dir); actionTarget = null },
                onMove = { moveTargetPaneId = target.id; actionTarget = null },
                onRename = { renameTarget = target; actionTarget = null },
                onClose = {
                    actionTarget = null
                    if (needsCloseConfirm(target)) confirmTarget = target
                    else vm.closeNode(target.kind.wire, target.id)
                },
                onDismiss = { actionTarget = null },
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

        confirmTarget?.let { target ->
            AlertDialog(
                onDismissRequest = { confirmTarget = null },
                title = { Text("Close ${target.label}") },
                text = { Text(closeConfirmMessage(target)) },
                confirmButton = {
                    TextButton(onClick = {
                        vm.closeNode(target.kind.wire, target.id)
                        confirmTarget = null
                    }) { Text("Close") }
                },
                dismissButton = { TextButton(onClick = { confirmTarget = null }) { Text("Cancel") } },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HerdrTopBar(connected: Boolean, count: Int, onMenu: () -> Unit) {
    val dark = isSystemInDarkTheme()
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
        ),
        navigationIcon = {
            IconButton(onClick = onMenu) {
                Icon(Icons.Filled.Menu, contentDescription = "workspaces", tint = MaterialTheme.colorScheme.onSurface)
            }
        },
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("herdr", style = MaterialTheme.typography.titleLarge)
                    Text(
                        "  ❯",
                        style = MaterialTheme.typography.titleLarge,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Text(
                    "one terminal for the whole herd",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        actions = {
            val color = if (connected) statusColor("done", dark) else statusColor("working", dark)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(end = 16.dp),
            ) {
                if (connected) {
                    Text("●", color = color, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "$count",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                } else {
                    Text(spinnerFrame(), color = color, style = MaterialTheme.typography.titleMedium)
                }
            }
        },
    )
}

@Composable
private fun ReconnectingBanner() {
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Text(
                spinnerFrame(),
                color = statusColor("working", isSystemInDarkTheme()),
                style = MaterialTheme.typography.titleMedium,
            )
            Spacer(Modifier.width(10.dp))
            Text(
                "reconnecting to companion…",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun EmptyState(connected: Boolean) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                if (connected) "◌" else "⠿",
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(12.dp))
            Text(
                if (connected) "no panes yet" else "connecting…",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            if (connected) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "start an agent in herdr and it'll show up here",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun RenameDialog(target: RowAction, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(target.id) { mutableStateOf(target.label) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                shape = MaterialTheme.shapes.small,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(text.trim()) },
                enabled = text.isNotBlank() && text.trim() != target.label,
            ) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
