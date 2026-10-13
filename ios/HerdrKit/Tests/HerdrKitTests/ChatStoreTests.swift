import Foundation
import Testing
@testable import HerdrKit

/// Stands in for the client: records each message and request, and answers requests with `reply`.
@MainActor private final class Wire {
    var messages: [ClientMessage] = []
    var requests: [ClientRequest] = []
    var reply: (ClientRequest) async throws -> ServerFrame = { _ in .chatSendResult(reqId: "", ok: true, error: nil) }
    var clock = 0

    func makeStore(imageReplyTimeout: Duration = .seconds(15), imageRetry: Duration = .seconds(3)) -> ChatStore {
        ChatStore(send: { self.messages.append($0) },
                  request: { r in self.requests.append(r); return try await self.reply(r) },
                  now: { self.clock },
                  imageReplyTimeout: imageReplyTimeout, imageRetry: imageRetry)
    }

    var images: [String] { messages.compactMap { if case .chatImage(_, let id) = $0 { id } else { nil } } }
}

private let main = ChatKey(paneId: "p")
private let thread = ChatKey(paneId: "p", agentId: "ag1")

@Test @MainActor func opensAndReopensTheChatsOnScreen() {
    let wire = Wire()
    let store = wire.makeStore()
    store.open(main)
    store.open(thread)
    #expect(wire.messages == [.chatOpen(paneId: "p"), .chatOpen(paneId: "p", agentId: "ag1")])
    store.close(thread)
    #expect(wire.messages.last == .chatClose(paneId: "p", agentId: "ag1"))

    wire.messages = []
    store.apply(.welcome)
    #expect(wire.messages == [.chatOpen(paneId: "p")], "a reconnect re-opens what is still open")
}

@Test @MainActor func aGapReopensTheChatOnce() {
    let wire = Wire()
    let store = wire.makeStore()
    store.open(main)
    store.apply(snapshot([said(1, "a")]))
    wire.messages = []
    store.apply(event(said(3, "skipped")))
    store.apply(event(said(4, "again")))
    #expect(wire.messages == [.chatOpen(paneId: "p")])
    #expect(store.view(main).gap)
}

@Test @MainActor func threadsAreReopenedWhenTheMainStreamResets() {
    let wire = Wire()
    let store = wire.makeStore()
    store.open(main)
    store.open(thread)
    store.apply(snapshot([]))
    store.apply(snapshot([], agentId: "ag1", missing: true))
    wire.messages = []
    store.apply(snapshot([], epoch: 2))
    #expect(wire.messages == [.chatOpen(paneId: "p", agentId: "ag1")], "a new main epoch re-opens the open thread")

    wire.messages = []
    store.apply(.chatAgent(paneId: "p", agent: agentSummary("ag1", parent: "toolu_a"), removedId: nil))
    #expect(wire.messages == [.chatOpen(paneId: "p", agentId: "ag1")], "its agent came back while it was missing")
    store.apply(snapshot([], agentId: "ag1"))
    wire.messages = []
    store.apply(.chatAgent(paneId: "p", agent: agentSummary("ag1", parent: "toolu_a", status: .done), removedId: nil))
    #expect(wire.messages.isEmpty)
    #expect(store.view(main).agents["ag1"]?.status == .done, "agents live on the main view")
}

@Test @MainActor func pagesBackFromTheOldestEntry() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.loadOlder(thread)
    store.apply(snapshot([said(5, "x")], agentId: "ag1", epoch: 2, hasMore: true))
    let (gate, open) = AsyncStream<Void>.makeStream()
    wire.reply = { _ in
        for await _ in gate {}
        return .chatHistoryPage(page([said(4, "older")], agentId: "ag1", epoch: 2, hasMore: false))
    }
    store.loadOlder(thread)
    store.loadOlder(thread)
    #expect(await until { wire.requests.count == 1 })
    #expect(wire.requests == [.chatHistory(paneId: "p", epoch: 2, beforeSeq: 5, limit: 300, agentId: "ag1")])
    #expect(store.view(thread).loadingOlder)
    open.finish()
    #expect(await until { store.view(thread).entries.map(\.seq) == [4, 5] })
    #expect(!store.view(thread).hasMore && !store.view(thread).loadingOlder)
    store.loadOlder(thread)
    #expect(wire.requests.count == 1, "nothing more to load")
}

