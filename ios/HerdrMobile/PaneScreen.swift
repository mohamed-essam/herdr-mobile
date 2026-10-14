import HerdrKit
import SwiftUI

/// An open pane: its chat when its herdr-chat mod reports, else its terminal; the header's
/// toggle flips between them. Agent threads stack over the chat; back pops one at a time.
/// Ported from Android's PaneScreen.kt.
struct PaneScreen: View {
    let pane: Pane
    let context: PaneContext
    let client: CompanionClient
    let chat: ChatStore
    let onExit: () -> Void
    @State private var showChat: Bool

    init(pane: Pane, context: PaneContext, client: CompanionClient, chat: ChatStore, onExit: @escaping () -> Void) {
        self.pane = pane
        self.context = context
        self.client = client
        self.chat = chat
        self.onExit = onExit
        _showChat = State(initialValue: pane.chat)
    }

    var body: some View {
        if showChat {
            // Leaving for the terminal drops the chat's state: back is a fresh entry at the newest item.
            PaneChat(pane: pane, context: context, connected: client.state == .connected, chat: chat, onExit: onExit,
                     onTerminal: { showChat = false })
        } else {
            TerminalScreen(pane: pane, context: context, client: client, onExit: onExit,
                           onChat: pane.chat ? { showChat = true } : nil)
        }
    }
}

/// A pane's chat with its threads pushed over it.
private struct PaneChat: View {
    let pane: Pane
    let context: PaneContext
    let connected: Bool
    let chat: ChatStore
    let onExit: () -> Void
    let onTerminal: () -> Void
    @State private var threads = ThreadStack()

    var body: some View {
        let main = ChatKey(paneId: pane.paneId)
        // Threads are pushed over the chat, so coming back keeps its scroll position and
        // draft, and its stream stays open: agent summaries only arrive on it.
        NavigationStack(path: Binding(get: { threads.ids }, set: { ids in while threads.ids.count > ids.count { threads.pop() } })) {
            ChatScreen(pane: pane, context: context, connected: connected, chat: chat, onExit: onExit,
                       onTerminal: onTerminal, onOpenThread: { threads.push($0) })
                .toolbarVisibility(.hidden, for: .navigationBar)
                .navigationDestination(for: String.self) { agentId in
                    ThreadScreen(pane: pane, context: context, agentId: agentId, agents: chat.view(main).agents, connected: connected,
                                 chat: chat, onOpen: { threads.push($0) }, onBack: { threads.pop() })
                        .toolbarVisibility(.hidden, for: .navigationBar)
                }
        }
        .onAppear { chat.open(main) }
        .onDisappear { chat.close(main) }
    }
}

/// A subagent's thread, read-only: the pane's header with the agent's "label · type" and
/// status, then its timeline. Nothing here sends to the agent. Ported from Android's ThreadScreen.kt.
struct ThreadScreen: View {
    let pane: Pane
    let context: PaneContext
    let agentId: String
    /// The main view's agents: the companion keeps every summary pane-wide.
    let agents: [String: AgentSummary]
    let connected: Bool
    let chat: ChatStore
    let onOpen: (String) -> Void
    let onBack: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let key = ChatKey(paneId: pane.paneId, agentId: agentId)
        let view = chat.view(key)
        let summary = agents[agentId]
        let type = summary?.type?.nonEmpty
        let label = summary?.label.nonEmpty ?? type ?? "subagent"
        let (status, color): (String, Color) =
            if !connected { ("offline", c.overlay2) }
            else if let summary { (summary.status.raw, summary.status == .running ? c.yellow : summary.status == .failed ? c.red : c.green) }
            else { ("", c.overlay2) }
        VStack(spacing: 0) {
            PaneHeader(context: context, breadcrumb: [label, type == label ? nil : type].compactMap { $0 }.joined(separator: " · "),
                       status: status, statusColor: color, onBack: onBack, masksBreadcrumb: true)
            if view.missing {
                VStack(spacing: 16) {
                    Text("This thread is no longer available").font(HerdrFont.body).foregroundStyle(c.overlay2)
                        .accessibilityIdentifier("threadMissing")
                    Button("Back", action: onBack).buttonStyle(SecondaryButtonStyle()).accessibilityIdentifier("threadBack")
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ChatTimelineView(key: key, view: view, chat: chat, agents: agents, agentLabel: type ?? "agent", readOnly: true,
                                 working: summary?.status == .running, onOpenThread: onOpen)
            }
        }
        .background(c.crust)
        .chatToasts(chat)
        .onAppear { chat.open(key) }
        .onDisappear { chat.close(key) }
    }
}

