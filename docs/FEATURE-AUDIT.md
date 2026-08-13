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
| `MAXIMACONTACTS` | contact list changed | **GAP** |
| `MAXIMAHOSTS` | `{host, connected}` | **GAP** |

Both gaps matter for a companion-app ecosystem: without them, an app using our
transport cannot react to a contact appearing or a host dropping.

## 6. Background behaviour

| behaviour | classic | ours |
|---|---|---|
| main loop | 20 min | 60 s maintain + alarm heartbeat |
| first loop after boot | 3 min | immediate |
| refresh delay after MLS check | 60 s | n/a |
| MLS staleness threshold | 30 min | not yet scheduled |
| check-connected audit | 30 s | attach is synchronous |
| MLS server rotation | max once / 12 h | **GAP** |
| MLS entry TTL | 24 h | **DONE** |
| host purge when disconnected | 7 days | scored, not purged |
| poll stack | 256 then **cleared wholesale** | Outbox with backoff (better) |
| max contacts | 30 | unlimited |
| re-announce on host loss | targeted per contact | **DONE** |

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

**To reach classic parity:** `mlsinfo`, `removepermanent`, `MAXIMACONTACTS` and
`MAXIMAHOSTS` events, the `maxcreate`/`maxsign`/`maxverify`/`maxencrypt`/
`maxdecrypt`/`maxmessage` utility surface, `send` by contact id, richer
`hosts`/`list` output, MLS staleness scheduling and 12 h rotation.

**Already beyond classic:** store-and-forward mailbox, msgid dedup, replay
window, retry with backoff, real end-to-end acks, multi-homing, reply-as-message
RPC, phone-hosted directory/gossip/blobs/witness, contribution policy.

**Still missing for production:** persistence (in progress — nothing survives a
restart today), notifications, message history, release signing, server
config/health/metrics.
