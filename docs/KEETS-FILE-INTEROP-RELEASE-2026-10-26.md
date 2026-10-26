# KeetChat ↔ Parlons release — 26 October 2026

The 100 MB encrypted Keets-address file bridge is released for user testing.
PandaBear and BlackBear use the same implementation as Pandamonium.

| Offering | Release |
|---|---|
| Parlons Android | 0.6.148 (748) |
| Parlons Cloud Android | 0.2.93 (293) |
| PandaBear / BlackBear / Pandamonium | 2.0.57 (253), with the matching edition suffix |
| Parlons Desktop | 1.5.117 — Mac, Windows and Linux |
| Parlons Node | 0.2.140 |
| Cloud account host | 0.11.126 |
| minimaCore Desktop | 0.18.24 — Mac, Windows and Linux |
| minimaDesk / MinimaClassic Desktop | 0.7.85 — Mac, Windows and Linux |

Source commits: maxima `d6a59f18`, bears `cf1d09b0`, minimaCore Desktop
`71d7126`, minimaDesk `1a1b0da`. Catalogue commits `5eb66bd` and `4b68eee`
update all 18 affected rows. The complete store validator passed: 57 entries,
56 binaries checked. Both desktop updater feeds list all three platforms.
The three Mac apps and DMGs passed Developer ID signature, notarization,
stapling and Gatekeeper checks. GitHub asset digests match the verified builds.

The website download links and KeetChat instructions are published in English,
Chinese, Russian and Ukrainian. Six Node hosts passed all 11 external relay
checks after the rolling update; their DEX health endpoints still return 0.1.5.
Sally and the Pi's existing tenant services are updated, with service settings
and firewall rules preserved. Sally's tenant panel returns HTTP 200.

IPFS snapshot: `bafybeidwo2ore2aeuiucdtsoo7yw24mhb2vnvngiuwwzla44ule7g266jy`. Recursive pin and the existing `pandastore` IPNS
resolution are verified. All 18 mirrored files match their catalogue checksums;
the Parlons APK was also downloaded through the immutable CID URL and its full
SHA-256 verified. All 18 versions and hashes agree across GitHub, eurobuddha.com
and the public IPFS catalogue. Both desktop feeds agree on all three platforms.

## Scope and remaining tests

Use Keets-address contacts and update both clients. The separate MAX# chat in
Pandamonium is not this bridge. Android Parlons needs its existing ADMIN file
permission enabled in the linked node's Apps tab. A Cloud host must have Keets
and a working direct connection to its client. Sally's separate tenant direct
listener remains disabled as previously documented; no new firewall rule was
added. Physical-phone carrier/Wi-Fi testing remains; no Fold installation was
performed during this publication turn.

A separate concurrent commit, bears `6a066aab`, updated Keets traffic tuning to
sidecar 1.2.11 while retaining the 2.0.57 app version. It is **not** in these APKs:
all three immutable release tags point to the tested `cf1d09b0`. The tuning
change needs a new version number before release; do not replace published
2.0.57 files. Other agents' work and pre-existing graph/artifact files were kept.

Validation details and setup: [implementation notes](KEETS-FILE-INTEROP-2026-10-26.md).

## Fold installation follow-up

On 26 October 2026, the published Parlons **0.6.148 (748)** and Parlons Cloud
**0.2.93 (293)** APKs were installed in place on the Fold. Both installations
succeeded and Android package-manager read-back confirmed the versions. Existing
app data was retained. The Fold already reported Pandamonium **2.0.57 (253)**;
that installation was preserved. The S10 was not touched. All 18 public store
versions/checksums and both desktop updater feeds were rechecked successfully.
