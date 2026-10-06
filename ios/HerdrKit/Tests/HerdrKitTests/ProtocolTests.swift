import Foundation
import Testing
@testable import HerdrKit

// MARK: Golden frames from the companion's Go tests

private let repo = URL(filePath: #filePath)
    .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
    .deletingLastPathComponent().deletingLastPathComponent()

/// One frame from every builder in the companion's `proto` package.
private let everyFrame = repo.appending(path: "companion/internal/proto/testdata/frames.json")

/// What the fake companion sends on connect for each e2e scenario.
private func scenarioFrameFiles() throws -> [URL] {
    let dir = repo.appending(path: "e2e/scenarios")
    return try FileManager.default.contentsOfDirectory(atPath: dir.path())
        .filter { $0.hasSuffix(".frames.json") }
        .map { dir.appending(path: $0) }
}

private func frames(in file: URL) throws -> [ServerFrame] {
    try JSONDecoder().decode([ServerFrame].self, from: Data(contentsOf: file))
}

private func frame(_ json: String) throws -> ServerFrame {
    try ServerFrame(json: Data(json.utf8))
}

@Test func decodesEveryFrameInTheGoldenFiles() throws {
    let files = try [everyFrame] + scenarioFrameFiles()
    #expect(files.count >= 2)
    for file in files {
        let decoded = try frames(in: file)
        #expect(!decoded.isEmpty, "\(file.lastPathComponent)")
        for f in decoded {
            if case .unknown(let t) = f { Issue.record("\(file.lastPathComponent): unknown frame \(t)") }
        }
    }
}

@Test func decodesTheScenarioStateTheFakeSends() throws {
    let decoded = try frames(in: repo.appending(path: "e2e/scenarios/one-idle-pane.frames.json"))
    #expect(decoded.first == .welcome)
    #expect(decoded.contains(.panes([Pane(
        paneId: "w1:p1", workspaceId: "w1", tabId: "w1:t1", terminalId: "term_1", cwd: "/Users/dev/herdr",
        focused: true, agent: "claude", agentStatus: .idle, chat: false, activity: nil, ask: nil, bgRunning: 0)])))
}

@Test func decodesAPaneWithItsActivityAndPendingQuestion() throws {
    let all = try frames(in: everyFrame)
    let panes = all.compactMap { f -> [Pane]? in if case .panes(let p) = f { p } else { nil } }.first
    let pane = try #require(panes?.first)
    #expect(pane.agentStatus == .blocked && pane.chat && pane.bgRunning == 2)
    #expect(pane.activity == PaneActivity(kind: .tool, tool: "Bash", text: "Bash: go test ./...", ts: 1_791_279_000_000))
    let ask = try #require(pane.ask)
    #expect(ask.toolUseId == "toolu_ask")
    #expect(ask.questions.map(\.kind) == [.choice, .number, .text])
    #expect(ask.questions[0].options == [QuestionOption(label: "Postgres", description: "relational"), QuestionOption(label: "SQLite", description: nil)])
    #expect(ask.questions[1].min == 1 && ask.questions[1].max == 8 && ask.questions[1].unit == "workers")
    #expect(ask.questions[2].placeholder == "feat/…")

    let shell = try #require(panes?.last)
    #expect(shell.agent == nil && shell.agentStatus == nil && !shell.chat && shell.activity == nil && shell.bgRunning == 0)
}

