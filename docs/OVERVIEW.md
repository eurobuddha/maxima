# Maxima for Minima Core — what this is

Minima Core (this fork) ships **no Maxima layer**, so there is no off-chain
comms substrate: every peer-to-peer operation on Minima — channel open, payment,
cooperative close, contact identity, message authentication — has nothing to
ride on. This project is that substrate, rebuilt from scratch to be **the same
protocol as classic Maxima, wire-for-wire, but without its structural defects**,
and reachable from a phone.

## The same — provably

It speaks classic Maxima on the live network, byte for byte. Same framing
(`int32 BE length | uint8 type | payload`), same message nesting, same crypto
envelope (RSA-1024, AES-128-CBC, SHA3), same quirky `Mx` base32 address. This is
not a claim — it is gated by tests that run the reference `minima.jar` alongside
ours, and by live interop against real nodes:

- our sender → a stock node accepts it and returns `MAXIMA_OK`
- a stock node sends **through our relay** to a third party
- a stock node **adopts our relay as its Maxima host** (`connected: True`)
- contact exchange, MLS resolve, oversized/corrupt rejection — all round-trip

A classic client cannot tell it is talking to us.

## Improved — invisibly

Everything below is added *without* changing a byte on the wire, so classic
peers are unaffected and simply don't use what they can't see:

| Classic defect | Ours |
|---|---|
| A message to an offline peer is **lost** | **Store-and-forward mailbox** holds ciphertext and delivers it on reconnect |
| Acks lie — a relay ack means only "bytes queued" | A **real end-to-end delivery receipt** comes back from the recipient's own device (the two ticks) |
| No dedup, no replay protection | msgid dedup + a freshness window |
| No retry | Outbox with backoff |
| One relay, one point of failure | **Multi-homing** across several relays; senders race and fail over |
| Phones can only consume | **Reply-as-a-new-message** lets a NAT'd phone answer queries by dialling out — so a handset serves the directory, holds mail, witnesses delivery |
| PoW pretends to gate spam, verified by nobody | Real admission control: rate limits, quotas, connection caps |

## The two artifacts, and how they hold each other up

**`:core` (the JAR)** is the whole protocol as a pure Java 11 library — zero
Android, zero chain dependency. Codec, crypto, identity, the relay/directory/
mailbox logic, reliability, chat, the RPC layer. It is the single source of
truth: everything else is a thin shell around it.

**`maxima-server` (the JAR's headless form)** wraps `:core` as a relay daemon —
the public host a phone dials out to. Six of them run across four operators and
five countries; a stock Minima node can use them too. Runs on a Raspberry Pi
because cheap, plentiful relays are what decentralisation actually needs.

**The APK** wraps the *same* `:core` as an always-on Android transport, plus a
chat app and an IPC surface. The service holds the outbound connection alive
through Doze; the chat UI is just a view over `:core`'s chat engine; the IPC
surface lets *other* apps use Maxima as their comms layer without shipping any
of this themselves.

They support each other in a loop:

- The **APK dials the server**. A phone has no public address, so it holds a
  connection open to relays and receives anything addressed to it back down that
  pipe. Without the servers, phones cannot be reached.
- The **phone gives back** whatever it can (Tier 1): holding mail for offline
  contacts, replicating the directory, witnessing delivery. Thousands of phones
  each doing a little is what keeps the directory and mailbox off a handful of
  central servers — so the servers don't *have* to be trusted or always-on.
- Because both are the **same `:core`**, a fix to the protocol lands in the
  relay and the phone at once, and a message built by one is understood by the
  other by construction — the interop tests exercise exactly this: two `:core`
  nodes talking through a real relay.
- **Any app** (Thunder, a wallet, a WhatsApp-style client) talks to the APK over
  IPC and inherits identity, sealing, NAT traversal, retries and the offline
  mailbox for free — bringing only its own message format.

## For Core specifically

To give Minima Core a Maxima layer, Core embeds `:core` (it already runs on the
JVM) or, on Android, talks to the Maxima APK over the IPC surface. Either way it
gets a transport that is **interoperable with the existing Maxima network from
day one** and strictly better than classic where it counts: messages that
survive the recipient being offline, receipts that don't lie, and no single
relay operator it depends on. Nothing about the chain changes; this is the
missing Layer-2 plumbing, not a fork.
