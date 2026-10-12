import HerdrKit
import SwiftUI

/// The time gutter's width and the gap beside it.
private let gutter: CGFloat = 40
private let gutterGap: CGFloat = 12
/// Minimum gap between `chat_history` requests from the paging trigger.
private let olderSpacing = Duration.seconds(2)

/// The scrolling timeline of one chat stream: a pane's main chat or an agent's thread.
/// Times sit in a left gutter, "you"/agent labels head each block, and tool calls fold onto
/// a rail. It opens at the newest item, follows new output while at the bottom, and pages
/// back at the top. `readOnly` (a thread) shows no pending messages, and its questions are
/// plain text. Ported from Android's ChatTimelineList.kt.
struct ChatTimelineView: View {
    let key: ChatKey
    let view: ChatView
    let chat: ChatStore
    /// The pane's agents, from its main view.
    let agents: [String: AgentSummary]
    var tasks: [BgTask] = []
    let agentLabel: String
    let readOnly: Bool
    /// The stream is working: its last unfinished tool calls spin.
    let working: Bool
    let onOpenThread: (String) -> Void
    /// A tool call whose card to scroll to (from the tasks sheet); cleared once handled.
    var scrollTarget: Binding<String?> = .constant(nil)
    @State private var expanded: Set<String> = []
    @State private var scroll = ScrollTracking()
    @State private var width: CGFloat = 300
    @Environment(\.launchOptions) private var options
    @Environment(\.herdr) private var c

    private static let bottom = "bottom"

    var body: some View {
        let items = buildTimeline(entries: view.entries, epoch: view.epoch, pageStarts: view.pageStarts)
        let results = Dictionary(view.entries.compactMap { e in e.resultId.map { ($0, e.event) } }, uniquingKeysWith: { $1 })
        let running = runningTools(items, resultIds: Set(results.keys), working: working)
        let asking = readOnly ? nil : pendingQuestion(view.entries, answered: view.answered)
        let pending = readOnly ? [] : view.pending
        let now = options.now().epochMs
        ZStack(alignment: .top) {
            if !view.loaded {
                Text("loading…").font(HerdrFont.meta).foregroundStyle(c.overlay2).frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                ScrollViewReader { proxy in
                    // A List, not a LazyVStack in a ScrollView: the lazy stack kept re-anchoring a
                    // row whose height differed from its estimate, a 100% CPU loop.
                    List {
                        ForEach(Array(items.enumerated()), id: \.element.key) { i, item in
                            row(item, results: results, running: running, asking: asking, now: now)
                                .timelineRow(top: i == 0 ? 16 : item.head ? 16 : 8)
                                .id(item.key)
                        }
                        ForEach(Array(pending.enumerated()), id: \.element.id) { i, p in
                            let head = i == 0 && items.last?.speaker != .user
                            PendingRow(message: p, head: head, label: pendingLabel(p, state: view.state)) {
                                Task { await chat.retry(paneId: key.paneId, pendingId: p.id) }
                            }
                            .timelineRow(top: items.isEmpty && i == 0 ? 16 : head ? 16 : 8)
                        }
                        Color.clear.frame(height: 16).timelineRow(top: 0).id(Self.bottom)
                    }
                    .listStyle(.plain)
                    .scrollContentBackground(.hidden)
                    .environment(\.defaultMinListRowHeight, 0)
                    // Entering opens at the newest item, once; later output uses the follow rule.
                    .task {
                        await Task.yield()
                        proxy.scrollTo(Self.bottom, anchor: .bottom)
                        scroll.entered = true
                    }
                    .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { width = $0 }
                    .onScrollPhaseChange { _, phase in
                        scroll.userScrolling = phase == .tracking || phase == .interacting || phase == .decelerating
                    }
                    .onScrollGeometryChange(for: [Bool].self) { g in
                        [g.contentOffset.y + g.containerSize.height >= g.contentSize.height + g.contentInsets.bottom - 4,
                         g.contentOffset.y <= -g.contentInsets.top + 4]
                    } action: { _, edges in
                        if edges[0] { scroll.follow = true } else if scroll.userScrolling { scroll.follow = false }
                        scroll.atTop = edges[1]
                        if edges[1] { loadOlderSoon() }
                    }
                    // Keyed on the newest item and what can still grow in it, so a page of
                    // older history prepended above doesn't count as new output.
                    .onChange(of: lastKey(items, pending: pending, results: results)) {
                        if scroll.follow { proxy.scrollTo(Self.bottom, anchor: .bottom) }
                    }
                    .onChange(of: scrollTarget.wrappedValue, initial: true) { _, id in
                        guard let id else { return }
                        if items.contains(where: { $0.key == "a:\(id)" }) { withAnimation { proxy.scrollTo("a:\(id)", anchor: .center) } }
                        scrollTarget.wrappedValue = nil
                    }
                }
                .onChange(of: view.loadingOlder) { loadOlderSoon() }
            }
            // An overlay, not a row: a row above the oldest item would become the scroll anchor.
            if let pill = view.loadingOlder ? "loading earlier…" : view.gap ? "resyncing…" : nil {
                Text(pill).font(HerdrFont.meta).foregroundStyle(c.overlay2)
                    .padding(.horizontal, 12).padding(.vertical, 4)
                    .background(c.base, in: .rect(cornerRadius: HerdrRadius.field))
                    .padding(.top, 8)
                    .accessibilityIdentifier("loadingEarlier")
            }
        }
        .maskedInReports()
    }

