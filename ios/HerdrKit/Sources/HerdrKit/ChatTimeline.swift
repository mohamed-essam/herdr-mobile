import Foundation

// The chat timeline's rows and the display rules behind them: agent cards, the header
// status, background tasks, the thread stack and the link rule. Ported from Android's
// ChatTimeline.kt, AgentCards.kt, TasksStrip.kt, ThreadStack.kt and ChatMarkdown.kt.

/// Who a timeline row belongs to; a change of speaker starts a new block.
public enum Speaker: Equatable, Sendable { case user, agent, notice }

/// A tool call, as its `tool_use` event carries it.
public struct ToolCall: Equatable, Sendable {
    public let toolUseId: String
    public let tool: String
    public let summary: String
    public let ts: Int?
}

/// One row of the timeline. `head` marks the first row of a speaker's block: it carries
/// the gutter timestamp and the "you"/agent label.
public enum TimelineItem: Equatable, Sendable {
    /// A message, notice or question.
    case event(key: String, entry: ChatEntry, speaker: Speaker, head: Bool)
    /// Consecutive tool calls folded onto one rail, keyed by the first.
    case tools(key: String, calls: [ToolCall], head: Bool)
    /// A subagent or workflow launch, keyed by its call's id so it is stable across epochs and pages.
    case agentCard(key: String, call: ToolCall, head: Bool)

    public var key: String {
        switch self {
        case .event(let key, _, _, _), .tools(let key, _, _), .agentCard(let key, _, _): key
        }
    }

    public var head: Bool {
        switch self {
        case .event(_, _, _, let head), .tools(_, _, let head), .agentCard(_, _, let head): head
        }
    }

    public var speaker: Speaker {
        if case .event(_, _, let speaker, _) = self { speaker } else { .agent }
    }
}

private let agentTools: Set = ["Agent", "Workflow"]

extension ChatEntry {
    var toolCall: ToolCall? {
        if case .toolUse(_, let id, let tool, let summary, let ts) = event { ToolCall(toolUseId: id, tool: tool, summary: summary, ts: ts) } else { nil }
    }
}

/// The timeline rows for `entries`. Tool results are dropped (they show under their call),
/// as is an AskUserQuestion's own call (its question shows instead). Runs of tool calls
/// fold into one rail keyed by its first call, so appending keeps the row's key. An Agent
/// or Workflow call ends the rail and becomes a card, once per call id. Nothing folds
/// across a `pageStarts` seam, so prepending a page leaves the rows below it unchanged.
public func buildTimeline(entries: [ChatEntry], epoch: Int, pageStarts: Set<Int> = []) -> [TimelineItem] {
    let questions = Set(entries.compactMap(\.questionId))
    var out: [TimelineItem] = []
    var run: [ChatEntry] = []
    var prev: Speaker?
    var cards = Set<String>()
    var lastSeq = Int.min
    func head(_ s: Speaker) -> Bool {
        defer { prev = s }
        return s != prev || s == .notice
    }
    func flush() {
        guard let first = run.first else { return }
        out.append(.tools(key: "e\(epoch)-\(first.seq)", calls: run.compactMap(\.toolCall), head: head(.agent)))
        run = []
    }
    for e in entries {
        // The seam's own entry may be one that isn't shown (a tool result).
        if pageStarts.contains(where: { $0 > lastSeq && $0 <= e.seq }) {
            flush()
            prev = nil
        }
        lastSeq = e.seq
        if e.resultId != nil { continue }
        if let c = e.toolCall {
            if agentTools.contains(c.tool) {
                guard cards.insert(c.toolUseId).inserted else { continue }
                flush()
                out.append(.agentCard(key: "a:\(c.toolUseId)", call: c, head: head(.agent)))
            } else if !questions.contains(c.toolUseId) {
                run.append(e)
            }
            continue
        }
        flush()
        let s: Speaker = switch e.event {
        case .userText, .commandOutput: .user
        case .taskNotice: .notice
        default: .agent
        }
        out.append(.event(key: "e\(epoch)-\(e.seq)", entry: e, speaker: s, head: head(s)))
    }
    flush()
    return out
}

