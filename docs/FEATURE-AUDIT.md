# Classic Maxima — complete feature audit

Every command, action, parameter, event and background behaviour in
`org.minima.system.commands.maxima` and `org.minima.system.network.maxima`,
with our implementation status. Read from the source, not from the help text.

Status: **DONE** = implemented and reachable · **CORE** = implemented in `:core`
but no UI/CLI · **PARTIAL** = incomplete · **GAP** = not built.

---

## 1. `maxima`

| action | params | behaviour | status |
|---|---|---|---|
| `info` | — | name, icon, publickey, mxpublickey, staticmls, mls, localidentity, p2pidentity, contact, logs, poll | **PARTIAL** — we lack `p2pidentity`, `logs`, `poll` |
| `setname` | `name` | display name; triggers a contact refresh | **DONE** |
| `seticon` | `icon` | icon; triggers a contact refresh | **CORE** |
| `hosts` | — | per host: pubkey, lastseen, connected, address | **PARTIAL** — we show host+state, not pubkey/lastseen |
| `send` | `id`\|`to`\|`publickey`, `application`, `data`, `poll`, `delay` | send one message | **PARTIAL** — no `poll`/`delay` (our Outbox supersedes `poll`) |
| `sendall` | `application`, `data`, `delay` | send to every contact | **CORE** |
| `refresh` | — | re-publish to MLS + re-announce to all contacts | **CORE** |

`send` accepts three ways to name a recipient — contact `id`, a full `to`
address, or a raw `publickey`. We only accept a full address. Worth matching:
`id` is what a human actually uses.

## 2. `maxcontacts`

| action | params | behaviour | status |
|---|---|---|---|
| `list` | — | id, publickey, currentaddress, myaddress, lastseen, extradata, **samechain** | **PARTIAL** |
| `add` | `contact` | introduce; peer reciprocates | **DONE** |
| `remove` | `id`\|`publickey` | delete locally **and tell them** | **CORE** |
| `search` | `id`\|`publickey` | find a contact | **CORE** |
| `export` | — | comma-separated address list | **CORE** |
| `import` | `contactlist` | re-add from that list | **CORE** |

Export format is literally `addr,addr,addr` (`maxcontacts.java:310`). Classic
warns these go stale fast, which is correct and important: an exported address
is only valid while that host connection lives. **Exporting addresses is the
wrong primitive** — see the design note in §7.

## 3. `maxextra`

| action | params | behaviour | status |
|---|---|---|---|
| `staticmls` | `host` | pin the Location Service | **DONE** |
| `addpermanent` | `publickey` | allow anyone to resolve this key | **CORE** |
| `removepermanent` | `publickey` | revoke | **GAP** |
| `listpermanent` | — | list | **CORE** |
| `clearpermanent` | — | clear all | **CORE** |
| `getaddress` | `maxaddress` | resolve `MAX#pubkey#mls` → live address | **CORE** |
| `mlsinfo` | — | who is using us as their MLS, and their contacts' keys | **GAP** |
| `allowallcontacts` | `enable` | may strangers add me | **CORE** |
| `addallowed` | `publickey` | allow-list one key (RAM-only in classic) | **CORE** |
| `listallowed` / `clearallowed` | — | manage the allow-list | **CORE** |

## 4. Standalone crypto utilities

These never touch the Maxima wire — they are offline helpers a MiniDapp calls.
We have all the primitives; none are exposed as a callable surface.

| command | behaviour | status |
|---|---|---|
| `maxcreate` | generate an RSA-1024 keypair | **GAP** |
| `maxsign` | sign data with the Maxima key or a given key | **GAP** |
| `maxverify` | verify a signature | **GAP** |
| `maxencrypt` / `maxdecrypt` | RSA+AES to a public key | **GAP** |
| `maxmessage` | `MaximumMessage{version, data, publickey, signature}` — a signed, optionally encrypted blob, with a `0xFFFFFFFF` sentinel for the unencrypted form | **GAP** |

## 5. Events published to apps

| event | payload | status |
|---|---|---|
| `MAXIMA` | `from, to, time, timemilli, random, application, data, msgid` | **DONE** (our listener + IPC) |
| `MAXIMACONTACTS` | contact list changed | **DONE** (EventListener.onContactsChanged → IPC EVENT_CONTACTS) |
| `MAXIMAHOSTS` | `{host, connected}` | **DONE** (EventListener.onHostsChanged → IPC EVENT_HOSTS, both connect and drop) |

## 6. Background behaviour

