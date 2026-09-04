#!/usr/bin/env bash
#
# Deploy (or update) a Parlons Node on your own VPS, idempotently.
#
# A Parlons Node is the merged binary: a FULL Minima node (org.minima, layer-1
# validate + P2P) wearing the Parlons cape — the clean-room :core/:server Maxima
# relay AND a hardened read+relay wallet gateway for phones — all in one JVM,
# driven from ONE seed. Running MegaMMR, it serves its own wallet from its own
# chain (no privateprivate.org) and can be the wallet gateway for any phone.
#
# One command, repeatable. Running it twice is a no-op plus a jar refresh, so
# "update" is the same operation as "install".
#
#   ops/deploy-parlons-node.sh <ssh-target> [--jar FILE] [--heap 2g]
#       [--relay-port N] [--gateway-port N] [--rootnode host:port]
#       [--gateway-public] [--no-megammr] [--passphrase-file /path/on/vps] [--memmax 2560M]
#       [--p2p-port 9001] [--rpc] [--megammr-seed https://eurobuddha.com/mega.mmr]
#       [--peers h:p,h:p] [--blobstore 1024] [--replace-relay]
#       [--seed-from /path/on/vps/seed.txt --archive 65.109.31.226:8001]
#   ops/deploy-parlons-node.sh root@1.2.3.4 --rootnode 65.109.31.226:9001
#
# What it does, in order:
#   1. installs a headless JRE if java is missing
#   2. creates an unprivileged `maxima` user and /var/lib/parlons-node (mode 700)
#   3. uploads the fatJar to /opt/maxima, sha256-verifies it, points a symlink at it
#   4. writes a hardened systemd unit (parlons-node.service)
#   5. opens the P2P + relay ports in ufw / firewalld (gateway stays loopback
#      unless --gateway-public: front it with TLS, e.g. Caddy — see cloud/NODE-SETUP.md)
#   6. restarts, waits, proves the node is listening + the gateway answers
#   7. prints the account address, the Maxima identity, and the gateway bearer token
#
# --rpc turns on the node's admin RPC (loopback use only: it binds every interface, so
# it is NOT opened in the firewall). The gateway is read+relay only by design, so this
# is the operator's ONLY channel for `vault` (seed backup) and `megammr action:import`
# (an IBD does NOT carry the MegaMMR — see cloud/NODE-SETUP.md §3b). --p2p-port moves
# the layer-1 port off 9001 for a box whose 9001 is already a stock Minima node.
# --megammr-seed URL (needs --rpc) fills an EMPTY MegaMMR from a published snapshot:
# download -> `megammr action:import` over loopback RPC -> restart -> prove non-empty.
# --peers / --blobstore mirror maxima-server.jar's flags (Phase-B mesh bootstrap list,
# media blob shelf) so the node's relay is a full fleet relay. --replace-relay stops +
# disables the box's maxima-relay.service first so the node's relay can take its port
# (the unit stays on disk: rollback = systemctl enable --now maxima-relay).
# --seed-from FILE (needs --rpc) makes the node adopt an EXISTING 24-word phrase — the
# box's relay seed (/var/lib/maxima/seed.txt) or a cloud account's — so its Maxima
# identity, and therefore every address pinned to it, is unchanged. Done ON the box over
# the loopback admin RPC: `megammrsync action:resync host:<--archive> phrase:"…"`, which
# resets the wallet keys to the phrase and pulls its coins from an archive node, then the
# node shuts itself down and is restarted. The phrase never leaves the box and is never
# printed; the script only reports whether the vault matches the file afterwards. One
# time: a marker file makes re-runs skip it.
#
# It never prints your seed. The seed at /var/lib/parlons-node/<ver>/... is your
# wallet AND identity — read it once with `vault` over ssh and back it up. To run
# THIS node on an EXISTING account seed (migration), see cloud/NODE-SETUP.md — it
# is a one-time `vault action:restorekeys` before first sync, done by hand because
# it is fund-critical.
#
set -euo pipefail

TARGET="${1:-}"
[ -z "$TARGET" ] && { echo "usage: $0 <ssh-target> [--jar F] [--heap 2g] [--memmax 2560M] [--p2p-port 9001] [--relay-port N] [--gateway-port N] [--rootnode h:p] [--rpc] [--megammr-seed URL] [--peers h:p,..] [--blobstore MB] [--replace-relay] [--gateway-public] [--no-megammr] [--passphrase-file /path/on/vps]" >&2; exit 2; }
shift

