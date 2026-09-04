# Parlons Node — VPS setup

A **Parlons Node** is the merged binary: a full Minima node (`org.minima`, layer-1
validate + P2P) wearing the Parlons cape — the clean-room `:core`/`:server` Maxima
relay **and** a hardened read+relay wallet gateway for phones — all in one JVM,
driven from **one seed**. Running MegaMMR, it serves its own wallet from its own
chain (no `privateprivate.org`) and can be the wallet gateway for any phone. As the
fleet grows, that gateway load spreads across every node — this is what replaces the
single hosted gateway.

Built as `parlons-node.jar` (`./gradlew :node:fatJar`). Deploy with
`ops/deploy-parlons-node.sh`.

## 1. Build the jar

```bash
cd ~/Projects/minima/maxima
./gradlew :node:fatJar
# -> node/build/libs/parlons-node.jar  (self-contained, ~14 MB)
```

Optionally stamp a version into the name for the deploy history:
`cp node/build/libs/parlons-node.jar dist/parlons-node-<ver>.jar`.

## 2. Size the box

MegaMMR + the chain DB is heavier than the old relay-only cloud node.

| role | RAM | disk | heap flag |
|---|---|---|---|
| plain full node (`--no-megammr`) | 1 GB | a few GB | `--heap 768m` |
| **gateway node (MegaMMR, default)** | **2–4 GB** | **grows with the chain** | `--heap 2g` |

The heap is a hard ceiling on purpose — an unbounded default heap is how you lose a
small VPS. `MemoryMax` in the unit sits just above the heap so a burst can't OOM the
whole box.

## 3. Give it a sync peer

This node fork ships an **empty** default peer list, so it will sit at block 0 until
you point it at a reachable Minima node:

```bash
ops/deploy-parlons-node.sh root@1.2.3.4 --rootnode 65.109.31.226:9001
```

`--rootnode host:port` seeds P2P discovery (the node then finds more peers itself).
Watch it climb: `journalctl -u parlons-node -f | grep heartbeat`.

If the box's 9001 is already a stock Minima node, move the layer-1 port with
`--p2p-port 9101` (the derived RPC port follows it: p2p + 4).

## 3b. Seed the MegaMMR (once — an IBD does NOT carry it)

A fresh `-megammr` node syncs the chain from its peer but its MegaMMR starts **empty**
(`databases/megammr.mmr` is 20 bytes) — it only tracks coins created from now on, so
`balance`/`coins megammr:true address:<existing>` come back EMPTY and the node is useless
as a wallet gateway. Seed it once from the nightly snapshot published at
`https://eurobuddha.com/mega.mmr` (~460 MB, rebuilt 02:00 UTC by the Hetzner node).
The deploy script does the whole thing when you pass the URL:

```bash
ops/deploy-parlons-node.sh root@1.2.3.4 --rootnode <peer:9001> --rpc \
    --megammr-seed https://eurobuddha.com/mega.mmr
```

It downloads into `/var/lib/parlons-node`, runs `megammr action:import file:…` over the
node's loopback RPC (which is why `--rpc` is required), restarts the service (import ends
with a clean node shutdown) and proves `megammr.mmr` is no longer empty. It is skipped
when the MegaMMR is already populated, so leaving the flag on a re-run is harmless.

By hand, on the box:

```bash
cd /var/lib/parlons-node && wget -q https://eurobuddha.com/mega.mmr && chown maxima:maxima mega.mmr
curl 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
systemctl restart parlons-node
# prove it: a known funded address must now show its coins
curl 'http://127.0.0.1:9005/coins%20megammr:true%20address:<Mx…>'
```

(No snapshot at hand? Any MegaMMR node you control can produce one:
`megammr action:export file:/root/donor.megammr` over its RPC — a read lock, no downtime —
then copy that file in and import it the same way.)

`--rpc` is what makes this (and the seed backup below) possible: the wallet gateway is
read+relay only by design, so the admin RPC is the operator's only channel. It binds every
interface but the deploy script never opens its port in the firewall — keep it that way.

## 3c. Replace a box's relay with the node — keeping its identity

Every fleet box already runs `maxima-server.jar` with a seed at `/var/lib/maxima/seed.txt`,
and clients have that relay's identity pinned (`Mx…@host:port`). The node can TAKE OVER that
role on the SAME identity — the deploy script does the whole thing:

```bash
ops/deploy-parlons-node.sh <box> --rpc --replace-relay --relay-port 9501 \
    --peers "<the other six, host:port>" --blobstore 1024 \
    --seed-from /var/lib/maxima/seed.txt --archive 65.109.31.226:9001 \
    [--megammr-seed https://eurobuddha.com/mega.mmr] [--p2p-port 9101]
```

