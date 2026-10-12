import Testing
@testable import HerdrKit

@Test func aSnapshotLoadsTheView() {
    let a = agentSummary("ag1", parent: "toolu_a")
    let v = view(snapshot([user(1, "hi"), said(2, "hello")], epoch: 3, state: .working, hasMore: true, maxSeq: 7,
                          agents: [a], tasks: [bgTask("t1")]))
    #expect(v.loaded && v.epoch == 3 && v.state == .working && v.hasMore)
    #expect(v.entries.map(\.seq) == [1, 2])
    #expect(v.lastSeq == 7, "an unreadable event's seq still counts")
    #expect(v.agents == ["ag1": a])
    #expect(v.tasks == [bgTask("t1")])
}

@Test func eventsAppendInOrderAndAGapWaitsForASnapshot() {
    var v = view(snapshot([user(1, "hi")]))
    v.apply(event(said(2, "a")), now: 0)
    v.apply(event(said(2, "dup")), now: 0)
    v.apply(event(said(3, "old epoch"), epoch: 0), now: 0)
    #expect(v.entries.map(\.seq) == [1, 2] && !v.gap)

    v.apply(event(nil, seq: 3), now: 0)
    v.apply(event(said(4, "b")), now: 0)
    #expect(v.entries.map(\.seq) == [1, 2, 4] && !v.gap, "an unknown event's seq counts, so the next is no gap")

    v.apply(event(said(6, "skipped 5")), now: 0)
    #expect(v.gap)
    v.apply(event(said(7, "after the gap")), now: 0)
    #expect(v.entries.map(\.seq) == [1, 2, 4], "events wait for the healing snapshot")

    v.apply(snapshot([said(1, "fresh")], epoch: 1), now: 0)
    #expect(!v.gap && v.entries.map(\.seq) == [1])

    v.apply(event(said(1, "next epoch"), epoch: 2), now: 0)
    #expect(v.gap, "an event of a newer epoch means its snapshot was lost")
}

@Test func eventsBeforeTheFirstSnapshotAreIgnored() {
    let v = view(event(said(1, "early")))
    #expect(!v.loaded && v.entries.isEmpty)
}

@Test func keepsTheNewest5000Entries() {
    let entries = (1...5001).map { said($0, "m\($0)") }
    var v = view(snapshot(entries))
    #expect(v.entries.count == 5000 && v.entries.first?.seq == 2 && v.hasMore)
    v = view(snapshot(Array(entries.dropLast())))
    #expect(!v.hasMore)
    v.apply(event(said(5001, "one more")), now: 0)
    #expect(v.entries.count == 5000 && v.hasMore)
}

@Test func aRepeatedQuestionShowsOnce() {
    var v = view(snapshot([asked(1, "toolu_q"), said(2, "x"), asked(3, "toolu_q")]))
    #expect(v.entries.map(\.seq) == [1, 2])
    v.apply(event(asked(4, "toolu_q")), now: 0)
    #expect(v.entries.map(\.seq) == [1, 2] && v.lastSeq == 4)
}

@Test func aQuestionIsAnsweredWhenItsResultArrives() {
    var v = view(snapshot([asked(1, "toolu_q"), call(2, "toolu_x"), result(3, "toolu_x")]))
    #expect(v.answered.isEmpty)
    v.markAnswering("toolu_q")
    v.apply(event(result(4, "toolu_q", preview: "SQLite")), now: 0)
    #expect(v.answered == ["toolu_q"] && v.answering.isEmpty)
    #expect(view(snapshot([result(1, "toolu_q"), asked(2, "toolu_q")])).answered == ["toolu_q"])
}

@Test func anAnswerInFlightSurvivesASnapshotOfTheSameEpochOnly() {
    var v = view(snapshot([asked(1, "toolu_q")]))
    v.markAnswering("toolu_q")
    v.apply(snapshot([asked(1, "toolu_q")]), now: 0)
    #expect(v.answering == ["toolu_q"])
    v.apply(snapshot([asked(1, "toolu_q"), result(2, "toolu_q")]), now: 0)
    #expect(v.answering.isEmpty, "its result arrived with the snapshot")
    v.markAnswering("toolu_q")
    v.apply(snapshot([asked(1, "toolu_q")], epoch: 2), now: 0)
    #expect(v.answering.isEmpty, "a new epoch dropped the held answer")
}

@Test func agentsAndTasksFollowTheirFrames() {
    let a = agentSummary("ag1", parent: "toolu_a")
    var v = view(snapshot([], agents: [a], tasks: [bgTask("t1")]))
    v.apply(snapshot([], agents: nil, tasks: nil), now: 0)
    #expect(v.agents == ["ag1": a] && v.tasks.count == 1, "a thread's snapshot sends neither: keep them")

    let b = agentSummary("ag2", parent: "toolu_b", status: .done)
    v.apply(.chatAgent(paneId: "p", agent: b, removedId: nil), now: 0)
    v.apply(.chatAgent(paneId: "p", agent: nil, removedId: "ag1"), now: 0)
    #expect(v.agents == ["ag2": b])
    v.apply(.chatTasks(paneId: "p", tasks: []), now: 0)
    #expect(v.tasks.isEmpty)
    v.apply(snapshot([], missing: true), now: 0)
    #expect(v.missing)
}

@Test func goingIdleStampsTheTime() {
    var v = view(snapshot([], state: .working))
    v.apply(.chatState(paneId: "p", state: .working), now: 50)
    #expect(v.idleSince == 0)
    v.apply(.chatState(paneId: "p", state: .idle), now: 100)
    #expect(v.state == .idle && v.idleSince == 100)
    v.apply(.chatState(paneId: "p", state: .idle), now: 200)
    #expect(v.idleSince == 100)
}

