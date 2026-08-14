# Tier 2 — opportunistic real reachability

> **STATUS: COMPLETE.** A ✅ map (`portmap`, 8/8 + live refusal) · B ✅ listen
> (`DirectEndpoint`, 4/4) · C ✅ prove (`probe.dial`, 7/7 + live on the fleet) ·
> D ✅ orchestrate (`DirectReachability`, review-hardened) · E ✅ LAN
> (`LanDiscovery` + `noteLanPeer`, 4/4). Each phase was code-reviewed and its
> findings fixed before the next. Only Wi-Fi Direct / BLE (phase F) remains
> deferred.

Tier 1 made phones useful **without** reachability: mailbox, directory, gossip,
blobs, witness — all served by dialling out. Tier 2 is the next rung: when
conditions allow, a phone acquires a **genuine public ip:port** and becomes
directly reachable, so a peer can hand it a message with no relay in the path.

The honest framing from the plan still governs everything here: phones are good
at storage and redundancy, bad at routing and uptime. Tier 2 therefore makes a
phone a **directly reachable endpoint**, NOT a relay. It accepts messages
addressed to itself; it does not forward for third parties. Relaying stays on
the six public relays, where uptime lives.

## Why this is worth having

- Every direct delivery is one relay hop removed: lower latency, less relay
  load, and one fewer party who can observe the routing-key-level social graph.
- A phone on home Wi-Fi is reachable for hours at a time. Multi-homing already
  lets senders race addresses and fail over, so a direct address that dies
  costs a sender one timeout, not a lost message.
- On a shared LAN, two phones can exchange messages with **no internet at all**
  (Phase E) — nothing in classic Maxima can do that.

## The classic sin we must not repeat

Classic publishes whatever address it believes it has, verified never. The
whole design below is shaped by one rule: **never advertise an address that has
not just been proven reachable from the outside.** Hairpin NAT makes
self-testing worthless (we measured this on the Pi deployment), so proof means
a third party dialled in and said so.

---

## Phases

### A. `:core` `portmap` — NAT-PMP and UPnP IGD clients  (pure JVM)

New package `com.eurobuddha.maxima.core.portmap`, zero dependencies, zero
Android imports — testable on this Mac against the real home router.

| class | what |
|---|---|
| `NatPmp` | RFC 6886 client. UDP :5351 to the gateway. `externalAddress()`, `map(tcp, internal, external, lifetime)` → external port + granted lifetime. Tiny binary protocol, ~150 lines. |
| `UpnpIgd` | SSDP `M-SEARCH` multicast (239.255.255.250:1900) for `InternetGatewayDevice`, GET the description XML, find the `WANIPConnection` control URL, SOAP `AddPortMapping` / `DeletePortMapping` / `GetExternalIPAddress`. Hand-rolled, ~300 lines; the XML we parse is from our OWN gateway, but is still treated as untrusted input (bounded reads, no entity expansion — regex-free tag scanning). |
| `PortMapper` | Facade. Try NAT-PMP against likely gateways (fast, 250 ms budget), fall back to SSDP (2.5 s budget). Returns `Mapping{externalIp, externalPort, lifetime, via}` with `renew()` and `release()`. |

**Gateway discovery**: SSDP finds the gateway by multicast on its own. NAT-PMP
needs the gateway IP, which the JVM cannot read portably; we try
`x.y.z.1`/`x.y.z.254` derived from the local address, and `:app` can inject the
real gateway from `ConnectivityManager` (LinkProperties) where available.

**Sanity gate**: if the reported external IP is RFC1918 or CGNAT space
(10/8, 172.16/12, 192.168/16, 100.64/10) the mapping is worthless — we are
behind a second NAT — and nothing is advertised. This will be the common case
on cellular and on many ISPs; detecting it cheaply and staying Tier 1 is a
success, not a failure.

**Tests**: in-process fake NAT-PMP responder and fake SSDP/IGD HTTP server with
golden request/response vectors, plus a live opt-in test (`PortMapLiveTest`)
run from this Mac against the real router.

### B. `:core` `net.DirectEndpoint` — accepting a connection at all

`:core` today only dials out; every accept loop lives in `:server`. Tier 2
needs a small, hard-capped inbound endpoint in `:core`:

- `ServerSocket` accept loop on a chosen port; per-connection: exchange
  greetings, then accept **only** `MAXIMA_TXPOW` whose routing key is OUR
  identity → verify → ack `OK`/`WRONGHASH`/`TOOBIG` → feed into the same
  `handle()` path the relay pump uses (dedup, replay window, chat, services all
  come for free).
- Anything addressed to someone else gets `FAIL`. A phone is not a relay; the
  cap is architectural, not a config default.
