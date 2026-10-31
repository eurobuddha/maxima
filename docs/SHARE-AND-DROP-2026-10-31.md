# Parlons sharing and desktop drops — 31 October 2026

## User flow

- Desktop: drop files onto the conversation or message composer. A single supported photo uses the existing preview and caption. Other files and batches show the destination and filenames before Send.
- Cloud/Node browser: drop files onto the open chat and confirm the recipient and filenames.
- Android: choose Share → Parlons! or Parlons Cloud in minimaDocs, Gallery, Files or another application. Unlock if needed, select/search a contact or group, review and Send.
- A single supported Android image uses the existing photo preparation and caption. Batches and larger images travel as original files. Up to 32 attachments, 100 MiB per original file (the existing UI says 100 MB).
- Existing encryption, file transports, download receipts and sharing controls are retained. No new fleet transfer route.

## Source reuse

- minimaDocs `android/.../MainActivity.java::sendElsewhere` and `Lending.java`: standard content-URI sharing, including encrypted-document provider pipes.
- minimaDocs `windows/.../DesktopDrops.java`: transfer-handler wrapping pattern that preserves text editing.
- Parlons `ImagePasteSupport`, `ChatsPanel`, `PrivateFileDialog`: existing image preparation, previews and file upload.
- Shared Android `PrivateFileUi`, app `ChatActivity` and portal `CloudChatActivity`: existing send paths.
- Browser `sendFile` and `privateFiles.send`: existing photo/private-file upload.

## New boundaries

`SharedFiles` accepts ACTION_SEND, ACTION_SEND_MULTIPLE and ClipData, deduplicates streams, checks URI grants, and rejects filesystem paths, URLs and the app's own providers. `ShareReceiverBase` handles unlocked recipient selection; client-specific ShareActivity implementations load contacts and groups. `ChatShareIntake` requires explicit confirmation and saves consumed state to prevent duplicate sends after recreation. Unknown-size provider streams are copied to bounded temporary private storage, then use the existing uploader.

`ChatDropSupport` preserves existing Swing clipboard handlers and installs across the conversation, including message bubbles. Desktop file batches prepare sequentially. Browser batches retain the original recipient across asynchronous sends, even if the active chat changes.

## Versions and artifacts

- Android 0.6.152 (752): `dist/maxima-app-0.6.152-release.apk`.
- Cloud Android 0.2.97 (297): `dist/parlons-cloud-portal-0.2.97-release.apk`.
- Desktop 1.5.121: JAR and locally Developer ID-signed app image built; Apple notarization and signed installer publication remain blocked pending explicit approval.
- Node 0.2.146: `dist/parlons-node-0.2.146.jar`.
- Cloud host 0.11.130: `dist/parlons-cloud-0.11.130.jar`.

Cloud host runtime VERSION now agrees with the artifact version; before this change source said 0.11.126 while Gradle said 0.11.128.

## Review fixes

- `PrivateFileUi` retains accepted uploads in a conversation-keyed Android ViewModel. Rotation/folding detaches dialogs, not the worker or staged source. Both Android clients restore that owner; progress and Resume/Cancel remain accessible from Transfers. The worker uses application context. This is configuration-change retention, not recovery after force-stop, process death or finishing the chat.
- `FileCommands` separates completed idempotency receipts from eight unfinished byte-import slots and permits a bounded queue of 32 preparation jobs. Completed receipts are retained for 30 minutes, with a 256-record ceiling. Native torrent capacity remains four; additional prepared offers show Queued and use the existing restart loop when capacity becomes available, normally within 12 seconds. Explicit Pause cancels queue intent. Existing 512 MiB staging budget, 2 GiB transfer storage budget and 32 stored-transfer limit still apply; 32 attachments does not promise capacity for 32 simultaneous 100 MiB files.
- Both Android photo paths use shared `PhotoInput` with a real 32 MiB stream cap, independent of provider metadata. Oversized shared previews fall back to the existing original-file path, which retains its 100 MiB cap and exact-length validation. The gallery error is localized in all four languages.
- Conversation changes detach the previous uploader before restoring the new conversation's owner; background completions cannot open the previous conversation's transfer sheet in the new chat. Transfer dialogs are gated on the resumed, unlocked screen.

## Verification

389 tests passed: Android share/photo 14, Cloud direct routing 1, Swing input/drop 9, core 291, file transport 42, browser panel/private files 32. New regressions cover retained real upload workers, unknown-size staged sources, a lying image provider and a 32-file backend batch. One existing socket-resume test timed out in a combined run, passed in isolation, and the complete 42-test transport suite then passed unchanged. Core was up-to-date; the full file transport suite ran, including its 100 MiB direct socket test. Tests cover URI grants, minimaDocs-shaped shares, ClipData fallback, private-provider rejection, exact size boundaries, cancellation, unlock gating, explicit Send, no repeat after recreation, clipboard regression and recipient stability across chat switches.

Release APKs built and passed apksigner verification. Merged release manifests contain exported share activities with SEND/SEND_MULTIPLE and */* MIME filters. Four-language resources validated. Node/Cloud fat JARs and desktop JAR built successfully. No production identity or wallet was launched.

Publication authorized by the owner; GitHub and catalogue publication is in progress. Not installed. Physical-device share-sheet and drag-gesture smoke tests, and user-to-user delivery on these builds, remain to be done. Automatic approval review rejected `desktop/release-mac.sh` because its Apple notarization submission using keychain credentials needs explicit user approval for this task. Do not bypass that rejection or distribute an unsigned Mac installer.