@Test func decodesWorkspacesTabsAndReplies() throws {
    let all = try frames(in: everyFrame)
    let workspaces = all.compactMap { f -> [Workspace]? in if case .workspaces(let w) = f { w } else { nil } }.first
    #expect(workspaces?.first?.worktree == Worktree(repoName: "herdr", isLinkedWorktree: true))
    #expect(workspaces?.first?.lastActivity == 1_791_279_000_000)
    #expect(workspaces?.last?.worktree == nil)
    #expect(all.contains(.tabs([Tab(tabId: "w1:t1", label: "1", number: 1, workspaceId: "w1", agentStatus: .blocked, focused: true, paneCount: 1)])))
    #expect(all.contains(.paneRemoved(paneId: "w2:p1")))
    #expect(all.contains(.paneRead(reqId: "r1", paneId: "w1:p1", source: "detection", text: "$ go test ./...\nok")))
    #expect(all.contains(.ack(reqId: "r2")))
    #expect(all.contains(.error(reqId: "r3", code: "read_failed", message: "pane not found")))
    #expect(all.contains(.actionResult(reqId: "a4", ok: true, error: nil)))
    #expect(all.contains(.actionResult(reqId: "a5", ok: false, error: "unknown kind: window")))
    #expect(all.contains(.created(reqId: "n6", ok: true, paneId: "w1:p2", terminalId: "term_3", error: nil)))
    #expect(all.contains(.created(reqId: "n7", ok: false, paneId: nil, terminalId: nil, error: "unknown what: window")))
    #expect(all.contains(.agents(reqId: "g8", names: ["claude", "codex"])))
    #expect(all.contains(.closeImpact(reqId: "i9", workspaceId: "w1", alsoCloses: [AlsoClose(workspaceId: "w3", label: "herdr-feature")])))
    #expect(all.contains(.pong))
    #expect(all.contains(.termOpened(reqId: "r10", termId: "t1")))
    #expect(all.contains(.termData(termId: "t1", data: Data("hello".utf8))))
    #expect(all.contains(.termExit(termId: "t1", code: 0, reason: "ended")))
    #expect(all.contains(.termError(reqId: "r11", termId: "", message: "invalid target")))
}

@Test func decodesTheMainChatSnapshot() throws {
    let all = try frames(in: everyFrame)
    let snapshot = try #require(all.compactMap { f -> ChatSnapshot? in
        if case .chatSnapshot(let s) = f, s.agentId == nil { s } else { nil }
    }.first)
    #expect(snapshot.paneId == "w1:p1" && snapshot.epoch == 2 && snapshot.state == .working && snapshot.hasMore)
    // seq 7 is an event type this app doesn't know: left out, but still counted in maxSeq.
    #expect(snapshot.entries.map(\.seq) == [1, 2, 3, 4, 5, 6])
    #expect(snapshot.maxSeq == 7)
    #expect(snapshot.entries[0].event == .userText(
        uuid: "u1", text: "look at this", ts: 1_791_279_000_000, images: ["img1"], imageSizes: ["img1": PixelSize(width: 640, height: 480)]))
    #expect(snapshot.entries[1].event == .assistantText(uuid: "u2", text: "On it.", ts: 1_791_279_001_000))
    #expect(snapshot.entries[2].event == .toolUse(uuid: "u3", toolUseId: "toolu_1", tool: "Bash", summary: "Bash: go test ./...", ts: 1_791_279_002_000))
    #expect(snapshot.entries[3].event == .toolResult(
        toolUseId: "toolu_1", isError: true, preview: "FAIL", images: ["img2"], ts: 1_791_279_003_000, imageSizes: ["img2": PixelSize(width: 100, height: 50)]))
    #expect(snapshot.entries[4].event == .taskNotice(uuid: "u5", status: "completed", summary: #"Agent "review" finished"#, ts: 1_791_279_004_000))
    guard case .question(let uuid, let toolUseId, let questions, _) = snapshot.entries[5].event else {
        Issue.record("not a question: \(snapshot.entries[5].event)")
        return
    }
    #expect(uuid == "toolu_ask" && toolUseId == "toolu_ask" && questions.count == 3)
    #expect(snapshot.agents == [AgentSummary(
        agentId: "ag1", parentToolUseId: "toolu_2", parentAgentId: nil, kind: .subagent, label: "review", type: "code-reviewer",
        phase: nil, status: .running, activity: PaneActivity(kind: .tool, tool: "Read", text: "Read: main.go", ts: 1_791_279_006_000),
        ts: 1_791_279_004_000)])
    #expect(snapshot.tasks == [
        BgTask(id: "bg1", kind: .subagent, label: "review", toolUseId: "toolu_2", status: .running, startedAt: 1_791_279_004_000, endedAt: nil),
        BgTask(id: "bg2", kind: .shell, label: "npm run dev", toolUseId: "toolu_3", status: .done, startedAt: 1_791_279_000_000, endedAt: 1_791_279_007_000),
    ])
}

