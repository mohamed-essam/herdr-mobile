package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.Server
import dev.herdr.mobile.data.defaultServerName
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType

/** The dashboard header's active-server chip; opens [ServerSheet]. */
@Composable
fun ServerChip(name: String, onClick: () -> Unit) {
    val c = Herdr.colors
    Row(
        Modifier.clip(RoundedCornerShape(16.dp)).background(c.base).clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(name, style = HerdrType.meta, color = c.subtext1, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.widthIn(max = 160.dp))
        Spacer(Modifier.width(6.dp))
        Text("▾", style = HerdrType.meta, color = c.overlay2)
    }
}

/**
 * Saved servers: tap one to switch to it, its ⋯ to rename or remove it, and
 * "+ Add server" to connect another.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ServerSheet(
    servers: List<Server>,
    activeId: String?,
    onSwitch: (Server) -> Unit,
    onRename: (Server, String) -> Unit,
    onRemove: (Server) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    val c = Herdr.colors
    var renaming by remember { mutableStateOf<Server?>(null) }
    var removing by remember { mutableStateOf<Server?>(null) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = c.mantle,
        shape = HerdrRadius.sheet,
        dragHandle = { SheetHandle() },
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp).navigationBarsPadding().padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("Servers", style = HerdrType.headline, color = c.text, modifier = Modifier.padding(bottom = 8.dp))
            servers.forEach { s ->
                ServerRow(s, active = s.id == activeId, onClick = { onSwitch(s) },
                    onRename = { renaming = s }, onRemove = { removing = s })
            }
            PrimaryButton("+ Add server", onAdd, Modifier.fillMaxWidth().padding(top = 8.dp))
        }
    }
    renaming?.let { s ->
        ServerRenameDialog(s, onConfirm = { onRename(s, it); renaming = null }, onDismiss = { renaming = null })
    }
    removing?.let { s ->
        RemoveServerDialog(s, onConfirm = { onRemove(s); removing = null }, onDismiss = { removing = null })
    }
}

@Composable
private fun ServerRow(server: Server, active: Boolean, onClick: () -> Unit, onRename: () -> Unit, onRemove: () -> Unit) {
    val c = Herdr.colors
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier.fillMaxWidth().clip(HerdrRadius.tile).background(c.base)
            .border(1.dp, if (active) c.mauve.copy(alpha = 0.5f) else c.base, HerdrRadius.tile)
            .clickable(onClick = onClick).padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(server.name, style = HerdrType.title, color = c.text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(hostPort(server.url), style = HerdrType.meta, color = c.overlay2, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (active) Text("✓", style = HerdrType.title, color = c.mauve)
        Box {
            Text("⋯", style = HerdrType.title, color = c.subtext1,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).clickable { menu = true }.padding(12.dp))
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Remove", color = c.red) }, onClick = { menu = false; onRemove() })
            }
        }
    }
}

@Composable
private fun ServerRenameDialog(server: Server, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember(server.id) { mutableStateOf(server.name) }
    HerdrDialog(
        title = "Rename server",
        onDismiss = onDismiss,
        confirmLabel = "Save",
        confirmEnabled = text.trim() != server.name,
        onConfirm = { onConfirm(text) },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = text, onValueChange = { text = it }, singleLine = true, shape = HerdrRadius.field,
                placeholder = { Text(defaultServerName(server.url)) },
            )
            Text("Leave blank to use the address.", style = HerdrType.small, color = Herdr.colors.overlay2)
        }
    }
}

@Composable
private fun RemoveServerDialog(server: Server, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    HerdrDialog(
        title = "Remove ${server.name}?",
        onDismiss = onDismiss,
        confirmLabel = "Remove",
        confirmEnabled = true,
        onConfirm = onConfirm,
    ) {
        Text(
            "Its notifications stop and its address is forgotten. Agents on it keep running.",
            style = HerdrType.body, color = Herdr.colors.subtext1,
        )
    }
}
