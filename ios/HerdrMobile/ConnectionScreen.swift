import HerdrKit
import SwiftUI

/// M1 placeholder: the connection state, until the dashboard replaces it.
struct ConnectionScreen: View {
    @State private var client: CompanionClient
    @Environment(\.scenePhase) private var scenePhase

    init(url: URL) { _client = State(initialValue: CompanionClient(url: url)) }

    var body: some View {
        Text(client.state.label)
            .accessibilityIdentifier("connectionState")
            .task { await client.run() }
            .onChange(of: scenePhase) { _, phase in
                if phase == .active { client.reconnectNow() }
            }
    }
}

private extension ConnectionState {
    var label: String {
        switch self {
        case .connecting: "Connecting…"
        case .connected: "Connected"
        case .reconnecting: "Reconnecting…"
        }
    }
}
