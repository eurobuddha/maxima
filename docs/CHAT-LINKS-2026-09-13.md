# Clickable chat links — 13 September 2026

HTTP/HTTPS and www links are clickable in received/sent chat messages and photo captions. The URL opens in the normal browser on user action. Text remains literal: no received HTML or Markdown is evaluated and no preview fetch, tracker or new central service is introduced. File cards, contact cards and payment actions retain their existing paths. Links use the bubble’s foreground colour with an underline for light/dark readability.

## Reuse and coverage

- Original Android `app/.../chat/ChatActivity.java` and Cloud Android `portal/.../CloudChatActivity.java` reuse one `chatshared/.../ChatLinkText.java` binder using native URLSpan and LinkMovementMethod. Recycled holders clear old movement/link state first. `core/.../chat/ChatLinks.java` finds bounded HTTP(S)/www spans and trims surrounding punctuation in linear time; the standalone desktop reuses it.
- The shared account panel `account/src/main/resources/panel/app.js` retains its existing `esc` boundary around plain text and href values; only validated HTTP(S) links become anchors, with noopener/noreferrer and a new browser target.
- minimaDesk already opens HTTP(S) links from guests in the external browser. minimaCore’s isolated Parlons guest now reuses that policy; guest child windows remain denied and its in-app navigation restriction stays intact. The separate top-level renderer policy is unchanged.
- Standalone Desktop reuses its existing HTML escaping and native Swing hyperlink events. Plain messages without links retain their established JTextArea layout. Only escaped text and generated anchors enter the HTML pane.
- iPhone uses Foundation’s native NSDataDetector, filtered to HTTP(S), to construct attributed literal text; the chat view applies native underlines. No extra dependency.

## Preserve the existing release

The published auto-resume branch was discovered during artifact collision checks. The JVM and both Electron feature branches were fast-forwarded to the completed auto-resume commits before applying this change. Existing automatic restoration, explicit Pause persistence and the desktop CI checksum fix are retained unchanged. Version numbers allocated by that release are not reused. Original dirty paused Wake checkouts remain untouched.

Final versions: original Android 0.6.126 / 726; Cloud Android 0.2.75 / 275; node 0.2.111; Cloud host 0.11.107; standalone desktop 1.5.103; minimaCore Desktop 0.16.85; minimaDesk 0.7.68; iPhone 0.1.35 / 33. Relay code is unchanged.

## Validation

- 260 core tests (including three new link tests), 14 file tests including automatic restoration, and Android release builds passed after integration.
- 11 panel tests passed, including literal HTML, rejected executable schemes/credentials, balanced punctuation, URL queries and safe new-window attributes.
- Actual Electron anchor clicks passed through the shipped minimaCore guest handler with only the OS-browser boundary stubbed. No automatic browser open occurred; unsafe protocols were denied, the chat remained open, and light/dark screenshots were inspected.
- The actual standalone Swing chat text component rendered literal HTML, generated links and both themes in a synthetic headless harness; no node was created.
- Native Android checks on S10+ passed for link spans, movement handling, recycled file bubbles and light/dark colours; the separate test app also passed six torrent/crypto checks. No user identity or real chat was used for these tests.
- Two Swift link tests and the iPhone simulator build passed. minimaCore’s seven and minimaDesk’s six existing Parlons checks passed.
- Final Android versions were installed and verified on both the Z Fold and S10+, preserving application data. Both apps include auto-resume and private file sharing.

## Code review

Reviewed link parsing, escaping, scheme checks, native holder reuse, caption/card routing and Electron navigation boundaries. Resolved the Core guest’s blanket link block and ensured punctuation trimming does not repeatedly scan long messages. Native UI frameworks handle clicks; no automatic preview network traffic. No blocking findings remain for this local candidate.

## Release status

This clickable-links update is a local candidate: source commits and local artifacts are recorded in `_artifacts/parlons-private-files-2026-09-13/CHAT-LINKS-STATUS.md`. No new store publication, remote fleet deployment or iPhone install was performed. Mac candidates are locally signed; final notarization and Windows/Linux release packaging are pending. The earlier published auto-resume builds are distinct and retained. WakeProxy centralisation remains unresolved and paused.


## Chat actions update — 4 October 2026

Implemented in the current `build/account-import` release worktree and the existing
`_worktrees/parlons-ios-private-files` iOS worktree. The earlier release notes above
remain a historical record.

- Android and Portal reuse `ChatLinkText` for normal link taps and long-press
  Open link / Copy link / Share link actions. Links use contrasting blue shades
  with native underlines. Recycled holders clear touch and menu handling.
- Shared Java and panel detection accepts bare ASCII domains with HTTPS as the
  default, retaining punctuation, credential rejection and HTTP(S)-only checks.
  Domain labels are bounded to avoid excessive regular-expression recursion.
  iOS retains its native detector, with a regression for bare domains.
- Native desktop routes context menus from bubble children as well as the bubble;
  right-clicking a link offers URL actions. iOS bubble context menus expose copying
  and per-link actions. Desktop and browsers without a native share API explicitly
  offer “Copy link to share”.
- Copy message / Copy caption / Copy contact address acts on visible content;
  voice waveform metadata is excluded. Existing payment, photo and file actions
  retain their routing.
- The shared Node/Cloud web panel has an accessible message-actions button,
  search-result navigation that pages existing history and highlights the exact
  message, and an emoji sheet using the existing phone/desktop categories. Emoji
  insertion replaces the selection and preserves multi-codepoint glyphs.
- No new runtime dependency, wire message type, preview fetching, relay service,
  wallet permission or account migration was introduced.

### Validation and local builds

Core 263, desktop 13, Android 38 and Portal 28 tests passed, plus 31 web tests and
three Swift link tests. Both signed release APKs and the iOS simulator build passed.
The rendered Electron harness used synthetic messages and verified opening links,
URL/caption copying, emoji insertion, light/dark styling, and clicking an older
search result through to its highlighted message. The isolated Android UI harness
on the S10 verified bare-domain tap, URL long-press menu, copying the selected URL,
and the plain-message menu. The emulator had system UI ANRs, so it was not used
as evidence. No real account was used.

Local candidate versions: Android 0.6.132 / 732, Portal 0.2.82 / 282, native Desktop
1.5.106, Node 0.2.120, Cloud 0.11.114, iOS 0.1.36 / 34. APKs and versioned host JARs
are in `maxima/dist/` with checksums; APK metadata and signatures were verified.
Node and Cloud JARs contain the exact tested panel script. Both desktop wrappers
consume that shared panel; their bundled Node must be refreshed when publishing
new wrapper installers. No store publication or running-host upgrade was performed
for this update.

### Code Review

#### Summary

Reviewed the changed renderers, URL bounds and escaping, clipboard/share handling,
holder reuse, browser navigation races, and keyboard interactions. Reused the
existing global keyboard handler to avoid opening search results twice. Added a
bounded-domain regression and avoided an extra quadratic lookup when wiring menus.

#### Findings

No blocking findings remain. Native sharing depends on the platform; the fallback
is labelled as copying. Search reuses the existing time-based history cursor and
reports failure if the target cannot be retrieved.

#### Verdict

Approve the local implementation. Build evidence and synthetic UI captures are in
`build/chat-actions-2026-10-04/`.
