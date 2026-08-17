#!/usr/bin/env bash
#
# Prove a relay works FROM OUTSIDE, over the public internet.
#
#   ops/verify-relay.sh 95.179.179.181:9501
#
# A relay that is "running" tells you nothing: the process can be up, the port
# bound, and a cloud firewall still dropping every packet - which is exactly
# how the first deployment of this project failed. So this attaches two real
# clients from this machine, through that relay, and makes them talk.
#
# It runs the full live chat suite, which exercises: greeting, attach, address
# assignment, the contact handshake, relayed delivery in both directions, the
# delivery receipt coming BACK through the relay, and group fan-out.
#
# Exit 0 only if every check passes.

set -euo pipefail

HOSTPORT="${1:-}"
if [ -z "$HOSTPORT" ]; then
    echo "usage: ops/verify-relay.sh <host:port>" >&2
    exit 1
fi

REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
CLASSES="$REPO/build/classes"

if [ ! -d "$CLASSES" ]; then
    echo "no build/classes - compile :core and its tests first" >&2
    exit 1
fi

HOST="${HOSTPORT%%:*}"
PORT="${HOSTPORT##*:}"

echo "=== reachability ==="
# Distinguish the three outcomes that look alike from a script: refused (port
# closed but the host answered), timeout (a firewall is dropping), open.
if nc -z -G 8 "$HOST" "$PORT" 2>/dev/null || nc -z -w 8 "$HOST" "$PORT" 2>/dev/null; then
    echo "  TCP $HOSTPORT is open"
else
    echo "  TCP $HOSTPORT did NOT accept a connection."
    echo "  refused => the process is down; timeout => a firewall is dropping."
    echo "  Check the CLOUD firewall (Hetzner/Vultr console) as well as ufw."
    exit 1
fi

echo
echo "=== two live clients through it ==="
java -cp "$CLASSES" com.eurobuddha.maxima.core.LiveChatTest "$HOSTPORT" "$HOSTPORT"
