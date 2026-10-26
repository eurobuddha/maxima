# Handover — Parlons Node fleet, 2026-10-22 (for the next agent adding work to the nodes)

## 26 October 2026 — direct-file release rollout

All six account-node hosts (sally, hetzner, megammr, vigilance, pi-old and openproject)
now run **Parlons Node 0.2.139**. Each was restarted separately, retained its previous
jar for rollback, and passed all 11 public `ops/verify-relay.sh` checks. All six public
DEX health endpoints still answer 0.1.5. Service flags, data folders and identities
were preserved; maxima-relay-only services remain 0.4.107.

Both `parlons-tenants` services now run **Cloud account host 0.11.125**. Sally has one
tenant; the Pi has none. Their existing `--no-relay --no-direct` configuration is
unchanged. **Sally's tenant direct-file listener is not enabled:** automatic approval
review rejected the proposed service change and opening TCP 9536. Explicit user
approval is pending. Do not report this standalone tenant service as ready for direct
Cloud uploads. The Pi's residential port forwarding is unchanged.

Owner nodes and their in-process hosted guests reuse the owner's existing verified
listener. Hosted accounts retain separate pairing authorization and storage. The
shared listener applies only within one process; do not advertise a different JVM's
node listener for a standalone tenant service.

Published host digests:
- `node-v0.2.139`: `878aa84d3c368a3c807ed408d69402b4e95f1442fcf6049060ff8a54d1b1535a`
- `cloud-v0.11.125`: `c2f18a2e54655e4c89dda57f5e2d2c8d30924d8ea592e3811a84261ed449c83a`

Cloud 0.11.125 is GitHub Latest. New Android releases are 0.6.147 / Cloud 0.2.92.
Rollback jar paths are recorded in `/opt/maxima/node-before-0.2.139.txt` and, on the
two tenant hosts, `/opt/maxima/cloud-before-0.11.125.txt`. Public verification logs
are in `build/direct-files-release/*-health.log` in the release workspace.

The sections below retain the historical deployment record.

State of every box as of the evening of 2026-10-22: what runs where, how to ship a change to the
nodes, and the traps hit today. Read this before touching `node/`, `server/`, `core/` or any fleet box.

## What is deployed

| box | ssh | runs | relay port | DEX relay (public, TLS) | front |
|---|---|---|---|---|---|
| sally | `root@95.179.179.181` | parlons-node **0.2.136** + cloud host `parlons-tenants` 0.11.121 | 9501 | https://store.eurobuddha.com/dex-relay | apache `/etc/apache2/snippets/dex-relay.conf` |
| eurobuddha.com | `hetzner` | parlons-node **0.2.136** | 9501 | https://eurobuddha.com/dex-relay | (existing front) |
| megammr | `root@minimammr.com` | parlons-node **0.2.136** | 9501 | https://minimammr.com/dex-relay | nginx `/etc/nginx/sites-enabled/minimammr.conf` |
| vigilance | `vigilance` | parlons-node **0.2.136** | 9501 | https://wallet.minima.global/dex-relay | nginx `/etc/nginx/sites-enabled/wallet.minima.global` |
| the Pi | `pi-old` | parlons-node **0.2.136** + cloud host `parlons-tenants` 0.11.121 | **8001** | https://minimacore.uk/dex-relay | apache snippet included in `/etc/apache2/sites-enabled/minimacore.uk.conf` |
| openproject | `openproject` | parlons-node **0.2.136** (relay :8001) + `maxima-relay` maxima-server **0.4.106** on :9501 + `parlons-wake` | 8001 / 9501 | https://wake.eurobuddha.com/dex-relay | caddy `/etc/caddy/Caddyfile` (wake block, `handle_path`) |
| Maxima-Lite | `maxlite` | `maxima-relay` maxima-server **0.4.106** only (RAM-bound) | 9501 | https://relay.privateprivate.org/dex-relay | caddy, `handle /dex-relay/* { uri strip_prefix … }` (a `handle_path` would sort after the catch-all `handle`) |

Releases (GitHub, eurobuddha/maxima, all `--latest=false`):

- `node-v0.2.136`: `parlons-node-0.2.136.jar`, SHA-256 `e354bdf4906ab216863c6dc4b0eb6fbe93c9427140fe2e931eb3bf305b59369c`
- `server-v0.4.106`: `maxima-server-0.4.106.jar`, SHA-256 `3cac9e9481ec6823da9c5c674df53a000d487b404a43aa227671bc919738d906`
- `server-v0.4.105`: the previous server (the first maxima-server ever given a GitHub release)
- `cloud-v0.11.121` is the repo's "Latest"; leave it so.

Source: maxima `main` at `f8a2c47e` (re-dial fix) on top of `5c0ed457`.

## The DEX relay cape (Dexicon's order book)

