#!/usr/bin/env bash
#
# Deploy (or update) Parlons Cloud on your own VPS, idempotently.
#
# Parlons Cloud is your always-on Parlons account: it holds your identity, chat
# and a watch-only wallet, and — being public — also contributes as a network
# relay. This installs it as a hardened systemd service and, on first run, hands
# you the two things your phone needs to pair: the account ADDRESS and a one-time
# PAIRING CODE.
#
# One command, repeatable. Running it twice is a no-op plus a jar refresh, so
# "update" is the same operation as "install".
#
#   ops/deploy-parlons-cloud.sh <ssh-target> [--port N] [--name "My account"]
#                               [--jar FILE] [--heap 256m] [--peers h:p,h:p]
#   ops/deploy-parlons-cloud.sh root@1.2.3.4 --port 9501 --name "Alice"
#
# What it does, in order:
#   1. installs a headless JRE if java is missing
#   2. creates an unprivileged `maxima` user and /var/lib/maxima (mode 700)
#   3. uploads the jar to /opt/maxima, sha256-verifies it, points a symlink at it
#   4. writes a hardened systemd unit (parlons-cloud.service)
#   5. opens the relay port in ufw / firewalld if either is active
#   6. restarts, waits, proves it is listening and has an identity
#   7. prints your PAIRING CREDENTIALS: account address + one-time code
#
# It never prints your seed. The seed at /var/lib/maxima/seed.txt is your
# identity AND a spendable wallet - read it once over ssh and back it up.

set -euo pipefail

TARGET="${1:-}"
if [ -z "$TARGET" ] || [ "$TARGET" = "-h" ] || [ "$TARGET" = "--help" ]; then
    sed -n '2,31p' "$0" | sed 's/^#\ \?//'
    exit 1
fi
shift

PORT=9501
HEAP=256m        # the cloud node runs a wallet + chat engine; heavier than a bare relay
MEMMAX=1024M     # cgroup ceiling (JVM heap + metaspace + threads + direct buffers)
BLOB=1024        # relay media shelf in MB (0 = off)
NAME=""          # optional display name; you can also set it in the app after pairing
PEERS=""         # comma-separated fleet host:ports for the MLS mesh (exclude self)
JAR=""
REPO="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"

while [ $# -gt 0 ]; do
    case "$1" in
        --port)   PORT="$2"; shift 2 ;;
        --name)   NAME="$2"; shift 2 ;;
        --jar)    JAR="$2"; shift 2 ;;
        --heap)   HEAP="$2"; shift 2 ;;
        --memmax) MEMMAX="$2"; shift 2 ;;
        --blob)   BLOB="$2"; shift 2 ;;
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
echo " deploying Parlons Cloud $VER  ->  $TARGET  relay port $PORT"
echo " sha256 $SUM"
echo "=============================================================="

SSH="ssh -o ConnectTimeout=20 -o BatchMode=yes $TARGET"

# ---- 1. java -------------------------------------------------------------
echo "[1/7] java"
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
echo "[2/7] user and directories"
$SSH 'bash -s' <<'REMOTE'
set -e
id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
mkdir -p /opt/maxima /var/lib/maxima
chown -R maxima:maxima /var/lib/maxima
chmod 700 /var/lib/maxima
REMOTE

# ---- 3. the jar ----------------------------------------------------------
echo "[3/7] uploading jar"
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

# ---- 4. systemd ----------------------------------------------------------
echo "[4/7] systemd unit"
# NAME is passed through only if set; systemd needs it quoted for spaces.
NAME_ARG=""
[ -n "$NAME" ] && NAME_ARG=" --name \"$NAME\""
$SSH "cat > /etc/systemd/system/parlons-cloud.service" <<REMOTE
[Unit]
Description=Parlons Cloud - always-on account + pool relay ($VER)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target
# Contain a repeatable-crash DoS: after 5 starts in 5 min, stop and stay failed
# so monitoring SEES it instead of an invisible flap.
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima
# -Xmx is deliberate: bound the heap so an unbounded default can't OOM a small VPS.
ExecStart=/usr/bin/java -Xmx$HEAP -jar /opt/maxima/parlons-cloud.jar \\
    --data /var/lib/maxima --relay-port $PORT --no-direct --blobstore $BLOB$NAME_ARG${PEERS:+ --peers $PEERS}
