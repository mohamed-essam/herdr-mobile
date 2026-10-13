import Foundation
import Testing
@testable import HerdrKit

/// Launch arguments reach the app as UserDefaults strings (`-companionURL ws://…`).
private func options(_ values: [String: String]) -> LaunchOptions {
    LaunchOptions(reading: { values[$0] })
}

@Test func emptyDefaultsMeanNoCompanionAndRealTime() {
    let o = options([:])
    #expect(o.companionURL == nil)
    #expect(o.disableAnimations == false)
    #expect(o.fixedNow == nil)
}

@Test(arguments: ["ws://127.0.0.1:18787", "wss://box.tailnet.ts.net:8787"])
func acceptsWebSocketURLs(_ url: String) {
    #expect(options(["companionURL": url]).companionURL == URL(string: url))
}

/// The connect screen saves the address under the same key; any address it accepts must
/// read back on the next launch (code review #3: "WS://" was saved, then rejected).
@Test(arguments: ["WS://box:1", "Wss://box.ts.net/ws", "box"])
func readsBackWhatTheConnectScreenSaves(_ input: String) throws {
    let saved = try #require(CompanionAddress(parsing: input)).url.absoluteString
    #expect(options([LaunchOptions.companionURLKey: saved]).companionURL?.absoluteString == saved)
}

@Test(arguments: ["http://127.0.0.1:8787", "127.0.0.1:8787", "ws://", ""])
func rejectsAnythingButAWebSocketURL(_ url: String) {
    #expect(options(["companionURL": url]).companionURL == nil)
}

@Test func disableAnimationsTakesYes() {
    #expect(options(["disableAnimations": "YES"]).disableAnimations)
}

@Test func fixedNowFreezesTheClock() {
    let o = options(["fixedNow": "2026-10-06T09:30:00Z"])
    let want = Date(timeIntervalSince1970: 1_791_279_000)
    #expect(o.fixedNow == want)
    #expect(o.now() == want)
}

@Test func unparsableFixedNowIsIgnored() {
    #expect(options(["fixedNow": "yesterday"]).fixedNow == nil)
}

@Test func resetStateClearsSavedSettingsBeforeReading() throws {
    let domain = "LaunchOptionsTests.resetState"
    let defaults = try #require(UserDefaults(suiteName: domain))
    defer { defaults.removePersistentDomain(forName: domain) }
    defaults.set("ws://saved:8787", forKey: "companionURL")
    #expect(LaunchOptions.load(from: defaults, domain: domain).companionURL == URL(string: "ws://saved:8787"))

    defaults.set(true, forKey: "resetState")
    #expect(LaunchOptions.load(from: defaults, domain: domain).companionURL == nil)
}