@Test func decodesThreadsAndLiveChatFrames() throws {
    let all = try frames(in: everyFrame)
    let threads = all.compactMap { f -> ChatSnapshot? in if case .chatSnapshot(let s) = f, s.agentId != nil { s } else { nil } }
    #expect(threads.map(\.agentId) == ["ag1", "ag9"])
    #expect(threads.map(\.missing) == [false, true])
    #expect(threads.allSatisfy { $0.agents == nil && $0.tasks == nil })

    #expect(all.contains(.chatEvent(paneId: "w1:p1", epoch: 2, seq: 2, entry: ChatEntry(seq: 2, event: .assistantText(uuid: "u2", text: "On it.", ts: 1_791_279_001_000)), agentId: nil)))
    #expect(all.contains { if case .chatEvent(_, _, 3, .some, "ag1") = $0 { true } else { false } })
    #expect(all.contains(.chatEvent(paneId: "w1:p1", epoch: 2, seq: 7, entry: nil, agentId: nil)))
    #expect(all.contains { if case .chatAgent("w1:p1", .some(let a), nil) = $0 { a.agentId == "ag1" } else { false } })
    #expect(all.contains(.chatAgent(paneId: "w1:p1", agent: nil, removedId: "ag1")))
    #expect(all.contains { if case .chatTasks("w1:p1", let tasks) = $0 { tasks.map(\.id) == ["bg1", "bg2"] } else { false } })
    #expect(all.contains(.chatState(paneId: "w1:p1", state: .idle)))
    #expect(all.contains(.chatSendResult(reqId: "c12", ok: true, error: nil)))
    #expect(all.contains(.chatSendResult(reqId: "c13", ok: false, error: "no_mod")))
    #expect(all.contains { if case .chatHistoryPage(let p) = $0 { p.reqId == "h14" && p.entries.count == 2 && p.hasMore && !p.stale } else { false } })
    #expect(all.contains(.chatHistoryPage(ChatHistoryPage(reqId: "h15", paneId: "w1:p1", epoch: 1, entries: [], hasMore: false, stale: true, agentId: "ag1"))))
    #expect(all.contains(.chatImage(ChatImage(paneId: "w1:p1", id: "img1", mediaType: "image/png", data: Data(base64Encoded: "iVBORw0KGgo="), missing: false))))
    #expect(all.contains(.chatImage(ChatImage(paneId: "w1:p1", id: "img2", mediaType: nil, data: nil, missing: true))))
    #expect(all.contains(.chatAnswerResult(reqId: "q16", ok: true, error: nil)))
    #expect(all.contains(.chatAnswerResult(reqId: "q17", ok: false, error: "no_question")))
}

// MARK: Lenient parsing, as on Android

@Test func keepsTheTypeOfAnUnknownFrame() throws {
    #expect(try frame(#"{"t":"from_a_newer_companion","x":1}"#) == .unknown("from_a_newer_companion"))
}

@Test(arguments: ["not json", #"{"panes":[]}"#, "[]"])
func rejectsFramesWithoutAType(_ text: String) {
    #expect(throws: (any Error).self) { try frame(text) }
}

@Test func fillsMissingPaneFieldsWithDefaults() throws {
    #expect(try frame(#"{"t":"pane_update","pane":{"paneId":"p"}}"#) == .paneUpdate(Pane(
        paneId: "p", workspaceId: "", tabId: "", terminalId: "", cwd: "", focused: false,
        agent: nil, agentStatus: nil, chat: false, activity: nil, ask: nil, bgRunning: 0)))
}

