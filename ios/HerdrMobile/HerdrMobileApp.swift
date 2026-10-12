import HerdrKit
import SwiftUI

@main
struct HerdrMobileApp: App {
    private let options: LaunchOptions
    private let servers: SavedServers

    init() {
        LuciqReports.start() // first, to catch everything after launch
        let defaults = UserDefaults.standard
        let domain = Bundle.main.bundleIdentifier!
        options = LaunchOptions.load(from: defaults, domain: domain)
        if options.disableAnimations {
            UIView.setAnimationsEnabled(false)
        }
        // The companion URL saved before there were several servers: the app's own domain
        // only, since `-companionURL` arrives under the same key in the argument domain.
        let legacy = defaults.persistentDomain(forName: domain)?[SettingsKey.companionURL] as? String
        var servers = SavedServers(reading: defaults.string(forKey:), legacyURL: legacy)
        if let url = options.companionURL { servers.useLaunchURL(url) }
        save(servers)
        defaults.removeObject(forKey: SettingsKey.companionURL)
        self.servers = servers
    }

    var body: some Scene {
        WindowGroup {
            RootView(servers: servers, now: { [options] in options.now() })
                .environment(\.launchOptions, options)
        }
    }
}

/// UserDefaults keys the app saves. `-resetState` clears them all.
enum SettingsKey {
    /// The single companion URL of older versions, moved into the server list at launch.
    /// Also the `-companionURL` switch.
    static let companionURL = LaunchOptions.companionURLKey
    /// Most recent first.
    static let recentAgents = "recentAgents"
    /// The terminal's pinch-zoomed font size.
    static let terminalFontSize = TerminalFont.key
}

private func save(_ servers: SavedServers) {
    for (key, value) in servers.stored { UserDefaults.standard.set(value, forKey: key) }
}

/// One companion connection and the dashboard and chat state it feeds.
@MainActor final class Session {
    /// The saved server it connects to; for a server being added, the id it will be saved under.
    let serverId: String
    let client: CompanionClient
    let store: DashboardStore
    let chat: ChatStore

    /// `now` is the app's clock (`-fixedNow` freezes it).
    init(serverId: String, url: URL, now: @escaping @Sendable () -> Date) {
        self.serverId = serverId
        let client = CompanionClient(url: url)
        self.client = client
        chat = ChatStore(send: { client.send($0) }, request: { try await client.request($0) }, now: { now().epochMs })
        store = DashboardStore(
            recentAgents: UserDefaults.standard.stringArray(forKey: SettingsKey.recentAgents) ?? [],
            saveRecentAgents: { UserDefaults.standard.set($0, forKey: SettingsKey.recentAgents) },
            request: { try await client.request($0) })
    }

    convenience init(_ server: Server, now: @escaping @Sendable () -> Date) {
        self.init(serverId: server.id, url: server.url, now: now)
    }

    /// Connects, and feeds every frame to the stores, until cancelled.
    func run() async {
        async let connection: Void = client.run()
        async let images: Void = feedImages()
        for await frame in client.frames() { // ends when cancelled
            store.apply(frame)
            chat.apply(frame)
        }
        await connection
        await images
    }

    private func feedImages() async {
        for await image in client.images() { chat.apply(image: image) }
    }
}

/// Setup (or adding a server), the first connection, or the dashboard. With a saved (or
/// `-companionURL`) server the app opens on the dashboard. The live session says which
/// server is active. Ported from Android's MainActivity.kt.
struct RootView: View {
    /// While connecting, a saved server means one is being added from the dashboard: Back
    /// returns there. `servers.active` stays the previous one until the new one connects.
    private enum Route {
        case connect
        case firstConnect(CompanionAddress)
        case dashboard
    }

    @State private var route: Route
    @State private var session: Session?
    @State private var servers: SavedServers
    /// What the connect screen shows again after going back from a failed first attempt.
    @State private var draft = ""
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.herdr) private var c

    private let now: @Sendable () -> Date

    init(servers: SavedServers, now: @escaping @Sendable () -> Date) {
        self.now = now
        _servers = State(initialValue: servers)
        _route = State(initialValue: servers.active == nil ? .connect : .dashboard)
        _session = State(initialValue: servers.active.map { Session($0, now: now) })
    }

    var body: some View {
        Group {
            switch route {
            case .connect:
                ConnectScreen(input: $draft, duplicateOf: { servers.savedAt($0.url)?.name },
                              onBack: servers.active == nil ? nil : { route = .dashboard }) { address in
                    session = Session(serverId: UUID().uuidString, url: address.url, now: now)
                    route = .firstConnect(address)
                }
            case .firstConnect(let address):
                FirstConnectionScreen(
                    client: session?.client, store: session?.store, hostPort: address.hostPort,
                    onConnected: {
                        // Only now is the address worth keeping: it joins the list as the active server.
                        if let id = session?.serverId { servers.add(url: address.url, id: id) }
                    },
                    onContinue: { route = .dashboard },
                    onBack: {
                        // Once saved, the connect screen would refuse its address as a duplicate.
                        if session.flatMap({ server($0.serverId) }) != nil {
                            route = .dashboard
                            return
                        }
                        // Drop the failed attempt; adding a server puts the previous one back.
                        connect(to: servers.active)
                        route = .connect
                    })
            case .dashboard:
                if let session {
                    DashboardScreen(client: session.client, store: session.store, chat: session.chat,
                                    servers: serverControls(activeId: session.serverId))
                        .id(ObjectIdentifier(session))
                }
            }
        }
        .background(c.crust)
        .task(id: session.map(ObjectIdentifier.init)) { await session?.run() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { session?.client.reconnectNow() }
        }
        .onChange(of: servers) { _, s in save(s) }
    }

    private func server(_ id: String) -> Server? { servers.servers.first { $0.id == id } }

    /// Drops the current connection and connects to `server`, or to none.
    private func connect(to server: Server?) {
        session = server.map { Session($0, now: now) }
    }

    private func serverControls(activeId: String) -> ServerControls {
        ServerControls(
            servers: servers.servers, activeId: activeId,
            switchTo: { s in
                guard s.id != session?.serverId else { return }
                servers.activate(s.id)
                connect(to: s)
            },
            rename: { s, name in servers.rename(s.id, to: name) },
            remove: { s in
                servers.remove(s.id)
                guard s.id == session?.serverId else { return }
                connect(to: servers.active)
                if session == nil {
                    draft = ""
                    route = .connect
                }
            },
            add: {
                draft = ""
                route = .connect
            })
    }
}