/// A pane's chat: the header, the tasks strip, the timeline, and the composer at the
/// bottom, with Interrupt while the agent works and the slash-command picker above it while a
/// command name is typed; a pending question replaces the composer as a pinned sheet that
/// folds to a bar. The draft is the store's, per pane. Ported from Android's ChatScreen.kt.
struct ChatScreen: View {
    let pane: Pane
    let context: PaneContext
    let connected: Bool
    let chat: ChatStore
    let onExit: () -> Void
    let onTerminal: () -> Void
    let onOpenThread: (String) -> Void
    /// The question whose sheet is folded to its bar.
    @State private var foldedQuestion: String?
    @State private var showTasks = false
    @State private var scrollTarget: String?
    @Environment(\.herdr) private var c

    var body: some View {
        let key = ChatKey(paneId: pane.paneId)
        let view = chat.view(key)
        let agent = pane.agent?.nonEmpty ?? "claude"
        let working = view.state == .working
        let asking = pendingQuestion(view.entries, answered: view.answered)
        let status = chatStatus(connected: connected, state: view.state,
                                questionPending: asking.map { !view.answering.contains($0.toolUseId) } ?? false)
        let folded = asking != nil && asking?.toolUseId == foldedQuestion
        VStack(spacing: 0) {
            PaneHeader(context: context, breadcrumb: context.breadcrumb, status: status.label, statusColor: color(status.kind),
                       onBack: onExit, toggle: PaneHeader.Toggle(label: ">_ terminal", id: "terminalToggle", action: onTerminal))
            TasksStrip(tasks: view.tasks) { showTasks = true }
            if !pane.chat { UnavailableBanner(onTerminal: onTerminal) }
            GeometryReader { geo in
                VStack(spacing: 0) {
                    ChatTimelineView(key: key, view: view, chat: chat, agents: view.agents, tasks: view.tasks, agentLabel: agent,
                                     readOnly: false, working: working, onOpenThread: onOpenThread, scrollTarget: $scrollTarget)
                        .opacity(asking == nil || folded ? 1 : 0.45)
                    if let asking {
                        QuestionPanel(question: asking, agent: agent, enabled: connected && pane.chat,
                                      sending: view.answering.contains(asking.toolUseId), maxSheetHeight: geo.size.height * 0.8,
                                      folded: folded, onFold: { foldedQuestion = $0 ? asking.toolUseId : nil }) { answers in
                            Task { await chat.answer(paneId: pane.paneId, toolUseId: asking.toolUseId, answers: answers) }
                        }
                        // A new question (or the same one grown) opens with fresh picks, one per question.
                        .id("\(asking.toolUseId)/\(asking.questions.count)")
                    } else {
                        ComposerArea(paneId: pane.paneId, chat: chat, commands: pane.chat ? view.commands : [], agent: agent,
                                     canSend: connected && pane.chat, working: working, connected: connected,
                                     maxPickerHeight: geo.size.height * 0.4)
                    }
                }
            }
        }
        .background(c.crust)
        .chatToasts(chat)
        .sheet(isPresented: $showTasks) {
            TasksSheet(tasks: view.tasks) { task in
                scrollTarget = task.toolUseId
                showTasks = false
            }
        }
        // Queued messages expire on this tick; the store holds them.
        .task {
            while (try? await Task.sleep(for: .seconds(10))) != nil { chat.expirePending() }
        }
    }

    private func color(_ kind: ChatStatusKind) -> Color {
        switch kind {
        case .waiting: c.red
        case .working: c.yellow
        case .offline, .idle: c.overlay2
        }
    }
}

private struct UnavailableBanner: View {
    let onTerminal: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        HStack {
            Text("chat unavailable — mod not reporting").font(HerdrFont.small).foregroundStyle(c.red)
                .accessibilityIdentifier("unavailableBanner")
            Spacer()
            Button("Open terminal", action: onTerminal)
                .font(HerdrFont.small.weight(.semibold)).foregroundStyle(c.mauve)
                .accessibilityIdentifier("openTerminal")
        }
        .padding(.leading, 20).padding(.trailing, 8).padding(.vertical, 8)
        .background(c.red.opacity(0.12))
    }
}

