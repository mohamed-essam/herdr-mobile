import Testing
@testable import HerdrKit

// Slash commands in the composer, their output in the timeline, drafts kept per pane,
// and a /clear delivered by the new session.

private func cmd(_ name: String) -> SlashCommand { SlashCommand(name: name, description: "", source: "builtin") }
private let commands = ["clear", "compact", "context", "review", "release-notes"].map(cmd)

private func output(_ seq: Int, _ command: String, _ text: String = "", error: Bool = false) -> ChatEntry {
    ChatEntry(seq: seq, event: .commandOutput(uuid: "c\(seq)", command: command, text: text, isError: error, ts: nil))
}

@Test func thePickerOpensOnlyWhileACommandNameIsTyped() {
    #expect(slashMatches("/", commands)?.map(\.name) == commands.map(\.name))
    #expect(slashMatches("hi", commands) == nil)
    #expect(slashMatches("", commands) == nil)
    #expect(slashMatches("/review ", commands) == nil, "past the name: arguments")
    #expect(slashMatches("/re\nview", commands) == nil)
    #expect(slashMatches("/", []) == nil, "no commands: no picker")
    #expect(slashMatches("/zzz", commands) == [], "typed, nothing matches")
}

@Test func namesStartingWithWhatIsTypedComeFirst() {
    #expect(slashMatches("/co", commands)?.map(\.name) == ["compact", "context"])
    // Then those that contain it, each group in the list's order; case is ignored.
    #expect(slashMatches("/ea", commands)?.map(\.name) == ["clear", "release-notes"])
    #expect(slashMatches("/E", commands)?.map(\.name) == ["clear", "context", "review", "release-notes"])
    #expect(slashMatches("/RE", commands)?.map(\.name) == ["review", "release-notes"])
}

@Test func theMainViewKeepsTheSessionsCommands() {
    var v = view(snapshot([], commands: commands))
    #expect(v.commands == commands)
    v.apply(snapshot([]), now: 0)
    #expect(v.commands == commands, "a snapshot without the list keeps it")
    v.apply(.chatCommands(paneId: "p", commands: [cmd("init")]), now: 0)
    #expect(v.commands == [cmd("init")])
}

@Test @MainActor func theStoreRoutesCommandsToThePanesMainView() {
    let store = ChatStore(send: { _ in }, request: { _ in .pong }, now: { 0 })
    store.apply(.chatCommands(paneId: "p", commands: commands))
    #expect(store.view(ChatKey(paneId: "p")).commands == commands)
    #expect(store.view(ChatKey(paneId: "p", agentId: "ag1")).commands.isEmpty)
}

@Test func aCommandsEchoConfirmsItHoweverItWasSpaced() {
    var v = view(snapshot([said(1, "hi")]))
    v.addPending(id: "p1", text: "/compact   keep the plan", now: 0)
    v.addPending(id: "p2", text: "/review\n42", now: 0)
    v.addPending(id: "p3", text: "hello  there", now: 0)
    v.apply(event(user(2, "/compact keep the plan")), now: 0)
    v.apply(event(user(3, "/review 42")), now: 0)
    v.apply(event(user(4, "hello there")), now: 0)
    #expect(v.pending.map(\.id) == ["p3"], "plain text still has to match exactly")
}

@Test func aNewSessionDeliversAClear() {
    var v = view(snapshot([said(1, "hi")]))
    for (i, text) in ["/clear", "/reset now", "/new", "/clearly", "hi"].enumerated() { v.addPending(id: "p\(i)", text: text, now: 0) }
    v.addPending(id: "failed", text: "/clear", now: 0)
    v.failPending(id: "failed", error: "no_mod")
    v.apply(snapshot([], epoch: 2), now: 0)
    #expect(v.pending.map(\.id) == ["p3", "p4", "failed"])
}

@Test func theFirstSnapshotDeliversNoClear() {
    var v = ChatView()
    v.addPending(id: "p1", text: "/clear", now: 0)
    v.apply(snapshot([], epoch: 2), now: 0)
    v.apply(snapshot([], epoch: 2), now: 0)
    #expect(v.pending.map(\.id) == ["p1"])
}

@Test func commandOutputSitsUnderItsEchoWithoutANewHeader() {
    let items = buildTimeline(entries: [said(1, "hi"), user(2, "/context"), output(3, "/context", "61%"), said(4, "ok")], epoch: 1)
    #expect(items.map(\.speaker) == [.agent, .user, .user, .agent])
    #expect(items.map(\.head) == [true, true, false, true])
}

@Test func aTaskShowsItsWholeCommandWhenTheModSentIt() {
    #expect(bgTask("b", label: "npm run dev …", detail: "npm run dev \\\n  --port 3000").fullText == "npm run dev \\\n  --port 3000")
    #expect(bgTask("b", label: "npm test").fullText == "npm test")
}

@Test @MainActor func draftsAreKeptPerPane() {
    let store = ChatStore(send: { _ in }, request: { _ in .pong }, now: { 0 })
    store.setDraft("a: half typed", paneId: "a")
    store.setDraft("/review ", paneId: "b")
    #expect(store.draft(paneId: "a") == "a: half typed")
    #expect(store.draft(paneId: "b") == "/review ")
    #expect(store.draft(paneId: "c") == "")
    store.setDraft("", paneId: "a")
    #expect(store.draft(paneId: "a") == "")
}
