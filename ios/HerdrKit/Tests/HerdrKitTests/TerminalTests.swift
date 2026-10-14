import Foundation
import Testing
@testable import HerdrKit

// MARK: Key bar

@Test func barKeysSendXtermNormalCursorBytes() {
    let esc = "\u{1b}"
    let want: [TermKey: String] = [
        .esc: esc, .tab: "\t", .up: "\(esc)[A", .down: "\(esc)[B", .right: "\(esc)[C", .left: "\(esc)[D",
        .home: "\(esc)[H", .end: "\(esc)[F", .pageUp: "\(esc)[5~", .pageDown: "\(esc)[6~",
    ]
    #expect(Set(want.keys) == Set(TermKey.allCases))
    for (key, bytes) in want { #expect(key.bytes == Data(bytes.utf8), "\(key)") }
}

@Test func aTapArmsAModifierForOneKeyAndAnotherTapDisarmsIt() {
    var mods = ModifierKeys()
    mods.tap(.ctrl)
    #expect(mods.ctrl == .oneShot)
    mods.tap(.ctrl)
    #expect(mods.ctrl == .off)

    mods.tap(.ctrl)
    mods.tap(.alt)
    #expect(mods.takeForKey() == (ctrl: true, alt: true))
    #expect(mods.ctrl == .off && mods.alt == .off, "a one-shot clears after one key")
    #expect(mods.takeForKey() == (ctrl: false, alt: false))
}

@Test func aLongPressLocksAModifierUntilTapped() {
    var mods = ModifierKeys()
    mods.lock(.alt)
    #expect(mods.takeForKey() == (ctrl: false, alt: true))
    #expect(mods.takeForKey() == (ctrl: false, alt: true), "locked persists")
    mods.tap(.alt)
    #expect(mods.alt == .off)
}

@Test func aBarKeyDropsALingeringOneShotButNotALock() {
    var mods = ModifierKeys()
    mods.tap(.ctrl)
    mods.lock(.alt)
    mods.consumeOneShot()
    #expect(mods.ctrl == .off)
    #expect(mods.alt == .locked)
}

// MARK: Font size

@Test func pinchStepsTheFontPastAThresholdWithinBounds() {
    #expect(TerminalFont.stepped(16, scale: 1.05) == nil, "sub-threshold pinch accumulates")
    #expect(TerminalFont.stepped(16, scale: 0.95) == nil)
    #expect(TerminalFont.stepped(16, scale: 1.2) == 18)
    #expect(TerminalFont.stepped(16, scale: 0.8) == 14)
    #expect(TerminalFont.stepped(32, scale: 1.2) == 32, "clamped at the top")
    #expect(TerminalFont.stepped(8, scale: 0.5) == 8, "clamped at the bottom")
}

@Test func theFontSizeSurvivesASaveAndARead() throws {
    let defaults = try #require(UserDefaults(suiteName: "TerminalTests.font"))
    defaults.removePersistentDomain(forName: "TerminalTests.font")
    #expect(TerminalFont.saved(defaults.string(forKey:)) == 16, "default before any save")
    defaults.set(TerminalFont.encode(22), forKey: TerminalFont.key)
    #expect(TerminalFont.saved(defaults.string(forKey:)) == 22)
    defaults.set("99", forKey: TerminalFont.key)
    #expect(TerminalFont.saved(defaults.string(forKey:)) == 32, "out of bounds clamps")
    defaults.set("big", forKey: TerminalFont.key)
    #expect(TerminalFont.saved(defaults.string(forKey:)) == 16)
}

@Test func resetStateClearsTheSavedFontSize() throws {
    let domain = "TerminalTests.reset"
    let defaults = try #require(UserDefaults(suiteName: domain))
    defaults.set(TerminalFont.encode(24), forKey: TerminalFont.key)
    defaults.set(true, forKey: "resetState")
    _ = LaunchOptions.load(from: defaults, domain: domain)
    #expect(TerminalFont.saved(defaults.string(forKey:)) == 16)
    defaults.removePersistentDomain(forName: domain)
}

// MARK: Copy

@Test func exitCopyNamesAnErrorAndOtherwiseSaysTheSessionEnded() {
    #expect(ExitCopy(reason: "error", code: 3) == ExitCopy(title: "terminal disconnected", detail: "ended unexpectedly (code 3)"))
    #expect(ExitCopy(reason: "ended", code: 0) == ExitCopy(title: "session ended", detail: "the terminal process exited"))
    #expect(ExitCopy(reason: "", code: 0).title == "session ended", "never claims what it can't prove")
}

// MARK: Attach

/// Stands in for the client: records each message, and answers each `term_open` with the
/// next termId (t1, t2, …), after `gate` ends when set, or with `refusal`.
@MainActor private final class Wire {
    var messages: [ClientMessage] = []
    var opens: [ClientRequest] = []
    var fed = Data()
    var gate: AsyncStream<Void>?
    var refusal: String?
    /// Answers `term_open` with this frame instead of `term_opened`.
    var reply: ServerFrame?
    private var lastTerm = 0

    func makeSession() -> TerminalSession {
        let s = TerminalSession(target: "term_1", send: { self.messages.append($0) }, request: { try await self.open($0) })
        s.output = { self.fed.append($0) }
        return s
    }

    private func open(_ r: ClientRequest) async throws -> ServerFrame {
        opens.append(r)
        if let gate { for await _ in gate {} }
        if let refusal { throw CompanionError.rejected(refusal) }
        if let reply { return reply }
        lastTerm += 1
        return .termOpened(reqId: "r1", termId: "t\(lastTerm)")
    }
}

/// Connected, in the foreground, emulator laid out at 54×33, attached as t1.
@MainActor private func attached(_ wire: Wire) async -> TerminalSession {
    let s = wire.makeSession()
    s.setConnected(true)
    s.resize(cols: 54, rows: 33)
    await s.attaching?.value
    return s
}

@Test @MainActor func attachesOnceConnectedAndLaidOutWithTheEmulatorsSize() async {
    let wire = Wire()
    let s = wire.makeSession()
    #expect(s.header == TerminalHeaderStatus(label: "connecting…", tone: .working))
    s.setConnected(true)
    #expect(wire.opens.isEmpty, "no attach before the emulator has a size")
    #expect(!s.showsReconnectOverlay, "no scrim before the emulator exists")
    s.resize(cols: 54, rows: 33)
    #expect(s.showsReconnectOverlay, "connecting…")
    await s.attaching?.value
    #expect(wire.opens == [.termOpen(target: "term_1", cols: 54, rows: 33)])
    #expect(s.header == TerminalHeaderStatus(label: "attached", tone: .done))
    #expect(!s.showsReconnectOverlay)
    #expect(s.keysLive)
}

@Test @MainActor func dataAndExitCountOnlyForTheLiveTerminal() async {
    let wire = Wire()
    let s = await attached(wire)
    s.apply(.termData(termId: "t0", data: Data("old".utf8)))
    s.apply(.termData(termId: "t1", data: Data("hi".utf8)))
    #expect(wire.fed == Data("hi".utf8))
    s.apply(.termExit(termId: "t0", code: 1, reason: "error"))
    #expect(s.exit == nil)
}

@Test @MainActor func inputAndResizeGoToTheLiveTerminal() async {
    let wire = Wire()
    let s = wire.makeSession()
    s.input(Data("x".utf8))
    #expect(wire.messages.isEmpty, "nothing to type into yet")
    s.setConnected(true)
    s.resize(cols: 54, rows: 51)
    await s.attaching?.value
    s.resize(cols: 54, rows: 33)
    s.resize(cols: 54, rows: 33)
    s.input(Data("x".utf8))
    #expect(wire.messages == [.termResize(termId: "t1", cols: 54, rows: 33), .termInput(termId: "t1", data: Data("x".utf8))])
}

@Test @MainActor func aDropShowsReconnectingAndTheReconnectAttachesAfresh() async {
    let wire = Wire()
    let s = await attached(wire)
    s.setConnected(false)
    #expect(s.header == TerminalHeaderStatus(label: "reconnecting…", tone: .working))
    #expect(s.showsReconnectOverlay)
    #expect(!s.keysLive)
    s.input(Data("x".utf8))
    #expect(wire.messages.isEmpty, "the companion killed the PTY with the socket")
    s.setConnected(true)
    await s.attaching?.value
    #expect(wire.opens.count == 2)
    s.apply(.termData(termId: "t1", data: Data("stale".utf8)))
    #expect(wire.fed.isEmpty)
    #expect(s.header.label == "attached")
}

@Test @MainActor func goingToTheBackgroundReleasesTheAttachAndComingBackTakesItAgain() async {
    let wire = Wire()
    let s = await attached(wire)
    s.setForeground(false)
    #expect(wire.messages == [.termClose(termId: "t1")])
    #expect(s.header == TerminalHeaderStatus(label: "paused", tone: .idle))
    s.apply(.termExit(termId: "t1", code: 0, reason: "closed"))
    #expect(s.exit == nil, "our own release is not an exit")
    s.setConnected(false)
    s.setConnected(true)
    #expect(s.attaching == nil, "a backgrounded screen never re-attaches")
    s.setForeground(true)
    await s.attaching?.value
    #expect(wire.opens.count == 2)
    #expect(s.header.label == "attached")
}

@Test @MainActor func anAttachThatLandsInTheBackgroundIsHandedStraightBack() async {
    let wire = Wire()
    let s = wire.makeSession()
    let (gate, open) = AsyncStream<Void>.makeStream()
    wire.gate = gate
    s.setConnected(true)
    s.resize(cols: 80, rows: 24)
    s.setForeground(false)
    open.finish()
    await s.attaching?.value
    #expect(wire.messages == [.termClose(termId: "t1")])
    #expect(!s.keysLive)
}

@Test @MainActor func aRefusedAttachShowsFailed() async {
    let wire = Wire()
    wire.refusal = "too many terminals"
    let s = await attached(wire)
    #expect(s.header == TerminalHeaderStatus(label: "failed", tone: .blocked))
    #expect(s.status == .failed("too many terminals"))
}

@Test @MainActor func anExitShowsTheOverlayAndReattachOpensANewTerminal() async {
    let wire = Wire()
    let s = await attached(wire)
    s.apply(.termExit(termId: "t1", code: 2, reason: "error"))
    #expect(s.exit == ExitCopy(title: "terminal disconnected", detail: "ended unexpectedly (code 2)"))
    #expect(s.header == TerminalHeaderStatus(label: "terminal disconnected", tone: .blocked))
    #expect(!s.showsReconnectOverlay, "the exit overlay owns the screen")
    #expect(!s.keysLive)
    await s.reattach()
    #expect(wire.opens.count == 2)
    #expect(s.exit == nil)
    #expect(s.header.label == "attached")
}

@Test @MainActor func closingReleasesTheAttach() async {
    let wire = Wire()
    let s = await attached(wire)
    s.close()
    #expect(wire.messages == [.termClose(termId: "t1")])
}


// MARK: Review fixes (CPE-2139 review, turn 3)

@Test @MainActor func aReattachBeforeTheEmulatorHasASizeDoesNotBlockTheAttach() async {
    let wire = Wire()
    let s = wire.makeSession()
    s.setConnected(true)
    await s.reattach() // nothing to open yet
    s.resize(cols: 54, rows: 33)
    await s.attaching?.value
    #expect(wire.opens == [.termOpen(target: "term_1", cols: 54, rows: 33)])
    #expect(s.header.label == "attached")
}

@Test @MainActor func anUnexpectedReplyToAnAttachDoesNotBlockTheNext() async {
    let wire = Wire()
    wire.reply = .pong
    let s = await attached(wire)
    wire.reply = nil
    s.setConnected(false)
    s.setConnected(true)
    await s.attaching?.value
    #expect(wire.opens.count == 2)
    #expect(s.header.label == "attached")
}