`--replace-relay` stops + disables `maxima-relay.service` (unit kept: rollback is
`systemctl enable --now maxima-relay`). `--seed-from` makes the node adopt the relay's
24-word phrase over the loopback admin RPC (`megammrsync action:resync host:<--archive>
phrase:…` — resets the wallet keys to the phrase, pulls its coins from an archive/MegaMMR
node, then the node shuts down and is restarted); the phrase never leaves the box and is
never printed — the script only reports whether the vault matches the file. Because the
Maxima identity is derived from the phrase the same way on both sides, the node's relay
comes up as the SAME `Mx…` the old relay had. `--p2p-port 9101` where 9001 is already a
stock node.

The same flag migrates a **Parlons Cloud account** into the node: stop `parlons-cloud`,
copy `node/ chat/ media/ devices.json cloud-settings.properties` from its data dir into
`/var/lib/parlons-node/`, then deploy with `--seed-from <its seed.txt>`. Same permanent
`MAX#`, same paired devices, same chats. The WALLET address changes (the node uses its
default key, the cloud used key #1000 of the same phrase) — anything at the old address is
still yours: the phrase derives it in any Parlons wallet.

## 3d. The account (M5)

Since node 0.2.0 every Parlons Node also runs the Parlons **account** — the exact
`ParlonsCore` that `parlons-cloud.jar` runs — on the node's identity, with the node's own
wallet behind it and the cape as its relay. So a Parlons Node is pairable from the Parlons
Cloud app: the deploy summary prints the permanent account address and where the one-time
pairing code lives (`/var/lib/parlons-node/pair-code.txt`). Knobs: `-Dparlons.account=false`
(relay/gateway only), `-Dparlons.account.name`, `-Dparlons.account.relays`,
`-Dparlons.account.direct`. Data layout matches the cloud's, which is what makes the
migration above a plain copy.

## 4. Seed: fresh vs. migrated (fund-critical — do this by hand)

On first boot the node **generates its own seed** at `/var/lib/parlons-node`. That
seed is the node wallet **and** the Maxima identity — back it up (read it with the
`vault` command over the loopback RPC on the box — `curl http://127.0.0.1:9005/vault` —
which needs `--rpc`; it is never logged).

To run this node on an **existing account seed** (e.g. migrate the sally account so
its address is unchanged), do a one-time restore **before** it syncs — this is manual
because it moves funds' custody:

```bash
ssh <host> 'systemctl stop parlons-node'
# start the node once interactively (or attach to its RPC) and run:
#   vault action:restorekeys phrase:"WORD1 WORD2 … WORD24"
# then let it resync from the peer, and confirm the account address is the old one.
ssh <host> 'systemctl start parlons-node'
```

The account has never signed (key-uses 0) → no reuse risk on the first send. Verify
`balance` shows the funds and the address matches before trusting it.

## 5. The wallet gateway (phones)

The node exposes a hardened `POST /cmd` proxy — the server side of the phone's
`GatewayNode`. It is an **allow-list**: only non-admin reads, track-only commands and
relay of a phone's **pre-signed** txn pass; `send`/`vault`/`keys`/`sign`/`quit` are
refused. It binds **loopback by default** — put TLS in front.

**Caddy** (recommended), on the same box — **include the `rate_limit` block**. (On a box
that already runs **apache**, the equivalent is a `<Location "/parlons-node/cmd">` with
`ProxyPass http://127.0.0.1:9585/cmd` inside an existing TLS vhost — that is how sally
serves `https://store.eurobuddha.com/parlons-node/cmd`; the node's own token buckets are
the rate limit there.) The `rate_limit` block matters because behind a
loopback front every request looks like `127.0.0.1` to the node, so per-IP throttling
there is moot; Caddy is where real per-client limiting happens (the node keeps a global
backstop, below):

```
wallet.example.com {
    rate_limit {
        zone phones {
            key    {remote_host}
            events 60
            window 1m
        }
    }
    reverse_proxy 127.0.0.1:9585
}
```

Then a phone's `WalletPublisher.gateway_url = https://wallet.example.com/cmd` with the
bearer token printed by the deploy script (`/var/lib/parlons-node/gateway-token.txt`,
owner-only). The token is read+relay only — it cannot move the node's funds — but
keep it off shared channels.

The node ALSO rate-limits itself: a **global** token bucket (the backstop that works
even when all traffic is loopback) plus a **per-IP** bucket (meaningful only with
`--gateway-public`). Tune with `-Dparlons.gateway.rate.global=<req/sec>` (default 50)
and `-Dparlons.gateway.rate.perip=<req/sec>` (default 10); `0` disables either.

`--gateway-public` exposes the gateway port directly (no TLS) — only for testing.

### Locked node → supply the passphrase

The cape + wallet + gateway derive from the node's seed, so a **password-locked** node
must be unlocked or they never start (the node itself keeps running; the journal says
so). Supply the passphrase out-of-band — never on the command line (argv is world-
readable via `ps`). Easiest is the deploy flag:

```bash
# put the passphrase (one line) in a file ON THE VPS first, e.g.:
#   /var/lib/parlons-node/passphrase.txt
ops/deploy-parlons-node.sh root@1.2.3.4 --rootnode <peer:9001> \
    --passphrase-file /var/lib/parlons-node/passphrase.txt
```

The script secures the file (`maxima:maxima`, mode 600) and wires
`-Dparlons.node.passphrase.file` into the unit. **Keep the file under
`/var/lib/parlons-node` or `/etc`** — the sandbox's `ProtectHome=true` makes
`/home` and `/root` unreadable to the service. Equivalent manual options: a systemd
`EnvironmentFile` (mode 600) setting `PARLONS_NODE_PASSPHRASE=…`, or the same `-D`
flag by hand.

The node unlocks once on boot (`vault action:passwordunlock`). A node started unlocked
needs none of this. (The passphrase can't contain spaces/quotes/`;` — the `vault`
command can't parse those anyway.)

## 6. The fleet as the phone default — DONE (Parlons 0.6.49, 2026-09-04)

`WalletPublisher.FLEET_GATEWAY_URLS` lists the MegaMMR Parlons Nodes behind TLS, tried in
order with automatic failover (`GatewayNode`): a transport failure or a 5xx moves to the next
node; a publish pins ONE node for its whole txnimport → txnbasics → txnpost. All fleet
gateways share one read+relay token (`/var/lib/parlons-node/gateway-token.txt`, identical on
every gateway box — a new gateway node gets that file, not a fresh one). The old hosted proxy
(`relay.privateprivate.org/cmd` on maxlite) is still up for pre-0.6.49 phones. Parlons Desktop
1.5.34 uses the same fleet list + failover (`DesktopWalletPublisher`); FreezePeach is deprecated.

### The fleet (2026-09-04)

| box | node | p2p | relay | MegaMMR gateway | account |
|---|---|---|---|---|---|
| sally 95.179.179.181 | 0.2.0 | 9001 | 9501 | `https://store.eurobuddha.com/parlons-node/cmd` | the eurobuddhaCloud account (migrated from parlons-cloud, same MAX#) |
| eurobuddha 65.109.31.226 | 0.2.0 | 9101 | 9501 | `https://eurobuddha.com/parlons-node/cmd` | fresh (pairable) |
| megammr 192.248.151.55 | 0.2.0 | 9101 | 9501 | loopback only (no TLS front yet) | fresh |
| vigilance 45.77.57.24 | 0.2.0 | 9101 | 9501 | none (`--no-megammr`: 8 GB box shared with the WOTS MegaMMR node) | fresh |
| the Pi 31.125.188.214 | 0.2.0 | 9001 (not port-forwarded; outbound sync only) | 8001 | none (`--no-megammr`: 32-bit JVM) | fresh |
| maxima-lite 45.77.246.226 | maxima-relay 0.4.33 (unchanged) | — | 9501 | hosts the legacy proxy | — |
| openproject 78.141.237.9 | maxima-relay 0.4.33 (unchanged) | — | 9501 | — | — |

Every node's relay kept its old relay identity (`--seed-from` the relay's seed.txt), so nothing
pinned to the fleet changed. maxima-lite and openproject stay on the plain relay: both have
under 800 MB free and maxima-lite hosts the legacy gateway that older phones still use.
Note: 9101 on the two Vultr boxes (megammr, vigilance) still needs an INBOUND rule in the Vultr
console — they sync fine outbound-only meanwhile.

## Ports

| port | what | exposure |
|---|---|---|
| 9001 | Minima P2P | public (peers) |
| 9005 | node RPC | **loopback only — never expose** (full admin) |
| 9501 | Maxima relay | public (phones attach) |
| 9585 | wallet gateway `/cmd` | loopback → TLS front (or `--gateway-public` for testing) |

## Rollback

```bash
ssh <host> 'cd /opt/maxima && ln -sf parlons-node-<oldver>.jar parlons-node.jar && systemctl restart parlons-node'
```
