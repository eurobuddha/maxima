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

## 3e. Minima's own startup flags (node 0.2.1+)

The node takes Minima's flags — the same ones a stock `minima.jar` takes — through Minima's own
parser, via `--node-args` (deploy script) = `-Dparlons.node.args="…"` (or env `PARLONS_NODE_ARGS`):

```bash
ops/deploy-parlons-node.sh root@1.2.3.4 --rpc --node-args "-port 9111 -host 1.2.3.4 -archive"
```

Precedence: Minima owns what it parses (`-port`, `-data`, `-host`, `-megammr`, `-archive`,
`-connect`, `-nop2p`, `-server`, …); the node's own knobs (`--p2p-port`, `--no-megammr`,
`--rootnode`, `-Dparlons.node.*`) are applied afterwards and win only when set explicitly. The
admin RPC port always follows the effective P2P port (+4). `-conf FILE` is expanded (its
`key=value` lines become flags) and filtered the same way. A `-data <dir>` is added to the
hardened unit's `ReadWritePaths` by the script.

Refused at boot (the node prints why and exits 2):

| flags | why |
|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | the stock RPC binds every interface with full admin; use `--rpc` (loopback AdminRpc) |
| `-seed -anyseed -dbpassword` | secrets in argv are readable by every process; use `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | wipe the data dir or leave mainnet |
| `-daemon -noshutdownhook -jnlp -help` | stdin / exit / shutdown-hook behaviour the merged JVM owns |

The MDS flags (`-mdsenable` …) are accepted but do nothing: this fork has no MDS package. The
node warns at boot.

## 3f. Identity is pinned; the wallet can be resynced (node 0.2.4+)

The account's Maxima identity (its `MAX#`, what devices pair to and contacts know) is derived from
the phrase in `/var/lib/parlons-node/identity.txt`, written ONCE from the vault on the first
0.2.4 boot (or from `--seed-from`). The node WALLET stays on the vault. So the wallet can be
re-pointed at a NEW 24-word phrase — when its one-time-signature keys are used up, or funds
should live on a fresh seed — and the identity, paired devices and contacts all survive:

- from a paired device: Parlons Cloud → Wallet → "Resync wallet to a new phrase…", or the CLI
  `parlons wallet resync <file-with-24-words>` (RPC `parlons.wallet.resync`);
- the node runs `megammrsync action:resync host:<-Dparlons.node.archive, default
  65.109.31.226:9001> phrase:…`, then exits 3 and systemd restarts it (`RestartForceExitStatus=3`). Since 0.2.7 it exits 3 ONLY
  when `megammrsync` reported success; on failure the node stays up, the wallet is untouched and
  the reason is published as `resyncError` in `parlons.wallet.address` — a red line on the
  portal wallet card, `wallet address` on the CLI. The resync button is only offered where
  `canResync` is true (node accounts). Note `getaddress` rotates among the wallet's 64 default
  addresses, so the "own address" a node account shows changes per boot — all 64 are the
  wallet's.
  About a minute; devices reconnect on their own.
- Funds at the OLD phrase's addresses stay with the old phrase (import it in any Parlons wallet).
- To re-pin the identity to whatever the vault holds: delete `identity.txt` and restart.
`parlons.seed.reveal` / backups reveal the IDENTITY phrase; the wallet phrase is read with
`vault` over the admin RPC on the box.

### 3g. Terminal IDE in Parlons Cloud (node 0.2.5, cloud 0.11.3, portal 0.30.0)

Parlons Cloud → Node tab → **Open Terminal IDE**: the Terminal IDE companion app (Terminal /
Scripts / Txn / Logs) ported whole into the portal, with every command running on the ACCOUNT's
Parlons Node instead of a phone-local Minima Core. Same on the Mac: `parlons cmd <any node command>`.