    /// Pages back while the oldest item is in view. A rejected page leaves `hasMore` set, so
    /// requests are spaced out rather than sent in a tight loop.
    private func loadOlderSoon() {
        guard scroll.entered, scroll.atTop, view.hasMore, !view.loadingOlder, !scroll.olderScheduled else { return }
        scroll.olderScheduled = true
        Task {
            try? await Task.sleep(for: max(scroll.lastOlder + olderSpacing - ContinuousClock.now, .milliseconds(300)))
            scroll.olderScheduled = false
            guard scroll.atTop else { return }
            scroll.lastOlder = ContinuousClock.now
            chat.loadOlder(key)
        }
    }

    private var agentSpeaker: SpeakerLabel { SpeakerLabel(text: agentLabel, color: c.blue) }

    /// The content column's width, for images.
    private var columnWidth: CGFloat { max(100, width - 32 - gutter - gutterGap) }

    @ViewBuilder
    private func row(_ item: TimelineItem, results: [String: ChatEvent], running: Set<String>, asking: PendingQuestion?, now: Int) -> some View {
        switch item {
        case .tools(_, let calls, let head):
            GutterRow(time: head ? calls.first?.ts.map { formatTs(ms: $0, now: now) } : nil, timeId: nil, speaker: head ? agentSpeaker : nil) {
                ToolRail(calls: calls, results: results, running: running, expanded: $expanded) { id, sizes in
                    ChatImageView(chat: chat, paneId: key.paneId, id: id, size: sizes[id], maxWidth: columnWidth - 12)
                }
            }
        case .agentCard(_, let call, let head):
            GutterRow(time: head ? call.ts.map { formatTs(ms: $0, now: now) } : nil, timeId: nil, speaker: head ? agentSpeaker : nil) {
                AgentCard(call: call, agents: agents, tasks: tasks, result: results[call.toolUseId], working: working, now: now,
                          onOpen: onOpenThread)
            }
        case .event(_, let entry, _, let head):
            let time = (head ? entry.event.ts : nil).map { formatTs(ms: $0, now: now) }
            switch entry.event {
            case .userText(_, let text, _, let images, let sizes):
                GutterRow(time: time, timeId: "ts-\(entry.seq)", speaker: head ? youSpeaker(c) : nil, spacing: 4) {
                    ForEach(images, id: \.self) { ChatImageView(chat: chat, paneId: key.paneId, id: $0, size: sizes[$0], maxWidth: columnWidth) }
                    // A message that is only an image has no text.
                    if !text.isEmpty {
                        Text(text).font(HerdrFont.body).foregroundStyle(c.text).textSelection(.enabled)
                            .accessibilityIdentifier("msg-\(entry.seq)")
                    }
                }
            case .assistantText(_, let text, _):
                GutterRow(time: time, timeId: "ts-\(entry.seq)", speaker: head ? agentSpeaker : nil) {
                    ChatMarkdown(text: text)
                        .accessibilityElement(children: .contain)
                        .accessibilityIdentifier("msg-\(entry.seq)")
                }
            case .question(_, let id, let questions, _):
                GutterRow(time: time, timeId: "ts-\(entry.seq)", speaker: head ? agentSpeaker : nil) {
                    if readOnly {
                        // A thread's question is history only: nothing here answers it.
                        Text(questions.map { "? \($0.question)" }.joined(separator: "\n"))
                            .font(HerdrFont.body).foregroundStyle(c.text).textSelection(.enabled)
                            .accessibilityIdentifier("msg-\(entry.seq)")
                    } else {
                        QuestionHistory(toolUseId: id, questions: questions, pending: id == asking?.toolUseId,
                                        sending: view.answering.contains(id), answered: view.answered.contains(id),
                                        answer: results[id].flatMap(\.preview))
                    }
                }
            case .taskNotice(_, _, _, let ts):
                GutterRow(time: ts.map { formatTs(ms: $0, now: now) }, timeId: "ts-\(entry.seq)") {
                    Text(taskNoticeLabel(entry.event)).font(HerdrFont.meta)
                        .foregroundStyle(taskNoticeIsError(entry.event) ? c.red : c.overlay2).lineLimit(2)
                        .accessibilityIdentifier("msg-\(entry.seq)")
                }
            // What a command sent from the phone printed, under its echo (the engine's own user row).
            case .commandOutput(_, _, let text, let isError, _):
                GutterRow(time: time, timeId: "ts-\(entry.seq)", speaker: head ? youSpeaker(c) : nil, spacing: 4) {
                    let out = text[..<(text.lastIndex { !$0.isWhitespace }.map(text.index(after:)) ?? text.startIndex)]
                    Text(out.isEmpty ? "(no output)" : out).font(HerdrFont.code).foregroundStyle(isError ? c.red : c.subtext1)
                        .lineLimit(20).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.horizontal, 12).padding(.vertical, 8)
                        .background(c.base, in: .rect(cornerRadius: HerdrRadius.tile))
                        .accessibilityIdentifier("msg-\(entry.seq)")
                        .maskedInReports()
                }
            case .toolUse, .toolResult:
                EmptyView()
            }
        }
    }

    /// The newest item plus what can still grow in it: a rail's calls, results and images,
    /// a card's agents, a question's state; or the newest pending message.
    private func lastKey(_ items: [TimelineItem], pending: [PendingMessage], results: [String: ChatEvent]) -> String {
        if let p = pending.last { return p.id }
        guard let item = items.last else { return "" }
        let grows: String = switch item {
        case .tools(_, let calls, _):
            "\(calls.count)/\(calls.map { results[$0.toolUseId].map { 1 + $0.imageCount } ?? 0 }.reduce(0, +))"
        case .agentCard(_, let call, _):
            agentsFor(toolUseId: call.toolUseId, agents: agents).map { "\($0.agentId):\($0.status.raw):\(activityLine($0.activity) ?? "")" }
                .joined(separator: ",")
        case .event(_, let entry, _, _):
            entry.questionId.map { "\(view.answering.contains($0))\(view.answered.contains($0))" } ?? ""
        }
        return "\(item.key)/\(grows)"
    }
}

