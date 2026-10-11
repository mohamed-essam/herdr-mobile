import Testing
@testable import HerdrKit

// MARK: Tree

@Test func buildsTheTreeInNumberOrderWithOrphansLast() {
    let tree = buildTree(
        workspaces: [workspace("w2", "b", number: 2), workspace("w1", "a", number: 1)],
        tabs: [tab("w1:t2", number: 2), tab("w1:t1", number: 1), tab("w2:t1", ws: "w2")],
        panes: [pane("w1:p2", tab: "w1:t1"), pane("w1:p1", tab: "w1:t1"), pane("w1:p3", tab: "w1:t2"),
                pane("w9:p1", ws: "w9"), pane("w2:p1", ws: "w2")])
    #expect(tree.map(\.workspace.workspaceId) == ["w1", "w2", ""])
    #expect(tree[0].tabs.map(\.tab.tabId) == ["w1:t1", "w1:t2"])
    #expect(tree[0].tabs[0].panes.map(\.paneId) == ["w1:p1", "w1:p2"])
    #expect(tree[0].panes.map(\.paneId) == ["w1:p1", "w1:p2", "w1:p3"])
    #expect(tree[2].workspace.label == "(unknown)" && tree[2].panes.map(\.paneId) == ["w9:p1"])
}

@Test func namesARepoByWorktreeThenCwdThenLabelThenId() {
    func key(_ ws: Workspace, _ panes: [Pane]) -> String {
        repoKey(for: WorkspaceNode(workspace: ws, tabs: [TabNode(tab: tab("t"), panes: panes)]))
    }
    #expect(key(workspace("w1", "lbl", repo: "herdr"), [pane("p", cwd: "/x/other")]) == "herdr")
    #expect(key(workspace("w1", "lbl"), [pane("p", cwd: ""), pane("q", cwd: "/x/app/")]) == "lbl")
    #expect(key(workspace("w1", "lbl"), [pane("p", cwd: "/x/app")]) == "app")
    #expect(key(workspace("w1", ""), []) == "w1")
    #expect(key(workspace("", "(unknown)"), [pane("p", cwd: "/x/app")]) == "(unknown)")
}

@Test func groupsByRepoSortingAttentionThenRecencyThenNumber() {
    let tree = buildTree(
        workspaces: [
            workspace("w1", "quiet", number: 1, lastActivity: 900, repo: "alpha"),
            workspace("w2", "done", number: 2, lastActivity: 100, repo: "alpha"),
            workspace("w3", "recent", number: 3, lastActivity: 500, repo: "beta"),
            workspace("w4", "blocked", number: 4, lastActivity: 10, repo: "gamma"),
            workspace("w5", "older", number: 5, lastActivity: 400, repo: "beta"),
        ],
        tabs: (1...5).map { tab("w\($0):t1", ws: "w\($0)") },
        panes: [pane("w1:p1", ws: "w1"), pane("w2:p1", ws: "w2", status: .done), pane("w3:p1", ws: "w3", status: .working),
                pane("w4:p1", ws: "w4", status: .blocked), pane("w5:p1", ws: "w5"), pane("w9:p1", ws: "w9", status: .blocked)])
    let repos = buildRepoTree(tree)
    // gamma has a blocked pane, alpha a done one; "(unknown)" is last even though it is blocked.
    #expect(repos.map(\.name) == ["gamma", "alpha", "beta", "(unknown)"])
    #expect(repos[1].workspaces.map(\.workspace.label) == ["done", "quiet"])
    #expect(repos[2].workspaces.map(\.workspace.label) == ["recent", "older"])
}

@Test func aWorkspaceHeadlinesItsMostUrgentAgent() {
    func node(_ panes: [Pane]) -> WorkspaceNode { WorkspaceNode(workspace: workspace("w1"), tabs: [TabNode(tab: tab("t"), panes: panes)]) }
    #expect(node([pane("a", status: .done), pane("b", status: .working)]).status == .working)
    #expect(node([pane("a", status: .done), pane("b", status: .blocked)]).status == .blocked)
    #expect(node([pane("a", agent: nil)]).status == .idle)
    #expect(node([pane("a", status: .working), pane("b", status: .blocked)]).mostRelevantPane?.paneId == "b")
    #expect(node([pane("a", status: .idle), pane("b", status: .working)]).mostRelevantPane?.paneId == "b")
    #expect(node([pane("a", status: .idle), pane("b", status: .done)]).mostRelevantPane?.paneId == "a")
}