JAR=""
HEAP="2g"
RELAY_PORT=9501
GW_PORT=9585
ROOTNODE=""
GW_BIND="127.0.0.1"
MEGAMMR="true"
PASSFILE=""
MEMMAX=""
P2P_PORT=9001
RPC="false"
MEGA_SEED=""
PEERS=""
BLOB_MB=0
REPLACE_RELAY="false"
SEED_FROM=""
ARCHIVE="65.109.31.226:8001"
while [ $# -gt 0 ]; do
    case "$1" in
        --jar)            JAR="$2"; shift 2 ;;
        --heap)           HEAP="$2"; shift 2 ;;
        # cgroup ceiling for the whole process (heap + metaspace + threads + direct buffers).
        # Default = heap + 512M: a MemoryMax EQUAL to -Xmx OOM-kills the JVM as soon as its
        # off-heap overhead shows up, which under MegaMMR is minutes, not days.
        --memmax)         MEMMAX="$2"; shift 2 ;;
        --p2p-port)       P2P_PORT="$2"; shift 2 ;;
        --rpc)            RPC="true"; shift ;;
        --megammr-seed)   MEGA_SEED="$2"; shift 2 ;;
        --peers)          PEERS="$2"; shift 2 ;;
        --blobstore)      BLOB_MB="$2"; shift 2 ;;
        --replace-relay)  REPLACE_RELAY="true"; shift ;;
        --seed-from)      SEED_FROM="$2"; shift 2 ;;
        --archive)        ARCHIVE="$2"; shift 2 ;;
        --relay-port)     RELAY_PORT="$2"; shift 2 ;;
        --gateway-port)   GW_PORT="$2"; shift 2 ;;
        --rootnode)       ROOTNODE="$2"; shift 2 ;;
        --gateway-public) GW_BIND="0.0.0.0"; shift ;;
        --no-megammr)     MEGAMMR="false"; shift ;;
        # Path (ON THE VPS) to a mode-600 file holding the node passphrase — only for a
        # password-locked node, so the cape/wallet/gateway can unlock and start. See NODE-SETUP.md.
        --passphrase-file) PASSFILE="$2"; shift 2 ;;
        *) echo "unknown arg: $1" >&2; exit 2 ;;
    esac
done

# Default to the newest built fatJar so this follows the repo without an edit per release.
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
REPO="$(cd "$SCRIPT_DIR/.." && pwd)"
if [ -z "$JAR" ]; then
    JAR="$(ls -t "$REPO"/dist/parlons-node-*.jar "$REPO"/node/build/libs/parlons-node.jar 2>/dev/null | head -1 || true)"
fi
[ -z "$JAR" ] || [ ! -f "$JAR" ] && { echo "no parlons-node jar found — build it: ./gradlew :node:fatJar (or pass --jar)" >&2; exit 1; }

VER="$(basename "$JAR" | sed -E 's/^parlons-node-?//; s/\.jar$//')"
[ -z "$VER" ] && VER="dev"
REMOTE_JAR="parlons-node-${VER}.jar"
SUM="$(shasum -a 256 "$JAR" | cut -d' ' -f1)"
# MegaMMR + chain DB is heavier than a plain relay: give the process room, and a
# hard MemoryMax ABOVE the heap (heap + 512M unless --memmax) so the JVM's own
# off-heap overhead can't trip the cgroup, while a runaway still can't take the box.
if [ -z "$MEMMAX" ]; then
    case "$HEAP" in
        *g|*G) MEMMAX="$(( ${HEAP%[gG]} * 1024 + 512 ))M" ;;
        *m|*M) MEMMAX="$(( ${HEAP%[mM]} + 512 ))M" ;;
        *)     echo "--heap must end in m or g (got '$HEAP')" >&2; exit 2 ;;
    esac
fi

echo "Deploying Parlons Node $VER -> $TARGET"
echo "  jar     $JAR"
echo "  heap    $HEAP (MemoryMax $MEMMAX)   p2p :$P2P_PORT   relay :$RELAY_PORT   gateway ${GW_BIND}:$GW_PORT   megammr=$MEGAMMR   rpc=$RPC$([ "$RPC" = true ] && echo " (loopback :$((P2P_PORT+4)), not opened in the firewall)")"
[ -n "$ROOTNODE" ] && echo "  peer    $ROOTNODE" || echo "  peer    (none set — node won't sync; pass --rootnode host:port)"
[ -n "$PEERS" ] && echo "  mesh    $PEERS"
[ "$BLOB_MB" -gt 0 ] && echo "  blobs   $BLOB_MB MB shelf"
[ "$REPLACE_RELAY" = true ] && echo "  relay   REPLACING maxima-relay.service on this box (its port becomes the node's)"
[ -n "$SEED_FROM" ] && echo "  seed    adopting the phrase in $SEED_FROM (on the box) via archive $ARCHIVE — identity preserved"

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
mkdir -p /opt/maxima /var/lib/parlons-node
chown -R maxima:maxima /var/lib/parlons-node
chmod 700 /var/lib/parlons-node
REMOTE
# Passphrase file (locked-node only): must exist on the VPS, readable by maxima, mode 600.
if [ -n "$PASSFILE" ]; then
    $SSH "bash -s" <<REMOTE