/// The tool calls still running: those without a result in the newest row, while the
/// agent is working. Older calls without one were cut off.
public func runningTools(_ items: [TimelineItem], resultIds: Set<String>, working: Bool) -> Set<String> {
    guard working, case .tools(_, let calls, _)? = items.last else { return [] }
    return Set(calls.map(\.toolUseId).filter { !resultIds.contains($0) })
}

/// The question waiting on the user: the newest unanswered AskUserQuestion with no
/// message after it (a later message means the agent moved on).
public func pendingQuestion(_ entries: [ChatEntry], answered: Set<String>) -> PendingQuestion? {
    for e in entries.reversed() {
        switch e.event {
        case .question(_, let id, let questions, _): return answered.contains(id) ? nil : PendingQuestion(toolUseId: id, questions: questions)
        case .userText, .assistantText: return nil
        default: continue
        }
    }
    return nil
}

public struct PendingQuestion: Equatable, Sendable {
    public let toolUseId: String
    public let questions: [QuestionItem]
}

public enum ChatStatusKind: Equatable, Sendable { case offline, waiting, working, idle }

/// The header's live status: `label` ends the breadcrumb, `kind` picks its color.
public struct ChatStatus: Equatable, Sendable {
    public let label: String
    public let kind: ChatStatusKind
}

public func chatStatus(connected: Bool, state: ChatState, questionPending: Bool) -> ChatStatus {
    if !connected { ChatStatus(label: "reconnecting…", kind: .offline) }
    else if questionPending { ChatStatus(label: "waiting on you", kind: .waiting) }
    else if state == .working { ChatStatus(label: "working", kind: .working) }
    else { ChatStatus(label: "idle", kind: .idle) }
}

/// `HH:mm` when `ms` falls on `now`'s day in `zone`, `MMM d HH:mm` otherwise.
public func formatTs(ms: Int, now: Int, zone: TimeZone = .current, locale: Locale = .current) -> String {
    var calendar = Calendar(identifier: .gregorian)
    calendar.timeZone = zone
    let t = Date(timeIntervalSince1970: Double(ms) / 1000)
    let f = DateFormatter()
    f.timeZone = zone
    f.locale = locale
    f.dateFormat = calendar.isDate(t, inSameDayAs: Date(timeIntervalSince1970: Double(now) / 1000)) ? "HH:mm" : "MMM d HH:mm"
    return f.string(from: t)
}

/// "⚙ tests passed": a background task's notice as one line.
public func taskNoticeLabel(_ e: ChatEvent) -> String {
    guard case .taskNotice(_, let status, let summary, _) = e else { return "" }
    let s = summary.trimmed, st = status.trimmed
    return !s.isEmpty ? "⚙ \(s)" : !st.isEmpty ? "⚙ background task \(st)" : "⚙ background task"
}

public func taskNoticeIsError(_ e: ChatEvent) -> Bool {
    guard case .taskNotice(_, let status, _, _) = e else { return false }
    return status == "failed" || status == "killed"
}

// MARK: Agent cards

/// Where an agent card stands. A card with no summary takes it from its call's result.
public enum CardStatus: Equatable, Sendable {
    case running, done, failed, launched, unknown

    public init(_ s: RunStatus) {
        self = switch s {
        case .running: .running
        case .done: .done
        case .failed: .failed
        case .unknown: .unknown
        }
    }

    /// Still going, so elapsed time counts to now.
    public var live: Bool { self == .running || self == .launched }
}

/// The agents launched by the call `toolUseId`, oldest first.
public func agentsFor(toolUseId: String, agents: [String: AgentSummary]) -> [AgentSummary] {
    agents.values.filter { $0.parentToolUseId == toolUseId }.sorted { ($0.ts, $0.agentId) < ($1.ts, $1.agentId) }
}

/// `agents` grouped by phase, in the order each phase is first seen.
public func workflowGroups(_ agents: [AgentSummary]) -> [(phase: String?, agents: [AgentSummary])] {
    var out: [(phase: String?, agents: [AgentSummary])] = []
    for a in agents {
        if let i = out.firstIndex(where: { $0.phase == a.phase }) { out[i].agents.append(a) } else { out.append((a.phase, [a])) }
    }
    return out
}

/// Rows a workflow card shows before "+N more".
public let workflowRows = 6

private let launchedMarks = ["async_launched", "Async agent launched", "launched in background"]

