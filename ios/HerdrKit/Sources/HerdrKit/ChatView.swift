import Foundation

// One chat stream's state and the pure reducer that frames drive. Ported from
// Android's ChatModel.kt.

public enum PendingStatus: Equatable, Sendable { case queued, failed, notDelivered }

/// A message sent from the phone that no `user_text` has confirmed yet. Times are epoch ms.
public struct PendingMessage: Equatable, Sendable {
    public let id: String
    public let text: String
    public let sentAt: Int
    public internal(set) var status = PendingStatus.queued
    /// The companion's error code, or why the send failed.
    public internal(set) var error: String?
}

/// A pane's main chat or one agent's thread, as the reducer last left it.
public struct ChatView: Equatable, Sendable {
    public internal(set) var loaded = false
    public internal(set) var epoch = 0
    public internal(set) var state = ChatState.idle
    public internal(set) var entries: [ChatEntry] = []
    public internal(set) var lastSeq = 0
    public internal(set) var pending: [PendingMessage] = []
    /// When the view last went working → idle; queued messages time out counting from here.
    public internal(set) var idleSince = 0
    /// A seq gap was seen in this epoch; events wait for the fresh snapshot.
    public internal(set) var gap = false
    /// The companion has events older than the first entry.
    public internal(set) var hasMore = false
    /// A `chat_history` request is in flight.
    public var loadingOlder: Bool { olderToken != nil }
    /// The request in flight; only its page is accepted.
    var olderToken: String?
    /// toolUseIds of questions whose tool_result arrived.
    public internal(set) var answered: Set<String> = []
    /// toolUseIds answered from the phone with no tool_result yet.
    public internal(set) var answering: Set<String> = []
    /// Seqs where a page of older history was joined on; the timeline doesn't fold across
    /// them, so rows already on screen keep their keys.
    public internal(set) var pageStarts: Set<Int> = []
    /// The pane's subagents and workflows by agentId; kept on the main view only.
    public internal(set) var agents: [String: AgentSummary] = [:]
    /// The pane's background tasks (main view only).
    public internal(set) var tasks: [BgTask] = []
    /// The session's slash commands (main view only); empty: no picker.
    public internal(set) var commands: [SlashCommand] = []
    /// A thread's snapshot said the companion doesn't know that agent.
    public internal(set) var missing = false

    public init() {}
}

/// Queued messages time out this long after the agent last went idle.
let pendingTimeoutMs = 120_000
/// Matches the companion's ring, so paging back through it is never trimmed away.
let maxEntries = 5000
let historyPageLimit = 300

extension ChatView {
    /// Applies a frame; `now` (epoch ms) stamps a working → idle change. An event whose seq
    /// skips ahead, or of a newer epoch, sets `gap`: later events are ignored until a
    /// snapshot heals the view. History pages go through `applyPage`.
    mutating func apply(_ frame: ServerFrame, now: Int) {
        switch frame {
        case .chatSnapshot(let s):
            let all = dedupQuestions(s.entries)
            let kept = Array(all.suffix(maxEntries))
            let sameEpoch = loaded && s.epoch == epoch
            pending = confirm(pending, sameEpoch
                ? s.entries.filter { $0.seq > lastSeq }.compactMap(\.userText)
                : Array(s.entries.compactMap(\.userText).suffix(pending.count { $0.status != .failed })))
            // A /clear's echo goes with the old session: the new session's epoch is its delivery.
            if loaded && !sameEpoch { pending.removeAll { $0.status != .failed && startsNewSession($0.text) } }
            answering = sameEpoch ? answering.subtracting(resultIds(kept)) : []
            setState(s.state, now: now)
            loaded = true
            epoch = s.epoch
            entries = kept
            lastSeq = max(s.entries.map(\.seq).max() ?? 0, s.maxSeq)
            gap = false
            hasMore = s.hasMore || kept.count < all.count
            olderToken = nil
            answered = answeredIds(kept)
            pageStarts = []
            // A thread's snapshot sends neither: keep what the view has.
            if let a = s.agents { agents = Dictionary(a.map { ($0.agentId, $0) }, uniquingKeysWith: { $1 }) }
            if let t = s.tasks { tasks = t }
            if let c = s.commands { commands = c }
            missing = s.missing
        case .chatEvent(_, let e, let seq, let entry, _):
            if !loaded || gap { return }
            // The new epoch's snapshot was dropped: heal like a seq gap.
            if e > epoch { gap = true; return }
            if e != epoch || seq <= lastSeq { return }
            if seq != lastSeq + 1 { gap = true; return }
            // An event of a type this app doesn't know: not shown, but its seq counts.
            guard let entry else { lastSeq = seq; return }
            append(entry)
        case .chatState(_, let s): setState(s, now: now)
        case .chatAgent(_, let agent?, _): agents[agent.agentId] = agent
        case .chatAgent(_, nil, let removed?): agents[removed] = nil
        case .chatTasks(_, let t): tasks = t
        case .chatCommands(_, let c): commands = c
        default: break
        }
    }

    private mutating func append(_ e: ChatEntry) {
        lastSeq = e.seq
        // A question repeated after a resync is shown once, where it first appeared.
        if case .question(_, let id, _, _) = e.event, entries.contains(where: { $0.questionId == id }) { return }
        entries.append(e)
        if entries.count > maxEntries {
            entries.removeFirst(entries.count - maxEntries)
            hasMore = true
        }
        pending = confirm(pending, [e.userText].compactMap { $0 })
        switch e.event {
        case .toolResult(let id, _, _, _, _, _):
            answering.remove(id)
            if entries.contains(where: { $0.questionId == id }) { answered.insert(id) }
        case .question(_, let id, _, _):
            if entries.contains(where: { $0.resultId == id }) { answered.insert(id) }
        default: break
        }
    }

