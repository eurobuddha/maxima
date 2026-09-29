# Desktop text size and clipboard images — 1.5.104

Ctrl + / Ctrl − changes text size in 10% steps from 80% to 150%; Ctrl 0 resets to 100%.
Command equivalents work on Mac, including shifted plus and numeric keypad keys.
The desktop preference remembers the size. Existing widgets resize in place so drafts,
selection, the current conversation and the wallet instance survive.

Ctrl V / Command V in a conversation accepts a clipboard image or one copied image file.
The preview offers an optional caption and Send/Cancel. The recipient is captured before
background decoding. Text paste, copy and cut retain Swing's original implementation.
Clipboard files are limited to 32 MiB and decoded images to 40 million pixels.
No clipboard URLs or HTML are fetched. Sending reuses the existing media transport.

## Sources reused

- `desktop/.../ui/Theme.java`: Manrope fonts and shared theme.
- `desktop/.../ui/DKit.java`: WrapText's measured wrapping and caret behavior.
- `desktop/.../ui/ChatsPanel.java`: attachment sending, group routing and link handling.
- `desktop/.../ui/DesktopImagePrep.java`: upright EXIF decoding and JPEG preparation.
- `desktop/.../ui/ImageViewer.java`: the existing image clipboard flavor.
- `desktop/.../ui/SettingsPanel.java`: desktop preference namespace.

The sibling minimaCore Desktop `renderer/app.js` paste handler was inspected. It is a
browser-only QR decoder, so the Swing transfer adapter is new. No font shortcut
implementation was found in Parlons, minimaDesk or minimaCore Desktop.

## Validation

- `./gradlew :desktop:test :core:test -PskipAndroid=true --offline --no-daemon`: 273 tests,
  zero failures (desktop 13, core 260).
- Clipboard tests cover screenshot pixels, alpha flattening, copied files, invalid files,
  no active conversation, draft preservation, and ordinary text copy/cut/paste.
- Shortcut tests cover Ctrl, Command, shifted plus, keypad plus/minus and reset.
- Font tests cover bounds, repeated reset, draft selection, and new-widget consistency.
- Offline visual fixture checked light/dark at 100% and 150%, 430px and 1040px widths.
  Automated checks also cover 80%, full-message heights and bubble containment.
- No live account was started, no user identity copied, and no messages sent.
- System clipboard interoperability still needs a hands-on check in the packaged app.

## Code review

Reviewed all changes for Swing thread ownership, preservation of text editing,
recipient capture, input limits, preference persistence and existing media behavior.
Visual review identified clipped message rows and fixed-width HTML links; these now
measure their height at the available width. No outstanding blocking code findings.

## Build and delivery

Base: remote main `a0ccfb435872aa9dd38cef6e432c8dcfbd92cab2` (verified 29 September).
Isolated checkout: `maxima/build/desktop-input`, branch `feature/desktop-font-paste`.
The original dirty checkout is preserved. Desktop version and APP_VERSION both advance
to 1.5.104; no Android or server source changes are included.

Signing/notarization was blocked by automatic approval review because it uses Keychain
credentials and uploads artifacts to Apple. Explicit user approval is pending. No release
was published and no installer was installed. The local `:desktop:installDist` build is
available for inspection; it is not a signed installer.
