import Foundation
import Observation

/// Why a request to the companion failed.
public enum CompanionError: Error, Equatable {
    /// There was no connection to send it on.
    case notConnected
    /// The connection dropped before the reply came.
    case disconnected
    /// No reply came within the request timeout.
    case timedOut
    /// The companion answered with an error, or with `ok: false`.
    case rejected(String)
}

/// Why a request failed, in words.
func errorLabel(_ error: any Error) -> String {
    switch error as? CompanionError {
    case .rejected(let message): message
    case .timedOut: "timed out"
    case .disconnected: "disconnected"
    case .notConnected: "not connected"
    case nil: error.localizedDescription
    }
}

/// Why an answer to a question failed, in words.
func answerErrorLabel(_ error: any Error) -> String {
    switch error as? CompanionError {
    case .rejected("no_mod"): "Claude isn't connected"
    case .rejected("no_question"): "the question is no longer waiting"
    case .rejected("empty"): "empty answer"
    default: "answer failed: \(errorLabel(error))"
    }
}

/// The app's one WebSocket to the companion. `run()` connects, and reconnects with
/// backoff after every drop; read frames from `frames()`, chat images from `images()`.
@MainActor @Observable
public final class CompanionClient {
    public private(set) var state = ConnectionState.connecting

    private let url: URL
    private let keepalive: Duration
    private let requestTimeout: Duration
    private let initialBackoff: Backoff
    // Sized as Android's SharedFlows (CompanionClient.kt).
    @ObservationIgnored private let frameFeed = Broadcast<ServerFrame>(replay: 16, unread: 64)
    @ObservationIgnored private let imageFeed = Broadcast<ChatImage>(replay: 0, unread: 8)
    @ObservationIgnored private let terminalFeed = Broadcast<ServerFrame>(replay: 0, unread: nil)
    /// The live connection. One that ended, or that a newer `run()` replaced, no longer touches the client.
    @ObservationIgnored private var link: Link?
    @ObservationIgnored private var lastReqId = 0
    @ObservationIgnored private var pushEndpoint: String?
    @ObservationIgnored private var backoffWait: Task<Void, Never>?
    /// `reconnectNow()` came while an attempt was open: if it fails, the next one goes at once.
    @ObservationIgnored private var kicked = false

    public convenience init(url: URL) {
        self.init(url: url, keepalive: .seconds(10), requestTimeout: .seconds(8), backoff: Backoff())
    }

    init(url: URL, keepalive: Duration, requestTimeout: Duration, backoff: Backoff) {
        self.url = url
        self.keepalive = keepalive
        self.requestTimeout = requestTimeout
        initialBackoff = backoff
    }

    /// A new stream of every frame except chat images, terminal output, pongs, frames this
    /// app can't read, and the replies `request` returns. It starts with the last 16 frames, then holds the
    /// newest 64 while unread. It ends when its reader is cancelled; call again for another.
    public func frames() -> AsyncStream<ServerFrame> { frameFeed.subscribe() }

    /// A new stream of chat image replies, apart from `frames()` so their megabytes are never
    /// replayed. Holds the newest 8 while unread.
    public func images() -> AsyncStream<ChatImage> { imageFeed.subscribe() }

    /// A new stream of terminal output (`term_data`, `term_exit`), apart from `frames()`
    /// because a byte stream can't lose a chunk: it holds everything unread, and replays nothing.
    public func terminal() -> AsyncStream<ServerFrame> { terminalFeed.subscribe() }

    /// Connects, and reconnects after every drop, until the calling task is cancelled.
    public func run() async {
        var backoff = initialBackoff
        state = .connecting
        while !Task.isCancelled {
            if await connection() {
                backoff.reset()
                kicked = false
            }
            guard !Task.isCancelled else { break }
            if kicked {
                kicked = false
                state = .reconnecting
                continue
            }
            let wait = Task { [delay = backoff.next()] in _ = try? await Task.sleep(for: delay) }
            backoffWait = wait
            state = .reconnecting
            await withTaskCancellationHandler { await wait.value } onCancel: { wait.cancel() }
            backoffWait = nil
        }
        if link == nil { state = .connecting } // else a newer run() owns the state
    }

    /// Ends the wait before the next reconnect, e.g. when the app returns to the foreground.
    /// During an attempt, it skips the wait after that attempt if it fails.
    public func reconnectNow() {
        if let backoffWait { backoffWait.cancel() } else { kicked = true }
    }

    /// Sends a frame that gets no reply; it is dropped while disconnected. The last
    /// `registerPush` endpoint is sent again after every reconnect.
    public func send(_ message: ClientMessage) {
        switch message {
        case .registerPush(let endpoint): pushEndpoint = endpoint
        case .chatImage: link?.imagesDue += 1
        default: break
        }
        link?.send(message.json)
    }

