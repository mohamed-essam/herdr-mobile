package dev.herdr.mobile.net

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.*

internal val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

@Serializable
data class Pane(
    val paneId: String,
    val workspaceId: String = "",
    val tabId: String = "",
    val terminalId: String = "",
    val cwd: String = "",
    val focused: Boolean = false,
    val agent: String? = null,
    val agentStatus: String? = null,
)

@Serializable
data class Worktree(
    val repoName: String? = null,
    val isLinkedWorktree: Boolean = false,
)

@Serializable
data class Workspace(
    val workspaceId: String,
    val label: String = "",
    val number: Int = 0,
    val agentStatus: String? = null,
    val focused: Boolean = false,
    val paneCount: Int = 0,
    val tabCount: Int = 0,
    val lastActivity: Long = 0,
    val worktree: Worktree? = null,
)

@Serializable
data class Tab(
    val tabId: String,
    val label: String = "",
    val number: Int = 0,
    val workspaceId: String = "",
    val agentStatus: String? = null,
    val focused: Boolean = false,
    val paneCount: Int = 0,
)

@Serializable
data class AlsoClose(
    val workspaceId: String,
    val label: String = "",
)

sealed interface ServerFrame {
    data object Welcome : ServerFrame
    data class Panes(val panes: List<Pane>) : ServerFrame
    data class Workspaces(val workspaces: List<Workspace>) : ServerFrame
    data class Tabs(val tabs: List<Tab>) : ServerFrame
    data class PaneUpdate(val pane: Pane) : ServerFrame
    data class PaneRemoved(val paneId: String) : ServerFrame
    data class PaneRead(val reqId: String, val paneId: String, val source: String, val text: String) : ServerFrame
    data class Ack(val reqId: String) : ServerFrame
    data class ErrorFrame(val reqId: String, val code: String, val message: String) : ServerFrame
    data class ActionResult(val reqId: String, val ok: Boolean, val error: String?) : ServerFrame
    data class Created(val reqId: String, val ok: Boolean, val paneId: String?, val terminalId: String?, val error: String?) : ServerFrame
    data class Agents(val reqId: String, val agents: List<String>) : ServerFrame
    data class CloseImpact(val reqId: String, val workspaceId: String, val alsoCloses: List<AlsoClose>) : ServerFrame
    data object Pong : ServerFrame
    data object Unknown : ServerFrame
    data class TermOpened(val reqId: String, val termId: String) : ServerFrame
    data class TermData(val termId: String, val data: String) : ServerFrame
    data class TermExit(val termId: String, val code: Int, val reason: String = "") : ServerFrame
    data class TermError(val reqId: String, val termId: String, val message: String) : ServerFrame
}

