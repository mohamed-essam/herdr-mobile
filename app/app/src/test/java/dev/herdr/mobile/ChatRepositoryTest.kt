package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
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

    @Test fun newerEpochEventReopensOnce() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open("p")
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", listOf(ChatEntry(1, ChatEvent.UserText("u1", "a")))))
        sent.clear()
        repo.onFrame(ServerFrame.ChatEventFrame("p", 2, ChatEntry(1, ChatEvent.AssistantText("a1", "r"))))
        repo.onFrame(ServerFrame.ChatEventFrame("p", 2, ChatEntry(2, ChatEvent.AssistantText("a2", "r"))))
        assertEquals(listOf(ClientMsg.chatOpen("p")), sent)
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

    // ---- protocol 9: history paging ----

    private fun snapMore(epoch: Int, hasMore: Boolean, vararg seqs: Int) = ServerFrame.ChatSnapshot(
        "p", epoch, "idle", seqs.map { ChatEntry(it, ChatEvent.AssistantText("a$it", "r")) }, hasMore)

    private fun historyMsgs(sent: List<String>) = sent.filter { it.contains("\"chat_history\"") }

    @Test fun loadOlderSendsOneRequestWhileInFlight() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.loadOlder("p") // not loaded yet
        assertEquals(emptyList<String>(), historyMsgs(sent))
        repo.onFrame(snapMore(3, true, 301, 302))
        repo.loadOlder("p")
        repo.loadOlder("p")
        val reqs = historyMsgs(sent)
        assertEquals(1, reqs.size)
        val o = kotlinx.serialization.json.Json.parseToJsonElement(reqs[0]) as kotlinx.serialization.json.JsonObject
        val reqId = (o["reqId"] as kotlinx.serialization.json.JsonPrimitive).content
        assertEquals(ClientMsg.chatHistory(reqId, "p", 3, 301, 300), reqs[0])
        assertTrue(repo.view("p").value.loadingOlder)
        repo.onFrame(ServerFrame.ChatHistoryPage(reqId, "p", 3, listOf(ChatEntry(300, ChatEvent.AssistantText("a300", "r"))), true, false))
        assertFalse(repo.view("p").value.loadingOlder)
        repo.loadOlder("p")
        assertEquals(2, historyMsgs(sent).size)
        assertTrue(historyMsgs(sent)[1].contains("\"beforeSeq\":300"))
    }

    @Test fun loadOlderDoesNothingWithoutHasMore() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.onFrame(snapMore(1, false, 1, 2))
        repo.loadOlder("p")
        assertEquals(emptyList<String>(), historyMsgs(sent))
        assertFalse(repo.view("p").value.loadingOlder)
    }

    @Test fun reconnectSnapshotResetsPaging() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open("p")
        repo.onFrame(snapMore(1, true, 5))
        repo.loadOlder("p")
        repo.onReconnected()
        // The reply to the lost request never comes; the fresh snapshot resets paging.
        repo.onFrame(snapMore(1, true, 5))
        repo.loadOlder("p")
        assertEquals(2, historyMsgs(sent).size)
    }

    // ---- protocol 9: images ----

    private fun imageMsgs(sent: List<String>) = sent.filter { it.contains("\"chat_image\"") }
    private fun b64(s: String) = java.util.Base64.getEncoder().encodeToString(s.toByteArray())

    @Test fun imageIsRequestedOnceAndServedFromCache() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        assertEquals(ImageState.Loading, st.value)
        assertSame(st, repo.imageState("p", "u#0"))
        assertEquals(listOf(ClientMsg.chatImage("p", "u#0")), imageMsgs(sent))
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", "image/png", b64("png!"), false))
        assertEquals("png!", String((st.value as ImageState.Ready).bytes))
        assertEquals("png!", String((repo.imageState("p", "u#0").value as ImageState.Ready).bytes))
        assertEquals(1, imageMsgs(sent).size)
    }

    @Test fun imagesAreKeyedByPane() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        repo.imageState("p", "u#0")
        repo.imageState("q", "u#0")
        assertEquals(listOf(ClientMsg.chatImage("p", "u#0"), ClientMsg.chatImage("q", "u#0")), imageMsgs(sent))
    }

    @Test fun imageLruEvictsAt31() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        for (i in 0 until 31) {
            repo.imageState("p", "i$i")
            repo.onFrame(ServerFrame.ChatImageData("p", "i$i", "image/png", b64("x$i"), false))
        }
        assertEquals(31, imageMsgs(sent).size)
        repo.imageState("p", "i30") // still cached
        assertEquals(31, imageMsgs(sent).size)
        repo.imageState("p", "i0") // evicted as least recently used
        assertEquals(32, imageMsgs(sent).size)
        assertEquals(ClientMsg.chatImage("p", "i0"), imageMsgs(sent).last())
    }

    @Test fun imageLruKeepsRecentlyUsed() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        for (i in 0 until 30) {
            repo.imageState("p", "i$i")
            repo.onFrame(ServerFrame.ChatImageData("p", "i$i", "image/png", b64("x"), false))
        }
        repo.imageState("p", "i0") // touch: i1 becomes the eldest
        repo.imageState("p", "i30")
        repo.onFrame(ServerFrame.ChatImageData("p", "i30", "image/png", b64("x"), false))
        val before = imageMsgs(sent).size
        repo.imageState("p", "i0")
        assertEquals(before, imageMsgs(sent).size)
        repo.imageState("p", "i1")
        assertEquals(before + 1, imageMsgs(sent).size)
    }

    @Test fun atMostThreeImageRequestsInFlight() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        for (i in 0 until 5) repo.imageState("p", "i$i")
        assertEquals((0 until 3).map { ClientMsg.chatImage("p", "i$it") }, imageMsgs(sent))
        repo.onFrame(ServerFrame.ChatImageData("p", "i1", "image/png", b64("x"), false))
        assertEquals(4, imageMsgs(sent).size)
        assertEquals(ClientMsg.chatImage("p", "i3"), imageMsgs(sent).last())
        repo.onFrame(ServerFrame.ChatImageData("p", "i0", null, null, true)) // missing: retry later, slot frees now
        assertEquals(5, imageMsgs(sent).size)
        assertEquals(ClientMsg.chatImage("p", "i4"), imageMsgs(sent).last())
    }

    @Test fun missingImageIsRetriedOnceAfterThreeSeconds() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", null, null, true))
        assertEquals(ImageState.Loading, st.value)
        advanceTimeBy(2_999); runCurrent()
        assertEquals(1, imageMsgs(sent).size)
        advanceTimeBy(1); runCurrent()
        assertEquals(2, imageMsgs(sent).size)
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", null, null, true))
        assertEquals(ImageState.Missing, st.value)
        advanceTimeBy(10_000); runCurrent()
        assertEquals(2, imageMsgs(sent).size)
        repo.imageState("p", "u#0")
        assertEquals(2, imageMsgs(sent).size)
    }

    @Test fun missingImageThatArrivesOnRetryIsReady() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", null, null, true))
        advanceTimeBy(3_000); runCurrent()
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", "image/png", b64("late"), false))
        assertEquals("late", String((st.value as ImageState.Ready).bytes))
    }

    @Test fun undecodableImageIsMissing() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", "image/png", "%%%not base64", false))
        assertEquals(ImageState.Missing, st.value)
    }

    @Test fun reconnectReRequestsLoadingImages() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        for (i in 0 until 4) repo.imageState("p", "i$i")
        repo.onFrame(ServerFrame.ChatImageData("p", "i0", "image/png", b64("x"), false))
        sent.clear()
        repo.onReconnected()
        assertEquals((1 until 4).map { ClientMsg.chatImage("p", "i$it") }, imageMsgs(sent))
    }

    // ---- protocol 9: answers ----

    @Test fun answerMarksAnsweringAndKeepsItUntilToolResult() = runTest {
        val calls = mutableListOf<Triple<String, String, Map<String, String>>>()
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, sendAnswer = { p, t, a -> calls += Triple(p, t, a) })
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", listOf(ChatEntry(1, ChatEvent.Question("q", "tq", emptyList(), null)))))
        repo.answer("p", "tq", mapOf("Color?" to "Red"))
        assertEquals(listOf(Triple("p", "tq", mapOf("Color?" to "Red"))), calls)
        assertEquals(setOf("tq"), repo.view("p").value.answering)
        repo.onFrame(ServerFrame.ChatEventFrame("p", 1, ChatEntry(2, ChatEvent.ToolResult("tq", false, "Red"))))
        assertEquals(emptySet<String>(), repo.view("p").value.answering)
        assertEquals(setOf("tq"), repo.view("p").value.answered)
    }

    @Test fun answerFailureClearsAnsweringAndRethrows() = runTest {
        var seen: Set<String>? = null
        lateinit var repo: ChatRepository
        repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, sendAnswer = { p, _, _ ->
            seen = repo.view(p).value.answering
            throw RuntimeException("no_question")
        })
        try { repo.answer("p", "tq", mapOf("Q" to "A")); fail("expected failure") } catch (e: RuntimeException) {
            assertEquals("no_question", e.message)
        }
        assertEquals(setOf("tq"), seen)
        assertEquals(emptySet<String>(), repo.view("p").value.answering)
    }

    @Test fun answerWhileInFlightIsIgnored() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, sendAnswer = { _, _, _ -> calls++ })
        repo.answer("p", "tq", mapOf("Q" to "A"))
        repo.answer("p", "tq", mapOf("Q" to "B"))
        assertEquals(1, calls)
    }
}