- RPC `parlons.node.cmd` (paired devices only — the account's own DevicePairing auth, end-to-end
  encrypted like every other control method). The command runs on the node's `parlons-console`
  lane, never on the inbound reader: the RPC waits 2.5 s, then replies `pending:true` + `key`
  and the device polls with `{key}` until it finishes (so a long `send` / `megammrsync` cannot
  deafen the node). Output is paged out in 120 000-char pieces (`{key, offset}`) under the 256K
  Maxima package ceiling and stitched back by `ParlonsRemote.nodeCmd` — a 2 MB `printtree` comes
  back complete, never truncated. One command may hold at most 16 MB of output in the node's
  heap (`CMD_MAX_OUTPUT`; over it the job returns an error — a MegaMMR node's
  `coins relevant:false` would otherwise OOM a 3g box); the text is released once the device
  has fetched the last page, and a running job is never evicted. `quit` is refused (restart from the box). Each command is
  logged as `terminal: <command word> (paired device)` in the node log (the IDE's Logs tab).
- parlons-cloud (no embedded node) answers `this account runs on parlons-cloud… the Terminal
  needs a Parlons Node`.
- Guard rails from the Terminal IDE app still apply client-side (unbounded `coins`, oversized
  `history` pages).

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
| sally 95.179.179.181 (8 GB) | 0.2.11, heap 4g | 9001 | 9501 | `https://store.eurobuddha.com/parlons-node/cmd` | the eurobuddhaCloud account (migrated from parlons-cloud, same MAX#) |
| eurobuddha 65.109.31.226 (64 GB) | 0.2.11, heap 6g | 9101 | 9501 | `https://eurobuddha.com/parlons-node/cmd` | fresh (pairable) |
| megammr 192.248.151.55 (32 GB) | 0.2.11, heap 3g | 9101 | 9501 | `https://minimammr.com/parlons-node/cmd` | fresh |
| vigilance 45.77.57.24 (8 GB, shared) | 0.2.11, heap 2560m, `-isclient` (no inbound peers) | 9101 | 9501 | none (`--no-megammr`: box shared with the WOTS MegaMMR node) | fresh |
| the Pi 31.125.188.214 (16 GB, 32-bit JVM) | 0.2.11, heap 3g | 9001 (not port-forwarded; outbound sync only) | 8001 | none (`--no-megammr`) | fresh |
| maxima-lite 45.77.246.226 | maxima-relay 0.4.36 | — | 9501 | hosts the legacy proxy | — |
| openproject 78.141.237.9 | maxima-relay 0.4.36 | — | 9501 | — | — |

Every node's relay kept its old relay identity (`--seed-from` the relay's seed.txt), so nothing
pinned to the fleet changed. maxima-lite and openproject stay on the plain relay: both have
under 800 MB free and maxima-lite hosts the legacy gateway that older phones still use.
Sizing, learned the hard way: a MegaMMR Parlons Node needs a **3g heap on an 8 GB box at
minimum** (sally at 2g and 2560m OOM-looped and stalled; hetzner at 3g OOM-crashed and systemd's
start limit left it DOWN); relay-only nodes need ~2.5–3g of headroom to serve IBDs to peers. A
node that crash-loops trips `StartLimitBurst` and stays down — check `systemctl is-active
parlons-node` after any heap change. 9101 is open in the Vultr console on megammr + vigilance.

### 3h. The cape is the node's public door (node 0.2.6, portal 0.31.0)

An account keeps `RELAY_TARGET = 2` relays attached and scores start equal at boot, so a node's
account used to fill its two slots with whichever relays answered first — sally spent an evening
advertising `@45.77.246.226:9501` (Maxima-Lite) while its own cape sat idle, and the control
panel's "Directly reachable: no" (the account's 9536 direct listener, off on nodes) read like a
fault. Since 0.2.6 a Parlons Node **prefers its own cape**: `ParlonsCore.Config.ownRelay` =
`<public host>:<RELAY_PORT>` (host from `-Dparlons.relay.host`, else what the Minima node
detected for itself in `status` → network.host; private/loopback hosts are skipped with a log
line). The pool (`HostPool.setPreferred`) attaches it first, never evicts it by merit (fill drops
the worst OTHER host to make room), lists it first in the advertised contact addresses and in the
score order the MLS anchor is picked from — so the permanent address ends in the node's own
`@host:9501`. The second slot is filled from the fleet by merit as before.

