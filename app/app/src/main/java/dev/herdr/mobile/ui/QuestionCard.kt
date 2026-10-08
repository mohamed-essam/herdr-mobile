package dev.herdr.mobile.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.herdr.mobile.data.joinAnswerLabels
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.net.QuestionKind
import dev.herdr.mobile.ui.theme.Herdr
import dev.herdr.mobile.ui.theme.HerdrRadius
import dev.herdr.mobile.ui.theme.HerdrType
import java.math.BigDecimal

/** What the user has entered for one question of a card. */
data class QuestionInput(
    val selected: Set<String> = emptySet(),
    val other: String = "",
    val text: String = "",
    val number: String = "",
)

fun clampNumber(v: Double, min: Double?, max: Double?): Double {
    var x = v
    if (min != null && x < min) x = min
    if (max != null && x > max) x = max
    return x
}

/** `10`, not `10.0`; `2.5`, not `2.50`. */
fun formatNumber(v: Double): String = BigDecimal.valueOf(v).stripTrailingZeros().toPlainString()

/** One question's answer, or null while it has none. */
fun answerFor(q: QuestionItem, input: QuestionInput): String? = when (q.kind) {
    QuestionKind.Text -> input.text.trim().ifEmpty { null }
    QuestionKind.Number -> input.number.trim().toDoubleOrNull()?.takeIf { it.isFinite() }
        ?.let { formatNumber(clampNumber(it, q.min, q.max)) }
    QuestionKind.Choice -> {
        val other = input.other.trim()
        // Labels in option order, so the answer doesn't depend on tap order.
        val labels = q.options.map { it.label }.filter { it in input.selected }
        when {
            q.multiSelect -> joinAnswerLabels(if (other.isEmpty()) labels else labels + other).ifEmpty { null }
            other.isNotEmpty() -> other
            else -> labels.firstOrNull()
        }
    }
}

/**
 * The answers map for chat_answer (question text -> answer), or null while any
 * question is still unanswered.
 */
fun buildAnswers(questions: List<QuestionItem>, inputs: List<QuestionInput>): Map<String, String>? {
    if (questions.isEmpty() || inputs.size < questions.size) return null
    val out = LinkedHashMap<String, String>()
    for ((i, q) in questions.withIndex()) out[q.question] = answerFor(q, inputs[i]) ?: return null
    return out
}

/**
 * A pending AskUserQuestion, pinned where the composer was: "● CLAUDE ASKS",
 * each question with large tap rows (or a text/number field), and "Send
 * answer" in thumb reach. Long or multi-question asks scroll inside the sheet.
 */
@Composable
fun QuestionSheet(
    q: ChatEvent.Question,
    agent: String,
    enabled: Boolean,
    sending: Boolean,
    // One per question, held by the caller so minimizing keeps the picks.
    inputs: List<QuestionInput>,
    onInputs: (List<QuestionInput>) -> Unit,
    onMinimize: () -> Unit,
    modifier: Modifier = Modifier,
    onSubmit: (Map<String, String>) -> Unit,
) {
    val c = Herdr.colors
    fun update(i: Int, f: (QuestionInput) -> QuestionInput) { onInputs(inputs.toMutableList().also { it[i] = f(it[i]) }) }
    val active = enabled && !sending
    val answers = buildAnswers(q.questions, inputs)

    Column(
        modifier
            .fillMaxWidth()
            .clip(HerdrRadius.sheet)
            .background(c.base)
            // The sheet's red top hairline: the ask is what's blocking the agent.
            .drawBehind { drawLine(c.red.copy(alpha = 0.25f), Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx()) }
            .navigationBarsPadding()
            .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
    ) {
        // The handle (or the chevron) folds the sheet to a bar, to read the chat.
        Box(Modifier.fillMaxWidth().clickable(onClick = onMinimize).padding(top = 4.dp, bottom = 4.dp)) {
            Box(Modifier.align(Alignment.Center).size(width = 32.dp, height = 4.dp).clip(RoundedCornerShape(2.dp)).background(c.surface1))
            Text(
                "▾ hide", style = HerdrType.button, color = c.overlay2,
                modifier = Modifier.align(Alignment.CenterEnd).padding(vertical = 8.dp),
            )
        }
        Column(
            Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            for ((i, item) in q.questions.withIndex()) {
                if (i > 0) Spacer(Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (i == 0) {
                        SectionLabel("● $agent asks", c.red)
                        Spacer(Modifier.width(8.dp))
                    }
                    HeaderChip(item.header)
                }
                Text(item.question, style = HerdrType.title.copy(fontSize = 17.sp, lineHeight = 23.sp), color = c.text)
                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = HerdrType.small, color = c.overlay2)
                }
                QuestionInputs(item, inputs[i], active) { next -> update(i) { next } }
            }
        }
        Spacer(Modifier.height(12.dp))
        PrimaryButton(
            if (sending) "Sending…" else "Send answer",
            onClick = { answers?.let(onSubmit) },
            enabled = active && answers != null,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/**
 * A question as timeline history: the header, the question and, once
 * answered, the answer. While it is [pending] the sheet below takes input.
 */
@Composable
fun QuestionHistory(q: ChatEvent.Question, pending: Boolean, sending: Boolean, answered: Boolean, answer: String?) {
    val c = Herdr.colors
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (item in q.questions) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("? ", style = HerdrType.meta, color = c.red)
                HeaderChip(item.header)
            }
            Text(item.question, style = HerdrType.body, color = c.text)
        }
        val (label, color) = when {
            answered -> "→ " + (answer?.takeIf { it.isNotBlank() } ?: "answered") to c.subtext1
            sending -> "sending answer…" to c.overlay2
            pending -> "waiting on your answer ↓" to c.red
            else -> "not answered" to c.overlay0
        }
        Text(
            label, style = HerdrType.code, color = color,
            maxLines = 6, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.drawBehind {
                drawLine(c.surface1, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
            }.padding(start = 12.dp),
        )
    }
}

