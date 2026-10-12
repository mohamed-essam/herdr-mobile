import HerdrKit
import LuciqSDK
import os
import SwiftUI

/// Crash, bug and session-replay reports through Luciq, on only when the build carries an app
/// token (`LuciqAppToken` in Info.plist, from ios/Config/Luciq.xcconfig). Shake, or take a
/// screenshot, to report a bug.
enum LuciqReports {
    /// Starts the SDK when this build has a token. Logs whether it did, never the token.
    static func start() {
        let log = Logger(subsystem: Bundle.main.bundleIdentifier ?? "herdr", category: "luciq")
        guard let token = LuciqToken(parsing: Bundle.main.object(forInfoDictionaryKey: "LuciqAppToken") as? String) else {
            log.notice("Luciq off: this build has no app token")
            return
        }
        // Terminal output and agent text can hold code and secrets. The companion socket is the
        // app's only traffic, so it is never intercepted, and user and repro steps keep no view text.
        // Screenshots and replay frames black out views marked `maskedInReports()`.
        NetworkLogger.disableAutomaticCapturingOfNetworkLogs()
        Luciq.viewsContentCaptureEnabled = false
        Luciq.start(withToken: token.value, invocationEvents: [.shake, .screenshot])
        // Never over a flow: a fresh simulator's first launch would show it.
        Luciq.welcomeMessageMode = .disabled
        log.notice("Luciq on")
    }
}

extension View {
    /// Black in every Luciq screenshot and replay frame: for terminal output and agent-written text.
    func maskedInReports() -> some View { luciq_privateView() }
}
