import HerdrKit
import SwiftUI

@main
struct HerdrMobileApp: App {
    private let options: LaunchOptions

    init() {
        options = LaunchOptions.load(from: .standard, domain: Bundle.main.bundleIdentifier!)
        if options.disableAnimations {
            UIView.setAnimationsEnabled(false)
        }
    }

    var body: some Scene {
        WindowGroup {
            RootView(savedURL: options.companionURL)
                .environment(\.launchOptions, options)
        }
    }
}

/// UserDefaults keys the app saves. `-resetState` clears them all.
enum SettingsKey {
    /// The companion URL, saved once the first connection lands. Also the `-companionURL` switch.
    static let companionURL = LaunchOptions.companionURLKey
    /// Most recent first.
    static let recentAgents = "recentAgents"
}

/// One companion connection and the dashboard state it feeds.
@MainActor final class Session {
    let client: CompanionClient
    let store: DashboardStore

    init(url: URL) {
        let client = CompanionClient(url: url)
        self.client = client
        store = DashboardStore(
            recentAgents: UserDefaults.standard.stringArray(forKey: SettingsKey.recentAgents) ?? [],
            saveRecentAgents: { UserDefaults.standard.set($0, forKey: SettingsKey.recentAgents) },
            request: { try await client.request($0) })
    }

    /// Connects, and feeds every frame to the store, until cancelled.
    func run() async {
        async let connection: Void = client.run()
        for await frame in client.frames() { store.apply(frame) } // ends when cancelled
        await connection
    }
}

/// Setup, the first connection, or the dashboard. With a saved (or `-companionURL`)
/// companion the app opens on the dashboard.
struct RootView: View {
    private enum Route { case connect, firstConnect(CompanionAddress), dashboard }

    @State private var route: Route
    @State private var session: Session?
    /// What the connect screen shows again after going back from a failed first attempt.
    @State private var draft = ""
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.herdr) private var c

    init(savedURL: URL?) {
        _route = State(initialValue: savedURL == nil ? .connect : .dashboard)
        _session = State(initialValue: savedURL.map(Session.init))
    }

    var body: some View {
        Group {
            switch route {
            case .connect:
                ConnectScreen(input: $draft) { address in
                    session = Session(url: address.url)
                    route = .firstConnect(address)
                }
            case .firstConnect(let address):
                FirstConnectionScreen(
                    client: session?.client, store: session?.store, hostPort: address.hostPort,
                    onConnected: { UserDefaults.standard.set(address.url.absoluteString, forKey: SettingsKey.companionURL) },
                    onContinue: { route = .dashboard },
                    onBack: { session = nil; route = .connect })
            case .dashboard:
                if let session { DashboardScreen(client: session.client, store: session.store) }
            }
        }
        .background(c.crust)
        .task(id: session.map(ObjectIdentifier.init)) { await session?.run() }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { session?.client.reconnectNow() }
        }
    }
}
