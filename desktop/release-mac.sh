#!/usr/bin/env bash
# release-mac.sh — the signed, notarized, stapled, verified Parlons/Maxima Desktop DMG, built locally.
#   desktop/release-mac.sh ["Developer ID Application: Name (TEAMID)"] [notary keychain profile]
# Defaults: the one Developer ID Application identity in the login keychain; profile "minimadesk"
# (created once with `xcrun notarytool store-credentials minimadesk --apple-id … --team-id … --password …`).
# jpackage needs a JDK with jmods: JPACKAGE_JDK, else the Homebrew OpenJDK, else /usr/libexec/java_home.
set -euo pipefail
cd "$(dirname "$0")/.."
IDENTITY="${1:-$(security find-identity -v -p codesigning | grep -o '"Developer ID Application: [^"]*"' | head -1 | tr -d '"')}"
PROFILE="${2:-minimadesk}"
[ -n "$IDENTITY" ] || { echo "no 'Developer ID Application' identity in the keychain"; exit 1; }
JDK="${JPACKAGE_JDK:-}"
[ -n "$JDK" ] || JDK=$(ls -d /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home 2>/dev/null | sort -V | tail -1)
[ -n "$JDK" ] || JDK=$(/usr/libexec/java_home 2>/dev/null)
[ -x "$JDK/bin/jpackage" ] || { echo "no jpackage in $JDK — set JPACKAGE_JDK"; exit 1; }
echo "identity: $IDENTITY"; echo "jdk: $JDK"
./gradlew :desktop:jpackage -PmacSignIdentity="$IDENTITY" -PjpackageJdk="$JDK" -PskipAndroid=true --no-daemon
DMG=$(ls desktop/build/jpackage/*.dmg | sort -V | tail -1)
echo "notarizing $DMG (profile $PROFILE)…"
xcrun notarytool submit "$DMG" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$DMG"
desktop/verify-mac.sh "$DMG"
