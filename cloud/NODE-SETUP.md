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

## 4. Seed: fresh vs. migrated (fund-critical — do this by hand)

On first boot the node **generates its own seed** at `/var/lib/parlons-node`. That
seed is the node wallet **and** the Maxima identity — back it up (read it with the
`vault` command over ssh; it is never logged).

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

**Caddy** (recommended), on the same box:

```
wallet.example.com {
    reverse_proxy 127.0.0.1:9585
}
```

Then a phone's `WalletPublisher.gateway_url = https://wallet.example.com/cmd` with the
bearer token printed by the deploy script (`/var/lib/parlons-node/gateway-token.txt`,
owner-only). The token is read+relay only — it cannot move the node's funds — but
keep it off shared channels.

`--gateway-public` exposes the gateway port directly (no TLS) — only for testing.

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
