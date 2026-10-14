import Foundation

/// A string field the companion fills from a fixed set of values. A value this app
/// doesn't know decodes as `unknown(raw)`: a newer companion's value never fails its frame.
public protocol WireEnum: Hashable, Sendable, Decodable {
    /// Every value this app knows.
    static var known: [Self] { get }
    static func unknown(_ raw: String) -> Self
    /// The value as sent on the wire.
    var raw: String { get }
}

extension WireEnum {
    public init(raw: String) {
        self = Self.known.first { $0.raw == raw } ?? .unknown(raw)
    }

    public init(from decoder: any Decoder) throws {
        self.init(raw: try decoder.singleValueContainer().decode(String.self))
    }
}

/// What herdr says an agent is doing. herdr sends "unknown" for a pane without an agent.
public enum AgentStatus: WireEnum {
    case idle, working, blocked, done, unknown(String)

    public static let known: [Self] = [.idle, .working, .blocked, .done]
    public var raw: String {
        switch self {
        case .idle: "idle"
        case .working: "working"
        case .blocked: "blocked"
        case .done: "done"
        case .unknown(let raw): raw
        }
    }
}

/// What a pane's or an agent's latest chat event was.
public enum ActivityKind: WireEnum {
    case tool, text, user, question, notice, unknown(String)

    public static let known: [Self] = [.tool, .text, .user, .question, .notice]
    public var raw: String {
        switch self {
        case .tool: "tool"
        case .text: "text"
        case .user: "user"
        case .question: "question"
        case .notice: "notice"
        case .unknown(let raw): raw
        }
    }
}

/// Whether a pane's chat agent is busy.
public enum ChatState: WireEnum {
    case idle, working, unknown(String)

    public static let known: [Self] = [.idle, .working]
    public var raw: String {
        switch self {
        case .idle: "idle"
        case .working: "working"
        case .unknown(let raw): raw
        }
    }
}

/// A rate-limit window's span.
public enum LimitKind: WireEnum {
    case fiveHour, sevenDay, unknown(String)

    public static let known: [Self] = [.fiveHour, .sevenDay]
    public var raw: String {
        switch self {
        case .fiveHour: "five_hour"
        case .sevenDay: "seven_day"
        case .unknown(let raw): raw
        }
    }
}

public enum AgentKind: WireEnum {
    case subagent, workflow, unknown(String)

    public static let known: [Self] = [.subagent, .workflow]
    public var raw: String {
        switch self {
        case .subagent: "subagent"
        case .workflow: "workflow"
        case .unknown(let raw): raw
        }
    }
}

public enum TaskKind: WireEnum {
    case shell, subagent, workflow, monitor, unknown(String)

    public static let known: [Self] = [.shell, .subagent, .workflow, .monitor]
    public var raw: String {
        switch self {
        case .shell: "shell"
        case .subagent: "subagent"
        case .workflow: "workflow"
        case .monitor: "monitor"
        case .unknown(let raw): raw
        }
    }
}

/// Where a background task or a subagent stands.
public enum RunStatus: WireEnum {
    case running, done, failed, unknown(String)

    public static let known: [Self] = [.running, .done, .failed]
    public var raw: String {
        switch self {
        case .running: "running"
        case .done: "done"
        case .failed: "failed"
        case .unknown(let raw): raw
        }
    }
}

// MARK: Values the app sends

/// A structural node of herdr's tree, as `action` and the app's sheets name it.
public enum NodeKind: String, Sendable, Encodable {
    case workspace, tab, pane
}

public enum ActionOp: String, Sendable, Encodable {
    case rename, close
}

/// What a `create` request makes.
public enum CreateWhat: String, Sendable, Encodable {
    case workspace, tab, shell, agent
}

public enum MoveDest: String, Sendable, Encodable {
    case tab
    case newTab = "new_tab"
    case newWorkspace = "new_workspace"
}

public enum SplitDirection: String, Sendable, Encodable {
    case right, down
}
