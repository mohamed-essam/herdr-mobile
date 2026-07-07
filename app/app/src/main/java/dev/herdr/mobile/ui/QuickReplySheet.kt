package dev.herdr.mobile.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickReplySheet(vm: DashboardViewModel, pane: Pane, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var output by remember { mutableStateOf("Loading…") }
    var reply by remember { mutableStateOf("") }

    LaunchedEffect(pane.paneId) {
        output = runCatching { vm.peek(pane.paneId) }.getOrElse { "Failed to read pane: ${it.message}" }
    }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(16.dp).fillMaxWidth()) {
            Text(pane.workspaceId + " · " + (pane.agentStatus ?: "—"), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            SelectionContainer {
                Text(output, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.heightIn(max = 260.dp).fillMaxWidth().verticalScroll(rememberScrollState()).horizontalScroll(rememberScrollState()))
            }
            Spacer(Modifier.height(12.dp))
            Row {
                listOf("y", "n", "enter", "esc", "ctrl+c").forEach { key ->
                    AssistChip(onClick = { scope.launch { runCatching {
                        if (key == "y" || key == "n") vm.reply(pane.paneId, key, sendEnter = true)
                        else vm.quickKey(pane.paneId, key)
                    } } }, label = { Text(key) }, modifier = Modifier.padding(end = 6.dp))
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = reply, onValueChange = { reply = it }, modifier = Modifier.weight(1f), label = { Text("Reply") })
                Spacer(Modifier.width(8.dp))
                Button(onClick = { scope.launch { runCatching { vm.reply(pane.paneId, reply, sendEnter = true) }; reply = "" } }) { Text("Send") }
            }
        }
    }
}
