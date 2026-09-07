#!/usr/bin/env bash
# Build node/libs/minima-node.jar (the embedded full Minima node, classes only) from the fork at
# core/minima-core with plain javac. The fork's own Gradle predates the JDK on this Mac and the previous
# jar was hand-built in an IDE; this is the reproducible path. Java 11 bytecode, like the rest of :node.
# Usage: node/build-minima-node.sh            (from the maxima repo root or anywhere)
set -euo pipefail
HERE="$(cd "$(dirname "$0")" && pwd)"
FORK="${FORK:-$HERE/../../core/minima-core}"
OUT="$HERE/libs/minima-node.jar"
[ -d "$FORK/src/org/minima" ] || { echo "fork sources not found at $FORK"; exit 1; }
TMP="$(mktemp -d)"; trap 'rm -rf "$TMP"' EXIT
CP="$(ls "$FORK"/lib/*.jar | grep -v junit | tr '\n' ':')"
find "$FORK/src" -name '*.java' > "$TMP/sources.txt"
echo "compiling $(wc -l < "$TMP/sources.txt" | tr -d ' ') sources from $FORK"
javac --release 11 -nowarn -encoding UTF-8 -cp "$CP" -d "$TMP/classes" @"$TMP/sources.txt" 2>&1 | grep -v "^Note:" || true
[ -f "$TMP/classes/org/minima/Minima.class" ] || { echo "compile failed"; exit 1; }
# every non-source file under src/ (tutorial.txt, kissvm.txt, …) keeps its package path
(cd "$FORK/src" && find . -type f ! -name '*.java' ! -name '.DS_Store' | while read -r r; do
  mkdir -p "$TMP/classes/$(dirname "$r")"; cp "$r" "$TMP/classes/$r"; done)
# the MDS assets (hublogin/ mdsjs/ minihub/ publicmds/ default/) live at the jar ROOT - MDSManager
# loads them by classloader path ("minihub/minihub-<ver>.mds.zip", "mdsjs/mds.js")
if [ -d "$FORK/resources" ]; then
  (cd "$FORK/resources" && find . -type f ! -name '.DS_Store' | while read -r r; do
    mkdir -p "$TMP/classes/$(dirname "$r")"; cp "$r" "$TMP/classes/$r"; done)
fi
printf 'Manifest-Version: 1.0\nMain-Class: org.minima.Minima\nBuilt-From: %s\n' "$(git -C "$FORK" rev-parse --short HEAD 2>/dev/null || echo unknown)" > "$TMP/MANIFEST.MF"
mkdir -p "$HERE/libs"
jar --create --file "$OUT" --manifest "$TMP/MANIFEST.MF" -C "$TMP/classes" .
echo "wrote $OUT ($(du -h "$OUT" | cut -f1), $(unzip -l "$OUT" | grep -c '\.class$') classes, $(unzip -l "$OUT" | grep -c 'mds\.zip$') mds zips, fork $(git -C "$FORK" rev-parse --short HEAD 2>/dev/null))"
