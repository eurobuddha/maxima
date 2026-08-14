#!/usr/bin/env bash
#
# Deploy (or update) a Maxima relay on a remote box, idempotently.
#
# One command, repeatable. Running it twice is a no-op plus a jar refresh, so
# "update the fleet" is the same operation as "install the fleet" - which is the
# only way a fleet stays consistent.
#
#   ops/deploy-relay.sh <ssh-target> [--port N] [--jar FILE] [--heap 128m]
#   ops/deploy-relay.sh hetzner --port 9501
#
# What it does, in order:
#   1. installs a headless JRE if java is missing
#   2. creates an unprivileged `maxima` user and /var/lib/maxima
#   3. uploads the jar to /opt/maxima and points a stable symlink at it
#   4. writes a hardened systemd unit
#   5. opens the port in ufw / firewalld if either is active
#   6. restarts, waits, and proves it is listening and has an identity
#
# It never touches anything else on the box. The relay is chainless: no wallet,
# no node, no ports beyond the one.

set -euo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then
    sed -n '2,26p' "$0" | sed 's/^#\ \?//'
    exit 1
fi
shift

PORT=9501
HEAP=128m
JAR=""
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

while [ $# -gt 0 ]; do
    case "$1" in
        --port) PORT="$2"; shift 2 ;;
        --jar)  JAR="$2"; shift 2 ;;
        --heap) HEAP="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

# Default to the newest built jar rather than a pinned version, so the fleet
# follows the repo without editing this script every release.
if [ -z "$JAR" ]; then
    JAR="$(ls -1 "$REPO"/dist/maxima-server-*.jar 2>/dev/null | sort -V | tail -1)"
fi
[ -f "$JAR" ] || { echo "no jar found - run ./gradlew :server:fatJar first" >&2; exit 1; }

VER="$(basename "$JAR" | sed 's/maxima-server-//; s/\.jar//')"
SUM="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"

echo "=============================================================="
echo " deploying maxima-server $VER  ->  $TARGET  port $PORT"
echo " sha256 $SUM"
echo "=============================================================="

SSH="ssh -o ConnectTimeout=20 -o BatchMode=yes $TARGET"

# ---- 1. java -------------------------------------------------------------
echo "[1/6] java"
$SSH 'bash -s' <<'REMOTE'
set -e
if command -v java >/dev/null 2>&1; then
    echo "      present: $(java -version 2>&1 | head -1)"
else
    echo "      installing a headless JRE (this box has none)"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    # default-jre-headless tracks whatever the distro considers current, which
    # avoids pinning a version that is absent on one release and not another.
    apt-get install -y -qq default-jre-headless >/dev/null
    echo "      installed: $(java -version 2>&1 | head -1)"
fi
REMOTE

# ---- 2. user and dirs ----------------------------------------------------
echo "[2/6] user and directories"
$SSH 'bash -s' <<'REMOTE'
set -e
id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
mkdir -p /opt/maxima /var/lib/maxima
chown -R maxima:maxima /var/lib/maxima
chmod 750 /var/lib/maxima
REMOTE

# ---- 3. the jar ----------------------------------------------------------
echo "[3/6] uploading jar"
scp -q -o ConnectTimeout=20 "$JAR" "$TARGET:/opt/maxima/maxima-server-$VER.jar"
$SSH "bash -s" <<REMOTE
set -e
cd /opt/maxima
# The unit points at the symlink, so a rollback is one ln -sf away.
ln -sf maxima-server-$VER.jar maxima-server.jar
chmod 644 maxima-server-$VER.jar
REMOTE

# ---- 4. systemd ----------------------------------------------------------
echo "[4/6] systemd unit"
$SSH "cat > /etc/systemd/system/maxima-relay.service" <<REMOTE
[Unit]
Description=Maxima relay, directory and mailbox ($VER)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target

[Service]
Type=simple
User=maxima
Group=maxima
# -Xmx is deliberate: the working set is a routing table and a mailbox of small
# records, and an unbounded default heap on a 1 GB VPS is how you lose the box.
ExecStart=/usr/bin/java -Xmx$HEAP -jar /opt/maxima/maxima-server.jar \\
    --port $PORT --data /var/lib/maxima
Restart=always
RestartSec=10

# The relay handles untrusted input from the open internet all day, so it gets
# no more of the filesystem than it needs.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/maxima
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectControlGroups=true
RestrictSUIDSGID=true
RestrictNamespaces=true
LockPersonality=true
MemoryMax=512M

StandardOutput=append:/var/log/maxima-relay.log
StandardError=append:/var/log/maxima-relay.log

[Install]
WantedBy=multi-user.target
REMOTE

$SSH "touch /var/log/maxima-relay.log && chown maxima:maxima /var/log/maxima-relay.log"

# ---- 5. firewall ---------------------------------------------------------
echo "[5/6] firewall"
$SSH "bash -s" <<REMOTE
set -e
opened=no
# grep -q active also matches "Status: inactive". Match the whole word.
if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | head -1 | grep -qw active; then
    ufw allow $PORT/tcp >/dev/null
    echo "      ufw: allowed $PORT/tcp"
    opened=yes
fi
if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
    firewall-cmd --permanent --add-port=$PORT/tcp >/dev/null
    firewall-cmd --reload >/dev/null
    echo "      firewalld: allowed $PORT/tcp"
    opened=yes
fi
if [ "\$opened" = no ]; then
    echo "      no host firewall active - nothing to open"
    echo "      NOTE: a CLOUD firewall (Hetzner/Vultr console) can still block it."
fi
REMOTE

# ---- 6. start and prove --------------------------------------------------
echo "[6/6] starting"
$SSH "bash -s" <<REMOTE
set -e
systemctl daemon-reload
systemctl enable --now maxima-relay >/dev/null 2>&1 || systemctl enable maxima-relay
systemctl restart maxima-relay
sleep 6
systemctl is-active maxima-relay | sed 's/^/      state: /'
if (ss -ltn 2>/dev/null || netstat -ltn) | grep -q ":$PORT "; then
    echo "      listening on $PORT"
else
    echo "      NOT LISTENING on $PORT - last log lines:"
    tail -20 /var/log/maxima-relay.log
    exit 1
fi
grep -m1 "identity" /var/log/maxima-relay.log | sed 's/^/      /' || true
REMOTE

echo
echo "deployed. verify from outside with:"
echo "  ops/verify-relay.sh <public-host>:$PORT"