/// A card's status while no summary exists, from its call's result: failed (an error),
/// launched (a background launch), done (any other result); with no result, running while
/// the pane works, else unknown.
public func fallbackStatus(result: ChatEvent?, working: Bool) -> CardStatus {
    guard let result, let preview = result.preview else { return working ? .running : .unknown }
    if result.isError { return .failed }
    return launchedMarks.contains { preview.contains($0) } ? .launched : .done
}

/// The activity line of a card with no summary.
public func fallbackActivity(_ s: CardStatus) -> String {
    switch s {
    case .launched: "running in background"
    case .running: "starting…"
    default: ""
    }
}

/// A workflow's overall status: running while any agent runs, else failed if any failed.
public func workflowStatus(_ agents: [AgentSummary], result: ChatEvent?, working: Bool) -> CardStatus {
    if agents.isEmpty { return fallbackStatus(result: result, working: working) }
    if agents.contains(where: { $0.status == .running }) { return .running }
    return agents.contains { $0.status == .failed } ? .failed : .done
}

/// A workflow card's title: its task's label (the workflow's name), else "Workflow".
public func workflowTitle(toolUseId: String, tasks: [BgTask]) -> String {
    tasks.first { $0.kind == .workflow && $0.toolUseId == toolUseId && !$0.label.isEmpty }?.label ?? "Workflow"
}

/// "12s", "4m", "2h": how long something ran, between two epoch ms.
public func elapsedLabel(from: Int, to: Int) -> String {
    let s = max(0, (to - from) / 1000)
    return s < 60 ? "\(s)s" : s < 3600 ? "\(s / 60)m" : "\(s / 3600)h"
}

// MARK: Background tasks

public struct TaskCounts: Equatable, Sendable {
    public let running: Int
    public let done: Int
    public let failed: Int
}

extension TaskCounts {
    public init(_ tasks: [BgTask]) {
        running = tasks.count { $0.status == .running }
        done = tasks.count { $0.status == .done }
        failed = tasks.count { $0.status == .failed }
    }
}

/// "⟳ 2 running · 1 done · 1 failed", zero parts left out; nil when all are zero.
public func stripLabel(_ c: TaskCounts) -> String? {
    let parts = [(c.running, "running"), (c.done, "done"), (c.failed, "failed")].filter { $0.0 > 0 }.map { "\($0.0) \($0.1)" }
    return parts.isEmpty ? nil : "⟳ " + parts.joined(separator: " · ")
}

/// "4s", "2m 05s", "1h 03m". A running task (or one with no end) counts to `now`.
public func taskDuration(_ t: BgTask, now: Int) -> String {
    let end = t.status == .running ? now : t.endedAt ?? now
    let s = max(0, (end - t.startedAt) / 1000)
    func two(_ n: Int) -> String { n < 10 ? "0\(n)" : "\(n)" }
    return s < 60 ? "\(s)s" : s < 3600 ? "\(s / 60)m \(two(s % 60))s" : "\(s / 3600)h \(two(s % 3600 / 60))m"
}

public func taskGlyph(_ kind: TaskKind) -> String {
    switch kind {
    case .shell: "$"
    case .subagent: "◆"
    case .workflow: "⧉"
    case .monitor: "◉"
    case .unknown: "•"
    }
}

extension BgTask {
    /// Subagent and workflow tasks have a card in the chat to jump to.
    public var hasCard: Bool { kind == .subagent || kind == .workflow }
    /// The whole command when the mod sent it, else the label.
    public var fullText: String { detail ?? label }
}

// MARK: Threads

/// The agent threads opened over a pane's chat, oldest first. Opening the thread already
/// on top doesn't stack it twice; back pops one level, never skipping to the chat.
public struct ThreadStack: Equatable, Sendable {
    public private(set) var ids: [String] = []

    public init() {}

    public var top: String? { ids.last }

    public mutating func push(_ id: String) { if top != id { ids.append(id) } }

    public mutating func pop() { _ = ids.popLast() }
}

// MARK: Links

/// Whether a link in assistant text may be opened: web and mail links only. The text is
/// model output, so tel:, file:, app deep links and the like are refused.
public func isOpenableLink(_ uri: String) -> Bool {
    let s = uri.trimmed
    return s.wholeMatch(of: /(?i)https?:\/\/[^\/?#\s]+\S*/) != nil || s.wholeMatch(of: /(?i)mailto:\S+/) != nil
}
