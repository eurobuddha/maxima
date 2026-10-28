# Parlons Node — Installation Runbook

For **Parlons Node 0.2.143** (release tag `node-v0.2.143`): the plain jar `parlons-node-0.2.143.jar`
and the fleet jar `parlons-node-fleet-0.2.143.jar`. Every command, flag, port, default and file in
this runbook comes from the installer `ops/get-parlons-node.sh`, the deploy script
`ops/deploy-parlons-node.sh`, the node source (`node/`, `core/`, `server/`) and the Minima fork the
jar embeds. Where the code has no value, this runbook says so.

This is the **install** document: it takes a fresh Linux machine to a verified, paired, backed-up
node. The [Operator's Manual](https://parlons.info/guide/node.html) is the reference for everything
after that (every property, TLS fronting, the DEX cape, monitoring, troubleshooting in depth).

Host names in examples (`node.example.com`, `203.0.113.10`) are placeholders.

---

## Contents

1. [What you are installing](#1-what-you-are-installing)
2. [Choose a profile](#2-choose-a-profile)
3. [Pre-flight checklist](#3-pre-flight-checklist)
4. [Profile A: plain full node (one command)](#4-profile-a-plain-full-node-one-command)
5. [Profile B: wallet gateway node with MegaMMR](#5-profile-b-wallet-gateway-node-with-megammr)
6. [Profile C: fleet endpoint (every service in one jar)](#6-profile-c-fleet-endpoint-every-service-in-one-jar)
7. [Install by hand (any profile)](#7-install-by-hand-any-profile)
8. [Parameter reference](#8-parameter-reference)
9. [First start: what a healthy boot looks like](#9-first-start-what-a-healthy-boot-looks-like)
10. [Pair your phone](#10-pair-your-phone)
11. [Back up the seed and the identity](#11-back-up-the-seed-and-the-identity)
12. [Verify from outside](#12-verify-from-outside)
13. [Optional services](#13-optional-services)
14. [Update, roll back, uninstall](#14-update-roll-back-uninstall)
15. [Troubleshooting](#15-troubleshooting)
16. [Security checklist](#16-security-checklist)
17. [Appendix: ports, files, checksums, exit codes](#17-appendix-ports-files-checksums-exit-codes)

---

## 1. What you are installing

A Parlons Node is **one Java process** that runs:

| Component | What it does | Default state |
|---|---|---|
| Full Minima node | Validates the chain, talks P2P on TCP 9001, holds the wallet | on |
| Maxima relay ("the cape") | Phones and other clients dial out to it on TCP 9501; part of the relay mesh | on |
| Wallet gateway | Allow-listed `POST /cmd` proxy on `127.0.0.1:9585` so phones can read balances and broadcast their own signed transactions through this node's MegaMMR | on, loopback |
| NFT hosting | Serves token image files at `/nft/…` on the gateway port | on, needs a public URL |
| Parlons account | The same account code as `parlons-cloud.jar`: pairing, chat, contacts, push, a local panel on `127.0.0.1:9587` | on |
| Admin RPC | Every node command, loopback only, `127.0.0.1:<p2p+4>` | off (fleet: on) |
| DEX relay cape | Dexicon's order-book relay on `127.0.0.1:9602` | off |
| keets chat bridge | KeetChat interop through the keets sidecar | off (fleet: on) |
| **Fleet mode** (fleet jar only) | Minima server role, keets transport, public HyperDHT bootstrap on UDP 49737 and a keets mailbox, from Node.js embedded in the jar | off (`-Dparlons.fleet=true`) |

**One seed drives everything.** On first boot the node's wallet seed is copied into
`identity.txt`, and the relay identity (`Mx…`) and the account address (`MAX#…`) are derived from
it. That seed is a spendable wallet. It is never printed to a log; you back it up in §11.

Who needs a Parlons Node? Most people do not. An always-on **account** without the chain runs in
1 GB with `get-parlons-cloud.sh`. Run a Parlons Node when you want a full copy of the chain, a relay
for the network, a wallet gateway for phones, or a fleet endpoint.

---

## 2. Choose a profile

Pick one before you start. The commands later in the runbook are per profile.

| | **A: Plain full node** | **B: Wallet gateway node** | **C: Fleet endpoint** |
|---|---|---|---|
| Jar | `parlons-node-0.2.143.jar` | `parlons-node-0.2.143.jar` | `parlons-node-fleet-0.2.143.jar` |
| MegaMMR | off (`--no-megammr`) | **on** + snapshot import | on if the box has the RAM, else off |
| Install path | one-line installer | deploy script (recommended) or by hand | one-line installer `--fleet` |
| RAM | 2 GB (installer minimum: 1 GB) | **8 GB** (3 GB heap) | **2 GB minimum**, 4 GB+ comfortable, 8 GB for MegaMMR |
| CPU arch | any 64-bit Java | any 64-bit Java | **x86_64 or aarch64 only** (embedded Node.js) |
| Needs a public IPv4 | no (home box behind NAT works with port forwards) | yes, plus a DNS name and TLS | **yes, directly reachable** |
| Inbound ports | TCP 9001, TCP 9501 | TCP 9001, 9501, 80, 443 | TCP 9001, TCP+UDP 9501, **UDP 49737**, (80, 443 if TLS) |
| What it gives the network | a Minima peer and a relay | the above + a wallet gateway + NFT hosting (+ DEX cape) | the above + keets transport, DHT bootstrap node, keets mailbox |
| Java heap (`-Xmx`) | 768m | 3g | 768m–2g without MegaMMR; 3g with |

Notes on the choice:

- **The installer sizes the node itself:** with **3.5 GB of RAM or more** it turns MegaMMR on with
  a **2 GB heap**; below that, a plain node with a 768 MB heap. The fleet's own experience is that a
  MegaMMR node wants a **3 GB heap** (2 GB and 2.5 GB heaps looped on out-of-memory). So on a 4 GB
  box pass `--no-megammr` explicitly, and on an 8 GB box pass `--heap 3g`. Do not accept the
  installer's MegaMMR default on a 4 GB machine.
- **MegaMMR is only useful once a snapshot is imported** (§5, step 5). The initial sync does not fill
  it. Until the import, balances for existing addresses come back empty.
- **Profile C is Linux x86_64/aarch64 only.** A 32-bit Raspberry Pi OS cannot be a fleet box. A Mac
  refuses fleet mode with a clear message. Existing fleet boxes that already run the three separate
  keets services are not migrated by this runbook; it is for new boxes.
- Mac or Windows: install Parlons Desktop instead. The jar is Linux-with-systemd on a server.

---

## 3. Pre-flight checklist

Run these on the target machine as root (or with `sudo`) before you install anything. Each check
says what must come back.

1. **Linux with systemd.** Any mainstream distribution (Debian, Ubuntu, Fedora, Alpine, Arch).
   This must print `systemd`:

   ```bash
   ps -p 1 -o comm=
   ```

2. **CPU architecture.** For profile C this must print `x86_64` or `aarch64`:

   ```bash
   uname -m
   ```

3. **RAM.** Compare with the table in §2:

   ```bash
   free -m | awk '/Mem:/ {print $2 " MB"}'
   ```

   The installer refuses under 900 MB, and under 1900 MB for `--fleet`.
4. **Disk.** The chain grows. Budget for the chain, a 460 MB MegaMMR snapshot while importing, and
   any NFT files or relay blob shelf. Check free space where `/var/lib` lives:

   ```bash
   df -h /var/lib
   ```

5. **Java.** 11 or newer is required; **21 or newer is recommended** (the relay then uses virtual
   threads and its connection cap rises from 512 to 4096). The installer adds a headless JRE if
   `java` is missing. If Java is already there, check it and that the `jdk.httpserver` module is
   present (the gateway, admin RPC, panel and DEX cape need it):

   ```bash
   java -version
   java --list-modules | grep jdk.httpserver
   ```

   The second command must print one line.
6. **Clock.** A node with a wrong clock never attaches. This must show `synchronized: yes`:

   ```bash
   timedatectl | grep -E 'synchronized|NTP'
   ```

7. **Ports free.** Nothing may already hold the node's ports. This must print nothing:

   ```bash
   ss -ltnp | grep -E ':(9001|9003|9004|9005|9501|9585|9587|9602) '
   ss -lunp | grep -E ':(9501|49737) '
   ```

   If a stock Minima node holds 9001, plan to move the node (§8, `parlons.node.port`). If an old
   `maxima-relay.service` holds 9501, plan to replace it (§7, step 6).
8. **Public address and DNS** (profiles B and C). The box must have a global IPv4 address, and for
   profile B a DNS name pointing at it. This must print a public address, not `10.x`, `172.16-31.x`,
   `192.168.x` or `100.64-127.x`:

   ```bash
   ip -4 -o addr show scope global | awk '{print $4}'
   ```

9. **Provider firewall.** Open the inbound ports for your profile (§2) in the cloud console
   (Hetzner, Vultr, AWS, …) or forward them on your home router. The installer only opens them in
   `ufw`/`firewalld` on the box; a provider firewall sits in front of that.
10. **Outbound access.** The box must reach GitHub (the download), the rootnode
    `65.109.31.226:9001`, the mesh relays on 9501 and, for keets, UDP 49737 to the DHT bootstraps.
    On a default-deny-outbound box, allow those first.
11. **A terminal.** Run the installer in an interactive SSH session. Without a TTY it skips the
    pairing-password and QR steps (you can run them later with `parlons-pair`).

---

## 4. Profile A: plain full node (one command)

A full Minima node plus a relay plus your always-on account, without MegaMMR. 2 GB of RAM is
plenty; the installer accepts 1 GB.

### 4.1 Install

1. Open **TCP 9001** and **TCP 9501** inbound in the provider firewall or router (§3, step 9).
2. In an SSH session on the box, run the installer with `--no-megammr`:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --no-megammr
   ```

   Pass `--heap 1g` as well if the box has 4 GB or more and you want the node to have more room.
3. Watch the five steps:
   1. `[1/5] Java` — installs `default-jre-headless` (apt), `java-21-openjdk-headless` (dnf),
      `openjdk21-jre-headless` (apk) or `jre-openjdk-headless` (pacman) if needed, plus `qrencode`.
   2. `[2/5] Newest Parlons Node release` — finds the newest `node-v…` release, downloads
      `parlons-node-<ver>.jar` and refuses it unless its sha256 matches the release's `SHA256SUMS`.
      It prints `sha256 verified: <hash>`. Compare with §17.3.
   3. `[3/5] Service (starts at boot)` — creates the `maxima` system user, `/var/lib/parlons-node`
      (mode 700), `/opt/maxima/parlons-node.jar` (a symlink to the versioned jar),
      `/usr/local/bin/parlons-pair` and the hardened unit `/etc/systemd/system/parlons-node.service`.
      It prints the sizing: `heap 768m (MemoryMax 1280M), MegaMMR false, syncing from 65.109.31.226:9001`.
   4. `[4/5] Firewall` — allows 9001/tcp and 9501/tcp in ufw or firewalld when one is active.
   5. `[5/5] Waiting for your account to come online` — up to 4 minutes, then prints
      **your account address in full** (`MAX#…`).
4. When asked, type an **admin pairing password** twice. It protects every future invitation for
   your own devices. Then type it once more: the installer draws the **QR code** your phone scans
   (§10).
5. Note the two lines the installer prints last: `BACK UP YOUR SEED` and the manual link. Do §11
   today.

### 4.2 What the installer wrote

The unit's `ExecStart` for profile A is exactly:

```ini
ExecStart=/usr/bin/java -Xmx768m \
    -Dparlons.node.data=/var/lib/parlons-node \
    -Dparlons.node.port=9001 \
    -Dparlons.relay.port=9501 \
    -Dparlons.node.megammr=false \
    -Dparlons.node.rootnode=65.109.31.226:9001 \
    -jar /opt/maxima/parlons-node.jar
```

(`/usr/bin/java` is whatever `readlink -f "$(command -v java)"` resolved to on your box.) Everything
else in the unit is the hardening and the resource ceilings shown in §7. The admin RPC is **off**,
the DEX cape is off, there is no keets sidecar, and the mesh uses the compiled-in bootstrap list.

### 4.3 Check it

Go to §9 (first start) and §12 (verify from outside).

---

## 5. Profile B: wallet gateway node with MegaMMR

A node that phones can use as a wallet gateway. It needs **8 GB of RAM**, a **3 GB heap**, the
**admin RPC** (for the MegaMMR import), a **DNS name with TLS** in front of the gateway, and a
one-time **MegaMMR snapshot import**. The deploy script does the node part in one command; the TLS
front is yours.

### 5.1 Install with the deploy script (from your own computer)

The deploy script runs on your computer against a fresh box you reach over SSH as root with a key.
Running it again later is the update path.

1. Clone the repository on your computer:

   ```bash
   git clone https://github.com/eurobuddha/maxima.git
   cd maxima
   ```

2. Download the jar and the checksums into `dist/`, and verify. The hash must match §17.3:

   ```bash
   mkdir -p dist && cd dist
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v0.2.143/parlons-node-0.2.143.jar
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v0.2.143/SHA256SUMS
   grep ' parlons-node-0.2.143.jar$' SHA256SUMS | shasum -a 256 -c -
   cd ..
   ```

   It must print `parlons-node-0.2.143.jar: OK`.
3. Check key-based root SSH (the script uses `BatchMode=yes` and cannot answer a password prompt):

   ```bash
   ssh -o BatchMode=yes root@node.example.com true && echo ssh-ok
   ```

4. Open **TCP 9001, 9501, 80 and 443** in the provider firewall.
5. Run the deploy. This is the full profile B command; every flag is explained in §8.4:

   ```bash
   ops/deploy-parlons-node.sh root@node.example.com \
       --jar dist/parlons-node-0.2.143.jar \
       --heap 3g \
       --rootnode 65.109.31.226:9001 \
       --rpc \
       --megammr-seed https://eurobuddha.com/mega.mmr \
       --public https://node.example.com/parlons-node \
       --dex 9602 --dex-public https://node.example.com/dex-relay
   ```

   Leave out the `--dex …` line if you do not want to carry Dexicon's order book. **Never pass
   `--dex` without `--dex-public`**: without the public URL the DEX cape binds `0.0.0.0` and the
   script opens the port as plain HTTP to the internet.

   What the script does, in order: installs a headless JRE if needed, creates the `maxima` user and
   `/var/lib/parlons-node`, uploads the jar to `/opt/maxima/parlons-node-0.2.143.jar` and verifies
   its sha256 **before** flipping the `parlons-node.jar` symlink, installs `parlons-pair`, writes the
   unit, opens 9001 and 9501 in ufw/firewalld, starts the node, waits for the relay port, then (step
   6c) downloads `mega.mmr` into the data directory, imports it over the loopback RPC, restarts, and
   proves `megammr.mmr` is larger than 1 MB. It ends with the account address, the `Mx…` identity
   and the gateway bearer token, all in full.
6. Set up the TLS front (§5.3). The public URLs are already in the unit from `--public` and
   `--dex-public`, so no unit edit is needed.
7. Pair your phone (§10), back up the seed (§11), verify from outside (§12).

### 5.2 Install with the one-line installer instead

If you prefer to work on the box, the installer gets you most of the way; you then add three
properties and import the MegaMMR by hand.

1. Open the ports (step 4 above) and run the installer with an explicit heap:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --heap 3g
   ```

   With 3.5 GB of RAM or more the installer turns MegaMMR on. `--heap 3g` gives it `MemoryMax=4096M`.
2. Edit the unit to turn on the admin RPC and name the public URL. Back it up first:

   ```bash
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-gateway
   nano /etc/systemd/system/parlons-node.service
   ```

   Add these lines inside `ExecStart`, before `-jar` (each line ends with a backslash):

   ```
   -Dparlons.node.rpc=true \
   -Dparlons.node.public=https://node.example.com/parlons-node \
   ```

3. Reload and restart, then check the boot line says `admin rpc 127.0.0.1:9005 (loopback-bound)`:

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   systemctl daemon-reload && systemctl restart parlons-node
   sleep 25; journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|admin rpc' | grep -v seed
   ```

4. Import the MegaMMR snapshot (§5.4).
5. Set up the TLS front (§5.3).

### 5.3 The TLS front

The gateway (`127.0.0.1:9585`) and the DEX cape (`127.0.0.1:9602`) speak plain HTTP on loopback. A
web server on the box terminates TLS and forwards two paths:

| Public path | Forward to | Strip |
|---|---|---|
| `https://node.example.com/parlons-node/…` | `http://127.0.0.1:9585/…` | `/parlons-node` |
| `https://node.example.com/dex-relay/…` | `http://127.0.0.1:9602/…` | `/dex-relay` |

Never forward the admin RPC (9005), the panel (9587) or MDS.

With **Caddy** (gets its own certificate):

```caddy
node.example.com {
    handle /dex-relay/* {
        uri strip_prefix /dex-relay
        reverse_proxy 127.0.0.1:9602
    }
    handle /parlons-node/* {
        uri strip_prefix /parlons-node
        reverse_proxy 127.0.0.1:9585
    }
    # a catch-all `handle { … }` for your own site goes LAST
}
```

Use `handle` + `uri strip_prefix`, **not** `handle_path`: Caddy sorts `handle_path` after a
catch-all `handle`, which then answers every DEX request with your web page.

With **nginx**, inside the `server { listen 443 ssl; … }` block:

```nginx
location /dex-relay/ {
    proxy_pass http://127.0.0.1:9602/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_read_timeout 60s;
}
location /parlons-node/ {
    proxy_pass http://127.0.0.1:9585/;
    proxy_http_version 1.1;
    proxy_set_header Host $host;
    client_max_body_size 2m;
}
```

The `X-Forwarded-For` line is required for the DEX block: without it every DEX client shares one
rate-limit bucket. Apache's equivalent is in the manual
([§11](https://parlons.info/guide/node.html#11-tls-fronting-caddy-nginx-apache)).

Then test from **another machine**:

```bash
curl -s https://node.example.com/dex-relay/dex/health          # {"ok":true,"version":"0.1.5",...}
curl -s -o /dev/null -w '%{http_code}\n' -X POST https://node.example.com/parlons-node/cmd   # 401
```

### 5.4 Import the MegaMMR snapshot (once)

The deploy script's `--rpc --megammr-seed` already did this. Do it by hand after the installer
path, or whenever `megammr.mmr` is under 1 MB.

1. The admin RPC must answer:

   ```bash
   curl -s http://127.0.0.1:9005/status | head -c 200; echo
   ```

2. Check the current size (under 1 MB = empty):

   ```bash
   stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

3. Download the snapshot into the data directory (about 460 MB; `-c` resumes):

   ```bash
   cd /var/lib/parlons-node
   wget -c -O mega.mmr https://eurobuddha.com/mega.mmr
   chown maxima:maxima mega.mmr
   ```

4. Import over loopback. It must reply `"status":true` and ends with a node shutdown:

   ```bash
   curl -s -m 3600 'http://127.0.0.1:9005/megammr%20action:import%20file:/var/lib/parlons-node/mega.mmr'
   ```

5. Bring the node back and check the file is now over 1 MB:

   ```bash
   systemctl restart parlons-node
   sleep 20; stat -c %s /var/lib/parlons-node/1.1/databases/megammr.mmr
   ```

6. Query a funded address. The balance must not be empty:

   ```bash
   curl -s 'http://127.0.0.1:9005/balance%20megammr:true%20address:<a funded Mx or 0x address>'
   ```

7. Delete the download:

   ```bash
   rm -f /var/lib/parlons-node/mega.mmr
   ```

**Order matters:** if you also adopt an existing seed phrase (§13.6), do that **before** the
import. A resync resets the chain databases and throws the imported MegaMMR away.

### 5.5 Confirm the gateway is advertised

Phones learn the gateway from the relay greeting only when **MegaMMR is on**, **`parlons.node.public`
is set** and **the relay is on**. Check:

```bash
journalctl -u parlons-node -b --no-pager | grep -E 'wallet gateway (up|advertised|FAILED)'
```

You want `wallet gateway advertised to phones: https://node.example.com/parlons-node/cmd`. Then
test the token (§12, step 4).

---

## 6. Profile C: fleet endpoint (every service in one jar)

The fleet jar makes a **fresh, directly reachable Linux box** a full endpoint for every service with
one install. On top of the node, cape, gateway and account it turns on:

- the Minima **server role** (`-server`: the node accepts inbound P2P links),
- the **keets transport** for the embedded node,
- a public **HyperDHT bootstrap node** on **UDP 49737**,
- a **keets mailbox** (store-and-forward for phones that are offline),
- the **admin RPC** (loopback) and, when you give it a TLS URL, the **DEX cape**.

The keets sidecar runs **inside the jar's own Node.js** (v20.20.2, embedded for linux-x64 and
linux-arm64, with keets sidecar 1.3.0), extracted once into
`/var/lib/parlons-node/runtime/node-v20.20.2+sidecar-1.3.0/`. Nothing else is installed on the box.

### 6.1 Requirements that differ from A and B

- **x86_64 or aarch64** (`uname -m`). Anything else is refused: "A fleet box needs x86_64 or aarch64".
- **2 GB of RAM minimum** (the installer refuses under 1900 MB). The unit's `MemoryMax` is heap +
  512 MB **+ 1 GB** for the sidecar. 4 GB is comfortable; 8 GB with `--heap 3g` for MegaMMR.
- **A public IPv4 address on the box itself.** The installer detects the box's global-scope address
  and refuses private, loopback and CGNAT (`100.64/10`) addresses. Behind NAT, pass
  `--public-host <the address the world reaches you on>` **and** forward every port below.
- **Inbound ports:** TCP 9001, TCP 9501, **UDP 9501** (STUN), **UDP 49737** (the DHT). The installer
  opens all four in ufw/firewalld; open them in the provider firewall too.
- The unit gains `AF_NETLINK` (the sidecar's `udx-native` watches interfaces over netlink) and
  `TasksMax=2048`.

### 6.2 Install

1. Open **TCP 9001, TCP 9501, UDP 9501, UDP 49737** (and 80/443 if you will front a DEX cape) in the
   provider firewall.
2. In an SSH session on the box, run the installer with `--fleet`. Add `--no-megammr` on a box under
   8 GB, `--heap 3g` on one with 8 GB or more:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --fleet --no-megammr
   ```

   If the box's address is not on one of its interfaces (NAT), name it:

   ```bash
   curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --fleet --public-host 203.0.113.10 --no-megammr
   ```

3. The installer downloads **`parlons-node-fleet-0.2.143.jar`** (about 109 MB), verifies its
   sha256 against the release's `SHA256SUMS` (§17.3), and writes the unit with two extra properties:

   ```ini
   ExecStart=/usr/bin/java -Xmx768m \
       -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.node.port=9001 \
       -Dparlons.relay.port=9501 \
       -Dparlons.node.megammr=false \
       -Dparlons.node.rootnode=65.109.31.226:9001 \
       -Dparlons.fleet=true -Dparlons.public.host=203.0.113.10 \
       -jar /opt/maxima/parlons-node.jar
   ```

   plus `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK`, `TasksMax=2048` and the
   larger `MemoryMax`. `/opt/maxima/parlons-node.jar` is a symlink to the **fleet** jar.
4. Set the admin pairing password and scan the QR as in profile A (§10).
5. The installer then waits up to 2 minutes for the sidecar and prints the **FLEET ENDPOINT**
   banner (§6.4). Copy it somewhere safe; you need the mailbox pubkey for §6.5.

### 6.3 What fleet mode sets (so you know what you are running)

With `-Dparlons.fleet=true` the node applies these before anything reads them. An explicit value
you set yourself always wins; Minima flags you put in `parlons.node.args` win over fleet mode's.

| Setting | Fleet value | Why |
|---|---|---|
| `parlons.node.rpc` | `true` (if unset) | the operator's channel for `vault`, `megammr action:import`, `keets action:info` |
| `parlons.relay.host` | the `parlons.public.host` value | the cape names itself at once instead of waiting to learn its address from peers |
| `parlons.dex.port` | `9602` **only if** `parlons.dex.public` is set | a plain-http DEX URL would be refused by phones, so the cape stays off until you front it with TLS |
| Minima `-server` role | on (unless you passed `-isclient`, `-desktop` or `-mobile`) | accept inbound P2P links |
| Minima `-host` | the public host | the node advertises the right address |
| Minima `-keets` | on | the embedded node runs the keets transport |
| Minima `-keetssidecar` / `-keetsnode` | the extracted `runtime/…/keets-sidecar/index.js` and `runtime/…/node/bin/node` | the embedded Node.js, not a system one |
| Sidecar extra arguments | `--serve --dht-port 49737 --mailbox-dir /var/lib/parlons-node/keets-mailbox --public-host <ip>` | one sidecar process is transport + DHT bootstrap + mailbox |
| `parlons.keets.dhtport` | `49737` (change it only if you must; every bootstrap list uses 49737) | the DHT's fixed UDP port |

The embedded node also fetches the **signed keets mailbox list** at boot from
`https://eurobuddha.com/keets/mailboxes.json`, checks its Ed25519 signature against the key baked
into the fork (`2b176758f4c9eedcd514cb2accb73d4aa5332821339e41db2aa51da4707a1883`), rejects a list
older than the one it has, caches it under the node's `keets/` folder and passes it to the sidecar.
The log line is `[KEETS] mailbox list : N mailboxes`. (The plain jar does this too.)

### 6.4 The FLEET ENDPOINT banner

Once the sidecar has written its keys (`<node data>/keets/pubkey` and
`/var/lib/parlons-node/keets-mailbox/pubkey`), the node logs:

```
[parlons-node] FLEET MODE: Minima server role on, keets transport + DHT bootstrap (udp 49737) + keets mailbox in one sidecar (node v20.20.2, sidecar 1.3.0, runtime /var/lib/parlons-node/runtime/node-v20.20.2+sidecar-1.3.0), public host 203.0.113.10
[parlons-node] FLEET ENDPOINT
[parlons-node]   keets pubkey    0x<64 hex>
[parlons-node]   DHT bootstrap   203.0.113.10:49737   (add to GeneralParams.KEETS_BOOTSTRAP to seed first contact)
[parlons-node]   keets mailbox   <64 hex>
[parlons-node]   publish it:     node keets/tools/mailboxes-sign.js --key ~/.keets/mailbox-signing.key --in mailboxes.json --out mailboxes.json --add <64 hex>   then copy mailboxes.json to eurobuddha.com/keets/
```

Read it again any time:

```bash
journalctl -u parlons-node --no-pager | grep -A4 'FLEET ENDPOINT' | tail -5
```

### 6.5 Get the box into the fleet lists (one message to the list maintainer)

The Minima P2P mesh and the relay mesh **pick the box up by gossip**; nothing to do. Two things need
a human:

1. **The keets mailbox** joins the fleet only when its pubkey is **signed into the published list**.
   Send the maintainer of `https://eurobuddha.com/keets/mailboxes.json` the `keets mailbox` line
   from the banner, in full. The maintainer runs the `publish it:` command the node printed (the
   signing key never leaves their machine) and publishes the new `mailboxes.json`. Every node
   re-fetches the list at boot, so the box becomes a mailbox for phones over the next restarts.
2. **The DHT bootstrap** `203.0.113.10:49737` becomes a first-contact bootstrap for new installs
   only when it is added to the fork's compiled-in `KEETS_BOOTSTRAP` list at the next release. Until
   then the box is still a full routing node that others find through the DHT itself. Send the
   maintainer the `DHT bootstrap` line too.

Nothing in the banner is secret. The mailbox and keets **private** keys are in `keypair` files
under `/var/lib/parlons-node/keets-mailbox/` and the node's `keets/` folder, owner `maxima`, and
are never printed.

### 6.6 Check the fleet services

1. All four public listeners are up (the DHT is UDP):

   ```bash
   ss -ltnp | grep -E ':(9001|9501) '
   ss -lunp | grep -E ':(9501|49737) '
   ```

2. The keets transport is up and sees the mailbox list, over the admin RPC:

   ```bash
   curl -s http://127.0.0.1:9005/keets%20action:info
   ```

   The reply carries the keets pubkey, the API port, the sidecar version and a `dht` block with
   `mailboxes` and `serve`.
3. From **another machine**, the DHT port answers (UDP, so use a DHT-aware check rather than `nc`):
   the simplest proof is a phone or desktop on the keets build whose `keets action:info` lists the
   new box among its DHT peers, or the maintainer's bootstrap check after the list update.
4. Check the heartbeat as in §9.

### 6.7 Add a TLS front and the DEX cape to a fleet box (optional)

Fleet mode turns the DEX cape on **only** when it has a public URL:

1. Set up Caddy or nginx as in §5.3 for `/dex-relay/` (and `/parlons-node/` if you also want the
   gateway and NFT hosting public).
2. Add to the unit's `ExecStart` and restart (§7, step 7):

   ```
   -Dparlons.dex.public=https://node.example.com/dex-relay \
   -Dparlons.node.public=https://node.example.com/parlons-node \
   ```

   Fleet mode then sets `parlons.dex.port=9602` and binds it to loopback.
3. Check `dex relay 0.1.5 up on 127.0.0.1:9602` and `dex relay announced in the mesh as https://…`
   in the log.

---

## 7. Install by hand (any profile)

These steps do on the box what the installer and the deploy script do. Use them when you want to
see and own every line. Run as root.

1. Install Java (11+, 21+ recommended) and `qrencode`:

   ```bash
   apt-get update && apt-get install -y default-jre-headless qrencode
   ```

2. Create the service user and directories:

   ```bash
   id maxima >/dev/null 2>&1 || useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
   mkdir -p /opt/maxima /var/lib/parlons-node
   chown maxima:maxima /var/lib/parlons-node
   chmod 700 /var/lib/parlons-node
   ```

3. Download the jar for your profile and verify it. Plain jar:

   ```bash
   cd /opt/maxima
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v0.2.143/parlons-node-0.2.143.jar
   echo 'ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc  parlons-node-0.2.143.jar' | sha256sum -c -
   chmod 644 parlons-node-0.2.143.jar
   ln -sfn parlons-node-0.2.143.jar parlons-node.jar
   ```

   Fleet jar:

   ```bash
   cd /opt/maxima
   curl -fLO https://github.com/eurobuddha/maxima/releases/download/node-v0.2.143/parlons-node-fleet-0.2.143.jar
   echo '3f48a0cdfc6ae668aef19b53f0f0c986267c6a390e2601ddf3caf686afb5e2b9  parlons-node-fleet-0.2.143.jar' | sha256sum -c -
   chmod 644 parlons-node-fleet-0.2.143.jar
   ln -sfn parlons-node-fleet-0.2.143.jar parlons-node.jar
   ```

   `sha256sum -c` must print `…: OK`. If not, delete the file and do not run it. Confirm the version
   without starting a node: `java -jar /opt/maxima/parlons-node.jar --version` prints
   `parlons-node 0.2.143`.
4. Install the pairing helper:

   ```bash
   curl -fsSL -o /usr/local/bin/parlons-pair https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/parlons-pair
   chmod 755 /usr/local/bin/parlons-pair
   ```

5. Write `/etc/systemd/system/parlons-node.service`. This is the installer's unit with every
   optional property shown; delete the lines you do not want. `systemd` does not allow comments
   inside `ExecStart`, so the explanations sit above it.

   ```ini
   [Unit]
   Description=Parlons Node - full Minima node + Maxima cape + wallet gateway (0.2.143)
   Documentation=https://parlons.info/guide/node.html
   After=network-online.target
   Wants=network-online.target
   StartLimitIntervalSec=300
   StartLimitBurst=5

   [Service]
   Type=simple
   User=maxima
   Group=maxima
   # -Xmx                 768m plain / 3g MegaMMR. MemoryMax below must sit ABOVE it.
   # parlons.node.data    data dir: seed, identity.txt, tokens, chain
   # parlons.node.port    Minima P2P (public). Derived: MDS +2/+3, admin RPC +4
   # parlons.node.rpc     loopback admin RPC (needed for vault, megammr import, keets info)
   # parlons.node.megammr keep the MegaMMR (wallet gateway needs it; 3g heap)
   # parlons.node.rootnode a Minima peer to discover the chain from (without it: no sync)
   # parlons.relay.port   Maxima cape, public TCP (+ UDP STUN on the same port); 0 = off; shared = rides 9001
   # parlons.relay.peers  mesh bootstrap list (omit = compiled-in fleet list)
   # parlons.gateway.*    wallet gateway + /nft on loopback behind TLS
   # parlons.node.public  public base URL of the gateway front (NFT URLs, gateway advert)
   # parlons.dex.*        DEX cape on loopback, announced as its TLS URL
   # parlons.fleet / parlons.public.host   FLEET JAR ONLY (section 6)
   ExecStart=/usr/bin/java -Xmx768m \
       -Dparlons.node.data=/var/lib/parlons-node \
       -Dparlons.node.port=9001 \
       -Dparlons.node.rpc=false \
       -Dparlons.node.megammr=false \
       -Dparlons.node.rootnode=65.109.31.226:9001 \
       -Dparlons.relay.port=9501 \
       -Dparlons.relay.blob=0 \
       -Dparlons.gateway.port=9585 \
       -Dparlons.gateway.bind=127.0.0.1 \
       -Dparlons.node.public=https://node.example.com/parlons-node \
       -Dparlons.dex.port=9602 \
       -Dparlons.dex.bind=127.0.0.1 \
       -Dparlons.dex.public=https://node.example.com/dex-relay \
       "-Dparlons.node.args=" \
       -jar /opt/maxima/parlons-node.jar
   # always, not on-failure: `quit`, a megammr import and a resync end with exit 0 and the node must come back.
   # exit 3 = "restart me" (a wallet resync or identity import from a paired device).
   Restart=always
   RestartSec=10
   RestartForceExitStatus=3
   SuccessExitStatus=3

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
   # fleet mode or any keets sidecar: add AF_NETLINK at the end of this line
   RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX
   IPAddressDeny=169.254.0.0/16 fe80::/10
   # heap + 512M (fleet: + a further 1024M). 768m -> 1280M; 3g -> 3584M; fleet 768m -> 2304M
   MemoryMax=1280M
   # fleet: 2048
   TasksMax=1024
   LimitNOFILE=65536
   StandardOutput=journal
   StandardError=journal

   [Install]
   WantedBy=multi-user.target
   ```

   For the **fleet jar**, add `-Dparlons.fleet=true -Dparlons.public.host=<public ip>` to
   `ExecStart`, append ` AF_NETLINK` to `RestrictAddressFamilies`, set `TasksMax=2048` and raise
   `MemoryMax` by 1024M.
6. If the box still runs the old standalone relay on 9501, retire it (the unit stays on disk for a
   rollback):

   ```bash
   systemctl disable --now maxima-relay
   ```

7. Open the ports on the host firewall (the provider firewall is separate):

   ```bash
   ufw allow OpenSSH
   ufw allow 9001/tcp
   ufw allow 9501/tcp
   # TLS front:            ufw allow 80/tcp && ufw allow 443/tcp
   # fleet:                ufw allow 9501/udp && ufw allow 49737/udp
   ufw enable && ufw status numbered
   ```

   firewalld: `firewall-cmd --permanent --add-port=9001/tcp --add-port=9501/tcp` (plus the others),
   then `firewall-cmd --reload`.
8. Check the unit, start the node:

   ```bash
   systemd-analyze verify /etc/systemd/system/parlons-node.service
   systemctl daemon-reload
   systemctl enable --now parlons-node
   ```

9. Continue with §9.

---

## 8. Parameter reference

Every setting is a **JVM system property** (`-Dname=value`) placed **before** `-jar`. The jar takes
no command-line options: the only arguments it accepts are `--version`/`-v` and `--help`/`-h`;
anything else prints the help and exits with code 2.

### 8.1 Node and chain

| Property | Meaning | Default | Profile A / B / C |
|---|---|---|---|
| `parlons.node.data` | Data directory. Precedence: this, then Minima `-data` (its parent), then `~/.parlons-node`. | `~/.parlons-node` | `/var/lib/parlons-node` everywhere |
| `parlons.node.port` | Minima P2P port (public). Derived: MDS files `+2`, MDS commands `+3`, admin RPC `+4`. | `9001` | `9001` |
| `parlons.node.rootnode` | A Minima peer `host:port` to discover the chain from. **This fork ships an empty peer list:** without it the node never syncs. | empty | `65.109.31.226:9001` |
| `parlons.node.connect` | Fixed peer list; turns P2P discovery **off**. | empty | unset |
| `parlons.node.megammr` | Keep the full MegaMMR (wallet gateway needs it). Precedence: this, Minima `-megammr`, then `true`. | `true` | A `false` / B `true` / C per RAM |
| `parlons.node.rpc` | Loopback admin RPC on `127.0.0.1:<p2p+4>`. If it cannot bind the node exits 2. | `false` | A `false` / B `true` / C `true` |
| `parlons.node.args` | Minima's own startup flags through Minima's parser (quoted as one argument). Env fallback `PARLONS_NODE_ARGS`. Refused flags: §8.8. | empty | empty |
| `parlons.node.conf` | A `key=value` file of Minima flags read **before** the args. Where `mdspassword=…` belongs (mode 600). | empty | unset |
| `parlons.node.passphrase.file` | Passphrase file of a password-locked node (unlock once at boot). Env alternative `PARLONS_NODE_PASSPHRASE`. No whitespace, `"` or `;` in the value. | empty | unset |
| `parlons.node.archive` | Archive host used by a wallet **resync** requested from a paired device. | a compiled-in archive node (`NodeAccountWallet.java`); the deploy script's `--archive` default is `65.109.31.226:8001` | unset |
| `parlons.node.selftest.send` | **Developer only.** Sends real funds at boot. Never set it. | empty | unset |

### 8.2 Relay cape, gateway, DEX, account, MDS

| Property | Meaning | Default | Profile notes |
|---|---|---|---|
| `parlons.relay.port` | Relay port. A number = own listener; `0` = relay off; `shared` = rides the Minima P2P port (one public port). | `9501` | `9501` |
| `parlons.relay.host` | Public host/IP the cape names itself by. Unset = learned from peers (re-checked every 30 s for 30 min, then every 10 min). Never a bind address, private, loopback or CGNAT. | learned | C: set to `parlons.public.host` |
| `parlons.relay.peers` | Mesh bootstrap list `host:port,…`. Unset = the compiled-in list (§17.4). | compiled-in | unset |
| `parlons.relay.blob` | Media blob shelf in MB (`0` = off). | `0` | `0` |
| `parlons.relay.maxconn` | Connection cap. `0` = 512 on platform threads, 4096 on virtual threads (JDK 21+). | `0` | `0` |
| `parlons.gateway.port` / `parlons.gateway.bind` | Wallet gateway `/cmd` and `/nft`. Keep loopback behind TLS. | `9585` / `127.0.0.1` | B: fronted by TLS |
| `parlons.gateway.token` | Bearer token; else `<data>/gateway-token.txt`, else generated (256-bit, mode 600). | from file | from file |
| `parlons.gateway.rate.global` / `.perip` | `/cmd` rate (req/s; burst 2 s worth). `0` = off. | `50` / `10` | defaults |
| `parlons.gateway.rate.nft.global` / `.perip` | `/nft` rates. | `400` / `40` | defaults |
| `parlons.node.public` | Public base URL of the gateway's TLS front, **without** `/cmd`. Sets NFT URLs; on a MegaMMR node with the relay on it also advertises the gateway to phones. Only a URL that really routes to this box's 9585. | empty | B: `https://node.example.com/parlons-node` |
| `parlons.dex.port` | DEX cape port. `0` = off. | `0` | B optional `9602`; C auto `9602` when `parlons.dex.public` set |
| `parlons.dex.bind` | Explicit wins; else `127.0.0.1` when `parlons.dex.public` is set, else **`0.0.0.0`**. | see left | `127.0.0.1` |
| `parlons.dex.public` | Public URL announced in the mesh. | empty | `https://node.example.com/dex-relay` |
| `parlons.account` | Run the account (`false` = relay/gateway only). | `true` | `true` |
| `parlons.account.name` | Display name (first boot only). | empty | optional |
| `parlons.account.relays` | Extra relays the account attaches to (csv). | empty | unset |
| `parlons.account.builtin` | `false` = don't seed from the compiled-in relay list. | `true` | `true` |
| `parlons.account.direct` | Account's direct (Tier-2) listener port. `0` = off. | `0` | `0` |
| `parlons.panel.port` | Local panel + JSON API on `127.0.0.1` only. `0` = off. | `9587` | `9587` |
| `parlons.hosted.dir` / `parlons.hosted.limit` | Hosted (guest) accounts directory and cap. Env `PARLONS_HOSTED_DIR`. Unset = managed hosting for a separate tenants service. | unset / `10` | unset |
| `parlons.restore` | Restore a `.pbk` account bundle into a **fresh** data dir, then exit (interactive console). | empty | unset |
| `parlons.mds` / `parlons.mds.bind` | Serve MDS (MiniHUB) on `https://<bind>:<p2p+2>/`. Loopback unless told otherwise. | off / `127.0.0.1` | unset |
| `parlons.node.keetschat` | `off` hands the keets inbox to a host app (desktop apps). Keep `on` on a server. | `on` | `on` |

### 8.3 Fleet mode (fleet jar only)

| Property | Meaning | Default |
|---|---|---|
| `parlons.fleet` | `true` turns fleet mode on (§6.3). The plain jar refuses: "this jar carries no fleet/VERSIONS". | `false` |
| `parlons.public.host` | The IPv4 the DHT, the cape and the Minima node name themselves by. The installer detects it or takes `--public-host`. | empty (the DHT learns it from its bootstraps; the cape from peers) |
| `parlons.keets.dhtport` | The DHT bootstrap's UDP port. | `49737` |

Minima keets flags you may put in `parlons.node.args` on any jar (the fleet sets the first three
itself): `-keets`, `-keetssidecar <index.js>`, `-keetsnode <node binary>`,
`-keetsbootstrap host:port,…` (default list in §17.4), `-keetsconnect <pubkeys>`,
`-keetsmaxconns N` (default 6), `-keetsmailboxurl <url>` (empty = the sidecar's built-in list),
`-keetssidecarargs "…"` (extra sidecar arguments; fleet mode appends its own `--serve …`).

Sidecar arguments fleet mode passes: `--serve` (DHT bootstrap + mailbox in the transport process),
`--dht-port 49737`, `--mailbox-dir /var/lib/parlons-node/keets-mailbox`, `--public-host <ip>`.
Other sidecar options exist (`--maxconns`, `--dht-mode routing|client`, `--bootstrap none` for a
private DHT); a fleet box leaves them at their defaults.

### 8.4 Script flags → properties

**Installer** `get-parlons-node.sh` (options go after `bash -s --`):

| Option | Effect |
|---|---|
| *(none)* | Install, or update to the newest release and restart. |
| `--update` | Same as running it again. |
| `--uninstall` | Stop and remove the service, `parlons-pair` and the extracted fleet runtime. Data and seed are kept. |
| `--no-megammr` | `-Dparlons.node.megammr=false`, heap 768m. Chosen for you under 3.5 GB RAM. |
| `--heap <size>` | `-Xmx`; `MemoryMax` = heap + 512M (+ 1024M with `--fleet`). Must end in `m` or `g`. |
| `--fleet` | The fleet jar, `-Dparlons.fleet=true -Dparlons.public.host=<detected>`, `AF_NETLINK`, `TasksMax=2048`, opens 9501/udp and 49737/udp. |
| `--public-host <ip>` | With `--fleet`: the public IPv4 instead of the detected one. |

Fixed by the installer: rootnode `65.109.31.226:9001`, P2P 9001, relay 9501, DHT 49737, data
`/var/lib/parlons-node`, jars in `/opt/maxima`, RPC off (non-fleet), no DEX, no peers list.

**Deploy script** `ops/deploy-parlons-node.sh <ssh-target> …`:

| Flag | Becomes | Default |
|---|---|---|
| `--jar FILE` | jar to upload | newest `dist/parlons-node-*.jar` |
| `--heap 3g` | `-Xmx` | `2g` (too small for MegaMMR: pass `3g`) |
| `--memmax 3584M` | `MemoryMax` | heap + 512M |
| `--p2p-port N` | `-Dparlons.node.port` | `9001` |
| `--relay-port N` | `-Dparlons.relay.port` | `9501` |
| `--gateway-port N` | `-Dparlons.gateway.port` | `9585` |
| `--gateway-public` | `-Dparlons.gateway.bind=0.0.0.0` and opens the port (avoid; use TLS) | loopback |
| `--public URL` | `-Dparlons.node.public` | unset |
| `--rootnode h:p` | `-Dparlons.node.rootnode` | **unset (node will not sync)** |
| `--no-megammr` | `-Dparlons.node.megammr=false` | MegaMMR on |
| `--rpc` | `-Dparlons.node.rpc=true` | off |
| `--peers h:p,…` | `-Dparlons.relay.peers` | compiled-in list |
| `--blobstore MB` | `-Dparlons.relay.blob` | `0` |
| `--dex PORT` | `-Dparlons.dex.port` | `0` |
| `--dex-public URL` | `-Dparlons.dex.bind=127.0.0.1 -Dparlons.dex.public=URL` | unset |
| `--passphrase-file F` | `-Dparlons.node.passphrase.file` (file on the box, mode 600) | unset |
| `--node-args "…"` | `-Dparlons.node.args` (no double quotes inside) | empty |
| `--keets` | adds `-keets -keetssidecar /opt/maxima/keets-sidecar/index.js`, uploads `../keets/sidecar`, adds `AF_NETLINK`; needs `node` on the box | off |
| `--seed-from F [--archive h:p]` | one-time wallet resync to the 24-word phrase in F (needs `--rpc`), then pins `identity.txt` | — / `65.109.31.226:8001` |
| `--megammr-seed URL` | one-time MegaMMR import (needs `--rpc`), skipped when already populated | — |
| `--replace-relay` | stops and disables `maxima-relay.service` first | — |

The deploy script has no fleet flag. A fleet box is installed with the installer's `--fleet`.

### 8.5 Environment variables

| Variable | Meaning |
|---|---|
| `PARLONS_NODE_ARGS` | Fallback for `parlons.node.args`. |
| `PARLONS_NODE_PASSPHRASE` | Passphrase of a locked node (use an `EnvironmentFile=` with mode 600). |
| `PARLONS_HOSTED_DIR` | Fallback for `parlons.hosted.dir`. |
| `MAXIMA_STUN` | `false` turns off the UDP STUN responder on the relay port. |
| `MAXIMA_POW_ENFORCE` | `true` enforces the relay's proof-of-work floor (default: count only). |

### 8.6 Relay-level properties (rarely changed)

`maxima.relay.maxpersource` (32 connections per source IP), `maxima.relay.shed` (384 soft client
target), `maxima.relay.replicate` (3 copies of each directory entry), `maxima.relay.vthreads`
(`true`). Fixed in code: protocol `1.0.48`, 600 messages/min per destination, DEX relay `0.1.5`
with 200 req/s global and 20 req/s per IP.

### 8.7 Precedence

1. Minima's own flags from `parlons.node.conf` (file first) then `parlons.node.args` go through
   Minima's parser.
2. Fleet mode fills what the operator left unset (§6.3).
3. Explicit `-Dparlons.node.*` properties are applied **after** and win on conflict.

The boot log shows what was applied: `minima flags: …` (with `mdspassword` masked) and the boot
line with the ports.

### 8.8 Minima flags the node refuses

Put in `parlons.node.args` or the conf file, these stop the node with exit 2 before it touches the
data directory:

| Flags | Why | Use instead |
|---|---|---|
| `-rpc -rpcenable -rpcpassword -rpccrlf` | the stock RPC binds every interface with full admin | `-Dparlons.node.rpc=true` (loopback only) |
| `-seed -anyseed -dbpassword` | a secret in argv is readable by every process | `--seed-from` / `--passphrase-file` |
| `-clean -genesis -test -solo -testchainlength` | wipe the data dir or leave mainnet | — |
| `-daemon -noshutdownhook -jnlp -help` | the node's JVM owns stdin, exit and the shutdown hook | — |

An unknown Minima flag also exits 2.

---

## 9. First start: what a healthy boot looks like

1. Service state. `-w` matters: `inactive` also contains "active":

   ```bash
   systemctl is-active parlons-node | grep -qw active && echo running
   ```

2. The boot line (version, data folder, ports). Always add `| grep -v seed` when you grep the journal:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Parlons Node|minima flags|FLEET MODE|REFUSING' | grep -v seed
   ```

   Expected:

   ```
   [parlons-node] Parlons Node 0.2.143 — booting embedded Minima node <ver> at /var/lib/parlons-node/1.1 (p2p 9001, megammr false, admin rpc off)
   ```

   with `admin rpc 127.0.0.1:9005 (loopback-bound)` on profiles B and C, and the `FLEET MODE:` line
   on C. A `REFUSING` line means a bad flag or a port clash: §15.
3. The identity and the cape (first boot pins the identity):

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'identity:|Maxima cape|mesh:|own relay|account wallet|wallet gateway|account up|keets|mailbox list' | grep -v seed
   ```

   Expected, in order:

   ```
   [parlons-node] identity: pinned from the vault into identity.txt (wallet resyncs no longer change the identity)
   [parlons-node] mesh: 6 bootstrap peer(s) (default fleet list)
   [parlons-node] Maxima cape up on port 9501 — identity Mx… (derived from the node seed; one seed drives both)
   [parlons-node] account wallet = node wallet: 0x… (Mx…) — …
   [parlons-node] nft hosting: /var/lib/parlons-node/nft served at …
   [parlons-node] wallet gateway up on 127.0.0.1:9585/cmd (megammr=…, bearer token in /var/lib/parlons-node/gateway-token.txt)
   [parlons-node] account up: attached to N relay(s), 0 paired device(s) — pair the first one with the code in …
   ```

   On a fleet box also `[KEETS] mailbox list : 6 mailboxes (…)`, `keets chat bridge up (KeetChat
   interop)`, `keets identity: 0x…` and the `FLEET ENDPOINT` banner. `own relay NOT advertised:
   public host unknown or private` on a home box behind NAT is normal until the node learns its
   address from peers (up to 30 minutes), or set `parlons.relay.host`.
4. The heartbeat, every 30 s:

   ```bash
   journalctl -u parlons-node -f | grep heartbeat
   ```

   ```
   [parlons-node] heartbeat: node block=<n> chainlen=<n> | cape=up:9501 | gateway=up:9585 | account=up:<n> hosts, <n> devices
   ```

   | Field | Healthy |
   |---|---|
   | `block=` | climbing, then at the chain tip (the first sync has no fixed duration) |
   | `cape=` | `up:9501` |
   | `gateway=` | `up:9585` |
   | `account=` | `up:` with 1 or more hosts |

   `heartbeat: node not ready` for the first minute is normal.
5. Nothing bad in the log:

   ```bash
   journalctl -u parlons-node -b --no-pager | grep -E 'Exception|FAILED|REFUSING|OutOfMemoryError|password-locked' | grep -v seed
   ```

   `Invalid SyncBlock as NO PARENT` during catch-up has been seen and recovers by itself as long as
   `block=` keeps climbing.
6. Memory and tasks against the ceilings:

   ```bash
   systemctl status parlons-node --no-pager | grep -E 'Memory|Tasks'
   ```

---

## 10. Pair your phone

Run on the box in an interactive SSH terminal. The app side is in the
[Parlons Cloud Portal manual](https://parlons.info/guide/cloud.html).

1. Set the admin pairing password once (the installer already asked; skip if done):

   ```bash
   sudo parlons-pair --set-admin-password
   ```

2. Show the account address and a one-time **owner** invitation, with a QR code when `qrencode`
   is installed:

   ```bash
   sudo parlons-pair --admin
   ```

3. In **Parlons Cloud** on the phone: **Scan pairing QR**, then **Connect & pair**. A valid code
   pairs immediately; a used code is never valid again.
4. The device count in the heartbeat goes up by one:

   ```bash
   journalctl -u parlons-node --since -2min --no-pager | grep heartbeat | tail -1
   ```

Guest accounts (`sudo parlons-pair --guest <name>`) need the separate `parlons-tenants` service
with `--panel-port 9597`; see the Cloud Portal manual, §21. `parlons-pair` reads
`/var/lib/parlons-node` by default; pass `--data DIR` otherwise.

---

## 11. Back up the seed and the identity

> **The seed is a wallet.** The 24-word phrase, `identity.txt` and any adopted `seed.txt` can all
> spend funds. Never print them to a log, a ticket, a chat or a recorded terminal.

There are two secrets with one origin: the **wallet seed** in the node's vault
(`/var/lib/parlons-node/1.1/…`) and the **identity phrase** in `/var/lib/parlons-node/identity.txt`
(mode 600), from which the `Mx…` identity and the `MAX#` are derived. On a fresh node they are the
same phrase; after a wallet resync or an identity import they differ, so back up both.

1. **Wallet phrase.** The admin RPC must be on (`-Dparlons.node.rpc=true`; profile A: add it
   temporarily, §7 step 5, or read it once via a paired device's backup feature). In an unrecorded
   session on the box:

   ```bash
   curl -s http://127.0.0.1:9005/vault
   ```

   Write the 24-word `phrase` on paper or into offline storage. Clear the terminal scrollback.
2. **Identity and account files.** Stop the node for a consistent copy, archive everything except
   the chain and NFT temp files, move the archive off the box, delete it there:

   ```bash
   systemctl stop parlons-node
   tar -C /var/lib/parlons-node -czf /root/parlons-node-backup.tgz --exclude='./1.1' --exclude='./nft/tmp' --exclude='./runtime' .
   systemctl start parlons-node
   chmod 600 /root/parlons-node-backup.tgz
   ```

   Then from your computer: `scp root@node.example.com:/root/parlons-node-backup.tgz .` and
   `ssh root@node.example.com rm /root/parlons-node-backup.tgz`. This archive holds `identity.txt`,
   the account (devices, contacts, chat), the relay state, the gateway token and, on a fleet box, the
   keets mailbox keypair. Treat it like the seed.
3. **The unit** and your TLS config: `cp /etc/systemd/system/parlons-node.service /root/`.
4. From a paired device, make a portable `.pbk` account bundle too (Settings → **Back up
   account…**).

Never restore the archive onto a second box while the first one runs. One identity runs in one
place.

---

## 12. Verify from outside

`systemctl is-active` only proves the process runs. Test from **another machine on the public
internet**, never from the box or its LAN (hairpin NAT lies both ways).

1. TCP reachability of the public ports:

   ```bash
   nc -z -w 8 node.example.com 9001 && echo p2p-open
   nc -z -w 8 node.example.com 9501 && echo relay-open
   ```

   Refused at once = host answered, nothing listening (check the service). Timeout = a firewall
   drops packets (provider console or router). Open = the network path is fine.
2. The relay end to end, from a checkout of the repository with `:core` compiled:

   ```bash
   ops/verify-relay.sh node.example.com:9501
   ```

   It attaches two real clients through the relay and makes them talk (greeting, attach, address,
   contact handshake, delivery both ways, receipt, group fan-out). Exit 0 only if everything passes.
3. DEX cape (if on): `curl -s https://node.example.com/dex-relay/dex/health` returns
   `{"ok":true,"version":"0.1.5",…}`; `/dex/relays` lists this node's URL.
4. Gateway (profile B). On the box, `TOKEN=$(sudo cat /var/lib/parlons-node/gateway-token.txt)`;
   then from outside:

   ```bash
   curl -s -H "Authorization: Bearer $TOKEN" -H 'Content-Type: application/json' -d '{"command":"block"}' https://node.example.com/parlons-node/cmd
   ```

   must return the node's JSON with `"status":true`; the same without the header must return
   HTTP 401; `{"command":"vault"}` with the header must return HTTP 403.
5. **Loopback-only ports must not answer.** This must print nothing:

   ```bash
   for p in 9003 9004 9005 9585 9587 9602; do nc -z -w 5 node.example.com $p && echo "EXPOSED $p"; done
   ```

6. Fleet box: the mesh and the DHT pick the box up by themselves. Within about 10 minutes other
   relays list it; after the mailbox list is republished (§6.5), phones use it as a mailbox.

---

## 13. Optional services

Each is a unit edit (back up the unit, edit, `systemd-analyze verify`, `daemon-reload`, restart,
check the boot log) unless noted. Full detail in the manual.

### 13.1 DEX relay cape (Dexicon order book)

```
-Dparlons.dex.port=9602 \
-Dparlons.dex.bind=127.0.0.1 \
-Dparlons.dex.public=https://node.example.com/dex-relay \
```

Front `/dex-relay/` with TLS (§5.3). Boot log: `dex relay 0.1.5 up on 127.0.0.1:9602` and
`dex relay announced in the mesh as …`. Check `ss -ltnp | grep ':9602 '` shows `127.0.0.1`.

### 13.2 NFT hosting

Always on; it only needs `-Dparlons.node.public=https://node.example.com/parlons-node` and the
`/parlons-node/` TLS route. Boot log: `nft hosting: … served at https://…/nft/<file>`. Files are
content-addressed (`<sha256>.<ext>`, at most 32 MB), uploaded from paired devices only.

### 13.3 Mesh peers and the blob shelf

`-Dparlons.relay.peers=host:port,…` to pin the mesh bootstrap list (default: the compiled-in six,
§17.4); `-Dparlons.relay.blob=1024` for a 1 GB media shelf. Boot log: `mesh: N bootstrap peer(s)`,
`blob shelf: 1024 MB`.

### 13.4 keets chat bridge on the plain jar

The fleet jar does this itself. On the plain jar: install Node.js on the box, copy a keets sidecar
with its `node_modules` to `/opt/maxima/keets-sidecar` (the deploy script's `--keets` does both),
then:

```
"-Dparlons.node.args=-keets -keetssidecar /opt/maxima/keets-sidecar/index.js" \
```

and `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK`. No inbound port is needed
(hyperswarm hole-punches). Boot log: `keets chat bridge up (KeetChat interop)` and
`keets identity: 0x…`.

### 13.5 MDS (MiniHUB)

```
-Dparlons.mds=true \
-Dparlons.mds.bind=127.0.0.1 \
-Dparlons.node.conf=/var/lib/parlons-node/minima.conf \
```

with `mdspassword=<password>` in the conf file (owner `maxima`, mode 600; a conf file that cannot
be read stops the node with exit 2). Reach it through an SSH tunnel to `127.0.0.1:9003`. Never bind
it publicly.

### 13.6 Adopt an existing 24-word phrase (keep a relay's or cloud account's identity)

Deploy script: `--rpc --seed-from /path/on/box/seed.txt [--archive 65.109.31.226:8001]`. It resyncs
the wallet to the phrase over the loopback RPC (the phrase travels as a POST body from a mode-600
temp file, never argv), restarts, compares the vault with the file **without printing either**, pins
`identity.txt` and writes `.seed-adopted`. Result line to expect:
`vault MATCHES … - identity preserved (and pinned in identity.txt)`. Do it **before** any MegaMMR
import. Delete the phrase file from the box once you hold an offline copy.

### 13.7 Password-locked node

Put the passphrase in `/var/lib/parlons-node/passphrase.txt` (owner `maxima`, mode 600; not under
`/root` or `/home`, which `ProtectHome` hides; no whitespace, `"` or `;`) and add
`-Dparlons.node.passphrase.file=/var/lib/parlons-node/passphrase.txt`. The node unlocks once per
boot. Until then the cape, gateway and account wait and the log says
`node is password-locked but no passphrase provided`.

### 13.8 Move the node off port 9001 (a stock Minima node on the box)

`-Dparlons.node.port=9101` moves P2P to 9101 and the derived ports to 9103/9104/9105. Open the new
P2P port and use 9105 in every `curl` to the admin RPC.

### 13.9 One public port (home router)

`-Dparlons.relay.port=shared`: the relay rides the Minima P2P port and only 9001 needs forwarding.
Tell users and mesh peers the relay address is `host:9001`.

---

## 14. Update, roll back, uninstall

### 14.1 Update with the installer

Running the installer again fetches the newest release, verifies it, flips the symlink and
restarts. Pass the same options you installed with (`--fleet`, `--no-megammr`, `--heap`), because
the unit is rewritten from them:

```bash
curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --fleet --no-megammr
```

Hand-added properties (`parlons.node.public`, `parlons.dex.*`, `parlons.node.rpc`) are **lost** by
an installer re-run: keep your `parlons-node.service.before-…` backups and re-apply them, or update
by hand (next section). A fleet box re-extracts the runtime only when the embedded versions change.

### 14.2 Update by hand (versioned jars + symlink)

1. Download and verify the new jar as in §7 step 3, under a `.part` name, then rename it.
2. Note the current target and back up the unit:

   ```bash
   readlink /opt/maxima/parlons-node.jar
   cp /etc/systemd/system/parlons-node.service /etc/systemd/system/parlons-node.service.before-parlons-node-<new>.jar
   ```

3. Flip and restart:

   ```bash
   ln -sfn /opt/maxima/parlons-node-<new>.jar /opt/maxima/parlons-node.jar
   systemctl daemon-reload && systemctl restart parlons-node
   sleep 25; systemctl is-active parlons-node
   journalctl -u parlons-node --since -40s --no-pager | grep -E 'Parlons Node|dex relay|FLEET MODE|Exception' | grep -v seed
   ```

4. Verify from outside (§12). Several boxes: **one at a time**.

With the deploy script: re-run it with the same flags and the new `--jar`; a flag you leave out
goes back to its default.

### 14.3 Roll back

```bash
cd /opt/maxima && ln -sfn parlons-node-<old>.jar parlons-node.jar
cp /etc/systemd/system/parlons-node.service.before-<what> /etc/systemd/system/parlons-node.service   # if the unit changed
systemctl daemon-reload && systemctl restart parlons-node
```

Every jar stays in `/opt/maxima`, so a rollback is one `ln -sfn`. A box that used to run
`maxima-relay.service` can go back with `systemctl disable --now parlons-node && systemctl enable --now maxima-relay`.

### 14.4 Uninstall

```bash
curl -fsSL https://raw.githubusercontent.com/eurobuddha/maxima/main/ops/get-parlons-node.sh | bash -s -- --uninstall
```

Stops and removes the service, `parlons-pair` and the fleet runtime directory. **Keeps**
`/var/lib/parlons-node` (identity, wallet, chats) and the jars in `/opt/maxima`. To erase the node
itself, after backing up the seed: `rm -rf /var/lib/parlons-node`.

---

## 15. Troubleshooting

First sort out "can't connect" with `nc -z -w 8 <host> <port>` from outside: **refused** = nothing
listening on the box; **timeout** = a firewall drops packets upstream (provider console, router) and
nothing on the box fixes it; **open** = look at the application. The decisive test is
`timeout 25 tcpdump -ni any 'tcp port 9501' -c 5` on the box while you connect from outside.

| Symptom | Cause | Fix |
|---|---|---|
| The process exits at once and prints the help text | A command-line argument after `-jar` | Every setting is a `-D` property before `-jar`. |
| `REFUSING to start: these Minima flags are not allowed` | A flag from §8.8 in `parlons.node.args` or the conf file | Remove it. |
| `REFUSING to run: admin rpc on 127.0.0.1:9005 cannot start` | Another Minima node on `p2p+4`, or a JRE without `jdk.httpserver` | Move the P2P port (`parlons.node.port=9101`) or install a full JRE (`java --list-modules \| grep jdk.httpserver`). |
| `REFUSING to run in fleet mode: this jar carries no fleet/VERSIONS` | The plain jar with `-Dparlons.fleet=true` | Install with `--fleet` (the fleet jar). |
| `REFUSING to run in fleet mode: no embedded Node.js runtime for <arch>` | 32-bit or unusual CPU | Fleet boxes are x86_64/aarch64 only. |
| `Could not find this box's public IPv4` (installer) | No global-scope address on an interface | `--public-host <ip>` and forward the ports. |
| `block=` stays at 0 | No rootnode, or outbound to it blocked, or clock wrong | Check `parlons.node.rootnode`, `nc -z -w 8 65.109.31.226 9001`, `timedatectl`. |
| `OutOfMemoryError` loop, service "failed" | Heap too small (MegaMMR with 2g) | `-Xmx3g`, `MemoryMax` ≥ heap + 512M, `systemctl reset-failed parlons-node`, restart. |
| `cape=down` / relay port clash | `maxima-relay.service` holds 9501 | `systemctl disable --now maxima-relay`, restart the node. |
| `own relay NOT advertised: public host unknown or private` | Behind NAT, address not yet learned | Wait up to 30 min, or set `-Dparlons.relay.host=<public host>`. |
| `dex relay: no public host known yet - NOT announced` | No `parlons.dex.public` | Set it (and front with TLS). |
| The DEX cape answers on the public IP | `parlons.dex.bind` is `0.0.0.0` | Set `127.0.0.1` and `parlons.dex.public`; close 9602 in the firewall. |
| Phones see empty balances | MegaMMR never imported | §5.4. |
| Gateway returns 503 `no MegaMMR` | `megammr=false` | By design; phones fail over. |
| Gateway never advertised to phones | Needs MegaMMR on, `parlons.node.public`, relay on | §5.5. |
| Caddy serves your website at `/dex-relay/…` | `handle_path` sorted after a catch-all `handle` | Use `handle` + `uri strip_prefix` (§5.3). |
| Every DEX client rate-limited together behind nginx | Missing `X-Forwarded-For` | Add the `proxy_set_header` line (§5.3). |
| keets sidecar: every socket fails with `EPERM` | No `AF_NETLINK` in the unit | `RestrictAddressFamilies=AF_INET AF_INET6 AF_UNIX AF_NETLINK`, `daemon-reload`, restart. |
| Fleet: no `FLEET ENDPOINT` banner within 3 minutes | Sidecar did not start | `journalctl -u parlons-node -b \| grep -iE 'keets\|sidecar' \| grep -v seed`; check `MemoryMax` (sidecar needs its 1 GB), `TasksMax=2048`, UDP 49737 free. |
| Fleet: `[KEETS] mailbox list` missing / falls back to defaults | No outbound HTTPS to eurobuddha.com at boot | Allow it; the sidecar's built-in list of six mailboxes is used meanwhile. |
| The service stays stopped after `quit`, an import or a resync | Old unit with `Restart=on-failure` | `sed -i 's/^Restart=on-failure$/Restart=always/'` the unit, `daemon-reload`. |
| The account never says "attached" | No outbound internet, or clock wrong | `curl -sI https://github.com \| head -1`; `timedatectl`. |
| Cape, gateway, account never start; log mentions a password lock | Locked vault | §13.7. |
| A stuck process | — | `systemctl show -p MainPID parlons-node`, `kill <pid>`. **Never** `pkill -f parlons-node.jar` (matches your own SSH session). |

Clear a tripped start limit with `systemctl reset-failed parlons-node` after fixing the cause.

---

## 16. Security checklist

- [ ] The jar's SHA-256 was checked against §17.3 before it was activated.
- [ ] The service runs as the unprivileged `maxima` user with the hardened unit (§7).
- [ ] `/var/lib/parlons-node` is mode 700; `identity.txt`, `gateway-token.txt`, `pair-code.txt`,
      `panel-ticket.txt` and `keets-mailbox/keypair` are mode 600, owner `maxima`.
- [ ] The wallet phrase and `identity.txt` are backed up offline and appear in **no** log, ticket or chat.
- [ ] Inbound: only the P2P port, the relay port (TCP, and UDP on a fleet box), UDP 49737 on a fleet
      box, and 80/443 for a TLS front.
- [ ] From outside, 9003, 9004, 9005, 9585, 9587 and 9602 do **not** answer (§12, step 5).
- [ ] Whenever `parlons.dex.port` is set, `parlons.dex.public` (or `parlons.dex.bind=127.0.0.1`) is set too.
- [ ] `parlons.gateway.bind` is `127.0.0.1`; the admin RPC is never proxied; MDS is never bound publicly.
- [ ] `-Xmx` is set and `MemoryMax` is above it (fleet: a further 1 GB).
- [ ] `parlons.node.public` points at a URL that really routes to this box.
- [ ] No secrets on the command line: passphrases and `mdspassword` live in mode-600 files.
- [ ] `parlons.node.selftest.send` is **not** set.
- [ ] One host runs this identity and this data directory. The archive from §11 is never restored
      onto a second live box.
- [ ] The provider firewall agrees with ufw/firewalld.
- [ ] An admin pairing password is set; old invitations are used or discarded.

---

## 17. Appendix: ports, files, checksums, exit codes

### 17.1 Ports

| Port (default) | Proto | What | Bind | Exposure |
|---|---|---|---|---|
| `9001` (`parlons.node.port`) | TCP | Minima P2P | all | **Public** |
| `9003` (`p2p+2`) | TCP/HTTPS | MDS files / MiniHUB (only with `parlons.mds=true`) | `127.0.0.1` | Loopback |
| `9004` (`p2p+3`) | TCP | MDS commands | follows MDS | Loopback |
| `9005` (`p2p+4`) | TCP/HTTP | Admin RPC (only with `parlons.node.rpc=true`) | `127.0.0.1`, hard-coded | **Never expose, never proxy** |
| `9501` (`parlons.relay.port`) | TCP | Maxima relay cape | all | **Public** |
| `9501` | UDP | STUN responder on the relay port | all | Optional; fleet opens it |
| `9585` (`parlons.gateway.port`) | TCP/HTTP | Wallet gateway `/cmd` + `/nft` | `127.0.0.1` | Loopback, behind TLS `/parlons-node/` |
| `9587` (`parlons.panel.port`) | TCP/HTTP | Account panel + API | `127.0.0.1`, hard-coded | Loopback (SSH tunnel) |
| `9602` (`parlons.dex.port`) | TCP/HTTP | DEX relay cape | `127.0.0.1` with `parlons.dex.public`, else `0.0.0.0` | Loopback, behind TLS `/dex-relay/` |
| `49737` (`parlons.keets.dhtport`) | UDP | HyperDHT bootstrap (fleet) | all | **Public** on a fleet box |
| `9006` (`p2p+5`) | TCP/HTTP | keets loopback API (token-gated, written to the node's `keets/apitoken`) | `127.0.0.1` | Loopback |

### 17.2 Files

| Path | What | Secret |
|---|---|---|
| `/opt/maxima/parlons-node-<ver>.jar`, `parlons-node-fleet-<ver>.jar` | The jars; every version is kept | no |
| `/opt/maxima/parlons-node.jar` | Symlink the unit runs; flip it to upgrade or roll back | no |
| `/etc/systemd/system/parlons-node.service` | The unit; keep `.before-<what>` backups | no |
| `/usr/local/bin/parlons-pair` | Pairing helper | no |
| `/var/lib/parlons-node/` | Data directory, mode 700, owner `maxima` | **yes** |
| `…/1.1/` | The Minima node: databases, wallet, chain; `1.1/databases/megammr.mmr` | **yes (wallet)** |
| `…/1.1/keets/` | keets identity (`pubkey`, keypair), `apitoken`, mailbox-list cache, media stores | **yes** |
| `…/identity.txt` | Pinned identity phrase or seed hex, mode 600 | **yes (seed)** |
| `…/gateway-token.txt` | Gateway bearer token (read + relay only; advertised to phones) | low |
| `…/account.txt`, `invite.txt`, `pair-code.txt`, `panel-ticket.txt` | Account address; current one-time invite, code and panel link | invite/code/ticket until used |
| `…/devices.json`, `cloud-settings.properties`, `node/`, `chat/`, `media/`, `private-files/`, `relay/`, `nft/` | Account, chat, relay state, hosted NFT files | private |
| `…/keets-mailbox/` (fleet) | Mailbox `keypair` + `pubkey` | **yes (keypair)** |
| `…/runtime/node-<v>+sidecar-<v>/` (fleet) | Extracted Node.js and keets sidecar; `.ok` marker; re-extracted when versions change | no |
| `…/.seed-adopted` | Marker written by `--seed-from` | no |

### 17.3 Release checksums (node-v0.2.143)

```
ff72041f98c1dc483dea6f5479628060170524851565acc1b7f9f9955c4ae3dc  parlons-node-0.2.143.jar
3f48a0cdfc6ae668aef19b53f0f0c986267c6a390e2601ddf3caf686afb5e2b9  parlons-node-fleet-0.2.143.jar
```

Source: `https://github.com/eurobuddha/maxima/releases/download/node-v0.2.143/SHA256SUMS`. Node
releases are published with `--latest=false`, so GitHub's "Latest" badge never points at them;
always pick a node release by its `node-v…` tag. The fleet jar embeds Node.js **v20.20.2**
(linux-x64 sha256 `19e56f0825510207dd904f087fe52faa0a4eb6b2aab5f0ea7a33830d04888b8b`, linux-arm64
`47ef73d543ecf6eb19435f6c03a0ac4809b3bf0dd6b26c7c571efc2a6572a74d`, both from nodejs.org) and keets
sidecar **1.3.0**.

### 17.4 Compiled-in lists (0.2.143)

Relay mesh bootstrap (`Bootstrap.RELAYS`, used when `parlons.relay.peers` is unset):

```
95.179.179.181:9501   sally        Amsterdam, NL
65.109.31.226:9501    eurobuddha   Helsinki, FI
45.77.246.226:9501    maxima       Singapore, SG
78.141.237.9:9501     openproject  London, GB
45.77.57.24:9501      vigilance    London, GB
192.248.151.55:9501   megammr      London, GB
```

keets HyperDHT bootstraps (`KEETS_BOOTSTRAP`, overridable with `-keetsbootstrap`):

```
31.125.188.214:49737, 95.179.179.181:49737, 65.109.31.226:49737, 45.77.246.226:49737,
78.141.237.9:49737, 45.77.57.24:49737, 192.248.151.55:49737,
node1.hyperdht.org:49737, node2.hyperdht.org:49737, node3.hyperdht.org:49737
```

keets mailbox list: `https://eurobuddha.com/keets/mailboxes.json`, Ed25519 public key
`2b176758f4c9eedcd514cb2accb73d4aa5332821339e41db2aa51da4707a1883`; the sidecar's built-in fallback
is the six fleet mailboxes.

Minima rootnode used by the installer: `65.109.31.226:9001`. Archive node used by the deploy
script's `--seed-from`: `65.109.31.226:8001`. MegaMMR snapshot: `https://eurobuddha.com/mega.mmr`.

### 17.5 Exit codes

| Code | Meaning |
|---|---|
| `0` | Clean stop: `quit` over the admin RPC, or the embedded node shut itself down after a resync, restore or reset (systemd restarts it under `Restart=always`). |
| `2` | Refused to start: unknown CLI argument, refused or unknown Minima flag, unreadable conf file, admin RPC could not bind, or fleet mode on the wrong jar or platform. |
| `3` | Restart requested: a wallet resync or an identity import from a paired device (`RestartForceExitStatus=3`). |

### 17.6 Glossary (short)

**Cape**: the Maxima relay inside the node. **MegaMMR**: Minima mode that keeps proofs for every
coin, so the node can answer for any address. **Rootnode**: the Minima peer the node first
discovers the network from. **Mesh**: the relays' network for directory lookups. **keets**: the
peer-to-peer transport over HyperDHT that KeetChat and the bears use; a fleet box is a bootstrap and
a mailbox for it. **MAX#**: an account's permanent, shareable address. **Mx…**: the Maxima public
identity. **MxUSD**: the Minima dollar-pegged token, id
`0x7D39745FBD29049BE29850B55A18BF550E4D442F930F86266E34193D89042A90`.
