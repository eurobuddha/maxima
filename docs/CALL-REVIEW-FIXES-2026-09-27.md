# Call review fixes — 2026-09-27

The five findings from the Parlons call review are fixed. The release combines these fixes with remote main at `6e001754428623b891bc582b05fe505c1f96f37c`, retaining private file transfers, chat links and gathered-ICE SDP. Physical call testing remains pending.

## Changes

1. Both Android call managers use `chatshared/.../call/CallIce.java` to validate candidate envelopes and bound pending ICE to 256 candidates. Non-decimal, negative, overflowing and out-of-range media indexes, empty candidates and oversized payloads are ignored.
2. Outgoing offer callbacks capture their original peer connection and call ID. A late callback after hangup or a replacement call cannot apply or send the old SDP. Remote video callbacks also check their call before attaching a track.
3. The existing Android transport services declare microphone and camera foreground-service types. The shared `CallForeground` helper promotes only the types needed for the current call, after permission checks. Call managers wait for successful promotion on the main thread before creating media. Refusal ends the call. Hangup retires media types using the call ID, so old cleanup cannot demote a replacement call. Idle service startup requests only its transport type.
4. Teardown detaches video sinks, disposes the peer connection and owned local audio/video tracks, and stops/disposes capture resources. Factory creation releases its temporary Java audio-device-module reference after handing ownership to WebRTC.
5. Cloud call pushes reuse `core/.../util/SerialLanes.java`, keyed by device, independently of ordinary push traffic. At most 256 call pushes can be pending; overload is logged and dropped. Android and browser clients retain up to 256 candidates across at most eight calls before an offer, keyed by sender and call ID, with a 90-second expiry. The account checks known-contact status before forwarding any call signal.

The existing signaling format, STUN endpoints and WebRTC dependency are retained.

## Reuse and scope

Reused the managers' guarded incoming-answer callbacks, the browser's validated bounded ICE queue, the existing foreground services and notification builders, and the tested `SerialLanes` utility. Shared Android additions live in the existing `chatshared` source set compiled by both apps. Tests extend the existing manager and authenticated account fixtures.

Searches across the repository and sibling `minima/apks`, `minima/desktop`, and `minima/support` projects found no existing microphone/camera foreground-service implementation to reuse. The media-type adapter is the minimum extension to the existing services.

Pre-existing wake-provider changes remain in the original checkout and are excluded from the release. Release work is isolated in `build/call-release`. No running fleet service is restarted by this publication.

The initial local APKs have been superseded by release builds from the merged source. Final artifact hashes and publication evidence are recorded below.

Version sources bumped to original Android **0.6.128 / 728**, Portal **0.2.77 / 277**, Cloud **0.11.109**, and Node **0.2.115**. These names avoid existing artifacts and locally known reserved versions.

## Evidence

Before fixes, new tests failed for malformed ICE (uncaught `NumberFormatException`), unbounded pending candidates, and missing native disposal. A cloud transport test held the offer send and demonstrated that ICE overtook it on the same device. The same regressions pass after the fixes.

Additional coverage exercises late outgoing callbacks after hangup/replacement, foreground promotion denial, hangup before promotion, candidate arrival before an offer, sender/call isolation, expiry, queue limits and executor shutdown. Android/WebRTC platform boundaries are mocked; cloud ordering uses the real account push path with an in-process transport stub.

Validation:

```sh
./gradlew :app:testDebugUnitTest :portal:testDebugUnitTest :cloud:test :node:test :core:test :server:test :files:test :app:lintVitalRelease :portal:lintVitalRelease --offline --console=plain
node --test account/src/test/js/*.test.cjs
git diff --check
```

| Suite | Passed |
|---|---:|
| Original Android | 32 |
| Portal Android | 25 |
| Cloud | 71 |
| Node | 10 |
| Core | 260 |
| Server | 73 |
| Private files | 14 |
| Browser | 25 |
| **Total** | **510** |

All results have zero failures/errors/skips. The merged release reran the Android, Cloud, Node, core, server, private-file and browser suites. Initial disk-space and socket-allocation failures were resolved before the final successful runs. Both Android release-critical lint tasks passed. Both merged release manifests include microphone/camera service permissions and types.

## Review and remaining device checks

Final source review found no further blocking issue in these changes. Foreground promotion follows [Android's service launch requirements](https://developer.android.com/develop/background-work/services/fgs/launch) and [media service types](https://developer.android.com/develop/background-work/services/fgs/service-types).

Physical validation remains necessary for microphone/camera operation when switching apps or locking the phone, repeated video-call teardown, and Wi-Fi/mobile-data connectivity. These checks have not been claimed by the unit tests. The existing absence of TURN remains a documented connectivity limitation.

## Release artifacts

Both APK signatures match the existing Minima Family certificate, SHA-256 `eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f`. Embedded APK versions and version codes match their Gradle sources. Node/Cloud jars contain the expected versions and the exact tested browser call engine.

| Artifact | SHA-256 |
|---|---|
| `parlons-0.6.128.apk` | `52f339ec2b3f1bf1ef9b8a9fa579a255a8400736d6c880a18d9ec0edc5eb01e9` |
| `parlons-cloud-portal-0.2.77-release.apk` | `821a35b2d84c3dad87dd2bcf612e5717f31cf33b72995f96ce9ec3a9a9c12716` |
| `parlons-cloud-0.11.109.jar` | `e00348abb1407f631e67cf819912397a10ebf9924ec5fdaf98d944f8b93a9a28` |
| `parlons-node-0.2.115.jar` | `a69e7a1a4587eb090693f0deda3b08dd077f96152b8c3acbc2da85ecd3b2d33f` |
