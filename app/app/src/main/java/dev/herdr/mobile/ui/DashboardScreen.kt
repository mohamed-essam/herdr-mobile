package dev.herdr.mobile.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(vm: DashboardViewModel, initialPaneId: String?) {
    val panes by vm.panes.collectAsState()
    val connected by vm.connected.collectAsState()
    var selected by remember { mutableStateOf<Pane?>(null) }

    // open the sheet if launched from a notification
    LaunchedEffect(initialPaneId, panes) {
        if (initialPaneId != null && selected == null) {
            panes.firstOrNull { it.paneId == initialPaneId }?.let { selected = it }
        }
    }

    Scaffold(topBar = {
        TopAppBar(title = { Text("herdr") }, actions = {
            Text(if (connected) "●" else "○", modifier = Modifier.padding(end = 16.dp))
        })
    }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            items(panes, key = { it.paneId }) { PaneRow(it) { p -> selected = p } }
        }
    }

    selected?.let { pane ->
        QuickReplySheet(vm, pane) { selected = null }
    }
}
