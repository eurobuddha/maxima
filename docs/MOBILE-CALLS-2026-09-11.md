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
