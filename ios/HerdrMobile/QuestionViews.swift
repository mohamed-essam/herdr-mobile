import HerdrKit
import SwiftUI

// A pending AskUserQuestion: the sheet pinned where the composer was, and the question as
// timeline history. Ported from Android's QuestionCard.kt.

/// "● CLAUDE ASKS", each question with large tap rows (or a text or number field) and an
/// "Other…" field, then "Send answer". A long or multi-question ask scrolls inside the sheet.
/// The handle row folds it to a `QuestionBar`, to read the chat.
private struct QuestionSheet: View {
    let question: PendingQuestion
    let agent: String
    let enabled: Bool
    let sending: Bool
    /// One per question, held by the panel so folding the sheet keeps the picks.
    @Binding var inputs: [QuestionInput]
    let onMinimize: () -> Void
    let onSubmit: ([String: String]) -> Void
    @State private var fieldsHeight: CGFloat = 0
    @Environment(\.herdr) private var c

    var body: some View {
        let active = enabled && !sending
        let answers = buildAnswers(question.questions, inputs)
        VStack(spacing: 12) {
            Button(action: onMinimize) {
                Capsule().fill(c.surface1).frame(width: 32, height: 4)
                    .frame(maxWidth: .infinity, minHeight: 28)
                    .overlay(alignment: .trailing) { Text("▾ hide").font(HerdrFont.meta).foregroundStyle(c.overlay2) }
                    .contentShape(.rect)
            }
            .buttonStyle(.plain)
            .padding(.top, 4)
            .accessibilityLabel("Hide question")
            .accessibilityIdentifier("questionHide")
            // As tall as the questions, scrolling once the sheet's height cap is reached.
            ScrollView {
                fields(active).onGeometryChange(for: CGFloat.self) { $0.size.height } action: { fieldsHeight = $0 }
            }
            .frame(maxHeight: fieldsHeight)
            .scrollBounceBehavior(.basedOnSize)
            Button(sending ? "Sending…" : "Send answer") { answers.map(onSubmit) }
                .buttonStyle(PrimaryButtonStyle())
                .disabled(!active || answers == nil)
                .accessibilityIdentifier("sendAnswer")
        }
        .padding(.horizontal, 16).padding(.bottom, 16)
        .background(c.base, in: UnevenRoundedRectangle(topLeadingRadius: 20, topTrailingRadius: 20))
        .overlay(alignment: .top) { Rectangle().fill(c.red.opacity(0.25)).frame(height: 1).padding(.horizontal, 20) }
        .accessibilityElement(children: .contain)
        .accessibilityIdentifier("questionSheet")
        .maskedInReports()
    }

