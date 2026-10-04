package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import org.junit.Assert.*
import org.junit.Test

class ChatReducerTest {
    private fun user(seq: Int, text: String) = ChatEntry(seq, ChatEvent.UserText("u$seq", text))
    private fun reply(seq: Int, text: String) = ChatEntry(seq, ChatEvent.AssistantText("a$seq", text))
    private fun snap(epoch: Int, vararg e: ChatEntry, state: String = "idle") =
        ServerFrame.ChatSnapshot("p", epoch, state, e.toList())
    private fun ev(epoch: Int, e: ChatEntry) = ServerFrame.ChatEventFrame("p", epoch, e)

    @Test fun snapshotReplacesEntries() {
        var v = ChatReducer.onFrame(ChatView(), snap(1, user(1, "a"), reply(2, "b")))
        assertTrue(v.loaded)
        assertEquals(2, v.lastSeq)
        v = ChatReducer.onFrame(v, snap(2, user(1, "fresh")))
        assertEquals(2, v.epoch)
        assertEquals(listOf("fresh"), v.entries.map { (it.event as ChatEvent.UserText).text })
        assertEquals(1, v.lastSeq)
    }

    @Test fun eventsAppendInOrderAndDuplicatesAreIgnored() {
        var v = ChatReducer.onFrame(ChatView(), snap(1, user(1, "a")))
        v = ChatReducer.onFrame(v, ev(1, reply(2, "b")))
        v = ChatReducer.onFrame(v, ev(1, reply(2, "b")))
        v = ChatReducer.onFrame(v, ev(1, reply(1, "old")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
    }

    @Test fun staleEpochAndPreSnapshotEventsAreIgnored() {
        val before = ChatReducer.onFrame(ChatView(), ev(1, reply(1, "x")))
        assertFalse(before.loaded)
        assertTrue(before.entries.isEmpty())
        var v = ChatReducer.onFrame(ChatView(), snap(3))
        v = ChatReducer.onFrame(v, ev(2, reply(5, "stale")))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun unknownEventEntryIsIgnored() {
        val v = ChatReducer.onFrame(ChatReducer.onFrame(ChatView(), snap(1)), ServerFrame.ChatEventFrame("p", 1, null))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun stateFrameUpdatesState() {
        val v = ChatReducer.onFrame(ChatView(), ServerFrame.ChatState("p", "working"))
        assertEquals("working", v.state)
    }

    @Test fun entriesAreCapped() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        for (i in 1..(MAX_ENTRIES + 20)) v = ChatReducer.onFrame(v, ev(1, reply(i, "r$i")))
        assertEquals(MAX_ENTRIES, v.entries.size)
        assertEquals(21, v.entries.first().seq)
    }

    @Test fun userTextConfirmsMatchingPending() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "  run tests ", 0)
        v = ChatReducer.onFrame(v, ev(1, user(1, "run tests")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun duplicateTextsConfirmOnePendingEach() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = ChatReducer.addPending(v, "p2", "yes", 1)
        v = ChatReducer.onFrame(v, ev(1, user(1, "yes")))
        assertEquals(listOf("p2"), v.pending.map { it.id })
    }

    @Test fun snapshotConfirmsDeliveredPending() {
        var v = ChatReducer.addPending(ChatView(), "p1", "hello", 0)
        v = ChatReducer.onFrame(v, snap(2, user(1, "hello")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun failedPendingIsNotConfirmed() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "hi", 0)
        v = ChatReducer.failPending(v, "p1", "no_mod")
        v = ChatReducer.onFrame(v, ev(1, user(1, "hi")))
        assertEquals(PendingStatus.Failed, v.pending.single().status)
        assertEquals("no_mod", v.pending.single().error)
    }

    @Test fun expireMarksOldQueuedAsNotDelivered() {
        var v = ChatReducer.addPending(ChatView(), "p1", "a", 0)
        v = ChatReducer.addPending(v, "p2", "b", 100_000)
        v = ChatReducer.expire(v, PENDING_TIMEOUT_MS)
        assertEquals(listOf(PendingStatus.NotDelivered, PendingStatus.Queued), v.pending.map { it.status })
    }

    @Test fun lateDeliveryConfirmsNotDelivered() {
        var v = ChatReducer.onFrame(ChatView(), snap(1))
        v = ChatReducer.expire(ChatReducer.addPending(v, "p1", "late", 0), PENDING_TIMEOUT_MS)
        v = ChatReducer.onFrame(v, ev(1, user(1, "late")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun pendingLabels() {
        val p = PendingMsg("p1", "x", 0)
        assertEquals("queued — Claude is busy", pendingLabel(p, "working"))
        assertEquals("sending…", pendingLabel(p, "idle"))
        assertEquals("not delivered", pendingLabel(p.copy(status = PendingStatus.NotDelivered), "idle"))
        assertEquals("failed: no_mod", pendingLabel(p.copy(status = PendingStatus.Failed, error = "no_mod"), "idle"))
    }
}