set -e
if [ ! -f "$PASSFILE" ]; then
    echo "      ERROR: --passphrase-file $PASSFILE does not exist on the VPS." >&2
    echo "      Create it (the node passphrase, one line), then re-run." >&2
    exit 1
fi
chown maxima:maxima "$PASSFILE"
chmod 600 "$PASSFILE"
echo "      passphrase file secured (maxima:maxima, 600): $PASSFILE"
REMOTE
fi

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
ln -sf $REMOTE_JAR parlons-node.jar
chmod 644 $REMOTE_JAR
REMOTE

# ---- 4. systemd ----------------------------------------------------------
echo "[4/7] systemd unit"
$SSH "cat > /etc/systemd/system/parlons-node.service" <<REMOTE
[Unit]
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway ($VER)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima
# -Xmx is deliberate: MegaMMR + chain is heavy, but an unbounded default heap is
# how you lose a small VPS. Every knob is a -D system property (see ParlonsNodeMain).
ExecStart=/usr/bin/java -Xmx$HEAP \\
    -Dparlons.node.data=/var/lib/parlons-node \\
    -Dparlons.node.port=$P2P_PORT \\
    -Dparlons.node.rpc=$RPC \\
    -Dparlons.relay.port=$RELAY_PORT \\${PEERS:+
    -Dparlons.relay.peers=$PEERS \\}
    -Dparlons.relay.blob=$BLOB_MB \\
    -Dparlons.gateway.port=$GW_PORT \\
    -Dparlons.gateway.bind=$GW_BIND \\
    -Dparlons.node.megammr=$MEGAMMR${ROOTNODE:+ \\
    -Dparlons.node.rootnode=$ROOTNODE}${PASSFILE:+ \\
    -Dparlons.node.passphrase.file=$PASSFILE} \\
    -jar /opt/maxima/parlons-node.jar
Restart=on-failure
RestartSec=10

# ---- filesystem: the node needs exactly its data dir, nothing more ----
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
ProtectHome=true
ReadWritePaths=/var/lib/parlons-node
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
#      instance credentials. The node still reaches public peers + phones. ----
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=169.254.0.0/16 fe80::/10

# ---- resource ceilings ----
MemoryMax=$MEMMAX
TasksMax=1024

# Journal, not a flat file - and NEVER let the seed reach it: the node prints the
# seed only via the vault command, never to stdout.
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
open_port() {
    local p="\$1"
    if command -v ufw >/dev/null 2>&1 && ufw status 2>/dev/null | head -1 | grep -qw active; then
        ufw allow \$p/tcp >/dev/null; echo "      ufw: allowed \$p/tcp"; opened=yes
    fi
    if command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
        firewall-cmd --permanent --add-port=\$p/tcp >/dev/null; echo "      firewalld: allowed \$p/tcp"; opened=yes
    fi
}
open_port $P2P_PORT       # Minima P2P (layer-1 peers)
open_port $RELAY_PORT     # Maxima relay (phones attach here)
if [ "$GW_BIND" = "0.0.0.0" ]; then
    open_port $GW_PORT    # wallet gateway exposed directly (prefer a TLS front instead)
fi
if [ "\$opened" = no ]; then
    echo "      no host firewall active - nothing to open"
fi
echo "      NOTE: a CLOUD firewall (Hetzner/Vultr/AWS console) can still block"
echo "            these ports. If peers/phones can't reach the node, open them there."
REMOTE

# ---- 6. start and prove --------------------------------------------------
echo "[6/7] starting"
if [ "$REPLACE_RELAY" = true ]; then
    $SSH 'bash -s' <<'REMOTE'
if systemctl is-enabled maxima-relay >/dev/null 2>&1 || systemctl is-active maxima-relay >/dev/null 2>&1; then
    systemctl disable --now maxima-relay
    echo "      maxima-relay.service stopped + disabled (unit kept: rollback = systemctl enable --now maxima-relay)"
else
    echo "      maxima-relay.service not running here - nothing to replace"
