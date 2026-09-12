# Android mobile-data call repair — 2026-09-11

The owner identified the original Parlons Android APK as the affected client. Ringing can work while media setup fails. This release repairs two defects proven with failing tests; a real mobile-data call to another user remains required before the goal can be closed.

## Changes and evidence

- Reused `server/MiniStun.java` in the lifecycle of `RelayRuntime`, instead of starting it only from the standalone relay entry point. Node, Cloud and desktop relay hosts now start the same UDP responder. `MAXIMA_STUN=false` remains an operator opt-out; a UDP port conflict does not kill the TCP relay. Stop releases the owned socket.
- External UDP probes before the repair: Sally, Hetzner, MegaMMR and Vigilance had no response and no local UDP9501 listener; standalone Maxlite and Openproject responded.
- Repaired `app/.../call/CallManager.java` and `portal/.../PortalCallManager.java` to apply queued ICE and create an incoming answer only after remote SDP succeeds. Call identity guards reject callbacks for retired calls. Existing Openproject joins the STUN list; diagnostics record candidate type/transport without addresses or SDP credentials.
- Reused `MiniStunTest.bindingRequest`, the existing shared-relay fixtures, and the Mockito/executor-barrier approach from `CloudSessionTest`. Tests exercise both actual Android call managers with only their Android/WebRTC boundaries mocked. Two regression tests failed the old call code; the runtime test failed the old shared-relay code.

## Validation

418 distinct module tests passed: core253, server73, full Android5, Android Cloud13, node10, Cloud host64. Release builds passed for both APKs and the node, relay and Cloud jars. The original signed APK0.6.120/code720 was installed on the Fold; signer matches the prior release. Desktop packaging validation is recorded separately with the release artifacts.

## Versions

Full Android0.6.120 (720), Android Cloud0.2.69 (269), node0.2.106, standalone relay0.4.100, Cloud host0.11.102, Parlons Desktop1.5.99. Node0.2.105 was reserved by paused work and is skipped here. minimaCore Desktop0.16.80 and minimaDesk0.7.63 pin node0.2.106.

## Review

No blocking finding in these changes. Candidate ordering, incoming answer ordering, retired callbacks, UDP lifecycle, port conflict and opt-out have regression coverage. The exact clean release tree excludes the paused WakeProviders/WakeService implementation and its dependency.

## Decentralisation and remaining validation

STUN discovers public addresses; it does not relay or decrypt call media. This repair distributes the existing responder through standard relay-hosting variants and adds redundancy, with operator opt-out. It does not establish reliable traversal of every carrier NAT: there is still no TURN/media-relay fallback. A failed real mobile-data trace must determine any further work. Default fleet entries remain operator-concentrated; redundancy alone does not establish operator independence.

The separately paused wake-proxy centralisation issue remains unresolved. This release does not deploy its unfinished fleet work and does not claim decentralisation is fully satisfied.

Existing call teardown/callback issues seen during review (empty bye ID after teardown, unguarded outgoing offer callback, no ICE restart) are recorded for follow-up and are not claimed fixed here.

Local evidence: `../_artifacts/parlons-mobile-calls-2026-09-11/`, including baseline failures, test/build logs, artifact hashes, deployment before/after records and the live Fold capture. A real cross-network Wi-Fi voice call reached LIVE on the Fold and S10+ at23:04:54 BST; the owner reported success after changing Wi-Fi. Mobile-data and video calls remain unverified.

## Publication and deployment completed

Source, versioned artifacts and all11 Android/desktop PandaApps rows are committed and pushed. Catalogue commit: `bd983eb0ddf94b68f61168dd0a3e26693e8110c0`. Both Electron update feeds list mac-arm64, win-x64 and linux-x64; all six installers are retained in their respective local `dist` folders and match the live feed hashes. All three standalone Parlons installers are in `maxima/dist`, with a combined checksum manifest.

Mac installers for all three apps passed Developer ID signing, notarisation, stapling and Gatekeeper verification. Both Electron CI matrices passed all three platforms. Standalone Parlons installer builds passed on all three platforms; the pre-existing Windows empty-checksum upload fault recurred and publication was completed with verified combined checksums, as in the preceding release. The workflow itself still reports failure. minimaDesk's catalogue push initially failed on a Windows download; the exact asset was subsequently downloaded, hash-verified, and the catalogue gate passed before pushing. No validation hook was bypassed.

Node0.2.106 is active on Sally, Hetzner, MegaMMR and Vigilance. All four passed11/11 external synthetic-client relay checks. Public identity and existing systemd configuration hashes were preserved. STUN passes from outside on Sally and Hetzner. MegaMMR and Vigilance listen on UDP9501 and have host firewall allow rules but remain externally unreachable from both the Mac and Sally; upstream firewall access was requested. Capture counters are inconclusive about the precise drop location. Maxlite and Openproject's existing standalone STUN responders already passed the baseline probes and were not changed. The Pi is not part of the Android STUN list.

The Fold was reconnected and confirmed on0.6.120/code720; live call capture resumed. A successful real mobile-data voice/video call is still unverified. IPFS publication and latest call-test status are tracked in the local evidence report. The goal remains open.

## Incoming-call follow-up — Android0.6.121 / Cloud0.2.70