fun parseServerFrame(text: String): ServerFrame {
    val obj = json.parseToJsonElement(text).jsonObject
    return when (obj["t"]?.jsonPrimitive?.content) {
        "welcome" -> ServerFrame.Welcome
        "panes" -> ServerFrame.Panes(json.decodeFromJsonElement(obj["panes"]!!))
        "workspaces" -> ServerFrame.Workspaces(json.decodeFromJsonElement(obj["workspaces"]!!))
        "tabs" -> ServerFrame.Tabs(json.decodeFromJsonElement(obj["tabs"]!!))
        "pane_update" -> ServerFrame.PaneUpdate(json.decodeFromJsonElement(obj["pane"]!!))
        "pane_removed" -> ServerFrame.PaneRemoved(obj["paneId"]!!.jsonPrimitive.content)
        "pane_read" -> ServerFrame.PaneRead(
            obj["reqId"]!!.jsonPrimitive.content, obj["paneId"]!!.jsonPrimitive.content,
            obj["source"]!!.jsonPrimitive.content, obj["text"]!!.jsonPrimitive.content)
        "ack" -> ServerFrame.Ack(obj["reqId"]!!.jsonPrimitive.content)
        "error" -> ServerFrame.ErrorFrame(
            obj["reqId"]?.jsonPrimitive?.content ?: "", obj["code"]!!.jsonPrimitive.content,
            obj["message"]!!.jsonPrimitive.content)
        "action_result" -> ServerFrame.ActionResult(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["error"]?.jsonPrimitive?.content)
        "created" -> ServerFrame.Created(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["paneId"]?.jsonPrimitive?.content,
            obj["terminalId"]?.jsonPrimitive?.content,
            obj["error"]?.jsonPrimitive?.content)
        "agents" -> ServerFrame.Agents(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            (obj["agents"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList())
        "close_impact" -> ServerFrame.CloseImpact(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["workspaceId"]?.jsonPrimitive?.content ?: "",
            (obj["alsoCloses"] as? JsonArray)?.map { json.decodeFromJsonElement<AlsoClose>(it) } ?: emptyList())
        "pong" -> ServerFrame.Pong
        "term_opened" -> ServerFrame.TermOpened(
            obj["reqId"]!!.jsonPrimitive.content, obj["termId"]!!.jsonPrimitive.content)
        "term_data" -> ServerFrame.TermData(
            obj["termId"]!!.jsonPrimitive.content, obj["data"]!!.jsonPrimitive.content)
        "term_exit" -> ServerFrame.TermExit(
            obj["termId"]!!.jsonPrimitive.content, obj["code"]?.jsonPrimitive?.int ?: 0,
            obj["reason"]?.jsonPrimitive?.content ?: "")
        "term_error" -> ServerFrame.TermError(
            obj["reqId"]?.jsonPrimitive?.content ?: "", obj["termId"]?.jsonPrimitive?.content ?: "",
            obj["message"]?.jsonPrimitive?.content ?: "")
        else -> ServerFrame.Unknown
    }
}

object ClientMsg {
    private fun obj(vararg pairs: Pair<String, JsonElement>) =
        JsonObject(pairs.toMap()).toString()

    fun hello() = obj("t" to JsonPrimitive("hello"), "client" to JsonPrimitive("herdr-mobile"), "clientVersion" to JsonPrimitive("1.0.0"))
    fun registerPush(endpoint: String) = obj("t" to JsonPrimitive("register_push"), "endpoint" to JsonPrimitive(endpoint))
    fun readPane(reqId: String, paneId: String, source: String, lines: Int) =
        obj("t" to JsonPrimitive("read_pane"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "source" to JsonPrimitive(source), "lines" to JsonPrimitive(lines))
    fun sendText(reqId: String, paneId: String, text: String) =
        obj("t" to JsonPrimitive("send_text"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "text" to JsonPrimitive(text))
    fun sendKeys(reqId: String, paneId: String, keys: String) =
        obj("t" to JsonPrimitive("send_keys"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "keys" to JsonPrimitive(keys))
    fun action(reqId: String, op: String, kind: String, id: String, label: String?): String {
        val pairs = mutableListOf(
            "t" to JsonPrimitive("action"),
            "reqId" to JsonPrimitive(reqId),
            "op" to JsonPrimitive(op),
            "kind" to JsonPrimitive(kind),
            "id" to JsonPrimitive(id),
        )
        if (label != null) pairs.add("label" to JsonPrimitive(label))
        return JsonObject(pairs.toMap()).toString()
    }
    fun create(
        reqId: String, what: String, workspaceId: String?, tabId: String?, paneId: String?,
        direction: String?, agentName: String?, argv: List<String>?,
    ): String {
        val pairs = mutableListOf<Pair<String, JsonElement>>(
            "t" to JsonPrimitive("create"),
            "reqId" to JsonPrimitive(reqId),
            "what" to JsonPrimitive(what),
        )
        if (workspaceId != null) pairs.add("workspaceId" to JsonPrimitive(workspaceId))
        if (tabId != null) pairs.add("tabId" to JsonPrimitive(tabId))
        if (paneId != null) pairs.add("paneId" to JsonPrimitive(paneId))
        if (direction != null) pairs.add("direction" to JsonPrimitive(direction))
        if (agentName != null) pairs.add("agentName" to JsonPrimitive(agentName))
        if (argv != null) pairs.add("argv" to JsonArray(argv.map { JsonPrimitive(it) }))
        return JsonObject(pairs.toMap()).toString()
    }

    fun move(reqId: String, paneId: String, dest: String, tabId: String?, direction: String?): String {
        val pairs = mutableListOf(
            "t" to JsonPrimitive("move"),
            "reqId" to JsonPrimitive(reqId),
            "paneId" to JsonPrimitive(paneId),
            "dest" to JsonPrimitive(dest),
        )
        if (tabId != null) pairs.add("tabId" to JsonPrimitive(tabId))
        if (direction != null) pairs.add("direction" to JsonPrimitive(direction))
        return JsonObject(pairs.toMap()).toString()
    }

    fun listAgents(reqId: String): String =
        JsonObject(mapOf("t" to JsonPrimitive("list_agents"), "reqId" to JsonPrimitive(reqId))).toString()

    fun closeImpact(reqId: String, workspaceId: String): String =
        JsonObject(mapOf("t" to JsonPrimitive("close_impact"), "reqId" to JsonPrimitive(reqId), "workspaceId" to JsonPrimitive(workspaceId))).toString()

    fun ping() = obj("t" to JsonPrimitive("ping"))
    fun termOpen(reqId: String, target: String, cols: Int, rows: Int) =
        obj("t" to JsonPrimitive("term_open"), "reqId" to JsonPrimitive(reqId), "target" to JsonPrimitive(target), "cols" to JsonPrimitive(cols), "rows" to JsonPrimitive(rows))
    fun termInput(termId: String, dataB64: String) =
        obj("t" to JsonPrimitive("term_input"), "termId" to JsonPrimitive(termId), "data" to JsonPrimitive(dataB64))
    fun termResize(termId: String, cols: Int, rows: Int) =
        obj("t" to JsonPrimitive("term_resize"), "termId" to JsonPrimitive(termId), "cols" to JsonPrimitive(cols), "rows" to JsonPrimitive(rows))
    fun termClose(termId: String) =
        obj("t" to JsonPrimitive("term_close"), "termId" to JsonPrimitive(termId))
}
