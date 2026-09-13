# Private-file auto-resume — release record

Requested behaviour: transfers that were active resume sharing/downloading after the account engine restarts. Explicitly paused transfers remain paused. Merely receiving a chat offer still does not start a download.

The implementation reuses `PrivateFiles` offer persistence, `start`, `pause`, the existing scheduled discovery worker, `PrivateTorrent` piece rechecking and current authorization. Persist the paused flag in offer.json on start/resume, explicit pause and failure. Shutdown only stops runtime activity; it does not overwrite the saved preference. Restore after chat storage finishes loading, with new capability tokens. Standalone Desktop initializes the service at startup instead of waiting for the file dialog.

Legacy migration: older records have no pause preference. They remain paused until the owner resumes once, because their previous deliberate pauses cannot be distinguished from active transfers. Subsequent restarts are automatic. Existing limits, chat membership authorization and private peer discovery are unchanged.

## Release scope

Implemented from the private-file feature at `9678a872ae7d8c7ecf740cfa1603a579f2c80d42` in an isolated worktree. Existing dirty source checkouts were preserved. Application source was committed and pushed to the existing upstream main/master branches.

Released versions: Android 0.6.125 / 725, Cloud Android 0.2.74 / 274, node 0.2.110, cloud host 0.11.106, relay 0.4.101, standalone desktop 1.5.102, minimaCore 0.16.84 and minimaDesk 0.7.67. Both Electron hosts bundle the same reviewed node 0.2.110 jar. The Android Cloud and iPhone clients use the node-side transfer service and need an updated paired node. This publication does not deploy running fleet services, install phones or create a new iOS build.

The owner explicitly approved uploading all three Mac application bundles and DMGs to Apple, then publishing them. All three passed notarization, stapling, strict signature checks and Gatekeeper validation. Official tagged CI builds passed on Mac, Windows and Linux for all three desktop applications. Final store, feed, IPFS and local artifact verification is recorded in the release evidence directory below.

## Validation

`./gradlew :files:test :desktop:compileJava -PskipAndroid=true --offline` succeeded. 14 tests passed: eight account/file API tests and six torrent/crypto tests. New coverage includes completed-file automatic restoration, explicit Pause surviving restart, Resume persisting across a further restart, partial accepted download restoration, legacy records staying paused and explicit Pause cancelling scheduled restoration. All identities and files were synthetic; no live account or external peer was used. Log: `auto-resume-validation.log`; XML results: `files/build/test-results/test/`.

## Code Review

### Summary

The narrow change reuses the existing lifecycle and authorization, defers restoration until asynchronous chat loading completes, and persists explicit user intent independently of shutdown. The background scheduler preserves startup responsiveness. The shared four-transfer cap is retained.

### Findings

- Resolved: standalone desktop previously initialized this service only on opening a file dialog, preventing automatic restoration at application startup.
- Resolved: restored verified files retain the Ready · sharing status and usable verified data.
- Intentional migration limit: old records cannot reveal whether the user had paused them. They require one initial Resume.

### Verdict

Source review passed. Release validation also passed: 257 core, 14 file-transfer, 12 Android app and 18 Cloud Android tests; Android release-vital lint and signature checks; minimaCore 7 Parlons / 2 RPC checks and minimaDesk 6 Parlons / 3 RPC checks. Each Mac application passed the 14 file tests using its packaged Java runtime. Final DMG contents were mounted read-only and verified, including both Electron node jar hashes. Device restart and independent-internet transfer success are not claimed from these automated checks.

## Publication request

The owner subsequently requested commit, push and publication. Release evidence is at `/Users/eurobuddha/Projects/minima/_artifacts/parlons-auto-resume-release-2026-09-13`. Electron release versions are minimaCore 0.16.84 and minimaDesk 0.7.67, each pinned to node 0.2.110. Source versions above supersede the initial planning note; inspect release evidence for actual publication completion.

## Remaining limits

WakeProxy centralisation is a separate paused issue and remains unresolved. No claim of full decentralisation is made. Legacy file records require one initial Resume; deliberately paused transfers and unaccepted offers remain inactive. Cloud users must update the paired node for this server-side behaviour.
