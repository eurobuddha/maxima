#!/usr/bin/env bash
#
# Parlons Node - one-command install of the whole thing on a Linux machine that stays on:
# a full Minima node + a Parlons relay + your always-on Parlons account, in one process.
# Linux VPS, Raspberry Pi (64-bit), any Debian/Ubuntu/Fedora/Alpine/Arch box. Needs root or sudo.
#
#   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash
#
#   … | bash -s -- --update       fetch the newest release and restart (re-running does this too)
#   … | bash -s -- --uninstall    stop and remove the service (node data and seed are kept)
#   … | bash -s -- --no-megammr   plain full node (1 GB RAM); picked for you under ~3.5 GB RAM
#   … | bash -s -- --heap 3g      a bigger Java heap than the size-based default
#
# What it does:
#   1. Java 11+ and qrencode (apt / dnf / apk / pacman)
#   2. downloads the newest parlons-node release from GitHub and checks its sha256
#   3. an unprivileged `maxima` user, /var/lib/parlons-node (mode 700), a hardened systemd unit
#   4. opens Minima P2P 9001 and the relay 9501 in ufw / firewalld when one is active
#   5. starts it, waits for the account, asks you to set the admin pairing password,
#      and draws the QR code your phone scans
#
# Mac or Windows? Install Parlons Desktop instead. Account only, no blockchain, 1 GB RAM?
# Use get-parlons-cloud.sh. Fleet-style deploys from your own machine: ops/deploy-parlons-node.sh.
#
# It never prints your seed. /var/lib/parlons-node holds your identity AND a wallet - back it up.
set -euo pipefail

REPO="eurobuddha/maxima"
RAW="https://raw.githubusercontent.com/$REPO/main/ops"
DATA=/var/lib/parlons-node
OPT=/opt/maxima
UNIT=/etc/systemd/system/parlons-node.service
ROOTNODE="65.109.31.226:9001"
P2P_PORT=9001
RELAY_PORT=9501
HEAP=""
MEGAMMR=""
ACTION="install"
while [ $# -gt 0 ]; do
    case "$1" in
        --update) ACTION="install"; shift ;;
        --uninstall) ACTION="uninstall"; shift ;;
        --no-megammr) MEGAMMR="false"; shift ;;
        --heap) HEAP="${2:?--heap needs a size such as 2g}"; shift 2 ;;
        -h|--help) echo "Usage and options: the header of $RAW/get-parlons-node.sh"; exit 0 ;;
        *) echo "unknown option: $1" >&2; exit 2 ;;
    esac
done

say()  { printf '\n\033[1m%s\033[0m\n' "$*"; }
note() { printf '   %s\n' "$*"; }
die()  { printf '\n\033[31m%s\033[0m\n' "$*" >&2; exit 1; }

[ "$(uname -s)" = Linux ] || die "Parlons Node installs on Linux. On a Mac or Windows, install Parlons Desktop (https://parlons.info)."
[ -d /run/systemd/system ] || die "This installer needs systemd (any mainstream Linux has it)."
if [ "$(id -u)" = 0 ]; then SUDO=""
elif command -v sudo >/dev/null 2>&1; then SUDO="sudo"; sudo -v || die "sudo is needed to install a system service."
else die "Run this as root, or install sudo."; fi

if [ "$ACTION" = uninstall ]; then
    say "Removing the Parlons Node service (your node data in $DATA stays)"
    $SUDO systemctl disable --now parlons-node >/dev/null 2>&1 || true
    $SUDO rm -f "$UNIT" /usr/local/bin/parlons-pair
    $SUDO systemctl daemon-reload
    note "Done. Jars are in $OPT. To remove the node itself (identity, wallet, chats): sudo rm -rf $DATA"
    exit 0
fi

# Size the node to the box: MegaMMR (the wallet gateway for phones) wants 2-4 GB.
MEM_MB=$(( $(awk '/MemTotal/ {print $2}' /proc/meminfo) / 1024 ))
if [ -z "$MEGAMMR" ]; then if [ "$MEM_MB" -ge 3500 ]; then MEGAMMR="true"; else MEGAMMR="false"; fi; fi
if [ -z "$HEAP" ]; then if [ "$MEGAMMR" = true ]; then HEAP="2g"; else HEAP="768m"; fi; fi
case "$HEAP" in
    *g|*G) MEMMAX="$(( ${HEAP%[gG]} * 1024 + 512 ))M" ;;
    *m|*M) MEMMAX="$(( ${HEAP%[mM]} + 512 ))M" ;;
    *) die "--heap must end in m or g (got '$HEAP')" ;;
