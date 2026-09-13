## Code Review

### Summary

Reviewed the new transfer engine, crypto/storage lifecycle, owner API, shared-port handoff and each changed frontend integration. The implementation reuses the existing sealed chat, authenticated control channel and listener, and uses an upstream torrent engine. Runtime and integration tests found defects that compilation alone did not catch. This is a candidate for field validation, not evidence of a completed public release.

### Findings resolved before candidate completion

- **CRITICAL — unbounded metadata allocations:** upstream bencoding accepted attacker-declared string sizes. `BoundedMetadata` now limits and validates the exact generated schema before parsing; the stream gateway rejects extension metadata entirely. Regression tests exercise malformed lengths, nesting, duplicate/unsupported fields and packets.
- **MAJOR — pause/resume left workers and connections behind:** upstream `stop()` completed its future while the processing worker still waited for pieces. The adapter uses existing lifecycle callbacks, interrupts its own worker, awaits stop/descriptor cleanup, closes peer connections and reconnects through a fresh gateway. Actual transfer/resume tests run on JVM, S23 and S10+.
- **MAJOR — incompatible Android dependency:** Guice 5 used reflection APIs missing on Android. The upstream 4.2.3 `no_aop` artifact is used; actual Android runtime tests verify it.
- **MAJOR — extension handshake advertised internal ports:** disabling standard extensions did not disable the core BEP-10 handshake. `ClosedSwarmModule` reuses the upstream connection factory with its bitfield handler only. The gateway enforces bounded BEP-3 packets and checks the exact swarm.
- **MAJOR — file cards were intercepted/disabled:** Cloud Android's generic handler disabled taps and treated audio files as voice notes; standalone desktop's media path captured all file offers. File cards now take their own path and Android text taps open the same controls as the bubble.
- **MAJOR — interrupted preparation became invisible:** incomplete storage is now listed for removal and counted toward quota. Verified cache files survive a paused restart. Failed transfers release their gateway before retry.
- **MINOR — upload and export cleanup:** invalid metadata is checked before staging, conflicting retries are rejected, failed/stale staging is swept, input streams close on validation failure, and incomplete exports are removed. The web download does not attempt to send a second response after headers.
- **MINOR — UI polish:** removed duplicate Close controls, retained light/dark foreground tokens, preserved filenames in save controls, and excluded private image files from photo galleries.

### Verdict

Approve as a local test candidate once the final artifacts match the reviewed source and their recorded checks pass. Field validation across independent internet connections is still required before claiming that path is verified. Fixed numeric limits and paired-RPC throughput are documented first-version limitations. WakeProxy decentralisation is explicitly not resolved by this change.

Final artifact gate passed: both Electron bundles contain the reviewed node SHA256, all three signed Mac installers were mounted read-only and verified, and the 11 file tests pass with each packaged Java runtime. Final notarization is pending explicit approval after automatic approval review rejected the Apple upload. No public release has occurred.
