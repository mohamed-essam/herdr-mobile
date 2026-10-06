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
            if let url = options.companionURL {
                ConnectionScreen(url: url)
            } else {
                Text("No companion")
                    .accessibilityIdentifier("connectionState")
            }
        }
    }
}
