import Foundation
import Testing
@testable import HerdrKit

// CompanionClient against a real WebSocket server (TestServer), each connection
// driven by hand. Timings are test-sized: backoff from 50 ms, no keepalive unless asked.

private let welcome = #"{"t":"welcome","herdrVersion":"fake","herdrProtocol":0,"companionProtocol":10}"#

private let hello = #"{"t":"hello","client":"herdr-mobile","clientVersion":"1.0.0"}"#

/// A fresh server and a client running against it.
@MainActor
private func start(
    keepalive: Duration = .seconds(60), requestTimeout: Duration = .seconds(5), backoff: Backoff = Backoff(first: .milliseconds(50))
) async throws -> (TestServer, CompanionClient, Task<Void, Never>) {
    let server = try await TestServer()
    let client = CompanionClient(url: server.url, keepalive: keepalive, requestTimeout: requestTimeout, backoff: backoff)
    return (server, client, Task { await client.run() })
}

extension Peer {
    /// The next text frame the client sent, keys sorted (see `canonical`).
    func nextJSON() async throws -> String {
        try canonical(#require(await next()))
    }
}

@MainActor
private func until(within wait: Duration = .seconds(5), _ condition: () -> Bool) async -> Bool {
    let deadline = ContinuousClock.now + wait
    while !condition() {
        guard ContinuousClock.now < deadline else { return false }
        try? await Task.sleep(for: .milliseconds(10))
    }
    return true
}

/// The client's next connection, past its hello and welcomed, so the client is connected.
@MainActor
private func welcomed(_ server: TestServer, _ client: CompanionClient) async throws -> Peer {
    let peer = try #require(await server.nextPeer())
    #expect(try await peer.nextJSON() == canonical(hello))
    peer.send(welcome)
    #expect(await until { client.state == .connected })
    return peer
}

@Test @MainActor func saysHelloAndConnectsOnWelcome() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try #require(await server.nextPeer())
    #expect(try await peer.nextJSON() == canonical(hello))
    #expect(client.state == .connecting)
    peer.send(welcome)
    #expect(await until { client.state == .connected })
}

@Test @MainActor func matchesEachReplyToItsRequestByReqId() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)

    let reply = Task { try await client.request(.readPane(paneId: "w1:p1")) }
    #expect(try await peer.nextJSON() == canonical(#"{"t":"read_pane","reqId":"r1","paneId":"w1:p1","source":"detection","lines":40}"#))
    peer.send(#"{"t":"pane_read","reqId":"r99","paneId":"w9:p9","source":"detection","text":"not yours"}"#)
    peer.send(#"{"t":"pane_read","reqId":"r1","paneId":"w1:p1","source":"detection","text":"$ ls"}"#)
    #expect(try await reply.value == .paneRead(reqId: "r1", paneId: "w1:p1", source: "detection", text: "$ ls"))

    // A reply nobody waits for is just another frame. A new reader starts with the recent frames.
    let frames = client.frames()
    #expect(await nextElement(of: frames) == .welcome)
    #expect(await nextElement(of: frames) == .paneRead(reqId: "r99", paneId: "w9:p9", source: "detection", text: "not yours"))
}

@Test @MainActor func throwsWhenTheCompanionTurnsARequestDown() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)

    let close = Task { try await client.request(.action(op: "close", kind: "window", id: "x")) }
    _ = await peer.next()
    peer.send(#"{"t":"action_result","reqId":"a1","ok":false,"error":"unknown kind: window"}"#)
    await #expect(throws: CompanionError.rejected("unknown kind: window")) { try await close.value }

    let read = Task { try await client.request(.readPane(paneId: "nope")) }
    _ = await peer.next()
    peer.send(#"{"t":"error","reqId":"r2","code":"read_failed","message":"pane not found"}"#)
    await #expect(throws: CompanionError.rejected("pane not found")) { try await read.value }
}

@Test @MainActor func aRequestWithoutAReplyTimesOut() async throws {
    let (server, client, run) = try await start(requestTimeout: .milliseconds(200))
    defer { run.cancel() }
    _ = try await welcomed(server, client)
    await #expect(throws: CompanionError.timedOut) { try await client.request(.listAgents) }
}

@Test @MainActor func requestsFailAtOnceWhenTheLinkIsDown() async throws {
    let (server, client, run) = try await start(backoff: Backoff(first: .seconds(60)))
    defer { run.cancel() }
    let peer = try await welcomed(server, client)

    let pending = Task { try await client.request(.listAgents) }
    _ = await peer.next()
    peer.drop()
    await #expect(throws: CompanionError.disconnected) { try await pending.value }
    #expect(await until { client.state == .reconnecting })
    await #expect(throws: CompanionError.notConnected) { try await client.request(.listAgents) }
}

@Test @MainActor func reconnectsWithABackoffThatAWelcomeResets() async throws {
    let (server, client, run) = try await start(backoff: Backoff(first: .milliseconds(300)))
    defer { run.cancel() }
    // Drops `peer` and times the reconnect.
    func gapAfterDropping(_ peer: Peer) async throws -> (Duration, Peer) {
        let dropped = ContinuousClock.now
        peer.drop()
        let next = try #require(await server.nextPeer())
        return (ContinuousClock.now - dropped, next)
    }

    // Never welcomed: the waits grow.
    var peer = try #require(await server.nextPeer())
    let (first, second): (Duration, Duration)
    (first, peer) = try await gapAfterDropping(peer)
    (second, peer) = try await gapAfterDropping(peer)
    #expect(first >= .milliseconds(300) && first < .milliseconds(550), "\(first)")
    #expect(second >= .milliseconds(600), "\(second)")

    // Welcomed: the next wait starts over.
    peer.send(welcome)
    #expect(await until { client.state == .connected })
    let (afterWelcome, _) = try await gapAfterDropping(peer)
    #expect(afterWelcome < .milliseconds(550), "\(afterWelcome)")
}

@Test @MainActor func reconnectNowSkipsTheBackoffWait() async throws {
    let (server, client, run) = try await start(backoff: Backoff(first: .seconds(60)))
    defer { run.cancel() }
    try await welcomed(server, client).drop()
    #expect(await until { client.state == .reconnecting })
    client.reconnectNow()
    #expect(await server.nextPeer(within: .seconds(2)) != nil)
}

@Test @MainActor func reSendsThePushEndpointAfterEveryReconnect() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let register = #"{"t":"register_push","endpoint":"https://push.example/abc"}"#

    let first = try await welcomed(server, client)
    client.send(.registerPush(endpoint: "https://push.example/abc"))
    #expect(try await first.nextJSON() == canonical(register))
    first.drop()

    let second = try await welcomed(server, client)
    #expect(try await second.nextJSON() == canonical(register))
}

