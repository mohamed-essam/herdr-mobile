import Foundation
import Testing
@testable import HerdrKit

// MARK: Timeline rows

@Test func foldsToolCallsOntoOneRailAndPullsOutAgentCards() {
    let items = buildTimeline(entries: [
        user(1, "go"),
        call(2, "t1", "Read"), result(3, "t1"), call(4, "t2", "Edit"),
        call(5, "a1", "Agent", summary: "Agent: explore"),
        call(6, "t3"),
        call(7, "a1", "Agent"), // a resync overlap repeats the launch
        call(8, "w1", "Workflow"),
        asked(9, "q1"), call(10, "q1", "AskUserQuestion"),
        said(11, "done"), notice(12), notice(13),
    ], epoch: 2)
    #expect(items.map(\.key) == ["e2-1", "e2-2", "a:a1", "e2-6", "a:w1", "e2-9", "e2-11", "e2-12", "e2-13"])
    #expect(items.map(\.head) == [true, true, false, false, false, false, false, true, true])
    #expect(items.map(\.speaker) == [.user, .agent, .agent, .agent, .agent, .agent, .agent, .notice, .notice])
    guard case .tools(_, let calls, _) = items[1] else { Issue.record("not a rail"); return }
    #expect(calls.map(\.toolUseId) == ["t1", "t2"], "results show under their call; the rail keeps its first call's key")
    guard case .agentCard(_, let launch, _) = items[2] else { Issue.record("not a card"); return }
    #expect(launch == ToolCall(toolUseId: "a1", tool: "Agent", summary: "Agent: explore", ts: nil))
}

@Test func nothingFoldsAcrossAPageSeam() {
    let entries = [call(1, "t1"), result(2, "t1"), call(3, "t2"), said(4, "x")]
    #expect(buildTimeline(entries: entries, epoch: 1).map(\.key) == ["e1-1", "e1-4"])
    #expect(buildTimeline(entries: entries, epoch: 1, pageStarts: [2]).map(\.key) == ["e1-1", "e1-3", "e1-4"],
            "the seam's own entry may be one that isn't shown")
    #expect(buildTimeline(entries: entries, epoch: 1, pageStarts: [3]).map(\.head) == [true, true, false])
}

@Test func runningToolsAreTheNewestRailsUnfinishedCalls() {
    let items = buildTimeline(entries: [call(1, "old"), said(2, "x"), call(3, "t1"), result(4, "t1"), call(5, "t2")], epoch: 1)
    #expect(runningTools(items, resultIds: ["t1"], working: true) == ["t2"])
    #expect(runningTools(items, resultIds: ["t1"], working: false).isEmpty)
    #expect(runningTools(Array(items.prefix(2)), resultIds: [], working: true).isEmpty, "the newest row isn't a rail")
}

@Test func thePendingQuestionIsTheNewestUnansweredOneWithNothingAfterIt() {
    let q1 = asked(1, "q1"), q2 = asked(3, "q2")
    #expect(pendingQuestion([q1, said(2, "x"), q2, call(4, "t")], answered: [])?.toolUseId == "q2")
    #expect(pendingQuestion([q1, said(2, "x"), q2], answered: ["q2"]) == nil)
    #expect(pendingQuestion([q1, user(2, "moved on")], answered: []) == nil)
    #expect(pendingQuestion([said(1, "x")], answered: []) == nil)
}

@Test func theHeaderStatusPutsTheQuestionFirst() {
    #expect(chatStatus(connected: false, state: .working, questionPending: true) == ChatStatus(label: "reconnecting…", kind: .offline))
    #expect(chatStatus(connected: true, state: .working, questionPending: true) == ChatStatus(label: "waiting on you", kind: .waiting))
    #expect(chatStatus(connected: true, state: .working, questionPending: false) == ChatStatus(label: "working", kind: .working))
    #expect(chatStatus(connected: true, state: .idle, questionPending: false) == ChatStatus(label: "idle", kind: .idle))
}

@Test func timestampsShowTheDayOnlyWhenItIsNotToday() {
    let utc = TimeZone(identifier: "UTC")!, en = Locale(identifier: "en_US_POSIX")
    let now = 1_791_279_000_000 // 2026-10-06 09:30 UTC
    #expect(formatTs(ms: now - 3_600_000, now: now, zone: utc, locale: en) == "08:30")
    #expect(formatTs(ms: now - 86_400_000, now: now, zone: utc, locale: en) == "Oct 5 09:30")
}

@Test func taskNoticesReadAsOneLine() {
    func n(_ status: String, _ summary: String) -> ChatEvent { .taskNotice(uuid: "n", status: status, summary: summary, ts: nil) }
    #expect(taskNoticeLabel(n("completed", " tests passed ")) == "⚙ tests passed")
    #expect(taskNoticeLabel(n("failed", "")) == "⚙ background task failed")
    #expect(taskNoticeLabel(n("", "")) == "⚙ background task")
    #expect(taskNoticeIsError(n("killed", "")) && taskNoticeIsError(n("failed", "")) && !taskNoticeIsError(n("completed", "")))
}