| behaviour | classic | ours |
|---|---|---|
| main loop | 20 min | **DONE** — 20-min gated Maxima loop (first at 3 min), on top of the 60 s maintain heartbeat |
| first loop after boot | 3 min | **DONE** — FIRST_LOOP_MS = 3 min |
| refresh delay after MLS check | 60 s | folded into refreshContacts() (publish + re-announce in one) |
| MLS staleness threshold | 30 min | **DONE** — checkStaleMls() re-resolves contacts unseen > 30 min |
| check-connected audit | 30 s | **DONE** — auditHosts() self-loopback probe, 30 s grace, drop non-relaying host |
| MLS server rotation | max once / 12 h | **DONE** — stable current + retained old, rotate ≤ once/12 h or on a dead host |
| MLS entry TTL | 24 h | **DONE** |
| host purge when disconnected | 7 days | **DONE** — purgeOldHosts() forgets 7-day-dead relays (still scored while alive) |
| poll stack | 256 then **cleared wholesale** | Outbox with backoff (better) |
| max contacts | 30 | unlimited |
| re-announce on host loss | targeted per contact | **DONE** (host-SET change, not just count) |

## 6b. Transport-liveness parity (the NIO layer under Maxima)

The reference keeps a Maxima host connection alive at the NIO layer, not the
Maxima layer — the original audit above never covered it, and its absence was
what silently dropped a quiet classic ↔ our-node link after 10 minutes.

| behaviour | classic | ours |
|---|---|---|
| read-silence disconnect | 10 min (`MAX_LASTREAD_CHECKER`, swept 2 min) | **DONE** — relay reaps a registered conn silent > 8 min; client detaches a stale host > 8 min |
| peer keep-alive traffic | PING (tip) 180 s + PULSE 10 min | **DONE** — SINGLE_PING every ~120 s, both roles (a chainless node cannot honestly emit PING/PULSE; SINGLE_PING serves the same read-clock purpose) |
| answer a reachability probe | SINGLE_PING → SINGLE_PONG | **DONE** — both relay and client answer with a comms-only greeting |
| reconnect on drop | 5 s fast / 30 s × 3 | **DONE** — reconcile detaches dead/stale and refills immediately |
| black-hole (no-RST) detection | via read-silence timer | **DONE** — the same silence timers, both roles (was absent: `isAttached()` only flipped on explicit close) |

Our one deliberate divergence: classic's periodic keep-alive frames are chain
gossip (a tip id / a recent-block list). We have no chain, so we cannot emit them
honestly; `SINGLE_PING`/`SINGLE_PONG` achieves the same outcome — keep the peer's
read-clock fresh and prove liveness — and the reference handles it with no IBD and
no disconnect (`NIOMessage.java:1058-1107`). This is outcome-parity, not less.

## 7. Where classic's model is wrong for phones

Three findings that should shape the design rather than be copied:

**Exported contacts are addresses, and addresses are ephemeral.** A contact's
`Mx…@host:port` is only valid while that host connection lives. Classic's own
help warns you to import quickly. For a messenger this is the wrong primitive
entirely: the durable handle is the **identity public key**, and the address is
cache. Our `Contact` already keys on `publicKey` with `addresses` as a list —
export should carry `MAX#pubkey#mls`, not a raw address.

**The 30-contact cap** (`maxcontacts.java:28`) is a testware limit.

**MLS authorisation is per-contact.** You can only resolve someone who listed
you in their `validPublicKeys`. That is good privacy and terrible onboarding:
there is no way to message someone who has not already added you, unless they
publish a `MAX#` permanent address and an operator allow-lists them. Any
consumer messenger has to solve first-contact, and classic's answer is
"exchange a long string out of band".

---

## 8. Gap summary — what to build

**Connection-maintenance parity — DONE (0.4.11–0.4.18).** The whole
keep-alive / liveness / reconnect / host-and-MLS-lifecycle set now matches or
exceeds the reference (see §6 and §6b): transport keep-alive both roles,
check-connect audit, scheduled 20-min loop, MLS staleness re-resolve, 12 h MLS
rotation with retained old, 7-day host purge, and the MAXIMAHOSTS/MAXIMACONTACTS
events.

**Still to reach classic parity (command/utility surface, not connectivity):**
`mlsinfo`, `removepermanent`, the `maxcreate`/`maxsign`/`maxverify`/`maxencrypt`/
`maxdecrypt`/`maxmessage` utility surface, `send` by contact id, richer
`hosts`/`list` output.

**Already beyond classic:** store-and-forward mailbox, msgid dedup, replay
window, retry with backoff, real end-to-end acks, multi-homing, reply-as-message
RPC, phone-hosted directory/gossip/blobs/witness, contribution policy.

**Still missing for production:** persistence (in progress — nothing survives a
restart today), notifications, message history, release signing, server
config/health/metrics.