@Composable
private fun HeaderChip(text: String) {
    if (text.isBlank()) return
    val c = Herdr.colors
    Text(
        text,
        style = HerdrType.meta,
        color = c.blue,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.clip(RoundedCornerShape(6.dp)).background(c.blue.copy(alpha = 0.14f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

@Composable
private fun QuestionInputs(
    item: QuestionItem,
    input: QuestionInput,
    enabled: Boolean,
    onChange: (QuestionInput) -> Unit,
) {
    when (item.kind) {
        QuestionKind.Text -> SheetField(
            input.text, { onChange(input.copy(text = it)) }, enabled,
            placeholder = item.placeholder ?: "Your answer…", maxLines = 5,
        )
        QuestionKind.Number -> Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SheetField(
                input.number, { onChange(input.copy(number = it)) }, enabled,
                placeholder = item.placeholder ?: "0", maxLines = 1,
                suffix = item.unit?.takeIf { it.isNotBlank() },
                // Decimal pads often lack a minus sign; take any text when negatives
                // are allowed (answerFor rejects what doesn't parse).
                keyboard = if (item.min == null || item.min < 0) KeyboardType.Text else KeyboardType.Decimal,
            )
            numberRange(item)?.let { Text(it, style = HerdrType.meta, color = Herdr.colors.overlay2, modifier = Modifier.padding(start = 16.dp)) }
        }
        QuestionKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (opt in item.options) {
                val selected = opt.label in input.selected
                ChoiceRow(opt.label, opt.description, selected, item.multiSelect, enabled) {
                    // Single-select: an option and "Other…" text replace each other.
                    onChange(
                        when {
                            !item.multiSelect -> input.copy(selected = setOf(opt.label), other = "")
                            selected -> input.copy(selected = input.selected - opt.label)
                            else -> input.copy(selected = input.selected + opt.label)
                        },
                    )
                }
            }
            SheetField(
                input.other,
                {
                    onChange(
                        if (item.multiSelect || it.isBlank()) input.copy(other = it)
                        else input.copy(other = it, selected = emptySet()),
                    )
                },
                enabled,
                placeholder = "Other…", maxLines = 3, minHeight = 44.dp,
            )
        }
    }
}

@Composable
private fun ChoiceRow(
    label: String,
    description: String?,
    selected: Boolean,
    multi: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val c = Herdr.colors
    val shape = HerdrRadius.tile
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .background(if (selected) c.mauve.copy(alpha = 0.12f) else c.mantle)
            .border(if (selected) 1.5.dp else 1.dp, if (selected) c.mauve else c.surface0, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val mark = if (multi) RoundedCornerShape(5.dp) else CircleShape
        Box(
            Modifier.size(18.dp).clip(mark)
                .then(
                    when {
                        selected && multi -> Modifier.background(c.mauve)
                        selected -> Modifier.border(5.dp, c.mauve, mark)
                        else -> Modifier.border(1.5.dp, c.surface2, mark)
                    },
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected && multi) Text("✓", style = HerdrType.meta.copy(fontSize = 12.sp), color = c.crust)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label, style = HerdrType.title, color = if (enabled) c.text else c.overlay0)
            description?.takeIf { it.isNotBlank() }?.let { Text(it, style = HerdrType.small, color = c.overlay2) }
        }
    }
}

/** The sheet's text input: mantle with a surface0 hairline, radius 14. */
@Composable
private fun SheetField(
    value: String,
    onValueChange: (String) -> Unit,
    enabled: Boolean,
    placeholder: String,
    maxLines: Int,
    minHeight: androidx.compose.ui.unit.Dp = 48.dp,
    suffix: String? = null,
    keyboard: KeyboardType = KeyboardType.Text,
) {
    val c = Herdr.colors
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        enabled = enabled,
        singleLine = maxLines == 1,
        maxLines = maxLines,
        textStyle = HerdrType.body.copy(color = c.text),
        cursorBrush = SolidColor(c.mauve),
        keyboardOptions = KeyboardOptions(keyboardType = keyboard),
        modifier = Modifier.fillMaxWidth(),
        decorationBox = { inner ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = minHeight)
                    .clip(HerdrRadius.tile)
                    .background(c.mantle)
                    .border(1.dp, c.surface0, HerdrRadius.tile)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.weight(1f)) {
                    if (value.isEmpty()) Text(placeholder, style = HerdrType.body, color = c.overlay0)
                    inner()
                }
                if (suffix != null) Text(suffix, style = HerdrType.meta, color = c.overlay2, modifier = Modifier.padding(start = 8.dp))
            }
        },
    )
}

private fun numberRange(item: QuestionItem): String? = when {
    item.min != null && item.max != null -> "${formatNumber(item.min)}–${formatNumber(item.max)}"
    item.min != null -> "≥ ${formatNumber(item.min)}"
    item.max != null -> "≤ ${formatNumber(item.max)}"
    else -> null
}
