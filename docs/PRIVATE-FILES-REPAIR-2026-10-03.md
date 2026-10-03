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

The follow-up publication includes Desktop 1.5.105 and preserves the separately delivered 1.5.104 text-size and image-paste changes. Its 13 desktop tests passed. The repair commit is `6656939`; the combined release commit is `6c89738`, pushed to `main`. Existing node listeners support the corrected connector without a server-side protocol change; the participant opening the torrent connection must run the repaired code.

## Code Review

### Summary

The production change is confined to consuming the existing optional node control frame before switching to the accepted byte stream. Both listener shapes are covered and the original stream, capability and content-integrity checks remain in place.

### Findings

No blocking findings in the repair. The new large-file tests use JUnit temporary directories so test payloads are removed automatically. Real-device and independent-internet confirmation remain outstanding.

### Verdict

Approve the source repair and release builds. Publication evidence is recorded below; device installation and fleet rollout remain separate actions.

## Release coverage

The shared `:files` library is built into Android, standalone Desktop, Node and Cloud. Cloud Portal and browser panels use the paired account host for torrent transfers, so their Node or Cloud host must also be updated. Both desktop wrappers bundle Node and require new installers:

- Parlons Android 0.6.131; Cloud Portal 0.2.81; Desktop 1.5.105 (Mac, Windows, Linux).
- Parlons Node 0.2.119 and Cloud host 0.11.113.
- MinimaClassic Desktop 0.7.71 (`minimaDesk` commit `3365ae3`), bundling Node 0.2.119.
- minimaCore Desktop 0.17.28 (commit `a6e4d3d`), with Node 0.2.119 and its SHA-256 pinned together.

Reused `desktop/release-mac.sh` and `.github/workflows/desktop-node.yml`; the wrappers' existing signed-build, node-fetch and platform-publish scripts; and the shared catalogue's `scripts/publish-app.py` / `check.py`. Only catalogue rows are staged, preserving pre-existing graph changes. The live Hetzner IPFS publisher matches the inspected `tools/dappstore/build_ipfs_store.sh` byte-for-byte.

GitHub JVM/panel regression run `37146915463` passed, including the new files tests. Standalone desktop matrix `37147426459` and MinimaClassic matrix `37147521958` passed all three platforms. Local wrapper checks passed: MinimaClassic 12 tests plus TypeScript checking, minimaCore 9 relevant RPC/Parlons tests. Public Node and Cloud JAR downloads match the tested local hashes.

minimaCore matrix `37147623258` passed on all three platforms, including its existing wallet, update, network-fetch, PandaPools, AtomiX and Casino gates. All three local Mac installers passed their projects' signature, hardened-runtime, stapled-ticket and Gatekeeper verification. Both wrapper Mac app bundles contain Node JAR SHA-256 `a9b9501c83878836f7cdcec9a299a686326fdb6013bab90d51e2dedaadac6105`; minimaCore CI independently checked the same committed digest on every platform.

### Store publication

Catalogue commit `c78a625` is pushed to `minima-core-apks/main`. Exactly eleven affected rows changed: both Android APKs and three platforms each for Parlons Desktop, MinimaClassic Desktop and minimaCore Desktop. Unrelated catalogue rows and pre-existing graph changes were preserved. The full gate and pre-push gate passed **50 entries / 39 binaries**. GitHub API and public raw read-back matched all eleven rows, including versions, version codes, URLs and hashes. GitHub's latest-release endpoint returns `v0.6.131`.

Both desktop in-app feeds were read back with all three current platform files and the same hashes as the catalogue. The catalogue update was pushed after the user correctly reported that 0.6.129 was still offered; the APK release had been live while catalogue verification was still running.

The IPFS mirror is published and locally pinned at `bafybeielvrjthma23cfl23r27vmpdrlmlft64e5uf7kv7dzeadsiqjhyhu`. Public gateway read-back matched all eleven versions, codes and hashes. Mirrored Android APK bytes match the signed GitHub releases. The publisher's existing optional Filebase pin-list warning remains; local pinning and public IPNS publication succeeded.

The iOS private-files implementation was also checked in `_worktrees/parlons-ios-private-files/ParlonsKit/Sources/ParlonsKit/Account/PrivateFiles.swift` and `App/Sources/Chats/PrivateFileView.swift`: it uses `parlons.files` RPC on the paired host, with no local torrent connector. Its repair therefore comes from upgrading that Node/Cloud host, as for browser clients. No additional iOS binary change is needed for this connector defect.

This work publishes updates; it does not install them on user devices or restart/upgrade running account hosts.
