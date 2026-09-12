# Android call retest: Parlons 0.6.122 and Cloud Android 0.2.71

This release fixes two confirmed call-handling defects and adds diagnostics for the unresolved external-network connection failure. It does not claim that the owner's Z Fold-to-friend failure is resolved. Both participants should update for the most useful retest; the unchanged signal format remains compatible with original Android 0.6.121.

The Android call managers reuse the already-tested shared browser call engine's first-answer guard and hangup ordering. Duplicate answers can no longer reapply remote SDP or turn LIVE back into CONNECTING. A connection-failure bye captures the original call id before cleanup, so the other participant can match it. New diagnostics show signal queue/send duration, remote SDP completion and remote ICE acceptance/type without adding address or SDP-credential logging.

Versions are original Android 0.6.122/code722 and Cloud Android 0.2.71/code271. No change to fleet STUN endpoints, WebRTC dependency, connection timeout, encryption or signalling schema. Google STUN remains excluded; wake-proxy fleet work remains paused. minimaCore Desktop0.16.81 and minimaDesk0.7.64 already have the equivalent guards and need no rebuild for this Android-only patch. Standalone desktop calling remains separate unfinished work.

Validation: 28 Android tests passed (original10, Cloud18), including new duplicate-answer, unexpected-answer and queued failure-bye cases. The 8 source browser-call tests pass. Signed release APKs built and verified with the Minima Family certificate; release-critical lint passed. Full original-app lint reports two existing errors in unchanged files (ZoomImageView AppCompatCustomView, manifest PermissionImpliesUnsupportedChromeOsHardware) and210 warnings; retained as follow-up, not suppressed. Builds use a clean source archive plus these six changed Android files, excluding uncommitted wake work.

APK SHA256:
- Original: `5394b71bec22a88a0457c983e2bbadab081b5412792e9be65036bdede5775762`
- Cloud Android: `c37daf9a02cc596918b9653637af3ba104a2cc55c3b08d0711e072cec94d6fed`

Publication/installation evidence is under the local family artifact folder `_artifacts/parlons-call-retest-2026-09-12/`. A successful call across the two actual internet connections remains required before calling that fault fixed. Existing fleet operator concentration and the paused wake-proxy centralisation issue remain unresolved.