/// The composer with the slash-command picker above it while a command name is typed. It
/// holds the draft, so typing redraws only this, and keeps it in the store per pane.
private struct ComposerArea: View {
    let paneId: String
    let chat: ChatStore
    let commands: [SlashCommand]
    let agent: String
    let canSend: Bool
    let working: Bool
    let connected: Bool
    let maxPickerHeight: CGFloat
    @State private var draft = ""

    var body: some View {
        if let matches = slashMatches(draft, commands), !matches.isEmpty {
            SlashPicker(commands: matches) { draft = "/\($0.name) " }
                .frame(maxHeight: maxPickerHeight)
        }
        Composer(draft: $draft, placeholder: working ? "Queue a message…" : "Message \(agent)…",
                 canSend: canSend, interrupt: working, connected: connected,
                 onSend: {
                     let text = draft
                     draft = ""
                     Task { await chat.send(paneId: paneId, text: text) }
                 },
                 onInterrupt: { Task { await chat.interrupt(paneId: paneId) } })
            .onAppear { draft = chat.draft(paneId: paneId) }
            .onChange(of: draft) { _, text in chat.setDraft(text, paneId: paneId) }
    }
}

/// The message field, a send button once there's text, and Interrupt (esc to the pane)
/// while the agent works. With a draft, Interrupt shrinks to its keycap.
private struct Composer: View {
    @Binding var draft: String
    let placeholder: String
    let canSend: Bool
    let interrupt: Bool
    let connected: Bool
    let onSend: () -> Void
    let onInterrupt: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let hasText = !draft.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
        HStack(alignment: .bottom, spacing: 8) {
            TextField(placeholder, text: $draft, axis: .vertical)
                .lineLimit(1...5)
                .font(HerdrFont.body).foregroundStyle(c.text)
                .padding(.horizontal, 16).padding(.vertical, 14)
                .background(c.base, in: .rect(cornerRadius: HerdrRadius.tile))
                .accessibilityIdentifier("composer")
                .maskedInReports()
            if hasText {
                Button(action: onSend) {
                    Text("↑").font(HerdrFont.title).foregroundStyle(canSend ? c.crust : c.overlay0)
                        .frame(width: 48, height: 48)
                        .background(canSend ? c.mauve : c.surface0, in: .rect(cornerRadius: HerdrRadius.tile))
                }
                .buttonStyle(.plain)
                .disabled(!canSend)
                .accessibilityLabel("Send")
                .accessibilityIdentifier("sendButton")
            }
            if interrupt {
                let ink = connected ? c.red : c.overlay0
                Button(action: onInterrupt) {
                    HStack(spacing: 8) {
                        Text("esc").font(HerdrFont.meta).foregroundStyle(ink)
                            .padding(.horizontal, 4).padding(.vertical, 2)
                            .overlay(RoundedRectangle(cornerRadius: 4).stroke(ink.opacity(0.4)))
                        if !hasText { Text("Interrupt").font(HerdrFont.small.weight(.semibold)).foregroundStyle(ink) }
                    }
                    .padding(.horizontal, hasText ? 12 : 16).frame(height: 48)
                    .background(c.red.opacity(0.12), in: .rect(cornerRadius: HerdrRadius.tile))
                }
                .buttonStyle(.plain)
                .disabled(!connected)
                .accessibilityLabel("Interrupt")
                .accessibilityIdentifier("interruptButton")
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
    }
}

/// The session's slash commands matching what's typed after "/", above the composer: a tap
/// fills in `/name ` for the arguments.
private struct SlashPicker: View {
    let commands: [SlashCommand]
    let onPick: (SlashCommand) -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        ScrollView {
            VStack(spacing: 0) {
                ForEach(commands, id: \.name) { cmd in
                    Button { onPick(cmd) } label: {
                        VStack(alignment: .leading, spacing: 2) {
                            HStack(spacing: 8) {
                                Text("/\(cmd.name)").font(HerdrFont.code).foregroundStyle(c.mauve).lineLimit(1)
                                if !cmd.source.isEmpty && cmd.source != "builtin" {
                                    Text(cmd.source).font(HerdrFont.meta).foregroundStyle(c.overlay2)
                                }
                            }
                            if !cmd.description.isEmpty {
                                Text(cmd.description).font(HerdrFont.small).foregroundStyle(c.overlay2).lineLimit(1)
                                    .maskedInReports()
                            }
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 16).padding(.vertical, 10)
                        .contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityElement(children: .combine)
                    .accessibilityIdentifier("slash-\(cmd.name)")
                }
            }
        }
        .scrollBounceBehavior(.basedOnSize)
        .fixedSize(horizontal: false, vertical: true)
        .background(c.base, in: .rect(cornerRadius: HerdrRadius.tile))
        .accessibilityIdentifier("slashPicker")
        .padding(.horizontal, 16).padding(.top, 8)
    }
}

/// One line under the header summing up the pane's background tasks; absent when there are none.
private struct TasksStrip: View {
    let tasks: [BgTask]
    let onTap: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let counts = TaskCounts(tasks)
        if let label = stripLabel(counts) {
            Button(action: onTap) {
                Text(label).font(HerdrFont.meta)
                    .foregroundStyle(counts.failed > 0 && counts.running == 0 ? c.red : c.subtext1).lineLimit(1)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding(.horizontal, 20).padding(.vertical, 8)
                    .background(c.mantle)
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier("tasksStrip")
        }
    }
}

/// The tasks as rows: glyph, label, status and duration, ticking each second while one
/// runs. Subagent and workflow rows jump to their card; shell and monitor rows expand to
/// their description and whole, selectable command.
private struct TasksSheet: View {
    let tasks: [BgTask]
    let onPick: (BgTask) -> Void
    @State private var expanded: Set<String> = []
    @Environment(\.launchOptions) private var options
    @Environment(\.herdr) private var c

