package dev.herdr.mobile.ui

/** A free-typed "Other…" agent command split for herdr's agent.start. */
data class AgentCommand(val name: String, val argv: List<String>)

/**
 * Split a typed command into argv (on runs of whitespace) and derive a display
 * name from the basename of argv[0]. Simple whitespace split — no shell quoting
 * (YAGNI for the mobile "Other…" field).
 */
fun parseAgentCommand(input: String): AgentCommand {
    val argv = input.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    val name = argv.firstOrNull()?.substringAfterLast('/') ?: ""
    return AgentCommand(name, argv)
}
