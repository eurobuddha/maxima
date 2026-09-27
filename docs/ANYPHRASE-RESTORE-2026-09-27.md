# Custom Minima phrase restore — Android 0.6.129 (729)

First-run onboarding and Settings restore now offer **Custom phrase (Minima anyphrase)**. Select this for a phrase created/restored using Minima's anyphrase/anyseed mode. The entered text is hashed verbatim as UTF-8 with SHA3-256: case, whitespace, punctuation and Unicode are preserved. Standard mode retains the previous BIP39 normalization and checksum warning.

SeedStore saves the mode alongside the Keystore-encrypted phrase in one durable preferences commit. All load/create-existing paths use it, including service startup, wallet seed selection and classic-engine migration. Import derives successfully before saving. Restore input windows block screenshots. Encrypted backup format 2 preserves the mode; old format 1 backups default to standard mode. Older apps reject format 2 instead of silently changing derivation.

## Reuse

Reused existing `Bip39.toNodeSeed` raw-hash building blocks (`MiniString`, `Hashes`) and `MaximaIdentity.fromSeed`; automatic dictionary detection could not express anyphrase for dictionary-only custom strings. Keystore AES/GCM and BackupCrypto remain the existing implementations. Compared against Minima `utils/BIP39.java:convertStringToSeed` and `commands/backup/archive.java` anyphrase branch. Also inspected minimaCore Desktop `main/webwallet.js` verbatim phrase handling and base Android restore callers. Test-only JSON dependency matches the existing account/desktop modules.

## Validation

- App: 38 tests; Portal shared-code compatibility: 25; core: 260. Zero failures/errors/skips.
- Six new tests cover exact custom input, Minima reference seed equality, identity reload, standard-mode compatibility/reset, rejected input, failed persistence, and encrypted backup round-trip/legacy parsing.
- Minima codec parity: 63 checks passed.
- `:app:lintVitalRelease` and signed `:app:assembleRelease` passed.
- Full `:app:lintRelease` reports two pre-existing errors: AppCompatCustomView in ZoomImageView and PermissionImpliesUnsupportedChromeOsHardware in AndroidManifest.xml. Neither is introduced by this change.
- APK metadata: com.eurobuddha.maxima.app, versionName 0.6.129, versionCode 729, minSdk 28.
- Family signer SHA-256: eca1383c9d27683a281fbe6355356267877dc2dd14d963d7cc289ca0700e517f.
- APK SHA-256: 842d6aaccaf25e762417f5412d728e42a590a3554728437ecfe4ae8210fb2dd0.

## Code review

Reviewed phrase entry, derivation, storage, restart, wallet/migration callers, backup compatibility and tests. No blocking findings in this change. Existing normalization remains the default for older installations. No runtime dependency or server protocol changed. Verdict: approve.

No real recovery phrase was read or used. Device installation/recovery has not been tested on the user's S10+. Seed restoration reconstructs the identity; the phrase alone does not contain old local chats or contacts.
