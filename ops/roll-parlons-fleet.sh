#!/usr/bin/env bash
# Roll ONE Parlons Node jar (and the matching maxima-server jar) across the whole fleet, one box at a
# time, with each box's LIVE flags (recovered from the units on 2026-11-06 - update here when a box changes).
# deploy-parlons-node.sh rewrites the unit from its flags, so a roll with fewer flags silently
# reconfigures a box: that is why the flags live here and not in anyone's head.
#
#   ops/roll-parlons-fleet.sh dist/parlons-node-0.2.150.jar dist/maxima-server-0.4.110.jar [box ...]
#
# Boxes (default: all): vigilance openproject megammr sally hetzner pi maxlite openproject-relay
# Each node box is verified before the next: new MainPID heartbeating "cape=up", symlink on the new jar.
set -euo pipefail
cd "$(dirname "$0")/.."
NODE_JAR="${1:?node jar}"; SERVER_JAR="${2:?server jar}"; shift 2
BOXES=("$@"); [ ${#BOXES[@]} -gt 0 ] || BOXES=(vigilance openproject megammr sally hetzner pi maxlite openproject-relay)
VER="$(basename "$NODE_JAR" | sed -E 's/^parlons-node-//; s/\.jar$//')"

verify_node() {   # <ssh-target> <relay-port> <old-pid>
  local t="$1" port="$2" old="$3" i
  for i in $(seq 1 60); do
    if ssh -o ConnectTimeout=15 -o BatchMode=yes "$t" "P=\$(systemctl show -p MainPID --value parlons-node); [ \"\$P\" != 0 ] && [ \"\$P\" != $old ] && [ \"\$(systemctl is-active parlons-node)\" = active ] && readlink /opt/maxima/parlons-node.jar | grep -q $VER && journalctl -u parlons-node --since '30 min ago' --no-pager | grep \"java\[\$P\]\" | grep -q 'heartbeat.*cape=up:$port'" 2>/dev/null; then
      echo "      VERIFIED $t: $VER heartbeating on :$port"; return 0
    fi
    sleep 20
  done
  echo "      NOT VERIFIED $t after 20 min - stop and look" >&2; return 1
}
roll_node() {   # <box> <ssh-target> <relay-port> <deploy flags...>
  local box="$1" t="$2" port="$3"; shift 3
  echo "==== $box ($t) -> parlons-node $VER"
  local old; old="$(ssh -o ConnectTimeout=15 -o BatchMode=yes "$t" 'systemctl show -p MainPID --value parlons-node')"
  ops/deploy-parlons-node.sh "$t" --jar "$NODE_JAR" "$@" || { echo "deploy script exited non-zero on $box; verifying anyway"; }
  verify_node "$t" "$port" "$old"
}
for box in "${BOXES[@]}"; do case "$box" in
  vigilance)  roll_node vigilance vigilance 9501 --heap 2560m --memmax 3328M --p2p-port 9101 --rpc --relay-port 9501 --peers 95.179.179.181:9501,65.109.31.226:9501,45.77.246.226:9501,78.141.237.9:9501,192.248.151.55:9501 --blobstore 1024 --gateway-port 9585 --no-megammr --rootnode 65.109.31.226:9001 --node-args "-isclient" --keets --dex 9602 --dex-public https://wallet.minima.global/dex-relay ;;
  openproject) roll_node openproject openproject 8001 --heap 2g --memmax 2560M --p2p-port 9001 --rpc --relay-port 8001 --peers 65.109.31.226:9501,95.179.179.181:9501,45.77.246.226:9501,192.248.151.55:9501,45.77.57.24:9501 --blobstore 1024 --gateway-port 9585 --no-megammr --rootnode 65.109.31.226:9001 --keets --dex 9602 --dex-public https://wake.eurobuddha.com/dex-relay ;;
  megammr)    roll_node megammr root@minimammr.com 9501 --heap 3g --memmax 3840M --p2p-port 9101 --rpc --relay-port 9501 --peers 95.179.179.181:9501,65.109.31.226:9501,45.77.246.226:9501,78.141.237.9:9501,45.77.57.24:9501 --blobstore 1024 --gateway-port 9585 --public https://minimammr.com/parlons-node --rootnode 65.109.31.226:9001 --keets --dex 9602 --dex-public https://minimammr.com/dex-relay ;;
  sally)      roll_node sally root@95.179.179.181 9501 --heap 4g --memmax 4864M --p2p-port 9001 --rpc --relay-port 9501 --peers 65.109.31.226:9501,45.77.246.226:9501,78.141.237.9:9501,192.248.151.55:9501,45.77.57.24:9501 --blobstore 1024 --gateway-port 9585 --public https://store.eurobuddha.com/parlons-node --rootnode 65.109.31.226:9001 --keets --dex 9602 --dex-public https://store.eurobuddha.com/dex-relay ;;
  hetzner)    roll_node hetzner hetzner 9501 --heap 6g --memmax 6912M --p2p-port 9101 --rpc --relay-port 9501 --peers 95.179.179.181:9501,45.77.246.226:9501,78.141.237.9:9501,192.248.151.55:9501,45.77.57.24:9501 --blobstore 1024 --gateway-port 9585 --public https://eurobuddha.com/parlons-node --rootnode 45.77.57.24:9001 --keets --dex 9602 --dex-public https://eurobuddha.com/dex-relay ;;
  pi)         roll_node pi pi-old 8001 --heap 3g --memmax 3840M --p2p-port 9001 --rpc --relay-port 8001 --peers 95.179.179.181:9501,65.109.31.226:9501,45.77.246.226:9501,78.141.237.9:9501,192.248.151.55:9501,45.77.57.24:9501 --blobstore 1024 --gateway-port 9585 --no-megammr --rootnode 65.109.31.226:9001 --node-args "-keetsnode /opt/node64/bin-node64" --keets --dex 9602 --dex-public https://minimacore.uk/dex-relay ;;
  maxlite)    echo "==== maxlite -> maxima-server $(basename "$SERVER_JAR")"; ops/deploy-relay.sh maxlite --jar "$SERVER_JAR" --port 9501 --heap 96m --blob 1024 --peers 95.179.179.181:9501,65.109.31.226:9501,78.141.237.9:9501,45.77.57.24:9501,192.248.151.55:9501,31.125.188.214:8001 --dex-port 9602 --dex-public https://relay.privateprivate.org/dex-relay ;;
  openproject-relay) echo "==== openproject:9501 -> maxima-server $(basename "$SERVER_JAR")"; ops/deploy-relay.sh openproject --jar "$SERVER_JAR" --port 9501 --heap 96m --blob 1024 --peers 95.179.179.181:9501,65.109.31.226:9501,45.77.246.226:9501,45.77.57.24:9501,192.248.151.55:9501,31.125.188.214:8001 ;;
  *) echo "unknown box $box" >&2; exit 1 ;;
esac; done
echo "fleet roll done."