    private func fields(_ active: Bool) -> some View {
        VStack(alignment: .leading, spacing: 12) {
            ForEach(Array(question.questions.enumerated()), id: \.offset) { i, item in
                HStack(spacing: 8) {
                    if i == 0 { SectionLabel(text: "● \(agent) asks", color: c.red) }
                    HeaderChip(text: item.header)
                }
                .padding(.top, i > 0 ? 4 : 0)
                Text(item.question).font(.system(size: 17, weight: .semibold)).foregroundStyle(c.text)
                    .accessibilityIdentifier("sheetQuestion-\(i)")
                if let d = item.description, !d.isEmpty { Text(d).font(HerdrFont.small).foregroundStyle(c.overlay2) }
                inputs(for: item, at: i, active: active)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private func inputs(for item: QuestionItem, at i: Int, active: Bool) -> some View {
        switch item.kind {
        case .text:
            SheetField(placeholder: item.placeholder ?? "Your answer…", text: $inputs[i].text, lines: 5, id: "sheetText-\(i)")
                .disabled(!active)
        case .number:
            VStack(alignment: .leading, spacing: 4) {
                HStack {
                    SheetField(placeholder: item.placeholder ?? "0", text: $inputs[i].number, lines: 1, id: "sheetNumber-\(i)")
                        // Decimal pads often lack a minus sign; allow any text when negatives are allowed.
                        .keyboardType(item.min.map { $0 >= 0 } == true ? .decimalPad : .numbersAndPunctuation)
                        .disabled(!active)
                    if let unit = item.unit, !unit.isEmpty { Text(unit).font(HerdrFont.meta).foregroundStyle(c.overlay2) }
                }
                if let range = numberRange(item) { Text(range).font(HerdrFont.meta).foregroundStyle(c.overlay2).padding(.leading, 16) }
            }
        case .choice:
            VStack(spacing: 8) {
                ForEach(item.options, id: \.label) { opt in
                    ChoiceRow(option: opt, selected: inputs[i].selected.contains(opt.label), multi: item.multiSelect, enabled: active) {
                        inputs[i].toggle(opt.label, in: item)
                    }
                }
                SheetField(placeholder: "Other…", text: Binding(get: { inputs[i].other }, set: { inputs[i].type(other: $0, in: item) }),
                           lines: 3, id: "sheetOther-\(i)")
                    .disabled(!active)
            }
        }
    }
}

private struct ChoiceRow: View {
    let option: QuestionOption
    let selected: Bool
    let multi: Bool
    let enabled: Bool
    let onTap: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        Button(action: onTap) {
            HStack(spacing: 12) {
                mark
                VStack(alignment: .leading, spacing: 2) {
                    Text(option.label).font(HerdrFont.title).foregroundStyle(enabled ? c.text : c.overlay0)
                    if let d = option.description, !d.isEmpty { Text(d).font(HerdrFont.small).foregroundStyle(c.overlay2) }
                }
                Spacer(minLength: 0)
            }
            .padding(.horizontal, 16).padding(.vertical, 12)
            .background(selected ? c.mauve.opacity(0.12) : c.mantle, in: .rect(cornerRadius: HerdrRadius.tile))
            .overlay(RoundedRectangle(cornerRadius: HerdrRadius.tile).stroke(selected ? c.mauve : c.surface0, lineWidth: selected ? 1.5 : 1))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .disabled(!enabled)
        .accessibilityAddTraits(selected ? .isSelected : [])
        .accessibilityIdentifier("sheetChoice-\(option.label)")
    }

    @ViewBuilder private var mark: some View {
        let shape = multi ? AnyShape(RoundedRectangle(cornerRadius: 5)) : AnyShape(Circle())
        ZStack {
            if selected && multi {
                shape.fill(c.mauve)
                Text("✓").font(.system(size: 12)).foregroundStyle(c.crust)
            } else {
                shape.stroke(selected ? c.mauve : c.surface2, lineWidth: selected ? 5 : 1.5)
            }
        }
        .frame(width: 18, height: 18)
        .accessibilityHidden(true)
    }
}

/// The sheet's text input: mantle with a surface0 hairline.
private struct SheetField: View {
    let placeholder: String
    @Binding var text: String
    let lines: Int
    let id: String
    @Environment(\.herdr) private var c

    var body: some View {
        TextField(placeholder, text: $text, axis: .vertical)
            .lineLimit(1...lines)
            .font(HerdrFont.body).foregroundStyle(c.text)
            .padding(.horizontal, 16).padding(.vertical, 12)
            .frame(minHeight: 44)
            .background(c.mantle, in: .rect(cornerRadius: HerdrRadius.tile))
            .overlay(RoundedRectangle(cornerRadius: HerdrRadius.tile).stroke(c.surface0))
            .accessibilityIdentifier(id)
    }
}

/// A folded question: one bar where the composer was; a tap brings the sheet back.
/// A pending question where the composer was: its sheet, or the bar it folds to. It holds
/// the picks, so folding keeps them.
struct QuestionPanel: View {
    let question: PendingQuestion
    let agent: String
    let enabled: Bool
    let sending: Bool
    let maxSheetHeight: CGFloat
    let folded: Bool
    let onFold: (Bool) -> Void
    let onSubmit: ([String: String]) -> Void
    @State private var inputs: [QuestionInput]

    init(question: PendingQuestion, agent: String, enabled: Bool, sending: Bool, maxSheetHeight: CGFloat,
         folded: Bool, onFold: @escaping (Bool) -> Void, onSubmit: @escaping ([String: String]) -> Void) {
        (self.question, self.agent, self.enabled, self.sending) = (question, agent, enabled, sending)
        (self.maxSheetHeight, self.folded, self.onFold, self.onSubmit) = (maxSheetHeight, folded, onFold, onSubmit)
        _inputs = State(initialValue: question.questions.map { _ in QuestionInput() })
    }

    var body: some View {
        if folded {
            QuestionBar(agent: agent, count: question.questions.count, sending: sending) { onFold(false) }
        } else {
            QuestionSheet(question: question, agent: agent, enabled: enabled, sending: sending, inputs: $inputs,
                          onMinimize: { onFold(true) }, onSubmit: onSubmit)
                .frame(maxHeight: maxSheetHeight)
                .layoutPriority(1) // sized to its questions first; the timeline takes the rest
        }
    }
}

private struct QuestionBar: View {
    let agent: String
    let count: Int
    let sending: Bool
    let onExpand: () -> Void
    @Environment(\.herdr) private var c

    var body: some View {
        Button(action: onExpand) {
            HStack {
                Text(sending ? "● sending answer…" : "● \(agent) asks" + (count > 1 ? " · \(count) questions" : ""))
                    .font(HerdrFont.title).foregroundStyle(c.red).lineLimit(1)
                Spacer()
                Text("Answer ▴").font(HerdrFont.title).foregroundStyle(c.mauve)
            }
            .padding(.horizontal, 16).frame(minHeight: 48)
            .background(c.red.opacity(0.12), in: .rect(cornerRadius: HerdrRadius.tile))
            .contentShape(.rect)
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityIdentifier("questionBar")
        .padding(.horizontal, 16).padding(.vertical, 12)
    }
}

struct HeaderChip: View {
    let text: String
    @Environment(\.herdr) private var c

    var body: some View {
        if !text.isEmpty {
            Text(text).font(HerdrFont.meta).foregroundStyle(c.blue).lineLimit(1)
                .padding(.horizontal, 8).padding(.vertical, 2)
                .background(c.blue.opacity(0.14), in: .rect(cornerRadius: 6))
        }
    }
}

/// A question in the timeline: its header and text, then how it stands: the answer once
/// its result arrived, "sending answer…", or "waiting on your answer ↓" while the sheet asks.
struct QuestionHistory: View {
    let toolUseId: String
    let questions: [QuestionItem]
    let pending: Bool
    let sending: Bool
    let answered: Bool
    let answer: String?
    @Environment(\.herdr) private var c

    var body: some View {
        let (label, color): (String, Color) =
            if answered { ("→ " + (answer?.nonEmpty ?? "answered"), c.subtext1) }
            else if sending { ("sending answer…", c.overlay2) }
            else if pending { ("waiting on your answer ↓", c.red) }
            else { ("not answered", c.overlay0) }
        VStack(alignment: .leading, spacing: 6) {
            ForEach(Array(questions.enumerated()), id: \.offset) { _, q in
                HStack(spacing: 0) {
                    Text("? ").font(HerdrFont.meta).foregroundStyle(c.red)
                    HeaderChip(text: q.header)
                }
                Text(q.question).font(HerdrFont.body).foregroundStyle(c.text).textSelection(.enabled)
            }
            Text(label).font(HerdrFont.code).foregroundStyle(color).lineLimit(6)
                .padding(.leading, 12)
                .overlay(alignment: .leading) { Rectangle().fill(c.surface1).frame(width: 1) }
                .accessibilityIdentifier("answer-\(toolUseId)")
        }
    }
}
