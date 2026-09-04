# maxima — working rules

## Versioning guardrail — every code change ships with a version bump

Real funds, real chain. NEVER change code without bumping the version
(versionCode + versionName in app/build.gradle), so every committed state is distinct, reversible and trackable. One
logical change = one version = one commit = one push, in order. Enforced by a
pre-commit hook (.githooks/pre-commit, install once: sh .githooks/install.sh)
that blocks a code change with no version bump. Do NOT bypass with --no-verify.
Docs/config-only commits need no bump.

## Signed desktop (Parlons / Maxima Desktop) releases (since desktop 1.5.33, 2026-09-04)
The Developer ID certificate + notary profile `minimadesk` exist on this Mac. The mac DMG for any
`desktop-vX.Y.Z` release is built with `desktop/release-mac.sh` (jpackage `--mac-sign`, notarize, staple,
then `desktop/verify-mac.sh <dmg>` must print ALL OK) — never a plain `:desktop:jpackage` for something that
ships. CI's mac DMG is unsigned until the `MACOS_CERT_P12`/`MACOS_SIGN_IDENTITY`/`APPLE_*` secrets exist:
upload the local signed DMG over it (`gh release upload desktop-vX.Y.Z desktop/build/jpackage/MaximaNode-X.Y.Z.dmg --clobber`).
Bump `desktop/build.gradle` `version` AND `DesktopMain.APP_VERSION` together. Family rule: `../CLAUDE.md`
"Desktop builds are SIGNED".