@Test func filtersReposByLabelRepoAgentOrPath() {
    let tree = buildTree(
        workspaces: [workspace("w1", "api", repo: "shop"), workspace("w2", "web", number: 2, repo: "shop"), workspace("w3", "notes", number: 3, repo: "wiki")],
        tabs: [tab("w1:t1"), tab("w2:t1", ws: "w2"), tab("w3:t1", ws: "w3")],
        panes: [pane("w1:p1", agent: "codex"), pane("w2:p1", ws: "w2", cwd: "/src/Frontend"), pane("w3:p1", ws: "w3")])
    let repos = buildRepoTree(tree)
    #expect(filterRepos(repos, query: "  ") == repos)
    #expect(filterRepos(repos, query: "CODEX").map { $0.workspaces.map(\.workspace.label) } == [["api"]])
    #expect(filterRepos(repos, query: "frontend").map { $0.workspaces.map(\.workspace.label) } == [["web"]])
    #expect(filterRepos(repos, query: "shop").map { $0.workspaces.count } == [2])
    #expect(filterRepos(repos, query: "nothing").isEmpty)
}

// MARK: Sections

@Test func sortsTheDashboardIntoSections() {
    let panes = [
        pane("old-block", status: .blocked, ts: 100),
        pane("new-block", status: .blocked, ts: 200),
        pane("no-ts-block", status: .blocked),
        pane("asking", status: .working, ts: 300, ask: ask()),
        pane("work-old", status: .working, ts: 100),
        pane("work-new", status: .working, ts: 900),
        pane("done", status: .done, ts: 50),
        pane("shell", agent: nil),
        pane("idle-agent", status: .idle),
    ]
    let s = DashboardSections(panes: panes)
    #expect(s.needsYou.map(\.paneId) == ["old-block", "new-block", "asking", "no-ts-block"])
    #expect(s.working.map(\.paneId) == ["work-new", "work-old"])
    #expect(s.done.map(\.paneId) == ["done"])
    #expect(s.idle.map(\.paneId) == ["idle-agent", "shell"])
}

@Test func countsTheStatTiles() {
    let counts = StatusCounts(panes: [
        pane("a", status: .blocked), pane("b", status: .working, ask: ask()), pane("c", status: .working),
        pane("d", status: .done), pane("e", agent: nil),
    ])
    #expect(counts == StatusCounts(blocked: 2, working: 1, done: 1))
}

@Test func answersOnlyALoneSingleChoiceInline() {
    #expect(ask().inlineChoice == question())
    #expect(ask("t", [question(), question("Second?")]).inlineChoice == nil)
    #expect(ask("t", [question(multiSelect: true)]).inlineChoice == nil)
    #expect(ask("t", [question(kind: .text)]).inlineChoice == nil)
    #expect(ask("t", [question(options: [])]).inlineChoice == nil)
    #expect(question().answer("SQLite") == ["Which DB?": "SQLite"])
}

@Test func inlineAnswersGoFromSendingToSentOrBackToIdle() {
    var answers = InlineAnswers()
    var started = answers.start(paneId: "p", toolUseId: "q1", label: "SQLite")
    #expect(started && answers["p"]?.phase == .sending)
    started = answers.start(paneId: "p", toolUseId: "q1", label: "Postgres")
    #expect(!started && answers["p"]?.label == "SQLite", "one answer at a time per pane")
    answers.failed(paneId: "p")
    #expect(answers["p"] == nil)

    started = answers.start(paneId: "p", toolUseId: "q1", label: "SQLite")
    #expect(started)
    answers.sent(paneId: "p")
    #expect(answers.resumed == [InlineAnswer(paneId: "p", toolUseId: "q1", label: "SQLite", phase: .sent)])
    answers.expire(paneId: "p", toolUseId: "other")
    #expect(answers["p"] != nil, "a newer answer isn't expired by an older timer")
    answers.expire(paneId: "p", toolUseId: "q1")
    #expect(answers["p"] == nil)
}

