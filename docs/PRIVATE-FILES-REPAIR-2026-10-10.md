# Private-file recovery fixes — 10 October 2026

## Repairs

The review reproduced three failures. The fixes reuse the existing private-file engine, staged-upload commands, browser panel and test harness:

- Bt can end its processing future normally after an internal storage error. `PrivateTorrent` now handles unexpected normal completion as well as exceptional completion, cleans up the stopped client and reports an interruption so Resume can start another client. Explicit pause remains distinct. Completion callbacks run outside the engine monitor.
- A lost browser upload response previously cancelled and deleted accepted bytes. The panel retries the same upload ID and offset, validates the returned offset and retains the selected file for Resume after retries are exhausted. Hidden failed uploads can be reopened from File transfers. Explicit cancellation removes staging.
- Failed preparation previously deleted the input and passed an upload ID to transfer Resume. `FileCommands` retains failed preparation, lists its error, accepts its upload ID for Resume and keeps successful finish calls idempotent. Removal and the existing staging expiry still clean up retained input.

The browser's selected file is retained in the current page. Existing upload limits, expiry and restart cleanup still apply. These repairs address the reproduced bugs; physical-device and independent-internet confirmation of the original incident was not performed.

## Published versions

Android was committed, pushed, published to GitHub and the shared stores, and downloaded successfully from IPFS before the remaining release work started.

| Product | Version | Delivery |
|---|---|---|
| Parlons Android | 0.6.135 / 735 | GitHub, shared stores, IPFS |
| Cloud Portal Android | 0.2.84 / 284 | GitHub, shared stores, IPFS |
| Parlons Desktop | 1.5.110 | Mac, Windows, Linux |
| Parlons Node | 0.2.123 | Versioned jar and checksum on GitHub |
| Parlons Cloud host | 0.11.116 | Versioned jar and checksum on GitHub |
| minimaCore Desktop | 0.17.34 | Mac, Windows, Linux; in-app update feed |
| minimaDesk / MinimaClassic Desktop | 0.7.75 | Mac, Windows, Linux; in-app update feed |

Cloud Portal and browser clients use the paired Node/Cloud host for transfers; that host needs the new version. Both desktop wrappers pin Node 0.2.123 and SHA-256 `f41ed62164924588628d9687335e8e4b3b582525fd6d7d7f2112bd2c1e443966`.

Reused `desktop/release-mac.sh`, `.github/workflows/desktop-node.yml`, both wrappers' `scripts/release-desktop.sh`, and the shared catalogue's `scripts/publish-app.py` and `check.py`. minimaDesk now reuses minimaCore's checksum-pinned fetcher and Mac signature gates. Its CI keeps unsigned Mac artifacts out of public releases, and its catalogue commit stages only `apks.json`.

## Verification

- 26 private-file tests passed, including a 64 MiB plus 19-byte reverse transfer through the real node listener.
- 36 browser tests passed. Nine regression tests were added for the reviewed failures.
- Core checks, including 63 parity vectors, passed. Cloud, Node, Desktop and Portal tests passed; Portal ran 56 unit tests.
- minimaCore's 15 relevant wrapper tests and minimaDesk's 17 tests passed. minimaDesk TypeScript checking passed.
- GitHub shared engine/panel runs `38068537737`, `38069052563` and `38069165716` passed.
- All three platform jobs passed for native Desktop (`38069070167`), minimaCore (`38069946013`) and minimaDesk (`38070092114`).
- Both APKs have the expected package IDs, increasing version codes and the existing Minima Family certificate.
- All three Mac installers and apps passed Developer ID, hardened runtime, notarization, stapling and Gatekeeper checks. Read-only inspection of the DMGs confirmed their versions and fixed transfer code / pinned Node jar.
- Public native Windows/Linux downloads matched their release checksums. The Linux installer contains the exact tested transfer classes. Both wrappers' public installers and update-feed hashes were verified.
- Catalogue `c7f61bb` passed the full gate: 59 entries, 58 verified binaries. All 11 affected public entries match the intended versions and downloaded artifact hashes. Both wrapper feeds match those same catalogue rows.

IPFS snapshot `bafybeicfhkr6j3i4yvibs5mbjfzil25rdodtdcmqfzrpuj65nousgwrjpi` is published through the stable IPNS name and pinned locally. Public read-back matched all 11 affected rows; all 11 mirrored binary hashes match the verified catalogue. The native Android APK was also downloaded from the public mirror and byte-verified before work on the other releases began. The publisher still reports its existing optional Filebase pin-list warning; local pinning and public publication succeeded.

## Source and evidence

The current source is `/Users/eurobuddha/Projects/minima/maxima/build/account-import`, branch `feature/chat-actions-2026-10-04`, pushed to both the feature branch and main. Fix commit `a9578815` was merged with current main as `017e9023`, preserving the newer minimaDocs work. Host versions are `40f03115`; Portal is `f9b3fd4d`. Wrapper commits: minimaCore `f6ee9cf`, minimaDesk `ad2df3f`.

Evidence and downloaded artifacts: `build/transfer-review-2026-10-10/`, especially `fix-results.json` and `publication/`. Versioned APKs and host jars also reside in the main repository's `dist/` with checksums. GitHub's general latest release is Android `v0.6.135`.

This publishes release updates. It does not install them on phones or upgrade/restart running account hosts. Tests used synthetic data; no production account was copied or started.
