# Parlons calls in minimaCore Desktop and minimaDesk

The shared account panel's voice/video buttons previously redirected users to a paired phone. They now place and answer calls on the computer itself. Incoming calls ring and fill the panel; both Electron hosts load the receiver before the Parlons tab is visited, select it and bring the window forward when a call arrives. On macOS, closing the window keeps its receiver alive while the existing app/node remains running; Quit still stops the app and node.

Versions: Parlons Node **0.2.107**, Cloud host **0.11.103**, minimaCore Desktop **0.16.81**, minimaDesk **0.7.64**. Source is committed and pushed. GitHub releases and both desktop update feeds are published for macOS arm64, Windows x64 and Linux x64. All six installers are present in the hosts' respective local `dist` folders; Mac installers are signed, notarised and stapled. All six PandaApps catalogue rows are published and read back with matching versions/checksums. Catalogue validation passed 50 entries and 39 binaries, including the pre-push gate. The IPFS snapshot is published under the existing IPNS name at CID `bafybeihz6wofx7johcaqh6v7pq5hp2wcbnmb7hc2wcflvp5cbrd5dz5o7q`; direct IPFS read-back matches all six versions/checksums. The public gateway responds but its catalogue still contains the previous desktop versions, so gateway propagation is not yet verified. Optional Filebase pin-list access still fails; local pinning/publication succeeded.

This is not a complete telephony closeout. The Z Fold-to-friend connection failure remains unresolved, and standalone Parlons Desktop **1.5.99** still has signalling-only call handling: its buttons show an explanation and it cannot carry audio/video. Updating the standalone app remains outstanding. The iPhone client also has no implemented calling engine.

## Reuse and implementation

- `portal/src/main/java/com/eurobuddha/maxima/app/portal/PortalCallManager.java`: reused signal schema, fleet STUN configuration, single-call states, 45-second ringing and 20-second connecting deadlines, asynchronous remote-SDP/ICE ordering and call-identity guards. Browser adaptation is `account/src/main/resources/panel/calls.js`; no added dependency.
- `account/src/main/resources/panel/app.js`, `panel.css`, `icons.js`: existing contacts, authenticated API, themes, buttons and avatar renderer. `calls-ui.js` supplies the full-window call screen, local ringtone, voice/video, mute and camera controls.
- `ParlonsLocal.java`, `ParlonsControl.java`, `ParlonsCore.java`: existing loopback ticket/cookie authentication, allow-list and SSE feed. An authenticated event connection now counts as an available call receiver. Each browser has a separate call identity bound to its session, including SSE reconnects. First answer/decline wins atomically; losing tabs/devices cannot hang up the winning call. Peer identity scopes the call claim.
- Both Electron hosts carry the same `main/parlons-calls.js`. Only their dedicated panel guest and exact loopback origin receive microphone/camera access and background autoplay. Guest preload restrictions remain. macOS packages declare microphone/camera use and audio-input entitlement.

## Validation

- Browser call-engine regressions: offer-before-ICE, early/concurrent ICE, duplicate answers, deferred microphone permission after hangup, exact-call Answer/Decline, sibling-answer cancellation, stale/unknown/busy offers, timeout and cleanup.
- Existing panel regression tests continue to pass.
- Real account handler tests cover browser/phone answer races, losing-device hangup refusal, unknown-contact rejection and local presence. Local HTTP tests cover event-client/session binding, forged local-client replacement and revoked-device presence, alongside the existing ticket/cookie/Host/Origin/allow-list tests.
- Electron host tests cover exact-origin media permission, cross-frame refusal, reserved partition, autoplay/background settings, call attention and loading before visiting the tab. minimaDesk TypeScript check passes.
- Isolated real Chromium tests exchanged both audio and video RTP in both directions with fake capture devices. Ringtone AudioContext ran while hidden, the actual guest integration selected/brought forward the panel, and Answer/Hangup stopped ringing and cleaned up. Light/dark screenshots captured.
- Synthetic tests do not establish physical speaker/microphone quality, Android interoperability, or cross-internet NAT traversal. macOS OS permission prompts require user consent; synthetic capture tests bypass those prompts.

## Decentralisation and unresolved cross-network call

Call signalling retains the existing authenticated, encrypted Maxima route. Media uses direct WebRTC DTLS-SRTP between participants; STUN discovers addresses and does not carry media. No new central service or media relay was introduced. The existing STUN list has redundant hosts but remains operator-concentrated; it is not proof of operator independence. There is no TURN fallback for networks that cannot establish a direct path.

The owner reported a failed Z Fold call to a friend on another internet connection on 2026-09-12. At 10:25 the Fold gathered public (`srflx`) addresses, received an answer, entered CONNECTING and received remote ICE candidates several seconds apart, but never reached LIVE before the remote bye. This proves a failed media establishment after signalling; it does not identify the far-end NAT or prove a missing-STUN cause. The friend's version and far-end diagnostics remain needed. No unsolicited test calls to that contact were placed. Earlier evidence records externally blocked UDP9501 at MegaMMR and Vigilance; this release does not claim to fix that infrastructure issue.

Wake-proxy fleet work remains paused and excluded from the release tree. The existing wake-proxy centralisation issue remains unresolved. Decentralisation is **not** claimed fully satisfied.

## Further observations retained for follow-up

The owner recalls successful external-network calls in the first telephony build and possibly after fleet STUN was introduced. Compare both historical baselines: Android **0.5.68**, commit `e88500cb1a00dc3b4a1dbda227b5ef53a161c777`, and **0.5.70**, commit `4907ec8453b91d25bd68609b4f6bf74ae2d36c46`. The former used Google STUN; **the owner explicitly forbids reintroducing Google STUN**, including as a workaround. The first fleet STUN responder's binding-response code is unchanged today; lifecycle hosting has changed. Version 0.5.69 added the 20-second connecting timeout. The Android WebRTC dependency remains `io.getstream:stream-webrtc-android:1.1.1`. Serial all-address signalling delivery already existed in the first voice build. None of these comparisons alone proves the cause of the current failure. Preserve fixes for authentication and stale calls while isolating any regression.

The Fold log also contains a separate earlier call to S10+ with two answers: the second attempts to apply an answer in the WebRTC `stable` state. The original Android answer handler does not currently restrict acceptance to OUTGOING_RINGING. This is separate from the later friend failure, whose captured timeline has one answer. The Android `end` method also clears the call identifier before its optional bye is queued. These observations have not been patched in this desktop release and are not claimed as the cause of the friend's failed call.

Local evidence: `../_artifacts/parlons-desktop-calls-2026-09-12/` (outside this repository), including clean source, test/build logs, media counters, screenshots, Fold diagnostic capture and publication records.
