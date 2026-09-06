#!/usr/bin/env bash
# Prove the wake proxy answers FROM OUTSIDE: health, then a malformed wake must be refused (400),
# and a well-formed one queued (202). Uses a throwaway token, so APNs simply rejects it upstream.
#   ops/verify-wake-proxy.sh https://wake.example.com
set -euo pipefail
BASE="${1:-}"
[ -n "$BASE" ] || { echo "usage: ops/verify-wake-proxy.sh https://host" >&2; exit 1; }
echo -n "healthz: "; curl -fsS "$BASE/healthz"; echo
BAD=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/wake" -H 'Content-Type: application/json' -d '{"token":"nope"}')
[ "$BAD" = "400" ] || { echo "expected 400 for a malformed token, got $BAD" >&2; exit 1; }
OK=$(curl -s -o /dev/null -w '%{http_code}' -X POST "$BASE/v1/wake" -H 'Content-Type: application/json' \
     -d '{"token":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef","env":"sandbox","kind":"message"}')
[ "$OK" = "202" ] || { echo "expected 202 for a well-formed wake, got $OK" >&2; exit 1; }
echo "ALL OK: refuses junk (400), queues a wake (202)"