@Test func aSentAnswerHidesItsPaneUntilANewQuestion() {
    var answers = InlineAnswers()
    _ = answers.start(paneId: "p", toolUseId: "q1", label: "SQLite")
    let blocked = pane("p", status: .blocked, ask: ask("q1"))
    #expect(DashboardSections(panes: [blocked], answers: answers).needsYou.map(\.paneId) == ["p"], "still sending")
    answers.sent(paneId: "p")
    let hidden = DashboardSections(panes: [blocked], answers: answers)
    #expect(hidden.needsYou.isEmpty && hidden.idle.isEmpty)
    #expect(DashboardSections(panes: [pane("p", status: .blocked, ask: ask("q2"))], answers: answers).needsYou.map(\.paneId) == ["p"])
}

// MARK: Close and action sheet

private let closeTree = buildTree(
    workspaces: [workspace("w1", "chat-view", number: 3, repo: "herdr")],
    tabs: [tab("w1:t1", number: 1), tab("w1:t2", number: 2)],
    panes: [pane("w1:p1", tab: "w1:t1", status: .working), pane("w1:p2", tab: "w1:t1", agent: nil),
            pane("w1:p3", tab: "w1:t2", agent: "codex", status: .blocked)])

@Test func summarisesWhatACloseEnds() {
    let ws = workspaceAction(closeTree[0])
    #expect(closeSummary(for: ws, in: closeTree) == CloseSummary(headline: "chat-view ends 3 panes across 2 tabs", panes: [
        ClosingPane(name: "claude", status: .agent(.working), tab: "tab 1"),
        ClosingPane(name: "shell", status: .shell, tab: "tab 1"),
        ClosingPane(name: "codex", status: .agent(.blocked), tab: "tab 2"),
    ]))
    let t = tabAction(closeTree[0].tabs[1])
    #expect(closeSummary(for: t, in: closeTree).headline == "tab 2 ends 1 pane")
    let p = paneAction(closeTree[0].tabs[0].panes[0])
    #expect(closeSummary(for: p, in: closeTree).headline == "Closing this pane terminates claude")
    #expect(closeSummary(for: paneAction(closeTree[0].tabs[0].panes[1]), in: closeTree).headline == "Closing this pane ends this shell")
    // A workspace the tree doesn't hold falls back to the action's own counts.
    #expect(closeSummary(for: RowAction(kind: .workspace, id: "gone", label: "old", paneCount: 1, tabCount: 1), in: closeTree)
        == CloseSummary(headline: "old ends 1 pane", panes: []))
}

@Test func confirmsClosesThatEndAgentsOrSeveralPanes() {
    #expect(needsCloseConfirm(paneAction(pane("p"))))
    #expect(!needsCloseConfirm(paneAction(pane("p", agent: nil))))
    #expect(needsCloseConfirm(tabAction(TabNode(tab: tab("t"), panes: [pane("a", agent: nil), pane("b", agent: nil)]))))
    #expect(!needsCloseConfirm(tabAction(TabNode(tab: tab("t"), panes: [pane("a", agent: nil)]))))
    #expect(needsCloseConfirm(tabAction(TabNode(tab: tab("t"), panes: [pane("a")]))))
    #expect(needsCloseConfirm(workspaceAction(closeTree[0])))
}

@Test func findsAPanesTabForTheTabActionsPivot() {
    let pivot = tabAction(ofPane: closeTree[0].tabs[0].panes[0].paneId, in: closeTree)
    #expect(pivot == tabAction(closeTree[0].tabs[0]))
    #expect(pivot?.workspaceId == "w1" && pivot?.paneCount == 2 && pivot?.hasAgent == true)
    // The orphan tab has no id to act on.
    let orphans = buildTree(workspaces: [], tabs: [], panes: [pane("x")])
    #expect(tabAction(ofPane: "x", in: orphans) == nil)
    #expect(tabOf(paneId: "w1:p3", in: closeTree)?.tab.tabId == "w1:t2")
}

