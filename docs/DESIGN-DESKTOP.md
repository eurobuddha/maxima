# The desktop node — set-and-forget relays for everyone

More relays is the whole ballgame: decentralisation is bottlenecked on relay
count, and the shortest path to more relays is removing every ounce of friction
from running one. The desktop node is a native installer (macOS dmg / Windows
msi / Linux deb) that an ordinary person double-clicks; it then runs a FULL
relay — host, MLS directory, mailbox — opens its own inbound port on the home
router, proves that port from outside, announces itself to the fleet, and sits
in the menu bar. No Minima node, no terminal, no config.

## Architecture

One self-contained Java app (`:desktop`, jpackage-bundled JRE) that embeds the
relay in-process — unlike minimaCore-desktop, which spawns `minima.jar` as a
child, our relay is our own Java, so there is no child process to manage:

```
DesktopMain (java.awt.SystemTray)
  ├─ RelayRuntime           the relay: seed 0600, FileStore mailbox, RelayServer,
  │                         30s maintain/flush loop (extracted from server/Main —
  │                         the CLI and the tray share one bring-up, no drift)
  ├─ MaximaNode (client)    outbound: attaches to Bootstrap.RELAYS; used to prove
  │                         our reachability and to hear/spread gossip
  ├─ ReachabilityManager    the router magic (below)
  ├─ RelayGossipClient      discovery (below)
  └─ Tray UI                status dot (green=advertised / amber=working / grey=off),
                            verified public address + copy, clients-attached count,
                            open data folder, quit. Headless-safe on servers.
```

Single instance per data dir (a shared seed run twice splits mailbox state).
Data dir `~/.maxima` — the same as the CLI, so an existing CLI identity is
reused. First run shows the wallet-grade seed-backup dialog (headless installs
log the file location, never the phrase). Auto-start on login: LaunchAgent /
Startup entry / XDG autostart, installed only when packaged
(`jpackage.app-path`).

## The router magic — never advertise hope

`ReachabilityManager` (`core/.../net/`) is the platform-neutral spine of the
phone's Tier-2 `DirectReachability`, with `Gates` (a desktop always wants in;
Android gates on Wi-Fi + charging) and a `Listener` (a relay sets its advertised
public host; a phone sets its direct address) injected:

```
OFF → MAPPING (PortMapper: NAT-PMP, then UPnP IGD; isPublic honesty gate)
    → PROBING (a bootstrap relay dials us back — Probe — and must get a greeting)
    → ADVERTISED (setPublicHost; renew at lease half-life; re-prove every 20 min)
```

The cardinal rule is unchanged from Tier 2: a router accepting a mapping proves
nothing (minimaCore-desktop measured routers that accept and still drop), so
NOTHING is advertised until a third party has dialled us and a greeting came
home. Double-NAT/CGNAT gets an honest "no forwardable public port" and the node
keeps running as an outbound-only contributor.

## Discovery — relay gossip, in classic's own vocabulary

Nothing previously propagated relay endpoints (MLS and address-gossip carry
USER addresses), so a new relay ran in isolation until someone hand-typed its
ip:port. Classic Minima already solved peer discovery, so we speak its exact
vocabulary instead of inventing a parallel protocol — greeting `extraData`
fields `host`, `port` and `peers` (flat `"ip:port"` strings, the
`InetSocketAddressIO` shape), with `P2PPeersChecker`'s verify-before-adopt
model:

- **Announce** — a node that has PROVEN its inbound port claims it in the
  greetings it sends (`HostPool.setAdvertisedEndpoint`), plus an immediate
  greet-and-close round to the bootstrap relays (`RelayGossipClient.announceNow`).
- **Verify (relay side, `RelayPeers`)** — a claim is considered ONLY when the
  claimed host equals the connection's source IP (self-nomination only — gossip
  can never make a relay probe or advertise a third party), rate-limited per
  source, then dial-back verified (`Probe.dial`, a greeting must come home) on a
  background thread. Verified peers are capped (128), TTL'd (2h, refreshed by
  re-claims), and only THEY are shared.
- **Share** — a relay's greeting reply carries its verified peers (≤32), so
  every client learns the wider fleet in the attach it was already doing. Zero
  new message types; pre-gossip relays interoperate untouched.
- **Adopt (client side, `RelayGossipClient`)** — peers lists are HEARSAY. A
  client adopts only after its OWN `Probe.dial` proves the endpoint is a live
  relay; gossip-learned relays are hard-capped (bounded minority, default 8);
  the trusted `Bootstrap.RELAYS` are never evicted; and an adopted relay is only
  a candidate that must earn traffic through `HostPool`'s uptime/success
  scoring.

### Why a Sybil flood gets nothing

Identities and endpoints are free to mint, so the defence never rests on a
name: fake endpoints die at dial-back (relay side) and again at probe (client
side); real-but-malicious relays are bounded to a minority of a client's pool,
start at the bottom of the host scoring, and even then see only per-key routing
metadata — content is E2E-encrypted and clients multi-home. The trusted
bootstrap set cannot be crowded out by construction.

## Packaging, CI, signing

- `org.beryx.runtime` (jlink + jpackage): dmg / msi / deb with a bundled
  minimal JRE (`java.base, java.desktop, java.naming, java.management,
  jdk.crypto.ec`). Locally: `./gradlew :desktop:jpackage -PjpackageJdk=<jdk
  with jmods>` (the pinned Android Studio JBR has no jpackage). Verified: a
  28 MB `MaximaNode-1.0.0.dmg` builds on this machine.
- CI: `.github/workflows/desktop-node.yml` — three-OS matrix (macos-14 /
  windows-latest / ubuntu-latest, Temurin 21), `-PskipAndroid=true` keeps AGP
  out of configuration, artifacts on every run, GitHub Release on
  `desktop-v*` tags.
- Signing is WIRED BUT DORMANT: macOS Developer ID + notarytool + stapler and
  Windows Authenticode run only when the repo secrets exist
  (`MACOS_CERT_P12/…`, `WINDOWS_CERT_PFX/…`, `APPLE_ID/…`). Until then
  installers are unsigned (right-click-Open / "Run anyway").

## Verification

- Loopback: `RelayGossipTest` (greeting codec, spoofed-claim refusal, dead-port
  never verified/shared, learn→prove→adopt end-to-end), plus the standing
  sweep — `ParityTest` 63/63 confirms the greeting changes kept byte parity,
  `FullSendTest` 13/13 confirms the relay stack.
- Live (requires a UPnP/NAT-PMP network): run the app at home → tray goes green
  with a verified `ip:port` → `ops/verify-relay.sh <ip:port>` from outside →
  another device's client hears it via gossip and routes through it.

## Deployment notes

- The FLEET must run a gossip-capable server build before desktop nodes become
  discoverable (old relays ignore claims and share nothing — harmless, not
  helpful). Redeploy with `ops/deploy-relay.sh` per relay.
- The Android app can adopt the same discovery by driving a
  `RelayGossipClient` from its heartbeat (follow-up; `RelayStore` merge).
- The phone's `DirectReachability` still carries its own copy of the
  map→probe→advertise spine; re-pointing it at `ReachabilityManager` is a safe
  follow-up refactor, deliberately not done mid-feature on a live-tested path.