// MARK: Pending messages

@Test func aDeliveredTextConfirmsTheOldestMatchingBubble() {
    var v = view(snapshot([user(1, "hi")]))
    v.addPending(id: "p1", text: " yes ", now: 0)
    v.addPending(id: "p2", text: "yes", now: 0)
    v.addPending(id: "p3", text: "no", now: 0)
    v.failPending(id: "p3", error: "no_mod")
    #expect(v.pending.map(\.text) == ["yes", "yes", "no"])
    v.apply(event(user(2, "yes ")), now: 0)
    #expect(v.pending.map(\.id) == ["p2", "p3"], "sending yes twice needs two deliveries")
    v.apply(event(user(3, "no")), now: 0)
    #expect(v.pending.map(\.id) == ["p2", "p3"], "a failed bubble is never confirmed")
    v.removePending(id: "p3")
    #expect(v.pending.map(\.id) == ["p2"])
}

@Test func aSnapshotConfirmsOnlyNewDeliveries() {
    var v = view(snapshot([user(1, "yes")]))
    v.addPending(id: "p1", text: "yes", now: 0)
    v.apply(snapshot([user(1, "yes")]), now: 0)
    #expect(v.pending.count == 1, "the same epoch's old history confirms nothing")
    v.apply(snapshot([user(1, "yes"), user(2, "yes")]), now: 0)
    #expect(v.pending.isEmpty)

    v.addPending(id: "p2", text: "go", now: 0)
    v.addPending(id: "p3", text: "stop", now: 0)
    v.apply(snapshot([user(1, "go"), user(2, "older"), user(3, "stop"), said(4, "ok")], epoch: 2), now: 0)
    #expect(v.pending.map(\.id) == ["p2"], "a new epoch: only the trailing user texts, one per outstanding bubble")
}

@Test func aQueuedMessageExpiresTwoMinutesAfterTheAgentWentIdle() {
    var v = view(snapshot([], state: .working))
    v.addPending(id: "p1", text: "a", now: 0)
    v.addPending(id: "p2", text: "b", now: 0)
    v.failPending(id: "p2", error: "outbox_full")
    v.expire(now: 500_000)
    #expect(v.pending[0].status == .queued, "nothing expires while the agent works")
    v.apply(.chatState(paneId: "p", state: .idle), now: 400_000)
    v.expire(now: 519_999)
    #expect(v.pending[0].status == .queued)
    v.expire(now: 520_000)
    #expect(v.pending.map(\.status) == [.notDelivered, .failed])
}

@Test func pendingLabelsSayWhatHappened() {
    func p(_ status: PendingStatus, _ error: String? = nil) -> PendingMessage {
        PendingMessage(id: "p", text: "t", sentAt: 0, status: status, error: error)
    }
    #expect(pendingLabel(p(.queued), state: .working) == "queued — Claude is busy")
    #expect(pendingLabel(p(.queued), state: .idle) == "sending…")
    #expect(pendingLabel(p(.failed, "no_mod"), state: .idle) == "Claude isn't connected")
    #expect(pendingLabel(p(.failed, "outbox_full"), state: .idle) == "too many queued messages")
    #expect(pendingLabel(p(.failed, "empty"), state: .idle) == "empty message")
    #expect(pendingLabel(p(.failed, "boom"), state: .idle) == "failed: boom")
    #expect(pendingLabel(p(.notDelivered), state: .idle) == "not delivered")
}

// MARK: Paging

@Test func aPageOfOlderHistoryIsPrependedOnlyWhenItFits() {
    var v = view(snapshot([asked(10, "toolu_q"), said(11, "x")], hasMore: true))
    v.applyPage(page([said(8, "a"), result(9, "toolu_q")], hasMore: true), token: "h1")
    #expect(v.entries.map(\.seq) == [10, 11], "no request in flight")

    v.startLoadingOlder(token: "h1")
    v.applyPage(page([said(8, "a")]), token: "h1")
    #expect(v.entries.map(\.seq) == [10, 11] && !v.loadingOlder, "a page that leaves a hole is dropped")

    v.startLoadingOlder(token: "h2")
    v.applyPage(page([said(9, "b")]), token: "h1")
    #expect(v.loadingOlder, "only the reply to the request in flight counts")
    v.applyPage(page([said(8, "a"), result(9, "toolu_q")], hasMore: false), token: "h2")
    #expect(v.entries.map(\.seq) == [8, 9, 10, 11] && !v.loadingOlder && !v.hasMore)
    #expect(v.pageStarts == [10])
    #expect(v.answered == ["toolu_q"])

    v.startLoadingOlder(token: "h3")
    v.applyPage(page([], stale: true), token: "h3")
    #expect(!v.loadingOlder && v.entries.count == 4)
    v.startLoadingOlder(token: "h4")
    v.applyPage(page([said(7, "c")], epoch: 9), token: "h4")
    #expect(!v.loadingOlder && v.entries.count == 4, "another epoch's page")

    v.startLoadingOlder(token: "h5")
    v.apply(snapshot([said(1, "fresh")]), now: 0)
    #expect(!v.loadingOlder && v.pageStarts.isEmpty, "a snapshot replaces what the page was meant to extend")
}

@Test func aLostPageAllowsPagingAgain() {
    var v = view(snapshot([said(5, "x")], hasMore: true))
    v.startLoadingOlder(token: "h1")
    v.olderTimedOut(token: "h0")
    #expect(v.loadingOlder)
    v.olderTimedOut(token: "h1")
    #expect(!v.loadingOlder)
}
