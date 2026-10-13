import Testing
@testable import HerdrKit

/// Stands in for `CompanionClient.request`: records each request and answers with `reply`.
@MainActor private final class Requests {
    var sent: [ClientRequest] = []
    var reply: (ClientRequest) async throws -> ServerFrame = { _ in .actionResult(reqId: "", ok: true, error: nil) }

    func callAsFunction(_ request: ClientRequest) async throws -> ServerFrame {
        sent.append(request)
        return try await reply(request)
    }
}

@MainActor private func makeStore(recent: [String] = [], ttl: Duration = .seconds(4), saved: ((([String]) -> Void))? = nil)
    -> (DashboardStore, Requests) {
    let requests = Requests()
    let store = DashboardStore(recentAgents: recent, resumedTTL: ttl, saveRecentAgents: saved ?? { _ in },
                               request: { try await requests($0) })
    return (store, requests)
}

@Test @MainActor func keepsTheHerdFromFrames() {
    let (store, _) = makeStore()
    store.apply(.workspaces([workspace("w1", "api", repo: "shop")]))
    store.apply(.tabs([tab("w1:t1")]))
    store.apply(.panes([pane("w1:p2"), pane("w1:p1")]))
    #expect(store.panes.map(\.paneId) == ["w1:p1", "w1:p2"])
    #expect(store.repos.map(\.name) == ["shop"])
    #expect(store.tree.first?.panes.count == 2)

    store.apply(.paneUpdate(pane("w1:p1", status: .blocked)))
    store.apply(.paneUpdate(pane("w1:p3")))
    store.apply(.paneRemoved(paneId: "w1:p2"))
    #expect(store.panes.map(\.paneId) == ["w1:p1", "w1:p3"])
    #expect(store.panes.first?.agentStatus == .blocked)

    store.apply(.panes([pane("w2:p1", ws: "w2")]))
    #expect(store.panes.map(\.paneId) == ["w2:p1"], "a snapshot replaces every pane")
    store.apply(.ack(reqId: ""))
    #expect(store.panes.map(\.paneId) == ["w2:p1"])
}

@Test @MainActor func answersAQuestionWithAChip() async {
    let (store, requests) = makeStore(ttl: .milliseconds(100))
    let blocked = pane("w1:p1", status: .blocked, ask: ask("toolu_ask"))
    store.apply(.panes([blocked]))
    let (gate, open) = AsyncStream<Void>.makeStream()
    requests.reply = { _ in
        for await _ in gate {}
        return .chatAnswerResult(reqId: "q1", ok: true, error: nil)
    }

    let answering = Task { await store.answer(blocked, label: "SQLite") }
    #expect(await until { requests.sent.count == 1 })
    #expect(requests.sent == [.chatAnswer(paneId: "w1:p1", toolUseId: "toolu_ask", answers: ["Which DB?": "SQLite"])])
    #expect(store.answers["w1:p1"]?.phase == .sending)
    let second = Task { await store.answer(blocked, label: "Postgres") }
    for _ in 0..<100 { await Task.yield() }
    #expect(requests.sent.count == 1, "a second tap while sending sends nothing")

    open.finish()
    await answering.value
    await second.value
    #expect(store.answers.resumed.map(\.label) == ["SQLite"])
    #expect(store.sections.needsYou.isEmpty)
    #expect(await until { store.answers["w1:p1"] == nil }, "the resumed row goes after its TTL")
}

@Test @MainActor func aFailedAnswerReEnablesTheChips() async {
    let (store, requests) = makeStore()
    let blocked = pane("w1:p1", status: .blocked, ask: ask())
    requests.reply = { _ in throw CompanionError.rejected("no_question") }
    await store.answer(blocked, label: "SQLite")
    #expect(store.answers["w1:p1"] == nil)
    #expect(store.error == "the question is no longer waiting")
    store.clearError()
    #expect(store.error == nil)
}