The panel/CLI replace "Directly reachable" on nodes with **Public relay `<host:port>`** and a
**Relay check**: `verified — relays to this node` once the account's self-addressed check-connect
has come back through the cape (`ownRelayVerified` in `parlons.node.figures`), else "attached,
verifying…" / "not attached". Cloud accounts (no cape) keep the direct-port rows. The direct-port
(9536) machinery is untouched for phones/desktops in Parlons proper, where a device may genuinely
act as a relay.

### 3i. NFT / token-art hosting on the node (node 0.2.8, cloud 0.11.6, portal 0.34.0)

The files a token's metadata links to can live ON the node and be served by its public TLS
front, so a marketplace or explorer links straight to your box:

- **Store**: `<data>/nft/` — single files CONTENT-ADDRESSED as `<sha256>.<ext>` (the URL never
  changes, anyone can verify the bytes against the hash the token carries); State-NFT
  collections as `c/<16-hex id>/<index>.<ext>` (1-based, the stamp index) + a `manifest.json`
  of per-item sha256s. 32 MB per file max.
- **Serve**: gateway `GET|HEAD /nft/<path>` — public, no token, strict path shapes (nothing else
  on disk is addressable), `Cache-Control: immutable`, nosniff, CORS `*`, and since 0.2.9 a
  `Content-Security-Policy: default-src 'none'; sandbox` + `Content-Disposition: inline` so an
  SVG navigated to directly cannot run script in the operator's web origin (the wallet also runs
  `SvgSanitizer` on SVG before uploading). `/nft` has its own rate budget
  (`parlons.gateway.rate.nft.global` 400/s, `.perip` 40/s) so hot-linked art never spends the
  wallet `/cmd` buckets. Abandoned upload parts are pruned after 24 h; the client retries a
  chunk once (offset-idempotent). The TLS front needs a
  second `<Location "/parlons-node/nft">` ProxyPass to `127.0.0.1:9585/nft` (GET/HEAD only) next
  to the `/cmd` one — added on sally, hetzner and megammr; put it in the Apache snippet on any
  new gateway box.
- **Public base**: `-Dparlons.node.public=https://host/parlons-node` (deploy script
  `--public URL`). Without it uploads still land (`path`), but `url` comes back "" and the
  wallet tells the user to ask the operator.
- **Upload**: paired devices only, over the control channel: `parlons.nft.put` (chunked,
  offset-idempotent, sha256-verified before the file is placed), `parlons.nft.newcollection`,
  `parlons.nft.list`, `parlons.nft.delete`; `nftBase` rides `parlons.node.figures`. CLI:
  `parlons nft put <file> [collection idx] | list | newcollection | delete <path>`.
- **Wallet (portal 0.34.0+)**: Mint → NFT in URL mode: "Upload the image to my node instead…".
  (Since 0.35.0 the wallet does NOT offer State-NFT collection minting — user's call, it needs
  atelier-level tooling; State NFTs are still viewed, sent and received in the Gallery. The
  collection upload path `newcollection` + `c/<id>/<n>.<ext>` stays in the node and the CLI.)
  Every upload is audited in the node log.

### Relay per-source cap (server 0.4.36)
A relay counts concurrent connections per SOURCE IP: a Parlons Node + phones + a desktop behind
one home NAT are one source. The cap is now 32 (was 16; `--maxpersource N` /
`-Dmaxima.relay.maxpersource`). An UNREGISTERED connection (never registered a route) is reaped
after 10 min even if it keeps sending frames - a classic node's P2P socket to a relay only pings
and never registers, and minimaDesk used to open one per 15-min heal (`connect host:` without
checking it was already attached; fixed in minimaDesk 0.7.13) until the cap refused the whole
household ("refused (per-source cap)"). Registered clients are still never reaped on idle.

### Relay discovery, classic Minima's way (core; app 0.6.57, portal 0.2.2, node 0.2.12, server 0.4.37)
Phones, accounts and desktops now find relays the way a classic node finds peers "out in the wild"
(`P2PPeersChecker` + `P2PManager`, ported as `core/.../session/PeerDiscovery.java`):

