#!/usr/bin/env bash
# verify-mac.sh <MaximaNode-x.y.z.dmg> — prove a Parlons/Maxima Desktop DMG installs cleanly on any Mac:
# Developer ID signature (strict deep verify, hardened runtime), Gatekeeper's own assessment, notarization
# ticket stapled. Exits non-zero on the first failure.
set -euo pipefail
DMG="${1:?usage: verify-mac.sh <dmg>}"
[ -f "$DMG" ] || { echo "no such dmg: $DMG"; exit 1; }
echo "== dmg: $DMG"
# The DMG container itself need not carry a signature — Gatekeeper judges the notarization ticket stapled to the
# DMG and the signed, notarized app inside it (jpackage signs the container only in some configurations).
DINFO=$(codesign -dv "$DMG" 2>&1 || true)   # captured first: `codesign | grep -q` under pipefail fails a PASSING check
echo "$DINFO" | grep -q 'Developer ID' && echo "ok: dmg container signed with Developer ID" || echo "note: dmg container unsigned (fine — ticket + signed app inside decide)"
xcrun stapler validate "$DMG" > /dev/null && echo "ok: notarization ticket stapled to the dmg"
DASSESS=$(spctl --assess --type open --context context:primary-signature --verbose=2 "$DMG" 2>&1 || true)
echo "$DASSESS" | grep -q 'accepted' || { echo "FAIL: Gatekeeper rejects the dmg: $DASSESS"; exit 1; }
echo "ok: spctl accepts the dmg"
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
codesign --verify --deep --strict --verbose=1 "$APP" && echo "ok: codesign strict deep verify"
xcrun stapler validate "$APP" > /dev/null && echo "ok: notarization ticket stapled to the app" \
  || { echo "FAIL: no ticket stapled to the app inside the dmg — build with desktop/release-mac.sh (two-stage)"; exit 1; }
ASSESS=$(spctl --assess --type execute --verbose=2 "$APP" 2>&1 || true)
echo "$ASSESS" | grep -q 'Notarized Developer ID' || { echo "FAIL: Gatekeeper does not see a notarized Developer ID app: $ASSESS"; exit 1; }
echo "ok: spctl accepts the app (Notarized Developer ID)"
echo "ALL OK — $DMG installs cleanly on any Mac"
