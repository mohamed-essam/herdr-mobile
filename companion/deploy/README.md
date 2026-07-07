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

Verify from a laptop on the tailnet:

```bash
# expects welcome + panes frames
websocat ws://$TS_IP:8787/
```