- **verify before adopt** - a peer listed in any greeting is dialled on a fresh socket and only a
  live `"welcome":"Maxima"` greeting promotes it to the verified list;
- **250-peer list, kept turning over** - once full, one newcomer in ten is considered and a random
  verified peer makes room (classic's admission rule), never a small "learned" cap (the old cap of 8
  pinned every phone to the 7 bootstrap relays);
- **a failed verified peer is rechecked in 30 min, then dropped**; the whole list is rechecked every
  6 h; a check with no network is deferred 60 s;
- **saved every 10 min and on shutdown** (store collection `peers`), but never when the list has
  shrunk below half its loaded size (an outage cannot overwrite a good list);
- **connect at RANDOM** - `HostPool.fill()` draws candidates at random like classic
  `P2P_RANDOM_CONNECT`; merit score still orders the ATTACHED hosts for the MLS anchor. Random draw
  is what spreads a growing population over a growing fleet (1 node per 20 phones);
- **three strikes** - a peer that fails three connects running is forgotten (`P2P_NOCONNECT`);
  the bootstrap floor and a node's own cape are never forgotten.

Relays share a SHUFFLED list of up to 50 verified peers PLUS THEMSELVES in every greeting
(classic `P2PGreeting`), hold up to 250, and learn peers from the greetings of relays they dial.
The greeting also carries `conns` (current clients) next to `cap`. A Parlons Node's cape joins the
mesh through the bootstrap list when `--peers` is not given.

### Stage-1 scalability hardening (2026-09-05: server 0.4.39→0.4.45, node 0.2.14→0.2.20, app 0.6.63)
From the scalability survey (P0 items). All rolled fleet-wide the same night.
- **Relay accept loop cannot die**: admission is guarded (`catch Throwable`, counted as
  `acceptfail=` in the stats line), a dead accept thread is restarted by maintain() and shows as
  `ACCEPT=DEAD`. Units: `TasksMax=1024`, `LimitNOFILE=65536` (node: `parlons-node.service.d/limits.conf`).
- **Stalled writers reaped**: a socket write blocked > 60 s (`stalls=`) is closed; keep-alives and
  drains run on an 8-thread push pool (`pushdrop=` counts refusals), never on the maintain thread.
- **Mailbox is one file per held item** (`<data>/relaystore/mailitems.d/`), ciphertext read only on
  delivery, global cap 50 000 items; the old `mailbox.tsv` records are migrated once at boot
  (`[mailbox] migrated N held item(s)` in the journal - check it equals `mail=`).
- **Directory cap sized for the heap** (6 144 entries on a 96 MB relay; 2 000..200 000); pool
  relays no longer retain reader lists.
- **Chat stores coalesce writes** (2 s) on the app/account/desktop built-in engine and are flushed
  before any mailbox ack is signed; the account flushes chat state every 60 s.
- **Own published media is pinned** on the local shelf (`media/pinned/`), never evicted.

### Wallet gateways are discovered with the relays (node 0.2.21, app 0.6.64, desktop 1.5.44)
A MegaMMR Parlons Node with a public base (`--public https://host/parlons-node`) advertises its
gateway in its cape's greeting (`"gw"`: the `/cmd` URL, `"gwkey"`: its bearer). Phones and desktops
keep one gateway per VERIFIED relay (persisted with the peer list) and build their fleet as
discovered gateways + the two compiled-in URLs, shuffled once per wallet session, so a population
spreads over every gateway instead of all starting on sally. A user-configured node is unchanged.
Boot log line: `[parlons-node] wallet gateway advertised to phones: <url>`. Not advertised by
`--no-megammr` nodes (vigilance, the Pi) or plain relays. Verify from outside with the greeting probe
(scratchpad `Gw.java` pattern: `Greeting.gatewayOf/gatewayKeyOf`) and a `block` POST with the key.
Trust model: a discovered gateway is a fleet node's, like a relay; signing never leaves the device,
so the worst a bad one can do is a wrong read or a dropped relay, which failover corrects.

