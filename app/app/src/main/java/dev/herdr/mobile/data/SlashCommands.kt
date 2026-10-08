package dev.herdr.mobile.data

import dev.herdr.mobile.net.SlashCommand
import java.util.concurrent.ConcurrentHashMap

/**
 * The picker's rows for [draft]: null while it isn't a command name being
 * typed (`/` then no whitespace yet) or there are no [commands]. Names
 * starting with what's typed come first, then those containing it, each in
 * the list's (typeahead) order; case is ignored.
 */
fun slashMatches(draft: String, commands: List<SlashCommand>): List<SlashCommand>? {
    if (commands.isEmpty() || !draft.startsWith("/") || draft.any { it.isWhitespace() }) return null
    val q = draft.substring(1)
    val (prefix, rest) = commands.partition { it.name.startsWith(q, ignoreCase = true) }
    return prefix + rest.filter { it.name.contains(q, ignoreCase = true) }
}

/**
 * Unsent composer text per pane, kept while the app runs: leaving the pane,
 * the terminal toggle or a thread doesn't lose it. An empty draft is dropped.
 */
class ChatDrafts {
    private val drafts = ConcurrentHashMap<String, String>()

    operator fun get(paneId: String): String = drafts[paneId] ?: ""

    operator fun set(paneId: String, text: String) {
        if (text.isEmpty()) drafts.remove(paneId) else drafts[paneId] = text
    }
}
