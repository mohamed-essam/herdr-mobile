import Foundation

// The companion's state and chat types, decoded as leniently as the Android app
// does: a missing field takes Android's default, and a chat part this app can't
// read (an unknown event type, an agent without an id) is skipped, not fatal.

public struct Pane: Equatable, Sendable {
    public let paneId: String
    public let workspaceId: String
    public let tabId: String
    public let terminalId: String
    public let cwd: String
    public let focused: Bool
    public let agent: String?
    public let agentStatus: AgentStatus?
    /// The herdr-chat mod in this pane is syncing, so it has a chat view.
    public let chat: Bool
    /// What the agent last did; nil when no herdr-chat mod is live.
    public let activity: PaneActivity?
    /// The newest pending AskUserQuestion, answerable with `chat_answer`.
    public let ask: PaneAsk?
    /// Background tasks still running.
    public let bgRunning: Int
}

/// A one-line summary of a pane's latest chat event. `tool` is set only for
/// `.tool`. `ts` is epoch ms.
public struct PaneActivity: Equatable, Sendable {
    public let kind: ActivityKind
    public let tool: String?
    public let text: String
    public let ts: Int
}

public struct PaneAsk: Equatable, Sendable {
    public let toolUseId: String
    public let questions: [QuestionItem]
}

public struct Worktree: Equatable, Sendable {
    public let repoName: String?
    public let isLinkedWorktree: Bool
}

public struct Workspace: Equatable, Sendable {
    public let workspaceId: String
    public let label: String
    public let number: Int
    public let agentStatus: AgentStatus?
    public let focused: Bool
    public let paneCount: Int
    public let tabCount: Int
    /// Epoch ms.
    public let lastActivity: Int
    public let worktree: Worktree?
}

public struct Tab: Equatable, Sendable {
    public let tabId: String
    public let label: String
    public let number: Int
    public let workspaceId: String
    public let agentStatus: AgentStatus?
    public let focused: Bool
    public let paneCount: Int
}

/// A sibling workspace herdr closes along with the one asked about.
public struct AlsoClose: Equatable, Sendable {
    public let workspaceId: String
    public let label: String
}

public struct PixelSize: Equatable, Sendable {
    public let width: Int
    public let height: Int
}

/// One event of a pane's chat. `ts` is epoch ms, nil when the mod sent none. Image
/// fields hold ids to fetch with `chat_image`; `imageSizes` has the pixel size of
/// those whose header the mod could read, so their space is reserved up front.
public enum ChatEvent: Equatable, Sendable {
    case userText(uuid: String, text: String, ts: Int?, images: [String], imageSizes: [String: PixelSize])
    case assistantText(uuid: String, text: String, ts: Int?)
    case toolUse(uuid: String, toolUseId: String, tool: String, summary: String, ts: Int?)
    case toolResult(toolUseId: String, isError: Bool, preview: String, images: [String], ts: Int?, imageSizes: [String: PixelSize])
    /// A background task finished (Claude Code's task-notification).
    case taskNotice(uuid: String, status: String, summary: String, ts: Int?)
    /// An AskUserQuestion call; it may repeat (e.g. after a resync), so key it by `toolUseId`.
    case question(uuid: String, toolUseId: String, questions: [QuestionItem], ts: Int?)
}

public struct ChatEntry: Equatable, Sendable {
    public let seq: Int
    public let event: ChatEvent
}

public enum QuestionKind: Equatable, Sendable {
    case choice, text, number
}

public struct QuestionOption: Equatable, Sendable {
    public let label: String
    public let description: String?
}

/// One question of an AskUserQuestion call.
public struct QuestionItem: Equatable, Sendable {
    public let question: String
    public let header: String
    public let kind: QuestionKind
    public let options: [QuestionOption]
    public let multiSelect: Bool
    public let min: Double?
    public let max: Double?
    public let step: Double?
    public let unit: String?
    public let placeholder: String?
    public let description: String?
}

/// A subagent or workflow of a pane's chat. `parentToolUseId` links it to the
/// tool call that launched it, `parentAgentId` to the agent that did (nested).
public struct AgentSummary: Equatable, Sendable {
    public let agentId: String
    public let parentToolUseId: String
    public let parentAgentId: String?
    public let kind: AgentKind
    public let label: String
    public let type: String?
    public let phase: String?
    public let status: RunStatus
    public let activity: PaneActivity?
    public let ts: Int
}

/// A background task of a pane. Times are epoch ms.
public struct BgTask: Equatable, Sendable {
    public let id: String
    public let kind: TaskKind
    public let label: String
    public let toolUseId: String
    public let status: RunStatus
    public let startedAt: Int
    public let endedAt: Int?
}

