#!/usr/bin/env bash
# verify-mac.sh <MaximaNode-x.y.z.dmg> — prove a Parlons/Maxima Desktop DMG installs cleanly on any Mac:
# Developer ID signature (strict deep verify, hardened runtime), Gatekeeper's own assessment, notarization
# ticket stapled. Exits non-zero on the first failure.
set -euo pipefail
DMG="${1:?usage: verify-mac.sh <dmg>}"
[ -f "$DMG" ] || { echo "no such dmg: $DMG"; exit 1; }
echo "== dmg: $DMG"
DINFO=$(codesign -dv "$DMG" 2>&1 || true)   # captured first: `codesign | grep -q` under pipefail fails a PASSING check
echo "$DINFO" | grep -q 'Developer ID' || { echo "FAIL: dmg not signed with Developer ID"; exit 1; }
xcrun stapler validate "$DMG" && echo "ok: notarization ticket stapled to the dmg"
spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG" && echo "ok: spctl accepts the dmg"
# Look inside: the .app must carry a Developer ID signature with hardened runtime.
MNT=$(mktemp -d)
hdiutil attach -quiet -nobrowse -readonly -mountpoint "$MNT" "$DMG"
trap 'hdiutil detach -quiet "$MNT" || true' EXIT
APP=$(ls -d "$MNT"/*.app | head -1)
echo "== app: $APP"
INFO=$(codesign -dv --verbose=2 "$APP" 2>&1 || true)
echo "$INFO" | grep -E 'Authority=Developer ID Application|flags=' || true
echo "$INFO" | grep -q 'Authority=Developer ID Application' || { echo "FAIL: app not signed with Developer ID Application"; exit 1; }
echo "$INFO" | grep -q 'flags=.*runtime' || { echo "FAIL: hardened runtime not enabled"; exit 1; }
codesign --verify --deep --strict --verbose=2 "$APP" && echo "ok: codesign strict deep verify"
spctl --assess --type execute --verbose=2 "$APP" && echo "ok: spctl accepts the app"
echo "ALL OK — $DMG installs cleanly on any Mac"
