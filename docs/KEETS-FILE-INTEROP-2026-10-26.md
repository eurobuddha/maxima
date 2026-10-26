# KeetChat ↔ Parlons private files

Implementation/build candidate: Parlons Android 0.6.148, Cloud Android 0.2.93,
Node 0.2.140, Cloud host 0.11.126, Desktop 1.5.117; bear-family Android 2.0.57.
These versions are local candidates until the release process publishes them.

## Using it

Both endpoints need the updated clients and a running Keets-enabled node.
Use the Keets contact/address already supported by Parlons' Keets bridge.
The separate MAX# conversation inside Pandamonium is not the Keets bridge.

- In Pandamonium/PandaBear/BlackBear's KeetChat, choose **Attach → File (up to
  100 MB)**. **Attach → File transfers** reopens stored outgoing preparations
  and accepted downloads.
- In Parlons, use the existing private-file picker and transfer sheet.
- The receiver taps the file, explicitly starts **Download / resume**, and can
  save the file only after authentication and the final digest check succeed.
- Keep both participating nodes online. The receiver's cached ciphertext can
  resume after interruption; closing KeetChat's activity pauses its active work.
- On Android, the node's Apps tab must enable Parlons and grant its existing
  ADMIN file permission. This is the same capability handoff used by Salon;
  it reads the local per-boot Keets API token. No permission is granted silently.
- Desktop uses its local linked Keets node. Cloud uses the account host's Keets
  bridge; a host without Keets cannot transfer these files. Cloud clients still
  use the existing encrypted direct file channel to their own host.
- Older KeetChat builds do not understand `state:file`: update both sides before
  testing. Existing text/photo/voice formats are unchanged. Android photo/voice uploads
  above the broadcast limit now use the same authenticated local media API
  (up to the existing 8 MiB receive limit); failed publication no longer sends
  a misleading caption-only replacement. Use File for larger originals.

## Reused implementation

`FileCrypto.java` moved, byte-for-byte, from `:files` into `:core` so the existing
bear-family vendored core can consume exactly the same AES-256-GCM records and
SHA-256 verification. `PrivateFiles`, its native/desktop/Cloud controls, and
Keets' existing Hyperdrive publish/fetch API are retained. KeetChat's native UI
adapts Parlons' document picker, progress, explicit-download and verified-save flow.
The bounded local HTTP reader adapts Salon's `KeetsLink.Session`, including
loopback-only access, per-boot token, no redirects, size limits and deadline.

`KeetsFiles` splits ciphertext into 112 KiB blocks. Each fits the existing Android
240,000-character publish envelope, so no large command or whole 100 MiB file
enters a broadcast or Java heap. The sidecar's 64 MiB request cap remains intact.
A hash-pinned Hyperdrive manifest lists the ciphertext hashes (under 64 KiB at
the maximum file size). Its URL occupies `ChatFile.torrent` for this alternative
transport; `isKeets()` dispatches it before any torrent parser. The sealed chat
`state:file` offer contains the `pf1:` reference, including the random AES key,
nonce, original name/type/length and plaintext digest. The public media drive
receives ciphertext and hashes only.

The private-file bridge never falls back to a photo caption. Displaying an offer
starts no download. The current chat/controller rules bind accepted offers to
the conversation. Every cached/downloaded block is hash-checked; AES-GCM checks
record identity/index/length, and the final SHA-256 must match before the temporary
plaintext is renamed to `verified.bin`. File bytes use Hyperdrive replication,
not the fleet messaging/mailbox path. The linked node remains the media host;
removing an app-local copy does not revoke ciphertext already replicated by peers.

## Validation

- `:core:test`: codec, routing guards, size limits, HTTP redirect/stream bounds,
  a full 100 MiB interrupted/resumed transfer, empty files and corruption rejection.
- `:files:test`: real account-controller / ChatEngine send and receive, no automatic
  downloads, no torrent engine for Keets files, saved-file recovery; existing torrent
  and direct-channel tests remain in the same suite.
- `./gradlew :core:parityClasses` then `node test-keets-file-interop.cjs` starts two
  independent real Hyperdrive stores on a private local DHT and runs Java's actual
  encryption/manifest/downloader in both directions. Each direction transfers
  100 MiB with an interruption and SHA-256-identical recovery. The fixture has no
  wallets, real contacts or public bootstrap, and rejects any messaging/mailbox use.
- Android native packaging passed the existing bear-family Keets verifier for all
  three release APKs. Four Robolectric UI regressions passed. The full JVM run
  passed 400 tests, followed by 24 targeted Keets/account regressions after the
  media-upload changes (including the two additional cases).

The real-peer test is local. It does not establish carrier-NAT connectivity or
replace a two-phone field test. Device installation and public release are separate
from this build task.
