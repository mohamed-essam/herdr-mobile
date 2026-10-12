#!/usr/bin/env bash
# testflight-ios.sh — archive the iOS app in Release and upload it to App Store
# Connect for internal TestFlight testing (ios/ExportOptions.plist).
#
# Usage:
#   scripts/testflight-ios.sh --check   # unsigned archive + bundle checks, no upload
#   scripts/testflight-ios.sh           # signed archive + bundle checks + upload
#
# Upload env (an App Store Connect team API key, Admin role; keep the .p8 outside
# the repo): ASC_KEY_PATH, ASC_KEY_ID, ASC_ISSUER_ID. Output goes to build/testflight.
set -euo pipefail

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
OUT="$REPO/build/testflight"
ARCHIVE="$OUT/HerdrMobile.xcarchive"
APP="$ARCHIVE/Products/Applications/HerdrMobile.app"
ICON="$REPO/ios/HerdrMobile/Assets.xcassets/AppIcon.appiconset/AppIcon.png"

log() { printf '\033[36m==>\033[0m %s\n' "$*"; }

case "${1:-}" in
  --check) CHECK=1 ;;
  "") CHECK=0 ;;
  *) echo "usage: $0 [--check]" >&2; exit 2 ;;
esac

for tool in xcodebuild plutil sips; do
  command -v "$tool" >/dev/null || { echo "missing: $tool" >&2; exit 1; }
done

if ((CHECK)); then
  signing=(CODE_SIGNING_ALLOWED=NO)
else
  # Refuse before building anything: an upload without the key can't sign.
  missing=()
  for var in ASC_KEY_PATH ASC_KEY_ID ASC_ISSUER_ID; do
    [[ -n "${!var:-}" ]] || missing+=("$var")
  done
  if ((${#missing[@]})); then
    echo "missing env: ${missing[*]} (App Store Connect API key; see header)" >&2
    exit 1
  fi
  [[ -f "$ASC_KEY_PATH" ]] || { echo "ASC_KEY_PATH: no such file: $ASC_KEY_PATH" >&2; exit 1; }
  signing=(-allowProvisioningUpdates -authenticationKeyPath "$ASC_KEY_PATH"
    -authenticationKeyID "$ASC_KEY_ID" -authenticationKeyIssuerID "$ASC_ISSUER_ID")
fi

# DerivedData stays between runs, as in e2e-ios.sh; the archive and export start fresh.
rm -rf "$ARCHIVE" "$OUT/export"

log "Archiving Release…"
# Packages may ship build plugins; Xcode asks to trust each one once, xcodebuild needs this flag.
xcodebuild -quiet -skipPackagePluginValidation -project "$REPO/ios/HerdrMobile.xcodeproj" \
  -scheme HerdrMobile -configuration Release -destination generic/platform=iOS \
  -derivedDataPath "$OUT/DerivedData" -archivePath "$ARCHIVE" archive "${signing[@]}"

# Checked on the archive that ships, in both modes.
failed=0
fail() { printf '\033[31mFAIL\033[0m %s\n' "$*"; failed=1; }
ok() { printf '\033[32mok\033[0m   %s\n' "$*"; }
want() { # <Info.plist key path> <value> <where it's set>
  local got
  got="$(plutil -extract "$1" raw -o - "$APP/Info.plist" 2>/dev/null || true)"
  [[ $got == "$2" ]] && ok "$1 = $2" || fail "$1: expected $2, got '${got}' ($3)"
}

log "Checking ${APP#"$REPO/"}…"
want CFBundleIcons.CFBundlePrimaryIcon.CFBundleIconName AppIcon ASSETCATALOG_COMPILER_APPICON_NAME
want ITSAppUsesNonExemptEncryption false HerdrMobile-Info.plist

plutil -lint -s "$APP/PrivacyInfo.xcprivacy" && ok "PrivacyInfo.xcprivacy present and lints" \
  || fail "PrivacyInfo.xcprivacy: expected a valid plist in the app bundle (ios/HerdrMobile/PrivacyInfo.xcprivacy)"

icon_info="$(sips -g hasAlpha -g pixelWidth -g pixelHeight "$ICON" 2>&1)"
[[ $icon_info == *"hasAlpha: no"* && $icon_info == *"pixelWidth: 1024"* && $icon_info == *"pixelHeight: 1024"* ]] \
  && ok "AppIcon.png is 1024×1024 with no alpha" \
  || fail "AppIcon.png: expected 1024×1024, hasAlpha no; sips says: $(echo $icon_info)"

((failed)) && { log "Checks failed."; exit 1; }
log "All checks passed."
((CHECK)) && exit 0

log "Uploading to App Store Connect…"
xcodebuild -exportArchive -archivePath "$ARCHIVE" -exportOptionsPlist "$REPO/ios/ExportOptions.plist" \
  -exportPath "$OUT/export" "${signing[@]}"
log "Uploaded. The build shows in TestFlight once App Store Connect has processed it."
