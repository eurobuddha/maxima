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

## Final release evidence

Committed and pushed: maxima `a1ee17a186b5414f6be398d834a767ccac509b4e`,
minimaCore Desktop `afbdf8d7cbd4bd744b86d6fbe2e3021f6d1da323`,
minimaDesk `2533a7c4f38ec230043ea32892296f40c17d0cea`,
iOS feature `acaaab3` (validation report `04301e9`).

Both Android versions, all nine desktop platform entries, and both desktop update feeds
are live. PandaApps final catalogue commit: `a34f67a4d5cc312bc2c0075e720ec5e5fef7aff7`.
All 11 affected rows were read back from the GitHub API, the public IPFS gateway, and
snapshot `bafybeicdtcbrcvutpduuladkbqcexlt2gvm4uhs6l65xarmy6ns6u7txqe`, with matching
versions and SHA-256 hashes. The mirror was published on Hetzner. Optional Filebase
remote pinning still reports an unavailable pin list; the local pin/public gateway work.

All three Mac installers are Developer ID signed, notarized, stapled and verified.
Windows/Linux installers were downloaded into each application's local `dist` folder
and checked against release/feed digests. Both Mac host bundles contain the exact verified
Node 0.2.108 jar. Standalone Windows compilation and MSI packaging succeeded; its CI
attachment step failed on an empty SHA256SUMS. The released MSI digest was verified,
the signed local Mac image replaced the unsigned CI image, and a complete three-platform
checksum manifest was uploaded. Fixing the CI checksum-generation step is follow-up work.

Parlons 0.6.123 (723) is installed and launched on the S23 and Z Fold, using in-place
updates that retain account data. The synthetic S23 test app was removed.
The exact iPhone gallery view passed an isolated simulator interaction test covering
swipes, earlier history without selection loss, arrows and dismissal. The signed iOS
archive and extension signatures verify; App Store Connect upload fails with
`exportArchive Failed to Use Accounts`. iOS is not published to TestFlight/App Store.

No fleet services were restarted for this gallery release. Node/Cloud jars are published;
the paused WakeProxy fleet rollout remains paused. The pre-existing cross-network calling
failure and standalone desktop calling implementation remain outside this feature.
