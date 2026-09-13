# Private file sharing — candidate build

This implements files shared inside existing Parlons conversations, with BitTorrent distributing encrypted pieces underneath. It is not a public torrent or magnet browser.

## Using it

Choose **Private file** from a chat's attachment menu. The recipient opens the file card and chooses **Download**. Transfers have progress, Pause/Resume, Save, and Remove controls. **File transfers** lists this account's retained transfers. Active downloads can upload encrypted pieces to the conversation's other participants. Restarting leaves transfers paused; previously verified files remain available to save.

At least one participating account must be reachable through its existing Parlons listening port, or reachable on the same LAN. An outbound-only participant can connect to a reachable participant; reverse introductions let an outbound-only sender serve a reachable recipient. If nobody is reachable, the transfer waits. There is no automatic central-server fallback. Cloud phone clients use their existing paired account, with no new account or credentials.

Current bounds: 512 MiB per file; 2 GiB for retained encrypted and verified transfer data; 32 retained and 4 active transfers; 1 MiB/s upload budget per active transfer; up to 512 MiB of separate, temporary paired-device upload staging. Pause and Remove provide bandwidth/storage control; numeric limits are fixed in this first candidate. Paired-device uploads and saves stream 48 KiB blocks. Very large cloud-phone imports can therefore be limited by RPC latency.

Both participants need a version supporting private files; Cloud clients also need an updated account node. This candidate adds no optional third-party seeding or public discovery.

## Reuse and protocol

- `core/.../chat/ChatMedia.java` and `ChatEngine` carry the `pf1:` offer inside the existing authenticated, encrypted chat. The offer includes a random file id, display metadata, AES key, nonce prefix, plaintext digest and private torrent metadata.
- `files/.../FileCrypto.java` applies the existing AES-256-GCM media primitive to independently authenticated records: 1 MiB ciphertext pieces, 16-byte tags, 8-byte random nonce prefix plus a 32-bit piece index. Associated data binds transfer id, plaintext length and piece index. A fresh key and nonce prefix are generated per file. SHA-256 verifies the completed plaintext before publishing the local verified file.
- Bt 1.10 supplies hashing, piece selection, TCP exchange, rechecking and seeding. Source reviewed at upstream tag `bt-parent-1.10`, commit `a89da712c8829f4a7ab8930e33b3f1135dd84975`. There is no fork. `ClosedSwarmModule` reuses its connection factory with the bitfield handler only. Its unconditional extended handshake and optional MSE negotiation are disabled; the content is already encrypted independently.
- `DirectEndpoint` and `RelayServer` accept an unregistered, dedicated frame-203 connection with a 256-bit transfer capability. `TorrentTunnel` verifies the exact infohash and forwards only bounded BEP-3 packets to the loopback-only engine. No arbitrary proxy destinations, new public port, tracker, DHT, PEX, LSD, magnet metadata, web seeds or module auto-loading.
- Peer introductions use `parlons.files.peers`, restricted to known contacts in the transfer's conversation and, for groups, current members. Offers, keys and introductions have no public index. A capability can be copied by a participant; previously delivered keys or files cannot be revoked remotely. Direct peer IPs, traffic volume and opaque swarm identifiers are not anonymous.
- `parlons.files` is the owner-device API. The account authenticates it through `ParlonsControl`; the local web panel retains session/origin/host checks. Uploads use offset and byte checks so retries cannot silently duplicate or overwrite data. Downloads must refer to an offer already in that conversation.
- Private state is kept under the account's `private-files` directory with owner-only permissions. Remove affects this transfer's directory, not separately saved copies. Interrupted preparation is exposed for cleanup. Existing small photo/voice media limits and paths are preserved.

## Variants and candidate versions

| Variant | Version |
|---|---|
| Original Android | 0.6.124 (724) |
| Cloud Android | 0.2.73 (273) |
| Parlons Node | 0.2.109 |
| Parlons Cloud host | 0.11.105 |
| Relay server | 0.4.101 |
| Standalone desktop | 1.5.101 |
| minimaCore Desktop | 0.16.83 |
| minimaDesk | 0.7.66 |
| iPhone Cloud client | 0.1.34 (32) |

Both Electron hosts reuse the same account panel and node jar, pinned by `package.json` `parlonsNode`. Classic-engine mode has no private-file engine. No WakeProxy code or credentials are changed.

## Validation and status

See the local evidence directory `/Users/eurobuddha/Projects/minima/_artifacts/parlons-private-files-2026-09-13` and its `STATUS.md` for actual completed build results. Do not infer publication from a version number or a local build.

Automated validation covers authenticated encryption/tampering/empty files, closed-swarm TCP transfer, the existing-port gateway, resume, malformed metadata and packets, upload retries, unauthorized offers/participants, restart persistence and interrupted preparation. Synthetic Android runtime tests run in the separate `com.eurobuddha.filescheck` app; they never load a real identity or chat. Build with `-PprivateFilesCheck` to include that test-only module.

The S23 Ultra and S10+ each passed six runtime checks. The existing core protocol parity suite passed 63 checks; the panel suite passed 17; Electron host suites passed 7 and 6. A real Electron UI harness verified explicit download, escaped filenames, progress, light/dark contrast, pause/resume and dismissal. Three Swift parser/media tests passed. A phone-to-friend transfer across independent internet connections remains a release validation item. No claim of end-to-end internet validation is made by the loopback/runtime tests.

Wake-proxy centralisation remains unresolved and paused as requested. This feature adds no new mandatory central service, but does not establish that the whole Parlons system is fully decentralised.