fi
REMOTE
fi
$SSH "bash -s" <<REMOTE
set -e
systemctl daemon-reload
systemctl enable --now parlons-node >/dev/null 2>&1 || systemctl enable parlons-node
systemctl restart parlons-node
sleep 12
systemctl is-active parlons-node | sed 's/^/      state: /'
if (ss -ltn 2>/dev/null || netstat -ltn) | grep -q ":$RELAY_PORT "; then
    echo "      Maxima relay listening on $RELAY_PORT"
else
    echo "      relay NOT LISTENING on $RELAY_PORT yet (node may still be initialising) - recent log:"
    journalctl -u parlons-node -n 25 --no-pager 2>/dev/null || true
fi
if (ss -ltn 2>/dev/null || netstat -ltn) | grep -q ":$GW_PORT "; then
    echo "      wallet gateway listening on ${GW_BIND}:$GW_PORT"
fi
REMOTE

# ---- 6b. adopt an existing seed phrase (identity-preserving, one time) -----
# MUST run BEFORE the MegaMMR seed: megammrsync resets the chain databases, which
# throws away an imported MegaMMR (learned the hard way on sally + hetzner).
if [ -n "$SEED_FROM" ]; then
    echo "[6b]  seed adoption"
    if [ "$RPC" != true ]; then
        echo "      --seed-from needs --rpc (resync runs over the node's loopback RPC); skipping" >&2
    else
        $SSH "bash -s" <<REMOTE
set -e
RPC_PORT=$((P2P_PORT + 4))
marker=/var/lib/parlons-node/.seed-adopted
if [ -f "\$marker" ]; then
    echo "      already adopted on \$(cat \$marker) - nothing to do"
    exit 0
fi
if [ ! -s "$SEED_FROM" ]; then
    echo "      ERROR: $SEED_FROM missing or empty on the box" >&2
    exit 1
fi
words=\$(tr -s ' \n\t' '   ' < "$SEED_FROM" | sed 's/^ *//; s/ *\$//' | wc -w | tr -d ' ')
if [ "\$words" != "24" ]; then
    echo "      ERROR: $SEED_FROM holds \$words words, expected 24" >&2
    exit 1
fi
for i in \$(seq 1 60); do curl -s -m 5 "http://127.0.0.1:\$RPC_PORT/status" >/dev/null 2>&1 && break; sleep 2; done
# The command goes as a POST body from a 600 temp file: never in argv, never in a log.
tmp=\$(mktemp /var/lib/parlons-node/.resync.XXXXXX); chmod 600 "\$tmp"
printf 'megammrsync action:resync host:%s phrase:"%s"' "$ARCHIVE" "\$(tr -s ' \n\t' '   ' < "$SEED_FROM" | sed 's/^ *//; s/ *\$//')" > "\$tmp"
echo "      resyncing the wallet to the phrase via archive $ARCHIVE (this takes a few minutes)"
out=\$(curl -s -m 3600 --data-binary @"\$tmp" "http://127.0.0.1:\$RPC_PORT/")
shred -u "\$tmp" 2>/dev/null || rm -f "\$tmp"
case "\$out" in *'"status":true'*) ;; *) echo "      RESYNC FAILED: \$(echo "\$out" | head -c 300)" >&2; exit 1 ;; esac
echo "      resync finished - restarting (resync ends with a node shutdown)"
systemctl restart parlons-node
for i in \$(seq 1 90); do curl -s -m 5 "http://127.0.0.1:\$RPC_PORT/status" >/dev/null 2>&1 && break; sleep 2; done
# Prove it without printing anything secret: vault phrase == file phrase?
want=\$(tr -s ' \n\t' '   ' < "$SEED_FROM" | sed 's/^ *//; s/ *\$//' | tr 'a-z' 'A-Z')
got=\$(curl -s -m 30 "http://127.0.0.1:\$RPC_PORT/vault" | python3 -c 'import sys,json; print(json.load(sys.stdin)["response"]["phrase"].strip().upper())' 2>/dev/null || echo "?")
if [ "\$want" = "\$got" ]; then
    date -u +%Y-%m-%dT%H:%M:%SZ > "\$marker"; chown maxima:maxima "\$marker"
    echo "      vault MATCHES $SEED_FROM - identity preserved"
else
    echo "      vault does NOT match $SEED_FROM after resync - investigate before trusting this node" >&2
    exit 1
fi
REMOTE
    fi
fi

# ---- 6c. seed the MegaMMR from a published snapshot (optional) -------------
if [ -n "$MEGA_SEED" ]; then
    echo "[6c]  MegaMMR seed"
    if [ "$RPC" != true ] || [ "$MEGAMMR" != true ]; then
        echo "      --megammr-seed needs --rpc (import runs over the node's loopback RPC) and MegaMMR on; skipping" >&2
    else
        $SSH "bash -s" <<REMOTE
