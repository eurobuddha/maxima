# Maxima — an annotated walkthrough

**Written for someone who already knows Minima.** It does not explain the chain,
the MMR or the KISS VM. It explains, step by step, what this implementation does
on the wire, where every byte comes from, and precisely where it agrees with or
departs from the reference in `org.minima.system.network.maxima`.

Every hex dump here is **generated from the running code** by
`tools/vectorgen/Annotate.java`, not typed by hand, so it cannot drift.

---

## 0. What this is, in one paragraph

Minima Core (this fork) ships no Maxima, so there is no off-chain comms layer and
therefore no Layer 2 at all. This is an original implementation of the same wire
protocol — byte-compatible with stock nodes — built as one dependency-free JVM
core with two hosts: a headless relay and an Android transport. The reason for
rebuilding rather than porting is that classic Maxima structurally excludes
mobile devices from every role except passive consumer, and that exclusion is
fixable without changing a single byte on the wire.

---

## 1. How to read the code

```
core/src/main/java/com/eurobuddha/maxima/core/
    codec/        the primitives: MiniData, MiniString, MiniNumber, MiniByte
    msg/          the envelope: MaximaMessage, MaximaInternal, MaximaPackage,
                  CryptoPackage, MaxTxPoW, TxHeader, Magic, Greeting, MLS packets
    crypto/       RSA/AES/SHA3, and deterministic key derivation from a seed
    identity/     the Mx address codec, BIP39, MaximaIdentity
    net/          Frame (the TCP framing) and HostConnection (relay attachment)
    rpc/          reply-as-new-message: the change that lets a phone serve
    directory/    MLS client, store and service
    mailbox/      store-and-forward
    reliability/  dedup, replay window, outbox with retry
    services/     Tier 1: what a phone contributes
    MaximaNode    the facade both hosts use
    MaximaSender  the complete send path
```

Read them in that order and the protocol assembles itself from the bottom up.

---

## 2. The primitives

Four types carry everything. All integers big-endian, all written through
`DataOutputStream`, exactly as `org.minima.utils.Streamable` does.

| Type | Encoding |
|---|---|
| `MiniData` | `int32 length` + that many raw bytes |
| `MiniString` | a `MiniData` of the UTF-8 bytes |
| `MiniByte` | one raw byte |
| `MiniNumber` | `int8 scale` + `int8 len` + `len` bytes of two's-complement BigInteger |

Three details that bite:

- **`MiniData` has the same shape as the frame header.** `int32 len + bytes`. That
  is why a prebuilt body can be written straight to a socket and already be a
  valid frame — the reference relies on this in `MaxMsgHandler.sendMaxPacket`.
- **Hex of an empty array is `""`, not `"0x"`** (`BaseConverter.encode16`). We
  reproduce the quirk in `Hex.encode`; a vector pins it.
- **`MiniNumber` is capped at ±(2^64−1)** and 44 decimal places, and it will throw
  rather than truncate. We match, including the throw.

---

## 3. A real frame, byte by byte

This is an actual 1,225-byte frame produced by `MaximaSender.build`, dumped by
`tools/vectorgen/Annotate.java`.

```
  offset  bytes                              field                        meaning
  --------------------------------------------------------------------------------

  ---- TCP frame header (Frame.java) ----
  0000  000004C5                           int32 length                 = 1221 bytes of body follow
  0004  0A                                 uint8 type                   0x0A = 10 = MSG_MAXIMA_TXPOW

  ---- MaxTxPoW ----
  0005  00000003                           MiniString len               = 3
  0009  312E30                               "1.0"                      version, parsed then discarded

  ---- MaximaPackage - PLAINTEXT, this is all a relay reads ----
  0012  00000003                           MiniString len               = 3
  0016  312E30                               "1.0"                      version
  0019  000000A2                           MiniData len                 = 162
  0023  30819F300D06092A864886F70D010101..   to (routing key)           recipient's X.509 DER pubkey, IN THE CLEAR
  0185  0000035C                           MiniData len                 = 860  (the CryptoPackage)

  ---- CryptoPackage - everything below here is encrypted to `to` ----
  0189  00000010                           MiniData len                 = 16
  0193  634FAE87425012D8D43FF4EF5A72A376     iv                         AES-CBC initialisation vector
  0209  00000080                           MiniData len                 = 128
  0213  CE2D1761F9C0B58BEAE92532E87C0F31..   secret                     AES-128 key, RSA-wrapped to the recipient
  0341  000002C0                           MiniData len                 = 704
  0345  F2B774A86DE94B687B2547D9DE95091C..   ciphertext                 AES/CBC/PKCS5 over the MaximaInternal

  ---- the TxPoW carrier follows ----
  1049  ...                                TxPoW                        carrier; only customHash is checked
```

