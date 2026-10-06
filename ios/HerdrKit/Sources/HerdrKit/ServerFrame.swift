import Foundation

/// A frame from the companion, keyed on its `t`. See companion/internal/proto.
public enum ServerFrame: Equatable, Sendable {
    case welcome
    case panes([Pane])
    case workspaces([Workspace])
    case tabs([Tab])
    case paneUpdate(Pane)
    case paneRemoved(paneId: String)
    case paneRead(reqId: String, paneId: String, source: String, text: String)
    case ack(reqId: String)
    case error(reqId: String, code: String, message: String)
    case actionResult(reqId: String, ok: Bool, error: String?)
    case created(reqId: String, ok: Bool, paneId: String?, terminalId: String?, error: String?)
    case agents(reqId: String, names: [String])
    case closeImpact(reqId: String, workspaceId: String, alsoCloses: [AlsoClose])
    case pong
    case termOpened(reqId: String, termId: String)
    case termData(termId: String, data: Data)
    case termExit(termId: String, code: Int, reason: String)
    case termError(reqId: String, termId: String, message: String)
    case chatSnapshot(ChatSnapshot)
    case chatHistoryPage(ChatHistoryPage)
    case chatImage(ChatImage)
    case chatAnswerResult(reqId: String, ok: Bool, error: String?)
    /// `entry` is nil for an event type this app doesn't know; `seq` is the frame's either way.
    case chatEvent(paneId: String, epoch: Int, seq: Int, entry: ChatEntry?, agentId: String?)
    /// An agent of the main stream was added or changed (`agent`), or dropped (`removedId`).
    case chatAgent(paneId: String, agent: AgentSummary?, removedId: String?)
    /// The pane's background tasks, replacing the previous list.
    case chatTasks(paneId: String, tasks: [BgTask])
    case chatState(paneId: String, state: String)
    case chatSendResult(reqId: String, ok: Bool, error: String?)
    /// A frame from a newer companion; the client ignores it.
    case unknown(String)

    /// Throws unless `json` is an object with a string `t` and the fields that frame needs.
    public init(json: Data) throws {
        self = try Self.decoder.decode(ServerFrame.self, from: json)
    }

    private static let decoder = JSONDecoder()

    /// The request this frame answers.
    var reqId: String? {
        switch self {
        case .paneRead(let id, _, _, _), .ack(let id), .error(let id, _, _), .actionResult(let id, _, _),
             .created(let id, _, _, _, _), .agents(let id, _), .closeImpact(let id, _, _), .termOpened(let id, _),
             .termError(let id, _, _), .chatAnswerResult(let id, _, _), .chatSendResult(let id, _, _):
            id
        case .chatHistoryPage(let page): page.reqId
        default: nil
        }
    }

    /// Why the companion turned the request down, for a reply that says so.
    var failure: String? {
        switch self {
        case .error(_, _, let message), .termError(_, _, let message): message
        case .actionResult(_, false, let error), .created(_, false, _, _, let error),
             .chatSendResult(_, false, let error), .chatAnswerResult(_, false, let error):
            error ?? "failed"
        default: nil
        }
    }
}

extension ServerFrame: Decodable {
    public init(from decoder: any Decoder) throws {
        let c = try decoder.container(keyedBy: JSONKey.self)
        func string(_ key: JSONKey) throws -> String { try c.decode(String.self, forKey: key) }
        func reqId() throws -> String { try c.value("reqId", or: "") }
        let t = try string("t")
        switch t {
        case "welcome": self = .welcome
        case "panes": self = .panes(try c.decode([Pane].self, forKey: "panes"))
        case "workspaces": self = .workspaces(try c.decode([Workspace].self, forKey: "workspaces"))
        case "tabs": self = .tabs(try c.decode([Tab].self, forKey: "tabs"))
        case "pane_update": self = .paneUpdate(try c.decode(Pane.self, forKey: "pane"))
        case "pane_removed": self = .paneRemoved(paneId: try string("paneId"))
        case "pane_read":
            self = .paneRead(reqId: try string("reqId"), paneId: try string("paneId"), source: try string("source"), text: try string("text"))
        case "ack": self = .ack(reqId: try string("reqId"))
        case "error": self = .error(reqId: try reqId(), code: try string("code"), message: try string("message"))
        case "action_result": self = .actionResult(reqId: try reqId(), ok: try c.value("ok", or: false), error: try c.value("error"))
        case "created":
            self = .created(reqId: try reqId(), ok: try c.value("ok", or: false), paneId: try c.value("paneId"),
                            terminalId: try c.value("terminalId"), error: try c.value("error"))
        case "agents": self = .agents(reqId: try reqId(), names: try c.value("agents", or: []))
        case "close_impact":
            self = .closeImpact(reqId: try reqId(), workspaceId: try c.value("workspaceId", or: ""), alsoCloses: try c.value("alsoCloses", or: []))
        case "pong": self = .pong
        case "term_opened": self = .termOpened(reqId: try string("reqId"), termId: try string("termId"))
        case "term_data": self = .termData(termId: try string("termId"), data: try c.decode(Data.self, forKey: "data"))
        case "term_exit": self = .termExit(termId: try string("termId"), code: try c.value("code", or: 0), reason: try c.value("reason", or: ""))
        case "term_error":
            self = .termError(reqId: try reqId(), termId: try c.value("termId", or: ""), message: try c.value("message", or: ""))
        case "chat_snapshot": self = .chatSnapshot(try ChatSnapshot(from: decoder))
        case "chat_history_page": self = .chatHistoryPage(try ChatHistoryPage(from: decoder))
        case "chat_image_data": self = .chatImage(try ChatImage(from: decoder))
        case "chat_answer_result": self = .chatAnswerResult(reqId: try reqId(), ok: try c.value("ok", or: false), error: try c.value("error"))
        case "chat_event":
            let e = try SeqEntry(from: decoder)
            self = .chatEvent(paneId: try c.value("paneId", or: ""), epoch: try c.value("epoch", or: 0), seq: e.seq, entry: e.entry,
                              agentId: try c.value("agentId"))
        case "chat_agent":
            let removed: Bool = try c.value("removed", or: false)
            self = .chatAgent(paneId: try c.value("paneId", or: ""), agent: try? c.value("agent"),
                              removedId: removed ? try c.value("agentId") : nil)
        case "chat_tasks": self = .chatTasks(paneId: try c.value("paneId", or: ""), tasks: try c.lossy("tasks") ?? [])
        case "chat_state": self = .chatState(paneId: try c.value("paneId", or: ""), state: try c.value("state", or: "idle"))
        case "chat_send_result": self = .chatSendResult(reqId: try reqId(), ok: try c.value("ok", or: false), error: try c.value("error"))
        default: self = .unknown(t)
        }
    }
}
