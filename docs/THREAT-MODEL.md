# Threat model and residual risks

The relays face untrusted input from the open internet; the phone's IPC surface
is callable by other apps on the device; the mailbox holds ciphertext for
strangers; the seed file is a spendable wallet seed. This records what is
defended, and — honestly — what is not.

> **Adversarial audit, 2026-08-14.** Five independent red-team passes (untrusted
> parsing/DoS, SSRF, filesystem, crypto/auth, deploy/systemd) confirmed: no RCE,
> no arbitrary file read/write, no SSRF, no auth bypass, no forgery, no directory
> poisoning, no key/plaintext/seed extraction, and the relay never runs as root.
> The findings were denial-of-service and blast-radius hardening; all are fixed
> in server 0.1.8 + the hardened systemd unit. What follows records the defences
> and the honest residuals.

## Defended

| Attack | Defence |
|---|---|
| CPU exhaustion via forced RSA decrypts | Per-source-IP budget on the addressed-to-us path (120/min); the private key is parsed once, not per message; constant-behaviour decrypt. |
| Memory exhaustion via junk route-key spam | CTRL/TYPE_ID keys must be a full RSA-1024 DER (162 bytes) or are refused; ≤4 routes per connection; `mKnownRoutes` LRU-capped; cleanup removes EVERY key a connection held, not just the last. |
| Connection-slot / thread / FD exhaustion (slow-loris) | Idle reaper applies to ALL connections (registered or not); connection cap 512, per-source 16; per-source inbound frame cap 2000/min. |
| Disk-I/O amplification (mailbox fsync-per-item) | The relay mailbox store is write-behind: one rewrite+fsync per dirty collection per maintenance tick, not per stored item. |
| Directory (MLS) memory exhaustion | `MlsStore` LRU-capped at 200k entries, ≤8 addresses per entry, expired entries swept on the maintenance tick. |
| RSA PKCS#1v1.5 padding oracle (Bleichenbacher) | The logical oracle was already masked (all failures → one `FAIL`); the timing residual is closed with the TLS countermeasure — a bad-padding decrypt substitutes a random key and runs the identical AES work. |
| Destructive mailbox drain by a route hijacker | Drain is non-destructive: it delivers but never acknowledges/deletes on an unauthenticated route, so a hijacker gets opaque ciphertext and the real recipient still collects. |
| Compromised-process reach (metadata credentials) | systemd `IPAddressDeny=169.254.0.0/16 fe80::/10` (a BPF egress filter applied even to an RCE'd process), `SystemCallFilter=@system-service`, empty `CapabilityBoundingSet`, `ProtectProc`, `PrivateDevices`, `RestrictAddressFamilies`. |
| Repeatable-crash → invisible flap DoS | `Restart=on-failure` + `StartLimitBurst=5/300s`: after 5 crashes it stops and stays failed so monitoring sees it. |
| Deploy of a corrupt/tampered jar | The jar's sha256 is verified ON THE BOX before the symlink is flipped. |
| Seed-file exposure window | Created 0600 atomically (perms baked into creation), fails loudly if perms can't be set; data dir 0700; `UMask=0077`; log to the journal, not a world-readable flat file. |
| Mailbox flood → OOM (a message to each of millions of random keys) | We only hold mail for a key that has actually registered a route with us this run; global caps on box count and total bytes with LRU eviction; per-box quotas. Bounded regardless of key count. See `Mailbox`, `RelayServer.handleMaxima`. |
| Allocation amplification (a 1 KB frame declaring a 512 MB field) | `Reads.exact` grows with the bytes that actually arrive and fails EOF the moment the stream runs dry. Memory is proportional to real input, not the claim. |
| Connection flood / slow-loris / FD exhaustion | Global connection cap, per-source-IP cap, and an idle timeout that reaps a connection which never became a client. `RelayServer` accept loop. |
| Rate-limit map growth | The per-destination map is swept of expired entries once it passes a cap. |
| Message replay | msgid dedup cache (the real defence) plus a 6-hour freshness window. Classic has neither. |
| Held ciphertext lost on relay restart | `Mailbox` persists to a `FileStore` under `--data`; store writes are fsync'd before rename. |
| Seed phrase leaking into logs | Printed only to a terminal; under systemd (stdout is a log file) it points at the 0600 seed file instead. |
| Any installed Android app impersonating an approved one | The IPC receiver is guarded by a `signature`-level permission (only apps signed with our key can send) AND verifies the claimed package is signed by our certificate. |
| An approved app forging contacts / chat / MLS as the user | Transport-owned application strings are reserved and refused for SEND/SUBSCRIBE. |
| An approved app hijacking another's inbound traffic | Application-string ownership is first-claim-wins and permanent. |
| Directory redirect by a hostile MLS | The MLS reply's correlation id is always validated (classic ignores it). |
| Message forged as another sender | The receive path binds `from` to the signer and verifies the signature before dispatch. |

## Residual risks — known and accepted

**Routing-key hijack / blackhole (partial).** A routing key is public — it is
in every user's contact address. A client can connect and announce someone
else's key. We refuse to displace a *live* binding for a key (the first holder
keeps it until it actually drops), which stops an online user from being
bumped. But while a user is OFFLINE, an attacker can claim their key and
receive their inbound ciphertext (undecryptable — no private key) or blackhole
it, and hold the key to keep the victim locked out until the attacker's
connection drops. A cryptographic proof-of-possession (relay sends a nonce, the
client signs it with the routing private key) would close it fully but is a new
wire exchange classic clients don't implement, so requiring it would break
interop. The **destructive** part — the mailbox drain deleting a hijacked
victim's held mail — is fixed: drain never acknowledges on an unauthenticated
route, so the worst a hijacker achieves is opaque ciphertext, and the real
recipient still collects when they reclaim the key. Mitigated further by
multi-homing (a sender races several relays; poisoning one is not enough).
**Blackhole/lockout accepted as an interop constraint; destruction fixed.** A
backwards-compatible optional challenge (our clients verify, classic clients
stay unverified and get only non-destructive delivery) is the planned full fix.