@Test @MainActor func sendsEachStructuralAction() async {
    let (store, requests) = makeStore()
    let tree = buildTree(workspaces: [workspace("w1", "api")], tabs: [tab("w1:t1")], panes: [pane("w1:p1")])
    let ws = workspaceAction(tree[0])
    let t = tabAction(tree[0].tabs[0])
    let p = paneAction(tree[0].panes[0])

    await store.rename(p, to: "build")
    await store.close(t)
    await store.newWorkspace()
    await store.newTab(in: ws)
    await store.newShell(beside: t)
    await store.split(p, .right)
    await store.move(p, to: .newWorkspace)
    await store.move(p, toTab: "w1:t2")
    #expect(requests.sent == [
        .action(op: .rename, kind: .pane, id: "w1:p1", label: "build"),
        .action(op: .close, kind: .tab, id: "w1:t1"),
        .create(what: .workspace),
        .create(what: .tab, workspaceId: "w1"),
        .create(what: .shell, workspaceId: "w1"),
        .create(what: .shell, paneId: "w1:p1", direction: .right),
        .move(paneId: "w1:p1", dest: .newWorkspace),
        .move(paneId: "w1:p1", dest: .tab, tabId: "w1:t2", direction: .down),
    ])
    #expect(store.error == nil)
}

@Test @MainActor func startsAnAgentAndRemembersIt() async {
    var saved: [[String]] = []
    let (store, requests) = makeStore(recent: ["codex"], saved: { saved.append($0) })
    let tree = buildTree(workspaces: [workspace("w1", "api")], tabs: [tab("w1:t1")], panes: [])
    await store.startAgent(AgentCommand(name: "Claude", argv: ["claude"]), in: workspaceAction(tree[0]))
    await store.startAgent(AgentCommand(parsing: "aider --yes"), in: tabAction(tree[0].tabs[0]))
    #expect(requests.sent == [
        .create(what: .agent, workspaceId: "w1", agentName: "Claude", argv: ["claude"]),
        .create(what: .agent, tabId: "w1:t1", direction: .down, agentName: "aider", argv: ["aider", "--yes"]),
    ])
    #expect(store.recentAgents == ["aider", "claude", "codex"])
    #expect(saved.last == ["aider", "claude", "codex"])
}

@Test @MainActor func listsAgentsAndCloseImpact() async {
    let (store, requests) = makeStore()
    requests.reply = { request in
        switch request {
        case .listAgents: .agents(reqId: "g1", names: ["claude", "codex"])
        default: .closeImpact(reqId: "i2", workspaceId: "w1", alsoCloses: [AlsoClose(workspaceId: "w2", label: "api-feature")])
        }
    }
    await store.refreshAgents()
    #expect(store.agents == ["claude", "codex"])
    #expect(await store.closeImpact(workspaceId: "w1") == ["api-feature"])

    requests.reply = { _ in throw CompanionError.timedOut }
    #expect(await store.closeImpact(workspaceId: "w1") == [], "an error falls back to the plain confirm")
    await store.refreshAgents()
    #expect(store.agents == ["claude", "codex"])
}

@Test @MainActor func reportsAFailedAction() async {
    let (store, requests) = makeStore()
    requests.reply = { _ in throw CompanionError.rejected("invalid id") }
    await store.close(RowAction(kind: .pane, id: "x", label: "shell"))
    #expect(store.error == "invalid id")
    requests.reply = { _ in throw CompanionError.notConnected }
    await store.newWorkspace()
    #expect(store.error == "not connected")
}

@Test @MainActor func aCreatedPaneIsOfferedOnceItAppears() async {
    let (store, requests) = makeStore()
    requests.reply = { _ in .created(reqId: "n1", ok: true, paneId: "w2:p1", terminalId: "term_9", error: nil) }
    await store.newWorkspace()
    #expect(store.createdPane == nil, "not in panes yet")
    store.apply(.paneUpdate(pane("w2:p1", ws: "w2", terminal: "term_9")))
    #expect(store.createdPane?.paneId == "w2:p1")
    store.clearCreated()
    #expect(store.createdPane == nil)

    requests.reply = { _ in .created(reqId: "n2", ok: true, paneId: nil, terminalId: "", error: nil) }
    await store.newTab(in: RowAction(kind: .workspace, id: "w2", label: "app"))
    #expect(store.createdPane == nil, "a create without a terminal opens nothing")
}
