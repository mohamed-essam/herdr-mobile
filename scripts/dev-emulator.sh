#!/usr/bin/env bash
# dev-emulator.sh — run the full herdr-mobile stack against a headless Android
# emulator, with NO physical device and NO Tailscale.
#
# How the bridge works: from inside an Android emulator, the magic IP 10.0.2.2
# is an alias for the host's loopback (127.0.0.1). So we bind the companion to
# 127.0.0.1:8787 on this machine and point the app at ws://10.0.2.2:8787.
# WebSocket traffic never leaves the host — no cleartext-over-LAN, no VPN.
#
# Usage:
#   scripts/dev-emulator.sh            # boot + install + configure + launch
#   scripts/dev-emulator.sh --build    # also rebuild the debug APK first
#
# Requires: Android SDK at $ANDROID_HOME (or ~/Android/Sdk), an AVD, and a
# built herdr-mobiled on $PATH or at ~/.local/bin/herdr-mobiled.
set -euo pipefail

ANDROID_HOME="${ANDROID_HOME:-$HOME/Android/Sdk}"
export ANDROID_HOME
export PATH="$PATH:$ANDROID_HOME/platform-tools:$ANDROID_HOME/emulator"

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
PKG="dev.herdr.mobile"
APK="$REPO/app/app/build/outputs/apk/debug/app-debug.apk"
COMPANION_BIN="${COMPANION_BIN:-$(command -v herdr-mobiled || echo "$HOME/.local/bin/herdr-mobiled")}"
LISTEN="127.0.0.1:8787"
APP_URL="ws://10.0.2.2:8787"
AVD="${AVD:-$(emulator -list-avds | head -1)}"
TMP="${TMPDIR:-/tmp}"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

# 1. Optionally rebuild the APK.
if [[ "${1:-}" == "--build" ]]; then
  log "Building debug APK…"
  (cd "$REPO/app" && ./gradlew :app:assembleDebug)
fi
[[ -f "$APK" ]] || { echo "APK not found at $APK (run with --build)"; exit 1; }

# 2. Boot the emulator headless if none is attached.
if ! adb devices | grep -q "emulator-.*device$"; then
  log "Booting emulator '$AVD' (headless)…"
  nohup emulator -avd "$AVD" -no-window -no-snapshot-save -no-boot-anim \
    -no-audio -gpu swiftshader_indirect > "$TMP/herdr-emulator.log" 2>&1 &
  disown
fi
log "Waiting for device…"
adb wait-for-device
until [[ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" == "1" ]]; do
  sleep 2
done
log "Emulator booted."

# 3. (Re)start the companion bound to loopback.
#    Kill any prior instance by PID (never pkill -f — it matches this script).
for pid in $(pgrep -x herdr-mobiled 2>/dev/null || true); do kill "$pid" 2>/dev/null || true; done
sleep 1
log "Starting companion on $LISTEN…"
setsid "$COMPANION_BIN" --listen "$LISTEN" > "$TMP/herdr-companion.log" 2>&1 &
disown
sleep 1

# 4. Install the APK.
log "Installing APK…"
adb install -r "$APK" >/dev/null

# 5. Seed the companion URL straight into the app's DataStore, so no onboarding
#    typing is needed. The file is androidx.datastore's raw PreferenceMap proto:
#    {companion_url: <string>}. We build the wire bytes and push via run-as.
log "Seeding companion URL ($APP_URL) into DataStore…"
python3 - "$APP_URL" > "$TMP/herdr-settings.pb" <<'PY'
import sys
url = sys.argv[1].encode()
def ld(tag, payload): return bytes([tag, len(payload)]) + payload   # single-byte len (<128) is enough here
key   = ld(0x0a, b"companion_url")                 # entry.field1 = key
value = ld(0x12, ld(0x2a, url))                     # entry.field2 = Value{field5=string}
entry = ld(0x0a, key + value)                       # PreferenceMap.field1 = map entry
sys.stdout.buffer.write(entry)
PY
adb shell run-as "$PKG" mkdir -p files/datastore
adb push "$TMP/herdr-settings.pb" /data/local/tmp/herdr-settings.pb >/dev/null
adb shell run-as "$PKG" cp /data/local/tmp/herdr-settings.pb files/datastore/settings.preferences_pb
adb shell rm /data/local/tmp/herdr-settings.pb

# 6. Grant notifications and launch.
adb shell pm grant "$PKG" android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell am force-stop "$PKG"
adb shell am start -n "$PKG/.MainActivity" >/dev/null

log "Done. App is live on the emulator, connected to the companion."
log "  companion log: $TMP/herdr-companion.log"
log "  logcat:        adb logcat --pid=\$(adb shell pidof $PKG)"
