#!/usr/bin/env bash
# e2e-ios.sh — run the iOS Maestro flows in e2e/ios/flows on a simulator,
# against the fake companion (companion/cmd/fake-companion). Builds everything
# it needs: no Xcode step, signing, or booted simulator required.
#
# Usage:
#   scripts/e2e-ios.sh                          # every flow
#   scripts/e2e-ios.sh e2e/ios/flows/connect.yaml   # one flow
#
# Env: SIM_NAME (default herdr-e2e, created if missing), WS_PORT (18787),
# CONTROL_PORT (18788). On failure, Maestro's screenshots and logs and the
# fake companion's log stay in build/e2e-ios/artifacts.
set -euo pipefail

export MAESTRO_CLI_NO_ANALYTICS=1 MAESTRO_CLI_ANALYSIS_NOTIFICATION_DISABLED=true

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/build/e2e-ios"
ART="$OUT/artifacts"
SIM_NAME="${SIM_NAME:-herdr-e2e}"
WS_PORT="${WS_PORT:-18787}"
CONTROL_PORT="${CONTROL_PORT:-18788}"
FLOWS="${1:-$REPO/e2e/ios/flows}"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

for tool in go xcodebuild maestro; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done
rm -rf "$ART"
mkdir -p "$ART"

log "Building fake companion…"
(cd "$REPO/companion" && go build -o "$OUT/fake-companion" ./cmd/fake-companion) &
GO_PID=$!

# A simulator this script owns, picked by UDID: names like "iPhone 17 Pro" repeat.
UDID="$(xcrun simctl list devices available | grep -E "^ +$SIM_NAME \(" | grep -oE '[0-9A-F-]{36}' | head -1 || true)"
if [[ -z "$UDID" ]]; then
  log "Creating simulator ${SIM_NAME}…"
  UDID="$(xcrun simctl create "$SIM_NAME" "iPhone 17 Pro")"
fi

log "Booting simulator…"
xcrun simctl bootstatus "$UDID" -b >/dev/null &
BOOT_PID=$!

log "Building app for $SIM_NAME ($UDID)…"
# Packages may ship build plugins; Xcode asks to trust each one once, xcodebuild needs this flag.
xcodebuild -quiet -skipPackagePluginValidation -project "$REPO/ios/HerdrMobile.xcodeproj" -scheme HerdrMobile \
  -destination "id=$UDID" -derivedDataPath "$OUT/DerivedData" build
wait "$GO_PID"
wait "$BOOT_PID"
xcrun simctl install "$UDID" "$OUT/DerivedData/Build/Products/Debug-iphonesimulator/HerdrMobile.app"

log "Starting fake companion (ws :$WS_PORT, control :${CONTROL_PORT})…"
"$OUT/fake-companion" -listen "127.0.0.1:$WS_PORT" -control "127.0.0.1:$CONTROL_PORT" \
  -scenarios "$REPO/e2e/scenarios" >"$ART/fake-companion.log" 2>&1 &
FAKE_PID=$!
trap 'kill $FAKE_PID 2>/dev/null || true' EXIT
# Ready = our fake logged it bound both ports; a stale one on them must not pass.
for _ in $(seq 50); do
  grep -q 'fake-companion: ws=' "$ART/fake-companion.log" && break
  sleep 0.1
done
grep -q 'fake-companion: ws=' "$ART/fake-companion.log" || { cat "$ART/fake-companion.log" >&2; exit 1; }

log "Running Maestro flows…"
if maestro --device "$UDID" test "$FLOWS" \
  -e COMPANION_URL="ws://127.0.0.1:$WS_PORT" -e COMPANION_HOST="127.0.0.1.nip.io:$WS_PORT" \
  -e CONTROL_URL="http://127.0.0.1:$CONTROL_PORT" \
  --test-output-dir "$ART/maestro" --debug-output "$ART/maestro"; then
  rm -rf "$ART"
  log "All flows passed."
else
  log "Flows failed. Artifacts: $ART"
  exit 1
fi
