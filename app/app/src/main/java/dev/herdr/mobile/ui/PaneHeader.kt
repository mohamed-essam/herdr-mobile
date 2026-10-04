package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType

/**
 * The timeline header shared by chat and terminal: back, a chat/terminal
 * toggle on the right, then the workspace badge + large title and a mono
 * breadcrumb that ends in the live status ("… › claude · working").
 */
@Composable
fun PaneHeader(
    ctx: PaneContext,
    breadcrumb: String,
    status: String,
    statusColor: Color,
    onBack: () -> Unit,
    toggleLabel: String?,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val c = Herdr.colors
    Column(modifier.fillMaxWidth().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(40.dp).clip(HerdrRadius.button).clickable(onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { Text("←", color = c.text, style = HerdrType.title.copy(fontSize = 17.sp)) }
            Spacer(Modifier.weight(1f))
            actions()
            if (toggleLabel != null) {
                Spacer(Modifier.width(8.dp))
                Box(
                    Modifier
                        .height(36.dp)
                        .clip(HerdrRadius.field)
                        .background(c.base)
                        .clickable(onClick = onToggle)
                        .padding(horizontal = 12.dp),
                    contentAlignment = Alignment.Center,
                ) { Text(toggleLabel, style = HerdrType.meta, color = c.text) }
            }
        }
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                WorkspaceBadge(ctx.workspaceNumber, size = 20.dp)
                Spacer(Modifier.width(8.dp))
                Text(
                    ctx.workspaceLabel,
                    style = HerdrType.display,
                    color = c.text,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.height(4.dp))
            Text(
                buildAnnotatedString {
                    if (breadcrumb.isNotBlank()) append("$breadcrumb · ")
                    withStyle(SpanStyle(color = statusColor)) { append(status) }
                },
                style = HerdrType.meta,
                color = c.overlay2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        HorizontalDivider(color = c.base, thickness = 1.dp)
    }
}
