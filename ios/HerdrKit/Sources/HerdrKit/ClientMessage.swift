import Foundation

/// A frame for the companion that gets no reply. See `CompanionClient.send(_:)`.
public enum ClientMessage: Equatable, Sendable {
    case hello
    case ping
    /// The client re-sends the last endpoint after every reconnect.
    case registerPush(endpoint: String)
    /// `agentId` picks a thread of the pane; nil is the main stream.
    case chatOpen(paneId: String, agentId: String? = nil)
    case chatClose(paneId: String, agentId: String? = nil)
    /// Answered on `CompanionClient.images()`.
    case chatImage(paneId: String, id: String)
    case termInput(termId: String, data: Data)
    case termResize(termId: String, cols: Int, rows: Int)
    case termClose(termId: String)

    var json: String {
        let fields: [String: (any Encodable)?] = switch self {
        case .hello: ["t": "hello", "client": "herdr-mobile", "clientVersion": "1.0.0"]
        case .ping: ["t": "ping"]
        case .registerPush(let endpoint): ["t": "register_push", "endpoint": endpoint]
        case .chatOpen(let paneId, let agentId): ["t": "chat_open", "paneId": paneId, "agentId": agentId]
        case .chatClose(let paneId, let agentId): ["t": "chat_close", "paneId": paneId, "agentId": agentId]
        case .chatImage(let paneId, let id): ["t": "chat_image", "paneId": paneId, "id": id]
        case .termInput(let termId, let data): ["t": "term_input", "termId": termId, "data": data]
        case .termResize(let termId, let cols, let rows): ["t": "term_resize", "termId": termId, "cols": cols, "rows": rows]
        case .termClose(let termId): ["t": "term_close", "termId": termId]
        }
        return OutgoingFrame(fields: fields).json
    }
}

/// A frame for the companion that gets one reply, matched by the reqId the client
/// adds. See `CompanionClient.request(_:)`.
public enum ClientRequest: Equatable, Sendable {
    case readPane(paneId: String, source: String = "detection", lines: Int = 40)
    case sendText(paneId: String, text: String)
    case sendKeys(paneId: String, keys: String)
    /// `label` goes with `.rename`.
    case action(op: ActionOp, kind: NodeKind, id: String, label: String? = nil)
    case create(what: CreateWhat, workspaceId: String? = nil, tabId: String? = nil, paneId: String? = nil,
                direction: SplitDirection? = nil, agentName: String? = nil, argv: [String]? = nil)
    case move(paneId: String, dest: MoveDest, tabId: String? = nil, direction: SplitDirection? = nil)
    case chatSend(paneId: String, text: String)
    case chatAnswer(paneId: String, toolUseId: String, answers: [String: String])
    case chatHistory(paneId: String, epoch: Int, beforeSeq: Int, limit: Int, agentId: String? = nil)
    case listAgents
    case closeImpact(workspaceId: String)
    case termOpen(target: String, cols: Int, rows: Int)

    /// Starts each reqId, as on Android, so logs show what a reply answers.
    var reqIdPrefix: String {
        switch self {
        case .readPane, .sendText, .sendKeys, .termOpen: "r"
        case .action: "a"
        case .create: "n"
        case .move: "v"
        case .chatSend: "c"
        case .chatAnswer: "q"
        case .chatHistory: "h"
        case .listAgents: "g"
        case .closeImpact: "i"
        }
    }

    func json(reqId: String) -> String {
        var fields: [String: (any Encodable)?] = switch self {
        case .readPane(let paneId, let source, let lines):
            ["t": "read_pane", "paneId": paneId, "source": source, "lines": lines]
        case .sendText(let paneId, let text): ["t": "send_text", "paneId": paneId, "text": text]
        case .sendKeys(let paneId, let keys): ["t": "send_keys", "paneId": paneId, "keys": keys]
        case .action(let op, let kind, let id, let label): ["t": "action", "op": op, "kind": kind, "id": id, "label": label]
        case .create(let what, let workspaceId, let tabId, let paneId, let direction, let agentName, let argv):
            ["t": "create", "what": what, "workspaceId": workspaceId, "tabId": tabId, "paneId": paneId,
             "direction": direction, "agentName": agentName, "argv": argv]
        case .move(let paneId, let dest, let tabId, let direction):
            ["t": "move", "paneId": paneId, "dest": dest, "tabId": tabId, "direction": direction]
        case .chatSend(let paneId, let text): ["t": "chat_send", "paneId": paneId, "text": text]
        case .chatAnswer(let paneId, let toolUseId, let answers):
            ["t": "chat_answer", "paneId": paneId, "toolUseId": toolUseId, "answers": answers]
        case .chatHistory(let paneId, let epoch, let beforeSeq, let limit, let agentId):
            ["t": "chat_history", "paneId": paneId, "epoch": epoch, "beforeSeq": beforeSeq, "limit": limit, "agentId": agentId]
        case .listAgents: ["t": "list_agents"]
        case .closeImpact(let workspaceId): ["t": "close_impact", "workspaceId": workspaceId]
        case .termOpen(let target, let cols, let rows): ["t": "term_open", "target": target, "cols": cols, "rows": rows]
        }
        fields["reqId"] = reqId
        return OutgoingFrame(fields: fields).json
    }
}

/// One JSON object; nil fields are left out, as on Android.
private struct OutgoingFrame: Encodable {
    let fields: [String: (any Encodable)?]

    func encode(to encoder: any Encoder) throws {
        var c = encoder.container(keyedBy: JSONKey.self)
        for case let (key, value?) in fields {
            try c.encode(value, forKey: JSONKey(stringValue: key))
        }
    }

    var json: String {
        // Strings, ints, Data and string maps always encode; only a non-finite float could throw.
        String(decoding: try! Self.encoder.encode(self), as: UTF8.self)
    }

    private static let encoder = JSONEncoder()
}
