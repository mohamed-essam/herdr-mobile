import Foundation
import Testing
@testable import HerdrKit

// The saved server list: migration from the single saved URL, the -companionURL switch,
// and add, switch, rename and remove. Stored as plain strings, as UserDefaults keeps them.

private func url(_ s: String) -> URL { URL(string: s)! }

/// What the app saves, read back the way it reads UserDefaults.
private struct Defaults {
    var values: [String: String] = [:]
    mutating func save(_ s: SavedServers) { for (k, v) in s.stored { values[k] = v } }
    func load(legacyURL: String? = nil) -> SavedServers { SavedServers(reading: { values[$0] }, legacyURL: legacyURL) }
}

@Test func theSingleSavedURLBecomesTheActiveServerAndReadsBack() {
    var d = Defaults()
    let migrated = d.load(legacyURL: "ws://100.64.0.1:8787")
    #expect(migrated.servers == [Server(id: "default", name: "100.64.0.1:8787", url: url("ws://100.64.0.1:8787"))])
    #expect(migrated.active?.id == "default")
    d.save(migrated)
    #expect(d.load() == migrated, "saved, then read back without the old key")
    #expect(d.load(legacyURL: "ws://other:8787") == migrated, "a saved list wins over the old key")
}

@Test func nothingSavedIsNoServers() {
    #expect(Defaults().load().servers.isEmpty)
    #expect(Defaults().load(legacyURL: "not a url").servers.isEmpty)
    #expect(Defaults(values: [SavedServers.serversKey: "{corrupt"]).load().servers.isEmpty, "never a crash at launch")
}

@Test func anActiveIdThatIsGoneFallsBackToTheFirstServer() {
    var d = Defaults()
    var s = d.load()
    let a = s.add(url: url("ws://a:8787"))
    _ = s.add(url: url("ws://b:8787"))
    d.save(s)
    d.values[SavedServers.activeKey] = "gone"
    #expect(d.load().active == a)
}

@Test func addingSavesTheServerAndMakesItActive() {
    var s = Defaults().load()
    let a = s.add(url: url("ws://a:8787"))
    let b = s.add(url: url("ws://b.tail.ts.net:8787"))
    #expect(a.name == "a:8787" && b.name == "b.tail.ts.net:8787" && a.id != b.id)
    #expect(s.servers == [a, b] && s.active == b)
    s.activate(a.id)
    #expect(s.active == a)
    s.activate("unknown")
    #expect(s.active == a)
}

@Test func theSameHostAndPortIsSavedOnce() {
    var s = Defaults().load()
    let a = s.add(url: url("ws://Box.local:8787"))
    #expect(s.savedAt(url("ws://box.LOCAL:8787/")) == a)
    #expect(s.savedAt(url("wss://box.local:8787")) == a)
    #expect(s.savedAt(url("ws://box.local:9999")) == nil)
}

@Test func renamingTrimsAndABlankNameFallsBackToTheAddress() {
    var s = Defaults().load()
    let a = s.add(url: url("ws://a:8787"))
    s.rename(a.id, to: "  laptop ")
    #expect(s.servers.first?.name == "laptop")
    s.rename(a.id, to: "   ")
    #expect(s.servers.first?.name == "a:8787")
}

@Test func removingTheActiveServerActivatesTheFirstLeft() {
    var s = Defaults().load()
    let a = s.add(url: url("ws://a:8787"))
    let b = s.add(url: url("ws://b:8787"))
    let c = s.add(url: url("ws://c:8787"))
    s.activate(b.id)
    s.remove(a.id)
    #expect(s.servers == [b, c] && s.active == b, "another server's removal keeps the active one")
    s.remove(b.id)
    #expect(s.active == c)
    s.remove(c.id)
    #expect(s.servers.isEmpty && s.active == nil)
    var d = Defaults()
    d.save(s)
    #expect(d.values[SavedServers.activeKey] == nil && d.load() == s)
}

@Test func theLaunchSwitchPicksTheSavedServerAtItsAddressOrAddsIt() {
    var s = Defaults().load(legacyURL: "ws://127.0.0.1:18787")
    let b = s.add(url: url("ws://b:8787"))
    s.useLaunchURL(url("ws://127.0.0.1:18787"))
    #expect(s.active?.id == "default" && s.servers.count == 2)
    s.useLaunchURL(url("ws://localhost:18787"))
    #expect(s.active?.name == "localhost:18787" && s.servers.count == 3)
    s.useLaunchURL(url("ws://b:8787"))
    #expect(s.active == b)
}