Decrypt the ciphertext and you get:

```
DECRYPTED: MaximaInternal, 696 bytes
  0000  000000A2                           MiniData len                 = 162
  0004  30819F300D06092A864886F70D010101..   from                       sender's pubkey (the SIGNER)
  0166  0000018A                           MiniData len                 = 394  (the MaximaMessage)
  0170  00000020B8BBFD6FC1E047DECCDE3237..   data                       the MaximaMessage, signed below
  0564  00000080                           MiniData len                 = 128
  0568  6B11B9A951E01741B3597CF83AC0961A..   signature                  SHA256withRSA over `data`
```

And inside that:

```
INNERMOST: MaximaMessage, 394 bytes
  NOTE THE ORDER: random, from, to, TIME, application, data
  0000  00000020                           MiniData len                 = 32
  0004  B8BBFD6FC1E047DECCDE32379EA7BBAB..   random                     makes every msgid unique
  0036  000000A2                           MiniData len                 = 162
  0040  30819F300D06092A864886F70D010101..   from                       must equal MaximaInternal.from
  0202  000000A2                           MiniData len                 = 162
  0206  30819F300D06092A864886F70D010101..   to
  0368  000601989E26CE00                   MiniNumber timeMilli         scale, len, then BigInteger BE
  0376  00000005                           MiniString len               = 5
  0380  6D79617070                           "myapp"                    the application string
  0385  00000005                           MiniData len                 = 5
  0389  68656C6C6F                           "hello"                    the payload
```

**`timeMilli` is the fourth field, not the second.** Writing it where it reads
naturally produces a message that decrypts fine and then fails to parse. It is
the single easiest thing to get wrong.

---

## 4. Send path, step by step

`MaximaSender.build` then `MaximaSender.send`.

1. **Build the `MaximaMessage`** — 32 random bytes, from, to, `System.currentTimeMillis()`,
   the application string, the payload.
2. **Serialise it.** These bytes are what gets signed and what the msgid is over.
3. **Sign** with `SHA256withRSA` → 128 bytes for RSA-1024.
4. **Wrap in `MaximaInternal`** `{from, data, signature}`.
5. **Encrypt.** Fresh AES-128 key per message, random 16-byte IV,
   `AES/CBC/PKCS5Padding` over the serialised `MaximaInternal`; the AES key is
   `RSA/ECB/PKCS1Padding`-wrapped to the recipient. → `CryptoPackage`.
6. **Wrap in `MaximaPackage`** `{"1.0", to, ciphertext}`. **`to` is in the clear** —
   it is the routing key, and it is the only thing a relay reads.
7. **Size gate.** Over 262,144 bytes and the peer answers `TOOBIG`, so we refuse
   locally rather than waste a round trip.
8. **Build the carrier.** `customHash = SHA3-256(serialised MaximaPackage)`, set
   into a `TxPoW` header. See §6 — we do not mine.
9. **Frame it** as type `10` and write it.
10. **Read the ack.** One frame of type `8` carrying a single status byte.

Sending needs **no greeting and no inbound reachability**. Open a socket, write
one frame, read the ack, close. That asymmetry is the foundation of everything
in §8.

---

## 5. Receive path

`HostConnection.receive`, mirroring `MaximaManager.MAXIMA_RECMESSAGE`.

1. Read a frame; if it is not type `10`, handle it as control or ignore it.
2. `checkValidTxPoW()` → `customHash == SHA3-256(MaximaPackage)`. Fail →
   `WRONGHASH`.
3. Is `MaximaPackage.to` one of our keys? No → not ours (a relay would forward
   here; a leaf answers `UNKNOWN`).
4. RSA-unwrap the AES key, AES-decrypt → `MaximaInternal`.
5. Verify the signature over `mData`.
6. **Bind check:** the inner `MaximaMessage.from` must equal
   `MaximaInternal.from`. Without this anyone could relay someone else's signed
   message under their own name. Fail → `FAIL`.
