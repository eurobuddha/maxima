# Private-file connection repair — 3 October 2026

## Result

Repaired a reproduced connection failure in `files/.../TorrentTunnel.java`. An account node sends two frames after the connector's greeting: its greeting and an MLS directory offer. The connector read only the greeting, sent its private-stream request, then treated the first byte of the remaining directory frame's length prefix (`0`) as a rejection. The tunnel closed before the BitTorrent handshake. The peer could remain at Connecting.

The existing direct-phone listener sends only the greeting. That explains why the previous 14 tests passed: they exercised the direct listener and bypassed the account-node listener. A new regression using the real `RelayServer` failed before the repair and passed after it. A 64 MiB plus 19-byte transfer through a reverse connection to the node listener completed and decrypted byte-for-byte correctly.

This reproduces a concrete product defect. It does not establish that it was the only cause of the user's reported transfer failure: the physical phone disconnected during log collection, and the inspected local/remote accounts had no retained transfer matching the report. No user account was copied or started for testing, and no test message was sent to a real contact.

## Reuse and scope

Worked in the current source checkout recorded by project memory: `/Users/eurobuddha/Projects/minima/maxima/build/account-import`, based on `93d288a`. The root checkout's unrelated changes were preserved. The saved graph predates private files; its account/listener relationships and the project memories led to the current source and release records.

Read the current transfer module, its Android/desktop/browser callers, crypto and metadata validators, account authorization, network listeners, and tests. Compared `PrivateTorrent.java` with the `parlons-file-auto-resume` worktree and inspected the existing upstream Bt 1.10 `PeerRegistry` source retained in the original feature's evidence directory.

Reused the existing torrent engine, tunnel, `Frame` and `MaximaCTRLMessage` codecs and transfer harness. Added a test-only dependency on the existing `:server` module to exercise its real listener. No new runtime dependency or wire format was introduced.

The connector now accepts either the immediate private-stream acceptance byte or one bounded MLS offer followed by that acceptance byte. Wrong frame types, wrong control types, oversized/truncated frames, repeated offers and missing/rejected acceptance fail closed. The capability and torrent infohash checks still run. Encryption, limits, discovery, Pause/Resume and storage are unchanged.

## Validation

- Before: the real-node listener regression failed after 20 seconds without completing the transfer. After: it completed in about 3 seconds.
- 64 MiB reverse node transfer: completed in about 77 seconds; authenticated decryption and exact byte comparison passed.
- Files: 20 tests, including the 14 existing tests, two real-network-listener regressions and four acceptance/validation cases.
- Core 260, server 73, cloud 90, node 20, Android 38 and Portal 28 tests passed, with no failures, errors or skips. Core results were reused by Gradle because core source was unchanged.
- Total: 529 tests passed, plus 63 byte-exact core wire vectors. The final file suite was rerun after adding automatic test-file cleanup.
- Both Android release builds and release-vital lint passed; desktop compilation passed.
- APK signature verification and embedded version checks passed. Both use the existing Minima Family signing certificate.
- Node and cloud jars contain the exact repaired `TorrentTunnel.class` from the tested build.

Evidence logs are in `build/file-transfer-repair-2026-10-03/`. Versioned artifacts and checksums are in the main repository's `dist/`.

## Builds

| Product | Version | Artifact |
|---|---|---|
| Parlons Android | 0.6.131 / 731 | `maxima-app-0.6.131-release.apk` |
| Cloud Portal | 0.2.81 / 281 | `parlons-cloud-portal-0.2.81-release.apk` |
| Node | 0.2.119 | `parlons-node-0.2.119.jar` |
| Cloud host | 0.11.113 | `parlons-cloud-0.11.113.jar` |

Desktop source metadata is reserved at 1.5.105 because 1.5.104 installers already exist. Desktop compilation was checked; no desktop installer was produced. These are local builds: no device installation, fleet rollout or store publication was performed. Existing node listeners support the corrected connector without a server-side protocol change; the participant opening the torrent connection must run the repaired code.

## Code Review

### Summary

The production change is confined to consuming the existing optional node control frame before switching to the accepted byte stream. Both listener shapes are covered and the original stream, capability and content-integrity checks remain in place.

### Findings

No blocking findings in the repair. The new large-file tests use JUnit temporary directories so test payloads are removed automatically. Real-device and independent-internet confirmation remain outstanding.

### Verdict

Approve the source repair and local builds. Do not describe them as installed or publicly released.
