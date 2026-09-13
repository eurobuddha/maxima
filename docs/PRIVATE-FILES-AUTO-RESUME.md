# Private-file auto-resume — local side-conversation change

Requested behaviour: transfers that were active resume sharing/downloading after the account engine restarts. Explicitly paused transfers remain paused. Merely receiving a chat offer still does not start a download.

The implementation reuses `PrivateFiles` offer persistence, `start`, `pause`, the existing scheduled discovery worker, `PrivateTorrent` piece rechecking and current authorization. Persist the paused flag in offer.json on start/resume, explicit pause and failure. Shutdown only stops runtime activity; it does not overwrite the saved preference. Restore after chat storage finishes loading, with new capability tokens. Standalone Desktop initializes the service at startup instead of waiting for the file dialog.

Legacy migration: older records have no pause preference. They remain paused until the owner resumes once, because their previous deliberate pauses cannot be distinguished from active transfers. Subsequent restarts are automatic. Existing limits, chat membership authorization and private peer discovery are unchanged.

## Scope and status

Isolated worktree: `/Users/eurobuddha/Projects/minima/_worktrees/parlons-file-auto-resume`, branch `feature/private-files-auto-resume`, based on `9678a872ae7d8c7ecf740cfa1603a579f2c80d42`. This records the initial local implementation, before the subsequent explicit commit/push/publish request. No existing worktree source, installed application, store or fleet node was changed.

Source version candidates: Android 0.6.125 / 725, Cloud Android 0.2.74 / 274, node 0.2.110, cloud host 0.11.106, standalone desktop 1.5.102. Reconcile these with any intervening main-thread versions at integration. Cloud Android/iPhone use the node-side service and need an updated paired node. Electron releases must bundle that node with their own version bumps. Those packages and installations have not been produced here; no inherited release or deployment task was resumed.

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

Approve as a local source change. Focused tests and desktop compilation passed. Android-device restart validation and release/host propagation remain integration work; no installed-build success is claimed.

## Publication request

The owner subsequently requested commit, push and publication. Release evidence is at `/Users/eurobuddha/Projects/minima/_artifacts/parlons-auto-resume-release-2026-09-13`. Electron release versions are minimaCore 0.16.84 and minimaDesk 0.7.67, each pinned to node 0.2.110. Source versions above supersede the initial planning note; inspect release evidence for actual publication completion.
