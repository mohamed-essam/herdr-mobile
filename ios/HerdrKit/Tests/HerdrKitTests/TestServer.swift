import Foundation
import Network

/// A WebSocket server on 127.0.0.1 for the CompanionClient tests, on Network.framework.
/// Each connection the client makes arrives as a `Peer` that the test drives by hand.
final class TestServer: Sendable {
    let url: URL
    private let listener: NWListener
    private let peers: AsyncStream<Peer>

    /// Listens on `port`, or on a free one when nil.
    init(port: UInt16? = nil) async throws {
        let parameters = NWParameters.tcp
        parameters.defaultProtocolStack.applicationProtocols.insert(NWProtocolWebSocket.Options(), at: 0)
        parameters.requiredLocalEndpoint = .hostPort(host: .ipv4(.loopback), port: port.flatMap(NWEndpoint.Port.init(rawValue:)) ?? .any)
        parameters.allowLocalEndpointReuse = true
        let listener = try NWListener(using: parameters)
        let (peers, newPeer) = AsyncStream.makeStream(of: Peer.self)
        // A peer arrives once its WebSocket handshake is done: dropping one mid-handshake hangs the client.
        listener.newConnectionHandler = { connection in
            connection.stateUpdateHandler = { state in
                if case .ready = state { newPeer.yield(Peer(connection)) }
            }
            connection.start(queue: .global())
        }
        let port = try await withCheckedThrowingContinuation { (ready: CheckedContinuation<UInt16, any Error>) in
            listener.stateUpdateHandler = { state in
                switch state {
                case .ready:
                    listener.stateUpdateHandler = nil
                    ready.resume(returning: listener.port!.rawValue)
                case .failed(let error):
                    listener.stateUpdateHandler = nil
                    ready.resume(throwing: error)
                default: break
                }
            }
            listener.start(queue: .global())
        }
        self.listener = listener
        self.peers = peers
        url = URL(string: "ws://127.0.0.1:\(port)")!
    }

    deinit { listener.cancel() }

    var port: UInt16 { UInt16(url.port!) }

    /// Stops listening, like a companion that went down: new connections are refused.
    func stop() { listener.cancel() }

    /// The client's next connection, or nil when none comes `within` the wait.
    func nextPeer(within wait: Duration = .seconds(5)) async -> Peer? {
        await nextElement(of: peers, within: wait)
    }
}

/// One client connection, seen from the server.
final class Peer: Sendable {
    private let connection: NWConnection
    private let messages: AsyncStream<String>

    /// `connection` is started and ready.
    init(_ connection: NWConnection) {
        let (messages, received) = AsyncStream.makeStream(of: String.self)
        @Sendable func receive() {
            connection.receiveMessage { data, context, _, error in
                let metadata = context?.protocolMetadata(definition: NWProtocolWebSocket.definition) as? NWProtocolWebSocket.Metadata
                if let data, metadata?.opcode == .text {
                    received.yield(String(decoding: data, as: UTF8.self))
                }
                if error != nil || metadata?.opcode == .close {
                    received.finish()
                } else {
                    receive()
                }
            }
        }
        self.connection = connection
        self.messages = messages
        receive()
    }

    /// The next text frame the client sent, or nil when it is gone or sends none `within` the wait.
    func next(within wait: Duration = .seconds(5)) async -> String? {
        await nextElement(of: messages, within: wait)
    }

    /// Answers every `ping` with a `pong`, as the companion does, until the client goes.
    /// The test can't read this peer's frames any more.
    func answerPings() {
        Task {
            for await message in messages where message.contains(#""t":"ping""#) {
                send(#"{"t":"pong"}"#)
            }
        }
    }

    func send(_ text: String) {
        connection.send(content: Data(text.utf8), contentContext: Self.textFrame(), completion: .idempotent)
    }

    /// Sends `text` as one frame in `parts` spread over `wait`, like a big frame on a slow link:
    /// the client sees nothing until the last part.
    func trickle(_ text: String, over wait: Duration, parts: Int = 8) async {
        let data = Data(text.utf8)
        let context = Self.textFrame() // one context for every part of the message
        let size = (data.count + parts - 1) / parts
        for start in stride(from: 0, to: data.count, by: size) {
            let end = min(start + size, data.count)
            connection.send(content: data[start..<end], contentContext: context, isComplete: end == data.count, completion: .idempotent)
            if end < data.count { try? await Task.sleep(for: wait / parts) }
        }
    }

    private static func textFrame() -> NWConnection.ContentContext {
        NWConnection.ContentContext(identifier: "text", metadata: [NWProtocolWebSocket.Metadata(opcode: .text)])
    }

    /// Closes the connection, like a companion that went away.
    func drop() { connection.cancel() }
}

/// The stream's next element, or nil when none comes `within` the wait. A timeout ends the stream.
func nextElement<T: Sendable>(of stream: AsyncStream<T>, within wait: Duration = .seconds(5)) async -> T? {
    await withTaskGroup(of: T?.self) { group in
        group.addTask {
            var iterator = stream.makeAsyncIterator()
            return await iterator.next()
        }
        group.addTask {
            try? await Task.sleep(for: wait)
            return nil
        }
        let result = await group.next()!
        group.cancelAll()
        return result
    }
}