extension View {
    /// A timeline row in the List: no separator or background, `top` above it.
    fileprivate func timelineRow(top: CGFloat) -> some View {
        listRowInsets(EdgeInsets(top: top, leading: 16, bottom: 0, trailing: 16))
            .listRowSeparator(.hidden)
            .listRowBackground(Color.clear)
    }
}

/// Where the user has the timeline. Scroll callbacks fire every frame, so this is a plain
/// object: changing it doesn't re-render the timeline (that fed a lazy-stack layout loop).
@MainActor private final class ScrollTracking {
    /// Follow new output: true at the very bottom, false once the user scrolls away from it.
    var follow = true
    var userScrolling = false
    var atTop = false
    var olderScheduled = false
    /// The entry scroll to the newest item is done; paging waits for it.
    var entered = false
    var lastOlder = ContinuousClock.now - .seconds(60)
}

/// A timeline row: `time` in the gutter, the block's content beside it.
private struct GutterRow<Content: View>: View {
    let time: String?
    let timeId: String?
    /// Heads the block: the first row of a speaker's run.
    var speaker: SpeakerLabel?
    var spacing: CGFloat = 8
    @ViewBuilder let content: Content
    @Environment(\.herdr) private var c

    var body: some View {
        HStack(alignment: .top, spacing: gutterGap) {
            Text(time ?? "").font(HerdrFont.meta).foregroundStyle(c.overlay0)
                .frame(width: gutter, alignment: .leading).padding(.top, 1)
                .accessibilityIdentifier(time == nil ? "" : timeId ?? "")
            VStack(alignment: .leading, spacing: spacing) {
                speaker
                content
            }
                .frame(maxWidth: .infinity, alignment: .leading)
        }
    }
}

