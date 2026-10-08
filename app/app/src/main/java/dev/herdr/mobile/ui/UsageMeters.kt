package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.net.LimitWindow
import dev.herdr.mobile.net.Limits
import dev.herdr.mobile.net.PaneUsage
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrType
import kotlinx.coroutines.delay
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

/** How full a meter reads: below 60 normal, 60–84 warn, 85+ critical. */
enum class UsageTone { Normal, Warn, Critical }

fun usageTone(pct: Double): UsageTone = when {
    pct >= 85 -> UsageTone.Critical
    pct >= 60 -> UsageTone.Warn
    else -> UsageTone.Normal
}

@Composable
private fun toneColor(t: UsageTone): Color = when (t) {
    UsageTone.Normal -> Herdr.colors.overlay2
    UsageTone.Warn -> Herdr.colors.yellow
    UsageTone.Critical -> Herdr.colors.red
}

private fun resetMs(resetsAt: String?): Long? =
    resetsAt?.let { runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

/** True once the window's reset time has passed (its percentage is stale). */
fun windowExpired(resetsAt: String?, now: Long): Boolean = resetMs(resetsAt)?.let { it <= now } ?: false

/** "↻ 2h13m" for the 5-hour window, "↻ Thu 14:00" for the 7-day one; "" when unknown or past. */
fun formatReset(kind: String, resetsAt: String?, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val at = resetMs(resetsAt) ?: return ""
    if (at <= now) return ""
    if (kind == "five_hour") {
        val mins = (at - now + 59_999) / 60_000
        val h = mins / 60
        return if (h > 0) "↻ ${h}h${mins % 60}m" else "↻ ${mins}m"
    }
    return "↻ " + Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH))
}

/** The reading is older than five minutes: no live mod is reporting. */
fun limitsStale(observedAt: Long, now: Long): Boolean = now - observedAt > 5 * 60_000

fun limitLabel(kind: String): String = when (kind) {
    "five_hour" -> "5h"
    "seven_day" -> "7d"
    else -> kind
}

/** The 5-hour then the 7-day window; others are dropped. */
fun limitWindows(limits: Limits?): List<LimitWindow> =
    listOf("five_hour", "seven_day").mapNotNull { k -> limits?.windows?.firstOrNull { it.kind == k } }

/** "5h 42% ↻ 2h13m"; "5h —" once the window has reset. */
fun limitText(w: LimitWindow, now: Long, zone: ZoneId = ZoneId.systemDefault()): String {
    val label = limitLabel(w.kind)
    if (windowExpired(w.resetsAt, now)) return "$label —"
    val reset = formatReset(w.kind, w.resetsAt, now, zone)
    return listOf("$label ${w.percentUsed.roundToInt()}%", reset).filter { it.isNotEmpty() }.joinToString(" ")
}

/** A ticking clock for countdowns, every [intervalMs]. */
@Composable
fun rememberNow(intervalMs: Long = 30_000): Long {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            delay(intervalMs)
            value = System.currentTimeMillis()
        }
    }
    return now
}

/** A thin fill bar; [fraction] 0..1. */
@Composable
private fun Meter(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.height(2.dp).clip(RoundedCornerShape(1.dp)).background(Herdr.colors.surface0)) {
        Box(Modifier.fillMaxHeight().fillMaxWidth(fraction.coerceIn(0f, 1f)).background(color))
    }
}

/** The context window's fill under a dashboard row. */
@Composable
fun ContextBar(percent: Int, modifier: Modifier = Modifier) {
    Meter(percent / 100f, toneColor(usageTone(percent.toDouble())), modifier.fillMaxWidth())
}

/** The dashboard strip: one line per window, dimmed with its age when stale. */
@Composable
fun LimitsStrip(limits: Limits, now: Long, modifier: Modifier = Modifier) {
    val windows = limitWindows(limits)
    if (windows.isEmpty()) return
    val stale = limitsStale(limits.observedAt, now)
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().alpha(if (stale) 0.5f else 1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (w in windows) {
            val expired = windowExpired(w.resetsAt, now)
            val color = if (expired) Herdr.colors.overlay0 else toneColor(usageTone(w.percentUsed))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(limitLabel(w.kind), style = HerdrType.meta, color = Herdr.colors.overlay2, modifier = Modifier.width(24.dp))
                Meter(if (expired) 0f else (w.percentUsed / 100).toFloat(), color, Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Text(limitText(w, now).removePrefix(limitLabel(w.kind)).trim(), style = HerdrType.meta, color = color)
            }
        }
        if (stale) Text("as of ${relativeAge(limits.observedAt, now)} ago", style = HerdrType.meta, color = Herdr.colors.overlay0)
    }
}

/** The pane header's line: "ctx ▓ 61% · 5h 42% ↻ 2h13m · 7d 18% ↻ Thu 14:00". */
@Composable
fun UsageLine(context: PaneUsage?, limits: Limits?, now: Long) {
    val windows = limitWindows(limits)
    if (context == null && windows.isEmpty()) return
    val stale = limits != null && limitsStale(limits.observedAt, now)
    Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        if (context != null) {
            val color = toneColor(usageTone(context.percent.toDouble()))
            Text("ctx", style = HerdrType.meta, color = Herdr.colors.overlay2)
            Meter(context.percent / 100f, color, Modifier.width(32.dp))
            Text("${context.percent}%", style = HerdrType.meta, color = color)
        }
        if (windows.isNotEmpty()) {
            val text = windows.joinToString(" · ") { limitText(it, now) } + if (stale) " · as of ${relativeAge(limits!!.observedAt, now)} ago" else ""
            Text(
                (if (context != null) "· " else "") + text,
                style = HerdrType.meta,
                color = Herdr.colors.overlay2,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.alpha(if (stale) 0.5f else 1f),
            )
        }
    }
}
