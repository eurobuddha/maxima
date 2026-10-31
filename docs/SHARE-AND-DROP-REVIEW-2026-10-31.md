# Share and drop code review — 31 October 2026

## Follow-up: fixes implemented and verified

The three findings below are resolved in Android 0.6.152, Cloud Android 0.2.97, desktop 1.5.121, Node 0.2.146 and Cloud host 0.11.130. The original review is retained below as the audit record.

- Conversation-keyed ViewModels retain the real uploader, upload IDs, retry hashes and temporary sources across configuration changes. Destroyed Activities and dialogs detach. Both Android clients restore the same upload owner; conversation changes detach before rebinding. Two Robolectric tests block a real upload during recreation, then prove one begin/finish with no interruption or duplicate send, including temporary-file survival and cleanup.
- Completed upload receipts no longer count against eight unfinished import slots. Preparation queues up to 32 jobs; native transport still runs at most four. Capacity produces a persisted Queued state instead of failing preparation. Tests submit 32 files, retry all IDs, assert 28 queued, free a slot, verify automatic progress, and prove an explicitly paused queued item stays paused. Eight unfinished byte imports remain the limit. Existing disk budgets remain enforced.
- Both Android photo readers use shared PhotoInput, capped at 32 MiB of actual input before decode. Tests cover the exact boundary, an endless stream, and a provider claiming one byte while delivering excess data. The preview falls back to the bounded original-file path.

Follow-up verdict: approve these code fixes. 389 automated tests pass (core results reused unchanged); the full 42-test transport suite passed after one existing socket-resume timeout passed in isolation. No physical Fold/share-sheet or user-to-user test on these new builds has been claimed. Notarization is an external release approval issue, separate from this code review.

## Original review summary

Request changes before release. The changes reuse the existing encrypted transports and preserve recipient snapshots and normal clipboard behaviour. However, the new integration has three substantive gaps. The previous passing tests did not cover actual upload lifetime or multi-file backend capacity. Production code was not changed during this review.

## Findings

### MAJOR — Accepted Android shares can be stranded by screen recreation

Primary location: `chatshared/com/eurobuddha/maxima/app/chat/ChatShareIntake.java:84`.

Send sets `consumed=true` before invoking `PrivateFileUi.upload`. Upload work and source information belong to the Activity's PrivateFileUi instance. Both chat activities close this object in onDestroy; close interrupts the executor and deletes newly staged source files. After a rotation/fold configuration change, the restored consumed flag prevents re-enqueueing the share, while the new uploader has no pending upload object. Incomplete backend uploads are not listed by FileCommands.list (it includes only complete input uploads awaiting preparation). This leaves an unfinished share with no normal resume path.

Evidence: an isolated Robolectric reproduction using the existing ChatShareIntakeTest activity/provider plus the real PrivateFileUi and a blocked fake append call. The test observed an interrupted append, zero finish calls, consumed=true, and no new begin call after recreation.

Fix: retain or persist the upload job independently of the screen, including its stable upload ID and source access, and restore its progress/actions after recreation. Mark the share consumed only once a durable job owns it; do not solve this by blindly re-sending with a new ID.

### MAJOR — Advertised 32-file batches exceed backend admission limits

Primary location: `desktop/src/main/java/com/eurobuddha/maxima/desktop/ui/PrivateFileDialog.java:25`; equivalent batch loops exist in ChatShareIntake and browser wireChatDrop.

Sequential calls to finish do not free backend upload slots: FileCommands.prepare returns Preparing while work runs asynchronously. Its uploads map retains successful entries, and begin refuses when uploads.size() reaches 8. Sweep removes them only after 30 minutes. Separately, the trackerless transfer engine permits only 4 active transfers. Thus a normal multi-file share can leave later items failed, and the ninth preparation is rejected even if the first eight have finished and their active transfers have been paused. The earlier claim that sequential imports resolve the capacity limit was incorrect.

Evidence: reused PrivateFilesTest.Fixture with fresh synthetic identities. Eight one-byte uploads were individually finished, awaited until a fileId appeared, and paused. The ninth begin still threw `Finish or cancel another upload first`.

Fix: introduce a capacity-aware pending queue integrated with actual transfer/preparation state, and separate completed idempotency records from active upload admission. Preserve retry deduplication and existing transfer limits. Alternatively, explicitly restrict the feature to supported capacity until queuing exists; do not advertise 32 immediately runnable transfers.

### MAJOR — External image providers can bypass the preview size guard

Primary location: `chatshared/com/eurobuddha/maxima/app/chat/ChatShareIntake.java:57`.

Image routing trusts OpenableColumns.SIZE supplied by the external content provider. A provider can report a small image but stream arbitrarily more bytes. The supplied preview callbacks call ChatActivity/CloudChatActivity.readScaledJpeg, whose readAll loops read the entire source into a ByteArrayOutputStream without a limit. This happens before Send, after choosing the recipient. A misreporting or malicious provider can exhaust the app heap; catch(Exception) does not catch OutOfMemoryError. The streaming cap tested for unknown-size original files does not protect this image path.

Evidence: source trace through both Android preview callbacks and unbounded readAll implementations. An intentional out-of-memory crash was not run.

Fix: enforce the actual byte limit while reading the preview source, before allocation/decoding; preferably reuse a bounded source snapshot for preview and send. Add a provider test that declares a small size but supplies excess bytes, and verify bounded rejection without a crash.

## Review verification

- Read the new Android boundary, recipient picker, preview lifecycle, native drop handler, changed send paths, manifests, version changes and their tests.
- Checked localization catalog and generated-resource consistency; `node tools/localization/generate.cjs --check` passed.
- `git diff --check` passed.
- Isolated reproductions live under ignored `build/share-review/`, with an opt-in Gradle init script; no production source or test source-set configuration was changed.
- `ShareBatchReviewTest.completedSequentialUploadsStillExhaustSlots`: reproduced the capacity failure.
- `ShareLifecycleReviewTest.recreationStopsAcceptedUploadWithoutRestartingIt`: reproduced the lifecycle failure.
- These diagnostic tests assert the observed faulty behaviour, not release acceptance criteria.
- No live identity, wallet or fleet was used. No publish/install/notarization action was attempted in this review.

## Verdict

Request changes. Hold Android 0.6.151, Cloud Android 0.2.96, desktop 1.5.120, Node 0.2.145 and Cloud host 0.11.129 from publication until the relevant findings are addressed and tested.
