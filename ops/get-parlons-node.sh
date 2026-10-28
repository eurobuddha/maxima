#!/usr/bin/env bash
#
# Parlons Node - one-command install of the whole thing on a Linux machine that stays on. One jar,
# one process, every service: a full Minima node (server role), the Parlons/Maxima relay and your
# always-on Parlons account, the keets transport (its public HyperDHT bootstrap node and keets
# mailbox), and the Dexicon DEX relay cape. Linux VPS, Raspberry Pi 4/5 (64-bit), any
# Debian/Ubuntu/Fedora/Alpine/Arch box. Needs root or sudo.
#
#   curl -fsSL https://parlons.info/install.sh | bash
#
#   … | bash -s -- --host node.example.com   serve the DEX relay and the wallet gateway over HTTPS at YOUR name
#                                            (point its A record at this box first). Without --host the box
#                                            uses <a-b-c-d>.sslip.io, a free name that resolves to its own
#                                            address, so the DEX relay is on either way. Caddy is installed
#                                            and fetches the certificate itself.
#   … | bash -s -- --update                  fetch the newest release and restart (re-running does this too)
#   … | bash -s -- --uninstall               stop and remove the service (node data and seed are kept)
#   … | bash -s -- --basic                   the plain jar: Minima node + relay + account, no keets/DHT/mailbox,
#                                            no DEX (1 GB RAM, any CPU). Chosen for you when the box cannot run
#                                            the full set (under 2 GB RAM, 32-bit CPU, no public IPv4).
#   … | bash -s -- --no-megammr              plain full node (1 GB RAM); picked for you under ~3.5 GB RAM
#   … | bash -s -- --heap 3g                 a bigger Java heap than the size-based default
#   … | bash -s -- --public-host 1.2.3.4     the public IPv4 (default: the box's global-scope address)
#
# (--fleet is accepted and means the default: the full parlons-node-fleet jar.)
#
# What it does:
#   1. Java 11+ and qrencode (apt / dnf / apk / pacman); Caddy with --host
#   2. downloads the newest parlons-node release from GitHub and checks its sha256
#   3. an unprivileged `maxima` user, /var/lib/parlons-node (mode 700), a hardened systemd unit
#   4. opens Minima P2P 9001/tcp, the relay 9501/tcp+udp and the DHT 49737/udp in ufw / firewalld
#      when one is active (and 80 + 443 with --host)
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
DHT_PORT=49737
HEAP=""
MEGAMMR=""
FLEET="auto"           # auto = the full jar when the box can run it, else the plain jar (see below)
PUBLIC_HOST=""
HOST_NAME=""           # --host: DNS name for the HTTPS front (DEX relay + wallet gateway)
ACTION="install"
while [ $# -gt 0 ]; do
    case "$1" in
        --update) ACTION="install"; shift ;;
        --uninstall) ACTION="uninstall"; shift ;;
        --no-megammr) MEGAMMR="false"; shift ;;
        --heap) HEAP="${2:?--heap needs a size such as 2g}"; shift 2 ;;
        --fleet) FLEET="true"; shift ;;
        --basic) FLEET="false"; shift ;;
        --host) HOST_NAME="${2:?--host needs a DNS name such as node.example.com}"; shift 2 ;;
        --public-host) PUBLIC_HOST="${2:?--public-host needs an IPv4 address}"; shift 2 ;;
        -h|--help) echo "Usage and options: the header of https://parlons.info/install.sh"; exit 0 ;;
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
    $SUDO rm -rf "$DATA/runtime"     # the fleet jar's extracted Node.js + sidecar (re-extracted on install)
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