7. `msgid = SHA3-256(mData)`.
8. Ack `OK`, then dispatch on the application string.

Note the ordering: on a **direct** send, `OK` is only sent after decrypt,
signature and bind all pass — so an `OK` is meaningful. Through a **relay** it
only means the relay found a socket. The reference has the same property and it
is worth being clear-eyed about (§9).

---

## 6. The carrier, and why we do not mine

The reference builds a real `TxPoW` around an empty transaction, sets
`customHash`, targets `minTxPowWork / 1.1`, and mines with a 15-second budget
(`MaxTxPoW.createMaxTxPoW`).

Measured: dividing the *target* by 1.1 makes it **smaller, i.e. harder** — about
**11,000 expected hashes** per message. Against a chain doing ~3.95×10⁸ work per
block, that is **~36,000× easier than a block**. It can never satisfy block
difficulty.

And **no receiver ever compares it to anything.** `checkValidTxPoW()` checks the
custom hash and nothing else. So the work is real CPU cost that enforces nothing:
it throttles honest clients running the reference (and would be a battery tax on
a phone) while costing an attacker nothing, since skipping it is undetectable.

We therefore emit a synthetic carrier with no body. Two things make that safe,
and both are verified:

- `isTransaction()` returns false when there is no body.
- `isBlock()` is `txpowid < blockDifficulty`. **The reference default is
  `MAX_HASH`, which makes this TRUE** — a carrier built with defaults would be
  pushed into the peer's blockchain pipeline. We set `blockDifficulty` to zero.
  This is the sharpest edge in building a carrier and there is a regression test
  pinning it.

`tools/vectorgen/CarrierCheck.java` hands our unmined carrier to the real
reference classes: they parse it, `checkValidTxPoW()` passes, `isTransaction` and
`isBlock` are both false, they decrypt our ciphertext and verify our signature.

**Merge-mining was considered and rejected.** `customHash` lives in `TxHeader`
and `calculateTXPOWID()` hashes the header, so the work *would* commit to the
message — genuinely elegant. But `setTxDifficulty()` writes `mBody.mTxnDifficulty`,
so the target lives in a **body** a carrier has no reason to carry; making it
work needs real coins, MMR proofs and a synced node. Nobody verifies it. And a
million messages a day would add ~28 blocks' worth of work network-wide. It could
never be required without re-excluding phones, which is the whole point.

---

## 7. Addresses

`Address.makeMinimaAddress`, reproduced exactly in `MxAddress`:

```
byte   0x01            mandatory non-zero guard
int16  datalen         big-endian
byte[] data            the raw key
byte[4] checksum       first 4 bytes of SHA3-256(data)
```

then base32 — **not RFC 4648, not Crockford**. It is
`new BigInteger(1, data).toString(32)`, lowercased, with `i→w, l→y, o→z`,
uppercased, prefixed `Mx`.

Because it is a positional big-integer conversion, **leading zero bytes are
destroyed**: `0x00` and `0x000001` both collapse. That is exactly why the `0x01`
guard byte exists. A 162-byte RSA key yields a ~273-character address; that is
expected.

Verified against a live node: our encoder reproduces a running node's own
published `mxpublickey` from its own published `publickey`, byte for byte,
checksum included.

---

## 8. What we changed — and why none of it touches the wire

### 8.1 Reply-as-new-message — the one that matters

In classic, a service reply is sent **in place of the socket ack**. Acks do not
traverse a relay (a relay treats `MSG_PING` as a no-op). Therefore **any
request/response service must be directly reachable**, and that single fact is
why every MLS on the network is a public server and why phones can only consume.

Change it and the problem dissolves. A reply here is a **fresh outbound Maxima
message** carrying a correlation id (`rpc/RpcEnvelope`). A query reaches a phone
through its relay; the phone answers by dialling out, which it can always do.

Not wire-visible: it is an ordinary message on our own application string.
Capability discovery rides in the contact-ctrl JSON, and a peer that advertises
nothing **is** a classic peer, so every extension degrades automatically.

Proven live: two peers on **different relays**, neither ever accepting an inbound
connection, one hosting services the other calls.

### 8.2 Multi-homing

Classic publishes one address from one randomly chosen host and purges hosts
after 7 days. We attach to N relays and publish all of them. It costs nothing on
the wire — just more contact metadata — and each relay sees a **different routing
key**, so two operators cannot correlate you.

