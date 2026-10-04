package dev.herdr.mobile.ui

sealed interface TextSegment {
    data class Prose(val text: String) : TextSegment
    data class Code(val text: String) : TextSegment
}

/** Splits assistant text on ``` fences; the only formatting the chat view draws. */
fun splitFences(text: String): List<TextSegment> {
    val out = mutableListOf<TextSegment>()
    val buf = StringBuilder()
    var inCode = false
    fun flush() {
        val s = buf.toString().trim('\n')
        if (s.isNotBlank()) out += if (inCode) TextSegment.Code(s) else TextSegment.Prose(s)
        buf.clear()
    }
    for (line in text.lines()) {
        if (line.trimStart().startsWith("```")) {
            flush()
            inCode = !inCode
            continue
        }
        buf.append(line).append('\n')
    }
    flush()
    return out
}