Restart=on-failure
RestartSec=10

# ---- filesystem: the node needs exactly its data dir, nothing more ----
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/maxima
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

# ---- privilege / capabilities: a compromised process gains nothing ----
CapabilityBoundingSet=
AmbientCapabilities=
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true

# ---- syscalls: seccomp allowlist for a network service ----
SystemCallArchitectures=native
SystemCallFilter=@system-service
SystemCallFilter=~@privileged ~@resources ~@obsolete
SystemCallErrorNumber=EPERM

# ---- network: block cloud-metadata + link-local so an RCE can't steal
#      instance credentials. The node still reaches public relays + the
#      wallet gateway over the internet. ----
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=169.254.0.0/16 fe80::/10

# ---- resource ceilings ----
MemoryMax=$MEMMAX
TasksMax=512

# Journal, not a flat file - and NEVER let the seed reach it: the node prints the
# seed only to a TTY, so under systemd (stdout = journal) it stays on disk only.
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
REMOTE

# ---- 5. firewall ---------------------------------------------------------
echo "[5/7] firewall"
$SSH "bash -s" <<REMOTE
set -e
opened=no
# grep -q active also matches "Status: inactive" - match the whole word.
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
fi
echo "      NOTE: a CLOUD firewall (Hetzner/Vultr/AWS console) can still block"
echo "            $PORT/tcp. If your phone can't reach the account, open it there."
REMOTE

# ---- 6. start and prove --------------------------------------------------
echo "[6/7] starting"
$SSH "bash -s" <<REMOTE
set -e
systemctl daemon-reload
systemctl enable --now parlons-cloud >/dev/null 2>&1 || systemctl enable parlons-cloud
systemctl restart parlons-cloud
sleep 8
systemctl is-active parlons-cloud | sed 's/^/      state: /'
if (ss -ltn 2>/dev/null || netstat -ltn) | grep -q ":$PORT "; then
    echo "      listening on $PORT"
else
    echo "      NOT LISTENING on $PORT - last log lines:"
    journalctl -u parlons-cloud -n 25 --no-pager 2>/dev/null || true
    exit 1
fi
REMOTE

# ---- 7. hand over the pairing credentials --------------------------------
echo "[7/7] pairing credentials"
# The ADDRESS is public (it's how anyone reaches you) and lives in the journal.
# The CODE is the one-time bootstrap secret - it exists only while no device is
# paired, is mode 0600, and is deliberately never logged; we read it directly.
$SSH "bash -s" <<'REMOTE'
set -e
addr=$(journalctl -u parlons-cloud --no-pager 2>/dev/null \
        | grep -oE 'permanent address MAX#[^ ]+' | tail -1 | sed 's/permanent address //')
# The bootstrap code exists ONLY while no device is paired, so its presence is
# the signal for first-run vs. already-paired.
code=$(cat /var/lib/maxima/pair-code.txt 2>/dev/null || true)

echo
echo "  ------------------------------------------------------------"
echo "   PAIR YOUR PHONE"
echo "  ------------------------------------------------------------"
if [ -n "$addr" ]; then
    echo "   1. Account address (paste or scan its QR in the app):"
    echo
    echo "      $addr"
    echo
else
    echo "   Address not in the journal yet - give it a few seconds, then:"
    echo "      journalctl -u parlons-cloud | grep 'permanent address' | tail -1"
    echo
fi
if [ -n "$code" ]; then
    echo "   2. One-time pairing code (single use):"
    echo
    echo "      $code"
    echo
    echo "   Open the Parlons Cloud app -> enter the address + this code -> Connect & pair."
    echo "   Need another later? On a paired phone: New pairing code (Node tab)."
else
    echo "   A device is already paired (no bootstrap code on disk)."
    echo "   To add another phone, mint a fresh code from an already-paired device"
    echo "   (Node tab -> New pairing code), or with the CLI: parlons newcode."
fi
echo "  ------------------------------------------------------------"
REMOTE

echo
echo "  !! BACK UP YOUR SEED - it is your identity AND a spendable wallet:"
echo "       ssh $TARGET 'cat /var/lib/maxima/seed.txt'   # read once, store safely"
echo
echo "  Update later: re-run this same command. Roll back: on the box,"
echo "    ln -sf parlons-cloud-<OLDVER>.jar parlons-cloud.jar && systemctl restart parlons-cloud"