    var body: some View {
        TimelineView(.periodic(from: .now, by: tasks.contains { $0.status == .running } ? 1 : 3600)) { _ in
            let now = options.now().epochMs
            VStack(alignment: .leading, spacing: 12) {
                SheetTitle(text: "Background tasks")
                ListCard {
                    ForEach(tasks, id: \.id) { t in
                        Button {
                            if t.hasCard { onPick(t) } else if expanded.remove(t.id) == nil { expanded.insert(t.id) }
                        } label: { row(t, now: now) }
                            .buttonStyle(.plain)
                            .accessibilityIdentifier("task-\(t.id)")
                        if expanded.contains(t.id) { detail(t) }
                    }
                }
                .maskedInReports()
                Spacer()
            }
            .padding(20)
        }
        .herdrSheet(c.base, detents: [.medium, .large])
    }

    private func detail(_ t: BgTask) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            if let d = t.description {
                Text(d).font(HerdrFont.body).foregroundStyle(c.subtext1)
                    .accessibilityIdentifier("taskDescription-\(t.id)")
            }
            Text(t.fullText).font(HerdrFont.code).foregroundStyle(c.text).textSelection(.enabled)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.horizontal, 10).padding(.vertical, 8)
                .background(c.mantle)
                .accessibilityIdentifier("taskDetail-\(t.id)")
                .maskedInReports()
        }
        .padding(.leading, 40).padding(.trailing, 16).padding(.bottom, 12)
    }

    private func row(_ t: BgTask, now: Int) -> some View {
        HStack(spacing: 8) {
            Text(taskGlyph(t.kind)).font(HerdrFont.title).foregroundStyle(c.overlay2).frame(width: 24, alignment: .leading)
            Text(t.label.isEmpty ? t.kind.raw : t.label).font(HerdrFont.body).foregroundStyle(c.text).lineLimit(1)
            Spacer()
            Text(t.status.raw).font(HerdrFont.meta)
                .foregroundStyle(t.status == .running ? c.yellow : t.status == .done ? c.green : c.red)
            Text(taskDuration(t, now: now)).font(HerdrFont.meta).foregroundStyle(c.overlay2)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .contentShape(.rect)
    }
}

extension View {
    /// The chat's toasts: "link not opened" for a refused link, and the store's errors.
    func chatToasts(_ chat: ChatStore) -> some View { modifier(ChatToasts(chat: chat)) }
}

private struct ChatToasts: ViewModifier {
    let chat: ChatStore
    @State private var notice: String?

    func body(content: Content) -> some View {
        content
            .chatLinks { notice = "link not opened" }
            .overlay(alignment: .bottom) {
                if let message = notice ?? chat.error {
                    Toast(message: message)
                        .padding(.bottom, 88)
                        .accessibilityIdentifier("chatToast")
                        .task(id: message) {
                            try? await Task.sleep(for: .seconds(3))
                            notice = nil
                            chat.clearError()
                        }
                }
            }
    }
}