struct SpeakerLabel: View {
    let text: String
    let color: Color

    var body: some View { Text(text).font(HerdrFont.meta.weight(.semibold)).foregroundStyle(color) }
}

private func youSpeaker(_ c: HerdrColors) -> SpeakerLabel { SpeakerLabel(text: "you", color: c.mauve) }

/// A message sent from the phone and not confirmed yet: dimmed, with its status and Retry.
private struct PendingRow: View {
    let message: PendingMessage
    let head: Bool
    let label: String
    let onRetry: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        GutterRow(time: nil, timeId: nil, speaker: head ? youSpeaker(c) : nil, spacing: 4) {
            Text(message.text).font(HerdrFont.body).foregroundStyle(c.text).opacity(0.6).textSelection(.enabled)
            HStack(spacing: 4) {
                Text(label).font(HerdrFont.meta).foregroundStyle(message.status == .queued ? c.overlay2 : c.red)
                    .accessibilityIdentifier("pendingLabel")
                if message.status != .queued {
                    Button("Retry", action: onRetry)
                        .buttonStyle(.borderless)
                        .font(HerdrFont.small.weight(.semibold)).foregroundStyle(c.mauve)
                        .padding(.horizontal, 8).padding(.vertical, 6)
                        .accessibilityIdentifier("retryButton")
                }
            }
        }
    }
}

/// Consecutive tool calls on a thin rail, one mono line each ("$ Read ui/X.kt"). A running
/// call spins on a yellow rail; a failed one is red. Tapping a finished call shows its
/// result's preview under it.
private struct ToolRail<Images: View>: View {
    let calls: [ToolCall]
    let results: [String: ChatEvent]
    let running: Set<String>
    @Binding var expanded: Set<String>
    @ViewBuilder let images: (String, [String: PixelSize]) -> Images
    @Environment(\.herdr) private var c

    var body: some View {
        let live = calls.contains { running.contains($0.toolUseId) }
        VStack(alignment: .leading, spacing: 6) {
            ForEach(calls, id: \.toolUseId) { call in
                let result = results[call.toolUseId]
                let isRunning = running.contains(call.toolUseId)
                let open = expanded.contains(call.toolUseId) && result != nil
                let color = isRunning ? c.yellow : result?.isError == true ? c.red : c.subtext1
                let line = toolLine(tool: call.tool, summary: call.summary)
                Button {
                    if open { expanded.remove(call.toolUseId) } else { expanded.insert(call.toolUseId) }
                } label: {
                    Group {
                        if isRunning {
                            HStack(spacing: 4) { Spinner(font: HerdrFont.meta); Text(line) }
                        } else {
                            Text("\(open ? "▾" : "$") \(line)")
                        }
                    }
                    .font(HerdrFont.meta).foregroundStyle(color).lineLimit(open ? 4 : 1)
                    .frame(maxWidth: .infinity, alignment: .leading).contentShape(.rect)
                }
                .buttonStyle(.plain)
                .disabled(result == nil)
                .accessibilityIdentifier("tool-\(call.toolUseId)")
                if open, let preview = result?.preview {
                    Text(preview.isEmpty ? "(no output)" : preview).font(HerdrFont.meta).foregroundStyle(c.overlay2)
                        .textSelection(.enabled).padding(.leading, 16)
                        .accessibilityIdentifier("toolOutput-\(call.toolUseId)")
                }
                if case .toolResult(_, _, _, let ids, _, let sizes)? = result {
                    ForEach(ids, id: \.self) { images($0, sizes) }
                }
            }
        }
        .padding(.leading, 12)
        .overlay(alignment: .leading) { Rectangle().fill(live ? c.yellow : c.surface1).frame(width: 1) }
    }
}
