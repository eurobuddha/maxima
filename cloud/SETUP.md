# Set up Parlons Cloud on your own VPS

Parlons Cloud is your **always-on Parlons account**. Instead of your identity
living on one phone that sleeps, it runs on a small server you own: it holds your
identity, your chats and a watch-only wallet, stays online 24/7, and — because
it's always up and public — also pulls its weight as a **relay** for the network.

The payoff: **one account on all your devices**, messages and calls that arrive
even when every phone is asleep, and you strengthen the mesh by running it.

This guide takes you from "I have a VPS" to "my phone is paired."

---

## What you need

- **A small VPS** — 1 GB RAM is plenty (it's a routing table, a mailbox and a
  light wallet, not a full chain node). Any provider: Vultr, Hetzner, DigitalOcean…
- **SSH access to it** as root (or a sudo user).
- **One open TCP port** — `9501` by default. See *Firewall* below.
- **The `parlons-cloud.jar`** — from the project's releases, or built with
  `./gradlew :cloud:fatJar` (lands in `cloud/build/libs/`).
- **The Parlons Cloud app** on your phone.

You do **not** need a domain, a reverse proxy, or a TLS cert. The node reaches
the network through the Maxima relay mesh; your phone talks to it over Maxima's
own encrypted transport, not a web port.

---

## The easy way — one command

From a checkout of this repo, with the jar built (or in `dist/`):

```bash
ops/deploy-parlons-cloud.sh root@YOUR.VPS.IP --port 9501 --name "My account"
```

That script is **idempotent** — run it again any time to update to a newer jar;
install and update are the same command. In order it:

1. installs a headless Java if the box has none,
2. creates an unprivileged `maxima` user and a `700` data dir at `/var/lib/maxima`,
3. uploads the jar, **sha256-verifies** it, and points a stable symlink at it,
4. writes a **hardened** `parlons-cloud.service` (sandboxed, memory-capped,
   seccomp-filtered, cloud-metadata blocked),
5. opens the port in `ufw`/`firewalld` if either is active,
6. starts it and proves it's listening,
7. **prints your pairing credentials** — the account address and the one-time code.

Skip to *Pair your phone*.

---

## The manual way

If you'd rather do it by hand, the whole thing is four commands. On the VPS:

```bash
# 1. Java + a place to live
apt-get update && apt-get install -y default-jre-headless
useradd --system --home /var/lib/maxima --shell /usr/sbin/nologin maxima
mkdir -p /opt/maxima /var/lib/maxima && chown -R maxima:maxima /var/lib/maxima && chmod 700 /var/lib/maxima

# 2. Drop the jar in /opt/maxima (scp it up, or download your release)

# 3. First run — foreground, so it prints your NEW seed to the terminal ONCE
sudo -u maxima java -jar /opt/maxima/parlons-cloud.jar --data /var/lib/maxima --relay-port 9501
```

That first foreground run prints your 24-word seed to the screen — **write it
down now** (see *Back up your seed*). Stop it with Ctrl-C, then install it as a
service so it survives reboots and stays off your terminal:

```bash
# 4. A minimal unit (the deploy script writes a much more hardened one)
cat > /etc/systemd/system/parlons-cloud.service <<'EOF'
[Unit]
Description=Parlons Cloud
After=network-online.target
Wants=network-online.target
[Service]
User=maxima
ExecStart=/usr/bin/java -Xmx256m -jar /opt/maxima/parlons-cloud.jar --data /var/lib/maxima --relay-port 9501
Restart=on-failure
[Install]
WantedBy=multi-user.target
EOF
systemctl daemon-reload && systemctl enable --now parlons-cloud
```

> Once it runs under systemd, its output goes to the journal — and the node
> **deliberately never prints the seed to a non-terminal**, so your spendable
> seed never lands in a log file. That's why you read it during the foreground
> run above (or with `cat /var/lib/maxima/seed.txt`).

---

## Pair your phone

Pairing needs exactly **two** things, both read over SSH:

**1. Your account address** — the permanent `MAX#…` string. It never changes and
it's public (it's how people reach you). Find it in the log:

```bash
journalctl -u parlons-cloud | grep 'permanent address' | tail -1
```

**2. A one-time pairing code** — auto-created on first run, single-use, and
**never written to any log** (it lives in a `0600` file). Read it:

```bash
cat /var/lib/maxima/pair-code.txt
```

Then, in the **Parlons Cloud app**:

1. On the welcome screen, **paste the account address** (or tap *Scan account
   QR* if you have it as a QR).
2. Enter the **pairing code** (e.g. `ABCD-EFGH-JKLM`).
3. Tap **Connect & pair.**

That's it — the device is authorized, the code is consumed, and the account is
remembered on that phone.

**Adding more devices later** never needs SSH again: on an already-paired phone,
open the **Node** tab → **New pairing code**, and enter it on the new device. (Or
a new device with no code lands in *pending* for an existing device to approve.)

---

## Back up your seed — this is money

The 24 words at `/var/lib/maxima/seed.txt` are your **identity AND a spendable
Minima wallet**. Whoever holds them *is* this account.

```bash
ssh root@YOUR.VPS.IP 'cat /var/lib/maxima/seed.txt'   # read once, store offline
```

Write them on paper, keep them somewhere safe, and never paste them into a chat
or a note that syncs to the cloud. If you lose the VPS but have the seed, you can
restore the whole account onto a new one.

> **One seed, one node — ever.** Never run two nodes on the same seed: two
> key-use counters over one wallet will eventually reuse a one-time signature and
> leak the key. To move to a new box, restore the backup there and **stop the old
> one for good.**

---

## Managing it

| Task | How |
|---|---|
| See status / logs | `journalctl -u parlons-cloud -f` |
| Update to a new jar | re-run `ops/deploy-parlons-cloud.sh <target>` |
| Roll back | `ln -sf parlons-cloud-<OLD>.jar parlons-cloud.jar && systemctl restart parlons-cloud` |
| Restart | `systemctl restart parlons-cloud` |
| Add another phone | Node tab → **New pairing code** (no SSH) |
| Revoke a lost phone | remove it from the device list in the app |
| Encrypted backup | app → Settings → **Back up account…** (a passphrase-encrypted `.pbk`) |
| Move to a new server | on the new box: `java -jar parlons-cloud.jar --restore backup.pbk` (old node stopped for good) |

---

## Firewall

The node needs **one inbound TCP port** reachable from the internet (`9501` by
default) — that's both its relay port and how it's directly reachable.

The deploy script opens it in `ufw`/`firewalld` for you. But most cloud
providers **also** have a separate firewall in their web console (Hetzner Cloud
Firewall, Vultr Firewall Group, AWS security group). If your phone can connect
but pairing fails or is flaky, that console firewall is almost always the cause —
allow `9501/tcp` there too.

To prove it from your laptop (not from the box itself — hairpin NAT lies):

```bash
nc -z -G 5 YOUR.VPS.IP 9501 && echo reachable
```

---

## What's actually running

- An **always-on Parlons account** — your identity, chat engine and a watch-only
  wallet (the spend keys stay on the node; your phone drives it as a thin client).
- A **pool relay** on the same port — so your box carries traffic for the network
  and helps other people's phones stay reachable. Running it is how the mesh gets
  stronger.

Manage the relay side, hosts, location and the live event log from the app's
**Node tab → Open control panel.**