esac
[ "$MEM_MB" -ge 900 ] || die "This machine has ${MEM_MB} MB of RAM; a Parlons Node needs at least 1 GB. Use get-parlons-cloud.sh for an account only."

# ---------- 1. java + qrencode ----------
say "[1/5] Java"
java_ok() { command -v java >/dev/null 2>&1 && java -version 2>&1 | head -1 | grep -qE '"(1[1-9]|[2-9][0-9])'; }
if java_ok; then
    note "present: $(java -version 2>&1 | head -1)"
else
    note "installing Java (needs 11 or newer)"
    if command -v apt-get >/dev/null 2>&1; then
        $SUDO env DEBIAN_FRONTEND=noninteractive apt-get update -qq && $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq default-jre-headless qrencode >/dev/null 2>&1
    elif command -v dnf >/dev/null 2>&1; then $SUDO dnf install -y -q java-21-openjdk-headless qrencode >/dev/null
    elif command -v apk >/dev/null 2>&1; then $SUDO apk add --quiet openjdk21-jre-headless libqrencode-tools
    elif command -v pacman >/dev/null 2>&1; then $SUDO pacman -S --noconfirm --quiet jre-openjdk-headless qrencode >/dev/null
    else die "No package manager found. Install Java 11+ (https://adoptium.net) and re-run."; fi
    java_ok || die "Java did not install cleanly; install Java 11+ from https://adoptium.net and re-run."
    note "installed: $(java -version 2>&1 | head -1)"
fi
if ! command -v qrencode >/dev/null 2>&1 && command -v apt-get >/dev/null 2>&1; then
    $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq qrencode >/dev/null 2>&1 || true
fi
JAVA_BIN="$(readlink -f "$(command -v java)")"

