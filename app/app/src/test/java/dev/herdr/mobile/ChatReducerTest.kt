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
    private fun onFrame(v: ChatView, f: ServerFrame, now: Long = 0L) = ChatReducer.onFrame(v, f, now)
    private fun state(s: String) = ServerFrame.ChatState("p", s)
    private val min = 60_000L

    @Test fun snapshotReplacesEntries() {
        var v = onFrame(ChatView(), snap(1, user(1, "a"), reply(2, "b")))
        assertTrue(v.loaded)
        assertEquals(2, v.lastSeq)
        v = onFrame(v, snap(2, user(1, "fresh")))
        assertEquals(2, v.epoch)
        assertEquals(listOf("fresh"), v.entries.map { (it.event as ChatEvent.UserText).text })
        assertEquals(1, v.lastSeq)
    }

    @Test fun eventsAppendInOrderAndDuplicatesAreIgnored() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ev(1, reply(2, "b")))
        v = onFrame(v, ev(1, reply(2, "b")))
        v = onFrame(v, ev(1, reply(1, "old")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
    }

    @Test fun staleEpochAndPreSnapshotEventsAreIgnored() {
        val before = onFrame(ChatView(), ev(1, reply(1, "x")))
        assertFalse(before.loaded)
        assertTrue(before.entries.isEmpty())
        var v = onFrame(ChatView(), snap(3))
        v = onFrame(v, ev(2, reply(5, "stale")))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun unknownEventEntryIsIgnored() {
        val v = onFrame(onFrame(ChatView(), snap(1)), ServerFrame.ChatEventFrame("p", 1, null))
        assertTrue(v.entries.isEmpty())
    }

    @Test fun unknownEventAdvancesSeqSoTheNextIsNoGap() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ServerFrame.ChatEventFrame("p", 1, null, seq = 2))
        assertEquals(2, v.lastSeq)
        v = onFrame(v, ev(1, reply(3, "b")))
        assertFalse(v.gap)
        assertEquals(listOf(1, 3), v.entries.map { it.seq })
    }

    @Test fun unknownEventPastAGapStillGaps() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ServerFrame.ChatEventFrame("p", 1, null, seq = 3))
        assertTrue(v.gap)
    }

    @Test fun snapshotEndingInAnUnknownEventCountsItsSeq() {
        var v = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(1, "a")), maxSeq = 2))
        assertEquals(2, v.lastSeq)
        v = onFrame(v, ev(1, reply(3, "b")))
        assertFalse(v.gap)
    }

    @Test fun stateFrameUpdatesState() {
        val v = onFrame(ChatView(), ServerFrame.ChatState("p", "working"))
        assertEquals("working", v.state)
    }

    @Test fun entriesAreCapped() {
        var v = onFrame(ChatView(), snap(1))
        for (i in 1..(MAX_ENTRIES + 20)) v = onFrame(v, ev(1, reply(i, "r$i")))
        assertEquals(MAX_ENTRIES, v.entries.size)
        assertEquals(21, v.entries.first().seq)
    }

    @Test fun userTextConfirmsMatchingPending() {
        var v = onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "  run tests ", 0)
        v = onFrame(v, ev(1, user(1, "run tests")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun duplicateTextsConfirmOnePendingEach() {
        var v = onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = ChatReducer.addPending(v, "p2", "yes", 1)
        v = onFrame(v, ev(1, user(1, "yes")))
        assertEquals(listOf("p2"), v.pending.map { it.id })
    }

    @Test fun snapshotConfirmsDeliveredPending() {
        var v = ChatReducer.addPending(ChatView(), "p1", "hello", 0)
        v = onFrame(v, snap(2, user(1, "hello")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun failedPendingIsNotConfirmed() {
        var v = onFrame(ChatView(), snap(1))
        v = ChatReducer.addPending(v, "p1", "hi", 0)
        v = ChatReducer.failPending(v, "p1", "no_mod")
        v = onFrame(v, ev(1, user(1, "hi")))
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
        var v = onFrame(ChatView(), snap(1))
        v = ChatReducer.expire(ChatReducer.addPending(v, "p1", "late", 0), PENDING_TIMEOUT_MS)
        v = onFrame(v, ev(1, user(1, "late")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun pendingLabels() {
        val p = PendingMsg("p1", "x", 0)
        assertEquals("queued — Claude is busy", pendingLabel(p, "working"))
        assertEquals("sending…", pendingLabel(p, "idle"))
        assertEquals("not delivered", pendingLabel(p.copy(status = PendingStatus.NotDelivered), "idle"))
        fun failed(e: String?) = pendingLabel(p.copy(status = PendingStatus.Failed, error = e), "idle")
        assertEquals("Claude isn't connected", failed("no_mod"))
        assertEquals("too many queued messages", failed("outbox_full"))
        assertEquals("empty message", failed("empty"))
        assertEquals("failed: socket closed", failed("socket closed"))
        assertEquals("failed: error", failed(null))
    }

    @Test fun sameEpochSnapshotWithOldYesDoesNotConfirmNewPending() {
        var v = onFrame(ChatView(), snap(1, user(1, "yes")))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = onFrame(v, snap(1, user(1, "yes")))
        assertEquals(listOf("p1"), v.pending.map { it.id })
    }

    @Test fun sameEpochSnapshotWithNewYesConfirms() {
        var v = onFrame(ChatView(), snap(1, user(1, "yes")))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = onFrame(v, snap(1, user(1, "yes"), user(2, "yes")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun newEpochSnapshotConfirmsTrailingYes() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = onFrame(v, snap(2, user(1, "x"), user(2, "yes")))
        assertTrue(v.pending.isEmpty())
    }

    @Test fun newEpochSnapshotWithYesFarBackDoesNotConfirm() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = ChatReducer.addPending(v, "p1", "yes", 0)
        v = onFrame(v, snap(2, user(1, "yes"), user(2, "x")))
        assertEquals(1, v.pending.size)
    }

    @Test fun pendingDoesNotExpireWhileWorkingAndRestartsAfterIdle() {
        var v = onFrame(ChatView(), snap(1), now = 0)
        v = ChatReducer.addPending(v, "p1", "next", 0)
        v = onFrame(v, state("working"), now = 1_000)
        v = ChatReducer.expire(v, 5 * min)
        assertEquals(PendingStatus.Queued, v.pending.single().status)
        val idleAt = 5 * min + 1_000
        v = onFrame(v, state("idle"), now = idleAt)
        v = ChatReducer.expire(v, idleAt + 1)
        assertEquals(PendingStatus.Queued, v.pending.single().status)
        v = ChatReducer.expire(v, idleAt + PENDING_TIMEOUT_MS - 1)
        assertEquals(PendingStatus.Queued, v.pending.single().status)
        v = ChatReducer.expire(v, idleAt + PENDING_TIMEOUT_MS)
        assertEquals(PendingStatus.NotDelivered, v.pending.single().status)
    }

    @Test fun snapshotWorkingToIdleAlsoRestartsTheClock() {
        var v = onFrame(ChatView(), snap(1, state = "working"), now = 0)
        v = ChatReducer.addPending(v, "p1", "next", 0)
        v = ChatReducer.expire(v, 10 * min)
        assertEquals(PendingStatus.Queued, v.pending.single().status)
        v = onFrame(v, snap(2, state = "idle"), now = 10 * min)
        v = ChatReducer.expire(v, 10 * min + PENDING_TIMEOUT_MS - 1)
        assertEquals(PendingStatus.Queued, v.pending.single().status)
        v = ChatReducer.expire(v, 10 * min + PENDING_TIMEOUT_MS)
        assertEquals(PendingStatus.NotDelivered, v.pending.single().status)
    }

    @Test fun idleBeforeSendDoesNotExtendTheTimeout() {
        var v = onFrame(ChatView(), snap(1, state = "working"), now = 0)
        v = onFrame(v, state("idle"), now = 1_000)
        v = ChatReducer.addPending(v, "p1", "x", 10 * min)
        v = ChatReducer.expire(v, 10 * min + PENDING_TIMEOUT_MS)
        assertEquals(PendingStatus.NotDelivered, v.pending.single().status)
    }

    @Test fun seqGapIsFlaggedAndLaterEventsWaitForTheSnapshot() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ev(1, reply(2, "b")))
        assertFalse(v.gap)
        v = onFrame(v, ev(1, reply(4, "d"))) // seq 3 was dropped
        assertTrue(v.gap)
        assertEquals(2, v.lastSeq)
        v = onFrame(v, ev(1, reply(5, "e")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
        v = ChatReducer.addPending(v, "p1", "c", 0)
        v = onFrame(v, snap(1, user(1, "a"), reply(2, "b"), user(3, "c"), reply(4, "d"), reply(5, "e")))
        assertFalse(v.gap)
        assertEquals(listOf(1, 2, 3, 4, 5), v.entries.map { it.seq })
        assertTrue(v.pending.isEmpty())
    }

    // L2: a task notice is an entry of its own and never confirms a bubble.
    @Test fun taskNoticeIsKeptAndNeverConfirmsPending() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = ChatReducer.addPending(v, "p1", "done", 0)
        v = onFrame(v, ev(1, ChatEntry(2, ChatEvent.TaskNotice("d#0", "completed", "done"))))
        assertEquals(ChatEvent.TaskNotice("d#0", "completed", "done"), v.entries.last().event)
        assertEquals(2, v.lastSeq)
        assertEquals(listOf("p1"), v.pending.map { it.id })
        v = onFrame(v, snap(2, ChatEntry(1, ChatEvent.TaskNotice("d#0", "completed", "done"))))
        assertEquals(listOf("p1"), v.pending.map { it.id })
    }

    @Test fun taskNoticeLabels() {
        assertEquals("⚙ Background command \"x\" completed", taskNoticeLabel(ChatEvent.TaskNotice("u", "completed", "Background command \"x\" completed")))
        assertEquals("⚙ background task failed", taskNoticeLabel(ChatEvent.TaskNotice("u", "failed", " ")))
        assertEquals("⚙ background task", taskNoticeLabel(ChatEvent.TaskNotice("u", "", "")))
        assertTrue(taskNoticeIsError(ChatEvent.TaskNotice("u", "failed", "")))
        assertTrue(taskNoticeIsError(ChatEvent.TaskNotice("u", "killed", "")))
        assertFalse(taskNoticeIsError(ChatEvent.TaskNotice("u", "completed", "")))
    }

    // P2: the new-epoch snapshot was dropped; its events must flag a gap so
    // the repository re-opens instead of freezing on the old epoch.
    @Test fun newerEpochEventFlagsGap() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ev(2, reply(1, "new epoch")))
        assertTrue(v.gap)
        assertEquals(1, v.epoch)
        assertEquals(listOf("a"), v.entries.map { (it.event as ChatEvent.UserText).text })
        v = onFrame(v, snap(2, reply(1, "new epoch")))
        assertFalse(v.gap)
        v = onFrame(v, ev(2, reply(2, "next")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
    }

    // L3: entering the chat jumps to the newest item once, regardless of
    // where the list happens to be laid out.
    @Test fun entryScrollTargetIsTheLastItemOncePerEntry() {
        assertNull(entryScrollTarget(loaded = false, itemCount = 5, done = false))
        assertNull(entryScrollTarget(loaded = true, itemCount = 0, done = false))
        assertEquals(4, entryScrollTarget(loaded = true, itemCount = 5, done = false))
        assertNull(entryScrollTarget(loaded = true, itemCount = 5, done = true))
    }

    // ---- protocol 9: history paging ----

    private fun page(epoch: Int, vararg e: ChatEntry, hasMore: Boolean = false, stale: Boolean = false) =
        ServerFrame.ChatHistoryPage("h", "p", epoch, e.toList(), hasMore, stale)

    @Test fun snapshotCarriesHasMore() {
        val v = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(5, "a")), hasMore = true))
        assertTrue(v.hasMore)
        assertFalse(onFrame(v, snap(1, user(5, "a"))).hasMore)
    }

    private fun loading(v: ChatView) = ChatReducer.startLoadingOlder(v, "h")

    @Test fun historyPagePrependsOlderEntries() {
        var v = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(4, "d"), reply(5, "e")), hasMore = true))
        v = loading(v)
        assertTrue(v.loadingOlder)
        assertEquals("h", v.olderReqId)
        v = onFrame(v, page(1, user(2, "b"), reply(3, "c"), hasMore = true))
        assertEquals(listOf(2, 3, 4, 5), v.entries.map { it.seq })
        assertEquals(5, v.lastSeq)
        assertTrue(v.hasMore)
        assertFalse(v.loadingOlder)
        assertNull(v.olderReqId)
        v = onFrame(loading(v), page(1, user(1, "a"), hasMore = false))
        assertEquals(listOf(1, 2, 3, 4, 5), v.entries.map { it.seq })
        assertFalse(v.hasMore)
        assertFalse(v.loadingOlder)
    }

    @Test fun pageWithoutAMatchingRequestIsIgnored() {
        val base = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(4, "d")), hasMore = true))
        // No request in flight.
        assertEquals(base, onFrame(base, page(1, user(3, "c"))))
        // A different request is in flight: it stays in flight.
        val other = ChatReducer.startLoadingOlder(base, "h2")
        assertEquals(other, onFrame(other, page(1, user(3, "c"))))
    }

    // A page requested before a same-epoch re-snapshot must not be prepended
    // to the new entries: it would leave a silent hole in the history.
    @Test fun latePageAfterSameEpochSnapshotIsIgnored() {
        var v = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", (1000..1002).map { reply(it, "r") }, hasMore = true))
        v = loading(v)
        v = onFrame(v, ServerFrame.ChatSnapshot("p", 1, "idle", (1750..1752).map { reply(it, "r") }, hasMore = true))
        assertFalse(v.loadingOlder)
        assertNull(v.olderReqId)
        v = onFrame(v, page(1, *(700..999).map { reply(it, "old") }.toTypedArray(), hasMore = true))
        assertEquals(listOf(1750, 1751, 1752), v.entries.map { it.seq })
    }

    @Test fun nonContiguousPageIsDroppedAndClearsLoading() {
        var v = loading(onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(10, "d")), hasMore = true)))
        v = onFrame(v, page(1, user(7, "a"), user(8, "b"), hasMore = true))
        assertEquals(listOf(10), v.entries.map { it.seq })
        assertFalse(v.loadingOlder)
        assertNull(v.olderReqId)
        assertTrue(v.hasMore)
    }

    @Test fun olderTimeoutClearsOnlyItsOwnRequest() {
        val v = loading(onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(10, "d")), hasMore = true)))
        assertEquals(v, ChatReducer.olderTimedOut(v, "other"))
        val t = ChatReducer.olderTimedOut(v, "h")
        assertFalse(t.loadingOlder)
        assertNull(t.olderReqId)
    }

    @Test fun staleOrOtherEpochPageOnlyClearsLoadingOlder() {
        val base = loading(
            onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 2, "idle", listOf(user(4, "d")), hasMore = true)))
        val other = onFrame(base, page(1, user(1, "x"), hasMore = false))
        assertEquals(listOf(4), other.entries.map { it.seq })
        assertTrue(other.hasMore)
        assertFalse(other.loadingOlder)
        val stale = onFrame(base, page(2, hasMore = false, stale = true))
        assertEquals(listOf(4), stale.entries.map { it.seq })
        assertTrue(stale.hasMore)
        assertFalse(stale.loadingOlder)
    }

    @Test fun pageBeforeSnapshotIsIgnored() {
        val v = onFrame(ChatView(), page(1, user(1, "x"), hasMore = true))
        assertFalse(v.loaded)
        assertTrue(v.entries.isEmpty())
        assertFalse(v.hasMore)
    }

    @Test fun snapshotClearsLoadingOlder() {
        val v = loading(onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(4, "d")), hasMore = true)))
        val s = onFrame(v, snap(2, user(1, "n")))
        assertFalse(s.loadingOlder)
        assertNull(s.olderReqId)
    }

    @Test fun historyPageDoesNotConfirmPending() {
        var v = onFrame(ChatView(), ServerFrame.ChatSnapshot("p", 1, "idle", listOf(user(4, "d")), hasMore = true))
        v = ChatReducer.addPending(loading(v), "p1", "yes", 0)
        v = onFrame(v, page(1, user(3, "yes")))
        assertEquals(listOf(3, 4), v.entries.map { it.seq })
        assertEquals(listOf("p1"), v.pending.map { it.id })
    }

    // ---- protocol 9: questions ----

    private val qItem = QuestionItem("Color?", "Color", QuestionKind.Choice, listOf(QuestionOption("Red", null)), false)
    private fun question(seq: Int, toolUseId: String) = ChatEntry(seq, ChatEvent.Question("q$seq", toolUseId, listOf(qItem), null))
    private fun result(seq: Int, toolUseId: String) = ChatEntry(seq, ChatEvent.ToolResult(toolUseId, false, "Red"))

    @Test fun liveToolResultMarksQuestionAnswered() {
        var v = onFrame(ChatView(), snap(1, user(1, "a")))
        v = onFrame(v, ev(1, question(2, "tq")))
        assertEquals(emptySet<String>(), v.answered)
        v = ChatReducer.markAnswering(v, "tq")
        assertEquals(setOf("tq"), v.answering)
        v = onFrame(v, ev(1, result(3, "other")))
        assertEquals(emptySet<String>(), v.answered)
        v = onFrame(v, ev(1, result(4, "tq")))
        assertEquals(setOf("tq"), v.answered)
        assertEquals(emptySet<String>(), v.answering)
    }

    @Test fun snapshotAndPageComputeAnswered() {
        var v = onFrame(ChatView(), snap(1, question(1, "t1"), result(2, "t1"), question(3, "t2")))
        assertEquals(setOf("t1"), v.answered)
        v = onFrame(v, ServerFrame.ChatSnapshot("p", 2, "idle", listOf(result(10, "t0"), question(11, "t3")), hasMore = true))
        assertEquals(emptySet<String>(), v.answered)
        v = onFrame(loading(v), page(2, question(9, "t0")))
        assertEquals(setOf("t0"), v.answered)
    }

    @Test fun repeatedQuestionIsKeptOnce() {
        var v = onFrame(ChatView(), snap(1, question(1, "tq")))
        v = onFrame(v, ev(1, question(2, "tq")))
        assertEquals(1, v.entries.count { it.event is ChatEvent.Question })
        assertEquals(2, v.lastSeq)
        v = onFrame(v, snap(1, question(1, "tq"), user(2, "x"), question(3, "tq")))
        assertEquals(listOf(1, 2), v.entries.map { it.seq })
        v = onFrame(v, ServerFrame.ChatSnapshot("p", 2, "idle", listOf(question(5, "tz")), hasMore = true))
        v = onFrame(loading(v), page(2, question(4, "tz")))
        assertEquals(listOf(4), v.entries.map { it.seq })
    }

    @Test fun clearAnsweringDropsTheMark() {
        var v = ChatReducer.markAnswering(onFrame(ChatView(), snap(1)), "tq")
        v = ChatReducer.clearAnswering(v, "tq")
        assertEquals(emptySet<String>(), v.answering)
    }

    @Test fun answerErrorLabels() {
        assertEquals("Claude isn't connected", answerErrorLabel("no_mod"))
        assertEquals("the question is no longer waiting", answerErrorLabel("no_question"))
        assertEquals("empty answer", answerErrorLabel("empty"))
        assertEquals("answer failed: timed out", answerErrorLabel("timed out"))
        assertEquals("answer failed: error", answerErrorLabel(null))
    }

    @Test fun newEpochSnapshotDropsAnswering() {
        var v = ChatReducer.markAnswering(onFrame(ChatView(), snap(1, question(1, "tq"))), "tq")
        assertEquals(setOf("tq"), onFrame(v, snap(1, question(1, "tq"))).answering)
        v = onFrame(v, snap(2, question(1, "tq")))
        assertEquals(emptySet<String>(), v.answering)
    }

    @Test fun trimmingLiveEntriesSetsHasMore() {
        var v = onFrame(ChatView(), snap(1))
        for (i in 1..MAX_ENTRIES) v = onFrame(v, ev(1, reply(i, "r")))
        assertFalse(v.hasMore)
        v = onFrame(v, ev(1, reply(MAX_ENTRIES + 1, "r")))
        assertTrue(v.hasMore)
    }

    @Test fun multiSelectAnswerJoinsLabels() {
        assertEquals("Red, Blue", joinAnswerLabels(listOf("Red", "Blue")))
        assertEquals("Red", joinAnswerLabels(listOf("Red")))
    }

    // ---- timestamps ----

    @Test fun formatTsTodayVsEarlierDay() {
        val zone = java.time.ZoneId.of("Africa/Cairo")
        val loc = java.util.Locale.US
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
        val now = at("2026-10-04T15:30:00")
        assertEquals("09:05", formatTs(at("2026-10-04T09:05:00"), now, zone, loc))
        assertEquals("00:00", formatTs(at("2026-10-04T00:00:00"), now, zone, loc))
        assertEquals("Oct 3 23:59", formatTs(at("2026-10-03T23:59:00"), now, zone, loc))
        assertEquals("Sep 12 07:45", formatTs(at("2026-09-12T07:45:00"), now, zone, loc))
        // Just past midnight: an hour ago is yesterday.
        val afterMidnight = at("2026-10-05T00:10:00")
        assertEquals("Oct 4 23:10", formatTs(at("2026-10-04T23:10:00"), afterMidnight, zone, loc))
        assertEquals("00:05", formatTs(at("2026-10-05T00:05:00"), afterMidnight, zone, loc))
    }
}
