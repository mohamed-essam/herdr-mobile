package main

import (
	"context"
	"sync"

	"github.com/mohamed-essam/herdr-mobile/companion/internal/chatbridge"
	"github.com/mohamed-essam/herdr-mobile/companion/internal/herdr"
)

// rpcCall is one companion call the app caused, as GET /received reports it:
// a wsserver.HerdrRPC method by name, a ChatHub method as "Chat"+name, or
// RegisterPush, with the arguments in order.
type rpcCall struct {
	Call string `json:"call"`
	Args []any  `json:"args"`
}

// recorder stands in for herdr and the chat hub behind wsserver: every call
// is recorded and succeeds with an empty result.
type recorder struct {
	mu    sync.Mutex
	calls []rpcCall
}

func (r *recorder) record(name string, args ...any) {
	if args == nil {
		args = []any{}
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls = append(r.calls, rpcCall{name, args})
}

func (r *recorder) all() []rpcCall {
	r.mu.Lock()
	defer r.mu.Unlock()
	return append([]rpcCall{}, r.calls...)
}

func (r *recorder) reset() {
	r.mu.Lock()
	defer r.mu.Unlock()
	r.calls = nil
}

// wsserver.HerdrRPC

func (r *recorder) ReadPane(_ context.Context, paneID, source string, lines int) (string, error) {
	r.record("ReadPane", paneID, source, lines)
	return "", nil
}
func (r *recorder) SendText(_ context.Context, paneID, text string) error {
	r.record("SendText", paneID, text)
	return nil
}
func (r *recorder) SendKeys(_ context.Context, paneID, keys string) error {
	r.record("SendKeys", paneID, keys)
	return nil
}
func (r *recorder) RenameWorkspace(_ context.Context, id, label string) error {
	r.record("RenameWorkspace", id, label)
	return nil
}
func (r *recorder) RenameTab(_ context.Context, id, label string) error {
	r.record("RenameTab", id, label)
	return nil
}
func (r *recorder) RenamePane(_ context.Context, id, label string) error {
	r.record("RenamePane", id, label)
	return nil
}
func (r *recorder) CloseWorkspace(_ context.Context, id string) error {
	r.record("CloseWorkspace", id)
	return nil
}
func (r *recorder) CloseTab(_ context.Context, id string) error {
	r.record("CloseTab", id)
	return nil
}
func (r *recorder) ClosePane(_ context.Context, id string) error {
	r.record("ClosePane", id)
	return nil
}
func (r *recorder) CreateWorkspace(context.Context) (string, string, error) {
	r.record("CreateWorkspace")
	return "", "", nil
}
func (r *recorder) CreateTab(_ context.Context, workspaceID string) (string, string, error) {
	r.record("CreateTab", workspaceID)
	return "", "", nil
}
func (r *recorder) SplitPane(_ context.Context, targetPaneID, workspaceID, direction string) (string, string, error) {
	r.record("SplitPane", targetPaneID, workspaceID, direction)
	return "", "", nil
}
func (r *recorder) StartAgent(_ context.Context, name string, argv []string, workspaceID, tabID, split string) (string, string, error) {
	r.record("StartAgent", name, argv, workspaceID, tabID, split)
	return "", "", nil
}
func (r *recorder) MovePane(_ context.Context, paneID, dest, tabID, direction string) error {
	r.record("MovePane", paneID, dest, tabID, direction)
	return nil
}
func (r *recorder) ListAgentNames(context.Context) ([]string, error) {
	r.record("ListAgentNames")
	return nil, nil
}
func (r *recorder) ListWorktrees(_ context.Context, workspaceID string) ([]herdr.WorktreeEntry, error) {
	r.record("ListWorktrees", workspaceID)
	return nil, nil
}

// wsserver.ChatHub. A subscription gets an idle, empty snapshot and no updates.

func (r *recorder) Subscribe(paneID, agentID string) (chatbridge.Snapshot, <-chan chatbridge.Update, func()) {
	r.record("ChatSubscribe", paneID, agentID)
	return chatbridge.Snapshot{PaneID: paneID, AgentID: agentID, State: "idle"}, nil, func() {}
}
func (r *recorder) Send(paneID, text string) error {
	r.record("ChatSend", paneID, text)
	return nil
}
func (r *recorder) History(paneID, agentID string, epoch, beforeSeq, limit int) ([]chatbridge.Entry, bool, bool) {
	r.record("ChatHistory", paneID, agentID, epoch, beforeSeq, limit)
	return nil, false, true
}
func (r *recorder) Image(paneID, id string) (string, string, bool) {
	r.record("ChatImage", paneID, id)
	return "", "", false
}
func (r *recorder) Answer(paneID, toolUseID string, answers map[string]string) error {
	r.record("ChatAnswer", paneID, toolUseID, answers)
	return nil
}