@Test func skipsChatPartsThisAppCannotRead() throws {
    let f = try frame(#"""
    {"t":"chat_snapshot","paneId":"p","epoch":1,"events":[
      {"seq":1,"event":{"type":"user_text","uuid":"u","text":"hi","imageSizes":{"a":[1],"b":[0,5],"c":[3,4]}}},
      {"seq":2,"event":{"type":"question","uuid":"q","toolUseId":"q","questions":[1,{"question":"?","header":"H","kind":"slider"}]}},
      "garbage"
    ],"agents":[{"label":"no id"},{"agentId":"","label":"empty id"}],"tasks":[{"label":"no id"}]}
    """#)
    guard case .chatSnapshot(let s) = f else {
        Issue.record("not a snapshot: \(f)")
        return
    }
    #expect(s.state == .idle)
    #expect(s.entries[0].event == .userText(uuid: "u", text: "hi", ts: nil, images: [], imageSizes: ["c": PixelSize(width: 3, height: 4)]))
    guard case .question(_, _, let questions, _) = s.entries[1].event else {
        Issue.record("not a question: \(s.entries[1].event)")
        return
    }
    #expect(questions.map(\.question) == ["?"])
    #expect(questions.first?.kind == .choice)
    #expect(s.maxSeq == 2)
    #expect(s.agents == [])
    #expect(s.tasks == [])
}

@Test func tellsAMissingAgentListFromAnEmptyOne() throws {
    guard case .chatSnapshot(let s) = try frame(#"{"t":"chat_snapshot","paneId":"p","epoch":1,"state":"idle","events":[]}"#) else {
        Issue.record("not a snapshot")
        return
    }
    #expect(s.agents == nil && s.tasks == nil)
}

// MARK: Fixed-value fields

@Test func readsFixedValueFieldsAsEnums() throws {
    let f = try frame(#"""
    {"t":"panes","panes":[
      {"paneId":"a","agent":"claude","agentStatus":"working","activity":{"kind":"notice","text":"x","ts":1}},
      {"paneId":"b","agentStatus":"unknown"}
    ]}
    """#)
    guard case .panes(let panes) = f else {
        Issue.record("not panes: \(f)")
        return
    }
    #expect(panes[0].agentStatus == .working && panes[0].activity?.kind == .notice)
    // herdr reports "unknown" for a pane without an agent.
    #expect(panes[1].agentStatus == .unknown("unknown"))
    #expect(AgentStatus(raw: "blocked") == .blocked && AgentStatus.blocked.raw == "blocked")
}

/// A value from a newer companion stays readable and never fails its frame,
/// so one odd pane can't drop the whole `panes` list.
@Test func keepsAValueThisAppDoesNotKnow() throws {
    let f = try frame(#"""
    {"t":"panes","panes":[{"paneId":"a","agent":"claude","agentStatus":"napping",
      "activity":{"kind":"thought","text":"hmm","ts":1}},{"paneId":"b","agentStatus":"idle"}]}
    """#)
    guard case .panes(let panes) = f else {
        Issue.record("not panes: \(f)")
        return
    }
    #expect(panes.map(\.agentStatus) == [.unknown("napping"), .idle])
    #expect(panes[0].activity?.kind == .unknown("thought"))
    #expect(AgentStatus.unknown("napping").raw == "napping")

    guard case .chatSnapshot(let s) = try frame(#"""
    {"t":"chat_snapshot","paneId":"p","epoch":1,"state":"dreaming","events":[],
     "agents":[{"agentId":"a1","kind":"swarm","status":"paused"}],"tasks":[{"id":"t1","kind":"cron","status":"queued"}]}
    """#) else {
        Issue.record("not a snapshot")
        return
    }
    #expect(s.state == .unknown("dreaming"))
    #expect(s.agents?.first?.kind == .unknown("swarm") && s.agents?.first?.status == .unknown("paused"))
    #expect(s.tasks?.first?.kind == .unknown("cron") && s.tasks?.first?.status == .unknown("queued"))
}

// MARK: Client frames

/// `json` with its keys sorted, so two encodings compare equal.
func canonical(_ json: String) throws -> String {
    let object = try JSONSerialization.jsonObject(with: Data(json.utf8))
    return String(decoding: try JSONSerialization.data(withJSONObject: object, options: .sortedKeys), as: UTF8.self)
}

@Test(arguments: [
    (ClientMessage.hello, #"{"t":"hello","client":"herdr-mobile","clientVersion":"1.0.0"}"#),
    (.ping, #"{"t":"ping"}"#),
    (.registerPush(endpoint: "https://push.example/abc"), #"{"t":"register_push","endpoint":"https://push.example/abc"}"#),
    (.chatOpen(paneId: "w1:p1"), #"{"t":"chat_open","paneId":"w1:p1"}"#),
    (.chatOpen(paneId: "w1:p1", agentId: "ag1"), #"{"t":"chat_open","paneId":"w1:p1","agentId":"ag1"}"#),
    (.chatClose(paneId: "w1:p1", agentId: "ag1"), #"{"t":"chat_close","paneId":"w1:p1","agentId":"ag1"}"#),
    (.chatImage(paneId: "w1:p1", id: "img1"), #"{"t":"chat_image","paneId":"w1:p1","id":"img1"}"#),
    (.termInput(termId: "t1", data: Data("ls\r".utf8)), #"{"t":"term_input","termId":"t1","data":"bHMN"}"#),
    (.termResize(termId: "t1", cols: 80, rows: 24), #"{"t":"term_resize","termId":"t1","cols":80,"rows":24}"#),
    (.termClose(termId: "t1"), #"{"t":"term_close","termId":"t1"}"#),
])
func encodesMessages(_ message: ClientMessage, _ json: String) throws {
    #expect(try canonical(message.json) == canonical(json))
}

@Test(arguments: [
    (ClientRequest.readPane(paneId: "w1:p1"), "r", #"{"t":"read_pane","paneId":"w1:p1","source":"detection","lines":40}"#),
    (.sendText(paneId: "w1:p1", text: "y"), "r", #"{"t":"send_text","paneId":"w1:p1","text":"y"}"#),
    (.sendKeys(paneId: "w1:p1", keys: "Enter"), "r", #"{"t":"send_keys","paneId":"w1:p1","keys":"Enter"}"#),
    (.action(op: .rename, kind: .pane, id: "w1:p1", label: "build"), "a", #"{"t":"action","op":"rename","kind":"pane","id":"w1:p1","label":"build"}"#),
    (.action(op: .close, kind: .tab, id: "w1:t1"), "a", #"{"t":"action","op":"close","kind":"tab","id":"w1:t1"}"#),
    (.create(what: .workspace), "n", #"{"t":"create","what":"workspace"}"#),
    (.create(what: .agent, workspaceId: "w1", tabId: "w1:t1", direction: .right, agentName: "claude", argv: ["claude", "--resume"]), "n",
     #"{"t":"create","what":"agent","workspaceId":"w1","tabId":"w1:t1","direction":"right","agentName":"claude","argv":["claude","--resume"]}"#),
    (.move(paneId: "w1:p1", dest: .tab, tabId: "w1:t2"), "v", #"{"t":"move","paneId":"w1:p1","dest":"tab","tabId":"w1:t2"}"#),
    (.chatSend(paneId: "w1:p1", text: "hi"), "c", #"{"t":"chat_send","paneId":"w1:p1","text":"hi"}"#),
    (.chatAnswer(paneId: "w1:p1", toolUseId: "toolu_ask", answers: ["DB": "Postgres"]), "q",
     #"{"t":"chat_answer","paneId":"w1:p1","toolUseId":"toolu_ask","answers":{"DB":"Postgres"}}"#),
    (.chatHistory(paneId: "w1:p1", epoch: 2, beforeSeq: 10, limit: 50, agentId: "ag1"), "h",
     #"{"t":"chat_history","paneId":"w1:p1","epoch":2,"beforeSeq":10,"limit":50,"agentId":"ag1"}"#),
    (.listAgents, "g", #"{"t":"list_agents"}"#),
    (.closeImpact(workspaceId: "w1"), "i", #"{"t":"close_impact","workspaceId":"w1"}"#),
    (.termOpen(target: "term_1", cols: 80, rows: 24), "r", #"{"t":"term_open","target":"term_1","cols":80,"rows":24}"#),
])
func encodesRequestsWithTheirReqId(_ request: ClientRequest, _ prefix: String, _ json: String) throws {
    #expect(request.reqIdPrefix == prefix)
    var withReqId = try JSONSerialization.jsonObject(with: Data(json.utf8)) as! [String: Any]
    withReqId["reqId"] = "\(prefix)7"
    let expected = String(decoding: try JSONSerialization.data(withJSONObject: withReqId), as: UTF8.self)
    #expect(try canonical(request.json(reqId: "\(prefix)7")) == canonical(expected))
}