**IPC is family-only.** The signature permission restricts callers to apps
signed with our release key. Within that family, one app could still name
another family app in `EXTRA_PACKAGE`, because a BroadcastReceiver cannot bind
the sender to its claim. This is a trust boundary we already accept (they are
all our apps). Opening the surface to arbitrary third-party apps would need a
bound `Service` reading `Binder.getCallingUid()` and a capability token issued
at approval time — deferred until there is a third-party consumer.

**Relay sees the social graph at routing-key granularity.** A relay learns who
talks to whom (the routing key is plaintext), never the content. Mitigated by
multi-homing and per-relay key rotation; not eliminated. Inherent to any relay
model.

**Probe confused-deputy on a shared/CGNAT egress IP.** The probe service dials
only the SOURCE IP of the asking connection, never a caller-named host, so it
cannot scan arbitrary targets. But where many users share one carrier-grade-NAT
public IP, a caller can make the relay dial high ports on that *shared* IP — its
NAT-neighbours. The leaked information is only "a Maxima endpoint answers here,"
and the probe rate limit is keyed on that source IP (not the caller's
free-to-mint identity), so it is throttled to 12/min per egress IP. A low-rate
port oracle against your own CGNAT neighbourhood remains; accepted as inherent
to the shared-IP model.

**No proof-of-work verification.** Deliberate — verifying classic's nominal PoW
would exclude phones and buys nothing, since nobody else verifies it either.
Admission control (rate limits, quotas, caps) is the substitute, and is real.

**LAN forged-OK (availability, our-builds only).** A LAN-discovered address is
tried first and honoured on an unauthenticated `RESPONSE_OK` ack. A hostile host
on the SAME Wi-Fi could advertise a real contact's identity (that contact is
broadcasting it on the segment) pointing at itself and return OK without holding
any key, so the message is not delivered and we do not fall back to the relay
for that send. Confidentiality is intact — the payload is sealed to the
contact's real identity key and the attacker cannot read it — and the contact
record is never altered; this is a LAN-local, our-own-builds, availability-only
issue. Two backstops make it self-correcting: a LAN address is forgotten on the
first failed send and on the mDNS "lost" event, and for chat the missing second
tick (a real end-to-end receipt the forger cannot produce) reveals non-delivery.
A cryptographically authenticated direct ack would close it fully; deferred as
disproportionate for an opportunistic bonus path. Accepted, consciously.

**IPv6 out of scope.** Classic parses an address on the first `:` and cannot
carry v6 literals. Every address path is v4-only by construction.

**Relay gossip is Sybil-bounded, not Sybil-proof.** Discovery reuses classic's
greeting vocabulary (`host`/`port` claim, `peers` list) with three gates: a
relay accepts a claim only from the claimant itself (claimed host must equal
the connection's source IP), verifies by dialling back before ever sharing it
(`RelayPeers`), and a client adopts only after its own probe, capped to a
bounded minority of its pool with the trusted bootstrap set never evicted
(`RelayGossipClient`). An attacker who runs REAL reachable relays can still get
some adopted — but they start at the bottom of `HostPool` scoring, are capped,
and see only per-key routing metadata on E2E-encrypted, multi-homed traffic.
Claim intake is rate-limited per source IP (dial-backs cost the relay an
outbound connection). Accepted: this matches the classic network's own trust
posture for peers.

## Not yet addressed

- Per-connection bandwidth accounting (only message-rate and connection caps
  today).
- MLS directory persistence (entries are 24h-TTL and clients republish, so it
  self-heals within a refresh cycle after a restart — low value, not done).
