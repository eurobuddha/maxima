# Run a Parlons Node

A Parlons Node is the full package for a server: a complete Minima blockchain node, a Maxima relay for the network, a wallet gateway that phones can use, and your own always-on Parlons account, all in one process. Most people do not need it: for an account alone, [Parlons Cloud](your-account.html) does the job in 1 GB of RAM. Run a Node when you want to serve the network and hold a full copy of the chain.

## What it costs

| | RAM on the box | Java heap |
|---|---|---|
| Node with MegaMMR (the default; needed for the wallet gateway) | 8 GB | 3 GB |
| Node without MegaMMR | 2 GB | 768 MB |

Disk grows with the chain. The first run downloads the chain; watch the heartbeat line in the log until `block=` reaches the current tip. There is no fixed time for that, it depends on the box and the connection.

## The one supported install

From the Parlons repository on your own computer, against a fresh Linux box you reach by SSH:

```
ops/deploy-parlons-node.sh root@your.box --rootnode 65.109.31.226:9001
```

It installs Java, creates an unprivileged user, uploads the jar and checks its checksum, writes a hardened systemd service, opens the two public ports (9001 for the chain, 9501 for the relay) in the box's firewall, starts, and prints your account address and the location of the one-time pairing code. Run it again to update. Everything the script accepts, the MegaMMR seed, taking over an existing relay's identity, a password-locked node, is in the [operator runbook]({{REPO}}/blob/main/cloud/NODE-SETUP.md).

## After the first start

- Your account's invite (address plus one-time code) is on the box at `/var/lib/parlons-node/invite.txt`; the address alone in `account.txt`. Scan or paste it in Parlons Cloud on your phone.
- `journalctl -u parlons-node -f` follows the log. The heartbeat line every 30 seconds shows the block height, the relay, the gateway and the account.
- The identity is pinned in `/var/lib/parlons-node/identity.txt` (readable by the service user only); the wallet's seed is the node's vault.

## The jar by hand

`parlons-node.jar` takes no command-line options: every setting is a `-D` property before `-jar`. `java -jar parlons-node.jar --help` prints them all with their defaults. The minimum is:

```
java -Xmx3g -Dparlons.node.data=/var/lib/parlons-node -jar parlons-node.jar
```

Use the script instead unless you are building your own service unit.

## If it goes wrong

- **The account never says "attached".** The box has no outbound internet, or the clock is badly wrong.
- **Out of memory loops.** With MegaMMR the heap must be 3 GB on an 8 GB box; the script's `--heap` sets it.
- **Balances are empty on a fresh node.** The MegaMMR starts empty; seed it once as the runbook describes.

Everything else: [Help](help.html) and the [runbook]({{REPO}}/blob/main/cloud/NODE-SETUP.md).
