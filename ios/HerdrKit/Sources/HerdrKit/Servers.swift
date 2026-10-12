import Foundation

// The saved companions: switch, add, rename and remove, and the move from the single
// saved URL. Ported from Android's Servers.kt and Settings.kt.

/// A saved companion.
public struct Server: Codable, Equatable, Sendable, Identifiable {
    public let id: String
    public var name: String
    public let url: URL

    public init(id: String, name: String, url: URL) {
        (self.id, self.name, self.url) = (id, name, url)
    }

    /// `host:port`, its name when the user gives none.
    public var hostPort: String { Self.hostPort(url) }

    static func hostPort(_ url: URL) -> String {
        let host = url.host() ?? ""
        let h = host.contains(":") ? "[\(host)]" : host
        return url.port.map { "\(h):\($0)" } ?? h
    }
}

/// The saved servers and which one is active, as kept under `serversKey` and `activeKey`.
public struct SavedServers: Equatable, Sendable {
    public static let serversKey = "servers"
    public static let activeKey = "activeServer"
    /// The id the server moved from the single saved URL gets.
    static let legacyId = "default"

    public private(set) var servers: [Server]
    private var activeId: String?

    /// Reads the list through `string` (UserDefaults' `string(forKey:)`). With no list saved
    /// yet, `legacyURL` (the companion URL saved before there were several) becomes the one
    /// server. A missing or corrupt list reads as none.
    public init(reading string: (String) -> String?, legacyURL: String?) {
        if let saved = string(Self.serversKey) {
            servers = (try? JSONDecoder().decode([Server].self, from: Data(saved.utf8))) ?? []
            activeId = string(Self.activeKey)
        } else if let url = legacyURL.flatMap(URL.init(string:)), url.host() != nil {
            servers = [Server(id: Self.legacyId, name: Server.hostPort(url), url: url)]
            activeId = Self.legacyId
        } else {
            servers = []
        }
    }

    /// The active server: the one chosen last, else the first.
    public var active: Server? { servers.first { $0.id == activeId } ?? servers.first }

    /// What to save, by key; nil removes the key.
    public var stored: [String: String?] {
        [Self.serversKey: String(decoding: (try? JSONEncoder().encode(servers)) ?? Data("[]".utf8), as: UTF8.self),
         Self.activeKey: active?.id]
    }

    /// The server saved at `url`'s host and port (any case), so one companion isn't saved twice.
    public func savedAt(_ url: URL) -> Server? {
        let hp = Server.hostPort(url).lowercased()
        return servers.first { $0.hostPort.lowercased() == hp }
    }

    /// Saves `url` as a new server, named by its address, and makes it active.
    @discardableResult
    public mutating func add(url: URL, id: String = UUID().uuidString) -> Server {
        let s = Server(id: id, name: Server.hostPort(url), url: url)
        servers.append(s)
        activeId = s.id
        return s
    }

    public mutating func activate(_ id: String) {
        if servers.contains(where: { $0.id == id }) { activeId = id }
    }

    /// A blank name falls back to the address.
    public mutating func rename(_ id: String, to name: String) {
        guard let i = servers.firstIndex(where: { $0.id == id }) else { return }
        servers[i].name = name.trimmed.isEmpty ? servers[i].hostPort : name.trimmed
    }

    /// Removing the active server makes the first one left active.
    public mutating func remove(_ id: String) {
        servers.removeAll { $0.id == id }
        if !servers.contains(where: { $0.id == activeId }) { activeId = servers.first?.id }
    }

    /// The `-companionURL` switch: the server saved at that address becomes active, or it is added.
    public mutating func useLaunchURL(_ url: URL) {
        if let s = savedAt(url) { activeId = s.id } else { add(url: url) }
    }
}
