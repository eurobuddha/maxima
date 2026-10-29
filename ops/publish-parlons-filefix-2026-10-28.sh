#!/usr/bin/env bash
# Publish the Parlons file-transfer repair (the bears' 2.1.0 fix) - built and verified from maxima a77fa4b0.
# Run by the owner: these steps create public releases and store rows.
set -euo pipefail
S="${S:-$HOME/.claude/jobs/3618ee4d/tmp/parlons-release}"
W=/Users/eurobuddha/Projects/minima/_worktrees/maxima-parlons-filefix
R=eurobuddha/maxima
N="File-transfer repair (same as the bears 2.1.0): failed-block reasons from the sidecar, re-send backoff while the peer is silent."

echo "== verify staged files"
for d in node cloud portal android; do (cd "$S/$d" && shasum -a 256 -c SHA256SUMS); done

echo "== GitHub releases"
gh release create node-v0.2.144 "$S"/node/* -R $R --target main --latest=false --title "Parlons Node 0.2.144" --notes "$N Fleet jar carries keets sidecar 1.3.4."
gh release create cloud-v0.11.128 "$S"/cloud/* -R $R --target main --latest --title "Parlons Cloud host 0.11.128" --notes "$N"
gh release create portal-v0.2.95 "$S"/portal/* -R $R --target main --latest=false --title "Parlons Cloud Android 0.2.95" --notes "$N"
gh release create v0.6.150 "$S"/android/* -R $R --target main --latest=false --title "Parlons! 0.6.150" --notes "$N"

echo "== Parlons Desktop 1.5.119: tag -> CI builds Windows + Linux, then the local signed DMG goes over CI's"
DMG="$W/desktop/build/jpackage/MaximaNode-1.5.119.dmg"
[ -f "$DMG" ] || { echo "no signed $DMG - run desktop/release-mac.sh in $W first"; exit 1; }
git -C "$W" tag -f desktop-v1.5.119 a77fa4b0 && git -C "$W" push -f origin desktop-v1.5.119
RUN=""; for i in $(seq 1 30); do RUN="$(gh run list -R $R --workflow desktop-node.yml --branch desktop-v1.5.119 --limit 1 --json databaseId --jq '.[0].databaseId' 2>/dev/null || true)"; [ -n "$RUN" ] && break; sleep 10; done
[ -n "$RUN" ] && gh run watch "$RUN" -R $R --exit-status > /dev/null
cp "$DMG" "$S/MaximaNode-1.5.119-notarized.dmg"
(cd "$S" && shasum -a 256 MaximaNode-1.5.119-notarized.dmg > SHA256SUMS-mac)
gh release upload desktop-v1.5.119 "$S/MaximaNode-1.5.119-notarized.dmg" "$S/SHA256SUMS-mac" -R $R --clobber
gh release view desktop-v1.5.119 -R $R --json assets --jq '.assets[].name'

echo "== store rows (PandaApps catalog)"
cd /Users/eurobuddha/Projects/minima/desktop/minima-core-apks
python3 scripts/publish-app.py com.eurobuddha.maxima.app 0.6.150 --name "Parlons!"
python3 scripts/publish-app.py com.eurobuddha.parlons.cloud 0.2.95 --name "Parlons Cloud Portal"
./check.py
git add apks.json && git commit -q -m "Publish the Parlons file-transfer repair: Parlons! 0.6.150, Cloud Portal 0.2.95" && git push -q
echo "ALL PUBLISHED"
