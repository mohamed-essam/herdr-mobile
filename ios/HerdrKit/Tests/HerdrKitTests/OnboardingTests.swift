import Testing
@testable import HerdrKit

// MARK: Companion address

@Test(arguments: [
    ("100.101.7.12", "ws://100.101.7.12:8787", "100.101.7.12:8787", false, false),
    (" box.tailnet.ts.net ", "ws://box.tailnet.ts.net:8787", "box.tailnet.ts.net:8787", false, false),
    ("box:9000", "ws://box:9000", "box:9000", false, true),
    ("box:", "ws://box:8787", "box:8787", false, false),
    ("box/ws", "ws://box:8787/ws", "box:8787", false, false),
    ("fd7a::1", "ws://[fd7a::1]:8787", "[fd7a::1]:8787", false, false),
    ("[fd7a::1]:9000", "ws://[fd7a::1]:9000", "[fd7a::1]:9000", false, true),
    ("wss://box.ts.net/ws", "wss://box.ts.net/ws", "box.ts.net", true, false),
    ("WS://box:1", "WS://box:1", "box:1", true, true),
])
func readsACompanionAddress(_ input: String, _ url: String, _ hostPort: String, _ hasScheme: Bool, _ hasPort: Bool) throws {
    let a = try #require(CompanionAddress(parsing: input))
    #expect(a == CompanionAddress(url: url, hostPort: hostPort, hasScheme: hasScheme, hasPort: hasPort))
}

@Test(arguments: ["", "   ", "box name", "http://box", "ws://", "ws://:8787", ":8787"])
func rejectsWhatIsNotAnAddress(_ input: String) {
    #expect(CompanionAddress(parsing: input) == nil)
}

@Test func movesThroughTheSetupSteps() {
    #expect(connectStepStates(hostEntered: false) == [.done, .current, .upcoming])
    #expect(connectStepStates(hostEntered: true) == [.done, .done, .current])
}

@Test func theFirstConnectionTurnsSlowAfterTenSeconds() {
    #expect(FirstConnectPhase.slowAfter == .seconds(10))
    #expect(FirstConnectPhase(everConnected: false, timedOut: false) == .connecting)
    #expect(FirstConnectPhase(everConnected: false, timedOut: true) == .slow)
    #expect(FirstConnectPhase(everConnected: true, timedOut: true) == .connected)
}

@Test func summarisesTheHerd() {
    let repos = buildRepoTree(buildTree(
        workspaces: [workspace("w1", "a", repo: "x"), workspace("w2", "b", number: 2, repo: "y")],
        tabs: [tab("w1:t1"), tab("w2:t1", ws: "w2")],
        panes: [pane("w1:p1", status: .blocked), pane("w2:p1", ws: "w2", status: .working), pane("w2:p2", ws: "w2", agent: nil),
                pane("w9:p1", ws: "w9", status: .done)]))
    let s = HerdSummary(repos: repos)
    #expect(s == HerdSummary(repos: 2, workspaces: 2, panes: 4, needYou: 1, working: 1, done: 1))
    #expect(s.line == "2 repos · 2 workspaces · 4 panes")
    #expect(HerdSummary(repos: 1, workspaces: 1, panes: 1, needYou: 0, working: 0, done: 0).line == "1 repo · 1 workspace · 1 pane")
}

@Test func monogramsARepoName() {
    #expect(monogram("herdr-mobile") == "HE")
    #expect(monogram("x") == "X")
    #expect(monogram("-_-") == "?")
    #expect(monogram("9lives") == "9L")
}

// MARK: Agents

@Test func describesAndFiltersAgents() {
    #expect(describeAgent("Claude") == "Anthropic Claude Code")
    #expect(describeAgent("mystery") == nil)
    #expect(filterAgents(["claude", "codex", "gemini"], query: " CO ") == ["codex"])
    #expect(filterAgents(["claude", "codex"], query: "") == ["claude", "codex"])
}

@Test func splitsATypedAgentCommand() {
    #expect(AgentCommand(parsing: "  /usr/bin/claude   --model opus ") == AgentCommand(name: "claude", argv: ["/usr/bin/claude", "--model", "opus"]))
    #expect(AgentCommand(parsing: "   ") == AgentCommand(name: "", argv: []))
}

@Test func keepsFiveRecentAgentsNewestFirst() {
    #expect(updatedMru(["a", "b", "c"], picked: "b") == ["b", "a", "c"])
    #expect(updatedMru(["a", "b", "c", "d", "e"], picked: "f") == ["f", "a", "b", "c", "d"])
}
