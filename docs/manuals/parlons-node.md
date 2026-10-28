# Parlons Node — Operator's Manual

For Parlons Node **0.2.143** (release tag `node-v0.2.143`), which carries DEX relay
**0.1.5**. The release ships two jars: `parlons-node-0.2.143.jar`, the plain node, and
`parlons-node-fleet-0.2.143.jar`, the same node with a Node.js runtime and the keets sidecar
embedded, so one jar also runs the keets transport, a public HyperDHT bootstrap node and a keets
mailbox ([§18](#18-the-keets-sidecar-keetchat-bridge)). The one-line installer picks the fleet jar
whenever the box can run it. Every flag, port, default and endpoint in this manual comes from the source in the
`eurobuddha/maxima` repository (`node/`, `server/`, `core/`, `account/`, `dexrelay/`) and the
deploy script `ops/deploy-parlons-node.sh`. If a value is not in the code, this manual says so
and does not guess it.

Host names in the examples (`node.example.com`, `peer1.example.net` and so on) are
placeholders. Replace them with your own.

Each task has a numbered **How to …** procedure with the exact commands. The commands assume a
Debian or Ubuntu box, the deploy script's paths (`/opt/maxima`, `/var/lib/parlons-node`), the
service user `maxima`, and the default ports. Change them if yours differ.

Related manuals:

- [Parlons! user manual](parlons.md): the apps, and the relays they use
  ([the Network tab](parlons.md#19-the-network-tab-relays-and-helping-the-network)).
- [Parlons Cloud Portal manual](parlons-cloud-portal.md): pairing a phone with the account on
  this node, and the `parlons-tenants` service.

---

## Table of contents

1. [What a Parlons Node is](#1-what-a-parlons-node-is)
2. [Architecture](#2-architecture)
3. [Requirements](#3-requirements)
4. [Download and verify the jar](#4-download-and-verify-the-jar)
5. [Three ways to install](#5-three-ways-to-install)
6. [First start](#6-first-start)
7. [The systemd unit (annotated)](#7-the-systemd-unit-annotated)
8. [Configuration reference: every property](#8-configuration-reference-every-property)
9. [Ports](#9-ports)
10. [Data directory layout](#10-data-directory-layout)
11. [TLS fronting: Caddy, nginx, Apache](#11-tls-fronting-caddy-nginx-apache)
12. [The relay cape and the mesh](#12-the-relay-cape-and-the-mesh)
13. [MegaMMR mode and the rootnode](#13-megammr-mode-and-the-rootnode)
14. [The wallet gateway and its token](#14-the-wallet-gateway-and-its-token)
15. [NFT hosting and `parlons.node.public`](#15-nft-hosting-and-parlonsnodepublic)
16. [The DEX relay cape (Dexicon)](#16-the-dex-relay-cape-dexicon)
17. [The account, pairing and the local panel](#17-the-account-pairing-and-the-local-panel)
18. [The keets sidecar (KeetChat bridge)](#18-the-keets-sidecar-keetchat-bridge)
19. [MDS (MiniHUB) on the node](#19-mds-minihub-on-the-node)
20. [Seeds, identity and backups](#20-seeds-identity-and-backups)
21. [The admin RPC](#21-the-admin-rpc)
22. [Upgrading and rolling back](#22-upgrading-and-rolling-back)
23. [Verifying from outside](#23-verifying-from-outside)
24. [Monitoring and logs](#24-monitoring-and-logs)
25. [Firewall notes](#25-firewall-notes)
26. [Troubleshooting](#26-troubleshooting)
27. [Security checklist](#27-security-checklist)
28. [Desktop apps that embed this jar](#28-desktop-apps-that-embed-this-jar)
29. [Glossary](#29-glossary)

---

## 1. What a Parlons Node is

A Parlons Node is one Java process that contains:

- **A full Minima node.** It is the `minima-core` fork (`org.minima`), bundled inside the jar as
  `node/libs/minima-node.jar`. It validates the chain, talks to peers over P2P, and holds a wallet.
- **The Maxima relay, called the "cape".** This is the same clean-room relay code as
  `maxima-server.jar` (`:core` + `:server`). Phones and other clients dial out to it. It also takes
  part in the relay mesh.
- **A wallet gateway.** This is a hardened `POST /cmd` proxy with an allow-list, so phones can use
  this node's MegaMMR to read balances and to broadcast transactions they signed themselves.
- **NFT hosting.** The node serves the image files that tokens point to, at `/nft/...` on the gateway port.
- **The Parlons account.** This is the same `ParlonsCore` that `parlons-cloud.jar` runs: pairing,
  chat, contacts, push, and a local web panel. Its wallet is the node's own wallet.
- **The Dexicon DEX relay cape** (optional). This is a small HTTP service that fans out signed
  order-book envelopes. It runs inside the same process.
- **A keets chat bridge** (optional). It connects the account's chat to KeetChat when the embedded
  node runs the keets sidecar.

**One seed drives the whole node.** On first boot, the identity of the Maxima relay and the account
is derived from the node's wallet seed and then pinned in `identity.txt`
(see [§20](#20-seeds-identity-and-backups)).

Who needs this? Most people do not. Parlons Cloud (`parlons-cloud.jar`) runs an always-on account
in about 1 GB of RAM. Run a Parlons Node when you want to hold a full copy of the chain, serve the
relay network, and give phones a wallet gateway.

---

## 2. Architecture

```
                        Internet
   ┌───────────────┬──────────────┬─────────────────────┐
   │ Minima peers  │ phones /     │ browsers, phones,    │
   │ (TCP p2p)     │ Maxima       │ Dexicon clients      │
   │               │ clients      │ (HTTPS 443)          │
   ▼               ▼ (TCP 9501)   ▼                      │
┌──────────────────────────────────────────┐   ┌─────────┴──────────────┐
│           parlons-node.jar (one JVM)     │   │  TLS front (Caddy /    │
│                                          │   │  nginx / Apache)       │
│  Minima node (org.minima)   :9001 public │   │                        │
│    ├─ admin RPC  127.0.0.1:p2p+4 (opt-in)│◄──┤ /parlons-node/ ──► 9585│
│    └─ MDS        127.0.0.1:p2p+2 (opt-in)│   │ /dex-relay/    ──► 9602│
│                                          │   └────────────────────────┘
│  Maxima relay cape           :9501 public│
│    (TCP, plus UDP STUN on the same port) │
│                                          │
│  Wallet gateway + NFT  127.0.0.1:9585    │
│  Account panel + API   127.0.0.1:9587    │
│  DEX relay cape        127.0.0.1:9602    │  (opt-in; loopback by default when
│                                          │   parlons.dex.public is set)
│  Parlons account (pairing, chat, push)   │
│  keets chat bridge (when -keets is on)   │──► keets sidecar (Node.js child)
└──────────────────────────────────────────┘
```

| Component | Code | Listens on (default) | Default state |
|---|---|---|---|
| Minima node | bundled `node/libs/minima-node.jar` | TCP `9001` (`parlons.node.port`) | on |
| Admin RPC | `node/.../AdminRpc.java` | `127.0.0.1:<p2p+4>`, always loopback | off (`parlons.node.rpc=false`) |
| MDS / MiniHUB | Minima fork MDS | `127.0.0.1:<p2p+2>` (files), `<p2p+3>` (commands) | off |
| Relay cape | `server/.../RelayRuntime`, `RelayServer` | TCP `9501`, plus UDP STUN on the same port | on |
| Wallet gateway and `/nft` | `node/.../NodeGateway.java`, `NftStore.java` | `127.0.0.1:9585` | on (it starts once the wallet is up) |
| Account panel | `account/.../ParlonsLocal.java` | `127.0.0.1:9587` | on |
| DEX relay cape | `dexrelay/.../DexRelay.java` | `127.0.0.1:<port>` when `parlons.dex.public` is set, else `0.0.0.0:<port>`; an explicit `parlons.dex.bind` wins | off (`parlons.dex.port=0`) |
| keets bridge | `node/.../KeetsChatBridge.java` | none (polls the node) | on only when the node runs with `-keets` |

**Boot order** (from `ParlonsNodeMain.main`):

1. Minima's own flags are applied (`parlons.node.args` and `parlons.node.conf`), then the
   `parlons.node.*` properties.
2. The embedded Minima node starts.
3. If enabled, the admin RPC starts. If it cannot bind, the process stops with exit code 2.
4. If enabled, the DEX cape starts. It does not depend on the chain, so it is up before the node syncs.
5. A background thread waits until the node's vault is readable (up to 120 s). It then derives the
   identity, starts the relay cape, the gateway and NFT store, and the account, in that order.
6. A heartbeat line is logged every 30 s.

---

## 3. Requirements

### Java

- **Java 11 or newer.** The jar is compiled to Java 11 bytecode (`node/build.gradle`).
- **Java 21 or newer is recommended.** On JDK 21+, the relay cape uses virtual threads, and its
  default connection cap rises from 512 to 4096 (`RelayServer.DEFAULT_MAX_CONNECTIONS_VIRTUAL`).
- **The `jdk.httpserver` module must be present.** The gateway, the admin RPC, the panel and the
  DEX cape all use `com.sun.net.httpserver`. A full JRE has this module. If you build a cut-down
  runtime with jlink, add it:
  `--add-modules java.se,jdk.unsupported,jdk.httpserver`. Without it, the admin RPC fails with
  `NoClassDefFoundError` and the node refuses to run.
- The deploy script installs `default-jre-headless` on Debian/Ubuntu when no `java` is found.

### RAM and heap

| Role | RAM on the box | Java heap (`-Xmx`) |
|---|---|---|
| Node with MegaMMR (the default; needed to be a wallet gateway) | 8 GB | 3 GB minimum |
| Node without MegaMMR (`-Dparlons.node.megammr=false`) | 2 GB | 768 MB |
| Fleet jar (any of the above + the keets sidecar: Node.js, DHT, mailbox) | + 1 GB, 2 GB minimum; x86_64 or aarch64 only | as above (`MemoryMax` + 1 GB) |

These figures come from the fleet notes in `cloud/NODE-SETUP.md`. They were learned the hard way: MegaMMR nodes at 2 GB and 2.5 GB heap looped on out-of-memory errors,
and one at 3 GB crashed.

- **Always set `-Xmx`.** An unbounded default heap is how you lose a small VPS.
- **Set the systemd `MemoryMax` above the heap.** The deploy script uses heap + 512 MB. If
  `MemoryMax` equals `-Xmx`, the cgroup kills the JVM as soon as its off-heap memory grows.
- A node that crash-loops trips `StartLimitBurst` and stays down. After any heap change, check
  `systemctl is-active parlons-node`.

### Disk

Disk use grows with the chain. The code sets no fixed figure. Also budget for:

- the MegaMMR snapshot download (about 460 MB according to `cloud/NODE-SETUP.md`) while you seed it;
- the relay blob shelf, if you enable it (`parlons.relay.blob`, in MB);
- hosted NFT files (at most 32 MB each).

### Network

- A public IPv4 address, or a router port-forward, for the Minima P2P port and the relay port.
- Outbound internet access, to reach Minima peers, mesh relays and the archive node.
- A domain name with TLS if you want to serve the gateway, NFT files or the DEX cape publicly.

### How to check a box before you install

1. Check the Java version. It must be 11 or newer; 21 or newer is better:

   ```bash
   java -version
   ```

2. Check that the `jdk.httpserver` module is present. This must print one line:

   ```bash
   java --list-modules | grep jdk.httpserver
   ```

3. Check the RAM. You need 8 GB for a MegaMMR node, or 2 GB without MegaMMR:

   ```bash
   free -h
   ```

4. Check the free disk space where the data directory will live:

   ```bash
   df -h /var/lib
   ```

5. Check that nothing already holds the ports the node needs:

   ```bash
   ss -ltnp | grep -E ':(9001|9005|9501|9585|9587|9602) '
   ```

   This should print nothing. If a stock Minima node holds 9001, plan to move the node's P2P port
   ([§9](#9-ports)). If `maxima-relay.service` holds 9501, plan to replace it ([§5](#5-three-ways-to-install)).

---

## 4. Download and verify the jar

Releases are on GitHub at `eurobuddha/maxima` under tags `node-v<version>`. They are published
with `--latest=false`, so the repository's "Latest" badge does **not** point at the node. Always
pick a node release by its tag.

1. Download the jar and the checksum file:

   ```bash
   VER=0.2.143
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/parlons-node-$VER.jar
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v$VER/SHA256SUMS
   ```

   For the fleet jar (keets transport, DHT bootstrap and mailbox included), fetch
   `parlons-node-fleet-$VER.jar` from the same release instead. Its SHA-256 is
   `3f48a0cdfc6ae668aef19b53f0f0c986267c6a390e2601ddf3caf686afb5e2b9`.

2. Check the SHA-256 hash. The hash of `parlons-node-0.2.143.jar` is:

   ```
   ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc
   ```

   ```bash
   echo "ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc  parlons-node-0.2.143.jar" | sha256sum -c -
   # macOS: shasum -a 256 -c  (same input)
   ```

   It must print `parlons-node-0.2.143.jar: OK`. If it does not, delete the file and do not run it.

3. Confirm the version. This does not start a node:

   ```bash
   java -jar parlons-node-0.2.143.jar --version
   # parlons-node 0.2.143
   ```

4. Print the built-in help, which lists the main properties. This does not start a node either:

   ```bash
   java -jar parlons-node-0.2.143.jar --help
   ```

### How to build the jar from source instead

1. Clone the repository and build the fat jar:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ./gradlew :node:fatJar
   ```

2. Find the jar. It is `node/build/libs/parlons-node-<version>.jar`:

   ```bash
   ls node/build/libs/
   ```

3. Check that the jar contains your change before you ship it. A stale `core.jar` has shipped
   under a new version number before. Count a symbol you added in a class you changed; the count
   must be 1 or more:

   ```bash
   unzip -p node/build/libs/parlons-node-<version>.jar <class path> | grep -ac <symbol>
   ```

4. Note its checksum, so you can check it again on the box before you run it:

   ```bash
   sha256sum node/build/libs/parlons-node-<version>.jar
   ```

> The jar takes **no command-line options**. The only arguments it accepts are `--version`/`-v`
> and `--help`/`-h`/`help`. Any other argument prints the help text to stderr and exits with code 2.
> Every setting is a `-D` system property placed **before** `-jar`.

---

## 5. Three ways to install

### A. The one-line installer (start here)

On the Linux machine itself (a VPS, a Raspberry Pi 4/5 with a 64-bit OS, a home server), as root or a user with `sudo`:

```bash
curl -fsSL {{NODE_INSTALL}} | bash
```

That one command gives the network a complete endpoint. By default the box runs, in one process:

- a **full Minima node** in the server role (P2P 9001/tcp), with MegaMMR when the box has 3.5 GB of RAM or more;
- the **Parlons relay** (Maxima cape, 9501/tcp+udp) and your always-on **Parlons account**;
- the **keets transport**, a public **HyperDHT bootstrap node** (49737/udp) and a **keets mailbox**, from the Node.js runtime and sidecar embedded in the fleet jar;
- the **DEX relay cape** (Dexicon order book) and the **wallet gateway**, served over HTTPS by Caddy at `https://<name>/dex-relay` and `https://<name>/parlons-node`. The name is the one you pass with `--host`; without it the box uses `<a-b-c-d>.sslip.io`, a free name that resolves to its own public address, so nothing has to be registered.

What it does, in order:

1. Installs Java 11+ and `qrencode` (apt, dnf, apk or pacman) if they are missing, and Caddy for the HTTPS front (unless another web server already holds ports 80/443, in which case it tells you which two paths to front yourself, [§11](#11-tls-fronting-caddy-nginx-apache)).
2. Finds the newest `node-v…` release on GitHub, downloads `parlons-node-fleet-<ver>.jar` and refuses it unless its sha256 matches the release's `SHA256SUMS`.
3. Creates the `maxima` user and `/var/lib/parlons-node` (mode 700), installs `/usr/local/bin/parlons-pair`, and writes the hardened `parlons-node.service` ([§7](#7-the-systemd-unit-annotated)) with `-Dparlons.fleet=true`, `-Dparlons.public.host=<your public IPv4>`, `-Dparlons.dex.public=https://<name>/dex-relay` and `-Dparlons.node.public=https://<name>/parlons-node`.
4. Sizes the node to the machine: with 3.5 GB of RAM or more it runs MegaMMR with a 2 GB heap; below that, a plain full node with a 768 MB heap. `MemoryMax` is the heap plus 1.5 GB for the sidecar. It syncs from `65.109.31.226:9001`.
5. Opens 9001/tcp, 9501/tcp+udp, 49737/udp and 80 + 443 in ufw or firewalld when one is active.
6. Starts the node, waits for your account address and prints it in full.
7. Asks you to set the admin pairing password (first install only), then asks for it once more and draws the QR code your phone scans in Parlons Cloud.
8. Prints the box's keets public key, its DHT bootstrap address and its mailbox key, with the command the list maintainer runs to sign the mailbox into the published list.

The full set needs an x86_64 or aarch64 CPU, 2 GB of RAM and a public IPv4 address. On a box that lacks one of those the installer says why and falls back to the plain jar (`parlons-node-<ver>.jar`: Minima node, Parlons relay and account, no keets, no DEX), which runs in 1 GB on any CPU.

Options (put them after `bash -s --`):

| Option | Effect |
|---|---|
| `--host node.example.com` | Serve the DEX relay and the wallet gateway at your own name instead of the sslip.io one. Point the name's A record at the box first; Caddy fetches the certificate itself. |
| `--public-host 1.2.3.4` | The public IPv4 the DHT and the relay announce. Default: the box's global-scope address. Behind a home router, pass your public address here and forward the ports. |
| `--basic` | The plain jar on purpose: no keets transport, DHT, mailbox or DEX. |
| `--fleet` | Insist on the full jar; fail instead of falling back. |
| `--update` | Fetch the newest release and restart. Running the install command again does the same. |
| `--uninstall` | Stop and remove the service and `parlons-pair`. `/var/lib/parlons-node` (identity, wallet, chats) is kept. |
| `--no-megammr` | A plain full node without the MegaMMR (1 GB RAM is enough). Chosen for you on machines under 3.5 GB. |
| `--heap <size>` | A different Java heap, for example `--heap 3g`. |

For example, with your own domain: `curl -fsSL {{NODE_INSTALL}} | bash -s -- --host node.example.com`

The installer sets no relay mesh peers (the compiled-in bootstrap list is used) and no blob shelf. For those, use the deploy script (B) or edit the unit ([§7](#7-the-systemd-unit-annotated)). On a Mac or Windows PC, install Parlons Desktop instead. If the machine cannot run a node at all, the account-only `get-parlons-cloud.sh` runs in 1 GB ([Parlons Cloud Portal manual](parlons-cloud-portal.md)).

#### How to install with the one-line installer

1. Open 9001/tcp, 9501/tcp and 9501/udp, 49737/udp, and 80 + 443/tcp in your cloud provider's firewall, or forward them on your home router. The account works without them; relaying for others, serving blocks, the DHT and the DEX relay need them.
2. If you have a domain, point an A record at the box now, so Caddy can fetch the certificate at the end of the install. Otherwise skip this: the sslip.io name needs nothing.
3. Run the command above in an SSH session or a terminal on the machine. Do not run it from a script with no terminal: it then skips the password and QR steps.
4. When asked, type an admin pairing password twice. Keep it; every new invitation for your own devices needs it.
5. When asked again, type the password once. The QR code and the complete invite appear.
6. In Parlons Cloud on your phone, tap **Scan pairing QR** and scan it.
7. Read the last lines. They name the DEX relay URL and the wallet gateway URL, and print the box's keets public key, DHT bootstrap address and mailbox key. Send the mailbox line to the list maintainer; until it is signed into the published list, the box relays and bootstraps but does not hold mail for offline phones.
8. Watch the chain sync: `journalctl -u parlons-node -f | grep heartbeat`.
9. Check the DEX relay from another machine: `curl -s https://<name>/dex-relay/dex/health` must answer. The certificate can take a minute after the first start.
10. Back up the seed ([§20](#20-seeds-identity-and-backups)).

If step 4 or 5 was skipped, run `sudo parlons-pair --set-admin-password` once and then `sudo parlons-pair --admin`.

### B. The deploy script (from your own computer)

Run it from a checkout of the repository on your own computer, against a fresh Linux box that
you can reach over SSH as root. Running it again updates the node; install and update are the
same operation.

#### How to install with the deploy script

1. On your computer, clone the repository. It holds `ops/deploy-parlons-node.sh` and
   `ops/parlons-pair`:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ```

2. Put the verified jar in `dist/` ([§4](#4-download-and-verify-the-jar)). Without `--jar`, the
   script takes the newest `dist/parlons-node-*.jar`:

   ```bash
   mkdir -p dist
   mv ~/Downloads/parlons-node-0.2.143.jar dist/
   ```

3. Check that key-based SSH as root works. The script uses `BatchMode=yes`, so it cannot answer a
   password prompt:

   ```bash
   ssh -o BatchMode=yes root@node.example.com true && echo ssh-ok
   ```

4. If the box already runs `maxima-relay.service` on 9501, add `--replace-relay` to the next
   command.
5. Run the script. This is a typical MegaMMR node with a TLS front; the table below lists every flag:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com \
       --jar dist/parlons-node-0.2.143.jar \
       --heap 3g \
       --rootnode seed-peer.example.net:9001 \
       --rpc \
       --megammr-seed https://eurobuddha.com/mega.mmr \
       --public https://node.example.com/parlons-node \
       --dex 9602 --dex-public https://node.example.com/dex-relay \
       --peers "peer1.example.net:9501,peer2.example.net:9501"
   ```

6. Read the summary at the end. It prints the account wallet address, the Maxima identity
   (`Mx…`) and the gateway bearer token. Keep the token private
   ([§14](#14-the-wallet-gateway-and-its-token)).
7. Set up the TLS front ([§11](#11-tls-fronting-caddy-nginx-apache)).
8. Pair your phone ([§17](#17-the-account-pairing-and-the-local-panel)) and back up the seed
   ([§20](#20-seeds-identity-and-backups)).
9. Verify the node from another machine ([§23](#23-verifying-from-outside)).

#### What the script does

The script runs these steps in order:

1. Installs a headless JRE if `java` is missing, and tries to install `qrencode` (for pairing QR codes).
2. Creates the unprivileged `maxima` system user, and creates `/var/lib/parlons-node` with mode 700.
3. Uploads the jar to `/opt/maxima/parlons-node-<ver>.jar` and checks its SHA-256. Only then does it
   point the symlink `/opt/maxima/parlons-node.jar` at the new jar.
4. Installs `/usr/local/bin/parlons-pair`.
5. Writes the hardened unit `/etc/systemd/system/parlons-node.service`.
6. Opens the P2P and relay ports in ufw or firewalld. It opens the DEX port only when `--dex` is
   given without `--dex-public`, and the gateway port only with `--gateway-public`.
7. Starts the node and checks that the relay port is listening.
8. Optional: `--seed-from` adopts an existing 24-word phrase ([§20](#20-seeds-identity-and-backups)).
9. Optional: `--megammr-seed` imports a MegaMMR snapshot ([§13](#13-megammr-mode-and-the-rootnode)).
10. Prints the account wallet address, the Maxima identity, the gateway token, and the pairing instructions.

| Script flag | Becomes | Default |
|---|---|---|
| `--jar FILE` | jar to deploy | newest `dist/parlons-node-*.jar` |
| `--heap 3g` | `-Xmx` | `2g` (too small for MegaMMR; pass `3g` or more) |
| `--memmax 3584M` | unit `MemoryMax` | heap + 512M |
| `--p2p-port N` | `-Dparlons.node.port` | `9001` |
| `--relay-port N` | `-Dparlons.relay.port` | `9501` |
| `--gateway-port N` | `-Dparlons.gateway.port` | `9585` |
| `--gateway-public` | `-Dparlons.gateway.bind=0.0.0.0`, and opens the port | loopback |
| `--public URL` | `-Dparlons.node.public` | unset |
| `--rootnode h:p` | `-Dparlons.node.rootnode` | unset (the node will not sync) |
| `--no-megammr` | `-Dparlons.node.megammr=false` | MegaMMR on |
| `--rpc` | `-Dparlons.node.rpc=true` | off |
| `--peers h:p,…` | `-Dparlons.relay.peers` | unset (the compiled-in bootstrap list is used) |
| `--blobstore MB` | `-Dparlons.relay.blob` | `0` |
| `--dex PORT` | `-Dparlons.dex.port` | `0` (off) |
| `--dex-public URL` | `-Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=URL` | unset |
| `--passphrase-file F` | `-Dparlons.node.passphrase.file` | unset |
| `--node-args "…"` | `-Dparlons.node.args` | empty |
| `--keets` | adds `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js` to the node args, and uploads the sidecar | off |
| `--seed-from F --archive h:p` | one-time wallet resync to the phrase in F (needs `--rpc`), then pins it in `identity.txt` | — (`--archive` defaults to an archive node written into the script) |
| `--megammr-seed URL` | one-time MegaMMR import (needs `--rpc`) | — |
| `--replace-relay` | stops and disables `maxima-relay.service` so the node can take its port | — |

> **Always pass `--dex-public` together with `--dex`.** With it, the script sets
> `parlons.dex.bind=127.0.0.1` and keeps the port closed. Without it, the DEX cape binds `0.0.0.0`
> and the script opens the port to the internet as plain HTTP.

The script writes `Restart=always` into the unit, so the node also comes back after a clean exit
(`quit`, a MegaMMR import, a resync).

### C. By hand

The minimum command is:

```bash
java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar
```

A node started like this has no rootnode, so it will not sync.

#### How to install by hand

These steps do on the box what the deploy script does. Run them as root.

1. Install Java if it is missing, and `qrencode` if you want pairing QR codes:

   ```bash
   apt-get update
   apt-get install -y default-jre-headless qrencode
   ```

2. Create the service user and the directories:

   ```bash
   id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
   mkdir -p /opt/maxima /var/lib/parlons-node
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Copy the verified jar into `/opt/maxima` and point the symlink at it:

   ```bash
   cp parlons-node-0.2.143.jar /opt/maxima/
   cd /opt/maxima
   echo "ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc  parlons-node-0.2.143.jar" | sha256sum -c -
   chmod 644 parlons-node-0.2.143.jar
   ln -sfn parlons-node-0.2.143.jar parlons-node.jar
   ```

4. Optional: install the pairing helper from a checkout of the repository:

   ```bash
   install -m 755 ops/parlons-pair /usr/local/bin/parlons-pair
   ```

5. Write the unit file `/etc/systemd/system/parlons-node.service` from the example in
   [§7](#7-the-systemd-unit-annotated). Change the host names, the heap and `MemoryMax` to suit
   the box.
6. Open the P2P and relay ports ([§25](#25-firewall-notes)):

   ```bash
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ```

7. Start the node, then follow [§6](#6-first-start):

   ```bash
   systemctl daemon-reload
   systemctl enable --now parlons-node
   ```

---

## 6. First start

1. Start the service (the one-line installer and the deploy script have already done this):

   ```bash
   systemctl enable --now parlons-node
   systemctl is-active parlons-node
   ```

2. Follow the log:

   ```bash
   journalctl -u parlons-node -f
   ```

3. Find the boot line. It names the version, the data folder and the ports:

   ```
   [parlons-node] Parlons Node 0.2.143 — booting embedded Minima node <ver> at /var/lib/parlons-node/<base> (p2p 9001, megammr true, admin rpc 127.0.0.1:9005 (loopback-bound))
   ```

4. Watch the chain sync. Every 30 s a heartbeat line prints the block height:

   ```
   [parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:9501 | gateway=up:9585 | account=up:<n> hosts, <n> devices
   ```

   The first sync is finished when `block=` reaches the current chain tip. There is no fixed time
   for this; it depends on the box and the connection.
5. Once the wallet is ready, these lines appear:
   - `identity: pinned from the vault into identity.txt` (first boot only);
   - `Maxima cape up on port 9501 — identity Mx…`;
   - `account wallet = node wallet: 0x… (Mx…)`;
   - `wallet gateway up on 127.0.0.1:9585/cmd (… bearer token in /var/lib/parlons-node/gateway-token.txt)`;
   - `account up: attached to N relay(s)…`.
6. On a MegaMMR node, import the MegaMMR once ([§13](#13-megammr-mode-and-the-rootnode)). Until
   you do, balances for existing addresses come back empty.
7. Pair your phone ([§17](#17-the-account-pairing-and-the-local-panel)):

   ```bash
   sudo parlons-pair --set-admin-password
   sudo parlons-pair --admin
   ```

8. Back up the seed ([§20](#20-seeds-identity-and-backups)).

---

## 7. The systemd unit (annotated)

This unit follows the one `ops/deploy-parlons-node.sh` writes, with the flags the fleet uses in
October 2026: a MegaMMR node, relay on 9501, gateway and DEX cape on loopback behind TLS, and an
explicit mesh bootstrap list.

systemd does not allow comments at the end of a line or inside a continued `ExecStart`, so the
explanations are in the comment block above `ExecStart`.

```ini
# /etc/systemd/system/parlons-node.service
[Unit]
Description=Parlons Node - full Minima node + Maxima cape + wallet gateway (0.2.143)
Documentation=https://github.com/eurobuddha/maxima
After=network-online.target
Wants=network-online.target
# If the node crashes 5 times within 300 s, systemd stops restarting it (check after heap changes).
StartLimitIntervalSec=300
StartLimitBurst=5

[Service]
Type=simple
User=maxima
Group=maxima

# What each property does (full reference: section 8):
#   -Xmx3g                          hard heap ceiling (MegaMMR needs at least 3g on an 8 GB box)
#   parlons.node.data               data directory (seed, identity, tokens, chain)
#   parlons.node.port=9001          Minima P2P port, public. The admin RPC is p2p+4 = 9005.
#   parlons.node.rpc=true           loopback admin RPC (needed for vault, megammr import)
#   parlons.node.megammr=true       keep the MegaMMR (required for the wallet gateway)
#   parlons.node.rootnode           a Minima peer to discover the chain from
#   parlons.relay.port=9501         Maxima relay cape, public TCP (and UDP STUN on the same port)
#   parlons.relay.peers             mesh bootstrap list: other relays, host:port, comma-separated
#   parlons.relay.blob=1024         media blob shelf in MB (0 = off)
#   parlons.gateway.port/bind       wallet gateway + /nft on loopback, fronted by TLS
#   parlons.node.public             public base URL of the gateway front (NFT URLs, gateway advert)
#   parlons.dex.port/bind/public    DEX relay cape on loopback, announced as its TLS URL
ExecStart=/usr/bin/java -Xmx3g \
    -Dparlons.node.data=/var/lib/parlons-node \
    -Dparlons.node.port=9001 \
    -Dparlons.node.rpc=true \
    -Dparlons.node.megammr=true \
    -Dparlons.node.rootnode=seed-peer.example.net:9001 \
    -Dparlons.relay.port=9501 \
    -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:9501 \
    -Dparlons.relay.blob=1024 \
    -Dparlons.gateway.port=9585 \
    -Dparlons.gateway.bind=127.0.0.1 \
    -Dparlons.node.public=https://node.example.com/parlons-node \
    -Dparlons.dex.port=9602 \
    -Dparlons.dex.bind=127.0.0.1 \
    -Dparlons.dex.public=https://node.example.com/dex-relay \
    "-Dparlons.node.args=" \
    -jar /opt/maxima/parlons-node.jar

# Restart on a crash. Exit code 3 means "restart me": a wallet resync or an identity import
# requested from a paired device ends with exit 3, and the node comes back on the same identity.
# always, not on-failure: `quit` and a megammr import/resync end with exit 0, and the node must come back.
# The deploy script writes Restart=always. Older units may say on-failure: change them.
Restart=always
RestartSec=10
RestartForceExitStatus=3
SuccessExitStatus=3

# Filesystem: the node gets write access to its data directory and nothing else.
NoNewPrivileges=true
PrivateTmp=true
ProtectSystem=strict
# ProtectHome makes /home and /root unreadable: keep passphrase files under /var/lib/parlons-node or /etc.
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

# Privileges: no capabilities at all.
CapabilityBoundingSet=
AmbientCapabilities=
RestrictSUIDSGID=true
RestrictNamespaces=true
RestrictRealtime=true
LockPersonality=true

# Syscalls: seccomp allow-list for a network service.
SystemCallArchitectures=native
SystemCallFilter=@system-service
SystemCallFilter=~@privileged ~@resources ~@obsolete
SystemCallErrorNumber=EPERM

# Network: block cloud metadata and link-local addresses.
# With --keets, add AF_NETLINK here (the Node.js sidecar needs it for interface discovery).
RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
IPAddressDeny=169.254.0.0/16 fe80::/10

# Resource ceilings: MemoryMax sits ABOVE -Xmx (heap + 512M).
MemoryMax=3584M
TasksMax=1024
LimitNOFILE=65536

# Logs go to the journal. The node never prints the seed to stdout.
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Notes:

- The whole `-Dparlons.node.args=…` property is quoted, because it is one argument to the JVM and
  can contain spaces. systemd honours double quotes. Do not put double quotes inside the value.
- If you pass Minima's `-data <dir>` in `parlons.node.args`, add that directory to `ReadWritePaths`.
- For a password-locked node, add `-Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt`
  (owner `maxima`, mode 600), or set `PARLONS_NODE_PASSPHRASE` in an `EnvironmentFile=` with mode 600.
- Keep a backup of the unit before each change. The convention is
  `parlons-node.service.before-<what>`.

### How to change the unit

1. Back up the current unit:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-<what>
   ```

2. Edit it:

   ```bash
   nano /etc/systemd/system/parlons-node.service
   ```

3. Check the unit for errors:

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   ```

4. Load it and restart the node:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

5. Wait about 25 s, then check that it runs and read the boot line:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|minima flags|REFUSING' | grep -v seed
   ```

6. If it does not come up, put the backup back and repeat step 4:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

### How to switch an old unit to `Restart=always`

1. Check what the unit says:

   ```bash
   grep -n '^Restart=' /etc/systemd/system/parlons-node.service
   ```

2. If it prints `Restart=on-failure`, change it:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-restart-always
   sed -i 's/^Restart=on-failure$/Restart=always/' /etc/systemd/system/parlons-node.service
   systemctl daemon-reload
   ```

   The new setting takes effect at once; no restart is needed.

---

## 8. Configuration reference: every property

All properties are JVM system properties (`-Dname=value`) placed before `-jar`. This list comes
from a grep of every `System.getProperty`, `Integer.getInteger`, `Long.getLong` and `getenv` call
in the modules bundled into the jar.

### How to change a setting

1. Find the property in the tables below.
2. Add or change its `-D…` line in the `ExecStart` of the unit, before `-jar`, and restart the
   node ([§7, How to change the unit](#how-to-change-the-unit)). If you deployed with the script,
   you can instead re-run the script with the matching flag ([§5](#5-three-ways-to-install)); it
   rewrites the unit from its flags.
3. Check that the node took it. The boot line shows the ports, and the `minima flags:` line shows
   Minima's own flags:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|minima flags' | grep -v seed
   ```

### How to pass Minima's own flags in a conf file

Use this for secrets such as `mdspassword`, so they never appear on the command line.

1. Write the file, one `key=value` per line. A bare `key` is a boolean flag. Leave out the
   leading `-`:

   ```bash
   cat > /var/lib/parlons-node/minima.conf <<'EOF'
   mdspassword=<your MDS password>
   EOF
   chown maxima:maxima /var/lib/parlons-node/minima.conf
   chmod 600 /var/lib/parlons-node/minima.conf
   ```

2. Add `-Dparlons.node.conf=/var/lib/parlons-node/minima.conf` to the unit and restart
   ([§7](#how-to-change-the-unit)).
3. Check the `minima flags:` line in the log. The `mdspassword` value is masked there.

### 8.1 Node and chain

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.node.data` | Data directory. Precedence: this property, then Minima's `-data` (its parent folder), then `~/.parlons-node`. | `~/.parlons-node` | `/var/lib/parlons-node` |
| `parlons.node.port` | Minima P2P port (public). The derived ports follow it: MDS files `+2`, MDS commands `+3`, admin RPC `+4`. | `9001` (Minima's default, unless `-port` is in the args) | `9101` |
| `parlons.node.rootnode` | A Minima peer (`host:port`) to discover the chain from. This fork ships an **empty** default peer list, so without this (or `connect`) the node boots but never syncs. | empty | `seed-peer.example.net:9001` |
| `parlons.node.connect` | A fixed peer list. Setting it **turns P2P discovery off**: static peers only. | empty | `a.example.net:9001,b.example.net:9001` |
| `parlons.node.megammr` | Keep the full MegaMMR, so `coins`/`balance megammr:true address:<any>` work for any address. The wallet gateway needs it. Precedence: this property, then Minima `-megammr`, then `true`. | `true` | `false` |
| `parlons.node.rpc` | Start the loopback admin RPC on `127.0.0.1:<p2p+4>`. If it cannot bind, the node exits with code 2. | `false` | `true` |
| `parlons.node.args` | Minima's own startup flags, run through Minima's own parser. Some flags are refused (see 8.8). Env fallback: `PARLONS_NODE_ARGS`. | empty | `"-host 203.0.113.10 -archive"` |
| `parlons.node.conf` | A `key=value` file of Minima flags, read **before** `parlons.node.args`. This is where secrets such as `mdspassword` belong, in a file with mode 600. A bare `key` line is a boolean flag. | empty | `/var/lib/parlons-node/minima.conf` |
| `parlons.node.passphrase.file` | A file holding the passphrase of a password-locked node. The node tries to unlock once at boot. Env alternative: `PARLONS_NODE_PASSPHRASE`. The passphrase must not contain whitespace, `"` or `;`. | empty | `/var/lib/parlons-node/passphrase.txt` |
| `parlons.node.archive` | The archive/MegaMMR host used by a **wallet resync** requested from a paired device (`megammrsync action:resync host:<this>`). | a compiled-in project archive node (see `NodeAccountWallet.java`) | `archive.example.net:9001` |
| `parlons.node.selftest.send` | **Developer test only.** At boot, sends `<address>[,<amount>]` from the node wallet (default amount `0.001`). On a funded node **this sends real funds**. Never set it in production. | empty | — |

### 8.2 Relay cape (Maxima)

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.relay.port` | The relay port. A number gives the relay its own listener. `0` turns the relay off (the account then uses fleet relays). `shared` makes the relay share the Minima P2P port: the node hands each connection that greets as a Parlons client over to the relay, so there is one public port. | `9501` | `8001`, `0`, `shared` |
| `parlons.relay.host` | The relay's public host name or IP. If unset, the node uses the public address it learned from its peers, and keeps re-checking (every 30 s for the first 30 minutes until it has one, then every 10 minutes, so a changed dynamic IP is followed). Private, loopback and CGNAT addresses are never used. | learned | `node.example.com` |
| `parlons.relay.peers` | The mesh bootstrap list (`host:port`, comma-separated). If unset, the compiled-in `Bootstrap.RELAYS` list is used. The account also uses these as mesh peers. | the compiled-in list | `peer1.example.net:9501,peer2.example.net:8001` |
| `parlons.relay.blob` | Size of the media blob shelf in MB (where users' photos are held). `0` = off. | `0` | `1024` |
| `parlons.relay.maxconn` | Maximum number of connections the relay holds. `0` keeps the built-in default: 512 on platform threads, 4096 on virtual threads (JDK 21+). | `0` | `8192` |

Relay-level properties from `RelayServer` (these also apply inside the node):

| Property | Meaning | Default |
|---|---|---|
| `maxima.relay.maxpersource` | Maximum concurrent connections from one source IP (a household behind NAT counts as one source). | `32` |
| `maxima.relay.shed` | Soft client target. Above it, the relay asks up to 4 clients per tick to move elsewhere. `0` = never ask. | `384` |
| `maxima.relay.replicate` | How many random verified pool peers each signed directory entry is copied to. `0` = off. | `3` |
| `maxima.relay.vthreads` | Use virtual threads when the JDK has them. `false` forces platform threads. | `true` |
| `maxima.pow.enforce` | Reject messages that lack the protocol's minimum proof-of-work. When false, they are only counted. Env: `MAXIMA_POW_ENFORCE=true`. | `false` |
| `maxima.mls.open` | Open-resolve pool default. **No effect on the node:** the node always sets pool mode on. | — |

Fixed in code (not configurable): protocol `1.0.48`; relay rate cap 600 messages per minute per destination.

Environment: `MAXIMA_STUN=false` turns off the UDP STUN responder on the relay port.

### 8.3 Wallet gateway and NFT

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.gateway.port` | Port of the wallet gateway (`/cmd`) and NFT server (`/nft`). | `9585` | `9585` |
| `parlons.gateway.bind` | Interface the gateway listens on. Keep it on loopback behind TLS. | `127.0.0.1` | `127.0.0.1` |
| `parlons.gateway.token` | Bearer token. If unset, the node reads `<data>/gateway-token.txt`, or generates a 256-bit token there (mode 600). | from file | — |
| `parlons.gateway.threads` | Handler threads. Each one blocks for one node command. | `8` | `16` |
| `parlons.gateway.rate.global` | Global `/cmd` request rate (requests/s; burst = 2 s worth). `0` = off. | `50` | `100` |
| `parlons.gateway.rate.perip` | Per-IP `/cmd` rate. Only meaningful when the gateway is exposed directly, because behind a proxy every request comes from 127.0.0.1. | `10` | `0` |
| `parlons.gateway.rate.nft.global` | Global `/nft` rate. | `400` | — |
| `parlons.gateway.rate.nft.perip` | Per-IP `/nft` rate. | `40` | — |
| `parlons.node.public` | Public base URL of the gateway's TLS front, **without** `/cmd`. It sets the NFT URLs. On a MegaMMR node with the relay on, it also advertises the gateway to phones in the relay greeting. | empty | `https://node.example.com/parlons-node` |

### 8.4 DEX relay cape

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.dex.port` | Port of the DEX relay cape. `0` = off. | `0` | `9602` |
| `parlons.dex.bind` | Interface it listens on. An explicit value wins. If unset, the cape binds `127.0.0.1` when `parlons.dex.public` is set, else `0.0.0.0`. Keep it on loopback and front it with TLS. | `127.0.0.1` with `parlons.dex.public`, else `0.0.0.0` | `127.0.0.1` |
| `parlons.dex.public` | Public base URL announced in the mesh. If unset, the node announces `http://<public host>:<port>` once it knows its public host. | empty | `https://node.example.com/dex-relay` |

Fixed in code: DEX relay version `0.1.5`; global rate 200 requests/s and per-IP rate 20 requests/s
(see [§16](#16-the-dex-relay-cape-dexicon) for what this means behind a proxy).

### 8.5 Account and panel

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.account` | Run the Parlons account. `false` = a relay and gateway only node. | `true` | `false` |
| `parlons.account.name` | Display name (used on first boot only). | empty | `Example Node` |
| `parlons.account.relays` | Extra relays the account attaches to (csv). | empty | `peer1.example.net:9501` |
| `parlons.account.builtin` | `false` = do not seed from the compiled-in relay list. The account then starts from `account.relays` plus relays it remembers. | `true` | `false` |
| `parlons.account.direct` | Port for the account's direct (Tier-2) listener. `0` = off. | `0` | — |
| `parlons.panel.port` | Local web panel and JSON API, on `127.0.0.1` only. `0` = off. | `9587` | `0` |
| `parlons.hosted.dir` | Directory of hosted (guest) accounts. Env: `PARLONS_HOSTED_DIR`. If unset, the node enables managed hosting for use with a separate tenants service. | unset | `/var/lib/parlons-tenants` |
| `parlons.hosted.limit` | Maximum number of hosted accounts. | `10` | — |
| `parlons.restore` | Restore a portable account bundle (`.pbk`) into a **fresh** data directory, then exit. Needs an interactive console for the passphrase. | empty | `/root/backup.pbk` |

### 8.6 MDS and keets

| Property | Meaning | Default | Example |
|---|---|---|---|
| `parlons.mds` | Serve MDS (MiniHUB and MiniDapps) on `https://<bind>:<p2p+2>/`. Same as Minima's `-mdsenable`. | off | `true` |
| `parlons.mds.bind` | Interface MDS listens on. Precedence: this property, then Minima `-mdsbind`, then `127.0.0.1`. `0.0.0.0` or `*` means every interface. | `127.0.0.1` | `127.0.0.1` |
| `parlons.node.keetschat` | `off`/`false`/`0`/`no` hands the keets inbox to the host app, and the bridge does not start. | `on` | `off` |

### 8.7 Environment variables

| Variable | Meaning |
|---|---|
| `PARLONS_NODE_ARGS` | Fallback for `parlons.node.args`. |
| `PARLONS_NODE_PASSPHRASE` | Passphrase for a locked node (use an `EnvironmentFile` with mode 600). |
| `PARLONS_HOSTED_DIR` | Fallback for `parlons.hosted.dir`. |
| `MAXIMA_STUN` | `false` disables the UDP STUN responder. |
| `MAXIMA_POW_ENFORCE` | `true` enforces the proof-of-work floor. |

### 8.8 Minima flags the node refuses

Flags in `parlons.node.args` or `parlons.node.conf` pass to Minima's own parser, **except** the
ones below. The node prints the reason and exits with code 2 before it touches the data directory.
An unknown flag also exits with code 2.

| Flags | Why | Use instead |
|---|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | The stock RPC binds every interface with full admin rights. | `-Dparlons.node.rpc=true` (loopback only) |
| `-seed -anyseed -dbpassword` | A secret in argv is readable by every process (`ps`). | `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | They wipe the data dir or leave mainnet. | — |
| `-daemon -noshutdownhook -jnlp -help` | The node's JVM manages stdin, exit and the shutdown hook itself. | — |

Explicit `-Dparlons.node.*` properties are applied after Minima's flags and win on conflict. The
boot log shows the applied flags as `minima flags: …`, with the `mdspassword` value masked.

### 8.9 Exit codes

| Code | Meaning |
|---|---|
| `0` | Clean stop: `quit` over the admin RPC, or the embedded node shut itself down after a resync, restore or reset. |
| `2` | Refused to start: unknown CLI argument, refused or unknown Minima flag, unreadable `-conf` file, or the admin RPC could not bind. |
| `3` | Restart requested: a wallet resync or an identity import from a paired device. systemd should restart it (`RestartForceExitStatus=3`). |

---

## 9. Ports

| Port (default) | Protocol | What | Bind | Exposure |
|---|---|---|---|---|
| `9001` (`parlons.node.port`) | TCP | Minima P2P | all | **Public.** Open it in the firewall. |
| `p2p+2` (`9003`) | TCP/HTTPS | MDS file server / MiniHUB (only with `parlons.mds=true`) | `127.0.0.1` | Loopback. Never public without your own TLS and firewall. |
| `p2p+3` (`9004`) | TCP | MDS command port (only with MDS on) | follows MDS | Loopback. |
| `p2p+4` (`9005`) | TCP/HTTP | Admin RPC (only with `parlons.node.rpc=true`) | `127.0.0.1`, hard-coded | **Never expose. Never proxy.** |
| `9501` (`parlons.relay.port`) | TCP | Maxima relay cape | all | **Public.** Phones and mesh peers connect here. |
| `9501` | UDP | STUN binding responder on the relay port | all | Optional. The deploy script does not open UDP. |
| `9585` (`parlons.gateway.port`) | TCP/HTTP | Wallet gateway `/cmd` and `/nft` | `127.0.0.1` | Loopback, behind a TLS front at `/parlons-node/`. |
| `9587` (`parlons.panel.port`) | TCP/HTTP | Account panel and API | `127.0.0.1`, hard-coded | Loopback. Reach it with an SSH tunnel. |
| `9602` (`parlons.dex.port`, off by default) | TCP/HTTP | DEX relay cape | `127.0.0.1` when `parlons.dex.public` is set, **else `0.0.0.0`**; `parlons.dex.bind` overrides | Loopback, behind a TLS front at `/dex-relay/`. |
| `49737` (`parlons.keets.dhtport`, fleet jar only) | UDP | keets HyperDHT bootstrap node | all | **Public.** Open it in the firewall. |
| `p2p+5` (`9006`, with `-keets`) | TCP/HTTP | keets loopback API (token in `<data>/keets/apitoken`) | `127.0.0.1`, hard-coded | Never expose. |

Port conflicts to know about:

- **A stock Minima node on the same box** already holds 9001 (and 9005). Move the Parlons Node with
  `-Dparlons.node.port=9101`. Its derived ports move too (MDS 9103/9104, RPC 9105).
- **`maxima-relay.service`** (the old standalone relay) holds 9501. Stop and disable it first
  (the deploy script's `--replace-relay`).
- **A `parlons-tenants` service** next to the node must not use 9587. Give it `--panel-port 9597`;
  tenants then take 9597, 9598 and so on, on loopback.
- On a box where only one port is forwarded (a home router), use `-Dparlons.relay.port=shared` or
  pick a relay port you can forward.

### How to move the node off port 9001

Use this when a stock Minima node already runs on the box.

1. Pick a free base port, for example 9101. Check that it and the next four are free:

   ```bash
   ss -ltnp | grep -E ':(9101|9103|9104|9105) '
   ```

2. Set `-Dparlons.node.port=9101` in the unit and restart ([§7](#how-to-change-the-unit)). With the
   deploy script, pass `--p2p-port 9101` instead.
3. Open the new P2P port:

   ```bash
   ufw allow 9101/tcp
   ```

4. Use the new admin RPC port (`9105`) in every `curl` command in this manual.

### How to see which ports the node holds

```bash
ss -ltnp | grep java
ss -lunp | grep java
```

The first command lists the TCP listeners and their bind addresses; the second lists the UDP STUN
responder.

---

## 10. Data directory layout

All paths are relative to `parlons.node.data` (default `/var/lib/parlons-node` in the deploy
script). The directory and every secret file must be owned by the service user, and private to it.

| Path | What | Secret? |
|---|---|---|
| `<base version>/` | The Minima node's own data: databases, wallet, chain. The MegaMMR file is `<base version>/databases/megammr.mmr` (the deploy script uses the path `1.1/databases/megammr.mmr`). | **Yes.** It holds the wallet. |
| `identity.txt` | The pinned identity phrase (or seed hex) the Maxima identity is derived from. Mode 600. | **Yes. It is a seed.** |
| `identity-anyphrase.txt` | A custom (non-BIP39) identity phrase, when one was imported. | **Yes.** |
| `gateway-token.txt` | Wallet gateway bearer token. Mode 600. | Low (read and relay only, and advertised to phones; see [§14](#14-the-wallet-gateway-and-its-token)). |
| `pair-code.txt` | The current unused one-time pairing code. | **Yes**, until it is used. |
| `account.txt` | The account's permanent address (`MAX#…`). | No. |
| `invite.txt` | The address plus the one-time code. | **Yes**, until the code is used. |
| `panel-ticket.txt` | A one-time sign-in link for the local panel. | **Yes.** |
| `local-device.key` | The panel's local-device key. Delete it and restart to re-enable a panel you revoked. | **Yes.** |
| `devices.json` | Paired devices. | Private. |
| `cloud-settings.properties` | Account settings. | Private. |
| `node/`, `chat/`, `media/`, `private-files/` | Account store, chat history, media. | Private. |
| `relay/` | Relay state: directory, mailbox items, blob shelf. | Private. |
| `nft/` | Hosted NFT files (`<sha256>.<ext>`, `c/<16-hex>/…`), plus `tmp/` for uploads in progress. | Public content. |
| `account-history/<import-id>/previous/` | The previous identity after an identity import. | **Yes.** |
| `.seed-adopted` | Marker written by the deploy script's `--seed-from`. | No. |

### How to check the data directory's permissions

1. List the owner and mode of the directory and the secret files:

   ```bash
   ls -ld /var/lib/parlons-node
   ls -l /var/lib/parlons-node/*.txt
   ```

2. The directory must be `drwx------ maxima maxima`, and each `.txt` secret `-rw------- maxima maxima`.
   If not, fix them:

   ```bash
   chown -R maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   chmod 600 /var/lib/parlons-node/*.txt
   ```

---

## 11. TLS fronting: Caddy, nginx, Apache

The gateway (9585) and the DEX cape (9602) speak plain HTTP on loopback. A web server on the
same box terminates TLS and forwards these two paths:

| Public path | Forward to | Prefix |
|---|---|---|
| `https://node.example.com/parlons-node/…` | `http://127.0.0.1:9585/…` | strip `/parlons-node` (so `/parlons-node/cmd` reaches `/cmd`, and `/parlons-node/nft/…` reaches `/nft/…`) |
| `https://node.example.com/dex-relay/…` | `http://127.0.0.1:9602/…` | strip `/dex-relay` (so `/dex-relay/dex/health` reaches `/dex/health`) |

Then set `parlons.node.public` and `parlons.dex.public` to the public URLs (step 4 below).

Never forward the admin RPC (`p2p+4`), the panel (9587) or MDS.

Sizes and timeouts that matter:

- The gateway accepts request bodies up to 1 MiB (a signed `txnimport` is the largest), and sends
  responses up to 12 MiB.
- DEX long-polls are held open for up to 25 s. Order envelopes are at most 16 KiB; message
  envelopes at most 256 KiB.
- Most proxies' default read timeout (60 s) is enough. If you have lowered it, keep it above 30 s.

### How to set up any TLS front

1. Point a DNS name (here `node.example.com`) at the box, and open TCP 443 (and 80 for the
   certificate challenge):

   ```bash
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Check that the node's two HTTP services answer on loopback (the DEX one only if you turned it on):

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -X POST http://127.0.0.1:9585/cmd   # 401 = gateway up
   curl -s http://127.0.0.1:9602/dex/health
   ```

3. Configure one of Caddy, nginx or Apache with the steps below.
4. Set the public URLs in the unit and restart the node ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

5. Test from another machine:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   curl -s -o /dev/null -w '%{http_code}\n' -X POST https://node.example.com/parlons-node/cmd   # 401
   ```

### Caddy

Caddy gets and renews the certificate by itself.

#### How to front the node with Caddy

1. Install Caddy from its official packages (<https://caddyserver.com/docs/install>).
2. Back up the Caddyfile:

   ```bash
   cp /etc/caddy/Caddyfile /etc/caddy/Caddyfile.before-parlons-node
   ```

3. Add these blocks to the site block for your host name in `/etc/caddy/Caddyfile`:

   ```caddy
   node.example.com {
       # DEX relay cape: use `handle` + `uri strip_prefix`, NOT `handle_path` (see the trap below).
       handle /dex-relay/* {
           uri strip_prefix /dex-relay
           reverse_proxy 127.0.0.1:9602
       }

       # Wallet gateway + NFT files.
       handle /parlons-node/* {
           uri strip_prefix /parlons-node
           reverse_proxy 127.0.0.1:9585
       }

       # Your site's catch-all (if any) goes LAST.
       handle {
           root * /var/www/html
           file_server
       }
   }
   ```

4. Check the file, then reload Caddy:

   ```bash
   caddy validate --config /etc/caddy/Caddyfile
   systemctl reload caddy
   ```

5. Test from another machine (step 5 of [How to set up any TLS front](#how-to-set-up-any-tls-front)).
   If `/dex-relay/dex/health` returns your web page instead of JSON, you have hit the trap below.

Caddy's `reverse_proxy` sends `X-Forwarded-For` by default, so the DEX cape rate-limits each
client separately.

> **Caddy ordering trap: `handle_path` vs `handle`.** Caddy sorts directives by a fixed directive
> order before it applies them, and `handle` sorts **before** `handle_path`. Among `handle` blocks,
> the more specific path matchers go first, and a `handle` with no matcher goes last. `handle`
> blocks are mutually exclusive: the first one that matches wins. So if the site block has a
> catch-all `handle { … }`, a `handle_path /dex-relay/*` sorts **after** it and is never reached:
> the catch-all answers every DEX request. Fix: write `handle /dex-relay/* { uri strip_prefix
> /dex-relay; reverse_proxy … }`, which is sorted with the other `handle` blocks by path. In a
> site block with no catch-all `handle`, `handle_path /dex-relay/*` works.

#### How to add per-client rate limiting in Caddy (optional)

The node's own per-IP gateway bucket sees only `127.0.0.1` behind a proxy, so limit each client
in Caddy instead. This needs the third-party `rate_limit` module, which is not part of stock Caddy.

1. Build or download a Caddy binary that includes the `rate_limit` module.
2. Replace the gateway block with:

   ```caddy
       handle /parlons-node/* {
           rate_limit {
               zone phones {
                   key    {remote_host}
                   events 60
                   window 1m
               }
           }
           uri strip_prefix /parlons-node
           reverse_proxy 127.0.0.1:9585
       }
   ```

3. Validate and reload, as in steps 4 and 5 above.

### nginx

#### How to front the node with nginx

1. Get a certificate for the host name (for example with certbot) and have a
   `server { listen 443 ssl; server_name node.example.com; … }` block for it.
2. Back up the site file you are about to change:

   ```bash
   cp /etc/nginx/sites-available/<your site> /etc/nginx/sites-available/<your site>.before-parlons-node
   ```

   If the box has a file-integrity monitor watching the nginx config, an edit will trip it. Tell
   whoever owns that monitor before you change the file.
3. Add these blocks inside the `server { … }` block:

   ```nginx
   # DEX relay cape. The trailing slashes strip the prefix.
   location /dex-relay/ {
       proxy_pass http://127.0.0.1:9602/;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
       proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
       proxy_read_timeout 60s;
   }

   # Wallet gateway + NFT files.
   location /parlons-node/ {
       proxy_pass http://127.0.0.1:9585/;
       proxy_http_version 1.1;
       proxy_set_header Host $host;
       client_max_body_size 2m;
   }
   ```

4. Check the configuration, then reload nginx:

   ```bash
   nginx -t
   systemctl reload nginx
   ```

5. Test from another machine (step 5 of [How to set up any TLS front](#how-to-set-up-any-tls-front)).

Notes:

- `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` is needed in the `/dex-relay/`
  block. nginx does not send the header by default, and without it every DEX client shares one
  rate-limit bucket ([§16](#16-the-dex-relay-cape-dexicon)).
- `client_max_body_size 2m` gives room above the gateway's 1 MiB body limit (the nginx default is
  `1m`).

### Apache

#### How to front the node with Apache

1. Have a TLS virtual host (`<VirtualHost *:443>`) for the host name, for example from certbot.
2. Enable the proxy modules:

   ```bash
   a2enmod proxy proxy_http
   ```

3. Put this in a snippet included from the TLS virtual host:

   ```apache
   # DEX relay cape
   ProxyPass        /dex-relay/ http://127.0.0.1:9602/
   ProxyPassReverse /dex-relay/ http://127.0.0.1:9602/

   # Wallet gateway (POST /cmd) and NFT files (GET/HEAD /nft/...)
   <Location "/parlons-node/cmd">
       ProxyPass        http://127.0.0.1:9585/cmd
       ProxyPassReverse http://127.0.0.1:9585/cmd
   </Location>
   <Location "/parlons-node/nft">
       ProxyPass        http://127.0.0.1:9585/nft
       ProxyPassReverse http://127.0.0.1:9585/nft
   </Location>
   ```

4. Check the configuration, then reload Apache:

   ```bash
   apachectl configtest
   systemctl reload apache2
   ```

5. Test from another machine (step 5 of [How to set up any TLS front](#how-to-set-up-any-tls-front)).

Apache's `ProxyPass` sends `X-Forwarded-For` by default. For the gateway, the node's own global
token buckets are the rate limit behind Apache.

---

## 12. The relay cape and the mesh

### What the cape does

The cape is a full Maxima relay. It speaks classic Maxima on the wire, byte for byte, plus the
Parlons extensions: store-and-forward mailbox, directory, delivery receipts, gossip and mesh. A
phone has no public address, so it dials out to the cape and keeps the connection open; messages
for it come back down that connection. A stock Minima node can use the cape as its Maxima host too.

The cape always runs as a **pool host**: an open-resolve directory host and a mesh forwarder.

### Identity

The cape's identity (`Mx…`) is derived from `identity.txt`, which is pinned from the node's seed on
first boot. A box that previously ran `maxima-server.jar` can keep its relay identity: the deploy
script's `--seed-from /var/lib/maxima/seed.txt` resyncs the node wallet to that phrase and pins it.

### Public host

The cape must know its public address. It uses this address to name itself in peer lists, to stay
out of its own forwarding, to anchor the account's permanent address, and to build the DEX URL it
announces. It is found in this order:

1. `-Dparlons.relay.host`, if you set it.
2. Otherwise, the public address the Minima node learned from its peers
   (`network` → `details.p2p.address`), then `status` → `network.host`.
3. If neither is public yet, a background thread re-checks every 30 s for the first 30 minutes,
   then every 10 minutes. When it finds an address, it logs
   `public address learned from peers: <ip> - the cape now names itself <ip>:<port>`.

Never set the relay host to a bind address such as `0.0.0.0`. Classic Minima treats `0.x`
addresses as internal and rejects the whole relay.

### Gossip and verified peers

From `core/.../session/RelayPeers.java`:

- A peer can only nominate **itself**. A claim in a greeting is accepted only if the claimed host
  equals the source IP of the connection.
- Every claim is **verified by dialling back** (4 s connect, 2 s read). A peer is promoted only if
  a real Maxima greeting comes back.
- Peers learned from other relays' greetings are also dialled back before they are used.
  Non-public addresses are skipped.
- At most **250** verified peers are held (`MAX_PEERS`). Each greeting shares a shuffled list of up
  to **50** (`SHARE_LIMIT`).
- **TTL 2 h** (`TTL_MS`). A verified peer that is not re-claimed or successfully re-dialled within
  2 hours expires.
- **Re-dial every 10 min** (`REDIAL_MS`). When its queue is idle, the check
  thread re-dials every verified peer it has not dialled in 10 minutes. This keeps each peer's
  advertised details (pool bit, DEX URL, gateway) current, and a successful re-dial refreshes the
  TTL. A dead peer fails the dial and expires.

### The mesh and `parlons.relay.peers`

- `parlons.relay.peers` is a **bootstrap** list, not a trust list and not a single point of failure.
  Trust comes from the publisher's signature, so a dead or hostile peer is simply skipped.
- On a directory miss, the relay forwards the query to its pool peers. Forwarding is strictly one
  hop, with fan-out capped at 8. The relay verifies the signed answer, caches it briefly and replies.
- Each accepted signed directory entry is copied to `maxima.relay.replicate` (default 3) random pool
  peers, so an address keeps resolving while its home relay is down.
- If `parlons.relay.peers` is not set, the compiled-in bootstrap list is used. The log says
  `mesh: N bootstrap peer(s) (default fleet list)`.
- List every other relay you want to be sure of reaching, as `host:port`. Use the port each peer's
  relay actually listens on; a home box may use 8001 instead of 9501.

### Capacity

- Default connection cap: 512 on platform threads, 4096 on virtual threads (JDK 21+). Override with
  `parlons.relay.maxconn`. The relay logs which mode and cap it uses at start.
- Per-source-IP cap: 32 (`maxima.relay.maxpersource`).
- An unregistered connection (one that never becomes a client) is dropped after 10 minutes. Idle
  unregistered connections are dropped after 120 s.
- Above `maxima.relay.shed` (384) registered clients, the relay asks up to 4 clients per tick to
  move to another relay. The client chooses where to go.

### Shared-port mode

With `-Dparlons.relay.port=shared`, the relay has no listener of its own. The Minima P2P listener
hands it every connection that greets as a Parlons client. One public port then serves both the
chain and the relay. The desktop apps use this when "contribute" is on. Fleet boxes keep an
explicit 9501.

### How to check that the cape is up

1. Look for the cape and mesh lines in this boot's log:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Maxima cape|mesh:|public address learned|own relay' | grep -v seed
   ```

   You want `Maxima cape up on port 9501 — identity Mx…` and `mesh: N bootstrap peer(s)`.
2. Check the heartbeat. It must show `cape=up:9501`:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Test it end to end from another machine ([§23](#23-verifying-from-outside), step 2).

### How to set the mesh bootstrap peers

1. Collect the `host:port` of each relay you want to reach. Use the port each relay really
   listens on.
2. Set them as one comma-separated value in the unit, then restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.peers=peer1.example.net:9501,peer2.example.net:9501,peer3.example.net:8001
   ```

   With the deploy script, pass `--peers "peer1.example.net:9501,peer2.example.net:9501"`.
3. If the box blocks outbound traffic by default, allow each peer's relay port
   ([§25](#25-firewall-notes)).
4. Check the boot log. It must say `mesh: N bootstrap peer(s)` with your count:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'mesh:'
   ```

### How to set the public host

Do this when the log says `own relay NOT advertised`, or when the box has a fixed name you want
the cape to use.

1. Add the public host name or IP to the unit, then restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.host=node.example.com
   ```

   Never use a bind address such as `0.0.0.0`, or a private, loopback or CGNAT address.
2. Check that the warning is gone:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised' || echo advertised-ok
   ```

### How to run the relay on the Minima P2P port (shared-port mode)

1. Set the relay port to `shared` in the unit, then restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.port=shared
   ```

2. Open (or forward) only the P2P port, for example 9001. Remove the 9501 rule if you no longer need it:

   ```bash
   ufw delete allow 9501/tcp
   ```

3. Tell your users and mesh peers to use `node.example.com:9001` as this relay's address.

### How to turn on the media blob shelf

1. Set the shelf size in MB in the unit, then restart ([§7](#how-to-change-the-unit)). With the
   deploy script, pass `--blobstore 1024`:

   ```
   -Dparlons.relay.blob=1024
   ```

2. Make sure the disk has that much space free (`df -h /var/lib`).
3. Check the boot log. It must say `blob shelf: 1024 MB`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'blob shelf'
   ```

### How to raise the connection cap

1. Run the node on JDK 21 or newer. The cap then rises to 4096 by itself.
2. To set your own cap, add it to the unit and restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.relay.maxconn=8192
   ```

3. Raise `LimitNOFILE` in the unit too if the cap comes near it (the unit sets 65536).

### How to turn the relay off

Set `-Dparlons.relay.port=0` in the unit and restart. The account then uses other relays. Close
the relay port in the firewall.

---

## 13. MegaMMR mode and the rootnode

### Rootnode

This node fork ships an **empty default peer list**. Give it one of these:

- `-Dparlons.node.rootnode=host:port`: a Minima peer to discover from. The node then finds more
  peers by itself. Use any reachable Minima node you trust. `--help` prints the project's suggested
  rootnode.
- `-Dparlons.node.connect=host:port,…`: a fixed list, with discovery turned off.
- Minima's own `-connect` or `-nop2p` flags inside `parlons.node.args`.

Without one of these, the node boots and serves its local services, but `block=` never moves.

#### How to set the rootnode

1. Print the project's suggested rootnode, or pick a Minima node you trust:

   ```bash
   java -jar /opt/maxima/parlons-node.jar --help | grep -i rootnode
   ```

2. Check that the box can reach it (replace the host and port):

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

3. Add it to the unit, then restart ([§7](#how-to-change-the-unit)). With the deploy script, pass
   `--rootnode seed-peer.example.net:9001`:

   ```
   -Dparlons.node.rootnode=seed-peer.example.net:9001
   ```

4. Watch `block=` climb in the heartbeat:

   ```bash
   journalctl -u parlons-node -f | grep heartbeat
   ```

### MegaMMR

MegaMMR is **on by default** on the node; Minima's own default is off. With MegaMMR, the node
can prove coins for **any** address (`coins`/`balance megammr:true address:<any>`). This is what
makes it a wallet gateway for phones.

**Initial sync does not fill the MegaMMR.** A freshly synced node's MegaMMR is empty (about 20
bytes) and only tracks coins created from then on. Until you import a snapshot, balances for
existing addresses come back **empty**.

#### How to import a MegaMMR snapshot

Do this once, after the first sync. Run the commands on the box as root.

1. Make sure the admin RPC is on (`-Dparlons.node.rpc=true`, [§21](#21-the-admin-rpc)). This
   must print a JSON status:

   ```bash
   curl -s http://127.0.0.1:9005/status | head -c 200; echo
   ```

2. Check the current MegaMMR size. Under 1 MB means it is empty:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

3. Download a snapshot into the data directory and give it to the service user (about 460 MB;
   `-c` resumes a broken download):

   ```bash
   cd /var/lib/parlons-node
   wget -c -O mega.mmr https://eurobuddha.com/mega.mmr
   chown maxima:maxima mega.mmr
   ```

   To use your own snapshot instead, see the next procedure.
4. Import it over the loopback RPC (P2P port 9001 means RPC 9005):

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
   ```

   It must reply with `"status":true`. The import ends with a node shutdown.
5. Bring the node back. `Restart=always` restarts it after 10 s; to be sure, run:

   ```bash
   systemctl restart parlons-node
   ```

6. Wait about 20 s, then check that `megammr.mmr` is now over 1 MB:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

7. Query an address you know is funded. Its balance must not be empty:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<a funded Mx or 0x address>'
   ```

8. Delete the downloaded file:

   ```bash
   rm -f /var/lib/parlons-node/mega.mmr
   ```

The deploy script's `--rpc --megammr-seed <URL>` runs all of these steps, and skips them when the
MegaMMR is already populated.

#### How to make your own snapshot

1. On any MegaMMR node you control, with its admin RPC on, export the MegaMMR. This takes a read
   lock and needs no downtime:

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:export%20file:/var/lib/parlons-node/donor.megammr'
   ```

2. Copy the file to the new node's data directory, and give it to the service user:

   ```bash
   scp /var/lib/parlons-node/donor.megammr root@node.example.com:/var/lib/parlons-node/mega.mmr
   ssh root@node.example.com chown maxima:maxima /var/lib/parlons-node/mega.mmr
   ```

3. Delete the export on the donor, then import on the new node from step 4 of the procedure above.

#### How to run a node without MegaMMR

1. Add `-Dparlons.node.megammr=false` to the unit (or pass `--no-megammr` to the deploy script).
2. Lower the heap to suit (768 MB on a 2 GB box, [§3](#3-requirements)) and set `MemoryMax`
   512 MB above it.
3. Restart ([§7](#how-to-change-the-unit)). The node can no longer be a wallet gateway for other
   addresses.

**Order matters when you also adopt a seed:** run `--seed-from` **before** the MegaMMR import.
`megammrsync` resets the chain databases and throws away an imported MegaMMR.

A node with `megammr=false` cannot serve `megammr:true` reads. Its gateway answers those requests
with HTTP 503 (`this node has no MegaMMR - use a MegaMMR gateway node`), so phones fail over to
another gateway.

---

## 14. The wallet gateway and its token

### Contract

```
POST /cmd
Authorization: Bearer <token>
Content-Type: application/json

{"command":"balance megammr:true address:Mx..."}
```

The reply is the node's own JSON. Errors come back as `{"status":false,"error":"…"}` with these HTTP codes:

| Code | Meaning |
|---|---|
| 400 | bad JSON body, or empty command |
| 401 | missing or wrong bearer token |
| 403 | command not on the allow-list, or `coins`/`balance` without `address:` |
| 405 | not a POST |
| 429 | rate limit exceeded (checked **before** authentication) |
| 500 | internal gateway error (details only in the node log) |
| 502 | node response over 12 MiB |
| 503 | `megammr:true` on a node without MegaMMR |

### The allow-list

Only these commands pass. Every `;`-separated segment is checked, because the node runs every
segment as its own command:

- **Reads:** `status`, `tokens`, `balance`, `coins`, `checkaddress`, `tokenvalidate`, `block`
- **Track-only** (cannot move funds): `newscript`, `coinexport`, `coinimport`
- **Relay of a transaction the phone signed itself:** `txnimport`, `txnbasics`, `txnpost`, `txndelete`, `txncheck`

Everything else is refused: `send`, `vault`, `keys`, `sign`, `quit`, and so on. `coins` and
`balance` must include `address:`, so nobody can list the operator's own coins through the gateway.

### The token

- Precedence: `-Dparlons.gateway.token`, then `<data>/gateway-token.txt`, then a new random
  256-bit token written to that file with mode 600.
- The token is **read and relay only**. It cannot move the node's funds.
- **It is not a secret from phones.** On a MegaMMR node with `parlons.node.public` set, the relay
  greeting advertises the gateway URL (`<public>/cmd`) and its key, so phones can discover gateways
  along with relays. The log says `wallet gateway advertised to phones: <url>`.
- A fleet of gateways can share one token: copy the same `gateway-token.txt` to each box.

### Rate limits

- Global bucket: 50 requests/s, burst 100 (`parlons.gateway.rate.global`). This is the real
  backstop behind a TLS front.
- Per-IP bucket: 10 requests/s (`parlons.gateway.rate.perip`). Behind a loopback proxy, every
  request comes from `127.0.0.1`, so do per-client limiting in the proxy.
- `/nft` has its own separate buckets (400 global, 40 per IP).

### How to publish the gateway for phones

1. Run the node with MegaMMR on, and import the MegaMMR ([§13](#13-megammr-mode-and-the-rootnode)).
2. Keep the gateway on loopback (`-Dparlons.gateway.bind=127.0.0.1`, the default), and front
   `/parlons-node/` with TLS ([§11](#11-tls-fronting-caddy-nginx-apache)).
3. Set the public base URL, without `/cmd`, in the unit and restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

4. Check that the node advertises it:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'wallet gateway (up|advertised|FAILED)'
   ```

   You want `wallet gateway advertised to phones: https://node.example.com/parlons-node/cmd`.
5. Test it from another machine (next procedure).

### How to test the gateway

1. On the box, read the token into a shell variable. Do not paste it into shared chats or tickets:

   ```bash
   TOKEN=$(sudo cat /var/lib/parlons-node/gateway-token.txt)
   ```

2. Send a `block` command. It must return the node's JSON with `"status":true`:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

3. Send it without the token. It must return HTTP 401:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H 'Content-Type: application/json' \
        -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

4. Send a refused command. It must return HTTP 403:

   ```bash
   curl -s -o /dev/null -w '%{http_code}\n' -H "Authorization: Bearer $TOKEN" \
        -H 'Content-Type: application/json' -d '{"command":"vault"}' \
        https://node.example.com/parlons-node/cmd
   ```

### How to share one token across several gateways

1. Copy `gateway-token.txt` from the first box to each other box:

   ```bash
   scp root@node.example.com:/var/lib/parlons-node/gateway-token.txt ./gateway-token.txt
   scp ./gateway-token.txt root@node2.example.com:/var/lib/parlons-node/gateway-token.txt
   rm ./gateway-token.txt
   ```

2. On each box that received it, fix the owner and mode, then restart:

   ```bash
   chown maxima:maxima /var/lib/parlons-node/gateway-token.txt
   chmod 600 /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

### How to replace the token

1. Delete the token file and restart. The node writes a new 256-bit token at start:

   ```bash
   rm /var/lib/parlons-node/gateway-token.txt
   systemctl restart parlons-node
   ```

2. Wait until the wallet is up (`wallet gateway up` in the log), then read the new token:

   ```bash
   sudo cat /var/lib/parlons-node/gateway-token.txt
   ```

3. Give the new token to anything that was set up with the old one by hand. Phones that found
   the gateway through the relay greeting receive the new key there.

If `-Dparlons.gateway.token` is set in the unit, it wins over the file: change it there instead.

### How to change the gateway rate limits

1. Add the properties you want to change to the unit (values are requests per second; `0`
   turns a `/cmd` bucket off):

   ```
   -Dparlons.gateway.rate.global=100
   -Dparlons.gateway.rate.perip=0
   ```

2. Restart ([§7](#how-to-change-the-unit)).
3. Behind a TLS front, limit each client in the proxy instead
   ([§11, Caddy](#how-to-add-per-client-rate-limiting-in-caddy-optional)).

---

## 15. NFT hosting and `parlons.node.public`

The node serves the files that token metadata links to, so a marketplace or block explorer can
link straight to your box.

- **Storage:** `<data>/nft/`.
  - Single files are **content-addressed**: `<sha256>.<ext>`. The URL never changes, and anyone
    can check the bytes against the hash.
  - Collections: `c/<16-hex id>/<index>.<ext>` (index 0 to 9999), with a `manifest.json` that
    lists each item's sha256.
  - At most **32 MB** per file. Extensions are 2 to 5 lowercase letters or digits.
  - Abandoned uploads in `tmp/` are deleted after 24 h.
- **Upload:** paired devices only, over the encrypted control channel (`parlons.nft.put`,
  `parlons.nft.newcollection`, `parlons.nft.list`, `parlons.nft.delete`). Each upload is sent in
  chunks and checked against its sha256 before it is put in place. There is no public upload endpoint.
- **Serving:** `GET`/`HEAD /nft/<path>` on the gateway port. No token is needed. Only paths of the
  two shapes above can be reached. Responses carry:
  - `Cache-Control: public, max-age=31536000, immutable`
  - `X-Content-Type-Options: nosniff`
  - `Access-Control-Allow-Origin: *`
  - `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`, so an SVG
    opened directly cannot run script on your domain
  - `Content-Disposition: inline`
- **Public base:** `-Dparlons.node.public=https://node.example.com/parlons-node`. The public URL
  of a file is `<public>/nft/<path>`.
  - Without it, uploads still succeed, but the returned `url` is empty. The log says
    `nft hosting: … (no public base - set -Dparlons.node.public=https://host/parlons-node)`.
  - **Only set it to a URL that really routes to this box's 9585.** A copied URL from another box
    makes wallets publish links to a server that does not hold the files.
  - On a MegaMMR node, the same property also advertises the gateway to phones ([§14](#14-the-wallet-gateway-and-its-token)).
- Content types are set from the extension: png, jpg/jpeg, gif, webp, svg, avif, mp4, webm, mp3,
  json, txt, glb. Anything else is `application/octet-stream`.

### How to turn on NFT hosting

NFT hosting is always on; it needs only a public URL.

1. Front `/parlons-node/` to `http://127.0.0.1:9585/` with TLS
   ([§11](#11-tls-fronting-caddy-nginx-apache)).
2. Set the public base in the unit and restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.node.public=https://node.example.com/parlons-node
   ```

3. Check the log line:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'nft hosting'
   ```

   It must end with `served at https://node.example.com/parlons-node/nft/<file>`, not with
   `(no public base …)`.
4. Upload a file from a paired device. The upload returns the file's public `url`.
5. Check the file from another machine. It must return `200`:

   ```bash
   curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext> | head -1
   ```

### How to see and remove hosted files

1. List what the node holds:

   ```bash
   ls -l /var/lib/parlons-node/nft/ /var/lib/parlons-node/nft/c/
   ```

2. Remove a file from a paired device (`parlons.nft.delete`). A collection's `manifest.json` lists
   each item, so do not delete collection files by hand.
3. Check the disk use:

   ```bash
   du -sh /var/lib/parlons-node/nft
   ```

---

## 16. The DEX relay cape (Dexicon)

The DEX relay cape (`dexrelay/`, version `0.1.5`, no dependencies) is the order-book relay for
Dexicon. It runs **inside** the node; it is not a separate service. It is untrusted by design:

- Every order and message is a **signed envelope** (Ed25519), and the relay checks the signature
  before it accepts it.
- Accepted envelopes are passed on byte for byte, so every client checks the same bytes the relay checked.
- **Nothing is saved to disk.** A restarted relay starts empty, and makers republish on their next tick.
- There is no authentication and CORS is open, because each message authenticates itself.

### How to turn on the DEX relay cape

The one-line installer ([§5 A](#5-three-ways-to-install)) already does all of this: it sets
`parlons.dex.public` to `https://<name>/dex-relay` (fleet mode then opens the cape on loopback
9602) and fronts it with Caddy. The steps below are for a node installed another way.

1. Add the three properties to the unit, then restart ([§7](#how-to-change-the-unit)):

   ```
   -Dparlons.dex.port=9602
   -Dparlons.dex.bind=127.0.0.1
   -Dparlons.dex.public=https://node.example.com/dex-relay
   ```

   With the deploy script, pass `--dex 9602 --dex-public https://node.example.com/dex-relay`. The
   script then sets the loopback bind for you.
2. Front `/dex-relay/` to `http://127.0.0.1:9602/` ([§11](#11-tls-fronting-caddy-nginx-apache)).
   With nginx, include the `X-Forwarded-For` line.
3. Check the boot log:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'dex relay'
   ```

   You want these two lines:

   ```
   [parlons-node] dex relay 0.1.5 up on 127.0.0.1:9602 (GET /dex/health)
   [parlons-node] dex relay announced in the mesh as https://node.example.com/dex-relay (GET /dex/relays)
   ```

   If no public URL or host is known yet, the second line reads instead
   `dex relay: no public host known yet - NOT announced in the mesh until one is learned …`.
4. Check that the cape listens on loopback only. The address must be `127.0.0.1:9602`:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

5. Check it from another machine (see [Check it](#check-it) below).

**The bind.** An explicit `parlons.dex.bind` always wins. If it is unset, the cape binds
`127.0.0.1` when `parlons.dex.public` is set, and `0.0.0.0` when it is not. So if you set only
`parlons.dex.port`, the cape answers plain HTTP to the whole internet. Set `parlons.dex.public`
(and, to be explicit, `parlons.dex.bind=127.0.0.1`) and put TLS in front.

### How to turn off the DEX relay cape

1. Remove the three `parlons.dex.*` properties from the unit (or set `-Dparlons.dex.port=0`) and
   restart ([§7](#how-to-change-the-unit)).
2. Remove the `/dex-relay/` block from the TLS front and reload it.

### API

| Method and path | Purpose | Responses |
|---|---|---|
| `POST /dex/publish` | Publish one order envelope (at most 16 KiB). | `200 {"ok":true,"seq":…,"cursor":…}` · `400` invalid · `409` stale seq · `413` too large · `503` relay full |
| `GET /dex/book?market=&since=&hold=` | Order-book changes since a cursor. Long-polls for up to `hold` seconds (at most 25). `since=0` returns a full snapshot (`reset:true`). | `200 {"cursor":…,"reset":…,"orders":[…]}` · `503` busy |
| `POST /dex/msg` | Deliver one sealed trade message (at most 256 KiB). | `200 {"ok":true}` · `400` · `413` · `503` inbox full |
| `GET /dex/inbox?pk=&since=&hold=` | Messages for a public id (`pk` must be `0x` plus 64 bytes of hex). Long-polls for up to 25 s. | `200 {"cursor":…,"messages":[…]}` · `400` · `503` busy |
| `GET /dex/health` | Liveness. | `{"ok":true,"version":"0.1.5","orders":<n>,"cursor":<n>,"uptime":<s>}` |
| `GET /dex/relays` | Every DEX relay URL this host knows, itself included, learned through the relay mesh. | `{"ok":true,"relays":[{"url":"…","seen":<epoch s>}]}` |

Any other path returns 404. Rate limiting returns 429 with `{"ok":false,"error":"rate limited"}`.

### Limits (fixed in code)

| Limit | Value |
|---|---|
| Live orders | 20,000 (after that, `503 relay full`) |
| Messages held in total | 20,000 |
| Messages per recipient | 200 |
| Concurrent long-polls | 512 |
| Longest long-poll | 25 s |
| Order expiry | at most 24 h ahead |
| Message expiry | at most 600 s ahead |
| Coins per order | 32 |
| Expired-entry sweep | every 30 s |
| Rate | 200 requests/s global, 20 requests/s per IP (burst = 2 s worth) |

**Rate limiting behind a proxy.** Behind a loopback TLS front, every request arrives from `127.0.0.1`.
When a request comes from loopback, the cape rate-limits the **rightmost `X-Forwarded-For` entry**
(the one your proxy appended) instead of the connection address. A direct, non-loopback caller's
header is ignored, so nobody can choose their own bucket. Your front must send the header:
Caddy's `reverse_proxy` and Apache's `ProxyPass` do by default; nginx needs
`proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` in the `/dex-relay/` block. Without
the header, the per-IP bucket of 20 requests/s (burst 40) applies to **all public traffic together**.

### Discovery

- Each relay with a DEX URL puts that URL in its mesh greeting (`dex`). Peers record it when they
  dial back, and refresh it on the 10-minute re-dial.
- `GET /dex/relays` lists this relay's own URL plus every DEX URL advertised by its verified
  peers. Wildcard addresses (`0.0.0.0`, `::`) are never listed.
- A new relay appears in other relays' lists within about one re-dial cycle (10 minutes) after
  it is verified.
- Dexicon clients start from a built-in list of relays and merge `/dex/relays` from each one, so a
  slow mesh never blocks trading.

### Check it

1. From another machine, ask for the health record. It must return
   `{"ok":true,"version":"0.1.5",…}`:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health
   ```

2. List the DEX relays this one knows. Its own URL must be in the list:

   ```bash
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

3. Check that the plain port is closed from outside. This must print nothing:

   ```bash
   nc -z -w 5 node.example.com 9602 && echo "EXPOSED 9602"
   ```

4. About 10 minutes later, ask another DEX relay for its list. This node's URL should appear there too.

---

## 17. The account, pairing and the local panel

Every Parlons Node also runs the Parlons **account**: the same code as `parlons-cloud.jar`, on the
node's identity, with the node's own wallet and the cape as its relay. Phones pair with it from the
Parlons Cloud app. Turn it off with `-Dparlons.account=false`.

### How to pair your phone

Run these on the box, in an interactive SSH terminal. The app side of each step is in the
[Parlons Cloud Portal manual](parlons-cloud-portal.md#4-pair-the-app-to-your-account).

1. Set the admin pairing password once:

   ```bash
   sudo parlons-pair --set-admin-password
   ```

2. Show your account's address and a one-time owner invitation. A QR code is drawn if `qrencode`
   is installed:

   ```bash
   sudo parlons-pair --admin
   ```

3. Scan or paste the invitation in the Parlons Cloud app. A valid code pairs immediately.
4. Check the heartbeat. The device count in `account=up:… devices` goes up by one:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

`parlons-pair` reads `/var/lib/parlons-node` by default. Pass `--data DIR` if your data directory
is elsewhere.

The unused code is kept in `pair-code.txt` until it is used. A used code is never valid again.
Do not take codes from old logs or old `invite.txt` copies.

### How to host a guest account

Guest accounts need the separate `parlons-tenants` service next to the node. Set it up first
([Parlons Cloud Portal manual, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
Give it `--panel-port 9597` so it does not clash with the node's panel on 9587.

1. Make a guest invitation for a new account name:

   ```bash
   sudo parlons-pair --guest <name>
   ```

2. Send the invitation to your guest over a private channel. They pair from their own Parlons
   Cloud app.

More on invitations: [Parlons Cloud Portal manual, §23](parlons-cloud-portal.md#23-owner-and-guest-invitations-from-the-server).

### How to run a node without the account

Set `-Dparlons.account=false` in the unit and restart ([§7](#how-to-change-the-unit)). The node
then runs as a relay and gateway only. The heartbeat shows `account=off`.

### Own relay

When the cape is on and its public host is known, the account attaches to its own cape first,
advertises it first, and anchors its permanent address on it. If the host is unknown or private,
the log says `own relay NOT advertised: … set -Dparlons.relay.host=<public ip>`.

### How to open the local panel

1. From your computer, open an SSH tunnel to the panel port and leave it open:

   ```bash
   ssh -L 9587:127.0.0.1:9587 root@node.example.com
   ```

2. In that SSH session, read the one-time link:

   ```bash
   sudo cat /var/lib/parlons-node/panel-ticket.txt
   ```

   It looks like `http://127.0.0.1:9587/open?ticket=…`.
3. Open the link in a browser on your computer. The account makes a new link as soon as one is used.
4. If you revoked the panel before and want it back, delete the local-device key and restart:

   ```bash
   rm /var/lib/parlons-node/local-device.key
   systemctl restart parlons-node
   ```

The panel listens on `127.0.0.1:9587` only (`parlons.panel.port`; `0` turns it off). It cannot
reach the seed, backups, wallet sends, payments, the node console or NFT hosting. Those stay on
paired phones and the CLI. What it shows: [Parlons Cloud Portal manual, §24](parlons-cloud-portal.md#24-the-local-web-panel).

### How to restore a portable account onto a new node

A `.pbk` bundle is made from a paired device (Settings → **Back up account…**, see
[Parlons Cloud Portal manual, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)). It
holds the identity, paired devices, settings, contacts and chat.

1. Stop the host the backup came from **for good**: one identity, one live account.
2. Install the node ([§5](#5-three-ways-to-install)) but do not start it, or stop it and move its
   data directory away. The restore needs a **fresh** data directory:

   ```bash
   systemctl stop parlons-node
   mv /var/lib/parlons-node /var/lib/parlons-node.before-restore
   mkdir -p /var/lib/parlons-node
   chown maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Copy the bundle in and give it to the service user:

   ```bash
   cp backup.pbk /var/lib/parlons-node/backup.pbk
   chown maxima:maxima /var/lib/parlons-node/backup.pbk
   ```

4. Run the restore as the service user, in an interactive terminal. It asks for the bundle's
   passphrase, writes the account, then exits:

   ```bash
   sudo -u maxima java -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.restore=/var/lib/parlons-node/backup.pbk -jar /opt/maxima/parlons-node.jar
   ```

5. Delete the bundle and start the node:

   ```bash
   rm /var/lib/parlons-node/backup.pbk
   systemctl start parlons-node
   ```

6. Paired devices reconnect by themselves, because the `MAX#` is the same.
7. The node's wallet stays its own vault. To make the funds follow, resync the wallet to the old
   phrase from a paired device ([§20](#how-to-resync-the-wallet-to-another-phrase)).

The app can also import an identity or a `.pbk` straight onto a running node (Settings →
**Import identity or backup…**). The node then exits with code 3 and comes back on the imported
identity. The previous identity is kept in `account-history/<import-id>/previous/`.

---

## 18. The keets sidecar (KeetChat bridge)

When the embedded Minima node runs keets, the account's chat engine gains the keets transport,
and the node becomes the **single reader** of its keets inbox.

**The fleet jar does all of this by itself.** `parlons-node-fleet-<ver>.jar` carries a Node.js
runtime and the sidecar inside it. Started with `-Dparlons.fleet=true` (what the one-line installer
writes), it extracts them to `<data>/runtime/` on first start, turns on the Minima server role and
`-keets`, and runs the sidecar in **serve** mode: one JavaScript process that is the box's keets
transport, its public HyperDHT bootstrap node (`49737/udp`, `-Dparlons.keets.dhtport`) and its
keets mailbox (`<data>/keets-mailbox`). `-Dparlons.public.host=<ip>` names the box. The boot log
prints a `FLEET ENDPOINT` banner with the keets public key, the DHT bootstrap address and the
mailbox key, plus the `mailboxes-sign.js` command the list maintainer runs; the mailbox only holds
mail for offline phones once it is signed into the published list. Minima flags you set in
`parlons.node.args` win over what fleet mode would set. The rest of this section is the by-hand
route with the plain jar and an external sidecar.

- Turn it on with Minima flags in `parlons.node.args`:
  `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`. The desktop apps also pass
  `-keetsnode <path to a node binary>`. The deploy script's `--keets` uploads the sidecar (from
  `../keets/sidecar`, which needs `node_modules`) and sets the flags.
- The box needs a **Node.js** runtime. The sidecar runs as a child process inside the service sandbox.
- **Add `AF_NETLINK` to `RestrictAddressFamilies`** in the unit. Without it, every socket the sidecar
  opens fails with `EPERM`.
- **No inbound firewall port is needed.** The sidecar uses hyperswarm hole punching.
- At start, the bridge logs `keets chat bridge up (KeetChat interop)`, then
  `keets identity: 0x<64 hex> (a KeetChat user adds this as a contact)`.
- The bridge checks the node every 5 s. Pay-address requests are answered only for **contacts**,
  at most once per peer every 10 minutes.
- `-Dparlons.node.keetschat=off` lets a host app read the keets inbox itself (minimaCore Desktop
  does this). There must only ever be one reader per node.

### How to turn on keets with the deploy script

1. Install Node.js on the box. The script refuses to continue without a `node` runtime:

   ```bash
   apt-get install -y nodejs
   node --version
   ```

2. On your computer, check out the keets repository next to the maxima checkout, so the sidecar
   is at `../keets/sidecar`, and install its packages:

   ```bash
   (cd ../keets/sidecar && npm ci --omit=dev)
   ```

   To use a sidecar elsewhere, set `SIDECAR=/path/to/sidecar` before the script.
3. Re-run the deploy script with all your usual flags plus `--keets`. It uploads the sidecar to
   `/opt/maxima/keets-sidecar`, adds the flags, and adds `AF_NETLINK` to the unit.
4. Check the log:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'keets'
   ```

   You want `keets chat bridge up (KeetChat interop)` and `keets identity: 0x…`.

### How to turn on keets by hand

1. Install Node.js on the box, and copy a sidecar with its `node_modules` to
   `/opt/maxima/keets-sidecar` (it must contain `index.js`).
2. Add the keets flags to `parlons.node.args` in the unit:

   ```
   "-Dparlons.node.args=-keets -keetssidecar /opt/maxima/keets-sidecar/index.js"
   ```

3. Add `AF_NETLINK` to the address families in the unit:

   ```ini
   RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK
   ```

4. Reload and restart ([§7](#how-to-change-the-unit)), then check the log as in step 4 above.
5. Give the `keets identity: 0x…` value to KeetChat users who want to add the node as a contact.

---

## 19. MDS (MiniHUB) on the node

- `-Dparlons.mds=true` serves MiniHUB and MiniDapps on `https://127.0.0.1:<p2p+2>/`. It uses a
  self-signed certificate.
- **It binds loopback unless you say otherwise.** MiniHUB is a full wallet UI behind one password.
  Never bind it publicly without your own TLS and firewall.
- The password comes from a mode-600 conf file (`-Dparlons.node.conf=/path/minima.conf` containing
  `mdspassword=…`). Otherwise one is generated: read it with the `mds` command over the admin RPC.
  The boot log masks it.
- Only the hub, the login page, `mds.js`, publicmds and the Pending dapp are bundled. Install other
  dapps yourself.

### How to turn on MDS and open MiniHUB

1. Put the MDS password in a conf file ([§8, conf file](#how-to-pass-minimas-own-flags-in-a-conf-file)),
   or skip this step and let the node generate one.
2. Add these properties to the unit and restart ([§7](#how-to-change-the-unit)). Leave out the
   `parlons.node.conf` line if you skipped step 1: a conf file that cannot be read stops the node
   with exit code 2.

   ```
   -Dparlons.mds=true
   -Dparlons.mds.bind=127.0.0.1
   -Dparlons.node.conf=/var/lib/parlons-node/minima.conf
   ```

3. If you did not set a password, read the generated one over the admin RPC, on the box:

   ```bash
   curl -s http://127.0.0.1:9005/mds
   ```

4. From your computer, open an SSH tunnel to the MDS file port (P2P port 9001 means 9003):

   ```bash
   ssh -L 9003:127.0.0.1:9003 root@node.example.com
   ```

5. Open `https://127.0.0.1:9003/` in a browser, accept the self-signed certificate, and log in
   with the password.

---

## 20. Seeds, identity and backups

> **The seed is a wallet.** The node's 24-word phrase, `identity.txt`, and any `seed.txt` you
> adopt can all spend funds. Never print them to a log, a ticket, a chat or a terminal that is
> being recorded. When you grep unit files or journals, add `| grep -v seed`.

### Two secrets, one origin

| What | Where | Used for |
|---|---|---|
| **Wallet seed** | the node's vault, under `<data>/<base version>/` | the node wallet (the account's wallet) |
| **Identity phrase** | `<data>/identity.txt` (mode 600) | the Maxima `Mx…` identity, the account's `MAX#`, pairing, contacts |

On first boot, the node creates its own wallet seed and copies the phrase into `identity.txt`. If
the node's phrase is a custom one that the phrase rule cannot reproduce, it stores the seed hex
instead. From then on, the identity comes from `identity.txt`, not from the vault. So:

- The wallet can be **resynced** to a new phrase (from a paired device: Wallet → "Resync wallet to
  a new phrase…") without changing the `MAX#`, paired devices or contacts. The node runs
  `megammrsync action:resync host:<parlons.node.archive> …` and exits with code 3. If the resync
  fails, the node stays up, the wallet is unchanged, and the reason is shown to the device.
- To re-pin the identity to whatever the vault now holds, delete `identity.txt` and restart.

### How to back up the wallet seed

1. Make sure the admin RPC is on ([§21](#21-the-admin-rpc)).
2. On the box, in a session that is not recorded, print the vault:

   ```bash
   curl -s http://127.0.0.1:9005/vault
   ```

3. Write the 24-word `phrase` on paper, or into offline storage. Do not redirect it into a file in
   a shared place.
4. Clear the terminal's scrollback.
5. If the identity phrase differs from the wallet phrase (after a resync or an identity import),
   back up `identity.txt` too (next procedure).

### How to back up the whole node

The chain itself does not need a backup; a new node syncs it again. Back up the secrets and your
settings.

1. Back up the wallet seed (previous procedure).
2. Copy the identity and account files to a private, encrypted place you control. Stop the node
   first so the files are consistent:

   ```bash
   systemctl stop parlons-node
   tar -C /var/lib/parlons-node -czf /root/parlons-node-backup.tgz \
       --exclude='./1.1' --exclude='./nft/tmp' .
   systemctl start parlons-node
   chmod 600 /root/parlons-node-backup.tgz
   ```

   This holds `identity.txt`, the account (devices, contacts, chat), the relay state, the gateway
   token and the hosted NFT files. It is a wallet-grade secret: treat it like the seed.
3. Copy the unit and your TLS front configuration:

   ```bash
   cp /etc/systemd/system/parlons-node.service /root/
   ```

4. Move the archive off the box, then delete it there:

   ```bash
   scp root@node.example.com:/root/parlons-node-backup.tgz .
   ssh root@node.example.com rm /root/parlons-node-backup.tgz
   ```

5. For a portable copy of the account alone, make a `.pbk` bundle from a paired device
   ([Parlons Cloud Portal manual, §16](parlons-cloud-portal.md#16-backups-recovery-and-moving)).

Never restore this archive onto a second box while the first one still runs. One identity runs in
one place.

### How to adopt an existing phrase with the deploy script

1. Put the 24-word phrase in a file on the box, owned by root, mode 600. A box that ran
   `maxima-server.jar` already has it at `/var/lib/maxima/seed.txt`.
2. Re-run the deploy script with `--rpc --seed-from <file on the box>`, plus your usual flags.
   Pass `--archive <host:port>` to choose the archive node. If you also seed the MegaMMR, the
   script adopts the phrase first, as it must.
3. Read the result. `vault MATCHES <file> - identity preserved (and pinned in identity.txt)` means
   it worked. Any other result means: do not trust the node until you know why.
4. Delete the phrase file from the box once you have an offline copy.

### What `--seed-from` does

It keeps the identity of an existing relay or cloud account. The deploy script:

1. Checks that the file on the box holds exactly 24 words.
2. Sends `megammrsync action:resync host:<--archive> phrase:"…"` as a **POST body from a temporary
   file with mode 600**. The phrase is never in argv and never in a log.
3. Restarts the node and compares the vault phrase with the file, without printing either.
4. If they match, writes `identity.txt` from the file and writes the `.seed-adopted` marker, so
   later runs skip this step.

The phrase never leaves the box.

### How to resync the wallet to another phrase

From a paired device: Wallet → **Resync wallet to a new phrase…**. The node uses the archive host
in `parlons.node.archive`, exits with code 3, and comes back on the same identity.

By hand on the box (this is what the deploy script's `--seed-from` does):

1. Write the command into a private temporary file, so the phrase is never in argv or a log:

   ```bash
   umask 077
   tmp=$(mktemp /var/lib/parlons-node/.resync.XXXXXX)
   printf 'megammrsync action:resync host:%s phrase:"%s"' 'archive.example.net:9001' '<24 words>' > "$tmp"
   ```

   Type the phrase in an unrecorded session, and clear the shell history afterwards.
2. Send it to the admin RPC as a POST body. This can take several minutes:

   ```bash
   curl -s -m 3600 --data-binary @"$tmp" http://127.0.0.1:9005/
   shred -u "$tmp"
   ```

   It must reply with `"status":true`. The resync ends with a node shutdown.
3. Restart, and wait until the RPC answers again:

   ```bash
   systemctl restart parlons-node
   until curl -s -m 5 http://127.0.0.1:9005/status >/dev/null; do sleep 2; done
   ```

4. Check that the vault now holds the new phrase (`curl -s http://127.0.0.1:9005/vault`), in an
   unrecorded session.
5. If you seed the MegaMMR, import it again now ([§13](#13-megammr-mode-and-the-rootnode)): the
   resync reset the chain databases.

The identity stays pinned in `identity.txt`, so the `MAX#`, paired devices and contacts do not
change.

### How to re-pin the identity to the vault

This changes the Maxima identity and the `MAX#` whenever the vault phrase differs from
`identity.txt`. Paired devices and contacts then point at the old identity.

1. Keep a copy of the old identity file, then remove it:

   ```bash
   cp /var/lib/parlons-node/identity.txt /var/lib/parlons-node/identity.txt.before-repin
   rm /var/lib/parlons-node/identity.txt
   ```

2. Restart. The boot log says `identity: pinned from the vault into identity.txt`:

   ```bash
   systemctl restart parlons-node
   journalctl -u parlons-node -b --no-pager | grep 'identity:' | grep -v seed
   ```

3. Delete the `.before-repin` copy once you are sure you do not need the old identity.

### How to run a password-locked node

If the vault is locked, the cape, gateway and account cannot start until it is unlocked. The
Minima node itself keeps running. The node tries to unlock exactly once per boot, from a file or
from the `PARLONS_NODE_PASSPHRASE` environment variable.

1. Write the passphrase into a file under `/var/lib/parlons-node` or `/etc` (not under `/root` or
   `/home`: `ProtectHome=true` hides them). The passphrase must not contain whitespace, `"` or `;`:

   ```bash
   umask 077
   printf '%s\n' '<passphrase>' > /var/lib/parlons-node/passphrase.txt
   chown maxima:maxima /var/lib/parlons-node/passphrase.txt
   chmod 600 /var/lib/parlons-node/passphrase.txt
   ```

2. Add it to the unit and restart ([§7](#how-to-change-the-unit)). With the deploy script, pass
   `--passphrase-file /var/lib/parlons-node/passphrase.txt`:

   ```
   -Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt
   ```

3. Check that the cape, gateway and account come up (`cape=up`, `gateway=up` in the heartbeat).
   `node is password-locked but no passphrase provided` in the log means the file was not read.

### Never

- Never pass `-seed`, `-anyseed` or `-dbpassword` on the command line. The node refuses them anyway.
- Never run two processes on one data directory, or one identity on two hosts. Mailbox and
  directory state split between them, and messages get lost.
- Never copy `identity.txt` to another live box.

---

## 21. The admin RPC

- Turn it on with `-Dparlons.node.rpc=true`. It listens on `127.0.0.1:<p2p+4>` (9005 when P2P is
  9001). The loopback bind is hard-coded; no setting can expose it.
- It accepts **every** node command, with **no authentication**. It trusts local processes. It
  refuses browser cross-origin requests (it checks the Host header, Origin and Sec-Fetch-Site).
- Two request forms: `GET /<url-encoded command>`, or `POST /` with the command as the body (at
  most 64 KiB).

  ```bash
  curl -s http://127.0.0.1:9005/status
  curl -s 'http://127.0.0.1:9005/balance'
  curl -s --data-binary 'network' http://127.0.0.1:9005/
  ```

- `quit` stops the node, and the JVM exits about 0.8 s later with code 0. Under `Restart=always`
  (what the deploy script writes), systemd starts it again after 10 s. To stop the node for good,
  use `systemctl stop parlons-node` instead.
- **Never put it behind a proxy, and never open its port.** The gateway is the hardened public surface.
- If the port is already taken, the node logs `REFUSING to run: admin rpc on 127.0.0.1:N cannot
  start …` and exits with code 2.

### How to turn on the admin RPC

1. Add `-Dparlons.node.rpc=true` to the unit and restart ([§7](#how-to-change-the-unit)). With the
   deploy script, pass `--rpc`.
2. Check the boot line. It must say `admin rpc 127.0.0.1:9005 (loopback-bound)`:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep 'admin rpc' | grep -v seed
   ```

3. On the box, run a command:

   ```bash
   curl -s http://127.0.0.1:9005/status
   ```

4. From another machine, check that the port does not answer. This must print nothing:

   ```bash
   nc -z -w 5 node.example.com 9005 && echo "EXPOSED 9005"
   ```

### How to run a command with spaces or quotes

1. For a short command, URL-encode the spaces as `%20`:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<address>'
   ```

2. For anything longer, or with quotes, send it as a POST body (up to 64 KiB):

   ```bash
   curl -s --data-binary 'coins relevant:true' http://127.0.0.1:9005/
   ```

### How to stop or restart the node

Use systemd, not `quit`:

```bash
systemctl stop parlons-node
systemctl start parlons-node
systemctl restart parlons-node
```

---

## 22. Upgrading and rolling back

The convention is **versioned jars plus a symlink**. Every jar is kept in `/opt/maxima`, and the
unit always runs `/opt/maxima/parlons-node.jar`, which is a symlink.

### How to upgrade one box

1. On your computer, download the new jar and check its SHA-256 ([§4](#4-download-and-verify-the-jar)).
2. Copy it to the box under a temporary name:

   ```bash
   B=parlons-node-0.2.143.jar
   scp $B root@node.example.com:/opt/maxima/$B.part
   ```

3. On the box, check the hash again, then put the jar in place:

   ```bash
   cd /opt/maxima
   echo 'ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc  parlons-node-0.2.143.jar.part' | sha256sum -c --quiet
   mv parlons-node-0.2.143.jar.part parlons-node-0.2.143.jar
   chmod 644 parlons-node-0.2.143.jar
   ```

4. Note the jar the symlink points at now. You need it to roll back:

   ```bash
   readlink /opt/maxima/parlons-node.jar
   ```

5. Back up the unit:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-parlons-node-0.2.143.jar
   ```

6. Update the version in the unit's `Description` line (optional; it is only a label):

   ```bash
   sed -i 's/wallet gateway ([0-9.]*)/wallet gateway (0.2.143)/' /etc/systemd/system/parlons-node.service
   ```

7. Point the symlink at the new jar:

   ```bash
   ln -sfn /opt/maxima/parlons-node-0.2.143.jar /opt/maxima/parlons-node.jar
   ```

8. Restart:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

9. Wait about 25 s, then check:

   ```bash
   systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|Exception' | grep -v seed
   ```

   The boot line must show `Parlons Node 0.2.143`, and a DEX cape must show `dex relay 0.1.5 up`.
10. Verify from outside ([§23](#23-verifying-from-outside)).

If you run several boxes, upgrade **one box at a time**, and verify each one before you start the next.

### How to upgrade with the deploy script

1. Put the new verified jar in `dist/` on your computer.
2. Re-run the script with the **same flags** you used before, and the new jar:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com --jar dist/parlons-node-0.2.143.jar <your usual flags>
   ```

   The script uploads and checks the jar, flips the symlink, and rewrites the unit from its flags.
   A flag you leave out goes back to its default.
3. Verify from outside ([§23](#23-verifying-from-outside)).

### How to roll back

1. Point the symlink at the previous jar (the one `readlink` showed in step 4 of the upgrade):

   ```bash
   cd /opt/maxima && ln -sfn parlons-node-<old version>.jar parlons-node.jar
   ```

2. If you changed the unit, restore it from the `.before-…` backup:

   ```bash
   cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service
   ```

3. Restart:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

4. Check the boot line shows the old version, then verify from outside ([§23](#23-verifying-from-outside)).

### How to go back to `maxima-relay.service`

If the box used to run `maxima-relay.service` and the node replaced it, you can roll back to that
too. The deploy script kept its unit on disk:

```bash
systemctl disable --now parlons-node
systemctl enable --now maxima-relay
```

---

## 23. Verifying from outside

`systemctl is-active` only shows that the process is running. Always test **from another machine
on the public internet**. Do not test from the box itself or from its own LAN: hairpin NAT gives
false results in both directions.

1. **TCP reachability** of the P2P and relay ports:

   ```bash
   nc -z -w 8 node.example.com 9001 && echo p2p-open
   nc -z -w 8 node.example.com 9501 && echo relay-open
   ```

2. **The relay end to end.** From a checkout of this repository, with `:core` and its tests
   compiled into `build/classes`:

   ```bash
   ops/verify-relay.sh node.example.com:9501
   ```

   This connects two real clients through the relay and makes them talk: greeting, attach, address
   assignment, contact handshake, relayed delivery both ways, the delivery receipt coming back, and
   group fan-out. It exits 0 only if every check passes.
3. **DEX cape:**

   ```bash
   curl -s https://node.example.com/dex-relay/dex/health    # {"ok":true,"version":"0.1.5",...}
   curl -s https://node.example.com/dex-relay/dex/relays
   ```

4. **Gateway:** a `block` POST with the token ([§14](#14-the-wallet-gateway-and-its-token)). A
   request without the token must return 401.
5. **NFT:** `curl -sI https://node.example.com/parlons-node/nft/<sha256>.<ext>` returns 200 for a
   hosted file and 404 for anything else.
6. **Loopback-only ports must NOT answer from outside:**

   ```bash
   for p in 9005 9585 9587 9602; do nc -z -w 5 node.example.com $p && echo "EXPOSED $p"; done
   ```

   This must print nothing.

---

## 24. Monitoring and logs

All output goes to the journal (`StandardOutput=journal`). Useful commands:

```bash
journalctl -u parlons-node -f                                   # follow
journalctl -u parlons-node -f | grep heartbeat                  # sync progress and subsystem health
journalctl -u parlons-node --since -1h | grep -E 'Exception|FAILED|REFUSING' | grep -v seed
journalctl -u parlons-node -b | grep -E 'Parlons Node|cape up|gateway up|dex relay|account up'
systemctl status parlons-node
```

The heartbeat line, every 30 s:

```
[parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:<port> | gateway=up:<port> | account=up:<n> hosts, <n> devices
```

| Field | Healthy |
|---|---|
| `block=` | climbing, then stays at the chain tip |
| `cape=` | `up:<relay port>` (`down` means the cape failed or is off) |
| `gateway=` | `up:<gateway port>` |
| `account=` | `up:` with 1 or more hosts (`off` when `parlons.account=false`) |

If you see `heartbeat: node not ready: …`, the embedded node is not answering `status` yet. This is
normal for a short time after boot.

### How to check a node's health in one minute

1. Is the service running? `-w` matches the whole word, so `inactive` does not count:

   ```bash
   systemctl is-active parlons-node | grep -qw active && echo running
   ```

2. Read the latest heartbeat:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

3. Check each field against the table above.
4. Look for errors in the last hour:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'Exception|FAILED|REFUSING|OutOfMemoryError' | grep -v seed
   ```

5. Check the disk and memory:

   ```bash
   df -h /var/lib
   systemctl status parlons-node --no-pager | grep -E 'Memory|Tasks'
   ```

6. Run the outside checks ([§23](#23-verifying-from-outside)).

### How to alert on problems

The node has no metrics endpoint; the heartbeat and the log lines below are the interface. A
simple check is a cron job that alerts when the heartbeat is missing or an alert line appears:

1. Write the check script `/usr/local/bin/parlons-node-check`:

   ```bash
   #!/bin/sh
   # Exit 1 (and print why) when the node needs attention.
   systemctl is-active parlons-node | grep -qw active || { echo "parlons-node not active"; exit 1; }
   journalctl -u parlons-node --since -2min --no-pager | grep -q heartbeat || { echo "no heartbeat for 2 min"; exit 1; }
   bad=$(journalctl -u parlons-node --since -5min --no-pager | grep -E 'REFUSING|FAILED|OutOfMemoryError|password-locked' | grep -v seed)
   [ -z "$bad" ] || { echo "$bad"; exit 1; }
   ```

2. Make it executable, and run it every 5 minutes from root's crontab with your own alert command:

   ```bash
   chmod 755 /usr/local/bin/parlons-node-check
   crontab -e
   # */5 * * * * /usr/local/bin/parlons-node-check || <your alert command>
   ```

Log lines worth alerting on:

| Line contains | Meaning |
|---|---|
| `REFUSING to start` / `REFUSING to run` | bad flags or a port clash (exit 2) |
| `Maxima cape FAILED to start` | relay did not come up |
| `wallet gateway FAILED` | gateway did not come up |
| `account layer FAILED to start` | account did not come up |
| `node is password-locked but no passphrase provided` | cape, gateway and account are waiting for an unlock |
| `node seed (vault) not available after 120s` | wallet not ready, so the cape cannot derive its identity |
| `the embedded node shut itself down` | after a resync, restore or reset the JVM exits; restart it |
| `wallet resync FAILED` | resync from a device failed; the wallet is unchanged |
| `OutOfMemoryError` | the heap is too small (see [§3](#3-requirements)) |

---

## 25. Firewall notes

**Inbound, open:**

- TCP `<p2p port>` (9001, or whatever you set). Minima peers connect here.
- TCP `<relay port>` (9501). Phones and mesh peers connect here.
- TCP 443 (and 80 for ACME), if the box serves the TLS front.
- Optional: UDP `<relay port>` for STUN. The deploy script does not open it.

**Inbound, never open:** `p2p+2`, `p2p+3`, `p2p+4`, 9585, 9587, 9602, and any 959x tenant panel.

**Cloud firewalls.** A provider firewall (in the Vultr, Hetzner or AWS console) can drop packets
before they reach the box. ufw cannot fix that. If a port times out from outside while it is open
in ufw and answers on localhost, open it in the provider console. Rule changes can take a minute or
two to apply, so test again before you conclude anything.

**Default-deny outbound.** On a box with an outbound default-deny policy (`ufw default deny
outgoing`), the node cannot reach peers you have not allowed. Allow, at least:

- outbound TCP to your rootnode and Minima peers;
- outbound TCP to **every** mesh peer's relay port, using the port the peer's relay **actually**
  listens on (some peers use 8001, not 9501): `ufw allow out to <peer-ip> port <port> proto tcp`;
- outbound TCP to the archive node used for resyncs;
- outbound 443 for snapshot downloads.

A missing outbound rule shows up as a peer that is missing from `/dex/relays`, or mesh lookups
that never reach one relay.

**Home routers.** Forward the P2P port and the relay port, or use `-Dparlons.relay.port=shared`
so that one forwarded port is enough.

### How to open the ports with ufw

1. Allow SSH first, so you do not lock yourself out, then the node's public ports:

   ```bash
   ufw allow OpenSSH
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   ufw allow 80/tcp
   ufw allow 443/tcp
   ```

2. Optional: allow STUN on the relay port:

   ```bash
   ufw allow 9501/udp
   ```

3. Turn ufw on if it is off, and check the rules:

   ```bash
   ufw enable
   ufw status numbered
   ```

4. Open the same ports in the cloud provider's firewall, if it has one.
5. Test from another machine ([§23](#23-verifying-from-outside)).

### How to open the ports with firewalld

1. Add the ports permanently, then reload so they take effect:

   ```bash
   firewall-cmd --permanent --add-port=9001/tcp
   firewall-cmd --permanent --add-port=9501/tcp
   firewall-cmd --permanent --add-service=http
   firewall-cmd --permanent --add-service=https
   firewall-cmd --reload
   ```

2. Check the result:

   ```bash
   firewall-cmd --list-all
   ```

The deploy script adds the ports with `--permanent` but does not reload firewalld. Run
`firewall-cmd --reload` after the script on a firewalld box.

### How to allow outbound traffic on a default-deny box

1. Allow each mesh peer's relay port (repeat for every peer, with its real port):

   ```bash
   ufw allow out to <peer ip> port 9501 proto tcp
   ```

2. Allow the rootnode, your Minima peers and the archive node (repeat for each):

   ```bash
   ufw allow out to <peer ip> port 9001 proto tcp
   ```

3. Allow HTTPS out for snapshot downloads, and DNS:

   ```bash
   ufw allow out 443/tcp
   ufw allow out 53
   ```

4. Check that a peer missing from `/dex/relays` appears within about 10 minutes.

### How to forward ports on a home router

1. Give the box a fixed address on your LAN (a DHCP reservation in the router).
2. In the router, forward TCP 9001 and TCP 9501 to that address. With
   `-Dparlons.relay.port=shared`, forward 9001 only.
3. Test from outside your home network, for example from a phone on mobile data or a VPS. A test
   from inside the LAN is not valid (hairpin NAT).

---

## 26. Troubleshooting

### First, sort out "can't connect"

From outside, `nc -z -w 8 <host> <port>` has three outcomes:

| Outcome | Means | Look at |
|---|---|---|
| **Refused**, immediately | The host answered, but nothing is listening. | `systemctl status parlons-node`; `ss -ltnp \| grep <port>`; the boot log |
| **Timeout** (~8 s) | A packet filter is silently dropping packets. | The cloud firewall or the router port-forward, then ufw |
| **Open** | The network path is fine; the problem is in the application. | The journal; `ops/verify-relay.sh` |

Checks on the box:

```bash
systemctl is-active parlons-node
ss -ltnp | grep -E ':(9001|9501|9585|9587|9602) '
ufw status numbered | head -20
bash -c '</dev/tcp/127.0.0.1/9501' && echo localhost-ok
```

The decisive test is to capture packets on the box while you connect from outside:

```bash
# on the box
timeout 25 tcpdump -ni any 'tcp port 9501' -c 5
# meanwhile, from outside
nc -z -w 5 node.example.com 9501
```

- No packets at all: they are dropped upstream, and nothing on the box will fix it.
- A SYN arrives with no SYN-ACK back: the box itself is dropping it.

Beware of `systemctl is-active x | grep -q active`: it also matches `inactive`. Use `grep -qw active`.

### "Invalid SyncBlock as NO PARENT" after a restart

This has been seen in the log right after a restart, while the node catches up to the chain tip.
It has not been investigated further. What to do:

1. Watch the heartbeat. If `block=` keeps climbing and reaches the tip, the node recovered by itself.
2. If `block=` stops moving for a long time, check that the rootnode and peers are reachable
   (outbound firewall, [§25](#25-firewall-notes)) and that the clock is correct.

### Port already in use

| Symptom | Cause | Fix |
|---|---|---|
| `REFUSING to run: admin rpc on 127.0.0.1:9005 cannot start` | Another Minima node holds `p2p+4`, or the JRE lacks `jdk.httpserver`. | Move the P2P port (`parlons.node.port=9101` puts the RPC on 9105), or use a full JRE. |
| Relay port clash; `cape=down` | `maxima-relay.service` or another relay holds 9501. | `systemctl disable --now maxima-relay`, or choose another relay port. |
| Panel fails on 9587 | A `parlons-tenants` service (or a second node) holds it. | Give tenants `--panel-port 9597`, or set `parlons.panel.port`. |
| `wallet gateway FAILED: …BindException` | Something holds 9585. | Set `parlons.gateway.port`, and update the TLS front to match. |
| The node will not start on 9001 | A stock Minima node is on 9001. | `parlons.node.port=9101` |

### Other symptoms

| Symptom | Fix |
|---|---|
| The process exits at once, printing the help text | You passed a command-line argument. All settings are `-D` properties before `-jar`. |
| `REFUSING to start: these Minima flags are not allowed` | Remove the listed flags from `parlons.node.args` or the conf file (see [§8.8](#88-minima-flags-the-node-refuses)). |
| `block=` stays at 0 | There is no rootnode or connect list, or outbound access to it is blocked. |
| Out-of-memory loop, or the service ends up "failed" | Raise the heap (3 GB minimum with MegaMMR) and keep `MemoryMax` above it. Clear the start limit with `systemctl reset-failed parlons-node`. |
| Phones see empty balances | The MegaMMR has not been imported yet ([§13](#13-megammr-mode-and-the-rootnode)). |
| The gateway returns 503 `no MegaMMR` | The node runs with `megammr=false`. Phones fail over to another gateway. |
| The gateway returns 403 `requires an address:` | Add `address:` to `coins`/`balance`. |
| The gateway returns 429 | The global bucket is exhausted. Raise `parlons.gateway.rate.global`, or rate-limit in the proxy. |
| The gateway is never advertised to phones | It needs MegaMMR on, `parlons.node.public` set, and the relay on. |
| NFT `url` is empty | Set `parlons.node.public` to a URL that really routes to this box's 9585. |
| `own relay NOT advertised: public host unknown or private` | Set `-Dparlons.relay.host=<public host>`. |
| `dex relay: no public host known yet - NOT announced` | Set `-Dparlons.dex.public=<https url>` (or `parlons.relay.host`). |
| A peer is missing from `/dex/relays` | Wait one 10-minute re-dial cycle. Then check that this box can dial **out** to that peer's relay port. |
| The DEX cape answers on the public IP | `parlons.dex.bind` is still `0.0.0.0`. Set it to `127.0.0.1` and restart. |
| Caddy serves your website at `/dex-relay/…` | The `handle_path` vs `handle` ordering trap ([§11](#11-tls-fronting-caddy-nginx-apache)). |
| The account never says "attached" | There is no outbound internet, or the clock is badly wrong. |
| The cape, gateway and account never start; the log mentions a password lock | Supply the passphrase ([§20](#how-to-run-a-password-locked-node)). |
| The service stopped after `quit`, a manual megammr import or a resync | The unit still says `Restart=on-failure`, which does not restart a clean exit. Switch it to `Restart=always` (see the fixes below). |
| keets sidecar fails with `EPERM` on sockets | Add `AF_NETLINK` to `RestrictAddressFamilies`. |
| Killing a stuck process | Kill it by PID. Never run `pkill -f parlons-node.jar`: the pattern can match your own SSH session. |

### Step-by-step fixes

#### Fix: the admin RPC port is taken

1. See who holds it:

   ```bash
   ss -ltnp | grep ':9005 '
   ```

2. If it is a stock Minima node, move the Parlons Node's P2P port ([§9](#how-to-move-the-node-off-port-9001)).
3. If nothing holds it, the JRE lacks `jdk.httpserver`. Check, and install a full JRE if the line is missing:

   ```bash
   java --list-modules | grep jdk.httpserver || apt-get install -y default-jre-headless
   ```

#### Fix: the relay port is taken

1. See who holds 9501:

   ```bash
   ss -ltnp | grep ':9501 '
   ```

2. If it is the old standalone relay, stop it and restart the node:

   ```bash
   systemctl disable --now maxima-relay
   systemctl restart parlons-node
   ```

3. Check that the heartbeat shows `cape=up:9501`.

#### Fix: the panel or gateway port is taken

1. See who holds it:

   ```bash
   ss -ltnp | grep -E ':(9585|9587) '
   ```

2. For a `parlons-tenants` service on 9587, give it `--panel-port 9597`
   ([Parlons Cloud Portal manual, §21](parlons-cloud-portal.md#21-many-accounts-the-parlons-tenants-service)).
   Otherwise move the node: set `-Dparlons.panel.port=<free port>` or `-Dparlons.gateway.port=<free port>`.
3. If you moved the gateway, change the port in the TLS front too, then reload it.
4. Restart the node ([§7](#how-to-change-the-unit)).

#### Fix: the node exits at once or refuses to start

1. Read why:

   ```bash
   journalctl -u parlons-node -n 30 --no-pager | grep -E 'REFUSING|usage|Usage' | grep -v seed
   ```

2. If the help text was printed, remove every argument after `-jar …parlons-node.jar` in the unit.
   All settings are `-D` properties before `-jar`.
3. If it names refused Minima flags, remove them from `parlons.node.args` or the conf file
   ([§8.8](#88-minima-flags-the-node-refuses)).
4. If it says it cannot read `-conf`, check the conf file's path, owner (`maxima`) and mode (600).
5. Restart ([§7](#how-to-change-the-unit)).

#### Fix: `block=` stays at 0

1. Check that the unit has a rootnode or connect list:

   ```bash
   grep -E 'parlons.node.(rootnode|connect)' /etc/systemd/system/parlons-node.service
   ```

2. If it has none, set one ([§13](#how-to-set-the-rootnode)).
3. Check that the box can reach it:

   ```bash
   nc -z -w 8 seed-peer.example.net 9001 && echo reachable
   ```

4. If it cannot, allow outbound traffic to it ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
5. Check the clock:

   ```bash
   timedatectl | grep -E 'synchronized|NTP'
   ```

#### Fix: out-of-memory loop, or the service is "failed"

1. Confirm the cause:

   ```bash
   journalctl -u parlons-node --since -1h --no-pager | grep -E 'OutOfMemoryError|oom' | tail -5
   ```

2. Raise `-Xmx` in the unit (3g or more with MegaMMR) and set `MemoryMax` at least 512 MB above it,
   for example `-Xmx3g` with `MemoryMax=3584M`.
3. Reload, clear the start limit and start again:

   ```bash
   systemctl daemon-reload
   systemctl reset-failed parlons-node
   systemctl restart parlons-node
   ```

4. Check a minute later that it is still `active`.

#### Fix: phones see empty balances

1. Check the MegaMMR size. Under 1 MB means it was never imported:

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

2. Import it ([§13](#how-to-import-a-megammr-snapshot)).

#### Fix: the gateway is never advertised to phones

1. Check the three conditions: MegaMMR on, `parlons.node.public` set, relay on:

   ```bash
   grep -E 'megammr|node.public|relay.port' /etc/systemd/system/parlons-node.service
   ```

2. Fix whichever is missing and restart ([§14](#how-to-publish-the-gateway-for-phones)).
3. Look for `wallet gateway advertised to phones:` in the log.

#### Fix: the cape does not advertise itself, or the DEX cape is not announced

1. Read the warning:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'own relay NOT advertised|dex relay: no public host'
   ```

2. Set the public host ([§12](#how-to-set-the-public-host)), and for the DEX cape also
   `-Dparlons.dex.public=https://node.example.com/dex-relay`.
3. Restart and check the log again.

#### Fix: the DEX cape answers on the public IP

1. Check the bind:

   ```bash
   ss -ltnp | grep ':9602 '
   ```

2. If it shows `0.0.0.0:9602` or `*:9602`, add `-Dparlons.dex.bind=127.0.0.1` (and
   `-Dparlons.dex.public=…`) to the unit, and restart.
3. Close the port in the firewall if the deploy script opened it:

   ```bash
   ufw delete allow 9602/tcp
   ```

4. Check from outside that 9602 no longer answers ([§23](#23-verifying-from-outside), step 6).

#### Fix: Caddy serves your website at `/dex-relay/…`

1. Find the DEX block in the Caddyfile:

   ```bash
   grep -n 'dex-relay' /etc/caddy/Caddyfile
   ```

2. If it says `handle_path /dex-relay/*`, change it to `handle /dex-relay/*` with
   `uri strip_prefix /dex-relay` inside ([§11](#how-to-front-the-node-with-caddy)).
3. Validate and reload Caddy, then test `/dex-relay/dex/health` from outside.

#### Fix: every DEX client is rate-limited together behind nginx

1. Check that the `/dex-relay/` block sends the header:

   ```bash
   grep -n 'X-Forwarded-For' /etc/nginx/sites-enabled/*
   ```

2. If it is missing, add `proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;` to the
   block ([§11](#how-to-front-the-node-with-nginx)), then run `nginx -t` and `systemctl reload nginx`.

#### Fix: the service stays stopped after a clean exit

1. Switch the unit to `Restart=always` ([§7](#how-to-switch-an-old-unit-to-restartalways)).
2. Start the node:

   ```bash
   systemctl start parlons-node
   ```

#### Fix: the keets sidecar fails with `EPERM`

1. Add `AF_NETLINK` to the unit:

   ```bash
   sed -i 's/^RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX$/RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK/' /etc/systemd/system/parlons-node.service
   ```

2. Reload and restart:

   ```bash
   systemctl daemon-reload
   systemctl restart parlons-node
   ```

#### Fix: the account never says "attached"

1. Check outbound internet and the clock:

   ```bash
   curl -sI https://github.com | head -1
   timedatectl | grep synchronized
   ```

2. If the box blocks outbound traffic, allow the relays' ports ([§25](#how-to-allow-outbound-traffic-on-a-default-deny-box)).
3. Look for `account up: attached to N relay(s)` in the log after a restart.

#### Fix: a stuck process

1. Find the PID. Never use `pkill -f parlons-node.jar`: the pattern can match your own SSH session:

   ```bash
   systemctl show -p MainPID parlons-node
   ```

2. Ask it to stop, then force it only if it does not:

   ```bash
   kill <pid>
   sleep 15
   kill -0 <pid> 2>/dev/null && kill -9 <pid>
   ```

3. Start it again with `systemctl start parlons-node`.

---

## 27. Security checklist

- [ ] The jar's SHA-256 was checked before it was activated.
- [ ] The service runs as the unprivileged `maxima` user, with the hardened unit ([§7](#7-the-systemd-unit-annotated)).
- [ ] `/var/lib/parlons-node` has mode 700. `identity.txt`, `gateway-token.txt`, `pair-code.txt`
      and `panel-ticket.txt` have mode 600.
- [ ] The seed and wallet phrase are backed up offline, and appear in **no** log, ticket or chat.
- [ ] Only the P2P port, the relay port and 443 are open inbound.
- [ ] From outside, 9005 (admin RPC), 9585, 9587 and 9602 do **not** answer ([§23](#23-verifying-from-outside), step 6).
- [ ] Whenever `parlons.dex.port` is set, `parlons.dex.public` is set too (the cape then binds `127.0.0.1`), or `parlons.dex.bind=127.0.0.1` is set.
- [ ] `parlons.gateway.bind` is `127.0.0.1` (no `--gateway-public` in production).
- [ ] The admin RPC is never proxied, and `parlons.mds.bind` is never public.
- [ ] `-Xmx` is set, and `MemoryMax` is above it.
- [ ] `parlons.node.public` points at a URL that really routes to this box.
- [ ] No secrets are on the command line. Passphrases and `mdspassword` are in mode-600 files.
- [ ] `parlons.node.selftest.send` is **not** set.
- [ ] Only one host runs each identity and each data directory.
- [ ] The cloud provider's firewall agrees with ufw.
- [ ] An admin pairing password is set, and old invites have been used or discarded.

---

## 28. Desktop apps that embed this jar

`parlons-node.jar` is also the node engine inside desktop apps. They download a `node-v*` release
into their resources (`scripts/fetch-parlons-node-jar.sh`) and start it with their own flags.

- **minimaDesk (MinimaClassic Desktop)** and **minimaCore Desktop** start it with
  `-Dparlons.node.rpc=true`, MegaMMR set from the user's choice, `-Dparlons.relay.port=shared`
  when the user contributes (else `0`), and panel and gateway ports derived from the app's own port settings.
- minimaDesk also passes `-Dparlons.mds=true -Dparlons.mds.bind=127.0.0.1` and
  `-Dparlons.node.conf=<file>`.
- minimaCore Desktop passes `-Dparlons.node.keetschat=off`, because it reads the keets inbox itself.
- A bundled JRE must include `jdk.httpserver`.

Operators of server nodes do not need to do anything for these apps. Never launch a desktop app
that holds real funds just to test something.

---

## 29. Glossary

| Term | Meaning |
|---|---|
| **Admin RPC** | The node's loopback-only, unauthenticated, full-command HTTP endpoint on `p2p+4`. |
| **Archive node** | A Minima node that keeps enough history (and a MegaMMR) for `megammrsync` resyncs. |
| **Bearer token** | The gateway credential in `gateway-token.txt`. It allows read and relay only. |
| **Bootstrap peers** | The first relays the cape contacts (`parlons.relay.peers`, or the compiled-in list). |
| **Cape** | The Maxima relay running inside the node. Also used for the DEX relay cape. |
| **Cursor** | A number that only ever grows in the DEX relay. Clients poll for changes "since" a cursor. |
| **Dexicon** | The DEX app whose order book the DEX relay cape carries. |
| **Envelope** | A signed JSON order or message accepted by the DEX relay. |
| **Gateway** | The allow-listed `POST /cmd` proxy that phones use for wallet reads and to broadcast transactions. |
| **Heartbeat** | The status line the node logs every 30 s. |
| **IBD** | Initial blockchain download: the first sync. It does not fill the MegaMMR. |
| **identity.txt** | The pinned phrase the node's Maxima identity and account `MAX#` are derived from. A wallet-grade secret. |
| **keets / KeetChat** | A peer-to-peer chat system. The node bridges to it through a Node.js sidecar. |
| **Long-poll** | An HTTP request the server holds open (here, up to 25 s) until there is news. |
| **MAX#** | An account's permanent, shareable address. |
| **MegaMMR** | A Minima mode that keeps proofs for every coin, so the node can answer for any address. |
| **Mesh** | The relays' network for forwarding directory lookups and copying directory entries. |
| **MDS / MiniHUB** | Minima's MiniDapp system and its hub UI. |
| **MxUSD** | The Minima dollar-pegged token, token id `0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`. The account's payment features recognise it by this id. |
| **Mx… identity** | The Maxima public identity of the relay or account. |
| **Panel** | The account's local web UI and API on `127.0.0.1:9587`. |
| **Pool host** | A relay that answers open directory lookups and takes part in the mesh. |
| **REDIAL_MS / TTL_MS** | The relay's 10-minute re-dial interval and 2-hour expiry for verified peers. |
| **Rootnode** | A Minima peer the node first discovers the network from. |
| **Shared port** | The relay riding the Minima P2P port (`parlons.relay.port=shared`). |
| **STUN** | A small UDP service on the relay port that tells clients their public address. |
| **Tenants host** | A separate `parlons-cloud` service that runs guest accounts beside the node. |
| **TLS front** | The web server (Caddy, nginx or Apache) that terminates HTTPS and forwards to loopback ports. |
| **Vault** | The Minima command and store that holds the node's wallet seed. |
| **Verified peer** | A relay whose claimed address was confirmed by dialling it back. |
