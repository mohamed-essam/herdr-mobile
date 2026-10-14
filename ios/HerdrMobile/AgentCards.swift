import HerdrKit
import SwiftUI

// The cards an Agent or Workflow call becomes in the timeline. Ported from Android's AgentCards.kt.

/// The launch call's card: a workflow's rows, or one subagent. `result` is the call's
/// tool_result and `working` whether the stream is working: they decide the status while no
/// summary exists.
struct AgentCard: View {
    let call: ToolCall
    let agents: [String: AgentSummary]
    let tasks: [BgTask]
    let result: ChatEvent?
    let working: Bool
    let now: Int
    let onOpen: (String) -> Void

    var body: some View {
        let mine = agentsFor(toolUseId: call.toolUseId, agents: agents)
        if call.tool == "Workflow" {
            WorkflowCard(call: call, agents: mine, title: workflowTitle(toolUseId: call.toolUseId, tasks: tasks), result: result,
                         working: working, now: now, onOpen: onOpen)
        } else {
            SubagentCard(call: call, agent: mine.first, result: result, working: working, now: now, onOpen: onOpen)
        }
    }
}

/// A subagent: label, type and elapsed time, its activity under them. Tapping opens its
/// thread once its summary is known.
private struct SubagentCard: View {
    let call: ToolCall
    let agent: AgentSummary?
    let result: ChatEvent?
    let working: Bool
    let now: Int
    let onOpen: (String) -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        let status = agent.map { CardStatus($0.status) } ?? fallbackStatus(result: result, working: working)
        let label = agent?.label.nonEmpty ?? toolLine(tool: call.tool, summary: call.summary)
        let start = call.ts ?? agent?.ts ?? now
        let end = status.live ? now : agent?.ts ?? result?.ts ?? start
        Button { if let agent { onOpen(agent.agentId) } } label: {
            CardSurface {
                HStack(spacing: 8) {
                    StatusGlyph(status: status)
                    Text(label).font(HerdrFont.meta.weight(.semibold)).foregroundStyle(c.text).lineLimit(1)
                    if let type = agent?.type, !type.isEmpty {
                        Text(type).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                    }
                    Spacer(minLength: 0)
                    Text(elapsedLabel(from: start, to: end)).font(HerdrFont.meta).foregroundStyle(c.overlay0)
                }
                Text((agent == nil ? fallbackActivity(status) : activityLine(agent?.activity)) ?? "")
                    .font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                    .padding(.leading, 16)
            }
        }
        .buttonStyle(.plain)
        .disabled(agent == nil)
        .accessibilityIdentifier("card-\(call.toolUseId)")
    }
}

/// A workflow: name and overall status, then its agents under their phase headings. Past
/// `workflowRows` rows the rest fold into "+N more", which expands in place.
private struct WorkflowCard: View {
    let call: ToolCall
    let agents: [AgentSummary]
    let title: String
    let result: ChatEvent?
    let working: Bool
    let now: Int
    let onOpen: (String) -> Void
    @State private var all = false
    @Environment(\.herdr) private var c

    var body: some View {
        let overall = workflowStatus(agents, result: result, working: working)
        let shown = all ? agents : Array(agents.prefix(workflowRows))
        let start = call.ts ?? agents.first?.ts ?? now
        let end = overall.live ? now : agents.map(\.ts).max() ?? result?.ts ?? start
        CardSurface {
            HStack(spacing: 8) {
                StatusGlyph(status: overall)
                Text(title).font(HerdrFont.meta.weight(.semibold)).foregroundStyle(c.text).lineLimit(1)
                    .accessibilityIdentifier("cardTitle-\(call.toolUseId)")
                Spacer(minLength: 0)
                Text(elapsedLabel(from: start, to: end)).font(HerdrFont.meta).foregroundStyle(c.overlay0)
            }
            if agents.isEmpty, case let activity = fallbackActivity(overall), !activity.isEmpty {
                Text(activity).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1).padding(.leading, 16)
            }
            ForEach(Array(workflowGroups(shown).enumerated()), id: \.offset) { _, group in
                if let phase = group.phase {
                    SectionLabel(text: phase, color: c.overlay2).padding(.top, 4)
                        .accessibilityIdentifier("phase-\(call.toolUseId)-\(phase)")
                }
                ForEach(group.agents, id: \.agentId) { a in
                    Button { onOpen(a.agentId) } label: {
                        HStack(spacing: 8) {
                            StatusGlyph(status: CardStatus(a.status))
                            Text(a.label).font(HerdrFont.meta).foregroundStyle(c.subtext1).lineLimit(1)
                            if let line = activityLine(a.activity) {
                                Text(line).font(HerdrFont.meta).foregroundStyle(c.overlay2).lineLimit(1)
                            }
                            Spacer(minLength: 0)
                        }
                        .padding(.leading, 8).padding(.vertical, 2).contentShape(.rect)
                    }
                    .buttonStyle(.plain)
                    .accessibilityIdentifier("agentRow-\(a.agentId)")
                }
            }
            if agents.count > shown.count {
                Button("+\(agents.count - shown.count) more") { all = true }
                    .buttonStyle(.borderless)
                    .font(HerdrFont.meta).foregroundStyle(c.mauve).padding(.vertical, 4)
                    .accessibilityIdentifier("more-\(call.toolUseId)")
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("card-\(call.toolUseId)")
    }
}

private struct CardSurface<Content: View>: View {
    @ViewBuilder let content: Content
    @Environment(\.herdr) private var c

    var body: some View {
        VStack(alignment: .leading, spacing: 2) { content }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 12).padding(.vertical, 8)
            .background(c.surface0, in: .rect(cornerRadius: HerdrRadius.badge))
    }
}

/// The working spinner, ✓ in green, ✗ in red, or a dim ○ for a launched or unknown one.
struct StatusGlyph: View {
    let status: CardStatus
    @Environment(\.herdr) private var c

    var body: some View {
        let font = HerdrFont.section
        switch status {
        case .done: Text("✓").font(font).foregroundStyle(c.green)
        case .failed: Text("✗").font(font).foregroundStyle(c.red)
        case .launched, .unknown: Text("○").font(font).foregroundStyle(c.overlay0)
        case .running: Spinner(font: font).foregroundStyle(c.yellow)
        }
    }
}