// MARK: Agent cards

@Test func aCardFindsItsAgentsOldestFirstAndGroupsThemByPhase() {
    let a = agentSummary("a", parent: "w1", phase: "plan", ts: 3)
    let b = agentSummary("b", parent: "w1", phase: "build", ts: 1)
    let c = agentSummary("c", parent: "w1", phase: nil, ts: 2)
    let other = agentSummary("d", parent: "w2", ts: 0)
    let mine = agentsFor(toolUseId: "w1", agents: ["a": a, "b": b, "c": c, "d": other])
    #expect(mine.map(\.agentId) == ["b", "c", "a"])
    #expect(workflowGroups(mine).map(\.phase) == ["build", nil, "plan"])
}

@Test func aCardWithoutASummaryTakesItsStatusFromTheCallsResult() {
    func r(_ preview: String, error: Bool = false) -> ChatEvent {
        .toolResult(toolUseId: "a1", isError: error, preview: preview, images: [], ts: nil, imageSizes: [:])
    }
    #expect(fallbackStatus(result: nil, working: true) == .running)
    #expect(fallbackStatus(result: nil, working: false) == .unknown)
    #expect(fallbackStatus(result: r("boom", error: true), working: true) == .failed)
    #expect(fallbackStatus(result: r(#"{"status":"async_launched"}"#), working: false) == .launched)
    #expect(fallbackStatus(result: r("Async agent launched"), working: false) == .launched)
    #expect(fallbackStatus(result: r("all good"), working: true) == .done)
    #expect(fallbackActivity(.launched) == "running in background" && fallbackActivity(.running) == "starting…")
    #expect(fallbackActivity(.done) == "")
}

@Test func aWorkflowIsRunningWhileAnyAgentRuns() {
    let run = agentSummary("a", parent: "w", status: .running), fail = agentSummary("b", parent: "w", status: .failed)
    let done = agentSummary("c", parent: "w", status: .done)
    #expect(workflowStatus([done, fail, run], result: nil, working: false) == .running)
    #expect(workflowStatus([done, fail], result: nil, working: false) == .failed)
    #expect(workflowStatus([done], result: nil, working: false) == .done)
    #expect(workflowStatus([], result: nil, working: true) == .running)
    #expect(CardStatus(agentSummary("x", parent: "w", status: .unknown("paused")).status) == .unknown)
}

@Test func aWorkflowCardIsNamedByItsTask() {
    let tasks = [bgTask("t", kind: .workflow, label: "nightly", toolUseId: "w1"), bgTask("s", kind: .shell, label: "x", toolUseId: "w2")]
    #expect(workflowTitle(toolUseId: "w1", tasks: tasks) == "nightly")
    #expect(workflowTitle(toolUseId: "w2", tasks: tasks) == "Workflow")
}

@Test func elapsedTimeIsCompact() {
    #expect(elapsedLabel(from: 0, to: 12_999) == "12s")
    #expect(elapsedLabel(from: 0, to: 240_000) == "4m")
    #expect(elapsedLabel(from: 0, to: 7_300_000) == "2h")
    #expect(elapsedLabel(from: 10, to: 0) == "0s")
}

// MARK: Background tasks

@Test func theStripCountsRunningDoneAndFailed() {
    let tasks = [bgTask("1"), bgTask("2"), bgTask("3", status: .done), bgTask("4", status: .failed), bgTask("5", status: .unknown("x"))]
    let counts = TaskCounts(tasks)
    #expect(counts == TaskCounts(running: 2, done: 1, failed: 1))
    #expect(stripLabel(counts) == "⟳ 2 running · 1 done · 1 failed")
    #expect(stripLabel(TaskCounts(running: 0, done: 3, failed: 0)) == "⟳ 3 done")
    #expect(stripLabel(TaskCounts([])) == nil)
}

@Test func aTaskRunsUntilItEnds() {
    #expect(taskDuration(bgTask("1", startedAt: 0), now: 4_000) == "4s")
    #expect(taskDuration(bgTask("1", status: .done, startedAt: 0, endedAt: 125_000), now: 999_000) == "2m 05s")
    #expect(taskDuration(bgTask("1", status: .failed, startedAt: 0), now: 3_780_000) == "1h 03m")
    #expect([TaskKind.shell, .subagent, .workflow, .monitor, .unknown("x")].map(taskGlyph) == ["$", "◆", "⧉", "◉", "•"])
    #expect(bgTask("1", kind: .subagent).hasCard && bgTask("1", kind: .workflow).hasCard && !bgTask("1", kind: .monitor).hasCard)
}

