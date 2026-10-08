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
    val chat: Boolean = false,
    /** What the agent last did; null when no herdr-chat mod is live. */
    val activity: PaneActivity? = null,
    /** The newest pending AskUserQuestion, answerable with chat_answer. */
    val ask: PaneAsk? = null,
    /** Background tasks (shells, agents, workflows, monitors) still running; 0 when none. */
    val bgRunning: Int = 0,
    /** The Claude session's context-window fill; null when no herdr-chat mod is live. */
    val context: PaneUsage? = null,
)

/**
 * A one-line summary of a pane's latest chat event. [kind] is "tool", "text",
 * "user", "question" or "notice"; [tool] is set only for "tool". [ts] is epoch ms.
 */
@Serializable
data class PaneActivity(
    val kind: String = "",
    val tool: String? = null,
    val text: String = "",
    val ts: Long = 0,
)

/** A pending AskUserQuestion; [questions] is the question event's array, verbatim. */
@Serializable
data class PaneAsk(
    val toolUseId: String = "",
    val questions: JsonArray = JsonArray(emptyList()),
) {
    /** [questions] parsed like a chat question event's. */
    val items: List<QuestionItem> get() = questions.mapNotNull(::parseQuestionItem)
}

/** A session's context window: [percent] of [window] tokens used ([tokens] when known, else 0). */
@Serializable
data class PaneUsage(
    val percent: Int = 0,
    val tokens: Long = 0,
    val window: Long = 0,
)

/** One rate-limit window: [kind] "five_hour" or "seven_day"; [resetsAt] ISO 8601 or null. */
@Serializable
data class LimitWindow(
    val kind: String = "",
    val percentUsed: Double = 0.0,
    val resetsAt: String? = null,
)

/** The account's rate-limit reading and when a mod last reported it (epoch ms). */
data class Limits(val windows: List<LimitWindow>, val observedAt: Long)

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

/** An image's size in pixels. */
data class PixelSize(val width: Int, val height: Int)

sealed interface ChatEvent {
    // [ts] is epoch ms, null when the companion didn't send one. Image fields
    // hold ids to fetch with chat_image; imageSizes has the pixel size of those
    // whose header the mod could read, so their space is reserved up front.
    data class UserText(
        val uuid: String, val text: String, val ts: Long? = null, val images: List<String> = emptyList(),
        val imageSizes: Map<String, PixelSize> = emptyMap(),
    ) : ChatEvent
    data class AssistantText(val uuid: String, val text: String, val ts: Long? = null) : ChatEvent
    data class ToolUse(val uuid: String, val toolUseId: String, val tool: String, val summary: String, val ts: Long? = null) : ChatEvent
    data class ToolResult(
        val toolUseId: String, val isError: Boolean, val preview: String, val images: List<String> = emptyList(), val ts: Long? = null,
        val imageSizes: Map<String, PixelSize> = emptyMap(),
    ) : ChatEvent
    /** A background task finished (Claude Code's task-notification). */
    data class TaskNotice(val uuid: String, val status: String, val summary: String, val ts: Long? = null) : ChatEvent
    /**
     * A slash command sent from the phone ran: [command] as run (`/name args`)
     * and what it printed ([text], empty when nothing), or why it was refused ([isError]).
     */
    data class CommandOutput(val uuid: String, val command: String, val text: String, val isError: Boolean = false, val ts: Long? = null) : ChatEvent
    /** An AskUserQuestion call; may repeat (e.g. after a resync), so key it by [toolUseId]. */
    data class Question(val uuid: String, val toolUseId: String, val questions: List<QuestionItem>, val ts: Long? = null) : ChatEvent
}

enum class QuestionKind { Choice, Text, Number }

data class QuestionOption(val label: String, val description: String? = null)

/** One question of an AskUserQuestion call. */
data class QuestionItem(
    val question: String,
    val header: String,
    val kind: QuestionKind = QuestionKind.Choice,
    val options: List<QuestionOption> = emptyList(),
    val multiSelect: Boolean = false,
    val min: Double? = null,
    val max: Double? = null,
    val step: Double? = null,
    val unit: String? = null,
    val placeholder: String? = null,
    val description: String? = null,
)

data class ChatEntry(val seq: Int, val event: ChatEvent)

