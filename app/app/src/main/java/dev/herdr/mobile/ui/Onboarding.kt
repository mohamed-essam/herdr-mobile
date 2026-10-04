package dev.herdr.mobile.ui

/*
 * Pure logic behind the connect and first-connection screens: turning what
 * the user typed into a companion URL, the setup steps' states, and the herd
 * summary shown once the first connection lands.
 */

const val DEFAULT_COMPANION_PORT = 8787

/** The command the connect screen tells the user to run on their host. */
const val COMPANION_COMMAND = "herdr-mobiled --listen \"\$(tailscale ip -4):$DEFAULT_COMPANION_PORT\""

/**
 * A parsed companion address. [hasScheme]/[hasPort] say whether the user typed
 * them, so the field only shows the dimmed `ws://` and `:8787` it will add.
 */
data class CompanionAddress(val url: String, val hostPort: String, val hasScheme: Boolean, val hasPort: Boolean)

/**
 * Leniently reads the address field. A bare host gets `ws://` and the default
 * port; `host:port` keeps its port; a full `ws://`/`wss://` URL is taken as is.
 * Returns null for blank input, whitespace inside, or another scheme.
 */
fun parseCompanionAddress(input: String): CompanionAddress? {
    val t = input.trim()
    if (t.isEmpty() || t.any { it.isWhitespace() }) return null
    if ("://" in t) {
        val scheme = t.substringBefore("://").lowercase()
        if (scheme != "ws" && scheme != "wss") return null
        val authority = t.substringAfter("://").substringBefore('/')
        if (authority.isEmpty() || authority.startsWith(':')) return null
        return CompanionAddress(t, authority, hasScheme = true, hasPort = authorityHasPort(authority))
    }
    var authority = t.substringBefore('/')
    val path = t.substring(authority.length)
    if (authority.count { it == ':' } == 1 && authority.endsWith(':')) authority = authority.dropLast(1)
    if (authority.isEmpty() || authority.startsWith(':')) return null
    val hasPort = authorityHasPort(authority)
    val hostPort = when {
        hasPort -> authority
        // A bare IPv6 address needs brackets before a port can follow it.
        authority.count { it == ':' } >= 2 && !authority.startsWith('[') -> "[$authority]:$DEFAULT_COMPANION_PORT"
        else -> "$authority:$DEFAULT_COMPANION_PORT"
    }
    return CompanionAddress("ws://$hostPort$path", hostPort, hasScheme = false, hasPort = hasPort)
}

private fun authorityHasPort(authority: String): Boolean {
    val bracketed = authority.startsWith('[')
    if (!bracketed && authority.count { it == ':' } != 1) return false
    val port = (if (bracketed) authority.substringAfter(']', "") else authority).substringAfter(':', "")
    return port.isNotEmpty() && port.all { it.isDigit() }
}

enum class StepState { Done, Current, Upcoming }

/** Step 1 is an instruction (always done); 2 is current until a host is entered, then 3. */
fun connectStepStates(hostEntered: Boolean): List<StepState> = listOf(
    StepState.Done,
    if (hostEntered) StepState.Done else StepState.Current,
    if (hostEntered) StepState.Current else StepState.Upcoming,
)

enum class FirstConnectPhase { Connecting, Slow, Connected }

/**
 * Where the first-connection screen is: Connected once the socket has ever
 * opened (a later blip doesn't take the success away), Slow (offer to go back
 * and fix the address) after [slowAfterMs] without it.
 */
fun firstConnectPhase(everConnected: Boolean, elapsedMs: Long, slowAfterMs: Long = 10_000): FirstConnectPhase = when {
    everConnected -> FirstConnectPhase.Connected
    elapsedMs >= slowAfterMs -> FirstConnectPhase.Slow
    else -> FirstConnectPhase.Connecting
}

/** The herd at a glance: sizes, and how many agents need you / work / are done. */
data class HerdSummary(
    val repos: Int,
    val workspaces: Int,
    val panes: Int,
    val needYou: Int,
    val working: Int,
    val done: Int,
)

/** Counts over [repos]; the synthetic "(unknown)" workspace (blank id) isn't a workspace. */
fun herdSummary(repos: List<RepoNode>): HerdSummary {
    val panes = repos.flatMap { r -> r.workspaces.flatMap { w -> w.tabs.flatMap { it.panes } } }
    return HerdSummary(
        repos = repos.count { r -> r.workspaces.any { it.ws.workspaceId.isNotBlank() } },
        workspaces = repos.sumOf { r -> r.workspaces.count { it.ws.workspaceId.isNotBlank() } },
        panes = panes.size,
        needYou = panes.count { it.agentStatus == "blocked" },
        working = panes.count { it.agentStatus == "working" },
        done = panes.count { it.agentStatus == "done" },
    )
}

/** "3 repos · 5 workspaces · 7 panes", singular where it reads so. */
fun herdLine(s: HerdSummary): String = listOf(
    plural(s.repos, "repo"), plural(s.workspaces, "workspace"), plural(s.panes, "pane"),
).joinToString(" · ")

private fun plural(n: Int, word: String) = if (n == 1) "1 $word" else "$n ${word}s"
