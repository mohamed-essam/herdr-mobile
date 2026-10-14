import Foundation

// The connect and first-connection screens' logic, the repo monogram, and the New
// Agent picker's catalog. Ported from Android's Onboarding.kt, RepoAvatar.kt,
// AgentCatalog.kt, AgentCommand.kt and Settings.kt.

public let defaultCompanionPort = 8787

/// The command the connect screen tells the user to run on their host.
public let companionCommand = #"herdr-mobiled --listen "$(tailscale ip -4):\#(defaultCompanionPort)""#

/// What the user typed as the companion's address. `hasScheme` and `hasPort` say
/// whether they typed those, so the field shows only the `ws://` and `:8787` it adds.
public struct CompanionAddress: Equatable, Sendable {
    public let url: URL
    public let hostPort: String
    public let hasScheme: Bool
    public let hasPort: Bool

    /// A bare host gets `ws://` and the default port; `host:port` keeps its port; a
    /// `ws://` or `wss://` URL is taken as is. Nil when blank, with whitespace inside,
    /// with another scheme, or not a URL.
    public init?(parsing input: String) {
        let t = input.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, !t.contains(where: \.isWhitespace) else { return nil }
        if let sep = t.range(of: "://") {
            guard ["ws", "wss"].contains(t[..<sep.lowerBound].lowercased()) else { return nil }
            let authority = String(t[sep.upperBound...].prefix { $0 != "/" })
            guard !authority.isEmpty, !authority.hasPrefix(":") else { return nil }
            self.init(url: t, hostPort: authority, hasScheme: true, hasPort: Self.hasPort(authority))
            return
        }
        var authority = String(t.prefix { $0 != "/" })
        let path = t.dropFirst(authority.count)
        if authority.count(where: { $0 == ":" }) == 1 && authority.hasSuffix(":") { authority.removeLast() }
        guard !authority.isEmpty, !authority.hasPrefix(":") else { return nil }
        let hasPort = Self.hasPort(authority)
        let hostPort = if hasPort {
            authority
        } else if authority.count(where: { $0 == ":" }) >= 2 && !authority.hasPrefix("[") {
            "[\(authority)]:\(defaultCompanionPort)" // a bare IPv6 address needs brackets before a port
        } else {
            "\(authority):\(defaultCompanionPort)"
        }
        self.init(url: "ws://\(hostPort)\(path)", hostPort: hostPort, hasScheme: false, hasPort: hasPort)
    }

    init?(url: String, hostPort: String, hasScheme: Bool, hasPort: Bool) {
        guard let url = URL(string: url) else { return nil }
        self.url = url
        self.hostPort = hostPort
        self.hasScheme = hasScheme
        self.hasPort = hasPort
    }

    private static func hasPort(_ authority: String) -> Bool {
        let bracketed = authority.hasPrefix("[")
        if !bracketed && authority.count(where: { $0 == ":" }) != 1 { return false }
        let afterHost = bracketed ? authority.split(separator: "]", maxSplits: 1).dropFirst().first.map(String.init) ?? "" : authority
        guard let colon = afterHost.firstIndex(of: ":") else { return false }
        let port = afterHost[afterHost.index(after: colon)...]
        return !port.isEmpty && port.allSatisfy(\.isASCII) && port.allSatisfy(\.isNumber)
    }
}

public enum StepState: Sendable { case done, current, upcoming }

/// Step 1 is an instruction, always done; step 2 is current until a host is entered, then step 3.
public func connectStepStates(hostEntered: Bool) -> [StepState] {
    [.done, hostEntered ? .done : .current, hostEntered ? .current : .upcoming]
}

/// Where the first-connection screen is: connected once the socket has ever opened
/// (a later blip doesn't take that away), slow once `slowAfter` passed without it.
public enum FirstConnectPhase: Sendable {
    case connecting, slow, connected

    /// How long the first connection may take before the screen offers Back.
    public static let slowAfter = Duration.seconds(10)

    public init(everConnected: Bool, timedOut: Bool) {
        self = everConnected ? .connected : timedOut ? .slow : .connecting
    }
}

/// The herd at a glance: its size, and how many agents need you, work, or are done.
public struct HerdSummary: Equatable, Sendable {
    public let repos: Int
    public let workspaces: Int
    public let panes: Int
    public let needYou: Int
    public let working: Int
    public let done: Int

    init(repos: Int, workspaces: Int, panes: Int, needYou: Int, working: Int, done: Int) {
        (self.repos, self.workspaces, self.panes) = (repos, workspaces, panes)
        (self.needYou, self.working, self.done) = (needYou, working, done)
    }

    /// The orphans workspace isn't a workspace.
    public init(repos: [RepoNode]) {
        let panes = repos.flatMap { $0.workspaces.flatMap(\.panes) }
        self.repos = repos.count { $0.workspaces.contains { !$0.isOrphans } }
        workspaces = repos.reduce(0) { $0 + $1.workspaces.count { !$0.isOrphans } }
        self.panes = panes.count
        needYou = panes.count { $0.agentStatus == .blocked }
        working = panes.count { $0.agentStatus == .working }
        done = panes.count { $0.agentStatus == .done }
    }

    /// "3 repos · 5 workspaces · 7 panes".
    public var line: String {
        [(repos, "repo"), (workspaces, "workspace"), (panes, "pane")]
            .map(counted).joined(separator: " · ")
    }
}

/// The first one or two letters or digits of `name`, uppercased; "?" when none.
public func monogram(_ name: String) -> String {
    let letters = name.filter { $0.isLetter || $0.isNumber }
    return letters.isEmpty ? "?" : String(letters.prefix(2)).uppercased()
}

// MARK: Agents

private let agentDescriptions = [
    "claude": "Anthropic Claude Code",
    "codex": "OpenAI Codex CLI",
    "gemini": "Google Gemini CLI",
    "cursor": "Cursor agent",
    "copilot": "GitHub Copilot CLI",
    "cline": "Cline coding agent",
    "opencode": "OpenCode agent",
    "amp": "Sourcegraph Amp",
    "grok": "xAI Grok CLI",
    "droid": "Factory Droid",
    "kimi": "Moonshot Kimi CLI",
    "devin": "Cognition Devin",
]

/// One line about a well-known agent; nil for the rest.
public func describeAgent(_ name: String) -> String? { agentDescriptions[name.lowercased()] }

/// Case-insensitive substring filter; a blank query keeps them all.
public func filterAgents(_ all: [String], query: String) -> [String] {
    let q = query.trimmingCharacters(in: .whitespaces)
    return q.isEmpty ? all : all.filter { $0.localizedCaseInsensitiveContains(q) }
}

/// An "Other…" agent command: argv split on whitespace (no shell quoting), named
/// after argv[0]'s basename.
public struct AgentCommand: Equatable, Sendable {
    public let name: String
    public let argv: [String]

    public init(name: String, argv: [String]) {
        self.name = name
        self.argv = argv
    }

    public init(parsing input: String) {
        argv = input.split(whereSeparator: \.isWhitespace).map(String.init)
        name = argv.first.map(pathBase) ?? ""
    }
}

/// Most recent first, without duplicates, at most `cap`.
public func updatedMru(_ current: [String], picked: String, cap: Int = 5) -> [String] {
    Array(([picked] + current.filter { $0 != picked }).prefix(cap))
}