@Test @MainActor func imagesOverOneMiBArriveOnTheirOwnStream() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    let images = client.images()

    let bytes = Data(repeating: 0xAB, count: 2 << 20) // 2 MiB, ~2.8 MB as base64
    peer.send(#"{"t":"chat_image_data","paneId":"w1:p1","id":"img1","mediaType":"image/png","data":"\#(bytes.base64EncodedString())"}"#)
    peer.send(#"{"t":"chat_state","paneId":"w1:p1","state":"idle"}"#)
    #expect(await nextElement(of: images) == ChatImage(paneId: "w1:p1", id: "img1", mediaType: "image/png", data: bytes, missing: false))
    let frames = client.frames()
    #expect(await nextElement(of: frames) == .welcome)
    #expect(await nextElement(of: frames) == .chatState(paneId: "w1:p1", state: "idle"))
}

@Test @MainActor func skipsPongsAndFramesItCannotRead() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)

    peer.send(#"{"t":"pong"}"#)
    peer.send(#"{"t":"from_a_newer_companion"}"#)
    peer.send("not json")
    peer.send(#"{"t":"pane_removed","paneId":"w1:p1"}"#)
    let frames = client.frames()
    #expect(await nextElement(of: frames) == .welcome)
    #expect(await nextElement(of: frames) == .paneRemoved(paneId: "w1:p1"))
    #expect(client.state == .connected)
}

@Test @MainActor func keepaliveDropsALinkThatWentSilent() async throws {
    let (server, client, run) = try await start(keepalive: .milliseconds(100))
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    #expect(try await peer.nextJSON() == canonical(#"{"t":"ping"}"#))
    // No pong: the client gives up on this link and connects again.
    #expect(await server.nextPeer(within: .seconds(2)) != nil)
}

@Test @MainActor func keepaliveKeepsALinkThatAnswers() async throws {
    let (server, client, run) = try await start(keepalive: .milliseconds(100))
    defer { run.cancel() }
    try await welcomed(server, client).answerPings()
    #expect(await server.nextPeer(within: .seconds(1)) == nil)
    #expect(client.state == .connected)
}

@Test @MainActor func cancellingRunClosesTheLink() async throws {
    let (server, client, run) = try await start()
    let peer = try await welcomed(server, client)
    run.cancel()
    let cancelled = ContinuousClock.now
    #expect(await peer.next() == nil)
    #expect(ContinuousClock.now - cancelled < .seconds(2))
    #expect(await server.nextPeer(within: .milliseconds(500)) == nil)
}

// MARK: Review fixes (CPE-2091 review, turn 3)

@Test @MainActor func aReaderThatGoesAwayLeavesFramesFlowingToTheOthers() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    let gone = Task { for await _ in client.frames() {} }
    gone.cancel()
    await gone.value

    let (first, second) = (client.frames(), client.frames())
    peer.send(#"{"t":"pane_removed","paneId":"w1:p1"}"#)
    for frames in [first, second] {
        #expect(await nextElement(of: frames) == .welcome)
        #expect(await nextElement(of: frames) == .paneRemoved(paneId: "w1:p1"))
    }
}

@Test @MainActor func imagesAreNotReplayedToANewReader() async throws {
    let (server, client, run) = try await start()
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    let early = client.images()
    peer.send(#"{"t":"chat_image_data","paneId":"w1:p1","id":"img2","missing":true}"#)
    #expect(await nextElement(of: early) == ChatImage(paneId: "w1:p1", id: "img2", mediaType: nil, data: nil, missing: true))
    #expect(await nextElement(of: client.images(), within: .milliseconds(200)) == nil)
}

@Test @MainActor func aRestartedRunKeepsItsNewLink() async throws {
    let (server, client, first) = try await start()
    _ = try await welcomed(server, client)
    // As a SwiftUI .task restarts: the old run is still closing its link when the new one opens.
    first.cancel()
    let second = Task { await client.run() }
    defer { second.cancel() }
    let peer = try #require(await server.nextPeer())
    #expect(try await peer.nextJSON() == canonical(hello))
    #expect(client.state == .connecting)
    peer.send(welcome)
    await first.value

    let reply = Task { try await client.request(.listAgents) }
    #expect(try await peer.nextJSON() == canonical(#"{"t":"list_agents","reqId":"g1"}"#))
    peer.send(#"{"t":"agents","reqId":"g1","agents":["claude"]}"#)
    #expect(try await reply.value == .agents(reqId: "g1", names: ["claude"]))
    #expect(client.state == .connected)
}

@Test @MainActor func cancellingARequestEndsItAtOnce() async throws {
    let (server, client, run) = try await start(requestTimeout: .seconds(5))
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    let reply = Task { try await client.request(.listAgents) }
    _ = await peer.next()
    let cancelled = ContinuousClock.now
    reply.cancel()
    await #expect(throws: CancellationError.self) { try await reply.value }
    #expect(ContinuousClock.now - cancelled < .seconds(1))
}

@Test @MainActor func aCancelledRunIsNoLongerConnected() async throws {
    let (server, client, run) = try await start()
    _ = try await welcomed(server, client)
    run.cancel()
    await run.value
    #expect(client.state == .connecting)
}

@Test @MainActor func keepaliveWaitsForAChatImageStillArriving() async throws {
    let (server, client, run) = try await start(keepalive: .milliseconds(100))
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    let images = client.images()
    client.send(.chatImage(paneId: "w1:p1", id: "img1"))
    #expect(try await peer.nextJSON() == canonical(#"{"t":"chat_image","paneId":"w1:p1","id":"img1"}"#))

    // Over 1 MiB, arriving for 4 keepalive intervals; no pong meanwhile, as it would queue behind the image.
    let bytes = Data(repeating: 0xAB, count: 2 << 20)
    await peer.trickle(#"{"t":"chat_image_data","paneId":"w1:p1","id":"img1","mediaType":"image/png","data":"\#(bytes.base64EncodedString())"}"#,
                       over: .milliseconds(400))
    peer.answerPings() // the pongs that queued behind the image
    #expect(await nextElement(of: images) == ChatImage(paneId: "w1:p1", id: "img1", mediaType: "image/png", data: bytes, missing: false))
    #expect(await server.nextPeer(within: .milliseconds(300)) == nil)
    #expect(client.state == .connected)
}

@Test @MainActor func keepaliveStillDropsADeadLinkWhileAnImageIsDue() async throws {
    let (server, client, run) = try await start(keepalive: .milliseconds(100))
    defer { run.cancel() }
    let peer = try await welcomed(server, client)
    client.send(.chatImage(paneId: "w1:p1", id: "img1"))
    _ = await peer.next()
    // Nothing comes back: the client waits six intervals, not forever.
    let asked = ContinuousClock.now
    #expect(await server.nextPeer(within: .seconds(2)) != nil)
    #expect(ContinuousClock.now - asked >= .milliseconds(500))
}
