package dev.herdr.mobile

import dev.herdr.mobile.data.*
import dev.herdr.mobile.net.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
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

    @Test fun loadOlderWithNoReplyTimesOut() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        repo.onFrame(snapMore(1, true, 5))
        repo.loadOlder("p")
        advanceTimeBy(REPLY_TIMEOUT_MS - 1); runCurrent()
        assertTrue(repo.view("p").value.loadingOlder)
        repo.loadOlder("p")
        assertEquals(1, historyMsgs(sent).size)
        advanceTimeBy(1); runCurrent()
        assertFalse(repo.view("p").value.loadingOlder)
        assertNull(repo.view("p").value.olderReqId)
        repo.loadOlder("p")
        assertEquals(2, historyMsgs(sent).size)
    }

    @Test fun oldRequestTimeoutDoesNotClearANewerRequest() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        repo.onFrame(snapMore(1, true, 5))
        repo.loadOlder("p")
        advanceTimeBy(REPLY_TIMEOUT_MS - 10); runCurrent()
        repo.onFrame(snapMore(1, true, 5)) // re-snapshot drops the first request
        repo.loadOlder("p")
        advanceTimeBy(10); runCurrent() // the first request's timer fires
        assertTrue(repo.view("p").value.loadingOlder)
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

    @Test fun unansweredImageRequestTimesOutLikeMissing() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        for (i in 0 until 4) repo.imageState("p", "i$i")
        val st = repo.imageState("p", "i0")
        assertEquals(3, imageMsgs(sent).size)
        advanceTimeBy(REPLY_TIMEOUT_MS - 1); runCurrent()
        assertEquals(3, imageMsgs(sent).size)
        advanceTimeBy(1); runCurrent()
        // Slots freed: the queued i3 goes out; i0..i2 are retried once after IMAGE_RETRY_MS.
        assertEquals(ClientMsg.chatImage("p", "i3"), imageMsgs(sent)[3])
        assertEquals(ImageState.Loading, st.value)
        advanceTimeBy(IMAGE_RETRY_MS); runCurrent()
        assertEquals(ClientMsg.chatImage("p", "i0"), imageMsgs(sent).filter { it.contains("\"i0\"") }.last())
        assertEquals(2, imageMsgs(sent).count { it.contains("\"i0\"") })
        // The retry also goes unanswered: settle on Missing.
        advanceTimeBy(REPLY_TIMEOUT_MS); runCurrent()
        assertEquals(ImageState.Missing, st.value)
    }

    @Test fun lateReplyAfterRetryIsStillAccepted() = runTest {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        advanceTimeBy(REPLY_TIMEOUT_MS); runCurrent()
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

    /** Answers [id] missing twice (the first is retried after IMAGE_RETRY_MS): it settles on Missing. */
    private fun kotlinx.coroutines.test.TestScope.settleMissing(repo: ChatRepository, pane: String, id: String) {
        repo.onFrame(ServerFrame.ChatImageData(pane, id, null, null, true))
        advanceTimeBy(IMAGE_RETRY_MS); runCurrent()
        repo.onFrame(ServerFrame.ChatImageData(pane, id, null, null, true))
    }

    @Test fun missingImageIsRequestedAgainAfterANewSnapshot() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        repo.imageState("p", "u#0")
        settleMissing(repo, "p", "u#0")
        assertEquals(ImageState.Missing, repo.imageState("p", "u#0").value)
        assertEquals(2, imageMsgs(sent).size)
        repo.onFrame(ServerFrame.ChatSnapshot("p", 2, "idle", emptyList()))
        val st = repo.imageState("p", "u#0")
        assertEquals(ImageState.Loading, st.value)
        assertEquals(3, imageMsgs(sent).size)
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", "image/png", b64("now"), false))
        assertEquals("now", String((st.value as ImageState.Ready).bytes))
    }

    @Test fun observedMissingImageIsReRequestedOnSnapshot() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        backgroundScope.launch { st.collect {} } // shown on screen
        runCurrent()
        settleMissing(repo, "p", "u#0")
        assertEquals(ImageState.Missing, st.value)
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", emptyList())) // same-epoch re-open
        assertEquals(ImageState.Loading, st.value)
        assertEquals(3, imageMsgs(sent).size)
        // The retry-once rule applies afresh.
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", null, null, true))
        assertEquals(ImageState.Loading, st.value)
        advanceTimeBy(IMAGE_RETRY_MS); runCurrent()
        assertEquals(4, imageMsgs(sent).size)
        repo.onFrame(ServerFrame.ChatImageData("p", "u#0", "image/png", b64("ok"), false))
        assertEquals("ok", String((st.value as ImageState.Ready).bytes))
    }

    @Test fun snapshotOnlyResetsItsOwnPanesMissingImages() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        repo.imageState("q", "u#0")
        settleMissing(repo, "q", "u#0")
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", emptyList()))
        assertEquals(ImageState.Missing, repo.imageState("q", "u#0").value)
        assertEquals(2, imageMsgs(sent).size)
    }

    @Test fun reconnectReRequestsMissingImages() = runTest {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> }, scope = backgroundScope)
        val st = repo.imageState("p", "u#0")
        backgroundScope.launch { st.collect {} }
        runCurrent()
        settleMissing(repo, "p", "u#0")
        sent.clear()
        repo.onReconnected()
        assertEquals(ImageState.Loading, st.value)
        assertEquals(listOf(ClientMsg.chatImage("p", "u#0")), imageMsgs(sent))
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

    @Test fun answerForAnsweredQuestionIsIgnored() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, sendAnswer = { _, _, _ -> calls++ })
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", listOf(
            ChatEntry(1, ChatEvent.Question("q", "tq", emptyList(), null)),
            ChatEntry(2, ChatEvent.ToolResult("tq", false, "Red")))))
        val before = repo.view("p").value
        assertEquals(setOf("tq"), before.answered)
        repo.answer("p", "tq", mapOf("Q" to "A"))
        assertEquals(0, calls)
        assertEquals(before, repo.view("p").value)
    }

    @Test fun answerWhileInFlightIsIgnored() = runTest {
        var calls = 0
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> }, sendAnswer = { _, _, _ -> calls++ })
        repo.answer("p", "tq", mapOf("Q" to "A"))
        repo.answer("p", "tq", mapOf("Q" to "B"))
        assertEquals(1, calls)
    }

    // ---- protocol 10: thread keys ----

    private fun threadReply(seq: Int) = ServerFrame.ChatEventFrame("p", 1, ChatEntry(seq, ChatEvent.AssistantText("a$seq", "r")), agentId = "aa1")

    @Test fun threadFramesUpdateOnlyTheirKey() {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> })
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", emptyList()))
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "working", emptyList(), agentId = "aa1"))
        assertEquals("idle", repo.view(ChatKey("p")).value.state)
        assertEquals("working", repo.view(ChatKey("p", "aa1")).value.state)
        repo.onFrame(ServerFrame.ChatState("p", "working"))
        assertEquals("working", repo.view(ChatKey("p")).value.state)
        repo.onFrame(threadReply(1))
        assertEquals(1, repo.view(ChatKey("p", "aa1")).value.entries.size)
        assertEquals(0, repo.view(ChatKey("p")).value.entries.size)
    }

    @Test fun threadGapReopensWithAgentId() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open(ChatKey("p", "aa1"))
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", emptyList(), agentId = "aa1"))
        sent.clear()
        repo.onFrame(threadReply(3))
        assertEquals(listOf(ClientMsg.chatOpen("p", "aa1")), sent)
    }

    @Test fun reconnectReopensEveryOpenedKey() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.open(ChatKey("p"))
        repo.open(ChatKey("p", "aa1"))
        sent.clear()
        repo.onReconnected()
        assertEquals(setOf(ClientMsg.chatOpen("p"), ClientMsg.chatOpen("p", "aa1")), sent.toSet())
        assertEquals(2, sent.size)
        repo.close(ChatKey("p", "aa1"))
        assertTrue(sent.last().contains("chat_close") && sent.last().contains("aa1"))
        sent.clear()
        repo.onReconnected()
        assertEquals(listOf(ClientMsg.chatOpen("p")), sent)
    }

    @Test fun chatAgentAndTasksLandOnTheMainView() {
        val repo = ChatRepository(sendRaw = {}, sendChat = { _, _ -> })
        val a = AgentSummary("aa1", "tu", kind = "subagent", label = "L", status = "running")
        repo.onFrame(ServerFrame.ChatAgent("p", a, null))
        repo.onFrame(ServerFrame.ChatTasks("p", listOf(BgTask("t", "shell", "l", "tu", "running", 1))))
        assertEquals(setOf("aa1"), repo.view(ChatKey("p")).value.agents.keys)
        assertEquals(1, repo.view(ChatKey("p")).value.tasks.size)
        assertTrue(repo.view(ChatKey("p", "aa1")).value.agents.isEmpty())
    }

    @Test fun threadLoadOlderSendsAgentId() {
        val sent = mutableListOf<String>()
        val repo = ChatRepository(sendRaw = { sent += it }, sendChat = { _, _ -> })
        repo.onFrame(ServerFrame.ChatSnapshot("p", 1, "idle", listOf(ChatEntry(5, ChatEvent.AssistantText("a", "r"))), hasMore = true, agentId = "aa1"))
        repo.loadOlder(ChatKey("p", "aa1"))
        assertEquals(1, historyMsgs(sent).size)
        assertTrue(historyMsgs(sent).single().contains(""""agentId":"aa1""""))
    }
}