@Test func headsAnActionSheetWithItsPath() {
    let repos = buildRepoTree(closeTree)
    #expect(actionHeader(for: workspaceAction(closeTree[0]), in: repos) == ActionHeader(number: 3, title: "chat-view", path: "herdr", status: .agent(.blocked)))
    #expect(actionHeader(for: tabAction(closeTree[0].tabs[0]), in: repos) == ActionHeader(number: 3, title: "tab 1", path: "herdr › chat-view", status: .agent(.working)))
    #expect(actionHeader(for: paneAction(closeTree[0].tabs[1].panes[0]), in: repos)
        == ActionHeader(number: 3, title: "codex", path: "herdr › chat-view › tab 2", status: .agent(.blocked)))
    #expect(actionHeader(for: RowAction(kind: .pane, id: "gone", label: "x"), in: repos) == ActionHeader(number: 0, title: "x", path: "", status: nil))
}

// MARK: Rows

@Test func describesWhereAPaneLives() {
    let contexts = paneContexts(buildRepoTree(closeTree))
    let ctx = try! #require(contexts["w1:p3"])
    #expect(ctx.workspaceLabel == "chat-view" && ctx.workspaceNumber == 3 && ctx.repoName == "herdr")
    #expect(ctx.tabCrumb == "tab 2")
    #expect(ctx.meta == "herdr · codex")
    let lost = PaneContext(fallback: pane("x", agent: nil, cwd: "/home/me/api"))
    #expect(lost.workspaceLabel == "api" && lost.meta == "shell")
}

@Test func linesUpTheLatestActivity() {
    #expect(activityLine(nil) == nil)
    #expect(activityLine(PaneActivity(kind: .text, tool: nil, text: "\n  Done.  \nmore", ts: 0)) == "Done.")
    #expect(activityLine(PaneActivity(kind: .tool, tool: "Read", text: "Read: /home/me/app/ui/X.kt", ts: 0)) == "▸ Read ui/X.kt")
    #expect(activityLine(PaneActivity(kind: .tool, tool: "Bash", text: "Bash: npm run build", ts: 0)) == "▸ Bash npm run build")
    #expect(activityLine(PaneActivity(kind: .tool, tool: "Grep", text: "TODO", ts: 0)) == "▸ Grep TODO")
    #expect(activityLine(PaneActivity(kind: .tool, tool: "", text: "", ts: 0)) == nil)
}

@Test func formatsAges() {
    let now = 1_000_000_000
    #expect(relativeAge(ms: 0, now: now) == "")
    #expect(relativeAge(ms: now - 30_000, now: now) == "now")
    #expect(relativeAge(ms: now - 300_000, now: now) == "5m")
    #expect(relativeAge(ms: now - 7_200_000, now: now) == "2h")
    #expect(relativeAge(ms: now - 2 * 86_400_000, now: now) == "2d")
    #expect(staleLabel(sinceMs: now - 32_000, now: now) == "32s ago")
    #expect(staleLabel(sinceMs: now - 300_000, now: now) == "5m ago")
}

@Test func badgesRunningBackgroundTasks() {
    #expect(pane("p", bg: 0).bgBadge == nil)
    #expect(pane("p", bg: 3).bgBadge == "⟳ 3")
    #expect(pane("p", agent: nil).rowStatus == .shell)
    #expect(pane("p", status: .done).rowStatus == .agent(.done))
    #expect(RowStatus.shell.label == "shell" && RowStatus.agent(.done).label == "done")
}

@Test func theChatBreadcrumbNamesRepoTabAndAgent() {
    let ctx = PaneContext(pane: pane("w1:p1"), workspaceLabel: "api", workspaceNumber: 1, repoName: "shop", tabLabel: "1", tabNumber: 2)
    #expect(ctx.breadcrumb == "shop › tab 2 › claude")
    let bare = PaneContext(pane: pane("w1:p1", agent: nil), workspaceLabel: "api", workspaceNumber: 0, repoName: "", tabLabel: "", tabNumber: 0)
    #expect(bare.breadcrumb == "")
}
