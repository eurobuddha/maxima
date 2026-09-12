# Chat photo gallery

Opening a photo now starts a gallery of images from that conversation, including sent
and received photos in chat order. Left goes newer; right goes older. Android retains
pinch zoom, bounded panning, double-tap reset, Save and Share. Paging is disabled during
pinch/pan; actions use the displayed bitmap, including after thumbnail cache eviction.
Unavailable images can be retried, and stale asynchronous loads cannot replace a newer
selection. Closing destroys the viewer's worker. Android controls respect system insets.

Reuse: Salon's `apks/salon/.../MainActivity.openCarousel` supplied bounded navigation,
counter and swipe-distance behavior. Parlons' existing ZoomImageView and encrypted
media loader are retained. The duplicate Android viewer is now compiled from
`chatshared/` by both apps, following `walletshared/`. `core/chat/ChatImages` supplies
the ordered photo snapshot to Android and Swing. The local account panel uses its
existing media endpoint and modal focus conventions; iOS uses its existing image cache
and remote media loader with a native paged presentation.

Cloud Android, the local panel and iOS retrieve older history through the existing paged
conversation API. They skip text-only pages when searching for earlier photos. Gallery
history is a snapshot: reopen to include new messages that arrive while it is open.

Versions:

- Parlons Android 0.6.123, code 723.
- Parlons Cloud Android 0.2.72, code 272.
- Parlons Node 0.2.108; headless Cloud 0.11.104 (shared panel).
- Parlons Desktop 1.5.100.
- iOS 0.1.33, build 31, in the separate parlons-ios repository.
- minimaCore Desktop 0.16.82 and minimaDesk 0.7.65 bundle Node 0.2.108.

Validation: 257 core tests, 12 Android app tests, 18 portal tests, 17 JavaScript tests,
7 minimaCore host tests and 6 minimaDesk host tests pass. Signed Android builds and
release-vital lint pass; Swing compiles; the iPhone simulator build passes. Real Chromium
checks cover selection, navigation, boundaries, actual mouse swipe, older history,
image decoding and Escape/focus cleanup. Synthetic S23 checks cover gestures and delayed
loads, without using contacts or sending messages. Detailed release and device evidence
is in `_artifacts/parlons-photo-gallery-2026-09-12` in the family workspace.

The release source is assembled from the committed baseline plus these changes. Paused
WakeProxy edits are excluded. This feature adds no network service and changes no
transport, call protocol, STUN configuration or credentials. The existing WakeProxy
centralisation issue and cross-network calling investigation remain unresolved.

Publication status and final installer hashes are recorded in the local release STATUS.md;
a successful build alone is not evidence of store publication.