### Stage-2 throughput (2026-09-06: server 0.4.49→0.4.53, node 0.2.24→0.2.28, app 0.6.71, portal 0.2.16)
Every item was checked against the decentralization rule (no central point of control/failure,
no steering, no new hosted dependency): all are local scheduling, memory or reply-sizing changes,
plus one advisory relay message.
- **Load shedding, advisory** (`--shed N` / `-Dmaxima.relay.shed`, default 384, 0 = off): over its
  soft client target a relay sends `CTRL_SHED` (42) to ≤4 clients per tick, each ≤ once per 30 min.
  The message names NO destination: the client draws its own replacement at random from relays it
  verified, never leaves its preferred cape, honours a relay ≤ once per 30 min, and only detaches after
  the replacement attached. (Classic's DoSwap names the target — that lets a relay steer clients, so
  it was not ported.) Stats line: `sheds=`.
- **Inbound lock narrowed**: `MaximaNode.handle` holds its lock for dedup + last-seen only; chat and
  contact-ctrl run in order on an inbound lane, RPC on its own lane, the reciprocal introduce on a side
  lane; the before-ack hook drains the inbound lane first. The account's send thread is now keyed lanes
  (`SerialLanes`: per peer / "wallet" / "group" / "mls", 4 threads). `node.cmd` leash 2.5 s → 300 ms.
- **Chat history loads off the main thread** (`ChatEngine.setStoreAsync`); per-message group bookkeeping
  is allocated lazily.
- **Contacts refresh** 4 at a time under a 90 s budget with exponential backoff per dead contact;
  `parlons.contacts.list` / `parlons.chat.summaries` are **paged** (offset/limit, `more`/`next`,
  250 / 200 per page) and `ParlonsRemote` fetches every page; older nodes/clients unchanged.
- **Push fan-out**: 4 s / 6 s socket leashes, an address failing 3 pushes running is skipped until the
  device's next RPC, pool 4→16 under load, state ticks coalesced per entry per 400 ms; RPC replies on a
  bounded pool.
- **Knobs**: `--maxconn N` (relay) / `-Dparlons.relay.maxconn` (cape) raise the 512-connection cap on big
  boxes; `-Dparlons.gateway.threads` (default 8). Parsed RSA public keys are cached (LRU 1024).