# The full jar (keets transport + DHT bootstrap + mailbox in one sidecar, Minima server role, DEX cape)
# needs x86_64/aarch64 for the embedded Node.js, 2 GB of RAM and a public IPv4 the DHT can be told.
# "auto" takes it when the box qualifies and falls back to the plain jar with a note; --basic forces
# the plain jar; --fleet insists and fails loudly instead.
FLEET_PROPS=""; AF_EXTRA=""; TASKS_MAX=1024; JAR_RE='parlons-node-[0-9.]+\.jar'; JAR_PREFIX="parlons-node-"
BASIC_WHY=""
if [ "$FLEET" != false ]; then
    case "$(uname -m)" in x86_64|aarch64) ;; *) BASIC_WHY="the embedded Node.js has no $(uname -m) build" ;; esac
    [ -n "$BASIC_WHY" ] || [ "$MEM_MB" -ge 1900 ] || BASIC_WHY="the full set wants 2 GB of RAM and this box has ${MEM_MB} MB"
    if [ -z "$BASIC_WHY" ] && [ -z "$PUBLIC_HOST" ]; then
        PUBLIC_HOST="$(ip -4 -o addr show scope global 2>/dev/null | awk '{print $4}' | cut -d/ -f1 | grep -vE '^(10\.|172\.(1[6-9]|2[0-9]|3[01])\.|192\.168\.|100\.(6[4-9]|[7-9][0-9]|1[01][0-9]|12[0-7])\.)' | head -1 || true)"
        [ -n "$PUBLIC_HOST" ] || BASIC_WHY="no public IPv4 on this box (behind a router? pass --public-host <your public ip> and forward the ports)"
    fi
    if [ -n "$BASIC_WHY" ]; then
        [ "$FLEET" = true ] && die "Cannot run the full node here: $BASIC_WHY."
        FLEET="false"
    else
        FLEET="true"
    fi
fi
if [ "$FLEET" = true ]; then
    case "$MEMMAX" in *M) MEMMAX="$(( ${MEMMAX%M} + 1024 ))M" ;; esac
    FLEET_PROPS="    -Dparlons.fleet=true -Dparlons.public.host=$PUBLIC_HOST \\
"
    AF_EXTRA=" AF_NETLINK"      # udx-native watches interfaces over netlink
    TASKS_MAX=2048
    JAR_RE='parlons-node-fleet-[0-9.]+\.jar'; JAR_PREFIX="parlons-node-fleet-"
fi
# --host: the DEX relay cape and the wallet gateway are only useful behind HTTPS (phones refuse plain
# http). Caddy fetches the certificate itself once the name resolves to this box. With no --host and a
# public IPv4, the box names itself <a-b-c-d>.sslip.io (free wildcard DNS: the name resolves to the
# address inside it, nothing to register), so the DEX relay is on by default.
HOST_PROPS=""; HOST_AUTO=""
if [ -z "$HOST_NAME" ] && [ "$FLEET" = true ]; then
    HOST_NAME="$(printf '%s' "$PUBLIC_HOST" | tr . -).sslip.io"; HOST_AUTO="yes"
fi
if [ -n "$HOST_NAME" ]; then
    case "$HOST_NAME" in *.*) ;; *) die "--host wants a DNS name (node.example.com), got '$HOST_NAME'" ;; esac
    HOST_PROPS="    -Dparlons.dex.public=https://$HOST_NAME/dex-relay -Dparlons.node.public=https://$HOST_NAME/parlons-node \\
"
    [ "$FLEET" = true ] || HOST_PROPS="    -Dparlons.node.rpc=true -Dparlons.dex.port=9602 -Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=https://$HOST_NAME/dex-relay -Dparlons.node.public=https://$HOST_NAME/parlons-node \\
"
fi

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

