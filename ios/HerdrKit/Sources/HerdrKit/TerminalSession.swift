import Foundation
import Observation

/// Where the terminal's attach stands. Ported from Android TerminalScreen.kt's `status` string.
public enum TerminalStatus: Equatable, Sendable {
    case connecting, connected, reconnecting, paused
    /// The companion refused the attach, with its reason.
    case failed(String)
    /// The terminal ended on its own; `exit` says why.
    case ended
}

/// The overlay and header copy for a terminal that ended, keyed by the companion's reason.
/// An unknown or empty reason says "session ended": it never claims what it can't prove.
public struct ExitCopy: Equatable, Sendable {
    public let title: String
    public let detail: String

    public init(title: String, detail: String) {
        self.title = title
        self.detail = detail
    }

    public init(reason: String, code: Int) {
        self = reason == "error"
            ? ExitCopy(title: "terminal disconnected", detail: "ended unexpectedly (code \(code))")
            : ExitCopy(title: "session ended", detail: "the terminal process exited")
    }
}

/// The header's status word and the status color it takes.
public struct TerminalHeaderStatus: Equatable, Sendable {
    public let label: String
    public let tone: AgentStatus
}

/// One pane's terminal attach: open it while the app is connected, in the foreground and
/// laid out; release it in the background; attach afresh after every reconnect (the
/// companion kills the PTY when the socket drops); show an exit and re-attach on request.
/// Ported from Android's TerminalScreen.kt.
///
/// Feed it `setConnected`, `setForeground`, the emulator's size (`resize`, the first call
/// means the emulator is ready) and the client's `terminal()` frames. Bytes for the emulator go to `output`.
@MainActor @Observable
public final class TerminalSession {
    public private(set) var status = TerminalStatus.connecting
    /// Why the terminal ended, while the exit overlay shows; cleared by a successful attach.
    public private(set) var exit: ExitCopy?

    /// Receives the live terminal's output.
    @ObservationIgnored public var output: (Data) -> Void = { _ in }
    /// The attach in flight.
    @ObservationIgnored private(set) var attaching: Task<Void, Never>?

    private var termId: String?
    private var connected = false
    private var foreground = true
    /// Nil until the emulator has laid out.
    @ObservationIgnored private var size: (cols: Int, rows: Int)?

    private let target: String
    private let send: (ClientMessage) -> Void
    private let request: @MainActor (ClientRequest) async throws -> ServerFrame

    /// `target` is the pane's terminalId; `send` and `request` are usually the client's.
    public init(target: String, send: @escaping (ClientMessage) -> Void,
                request: @escaping @MainActor (ClientRequest) async throws -> ServerFrame) {
        self.target = target
        self.send = send
        self.request = request
    }

    /// "attached" while live, the exit title once ended, "failed", "paused" in the
    /// background, else "connecting…" / "reconnecting…".
    public var header: TerminalHeaderStatus {
        switch status {
        case _ where exit != nil: TerminalHeaderStatus(label: label, tone: .blocked)
        case .connected: TerminalHeaderStatus(label: "attached", tone: .done)
        case .failed: TerminalHeaderStatus(label: "failed", tone: .blocked)
        case .paused: TerminalHeaderStatus(label: "paused", tone: .idle)
        default: TerminalHeaderStatus(label: label, tone: .working)
        }
    }

    /// The status in words, as the reconnect scrim shows it.
    public var label: String {
        switch status {
        case .connecting: "connecting…"
        case .connected: "connected"
        case .reconnecting: "reconnecting…"
        case .paused: "paused"
        case .failed(let reason): "failed: \(reason)"
        case .ended: exit?.title ?? ""
        }
    }

    /// The scrim over a terminal that exists but isn't live; the exit overlay owns the screen instead.
    public var showsReconnectOverlay: Bool { size != nil && exit == nil && status != .connected }

    /// The key bar only reaches a live attach on a live socket.
    public var keysLive: Bool { connected && termId != nil && exit == nil }

    public func setConnected(_ up: Bool) {
        guard up != connected else { return }
        connected = up
        update()
    }

    /// Holding an attach in the background clamps the desktop pane to this phone and
    /// re-attaches on every socket flap, so it is released and taken again on return.
    public func setForeground(_ shown: Bool) {
        guard shown != foreground else { return }
        foreground = shown
        if !shown, let id = termId {
            termId = nil
            status = .paused
            send(.termClose(termId: id))
        }
        update()
    }

    /// The emulator's size; the first call means it can take bytes. Repeats are ignored.
    public func resize(cols: Int, rows: Int) {
        if let size, size == (cols, rows) { return }
        let first = size == nil
        size = (cols, rows)
        if first {
            update()
        } else if let termId {
            send(.termResize(termId: termId, cols: cols, rows: rows))
        }
    }

    /// Typed bytes, dropped unless attached.
    public func input(_ data: Data) {
        if let termId { send(.termInput(termId: termId, data: data)) }
    }

    public func apply(_ frame: ServerFrame) {
        switch frame {
        case .termData(let id, let data) where id == termId: output(data)
        case .termExit(let id, let code, let reason) where id == termId:
            exit = ExitCopy(reason: reason, code: code)
            status = .ended
            termId = nil
        default: break
        }
    }

    /// "Reattach" on the exit overlay.
    public func reattach() async {
        if attaching == nil { attaching = Task { await attach() } }
        await attaching?.value
    }

    /// The screen went away: release the attach, and hand back one still in flight.
    public func close() { setForeground(false) }

    /// (Re)attaches once connected, laid out and in the foreground. A drop clears the dead
    /// attach and shows "reconnecting…".
    private func update() {
        guard foreground else { return }
        guard connected else {
            termId = nil
            if size != nil { status = .reconnecting }
            return
        }
        guard size != nil, termId == nil, attaching == nil else { return }
        attaching = Task { await attach() }
    }

    private func attach() async {
        defer { attaching = nil }
        guard let size else { return }
        status = .connecting
        do {
            guard case .termOpened(_, let id) = try await request(.termOpen(target: target, cols: size.cols, rows: size.rows)) else { return }
            if foreground {
                termId = id
                status = .connected
                exit = nil
            } else {
                send(.termClose(termId: id))
            }
        } catch {
            status = .failed(errorLabel(error))
        }
    }
}
