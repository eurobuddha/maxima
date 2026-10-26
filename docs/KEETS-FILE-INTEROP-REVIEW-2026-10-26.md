## Code Review

### Summary

Reviewed the shared format/crypto move, Keets wire routing, account file-controller
integration, Android/desktop/node media access, native KeetChat controls, translations,
version sources and test fixtures. The existing AES-GCM implementation is reused,
file ciphertext never enters chat/mailbox payloads, and bounded media reads and
hash checks precede plaintext publication. The tested wire path is Keets-address
contacts; it does not add file support to Pandamonium's separate MAX# client.

### Findings

#### 🟠 MAJOR — Android blocks the new loopback media reader (fixed)
**File:** `app/src/main/AndroidManifest.xml`
**Problem:** Java URLConnection to Keets would be rejected by the Android cleartext
policy even though JVM/network tests pass.
**Fix:** Reused the bear-family network-security configuration, permitting only
127.0.0.1/localhost. Remote cleartext policy remains blocked.

#### 🟠 MAJOR — Overlapping screens could race file state (fixed)
**File:** `apks/base/app/src/main/java/com/eurobuddha/minimacore/chat/ChatFiles.java`
**Problem:** Two activity instances could write different offers into the same
transfer directory during a transition.
**Fix:** Fixed-size shared disk locks and in-lock peer/direction/reference checks.
An existing offer cannot silently be replaced by another conversation's offer.

#### 🟠 MAJOR — Preparation pause could be lost (fixed)
**File:** `apks/base/app/src/main/java/com/eurobuddha/minimacore/chat/ChatFiles.java`
**Problem:** Automatically continuing after encryption reset an explicit pause.
**Fix:** Separate automatic continuation from an explicit Resume action. Only the
latter clears pause intent. Interrupted preparations remain visible/removable.

#### 🟡 MINOR — Hidden active transfers were hard to reopen (fixed)
**File:** `apks/base/app/src/main/java/com/eurobuddha/minimacore/chat/ChatFiles.java`
**Problem:** The transfer list queued behind a long download on the same executor.
**Fix:** Separate catalog reads, allowing the active transfer sheet and Pause to
be reopened immediately. Closing a sheet is labelled Close, not Cancel.

### Verdict

✅ Approve with suggestions

No unresolved critical/major findings from this source review. The meaningful
remaining validation is a two-device carrier/Wi-Fi field test and confirmation of
the Android node's existing ADMIN permission handoff on the user's device.
Local two-peer Hyperdrive tests cover 100 MiB in both directions, interruption,
resume and final byte identity. JVM tests cover account routing, corrupt/empty
files, size bounds, redirect refusal and existing file/chat behavior. Final release
package verification passed after the source edits. Four native UI regressions
and all 24 final targeted Keets/account regressions passed.
