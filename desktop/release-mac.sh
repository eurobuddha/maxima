#!/usr/bin/env bash
# release-mac.sh — the signed, notarized, stapled, verified Parlons/Maxima Desktop DMG, built locally.
#   desktop/release-mac.sh ["Developer ID Application: Name (TEAMID)"] [notary keychain profile]
# Defaults: the one Developer ID Application identity in the login keychain; profile "minimadesk"
# (created once with `xcrun notarytool store-credentials minimadesk --apple-id … --team-id … --password …`).
# jpackage needs a JDK with jmods: JPACKAGE_JDK, else the Homebrew OpenJDK, else /usr/libexec/java_home.
#
# TWO STAGES, on purpose. jpackage's one-shot DMG seals the app inside before it can be stapled, so a user
# who is offline gets no ticket for the app. Instead: (1) build + sign the app image, notarize it and
# staple the ticket TO THE APP; (2) package that stapled app into the DMG with jpackage --app-image,
# notarize the DMG and staple it too. Both artefacts then verify with no network.
set -euo pipefail
cd "$(dirname "$0")/.."
IDENTITY="${1:-$(security find-identity -v -p codesigning | grep -o '"Developer ID Application: [^"]*"' | head -1 | tr -d '"')}"
PROFILE="${2:-minimadesk}"
[ -n "$IDENTITY" ] || { echo "no 'Developer ID Application' identity in the keychain"; exit 1; }
USER_NAME="${IDENTITY#Developer ID Application: }"      # jpackage wants the part after the prefix
JDK="${JPACKAGE_JDK:-}"
[ -n "$JDK" ] || JDK=$(ls -d /opt/homebrew/Cellar/openjdk/*/libexec/openjdk.jdk/Contents/Home 2>/dev/null | sort -V | tail -1)
[ -n "$JDK" ] || JDK=$(/usr/libexec/java_home 2>/dev/null)
[ -x "$JDK/bin/jpackage" ] || { echo "no jpackage in $JDK — set JPACKAGE_JDK"; exit 1; }
VERSION=$(grep -E "^version = '" desktop/build.gradle | sed -E "s/version = '([^']+)'/\1/")
OUT=desktop/build/jpackage
echo "identity: $IDENTITY"; echo "jdk: $JDK"; echo "version: $VERSION"

# ---- stage 1: signed app image → notarize → staple the .app ----
./gradlew :desktop:jpackageImage -PmacSignIdentity="$IDENTITY" -PjpackageJdk="$JDK" -PskipAndroid=true --no-daemon
APP="$OUT/MaximaNode.app"
[ -d "$APP" ] || { echo "no app image at $APP"; exit 1; }
ZIP="$OUT/MaximaNode-$VERSION-app.zip"
rm -f "$ZIP"; ditto -c -k --keepParent "$APP" "$ZIP"
echo "notarizing the app (profile $PROFILE)…"
xcrun notarytool submit "$ZIP" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$APP"
rm -f "$ZIP"

# ---- stage 2: DMG from the stapled app → notarize → staple the DMG ----
DMG="$OUT/MaximaNode-$VERSION.dmg"
rm -f "$DMG"
"$JDK/bin/jpackage" --type dmg --app-image "$APP" --name MaximaNode --app-version "$VERSION" \
  --dest "$OUT" --vendor eurobuddha --mac-package-identifier com.eurobuddha.maxima.node \
  --mac-sign --mac-signing-key-user-name "$USER_NAME"
[ -f "$DMG" ] || { echo "jpackage produced no $DMG"; ls "$OUT"; exit 1; }
echo "notarizing $DMG (profile $PROFILE)…"
xcrun notarytool submit "$DMG" --keychain-profile "$PROFILE" --wait
xcrun stapler staple "$DMG"
desktop/verify-mac.sh "$DMG"