`dexrelay/` (0.1.4, zero deps) is mounted INSIDE parlons-node and maxima-server. It is not a standalone service.

- Node flags: `-Dparlons.dex.port=9602 -Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=<https url>`
- Server flags: `--dex-port 9602 --dex-bind 127.0.0.1 --dex-public <https url>`
- **Always bind loopback** and front it with TLS at `/dex-relay/` → `http://127.0.0.1:9602/`. sally and
  megammr had `bind=0.0.0.0` until today (sally answered plain HTTP on the internet); both were fixed.
  Unit backups: `parlons-node.service.before-dex-loopback`.
- Each relay advertises its `dex` URL in the mesh greeting. `GET /dex/relays` lists every DEX URL it has
  learned, and `GET /dex/health` returns `{"ok":true,"version":"0.1.4",…}`.
- Dexicon clients start from three built-in relays (eurobuddha.com, store.eurobuddha.com, minimammr.com)
  and union `/dex/relays` from each, so a slow mesh never blocks trading.

### Mesh discovery: fixed today (node 0.2.136 / server 0.4.106)

`core/.../session/RelayPeers.java` used to re-read a verified peer's greeting (pool bit, dex URL) only when
that peer re-claimed by dialling IN. Lists therefore filled in unevenly: eurobuddha.com showed 3 of 7 while
maxlite showed 7. The check thread now re-queues every verified peer not dialled within `REDIAL_MS` (10 min)
whenever its queue is idle. A successful re-dial also refreshes the 2 h TTL; a dead peer fails the dial and
still expires. Test: `DexDiscoveryTest.verifiedPeersAreReDialledWithoutAReClaim`.

## How to ship a change to the nodes

1. **Version bump, same commit.** parlons-node: `node/build.gradle` `version` AND
   `ParlonsNodeMain.NODE_VERSION`. maxima-server: `server/.../Main.java` `VERSION`. A `core/` change reaches
   both, so bump both. The pre-commit hook runs `:core:test`; never `--no-verify`.
2. **Tests:** `./gradlew :core:test :server:test :node:test`.
   `RelayRuntimeStunTest.sharedRuntimeAnswersStunAndReleasesUdpOnStop` times out on UDP on this Mac with or
   without any change. Treat it as an environment failure only after confirming it also fails on the
   unchanged code (`git stash`).
3. **Build:** `./gradlew :node:fatJar :server:fatJar`. Outputs are `node/build/libs/parlons-node-<ver>.jar` and
   `server/build/libs/maxima-server.jar`. Before releasing, check that the jar carries your class, e.g.
   `unzip -p <jar> <class path> | grep -ac <symbol>`. A stale core.jar has shipped under a new number before.
4. **Release:** `gh release create node-v<ver> <jar> SHA256SUMS --latest=false` (body format: see
   `node-v0.2.135`). Server: `server-v<ver>` with a `.sha256` file.
5. **Roll, one box at a time** (owner approval needed: the auto-mode classifier blocks a fleet-wide loop as
   a production deploy, so ask first, then run each box as its own command):
   ```bash
   B=parlons-node-<ver>.jar; SUM=<sha256>
   scp $B <box>:/opt/maxima/$B.part
   ssh <box> "set -e; cd /opt/maxima; echo '$SUM  $B.part' | sha256sum -c --quiet; mv $B.part $B; chmod 644 $B;
     cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-$B;
     ln -sfn /opt/maxima/$B parlons-node.jar; systemctl daemon-reload; systemctl restart parlons-node; sleep 25;
     systemctl is-active parlons-node; journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|Exception'"
   ```
   maxima-server boxes: the same, but the symlink is `maxima-server.jar` and the service is `maxima-relay`.
   **Rollback:** point the symlink at the previous jar (all are kept in `/opt/maxima`) and restart. Unit
   backups are `*.before-<jar>`.
6. **Verify from outside, never from the box:** `ops/verify-relay.sh <ip>:<relay port>`, and
   `curl https://<host>/dex-relay/dex/health` for all seven URLs above.
7. Update `~/.claude/skills/maxima-relay/SKILL.md` (it has a 2026-10-22 section with the current state)
   and this doc's table.

## Traps hit today

- **Cloud hosts are `parlons-tenants.service`**, not `parlons-cloud.service`
  (`java -Xmx512m -jar /opt/maxima/parlons-cloud.jar --tenants /var/lib/parlons-tenants --no-relay --no-direct …`).
  sally has 1 tenant (`review-2026-09-06`). Its web panel used to fail on 127.0.0.1:9587 because
  parlons-node holds that port. **Fixed 2026-10-22:** sally's unit and the Pi's (which had `--no-panel`)
  now pass `--panel-port 9597`, so tenant panels take 9597, 9598 and so on, on loopback. Unit backups:
  `parlons-tenants.service.before-panel-port-9597`. On sally both 9587 (node) and 9597 (tenant) answer
  HTTP 200. The Pi has 0 tenants.
