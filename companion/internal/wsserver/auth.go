package wsserver

import "net/http"

// Authorizer gates incoming WS connections. v1 uses AllowAll; a future
// token/QR/password check drops in here without touching the server.
type Authorizer interface {
	Authorize(r *http.Request) error
}

type AllowAll struct{}

func (AllowAll) Authorize(*http.Request) error { return nil }
