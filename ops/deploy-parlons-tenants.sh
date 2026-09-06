#!/usr/bin/env bash
#
# Deploy (or update) a Parlons multi-account HOST (parlons-cloud --tenants) on a box you run.
#
# The host keeps one always-on account per folder under /var/lib/parlons-tenants, all in one
# process, for people who will not run their own server (an iPhone user, a friend, the App
# Store reviewer). Every tenant has its own identity, devices, chat and backup, and can leave
# at any time as an encrypted bundle. Seeds are encrypted at rest under a passphrase kept in
# /etc/parlons-tenants.env (root-only). No inbound port: the host attaches to the relay fleet.
#
# One command, repeatable ("update" = re-run). Add a user afterwards with
#   ops/tenant-new.sh <ssh-target> <name>      -> prints the invite the phone scans
#
#   ops/deploy-parlons-tenants.sh <ssh-target> [--jar FILE] [--heap 512m] [--memmax 768M]
#                                 [--dir /var/lib/parlons-tenants] [--peers h:p,h:p]
#
# What it does, in order:
#   1. installs a headless JRE if java is missing
#   2. creates an unprivileged `maxima` user and the tenants dir (mode 700)
#   3. uploads the jar to /opt/maxima, sha256-verifies it, points parlons-cloud.jar at it
#   4. writes the unlock passphrase file (once) and a hardened systemd unit (parlons-tenants)
#   5. restarts, waits, proves the host is up and polling for tenants
#
set -euo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then
    sed -n '2,22p' "$0" | sed 's/^#\ \?//'
    exit 1
fi
shift

HEAP=512m        # ~10 tenants; each account is a chat engine + watch-only wallet
MEMMAX=768M      # cgroup ceiling (JVM heap + metaspace + threads + direct buffers)
DIR=/var/lib/parlons-tenants
PEERS=""         # comma-separated fleet host:ports (optional seeds; built-ins are used anyway)
JAR=""
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

while [ $# -gt 0 ]; do
    case "$1" in
        --jar)    JAR="$2"; shift 2 ;;
        --heap)   HEAP="$2"; shift 2 ;;
        --memmax) MEMMAX="$2"; shift 2 ;;
        --dir)    DIR="$2"; shift 2 ;;
        --peers)  PEERS="$2"; shift 2 ;;
        *) echo "unknown option: $1" >&2; exit 1 ;;
    esac
done

# Default to the newest built jar so this follows the repo without an edit per release.
if [ -z "$JAR" ]; then
    JAR="$(ls -1 "$REPO"/dist/parlons-cloud-*.jar 2>/dev/null | sort -V | tail -1)"
    [ -z "$JAR" ] && JAR="$(ls -1 "$REPO"/cloud/build/libs/parlons-cloud.jar 2>/dev/null | tail -1)"
fi
[ -f "$JAR" ] || { echo "no jar found - run ./gradlew :cloud:fatJar first, or pass --jar" >&2; exit 1; }

VER="$(basename "$JAR" | sed 's/parlons-cloud-//; s/\.jar//')"
[ "$VER" = "parlons-cloud" ] && VER="dev"   # cloud/build/libs/parlons-cloud.jar has no version in the name
SUM="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"
REMOTE_JAR="parlons-cloud-$VER.jar"

echo "=============================================================="
echo " deploying Parlons multi-account host $VER  ->  $TARGET  ($DIR)"
echo " sha256 $SUM"
echo "=============================================================="

SSH="ssh -o ConnectTimeout=20 -o BatchMode=yes $TARGET"

# ---- 1. java -------------------------------------------------------------
echo "[1/5] java"
$SSH 'bash -s' <<'REMOTE'
set -e
if command -v java >/dev/null 2>&1; then
    echo "      present: $(java -version 2>&1 | head -1)"
else
    echo "      installing a headless JRE (this box has none)"
    export DEBIAN_FRONTEND=noninteractive
    apt-get update -qq
    apt-get install -y -qq default-jre-headless >/dev/null
    echo "      installed: $(java -version 2>&1 | head -1)"
fi
REMOTE

# ---- 2. user and dirs ----------------------------------------------------
echo "[2/5] user and directories"
$SSH "bash -s" <<REMOTE
set -e
id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
mkdir -p /opt/maxima $DIR
chown maxima:maxima $DIR
chmod 700 $DIR
REMOTE

