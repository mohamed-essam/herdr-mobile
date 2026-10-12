import HerdrKit
import SwiftUI

/// Setup: run the companion, enter its tailnet address, connect. Also adds a server from
/// the dashboard, with "← back"; an address already saved is refused. Ported from
/// Android's OnboardingScreen.kt; push (step 3) waits for its own milestone.
struct ConnectScreen: View {
    @Binding var input: String
    /// The saved server's name at this address, if any.
    let duplicateOf: (CompanionAddress) -> String?
    /// Back to the dashboard, when adding a server.
    let onBack: (() -> Void)?
    let onConnect: (CompanionAddress) -> Void
    @Environment(\.herdr) private var c

    private var address: CompanionAddress? { CompanionAddress(parsing: input) }

    var body: some View {
        let steps = connectStepStates(hostEntered: address != nil)
        let duplicate = address.flatMap(duplicateOf)
        VStack(spacing: 0) {
            ScrollView {
                if let onBack {
                    Button("← back", action: onBack).font(HerdrFont.meta).foregroundStyle(c.subtext1)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(.top, 16)
                        .accessibilityIdentifier("connectBack")
                }
                VStack(alignment: .leading, spacing: 8) {
                    Wordmark(font: .system(size: 28, weight: .semibold, design: .monospaced))
                    Text("One terminal for the whole herd, in your pocket.").font(HerdrFont.body).foregroundStyle(c.subtext1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 48).padding(.bottom, 32)
                VStack(alignment: .leading, spacing: 24) {
                    Step(number: 1, state: steps[0]) {
                        Text("Run the companion on your host").font(HerdrFont.title).foregroundStyle(c.text)
                        CommandBlock(command: companionCommand)
                    }
                    Step(number: 2, state: steps[1]) {
                        Text("Enter its tailnet address").font(HerdrFont.title).foregroundStyle(c.text)
                        AddressField(input: $input, address: address, onGo: connect)
                        if let duplicate {
                            Text("Already saved as \(duplicate).")
                                .font(HerdrFont.small).foregroundStyle(c.red).accessibilityIdentifier("addressError")
                        } else if !input.trimmingCharacters(in: .whitespaces).isEmpty && address == nil {
                            Text("That doesn't look like a host or ws:// address.")
                                .font(HerdrFont.small).foregroundStyle(c.red).accessibilityIdentifier("addressError")
                        } else {
                            Text("Keep it on your tailnet. The companion has no auth.").font(HerdrFont.small).foregroundStyle(c.overlay2)
                        }
                    }
                    Step(number: 3, state: steps[2]) {
                        Text("Notifications").font(HerdrFont.title).foregroundStyle(c.text)
                        Text("Coming in a later version").font(HerdrFont.small).foregroundStyle(c.overlay2)
                    }
                    .opacity(0.6)
                }
            }
            .padding(.horizontal, 24)
            Button("Connect", action: connect)
                .buttonStyle(PrimaryButtonStyle())
                .disabled(address == nil || duplicate != nil)
                .accessibilityIdentifier("connectButton")
                .padding(.horizontal, 24).padding(.bottom, 32)
        }
        .background(c.crust)
    }

    private func connect() {
        if let address, duplicateOf(address) == nil { onConnect(address) }
    }
}

/// A numbered setup step: a marker (✓ done, mauve current, outlined upcoming) beside `content`.
private struct Step<Content: View>: View {
    let number: Int
    let state: StepState
    @ViewBuilder let content: Content
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(alignment: .top, spacing: 16) {
            Text(state == .done ? "✓" : "\(number)")
                .font(HerdrFont.section)
                .foregroundStyle(state == .upcoming ? c.text : c.crust)
                .frame(width: 24, height: 24)
                .background(state == .done ? c.green : state == .current ? c.mauve : .clear, in: .circle)
                .overlay(Circle().stroke(state == .upcoming ? c.surface2 : .clear, lineWidth: 1.5))
            VStack(alignment: .leading, spacing: 8) { content }
        }
    }
}

/// The companion command with a "copy" action.
private struct CommandBlock: View {
    let command: String
    @State private var copied = false
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(alignment: .top, spacing: 8) {
            Text(command).font(HerdrFont.meta).foregroundStyle(c.subtext1).frame(maxWidth: .infinity, alignment: .leading)
            Button(copied ? "copied" : "copy") {
                UIPasteboard.general.string = command
                copied = true
            }
            .font(HerdrFont.meta).foregroundStyle(c.mauve)
        }
        .padding(12)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.field))
        .task(id: copied) {
            guard copied else { return }
            try? await Task.sleep(for: .seconds(1.5))
            copied = false
        }
    }
}