/// A pane's chat, or (with `agentId`) one agent's thread of it.
public struct ChatSnapshot: Equatable, Sendable {
    public let paneId: String
    public let epoch: Int
    public let state: ChatState
    public let entries: [ChatEntry]
    public let hasMore: Bool
    /// The highest seq among all events, counting those left out of `entries`.
    public let maxSeq: Int
    public let agentId: String?
    /// The companion doesn't know this thread.
    public let missing: Bool
    /// Main snapshots only; nil means not sent, distinct from empty.
    public let agents: [AgentSummary]?
    public let tasks: [BgTask]?
}

/// A page of older chat entries. A `stale` page (the epoch moved on) is empty.
public struct ChatHistoryPage: Equatable, Sendable {
    public let reqId: String
    public let paneId: String
    public let epoch: Int
    public let entries: [ChatEntry]
    public let hasMore: Bool
    public let stale: Bool
    public let agentId: String?
}

/// A chat image's bytes; `data` is nil, with `missing`, when the companion doesn't have it.
public struct ChatImage: Equatable, Sendable {
    public let paneId: String
    public let id: String
    public let mediaType: String?
    public let data: Data?
    public let missing: Bool
}

// MARK: Decoding

extension Pane: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        paneId = try c.decode(String.self, forKey: "paneId")
        workspaceId = try c.value("workspaceId", or: "")
        tabId = try c.value("tabId", or: "")
        terminalId = try c.value("terminalId", or: "")
        cwd = try c.value("cwd", or: "")
        focused = try c.value("focused", or: false)
        agent = try c.value("agent")
        agentStatus = try c.value("agentStatus")
        chat = try c.value("chat", or: false)
        activity = try c.value("activity")
        ask = try c.value("ask")
        bgRunning = try c.value("bgRunning", or: 0)
    }
}

extension PaneActivity: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        kind = try c.value("kind", or: .unknown(""))
        tool = try c.value("tool")
        text = try c.value("text", or: "")
        ts = try c.value("ts", or: 0)
    }
}

extension PaneAsk: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        toolUseId = try c.value("toolUseId", or: "")
        questions = try c.lossy("questions") ?? []
    }
}

extension Worktree: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        repoName = try c.value("repoName")
        isLinkedWorktree = try c.value("isLinkedWorktree", or: false)
    }
}

extension Workspace: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        workspaceId = try c.decode(String.self, forKey: "workspaceId")
        label = try c.value("label", or: "")
        number = try c.value("number", or: 0)
        agentStatus = try c.value("agentStatus")
        focused = try c.value("focused", or: false)
        paneCount = try c.value("paneCount", or: 0)
        tabCount = try c.value("tabCount", or: 0)
        lastActivity = try c.value("lastActivity", or: 0)
        worktree = try c.value("worktree")
    }
}

extension Tab: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        tabId = try c.decode(String.self, forKey: "tabId")
        label = try c.value("label", or: "")
        number = try c.value("number", or: 0)
        workspaceId = try c.value("workspaceId", or: "")
        agentStatus = try c.value("agentStatus")
        focused = try c.value("focused", or: false)
        paneCount = try c.value("paneCount", or: 0)
    }
}

extension AlsoClose: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        workspaceId = try c.decode(String.self, forKey: "workspaceId")
        label = try c.value("label", or: "")
    }
}

extension ChatEvent: Decodable {
    /// Throws for an event type this app doesn't know.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        let ts: Int? = try c.value("ts")
        func string(_ key: JSONKey) throws -> String { try c.value(key, or: "") }
        func imageSizes() throws -> [String: PixelSize] {
            try c.value("imageSizes", or: [String: Lossy<[Int]>]()).compactMapValues {
                guard let wh = $0.value, wh.count == 2, wh[0] > 0, wh[1] > 0 else { return nil }
                return PixelSize(width: wh[0], height: wh[1])
            }
        }
        switch try string("type") {
        case "user_text":
            self = .userText(uuid: try string("uuid"), text: try string("text"), ts: ts,
                             images: try c.value("images", or: []), imageSizes: try imageSizes())
        case "assistant_text":
            self = .assistantText(uuid: try string("uuid"), text: try string("text"), ts: ts)
        case "tool_use":
            self = .toolUse(uuid: try string("uuid"), toolUseId: try string("toolUseId"), tool: try string("tool"),
                            summary: try string("summary"), ts: ts)
        case "tool_result":
            self = .toolResult(toolUseId: try string("toolUseId"), isError: try c.value("isError", or: false),
                               preview: try string("preview"), images: try c.value("images", or: []), ts: ts,
                               imageSizes: try imageSizes())
        case "task_notice":
            self = .taskNotice(uuid: try string("uuid"), status: try string("status"), summary: try string("summary"), ts: ts)
        case "question":
            self = .question(uuid: try string("uuid"), toolUseId: try string("toolUseId"),
                             questions: try c.lossy("questions") ?? [], ts: ts)
        case let type:
            throw DecodingError.dataCorruptedError(forKey: "type", in: c, debugDescription: "unknown chat event \(type)")
        }
    }
}

extension QuestionOption: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        label = try c.value("label", or: "")
        description = try c.value("description")
    }
}

