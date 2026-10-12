package main

import (
	"context"
	"errors"
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
// is recorded and succeeds, with the scenario's agents and worktrees or an
// empty result.
type recorder struct {
	scenario func() scenario

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
	return r.created()
}
func (r *recorder) CreateTab(_ context.Context, workspaceID string) (string, string, error) {
	r.record("CreateTab", workspaceID)
	return r.created()
}
func (r *recorder) SplitPane(_ context.Context, targetPaneID, workspaceID, direction string) (string, string, error) {
	r.record("SplitPane", targetPaneID, workspaceID, direction)
	return r.created()
}
func (r *recorder) StartAgent(_ context.Context, name string, argv []string, workspaceID, tabID, split string) (string, string, error) {
	r.record("StartAgent", name, argv, workspaceID, tabID, split)
	return r.created()
}

// created is the scenario's pane for every create.
func (r *recorder) created() (string, string, error) {
	c := r.scenario().Created
	return c.PaneID, c.TerminalID, nil
}
func (r *recorder) MovePane(_ context.Context, paneID, dest, tabID, direction string) error {
	r.record("MovePane", paneID, dest, tabID, direction)
	return nil
}
func (r *recorder) ListAgentNames(context.Context) ([]string, error) {
	r.record("ListAgentNames")
	return r.scenario().Agents, nil
}
func (r *recorder) ListWorktrees(_ context.Context, workspaceID string) ([]herdr.WorktreeEntry, error) {
	r.record("ListWorktrees", workspaceID)
	return r.scenario().Worktrees[workspaceID], nil
}

// wsserver.ChatHub, served from the scenario's chats and images. A subscription
// gets its snapshot and no updates (flows send those with /emit or /play), and an
// unknown thread is missing.

func (r *recorder) Subscribe(paneID, agentID string) (chatbridge.Snapshot, <-chan chatbridge.Update, func()) {
	r.record("ChatSubscribe", paneID, agentID)
	snap := chatbridge.Snapshot{PaneID: paneID, AgentID: agentID, State: "idle"}
	if c, ok := r.scenario().chat(paneID, agentID); ok {
		snap.Epoch, snap.Events, snap.HasMore, snap.Agents, snap.Tasks = c.epoch(), c.Events, len(c.Older) > 0, c.Agents, c.Tasks
		if c.State != "" {
			snap.State = c.State
		}
	} else if agentID != "" {
		snap.Missing = true
	}
	return snap, nil, func() { r.record("ChatClose", paneID, agentID) }
}
func (r *recorder) Send(paneID, text string) error {
	r.record("ChatSend", paneID, text)
	if c, _ := r.scenario().chat(paneID, ""); c.SendError != "" {
		return errors.New(c.SendError)
	}
	return nil
}
func (r *recorder) History(paneID, agentID string, epoch, beforeSeq, limit int) ([]chatbridge.Entry, bool, bool) {
	r.record("ChatHistory", paneID, agentID, epoch, beforeSeq, limit)
	c, ok := r.scenario().chat(paneID, agentID)
	if !ok || epoch != c.epoch() {
		return nil, false, false
	}
	page, more := chatbridge.Page(c.Older, beforeSeq, limit)
	return page, more, true
}
func (r *recorder) Image(paneID, id string) (string, string, bool) {
	r.record("ChatImage", paneID, id)
	img, ok := r.scenario().Images[id]
	return img.MediaType, img.Data, ok
}
func (r *recorder) Answer(paneID, toolUseID string, answers map[string]string) error {
	r.record("ChatAnswer", paneID, toolUseID, answers)
	return nil
}