/// The user types the host; the `ws://` and `:8787` it will add show dimmed.
private struct AddressField: View {
    @Binding var input: String
    let address: CompanionAddress?
    let onGo: () -> Void
    @FocusState private var focused: Bool
    @Environment(\.herdr) private var c

    var body: some View {
        let showScheme = !(address?.hasScheme ?? input.contains("://"))
        let showPort = address.map { !$0.hasPort && !$0.hasScheme } ?? true
        HStack(spacing: 0) {
            if showScheme { Text("ws://").foregroundStyle(c.overlay0) }
            TextField("100.x.y.z", text: $input)
                .foregroundStyle(c.text)
                .keyboardType(.URL)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .submitLabel(.go)
                .onSubmit(onGo)
                .focused($focused)
                .accessibilityIdentifier("addressField")
            if showPort { Text(":\(defaultCompanionPort)").foregroundStyle(c.overlay0) }
        }
        .font(.system(size: 15, design: .monospaced))
        .padding(.horizontal, 16)
        .frame(height: 52)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.tile))
        .overlay(RoundedRectangle(cornerRadius: HerdrRadius.tile).stroke(focused ? c.mauve : c.surface0, lineWidth: 1.5))
    }
}

/// The first connection: connecting, then a summary of the herd once the companion
/// answers. After a while without an answer it offers to go back and fix the address.
struct FirstConnectionScreen: View {
    let client: CompanionClient?
    let store: DashboardStore?
    let hostPort: String
    let onConnected: () -> Void
    let onContinue: () -> Void
    let onBack: () -> Void
    @State private var everConnected = false
    @State private var timedOut = false
    @Environment(\.herdr) private var c

    var body: some View {
        let phase = FirstConnectPhase(everConnected: everConnected, timedOut: timedOut)
        let ok = phase == .connected
        VStack(spacing: 0) {
            Spacer().frame(height: 160)
            Group {
                if ok { Text("✓").font(.system(size: 32, weight: .semibold, design: .monospaced)) } else { Spinner(font: HerdrFont.stat) }
            }
            .foregroundStyle(ok ? c.crust : c.yellow)
            .frame(width: 72, height: 72)
            .background(ok ? c.green : c.surface0, in: .circle)
            .shadow(color: (ok ? c.green : c.yellow).opacity(0.3), radius: 16)
            Text(ok ? "Connected" : "Connecting")
                .font(HerdrFont.display).foregroundStyle(c.text).padding(.top, 32)
                .accessibilityIdentifier("firstConnectState")
            Text(hostPort).font(HerdrFont.badge.weight(.regular)).foregroundStyle(c.overlay2).padding(.top, 8)
            if ok, let store {
                let s = HerdSummary(repos: store.repos)
                VStack(alignment: .leading, spacing: 16) {
                    Text(s.line).font(HerdrFont.small).foregroundStyle(c.overlay2).accessibilityIdentifier("herdLine")
                    HStack {
                        HerdCount(n: s.needYou, label: "need you", color: c.red)
                        Spacer()
                        HerdCount(n: s.working, label: "working", color: c.yellow)
                        Spacer()
                        HerdCount(n: s.done, label: "done", color: c.green)
                    }
                }
                .padding(16)
                .background(c.base, in: .rect(cornerRadius: HerdrRadius.card))
                .padding(.horizontal, 24).padding(.top, 32)
            } else if phase == .slow {
                Text("Still trying. Check that the companion is running and this phone is on your tailnet.")
                    .font(HerdrFont.small).foregroundStyle(c.overlay2).multilineTextAlignment(.center)
                    .padding(.horizontal, 40).padding(.top, 24)
            }
            Spacer()
            switch phase {
            case .connected:
                Button("See who needs you", action: onContinue).buttonStyle(PrimaryButtonStyle())
                    .accessibilityIdentifier("seeWhoNeedsYou")
            case .slow:
                Button("Back", action: onBack).buttonStyle(SecondaryButtonStyle(height: 52))
                    .accessibilityIdentifier("backButton")
            case .connecting:
                EmptyView()
            }
        }
        .padding(.horizontal, 24).padding(.bottom, 32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .background(c.crust)
        .onChange(of: client?.state == .connected, initial: true) { _, connected in
            guard connected, !everConnected else { return }
            everConnected = true
            onConnected() // only now is the address worth keeping
        }
        .task {
            if (try? await Task.sleep(for: FirstConnectPhase.slowAfter)) != nil { timedOut = true }
        }
    }
}

private struct HerdCount: View {
    let n: Int
    let label: String
    let color: Color
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text("\(n)").font(HerdrFont.stat).foregroundStyle(color)
            Text(label).font(HerdrFont.caption).foregroundStyle(c.overlay2)
        }
    }
}
