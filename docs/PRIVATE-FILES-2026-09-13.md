# Private file sharing — candidate build

## Direct transport update — 25 October 2026

Current local candidates: Android **0.6.147 (747)**, Cloud Android **0.2.92 (292)**,
Parlons Node **0.2.139**, and legacy Cloud host **0.11.125**. These supersede the
historical limits and transport notes below. A local build does not imply publication.

New transfers are limited to **100 MiB** (displayed as 100 MB). Existing saved offers
retain their legacy read bound. The attachment, explicit Download, Pause/Resume and
Save workflow is unchanged.

* Updated standalone Android peers negotiate reliable ordered WebRTC DataChannels
  using the existing authenticated contact RPC. `WebRtcFiles` reuses `GatheredCallSdp`
  and the existing four fleet STUN endpoints for small address-discovery probes only.
  It creates no microphone/camera tracks and configures no TURN servers. It rejects
  relay candidates and checks the selected ICE candidate pair before admitting bytes.
* The channel feeds `TorrentTunnel`'s loopback capability/infohash gateway. The existing
  encrypted pieces, packet validation, upload pacing, rechecking and resume stay in use.
  A 16 × 16 KiB acknowledgement window bounds buffering; native callback buffers are
  copied before return. Current transfer capabilities, contact/group checks and a
  connection UUID reject unauthorised or stale signalling. Up to eight RTC sessions
  are admitted. Sessions expire on stalled progress and release their wake locks.
  Peers also disconnect once both confirm that their files are complete.
* Reachable Cloud/Desktop accounts keep the existing direct TCP transfer path. Both
  Android peers need the update for WebRTC. Desktop/headless hosts do not gain a native
  WebRTC runtime in this change. Some NAT/firewall combinations cannot connect without
  relaying; those transfers wait for a working direct route rather than send through
  TURN or the fleet.
* Cloud Android now sends **all private-file commands and replies directly to its own
  account host**, including upload and save blocks. `parlons.files.direct` issues only
  a short, sealed owner-RPC ticket. The ticket is single-use, expires after 60 seconds
  unopened, and carries fresh independent AES-256-GCM keys for each direction. Bounded
  records authenticate their sequence. Revocation is checked before each command,
  and a watchdog closes revoked, expired or stalled sessions. Bulk RPC fallback is
  absent from `ParlonsRemote`, including its timeout overload. Older hosts must be
  updated; Cloud Android reports that requirement.
* Direct Cloud connections reuse the account's verified listening address and port;
  no additional public port is needed. A reachable own host or same-LAN route is
  required. Older clients can still use the historical RPC API until upgraded.
  Small photo/voice-note media paths are outside this private-file change.
* Android holds bounded partial CPU wake locks while RTC streams or device import/export
  are active. The existing production foreground services remain responsible for app
  lifecycle. Classic hidden jar-engine mode still has no private-file engine.

Validation evidence is under `build/direct-files-*`. The synthetic Fold test transfers
100 MiB through native ICE/DTLS and the production encrypted torrent engine, interrupts
it after verified pieces, resumes, then checks GCM, SHA-256 and every plaintext byte.
The final Fold run passed in 28 seconds after interruption at three verified pieces.
The test observed Android Dozing with active wake locks and an unfrozen foreground-service
process during transfer; extended deep-Doze and independent-internet tests are not established.
465 unique JVM/Android unit/integration tests and 56 browser tests passed. APK signatures
match the existing Minima Family signer, and both host jars report their filename version.
The direct account-channel integration test uploads and downloads 100 MiB on a real
socket; security tests cover tampering, replay, length bounds, revocation, admission,
current transfer capabilities and no fleet fallback. These synthetic tests do not
establish connectivity across two independent internet connections.

## Historical September candidate

This implements files shared inside existing Parlons conversations, with BitTorrent distributing encrypted pieces underneath. It is not a public torrent or magnet browser.

## Using it

Choose **Private file** from a chat's attachment menu. The recipient opens the file card and chooses **Download**. Transfers have progress, Pause/Resume, Save, and Remove controls. **File transfers** lists this account's retained transfers. Active downloads can upload encrypted pieces to the conversation's other participants. After a restart, previously active transfers automatically resume once chat storage is loaded; deliberately paused transfers remain paused. Previously verified files remain available to save. Files saved by the first private-file build lack a persisted pause state and need one initial Resume to opt into this behaviour.

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
| Original Android | 0.6.125 (725) |
| Cloud Android | 0.2.74 (274) |
| Parlons Node | 0.2.110 |
| Parlons Cloud host | 0.11.106 |
| Relay server | 0.4.101 |
| Standalone desktop | 1.5.102 |
| minimaCore Desktop | 0.16.84 |
| minimaDesk | 0.7.67 |
| iPhone Cloud client | 0.1.34 (32) |

Both Electron hosts reuse the same account panel and node jar, pinned by `package.json` `parlonsNode`. Classic-engine mode has no private-file engine. No WakeProxy code or credentials are changed.

## Validation and status

See the local evidence directory `/Users/eurobuddha/Projects/minima/_artifacts/parlons-private-files-2026-09-13` and its `STATUS.md` for actual completed build results. Do not infer publication from a version number or a local build.

Automated validation covers authenticated encryption/tampering/empty files, closed-swarm TCP transfer, the existing-port gateway, resume, malformed metadata and packets, upload retries, unauthorized offers/participants, restart persistence and interrupted preparation. Synthetic Android runtime tests run in the separate `com.eurobuddha.filescheck` app; they never load a real identity or chat. Build with `-PprivateFilesCheck` to include that test-only module.

The S23 Ultra and S10+ each passed six runtime checks. The existing core protocol parity suite passed 63 checks; the panel suite passed 17; Electron host suites passed 7 and 6. A real Electron UI harness verified explicit download, escaped filenames, progress, light/dark contrast, pause/resume and dismissal. Three Swift parser/media tests passed. A phone-to-friend transfer across independent internet connections remains a release validation item. No claim of end-to-end internet validation is made by the loopback/runtime tests.

Wake-proxy centralisation remains unresolved and paused as requested. This feature adds no new mandatory central service, but does not establish that the whole Parlons system is fully decentralised.

## Local auto-resume follow-up

The auto-resume change is isolated in `feature/private-files-auto-resume`, worktree `parlons-file-auto-resume`. The version bumps above are source candidates only. Phone installation, new installers, fleet deployment and bundling node 0.2.110 into minimaCore/minimaDesk have not been performed in this side conversation. Cloud Android and iPhone use the account node’s shared transfer service, so this behaviour requires updating the paired node; their client source is unchanged.

Publication of this follow-up was explicitly requested on 13 September 2026. Current release evidence is in `_artifacts/parlons-auto-resume-release-2026-09-13` at the family root. Cloud Android also receives a version bump because its package contains the shared host library; iPhone client source is unchanged by auto-resume.