    /// Sends `request` and returns the reply with its reqId. Throws `CompanionError`,
    /// `.rejected` when the reply is an error or says `ok: false`, or `CancellationError`.
    public func request(_ request: ClientRequest) async throws -> ServerFrame {
        guard let link else { throw CompanionError.notConnected }
        lastReqId += 1
        let reqId = "\(request.reqIdPrefix)\(lastReqId)"
        let timeout = Task { [requestTimeout] in
            guard (try? await Task.sleep(for: requestTimeout)) != nil else { return }
            link.pending.removeValue(forKey: reqId)?.resume(throwing: CompanionError.timedOut)
        }
        defer { timeout.cancel() }
        return try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { reply in
                link.pending[reqId] = reply
                link.send(request.json(reqId: reqId))
            }
        } onCancel: {
            Task { @MainActor in link.pending.removeValue(forKey: reqId)?.resume(throwing: CancellationError()) }
        }
    }

    /// One connection, from opening it to its drop. True if the companion welcomed it.
    private func connection() async -> Bool {
        let socket = URLSession.shared.webSocketTask(with: url)
        // Chat image replies run to several MB; the default limit is 1 MiB. The mod's bodies cap at 8 MiB.
        socket.maximumMessageSize = 16 << 20
        let link = Link(socket)
        self.link = link
        socket.resume()
        send(.hello)
        if let pushEndpoint { send(.registerPush(endpoint: pushEndpoint)) }
        await withTaskCancellationHandler {
            await withTaskGroup(of: Void.self) { group in
                group.addTask { await self.receive(on: link) }
                group.addTask { await self.keepAlive(link) }
                await group.next()
                socket.cancel(with: .goingAway, reason: nil) // ends the other loop
                group.cancelAll()
            }
        } onCancel: {
            socket.cancel(with: .goingAway, reason: nil)
        }
        if self.link === link { self.link = nil }
        link.failPending()
        return link.welcomed
    }

    private func receive(on link: Link) async {
        while let message = try? await link.socket.receive() {
            link.heard = true
            guard case .string(let text) = message, let frame = await Self.parse(text), self.link === link else { continue }
            switch frame {
            case .pong, .unknown: continue
            case .chatImage(let image):
                link.imagesDue = max(0, link.imagesDue - 1)
                imageFeed.yield(image)
                continue
            case .termData, .termExit:
                terminalFeed.yield(frame)
                continue
            case .welcome:
                link.welcomed = true
                state = .connected
            default: break
            }
            if let reqId = frame.reqId, let reply = link.pending.removeValue(forKey: reqId) {
                if let failure = frame.failure {
                    reply.resume(throwing: CompanionError.rejected(failure))
                } else {
                    reply.resume(returning: frame)
                }
            } else {
                frameFeed.yield(frame)
            }
        }
    }

    /// Off the main actor: an image frame is megabytes of JSON and base64.
    @concurrent private nonisolated static func parse(_ text: String) async -> ServerFrame? {
        try? ServerFrame(json: Data(text.utf8))
    }

    /// Pings every `keepalive`, and returns once the link has been silent too long: a whole
    /// interval after a ping, or six while a chat image is due. URLSession shows nothing of a
    /// frame until all of it has arrived, and a big image takes that long on a slow link.
    /// The companion answers a ping with a pong; any other frame counts as a sign of life too.
    private func keepAlive(_ link: Link) async {
        var silence = Duration.zero
        while (try? await Task.sleep(for: keepalive)) != nil {
            silence = link.heard ? .zero : silence + keepalive
            link.heard = false
            guard silence < (link.imagesDue > 0 ? keepalive * 6 : keepalive) else { return }
            link.send(ClientMessage.ping.json)
        }
    }
}

/// One connection's socket, and what the client tracks for it alone.
@MainActor private final class Link {
    let socket: URLSessionWebSocketTask
    /// The companion sent `welcome`.
    var welcomed = false
    /// A frame arrived since the keepalive last looked.
    var heard = false
    /// Chat images asked for that haven't arrived.
    var imagesDue = 0
    var pending: [String: CheckedContinuation<ServerFrame, any Error>] = [:]

    init(_ socket: URLSessionWebSocketTask) { self.socket = socket }

    func send(_ text: String) { socket.send(.string(text)) { _ in } }

    func failPending() {
        for reply in pending.values { reply.resume(throwing: CompanionError.disconnected) }
        pending = [:]
    }
}

/// Hands each value to every reader's own stream. A new reader first gets the last
/// `replay` values; one more than `unread` behind loses its oldest (nil: none is lost).
@MainActor private final class Broadcast<Element: Sendable> {
    private let replay: Int
    private let unread: Int?
    private var recent: [Element] = []
    private var readers: [Int: AsyncStream<Element>.Continuation] = [:]
    private var lastId = 0

    init(replay: Int, unread: Int?) {
        self.replay = replay
        self.unread = unread
    }

    func subscribe() -> AsyncStream<Element> {
        let (stream, reader) = AsyncStream.makeStream(of: Element.self, bufferingPolicy: unread.map { .bufferingNewest(replay + $0) } ?? .unbounded)
        recent.forEach { reader.yield($0) }
        lastId += 1
        let id = lastId
        readers[id] = reader
        reader.onTermination = { [weak self] _ in Task { @MainActor in self?.readers[id] = nil } }
        return stream
    }

    func yield(_ value: Element) {
        recent.append(value)
        recent.removeFirst(max(0, recent.count - replay))
        for reader in readers.values { reader.yield(value) }
    }
}
