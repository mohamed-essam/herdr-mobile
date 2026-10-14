import Foundation

// Answering an AskUserQuestion from the chat's question sheet. Ported from Android's
// QuestionCard.kt.

/// What the user has entered for one question.
public struct QuestionInput: Equatable, Sendable {
    public internal(set) var selected: Set<String>
    public internal(set) var other: String
    public var text: String
    public var number: String

    public init(selected: Set<String> = [], other: String = "", text: String = "", number: String = "") {
        (self.selected, self.other, self.text, self.number) = (selected, other, text, number)
    }

    /// Taps an option. On a single-select it replaces the choice and any typed answer.
    public mutating func toggle(_ label: String, in q: QuestionItem) {
        if !q.multiSelect {
            selected = [label]
            other = ""
        } else if selected.contains(label) {
            selected.remove(label)
        } else {
            selected.insert(label)
        }
    }

    /// Types the "Other…" answer. On a single-select it replaces the chosen option.
    public mutating func type(other text: String, in q: QuestionItem) {
        other = text
        if !q.multiSelect && !text.trimmingCharacters(in: .whitespaces).isEmpty { selected = [] }
    }

    /// This question's answer, or nil while it has none.
    public func answer(to q: QuestionItem) -> String? {
        switch q.kind {
        case .text: return text.trimmed.nonEmpty
        case .number:
            guard let v = Double(number.trimmed), v.isFinite else { return nil }
            return formatNumber(min(max(v, q.min ?? -.infinity), q.max ?? .infinity))
        case .choice:
            // Labels in option order, so the answer doesn't depend on tap order.
            let labels = q.options.map(\.label).filter(selected.contains)
            let typed = other.trimmed
            if q.multiSelect { return (typed.isEmpty ? labels : labels + [typed]).joined(separator: ", ").nonEmpty }
            return typed.nonEmpty ?? labels.first
        }
    }
}

/// The `chat_answer` answers (question text → answer), or nil while any question has none.
public func buildAnswers(_ questions: [QuestionItem], _ inputs: [QuestionInput]) -> [String: String]? {
    guard !questions.isEmpty, inputs.count >= questions.count else { return nil }
    var out: [String: String] = [:]
    for (q, input) in zip(questions, inputs) {
        guard let a = input.answer(to: q) else { return nil }
        out[q.question] = a
    }
    return out
}

/// "1–8", "≥ 0.5", "≤ 10"; nil without bounds.
public func numberRange(_ q: QuestionItem) -> String? {
    switch (q.min, q.max) {
    case let (lo?, hi?): "\(formatNumber(lo))–\(formatNumber(hi))"
    case let (lo?, nil): "≥ \(formatNumber(lo))"
    case let (nil, hi?): "≤ \(formatNumber(hi))"
    case (nil, nil): nil
    }
}

/// `10`, not `10.0`; `2.5`, not `2.50`.
func formatNumber(_ v: Double) -> String {
    v == v.rounded() && abs(v) < 1e15 ? String(Int(v)) : String(v)
}

extension String {
    public var trimmed: String { trimmingCharacters(in: .whitespacesAndNewlines) }
    public var nonEmpty: String? { isEmpty ? nil : self }
}