    /// Joins a page of older history on, if it answers the request `token` and ends right
    /// before the first entry.
    mutating func applyPage(_ page: ChatHistoryPage, token: String) {
        guard loaded, olderToken == token else { return }
        olderToken = nil
        let first = entries.first?.seq ?? lastSeq + 1
        guard !page.stale, page.epoch == epoch else { return }
        // A page must end right before the first entry, or it would leave a hole.
        if let last = page.entries.map(\.seq).max(), last != first - 1 { return }
        if !page.entries.isEmpty && !entries.isEmpty { pageStarts.insert(first) }
        entries = dedupQuestions(page.entries.filter { $0.seq < first }.sorted { $0.seq < $1.seq } + entries)
        hasMore = page.hasMore
        answered = answeredIds(entries)
        answering.subtract(resultIds(entries))
    }

    mutating func startLoadingOlder(token: String) { olderToken = token }

    /// The page for `token` never came: allow paging again (a later reply is ignored).
    mutating func olderTimedOut(token: String) {
        if olderToken == token { olderToken = nil }
    }

    mutating func markAnswering(_ toolUseId: String) { answering.insert(toolUseId) }
    mutating func clearAnswering(_ toolUseId: String) { answering.remove(toolUseId) }

    mutating func addPending(id: String, text: String, now: Int) {
        pending.append(PendingMessage(id: id, text: text.trimmed, sentAt: now))
    }

    mutating func failPending(id: String, error: String) {
        guard let i = pending.firstIndex(where: { $0.id == id }) else { return }
        pending[i].status = .failed
        pending[i].error = error
    }

    mutating func removePending(id: String) { pending.removeAll { $0.id == id } }

    /// The mod holds a message until the agent goes idle, so nothing expires while it
    /// works, and the clock restarts when it goes idle again.
    mutating func expire(now: Int) {
        guard state != .working else { return }
        for i in pending.indices where pending[i].status == .queued && now - max(pending[i].sentAt, idleSince) >= pendingTimeoutMs {
            pending[i].status = .notDelivered
        }
    }

    private mutating func setState(_ s: ChatState, now: Int) {
        if state == .working && s != .working { idleSince = now }
        state = s
    }
}

/// Each delivered text confirms the oldest unconfirmed bubble with the same text, so
/// sending "yes" twice needs two deliveries.
private func confirm(_ pending: [PendingMessage], _ texts: [String]) -> [PendingMessage] {
    var out = pending
    for t in texts {
        if let i = out.firstIndex(where: { $0.status != .failed && sameText($0.text, t) }) { out.remove(at: i) }
    }
    return out
}

/// A command comes back as `/name args`, however the space after its name was typed.
private func sameText(_ a: String, _ b: String) -> Bool {
    func spaced(_ s: String) -> String { s.replacing(/\s+/, with: " ", maxReplacements: 1) }
    return a == b || (a.hasPrefix("/") && b.hasPrefix("/") && spaced(a) == spaced(b))
}

private let newSessionCommands: Set = ["/clear", "/reset", "/new"]

/// A command that starts a new session.
private func startsNewSession(_ text: String) -> Bool {
    newSessionCommands.contains(String(text.prefix { !$0.isWhitespace }))
}

private func dedupQuestions(_ entries: [ChatEntry]) -> [ChatEntry] {
    var seen = Set<String>()
    return entries.filter { $0.questionId.map { seen.insert($0).inserted } ?? true }
}

private func resultIds(_ entries: [ChatEntry]) -> Set<String> { Set(entries.compactMap(\.resultId)) }

private func answeredIds(_ entries: [ChatEntry]) -> Set<String> {
    let results = resultIds(entries)
    return Set(entries.compactMap(\.questionId).filter(results.contains))
}

extension ChatEvent {
    /// Epoch ms, when the mod sent one.
    public var ts: Int? {
        switch self {
        case .userText(_, _, let ts, _, _), .assistantText(_, _, let ts), .toolUse(_, _, _, _, let ts),
             .toolResult(_, _, _, _, let ts, _), .taskNotice(_, _, _, let ts), .question(_, _, _, let ts),
             .commandOutput(_, _, _, _, let ts): ts
        }
    }
}

extension ChatEntry {
    var userText: String? {
        if case .userText(_, let text, _, _, _) = event { text.trimmed } else { nil }
    }
    /// A question's toolUseId.
    public var questionId: String? { if case .question(_, let id, _, _) = event { id } else { nil } }
    /// A tool result's toolUseId.
    public var resultId: String? { if case .toolResult(let id, _, _, _, _, _) = event { id } else { nil } }
}

extension ChatEvent {
    /// A tool result's preview.
    public var preview: String? { if case .toolResult(_, _, let preview, _, _, _) = self { preview } else { nil } }
    public var isError: Bool { if case .toolResult(_, true, _, _, _, _) = self { true } else { false } }
    public var imageCount: Int { if case .toolResult(_, _, _, let ids, _, _) = self { ids.count } else { 0 } }
}

/// The status line under a pending bubble.
public func pendingLabel(_ p: PendingMessage, state: ChatState) -> String {
    switch p.status {
    case .queued: state == .working ? "queued — Claude is busy" : "sending…"
    case .notDelivered: "not delivered"
    case .failed:
        switch p.error {
        case "no_mod": "Claude isn't connected"
        case "outbox_full": "too many queued messages"
        case "empty": "empty message"
        default: "failed: \(p.error ?? "error")"
        }
    }
}