The owner reported silent ringing and a small notification, including on the S10+ with Sound enabled. The S10+ log proves `RingtoneManager.getRingtone(content://settings/system/ringtone)` repeatedly failed with a Samsung `WRITE_SETTINGS` SecurityException. Its ringtone volume was15/15, unmuted, with DND off. The old call channel also explicitly had no sound, leaving no audible fallback. The Fold separately had full-screen call permission denied; it was enabled for the owner's test, and both devices subsequently reported normal Sound mode.

Reused both existing incoming notification helpers, managers, call activities, settings rows and actual-manager test fixtures. Android now owns looping ringtone playback via a high-importance ringtone channel; cancellation stops it on accept, decline, timeout or hangup. A new channel migrates the old code-silent default while retaining importance, vibration and exposed user sound choices. Android12+ uses native CallStyle with Answer/Decline; older supported Android versions retain the full-screen intent and gain expanded text/actions. Settings links expose ringtone and full-screen alert controls. No WRITE_SETTINGS permission, server, central service or new dependency is introduced.

Notification actions carry immutable, per-call PendingIntents. Both managers validate the expected call ID on their state executor before answering or declining. Stale call activities cannot act on replacement calls. Answer waits for microphone/camera permission; denial leaves an incoming call unanswered. The full Android app also declares vibration permission.

Validation: signed release builds and release lint checks passed;22 Android tests passed (full7, Cloud15). Regression tests exercise stale actions, matching decline, SDP ordering and old callbacks. Source review found and corrected a stale-intent activity path; no remaining blocking finding in this change. Physical audible ringing, lock-screen prominence and notification actions await the owner's test. Both Fold and S10+ were upgraded in place to0.6.121/code721 and launched; Android Cloud0.2.70/code270 carries the same fix. APK signatures verify. This follow-up changes only the two Android apps, so desktop/node binaries remain the published versions above.

The preceding IPFS snapshot was published to CID `bafybeidq3evmjz2bd2dda7gm5calv4wngnxqgzoag3ykc6i36fxjbeykq4` and the existing IPNS name. All11 changed catalogue rows were read back directly by CID and matched versions/hashes. The public gateway returned504 during verification; optional Filebase pin-list access failed. These limitations do not establish external gateway availability.

### Incoming-call publication and device status

Both Android releases are committed and pushed (`ad5ce438c598c95f025a0da7480f73dfbc3066b2`), uploaded to GitHub, and published in the live PandaApps catalogue (`760a83145b6237b77ab0a91e594ef39b9251c503`). The complete catalogue check passed50 entries /39 binaries before publication and again in the pre-push hook. Both catalogue rows were downloaded and matched to the signed APK hashes. The new IPFS snapshot is `bafybeia64uqlchs5hy3cu4skyuq6f27mqx3d4sua47fh4qlgmkxusbh35m`; both Android rows were read back by CID and verified. Optional Filebase remote pin-list access still fails.

The owner took the Fold to bed and requested S10+ → S23 testing. The S23 was upgraded in place to0.6.121/code721 and set from Vibrate to Sound (ring volume11/15, DND off). The S10+ remains locked at its PIN screen, so that requested call test could not be started. Unlock was requested. No test call was made and audible ringing / full-screen presentation / notification actions remain unverified on hardware. Both devices report the expected installed version and vibration/full-screen permissions; the S23 still needs microphone permission, which the new Answer flow requests. No further Fold interaction after the owner's instruction. The complete resumable state is `../_artifacts/parlons-mobile-calls-2026-09-11/INCOMING-STATUS.md`.

### Hardware verification completed — 2026-09-12

After the owner unlocked the S10+, the two test phones were paired through the normal contact introduction flow, using the complete public address read from the S23 itself. Both remained on published0.6.121/code721. No identity was cloned and no calls were made to any other contact.

- S10+ → S23 at09:55 BST: the S23 woke into the full-screen CallActivity over its showing keyguard. Screenshot and UI hierarchy show caller, Answer and Decline. Its system-owned ringtone player started unmuted on speaker device3. Answer cancelled/released that player and the call reached LIVE on both phones. The owner confirmed “success on that call”.
- S23 → S10+ at09:57 BST: the S10+ also displayed CallActivity over its showing keyguard. The system ringtone player started on speaker device3, without the previous app-process WRITE_SETTINGS failure. The unanswered call timed out at45 seconds, stopping/releasing playback. Separate human confirmation of S10+ audibility was not received; the evidence is the device audio trace and displayed screen.
- A brief repeat at10:01 BST exercised the S10+'s native notification Decline action. The visible action ended the exact call as declined and removed the incoming notification, leaving only the transport notification. Opening the notification shade had already stopped the insistent ringtone, which is Android's normal behavior; this test is not claimed to isolate Decline as the sound-stop trigger.

Local evidence: `RINGER-VERIFIED-2026-09-12.json`, both `*-incoming.png` screenshots, UI/window/audio snapshots, `decline-action-test.json` and call logs under the evidence directory.22 Android unit tests and both signed release builds had passed before publication. Android Cloud0.2.70 carries the same repair and passed its tests/build; a paired Cloud device was not part of this hardware run. No further APK changes were needed after the successful hardware checks. Mobile-data/video and the separately paused wake-proxy issue remain outside this ringing verification.

The S10+ screenshot also exposes low-contrast status-bar text over a light status-bar background. Record that as cosmetic follow-up; the full-screen caller display and controls are readable.