set -e
RPC_PORT=$((P2P_PORT + 4))
mmr=/var/lib/parlons-node/1.1/databases/megammr.mmr
size=\$(stat -c %s "\$mmr" 2>/dev/null || echo 0)
if [ "\$size" -gt 1048576 ]; then
    echo "      MegaMMR already populated (\$size bytes) - nothing to do"
    exit 0
fi
echo "      MegaMMR is empty (\$size bytes) - downloading $MEGA_SEED"
cd /var/lib/parlons-node
rm -f mega.mmr
wget -q --timeout=60 -O mega.mmr "$MEGA_SEED"
chown maxima:maxima mega.mmr
echo "      downloaded \$(stat -c %s mega.mmr) bytes; importing over loopback RPC :\$RPC_PORT"
for i in \$(seq 1 30); do curl -s -m 5 "http://127.0.0.1:\$RPC_PORT/status" >/dev/null 2>&1 && break; sleep 2; done
out=\$(curl -s -m 3600 "http://127.0.0.1:\$RPC_PORT/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr")
case "\$out" in *'"status":true'*) ;; *) echo "      IMPORT FAILED: \$out" >&2; exit 1 ;; esac
echo "      import finished - restarting (import ends with a node shutdown)"
systemctl restart parlons-node
sleep 20
size=\$(stat -c %s "\$mmr" 2>/dev/null || echo 0)
if [ "\$size" -gt 1048576 ]; then echo "      MegaMMR populated: \$size bytes"; rm -f mega.mmr; else echo "      MegaMMR STILL EMPTY after import (\$size bytes)" >&2; exit 1; fi
REMOTE
    fi
fi

# ---- 7. hand over the details --------------------------------------------
echo "[7/7] node details"
$SSH "bash -s" <<'REMOTE'
set -e
mx=$(journalctl -u parlons-node --no-pager 2>/dev/null | grep -oE 'identity Mx[0-9A-Z]+' | tail -1 | sed 's/identity //')
acct=$(journalctl -u parlons-node --no-pager 2>/dev/null | grep -oE 'account wallet = node wallet: 0x[0-9A-F]+' | tail -1 | grep -oE '0x[0-9A-F]+')
tok=$(cat /var/lib/parlons-node/gateway-token.txt 2>/dev/null || true)
perm=$(journalctl -u parlons-node --no-pager 2>/dev/null | grep -oE 'permanent address MAX#[^ ]+' | tail -1 | sed 's/permanent address //')
devs=$(journalctl -u parlons-node --no-pager 2>/dev/null | grep -oE 'account up: attached to [0-9]+ relay\(s\), [0-9]+ paired' | tail -1 | grep -oE '[0-9]+ paired' | cut -d' ' -f1)

echo
echo "  ------------------------------------------------------------"
echo "   PARLONS NODE"
echo "  ------------------------------------------------------------"
[ -n "$acct" ] && { echo "   Account / node wallet address:"; echo; echo "      $acct"; echo; }
[ -n "$mx" ]   && { echo "   Maxima identity (phones reach the node here):"; echo; echo "      $mx"; echo; }
if [ -n "$perm" ]; then
    echo "   Parlons ACCOUNT address (paste into the Parlons Cloud app):"; echo; echo "      $perm"; echo
    if [ "${devs:-0}" = "0" ]; then
        echo "   No device paired yet. One-time pairing code (consumed on first pair):"
        echo "      cat /var/lib/parlons-node/pair-code.txt"
    else
        echo "   $devs device(s) paired."
    fi
    echo
fi
if [ -n "$tok" ]; then
    echo "   Wallet-gateway bearer token (phones' gateway_url = https://<host>/cmd):"
    echo
    echo "      $tok"
    echo
    echo "   Front the gateway with TLS (Caddy) -> 127.0.0.1:GW_PORT, then point a"
    echo "   phone's WalletPublisher.gateway_url + token at it. See cloud/NODE-SETUP.md."
fi
echo "   Watch it sync:  journalctl -u parlons-node -f | grep heartbeat"
echo "   MegaMMR:        an IBD does NOT carry it - seed it once from another MegaMMR"
echo "                   node (export -> import), see cloud/NODE-SETUP.md 3b. Until then"
echo "                   balance/coins for pre-existing addresses come back EMPTY."
echo "   Seed backup:    ssh <host> 'systemctl stop parlons-node' then read it with"
echo "                   the vault command — the seed is a spendable wallet, guard it."
echo "  ------------------------------------------------------------"
REMOTE
echo "done."
