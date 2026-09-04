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
as a wallet gateway. Seed it from any MegaMMR node you control (export takes a read lock,
no downtime there; import shuts the receiving node down when it finishes):

```bash
# on the DONOR MegaMMR node (its RPC, loopback):
curl 'http://127.0.0.1:9005/megammr%20action:export%20file:/root/donor.megammr'
# copy the file to the new node's data dir (the service can only read under /var/lib/parlons-node):
scp donor:/root/donor.megammr ./ && scp donor.megammr newnode:/var/lib/parlons-node/
ssh newnode 'chown maxima:maxima /var/lib/parlons-node/donor.megammr'
# on the NEW node (deployed with --rpc; its RPC is p2p+4, loopback via the firewall):
ssh newnode "curl 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/donor.megammr'"
ssh newnode 'systemctl restart parlons-node'      # import ends with a clean node shutdown
# prove it: a known funded address must now show its coins
ssh newnode "curl 'http://127.0.0.1:9005/coins%20megammr:true%20address:<Mx…>'"
```

`--rpc` is what makes this (and the seed backup below) possible: the wallet gateway is
read+relay only by design, so the admin RPC is the operator's only channel. It binds every
interface but the deploy script never opens its port in the firewall — keep it that way.

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

## 6. Make the fleet the phone default (last step, after ≥2 nodes are live + synced)

Once two Parlons Nodes are synced and serving `/cmd` over TLS, point the phone
default at them and retire `privateprivate.org`:

- add the nodes' gateway URLs to the phone wallet's default gateway list
- rebuild + release the APK (versionCode + versionName bump — enforced)
- verify a phone reads balance + sends through the fleet with `privateprivate.org`
  blocked, then decommission the old gateway

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