# ---- 3. the jar ----------------------------------------------------------
echo "[3/5] uploading jar"
scp -q -o ConnectTimeout=20 "$JAR" "$TARGET:/opt/maxima/$REMOTE_JAR"
$SSH "bash -s" <<REMOTE
set -e
cd /opt/maxima
got=\$(sha256sum $REMOTE_JAR | cut -d' ' -f1)
if [ "\$got" != "$SUM" ]; then
    echo "SHA256 MISMATCH: expected $SUM got \$got - refusing to activate" >&2
    rm -f $REMOTE_JAR
    exit 1
fi
# Only now flip the symlink. The unit points at it, so a rollback is one ln -sf.
ln -sf $REMOTE_JAR parlons-cloud.jar
chmod 644 $REMOTE_JAR
REMOTE

# ---- 4. unlock passphrase + systemd --------------------------------------
echo "[4/5] unlock passphrase + systemd unit"
$SSH "bash -s" <<REMOTE
set -e
if [ ! -s /etc/parlons-tenants.env ]; then
    umask 077
    echo "PARLONS_UNLOCK=\$(head -c 32 /dev/urandom | base64 | tr -d '/+=' | head -c 40)" > /etc/parlons-tenants.env
    chmod 600 /etc/parlons-tenants.env
    echo "      wrote /etc/parlons-tenants.env (seeds are encrypted at rest under it - back it up)"
else
    echo "      /etc/parlons-tenants.env present"
fi
REMOTE
$SSH "cat > /etc/systemd/system/parlons-tenants.service" <<REMOTE
[Unit]
Description=Parlons multi-account host - one always-on account per tenant folder ($VER)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima
EnvironmentFile=/etc/parlons-tenants.env
# No pool relay and no direct listener: the host attaches to the fleet outbound only,
# so it opens no inbound port and can sit next to a Parlons Node on the same box.
ExecStart=/usr/bin/java -Xmx$HEAP -jar /opt/maxima/parlons-cloud.jar \\
    --tenants $DIR --no-relay --no-direct --unlock env${PEERS:+ --peers $PEERS}
Restart=on-failure
RestartSec=10

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$DIR
UMask=0077
PrivateDevices=true
ProtectProc=invisible
ProcSubset=pid
ProtectKernelTunables=true
ProtectKernelModules=true
ProtectKernelLogs=true
ProtectControlGroups=true
ProtectClock=true
ProtectHostname=true
RemoveIPC=true
CapabilityBoundingSet=
AmbientCapabilities=
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true
SystemCallArchitectures=native
SystemCallFilter=@system-service
SystemCallFilter=~@privileged ~@resources ~@obsolete
SystemCallErrorNumber=EPERM
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=169.254.0.0/16 fe80::/10
MemoryMax=$MEMMAX
TasksMax=512
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
REMOTE

# ---- 5. start and prove --------------------------------------------------
echo "[5/5] starting"
$SSH "bash -s" <<REMOTE
set -e
systemctl daemon-reload
systemctl enable parlons-tenants >/dev/null 2>&1 || true
systemctl restart parlons-tenants
sleep 8
systemctl is-active parlons-tenants | sed 's/^/      state: /'
if journalctl -u parlons-tenants --since "-2 min" --no-pager 2>/dev/null | grep -q "multi-account host"; then
    journalctl -u parlons-tenants --since "-2 min" --no-pager | grep "multi-account host\|tenant " | sed 's/^.*parlons-cloud[^:]*: /      /' | tail -12
else
    echo "      host did not report in - last log lines:"
    journalctl -u parlons-tenants -n 25 --no-pager 2>/dev/null || true
    exit 1
fi
REMOTE

echo
echo "  Add a user:   ops/tenant-new.sh $TARGET <name>      (prints the invite the phone scans)"
echo "  Stop one:     touch $DIR/<name>/.stop   (remove the marker to start it again)"
echo "  Back up:      /etc/parlons-tenants.env (unlock passphrase) + each tenant's bundle"
echo "  Update later: re-run this command. Roll back: ln -sf parlons-cloud-<OLDVER>.jar"
echo "                /opt/maxima/parlons-cloud.jar && systemctl restart parlons-tenants"