// MARK: Threads

@Test func backWalksUpTheThreadStackOneLevelAtATime() {
    var s = ThreadStack()
    #expect(s.top == nil)
    s.push("a")
    s.push("b")
    s.push("b")
    #expect(s.ids == ["a", "b"], "the thread on top isn't stacked twice")
    s.pop()
    #expect(s.top == "a")
    s.pop()
    s.pop()
    #expect(s.top == nil)
}

// MARK: Links

@Test func onlyWebAndMailLinksOpen() {
    for ok in ["https://example.com", "HTTP://x.io/a?b#c", " mailto:me@x.io "] { #expect(isOpenableLink(ok), "\(ok)") }
    for bad in ["tel:123", "file:///etc/passwd", "javascript:alert(1)", "myapp://open", "https://", "http:// x", "ftp://x"] {
        #expect(!isOpenableLink(bad), "\(bad)")
    }
}

// MARK: Answering questions

private func numberQ(min: Double? = nil, max: Double? = nil) -> QuestionItem {
    QuestionItem(question: "How many?", header: "N", kind: .number, options: [], multiSelect: false, min: min, max: max,
                 step: nil, unit: nil, placeholder: nil, description: nil)
}

@Test func eachKindOfQuestionHasItsAnswer() {
    let single = question(), multi = question("Which?", options: ["a", "b", "c"], multiSelect: true)
    let text = question("Why?", kind: .text)
    #expect(QuestionInput().answer(to: single) == nil)
    #expect(QuestionInput(selected: ["SQLite"]).answer(to: single) == "SQLite")
    #expect(QuestionInput(selected: ["SQLite"], other: " Mongo ").answer(to: single) == "Mongo", "typed text wins")
    let five = question("Which?", options: ["a", "b", "c", "d", "e"], multiSelect: true)
    #expect(QuestionInput(selected: ["e", "c", "a", "d", "b"]).answer(to: five) == "a, b, c, d, e", "option order, not tap order")
    #expect(QuestionInput(selected: ["b"], other: "z").answer(to: multi) == "b, z")
    #expect(QuestionInput(text: "  ").answer(to: text) == nil)
    #expect(QuestionInput(text: " because ").answer(to: text) == "because")
    #expect(QuestionInput(number: "12").answer(to: numberQ(max: 8)) == "8")
    #expect(QuestionInput(number: "2.50").answer(to: numberQ()) == "2.5")
    #expect(QuestionInput(number: "-3").answer(to: numberQ(min: 0)) == "0")
    #expect(QuestionInput(number: "x").answer(to: numberQ()) == nil)
    #expect(QuestionInput(number: "inf").answer(to: numberQ()) == nil)
}

@Test func choosingAnOptionAndTypingReplaceEachOtherOnASingleSelect() {
    let single = question(), multi = question("Which?", options: ["a", "b"], multiSelect: true)
    var input = QuestionInput()
    input.toggle("SQLite", in: single)
    input.toggle("Postgres", in: single)
    #expect(input.selected == ["Postgres"])
    input.type(other: "Mongo", in: single)
    #expect(input.selected.isEmpty && input.other == "Mongo")
    input.toggle("SQLite", in: single)
    #expect(input.selected == ["SQLite"] && input.other.isEmpty)

    var m = QuestionInput()
    m.toggle("a", in: multi)
    m.toggle("b", in: multi)
    m.toggle("a", in: multi)
    m.type(other: "z", in: multi)
    #expect(m.selected == ["b"] && m.other == "z")
}

@Test func theAnswersNeedEveryQuestion() {
    let qs = [question(), question("Why?", kind: .text)]
    #expect(buildAnswers(qs, [QuestionInput(selected: ["SQLite"]), QuestionInput()]) == nil)
    #expect(buildAnswers(qs, [QuestionInput(selected: ["SQLite"])]) == nil)
    #expect(buildAnswers([], []) == nil)
    #expect(buildAnswers(qs, [QuestionInput(selected: ["SQLite"]), QuestionInput(text: "fast")])
            == ["Which DB?": "SQLite", "Why?": "fast"])
}

@Test func aNumberQuestionShowsItsRange() {
    #expect(numberRange(numberQ(min: 1, max: 8)) == "1–8")
    #expect(numberRange(numberQ(min: 0.5)) == "≥ 0.5")
    #expect(numberRange(numberQ(max: 10)) == "≤ 10")
    #expect(numberRange(numberQ()) == nil)
}

@Test func answerErrorsReadAsSentences() {
    #expect(answerErrorLabel(CompanionError.rejected("no_mod")) == "Claude isn't connected")
    #expect(answerErrorLabel(CompanionError.rejected("empty")) == "empty answer")
    #expect(answerErrorLabel(CompanionError.timedOut) == "answer failed: timed out")
}
