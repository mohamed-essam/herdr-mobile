package dev.herdr.mobile.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import dev.herdr.mobile.data.joinAnswerLabels
import dev.herdr.mobile.net.ChatEvent
import dev.herdr.mobile.net.QuestionItem
import dev.herdr.mobile.net.QuestionKind
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
 * An AskUserQuestion call. [answer] is the tool_result text once the question
 * was answered (from either side): the card then collapses to it.
 */
@Composable
fun QuestionCard(
    q: ChatEvent.Question,
    enabled: Boolean,
    sending: Boolean,
    answered: Boolean,
    answer: String?,
    onSubmit: (Map<String, String>) -> Unit,
) {
    var inputs by remember(q.toolUseId) { mutableStateOf(List(q.questions.size) { QuestionInput() }) }
    fun update(i: Int, f: (QuestionInput) -> QuestionInput) { inputs = inputs.toMutableList().also { it[i] = f(it[i]) } }
    val active = enabled && !sending && !answered
    // A lone single-select question answers on tap, like the terminal dialog.
    val tapSubmits = q.questions.size == 1 && q.questions[0].kind == QuestionKind.Choice && !q.questions[0].multiSelect

    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(6.dp),
        modifier = Modifier.fillMaxWidth().alpha(if (answered) 0.6f else 1f),
    ) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for ((i, item) in q.questions.withIndex()) {
                HeaderChip(item.header)
                Text(item.question, style = MaterialTheme.typography.bodyMedium)
                if (answered) continue
                item.description?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                QuestionInputs(
                    item, inputs[i], active,
                    onPick = if (tapSubmits) ({ label -> onSubmit(mapOf(item.question to label)) }) else null,
                ) { next -> update(i) { next } }
            }
            when {
                answered -> Text(
                    answer?.takeIf { it.isNotBlank() } ?: "answered",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                sending -> Text("sending…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                // The tap-to-submit card still needs a button for a typed "Other…".
                !tapSubmits || inputs[0].other.isNotBlank() -> {
                    val answers = buildAnswers(q.questions, inputs)
                    Button(
                        onClick = { answers?.let(onSubmit) },
                        enabled = active && answers != null,
                        modifier = Modifier.align(Alignment.End),
                    ) { Text("Submit") }
                }
            }
        }
    }
}

@Composable
private fun HeaderChip(text: String) {
    if (text.isBlank()) return
    Surface(color = MaterialTheme.colorScheme.secondaryContainer, shape = RoundedCornerShape(4.dp)) {
        Text(
            text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
    }
}

@Composable
private fun QuestionInputs(
    item: QuestionItem,
    input: QuestionInput,
    enabled: Boolean,
    onPick: ((String) -> Unit)?,
    onChange: (QuestionInput) -> Unit,
) {
    when (item.kind) {
        QuestionKind.Text -> OutlinedTextField(
            value = input.text,
            onValueChange = { onChange(input.copy(text = it)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            placeholder = item.placeholder?.let { { Text(it) } },
            maxLines = 5,
        )
        QuestionKind.Number -> OutlinedTextField(
            value = input.number,
            onValueChange = { onChange(input.copy(number = it)) },
            enabled = enabled,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            placeholder = item.placeholder?.let { { Text(it) } },
            suffix = item.unit?.takeIf { it.isNotBlank() }?.let { { Text(it) } },
            supportingText = numberRange(item)?.let { { Text(it) } },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        )
        QuestionKind.Choice -> Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (opt in item.options) {
                val selected = opt.label in input.selected
                val toggle = {
                    onPick?.invoke(opt.label)
                    onChange(
                        input.copy(
                            selected = when {
                                !item.multiSelect -> setOf(opt.label)
                                selected -> input.selected - opt.label
                                else -> input.selected + opt.label
                            },
                        ),
                    )
                }
                Surface(
                    onClick = toggle,
                    enabled = enabled,
                    shape = RoundedCornerShape(6.dp),
                    color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        if (item.multiSelect) {
                            Checkbox(checked = selected, onCheckedChange = { toggle() }, enabled = enabled)
                            Spacer(Modifier.width(4.dp))
                        }
                        Column(Modifier.weight(1f)) {
                            Text(opt.label, style = MaterialTheme.typography.bodyMedium)
                            opt.description?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
            OutlinedTextField(
                value = input.other,
                onValueChange = { onChange(input.copy(other = it)) },
                enabled = enabled,
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("Other…") },
                maxLines = 3,
            )
        }
    }
}

private fun numberRange(item: QuestionItem): String? = when {
    item.min != null && item.max != null -> "${formatNumber(item.min)}–${formatNumber(item.max)}"
    item.min != null -> "≥ ${formatNumber(item.min)}"
    item.max != null -> "≤ ${formatNumber(item.max)}"
    else -> null
}
