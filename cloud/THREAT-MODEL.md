# Parlons Cloud — threat model

Parlons Cloud runs your Parlons account (identity + chat, and later a watch-only wallet view)
on an always-on node you control, driven remotely by your paired devices. The node is
internet-facing and holds a long-lived identity, so this document states what it defends, how,
and — honestly — what is built vs. still planned.

## Assets
1. **Comms identity** (RSA-1024 from the seed) — signs/receives all your Maxima messages. Must be
   live in RAM whenever the node runs. Compromise = impersonate you to contacts + read/route your mail.
2. **Chat history + contacts** — on the node's disk.
3. **Spendable wallet** — the Minima funds. **Kept COLD**: the spend key is a *separate* phrase held
   on your device, never on the node (see Wallet below). The node holds only a watch-only address.
4. **Device pairing set** — which device keys may drive the account.

## Trust model
- **You are the operator.** Self-hosted: one operator = one account. "Trust the host" = "trust
  yourself" — the sovereignty win. The design still minimizes what a *compromised* host leaks.
- **Devices are clients, not copies.** A device has its own key; it never holds the account identity.
  The relay enforces one holder per routing key, so the node is the sole holder — devices drive it.

## Controls (built ✓ / planned ◻)
- ✓ **Device pairing, not passwords.** Every owner command is an identity-signed Maxima RPC; auth =
  "is the caller's signature-verified key a paired device?" (`DevicePairing`, `ParlonsControl`).
  Bootstrap via a one-time code read over ssh (never logged); further devices via approval by an
  existing device. **Revoke a lost device without rotating the identity.** No public login surface.
- ✓ **No public web port (v1).** The control channel rides the encrypted, signature-verified Maxima
  transport. There is no HTTP login to attack. (A browser UI is a later phase, gated by the same
  pairing.)
- ✓ **Transport E2E.** Maxima messages are encrypted to the recipient and signed by the sender
  (RSA-OAEP + AES-GCM), Bleichenbacher-hardened decrypt on the relay path.
- ✓ **Funds cold.** The node never holds the spend key; a full node compromise cannot move money.
- ✓ **Hardened service.** systemd unit: `NoNewPrivileges`, empty `CapabilityBoundingSet`,
  `SystemCallFilter=@system-service ~@privileged`, `ProtectSystem=strict`, `ProtectHome`,
  `ProtectProc=invisible`, `PrivateDevices`, `IPAddressDeny` for cloud metadata, `UMask=0077`.
  Data dir 0700; seed / pair-code / devices.json 0600.
- ✓ **Least-secrets in logs.** The wallet-grade seed is never printed to a non-tty (systemd log);
  the bootstrap code is written to a 0600 file, never logged.
- ◻ **Seed encrypted at rest.** TODAY the comms seed is 0600 plaintext (as the relay always was).
  Planned: encrypt at rest and decrypt into RAM only at start, unlocked by a systemd *encrypted
  credential* (host-TPM-bound, so a stolen disk alone can't decrypt) or an operator-supplied
  passphrase. Tension acknowledged: an unattended auto-restart needs the unlock key reachable by the
  host, so this raises the bar against disk theft, not against a live-host compromise.
- ◻ **Chat history sealed at rest.** Planned: the node writes incoming messages encrypted to a
  login-derived public key; only a paired device's session decrypts on read — so a stolen disk leaks
  no readable history even though the node wrote it while you were offline.
- ✓ **Recoverable account.** The encrypted `BackupBundle` (phrase + contacts + MLS + key-uses)
  restores the account onto a fresh VPS.

## Residual risk (honest)
- A **live-host compromise while running** exposes the in-RAM comms identity (unavoidable for an
  always-on node) → an attacker could impersonate you to contacts until you notice and rotate. It
  can NOT spend your funds (cold) and, once history-sealing lands, can NOT read past history at rest.
- **Bootstrap-code theft** before first pair authorizes a device. Mitigate: pair promptly; the code
  is one-time and consumed; rotate via `parlons` (newcode) or by deleting `pair-code.txt`.
- **Relay operators** on the transport path see ciphertext + routing metadata (who talks to whom,
  when), same as any Maxima user — not message contents.

## Verification
- Pairing/auth/revoke proven end-to-end over the live fleet (a device is refused → pairs with the
  code → drives the account → is revoked → refused again).
- Adversarial review to run before v1 GA, mirroring the relay's 5-surface audit: control-method
  authz (every method gates on `DevicePairing` except `parlons.pair`), payload parsing, pairing
  race conditions, and the at-rest controls once built.