### Directory replication + anchor failover (server 0.4.54, node 0.2.29, app 0.6.72)
A pool relay that accepts a signed directory SET now pushes it (`MSG_DIR_PUBLISH` 202, the same
proof triplet `DIR_ANSWER` carries) to `--replicate N` random verified pool peers (default 3,
`-Dmaxima.relay.replicate`, 0 = off). Every receiver re-verifies the signature and the signer/from
binding before storing; a replica is never pushed a second hop; a replica never overwrites the relay's
own live copy from the publisher. So a permanent `MAX#` resolves on relays its owner never touched,
and keeps resolving while its anchor is down. The mesh pull on a miss now fans out in PARALLEL
(first verified answer wins, inside the client's 5 s leash); clients dial a MAX#'s anchor with the
5 s self-heal leash instead of 20 s + 20 s; the account attaches to 3 relays (own cape + 2). Stats
line: `dirrep=sent/stored`. Decentralization: no new trusted party — a relay can withhold, never
forge; copies go to random peers, not a designated set. Verify: `MeshReplicateTest`; live: stop a
node for 2 min and resolve its MAX# via another relay.

### The iOS wake path and catch-up (node 0.2.39, cloud 0.11.35, portal 0.2.27; wake proxy 0.1.0)
Parlons Cloud for iOS (`support/parlons-ios`) is a paired device like the Android portal, but iOS
kills a backgrounded socket, so three account-side additions carry it:
- `parlons.push.register {live:false}` - the device says "going dark"; its 3-minute live window
  ends now, so the next message wakes it instead of being dialled into a relay mailbox.
- `parlons.push.apns {token, env, proxy}` - the device's APNs token and the wake proxy IT chose
  ("" none, "off" never, or an https URL; stored with the pairing in `devices.json`, dropped on
  revoke; `parlons.settings.get` reports it as `push`). When a `message` or `call` arrives and
  the device is not live (or every live address failed), the account POSTs a bare
  `{token, env, kind}` to that proxy (`WakeProxyClient`: one wake per device per 20 s, then quiet
  until the device's next RPC or 5 min; a proxy failing three times is left alone 5 min). No
  content, no sender, nothing but "wake" ever leaves the account.
- `parlons.chat.since {cursor, limit, offset}` - every conversation's entries newer than the
  cursor, ordered by newness (the later of `time` and `arrived`, so a late-relayed message is not
  skipped), paged; the reply's `cursor` is what the device keeps. One RPC replaces N
  `conversation(after)` calls on resume and in the notification extension.
**The wake proxy** (`wakeproxy/`, `dist/parlons-wake-<ver>.jar`): stateless, holds the publisher's
APNs `.p8` in memory, sends a content-free alert (`"New message"` / `"Incoming call"`,
`mutable-content:1` so the app's extension fetches the real message E2E and rewrites the banner),
per-token rate limits (1 per 10 s, 60 per hour, 3000/min global), never logs a token (an 8-hex
SHA-256 prefix only). Deploy: `ops/deploy-wake-proxy.sh <box> --key AuthKey.p8 --key-id .. --team-id
Z4JD286WF4 --bundle com.eurobuddha.parlons` (loopback bind, Caddy in front), verify with
`ops/verify-wake-proxy.sh https://wake.<domain>`. Decentralization: the proxy is OPTIONAL (off =
BGAppRefresh polling), REPLACEABLE (anyone building the app with their own bundle id and `.p8`
runs their own), and blind (a token and a timestamp); the account, the relays and the message path
are unchanged. Honest limit: Apple binds push keys to the publisher, so the App Store binary's
wake path is publisher-run or off. Verify: `WakeProxyClientTest`, `DevicePairingApnsTest`,
`WakeProxyTest`; live: `pkinterop watch` on a Mac paired to an account receives pushes E2E.

### Portable accounts, then an optional multi-account host (node 0.2.36, cloud 0.11.32, portal 0.2.24)
**The portable account bundle.** `Back up account…` on a paired device (or `parlons backup
<file.pbk>`) now writes the WHOLE account, encrypted under your passphrase (scrypt + AES-GCM, the
same `.pbk` container the phone app reads): the identity phrase, the paired devices
(`devices.json`), the host settings (`cloud-settings.properties`), every collection and log of the
node store (contacts, settings, remembered relays, address history) and of the chat store (messages,
groups, read state, wallet notes), plus the v1 fields every older reader knows - so the phone app's
restore still takes it (identity + contacts), and an old v1 backup still restores here. Restore is
OFFLINE and CLI-only by design (a paired device must never be able to swap the account from under
the others): `java -jar parlons-cloud.jar --restore backup.pbk` on a server, or
`java -Dparlons.restore=backup.pbk -jar parlons-node.jar` on a Parlons Node (writes `identity.txt`;
the node's WALLET stays its own vault - resync it to the old phrase from a paired device if the funds
should follow). The identity file is written LAST and never overwritten; a fresh data dir is
required. Because the identity is the same, the MAX# is the same: paired devices reconnect without
re-pairing (the old anchor is down, the fleet's replicated directory - Stage-3 item 1 - resolves
the key at its new home). One identity, one live account: stop the old host for good.
**The multi-account host** (optional, self-hostable, replaceable): `parlons-cloud --tenants <dir>`
runs every `<dir>/<name>/` account - the same layout a bundle restores into - in one process,
sharing one pool relay (the first tenant's `--relay-port`) and one Tier-2 listener. Seeds can be
kept ENCRYPTED AT REST: `--tenants <dir> --unlock prompt --encrypt-seeds` turns each `seed.txt`
into `seed.enc` (verified, plaintext deleted); from then on start with `--unlock prompt` (console)
or `--unlock env` (`PARLONS_UNLOCK`, for systemd). Every tenant keeps its own identity, devices,
contacts, chat, `pair-code.txt` and backup, and leaves at any time as a bundle. Honest limits: the
running process holds the phrases (an always-on account must hold its key to act for you) - at-rest
encryption protects disk images and backups, not against the operator; a user who wants no operator
runs their own node, which is why this host is one option among several. Relays cap unregistered
connections per source IP (32, `-Dmaxima.relay.maxpersource`), so plan on ~10 tenants per host
before raising it on relays you run. Decentralization: nothing new is centralised - the bundle
makes every account movable (principles 2, 3, 6), the host is optional and replaceable (3, 5), and
restore stays out of the RPC surface (4). Verify: `AccountBackupTest`, `TenantsTest`; live: export
from a paired device, restore into a fresh dir on another box, start, watch the device reconnect.

### Bootstrap without a single operator (server 0.4.59, node 0.2.34, app 0.6.77, portal 0.2.22, desktop 1.5.57)
The compiled-in relay list (`Bootstrap.RELAYS`) is now ONE seed source among several, never a
requirement (`core/session/SeedRelays`): a client starts from (1) the relays its user added -
typed, pasted, or scanned from a relay's QR - then (2) the relays it remembers (discovery's saved
verified list, the phone's recent swarm), then (3) the compiled-in list, which the user can switch
off or drop entries from. Every surface has the same controls: the app's Network → Manage hosts
(scan a relay QR, share any host as a QR, "Use built-in relays" switch, built-in / yours labels),
the desktop Network panel (paste a relay QR text, QR per host, the switch), the portal's Settings →
"Relays this phone starts from" (for the DEVICE) and its node panel (for the ACCOUNT: QR text
accepted, "Account uses the built-in relay list" switch), the account (`--no-builtin-relays` on
parlons-cloud, `-Dparlons.account.builtin=false` on the node, `builtin` in `parlons.node.hosts`;
persisted as `builtinrelays`). Switching the list off is refused while there is no relay of your
own, so nobody strands themselves. A relay's share text is `parlons-relay:host:port[,host:port…]`
- a prefix no contact or wallet address can collide with. The phone announces its endpoint to the
relays IT knows, not to one operator's list. Decentralization: removes the last mandatory
dependency on this operator's choices (principles 1, 2, 3, 5); a hostile seed is still
verify-before-adopt (greeting must be Maxima) and resolves still need two agreeing relays; nothing
is centralised, nothing new is hosted. Verify: `SeedRelaysTest`; on a phone: drop a built-in,
switch the list off with one relay of your own, restart - it attaches to yours.

### Relay capacity per box (server 0.4.57, node 0.2.32, app 0.6.75)
Three changes, each evaluated against the decentralization principles (all three are pure
throughput: no new party, no new dependency, nothing an operator gains control of):
- **Connections on virtual threads** where the JDK has them (21+: sally, megammr, maxlite,
  openproject). A parked client costs a few hundred bytes instead of a platform thread, so the
  default cap is 4096 (was 512); JDK 11/17 boxes (hetzner, pi, vigilance) and phones keep
  platform threads and the 512 default. `--maxconn` still overrides; `-Dmaxima.relay.vthreads=false`
  forces platform threads. The relay logs `connections on virtual|platform threads, cap N` at start.
  Upgrading a JDK 11/17 box to 21 raises its cape's capacity with no config change.
- **Sends ride the attached connection.** A phone or account sending to a relay it is attached
  to now writes one frame on that link and waits for the relay's ack there (acks are matched in
  order; the relay handles a connection's frames serially), instead of a TCP handshake plus a
  relay thread per message. Anything else - a relay we are not attached to, a peer's direct
  port - still dials as before. A send whose ack does not arrive drops the link so the pool
  re-attaches with a clean ledger. Directory publish/resolve and device pushes use the same path.
- **Store locks narrowed.** The mailbox's durable write (file + fsync, milliseconds) now happens
  OUTSIDE the mailbox monitor (reserve → write → commit, with the reservation undone on a failed
  write or an eviction mid-write; a failed write answers `IO_ERROR` → the relay acks UNKNOWN so the
  sender retries elsewhere). Blob shelf reads take no lock at all (files are published by atomic
  rename). Verify: `MailboxStoreTest`, `AttachedSendTest`, `RelayVirtualThreadsTest`.

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
