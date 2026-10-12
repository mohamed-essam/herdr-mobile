import HerdrKit
import SwiftTerm
import SwiftUI

/// A pane's live terminal: the pane header with the attach status, SwiftTerm's emulator, the
/// reconnect scrim or exit overlay over it, and the key bar under it. The attach rules live
/// in `TerminalSession`. Ported from Android's TerminalScreen.kt.
struct TerminalScreen: View {
    let context: PaneContext
    let client: CompanionClient
    let onExit: () -> Void
    /// Back to the chat; nil while the pane has none.
    let onChat: (() -> Void)?
    @State private var session: TerminalSession
    @State private var mods = ModifierKeys()
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.herdr) private var c

    init(pane: Pane, context: PaneContext, client: CompanionClient, onExit: @escaping () -> Void, onChat: (() -> Void)?) {
        self.context = context
        self.client = client
        self.onExit = onExit
        self.onChat = onChat
        _session = State(initialValue: TerminalSession(target: pane.terminalId, send: { client.send($0) },
                                                       request: { try await client.request($0) }))
    }

    var body: some View {
        let header = session.header
        VStack(spacing: 0) {
            PaneHeader(context: context, breadcrumb: context.terminalBreadcrumb, status: header.label,
                       statusColor: c.status(header.tone), onBack: onExit,
                       toggle: onChat.map { PaneHeader.Toggle(label: "chat", id: "chatToggle", action: $0) })
            ZStack {
                TerminalSurface(session: session, mods: $mods)
                    .accessibilityIdentifier("terminal")
                if let exit = session.exit {
                    ExitOverlay(copy: exit) { Task { await session.reattach() } }
                } else if session.showsReconnectOverlay {
                    ReconnectScrim(label: session.label)
                }
            }
            KeyBar(mods: $mods, enabled: session.keysLive) { key in
                session.input(key.bytes)
                mods.consumeOneShot() // bar keys don't combine with a modifier
            }
        }
        .background(c.crust)
        // Subscribed before the attach can be sent, so no reply is missed.
        .task { for await frame in client.terminal() { session.apply(frame) } }
        .onChange(of: client.state == .connected, initial: true) { _, up in session.setConnected(up) }
        // A partly covered app (.inactive) is still worth keeping live.
        .onChange(of: scenePhase, initial: true) { _, phase in session.setForeground(phase != .background) }
        .onDisappear { session.close() }
    }
}

/// "reconnecting…" (or "connecting…") over a terminal that isn't live; it swallows taps so a
/// dead terminal doesn't raise the keyboard.
private struct ReconnectScrim: View {
    let label: String
    @Environment(\.herdr) private var c

    var body: some View {
        ZStack {
            c.crust.opacity(0.6).contentShape(.rect).onTapGesture {}
            HStack(spacing: 8) {
                Spinner(font: HerdrFont.badge).foregroundStyle(c.yellow).accessibilityHidden(true)
                Text(label).font(HerdrFont.code).foregroundStyle(c.subtext1)
                    .accessibilityIdentifier("terminalReconnecting")
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
        }
    }
}

/// The terminal ended: why, and "Reattach".
private struct ExitOverlay: View {
    let copy: ExitCopy
    let onReattach: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(spacing: 0) {
            Text("⚠").font(.system(size: 22, design: .monospaced)).foregroundStyle(c.red).accessibilityHidden(true)
            Text(copy.title).font(HerdrFont.title).foregroundStyle(c.text).multilineTextAlignment(.center)
                .padding(.top, 8)
                .accessibilityIdentifier("terminalExit")
            Text(copy.detail).font(HerdrFont.small).foregroundStyle(c.overlay2).multilineTextAlignment(.center)
                .padding(.top, 4)
                .accessibilityIdentifier("terminalExitDetail")
            Button("Reattach", action: onReattach).buttonStyle(PrimaryButtonStyle()).padding(.top, 24)
                .accessibilityIdentifier("reattach")
        }
        .padding(24)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
        .padding(24)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(c.crust)
    }
}

// MARK: Emulator

/// SwiftTerm's view, with the key bar's ctrl and alt applied to each typed key (soft or
/// hardware keyboard) as on Android.
final class HerdrTerminalView: TerminalView {
    /// Which modifiers apply to the next typed key; clears the one-shots.
    var takeModifiers: () -> (ctrl: Bool, alt: Bool) = { (false, false) }
    /// The first layout's size, for when SwiftTerm reports no change from its 80×24 start.
    var onFirstLayout: ((Int, Int) -> Void)?