@Test @MainActor func aLostPageLetsPagingResume() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([said(5, "x")], hasMore: true))
    wire.reply = { _ in throw CompanionError.timedOut }
    store.loadOlder(main)
    #expect(await until { wire.requests.count == 1 && !store.view(main).loadingOlder })
    store.loadOlder(main)
    #expect(await until { wire.requests.count == 2 })
}

@Test @MainActor func sendsAMessageAsAPendingBubble() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([]))
    wire.clock = 42
    await store.send(paneId: "p", text: "  hi  ")
    await store.send(paneId: "p", text: "   ")
    #expect(wire.requests == [.chatSend(paneId: "p", text: "hi")])
    #expect(store.view(main).pending.map(\.text) == ["hi"])
    #expect(store.view(main).pending.first?.sentAt == 42 && store.view(main).pending.first?.status == .queued)

    wire.reply = { _ in throw CompanionError.timedOut }
    await store.send(paneId: "p", text: "late")
    #expect(store.view(main).pending.last?.status == .queued, "it may still have been queued")

    wire.reply = { _ in throw CompanionError.rejected("no_mod") }
    await store.send(paneId: "p", text: "nope")
    #expect(store.view(main).pending.last?.status == .failed && store.view(main).pending.last?.error == "no_mod")
}

@Test @MainActor func retrySendsAFailedMessageAgain() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([]))
    wire.reply = { _ in throw CompanionError.rejected("outbox_full") }
    await store.send(paneId: "p", text: "again")
    let failed = store.view(main).pending[0].id
    wire.reply = { _ in .chatSendResult(reqId: "", ok: true, error: nil) }
    await store.retry(paneId: "p", pendingId: failed)
    #expect(wire.requests.count == 2)
    #expect(store.view(main).pending.map(\.status) == [.queued])
    await store.retry(paneId: "p", pendingId: store.view(main).pending[0].id)
    #expect(wire.requests.count == 2, "a queued message isn't retried")
}

@Test @MainActor func expiresQueuedMessages() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([]))
    await store.send(paneId: "p", text: "hi")
    wire.clock = 120_000
    store.expirePending()
    #expect(store.view(main).pending.first?.status == .notDelivered)
}

@Test @MainActor func answersAQuestionOnce() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([asked(1, "toolu_q")]))
    let (gate, open) = AsyncStream<Void>.makeStream()
    wire.reply = { _ in
        for await _ in gate {}
        return .chatAnswerResult(reqId: "", ok: true, error: nil)
    }
    Task { await store.answer(paneId: "p", toolUseId: "toolu_q", answers: ["Which DB?": "SQLite"]) }
    #expect(await until { wire.requests.count == 1 })
    #expect(wire.requests == [.chatAnswer(paneId: "p", toolUseId: "toolu_q", answers: ["Which DB?": "SQLite"])])
    #expect(store.view(main).answering == ["toolu_q"])
    Task { await store.answer(paneId: "p", toolUseId: "toolu_q", answers: ["Which DB?": "Postgres"]) }
    for _ in 0..<100 { await Task.yield() }
    #expect(wire.requests.count == 1, "one answer at a time")
    open.finish()
    for _ in 0..<100 { await Task.yield() }
    #expect(store.view(main).answering == ["toolu_q"], "held until its tool_result arrives")
    store.apply(event(result(2, "toolu_q")))
    #expect(store.view(main).answering.isEmpty && store.view(main).answered == ["toolu_q"])
    await store.answer(paneId: "p", toolUseId: "toolu_q", answers: ["Which DB?": "Postgres"])
    #expect(wire.requests.count == 1, "an answered question takes no answer")
}

@Test @MainActor func aFailedAnswerFreesTheQuestion() async {
    let wire = Wire()
    let store = wire.makeStore()
    store.apply(snapshot([asked(1, "toolu_q")]))
    wire.reply = { _ in throw CompanionError.rejected("no_question") }
    await store.answer(paneId: "p", toolUseId: "toolu_q", answers: ["Which DB?": "SQLite"])
    #expect(store.view(main).answering.isEmpty)
    #expect(store.error == "the question is no longer waiting")
    store.clearError()
    #expect(store.error == nil)
}

