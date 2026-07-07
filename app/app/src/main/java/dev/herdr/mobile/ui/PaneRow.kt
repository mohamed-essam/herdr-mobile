package dev.herdr.mobile.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.Pane

@Composable
fun PaneRow(pane: Pane, onClick: (Pane) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onClick(pane) },
        headlineContent = { Text(pane.workspaceId + "  " + pane.cwd.substringAfterLast('/')) },
        supportingContent = { Text(pane.agent ?: "—") },
        trailingContent = { StatusChip(pane.agentStatus) },
    )
}

@Composable
private fun StatusChip(status: String?) {
    val (label, color) = when (status) {
        "blocked" -> "blocked" to Color(0xFFD32F2F)
        "working" -> "working" to Color(0xFFF9A825)
        "idle" -> "idle" to Color(0xFF616161)
        "done" -> "done" to Color(0xFF388E3C)
        else -> (status ?: "—") to Color(0xFF9E9E9E)
    }
    Surface(color = color, shape = RoundedCornerShape(12.dp)) {
        Text(label, color = Color.White, modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp))
    }
}
