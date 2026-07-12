# Security policy

## Threat model (read this before deploying)

The v1 companion daemon (`herdr-mobiled`) is designed to run on a **trusted private
network**, not the public internet.

- **No authentication.** The WebSocket API accepts any client that can reach the listen
  address. There is no token, password, or TLS in v1.
- **Full terminal control.** A connected client can read pane output *and send input to
  your terminals*, including agent panes and shells. Anyone who can reach the port can
  run commands as you.

Because of this, the only supported deployment is:

- Bind `--listen` to a **private address** — your [Tailscale](https://tailscale.com)
  tailnet IP is the intended setup. A loopback bind (`127.0.0.1`) plus an SSH tunnel is
  also fine.
- **Never** bind to a public interface or `0.0.0.0`. The daemon logs a warning when it
  detects a non-loopback bind as a reminder.

Authentication (token / QR pairing) is planned; the `Authorizer` interface in
`companion/internal/wsserver/auth.go` is the drop-in point. Until then, network isolation
is the security boundary.

## Supported versions

This is a young project under active development. Security fixes land on the default
branch and in the next tagged release; there are no long-term support branches.

## Reporting a vulnerability

Please report security issues **privately** rather than opening a public issue.

- Use GitHub's [private vulnerability reporting](https://github.com/mohamed-essam/herdr-mobile/security/advisories/new)
  ("Report a vulnerability" under the Security tab), or
- open a minimal issue asking for a private contact channel without disclosing details.

Please include what you were doing, what happened, and a reproduction if you have one.
I'll acknowledge as quickly as I can and keep you posted on a fix.
