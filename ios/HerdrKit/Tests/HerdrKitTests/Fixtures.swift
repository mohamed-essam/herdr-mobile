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
    ask: PaneAsk? = nil, bg: Int = 0, focused: Bool = false, context: PaneUsage? = nil
) -> Pane {
    Pane(paneId: id, workspaceId: ws, tabId: tab ?? "\(ws):t1", terminalId: terminal, cwd: cwd, focused: focused,
         agent: agent, agentStatus: agent == nil ? .unknown("unknown") : status, chat: chat,
         activity: activity ?? ts.map { PaneActivity(kind: .text, tool: nil, text: "", ts: $0) }, ask: ask, bgRunning: bg, context: context)
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

// Chat builders: one entry each, seq first.

func user(_ seq: Int, _ text: String, ts: Int? = nil, images: [String] = [], sizes: [String: PixelSize] = [:]) -> ChatEntry {
    ChatEntry(seq: seq, event: .userText(uuid: "u\(seq)", text: text, ts: ts, images: images, imageSizes: sizes))
}

func said(_ seq: Int, _ text: String, ts: Int? = nil) -> ChatEntry {
    ChatEntry(seq: seq, event: .assistantText(uuid: "a\(seq)", text: text, ts: ts))
}

func call(_ seq: Int, _ id: String, _ tool: String = "Bash", summary: String = "", ts: Int? = nil) -> ChatEntry {
    ChatEntry(seq: seq, event: .toolUse(uuid: "t\(seq)", toolUseId: id, tool: tool, summary: summary, ts: ts))
}

func result(_ seq: Int, _ id: String, error: Bool = false, preview: String = "", images: [String] = [], ts: Int? = nil) -> ChatEntry {
    ChatEntry(seq: seq, event: .toolResult(toolUseId: id, isError: error, preview: preview, images: images, ts: ts, imageSizes: [:]))
}

func asked(_ seq: Int, _ id: String, _ questions: [QuestionItem] = [question()], ts: Int? = nil) -> ChatEntry {
    ChatEntry(seq: seq, event: .question(uuid: "q\(seq)", toolUseId: id, questions: questions, ts: ts))
}

func notice(_ seq: Int, status: String = "completed", summary: String = "") -> ChatEntry {
    ChatEntry(seq: seq, event: .taskNotice(uuid: "n\(seq)", status: status, summary: summary, ts: nil))
}

func agentSummary(_ id: String, parent: String, kind: AgentKind = .subagent, label: String = "", type: String? = nil,
                  phase: String? = nil, status: RunStatus = .running, activity: PaneActivity? = nil, ts: Int = 0) -> AgentSummary {
    AgentSummary(agentId: id, parentToolUseId: parent, parentAgentId: nil, kind: kind, label: label, type: type, phase: phase,
                 status: status, activity: activity, ts: ts)
}

func bgTask(_ id: String, kind: TaskKind = .shell, status: RunStatus = .running, label: String = "", toolUseId: String = "",
            startedAt: Int = 0, endedAt: Int? = nil, detail: String? = nil, description: String? = nil) -> BgTask {
    BgTask(id: id, kind: kind, label: label, toolUseId: toolUseId, status: status, startedAt: startedAt, endedAt: endedAt,
           detail: detail, description: description)
}

func snapshot(_ entries: [ChatEntry], pane: String = "p", agentId: String? = nil, epoch: Int = 1, state: ChatState = .idle,
              hasMore: Bool = false, maxSeq: Int? = nil, missing: Bool = false, agents: [AgentSummary]? = nil,
              tasks: [BgTask]? = nil, commands: [SlashCommand]? = nil) -> ServerFrame {
    .chatSnapshot(ChatSnapshot(paneId: pane, epoch: epoch, state: state, entries: entries, hasMore: hasMore,
                               maxSeq: maxSeq ?? entries.map(\.seq).max() ?? 0, agentId: agentId, missing: missing,
                               agents: agents, tasks: tasks, commands: commands))
}

func event(_ entry: ChatEntry?, seq: Int? = nil, pane: String = "p", agentId: String? = nil, epoch: Int = 1) -> ServerFrame {
    .chatEvent(paneId: pane, epoch: epoch, seq: seq ?? entry?.seq ?? 0, entry: entry, agentId: agentId)
}

func page(_ entries: [ChatEntry], pane: String = "p", agentId: String? = nil, epoch: Int = 1, hasMore: Bool = false,
          stale: Bool = false) -> ChatHistoryPage {
    ChatHistoryPage(reqId: "h1", paneId: pane, epoch: epoch, entries: entries, hasMore: hasMore, stale: stale, agentId: agentId)
}

/// The chat view after `frames`, applied in order at `now`.
func view(_ frames: ServerFrame..., now: Int = 0) -> ChatView {
    var v = ChatView()
    for f in frames { v.apply(f, now: now) }
    return v
}