- Hard limits from birth: max 8 concurrent connections, 30 s idle timeout,
  per-IP rate limit, `MaximaPackage.MAX_SIZE` enforced by the existing frame
  reader. This code faces the open internet from a phone.

`MaximaNode` gains `startDirect(port)` / `stopDirect()` /
`setDirectAddress(ip:port)`; `myAddresses()` returns the direct address first,
then relay addresses. Contact refresh (already multi-homed) advertises it;
senders already race addresses, so no sender-side change at all.

### C. Fleet `probe.dial` — third-party reachability proof  (server 0.1.6)

New RPC service on the relays: "dial this ip:port, exchange a greeting, tell me
if it answered." The phone maps a port, asks a relay to probe it, and only
advertises after a PASS.

**Abuse control** — a naive probe service is a free port scanner:
- the target IP MUST equal the source IP of the connection asking (you can
  only prove YOUR OWN reachability),
- target port > 1024,
- rate-limited per identity alongside the existing message rate cap.

Rolls out with `ops/deploy-relay.sh` (idempotent, fleet-wide, ~2 min).

### D. `:app` `DirectReachability` — the policy loop

State machine driven from the existing 60 s maintain heartbeat (no new
wakeups, no battery cost beyond what the transport already spends):

```
OFF (gates fail) → MAPPING → PROBING → ADVERTISED → (renew at half-life)
     ↑                                     |
     └──────── withdraw + refreshContacts ─┘   on network change/loss/gate-off
```

- **Gates**, reusing `AndroidContribution`: contribution ON + unmetered Wi-Fi
  (+ charging for the initial attempt, per the plan). Cellular never tries —
  carrier CGNAT makes it pointless and the sanity gate would refuse anyway.
- `NetworkCallback.onLost` withdraws the address and refreshes contacts
  immediately — never leave a dead address advertised (the classic sin).
- Lease renewal at half-life on the heartbeat; a failed renewal = withdraw.
- SSDP needs `CHANGE_WIFI_MULTICAST_STATE` + a `MulticastLock` held only
  during discovery.
- UI: a "DIRECT REACHABILITY" card on the Network tab — state, the proven
  address, and a `?` entry in Explain (what it is, why it only happens on
  Wi-Fi, why CGNAT means "unavailable", what it does for the network).

### E. LAN discovery — mDNS/NSD  (second)

`NsdManager` (`:app`): register `_maxima._tcp` with the identity key in a TXT
record; discover peers on the same LAN; deliver directly to their
`DirectEndpoint` over the LAN socket — works with the internet down.

- LAN addresses are shared only with capability-flagged (our-build) contacts.
  Classic rejects RFC1918 hosts outright (`-allowallip`), so it never sees
  them.
- Discovery events feed the contact's address list; the existing sender race
  prefers whatever answers fastest, which on a LAN is the LAN.

### F. Wi-Fi Direct / BLE — explicitly deferred

Infrastructure-free exchange is Tier 2 in the plan but is a different beast
(pairing UX, Android permission thicket, no JVM analogue to test). It stays on
the roadmap; nothing in A–E blocks it.

---

## Order and estimates

| phase | size | risk |
|---|---|---|
| A portmap + fakes + live test | ~600 lines | router quirks — mitigated by testing on the real one |
| B DirectEndpoint + tests | ~300 lines | internet-facing accept loop — mitigated by hard caps |
| C probe.dial + fleet deploy | ~150 lines | scanner abuse — mitigated by source-IP pinning |
| D app wiring + UI + Explain | ~400 lines | Doze/lifecycle — rides existing heartbeat |
| E mDNS/NSD | ~300 lines | Android NSD flakiness — advisory only, never load-bearing |

Verification at the end of each phase, not one big bang at the end:
A: unit vectors + live map on the home router. B: loopback + a live direct
send from the Mac. C: probe from the fleet against a mapped port on this Mac.
D: on-device on home Wi-Fi — map, probe, advertise, then kill Wi-Fi and watch
the withdrawal reach a contact. E: two phones, airplane-mode router test.
Plus the full regression suite before each commit, as always.

## Risks

| risk | posture |
|---|---|
| Router has UPnP/NAT-PMP disabled | Common. Detect fast, stay Tier 1 silently. The card says "unavailable on this network", not an error. |
| CGNAT behind Wi-Fi (double NAT) | The external-IP sanity gate refuses to advertise; correct outcome. |
| Advertised address dies (DHCP change, AP roam) | Withdraw-on-change + senders already fail over to relay addresses; worst case one timeout. |
| Probe service abused for scanning | Source-IP pinning, port floor, rate cap — in the same commit as the service, not after. |
| IPv6 | Deferred entirely: classic parses addresses on first `:` and cannot carry IPv6 literals. Same constraint as always. |
| Battery | All work rides the existing 60 s maintain tick; mapping attempts happen only on gate transitions, not per tick. |
