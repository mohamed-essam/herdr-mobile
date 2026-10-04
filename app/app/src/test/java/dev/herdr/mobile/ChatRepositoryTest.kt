package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import kotlinx.coroutines.test.runTest
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
}
