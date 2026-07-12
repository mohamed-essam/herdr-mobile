package dev.herdr.mobile.ui

/** One-line descriptions for well-known agent slugs; unknown slugs return null. */
val agentDescriptions: Map<String, String> = mapOf(
    "claude" to "Anthropic Claude Code",
    "codex" to "OpenAI Codex CLI",
    "gemini" to "Google Gemini CLI",
    "cursor" to "Cursor agent",
    "copilot" to "GitHub Copilot CLI",
    "cline" to "Cline coding agent",
    "opencode" to "OpenCode agent",
    "amp" to "Sourcegraph Amp",
    "grok" to "xAI Grok CLI",
    "droid" to "Factory Droid",
    "kimi" to "Moonshot Kimi CLI",
    "devin" to "Cognition Devin",
)

fun describeAgent(name: String): String? = agentDescriptions[name.lowercase()]

/** Case-insensitive substring filter; blank query returns the list unchanged. */
fun filterAgents(all: List<String>, query: String): List<String> {
    val q = query.trim()
    return if (q.isEmpty()) all else all.filter { it.contains(q, ignoreCase = true) }
}