# ---------- 1b. caddy (--host) ----------
CADDY=""
if [ -n "$HOST_NAME" ]; then
    say "[1b/5] HTTPS front for $HOST_NAME"
    if command -v caddy >/dev/null 2>&1; then CADDY="present"
    elif ss -ltn 2>/dev/null | awk '{print $4}' | grep -qE ':(80|443)$'; then
        note "another web server already holds port 80/443 - not installing Caddy."
        note "Front https://$HOST_NAME/dex-relay/ -> http://127.0.0.1:9602/ and /parlons-node/ -> http://127.0.0.1:9585/ in it"
        note "(the Node manual shows the nginx/Apache blocks: https://parlons.info/guide/node.html#11-tls-fronting-caddy-nginx-apache)."
    else
        note "installing Caddy"
        if command -v apt-get >/dev/null 2>&1; then $SUDO env DEBIAN_FRONTEND=noninteractive apt-get install -y -qq caddy >/dev/null 2>&1 || true
        elif command -v dnf >/dev/null 2>&1; then $SUDO dnf install -y -q caddy >/dev/null 2>&1 || true
        elif command -v apk >/dev/null 2>&1; then $SUDO apk add --quiet caddy >/dev/null 2>&1 || true
        elif command -v pacman >/dev/null 2>&1; then $SUDO pacman -S --noconfirm --quiet caddy >/dev/null 2>&1 || true; fi
        command -v caddy >/dev/null 2>&1 && CADDY="installed" || note "Caddy is not in this distribution's repositories - install it from https://caddyserver.com/docs/install and re-run."
    fi
    if [ -n "$CADDY" ]; then
        $SUDO mkdir -p /etc/caddy
        if [ -f /etc/caddy/Caddyfile ] && ! grep -q "parlons-node" /etc/caddy/Caddyfile; then
            $SUDO cp /etc/caddy/Caddyfile "/etc/caddy/Caddyfile.before-parlons-node-$(date +%s)"
        fi
        $SUDO tee /etc/caddy/Caddyfile >/dev/null <<EOF