# ---------- 2. download ----------
say "[2/5] Newest Parlons Node release"
JSON="$(curl -fsSL -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$REPO/releases?per_page=50")" || die "Could not reach GitHub to find the release."
JAR_URL="$(printf '%s' "$JSON" | grep -oE 'https://github.com/[^"]+/node-v[^"]+/parlons-node-[0-9.]+\.jar' | head -1)"
SUM_URL="$(printf '%s' "$JSON" | grep -oE 'https://github.com/[^"]+/node-v[^"]+/SHA256SUMS' | head -1)"
[ -n "$JAR_URL" ] && [ -n "$SUM_URL" ] || die "No parlons-node release with checksums found under $REPO."
JAR_NAME="$(basename "$JAR_URL")"; VER="${JAR_NAME#parlons-node-}"; VER="${VER%.jar}"
$SUDO mkdir -p "$OPT"
if [ -f "$OPT/$JAR_NAME" ]; then
    note "already have $VER"
else
    note "downloading $JAR_NAME"
    TMP="$(mktemp)"; trap 'rm -f "$TMP"' EXIT
    curl -fsSL -o "$TMP" "$JAR_URL"
    want="$(curl -fsSL "$SUM_URL" | grep " $JAR_NAME\$" | cut -d' ' -f1 || true)"
    got="$(sha256sum "$TMP" | cut -d' ' -f1)"
    [ -n "$want" ] && [ "$want" = "$got" ] || die "Checksum mismatch for $JAR_NAME - download refused."
    note "sha256 verified: $got"
    $SUDO install -m 644 "$TMP" "$OPT/$JAR_NAME"
fi
$SUDO ln -sf "$JAR_NAME" "$OPT/parlons-node.jar"
TMP2="$(mktemp)"; curl -fsSL -o "$TMP2" "$RAW/parlons-pair" || die "Could not download parlons-pair."
$SUDO install -m 755 "$TMP2" /usr/local/bin/parlons-pair; rm -f "$TMP2"

# ---------- 3. user, data, service ----------
say "[3/5] Service (starts at boot)"
id maxima >/dev/null 2>&1 || $SUDO useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
$SUDO mkdir -p "$DATA"; $SUDO chown maxima:maxima "$DATA"; $SUDO chmod 700 "$DATA"
# Same hardened unit as ops/deploy-parlons-node.sh, at its defaults.
$SUDO tee "$UNIT" >/dev/null <<EOF
[Unit]
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway ($VER)
Documentation=https://parlons.info/guide/node.html
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima
ExecStart=$JAVA_BIN -Xmx$HEAP \\
    -Dparlons.node.data=$DATA \\
    -Dparlons.node.port=$P2P_PORT \\
    -Dparlons.relay.port=$RELAY_PORT \\
    -Dparlons.node.megammr=$MEGAMMR \\
    -Dparlons.node.rootnode=$ROOTNODE \\
    -jar $OPT/parlons-node.jar
# always, not on-failure: a resync ends with exit 0 and the node must come back.
Restart=always
RestartSec=10
RestartForceExitStatus=3
SuccessExitStatus=3

NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=$DATA
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
TasksMax=1024
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
note "heap $HEAP (MemoryMax $MEMMAX), MegaMMR $MEGAMMR, syncing from $ROOTNODE"

# ---------- 4. firewall ----------
say "[4/5] Firewall"
opened=no
if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | head -1 | grep -qw active; then
    $SUDO ufw allow $P2P_PORT/tcp >/dev/null; $SUDO ufw allow $RELAY_PORT/tcp >/dev/null; opened=ufw
fi
if command -v firewall-cmd >/dev/null 2>&1 && $SUDO firewall-cmd --state >/dev/null 2>&1; then
    $SUDO firewall-cmd --permanent --add-port=$P2P_PORT/tcp >/dev/null
    $SUDO firewall-cmd --permanent --add-port=$RELAY_PORT/tcp >/dev/null
    $SUDO firewall-cmd --reload >/dev/null; opened=firewalld
fi
[ "$opened" = no ] && note "no host firewall active - nothing to open" || note "$opened: opened $P2P_PORT/tcp and $RELAY_PORT/tcp"
note "A cloud firewall (Hetzner/Vultr/AWS console) or a home router can still block them."
note "Your account works without them; other people's messages and blocks need them open."

$SUDO systemctl daemon-reload
$SUDO systemctl enable parlons-node >/dev/null 2>&1
$SUDO systemctl restart parlons-node

# ---------- 5. account + pairing ----------
say "[5/5] Waiting for your account to come online"
for i in $(seq 1 120); do $SUDO test -s "$DATA/account.txt" && break; sleep 2; done
$SUDO test -s "$DATA/account.txt" || { note "not up after 4 minutes - last log lines:"; $SUDO journalctl -u parlons-node -n 25 --no-pager; die "Fix the cause and re-run; nothing is lost."; }
ADDR="$($SUDO cat "$DATA/account.txt")"

printf '\n\033[1m  Your Parlons Node is running (Parlons Node %s).\033[0m\n\n' "$VER"
echo "  Account address (share it; it never changes):"
echo "  $ADDR"
echo
if [ -r /dev/tty ] && { exec 3</dev/tty; } 2>/dev/null; then
    exec 3<&-
    if ! $SUDO test -e "$DATA/.admin-pairing-password"; then
        echo "  Set an admin pairing password. It protects the invitations for your own devices."
        $SUDO parlons-pair --set-admin-password </dev/tty || note "not set - run: sudo parlons-pair --set-admin-password"
    fi
    echo
    echo "  Pair your phone: type the admin password, then scan the QR in the Parlons Cloud app."
    $SUDO parlons-pair --admin </dev/tty || note "run later: sudo parlons-pair --admin"
else
    echo "  No terminal to ask for a password. Pair your phone later with:"
    echo "    sudo parlons-pair --set-admin-password   (once)"
    echo "    sudo parlons-pair --admin                (draws the QR)"
fi
echo
echo "  Watch it sync:   journalctl -u parlons-node -f | grep heartbeat"
echo "  New invitation:  sudo parlons-pair --admin"
echo "  Update:          curl -fsSL $RAW/get-parlons-node.sh | bash"
echo
printf '\033[33m  BACK UP YOUR SEED: it lives in %s and is your identity AND a wallet.\033[0m\n' "$DATA"
printf '\033[33m  The Parlons Node manual shows how to read it once: https://parlons.info/guide/node.html#20-seeds-identity-and-backups\033[0m\n\n'
