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

Local evidence: `../_artifacts/parlons-mobile-calls-2026-09-11/`, including baseline failures, test/build logs, artifact hashes, deployment before/after records and the live Fold capture. No real call has yet been verified.

## Publication and deployment completed

Source, versioned artifacts and all11 Android/desktop PandaApps rows are committed and pushed. Catalogue commit: `bd983eb0ddf94b68f61168dd0a3e26693e8110c0`. Both Electron update feeds list mac-arm64, win-x64 and linux-x64; all six installers are retained in their respective local `dist` folders and match the live feed hashes. All three standalone Parlons installers are in `maxima/dist`, with a combined checksum manifest.

Mac installers for all three apps passed Developer ID signing, notarisation, stapling and Gatekeeper verification. Both Electron CI matrices passed all three platforms. Standalone Parlons installer builds passed on all three platforms; the pre-existing Windows empty-checksum upload fault recurred and publication was completed with verified combined checksums, as in the preceding release. The workflow itself still reports failure. minimaDesk's catalogue push initially failed on a Windows download; the exact asset was subsequently downloaded, hash-verified, and the catalogue gate passed before pushing. No validation hook was bypassed.

Node0.2.106 is active on Sally, Hetzner, MegaMMR and Vigilance. All four passed11/11 external synthetic-client relay checks. Public identity and existing systemd configuration hashes were preserved. STUN passes from outside on Sally and Hetzner. MegaMMR and Vigilance listen on UDP9501 and have host firewall allow rules but remain externally unreachable from both the Mac and Sally; upstream firewall access was requested. Capture counters are inconclusive about the precise drop location. Maxlite and Openproject's existing standalone STUN responders already passed the baseline probes and were not changed. The Pi is not part of the Android STUN list.

The Fold was reconnected and confirmed on0.6.120/code720; live call capture resumed. A successful real mobile-data voice/video call is still unverified. IPFS publication and latest call-test status are tracked in the local evidence report. The goal remains open.
