package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class SlashCommandsTest {
    private val cmds = listOf(
        SlashCommand("compact", "Clear history but keep a summary", "builtin"),
        SlashCommand("config", "Open config", "builtin"),
        SlashCommand("superpowers:brainstorming", "Explore intent", "plugin"),
        SlashCommand("context", "Show context", "builtin"),
    )

    @Test fun parsesCommandsFrameSnapshotFieldAndOutputEvent() {
        val f = parseServerFrame("""{"t":"chat_commands","paneId":"p","commands":[{"name":"compact","description":"Clear","source":"builtin"},{"description":"nameless"}]}""") as ServerFrame.ChatCommands
        assertEquals("p", f.paneId)
        assertEquals(listOf(SlashCommand("compact", "Clear", "builtin")), f.commands)
        val s = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[],"commands":[{"name":"x","description":"","source":"user"}]}""") as ServerFrame.ChatSnapshot
        assertEquals(listOf("x"), s.commands?.map { it.name })
        val none = parseServerFrame("""{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[]}""") as ServerFrame.ChatSnapshot
        assertNull(none.commands)
        val e = parseChatEvent(kotlinx.serialization.json.Json.parseToJsonElement(
            """{"type":"command_output","uuid":"cmd-m1","command":"/compact","text":"Compacted","isError":true,"ts":5}""",
        ) as kotlinx.serialization.json.JsonObject)
        assertEquals(ChatEvent.CommandOutput("cmd-m1", "/compact", "Compacted", isError = true, ts = 5), e)
    }

    @Test fun reducerKeepsCommandsAcrossSnapshotsWithoutTheField() {
        var v = ChatReducer.onFrame(ChatView(), ServerFrame.ChatCommands("p", cmds), 0)
        assertEquals(cmds, v.commands)
        v = ChatReducer.onFrame(v, ServerFrame.ChatSnapshot("p", 1, "idle", emptyList()), 0)
        assertEquals(cmds, v.commands)
        v = ChatReducer.onFrame(v, ServerFrame.ChatSnapshot("p", 2, "idle", emptyList(), commands = cmds.take(1)), 0)
        assertEquals(cmds.take(1), v.commands)
    }

    @Test fun theEnginesEchoConfirmsThePendingSlashMessageNotItsOutput() {
        var v = ChatReducer.onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", emptyList()), 0)
        v = ChatReducer.addPending(v, "m1", "/compact   keep the plan ", 0)
        v = ChatReducer.addPending(v, "m2", "/compact keep the plan", 0)
        v = ChatReducer.onFrame(v, ServerFrame.ChatEventFrame("p", 1, ChatEntry(1, ChatEvent.UserText("u1", "/compact keep the plan"))), 0)
        v = ChatReducer.onFrame(v, ServerFrame.ChatEventFrame("p", 1, ChatEntry(2, ChatEvent.CommandOutput("cmd-m1", "/compact keep the plan", "Compacted"))), 0)
        assertEquals(listOf("m2"), v.pending.map { it.id })
    }

    @Test fun aClearIsDeliveredByTheNewSessionsSnapshot() {
        // /clear starts a new session: its echo never arrives, the new epoch does.
        var v = ChatReducer.onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", emptyList()), 0)
        v = ChatReducer.addPending(v, "m1", "/clear", 0)
        v = ChatReducer.addPending(v, "m2", "hello", 0)
        v = ChatReducer.onFrame(v, ServerFrame.ChatSnapshot("p", 2, "idle", emptyList()), 0)
        assertEquals(listOf("m2"), v.pending.map { it.id })
        // Its aliases too; a resync of the same epoch confirms nothing.
        v = ChatReducer.addPending(v, "m3", "/reset", 0)
        v = ChatReducer.onFrame(v, ServerFrame.ChatSnapshot("p", 2, "idle", emptyList()), 0)
        assertEquals(listOf("m2", "m3"), v.pending.map { it.id })
        v = ChatReducer.onFrame(v, ServerFrame.ChatSnapshot("p", 3, "idle", emptyList()), 0)
        assertEquals(listOf("m2"), v.pending.map { it.id })
    }

    @Test fun pickerOpensOnlyWhileTypingTheName() {
        assertNotNull(slashMatches("/", cmds))
        assertNotNull(slashMatches("/co", cmds))
        assertNull(slashMatches("", cmds))
        assertNull(slashMatches("hi /co", cmds))
        assertNull(slashMatches("/compact ", cmds))
        assertNull(slashMatches("/co\n", cmds))
        assertNull(slashMatches("/co", emptyList()))
    }

    @Test fun prefixMatchesComeBeforeSubstringOnes() {
        assertEquals(cmds, slashMatches("/", cmds))
        assertEquals(listOf("compact", "config", "context"), slashMatches("/co", cmds)!!.map { it.name })
        assertEquals(listOf("superpowers:brainstorming"), slashMatches("/brain", cmds)!!.map { it.name })
        // Case-insensitive substring, in list order, after any prefix match.
        assertEquals(listOf("config", "context"), slashMatches("/ON", cmds)!!.map { it.name })
        assertEquals(listOf("context", "compact"), slashMatches("/c", listOf(cmds[3], SlashCommand("xc", "", "user"), cmds[0]))!!.map { it.name }.take(2))
        assertEquals(emptyList<SlashCommand>(), slashMatches("/zzz", cmds))
    }

    @Test fun draftsAreKeptPerPaneUntilCleared() {
        val d = ChatDrafts()
        assertEquals("", d["p1"])
        d["p1"] = "half a thought"
        d["p2"] = "other"
        assertEquals("half a thought", d["p1"])
        d["p1"] = ""
        assertEquals("", d["p1"])
        assertEquals("other", d["p2"])
    }
}
