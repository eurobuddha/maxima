# Session publication — 10 September 2026

The owner explicitly authorised committing, pushing and publishing the completed casino and Parlons session work. This report supplements the closed [Parlons audit](AUDIT-CLOSEOUT-2026-09-10.md); it does not reopen that audit or resolve wake-proxy centralisation.

## Published versions

| Product | Version | Distribution |
| --- | --- | --- |
| Android Zero Edge Casino | 0.7.2 / code 702 | [Release](https://github.com/eurobuddha/minima-core-android-casino/releases/tag/v0.7.2), PandaApps |
| Universal / Zero Edge Casino MDS | 2.9.2 | [Release](https://github.com/eurobuddha/universal-casino/releases/tag/v2.9.2), PandaDapps and store mirror |
| minimaCore Desktop | 0.16.79 | [Release](https://github.com/eurobuddha/minimacore-desktop/releases/tag/v0.16.79), three-platform update feed and PandaApps |
| minimaDesk / MinimaClassic Desktop | 0.7.62 | [Release](https://github.com/eurobuddha/minimaDesk/releases/tag/v0.7.62), three-platform update feed and PandaApps |
| Parlons standalone desktop | 1.5.98 | [Release](https://github.com/eurobuddha/maxima/releases/tag/desktop-v1.5.98), Mac/Windows/Linux PandaApps listings |
| Android Parlons | 0.6.119 / code 719 | [Release](https://github.com/eurobuddha/maxima/releases/tag/v0.6.119), PandaApps |
| Android Cloud Portal | 0.2.67 / code 267 | [Release](https://github.com/eurobuddha/maxima/releases/tag/portal-v0.2.67), new PandaApps listing |
| Parlons Cloud | 0.11.101 | [Jar and checksum](https://github.com/eurobuddha/maxima/releases/tag/cloud-v0.11.101) |
| Parlons relay | 0.4.99 | [Jar and checksum](https://github.com/eurobuddha/maxima/releases/tag/relay-v0.4.99) |
| Parlons Node | 0.2.104 | [Previously published jar](https://github.com/eurobuddha/maxima/releases/tag/node-v0.2.104), retained in both Electron hosts |
| Optional wake proxy | 0.1.5 | [Previously published jar](https://github.com/eurobuddha/maxima/releases/tag/wake-v0.1.5), unchanged by publication |

The portal was initially published as 0.2.66, then superseded by 0.2.67 solely to raise its Android versionCode from the old counter 110 to the store convention 267. The application code is unchanged. Source commit: `fa4f2b0c1edbd044267679ae9956784e812ff19c`.

minimaDesk runs the casino as an installed MDS MiniDapp. Update the existing casino with MDS 2.9.2 to retain its UID and saved secrets; installing the desktop update alone does not update that MiniDapp. The owner node and automatic processor must run for offer renewal. No live bets, wallet migrations, installed MiniDapps or running relay/cloud/wake services were changed by this publication.

## Stores and local installers

- [PandaApps official catalog](https://raw.githubusercontent.com/eurobuddha/minima-core-apks/main/apks.json), commit `91f1aa2013dcbb2ab3ffe7eaa1f79cbb6e44770d`.
- [PandaDapps](https://eurobuddha.com/pandadapps.json) and [mirror](https://store.eurobuddha.com/pandadapps.json): Zero Edge Casino 2.9.2 and its exact release hash verified on both.
- [minimaCore update feed](https://eurobuddha.com/pandaapps/minimacore-desktop.json) and [minimaDesk update feed](https://eurobuddha.com/pandaapps/minimadesk.json) contain Mac arm64, Windows x64 and Linux x64 for the published versions.
- IPFS/IPNS publication succeeded. Snapshot [`bafybeiezabcle4fqkf3ynm2jfkbcdss3n4nnvpipkhgk22kgfwsalyb4vm`](https://ipfs.eurobuddha.com/ipfs/bafybeiezabcle4fqkf3ynm2jfkbcdss3n4nnvpipkhgk22kgfwsalyb4vm/) was read back and its casino and native/desktop catalog versions verified. The existing Filebase remote-pinning service remains unavailable; this did not prevent local pinning or IPNS publication. The second self-hosted provider uses its established background mirror sync.

The final published Mac, Windows and Linux files are present in these local folders, and every file matches its live update feed checksum:

- `/Users/eurobuddha/Projects/minima/desktop/minimacore-desktop/dist/` — 0.16.79, plus `SHA256SUMS-0.16.79`.
- `/Users/eurobuddha/Projects/minima/desktop/minimaDesk/dist/` — 0.7.62, plus `SHA256SUMS-0.7.62`.
- Standalone Parlons installers: `/Users/eurobuddha/Projects/minima/maxima/desktop/build/jpackage/`.

The casino bundle at `/Users/eurobuddha/Projects/minima/_artifacts/casino-keepalive-2026-09-10/` has been refreshed to the final published desktop binaries. The remaining component artifacts and iPhone archive are in `/Users/eurobuddha/Projects/minima/_artifacts/session-publication-2026-09-10/`.

## Verification and limits

- PandaApps pre-publication gate and pre-push gate passed: 50 listings, 35 hosted binaries verified. APK identity, increasing version code, signing key and SHA-256 checks passed for the published apps. All 12 affected native/desktop listings were subsequently read back from the official GitHub catalog.
- All 31 PandaDapps package downloads passed checksum verification. The existing App Store package had been replaced under the same URL; its stale catalog hash was updated to the downloaded bytes. The newer live PandaPools 0.6.23 entry was preserved.
- [minimaCore release CI](https://github.com/eurobuddha/minimacore-desktop/actions/runs/34527763474) and [minimaDesk release CI](https://github.com/eurobuddha/minimaDesk/actions/runs/34527818305): all three platform jobs passed. Local signed, notarised and stapled Mac DMGs replaced unsigned CI Mac assets before their feed entries were published.
- [Standalone Parlons CI](https://github.com/eurobuddha/maxima/actions/runs/34527985996): all three installer build steps passed. The Windows job failed later while uploading an empty SHA256SUMS asset. Publication was completed manually with a single checksum manifest covering all three installers; GitHub asset digests match it. The workflow still reports failure, and its checksum-generation/upload step remains follow-up work. The signed local Mac installer passed app and DMG notarisation, stapling and Gatekeeper verification.
- Previously completed casino validation: 25 Android tests; 22 regression/parity tests plus transaction glue checks; 160 timeout vectors; six game/currency plans executed in the offline Minima covenant VM; 10 desktop RPC/session tests; minimaDesk typecheck and renderer build.
- Previously completed Parlons audit validation remains recorded in the audit closeout: 424 JVM tests and 63 wire vectors, with earlier Swift and iOS gates. Publication did not rerun those unchanged suites.
- Portal 0.2.67 release assembly and store validation passed. Additional full lint reported three pre-existing errors and 449 warnings. Deferred errors: ReceiveView.java constant annotation (WrongConstant), ZoomImageView.java AppCompatCustomView, and the missing optional camera feature declaration (PermissionImpliesUnsupportedChromeOsHardware). These were not concealed with a lint baseline or suppression. No new audit branch was opened.
- No interactive Windows/Linux installation or mined multi-device casino renewal test is claimed.

## iPhone publication — blocked by Apple account access

The iOS app and extension remain 0.1.31, build 29. The signed release archive succeeded. Upload to App Store Connect failed with:

> App Store Connect access for team Z4JD286WF4 is required. Ensure that your Apple Account usernames and passwords are correct in Accounts settings.

The archive is ready at `session-publication-2026-09-10/Parlons-0.1.31.xcarchive`. Xcode account access must be restored before retrying distribution. This pass did **not** publish to TestFlight or the Apple App Store, and does not claim Apple review or approval. The previously installed iPhone development build remains installed.

## WakeProxy

Wake-proxy centralisation is unresolved. Publishing the completed reliability fixes does not satisfy the decentralisation requirement. Its architecture is reserved for the subsequent discussion and goal requested by the owner.