private fun JsonObject.str(k: String) = strOrNull(k) ?: ""
private fun JsonObject.strOrNull(k: String) = (this[k] as? JsonPrimitive)?.contentOrNull
private fun JsonObject.bool(k: String) = (this[k] as? JsonPrimitive)?.booleanOrNull ?: false
private fun JsonObject.long(k: String) = (this[k] as? JsonPrimitive)?.longOrNull
private fun JsonObject.double(k: String) = (this[k] as? JsonPrimitive)?.doubleOrNull
private fun JsonObject.strings(k: String) =
    (this[k] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()

/** The event's imageSizes: {id: [width, height]}, skipping malformed or empty sizes. */
private fun JsonObject.imageSizes(): Map<String, PixelSize> =
    (this["imageSizes"] as? JsonObject)?.mapNotNull { (id, v) ->
        val wh = (v as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.intOrNull }
        if (wh != null && wh.size == 2 && wh[0] > 0 && wh[1] > 0) id to PixelSize(wh[0], wh[1]) else null
    }?.toMap() ?: emptyMap()

internal fun parseQuestionItem(el: JsonElement): QuestionItem? {
    val o = el as? JsonObject ?: return null
    return QuestionItem(
        question = o.str("question"),
        header = o.str("header"),
        kind = when (o.strOrNull("kind")) {
            "text" -> QuestionKind.Text
            "number" -> QuestionKind.Number
            else -> QuestionKind.Choice
        },
        options = (o["options"] as? JsonArray)?.mapNotNull { op ->
            (op as? JsonObject)?.let { QuestionOption(it.str("label"), it.strOrNull("description")) }
        } ?: emptyList(),
        multiSelect = o.bool("multiSelect"),
        min = o.double("min"),
        max = o.double("max"),
        step = o.double("step"),
        unit = o.strOrNull("unit"),
        placeholder = o.strOrNull("placeholder"),
        description = o.strOrNull("description"),
    )
}

/**
 * A subagent or workflow of a pane's chat. [kind] is "subagent" or "workflow";
 * [status] is "running", "done" or "failed". [parentToolUseId] links it to the
 * tool call that launched it, [parentAgentId] to the agent that did (nested).
 */
data class AgentSummary(
    val agentId: String,
    val parentToolUseId: String,
    val parentAgentId: String? = null,
    val kind: String,
    val label: String,
    val type: String? = null,
    val phase: String? = null,
    val status: String,
    val activity: PaneActivity? = null,
    val ts: Long = 0,
)

/**
 * A background task of a pane. [kind] is "shell", "subagent", "workflow" or
 * "monitor"; [status] is "running", "done" or "failed". Times are epoch ms.
 */
data class BgTask(
    val id: String,
    val kind: String,
    val label: String,
    val toolUseId: String,
    val status: String,
    val startedAt: Long,
    val endedAt: Long? = null,
)

/** Null for an element without an agentId (skipped, not an error). */
internal fun parseAgentSummary(el: JsonElement): AgentSummary? {
    val o = el as? JsonObject ?: return null
    val id = o.strOrNull("agentId")?.takeIf { it.isNotEmpty() } ?: return null
    val act = (o["activity"] as? JsonObject)?.let {
        PaneActivity(it.str("kind"), it.strOrNull("tool"), it.str("text"), it.long("ts") ?: 0)
    }
    return AgentSummary(
        agentId = id,
        parentToolUseId = o.str("parentToolUseId"),
        parentAgentId = o.strOrNull("parentAgentId"),
        kind = o.str("kind"),
        label = o.str("label"),
        type = o.strOrNull("type"),
        phase = o.strOrNull("phase"),
        status = o.str("status"),
        activity = act,
        ts = o.long("ts") ?: 0,
    )
}

/** Null for an element without an id (skipped, not an error). */
internal fun parseBgTask(el: JsonElement): BgTask? {
    val o = el as? JsonObject ?: return null
    val id = o.strOrNull("id")?.takeIf { it.isNotEmpty() } ?: return null
    return BgTask(id, o.str("kind"), o.str("label"), o.str("toolUseId"), o.str("status"), o.long("startedAt") ?: 0, o.long("endedAt"))
}

/** Null when [k] isn't an array, so "not sent" stays distinct from empty. */
private fun JsonObject.agentsOrNull(k: String): List<AgentSummary>? =
    (this[k] as? JsonArray)?.mapNotNull(::parseAgentSummary)

private fun JsonObject.tasksOrNull(k: String): List<BgTask>? =
    (this[k] as? JsonArray)?.mapNotNull(::parseBgTask)

/** One of the session's slash commands, as the composer's picker lists it. */
data class SlashCommand(val name: String, val description: String, val source: String)

private fun parseSlashCommand(el: JsonElement): SlashCommand? {
    val o = el as? JsonObject ?: return null
    val name = o.strOrNull("name")?.takeIf { it.isNotEmpty() } ?: return null
    return SlashCommand(name, o.strOrNull("description") ?: "", o.strOrNull("source") ?: "")
}

/** Null when [k] isn't an array: a mod that sent no list (no picker). */
private fun JsonObject.commandsOrNull(k: String): List<SlashCommand>? =
    (this[k] as? JsonArray)?.mapNotNull(::parseSlashCommand)

/** Null for an event type this app version doesn't know (skipped, not an error). */
fun parseChatEvent(o: JsonObject): ChatEvent? {
    fun s(k: String) = o.str(k)
    val ts = o.long("ts")
    return when (s("type")) {
        "user_text" -> ChatEvent.UserText(s("uuid"), s("text"), ts, o.strings("images"), o.imageSizes())
        "assistant_text" -> ChatEvent.AssistantText(s("uuid"), s("text"), ts)
        "tool_use" -> ChatEvent.ToolUse(s("uuid"), s("toolUseId"), s("tool"), s("summary"), ts)
        "task_notice" -> ChatEvent.TaskNotice(s("uuid"), s("status"), s("summary"), ts)
        "command_output" -> ChatEvent.CommandOutput(s("uuid"), s("command"), s("text"), o.bool("isError"), ts)
        "tool_result" -> ChatEvent.ToolResult(s("toolUseId"), o.bool("isError"), s("preview"), o.strings("images"), ts, o.imageSizes())
        "question" -> ChatEvent.Question(
            s("uuid"), s("toolUseId"),
            (o["questions"] as? JsonArray)?.mapNotNull(::parseQuestionItem) ?: emptyList(), ts)
        else -> null
    }
}

private fun parseChatEntry(el: JsonElement): ChatEntry? {
    val o = el as? JsonObject ?: return null
    val ev = (o["event"] as? JsonObject)?.let(::parseChatEvent) ?: return null
    return ChatEntry(o["seq"]?.jsonPrimitive?.intOrNull ?: 0, ev)
}

/** The highest seq among [events], counting entries [parseChatEntry] leaves out. */
private fun maxSeq(events: JsonArray?): Int =
    events?.maxOfOrNull { ((it as? JsonObject)?.get("seq") as? JsonPrimitive)?.intOrNull ?: 0 } ?: 0

sealed interface ServerFrame {
    /**
     * [maxSeq]: the highest seq among all its events, including ones of an unknown type (left out of [entries]).
     * [agentId] is set for a thread's snapshot; [missing] says the companion doesn't know that thread.
     * [agents] and [tasks] come with the main stream's snapshot; null means not sent (distinct from empty).
     * [commands] too, from a mod that lists them.
     */
    data class ChatSnapshot(
        val paneId: String, val epoch: Int, val state: String, val entries: List<ChatEntry>,
        val hasMore: Boolean = false, val maxSeq: Int = 0,
        val agentId: String? = null, val missing: Boolean = false,
        val agents: List<AgentSummary>? = null, val tasks: List<BgTask>? = null,
        val commands: List<SlashCommand>? = null,
    ) : ServerFrame
    /** A [stale] page (the epoch moved on) carries no entries and hasMore false. */
    data class ChatHistoryPage(val reqId: String, val paneId: String, val epoch: Int, val entries: List<ChatEntry>, val hasMore: Boolean, val stale: Boolean = false, val agentId: String? = null) : ServerFrame
    /** [data] is base64; null with [missing] when the companion doesn't have the image. */
    data class ChatImageData(val paneId: String, val id: String, val mediaType: String?, val data: String?, val missing: Boolean) : ServerFrame
    data class ChatAnswerResult(val reqId: String, val ok: Boolean, val error: String?) : ServerFrame
    /** [entry] is null for an event of an unknown type; [seq] is the frame's seq either way. */
    data class ChatEventFrame(val paneId: String, val epoch: Int, val entry: ChatEntry?, val seq: Int = entry?.seq ?: 0, val agentId: String? = null) : ServerFrame
    /** An agent of the main stream was added or changed ([agent]), or dropped ([removedId]). */
    data class ChatAgent(val paneId: String, val agent: AgentSummary?, val removedId: String?) : ServerFrame
    /** The pane's background tasks, replacing the previous list. */
    data class ChatTasks(val paneId: String, val tasks: List<BgTask>) : ServerFrame
    /** The session's slash commands, replacing the previous list. */
    data class ChatCommands(val paneId: String, val commands: List<SlashCommand>) : ServerFrame
    data class ChatState(val paneId: String, val state: String) : ServerFrame
    data class ChatSendResult(val reqId: String, val ok: Boolean, val error: String?) : ServerFrame
    data object Welcome : ServerFrame
    data class Panes(val panes: List<Pane>) : ServerFrame
    data class Workspaces(val workspaces: List<Workspace>) : ServerFrame
    data class Tabs(val tabs: List<Tab>) : ServerFrame
    data class PaneUpdate(val pane: Pane) : ServerFrame
    data class PaneRemoved(val paneId: String) : ServerFrame
    data class LimitsFrame(val limits: Limits) : ServerFrame
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
        "limits" -> ServerFrame.LimitsFrame(Limits(
            (obj["limits"] as? JsonArray)?.map { json.decodeFromJsonElement<LimitWindow>(it) } ?: emptyList(),
            obj["observedAt"]?.jsonPrimitive?.longOrNull ?: 0L))
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
        "chat_snapshot" -> ServerFrame.ChatSnapshot(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
            obj["state"]?.jsonPrimitive?.contentOrNull ?: "idle",
            (obj["events"] as? JsonArray)?.mapNotNull(::parseChatEntry) ?: emptyList(),
            obj.bool("hasMore"),
            maxSeq(obj["events"] as? JsonArray),
            obj.strOrNull("agentId"), obj.bool("missing"),
            obj.agentsOrNull("agents"), obj.tasksOrNull("tasks"), obj.commandsOrNull("commands"))
        "chat_history_page" -> ServerFrame.ChatHistoryPage(
            obj.str("reqId"), obj.str("paneId"),
            obj["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
            (obj["events"] as? JsonArray)?.mapNotNull(::parseChatEntry) ?: emptyList(),
            obj.bool("hasMore"), obj.bool("stale"), obj.strOrNull("agentId"))
        "chat_image_data" -> ServerFrame.ChatImageData(
            obj.str("paneId"), obj.str("id"), obj.strOrNull("mediaType"), obj.strOrNull("data"), obj.bool("missing"))
        "chat_answer_result" -> ServerFrame.ChatAnswerResult(obj.str("reqId"), obj.bool("ok"), obj.strOrNull("error"))
        "chat_event" -> ServerFrame.ChatEventFrame(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["epoch"]?.jsonPrimitive?.intOrNull ?: 0,
            parseChatEntry(obj),
            (obj["seq"] as? JsonPrimitive)?.intOrNull ?: 0,
            obj.strOrNull("agentId"))
        "chat_agent" -> ServerFrame.ChatAgent(
            obj.str("paneId"),
            obj["agent"]?.let(::parseAgentSummary),
            if (obj.bool("removed")) obj.strOrNull("agentId") else null)
        "chat_tasks" -> ServerFrame.ChatTasks(obj.str("paneId"), obj.tasksOrNull("tasks") ?: emptyList())
        "chat_commands" -> ServerFrame.ChatCommands(obj.str("paneId"), obj.commandsOrNull("commands") ?: emptyList())
        "chat_state" -> ServerFrame.ChatState(
            obj["paneId"]?.jsonPrimitive?.content ?: "",
            obj["state"]?.jsonPrimitive?.contentOrNull ?: "idle")
        "chat_send_result" -> ServerFrame.ChatSendResult(
            obj["reqId"]?.jsonPrimitive?.content ?: "",
            obj["ok"]?.jsonPrimitive?.boolean ?: false,
            obj["error"]?.jsonPrimitive?.contentOrNull)
        else -> ServerFrame.Unknown
    }
}

object ClientMsg {
    private fun obj(vararg pairs: Pair<String, JsonElement>) =
        JsonObject(pairs.toMap()).toString()

    /** [agentId] picks a thread of the pane; omitted for the main stream. */
    private fun withAgent(agentId: String?, vararg pairs: Pair<String, JsonElement>) =
        obj(*pairs, *(if (agentId != null) arrayOf("agentId" to JsonPrimitive(agentId)) else emptyArray()))

    fun chatOpen(paneId: String, agentId: String? = null) =
        withAgent(agentId, "t" to JsonPrimitive("chat_open"), "paneId" to JsonPrimitive(paneId))
    fun chatClose(paneId: String, agentId: String? = null) =
        withAgent(agentId, "t" to JsonPrimitive("chat_close"), "paneId" to JsonPrimitive(paneId))
    fun chatSend(reqId: String, paneId: String, text: String) =
        obj("t" to JsonPrimitive("chat_send"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId), "text" to JsonPrimitive(text))
    fun chatHistory(reqId: String, paneId: String, epoch: Int, beforeSeq: Int, limit: Int, agentId: String? = null) =
        withAgent(agentId, "t" to JsonPrimitive("chat_history"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId),
            "epoch" to JsonPrimitive(epoch), "beforeSeq" to JsonPrimitive(beforeSeq), "limit" to JsonPrimitive(limit))
    fun chatImage(paneId: String, id: String) =
        obj("t" to JsonPrimitive("chat_image"), "paneId" to JsonPrimitive(paneId), "id" to JsonPrimitive(id))
    fun chatAnswer(reqId: String, paneId: String, toolUseId: String, answers: Map<String, String>) =
        obj("t" to JsonPrimitive("chat_answer"), "reqId" to JsonPrimitive(reqId), "paneId" to JsonPrimitive(paneId),
            "toolUseId" to JsonPrimitive(toolUseId), "answers" to JsonObject(answers.mapValues { JsonPrimitive(it.value) }))
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
