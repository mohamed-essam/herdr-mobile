import HerdrKit
import SwiftUI

/// M1 placeholder: the connection state, until the dashboard replaces it.
struct ConnectionScreen: View {
    @State private var client: CompanionClient

    init(url: URL) { _client = State(initialValue: CompanionClient(url: url)) }

    var body: some View {
        Text(client.state.label)
            .accessibilityIdentifier("connectionState")
            .task { await client.run() }
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
