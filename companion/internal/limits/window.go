// Package limits keeps the account's rate-limit reading (Claude's 5-hour
// and 7-day windows) as the herdr-chat mods last reported it.
package limits

// Window is one rate-limit window: Kind "five_hour" or "seven_day",
// PercentUsed 0-100, ResetsAt an ISO 8601 time (empty when unknown).
type Window struct {
	Kind        string  `json:"kind"`
	PercentUsed float64 `json:"percentUsed"`
	ResetsAt    string  `json:"resetsAt,omitempty"`
}