extension QuestionItem: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        question = try c.value("question", or: "")
        header = try c.value("header", or: "")
        kind = switch try c.value("kind") as String? {
        case "text": .text
        case "number": .number
        default: .choice
        }
        options = try c.lossy("options") ?? []
        multiSelect = try c.value("multiSelect", or: false)
        min = try c.value("min")
        max = try c.value("max")
        step = try c.value("step")
        unit = try c.value("unit")
        placeholder = try c.value("placeholder")
        description = try c.value("description")
    }
}

extension AgentSummary: Decodable {
    /// Throws without an `agentId`, so a list skips it.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        agentId = try c.value("agentId", or: "")
        guard !agentId.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: "agentId", in: c, debugDescription: "agent without an id")
        }
        parentToolUseId = try c.value("parentToolUseId", or: "")
        parentAgentId = try c.value("parentAgentId")
        kind = try c.value("kind", or: .unknown(""))
        label = try c.value("label", or: "")
        type = try c.value("type")
        phase = try c.value("phase")
        status = try c.value("status", or: .unknown(""))
        activity = try? c.value("activity")
        ts = try c.value("ts", or: 0)
    }
}

extension BgTask: Decodable {
    /// Throws without an `id`, so a list skips it.
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        id = try c.value("id", or: "")
        guard !id.isEmpty else {
            throw DecodingError.dataCorruptedError(forKey: "id", in: c, debugDescription: "task without an id")
        }
        kind = try c.value("kind", or: .unknown(""))
        label = try c.value("label", or: "")
        toolUseId = try c.value("toolUseId", or: "")
        status = try c.value("status", or: .unknown(""))
        startedAt = try c.value("startedAt", or: 0)
        endedAt = try c.value("endedAt")
    }
}

/// One element of an `events` array, or a `chat_event` frame: its seq always, its
/// entry only when this app knows the event's type.
struct SeqEntry: Decodable {
    let seq: Int
    let entry: ChatEntry?

    init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        seq = try c.value("seq", or: 0)
        entry = (try? c.decode(ChatEvent.self, forKey: "event")).map { [seq] in ChatEntry(seq: seq, event: $0) }
    }
}

extension KeyedDecodingContainer<JSONKey> {
    /// The `events` array's entries this app can read, and the highest seq among all of them.
    func events() throws -> (entries: [ChatEntry], maxSeq: Int) {
        let all: [SeqEntry] = try lossy("events") ?? []
        return (all.compactMap(\.entry), all.map(\.seq).max() ?? 0)
    }
}

extension ChatSnapshot: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        paneId = try c.value("paneId", or: "")
        epoch = try c.value("epoch", or: 0)
        state = try c.value("state", or: .idle)
        (entries, maxSeq) = try c.events()
        hasMore = try c.value("hasMore", or: false)
        agentId = try c.value("agentId")
        missing = try c.value("missing", or: false)
        agents = try c.lossy("agents")
        tasks = try c.lossy("tasks")
    }
}

extension ChatHistoryPage: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        reqId = try c.value("reqId", or: "")
        paneId = try c.value("paneId", or: "")
        epoch = try c.value("epoch", or: 0)
        entries = try c.events().entries
        hasMore = try c.value("hasMore", or: false)
        stale = try c.value("stale", or: false)
        agentId = try c.value("agentId")
    }
}

extension ChatImage: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        paneId = try c.value("paneId", or: "")
        id = try c.value("id", or: "")
        mediaType = try c.value("mediaType")
        data = try c.value("data") // base64, JSONDecoder's default for Data
        missing = try c.value("missing", or: false)
    }
}

// MARK: JSON helpers

/// A coding key spelled as a string literal: `c.value("paneId", or: "")`.
struct JSONKey: CodingKey, ExpressibleByStringLiteral {
    let stringValue: String
    init(stringValue: String) { self.stringValue = stringValue }
    init(stringLiteral value: String) { stringValue = value }
    var intValue: Int? { nil }
    init?(intValue: Int) { nil }
}

/// An array element that decodes to nil instead of failing its whole array.
struct Lossy<T: Decodable>: Decodable {
    let value: T?
    init(from decoder: any Decoder) throws { value = try? T(from: decoder) }
}

extension KeyedDecodingContainer<JSONKey> {
    /// The value at `key`, or `fallback` when it is missing or null.
    func value<T: Decodable>(_ key: JSONKey, or fallback: T) throws -> T {
        try decodeIfPresent(T.self, forKey: key) ?? fallback
    }

    /// The value at `key`, or nil when it is missing or null.
    func value<T: Decodable>(_ key: JSONKey) throws -> T? {
        try decodeIfPresent(T.self, forKey: key)
    }

    /// The array at `key` without the elements that don't decode; nil when it is missing or null.
    func lossy<T: Decodable>(_ key: JSONKey) throws -> [T]? {
        try decodeIfPresent([Lossy<T>].self, forKey: key)?.compactMap(\.value)
    }
}
