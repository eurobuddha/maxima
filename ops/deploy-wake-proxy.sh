#!/usr/bin/env bash
#
# Deploy (or update) the Parlons WAKE PROXY on a remote box, idempotently.
#
# The proxy is the one publisher-run piece of the iOS app: a stateless HTTP service that holds
# the publisher's APNs key and turns a Parlons account's "wake this device" into a content-free
# Apple push. It stores nothing, sees no message content, and a user can switch it off in the
# app (background polling then carries the messages).
#
#   ops/deploy-wake-proxy.sh <ssh-target> --key AuthKey_XXXX.p8 --key-id XXXX --team-id Z4JD286WF4 \
#                            --bundle com.eurobuddha.parlons [--port 8090] [--jar FILE]
#
# What it does, in order:
#   1. installs a headless JRE if java is missing
#   2. creates an unprivileged `parlons-wake` user and /etc/parlons-wake (the .p8, 0600)
#   3. uploads the jar to /opt/parlons-wake and points a stable symlink at it
#   4. writes a hardened systemd unit bound to 127.0.0.1 (put Caddy in front for TLS:
#        wake.example.com { reverse_proxy 127.0.0.1:8090 }  )
#   5. restarts, waits, and proves /healthz answers
#
# Never opens a public port itself: TLS termination stays with Caddy.
set -euo pipefail
TARGET="${1:-}"
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then
    sed -n '2,22p' "$0" | sed 's/^#\ \?//'
    exit 1
fi
shift
PORT=8090
KEY=""; KEYID=""; TEAM=""; BUNDLE=""; JAR=""
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --key) KEY="$2"; shift 2 ;;
        --key-id) KEYID="$2"; shift 2 ;;
        --team-id) TEAM="$2"; shift 2 ;;
        --bundle) BUNDLE="$2"; shift 2 ;;
        --jar) JAR="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done
[ -n "$KEY" ] && [ -n "$KEYID" ] && [ -n "$TEAM" ] && [ -n "$BUNDLE" ] || { echo "--key, --key-id, --team-id and --bundle are required" >&2; exit 1; }
[ -f "$KEY" ] || { echo "no such key file: $KEY" >&2; exit 1; }
if [ -z "$JAR" ]; then
    JAR="$(ls -t "$REPO"/dist/parlons-wake-*.jar 2>/dev/null | head -1)"
fi
[ -n "$JAR" ] && [ -f "$JAR" ] || { echo "no jar: build with ./gradlew :wakeproxy:fatJar and copy to dist/" >&2; exit 1; }
VER="$(basename "$JAR" .jar)"
SSH="ssh -o BatchMode=yes $TARGET"
SCP="scp -q -o BatchMode=yes"

echo "[1/5] java"
$SSH 'command -v java >/dev/null 2>&1 || (apt-get update -qq && DEBIAN_FRONTEND=noninteractive apt-get install -y -qq default-jre-headless >/dev/null)'
echo "[2/5] user + key"
$SSH 'id -u parlons-wake >/dev/null 2>&1 || useradd --system --home /var/lib/parlons-wake --shell /usr/sbin/nologin parlons-wake; mkdir -p /etc/parlons-wake /opt/parlons-wake'
$SCP "$KEY" "$TARGET:/etc/parlons-wake/apns.p8"
$SSH 'chown -R parlons-wake:parlons-wake /etc/parlons-wake; chmod 700 /etc/parlons-wake; chmod 600 /etc/parlons-wake/apns.p8'
echo "[3/5] jar $VER"
$SCP "$JAR" "$TARGET:/opt/parlons-wake/$VER.jar"
$SSH "chown parlons-wake:parlons-wake /opt/parlons-wake/$VER.jar; ln -sf /opt/parlons-wake/$VER.jar /opt/parlons-wake/parlons-wake.jar"
echo "[4/5] systemd unit"
$SSH "cat > /etc/systemd/system/parlons-wake.service" <<REMOTE
[Unit]
Description=Parlons wake proxy (stateless APNs relay for sleeping iOS devices)
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=parlons-wake
Group=parlons-wake
WorkingDirectory=/var/lib/parlons-wake
ExecStart=/usr/bin/java -Xmx96m -jar /opt/parlons-wake/parlons-wake.jar \\
    --key /etc/parlons-wake/apns.p8 --key-id $KEYID --team-id $TEAM --bundle $BUNDLE --port $PORT --bind 127.0.0.1
Restart=always
RestartSec=3
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadOnlyPaths=/etc/parlons-wake
StateDirectory=parlons-wake
TasksMax=256
LimitNOFILE=8192

[Install]
WantedBy=multi-user.target
REMOTE
$SSH 'systemctl daemon-reload; systemctl enable --now parlons-wake >/dev/null 2>&1 || true; systemctl restart parlons-wake'
echo "[5/5] verify"
sleep 3
$SSH "curl -fsS http://127.0.0.1:$PORT/healthz && echo && systemctl is-active parlons-wake"
echo "deployed. Front it with Caddy:  wake.<your-domain> { reverse_proxy 127.0.0.1:$PORT }"
echo "then from anywhere:  ops/verify-wake-proxy.sh https://wake.<your-domain>"
