import Foundation
import Observation

/// Holds one WebSocket to the companion and reports its state, reconnecting with
/// backoff whenever it drops. M2 adds the rest of the protocol.
@MainActor @Observable
public final class CompanionClient {
    public private(set) var state = ConnectionState.connecting
    private let url: URL

    public init(url: URL) { self.url = url }

    /// Connects, and reconnects after every drop, until the calling task is cancelled.
    public func run() async {
        var backoff = Backoff()
        while !Task.isCancelled {
            let socket = URLSession.shared.webSocketTask(with: url)
            socket.resume()
            await withTaskCancellationHandler {
                while let message = try? await socket.receive() {
                    if case .string(let text) = message, (try? ServerFrame(json: Data(text.utf8))) == .welcome {
                        state = .connected
                        backoff.reset()
                    }
                }
            } onCancel: {
                socket.cancel(with: .goingAway, reason: nil)
            }
            socket.cancel(with: .goingAway, reason: nil)
            guard !Task.isCancelled else { return }
            state = .reconnecting
            try? await Task.sleep(for: backoff.next())
        }
    }
}
