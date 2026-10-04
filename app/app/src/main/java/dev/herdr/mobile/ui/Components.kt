package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.ui.theme.AvatarInk
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import dev.herdr.mobile.ui.theme.MonoFamily
import dev.herdr.mobile.ui.theme.badgeColor

/*
 * Shared building blocks of the v2 UI spec: workspace badges, section labels,
 * the wordmark, buttons and the bottom-sheet surface. Screens compose these
 * so the radii, type and colors stay consistent.
 */

/** A workspace's numbered badge; [number] 0 (unknown) shows a dot. */
@Composable
fun WorkspaceBadge(number: Int, modifier: Modifier = Modifier, size: Dp = 28.dp) {
    val dark = isSystemInDarkTheme()
    val small = size < 24.dp
    Box(
        modifier
            .size(size)
            .clip(RoundedCornerShape(if (small) 6.dp else 8.dp))
            .background(badgeColor(number, dark)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (number > 0) "$number" else "·",
            color = AvatarInk,
            style = if (small) HerdrType.badge.copy(fontSize = 11.sp) else HerdrType.badge,
        )
    }
}

/** A tracked mono section label, e.g. "● NEEDS YOU" or "/ WORKING · 4". */
@Composable
fun SectionLabel(text: String, color: Color, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = color, style = HerdrType.section, modifier = modifier)
}

/** "herdr ❯" in mono with the mauve chevron. */
@Composable
fun Wordmark(modifier: Modifier = Modifier, style: TextStyle = HerdrType.wordmark) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Text("herdr", style = style, color = Herdr.colors.text)
        Spacer(Modifier.width(8.dp))
        Text("❯", style = style, color = Herdr.colors.mauve)
    }
}

/** A small glowing status dot. */
@Composable
fun GlowDot(color: Color, modifier: Modifier = Modifier, size: Dp = 6.dp) {
    Box(
        modifier
            .size(size)
            .drawBehind { drawCircle(color.copy(alpha = 0.35f), radius = this.size.minDimension) }
            .clip(CircleShape)
            .background(color),
    )
}

/** Top-bar pill: a glowing dot and the pane count ("7 panes"). */
@Composable
fun CountPill(text: String, dotColor: Color, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(16.dp))
            .background(Herdr.colors.base)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlowDot(dotColor)
        Spacer(Modifier.width(8.dp))
        Text(text, style = HerdrType.meta, color = Herdr.colors.subtext1)
    }
}

/** The spec's primary action: 52dp, mauve, radius 16, with a top highlight. */
@Composable
fun PrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 52.dp,
    shape: Shape = HerdrRadius.card,
    container: Color = Herdr.colors.mauve,
    content: Color = Herdr.colors.crust,
) {
    val c = Herdr.colors
    Box(
        modifier
            .height(height)
            .then(if (enabled && height >= 48.dp) Modifier.shadow(12.dp, shape, ambientColor = container, spotColor = container) else Modifier)
            .clip(shape)
            .background(if (enabled) container else c.surface0)
            .drawBehind {
                if (enabled) drawLine(Color.White.copy(alpha = 0.35f), Offset(0f, 0.5f), Offset(size.width, 0.5f), 1.dp.toPx())
            }
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (height >= 48.dp) HerdrType.title else HerdrType.button,
            color = if (enabled) content else c.overlay0,
        )
    }
}

/** Secondary action on surface0 (Deny, Cancel, choice chips). */
@Composable
fun SecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 40.dp,
    shape: Shape = HerdrRadius.button,
    color: Color = Herdr.colors.text,
    container: Color = Herdr.colors.surface0,
) {
    Box(
        modifier
            .height(height)
            .clip(shape)
            .background(container)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text,
            style = if (height >= 48.dp) HerdrType.title else HerdrType.button,
            color = if (enabled) color else Herdr.colors.overlay0,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Mauve-tinted ghost action ("Other…", "›", "+ New"). */
@Composable
fun GhostButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    height: Dp = 36.dp,
) {
    val c = Herdr.colors
    SecondaryButton(
        text, onClick, modifier, enabled, height,
        color = c.mauve, container = c.mauve.copy(alpha = 0.08f),
    )
}

/** The spec's sheet surface: base, 24dp top radius, a grab handle. */
@Composable
fun SheetSurface(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(HerdrRadius.sheet)
            .background(Herdr.colors.base)
            .padding(horizontal = 16.dp),
    ) {
        SheetHandle()
        content()
    }
}

@Composable
fun SheetHandle(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(top = 8.dp, bottom = 16.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.size(width = 36.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(Herdr.colors.surface1))
    }
}

/** The light snackbar-style toast: "✓ Answer sent to keybar-v2   Open". */
@Composable
fun HerdrToast(
    text: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: () -> Unit = {},
) {
    val c = Herdr.colors
    Row(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .shadow(16.dp, HerdrRadius.card)
            .clip(HerdrRadius.card)
            .background(c.text)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("✓", color = Color(0xFF40A02B), style = HerdrType.title.copy(fontFamily = MonoFamily))
        Spacer(Modifier.width(12.dp))
        Text(text, color = c.crust, style = HerdrType.body, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (actionLabel != null) {
            Text(
                actionLabel,
                color = Color(0xFF8839EF),
                style = HerdrType.title,
                modifier = Modifier.clickable(onClick = onAction).padding(start = 12.dp, top = 8.dp, bottom = 8.dp),
            )
        }
    }
}
