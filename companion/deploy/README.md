# Deploying the companion

```bash
cd companion
go build -o ~/.local/bin/herdr-mobiled ./cmd/herdr-mobiled

# Find your tailnet IP:
TS_IP=$(tailscale ip -4)

# Install the user service, templated with your tailnet IP:
mkdir -p ~/.config/systemd/user
sed "s/%i/$TS_IP/" deploy/herdr-mobiled.service > ~/.config/systemd/user/herdr-mobiled.service
systemctl --user daemon-reload
systemctl --user enable --now herdr-mobiled
systemctl --user status herdr-mobiled
```

The unit puts `~/.local/bin` on `PATH`, because opening a pane's terminal runs
`herdr terminal attach` and the systemd user manager's default `PATH` doesn't
include it. If herdr is installed somewhere else (`command -v herdr`), add that
directory to the unit's `Environment=PATH=` line. Otherwise opening a terminal from
the phone fails with `exec: "herdr": executable file not found in $PATH`.

Verify from a laptop on the tailnet:

```bash
# expects welcome + panes frames
websocat ws://$TS_IP:8787/
```