### 8.3 Reliability

Classic has no dedup, no replay protection (`timeMilli` is never checked), no
retry (`SocketTimeoutException` is swallowed), and a poll queue that is **cleared
wholesale** past 256 entries. We add msgid dedup, a freshness window, an outbox
with backoff that rotates addresses on failure, and a real end-to-end ack. All
local; all invisible.

### 8.4 Store-and-forward

Classic drops a message for an offline peer and tells the sender `UNKNOWN`. Our
relay holds ciphertext addressed to a routing key and delivers on reconnect. The
operator sees who has mail and roughly how much, never its content. Quotas are
mandatory, because relaying is free and the nominal PoW anti-spam is not actually
verified by anyone.

---

## 9. Where we deviate, stated plainly

| Reference behaviour | Ours | Wire-visible? |
|---|---|---|
| Mines ~11,000 hashes per message | Does not mine | No — nobody checks |
| No dedup or replay protection | msgid cache + freshness window | No |
| No retry | Outbox with backoff | No |
| Offline peer ⇒ message lost | Mailbox | No |
| Sync MLS resolve ignores the echoed `randomUID` (`maxextra.java:339-388`) | Always validated | No |
| One address from one random host | All addresses, multi-homed | No (more metadata) |
| Naive IP prefix blocklist (`100.`/`172.`/`198.` catch public ranges) | Correct CIDR | No |

Things we deliberately reproduce even though they are odd, because interop
demands it: the `MSG_PING` ack channel, the five byte-compared ack bodies, the
non-RFC base32, the empty-hex quirk, `MaximaMessage` field order, and the
asymmetric `CTRL` payloads.

---

## 10. Three bugs that only real software found

Worth recording, because each was invisible to a test suite that only talked to
itself:

1. **`CTRL/TYPE_MLS` carries a bare `Mx` key with no `@host`.** The receiver
   appends the observed socket address (`setMaximaMLS(pk + "@" + fullAddress)`).
   Sending a full address doubles the host. Caught by decoding a real frame from
   a live node — 271 bytes, no `@`.

2. **msgid hashes the `MaximaMessage`, not the `MaximaPackage`.** Two SHA3-256
   values of the same shape, one nested inside the other; the second is the
   carrier's `customHash`. Caught because the relay test compared the sender's
   and receiver's msgids instead of just reporting "message received".

3. **Classic carriers have a body.** The reference builds from
   `new Transaction(), new Witness()`, so `hasBody` is true. Our decoder refused
   to parse one, which would have blocked receiving from **every stock node** —
   and was invisible until a real node sent to us, because our own carriers never
   have a body. We now capture it opaquely and re-emit it byte-identically.

---

## 11. How correctness is established

Not by asserting the protocol was read correctly.

- **`tools/vectorgen/VectorGen.java`** drives the **real `minima.jar`** to emit 54
  byte-exact fixtures. `ParityTest` asserts our codec reproduces every one. The
  gate exits non-zero on any mismatch, and I verified it fails when a vector is
  corrupted — a test that cannot fail proves nothing.
- **`Bip39Check`** runs our seed derivation and the reference's side by side on
  the same phrases, including short words, mixed case and 4-char abbreviation.
- **`CarrierCheck`** hands our output to the reference classes to parse, decrypt
  and verify.
- **Live gates** against a running node and public relays: send, relayed receive,
  cross-relay RPC, multi-homing, and a two-way exchange with a stock node where
  its reciprocation returns through our own relay.

```bash
./gradlew :core:parityTest
java -jar dist/maxima-server-0.1.2.jar --selftest --port 9701
```

---

## 12. What is not proven

- **The Android app has never run on a device.** It builds. Doze, carrier NAT
  idle timeouts, half-open sockets, handover and OEM battery killers are all
  untested. This is the top risk in the project.
- **A classic node adopting *our* relay as its host** is unproven, because the
  reference rejects any host on `127./10./192./172.` without `-allowallip`
  (`MaximaManager.java:525-533`), so it cannot be demonstrated on loopback.
  Sending *through* our relay is proven with `delivered: true` from a stock node.
- **Tier 2 opportunistic reachability** (UPnP/NAT-PMP, LAN mDNS) is designed but
  not built.
