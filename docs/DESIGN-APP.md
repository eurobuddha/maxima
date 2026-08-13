# The phone app and contacts suite — design

Written after the classic audit (`FEATURE-AUDIT.md`). The audit turned up one
finding that should drive the whole design, so it goes first.

---

## 1. The central problem: identity is stable, addresses are not

A Maxima contact address is `Mx<per-host-key>@host:port`. It is valid **only
while that host connection lives**. Every relay drop changes it. Classic's own
`maxcontacts action:export` help warns you to import the list quickly.

So an address is a **cache entry**, not an identity. The durable handle is the
identity public key, which for us is seed-derived and never changes for the life
of the seed.

Everything below follows from taking that seriously.

```
IDENTITY   0x30819F…   permanent, seed-derived, survives reinstall
   |
   +-- addresses[]     ephemeral, one per attached host, churn constantly
   +-- MLS             who can tell you the current address
   +-- capabilities    what extensions they support
   +-- mailbox         where mail waits when they are gone
```

**Design rule: never store or exchange a bare address as though it identified
someone.** Addresses are refreshed; identities are remembered.

## 2. Resolution ladder

When we need to reach a contact, try in order and stop at the first success.
Each rung is already built:

1. **Cached addresses**, newest first. Multi-homed peers give several — race or
   fail over (`Outbox` rotates on failure).
2. **MLS lookup** — the peer publishes its current address to its Location
   Service. Requires being on their `validPublicKeys`, which mutual contacts are.
3. **Gossip** — ask mutual contacts if they have seen a newer address. This
   fixes classic's orphaning race, where both peers lose their hosts at once and
   each announces to the other's dead address.
4. **Mailbox** — leave it with a relay or a contact's phone; delivered on their
   reconnect.

Only step 1 exists in classic. Steps 2–4 are what make a phone-based messenger
actually work.

## 3. First contact — the honest hard problem

Classic has no answer. MLS authorisation is per-contact: you can only resolve
someone who already listed you. `MAX#pubkey#mls` permanent addresses exist but
need an operator to allow-list you on a static MLS.

For a consumer product the options are:

| approach | works offline | privacy | friction |
|---|---|---|---|
| **QR / share sheet** carrying `MAX#pubkey#mls` | yes, in person | excellent | low in person, none remotely |
| **Invite link** (`maxima://…`) over any existing channel | no | good | low |
| **Directory opt-in** — publish name→identity to relays | no | weak, enumerable | lowest |

**Recommendation: QR + invite link only.** Both carry `MAX#pubkey#mls`, which is
permanent and resolvable, rather than a decaying address. A searchable directory
should stay opt-in and off by default; it is the one feature that would turn a
private network into an enumerable one.

## 4. Contacts suite

**Contact record** (already the shape of `contacts/Contact.java`):

```
publicKey        stable identity, the primary key
name, icon       from their contact-ctrl
addresses[]      all known, most-recently-confirmed first
mls              their Location Service
capabilities     classic vs extended
lastSeen         last successful contact
addressHistory[] NEW - when each address was first/last seen
```

`addressHistory` is the *"information about our changing addresses"* piece:
being able to see that a contact moved host at 14:02 is the difference between
diagnosable and mysterious.

**Screens**

- **Contacts** — list with presence hint (last seen), search, add via QR/paste/
  invite, per-contact detail showing identity, current + historical addresses,
  MLS, capabilities, and the actions classic exposes (remove — which tells them,
  refresh, block).
- **Conversation** — per-contact thread, persisted, with delivery state per
  message (queued → sent → relay-acked → **delivered** via end-to-end ack →
  read). Classic can only ever show "relay acked", which is why its `delivered`
  flag is misleading; we can show the real thing.
- **Me** — name, icon, identity, permanent `MAX#` address, QR, seed backup.
- **Network** — hosts with state, MLS, contribution panel, event log. This is
  the diagnostic screen; it already exists in rough form.

## 5. App architecture

```
MaximaService (foreground)      the transport, always on
  |
  +-- MaximaNode (:core)        identity, hosts, contacts, services
  +-- Store (FileStore)         durable: contacts, settings, threads, mailbox
  +-- NotificationManager       inbound message notifications
  |
  +-- IPC surface               other apps use us as transport
```

Chat lives **on top of** the transport as an application string
(`maxima_chat_v1`), exactly as any third-party app would. That is deliberate: if
our own messenger cannot be built cleanly on the public IPC surface, the surface
is wrong.

## 6. What "production grade" requires

Ranked by what actually breaks:

1. **Persistence** — contacts, threads, mailbox, dedup, outbox. *Nothing
   survives a restart today.* `Store`/`FileStore` are written; wiring is partly
   done.
2. **Notifications** — an inbound message with no notification is a lost message.
3. **Delivery state** — per-message, using the real end-to-end ack.
4. **Release signing** — debug-signed builds cannot be upgraded over.
5. **Battery validation** — the untested risk, and only a real overnight soak
   answers it.
6. **Server ops** — config file, health endpoint, metrics, graceful state save,
   log rotation, systemd hardening.

## 7. Classic parity still outstanding

From the audit: `mlsinfo`, `removepermanent`, `MAXIMACONTACTS`/`MAXIMAHOSTS`
events, the `maxcreate`/`maxsign`/`maxverify`/`maxencrypt`/`maxdecrypt`/
`maxmessage` utility surface, send-by-contact-id, richer `hosts`/`list` output,
MLS staleness scheduling and 12 h rotation.

None of these block a messenger, but all are needed before anyone would call it
a Maxima implementation.

## 8. Deliberately NOT copied from classic

- **30-contact cap** — testware limit.
- **Exporting bare addresses** — export `MAX#pubkey#mls` instead.
- **Poll stack cleared wholesale at 256** — we have a bounded outbox that drops
  oldest-first and never silently empties.
- **`delivered` meaning "a relay queued bytes"** — we distinguish relay-ack from
  end-to-end delivery, because on a phone the difference is constant.