    override func insertText(_ text: String) {
        let mods = takeModifiers()
        if mods.alt { send([0x1b]) }
        controlModifier = mods.ctrl
        super.insertText(text)
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        guard !bounds.isEmpty, let first = onFirstLayout else { return }
        onFirstLayout = nil
        first(getTerminal().cols, getTerminal().rows)
        _ = becomeFirstResponder()
    }
}

/// SwiftTerm in SwiftUI: output from the session, typed bytes and size changes to it,
/// pinch to resize the font (saved), long press for Select / Copy / Paste.
private struct TerminalSurface: UIViewRepresentable {
    let session: TerminalSession
    @Binding var mods: ModifierKeys

    func makeCoordinator() -> Coordinator { Coordinator(session: session) }

    func makeUIView(context: Context) -> HerdrTerminalView {
        let size = TerminalFont.saved(UserDefaults.standard.string(forKey:))
        let view = HerdrTerminalView(frame: .zero, font: terminalFont(size))
        view.terminalDelegate = context.coordinator
        view.inputAccessoryView = nil // the key bar replaces SwiftTerm's own
        view.applyCatppuccin()
        view.onFirstLayout = { [session] cols, rows in session.resize(cols: cols, rows: rows) }
        view.addGestureRecognizer(UIPinchGestureRecognizer(target: context.coordinator, action: #selector(Coordinator.pinch(_:))))
        session.output = { [weak view] data in view?.feed(byteArray: ArraySlice(data)) }
        return view
    }

    func updateUIView(_ view: HerdrTerminalView, context: Context) {
        view.takeModifiers = { mods.takeForKey() }
    }

    static func dismantleUIView(_ view: HerdrTerminalView, coordinator: Coordinator) {
        _ = view.resignFirstResponder()
    }

    @MainActor final class Coordinator: NSObject, TerminalViewDelegate {
        let session: TerminalSession

        init(session: TerminalSession) { self.session = session }

        @objc func pinch(_ gesture: UIPinchGestureRecognizer) {
            guard let view = gesture.view as? TerminalView,
                  let next = TerminalFont.stepped(view.font.pointSize, scale: gesture.scale) else { return }
            gesture.scale = 1 // threshold crossed: start the next step afresh
            guard next != view.font.pointSize else { return }
            view.font = terminalFont(next)
            UserDefaults.standard.set(TerminalFont.encode(next), forKey: SettingsKey.terminalFontSize)
        }

        func sizeChanged(source: TerminalView, newCols: Int, newRows: Int) { session.resize(cols: newCols, rows: newRows) }
        func send(source: TerminalView, data: ArraySlice<UInt8>) { session.input(Data(data)) }
        /// Agent-written links open only through the app's link rule.
        func requestOpenLink(source: TerminalView, link: String, params: [String: String]) {
            if isOpenableLink(link), let url = URL(string: link.trimmed) { UIApplication.shared.open(url) }
        }
        /// OSC 52, as Termux does.
        func clipboardCopy(source: TerminalView, content: Data) {
            UIPasteboard.general.string = String(decoding: content, as: UTF8.self)
        }
        func setTerminalTitle(source: TerminalView, title: String) {}
        func hostCurrentDirectoryUpdate(source: TerminalView, directory: String?) {}
        func scrolled(source: TerminalView, position: Double) {}
        func bell(source: TerminalView) {}
        func rangeChanged(source: TerminalView, startY: Int, endY: Int) {}
    }
}

/// The bundled JetBrains Mono (OFL), else the system mono.
private func terminalFont(_ size: Double) -> UIFont {
    UIFont(name: HerdrFont.terminalFace, size: size) ?? .monospacedSystemFont(ofSize: size, weight: .regular)
}

private extension TerminalView {
    /// Catppuccin Mocha's 16 ANSI colors on herdr's crust, in both app themes: the terminal is
    /// always the dark surface, as on Android. https://catppuccin.com/palette
    func applyCatppuccin() {
        let m = HerdrColors.mocha
        let pink = Color(hex: 0xF5C2E7), teal = Color(hex: 0x94E2D5)
        let ansi = [m.surface1, m.red, m.green, m.yellow, m.blue, pink, teal, m.subtext1,
                    m.surface2, m.red, m.green, m.yellow, m.blue, pink, teal, Color(hex: 0xA6ADC8)]
        installColors(ansi.map { color in
            let c = UIColor(color).cgColor.components ?? [0, 0, 0]
            let u = { (x: CGFloat) in UInt16((x * 65535).rounded()) }
            return SwiftTerm.Color(red: u(c[0]), green: u(c[1]), blue: u(c[2]))
        })
        nativeBackgroundColor = UIColor(m.crust)
        nativeForegroundColor = UIColor(m.text)
        caretColor = UIColor(Color(hex: 0xF5E0DC))
        selectedTextBackgroundColor = UIColor(m.surface2)
    }
}