# Parlons Node front (written by install.sh). The node keeps both services on loopback;
# Caddy fetches and renews the certificate for $HOST_NAME itself.
$HOST_NAME {
    handle_path /dex-relay/* {
        reverse_proxy 127.0.0.1:9602
    }
    handle_path /parlons-node/* {
        reverse_proxy 127.0.0.1:9585
    }
    respond / "Parlons Node $HOST_NAME" 200
}
EOF
        $SUDO systemctl enable --now caddy >/dev/null 2>&1 || true
        $SUDO systemctl reload caddy >/dev/null 2>&1 || $SUDO systemctl restart caddy >/dev/null 2>&1 || true
        note "Caddy $CADDY: https://$HOST_NAME/dex-relay/ and /parlons-node/ (certificate appears once DNS points here)"
    fi
fi

# ---------- 2. download ----------
say "[2/5] Newest Parlons Node release"
JSON="$(curl -fsSL -H 'Accept: application/vnd.github+json' "https://api.github.com/repos/$REPO/releases?per_page=50")" || die "Could not reach GitHub to find the release."
JAR_URL="$(printf '%s' "$JSON" | grep -oE "https://github.com/[^\"]+/node-v[^\"]+/$JAR_RE" | head -1)"
[ -n "$JAR_URL" ] || die "No ${JAR_PREFIX}<ver>.jar release found under $REPO."
SUM_URL="$(printf '%s' "$JSON" | grep -oE "https://github.com/[^\"]+/$(basename "$(dirname "$JAR_URL")")/SHA256SUMS" | head -1)"
[ -n "$SUM_URL" ] || die "The release holding $(basename "$JAR_URL") has no SHA256SUMS."
JAR_NAME="$(basename "$JAR_URL")"; VER="${JAR_NAME#$JAR_PREFIX}"; VER="${VER%.jar}"
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
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway$([ "$FLEET" = true ] && echo " + keets fleet endpoint") ($VER)
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
$FLEET_PROPS$HOST_PROPS    -jar $OPT/parlons-node.jar
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
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX$AF_EXTRA
IPAddressDeny=169.254.0.0/16 fe80::/10
MemoryMax=$MEMMAX
TasksMax=$TASKS_MAX
LimitNOFILE=65536
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
EOF
if [ "$FLEET" = true ]; then
    note "heap $HEAP (MemoryMax $MEMMAX), MegaMMR $MEGAMMR, syncing from $ROOTNODE, full node on $PUBLIC_HOST"
else
    note "heap $HEAP (MemoryMax $MEMMAX), MegaMMR $MEGAMMR, syncing from $ROOTNODE, plain jar${BASIC_WHY:+ ($BASIC_WHY)}"
fi

# ---------- 4. firewall ----------
say "[4/5] Firewall"
opened=no
PORTS="$P2P_PORT/tcp $RELAY_PORT/tcp"
[ "$FLEET" = true ] && PORTS="$PORTS $RELAY_PORT/udp $DHT_PORT/udp"     # STUN on the relay port, the HyperDHT
[ -n "$CADDY" ] && PORTS="$PORTS 80/tcp 443/tcp"
if command -v ufw >/dev/null 2>&1 && $SUDO ufw status 2>/dev/null | head -1 | grep -qw active; then
    for p in $PORTS; do $SUDO ufw allow $p >/dev/null; done; opened=ufw
fi
if command -v firewall-cmd >/dev/null 2>&1 && $SUDO firewall-cmd --state >/dev/null 2>&1; then
    for p in $PORTS; do $SUDO firewall-cmd --permanent --add-port=$p >/dev/null; done
    $SUDO firewall-cmd --reload >/dev/null; opened=firewalld
fi
[ "$opened" = no ] && note "no host firewall active - nothing to open" || note "$opened: opened $PORTS"
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
if [ "$FLEET" = true ]; then
    echo
    echo "  This box now serves: Minima node (P2P $P2P_PORT/tcp), Parlons relay ($RELAY_PORT), keets transport +"
    echo "  DHT bootstrap ($DHT_PORT/udp) + keets mailbox, on $PUBLIC_HOST. The Minima and relay meshes pick it up"
    echo "  by gossip. Its keets mailbox joins once its pubkey is signed into the published list - the node"
    echo "  prints the exact command (wait for the sidecar):"
    for i in $(seq 1 60); do $SUDO journalctl -u parlons-node --no-pager 2>/dev/null | grep -q 'keets mailbox   0x' && break; sleep 2; done
    $SUDO journalctl -u parlons-node --no-pager 2>/dev/null | grep -A4 'FLEET ENDPOINT' | tail -5 | sed 's/^.*\[parlons-node\]/   /' || true
else
    echo
    echo "  Plain jar${BASIC_WHY:+ ($BASIC_WHY)}: Minima node + Parlons relay + account, no keets transport."
fi
if [ -n "$HOST_NAME" ]; then
    echo
    echo "  DEX relay cape: https://$HOST_NAME/dex-relay  (health: curl -s https://$HOST_NAME/dex-relay/dex/health)"
    echo "  Wallet gateway: https://$HOST_NAME/parlons-node (token in $DATA/gateway-token.txt - keep it private)"
    [ -n "$HOST_AUTO" ] && echo "  (an automatic sslip.io name; own a domain? point its A record here and re-run with --host your.name)"
    [ -n "$CADDY" ] || echo "  (no Caddy: your own web server must front those two paths - see the manual, section 11)"
else
    echo
    echo "  DEX relay cape: off (plain jar, no public IPv4). Re-run with --public-host <ip> --host <name> on a reachable box."
fi
echo
echo "  Watch it sync:   journalctl -u parlons-node -f | grep heartbeat"
echo "  New invitation:  sudo parlons-pair --admin"
echo "  Update:          curl -fsSL https://parlons.info/install.sh | bash"
echo
printf '\033[33m  BACK UP YOUR SEED: it lives in %s and is your identity AND a wallet.\033[0m\n' "$DATA"
printf '\033[33m  The Parlons Node manual shows how to read it once: https://parlons.info/guide/node.html#20-seeds-identity-and-backups\033[0m\n\n'