- **openproject had `-Dparlons.node.public=https://store.eurobuddha.com/parlons-node`** (sally's URL, copy-pasted).
  The flag only sets the NFT-hosting base (and the advertised wallet gateway, on MegaMMR nodes; openproject is
  not one). openproject has no `/parlons-node` front and hosted 0 NFT files, so the flag was REMOVED on
  2026-10-22 (backup `parlons-node.service.before-node-public-fix`). If openproject ever needs NFT hosting,
  add a TLS route to 127.0.0.1:9585 first, then set the flag to that URL.
- openproject runs two relays: the node's on **8001**, which carries the DEX cape, and maxima-server on
  **9501**, which has no `--dex-*` flags. Peers' bootstrap lists name `78.141.237.9:9501`.
- The Pi and vigilance logged `Invalid SyncBlock as NO PARENT` right after a restart, during catch-up.
  Not rechecked.
- vigilance has ufw **default-deny outbound**. A new outbound port or peer needs `ufw allow out …`.
  **Open item:** after the re-dial fix, 6 of 7 relays list all 7 DEX URLs (checked 2026-10-22 ~20:45 BST).
  vigilance lists 6 and is missing wake.eurobuddha.com, because ufw allows `78.141.237.9` on 9501 but not
  8001 (openproject's node relay, which carries the DEX cape). **Fixed 2026-10-22:** that rule was added; vigilance now lists all 7.
  The nginx edit tripped vigilance's file-integrity monitor (`/root/.fileintegrity/check.sh`, Telegram alerts
  every 5 min). It was verified as ours (diff = the `/dex-relay/` block only, all logins from the owner's key)
  and the owner re-baselined it. **Any future edit to the wallet.minima.global nginx file trips it again**:
  tell the owner, and have them re-baseline with `rm /root/.fileintegrity/baseline.sha256 && /root/.fileintegrity/check.sh`.
- The Pi is residential: only 8001 (and 443) are forwarded. ufw is active; leave it alone.
- `ssh eurobuddha.com` fails; use the `hetzner` alias.
- zsh does not word-split `$VAR`. Write ssh flags out literally, or use a function.
- Never `pkill -f maxima-server.jar` (it matches your own ssh). Kill by PID.
- `/var/lib/maxima/seed.txt` and the node seed are wallets. Never print them. Grep unit/journal output with
  `-v seed`.
- Never run minimaDesk on this Mac (real funds). The dev node is `~/minima4442`.
- Identifiers are never truncated anywhere, and the Minima dollar token is **MxUSD**
  (`0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`).

## 2026-10-23 — fixes released and ROLLED to all 7 boxes (node-v0.2.138, server-v0.4.107, cloud-v0.11.123)

parlons-node **0.2.138** / maxima-server **0.4.107** / DEX relay **0.1.5** / parlons-cloud **0.11.123**:

- DEX rate limit: from loopback, the cape buckets by the rightmost `X-Forwarded-For` entry (the front's own);
  a direct caller's header is ignored. Caddy and Apache send it already; the **nginx fronts on megammr and
  vigilance do not** and need `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` in the
  `/dex-relay/` block (vigilance: owner re-baselines the integrity monitor after). Until then they behave as before.
- DEX bind default follows the public URL: `127.0.0.1` when `parlons.dex.public` / `--dex-public` is set, else `0.0.0.0`.
- `ops/deploy-parlons-tenants.sh` writes `--panel-port 9597` (was `--no-panel`, which undid the hand fix on re-run).
- `ops/deploy-parlons-node.sh` writes `Restart=always` (a `quit` or resync exits 0). Live units still say
  `on-failure` until each box is re-deployed or hand-edited.
- `parlons newcode` prompts for the admin password at the console when the host requires one.

Rolled 2026-10-23 ~13:00 UTC: every parlons-node box on 0.2.138 with `Restart=always`; maxlite and openproject's
maxima-relay on 0.4.107; the X-Forwarded-For line added to the `/dex-relay/` block on megammr and vigilance
(backups `/root/<file>.before-xff`). Unit backups `*.before-parlons-node-0.2.138.jar` / `*.before-maxima-server-0.4.107.jar`.
Verified from outside: all 7 DEX relays answer 0.1.5 and list all 7; 9602 closed publicly everywhere;
`ops/verify-relay.sh` passes on all 8 relay ports. Tenant hosts (sally, the Pi) stay on cloud 0.11.121.
SHA-256: node `597a77225514b8ceddf1036a6016abc6d4b826e7711210c7f55de01857c9b4af`,
server `69201869c70d256e47459fe2207ee87e72e09ea50580738a9da83f91ca1da79e`,
cloud `536964bf218769f5e70ff6062a7c43eda2a9a7be5f796cfc2c0a2cabe56b90b5`.