@Test @MainActor func interruptSendsEscape() async {
    let wire = Wire()
    let store = wire.makeStore()
    await store.interrupt(paneId: "p")
    #expect(wire.requests == [.sendKeys(paneId: "p", keys: "esc")])
    wire.reply = { _ in throw CompanionError.notConnected }
    await store.interrupt(paneId: "p")
    #expect(store.error == "not connected")
}

// MARK: Images

private let png = Data([0x89, 0x50, 0x4E, 0x47])

private func imageReply(_ id: String, data: Data? = png) -> ChatImage {
    ChatImage(paneId: "p", id: id, mediaType: data == nil ? nil : "image/png", data: data, missing: data == nil)
}

@Test @MainActor func fetchesImagesThreeAtATime() {
    let wire = Wire()
    let store = wire.makeStore()
    for id in ["i1", "i2", "i3", "i4"] { store.loadImage(paneId: "p", id: id) }
    store.loadImage(paneId: "p", id: "i1")
    #expect(wire.images == ["i1", "i2", "i3"])
    #expect(store.image(paneId: "p", id: "i4") == .loading)
    #expect(store.image(paneId: "p", id: "i9") == nil)

    store.apply(image: imageReply("i1"))
    #expect(store.image(paneId: "p", id: "i1") == .ready(png))
    #expect(wire.images == ["i1", "i2", "i3", "i4"], "a free slot takes the next")
    store.apply(image: imageReply("i1", data: Data([1])))
    #expect(store.image(paneId: "p", id: "i1") == .ready(png), "a late duplicate changes nothing")
}

@Test @MainActor func aMissingImageIsRetriedOnce() async {
    let wire = Wire()
    let store = wire.makeStore(imageRetry: .milliseconds(20))
    store.loadImage(paneId: "p", id: "i1")
    store.apply(image: imageReply("i1", data: nil))
    #expect(store.image(paneId: "p", id: "i1") == .loading)
    #expect(await until { wire.images == ["i1", "i1"] })
    store.apply(image: imageReply("i1", data: nil))
    #expect(store.image(paneId: "p", id: "i1") == .missing)
}

@Test @MainActor func anUnansweredImageCountsAsMissing() async {
    let wire = Wire()
    let store = wire.makeStore(imageReplyTimeout: .milliseconds(20), imageRetry: .milliseconds(20))
    store.loadImage(paneId: "p", id: "i1")
    #expect(await until { store.image(paneId: "p", id: "i1") == .missing })
    #expect(wire.images == ["i1", "i1"])
}

@Test @MainActor func aSnapshotOrAReconnectGivesImagesAnotherGo() async {
    let wire = Wire()
    let store = wire.makeStore(imageRetry: .milliseconds(1))
    store.loadImage(paneId: "p", id: "gone")
    store.apply(image: imageReply("gone", data: nil))
    #expect(await until { wire.images.count == 2 })
    store.apply(image: imageReply("gone", data: nil))
    store.loadImage(paneId: "q", id: "other")
    #expect(store.image(paneId: "p", id: "gone") == .missing)

    wire.messages = []
    store.apply(snapshot([], pane: "p"))
    #expect(store.image(paneId: "p", id: "gone") == .loading)
    #expect(wire.images == ["gone"])

    wire.messages = []
    store.apply(.welcome)
    #expect(Set(wire.images) == ["gone", "other"], "replies on the old socket never come")
}

@Test @MainActor func keepsTheNewest30Images() {
    let wire = Wire()
    let store = wire.makeStore()
    for i in 0..<31 {
        store.loadImage(paneId: "p", id: "i\(i)")
        store.apply(image: imageReply("i\(i)"))
    }
    #expect(store.image(paneId: "p", id: "i0") == nil)
    #expect(store.image(paneId: "p", id: "i1") == .ready(png))
    store.loadImage(paneId: "p", id: "i1") // a use makes it newest
    store.loadImage(paneId: "p", id: "i31")
    #expect(store.image(paneId: "p", id: "i1") == .ready(png))
    #expect(store.image(paneId: "p", id: "i2") == nil)
}
