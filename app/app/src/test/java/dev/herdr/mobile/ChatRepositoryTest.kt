package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ChatRepositoryTest {
    @Test fun openCloseAndReconnectResubscribe() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open("w1:p1")
        repo.open("w2:p1")
        repo.close("w2:p1")
        sent.clear()
        repo.onReconnected()
        assertEquals(listOf(ClientMsg.chatOpen("w1:p1")), sent)
    }

    @Test fun framesRouteToTheirPane() {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> })
        repo.onFrame(ServerFrame.ChatState("w1:p1", "working"))
        assertEquals("working", repo.view("w1:p1").value.state)
        assertEquals("idle", repo.view("w2:p1").value.state)
    }

    @Test fun sendAddsPendingAndFailureMarksIt() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> throw RuntimeException("no_mod") }, now = { 5 })
        repo.send("p", "  hi  ")
        val p = repo.view("p").value.pending.single()
        assertEquals("hi", p.text)
        assertEquals(PendingStatus.Failed, p.status)
        assertEquals("no_mod", p.error)
    }

    @Test fun blankSendIsIgnored() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> calls++ })
        repo.send("p", "   ")
        assertEquals(0, calls)
        assertTrue(repo.view("p").value.pending.isEmpty())
    }

    @Test fun retryResendsAndReplacesThePending() = runTest {
        var fail = true
        val texts = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, t -> texts += t; if (fail) throw RuntimeException("outbox_full") })
        repo.send("p", "again")
        fail = false
        repo.retry("p", repo.view("p").value.pending.single().id)
        val p = repo.view("p").value.pending.single()
        assertEquals(PendingStatus.Queued, p.status)
        assertEquals(listOf("again", "again"), texts)
    }

    @Test fun expirePendingUsesTheClock() = runTest {
        var t = 0L
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, now = { t })
        repo.send("p", "x")
        t = PENDING_TIMEOUT_MS
        repo.expirePending()
        assertEquals(PendingStatus.NotDelivered, repo.view("p").value.pending.single().status)
    }

    @Test fun timeoutLeavesBubbleQueued() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> withTimeout(1) { awaitCancellation() } })
        repo.send("p", "x")
        assertEquals(PendingStatus.Queued, repo.view("p").value.pending.single().status)
    }

    @Test fun cancellationRethrowsAndLeavesBubbleQueued() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> throw CancellationException("c") })
        try { repo.send("p", "x"); fail("expected cancellation") } catch (e: CancellationException) {}
        assertEquals(PendingStatus.Queued, repo.view("p").value.pending.single().status)
    }

    @Test fun retryOnQueuedDoesNothing() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> calls++ })
        repo.send("p", "x")
        repo.retry("p", repo.view("p").value.pending.single().id)
        assertEquals(1, calls)
        assertEquals(1, repo.view("p").value.pending.size)
    }

    @Test fun seqGapReopensThePaneOncePerGap() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open("p")
        fun reply(seq: Int) = ServerFrame.ChatEventFrame("p", 1, ChatEntry(seq, ChatEvent.AssistantText("a$seq", "r")))
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", listOf(ChatEntry(1, ChatEvent.UserText("u1", "a")))))
        sent.clear()
        repo.onFrame(reply(2))
        assertEquals(emptyList<String>(), sent)
        repo.onFrame(reply(4))
        repo.onFrame(reply(5))
        assertEquals(listOf(ClientMsg.chatOpen("p")), sent)
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", (1..5).map { ChatEntry(it, ChatEvent.AssistantText("a$it", "r")) }))
        repo.onFrame(reply(7))
        assertEquals(listOf(ClientMsg.chatOpen("p"), ClientMsg.chatOpen("p")), sent)
    }

    @Test fun workingPendingSurvivesLongTurnsViaTheRepositoryClock() = runTest {
        var t = 0L
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, now = { t })
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "working", emptyList()))
        repo.send("p", "x")
        t = 5 * 60_000L
        repo.expirePending()
        assertEquals(PendingStatus.Queued, repo.view("p").value.pending.single().status)
        repo.onFrame(ServerFrame.ChatState("p", "idle"))
        t += PENDING_TIMEOUT_MS - 1
        repo.expirePending()
        assertEquals(PendingStatus.Queued, repo.view("p").value.pending.single().status)
        t += 1
        repo.expirePending()
        assertEquals(PendingStatus.NotDelivered, repo.view("p").value.pending.single().status)
    }
}
