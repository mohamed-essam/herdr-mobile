import Foundation
@testable import HerdrKit

/// Polls `condition` until it holds; false if it doesn't within `wait`.
@MainActor
func until(within wait: Duration = .seconds(5), _ condition: () -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + wait
    while !condition() {
        guard ContinuousClock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return true
}

// Small builders for the dashboard tests: only the fields a test cares about.

func pane(
    _ id: String, ws: String = "w1", tab: String? = nil, agent: String? = "claude", status: AgentStatus? = .idle,
    cwd: String = "", terminal: String = "term", chat: Bool = false, ts: Int? = nil, activity: PaneActivity? = nil,
    ask: PaneAsk? = nil, bg: Int = 0, focused: Bool = false
) -> Pane {
    Pane(paneId: id, workspaceId: ws, tabId: tab ?? "\(ws):t1", terminalId: terminal, cwd: cwd, focused: focused,
         agent: agent, agentStatus: agent == nil ? .unknown("unknown") : status, chat: chat,
         activity: activity ?? ts.map { PaneActivity(kind: .text, tool: nil, text: "", ts: $0) }, ask: ask, bgRunning: bg)
}

func workspace(_ id: String, _ label: String = "", number: Int = 1, lastActivity: Int = 0, repo: String? = nil) -> Workspace {
    Workspace(workspaceId: id, label: label, number: number, agentStatus: nil, focused: false, paneCount: 0, tabCount: 0,
              lastActivity: lastActivity, worktree: repo.map { Worktree(repoName: $0, isLinkedWorktree: false) })
}

func tab(_ id: String, ws: String = "w1", number: Int = 1, label: String = "") -> Tab {
    Tab(tabId: id, label: label, number: number, workspaceId: ws, agentStatus: nil, focused: false, paneCount: 0)
}

func question(_ text: String = "Which DB?", options: [String] = ["Postgres", "SQLite"], kind: QuestionKind = .choice,
              multiSelect: Bool = false) -> QuestionItem {
    QuestionItem(question: text, header: "DB", kind: kind, options: options.map { QuestionOption(label: $0, description: nil) },
                 multiSelect: multiSelect, min: nil, max: nil, step: nil, unit: nil, placeholder: nil, description: nil)
}

func ask(_ toolUseId: String = "toolu_ask", _ questions: [QuestionItem] = [question()]) -> PaneAsk {
    PaneAsk(toolUseId: toolUseId, questions: questions)
}
